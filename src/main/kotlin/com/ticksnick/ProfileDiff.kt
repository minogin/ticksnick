package com.ticksnick

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** The header lines, wall, active, paused and the root row: before -> after, the change as a range, absolute and relative. */
internal val RUNS_DIFF_HEADER: String get() = "%7s ${sym.arrow} %-7s  %-22s %-16s %s"

/** A change from [before] to [after] as a fraction of [before]: the one rule for it. NaN from 0, which no fraction describes. */
internal fun relativeChange(before: Double, after: Double): Double = if (before == 0.0) Double.NaN else (after - before) / before

/**
 * The change of one figure between two profiles: after's mean of its runs minus before's, and the
 * half-width of its 95% interval by Welch's t, which does not assume the two sides spread alike. A
 * side with one run has no spread, so the change has no interval.
 */
internal class Change(val before: Estimate, val after: Estimate) {
    val delta: Double = after.mean - before.mean

    private val a = before.variance / before.n
    private val b = after.variance / after.n

    /** Welch-Satterthwaite, rounded down: fewer degrees of freedom, a wider interval. */
    private val degrees: Int = if (a + b == 0.0) 0 else floor((a + b) * (a + b) / (a * a / (before.n - 1) + b * b / (after.n - 1))).toInt().coerceAtLeast(1)

    val half: Double = when {
        before.n < 2 || after.n < 2 -> Double.NaN
        a + b == 0.0 -> 0.0
        else -> t95(degrees) * sqrt(a + b)
    }

    val relative: Double get() = relativeChange(before.mean, after.mean)
    val relativeHalf: Double get() = if (before.mean == 0.0) Double.NaN else half / before.mean
}

/** A fraction as a signed percentage, `-21%`, `+2.4%`. */
internal fun signedPercent(f: Double): String {
    if (f.isNaN()) return ""
    val p = f * 100
    val sign = if (p < 0) "-" else "+"
    return if (abs(p) < 9.95) String.format(Locale.ROOT, "%s%.1f%%", sign, abs(p)) else String.format(Locale.ROOT, "%s%.0f%%", sign, abs(p))
}

/**
 * [before] -> [after] as text. The banner of each side's failed runs leads; a side whose runs all failed
 * has no profile, so the diff is the banners and nothing else.
 */
internal fun diffText(before: Profile, after: Profile): String =
    if (before.runs.isEmpty() || after.runs.isEmpty()) before.failedBanner(named = true) + after.failedBanner(named = true)
    else ProfileDiff(before, after).render()

/**
 * **Two profiles compared**: each side a folder of runs (see [Profile]), each figure the mean of its
 * side's runs, and the change, with its 95% interval once both sides have two. Operations are matched
 * by name and coarse rows by path.
 *
 * **Calls are the check.** An operation that got faster at the same call count is faster; one with
 * fewer calls did less work, which is a different finding, and the column says `=` when the two agree
 * so that the exception stands out.
 */
internal class ProfileDiff(val before: Profile, val after: Profile) {
    private val wallRows = paired(before.wallTable.rows, after.wallTable.rows)
    private val sharedRows = paired(before.wallTable.shared, after.wallTable.shared)

    /** What moved most on top, whether it went up or down. */
    private val ownRows = paired(before.ownTable.rows, after.ownTable.rows).sortedByDescending { r ->
        fun time(row: Row?) = row?.figures?.get(THREAD_TIME)?.mean?.takeIf { !it.isNaN() } ?: 0.0
        abs(time(r.after) - time(r.before))
    }

    private val wallText = DiffText(before.wallTable, after.wallTable, wallRows, sharedRows, "Coarse operation", WALL_NAME_WIDTH, WALL)
    private val ownText = DiffText(before.ownTable, after.ownTable, ownRows, emptyList(), "Operation", OWN_NAME_WIDTH, null)
    private val width = maxOf(wallText.width, ownText.width)

    private val overRuns = if (before.runs.size > 1 || after.runs.size > 1) "; each side the mean of its runs, the change its 95% interval" else ""

    private fun Profile.wallTime() = Estimate(runs.map { it.samplingSpanNanos.toDouble() })
    private fun Profile.active() = Estimate(runs.map { it.activeNanos.toDouble() })
    private fun Profile.paused() = Estimate(runs.map { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() })
    private fun Profile.tree() = runs.map { it.coarseTree }
    private fun Profile.described() = if (runs.size == 1) "1 run" else "${runs.size} runs"

    private fun header(key: String, b: Estimate, a: Estimate, suffix: String = ""): String {
        val c = Change(b, a)
        // Not knowing a side is not knowing the change.
        if (b.unknown() || a.unknown())
            return row(key, String.format(Locale.ROOT, RUNS_DIFF_HEADER, duration(b.mean), duration(a.mean), UNKNOWN, UNKNOWN, suffix).trimEnd())
        val sign = if (c.delta < 0) "-" else "+"
        // A change is always a range, the interval's own ends, in time and in percent: `-5.65..-3.65s  -42..-27%`.
        val ranged = sureness(c.delta, c.half, change = true) == Sure.RANGE
        val absolute = if (ranged) durationRange(c.delta - c.half, c.delta + c.half, signed = true) else sign + duration(abs(c.delta))
        val r = c.relative * 100
        val h = c.relativeHalf * 100
        val relative = if (sureness(r, h, change = true) == Sure.RANGE) intervalRange(signedEnd(maxOf(-100.0, r - h)), signedEnd(r + h), "%") else signedPercent(c.relative)
        return row(key, String.format(Locale.ROOT, RUNS_DIFF_HEADER, duration(b.mean), duration(a.mean), absolute, relative, suffix).trimEnd())
    }

    fun render(): String = buildString {
        append(before.failedBanner(named = true, width = width))
        append(after.failedBanner(named = true, width = width))
        appendLine("=".repeat(width))
        appendLine(row("Diff", "${before.name} (${before.described()}) ${sym.arrow} ${after.name} (${after.described()})"))
        val rootName = (after.root() ?: before.root())?.name
        warningsSection(warnings(rootName))
        factDiffLines(before.tagMaps, after.tagMaps, before.setupMaps, after.setupMaps)
        if (overRuns.isNotEmpty()) appendLine(row("", "each side the mean of its runs, one JVM each; the change the range of its 95% interval (Welch's t)"))
        with(before) { runList("before") }
        with(after) { runList("after", header = false) }
        appendLine(header("Wall time", before.wallTime(), after.wallTime()))
        appendLine(header("  Active", before.active(), after.active()))
        // No pause on either side is the default, nothing to look at: `Paused 0.00ns -> 0.00ns +0.00ns` said nothing.
        if (before.paused().mean > 0 || after.paused().mean > 0) appendLine(header("  Paused", before.paused(), after.paused()))
        if (rootName != null) {
            val b = before.matched(before.tree(), rootName)
            val a = after.matched(after.tree(), rootName)
            if (b.present.isNotEmpty() && a.present.isNotEmpty())
                appendLine(header(rootName, before.meanSpan(b), after.meanSpan(a), "per execution"))
        }
        clock()
        bothSides("Cores", before.coresText(), after.coresText())
        bothSides("Sampling", before.samplingText(), after.samplingText())
        // The tree first, as in the report: the shape, then the own-time detail.
        if (before.runs.any { it.coarseTree.isNotEmpty() } || after.runs.any { it.coarseTree.isNotEmpty() }) {
            appendLine()
            appendLine("NESTED WALL TIME${sym.dash}each row its whole span, matched by path$overRuns")
            with(wallText) { rows() }
            if (sharedRows.isNotEmpty()) {
                appendLine(SHARED_HEADING)
                with(wallText) { shared() }
            }
            perSide(before.coarseKeys(), after.coarseKeys())
        }
        appendLine()
        appendLine("OWN THREAD-TIME${sym.dash}outside the operations inside, matched by name, sorted by the change$overRuns")
        with(ownText) { rows() }
        perSide(before.fineNotes(), after.fineNotes())
        appendLine()
        bothSides("Sampled", before.sampledText(), after.sampledText())
        appendLine("-".repeat(width))
        with(after.runs[0]) { renderLegend(false) }
        appendLine("=".repeat(width))
    }

    /** What is under a table: a line both sides have once, and one only a side has with that side's name. */
    private fun StringBuilder.perSide(b: List<String>, a: List<String>) {
        for (line in (b + a).distinct()) appendLine(
            when (line) {
                !in a -> "  before: " + line.trim()
                !in b -> "  after: " + line.trim()
                else -> line
            }
        )
    }

    /** A line of the report that is about the run, not the program: one line per side. */
    private fun StringBuilder.bothSides(key: String, b: String?, a: String?) {
        if (b == null && a == null) return
        appendLine(row(key, "before  ${b ?: UNKNOWN}"))
        appendLine(row("", "after   ${a ?: UNKNOWN}"))
    }

    private fun clockMean(r: Profile) = r.runs.map { it.clock.mean }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun StringBuilder.clock() {
        val b = clockMean(before)
        val a = clockMean(after)
        if (b.isNaN() && a.isNaN()) return
        appendLine(row("CPU clock", "${before.runs[0].clock.format(b)} ${sym.arrow} ${after.runs[0].clock.format(a)}, the mean of each side's runs"))
    }

    /**
     * What makes the change mean something other than the code's: printed on top, see [warningsSection].
     * The diff's own first, then each side's, which name their side: one run on a side is one of them.
     */
    private fun warnings(rootName: String?): Warnings {
        val own = mutableListOf<Warning>()
        val b = clockMean(before)
        val a = clockMean(after)
        val ofBase = (if (!a.isNaN()) after else before).runs[0].clock.ofBase
        if (!a.isNaN() && !b.isNaN() && (before.runs + after.runs).all { it.clock.ofBase == ofBase } && maxOf(a, b) > minOf(a, b) * (1 + CLOCK_MOVED))
            own += Warning(
                Kind.CLOCK_BETWEEN_SIDES, words(before.runs[0].clock.format(b)), words(after.runs[0].clock.format(a)),
                figure(maxOf(a, b) / minOf(a, b) - 1, ::shareText),
            )
        if (rootName != null) {
            fun countsOf(side: Profile) = side.matched(side.tree(), rootName).let { m -> m.present.map { m.nodes[it]!!.count } }.distinct()
            val cb = countsOf(before)
            val ca = countsOf(after)
            if (cb.size == 1 && ca.size == 1 && ca != cb)
                own += Warning(Kind.ROOT_COUNT_SIDES, words(code(rootName)), words(countText(cb[0].toDouble())), words(countText(ca[0].toDouble())))
        }
        own += allocationsWarnings(before.runs, after.runs)
        val sides = before.warnings(named = true) + after.warnings(named = true)
        return Warnings(own.map { it.text() } + sides.ranked, sides.always)
    }
}
