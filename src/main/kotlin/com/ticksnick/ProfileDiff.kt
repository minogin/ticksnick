package com.ticksnick

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** The header lines, wall, active, paused and the root row: before -> after, the change +- 95% absolute and relative. */
internal val RUNS_DIFF_HEADER: String get() = "%7s ${sym.arrow} %-7s  %-22s %-16s %s"

/** The chance a diff may run of showing one false change, across all of it. */
internal const val FALSE_CHANGE = 0.05

/** Under a diff's table that has a change marked `?`: what it means, and what to do about it. */
internal const val NOISE_NOTE = "  ? the change could be noise: the runs differ too much to tell, given how many changes this diff shows; " +
        "more runs of each profile can settle it"

/** The `CAREFUL` line of a diff of one run against one. */
internal val ONE_RUN_DIFF_CAREFUL: String get() = "times: 1 run on each side, no interval${sym.dash}a change in time may be noise, a run goes faster or " +
        "slower as a whole; changes in share of the parent hold"

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

    val relative: Double get() = if (before.mean == 0.0) Double.NaN else delta / before.mean
    val relativeHalf: Double get() = if (before.mean == 0.0) Double.NaN else half / before.mean

    /**
     * **Whether this change could be noise, in a diff of [changes] changes.** A 95% interval misses
     * zero one time in twenty when nothing changed, so a diff of 80 of them would show four changes
     * that are not there, looking much like `+10% +-9 pp`. So each is held to 5% divided by [changes]
     * (Bonferroni), and the whole diff has about a 5% chance of showing one false change. Changes that
     * move together, as Wall and Mean do, make it cautious.
     *
     * This asks whether the change is real. How well its size is known is the `+-`, whatever this says.
     */
    fun isNoise(changes: Int): Boolean {
        if (half.isNaN() || delta.isNaN() || delta == 0.0) return false
        if (a + b == 0.0) return false
        return studentTail(abs(delta) / sqrt(a + b), degrees) > FALSE_CHANGE / changes
    }
}

/** A fraction as a signed percentage, `-21%`, `+2.4%`. */
internal fun signedPercent(f: Double): String {
    if (f.isNaN()) return ""
    val p = f * 100
    val sign = if (p < 0) "-" else "+"
    return if (abs(p) < 9.95) String.format(Locale.ROOT, "%s%.1f%%", sign, abs(p)) else String.format(Locale.ROOT, "%s%.0f%%", sign, abs(p))
}

/**
 * A change's interval, in points of the change, with the change's [unit]: `+-2.2 pp` for a share's,
 * `+-119%` for a relative one. Written after the change without its unit, `-22 +-119%`, so the unit is
 * said once. Nothing where there is none.
 */
internal fun pointsHalf(points: Double, unit: String): String = when {
    points.isNaN() -> ""
    points < 9.95 -> String.format(Locale.ROOT, "${sym.plusMinus}%.1f%s", points, unit)
    else -> String.format(Locale.ROOT, "${sym.plusMinus}%.0f%s", points, unit)
}

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
    private val coarseRows = paired(before.coarseTable.rows, after.coarseTable.rows)
    private val sharedRows = paired(before.coarseTable.shared, after.coarseTable.shared)

    /** What moved most on top, whether it went up or down. */
    private val fineRows = paired(before.fineTable.rows, after.fineTable.rows).sortedByDescending { r ->
        fun time(row: Row?) = row?.figures?.get(THREAD_TIME)?.mean?.takeIf { !it.isNaN() } ?: 0.0
        abs(time(r.after) - time(r.before))
    }

    /** How many changes this diff shows with an interval, in either table. See [Change.isNoise]. */
    private val changes: Int by lazy {
        fun count(r: DiffRow, columns: List<Column>): Int =
            columns.count { c -> c.interval && r.change(c)?.half?.isNaN() == false } + r.children.sumOf { count(it, columns) }
        ((coarseRows + sharedRows).sumOf { count(it, COARSE_COLUMNS) } + fineRows.sumOf { count(it, FINE_COLUMNS) }).coerceAtLeast(1)
    }

    private val coarseText = DiffText(before.coarseTable, after.coarseTable, coarseRows, sharedRows, "Coarse operation", COARSE_NAME_WIDTH, WALL) { it.isNoise(changes) }
    private val fineText = DiffText(before.fineTable, after.fineTable, fineRows, emptyList(), "Operation", FINE_NAME_WIDTH, null) { it.isNoise(changes) }
    private val width = maxOf(coarseText.width, fineText.width)

    /** Whether a header line printed a `?`, which no table's note may be there to explain. */
    private var headerNoise = false
    private val overRuns = if (before.runs.size > 1 || after.runs.size > 1) "; each side the mean of its runs, the change ${sym.plusMinus} 95%" else ""

    private fun Profile.wallTime() = Estimate(runs.map { it.samplingSpanNanos.toDouble() })
    private fun Profile.active() = Estimate(runs.map { it.activeNanos.toDouble() })
    private fun Profile.paused() = Estimate(runs.map { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() })
    private fun Profile.tree() = runs.map { it.coarseTree }
    private fun Profile.described() = if (runs.size == 1) "1 run" else "${runs.size} runs"

    private fun header(key: String, b: Estimate, a: Estimate, suffix: String = ""): String {
        val c = Change(b, a)
        val sign = if (c.delta < 0) "-" else "+"
        val absolute = if (c.half.isNaN()) sign + duration(abs(c.delta)) else durationPlusMinus(c.delta, c.half, signed = true)
        headerNoise = headerNoise || c.isNoise(changes)
        // The change without its unit and with its `?`, the interval with the unit: `-27? +-67%`.
        val relative = if (c.relativeHalf.isNaN()) signedPercent(c.relative)
        else signedPercent(c.relative).removeSuffix("%") + (if (c.isNoise(changes)) "?" else "") + " " + pointsHalf(c.relativeHalf * 100, "%")
        return row(key, String.format(Locale.ROOT, RUNS_DIFF_HEADER, duration(b.mean), duration(a.mean), absolute, relative, suffix).trimEnd())
    }

    fun render(): String = buildString {
        appendLine("=".repeat(width))
        appendLine(row("Diff", "${before.name} (${before.described()}) ${sym.arrow} ${after.name} (${after.described()})"))
        val rootName = (after.root() ?: before.root())?.name
        carefulBlock(careful(rootName))
        factDiffLines(before.tagMaps, after.tagMaps, before.setupMaps, after.setupMaps)
        if (overRuns.isNotEmpty()) appendLine(row("", "each side the mean of its runs, one JVM each; the change ${sym.plusMinus} its 95% interval (Welch's t)"))
        with(before) { runList("before") }
        with(after) { runList("after", header = false) }
        appendLine(header("Wall time", before.wallTime(), after.wallTime()))
        appendLine(header("  Active", before.active(), after.active()))
        appendLine(header("  Paused", before.paused(), after.paused()))
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
            appendLine("COARSE OPERATIONS${sym.dash}wall time, inclusive, matched by path$overRuns")
            with(coarseText) { rows() }
            if (sharedRows.isNotEmpty()) {
                appendLine(SHARED_HEADING)
                with(coarseText) { shared() }
            }
            if (coarseText.anyNoise) appendLine(NOISE_NOTE)
            perSide(before.coarseKeys(), after.coarseKeys())
        }
        appendLine()
        appendLine("COARSE AND FINE OPERATIONS${sym.dash}own thread-time, matched by name, sorted by the change$overRuns")
        with(fineText) { rows() }
        if (fineText.anyNoise) appendLine(NOISE_NOTE)
        perSide(before.fineNotes(), after.fineNotes())
        if (headerNoise && !coarseText.anyNoise && !fineText.anyNoise) appendLine(NOISE_NOTE)
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
        appendLine(row(key, "before  ${b ?: "-"}"))
        appendLine(row("", "after   ${a ?: "-"}"))
    }

    private fun clockMean(r: Profile) = r.runs.map { it.clock.mean }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun StringBuilder.clock() {
        val b = clockMean(before)
        val a = clockMean(after)
        if (b.isNaN() && a.isNaN()) return
        appendLine(row("Clock", "${before.runs[0].clock.format(b)} ${sym.arrow} ${after.runs[0].clock.format(a)}, the mean of each side's runs"))
    }

    /** What makes the change mean something other than the code's: printed on top, see [carefulBlock]. */
    private fun careful(rootName: String?): Warnings {
        val lines = mutableListOf<String>()
        val b = clockMean(before)
        val a = clockMean(after)
        val ofBase = (if (!a.isNaN()) after else before).runs[0].clock.ofBase
        if (!a.isNaN() && !b.isNaN() && (before.runs + after.runs).all { it.clock.ofBase == ofBase } && maxOf(a, b) > minOf(a, b) * (1 + CLOCK_MOVED))
            lines += String.format(
                Locale.ROOT, "clock: %s ${sym.arrow} %s between the sides${sym.dash}up to %.2f${sym.times} of a time difference is the machine, not the code; shares hold",
                before.runs[0].clock.format(b), after.runs[0].clock.format(a), maxOf(a, b) / minOf(a, b)
            )
        val oneRunEach = before.runs.size == 1 && after.runs.size == 1
        if (oneRunEach) lines += ONE_RUN_DIFF_CAREFUL
        var sides = Warnings()
        for (side in listOf(before, after)) {
            val interval = if (side.runs.size == 1 && !oneRunEach) listOf("times: 1 run of '${side.name}', no interval${sym.dash}a change may be noise; run it again for one") else emptyList()
            sides += Warnings(interval) + side.warnings(named = true)
        }
        if (rootName != null) {
            fun countsOf(side: Profile) = side.matched(side.tree(), rootName).let { m -> m.present.map { m.nodes[it]!!.count } }.distinct()
            val cb = countsOf(before)
            val ca = countsOf(after)
            if (cb.size == 1 && ca.size == 1 && ca != cb)
                lines += "$rootName: run ${cb[0]} times before, ${ca[0]} after${sym.dash}compare per execution"
        }
        lines += allocationsCareful(before.runs, after.runs)
        return Warnings(lines + sides.ranked, sides.always)
    }
}
