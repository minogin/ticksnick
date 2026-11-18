package com.ticksnick

/** The comparisons a condition may make, longest first so that `>=` is not read as `>`. */
private val OPERATORS = listOf(">=", "<=", ">", "<")

/** A condition as written: a key or `change(key)`, a comparison, a number and its unit. */
private val CONDITION = Regex("""^\s*(?:change\(\s*([A-Za-z]+)\s*\)|([A-Za-z]+))\s*(>=|<=|>|<)\s*(-?[0-9]+(?:\.[0-9]+)?)\s*([A-Za-z%]*)\s*$""")

/** The keys a filter knows, each a measure's column. One line for each key added. */
private val KEYS: Map<String, Column> = mapOf(
    "wall" to WALL,
)

/** The units of a time, in nanoseconds. */
private val TIME_UNITS = mapOf("ns" to 1.0, "us" to 1e3, "ms" to 1e6, "s" to 1e9)

/**
 * **Which rows a report shows**: conditions you write, each naming a measure by its key and comparing it with a
 * bound. Off by default, [NONE]: every op is shown. Every condition must hold.
 *
 * `Filter.of("wall >= 100ms")` shows the ops whose Wall is 100 ms or more; `Filter.of("change(wall) <= -5%")`, in a
 * diff, those whose Wall fell by 5% or more. A row is shown when it may pass: when either end of its 95% interval
 * passes, and in a diff when either side does. A parent op stays while a child op of it is shown, and every row
 * left out is named where it would have been, so nothing goes unseen.
 *
 * The keys so far: `wall`, a time (`ns`, `us`, `ms`, `s`), and `change(wall)`, in % of before.
 */
class Filter private constructor(internal val conditions: List<Condition>) {

    /** The conditions as written, joined with `and`. */
    override fun toString(): String = conditions.joinToString(" and ") { it.text }

    companion object {
        /** No filter: every op is shown. */
        @JvmField
        val NONE: Filter = Filter(emptyList())

        /**
         * A filter of [conditions], every one of which must hold. Throws [IllegalArgumentException] on a condition it
         * cannot read, with what it expected.
         */
        @JvmStatic
        fun of(vararg conditions: String): Filter = Filter(conditions.map { parseCondition(it) })
    }
}

/**
 * One condition of a [Filter]: [column]'s figure, or with [change] its change in a diff, compared by [op] with
 * [bound] - nanoseconds for a time, percent for a change.
 */
internal class Condition(val text: String, val column: Column, val change: Boolean, val op: String, val bound: Double) {

    /** Whether an interval `low..high` may pass: `>=` and `>` on its upper end, `<=` and `<` on its lower one. */
    fun mayPass(low: Double, high: Double): Boolean = when (op) {
        ">=" -> high >= bound
        ">" -> high > bound
        "<=" -> low <= bound
        else -> low < bound
    }

    /** Whether [e] may pass; a figure that is not there does not. One run is the interval of its one value. */
    fun passes(e: Estimate?): Boolean {
        if (e == null || e.n == 0 || e.mean.isNaN()) return false
        val half = if (e.half.isNaN()) 0.0 else e.half
        return mayPass(e.mean - half, e.mean + half)
    }

    /** Whether the change [c] may pass, in percent of before; none, or from 0, does not. */
    fun passes(c: Change?): Boolean {
        if (c == null || c.relative.isNaN()) return false
        val middle = c.relative * 100
        val half = if (c.relativeHalf.isNaN()) 0.0 else c.relativeHalf * 100
        return mayPass(middle - half, middle + half)
    }
}

/** [text] as a [Condition], or why it is not one. */
internal fun parseCondition(text: String): Condition {
    val m = CONDITION.matchEntire(text) ?: throw IllegalArgumentException(
        "'$text' is not a filter condition: write a key, a comparison and a bound, such as `wall >= 100ms` or `change(wall) <= -5%`"
    )
    val changeKey = m.groupValues[1]
    val key = changeKey.ifEmpty { m.groupValues[2] }
    val column = KEYS[key] ?: throw IllegalArgumentException(
        "'$key' is not a key a filter knows: so far `wall`, and `change(wall)` in a diff"
    )
    val op = m.groupValues[3]
    check(op in OPERATORS)
    val number = m.groupValues[4].toDouble()
    val unit = m.groupValues[5]
    val bound = if (changeKey.isNotEmpty()) {
        if (unit != "%") throw IllegalArgumentException("`change($key)` is in % of before: give its bound in %, such as `change($key) <= -5%`")
        number
    } else {
        number * (TIME_UNITS[unit] ?: throw IllegalArgumentException("`$key` is a time: give its bound in ns, us, ms or s, such as `$key >= 100ms`"))
    }
    return Condition(text.trim(), column, changeKey.isNotEmpty(), op, bound)
}

/** A row's name as a list of what is not shown gives it: a coarse op with its mark, and how many rows went with it. */
private fun hiddenName(name: String, coarse: Boolean, below: Int): String =
    name + (if (coarse) STAR else "") + if (below > 0) " (with ${Run.plural(below.toLong(), "row")} below it)" else ""

/** How many rows are under [r], at every depth. */
private fun below(r: Row): Int = r.children.sumOf { 1 + below(it) }

private fun below(r: DiffRow): Int = r.children.sumOf { 1 + below(it) }

/**
 * **A filter's work on one table**: the conditions that apply to it, the rows it shows, and what it left out. A
 * condition applies when the table has its measure, and a change only in a diff.
 */
internal class Filtered<R>(
    val applied: List<Condition>,
    val notApplied: List<Condition>,
    val rows: List<R>,
    val notShown: List<String>,
    val shared: List<R>,
    val sharedNotShown: List<String>,
    val hidden: Int,
    val context: Boolean,
)

/** [rows] and [shared] of a report's table with [columns], as [filter] leaves them. */
internal fun filterRows(filter: Filter, columns: List<Column>, rows: List<Row>, shared: List<Row>): Filtered<Row> {
    val applied = filter.conditions.filter { !it.change && it.column in columns }
    val notApplied = filter.conditions - applied.toSet()
    if (applied.isEmpty()) return Filtered(applied, notApplied, rows, emptyList(), shared, emptyList(), 0, false)
    var hidden = 0
    var context = false
    fun passes(r: Row) = applied.all { it.passes(r.figures[it.column]) }
    fun names(dropped: List<Row>) = dropped.map { hiddenName(it.name, it.coarse, below(it)) }.also { hidden += it.size }
    fun prune(r: Row): Row? {
        val kept = r.children.associateWith { prune(it) }
        val shown = kept.values.filterNotNull()
        val pass = passes(r)
        if (!pass && shown.isEmpty()) return null
        if (!pass) context = true
        return r.with(shown, names(r.children.filter { kept[it] == null }))
    }
    val top = rows.associateWith { prune(it) }
    val topNotShown = names(rows.filter { top[it] == null })
    val sharedShown = shared.filter { passes(it) }
    return Filtered(applied, notApplied, top.values.filterNotNull(), topNotShown, sharedShown, names(shared - sharedShown.toSet()), hidden, context)
}

/** [rows] and [shared] of a diff's table with [columns], as [filter] leaves them: a row passes when either side may. */
internal fun filterDiffRows(filter: Filter, columns: List<Column>, rows: List<DiffRow>, shared: List<DiffRow>): Filtered<DiffRow> {
    val applied = filter.conditions.filter { it.column in columns }
    val notApplied = filter.conditions - applied.toSet()
    if (applied.isEmpty()) return Filtered(applied, notApplied, rows, emptyList(), shared, emptyList(), 0, false)
    var hidden = 0
    var context = false
    fun passes(r: DiffRow) = applied.all { c ->
        if (c.change) c.passes(r.change(c.column))
        else c.passes(r.before?.figures?.get(c.column)) || c.passes(r.after?.figures?.get(c.column))
    }
    fun names(dropped: List<DiffRow>) = dropped.map { hiddenName(it.name, it.coarse, below(it)) }.also { hidden += it.size }
    fun prune(r: DiffRow): DiffRow? {
        val kept = r.children.associateWith { prune(it) }
        val shown = kept.values.filterNotNull()
        val pass = passes(r)
        if (!pass && shown.isEmpty()) return null
        if (!pass) context = true
        return DiffRow(r.before, r.after, shown, names(r.children.filter { kept[it] == null }))
    }
    val top = rows.associateWith { prune(it) }
    val topNotShown = names(rows.filter { top[it] == null })
    val sharedShown = shared.filter { passes(it) }
    return Filtered(applied, notApplied, top.values.filterNotNull(), topNotShown, sharedShown, names(shared - sharedShown.toSet()), hidden, context)
}

/**
 * **The filter's line under a table's heading**, none without a filter: the conditions applied as written and how
 * many rows they left out; and those that do not apply here, with why.
 */
internal fun filterLine(f: Filtered<*>, diff: Boolean): String? {
    if (f.applied.isEmpty() && f.notApplied.isEmpty()) return null
    val parts = buildList {
        if (f.applied.isNotEmpty()) {
            val conditions = f.applied.joinToString(" and ") { it.text }
            add(
                if (f.hidden == 0) "Filter: $conditions. Every row passes"
                else if (f.hidden == 1) "Filter: $conditions. 1 row is not shown; it is named under its parent op"
                else "Filter: $conditions. ${Run.plural(f.hidden.toLong(), "row")} are not shown; each is named under its parent op"
            )
        }
        for (c in f.notApplied) add(
            "Filter: ${c.text} is not applied here: " +
                    if (c.change && !diff) "a profile has no change" else "this table has no ${c.column.name}"
        )
    }
    return parts.joinToString("; ")
}

/** The note under a filtered table when it keeps a parent op that does not pass. */
internal const val FILTER_CONTEXT_NOTE = "  A parent op that does not pass the filter is shown while one of its child ops does"

/** The line naming what a filter left out under one parent op, at [depth]: the rows of the next depth. */
internal fun notShownLine(depth: Int, names: List<String>): String = "  ".repeat(depth) + "not shown: " + names.joinToString(", ")
