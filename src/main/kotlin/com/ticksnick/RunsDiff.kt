package com.ticksnick

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal const val RUNS_DIFF_WIDTH = 165

/** The header lines, wall, active, paused and the root row: before -> after, the change +- 95% absolute and relative, the verdict. */
internal const val RUNS_DIFF_HEADER = "%7s -> %-7s  %-22s %-15s %s"
internal const val RUNS_DIFF_COARSE_ROW = "%-40s | %-20s %-24s | %-28s | %s"
internal const val RUNS_DIFF_FINE_ROW = "%-26s | %-20s %-24s | %-20s %-24s | %s"

/** The `CAREFUL` line of a diff of one run against one, which [ProfileDiff] renders. */
internal const val ONE_RUN_DIFF_CAREFUL = "times: 1 run on each side, no interval - a change in time may be noise, a run goes faster or " +
        "slower as a whole; changes in share of the parent hold"

/**
 * The change of one figure between two versions: after's mean of its runs minus before's, and the
 * half-width of its 95% interval by Welch's t, which does not assume the two sides spread alike.
 *
 * **A verdict only where the interval is on one side of 0.** `-10% +-12%` is somewhere between -22%
 * and +2%: it leans faster, but saying so would be wrong more often than one time in twenty, so it
 * reads `unclear`, not `faster` and not "the same" - the runs cannot tell yet. A side with one run
 * has no spread, so the change has no interval and no verdict.
 */
internal class Change(val before: Estimate, val after: Estimate) {
    val delta: Double = after.mean - before.mean

    val half: Double = if (before.n < 2 || after.n < 2) Double.NaN else {
        val a = before.variance / before.n
        val b = after.variance / after.n
        if (a + b == 0.0) 0.0 else {
            // Welch-Satterthwaite, rounded down: fewer degrees of freedom, a wider interval.
            val df = (a + b) * (a + b) / (a * a / (before.n - 1) + b * b / (after.n - 1))
            t95(floor(df).toInt().coerceAtLeast(1)) * sqrt(a + b)
        }
    }

    val relative: Double get() = if (before.mean == 0.0) Double.NaN else delta / before.mean
    val relativeHalf: Double get() = if (before.mean == 0.0) Double.NaN else half / before.mean

    val verdict: String
        get() = when {
            half.isNaN() || delta.isNaN() -> ""
            delta + half < 0 -> "faster"
            delta - half > 0 -> "slower"
            else -> "unclear"
        }
}

/** A fraction as a signed percentage, `-21%`, `+2.4%`. */
private fun signedPercent(f: Double): String {
    if (f.isNaN()) return ""
    val p = f * 100
    val sign = if (p < 0) "-" else "+"
    return if (abs(p) < 9.95) String.format(Locale.ROOT, "%s%.1f%%", sign, abs(p)) else String.format(Locale.ROOT, "%s%.0f%%", sign, abs(p))
}

/** A fraction as `+-3.5%`, nothing where there is no interval. */
private fun percentHalf(f: Double): String = when {
    f.isNaN() -> ""
    f * 100 < 9.95 -> String.format(Locale.ROOT, "+-%.1f%%", f * 100)
    else -> String.format(Locale.ROOT, "+-%.0f%%", f * 100)
}

/** `-21% +-3.5%  faster`, or the change alone when there is no interval, or nothing when a side lacks the row. */
private fun changeCell(c: Change): String =
    if (c.before.n == 0 || c.after.n == 0 || c.delta.isNaN()) ""
    else String.format(Locale.ROOT, "%-6s %-7s %s", signedPercent(c.relative), percentHalf(c.relativeHalf), c.verdict).trimEnd()

/** `before -> after` of two means, a dash on a side that has none. */
private fun pair(b: Estimate, a: Estimate): String =
    (if (b.n == 0) "-" else duration(b.mean)) + " -> " + (if (a.n == 0) "-" else duration(a.mean))

/** One side's counts over its runs: the count when every run agrees, `~` their mean when not. */
private fun counts(c: List<Long>): String = when {
    c.isEmpty() -> "-"
    c.distinct().size == 1 -> String.format(Locale.ROOT, "%,d", c[0])
    else -> String.format(Locale.ROOT, "~%,.0f", c.average())
}

/** `2 =` when both sides agree, `2 -> 3` when not: a step that did different work is a different finding. */
private fun countPair(b: List<Long>, a: List<Long>): String =
    if (b.isNotEmpty() && b.distinct().size == 1 && b.distinct() == a.distinct()) "${counts(b)} =" else "${counts(b)} -> ${counts(a)}"

/**
 * **Two versions over their runs**: each side a folder of runs (see [ProfileRuns]), every
 * figure the mean of its side's runs, and the change with its 95% interval and a verdict.
 *
 * One run on each side renders as a [ProfileDiff]. One run on one side only prints the
 * means and the change, with no interval, and says so.
 */
internal class RunsDiff(val before: ProfileRuns, val after: ProfileRuns) {

    fun render(): String =
        if (before.runs.size == 1 && after.runs.size == 1)
            ProfileDiff(before.runs[0], after.runs[0], before.runName(0), after.runName(0)).render(careful = listOf(ONE_RUN_DIFF_CAREFUL))
        else across()

    private fun ProfileRuns.wallTime() = Estimate(runs.map { it.samplingSpanNanos.toDouble() })
    private fun ProfileRuns.active() = Estimate(runs.map { it.activeNanos.toDouble() })
    private fun ProfileRuns.paused() = Estimate(runs.map { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() })
    private fun ProfileRuns.tree() = runs.map { it.coarseTree }
    private fun ProfileRuns.described() = if (runs.size == 1) "1 run" else "${runs.size} runs"

    private fun header(key: String, b: Estimate, a: Estimate, suffix: String = ""): String {
        val c = Change(b, a)
        val sign = if (c.delta < 0) "-" else "+"
        val absolute = sign + duration(abs(c.delta)) + if (c.half.isNaN()) "" else " +- " + duration(c.half)
        val relative = (signedPercent(c.relative) + " " + percentHalf(c.relativeHalf)).trimEnd()
        return row(key, String.format(Locale.ROOT, RUNS_DIFF_HEADER, duration(b.mean), duration(a.mean), absolute, relative, c.verdict + suffix).trimEnd())
    }

    private fun across(): String = buildString {
        appendLine("=".repeat(RUNS_DIFF_WIDTH))
        appendLine(row("Diff", "${before.name} (${before.described()}) -> ${after.name} (${after.described()})"))
        val rootName = (after.root() ?: before.root())?.name
        carefulBlock(careful(rootName))
        appendLine(
            row(
                "", "each side the mean of its runs, one JVM each; the change +- its 95% interval (Welch's t); " +
                        "faster or slower only where the whole interval is on one side of 0"
            )
        )
        with(before) { runList("before") }
        with(after) { runList("after", header = false) }
        appendLine(header("Wall time", before.wallTime(), after.wallTime()))
        appendLine(header("  Active", before.active(), after.active()))
        appendLine(header("  Paused", before.paused(), after.paused()))
        if (rootName != null) {
            val b = before.matched(before.tree(), rootName)
            val a = after.matched(after.tree(), rootName)
            if (b.present.isNotEmpty() && a.present.isNotEmpty())
                appendLine(header(rootName, before.meanSpan(b), after.meanSpan(a), "   per execution"))
        }
        clock()
        coarse()
        fine()
        appendLine()
        appendLine("  faster / slower: the whole 95% interval of the change is below / above 0; unclear: it includes 0 - more runs on both sides narrow it")
        appendLine("=".repeat(RUNS_DIFF_WIDTH))
    }

    private fun clockMean(r: ProfileRuns) = r.runs.map { it.clock.mean }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

    private fun StringBuilder.clock() {
        val b = clockMean(before)
        val a = clockMean(after)
        if (b.isNaN() && a.isNaN()) return
        appendLine(row("Clock", "${before.runs[0].clock.format(b)} -> ${after.runs[0].clock.format(a)}, the mean of each side's runs"))
    }

    /** What makes the change mean something other than the code's: printed on top, see [carefulBlock]. */
    private fun careful(rootName: String?): List<String> {
        val lines = mutableListOf<String>()
        val b = clockMean(before)
        val a = clockMean(after)
        val ofBase = (if (!a.isNaN()) after else before).runs[0].clock.ofBase
        if (!a.isNaN() && !b.isNaN() && (before.runs + after.runs).all { it.clock.ofBase == ofBase } && maxOf(a, b) > minOf(a, b) * (1 + CLOCK_MOVED))
            lines += String.format(
                Locale.ROOT, "clock: %s -> %s between the sides - up to %.2fx of a time difference is the machine, not the code; shares hold",
                before.runs[0].clock.format(b), after.runs[0].clock.format(a), maxOf(a, b) / minOf(a, b)
            )
        for (side in listOf(before, after)) {
            if (side.runs.size == 1) lines += "times: 1 run of '${side.name}', no interval - a change may be noise and no row gets a verdict; run it again for one"
            val root = rootName?.let { side.matched(side.tree(), it) }
            lines += side.carefulLines(root, where = " in '${side.name}'")
        }
        if (rootName != null) {
            fun countsOf(side: ProfileRuns) = side.matched(side.tree(), rootName).let { m -> m.present.map { m.nodes[it]!!.count } }.distinct()
            val cb = countsOf(before)
            val ca = countsOf(after)
            if (cb.size == 1 && ca.size == 1 && ca != cb)
                lines += "$rootName: run ${cb[0]} times before, ${ca[0]} after - compare per execution"
        }
        return lines
    }

    private fun StringBuilder.coarse() {
        if (before.runs.all { it.coarseTree.isEmpty() } && after.runs.all { it.coarseTree.isEmpty() }) return
        appendLine()
        appendLine("COARSE OPERATIONS - wall time, inclusive, matched by path; each side the mean of its runs, the change +- 95%")
        appendLine(String.format(Locale.ROOT, RUNS_DIFF_COARSE_ROW, "Coarse operation", "Wall", "change", "% parent", "Executions"))
        appendLine("-".repeat(RUNS_DIFF_WIDTH))
        fun walk(bLevel: List<List<CoarseNode>>, aLevel: List<List<CoarseNode>>, bParents: List<CoarseNode?>, aParents: List<CoarseNode?>, depth: Int) {
            // The later version's order, then the paths only the earlier one had, so a removed row is not silently gone.
            val names = after.childrenOf(aLevel).map { it.name }.let { seen -> seen + before.childrenOf(bLevel).map { it.name }.filter { it !in seen } }
            for (name in names) {
                val b = before.matched(bLevel, name)
                val a = after.matched(aLevel, name)
                val wb = before.wall(b)
                val wa = after.wall(a)
                val sb = before.share(b, bParents)
                val sa = after.share(a, aParents)
                val share = when {
                    sb.n == 0 && sa.n == 0 -> ""
                    sb.n == 0 || sa.n == 0 -> (if (sb.n == 0) "-" else "%.0f%%".format(Locale.ROOT, sb.mean)) + " -> " +
                            (if (sa.n == 0) "-" else "%.0f%%".format(Locale.ROOT, sa.mean))
                    else -> {
                        val c = Change(sb, sa)
                        String.format(Locale.ROOT, "%.0f%% -> %.0f%%  %+.1f", sb.mean, sa.mean, c.delta) +
                                if (c.half.isNaN()) " pp" else String.format(Locale.ROOT, " +-%.1f pp", c.half)
                    }
                }
                val partial = b.present.size < before.runs.size || a.present.size < after.runs.size
                val where = if (partial) " (${b.present.size} -> ${a.present.size} runs)" else ""
                appendLine(
                    String.format(
                        Locale.ROOT, RUNS_DIFF_COARSE_ROW, "  ".repeat(depth) + name + where,
                        pair(wb, wa), changeCell(Change(wb, wa)), share,
                        countPair(b.present.map { b.nodes[it]!!.count }, a.present.map { a.nodes[it]!!.count }),
                    ).trimEnd()
                )
                walk(b.nodes.map { it?.children ?: emptyList() }, a.nodes.map { it?.children ?: emptyList() }, b.nodes, a.nodes, depth + 1)
            }
        }
        walk(before.tree(), after.tree(), before.runs.map { null }, after.runs.map { null }, 0)
        appendLine("-".repeat(RUNS_DIFF_WIDTH))
        appendLine("  % parent is each run's share of its own parent, its change in percentage points: the run's speed cancels out of it")
    }

    private fun StringBuilder.fine() {
        appendLine()
        appendLine("COARSE AND FINE OPERATIONS - own thread-time, matched by name, sorted by the change; each side the mean of its runs, the change +- 95%")
        appendLine(String.format(Locale.ROOT, RUNS_DIFF_FINE_ROW, "Operation", "Thread-time", "change", "Per call", "change", "Calls"))
        appendLine("-".repeat(RUNS_DIFF_WIDTH))
        val b = before.fineLines()
        val a = after.fineLines()
        val none = Estimate(emptyList())
        fun moved(label: String): Double {
            val tb = b[label]?.time?.mean ?: 0.0
            val ta = a[label]?.time?.mean ?: 0.0
            return abs(ta - tb)
        }
        for (label in (b.keys + a.keys).distinct().sortedByDescending { moved(it) }) {
            val lb = b[label]
            val la = a[label]
            val partial = (lb?.present ?: 0) < before.runs.size || (la?.present ?: 0) < after.runs.size
            val where = if (partial) " (${lb?.present ?: 0} -> ${la?.present ?: 0})" else ""
            val time = Change(lb?.time ?: none, la?.time ?: none)
            val perCall = Change(lb?.perCall ?: none, la?.perCall ?: none)
            val calls = if ((lb?.calls ?: emptyList()).all { it == 0L } && (la?.calls ?: emptyList()).all { it == 0L }) "-"
            else countPair(lb?.calls ?: emptyList(), la?.calls ?: emptyList())
            appendLine(
                String.format(
                    Locale.ROOT, RUNS_DIFF_FINE_ROW, label + where,
                    pair(time.before, time.after), changeCell(time),
                    pair(perCall.before, perCall.after), changeCell(perCall), calls,
                ).trimEnd()
            )
        }
        appendLine("-".repeat(RUNS_DIFF_WIDTH))
    }
}
