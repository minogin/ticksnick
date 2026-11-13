package com.ticksnick

import java.util.Locale
import kotlin.math.abs

/** What marks a fine table's row as a coarse operation's own work. */
internal const val STAR = " *"

/** The smallest change of a share a diff can print, in percentage points. */
internal const val MIN_SHARE_CHANGE = 0.05

/** The width of a `+-` beside a figure, its separating space included: the widest is a change's, `+-4.7pp`. */
internal const val INTERVAL_COLUMN = 8

/**
 * A figure TickSnick could not measure, or measured too little of to say: never 0, which is a measured
 * nothing. A blank, by contrast, has nothing to look at: the column's default, or a column that does not
 * apply to the row.
 */
internal const val UNKNOWN = "?"

/** The share of its figure an interval may be with the figure still written `±`: past it, a range. */
internal const val CERTAIN = 0.20

/**
 * `% parent`'s name in the block under the coarse table: an operation there is over all its parents, so its share
 * is of the root's Wall, which the tree's Wall is already a part of.
 */
internal const val ROOT_SHARE = "% root"

/** How a column's figures read. Text only: a [Row] holds raw values, so that another writer can take them. */
internal enum class Form(val percentage: Boolean = false) {
    COUNT, PER_PARENT, WHOLE, ONE_DECIMAL, TWO_DECIMALS, TIME, BYTES, PARENT_SHARE(true), SHARE(true), WAITING(true)
}

/**
 * **One column of a table, for every report and every diff.** A figure added here is in all of them,
 * so nothing has to remember to add it twice.
 *
 * @param interval whether the figure gets a `+-` over the runs: yes for what says how much, no for
 * what says how it ran, and no for what is counted. Required, so that a new column has to answer.
 * @param width the column's narrowest width in text, the figures being right-aligned in it.
 * @param absent what a figure no run could give prints: [UNKNOWN], or blank where that means the column's
 * default, as `Parallel` under its threshold. A row the column does not apply to has no figure at all,
 * and prints blank.
 * @param zeroIsAbsent a 0 is the column's default, and prints blank.
 * @param largest over the runs the figure is their largest, not their mean.
 */
internal class Column(
    val name: String,
    val group: String,
    val form: Form,
    val interval: Boolean,
    val width: Int,
    val absent: String = UNKNOWN,
    val zeroIsAbsent: Boolean = false,
    val largest: Boolean = false,
)

internal val EXECUTIONS = Column("Executions", "Load", Form.COUNT, interval = false, width = 11)
internal val PER_PARENT = Column("Per parent", "Load", Form.PER_PARENT, interval = false, width = 10, absent = "")
internal val PARALLEL = Column("Parallel", "Load", Form.ONE_DECIMAL, interval = false, width = 8, absent = "")
internal val WALL = Column("Wall", "Load", Form.TIME, interval = true, width = 10)
internal val PARENT_SHARE = Column("% parent", "Load", Form.PARENT_SHARE, interval = true, width = 8)
internal val PAUSED = Column("Paused", "Load", Form.TIME, interval = true, width = 10, zeroIsAbsent = true)
internal val COARSE_WAITING = Column("Waiting", "Load", Form.WAITING, interval = true, width = 9, zeroIsAbsent = true)
internal val MEAN = Column("Mean", "Load", Form.TIME, interval = true, width = 10)
internal val ALLOCATED = Column("Total", "Allocated", Form.BYTES, interval = false, width = 10)
internal val ALLOCATED_PER_EXECUTION = Column("Per execution", "Allocated", Form.BYTES, interval = false, width = 13)
internal val P50 = Column("p50", "Long", Form.TIME, interval = false, width = 10)
internal val P90 = Column("p90", "Long", Form.TIME, interval = false, width = 10)
internal val P99 = Column("p99", "Long", Form.TIME, interval = false, width = 10)
internal val MAX = Column("Max", "Long", Form.TIME, interval = false, width = 10, largest = true)

internal val COARSE_COLUMNS = listOf(
    EXECUTIONS, PER_PARENT, PARALLEL, WALL, PARENT_SHARE, PAUSED, COARSE_WAITING, MEAN, ALLOCATED, ALLOCATED_PER_EXECUTION,
    P50, P90, P99, MAX,
)

internal val THREAD_SHARE = Column("Thread-time%", "Load", Form.SHARE, interval = true, width = 12)
internal val THREAD_TIME = Column("Thread-time", "Load", Form.TIME, interval = true, width = 13)
internal val FINE_WAITING = Column("Waiting", "Load", Form.WAITING, interval = true, width = 9, zeroIsAbsent = true)
internal val CALLS = Column("Calls", "Load", Form.COUNT, interval = false, width = 13)
internal val PER_CALL = Column("Thread-time per call", "Load", Form.TIME, interval = true, width = 20)
// Blank, not unknown, where the long calls are within the noise: the ordinary case, and nothing to look at.
internal val LONG_CALLS = Column("Long calls", "Long", Form.COUNT, interval = false, width = 10, absent = "")
internal val LONG_TIME = Column("Time", "Long", Form.TIME, interval = false, width = 8, absent = "")
internal val LONGEST = Column("Longest", "Long", Form.TIME, interval = false, width = 8, largest = true, absent = "")
internal val WALL_TIME = Column("Wall-time", "Spread", Form.TIME, interval = false, width = 9)
internal val CONCURRENCY = Column("Concurrency", "Spread", Form.TWO_DECIMALS, interval = false, width = 11)
internal val WORKERS = Column("Workers", "Spread", Form.WHOLE, interval = false, width = 7)
internal val POOL = Column("Pool", "Spread", Form.WHOLE, interval = false, width = 5)

internal val FINE_COLUMNS = listOf(
    THREAD_TIME, THREAD_SHARE, FINE_WAITING, CALLS, PER_CALL, LONG_CALLS, LONG_TIME, LONGEST, WALL_TIME, CONCURRENCY, WORKERS, POOL,
)

/** An operation the sampler never caught, with the most calls any run made of it. */
internal class Unsampled(val name: String, val coarse: Boolean, val calls: Long)

/**
 * **One row of a table**: raw values, nanos and bytes and shares in percent, each with every run's
 * value behind it, never text. A run with no figure for a column has none in its [Estimate], and a
 * row with none at all prints its column's [Column.absent]. A column that does not apply to the row is not
 * in [figures], and prints blank.
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
    /**
     * How many rows of the tree the operation is on, each its part under that parent: over 1 for one
     * under several parents, whose whole is in [Table.shared].
     */
    var parents: Int = 0
}

/**
 * **One table of a profile**, built once from its runs, one run being a folder of one. See [TableText],
 * which writes it; a writer of another form takes the same rows.
 *
 * @param runs how many runs are behind it.
 * @param sortedBy the column the rows are sorted by, for the mark in its heading.
 * @param shared the operations under more than one parent, coarse and fine, each over all of its rows.
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

/**
 * **What a cell shows: a figure, [UNKNOWN] or nothing**, the one rule for every table of every report and diff.
 * No figure on the row: the column does not apply to it, blank. A figure no run could give: [Column.absent],
 * unknown unless that means the default. A measured default, `Paused` 0: blank. [own]: the row is a fine label
 * in a coarse row's own time.
 */
internal fun figureText(c: Column, f: Estimate?, own: Boolean): String {
    if (f == null) return ""
    if (f.n == 0) return c.absent
    if (c.zeroIsAbsent && f.mean == 0.0) return ""
    val v = if (c.largest) f.values.max() else f.mean
    return when (c.form) {
        Form.COUNT -> if (f.agreed()) String.format(Locale.ROOT, "%,d", v.toLong()) else String.format(Locale.ROOT, "~%,.0f", v)
        Form.PER_PARENT -> if (!f.agreed()) "~" + perParentText(v) else perParentText(v).let { if (it == "1" && !own) "" else it }
        Form.WHOLE -> if (f.agreed()) v.toLong().toString() else String.format(Locale.ROOT, "~%.1f", v)
        Form.ONE_DECIMAL -> String.format(Locale.ROOT, "%.1f", v)
        Form.TWO_DECIMALS -> String.format(Locale.ROOT, "%.2f", v)
        Form.TIME -> duration(v)
        // Counted, but runs of the same work never create quite the same bytes: their mean, plainly.
        Form.BYTES -> bytes(v)
        Form.PARENT_SHARE -> parentShareText(v)
        Form.SHARE -> percent(v)
        Form.WAITING -> percent(v * 100)
    }
}

/** How a figure with an interval is written, by how sure it is. See [sureness]. */
internal enum class Sure {
    /** No interval: one run, or runs that agree exactly. The figure alone. */
    PLAIN,
    /** The interval at most [CERTAIN] of the figure: `6.34s ±5.0%`, a share `63% ±9.2pp`. */
    CERTAIN,
    /** Wider: the interval's own ends, `374..558ms`. A change in a diff is always this. */
    RANGE,
    /** As wide as the figure or more, so it would reach 0: [UNKNOWN], measured too little to say. */
    UNKNOWN,
}

/**
 * **How sure a figure is: the one rule** for every figure with an interval, in a report and in a diff. [mean] and
 * [half], the 95% interval's half-width, in the figure's unit. A [change] can be negative and its interval may cross
 * zero, which is its normal "either way": it is always a range, never `±` and never unknown.
 */
internal fun sureness(mean: Double, half: Double, change: Boolean = false): Sure = when {
    half.isNaN() || half == 0.0 -> Sure.PLAIN
    change -> Sure.RANGE
    mean != 0.0 && half / abs(mean) <= CERTAIN -> Sure.CERTAIN
    half >= abs(mean) -> Sure.UNKNOWN
    else -> Sure.RANGE
}

/** Whether this figure, in its unit times [scale], is [Sure.UNKNOWN] by [sureness]: and so is any change from or to it. */
internal fun Estimate.unknown(scale: Double = 1.0): Boolean = n >= 2 && sureness(mean * scale, half * scale) == Sure.UNKNOWN

/** A share as the rule writes it, without an interval: `63%`, `5.80%`. */
private fun shareText(c: Column, v: Double): String = if (c.form == Form.PARENT_SHARE) parentShareText(v) else percent(v)

/** `+-9.2pp`, `+-12pp`. */
private fun pointsText(p: Double): String =
    if (p < 9.95) String.format(Locale.ROOT, "${sym.plusMinus}%.1fpp", p) else String.format(Locale.ROOT, "${sym.plusMinus}%.0fpp", p)

/** `59..67%`, `0.84..1.13%`: whole percents from 10% up, three digits below, where whole ones would round a range away. */
private fun shareRange(c: Column, lo: Double, hi: Double): String =
    if (c.form == Form.PARENT_SHARE && hi >= 10) intervalRange(String.format(Locale.ROOT, "%.0f", lo), String.format(Locale.ROOT, "%.0f", hi), "%")
    else intervalRange(threeDigits(lo), threeDigits(hi), "%")

/**
 * **A figure and its interval as a cell writes them**, value text and interval text, by [sureness]. [plain] is the
 * figure alone as [figureText] gives it; a share's range stops at 100%, and no range goes below 0.
 */
internal fun ruledText(c: Column, f: Estimate?, plain: String): Pair<String, String> {
    if (f == null || f.n < 2 || !c.interval || plain.isEmpty() || plain == UNKNOWN) return plain to ""
    val scale = if (c.form == Form.WAITING) 100.0 else 1.0
    val mean = f.mean * scale
    val half = f.half * scale
    val share = c.form.percentage
    return when (sureness(mean, half)) {
        Sure.PLAIN -> (if (share) shareText(c, mean) else plain) to ""
        Sure.CERTAIN -> if (share) shareText(c, mean) to pointsText(half) else plain to relativeText(half / abs(mean) * 100)
        Sure.UNKNOWN -> UNKNOWN to ""
        Sure.RANGE -> {
            val lo = maxOf(0.0, mean - half)
            val hi = if (share) minOf(100.0, mean + half) else mean + half
            (if (share) shareRange(c, lo, hi) else if (c.form == Form.TIME) durationRange(lo, hi) else plain) to ""
        }
    }
}

/** A share of the parent as the tables print it, in percent. */
internal fun parentShareText(share: Double): String = if (share < 0.5) "<1%" else String.format(Locale.ROOT, "%.0f%%", share)

/**
 * Where a table's columns fall in text, shared by a report and a diff so that they read the same. Each
 * figure right-aligned at its width, its `+-` left-aligned beside it, the name column sized to the
 * longest name: a name is what the reader searches the code for, so it is never cut.
 *
 * @param intervals whether there is room for a `+-` beside the columns that have one.
 */
private fun StringBuilder.slowestLines(indent: String, label: String, slowest: List<SlowExecution>) {
    slowest.forEachIndexed { i, slow ->
        appendLine(indent + "  " + (if (i == 0) label else " ".repeat(label.length)) + String.format(Locale.ROOT, "%9s  %s", duration(slow.nanos.toDouble()), slow.key))
    }
}

/** The text of [c] on [r], a row's own rows having no `own` to say. */
private fun cellText(c: Column, r: Row?, own: Boolean): String =
    if (r != null && r.foldedRows > 0 && r.figures[c].let { it == null || it.n == 0 }) "" else figureText(c, r?.figures?.get(c), own)

private class Layout(
    private val columns: List<Column>, private val intervals: Boolean, private val heading: String, minName: Int, names: List<String>,
    private val sortedBy: Column?,
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

    /**
     * One figure and its `+-`, sized for its column. A percentage with an interval leaves its unit to
     * the interval, `57 +-0.3%`, so it is said once; without one it keeps it, `57%`.
     */
    fun cell(c: Column, value: String, interval: String): String {
        // Every form right-aligned to the column's one edge: `6.34s ±5.0%`, `422..490ms`, a heading.
        return if (hasInterval(c)) (if (interval.isEmpty()) value else "$value $interval").padStart(c.width + INTERVAL_COLUMN) else value
    }

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

    /**
     * The heading of the block under the table, in the same places: a column is named only where the block shows
     * something in it, and `% parent` is [ROOT_SHARE].
     */
    fun sharedHeading(shown: Set<Column>): String = line(listOf(heading) + columns.map { c ->
        val name = if (c === PARENT_SHARE) ROOT_SHARE else c.name
        cell(c, if (c in shown) name + if (c === sortedBy) " v" else "" else "", "")
    })
}

/**
 * **A table as text**, for a report.
 *
 * @param minName the name column's narrowest width.
 */
internal class TableText(private val table: Table, heading: String, minName: Int) {
    /**
     * The name as drawn in the first column, indent and mark included, and every fact about the row in
     * one parenthesis: `onCentral (3 parents, 2 of 3 runs)`. In the block of [Table.shared] the
     * parents go unsaid, since its heading says them.
     */
    private fun nameOf(r: Row, depth: Int, shared: Boolean = false): String {
        val indent = "  ".repeat(depth) + if (r.own) "$SPLIT_MARK " else ""
        if (r.foldedRows > 0) return indent + "and ${r.foldedRows} more"
        val facts = listOfNotNull(
            "${r.parents} parents".takeIf { !shared && r.parents > 1 },
            "${r.present} of ${table.runs} runs".takeIf { r.present < table.runs },
        )
        return indent + r.name + (if (r.coarse) STAR else "") + if (facts.isEmpty()) "" else " (${facts.joinToString()})"
    }

    /** Every name the table draws. */
    private val names: List<String> = buildList {
        fun walk(r: Row, depth: Int) {
            add(nameOf(r, depth))
            r.children.forEach { walk(it, depth + 1) }
        }
        table.rows.forEach { walk(it, 0) }
        table.shared.forEach { add(nameOf(it, 1, shared = true)) }
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
        appendLine(layout.line(cells(nameOf(r, depth), r)))
        slowestLines("  ".repeat(depth), "slowest: ", r.slowest)
        r.children.forEach { row(it, depth + 1) }
    }

    private fun cells(name: String, r: Row): List<String> =
        listOf(name) + table.columns.map { c -> ruledText(c, r.figures[c], cellText(c, r, r.own)).let { (v, i) -> layout.cell(c, v, i) } }

    /** The rows under more than one caller, after the rule, under a heading of their own. */
    fun StringBuilder.shared() {
        appendLine(layout.sharedHeading(table.columns.filter { c -> table.shared.any { cellText(c, it, it.own).isNotEmpty() } }.toSet()))
        for (r in table.shared) appendLine(layout.line(cells(nameOf(r, 1, shared = true), r)))
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
 */
internal class DiffText(
    private val before: Table, private val after: Table, private val rows: List<DiffRow>, private val shared: List<DiffRow>,
    heading: String, minName: Int, sortedBy: Column?,
) {
    private val columns = (before.columns + after.columns).distinct().sortedBy { c -> (COARSE_COLUMNS + FINE_COLUMNS).indexOf(c) }

    /** As in a report, `->` where the sides differ: `onCentral (3 parents, 3 -> 0 runs)`. */
    private fun nameOf(r: DiffRow, depth: Int, shared: Boolean = false): String {
        val indent = "  ".repeat(depth) + if (r.own) "$SPLIT_MARK " else ""
        val b = r.before?.foldedRows ?: 0
        val a = r.after?.foldedRows ?: 0
        if (a > 0 || b > 0) return indent + if (a == b) "and $a more" else "and $b ${sym.arrow} $a more"
        val pb = r.before?.parents?.coerceAtLeast(1)
        val pa = r.after?.parents?.coerceAtLeast(1)
        val inB = r.before?.present ?: 0
        val inA = r.after?.present ?: 0
        val facts = listOfNotNull(
            when {
                shared || ((pb ?: 1) <= 1 && (pa ?: 1) <= 1) -> null
                pb == null || pa == null || pb == pa -> "${pa ?: pb} parents"
                else -> "$pb ${sym.arrow} $pa parents"
            },
            "$inB ${sym.arrow} $inA runs".takeIf { inB < before.runs || inA < after.runs },
        )
        return indent + r.name + (if (r.coarse) STAR else "") + if (facts.isEmpty()) "" else " (${facts.joinToString()})"
    }

    private val layout = Layout(
        columns, before.runs >= 2 || after.runs >= 2, heading, minName,
        buildList {
            fun walk(r: DiffRow, depth: Int) {
                add(nameOf(r, depth))
                r.children.forEach { walk(it, depth + 1) }
            }
            rows.forEach { walk(it, 0) }
            shared.forEach { add(nameOf(it, 1, shared = true)) }
        },
        sortedBy,
    )

    /** The full width of the table. */
    val width: Int get() = layout.width

    fun StringBuilder.rows() {
        with(layout) { heading() }
        for (r in rows) row(r, 0)
        appendLine(layout.rule())
    }

    /** The rows under more than one caller, after the rule, under a heading of their own. */
    fun StringBuilder.shared() {
        appendLine(layout.sharedHeading(columns.filter { c -> shared.any { r -> listOf(r.before, r.after).any { cellText(c, it, r.own).isNotEmpty() } } }.toSet()))
        for (r in shared) row(r, 1, shared = true)
        appendLine(layout.rule())
    }

    /**
     * A side's cell: its default is blank, as in a report, unless the other side has a figure there - `Per parent`
     * 3 -> 1 must print the 1, or the change reads against nothing.
     */
    private fun sideText(c: Column, row: Row?, other: Row?, own: Boolean): String {
        val text = cellText(c, row, own)
        val f = row?.figures?.get(c)
        if (text.isNotEmpty() || f == null || f.n == 0) return text
        val across = cellText(c, other, own)
        if (across.isEmpty() || across == UNKNOWN) return text
        return when (c) {
            PER_PARENT -> "1"
            PAUSED -> "0"
            else -> text
        }
    }

    /** Before, after, the change, what is attached to the row on each side, and the rows under it. */
    private fun StringBuilder.row(r: DiffRow, depth: Int, shared: Boolean = false) {
        fun side(name: String, row: Row?, other: Row?) = layout.line(
            listOf(name) + columns.map { c ->
                val text = sideText(c, row, other, r.own)
                val (v, i) = if (text != cellText(c, row, r.own)) text to "" else ruledText(c, row?.figures?.get(c), text)
                layout.cell(c, v, i)
            }
        )
        appendLine(side(nameOf(r, depth, shared), r.before, r.after))
        appendLine(side("", r.after, r.before))
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
        val share = c.form == Form.PARENT_SHARE || c.form == Form.SHARE || c.form == Form.WAITING
        val scale = if (c.form == Form.WAITING) 100.0 else 1.0
        // Not knowing a side is not knowing the change: a - b of an unknown a is unknown.
        if (c.interval && (change.before.unknown(scale) || change.after.unknown(scale))) return layout.cell(c, UNKNOWN, "")
        if (a == b || (!c.interval && figureText(c, change.before, r.own) == figureText(c, change.after, r.own))) return layout.cell(c, "=", "")
        // A share that moved by less than it prints is no change, and `+0.0 pp` is not a sign to read.
        if (share && abs((a - b) * scale) < MIN_SHARE_CHANGE) return layout.cell(c, "=", "")
        // From 0 no fraction describes the change: blank, as for a row with nothing to compare.
        if (!share && relativeChange(b, a).isNaN()) return layout.cell(c, "", "")
        val value = if (share) String.format(Locale.ROOT, "%+.1fpp", (a - b) * scale) else signedPercent(relativeChange(b, a))
        if (!c.interval) return value
        val half = if (share) change.half * scale else change.relativeHalf * 100
        if (half.isNaN()) return layout.cell(c, value, "")
        val v = if (share) (a - b) * scale else relativeChange(b, a) * 100
        val unit = if (share) "pp" else "%"
        // A change is always a range, the interval's own ends, stopping at -100% (sureness: change).
        if (sureness(v, half, change = true) == Sure.PLAIN) return layout.cell(c, value, "")
        val lo = if (share) v - half else maxOf(-100.0, v - half)
        return layout.cell(c, intervalRange(signedEnd(lo), signedEnd(v + half), unit), "")
    }

}
