package com.ticksnick

import java.nio.file.Path
import java.util.Locale
import kotlin.math.pow
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
        "shares of the parent hold"

/** How many warnings the `CAREFUL` section prints before it counts the rest instead of listing them. */
internal const val MAX_CAREFUL = 10

internal const val RUNS_WIDTH = 165
internal const val RUNS_COARSE_ROW = "%-40s %11s %10s %7s %8s %9s %10s %7s %10s %10s %10s"
internal const val RUNS_FINE_ROW = "%-26s | %11s %7s | %11s %7s | %27s"

/**
 * **Every warning of every report, as a section of its own right under its first line**: before any
 * number, because a reader stops at the first one, and a blank line either side, because a
 * warning among the figures goes unseen: a diff that reads -56% at clocks of 99% and 197% must say
 * so before the -56%.
 *
 * A warning with line breaks continues under its own text. Capped at [MAX_CAREFUL], the rest
 * counted: a section that runs to fifty is one a reader learns to skip. The caller orders them by
 * what each costs the reader, worst first.
 */
internal fun StringBuilder.carefulBlock(warnings: List<String>) {
    if (warnings.isEmpty()) return
    appendLine()
    for (w in warnings.take(MAX_CAREFUL)) {
        val lines = w.lines()
        appendLine(row("CAREFUL", lines.first().trim()))
        for (line in lines.drop(1)) appendLine(row("", line.trim()))
    }
    if (warnings.size > MAX_CAREFUL) appendLine(row("", "... and ${warnings.size - MAX_CAREFUL} more"))
    appendLine()
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
 * **The runs of one version, read together**: every profile in a folder (see
 * [ProfileStore.saveNew]), each figure their mean with a 95% interval from how much the runs differ.
 *
 * One profile is one JVM, and one JVM cannot say how far its own numbers would move in the next one:
 * the clock, the heat, the heap layout, the JIT's choices. That is what a number for a report needs,
 * and the same run can move by 20% from one JVM to the next. One run renders as a single profile,
 * with a `CAREFUL` line saying its times have no interval.
 *
 * Coarse rows are matched by path and fine rows by name, as in [ProfileDiff]. A row missing from
 * some runs says in how many it was, and its figures are over those.
 */
class ProfileRuns internal constructor(internal val name: String, internal val files: List<Path>, internal val runs: List<Profile>) {
    init {
        require(runs.isNotEmpty()) { "no runs" }
        require(files.size == runs.size)
    }

    private val walls = runs.map { it.wallsOf() }

    /** Run [i] by name: its folder and time. */
    internal fun runName(i: Int): String = "$name/${files[i].fileName}"

    /**
     * One run renders as a single profile, with a `CAREFUL` line on its times: a run can be faster
     * or slower as a whole by 20-30% from one JVM to the next, while each row's share of its parent
     * holds within a couple of points. So the shares of one run can
     * be worked from, and its times cannot - which is what the line says, every time, rather than
     * refusing the first look.
     */
    fun render(): String {
        Logo.once()
        return if (runs.size == 1) runs[0].render(careful = listOf(ONE_RUN_CAREFUL.format(name))) else across()
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
            .sortedByDescending { m -> wall(m).mean.let { if (it.isNaN()) 0.0 else it } }

    internal fun wall(m: Matched): Estimate = estimateOf(m) { i, node -> wallOf(i, node) }

    internal fun meanSpan(m: Matched): Estimate = estimateOf(m) { _, node -> node.meanSpanNanos }

    /**
     * Each run's share of its own parent, in percent: the run's speed cancels out of it, so it says
     * whether the structure held while the whole run got faster or slower. [parents] has one node
     * per run, null at the top.
     */
    internal fun share(m: Matched, parents: List<CoarseNode?>): Estimate = estimateOf(m) { i, node ->
        val parent = parents[i]
        if (parent == null || parent.count == 0L) Double.NaN else wallOf(i, node) * 100 / wallOf(i, parent)
    }

    /**
     * The fine labels drawn in the own time of the row [m] in any run, each with its row in every
     * run: null where the run has the coarse row and not the label.
     */
    internal fun ownOf(m: Matched): Map<String, List<Profile.OwnRow?>> {
        val rows = m.nodes.mapIndexed { i, n -> n?.let { runs[i].ownRowsOf(it) }.orEmpty() }
        val drawn = m.nodes.indices.flatMap { i -> runs[i].drawnOwnRows(rows[i]).map { it.name } }.distinct()
        return drawn.associateWith { name -> rows.map { r -> r.firstOrNull { it.name == name } } }
    }

    /**
     * Calls per execution of [m] for one fine label, [rows] from [ownOf]: one figure when every run
     * agrees, `~` their mean when not, blank where no run counted them.
     */
    internal fun perExecution(m: Matched, rows: List<Profile.OwnRow?>?): String {
        val per = m.present.mapNotNull { i ->
            val node = m.nodes[i]!!
            val calls = rows?.get(i)?.calls ?: if (node.calls.isNotEmpty()) 0L else -1L
            if (calls < 0) null else calls to node.count
        }
        if (per.isEmpty()) return ""
        val texts = per.map { (c, n) -> callsPerExecution(c, n) }.distinct()
        return if (texts.size == 1) texts[0] else String.format(Locale.ROOT, "~x%.1f", per.map { (c, n) -> c.toDouble() / n }.average())
    }

    /** Whether any run here counted allocated bytes. */
    internal val allocations: Boolean get() = runs.any { it.allocations }

    /** Bytes per execution of [m], over the runs that counted them. See [Profile.allocatedPerExecution]. */
    internal fun allocated(m: Matched): Estimate =
        Estimate(m.present.filter { runs[it].allocations }.map { runs[it].allocatedPerExecution(m.nodes[it]!!) }.filter { !it.isNaN() })

    /** The heaviest root row, a benchmark's batch: the number an article quotes. */
    internal fun root(): Matched? = childrenOf(runs.map { it.coarseTree }).firstOrNull()

    /** One fine or starred coarse row over the runs, by its printed label. */
    internal class FineLine(val label: String, val time: Estimate, val perCall: Estimate, val calls: List<Long>, val present: Int)

    internal fun fineLines(): Map<String, FineLine> {
        // A label called but never sampled in a run is in it, at 0: a draw of a sampled count like any
        // other. Left out, only the runs it was lucky in remain, and the mean reads high.
        val rows = runs.map { p -> p.rows().filter { it.hits > 0 || it.calls > 0 }.associateBy { it.label } }
        return rows.flatMap { it.keys }.distinct().associateWith { label ->
            val present = runs.indices.filter { rows[it][label] != null }
            FineLine(
                label,
                Estimate(present.map { rows[it][label]!!.hits * runs[it].stepNanos }),
                Estimate(present.map { rows[it][label]!!.perCallNanos() }.filter { !it.isNaN() }),
                present.map { rows[it][label]!!.calls },
                present.size,
            )
        }
    }

    private fun across(): String = buildString {
        val n = runs.size
        appendLine("=".repeat(RUNS_WIDTH + maxOf(coarseExtra, fineExtra)))
        appendLine(
            row(
                "Runs", "$n runs of '$name', one JVM each: every figure below is their mean, " +
                        "+- its 95% interval from how much the runs differ (Student's t, ${n - 1} degrees of freedom)"
            )
        )
        val root = root()
        carefulBlock(carefulLines(root))
        runList()
        val wall = Estimate(runs.map { it.samplingSpanNanos.toDouble() })
        val active = Estimate(runs.map { it.activeNanos.toDouble() })
        val paused = Estimate(runs.map { it.pause.confirmedPausedIn(it.samplingSpanNanos).toDouble() })
        appendLine(
            row(
                "Wall time", "${duration(wall.mean)} ${plusMinusBoth(wall)} = ${duration(active.mean)} ${plusMinus(active)} active + " +
                        "${duration(paused.mean)} ${plusMinus(paused)} paused"
            )
        )
        if (root != null) {
            val mean = meanSpan(root)
            if (mean.n > 0) appendLine(row(root.name, "${duration(mean.mean)} ${plusMinusBoth(mean)} per execution"))
        }
        coarseAcross()
        fineAcross()
        appendLine()
        appendLine("  each run's own report, with its fine rows under every coarse row and its warnings: render a folder holding that run alone")
        appendLine("=".repeat(RUNS_WIDTH + maxOf(coarseExtra, fineExtra)))
    }

    /**
     * What makes the interval mean something other than the code's own noise: runs that do not look
     * like one setup, and a clock that was not the same in all of them.
     */
    internal fun carefulLines(root: Matched?, where: String = ""): List<String> {
        val lines = mutableListOf<String>()
        if (root != null) {
            val counts = runs.indices.map { root.nodes[it]?.count ?: 0L }
            if (counts.distinct().size > 1) lines +=
                "${root.name}$where: the runs ran it ${counts.joinToString(", ")} times - " +
                        "a folder is one version on one setup; move a run that is not out of it"
        }
        val clocks = runs.map { it.clock.mean }
        if (clocks.all { !it.isNaN() } && runs.all { it.clock.ofBase == runs[0].clock.ofBase }) {
            val lo = clocks.min()
            val hi = clocks.max()
            if (lo > 0 && (hi - lo) / lo > RUNS_CLOCK_SPREAD) lines +=
                "clock$where: ${runs[0].clock.format(lo)} to ${runs[0].clock.format(hi)} across the runs - a run's time " +
                        "follows it and the interval includes that; hold the clock still (turbo off) for a tighter one"
        }
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

    /** The coarse table's rows, name first. Built before anything is printed: the longest name sizes the page. */
    private val coarseRows: List<List<String>> by lazy {
        val rows = ArrayList<List<String>>()
        fun walk(level: List<List<CoarseNode>>, parents: List<CoarseNode?>, depth: Int) {
            for (m in childrenOf(level)) {
                val wall = wall(m)
                val mean = meanSpan(m)
                val pct = listOf(0.50, 0.90, 0.99).map { p -> estimateOf(m) { _, node -> node.percentileNanos(p) } }
                val share = share(m, parents)
                val counts = m.present.map { m.nodes[it]!!.count }
                val executions = when {
                    counts.isEmpty() -> "-"
                    counts.distinct().size == 1 -> String.format(Locale.ROOT, "%,d", counts[0])
                    else -> String.format(Locale.ROOT, "~%,.0f", counts.average())
                }
                val where = if (m.present.size < runs.size) " (${m.present.size} of ${runs.size} runs)" else ""
                fun d(e: Estimate) = if (e.n == 0) "-" else duration(e.mean)
                fun pm(e: Estimate) = if (e.n == 0) "" else plusMinus(e)
                rows += listOf(
                    "  ".repeat(depth) + m.name + where, executions,
                    d(wall), pm(wall),
                    if (share.n == 0) "" else String.format(Locale.ROOT, "%.0f%%", share.mean), plusMinusPoints(share),
                    d(mean), pm(mean), d(pct[0]), d(pct[1]), d(pct[2]),
                    allocated(m).let { if (it.n == 0) "-" else bytes(it.mean) },
                )
                walk(m.nodes.map { it?.children ?: emptyList() }, m.nodes, depth + 1)
            }
        }
        walk(runs.map { it.coarseTree }, runs.map { null }, 0)
        rows
    }

    /** The fine table's rows, name first. See [coarseRows]. */
    private val fineRows: List<List<String>> by lazy {
        fineLines().values.sortedByDescending { it.time.mean }.map { l ->
            val calls = when {
                l.calls.all { it == 0L } -> "-"
                l.calls.distinct().size == 1 -> String.format(Locale.ROOT, "%,d in each", l.calls[0])
                else -> String.format(Locale.ROOT, "%,d to %,d", l.calls.min(), l.calls.max())
            }
            val where = if (l.present < runs.size) " (${l.present} of ${runs.size})" else ""
            listOf(
                l.label + where, duration(l.time.mean), plusMinus(l.time),
                if (l.perCall.n == 0) "-" else duration(l.perCall.mean), if (l.perCall.n == 0) "" else plusMinus(l.perCall),
                calls,
            )
        }
    }

    private val coarseExtra by lazy { nameOverflow(RUNS_COARSE_ROW, coarseRows.map { it[0] }) }
    private val fineExtra by lazy { nameOverflow(RUNS_FINE_ROW, fineRows.map { it[0] }) }

    private fun StringBuilder.coarseAcross() {
        if (runs.all { it.coarseTree.isEmpty() }) return
        val format = widened(RUNS_COARSE_ROW, coarseExtra) + if (allocations) " %10s" else ""
        val rule = RUNS_WIDTH + coarseExtra + if (allocations) ALLOCATED_COLUMN else 0
        appendLine()
        appendLine("COARSE OPERATIONS ACROSS RUNS - wall time, inclusive, matched by path; each figure the mean of the runs +- 95%")
        appendLine(
            String.format(
                Locale.ROOT, format, "Coarse operation", "Executions", "Wall v", "", "% parent", "",
                "Mean", "", "p50", "p90", "p99", "Allocated"
            )
        )
        appendLine("-".repeat(rule))
        for (r in coarseRows) appendLine(String.format(Locale.ROOT, format, *r.toTypedArray()).trimEnd())
        appendLine("-".repeat(rule))
        appendLine("  % parent is each run's share of its own parent, +- in percentage points: the run's speed cancels out of it")
        appendLine("  a percentile is the mean of the runs' percentiles, with no interval; - where a run had too few executions for it")
        if (allocations) appendLine("  Allocated: bytes per execution, its coarse children and every thread under it included; the mean of the runs that counted them")
    }

    private fun StringBuilder.fineAcross() {
        appendLine()
        appendLine("COARSE AND FINE OPERATIONS ACROSS RUNS - own thread-time, matched by name; each figure the mean of the runs +- 95%")
        val format = widened(RUNS_FINE_ROW, fineExtra)
        appendLine(String.format(Locale.ROOT, format, "Operation", "Thread-time", "", "Per call", "", "Calls"))
        appendLine("-".repeat(RUNS_WIDTH + fineExtra))
        for (r in fineRows) appendLine(String.format(Locale.ROOT, format, *r.toTypedArray()))
        appendLine("-".repeat(RUNS_WIDTH + fineExtra))
    }

    companion object {
        /** Every profile in [folder]. See [ProfileStore.runs]. */
        internal fun load(folder: Path): ProfileRuns {
            require(java.nio.file.Files.isDirectory(folder)) { "$folder is not a folder of runs" }
            val files = ProfileStore.runs(folder)
            require(files.isNotEmpty()) { "no profile in $folder" }
            // The folder's own name, also for `.` or a drive root, which have none as given.
            val name = folder.toAbsolutePath().normalize().let { (it.fileName ?: it).toString() }
            return ProfileRuns(name, files, files.map { Profile.load(it) })
        }
    }
}
