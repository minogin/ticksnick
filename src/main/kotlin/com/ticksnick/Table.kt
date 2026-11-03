package com.ticksnick

import java.util.Locale
import kotlin.math.abs

/** What marks a fine table's row as a coarse operation's own work. */
internal const val STAR = " *"

/** The smallest change of a share a diff can print, in percentage points. */
internal const val MIN_SHARE_CHANGE = 0.05

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

internal val EXECUTIONS = Column("Executions", "Load", Form.COUNT, interval = false, width = 11, absentOwn = "own")
internal val PER_PARENT = Column("Per parent", "Load", Form.PER_PARENT, interval = false, width = 10, absent = "")
internal val PARALLEL = Column("Parallel", "Load", Form.ONE_DECIMAL, interval = false, width = 8, absent = "")
internal val WALL = Column("Wall", "Load", Form.TIME, interval = true, width = 10, absentOwn = "-")
internal val PARENT_SHARE = Column("% parent", "Load", Form.PARENT_SHARE, interval = true, width = 8, absent = "")
internal val PAUSED = Column("Paused", "Load", Form.TIME, interval = true, width = 10, zeroIsAbsent = true)
internal val COARSE_WAITING = Column("Runnable / Waiting", "Load", Form.RUNNABLE_WAITING, interval = true, width = 18)
internal val MEAN = Column("Mean", "Load", Form.TIME, interval = true, width = 10)
internal val ALLOCATED = Column("Total", "Allocated", Form.BYTES, interval = true, width = 10)
internal val ALLOCATED_PER_EXECUTION = Column("Per execution", "Allocated", Form.BYTES, interval = true, width = 13)
internal val P50 = Column("p50", "Long", Form.TIME, interval = false, width = 10)
internal val P90 = Column("p90", "Long", Form.TIME, interval = false, width = 10)
internal val P99 = Column("p99", "Long", Form.TIME, interval = false, width = 10)
internal val MAX = Column("Max", "Long", Form.TIME, interval = false, width = 10, largest = true)

internal val COARSE_COLUMNS = listOf(
    EXECUTIONS, PER_PARENT, PARALLEL, WALL, PARENT_SHARE, PAUSED, COARSE_WAITING, MEAN, ALLOCATED, ALLOCATED_PER_EXECUTION,
    P50, P90, P99, MAX,
)

internal val THREAD_SHARE = Column("Thread-time%", "Load", Form.SHARE, interval = true, width = 14)
internal val THREAD_TIME = Column("Thread-time", "Load", Form.TIME, interval = true, width = 11)
internal val FINE_WAITING = Column("Runnable / Waiting", "Load", Form.RUNNABLE_WAITING, interval = true, width = 18)
internal val CALLS = Column("Calls", "Load", Form.COUNT, interval = false, width = 13)
internal val PER_CALL = Column("Thread-time per call", "Load", Form.TIME, interval = true, width = 20)
internal val LONG_CALLS = Column("Long calls", "Long", Form.COUNT, interval = false, width = 10)
internal val LONG_TIME = Column("Time", "Long", Form.TIME, interval = false, width = 8)
internal val LONGEST = Column("Longest", "Long", Form.TIME, interval = false, width = 8, largest = true)
internal val WALL_TIME = Column("Wall-time", "Spread", Form.TIME, interval = false, width = 9)
internal val CONCURRENCY = Column("Concurrency", "Spread", Form.TWO_DECIMALS, interval = false, width = 11)
internal val WORKERS = Column("Workers", "Spread", Form.WHOLE, interval = false, width = 7)
internal val POOL = Column("Pool", "Spread", Form.WHOLE, interval = false, width = 5)

internal val FINE_COLUMNS = listOf(
    THREAD_SHARE, THREAD_TIME, FINE_WAITING, CALLS, PER_CALL, LONG_CALLS, LONG_TIME, LONGEST, WALL_TIME, CONCURRENCY, WORKERS, POOL,
)

/** An operation the sampler never caught, with the most calls any run made of it. */
internal class Unsampled(val name: String, val coarse: Boolean, val calls: Long)

/** A fine label's total under every row it is under, beside the largest of them. Null [calls] where they were not counted. */
internal class InAll(val wallNanos: Double, val calls: Double?, val operations: Int)

/**
 * **One row of a table**: raw values, nanos and bytes and shares in percent, each with every run's
 * value behind it, never text. A run with no figure for a column has none in its [Estimate], and a
 * row with none at all prints its column's [Column.absent].
 *
 * @param present how many runs have the row.
 * @param own a fine label in a coarse row's own time.
 * @param coarse in the fine table, a coarse operation's own work: the rest of it is in the coarse table.
 * @param children the rows under it, heaviest first, coarse and [own] sorted together.
 * @param foldedRows how many of a row's own rows this one stands for, over 0 for the row of those not drawn.
 */
internal class Row(
    val name: String,
    val figures: Map<Column, Estimate>,
    val present: Int,
    val own: Boolean = false,
    val coarse: Boolean = false,
    val children: List<Row> = emptyList(),
    val foldedRows: Int = 0,
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
 * @param folded operations never sampled.
 */
internal class Table(
    val columns: List<Column>,
    val rows: List<Row>,
    val runs: Int,
    val sortedBy: Column?,
    val shared: List<Row> = emptyList(),
    val splitLeaves: Boolean = false,
    val contained: List<String> = emptyList(),
    val folded: List<Unsampled> = emptyList(),
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

/** The `+-` of a figure: `pp` for what is itself a percentage, whose relative interval would read two ways. */
internal fun intervalText(c: Column, f: Estimate?): String = when {
    f == null || f.n == 0 || (c.zeroIsAbsent && f.mean == 0.0) -> ""
    c.form == Form.RUNNABLE_WAITING -> plusMinusPoints(Estimate(f.values.map { it * 100 }))
    c.form == Form.PARENT_SHARE || c.form == Form.SHARE -> plusMinusPoints(f)
    else -> plusMinus(f)
}

/**
 * Where a table's columns fall in text, shared by a report and a diff so that they read the same. Each
 * figure right-aligned at its width, its `+-` left-aligned beside it, the name column sized to the
 * longest name: a name is what the reader searches the code for, so it is never cut.
 *
 * @param intervals whether there is room for a `+-` beside the columns that have one.
 */
private fun inAllText(r: Row?): String = r?.inAll?.let { all ->
    " ".repeat(NOTE_MARGIN) + duration(all.wallNanos) +
            (all.calls?.let { String.format(Locale.ROOT, " and %,.0f calls", it) } ?: "") + " in all, under ${all.operations} operations"
} ?: ""

private fun StringBuilder.slowestLines(indent: String, label: String, slowest: List<SlowExecution>) {
    slowest.forEachIndexed { i, slow ->
        appendLine(indent + "  " + (if (i == 0) label else " ".repeat(label.length)) + String.format(Locale.ROOT, "%9s  %s", duration(slow.nanos.toDouble()), slow.key))
    }
}

/** The text of [c] on [r], a row's own rows having no `own` to say. */
private fun cellText(c: Column, r: Row?, own: Boolean): String =
    if (r != null && r.foldedRows > 0 && r.figures[c].let { it == null || it.n == 0 }) "" else figureText(c, r?.figures?.get(c), own)

private class Layout(
    private val columns: List<Column>, private val intervals: Boolean, heading: String, minName: Int, names: List<String>, sortedBy: Column?,
) {
    private val widths: IntArray = intArrayOf(maxOf(heading.length, minName, names.maxOfOrNull { it.length } ?: 0)) +
            columns.map { it.width + if (hasInterval(it)) INTERVAL_COLUMN else 0 }.toIntArray()

    /** The columns a bar follows: the name, and the last of each group. */
    private val breaks: Set<Int> = buildSet {
        add(0)
        for (i in 1 until columns.size) if (columns[i].group != columns[i - 1].group) add(i)
    }

    private val starts = IntArray(widths.size)
    private val bars = ArrayList<Int>()

    init {
        var pos = 0
        for (i in widths.indices) {
            starts[i] = pos
            pos += widths[i] + 1
            if (i in breaks && i < widths.size - 1) {
                bars += pos
                pos += 2
            }
        }
    }

    private fun hasInterval(c: Column) = intervals && c.interval

    /** The full width of the table. */
    val width: Int = starts.last() + widths.last()

    private val headings: List<String> = listOf(heading) + columns.map { cell(it, if (it === sortedBy) it.name + " v" else it.name, "") }

    /** One figure and its `+-`, sized for its column. */
    fun cell(c: Column, value: String, interval: String): String =
        if (hasInterval(c)) value.padStart(c.width) + " " + interval.padEnd(INTERVAL_COLUMN - 1) else value

    /** One line of the table, with nothing after its last figure: no bar closes an empty group. */
    fun line(cells: List<String>): String {
        var out = buildString {
            for (i in cells.indices) {
                if (i == 0) append(cells[0].padEnd(widths[0]))
                else {
                    append(if (i - 1 in breaks) " | " else " ")
                    append(cells[i].padStart(widths[i]))
                }
            }
        }.trimEnd()
        while (out.endsWith("|")) out = out.dropLast(1).trimEnd()
        return out
    }

    /** The rule under a heading: dashes, with a cross where each bar comes down. */
    fun rule(): String {
        val out = CharArray(width) { '-' }
        for (b in bars) out[b] = '+'
        return String(out)
    }

    /**
     * The band of group names over a table's column heads, centred and unruled because the bars below
     * them already say where each group starts and stops. The groups are the reading order.
     */
    private fun band(): String {
        val out = CharArray(width) { ' ' }
        for ((group, at) in columns.indices.groupBy { columns[it].group }) {
            val from = starts[at.first() + 1]
            val span = starts[at.last() + 1] + widths[at.last() + 1] - from
            if (group.isEmpty() || span < group.length) continue
            val pad = (span - group.length) / 2
            for (i in group.indices) out[from + pad + i] = group[i]
        }
        for (b in bars) out[b] = '|'
        return String(out).trimEnd()
    }

    /** The band, the column heads and the rule under them. */
    fun StringBuilder.heading() {
        appendLine(band())
        appendLine(line(headings))
        appendLine(rule())
    }
}

/**
 * **A table as text**, for a report.
 *
 * @param minName the name column's narrowest width.
 */
internal class TableText(private val table: Table, heading: String, minName: Int) {
    /** The name as drawn in the first column, indent and mark included. */
    private fun nameOf(r: Row, depth: Int): String =
        "  ".repeat(depth) + (if (r.own) "$SPLIT_MARK " else "") + (if (r.foldedRows > 0) "and ${r.foldedRows} more" else r.name) + (if (r.coarse) STAR else "") +
                if (r.foldedRows == 0 && r.present < table.runs) " (${r.present} of ${table.runs} runs)" else ""

    /** Every name the table draws. */
    private val names: List<String> = buildList {
        fun walk(r: Row, depth: Int) {
            add(nameOf(r, depth))
            r.children.forEach { walk(it, depth + 1) }
        }
        table.rows.forEach { walk(it, 0) }
        table.shared.forEach { add("  " + it.name) }
    }

    private val layout = Layout(table.columns, table.runs >= 2, heading, minName, names, table.sortedBy)

    /** The full width of the table. */
    val width: Int get() = layout.width

    /** The heading, the rule under it, and every row with what is attached to it. */
    fun StringBuilder.rows() {
        with(layout) { heading() }
        for (r in table.rows) row(r, 0)
        appendLine(layout.rule())
    }

    /** [r], what is attached to it, and the rows under it. */
    private fun StringBuilder.row(r: Row, depth: Int) {
        appendLine(layout.line(cells(nameOf(r, depth), r)) + inAllText(r))
        slowestLines("  ".repeat(depth), "slowest: ", r.slowest)
        r.children.forEach { row(it, depth + 1) }
    }

    private fun cells(name: String, r: Row): List<String> =
        listOf(name) + table.columns.map { layout.cell(it, cellText(it, r, r.own), intervalText(it, r.figures[it])) }

    /** The rows under more than one caller, after the rule. */
    fun StringBuilder.shared() {
        for (r in table.shared) appendLine(layout.line(cells("  " + r.name, r)))
        appendLine(layout.rule())
    }
}

/** One row of a diff: the row on each side, null on a side without it, and the rows under it. */
internal class DiffRow(val before: Row?, val after: Row?, val children: List<DiffRow>) {
    val name: String get() = (after ?: before)!!.name
    val own: Boolean get() = (after ?: before)!!.own
    val coarse: Boolean get() = (after ?: before)!!.coarse

    /** [c] on both sides, when both sides have it. */
    fun change(c: Column): Change? {
        val b = before?.figures?.get(c)?.takeIf { it.n > 0 } ?: return null
        val a = after?.figures?.get(c)?.takeIf { it.n > 0 } ?: return null
        return Change(b, a)
    }
}

/** The rows of two tables paired by name: after's order, then the rows only before had, so a removed row is not silently gone. */
internal fun paired(before: List<Row>, after: List<Row>): List<DiffRow> {
    fun key(r: Row) = Triple(r.name, r.own, r.coarse)
    val before1 = before.associateBy { key(it) }
    val seen = after.map { key(it) }.toSet()
    return after.map { a -> before1[key(a)].let { b -> DiffRow(b, a, paired(b?.children.orEmpty(), a.children)) } } +
            before.filter { key(it) !in seen }.map { DiffRow(it, null, paired(it.children, emptyList())) }
}

/**
 * **Two tables as text**, for a diff: the report's columns, names and forms, each row three lines, so a
 * diff is as wide as a report and every number sits above the one it compares with. Before, after, and
 * the change: a percentage, `pp` for what is itself a percentage, `=` for no change.
 *
 * @param noise whether a change could be noise. See [Change.isNoise].
 */
internal class DiffText(
    private val before: Table, private val after: Table, private val rows: List<DiffRow>, private val shared: List<DiffRow>,
    heading: String, minName: Int, sortedBy: Column?, private val noise: (Change) -> Boolean,
) {
    private val columns = (before.columns + after.columns).distinct().sortedBy { c -> (COARSE_COLUMNS + FINE_COLUMNS).indexOf(c) }

    private fun nameOf(r: DiffRow, depth: Int): String {
        val indent = "  ".repeat(depth) + if (r.own) "$SPLIT_MARK " else ""
        val b = r.before?.foldedRows ?: 0
        val a = r.after?.foldedRows ?: 0
        if (a > 0 || b > 0) return indent + if (a == b) "and $a more" else "and $b -> $a more"
        val inB = r.before?.present ?: 0
        val inA = r.after?.present ?: 0
        return indent + r.name + (if (r.coarse) STAR else "") + if (inB < before.runs || inA < after.runs) " ($inB -> $inA runs)" else ""
    }

    private val layout = Layout(
        columns, before.runs >= 2 || after.runs >= 2, heading, minName,
        buildList {
            fun walk(r: DiffRow, depth: Int) {
                add(nameOf(r, depth))
                r.children.forEach { walk(it, depth + 1) }
            }
            rows.forEach { walk(it, 0) }
            shared.forEach { walk(it, 1) }
        },
        sortedBy,
    )

    /** The full width of the table. */
    val width: Int get() = layout.width

    /** Whether any change printed so far could be noise, for the note under the table. */
    var anyNoise = false
        private set

    fun StringBuilder.rows() {
        with(layout) { heading() }
        for (r in rows) row(r, 0)
        appendLine(layout.rule())
    }

    /** The rows under more than one caller, after the rule. */
    fun StringBuilder.shared() {
        for (r in shared) row(r, 1)
        appendLine(layout.rule())
    }

    /** Before, after, the change, what is attached to the row on each side, and the rows under it. */
    private fun StringBuilder.row(r: DiffRow, depth: Int) {
        fun side(name: String, row: Row?) = layout.line(
            listOf(name) + columns.map { c -> layout.cell(c, cellText(c, row, r.own), intervalText(c, row?.figures?.get(c))) }
        ) + inAllText(row)
        appendLine(side(nameOf(r, depth), r.before))
        appendLine(side("", r.after))
        appendLine(layout.line(listOf("") + columns.map { c -> changeCell(c, r) }))
        val indent = "  ".repeat(depth)
        slowestLines(indent, "slowest before: ", r.before?.slowest.orEmpty())
        slowestLines(indent, "slowest after:  ", r.after?.slowest.orEmpty())
        r.children.forEach { row(it, depth + 1) }
    }

    private fun changeCell(c: Column, r: DiffRow): String {
        val change = r.change(c) ?: return layout.cell(c, "", "")
        val b = if (c.largest) change.before.values.max() else change.before.mean
        val a = if (c.largest) change.after.values.max() else change.after.mean
        if (a == b || (!c.interval && figureText(c, change.before, r.own) == figureText(c, change.after, r.own))) return layout.cell(c, "=", "")
        val share = c.form == Form.PARENT_SHARE || c.form == Form.SHARE || c.form == Form.RUNNABLE_WAITING
        val scale = if (c.form == Form.RUNNABLE_WAITING) 100.0 else 1.0
        // A share that moved by less than it prints is no change, and `+0.0 pp` is not a sign to read.
        if (share && abs((a - b) * scale) < MIN_SHARE_CHANGE) return layout.cell(c, "=", "")
        val value = if (share) String.format(Locale.ROOT, "%+.1f pp", (a - b) * scale) else signedPercent((a - b) / b)
        if (!c.interval) return value
        val isNoise = noise(change)
        anyNoise = anyNoise || isNoise
        val half = if (share) change.half * scale else change.relativeHalf * 100
        return layout.cell(c, value + if (isNoise) "?" else "", pointsHalf(half))
    }
}
