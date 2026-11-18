package com.ticksnick

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** The header lines' columns at their narrowest: before, after, the change in time, in percent. Wider sides widen them all. */
private const val HEADER_SIDE = 7
private const val HEADER_ABSOLUTE = 22
private const val HEADER_RELATIVE = 16

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

/** A fraction as a signed percentage, `-21%`, `+2.4%`: a change with no interval ([unjudgedChange]). */
internal fun signedPercent(f: Double): String = if (f.isNaN()) "" else unjudgedChange(f * 100, "%")

/**
 * [before] -> [after] as text. The banner of each side's failed runs leads; a side whose runs all failed
 * has no profile, so the diff is the banners and nothing else.
 */
internal fun diffText(before: Profile, after: Profile, view: View = View.FULL, lenses: Set<Lens> = emptySet()): String =
    if (before.runs.isEmpty() || after.runs.isEmpty()) before.failedBanner(named = true) + after.failedBanner(named = true)
    else ProfileDiff(before, after, view, lenses).render()

/**
 * **Two profiles compared**: each side a folder of runs (see [Profile]), each figure the mean of its
 * side's runs, and the change, with its 95% interval once both sides have two. Operations are matched
 * by name and coarse rows by path.
 *
 * **Calls are the check.** An operation that got faster at the same call count is faster; one with
 * fewer calls did less work, which is a different finding, and the column says `=` when the two agree
 * so that the exception stands out.
 *
 * [view] is what the two tables show, with the [lenses] opened, as in [Profile.render]; every other line is the same
 * in every view.
 */
internal class ProfileDiff(
    val before: Profile, val after: Profile, private val view: View = View.FULL, private val lenses: Set<Lens> = emptySet(),
) {
    private val wallRows = paired(before.wallTable.rows, after.wallTable.rows)
    private val sharedRows = paired(before.wallTable.shared, after.wallTable.shared)

    /** What moved most on top, whether it went up or down. */
    private val ownRows = paired(before.ownTable.rows, after.ownTable.rows).sortedByDescending { r ->
        fun time(row: Row?) = row?.figures?.get(THREAD_TIME)?.mean?.takeIf { !it.isNaN() } ?: 0.0
        abs(time(r.after) - time(r.before))
    }

    private val wallText = DiffText(before.wallTable, after.wallTable, wallRows, sharedRows, OP_HEAD, WALL_NAME_WIDTH, WALL, view, lenses)
    private val ownText = DiffText(before.ownTable, after.ownTable, ownRows, emptyList(), OP_HEAD, OWN_NAME_WIDTH, THREAD_TIME, view, lenses)
    private val width = maxOf(wallText.width, ownText.width)

    // Only with both sides over runs: a side of one run leaves the change no interval, and fewRunsLines says so instead.
    private val overRuns = if (before.runs.size > 1 && after.runs.size > 1) ". Each side is the mean of its runs, and each change has its 95% interval" else ""
    private val fewRuns = fewRunsLines(before.runs.size, after.runs.size)

    /** The brief view's keys to its figures and changes, under each table: not with a side of one run, see [briefKeys]. */
    private val keys = briefKeys(view, minOf(before.runs.size, after.runs.size), changes = true)

    private fun Profile.wallTime() = Estimate(runs.map { it.samplingSpanNanos.toDouble() })
    private fun Profile.active() = Estimate(runs.map { it.activeNanos.toDouble() })
    private fun Profile.paused() = Estimate(runs.map { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() })
    private fun Profile.tree() = runs.map { it.coarseTree }
    private fun Profile.described() = if (runs.size == 1) "1 run" else "${runs.size} runs"

    /** A header line's side, by its rule in either view, as every figure with an interval is ([durationWithInterval]). */
    private fun side(e: Estimate): String = durationWithInterval(e, view)

    /** A header line's parts, laid out together by [headerLines] so the lines' columns agree whatever a side's form. */
    private class HeaderLine(val key: String, val before: String, val after: String, val absolute: String, val relative: String, val suffix: String)

    /**
     * A header line, before -> after and the change in time and in percent: `?` from or to an unknown side, `=` when
     * equal, signed values with no interval; else judged once, on its percentage of before - the time over before, so
     * the two always agree - and written in both by [Judged.text]. From a before of 0 no percentage: judged on the time.
     */
    private fun header(key: String, b: Estimate, a: Estimate, suffix: String = ""): HeaderLine {
        val c = Change(b, a)
        fun line(absolute: String, relative: String) = HeaderLine(key, side(b), side(a), absolute, relative, suffix)
        // Not knowing a side is not knowing the change.
        if (b.unknown() || a.unknown()) return line(UNKNOWN, UNKNOWN)
        if (b.mean == a.mean) return line("=", "=")
        if (c.half.isNaN()) return line(unjudgedDuration(c.delta), signedPercent(c.relative))
        val r = c.relative * 100
        val h = c.relativeHalf * 100
        val judged = if (b.mean > 0) Judged(r, h, 100.0) else Judged(c.delta, c.half, 0.0)
        // Runs that agree on each side give an interval of 0: known exactly, one value with its arrow in either view.
        val exact = c.half == 0.0
        return line(
            judged.text(view, { durationChangeValue(c.delta) }, { if (exact) durationChangeValue(c.delta) else durationChangeRange(c.delta - c.half, c.delta + c.half) }),
            if (b.mean > 0) judged.text(view, { percentChangeValue(r, "%") }, { if (exact) percentChangeValue(r, "%") else percentChangeRange(maxOf(-100.0, r - h), r + h, "%") }) else "",
        )
    }

    /** The header lines, each column as wide as its widest cell: a side may be `6.34s ±5.0%` or `0.75..2.1s`. */
    private fun StringBuilder.headerLines(lines: List<HeaderLine>) {
        val before = maxOf(HEADER_SIDE, lines.maxOf { it.before.length })
        val after = maxOf(HEADER_SIDE, lines.maxOf { it.after.length })
        val absolute = maxOf(HEADER_ABSOLUTE, lines.maxOf { it.absolute.length })
        val relative = maxOf(HEADER_RELATIVE, lines.maxOf { it.relative.length })
        for (l in lines) appendLine(
            row(
                l.key, (l.before.padStart(before) + " ${sym.arrow} " + l.after.padEnd(after) + "  " + l.absolute.padEnd(absolute) +
                        " " + l.relative.padEnd(relative) + " " + l.suffix).trimEnd()
            )
        )
    }

    fun render(): String = buildString {
        append(before.failedBanner(named = true, width = width))
        append(after.failedBanner(named = true, width = width))
        appendLine("=".repeat(width))
        appendLine(row("Diff", "${before.name} (${before.described()}) ${sym.arrow} ${after.name} (${after.described()})"))
        warningsSection(warnings())
        factDiffLines(before.tagMaps, after.tagMaps, before.setupMaps, after.setupMaps)
        if (overRuns.isNotEmpty()) appendLine(row("", "Each side is the mean of its runs, each run in its own JVM. Each change is judged by its 95% interval (Welch's t)"))
        with(before) { runList("before") }
        with(after) { runList("after", header = false) }
        headerLines(buildList {
            add(header("Wall time", before.wallTime(), after.wallTime()))
            add(header("  Active", before.active(), after.active()))
            // No pause on either side is the default, nothing to look at: `Paused 0.00ns -> 0.00ns +0.00ns` said nothing.
            if (before.paused().mean > 0 || after.paused().mean > 0) add(header("  Paused", before.paused(), after.paused()))
        })
        clock()
        bothSides("Cores", before.coresText(), after.coresText())
        bothSides("Sampling", before.samplingText(), after.samplingText())
        // The tree first, as in the report: the shape, then the own-time detail.
        if (before.runs.any { it.coarseTree.isNotEmpty() } || after.runs.any { it.coarseTree.isNotEmpty() }) {
            appendLine()
            appendLine(
                "NESTED WALL TIME${sym.dash}$WALL_TIMED. An op is matched between the sides by its place in the tree: its parent op," +
                        " that op's parent op, up to the root op$overRuns"
            )
            fewRuns.forEach { appendLine(it) }
            appendLine(lensesLine(view, wallText.allColumns, lenses))
            with(wallText) { rows() }
            if (sharedRows.isNotEmpty()) {
                appendLine(SHARED_HEADING)
                with(wallText) { shared() }
            }
            keys.forEach { appendLine(it) }
            perSide(before.coarseKeys(view, lenses), after.coarseKeys(view, lenses))
        }
        appendLine()
        appendLine("OWN THREAD-TIME${sym.dash}$OWN_TIMED. An op is matched between the sides by its name, and the rows are sorted by the change$overRuns")
        fewRuns.forEach { appendLine(it) }
        appendLine(lensesLine(view, ownText.allColumns, lenses))
        with(ownText) { rows() }
        keys.forEach { appendLine(it) }
        perSide(before.fineNotes(view, lenses), after.fineNotes(view, lenses))
        appendLine()
        bothSides("Sampled", before.sampledText(), after.sampledText())
        appendLine("-".repeat(width))
        with(after.runs[0]) { renderLegend() }
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
        appendLine(row("CPU clock", "${before.runs[0].clock.format(b)} ${sym.arrow} ${after.runs[0].clock.format(a)} (the mean of each side's runs)"))
    }

    /**
     * What makes the change mean something other than the code's: printed on top, see [warningsSection].
     * The diff's own first, then each side's, which name their side: one run on a side is one of them.
     */
    private fun warnings(): Warnings {
        val own = mutableListOf<Warning>()
        val b = clockMean(before)
        val a = clockMean(after)
        val ofBase = (if (!a.isNaN()) after else before).runs[0].clock.ofBase
        if (!a.isNaN() && !b.isNaN() && (before.runs + after.runs).all { it.clock.ofBase == ofBase } && maxOf(a, b) > minOf(a, b) * (1 + CLOCK_MOVED))
            own += Warning(
                Kind.CLOCK_BETWEEN_SIDES, words(before.runs[0].clock.format(b)), words(after.runs[0].clock.format(a)),
                figure(maxOf(a, b) / minOf(a, b) - 1, ::shareText),
            )
        own += allocationsWarnings(before.runs, after.runs)
        val sides = before.warnings(named = true) + after.warnings(named = true)
        return Warnings(own.map { it.text() } + sides.ranked, sides.always)
    }
}
