package com.ticksnick

import java.util.Locale
import kotlin.math.abs

/** The three header lines, wall, active and paused: before -> after, the change, the percentage, each column right-aligned. */
const val HEADER_DIFF_ROW = "%7s -> %7s  %9s %7s"

/**
 * Two profiles side by side: what changed between a run and the one before it.
 *
 * Operations are matched by name and coarse nodes by path, and every figure is printed as
 * `before -> after` with the change beside it, because a diff tool on two rendered reports shows
 * that every number moved and leaves the arithmetic to the eye. Sorted by the size of the change
 * in thread-time, so what moved most is on top whether it went up or down.
 *
 * **Calls are the check.** A step that got faster at the same call count is a faster step; a step
 * with fewer calls did less work, which is a different finding, and the column says `=` when the
 * two agree so that the exception stands out.
 */
class ProfileDiff(
    val before: Profile,
    val after: Profile,
    val beforeName: String = "before",
    val afterName: String = "after",
) {
    /** One operation on both sides; either side may be absent. */
    private class Pair(val label: String, val a: Profile.OperationRow?, val b: Profile.OperationRow?)

    fun render(): String = buildString {
        appendLine("=".repeat(DIFF_WIDTH))
        appendLine(row("Diff", "$beforeName -> $afterName"))
        // Three lines and not one `wall = active + paused`, so that each carries its change: the
        // header pairs had none while every row below did. Andrey, 2026-10-13.
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
        // The tree first, as in the report: the shape, then the own-time detail.
        if (before.coarseTree.isNotEmpty() || after.coarseTree.isNotEmpty()) {
            appendLine()
            appendLine("COARSE OPERATIONS - inclusive wall time, matched by path")
            appendLine(
                String.format(
                    Locale.ROOT, COARSE_DIFF_ROW,
                    "Coarse operation", "Total", "change", "Paused", "Executions"
                )
            )
            appendLine("-".repeat(DIFF_WIDTH))
            tree(after.coarseTree, before.coarseTree, 0)
            appendLine("-".repeat(DIFF_WIDTH))
        }
        appendLine()
        appendLine("COARSE AND FINE OPERATIONS - own time, matched by name, sorted by the change")
        appendLine(
            String.format(
                Locale.ROOT, FINE_DIFF_ROW,
                "Operation", "Thread-time", "change", "Thread-time per call", "Calls", "Long"
            )
        )
        appendLine("-".repeat(DIFF_WIDTH))
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
                    Locale.ROOT, FINE_DIFF_ROW,
                    p.label,
                    pair(ta, tb, hasA, hasB),
                    change(ta, tb, hasA && hasB),
                    pair(p.a?.perCallNanos() ?: Double.NaN, p.b?.perCallNanos() ?: Double.NaN, hasA, hasB),
                    calls(p.a, p.b),
                    long(before, p.a) + " -> " + long(after, p.b),
                )
            )
        }
        appendLine("-".repeat(DIFF_WIDTH))
        appendLine("=".repeat(DIFF_WIDTH))
    }

    private fun StringBuilder.tree(afterNodes: List<CoarseNode>, beforeNodes: List<CoarseNode>, depth: Int) {
        val indent = "  ".repeat(depth)
        val seen = HashSet<String>()
        for (b in afterNodes.sortedByDescending { it.spanSumNanos }) {
            val a = beforeNodes.firstOrNull { it.name == b.name }
            seen += b.name
            node(indent, b.name, a, b)
            tree(b.children, a?.children ?: emptyList(), depth + 1)
        }
        // Paths that the earlier run had and this one does not: printed under their parent, with
        // nothing on the right, so that a label that was removed is not silently gone.
        for (a in beforeNodes.filter { it.name !in seen }.sortedByDescending { it.spanSumNanos }) {
            node(indent, a.name, a, null)
            tree(emptyList(), a.children, depth + 1)
        }
    }

    private fun StringBuilder.node(indent: String, name: String, a: CoarseNode?, b: CoarseNode?) {
        val ta = a?.spanSumNanos?.toDouble() ?: Double.NaN
        val tb = b?.spanSumNanos?.toDouble() ?: Double.NaN
        appendLine(
            String.format(
                Locale.ROOT, COARSE_DIFF_ROW,
                indent + name,
                pair(ta, tb, a != null && a.count > 0, b != null && b.count > 0),
                change(ta, tb, a != null && b != null && a.count > 0 && b.count > 0),
                pair(a?.pausedNanos?.toDouble() ?: 0.0, b?.pausedNanos?.toDouble() ?: 0.0, a != null && a.pausedNanos > 0, b != null && b.pausedNanos > 0),
                counts(a?.count, b?.count),
            )
        )
    }

    /** Every operation on either side, by its printed label, fine rows and starred coarse rows alike. */
    private fun matched(): List<Pair> {
        val a = before.rows().associateBy { it.label }
        val b = after.rows().associateBy { it.label }
        return (a.keys + b.keys).distinct().map { Pair(it, a[it], b[it]) }
            .filter { (it.a?.hits ?: 0) > 0 || (it.b?.hits ?: 0) > 0 }
    }

    private fun threadTime(p: Profile, r: Profile.OperationRow?): Double =
        if (r == null) Double.NaN else r.hits * p.stepNanos

    private fun long(p: Profile, r: Profile.OperationRow?): String =
        if (r == null || !r.longShown) "-"
        else String.format(Locale.ROOT, "%,d / %s", r.stuckInstances, threadTime(r.stuckHits * p.stepNanos))

    private fun calls(a: Profile.OperationRow?, b: Profile.OperationRow?): String = counts(a?.calls, b?.calls)

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
        const val COARSE_DIFF_ROW = "%-40s | %21s %16s | %21s | %28s"
    }
}
