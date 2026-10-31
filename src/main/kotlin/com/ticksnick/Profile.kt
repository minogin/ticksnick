package com.ticksnick

import java.nio.file.Path
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Student's t for a two-sided 95% interval, by degrees of freedom: index 1 to 30. Past 30, the
 * Cornish-Fisher expansion around [Z95] (see [t95]); 1.96 itself was 4% too narrow at 31. Three runs,
 * two degrees of freedom, read 4.30: so few runs know their own spread badly, and the interval pays for it.
 */
private val T95 = doubleArrayOf(
    Double.NaN, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
    2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
    2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
)
/**
 * The chance of a t this far from 0 or further, on either side, with [df] degrees of freedom: Student's
 * t by its closed form for whole degrees of freedom (Abramowitz and Stegun 26.7.3, 26.7.4).
 */
internal fun studentTail(t: Double, df: Int): Double {
    val theta = atan(abs(t) / sqrt(df.toDouble()))
    val cos2 = cos(theta) * cos(theta)
    var term = if (df % 2 == 1) cos(theta) else 1.0
    var sum = if (df == 1) 0.0 else term
    for (k in (if (df % 2 == 1) 3 else 2)..(df - 2) step 2) {
        term *= (k - 1.0) / k * cos2
        sum += term
    }
    val inside = if (df % 2 == 1) 2 / PI * (theta + sin(theta) * sum) else sin(theta) * sum
    return (1 - inside).coerceAtLeast(0.0)
}

/** The normal distribution's two-sided 95% point: where t goes as the degrees of freedom grow. */
private const val Z95 = 1.959964

/** The most executions of a run's root row listed one by one beside it: its batches, not its requests. */
internal const val RUN_EXECUTIONS_LISTED = 10

/**
 * How far apart the runs' mean clocks may be, as a fraction of the slowest, before the report says
 * the interval includes a clock change: 205% against 159% of base is 29%, and a run's time follows
 * its clock.
 */
internal const val RUNS_CLOCK_SPREAD = 0.10

/** The `CAREFUL` line of a folder with one run in it. */
internal const val ONE_RUN_CAREFUL = "times: 1 run of '%s', no interval - the next JVM can run faster or slower as a whole; " +
        "shares of all but the smallest rows hold"

/** How many warnings the `CAREFUL` section prints before it counts the rest instead of listing them. */
internal const val MAX_CAREFUL = 10

/** The name column's narrowest width, in the coarse and in the fine table. */
/** The name that pairs the row of a row's own rows not drawn, on the two sides of a diff: how many it stands for can differ. */
internal const val FOLDED_ROWS = "and more"

internal const val COARSE_NAME_WIDTH = 30
internal const val FINE_NAME_WIDTH = 26

/**
 * **Every warning of every report, as a section of its own right under its first line**: before any
 * number, because a reader stops at the first one, and a blank line either side, because a
 * warning among the figures goes unseen: a diff that reads -56% at clocks of 99% and 197% must say
 * so before the -56%.
 *
 * A warning with line breaks continues under its own text. Capped at [MAX_CAREFUL], the rest
 * counted: a section that runs to fifty is one a reader learns to skip. The caller orders them by
 * what each costs the reader, worst first. [Warnings.always] come after the cap and are not cut.
 */
internal fun StringBuilder.carefulBlock(warnings: Warnings) {
    if (warnings.ranked.isEmpty() && warnings.always.isEmpty()) return
    fun print(w: String) {
        val lines = w.lines()
        appendLine(row("CAREFUL", lines.first().trim()))
        for (line in lines.drop(1)) appendLine(row("", line.trim()))
    }
    appendLine()
    warnings.ranked.take(MAX_CAREFUL).forEach(::print)
    if (warnings.ranked.size > MAX_CAREFUL) appendLine(row("", "... and ${warnings.ranked.size - MAX_CAREFUL} more"))
    warnings.always.forEach(::print)
    appendLine()
}

/**
 * What a `CAREFUL` section prints: [ranked] worst first, up to its cap, and [always] after them.
 * A profile whose runs are mixed is one line in [always]: last, so a mix that was meant pushes out
 * nothing, and past the cap, so a long section cannot hide that a profile is not one.
 */
internal class Warnings(val ranked: List<String> = emptyList(), val always: List<String> = emptyList()) {
    operator fun plus(other: Warnings) = Warnings(ranked + other.ranked, always + other.always)
}

/** Student's t for a 95% interval with [df] degrees of freedom. */
internal fun t95(df: Int): Double {
    if (df < T95.size) return T95[df]
    // Cornish-Fisher, three terms: within 0.0001 of the table's value at 30, and closer after.
    val z = Z95
    val n = df.toDouble()
    return z + (z * z * z + z) / (4 * n) +
            (5 * z.pow(5) + 16 * z * z * z + 3 * z) / (96 * n * n) +
            (3 * z.pow(7) + 19 * z.pow(5) + 17 * z * z * z - 15 * z) / (384 * n * n * n)
}

/**
 * One figure over several runs: their mean, and the half-width of its 95% interval.
 *
 * **Each run is one sample**, never each batch or each execution: the batches inside one JVM share
 * its compiled code, its heap and the moment's CPU clock, so they are not independent tries. Three
 * runs of three batches each can give +-8.9% counting the nine batches and +-20.5% counting the
 * three runs, and the narrower one claims knowledge the runs do not have.
 */
internal class Estimate(val values: List<Double>) {
    val n: Int get() = values.size
    val mean: Double = if (values.isEmpty()) Double.NaN else values.average()

    /** The runs' sample variance, NaN with fewer than two. */
    val variance: Double = if (values.size < 2) Double.NaN else {
        val m = mean
        values.sumOf { (it - m) * (it - m) } / (values.size - 1)
    }

    /** The 95% half-width, NaN with fewer than two runs: one run says nothing about how runs differ. */
    val half: Double = if (values.size < 2) Double.NaN else t95(values.size - 1) * sqrt(variance) / sqrt(values.size.toDouble())

    /** [half] as a fraction of [mean]. */
    val relative: Double get() = if (mean == 0.0) Double.NaN else half / mean
}

/** `+- 1.9 s (28%)`: the form a report quotes, absolute and relative. Nothing where there is no interval. */
internal fun plusMinusBoth(e: Estimate): String =
    if (e.half.isNaN()) "" else "+- ${duration(e.half)} (${plusMinus(e).removePrefix("+-")})"

/** A share's interval in percentage points, `+-1 pp`: a relative one of a percentage reads two ways. */
internal fun plusMinusPoints(e: Estimate): String = when {
    e.half.isNaN() -> ""
    e.half < 9.95 -> String.format(Locale.ROOT, "+-%.1f pp", e.half)
    else -> String.format(Locale.ROOT, "+-%.0f pp", e.half)
}

/** `+-2.3%`, `+-12%`, or `-` where there is no interval. */
internal fun plusMinus(e: Estimate): String {
    val r = e.relative * 100
    return when {
        r.isNaN() -> "-"
        r < 9.95 -> String.format(Locale.ROOT, "+-%.1f%%", r)
        else -> String.format(Locale.ROOT, "+-%.0f%%", r)
    }
}

/**
 * **How a program behaves: same code, same workload, same setup.** Every run in a folder (see
 * [RunStore.saveNew]), each figure their mean with a 95% interval from how much the runs differ.
 *
 * One run is one JVM, and one JVM cannot say how far its own numbers would move in the next one:
 * the clock, the heat, the heap layout, the JIT's choices. That is what a number for a report needs,
 * and the same program can move by 20% from one JVM to the next. A profile of one run renders with a
 * `CAREFUL` line saying its times have no interval.
 *
 * Coarse rows are matched by path and fine rows by name, as in [ProfileDiff]. A row missing from
 * some runs says in how many it was, and its figures are over those.
 */
class Profile internal constructor(internal val name: String, internal val files: List<Path>, internal val runs: List<Run>) {
    init {
        require(runs.isNotEmpty()) { "no runs" }
        require(files.size == runs.size)
    }

    private val walls = runs.map { it.wallsOf() }

    /** Run [i] by name: its folder and time. */
    internal fun runName(i: Int): String = "$name/${files[i].fileName}"

    /**
     * A profile of one run has a `CAREFUL` line on its times: a run can be faster or slower as a whole by
     * 20-30% from one JVM to the next, while each row's share of its parent holds within a couple of
     * points. So the shares of one run can be worked from, and its times cannot - which is what the
     * line says, every time, rather than refusing the first look.
     */
    fun render(): String {
        Logo.once()
        return report(false, (if (runs.size == 1) Warnings(listOf(ONE_RUN_CAREFUL.format(name))) else Warnings()) + warnings())
    }

    /** One coarse row over the runs: the node at this path in each, null where a run has none. */
    internal class Matched(val name: String, val nodes: List<CoarseNode?>) {
        val present: List<Int> get() = nodes.indices.filter { nodes[it] != null && nodes[it]!!.count > 0 }
    }

    private fun wallOf(i: Int, n: CoarseNode): Double = walls[i][n] ?: n.spanSumNanos.toDouble()

    private fun estimateOf(m: Matched, f: (Int, CoarseNode) -> Double): Estimate =
        Estimate(m.present.map { f(it, m.nodes[it]!!) }.filter { !it.isNaN() })

    /** The row [name] among [level], one list of sibling nodes per run. */
    internal fun matched(level: List<List<CoarseNode>>, name: String): Matched =
        Matched(name, level.map { nodes -> nodes.firstOrNull { it.name == name } })

    internal fun childrenOf(level: List<List<CoarseNode>>): List<Matched> =
        level.flatten().map { it.name }.distinct().map { matched(level, it) }
            .sortedWith(
                compareByDescending<Matched> { m -> wall(m).mean.let { if (it.isNaN()) 0.0 else it } }
                    .thenByDescending { m -> m.nodes.sumOf { it?.inclusiveHits ?: 0L } }
            )

    internal fun wall(m: Matched): Estimate = estimateOf(m) { i, node -> wallOf(i, node) }

    internal fun meanSpan(m: Matched): Estimate = estimateOf(m) { _, node -> node.meanSpanNanos }

    /** Whether any run here counted allocated bytes. */
    internal val allocations: Boolean get() = runs.any { it.allocations }

    /** Bytes per execution of [m], over the runs that counted them. See [Run.allocatedPerExecution]. */
    internal fun allocated(m: Matched): Estimate =
        Estimate(m.present.filter { runs[it].allocations }.map { runs[it].allocatedPerExecution(m.nodes[it]!!) }.filter { !it.isNaN() })

    /** One figure of each run. */
    private fun over(f: (Run) -> Double) = Estimate(runs.map(f))

    /** Who had the cores, the mean of the runs that read it. */
    internal fun coresText(): String? = coresLine(runs.mapNotNull { it.duty.cores() })

    /** How often the sampler sampled, at the mean step of the runs. */
    internal fun samplingText(): String? = runs[0].samplingLine(runs.map { it.stepNanos }.average())

    /** How much evidence there is, which is the precision of everything in the tables. See [Run]. */
    internal fun sampledText(): String {
        val per = if (runs.size > 1) " a run" else ""
        return String.format(
            Locale.ROOT, "%,.0f samples$per - %,.0f ticks at %.3f ms mean (jittered) x %s, one sample per thread per tick",
            runs.map { it.allHits }.average(), runs.map { it.ticks }.average(), runs.map { it.stepNanos }.average() / 1e6,
            Run.plural(Math.round(runs.map { it.threads }.average()), "thread")
        )
    }

    /**
     * **The report: the same lines for one run or several.** Only the data differs: a `+-` needs two runs,
     * and the run list has as many lines as there are runs.
     *
     * One long rule at the top and one at the end: the report lands in a console full of the program's
     * own output, and its first and last lines should say so. No rules between sections - the headings
     * and the tables' own rules do that. The logo is not here: it is printed once per JVM by the first
     * TickSnick call, see [Logo].
     *
     * @param legend every column explained, at the bottom. See [Run.renderLegend].
     * @param careful the warnings, in a `CAREFUL` section: before any number, see [carefulBlock].
     */
    internal fun report(legend: Boolean, careful: Warnings): String = buildString {
        for (failure in runs.mapNotNull { it.failure }.distinct()) {
            appendLine("!".repeat(pageWidth))
            appendLine("PROFILING STOPPED - these numbers were not going to be right:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(pageWidth))
        }
        val n = runs.size
        appendLine("=".repeat(pageWidth))
        appendLine(
            row(
                "Runs", if (n == 1) "1 run of '$name', one JVM: no interval"
                else "$n runs of '$name', one JVM each: every figure below is their mean, " +
                        "+- its 95% interval from how much the runs differ (Student's t, ${n - 1} degrees of freedom)"
            )
        )
        carefulBlock(careful)
        factLines(tagMaps, setupMaps)
        runList()
        appendLine(
            row(
                "Wall time", wallLine(
                    over { it.samplingSpanNanos.toDouble() }, over { it.activeNanos.toDouble() }, over { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() },
                    Math.round(runs.map { it.pause.pauses }.average()), runs.maxOf { it.pause.longestNanos },
                )
            )
        )
        root()?.let { root ->
            val mean = meanSpan(root)
            if (mean.n > 0) appendLine(row(root.name, "${duration(mean.mean)} ${plusMinusBoth(mean)}".trimEnd() + " per execution"))
        }
        // Always, under the wall time: who had the cores, the program's threads being only some of it.
        coresText()?.let { appendLine(row("Cores", it)) }
        // The one mechanism shown: whether the sampler held a processor, which the reader can change.
        samplingText()?.let { appendLine(row("Sampling", it)) }
        // The tree first: the overview - what ran inside what, how often, what part of its parent - and
        // the own-time table after it for the detail. The tree is what a reader reads; the table is
        // what they look up.
        if (runs.any { r -> r.coarse.any { it.count > 0 || it.inclusiveHits > 0 } }) coarseSection()
        appendLine()
        fineSection()
        // After the tables: how much evidence there is says nothing a reader acts on before the numbers,
        // and something after them, when a share looks too close to call. "mean (jittered)": the interval
        // is drawn +/-25% around the step so the sampler cannot lock onto a workload with a rhythm of its
        // own, and over a short run the mean of a handful of draws is visibly off the step.
        appendLine()
        appendLine(row("Sampled", sampledText()))
        appendLine("-".repeat(pageWidth))
        // Said in the output rather than only in a document: the gap between a share and what removing
        // the operation would save can be a factor of 275, and nothing else on the screen hints at it.
        with(runs[0]) { renderLegend(legend) }
        appendLine("=".repeat(pageWidth))
    }

    /** The heaviest root row, a benchmark's batch: the number an article quotes. */
    internal fun root(): Matched? = childrenOf(runs.map { it.coarseTree }).firstOrNull()

    /** One run's fine rows in the coarse row [n]'s own time. */
    private fun ownOf(i: Int, n: CoarseNode): List<Run.OwnRow> = runs[i].ownRowsOf(n) ?: runs[i].splitRowsOf(n)

    /** Which of [rows], those of [n], the run draws. */
    private fun drawnOf(i: Int, n: CoarseNode, rows: List<Run.OwnRow>): List<Run.OwnRow> =
        if (runs[i].ownRowsOf(n) != null) runs[i].drawnOwnRows(rows) else rows.take(FINE_ROWS_SHOWN)

    /** The coarse row [m], and under it its children and the fine labels in its own time. [counted] collects every own row. */
    private fun coarseRow(m: Matched, parents: List<CoarseNode?>, counted: MutableList<Row>): Row {
        val nodes = m.nodes.indices.filter { m.nodes[it] != null }
        fun overAll(f: (Int, CoarseNode) -> Double) = Estimate(nodes.map { f(it, m.nodes[it]!!) }.filter { !it.isNaN() })
        fun timed(f: (CoarseNode) -> Double) = estimateOf(m) { _, n -> f(n) }
        val (drawn, folded) = ownRows(m, nodes, counted)
        val children = childrenOf(m.nodes.map { it?.children ?: emptyList() }).map { coarseRow(it, m.nodes, counted) }
        return Row(
            m.name,
            mapOf(
                EXECUTIONS to overAll { _, n -> n.count.toDouble() },
                PER_PARENT to overAll { i, n -> parents[i]?.takeIf { it.count > 0 }?.let { n.count.toDouble() / it.count } ?: Double.NaN },
                PARALLEL to overAll { i, n -> if (runs[i].factorOf(n) > 1.0) n.parallel else Double.NaN },
                WALL to wall(m),
                PARENT_SHARE to estimateOf(m) { i, n ->
                    val parent = parents[i]
                    if (parent == null || parent.spanSumNanos == 0L) Double.NaN else wallOf(i, n) * 100 / wallOf(i, parent)
                },
                PAUSED to overAll { _, n -> n.pausedNanos.toDouble() },
                MEAN to meanSpan(m),
                ALLOCATED to allocated(m),
                P50 to timed { it.percentileNanos(0.50) },
                P90 to timed { it.percentileNanos(0.90) },
                P99 to timed { it.percentileNanos(0.99) },
                MAX to timed { it.spanMaxNanos.toDouble() },
                COARSE_WAITING to overAll { _, n -> waiting(n.inclusiveHits, n.runningInclusiveHits) },
            ),
            nodes.size,
            children = (children + drawn).sortedByDescending { r -> r.figures[WALL]?.mean?.takeIf { !it.isNaN() } ?: 0.0 } + listOfNotNull(folded),
            slowest = m.nodes.flatMap { it?.slowest.orEmpty() }.sortedByDescending { it.nanos }.take(m.nodes.maxOf { it?.slowest?.size ?: 0 }),
        )
    }

    private fun waiting(inclusive: Long, running: Long): Double = if (inclusive == 0L) 0.0 else (inclusive - running).toDouble() / inclusive

    /**
     * The fine labels in the own time of the row [m], in the runs [nodes]: those drawn in any run, and
     * one line for the rest. A run with the coarse row and not the label counts at 0.
     */
    private fun ownRows(m: Matched, nodes: List<Int>, counted: MutableList<Row>): kotlin.Pair<List<Row>, Row?> {
        val rows = nodes.associateWith { ownOf(it, m.nodes[it]!!) }
        val drawn = nodes.flatMap { i -> drawnOf(i, m.nodes[i]!!, rows[i]!!).map { it.name } }.distinct()
        val all = (drawn + nodes.flatMap { i -> rows[i]!!.map { it.name } }).distinct().map { name ->
            val of = nodes.associateWith { i -> rows[i]!!.firstOrNull { it.name == name } }
            fun anyOver0(v: List<Double>) = Estimate(v.takeIf { it.any { x -> x > 0 } } ?: emptyList())
            val calls = nodes.mapNotNull { i ->
                val c = of[i]?.calls ?: if (name != "unlabelled" && m.nodes[i]!!.calls.isNotEmpty()) 0L else -1L
                c.takeIf { it >= 0 }?.let { i to it }
            }
            val ofTimed = m.present
            val row = Row(
                name,
                mapOf(
                    EXECUTIONS to Estimate(calls.map { it.second.toDouble() }),
                    PER_PARENT to Estimate(
                        calls.takeIf { it.size == nodes.size }.orEmpty().filter { m.nodes[it.first]!!.count > 0 }
                            .map { (i, c) -> c.toDouble() / m.nodes[i]!!.count }
                    ),
                    PARALLEL to Estimate(nodes.mapNotNull { of[it]?.parallel }.filter { it >= PARALLEL_SHOWN }),
                    WALL to anyOver0(ofTimed.map { of[it]?.wallNanos ?: 0.0 }),
                    PARENT_SHARE to anyOver0(ofTimed.map { i -> of[i]?.share?.takeIf { !it.isNaN() }?.times(100) ?: 0.0 }),
                    MEAN to Estimate(nodes.mapNotNull { of[it]?.perCallNanos }.filter { !it.isNaN() }),
                ),
                nodes.count { of[it] != null },
                own = true,
            )
            if (name != "unlabelled" && nodes.any { runs[it].ownRowsOf(m.nodes[it]!!) != null }) counted += row
            row
        }
        val rest = all.filter { it.name !in drawn }.map { it.name }
        fun overRest(f: (Run.OwnRow) -> Double) =
            nodes.map { i -> rows[i]!!.filter { it.name in rest }.sumOf { r -> f(r).takeIf { !it.isNaN() } ?: 0.0 } }
                .let { v -> Estimate(v.takeIf { it.any { x -> x > 0 } } ?: emptyList()) }
        val folded = if (rest.isEmpty()) null else Row(
            FOLDED_ROWS,
            mapOf(
                EXECUTIONS to overRest { it.calls.coerceAtLeast(0).toDouble() },
                WALL to overRest { it.wallNanos },
                PARENT_SHARE to overRest { it.share * 100 },
            ),
            nodes.size,
            own = true,
            foldedRows = rest.size,
        )
        return all.filter { it.name in drawn } to folded
    }

    /** The coarse operations under more than one caller in any run: their figures over all callers. */
    private fun sharedRows(): List<Row> {
        val nodes = runs.map { r -> r.coarseTree.flatMap { it.flatten() }.groupBy { it.name } }
        val names = nodes.flatMap { n -> n.filterValues { it.size > 1 }.keys }.distinct()
        return names.map { name ->
            val with = runs.indices.filter { nodes[it][name] != null }
            fun of(f: (Int, CoarseStat, List<CoarseNode>) -> Double) =
                Estimate(with.map { i -> f(i, runs[i].coarse.first { it.name == name }, nodes[i][name]!!) }.filter { !it.isNaN() })
            Row(
                name,
                mapOf(
                    EXECUTIONS to of { _, c, _ -> c.count.toDouble() },
                    WALL to of { i, _, n -> n.sumOf { wallOf(i, it) } },
                    PAUSED to of { _, _, n -> n.sumOf { it.pausedNanos }.toDouble() },
                    MEAN to of { _, c, _ -> c.meanSpanNanos },
                    ALLOCATED to Estimate(
                        with.filter { runs[it].allocations }.map { i ->
                            nodes[i][name]!!.sumOf { n -> n.flatten().sumOf { it.allocated } }.toDouble() / runs[i].coarse.first { it.name == name }.count
                        }
                    ),
                    P50 to of { _, c, _ -> c.percentileNanos(0.50) },
                    P90 to of { _, c, _ -> c.percentileNanos(0.90) },
                    P99 to of { _, c, _ -> c.percentileNanos(0.99) },
                    MAX to of { _, c, _ -> c.spanMaxNanos.toDouble() },
                    COARSE_WAITING to of { _, c, _ -> waiting(c.inclusiveHits, c.runningInclusiveHits) },
                ),
                with.size,
            )
        }.sortedByDescending { r -> r.figures[WALL]!!.mean }
    }

    /** **The coarse table**: one row per path, over the runs. */
    internal val coarseTable: Table by lazy {
        val own = ArrayList<Row>()
        val rows = childrenOf(runs.map { it.coarseTree }).map { coarseRow(it, runs.map { null }, own) }
        // A fine label under more than one row: each row is its time under that row alone, so the tree
        // never shows its total, and a label spread over three walks reads smaller than one that leads
        // a single walk. The total goes once, beside the label's largest row. With calls counted, every
        // row the label was called under counts, sampled or not, so the number of operations is exact.
        for ((_, under) in own.groupBy { it.name }) {
            fun wall(r: Row) = r.figures[WALL]?.mean?.takeIf { !it.isNaN() } ?: 0.0
            if (under.size < 2 || under.sumOf { wall(it) } < NOTE_MIN_GAIN * under.maxOf { wall(it) }) continue
            val calls = under.map { it.figures[EXECUTIONS]?.mean?.takeIf { c -> !c.isNaN() } }
            under.maxBy { wall(it) }.inAll = InAll(under.sumOf { wall(it) }, calls.takeIf { null !in it }?.sumOf { it!! }, under.size)
        }
        val split = runs.any { r -> r.coarseTree.any { t -> t.flatten().any { r.ownRowsOf(it) == null && r.splitRowsOf(it).isNotEmpty() } } }
        Table(COARSE_COLUMNS.filter { it !== ALLOCATED || allocations }, rows, runs.size, WALL, sharedRows(), splitLeaves = split)
    }

    /** **The fine table**: fine and coarse operations together, each by its own time, heaviest first. */
    internal val fineTable: Table by lazy {
        val rows = runs.map { r -> r.tableRows().associateBy { it.label } }
        val labels = rows.flatMap { it.keys }.distinct()
        fun inRuns(label: String) = rows.mapNotNull { it[label] }
        // A coarse operation with no own time is not a row: three rows of zeros at the bottom are not
        // digits. It is one line under the table, worded for what happened - all of its time was inside
        // other operations, which is the ordinary state of a span around labelled work.
        val contained = labels.filter { l -> inRuns(l).let { r -> r.all { it.coarse && it.hits == 0L } && r.any { it.inclusiveHits > 0 } } }
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes. Folded, not dropped: a *called* operation with no samples is a real finding, since it
        // means the label is on something too small to see.
        val (seen, unseen) = labels.filter { it !in contained }.partition { l -> inRuns(l).any { it.hits > 0 } }
        val table = seen.map { label ->
            // A label called but never sampled in a run is in it, at 0: a draw of a sampled count like
            // any other. Left out, only the runs it was lucky in remain, and the mean reads high.
            val present = runs.indices.filter { i -> rows[i][label]?.let { it.hits > 0 || it.calls > 0 } == true }
            fun of(f: (Run, Run.OperationRow) -> Double) = Estimate(present.map { f(runs[it], rows[it][label]!!) }.filter { !it.isNaN() })
            fun long(f: (Run, Run.OperationRow) -> Double) = of { p, r -> if (r.longShown) f(p, r) else Double.NaN }
            fun named(f: (Run, Run.OperationRow) -> Double) = of { p, r -> if (r.name == Run.NO_OPERATION) Double.NaN else f(p, r) }
            Row(
                rows[present.first()][label]!!.name,
                mapOf(
                    THREAD_SHARE to of { _, r -> r.shareOf() * 100 },
                    THREAD_TIME to of { p, r -> r.hits * p.stepNanos },
                    FINE_WAITING to of { _, r -> r.waitingShare },
                    CALLS to named { _, r -> r.calls.toDouble() },
                    PER_CALL to of { _, r -> r.perCallNanos() },
                    LONG_CALLS to long { _, r -> r.stuckInstances.toDouble() },
                    LONG_TIME to long { p, r -> r.stuckHits * p.stepNanos },
                    LONGEST to long { p, r -> r.longestStuckTicks * p.stepNanos },
                    WALL_TIME to named { p, r -> r.activeTicks * p.stepNanos },
                    CONCURRENCY to of { _, r -> if (r.activeTicks == 0L) Double.NaN else r.hits.toDouble() / r.activeTicks },
                    WORKERS to of { _, r -> if (r.peakInside <= 0) Double.NaN else r.peakInside.toDouble() },
                    POOL to of { _, r -> if (r.threads <= 0) Double.NaN else r.threads.toDouble() },
                ),
                present.size,
                coarse = rows[present.first()][label]!!.coarse,
            )
        }
        Table(
            FINE_COLUMNS, table.sortedByDescending { it.figures[THREAD_SHARE]!!.mean }, runs.size, THREAD_SHARE,
            contained = contained.sortedByDescending { l -> inRuns(l).sumOf { it.inclusiveHits } }.map { inRuns(it).first().name },
            folded = unseen.map { l -> inRuns(l).let { r -> Unsampled(r.first().name, r.first().coarse, r.maxOf { it.calls }) } },
        )
    }

    internal val coarseText: TableText by lazy { TableText(coarseTable, "Coarse operation", COARSE_NAME_WIDTH) }
    internal val fineText: TableText by lazy { TableText(fineTable, "Operation", FINE_NAME_WIDTH) }

    /** The widest table, for the rules around a report. */
    internal val pageWidth: Int get() = maxOf(coarseText.width, fineText.width)

    private val overRuns: String get() = if (runs.size > 1) "; each figure the mean of the runs +- 95%" else ""

    /**
     * The coarse table with its keys, each once and next to the rows that need it.
     *
     * Wall time, not active: a span is two timestamps, and a request that sat through a 2.6 s
     * collection took 2.6 s longer. The pauses stay in - the caller waited for them - and the
     * `Paused` column says how much of each row they were. Taking them out would make a 9.07 s level
     * look like an 8.5 s one, and where they fell is itself a lead: the span that collects is the span
     * that allocated, or the one after it.
     *
     * The table is the tree, with no separate flat ranking. Inclusive time is monotone up a path, so
     * the heaviest operation anywhere is at the end of the first branch - the ranking holds.
     */
    internal fun StringBuilder.coarseSection() {
        val paused = runs.any { it.pause.confirmedPausedIn(it.samplingSpanNanos) > 0 }
        appendLine()
        appendLine(
            // Wall time, so each row says what part of its parent it took: `branch` 88% of the request,
            // and the rest the request's own merge. Summed durations would read 988% of the parent
            // once a fork runs ten at a time. See Run.wallsOf.
            "COARSE OPERATIONS - wall time, inclusive, nested as it ran" +
                    (if (paused) "; a pause is charged to every operation it interrupted" else "") + overRuns
        )
        with(coarseText) { rows() }
        coarseKeys().forEach { appendLine(it) }
        // An operation with more than one caller is a row under each, and those rows are its figures
        // under that caller alone. Its percentiles over everything are a different question - "how long
        // does a parse take" - and they are answered here, once, only when the question exists.
        if (coarseTable.shared.isNotEmpty()) {
            appendLine("  Under more than one caller - all callers together:")
            with(coarseText) { shared() }
        }
    }

    /** What the coarse table's rows need explained, each once and next to them. */
    internal fun coarseKeys(): List<String> = buildList {
        val all = buildList {
            fun walk(r: Row) {
                add(r)
                r.children.forEach { walk(it) }
            }
            coarseTable.rows.forEach { walk(it) }
        }
        val anyOwn = all.any { it.own }
        if (coarseTable.splitLeaves && anyOwn) add(
            "  $SPLIT_MARK rows split a leaf's time by the innermost label, each sample once - they add up to 100%;" +
                    " a fine label with labels inside it shows only its own part"
        )
        if (!coarseTable.splitLeaves && anyOwn) add(
            "  $SPLIT_MARK rows are fine labels in a row's own time, outside its coarse children; Parallel on one is how many" +
                    " threads ran it at a time in one execution; a fine label with labels inside it shows only its own part"
        )
        if (all.any { it.own && it.foldedRows == 0 && it.figures[EXECUTIONS]?.n != 0 }) add(
            "  on a $SPLIT_MARK row, Executions is its calls on that path and Per parent per execution of the row above, both counted;" +
                    " Mean is its own thread-time per call, sampled; - for a label called there and never caught"
        )
        if (allocations) add(
            "  Allocated: bytes per execution, its coarse children and every thread under it included; counted, not sampled" +
                    if (runs.size > 1) "; the mean of the runs that counted them" else ""
        )
        if (all.any { it.inAll != null }) add(
            "  'in all, under N operations' beside a fine row: that label's time, and calls where counted, under every row it is under, on its largest"
        )
        if (runs.size > 1) {
            add("  % parent is each run's share of its own parent, +- in percentage points: the run's speed cancels out of it")
            add("  Max is the largest of the runs, and a percentile the mean of the runs' percentiles, with no interval; - where a run had too few executions for it")
        }
    }

    /**
     * The fine table with what is under it. FINE and COARSE together, not bare OPERATIONS: a coarse
     * operation appears in both tables with different numbers - `request` at 5.03% of its own work here
     * and 99.6% including everything it contains above - and the heading says they are two questions.
     * It also says the clock the table is over: a thread-time is a sum over threads and `active` is the
     * span it was summed in, and on one thread the two figures match.
     */
    internal fun StringBuilder.fineSection() {
        val fine = fineTable
        appendLine("COARSE AND FINE OPERATIONS - own time, out of ${duration(runs.map { it.activeNanos.toDouble() }.average())} active time$overRuns")
        if (fine.rows.isNotEmpty()) with(fineText) { rows() }
        // A table with no rows is two rules around nothing. Say what happened instead: an empty
        // table is nearly always a run too short for the sampler to catch, and that is worth
        // naming rather than leaving the reader to infer from a blank.
        // It says "run for longer" and not "or lower tickMillis": below
        // MIN_TICKS_FOR_A_TABLE there is no step that rescues the run. Two ticks would need a step
        // of 0.04 ms to reach fifty, which measures the sampler rather than the program, and fifty
        // samples still says nothing. Lowering the step is real advice for an operation that is
        // called but never caught in a run long enough to be real - that case is the fold below.
        if (fine.rows.isEmpty()) {
            val ticks = runs.maxOf { it.ticks }
            appendLine(
                if (runs.all { it.labelledHits == 0L } && ticks < Run.MIN_TICKS_FOR_A_TABLE)
                    "  nothing was sampled - $ticks ticks is too few for the sampler to catch anything. Run for longer."
                else "  nothing was sampled."
            )
        }
        fineNotes().forEach { appendLine(it) }
    }

    /** What is under the fine table. */
    internal fun fineNotes(): List<String> = buildList {
        val fine = fineTable
        if (fine.contained.isNotEmpty()) add(
            "  " + fine.contained.joinToString { it + STAR } + ": coarse, with no own time - every sample inside " +
                    (if (fine.contained.size == 1) "it" else "them") + " was in an operation above"
        )
        if (fine.folded.isNotEmpty()) {
            val called = fine.folded.filter { it.calls > 0 }
            // Named, in both branches: "1 operation never sampled" leaves the reader to work out
            // which of their labels it was, and on a short run — where this line fires most — the
            // answer is usually the whole point. Capped at four, since a run can fold twenty-five.
            fun names(ops: List<Unsampled>) = ops.take(4).joinToString { it.name + if (it.coarse) STAR else "" } + if (ops.size > 4) ", ..." else ""
            // "called anyway" rather than "N of them did run", which reads as nonsense at N = 1 —
            // and one is the common case on a short run, which is exactly when a reader is most
            // likely to be new to the report.
            add(
                "  ${Run.plural(fine.folded.size.toLong(), "operation")} never sampled and folded away" +
                        (if (called.isEmpty()) ", and never called: " + names(fine.folded) else "; called anyway: " + names(called.sortedByDescending { it.calls }))
            )
        }
        // The marker, explained once and next to the rows that carry it. Not in the legend
        // alone: a star with no key is the reader's problem, and the key is one line.
        if (fine.rows.any { it.coarse } || fine.contained.isNotEmpty()) {
            add("  * a coarse operation, showing its OWN work - its full span is in the table above")
        }
        // A note and not a warning: it is the threshold the `Long` group is printed against, so it
        // belongs under the table that has those columns rather than in the warning section. The
        // floor stays over wall time on purpose: a pause inside a call stretches it past a tick, so GC
        // is a cause of a long call even though it is out of active time.
        add(
            String.format(
                Locale.ROOT,
                "  Long: calls over a tick hold %s of thread-time run-wide; preemption and GC pauses alone could put it at %s, and a row at or under that shows -",
                percent(runs.map { it.stuckBaseline }.average() * 100), percent(runs.map { it.machineFloor }.average() * 100)
            )
        )
    }

    /** Each run's tags, one map per run. */
    internal val tagMaps: List<Map<String, String>> get() = runs.map { it.tags }

    /** Each run's facts, one map per run that has any: a run saved without a setup does not say. */
    internal val setupMaps: List<Map<String, String>> get() = runs.filter { it.setup.facts.isNotEmpty() }.map { it.facts }

    /**
     * **Every warning about this profile**, worst first. The one place they come from: a report prints
     * them, and a diff prints them for each of its sides, because a diff is only as good as the two
     * profiles in it. Each run's own come up from [Run.warnings], one line for those that read the same.
     * [named] says whose they are, for a diff.
     *
     * What makes the interval mean something other than chance comes first, since every figure below
     * rests on it, and the runs' own after it. The line that the runs differ is one line however many
     * names it lists, and is in [Warnings.always].
     */
    internal fun warnings(named: Boolean = false): Warnings {
        val where = if (named) " in '$name'" else ""
        val mixed = mixedNames(tagMaps) + mixedNames(setupMaps)
        return Warnings(
            intervalWarnings(root(), where) + runWarnings(where),
            if (mixed.isEmpty()) emptyList() else listOf(
                "runs$where: they differ in ${mixed.joinToString(", ")} - " +
                        "a folder is same code, same workload, same setup; move a run that differs out of it"
            ),
        )
    }

    /** Each run's own warnings, the same one once, with how many runs said it. */
    private fun runWarnings(where: String): List<String> {
        val said = LinkedHashMap<String, Int>()
        for (run in runs) {
            val failure = run.failure?.let { "profiling stopped: $it - this run's numbers are not a result" }
            for (w in listOfNotNull(failure) + run.warnings()) said.merge(w, 1, Int::plus)
        }
        return said.map { (warning, count) ->
            val place = when {
                runs.size > 1 -> " ($count of ${runs.size} runs$where)"
                where.isEmpty() -> ""
                else -> " (${where.trim()})"
            }
            val lines = warning.lines()
            (listOf(lines.first() + place) + lines.drop(1)).joinToString("\n")
        }
    }

    /**
     * What makes the interval mean something other than chance: runs that do not look like one setup,
     * a clock that was not the same in all of them, and runs of one JVM, which are not independent.
     */
    private fun intervalWarnings(root: Matched?, where: String): List<String> {
        val lines = mutableListOf<String>()
        if (root != null) {
            val counts = runs.indices.map { root.nodes[it]?.count ?: 0L }
            if (counts.distinct().size > 1) lines +=
                "${root.name}$where: the runs ran it ${counts.joinToString(", ")} times - " +
                        "a folder is same code, same workload, same setup; move a run that is not out of it"
        }
        val clocks = runs.map { it.clock.mean }
        if (clocks.all { !it.isNaN() } && runs.all { it.clock.ofBase == runs[0].clock.ofBase }) {
            val lo = clocks.min()
            val hi = clocks.max()
            if (lo > 0 && (hi - lo) / lo > RUNS_CLOCK_SPREAD) lines +=
                "clock$where: ${runs[0].clock.format(lo)} to ${runs[0].clock.format(hi)} across the runs - a run's time " +
                        "follows it and the interval includes that; hold the clock still (turbo off) for a tighter one"
        }
        val shared = runs.mapNotNull { it.setup.jvm }.groupingBy { it }.eachCount().values.filter { it > 1 }.sum()
        if (shared > 0) lines +=
            "runs$where: $shared of ${runs.size} came from one JVM - the interval counts them as independent, so it reads too narrow"
        return lines
    }

    /**
     * The runs one by one: file, wall time, clock, and the executions of the heaviest root row while
     * they are few - the batches of a benchmark, exactly. The table a reader checks first: a run that
     * stands out, and whether its clock says why.
     */
    internal fun StringBuilder.runList(side: String = "", header: Boolean = true) {
        val root = root()
        val listed = root != null && root.present.all { i ->
            val node = root.nodes[i]!!
            node.count <= RUN_EXECUTIONS_LISTED && node.spans.isExact
        }
        // A diff lists both sides' runs in one table, each line saying whose run it is.
        val indent = "              " + if (side.isEmpty()) "" else String.format(Locale.ROOT, "%-7s", side)
        if (header) appendLine(
            String.format(
                Locale.ROOT, "              %s%-4s %-28s %10s %8s   %s", if (side.isEmpty()) "" else " ".repeat(7),
                "run", "file", "wall", "clock", if (root != null && listed) "${root.name}, each execution" else ""
            ).trimEnd()
        )
        runs.forEachIndexed { i, p ->
            val each = if (root != null && listed) root.nodes[i]?.spans?.exactDurations()?.joinToString("  ") { duration(it.toDouble()) } ?: "-" else ""
            appendLine(
                String.format(
                    Locale.ROOT, "%s%-4d %-28s %10s %8s   %s", indent, i + 1, files[i].fileName.toString(),
                    duration(p.samplingSpanNanos.toDouble()), p.clock.format(p.clock.mean), each
                ).trimEnd()
            )
        }
    }

    companion object {
        /** The profile in [folder]: every run in it. See [RunStore.runs]. */
        internal fun load(folder: Path): Profile {
            require(java.nio.file.Files.isDirectory(folder)) {
                if (java.nio.file.Files.exists(folder)) "'$folder' is not a profile: a profile is a folder" else "no profile at '$folder': no such folder"
            }
            val files = RunStore.runs(folder)
            require(files.isNotEmpty()) { "no run in '$folder'" }
            // The folder's own name, also for `.` or a drive root, which have none as given.
            val name = folder.toAbsolutePath().normalize().let { (it.fileName ?: it).toString() }
            return Profile(name, files, files.map { Run.load(it) })
        }
    }
}
