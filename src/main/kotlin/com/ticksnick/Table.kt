package com.ticksnick

import java.util.Locale
import kotlin.math.abs

/** What marks an own table's row as a coarse operation's own work. */
internal const val STAR = " *"

/**
 * Between two columns of a group: as wide as the bar between two groups, so that a figure never reads as the next
 * column's - `-18%   10.8ms`, not `-18% 10.8ms`.
 */
internal const val GAP = "   "

/**
 * A figure TickSnick could not measure, or measured too little of to say: never 0, which is a measured
 * nothing. A blank, by contrast, has nothing to look at: the column's default, or a column that does not
 * apply to the row.
 */
internal const val UNKNOWN = "?"

/**
 * The share of its figure an interval may be with the figure still written `±`: past it, a range. The same 20% judges a
 * change ([Judged]): its direction, by how much of its interval lies across zero, and how precisely it is known.
 */
internal const val CERTAIN = 0.20

/**
 * How far from before a change's whole interval may reach and the change still be small ([Judged]): small, it is known
 * whatever its direction - with none, it did not move. A change of up to 3% is not worth acting on, and one that may
 * reach 8% cannot be called no change.
 */
internal const val SMALL_CHANGE = 0.05

/**
 * `% parent`'s name in the block under the wall table: an operation there is over all its parents, so its share
 * is of the root's Wall, which the tree's Wall is already a part of.
 */
internal const val ROOT_SHARE = "% root"

/** What the full view shows, said in every table's notice, whichever view it is in ([lensesLine]). */
internal const val FULL_VIEW = "every lens and every interval"

/**
 * **The measures that answer one question, and lead to one kind of work**: what a column's measure is used
 * for, not where it is drawn. The basis groups, with no [lens], are in every view; the others are lenses, which
 * the brief view shows when opened and names when not ([lensesLine]).
 */
internal enum class MeasureGroup(val title: String, val lens: Lens? = null) {
    TIME("Time"), SHARE("Share"), WORK("Work"), COST("Cost"),
    PARALLELISM("Parallelism", Lens.PARALLELISM), WAITING("Waiting", Lens.WAITING), MEMORY("Memory", Lens.MEMORY),
    LATENCY("Latency", Lens.LATENCY);

    /** In every view, with no opt-out: Time, Share, Work and Cost. */
    val basis: Boolean get() = lens == null
}

/** How a column's figures read. Text only: a [Row] holds raw values, so that another writer can take them. */
internal enum class Form(val percentage: Boolean = false) {
    COUNT, PER_PARENT, WHOLE, ONE_DECIMAL, TWO_DECIMALS, TIME, BYTES, PARENT_SHARE(true), SHARE(true), WAITING(true)
}

/**
 * **One column of a table, for every report and every diff.** A figure added here is in all of them,
 * so nothing has to remember to add it twice. Every figure is the mean of the runs that have it, with its 95%
 * interval over them, written by [sureness]: a count too, which runs may differ in as in any figure, and `Max`,
 * the mean of the runs' largest - the largest of all runs would grow with how many there are.
 *
 * @param absent what a figure no run could give prints: [UNKNOWN], or blank where that means the column's
 * default, as `Parallel` under its threshold. A row the column does not apply to has no figure at all,
 * and prints blank.
 * @param zeroIsAbsent a 0 is the column's default, and prints blank.
 * @param measures the group its measure belongs to, by use: the band over the heads names it, a table keeps a
 * group's columns together, and the brief view shows the basis and the lenses opened.
 */
internal class Column(
    val name: String,
    val form: Form,
    val absent: String = UNKNOWN,
    val zeroIsAbsent: Boolean = false,
    val measures: MeasureGroup,
)

internal val EXECUTIONS = Column("Executions", Form.COUNT, measures = MeasureGroup.WORK)
internal val PER_PARENT = Column("Per parent", Form.PER_PARENT, absent = "", measures = MeasureGroup.WORK)
internal val PARALLEL = Column("Parallel", Form.ONE_DECIMAL, absent = "", measures = MeasureGroup.PARALLELISM)
internal val WALL = Column("Wall", Form.TIME, measures = MeasureGroup.TIME)
internal val PARENT_SHARE = Column("% parent", Form.PARENT_SHARE, measures = MeasureGroup.SHARE)
internal val PAUSED = Column("Paused", Form.TIME, zeroIsAbsent = true, measures = MeasureGroup.MEMORY)
internal val WALL_WAITING = Column("Waiting", Form.WAITING, zeroIsAbsent = true, measures = MeasureGroup.WAITING)
internal val MEAN = Column("Mean", Form.TIME, measures = MeasureGroup.COST)
internal val ALLOCATED = Column("Allocated", Form.BYTES, measures = MeasureGroup.MEMORY)
internal val ALLOCATED_PER_EXECUTION = Column("Per execution", Form.BYTES, measures = MeasureGroup.MEMORY)
internal val P50 = Column("p50", Form.TIME, measures = MeasureGroup.LATENCY)
internal val P90 = Column("p90", Form.TIME, measures = MeasureGroup.LATENCY)
internal val P99 = Column("p99", Form.TIME, measures = MeasureGroup.LATENCY)
internal val MAX = Column("Max", Form.TIME, measures = MeasureGroup.LATENCY)

/**
 * The wall table's columns, a group's together and the groups in [MeasureGroup]'s order: Time, Share, Work, Cost,
 * then the lenses Parallelism, Waiting, Memory, Latency.
 */
internal val WALL_COLUMNS = listOf(
    WALL, PARENT_SHARE, EXECUTIONS, PER_PARENT, MEAN, PARALLEL, WALL_WAITING, PAUSED, ALLOCATED, ALLOCATED_PER_EXECUTION,
    P50, P90, P99, MAX,
)

/** The columns only a run that counted its allocations has. */
internal val ALLOCATION_COLUMNS = setOf(ALLOCATED, ALLOCATED_PER_EXECUTION)

internal val THREAD_SHARE = Column("Thread-time%", Form.SHARE, measures = MeasureGroup.SHARE)
internal val THREAD_TIME = Column("Thread-time", Form.TIME, measures = MeasureGroup.TIME)
internal val OWN_WAITING = Column("Waiting", Form.WAITING, zeroIsAbsent = true, measures = MeasureGroup.WAITING)
internal val CALLS = Column("Calls", Form.COUNT, measures = MeasureGroup.WORK)
internal val PER_CALL = Column("Thread-time per call", Form.TIME, measures = MeasureGroup.COST)
// Blank, not unknown, where the long calls are within the noise: the ordinary case, and nothing to look at.
internal val LONG_CALLS = Column("Long calls", Form.COUNT, absent = "", measures = MeasureGroup.LATENCY)
internal val LONG_TIME = Column("Long time", Form.TIME, absent = "", measures = MeasureGroup.LATENCY)
internal val LONGEST = Column("Longest", Form.TIME, absent = "", measures = MeasureGroup.LATENCY)
internal val WALL_TIME = Column("Wall-time", Form.TIME, measures = MeasureGroup.PARALLELISM)
internal val CONCURRENCY = Column("Concurrency", Form.TWO_DECIMALS, measures = MeasureGroup.PARALLELISM)
internal val WORKERS = Column("Workers", Form.WHOLE, measures = MeasureGroup.PARALLELISM)
internal val POOL = Column("Pool", Form.WHOLE, measures = MeasureGroup.PARALLELISM)

/** Whether [this] view shows [lens]: the full one every lens, the brief one those in [lenses]. */
internal fun View.shows(lens: Lens, lenses: Set<Lens>): Boolean = this == View.FULL || lens in lenses

/** The columns [view] shows of [columns]: all of them in the full view; the basis and the [lenses] opened in the brief one. */
internal fun shownIn(view: View, columns: List<Column>, lenses: Set<Lens> = emptySet()): List<Column> =
    columns.filter { it.measures.lens.let { lens -> lens == null || view.shows(lens, lenses) } }

/**
 * **A table's notice of what its view leaves out**, right after its heading and the run lines, and in every view what
 * the full view is: in the brief one each lens of [columns], the table's full columns, not among [lenses], by name,
 * and how to open one; in the full one, how to go back. The CLI's flags; the API's names are the same.
 */
internal fun lensesLine(view: View, columns: List<Column>, lenses: Set<Lens> = emptySet()): String {
    if (view == View.FULL) return "The full view shows $FULL_VIEW; --view brief shows the basis, and --lens adds a lens to it"
    val closed = MeasureGroup.entries.filter { g -> g.lens != null && g.lens !in lenses && columns.any { it.measures == g } }
    if (closed.isEmpty()) return "All lenses shown; --view full also shows every interval"
    return "Lenses not shown: ${closed.joinToString { it.title }}${sym.dash}open one with --lens; --view full shows $FULL_VIEW"
}

/** The own table's columns, in the same order of groups as [WALL_COLUMNS]. */
internal val OWN_COLUMNS = listOf(
    THREAD_TIME, THREAD_SHARE, CALLS, PER_CALL, WALL_TIME, CONCURRENCY, WORKERS, POOL, OWN_WAITING, LONG_CALLS, LONG_TIME, LONGEST,
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
 * @param coarse in the own table, a coarse operation's own work: the rest of it is in the wall table.
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

/** Whether every run has the same figure: a whole number prints as one then, and the mean in tenths when not. */
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
    val v = f.mean
    return when (c.form) {
        Form.COUNT -> fixed(v, 0, grouped = true)
        Form.PER_PARENT -> perParentText(v).let { if (it == "1" && !own) "" else it }
        Form.WHOLE -> if (f.agreed()) v.toLong().toString() else fixed(v, 1)
        Form.ONE_DECIMAL -> fixed(v, 1)
        Form.TWO_DECIMALS -> fixed(v, 2)
        Form.TIME -> duration(v)
        Form.BYTES -> bytes(v)
        Form.PARENT_SHARE -> parentShareText(v)
        Form.SHARE -> percent(v)
        Form.WAITING -> percent(v * 100)
    }
}

/** How sure a figure is, which decides how it is written. See [sureness]; a change is judged by [Judged]. */
internal enum class Sure {
    /** No interval: one run, or runs that agree exactly. The figure alone. */
    PLAIN,
    /** The interval at most [CERTAIN] of the figure: `6.34s ±5.0%`, a share `63% ±9.2pp`. */
    CERTAIN,
    /** Loose, wider: the interval's own ends, `374..558ms`. */
    LOOSE,
    /** As wide as the figure or more, so it would reach 0: [UNKNOWN], measured too little to say. */
    UNKNOWN,
}

/**
 * **How sure a figure is: the one rule** for every figure with an interval, in a report and in a diff. [mean] and
 * [half], the 95% interval's half-width, in its unit, sign aside.
 */
internal fun sureness(mean: Double, half: Double): Sure = when {
    half.isNaN() || half == 0.0 -> Sure.PLAIN
    mean != 0.0 && half / abs(mean) <= CERTAIN -> Sure.CERTAIN
    half >= abs(mean) -> Sure.UNKNOWN
    else -> Sure.LOOSE
}

/** What a change with an interval says ([Judged]): it moved, it did not move (`=`), or it is not known (`?`). */
internal enum class Verdict { MOVED, STILL, UNKNOWN }

/**
 * **A change judged on its own interval: the one rule** for every change with an interval a diff writes, in its tables
 * and its header (docs/output.md, "The diff"). [middle] is the change, [half] its 95% interval's half-width,
 * [before] the figure it is against - all in one unit, and on the real interval, before any cut at -100%:
 * - its **direction** is certain when at most [CERTAIN] of its interval lies on the other side of zero, so zero is not
 *   a sharp line: `0.1..1.7pp` and `-0.1..1.5pp` are the same measurement, both up;
 * - it is **small** when its whole interval stays within [SMALL_CHANGE] of [before]; a [before] of 0 is never small;
 * - it is **certain** when [half] is at most [CERTAIN] of [middle]: how precisely it is known how much it moved.
 *
 * With a certain direction it moved; without one it did not move when small, and is not known when not.
 */
internal class Judged(val middle: Double, val half: Double, before: Double) {
    val low: Double = middle - half
    val high: Double = middle + half
    val direction: Boolean = !(low < 0 && high > 0) || minOf(-low, high) / (high - low) <= CERTAIN
    val small: Boolean = before > 0 && maxOf(abs(low), abs(high)) <= SMALL_CHANGE * before
    val certain: Boolean = half <= CERTAIN * abs(middle)
    val verdict: Verdict = when {
        direction -> Verdict.MOVED
        small -> Verdict.STILL
        else -> Verdict.UNKNOWN
    }

    /** Whether the brief view writes a change that moved as one value, its middle: certain, or small. */
    val oneValue: Boolean get() = certain || small

    /**
     * **This change as [view] writes it**: `?` not known, `=` did not move; one that moved as a range ([range]), or in
     * the brief view, when certain or small, as one value ([value]): how much exactly does not change a decision about
     * something under [SMALL_CHANGE] of before.
     */
    fun text(view: View, value: () -> String, range: () -> String): String = when (verdict) {
        Verdict.UNKNOWN -> UNKNOWN
        Verdict.STILL -> "="
        Verdict.MOVED -> if (view == View.BRIEF && oneValue) value() else range()
    }
}

/** Whether this figure, in its unit times [scale], is [Sure.UNKNOWN] by [sureness]: and so is any change from or to it. */
internal fun Estimate.unknown(scale: Double = 1.0): Boolean = n >= 2 && sureness(mean * scale, half * scale) == Sure.UNKNOWN

/** A share as the rule writes it, without an interval: `63%`, `5.80%`. */
private fun shareText(c: Column, v: Double): String = if (c.form == Form.PARENT_SHARE) parentShareText(v) else percent(v)

/** `+-9.2pp`, `+-12pp`. */
private fun pointsText(p: Double): String =
    sym.plusMinus + fixed(p, if (p < 9.95) 1 else 0) + "pp"

/** `59..67%`, `0.84..1.1%`: by [intervalEnd]; a share of the parent in whole percents from 10% up, as its one value is. */
private fun shareRange(c: Column, lo: Double, hi: Double): String {
    val places = if (c.form == Form.PARENT_SHARE && hi >= 10) 0 else Int.MAX_VALUE
    return intervalRange(intervalEnd(lo, places), intervalEnd(hi, places), "%")
}

/**
 * **A figure and its interval as a cell writes them**, value text and interval text, by [sureness]. [plain] is the
 * figure alone as [figureText] gives it; a share's range stops at 100%, and no range goes below 0.
 */
internal fun ruledText(c: Column, f: Estimate?, plain: String): Pair<String, String> {
    if (f == null || f.n < 2 || plain.isEmpty() || plain == UNKNOWN) return plain to ""
    val scale = if (c.form == Form.WAITING) 100.0 else 1.0
    val mean = f.mean * scale
    val half = f.half * scale
    val share = c.form.percentage
    return when (sureness(mean, half)) {
        Sure.PLAIN -> (if (share) shareText(c, mean) else plain) to ""
        Sure.CERTAIN -> if (share) shareText(c, mean) to pointsText(half) else plain to relativeText(half / abs(mean) * 100)
        Sure.UNKNOWN -> UNKNOWN to ""
        Sure.LOOSE -> {
            val lo = maxOf(0.0, mean - half)
            val hi = if (share) minOf(100.0, mean + half) else mean + half
            (if (share) shareRange(c, lo, hi) else figureRange(c.form, lo, hi)) to ""
        }
    }
}

/**
 * A loose figure's range in its column's form, by [intervalEnd]: `1.3..6.7ms`, `1.2..2.4 MB`, `2.3..4.1`; a count
 * from a thousand up grouped as its one value is, `5,100..5,400`; never more places than the column writes - none
 * for a count, `1..5`, one for a mean of whole numbers, as their figure is written.
 */
private fun figureRange(form: Form, lo: Double, hi: Double): String {
    fun end(v: Double, places: Int = Int.MAX_VALUE): String {
        val text = intervalEnd(v, places)
        return if (v >= 1000 && '.' !in text) String.format(Locale.ROOT, "%,d", text.toLong()) else text
    }
    return when (form) {
        Form.TIME -> durationRange(lo, hi)
        Form.BYTES -> bytesRange(lo, hi)
        Form.ONE_DECIMAL -> intervalRange(end(lo, 1), end(hi, 1), "")
        Form.TWO_DECIMALS -> intervalRange(end(lo, 2), end(hi, 2), "")
        Form.COUNT -> intervalRange(end(lo, 0), end(hi, 0), "")
        Form.WHOLE -> intervalRange(end(lo, 1), end(hi, 1), "")
        else -> intervalRange(end(lo), end(hi), "")
    }
}

/** A share of the parent as the tables print it, in percent. */
internal fun parentShareText(share: Double): String = fixed(share, 0) + "%"

private fun StringBuilder.slowestLines(indent: String, label: String, slowest: List<SlowExecution>) {
    slowest.forEachIndexed { i, slow ->
        appendLine(indent + "  " + (if (i == 0) label else " ".repeat(label.length)) + String.format(Locale.ROOT, "%9s  %s", duration(slow.nanos.toDouble()), slow.key))
    }
}

/** One figure and its `+-` as a cell, `6.34s ±5.0%`, `422..490ms`: right-aligned to its column's one edge by [Layout.line]. */
private fun cell(value: String, interval: String): String = if (interval.isEmpty()) value else "$value $interval"

/** The text of [c] on [r], a row's own rows having no `own` to say. */
private fun cellText(c: Column, r: Row?, own: Boolean): String =
    if (r != null && r.foldedRows > 0 && r.figures[c].let { it == null || it.n == 0 }) "" else figureText(c, r?.figures?.get(c), own)

/**
 * Where a table's columns fall in text, shared by a report and a diff so that they read the same. Each cell
 * right-aligned in its column, its figure and interval together, the name column sized to the longest name: a name
 * is what the reader searches the code for, so it is never cut.
 *
 * @param fit each column's widest text, heading included: a column is as wide as what it holds, in either view, so a
 * column whose figures have no interval takes no room for one.
 */
private class Layout(
    private val columns: List<Column>, private val heading: String, minName: Int, names: List<String>,
    private val sortedBy: Column?, fit: Map<Column, Int> = emptyMap(),
) {
    /**
     * The name column, then each column as wide as its widest cell or heading. A group narrower than its name in the band
     * widens its first column to fit it: `Parallelism` over the one `Parallel`.
     */
    private val widths: IntArray = (intArrayOf(maxOf(heading.length, minName, names.maxOfOrNull { it.length } ?: 0)) +
            columns.map { fit[it] ?: it.name.length }.toIntArray()).also { w ->
        for (at in groups().values) {
            val span = at.sumOf { w[it + 1] } + (at.size - 1) * GAP.length
            val title = columns[at.first()].measures.title
            if (span < title.length) w[at.first() + 1] += title.length - span
        }
    }

    /** Each group's columns, by their index in [columns]: next to each other, as the column lists keep them. */
    private fun groups(): Map<MeasureGroup, List<Int>> = columns.indices.groupBy { columns[it].measures }

    /** The columns a bar follows: the name, and the last of each group. */
    private val breaks: Set<Int> = buildSet {
        add(0)
        for (i in 1 until columns.size) if (columns[i].measures != columns[i - 1].measures) add(i)
    }

    private val starts = IntArray(widths.size)
    private val bars = ArrayList<Int>()

    init {
        var pos = 0
        for (i in widths.indices) {
            starts[i] = pos
            pos += widths[i]
            if (i == widths.size - 1) break
            if (i in breaks) {
                bars += pos + 1
                pos += 3
            } else pos += GAP.length
        }
    }

    /** The full width of the table. */
    val width: Int = starts.last() + widths.last()

    private val headings: List<String> = listOf(heading) + columns.map { if (it === sortedBy) it.name + " v" else it.name }

    /**
     * One line of the table, every bar drawn, an empty cell's too, so a row reads in the same columns as the heading;
     * nothing after the last bar but the last group's figures.
     */
    fun line(cells: List<String>): String = buildString {
        for (i in cells.indices) {
            if (i == 0) append(cells[0].padEnd(widths[0]))
            else {
                append(if (i - 1 in breaks) " | " else GAP)
                append(cells[i].padStart(widths[i]))
            }
        }
    }.trimEnd()

    /** The rule under a heading: dashes, with a cross where each bar comes down. */
    fun rule(): String {
        val out = CharArray(width) { '-' }
        for (b in bars) out[b] = '+'
        return String(out)
    }

    /**
     * The band of group names over a table's column heads, centred and unruled because the bars below
     * them already say where each group starts and stops: the measure groups, Time to Latency, the reading order.
     */
    private fun band(): String {
        val out = CharArray(width) { ' ' }
        for ((group, at) in groups()) {
            val title = group.title
            val from = starts[at.first() + 1]
            val span = starts[at.last() + 1] + widths[at.last() + 1] - from
            val pad = (span - title.length) / 2
            for (i in title.indices) out[from + pad + i] = title[i]
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
        if (c in shown) name + if (c === sortedBy) " v" else "" else ""
    })
}

/**
 * **A table as text**, for a report.
 *
 * @param minName the name column's narrowest width.
 * @param view the full view, every column with every interval; or the brief one, the basis columns and the [lenses]
 * opened, a certain figure without its interval, and the `slowest:` list only with Latency open.
 */
internal class TableText(
    private val table: Table, heading: String, minName: Int, private val view: View = View.FULL, private val lenses: Set<Lens> = emptySet(),
) {
    private val brief = view == View.BRIEF

    /** The columns the view shows. */
    private val columns = shownIn(view, table.columns, lenses)

    /** [c]'s figure on [r] and its interval: none in the brief view on a certain figure, which is usable alone. */
    private fun ruled(c: Column, r: Row): Pair<String, String> =
        ruledText(c, r.figures[c], cellText(c, r, r.own)).let { if (brief) it.first to "" else it }

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

    /** The columns sized to their widest figure, with its interval in the full view, or heading. */
    private val fit: Map<Column, Int> = run {
        val all = buildList {
            fun walk(r: Row) {
                add(r)
                r.children.forEach { walk(it) }
            }
            table.rows.forEach { walk(it) }
            addAll(table.shared)
        }
        columns.associateWith { c ->
            maxOf(c.name.length + if (c === table.sortedBy) 2 else 0, all.maxOfOrNull { r -> ruled(c, r).let { (v, i) -> cell(v, i).length } } ?: 0)
        }
    }

    private val layout = Layout(columns, heading, minName, names, table.sortedBy, fit)

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
        if (view.shows(Lens.LATENCY, lenses)) slowestLines("  ".repeat(depth), "slowest: ", r.slowest)
        r.children.forEach { row(it, depth + 1) }
    }

    private fun cells(name: String, r: Row): List<String> =
        listOf(name) + columns.map { c -> ruled(c, r).let { (v, i) -> cell(v, i) } }

    /** The rows under more than one caller, after the rule, under a heading of their own. */
    fun StringBuilder.shared() {
        appendLine(layout.sharedHeading(columns.filter { c -> table.shared.any { cellText(c, it, it.own).isNotEmpty() } }.toSet()))
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
 * **Two tables as text**, for a diff: the report's columns, names and forms. In the full view each row is
 * three lines, so a diff is as wide as a report and every number sits above the one it compares with.
 * Before, after, and the change: a percentage, `pp` for what is itself a percentage, `=` for no change.
 *
 * In the brief view a row is one line, of the basis columns and the [lenses] opened: Time and Share, and an open
 * lens's, as `before -> after` and the change, the sides aligned on the arrow; Work and Cost the change only. A
 * certain figure is written without its interval, and a change that moved as one value when it is certain or
 * small ([Judged.text]).
 */
internal class DiffText(
    private val before: Table, private val after: Table, private val rows: List<DiffRow>, private val shared: List<DiffRow>,
    heading: String, minName: Int, private val sortedBy: Column?, private val view: View = View.FULL,
    private val lenses: Set<Lens> = emptySet(),
) {
    private val brief = view == View.BRIEF

    /** Every column either side has, in the report's order: what the full view shows. */
    val allColumns: List<Column> = (before.columns + after.columns).distinct().sortedBy { c -> (WALL_COLUMNS + OWN_COLUMNS).indexOf(c) }

    private val columns = shownIn(view, allColumns, lenses)

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

    /** Every row the table draws, the tree's and the block's under it. */
    private val allRows: List<DiffRow> = buildList {
        fun walk(r: DiffRow) {
            add(r)
            r.children.forEach { walk(it) }
        }
        rows.forEach { walk(it) }
        addAll(shared)
    }

    /**
     * In the brief view, a column written `before -> after` and the change: Time and Share, and an open lens's, whose
     * measures are levels - `Workers 2 -> 4`, where `+2` alone would not say from where. Work and Cost the change only.
     */
    private fun twoSided(c: Column) = brief && c.measures != MeasureGroup.WORK && c.measures != MeasureGroup.COST

    /** The brief view's widest before, after and change of each column, so the sides line up on the arrow. */
    private val beforeWidth = HashMap<Column, Int>()
    private val afterWidth = HashMap<Column, Int>()
    private val changeWidth = HashMap<Column, Int>()

    /** Between the sides of a brief cell: the arrow, or as many spaces where neither side has anything. */
    private val arrow: String get() = " ${sym.arrow} "

    /** A side's cell in the full view: as [sideText] gives it, with its interval by [ruledText]. */
    private fun fullSide(c: Column, row: Row?, other: Row?, own: Boolean): String {
        val text = sideText(c, row, other, own)
        return if (text != cellText(c, row, own)) text else ruledText(c, row?.figures?.get(c), text).let { (v, i) -> cell(v, i) }
    }

    /** The columns sized to their widest cell or heading: in the full view a side with its interval, or the change. */
    private val fit: Map<Column, Int> = if (!brief) columns.associateWith { c ->
        maxOf(
            c.name.length + if (c === sortedBy) 2 else 0,
            allRows.maxOfOrNull { r -> maxOf(fullSide(c, r.before, r.after, r.own).length, fullSide(c, r.after, r.before, r.own).length, changeText(c, r).length) } ?: 0,
        )
    } else {
        for (c in columns) {
            beforeWidth[c] = allRows.maxOfOrNull { briefSide(c, it.before, it.after, it.own).length } ?: 0
            afterWidth[c] = allRows.maxOfOrNull { briefSide(c, it.after, it.before, it.own).length } ?: 0
            changeWidth[c] = allRows.maxOfOrNull { changeText(c, it).length } ?: 0
        }
        columns.associateWith { c ->
            val cell = if (twoSided(c)) beforeWidth[c]!! + arrow.length + afterWidth[c]!! + 2 + changeWidth[c]!! else changeWidth[c]!!
            maxOf(c.name.length + if (c === sortedBy) 2 else 0, cell)
        }
    }

    private val layout = Layout(
        columns, heading, minName,
        buildList {
            fun walk(r: DiffRow, depth: Int) {
                add(nameOf(r, depth))
                r.children.forEach { walk(it, depth + 1) }
            }
            rows.forEach { walk(it, 0) }
            shared.forEach { add(nameOf(it, 1, shared = true)) }
        },
        sortedBy, fit,
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

    /** A side's figure in the brief view: as [sideText] gives it, and by [ruledText] without a certain figure's interval. */
    private fun briefSide(c: Column, row: Row?, other: Row?, own: Boolean): String {
        val text = sideText(c, row, other, own)
        return if (text != cellText(c, row, own)) text else ruledText(c, row?.figures?.get(c), text).first
    }

    /**
     * A brief cell: Time, Share and an open lens's `before -> after  change`, aligned on the arrow; Work and Cost the
     * change alone. A row on one side only has no arrow - its name says `1 -> 0 runs` - but its side in its place, and
     * nor has a side left blank, its column's default; a side that is `?` keeps it.
     */
    private fun briefCell(c: Column, r: DiffRow): String {
        val change = changeText(c, r)
        if (!twoSided(c)) return change
        val b = briefSide(c, r.before, r.after, r.own)
        val a = briefSide(c, r.after, r.before, r.own)
        val between = if (r.before == null || r.after == null || b.isEmpty() || a.isEmpty()) " ".repeat(arrow.length) else arrow
        return b.padStart(beforeWidth[c]!!) + between + a.padEnd(afterWidth[c]!!) + "  " + change.padStart(changeWidth[c]!!)
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

    /**
     * Before, after, the change, what is attached to the row on each side, and the rows under it. In the brief view
     * one line, and the slowest executions attached only with Latency open.
     */
    private fun StringBuilder.row(r: DiffRow, depth: Int, shared: Boolean = false) {
        if (brief) {
            appendLine(layout.line(listOf(nameOf(r, depth, shared)) + columns.map { c -> briefCell(c, r) }))
            if (view.shows(Lens.LATENCY, lenses)) {
                slowestLines("  ".repeat(depth), "slowest before: ", r.before?.slowest.orEmpty())
                slowestLines("  ".repeat(depth), "slowest after:  ", r.after?.slowest.orEmpty())
            }
            r.children.forEach { row(it, depth + 1) }
            return
        }
        fun side(name: String, row: Row?, other: Row?) = layout.line(listOf(name) + columns.map { c -> fullSide(c, row, other, r.own) })
        appendLine(side(nameOf(r, depth, shared), r.before, r.after))
        appendLine(side("", r.after, r.before))
        appendLine(layout.line(listOf("") + columns.map { c -> changeText(c, r) }))
        val indent = "  ".repeat(depth)
        slowestLines(indent, "slowest before: ", r.before?.slowest.orEmpty())
        slowestLines(indent, "slowest after:  ", r.after?.slowest.orEmpty())
        r.children.forEach { row(it, depth + 1) }
    }

    /**
     * [c]'s change on [r] as text: blank with nothing to compare, `=` before equal to after, `?` from or to an unknown
     * figure, one signed value with no interval; else as [Judged] says, against before: a percentage of it, or for a
     * share its points against the share before.
     */
    private fun changeText(c: Column, r: DiffRow): String {
        // A side that is `?` - no run could give it - leaves the change unknown too; with no figure on a side, nothing.
        val change = r.change(c) ?: return if (r.before != null && r.after != null &&
            (cellText(c, r.before, r.own) == UNKNOWN || cellText(c, r.after, r.own) == UNKNOWN)) UNKNOWN else ""
        val b = change.before.mean
        val a = change.after.mean
        val share = c.form == Form.PARENT_SHARE || c.form == Form.SHARE || c.form == Form.WAITING
        val scale = if (c.form == Form.WAITING) 100.0 else 1.0
        // Not knowing a side is not knowing the change: a - b of an unknown a is unknown.
        if (change.before.unknown(scale) || change.after.unknown(scale)) return UNKNOWN
        if (a == b) return "="
        // From 0 no fraction describes the change: blank, as for a row with nothing to compare.
        if (!share && relativeChange(b, a).isNaN()) return ""
        val value = if (share) unjudgedChange((a - b) * scale, "pp") else signedPercent(relativeChange(b, a))
        val half = if (share) change.half * scale else change.relativeHalf * 100
        if (half.isNaN()) return value
        val v = if (share) (a - b) * scale else relativeChange(b, a) * 100
        val unit = if (share) "pp" else "%"
        // Judged on the real interval; a decrease is written stopping at -100%, a share's points never are. Runs that
        // agree on each side give an interval of 0: known exactly, one value in either view.
        return Judged(v, half, if (share) b * scale else 100.0).text(
            view, { percentChangeValue(v, unit) },
            { if (half == 0.0) percentChangeValue(v, unit) else percentChangeRange(if (share) v - half else maxOf(-100.0, v - half), v + half, unit) },
        )
    }

}
