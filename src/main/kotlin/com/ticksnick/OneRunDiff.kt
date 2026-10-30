package com.ticksnick

import java.util.Locale
import kotlin.math.abs

/** The three header lines, wall, active and paused: before -> after, the change, the percentage, each column right-aligned. */
internal const val HEADER_DIFF_ROW = "%7s -> %7s  %9s %7s"

/**
 * Two runs side by side: what changed between a run and the one before it.
 *
 * Operations are matched by name and coarse nodes by path, and every figure is printed as
 * `before -> after` with the change beside it, because a diff tool on two rendered reports shows
 * that every number moved and leaves the arithmetic to the eye. Sorted by the size of the change
 * in thread-time, so what moved most is on top whether it went up or down.
 *
 * **Calls are the check.** An operation that got faster at the same call count is faster; one
 * with fewer calls did less work, which is a different finding, and the column says `=` when the
 * two agree so that the exception stands out.
 */
internal class OneRunDiff(
    val before: Run,
    val after: Run,
    val beforeName: String = "before",
    val afterName: String = "after",
) {
    /**
     * Each run's coarse rows by wall time, as the report prints them (see [Run.wallsOf]).
     */
    private val wallBefore = before.wallsOf()
    private val wallAfter = after.wallsOf()

    private val coarseExtra = nameOverflow(COARSE_DIFF_ROW, before.coarseRowNames() + after.coarseRowNames())
    private val allocations = before.allocations || after.allocations
    private val coarseRow = widened(COARSE_DIFF_ROW, coarseExtra) + if (allocations) " | %21s" else ""
    private val fineExtra = nameOverflow(FINE_DIFF_ROW, matched().map { it.label })
    private val fineRow = widened(FINE_DIFF_ROW, fineExtra)

    /** One operation on both sides; either side may be absent. */
    private class Pair(val label: String, val a: Run.OperationRow?, val b: Run.OperationRow?)

    /** @param careful warnings from the caller, in the `CAREFUL` section under the title. */
    fun render(careful: Warnings = Warnings()): String = buildString {
        appendLine("=".repeat(DIFF_WIDTH + maxOf(coarseExtra, fineExtra)))
        appendLine(row("Diff", "$beforeName -> $afterName"))
        carefulBlock(
            careful + Warnings(ClockTrace.diffCareful(before.clock, after.clock) + allocationsCareful(listOf(before), listOf(after)))
        )
        factDiffLines(listOf(before.tags), listOf(after.tags), listOf(before.facts), listOf(after.facts))
        // Three lines and not one `wall = active + paused`, so that each carries its change, as every
        // row below does.
        fun headerLine(key: String, a: Double, b: Double): String {
            if (a.isNaN() || b.isNaN()) return row(key, pair(a, b))
            val d = b - a
            val sign = if (d < 0) "-" else "+"
            val pct = if (a == 0.0) "" else String.format(Locale.ROOT, "%s%.1f%%", sign, abs(d) / a * 100)
            return row(key, String.format(Locale.ROOT, HEADER_DIFF_ROW, duration(a), duration(b), sign + duration(abs(d)), pct))
        }
        appendLine(headerLine("Wall time", before.samplingSpanNanos.toDouble(), after.samplingSpanNanos.toDouble()))
        appendLine(headerLine("  Active", before.activeNanos.toDouble(), after.activeNanos.toDouble()))
        appendLine(headerLine("  Paused", before.pause.confirmedPausedIn(before.samplingSpanNanos).toDouble(), after.pause.confirmedPausedIn(after.samplingSpanNanos).toDouble()))
        for (l in ClockTrace.diffLines(before.clock, after.clock)) appendLine(l)
        // The tree first, as in the report: the shape, then the own-time detail.
        if (before.coarseTree.isNotEmpty() || after.coarseTree.isNotEmpty()) {
            appendLine()
            appendLine("COARSE OPERATIONS - wall time, inclusive, matched by path")
            appendLine(
                String.format(
                    Locale.ROOT, coarseRow,
                    "Coarse operation", "Wall", "change", "Paused", "Executions", "Per parent", "Allocated"
                )
            )
            appendLine("-".repeat(DIFF_WIDTH + coarseExtra))
            tree(after.coarseTree, before.coarseTree, 0)
            appendLine("-".repeat(DIFF_WIDTH + coarseExtra))
            if (anyOwn) appendLine(
                "  $SPLIT_MARK rows are fine labels in a row's own time; Per parent on one is its calls per execution of the row, " +
                "counted, which holds whatever the size of each run"
            )
        }
        appendLine()
        appendLine("COARSE AND FINE OPERATIONS - own time, matched by name, sorted by the change")
        appendLine(
            String.format(
                Locale.ROOT, fineRow,
                "Operation", "Thread-time", "change", "Thread-time per call", "Calls", "Long"
            )
        )
        appendLine("-".repeat(DIFF_WIDTH + fineExtra))
        val pairs = matched()
        fun moved(x: Pair): Double {
            val a = threadTime(before, x.a).let { if (it.isNaN()) 0.0 else it }
            val b = threadTime(after, x.b).let { if (it.isNaN()) 0.0 else it }
            return abs(b - a)
        }
        for (p in pairs.sortedByDescending { moved(it) }) {
            val ta = threadTime(before, p.a)
            val tb = threadTime(after, p.b)
            // A side with no samples reads as absent, not as 0.00 ns: a coarse row with no own
            // time on one run and a millisecond on the other is not a change worth a percentage.
            val hasA = (p.a?.hits ?: 0L) > 0L
            val hasB = (p.b?.hits ?: 0L) > 0L
            appendLine(
                String.format(
                    Locale.ROOT, fineRow,
                    p.label,
                    pair(ta, tb, hasA, hasB),
                    change(ta, tb, hasA && hasB),
                    pair(p.a?.perCallNanos() ?: Double.NaN, p.b?.perCallNanos() ?: Double.NaN, hasA, hasB),
                    calls(p.a, p.b),
                    long(before, p.a) + " -> " + long(after, p.b),
                )
            )
        }
        appendLine("-".repeat(DIFF_WIDTH + fineExtra))
        appendLine("=".repeat(DIFF_WIDTH + maxOf(coarseExtra, fineExtra)))
    }

    private fun StringBuilder.tree(
        afterNodes: List<CoarseNode>, beforeNodes: List<CoarseNode>, depth: Int,
        parentBefore: CoarseNode? = null, parentAfter: CoarseNode? = null,
    ) {
        val indent = "  ".repeat(depth)
        val seen = HashSet<String>()
        for (b in afterNodes.sortedByDescending { wallAfter[it] ?: 0.0 }) {
            val a = beforeNodes.firstOrNull { it.name == b.name }
            seen += b.name
            node(indent, b.name, a, b, parentBefore, parentAfter)
            ownRows(indent, a, b)
            tree(b.children, a?.children ?: emptyList(), depth + 1, a, b)
        }
        // Paths that the earlier run had and this one does not: printed under their parent, with
        // nothing on the right, so that a label that was removed is not silently gone.
        for (a in beforeNodes.filter { it.name !in seen }.sortedByDescending { wallBefore[it] ?: 0.0 }) {
            node(indent, a.name, a, null, parentBefore, parentAfter)
            ownRows(indent, a, null)
            tree(emptyList(), a.children, depth + 1, a, null)
        }
    }

    /** Whether any fine row was drawn under a coarse one, for the key under the tree. */
    private var anyOwn = false

    /**
     * The fine labels in a coarse row's own time, on either side, in the report's form: wall, calls,
     * and calls per execution of the row. Totals of two runs that did different amounts of work cannot
     * be compared; calls per execution can, and `340 =` says the row did the same work.
     */
    private fun StringBuilder.ownRows(indent: String, a: CoarseNode?, b: CoarseNode?) {
        val ra = a?.let { before.ownRowsOf(it) }.orEmpty()
        val rb = b?.let { after.ownRowsOf(it) }.orEmpty()
        val names = (after.drawnOwnRows(rb) + before.drawnOwnRows(ra)).map { it.name }.distinct()
        for (name in names) {
            anyOwn = true
            val x = ra.firstOrNull { it.name == name }
            val y = rb.firstOrNull { it.name == name }
            val ta = x?.wallNanos ?: 0.0
            val tb = y?.wallNanos ?: 0.0
            val ca = ownCalls(x, a)
            val cb = ownCalls(y, b)
            appendLine(
                String.format(
                    Locale.ROOT, coarseRow, "$indent  $SPLIT_MARK $name",
                    pair(ta, tb, ta > 0, tb > 0), change(ta, tb, ta > 0 && tb > 0), "",
                    if (ca == null && cb == null) "own" else counts(ca, cb),
                    perParentPair(ca?.let { perParentText(it, a!!.count) } ?: "", cb?.let { perParentText(it, b!!.count) } ?: ""),
                    "",
                ).trimEnd()
            )
        }
    }

    /** [r]'s calls on [n]: 0 for a label not there on a path that counted calls; null where they were not counted. */
    private fun ownCalls(r: Run.OwnRow?, n: CoarseNode?): Long? {
        if (n == null) return null
        val calls = r?.calls ?: if (n.calls.isNotEmpty()) 0L else -1L
        return calls.takeIf { it >= 0 }
    }

    private fun perParent(n: CoarseNode?, parent: CoarseNode?): String =
        if (n == null || parent == null) "" else perParentText(n.count, parent.count)

    private fun StringBuilder.node(indent: String, name: String, a: CoarseNode?, b: CoarseNode?, parentBefore: CoarseNode?, parentAfter: CoarseNode?) {
        val ta = a?.let { wallBefore[it] } ?: Double.NaN
        val tb = b?.let { wallAfter[it] } ?: Double.NaN
        appendLine(
            String.format(
                Locale.ROOT, coarseRow,
                indent + name,
                pair(ta, tb, a != null && a.count > 0, b != null && b.count > 0),
                change(ta, tb, a != null && b != null && a.count > 0 && b.count > 0),
                pair(a?.pausedNanos?.toDouble() ?: 0.0, b?.pausedNanos?.toDouble() ?: 0.0, a != null && a.pausedNanos > 0, b != null && b.pausedNanos > 0),
                counts(a?.count, b?.count),
                coarsePerParentPair(perParent(a, parentBefore), perParent(b, parentAfter)),
                bytesPair(
                    a?.takeIf { before.allocations }?.let { before.allocatedPerExecution(it) } ?: Double.NaN,
                    b?.takeIf { after.allocations }?.let { after.allocatedPerExecution(it) } ?: Double.NaN,
                ),
            ).trimEnd()
        )
    }

    /** Every operation on either side, by its printed label, fine rows and starred coarse rows alike. */
    private fun matched(): List<Pair> {
        val a = before.rows().associateBy { it.label }
        val b = after.rows().associateBy { it.label }
        return (a.keys + b.keys).distinct().map { Pair(it, a[it], b[it]) }
            .filter { (it.a?.hits ?: 0) > 0 || (it.b?.hits ?: 0) > 0 }
    }

    private fun threadTime(p: Run, r: Run.OperationRow?): Double =
        if (r == null) Double.NaN else r.hits * p.stepNanos

    private fun long(p: Run, r: Run.OperationRow?): String =
        if (r == null || !r.longShown) "-"
        else String.format(Locale.ROOT, "%,d / %s", r.stuckInstances, threadTime(r.stuckHits * p.stepNanos))

    private fun calls(a: Run.OperationRow?, b: Run.OperationRow?): String = counts(a?.calls, b?.calls)

    private fun counts(a: Long?, b: Long?): String = when {
        a == null && b == null -> "-"
        a == null -> String.format(Locale.ROOT, "- -> %,d", b)
        b == null -> String.format(Locale.ROOT, "%,d -> -", a)
        a == b -> String.format(Locale.ROOT, "%,d =", a)
        else -> String.format(Locale.ROOT, "%,d -> %,d", a, b)
    }

    /** `before -> after` as durations, a dash on a side that has nothing. */
    private fun pair(a: Double, b: Double, hasA: Boolean = true, hasB: Boolean = true): String =
        (if (hasA && !a.isNaN()) duration(a) else "-") + " -> " + (if (hasB && !b.isNaN()) duration(b) else "-")

    /** The change as a signed duration and a signed percentage, or nothing when a side is missing. */
    private fun change(a: Double, b: Double, both: Boolean): String {
        if (!both || a.isNaN() || b.isNaN()) return ""
        val d = b - a
        val sign = if (d < 0) "-" else "+"
        val pct = if (a == 0.0) "" else String.format(Locale.ROOT, " %s%.1f%%", sign, abs(d) / a * 100)
        return sign + duration(abs(d)) + pct
    }

    companion object {
        const val DIFF_WIDTH = 150
        const val FINE_DIFF_ROW = "%-26s | %21s %16s | %21s | %28s | %24s"
        const val COARSE_DIFF_ROW = "%-40s | %21s %16s | %21s | %24s | %14s"
    }
}
