package com.ticksnick

import java.util.Locale

/** The width of a `+-` beside a figure, `+-0.4 pp`, its separating space included. */
internal const val INTERVAL_COLUMN = 9

/** How a column's figures read. Text only: a [Row] holds raw values, so that another writer can take them. */
internal enum class Form { COUNT, PER_PARENT, WHOLE, ONE_DECIMAL, TWO_DECIMALS, TIME, BYTES, PARENT_SHARE, SHARE, RUNNABLE_WAITING }

/**
 * **One column of a table, for every report and every diff.** A figure added here is in all of them,
 * so nothing has to remember to add it twice.
 *
 * @param interval whether the figure gets a `+-` over the runs: yes for what says how much, no for
 * what says how it ran, and no for what is counted. Required, so that a new column has to answer.
 * @param width the column's narrowest width in text, the figures being right-aligned in it.
 * @param absent what a coarse or fine row prints with no figure here, and [absentOwn] what a fine row
 * under a coarse one prints.
 * @param zeroIsAbsent a 0 reads as no figure.
 * @param largest over the runs the figure is their largest, not their mean.
 */
internal class Column(
    val name: String,
    val group: String,
    val form: Form,
    val interval: Boolean,
    val width: Int,
    val absent: String = "-",
    val absentOwn: String = "",
    val zeroIsAbsent: Boolean = false,
    val largest: Boolean = false,
)

internal val EXECUTIONS = Column("Executions", "", Form.COUNT, interval = false, width = 11, absentOwn = "own")
internal val PER_PARENT = Column("Per parent", "", Form.PER_PARENT, interval = false, width = 10, absent = "")
internal val PARALLEL = Column("Parallel", "", Form.ONE_DECIMAL, interval = false, width = 8, absent = "")
internal val WALL = Column("Wall", "", Form.TIME, interval = true, width = 10, absentOwn = "-")
internal val PARENT_SHARE = Column("% parent", "", Form.PARENT_SHARE, interval = true, width = 8, absent = "")
internal val PAUSED = Column("Paused", "", Form.TIME, interval = true, width = 10, zeroIsAbsent = true)
internal val MEAN = Column("Mean", "", Form.TIME, interval = true, width = 10)
internal val ALLOCATED = Column("Allocated", "", Form.BYTES, interval = true, width = 10)
internal val P50 = Column("p50", "", Form.TIME, interval = false, width = 10)
internal val P90 = Column("p90", "", Form.TIME, interval = false, width = 10)
internal val P99 = Column("p99", "", Form.TIME, interval = false, width = 10)
internal val MAX = Column("Max", "", Form.TIME, interval = false, width = 10, largest = true)
internal val COARSE_WAITING = Column("Runnable / Waiting", "", Form.RUNNABLE_WAITING, interval = false, width = 18)

internal val COARSE_COLUMNS = listOf(
    EXECUTIONS, PER_PARENT, PARALLEL, WALL, PARENT_SHARE, PAUSED, MEAN, ALLOCATED, P50, P90, P99, MAX, COARSE_WAITING,
)

internal val THREAD_SHARE = Column("Thread-time%", "Load", Form.SHARE, interval = true, width = 14)
internal val THREAD_TIME = Column("Thread-time", "Load", Form.TIME, interval = true, width = 11)
internal val FINE_WAITING = Column("Runnable / Waiting", "Load", Form.RUNNABLE_WAITING, interval = true, width = 18)
internal val CALLS = Column("Calls", "Load", Form.COUNT, interval = false, width = 13)
internal val PER_CALL = Column("Thread-time per call", "Load", Form.TIME, interval = true, width = 20)
internal val LONG_CALLS = Column("Execs", "Long", Form.COUNT, interval = false, width = 7)
internal val LONG_TIME = Column("Time", "Long", Form.TIME, interval = false, width = 8)
internal val LONGEST = Column("Longest", "Long", Form.TIME, interval = false, width = 8, largest = true)
internal val WALL_TIME = Column("Wall-time", "Spread", Form.TIME, interval = false, width = 9)
internal val CONCURRENCY = Column("Concurrency", "Spread", Form.TWO_DECIMALS, interval = false, width = 11)
internal val WORKERS = Column("Workers", "Spread", Form.WHOLE, interval = false, width = 7)
internal val POOL = Column("Pool", "Spread", Form.WHOLE, interval = false, width = 5)
internal val HITS = Column("Hits", "Trust", Form.WHOLE, interval = false, width = 8)
internal val NOISE = Column("Noise", "Trust", Form.SHARE, interval = false, width = 6)

internal val FINE_COLUMNS = listOf(
    THREAD_SHARE, THREAD_TIME, FINE_WAITING, CALLS, PER_CALL, LONG_CALLS, LONG_TIME, LONGEST,
    WALL_TIME, CONCURRENCY, WORKERS, POOL, HITS, NOISE,
)

/** The rows of a row's own time that are not drawn: how many, their share of it in percent, their calls. */
internal class FoldedRows(val rows: Int, val share: Double, val calls: Double)

/** A fine label's total under every row it is under, beside the largest of them. Null [calls] where they were not counted. */
internal class InAll(val wallNanos: Double, val calls: Double?, val operations: Int)

/**
 * **One row of a table**: raw values, nanos and bytes and shares in percent, each with every run's
 * value behind it, never text. A run with no figure for a column has none in its [Estimate], and a
 * row with none at all prints its column's [Column.absent].
 *
 * @param present how many runs have the row.
 * @param own a fine label in a coarse row's own time.
 * @param children the rows under it, heaviest first, coarse and [own] sorted together.
 */
internal class Row(
    val name: String,
    val figures: Map<Column, Estimate>,
    val present: Int,
    val own: Boolean = false,
    val children: List<Row> = emptyList(),
    val folded: FoldedRows? = null,
    val slowest: List<SlowExecution> = emptyList(),
) {
    var inAll: InAll? = null
}

/**
 * **One table of a profile**, built once from its runs, one run being a folder of one. See [TableText],
 * which writes it; a writer of another form takes the same rows.
 *
 * @param runs how many runs are behind it.
 * @param sortedBy the column the rows are sorted by, for the mark in its heading.
 * @param shared the coarse operations under more than one caller, all callers together.
 * @param splitLeaves own rows are a leaf's samples split by label, from a run saved without own time.
 * @param contained coarse operations with no own time.
 * @param folded operations never sampled, with their calls: by [Table.rows] nothing else says so.
 */
internal class Table(
    val columns: List<Column>,
    val rows: List<Row>,
    val runs: Int,
    val sortedBy: Column?,
    val shared: List<Row> = emptyList(),
    val splitLeaves: Boolean = false,
    val contained: List<String> = emptyList(),
    val folded: List<Pair<String, Long>> = emptyList(),
)

/** Whether every run has the same figure: a counted one prints plainly then, and `~` the mean when not. */
private fun Estimate.agreed(): Boolean = values.distinct().size == 1

/** The figure of [c] as the report prints it, value only. [own]: the row is a fine label in a coarse row's own time. */
internal fun figureText(c: Column, f: Estimate?, own: Boolean): String {
    if (f == null || f.n == 0 || (c.zeroIsAbsent && f.mean == 0.0)) return if (own) c.absentOwn else c.absent
    val v = if (c.largest) f.values.max() else f.mean
    return when (c.form) {
        Form.COUNT -> if (f.agreed()) String.format(Locale.ROOT, "%,d", v.toLong()) else String.format(Locale.ROOT, "~%,.0f", v)
        Form.PER_PARENT -> if (!f.agreed()) String.format(Locale.ROOT, "~%.1f", v) else perParentText(v).let { if (it == "1" && !own) "" else it }
        Form.WHOLE -> if (f.agreed()) v.toLong().toString() else String.format(Locale.ROOT, "~%.1f", v)
        Form.ONE_DECIMAL -> String.format(Locale.ROOT, "%.1f", v)
        Form.TWO_DECIMALS -> String.format(Locale.ROOT, "%.2f", v)
        Form.TIME -> duration(v)
        Form.BYTES -> bytes(v)
        Form.PARENT_SHARE -> parentShareText(v)
        Form.SHARE -> percent(v)
        Form.RUNNABLE_WAITING -> runnableSplit(v)
    }
}

/**
 * The two halves of an operation's thread-time, as `7.2% / 92.8%`, summing to 100.
 *
 * Printed as a pair because a reader could not tell whether `waiting` was a part of the
 * thread-time beside it or an addition to it, and no unit settles that - seconds against
 * seconds is as ambiguous as a percentage. Two shares that add to the whole are not readable
 * as an addition to it.
 *
 * The left half is **runnable**, which is not *working*: it counts a preempted thread and a
 * thread stopped inside a native call, both of which the JVM reports as `RUNNABLE`. It is an
 * upper bound on the work, and the time-on-CPU block is the only thing that bounds it in turn.
 */
internal fun runnableSplit(waiting: Double): String =
    percent((1 - waiting) * 100) + " / " + percent(waiting * 100)

/** A share of the parent as the tables print it, in percent. */
internal fun parentShareText(share: Double): String = if (share < 0.5) "<1%" else String.format(Locale.ROOT, "%.0f%%", share)

/** Where each column starts, and where the bars between groups fall. [breaks]: the columns a bar follows. */
private fun layout(widths: IntArray, breaks: Set<Int>): Pair<IntArray, IntArray> {
    val starts = IntArray(widths.size)
    val bars = ArrayList<Int>()
    var pos = 0
    for (i in widths.indices) {
        starts[i] = pos
        pos += widths[i] + 1
        if (i in breaks && i < widths.size - 1) {
            bars += pos
            pos += 2
        }
    }
    return starts to bars.toIntArray()
}

/**
 * **A table as text**, for a report. Each column right-aligned at its width, the name column sized to
 * the longest name: a name is what the reader searches the code for, so it is never cut.
 *
 * @param minName the name column's narrowest width.
 * @param bars a bar between the groups, and the groups' names over them.
 */
internal class TableText(private val table: Table, heading: String, minName: Int, private val bars: Boolean) {
    /** The name as drawn in the first column, indent and mark included. */
    private fun nameOf(r: Row, depth: Int): String =
        "  ".repeat(depth) + (if (r.own) "$SPLIT_MARK " else "") + r.name + if (r.present < table.runs) " (${r.present} of ${table.runs} runs)" else ""

    /** Every name the table draws. */
    private val names: List<String> = buildList {
        fun walk(r: Row, depth: Int) {
            add(nameOf(r, depth))
            r.children.forEach { walk(it, depth + 1) }
        }
        table.rows.forEach { walk(it, 0) }
        table.shared.forEach { add("  " + it.name) }
    }

    private val widths: IntArray = intArrayOf(heading.length.coerceAtLeast(minName).coerceAtLeast(names.maxOfOrNull { it.length } ?: 0)) +
            table.columns.map { it.width }.toIntArray()

    private val headings: List<String> = listOf(heading) + table.columns.map { if (it === table.sortedBy) it.name + " v" else it.name }

    /** The columns a bar follows: the name, and the last of each group. */
    private val breaks: Set<Int> = if (!bars) emptySet() else buildSet {
        add(0)
        for (i in 1 until table.columns.size) if (table.columns[i].group != table.columns[i - 1].group) add(i)
    }

    /** The full width of the table. */
    val width: Int = layout(widths, breaks).first.last() + widths.last()

    /** How much wider than its narrowest the name column came out. */
    val nameExtra: Int = widths[0] - minName

    private fun line(cells: List<String>): String = buildString {
        for (i in cells.indices) {
            if (i == 0) append(cells[0].padEnd(widths[0]))
            else {
                append(if (i - 1 in breaks) " | " else " ")
                append(cells[i].padStart(widths[i]))
            }
        }
    }.trimEnd()

    /** The rule under a heading: dashes, with a cross where each bar comes down. */
    private fun rule(): String {
        val out = CharArray(width) { '-' }
        for (b in layout(widths, breaks).second) out[b] = '+'
        return String(out)
    }

    /**
     * The band of group names over a table's column heads, centred and unruled because the bars below
     * them already say where each group starts and stops.
     */
    private fun band(): String {
        val (starts, barsAt) = layout(widths, breaks)
        val out = CharArray(width) { ' ' }
        for ((group, columns) in table.columns.indices.groupBy { table.columns[it].group }) {
            val from = starts[columns.first() + 1]
            val span = starts[columns.last() + 1] + widths[columns.last() + 1] - from
            if (group.isEmpty() || span < group.length) continue
            val pad = (span - group.length) / 2
            for (i in group.indices) out[from + pad + i] = group[i]
        }
        for (b in barsAt) out[b] = '|'
        return String(out).trimEnd()
    }

    /** The heading, the rule under it, and every row with what is attached to it. */
    fun StringBuilder.rows() {
        if (bars) appendLine(band())
        appendLine(line(headings))
        appendLine(rule())
        for (r in table.rows) row(r, 0)
        appendLine(rule())
    }

    /** [r], what is attached to it, the rows under it, and the line for those not drawn. */
    private fun StringBuilder.row(r: Row, depth: Int) {
        val note = r.inAll?.let { all ->
            " ".repeat(NOTE_MARGIN) + duration(all.wallNanos) +
                    (all.calls?.let { String.format(Locale.ROOT, " and %,.0f calls", it) } ?: "") + " in all, under ${all.operations} operations"
        } ?: ""
        appendLine(line(cells(nameOf(r, depth), r)) + note)
        val indent = "  ".repeat(depth)
        r.slowest.forEachIndexed { i, slow ->
            appendLine(indent + "  " + (if (i == 0) "slowest: " else "         ") + String.format(Locale.ROOT, "%9s  %s", duration(slow.nanos.toDouble()), slow.key))
        }
        r.children.forEach { row(it, depth + 1) }
        r.folded?.let { f ->
            appendLine(
                "$indent  $SPLIT_MARK and ${f.rows} more, " + parentShareText(f.share) + " between them" +
                        if (f.calls > 0) String.format(Locale.ROOT, ", %,.0f calls", f.calls) else ""
            )
        }
    }

    private fun cells(name: String, r: Row): List<String> = listOf(name) + table.columns.map { figureText(it, r.figures[it], r.own) }

    /** The rows under more than one caller, after the rule. */
    fun StringBuilder.shared() {
        for (r in table.shared) appendLine(line(cells("  " + r.name, r)))
        appendLine(rule())
    }
}
