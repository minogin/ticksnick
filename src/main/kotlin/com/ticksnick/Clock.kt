package com.ticksnick

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** How far the clock may move within a run, or differ between two runs, before the report says so. */
const val CLOCK_MOVED = 0.15

/** How often the clock is read. */
const val CLOCK_PERIOD_MILLIS = 1000L

/** Fewer clock samples than this say nothing about a trend, and the report stays silent on them. */
const val MIN_CLOCK_SAMPLES = 5

/**
 * A second in which the cores together were busier than this many cores' worth is a sample; a
 * quieter second is a gap. An idle machine's clock is whatever the power manager left it at, and
 * says nothing about the speed the work ran at.
 */
const val MIN_BUSY_CORES = 0.5

/** The Windows counters, by their English names. A localised Windows names them differently. */
private const val WINDOWS_PERFORMANCE = "% Processor Performance"
private const val WINDOWS_TIME = "% Processor Time"

/**
 * The CPU clock over a session: one sample a second, the clock of the cores that did the work.
 *
 * Seen on maven-graph, 2026-10-17: a six-minute run started at 205% of the base clock and spent its
 * last four minutes at 135%, and the measured batches took 1.5x the warmup's time for that reason
 * alone. Nothing in the report said so. The clock was traced only because a script ran beside the
 * profiler, and a user does not run one.
 *
 * Each sample is the mean of the per-core clocks **weighted by how busy each core was** that second:
 * a single-threaded run reads the clock of the one core it ran on, not an average diluted by idle
 * cores parked at a low frequency. Read from the OS, not by timing a piece of work on a thread of
 * our own, which would measure the core that thread landed on - on a hybrid CPU possibly an
 * efficiency core reporting a drop that never happened - and which would take a core from the run.
 */
class ClockReport internal constructor(
    /** One per [CLOCK_PERIOD_MILLIS], NaN for a second the machine was idle or the read failed. */
    val samples: DoubleArray,
    /** True: percentages of the base clock. False: MHz, where the OS does not publish the base. */
    val ofBase: Boolean,
    /** Why the clock was not measured, or null when it was. */
    val notMeasured: String?,
) {
    private val valid: List<Double> get() = samples.filter { !it.isNaN() }

    /** The mean over the samples, NaN when there are none. */
    val mean: Double get() = valid.let { if (it.isEmpty()) Double.NaN else it.average() }

    /** A clock reading in the report's unit: `205%` of base, or `2,730 MHz`. */
    fun format(v: Double): String = when {
        v.isNaN() -> "-"
        ofBase -> String.format(Locale.ROOT, "%.0f%%", v)
        else -> String.format(Locale.ROOT, "%,.0f MHz", v)
    }

    /**
     * A `Careful` line when the clock moved during the run or could not be read; none on a steady
     * clock, which is the healthy case and says nothing.
     *
     * "Moved" is the spread between the 10th and the 90th percentile of the samples, not the minimum
     * and the maximum: one second caught in a collection or a page fault is not a trend.
     */
    fun careful(): List<String> {
        notMeasured?.let { return listOf("clock not measured: $it") }
        val v = valid
        if (v.size < MIN_CLOCK_SAMPLES) return emptyList()
        val sorted = v.sorted()
        val low = quantile(sorted, 0.1)
        val high = quantile(sorted, 0.9)
        if (high <= low * (1 + CLOCK_MOVED)) return emptyList()
        // Up to five samples at each end, and never more than a third of them, so that on a short run
        // the start and the end are not the same seconds.
        val ends = (v.size / 3).coerceIn(1, MIN_CLOCK_SAMPLES)
        val start = median(v.take(ends))
        val end = median(v.takeLast(ends))
        return listOf(
            "the CPU clock moved during the run: ${format(start)}${unit()} at the start, ${format(end)} at the end, " +
                    "${format(low)} to ${format(high)} for 80% of it - times and per-call costs drift with it, shares hold"
        )
    }

    private fun unit(): String = if (ofBase) " of base" else ""

    internal companion object {
        /** A profile saved before the clock was recorded, or built by hand: nothing to say. */
        val NOT_RECORDED = ClockReport(DoubleArray(0), ofBase = true, notMeasured = null)

        /**
         * A `Clock` line for the diff, and a `Careful` line under it when the two runs ran at clocks
         * further apart than [CLOCK_MOVED]: a time difference that size may be the machine, not the
         * code. Empty when neither side recorded a clock.
         */
        fun diffLines(before: ClockReport, after: ClockReport): List<String> {
            val a = before.mean
            val b = after.mean
            if (a.isNaN() && b.isNaN()) return emptyList()
            val unit = (if (!b.isNaN()) after else before).unit()
            val lines = mutableListOf(row("Clock", "${before.format(a)} -> ${after.format(b)}$unit, the mean of each run"))
            if (!a.isNaN() && !b.isNaN() && before.ofBase == after.ofBase && max(a, b) > min(a, b) * (1 + CLOCK_MOVED)) {
                lines += row(
                    "Careful", String.format(
                        Locale.ROOT, "the two runs ran at different clocks: up to %.2fx of a time difference is the " +
                                "machine, not the code - shares hold", max(a, b) / min(a, b)
                    )
                )
            }
            return lines
        }

        private fun quantile(sorted: List<Double>, q: Double): Double = sorted[Math.round((sorted.size - 1) * q).toInt()]

        private fun median(v: List<Double>): Double = quantile(v.sorted(), 0.5)
    }
}

/**
 * Reads the clock once a second on a thread of its own, between [start] and [stop].
 *
 * The thread sleeps or blocks between reads and never touches a label, so the sampler does not see
 * it and the run loses no core to it. On Windows the reads come from a `typeperf` child process,
 * which keeps the library free of a native binding.
 */
internal class ClockProbe {
    private val samples = ArrayList<Double>()
    @Volatile private var running = false
    @Volatile private var failure: String? = null
    @Volatile private var source: ClockSource? = null
    private var thread: Thread? = null

    /** Returns at once: the source is opened on the probe's thread, since `typeperf` takes a second to start. */
    fun start() {
        running = true
        thread = Thread({
            try {
                val s = ClockSource.forThisOs()
                source = s
                while (running) {
                    val v = s.next()
                    if (running) synchronized(samples) { samples += v }
                }
                s.close()
            } catch (e: ClockUnavailable) {
                failure = e.message
            } catch (e: Exception) {
                if (running) failure = "the clock stopped being readable: ${e.message}"
            } finally {
                source?.close()
            }
        }, "ticksnick-clock").apply { isDaemon = true; start() }
    }

    fun stop(): ClockReport {
        running = false
        source?.close()
        // Not waiting for the thread: what it read is in [samples] already, and a `typeperf` still starting
        // closes itself when it sees the probe stopped.
        thread?.join(100)
        val values = synchronized(samples) { samples.toDoubleArray() }
        return ClockReport(values, ofBase = source?.ofBase ?: true, notMeasured = failure)
    }
}

internal class ClockUnavailable(message: String) : Exception(message)

/** One OS's way to read the busy-weighted clock. [next] blocks for about a second. */
internal interface ClockSource : AutoCloseable {
    val ofBase: Boolean

    /** The next second's busy-weighted clock, NaN when the machine was idle or a read failed. */
    fun next(): Double

    override fun close()

    companion object {
        fun forThisOs(): ClockSource {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") -> WindowsClock()
                os.startsWith("linux") -> LinuxClock()
                os.startsWith("mac") -> throw ClockUnavailable(
                    "macOS reads the CPU clock only as root (powermetrics), so times cannot be checked against it"
                )
                else -> throw ClockUnavailable("no way to read the CPU clock on ${System.getProperty("os.name")}")
            }
        }

        /** The mean of [clocks] weighted by [busy], NaN when the cores together were idle. */
        fun weighted(clocks: DoubleArray, busy: DoubleArray): Double {
            var sum = 0.0
            var weight = 0.0
            for (i in clocks.indices) {
                if (clocks[i].isNaN() || busy[i].isNaN() || busy[i] <= 0) continue
                sum += clocks[i] * busy[i]
                weight += busy[i]
            }
            return if (weight < MIN_BUSY_CORES) Double.NaN else sum / weight
        }
    }
}

/**
 * `typeperf` with the per-core `% Processor Performance` (the clock as a percentage of base, above
 * 100 in turbo) and `% Processor Time` (how busy), one CSV line a second. English counter names only.
 */
internal class WindowsClock : ClockSource {
    override val ofBase = true
    private val process: Process
    private val reader: BufferedReader
    /** For each core: the column of its clock and the column of its busy time. */
    private val columns: List<Pair<Int, Int>>

    init {
        process = try {
            ProcessBuilder(
                "typeperf", "\\Processor Information(*)\\$WINDOWS_PERFORMANCE", "\\Processor Information(*)\\$WINDOWS_TIME",
                "-si", (CLOCK_PERIOD_MILLIS / 1000).toString(),
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw ClockUnavailable("typeperf could not be started: ${e.message}")
        }
        reader = BufferedReader(InputStreamReader(process.inputStream))
        val header = generateSequence { reader.readLine() }.firstOrNull { it.startsWith("\"(PDH-CSV") }
        if (header == null) {
            process.destroy()
            throw ClockUnavailable(
                "typeperf found no '$WINDOWS_PERFORMANCE' counter; the counter names are English and a localised Windows names them differently"
            )
        }
        columns = typeperfColumns(header)
        if (columns.isEmpty()) {
            process.destroy()
            throw ClockUnavailable("typeperf listed no per-core '$WINDOWS_PERFORMANCE' counters")
        }
    }

    override fun next(): Double {
        val line = reader.readLine() ?: throw IllegalStateException("typeperf exited")
        return typeperfSample(line, columns)
    }

    override fun close() {
        process.destroy()
    }

    companion object {
        /** Pairs each core's clock column with its busy column, by the instance in the counter path. */
        fun typeperfColumns(header: String): List<Pair<Int, Int>> {
            val cells = csv(header)
            val clock = HashMap<String, Int>()
            val busy = HashMap<String, Int>()
            for ((i, cell) in cells.withIndex()) {
                val instance = cell.substringAfter('(', "").substringBefore(')', "")
                if (instance.isEmpty() || "_Total" in instance) continue
                when {
                    cell.endsWith(WINDOWS_PERFORMANCE) -> clock[instance] = i
                    cell.endsWith(WINDOWS_TIME) -> busy[instance] = i
                }
            }
            return clock.keys.filter { it in busy }.sorted().map { clock.getValue(it) to busy.getValue(it) }
        }

        /** One line of values: the busy-weighted clock, with `% Processor Time` read as cores (100% = 1). */
        fun typeperfSample(line: String, columns: List<Pair<Int, Int>>): Double {
            val cells = csv(line)
            fun at(i: Int): Double = cells.getOrNull(i)?.trim()?.toDoubleOrNull() ?: Double.NaN
            val clocks = DoubleArray(columns.size) { at(columns[it].first) }
            val busy = DoubleArray(columns.size) { at(columns[it].second) / 100 }
            return ClockSource.weighted(clocks, busy)
        }

        private fun csv(line: String): List<String> =
            line.trim().removePrefix("\"").removeSuffix("\"").split("\",\"")
    }
}

/**
 * `/proc/stat` for how busy each core was over the last second and `scaling_cur_freq` for its clock,
 * as a percentage of `base_frequency` where the driver publishes it (intel_pstate), in MHz otherwise.
 */
internal class LinuxClock(private val root: Path = Path.of("/")) : ClockSource {
    private val cpus: List<Int>
    private val base: DoubleArray?
    @Volatile private var closed = false
    private var lastStat: Map<Int, LongArray>

    init {
        lastStat = try {
            procStat(Files.readAllLines(root.resolve("proc/stat")))
        } catch (e: Exception) {
            throw ClockUnavailable("/proc/stat could not be read: ${e.message}")
        }
        cpus = lastStat.keys.sorted().filter { Files.isReadable(freqFile(it, "scaling_cur_freq")) }
        if (cpus.isEmpty()) throw ClockUnavailable("no core publishes cpufreq/scaling_cur_freq")
        val bases = cpus.map { readKhz(freqFile(it, "base_frequency")) }
        base = if (bases.all { !it.isNaN() && it > 0 }) bases.toDoubleArray() else null
    }

    override val ofBase: Boolean get() = base != null

    override fun next(): Double {
        Thread.sleep(CLOCK_PERIOD_MILLIS)
        if (closed) return Double.NaN
        val stat = procStat(Files.readAllLines(root.resolve("proc/stat")))
        val busy = DoubleArray(cpus.size) { i ->
            val now = stat[cpus[i]]
            val before = lastStat[cpus[i]]
            if (now == null || before == null) Double.NaN else busyFraction(before, now)
        }
        lastStat = stat
        val clocks = DoubleArray(cpus.size) { i ->
            val khz = readKhz(freqFile(cpus[i], "scaling_cur_freq"))
            if (base != null) khz / base[i] * 100 else khz / 1000
        }
        return ClockSource.weighted(clocks, busy)
    }

    override fun close() {
        closed = true
    }

    private fun freqFile(cpu: Int, name: String): Path = root.resolve("sys/devices/system/cpu/cpu$cpu/cpufreq/$name")

    private fun readKhz(p: Path): Double = try {
        Files.readString(p).trim().toDouble()
    } catch (_: Exception) {
        Double.NaN
    }

    companion object {
        /** Each `cpuN` line's counters, by N. The aggregate `cpu` line is skipped. */
        fun procStat(lines: List<String>): Map<Int, LongArray> = lines
            .filter { it.startsWith("cpu") && it.length > 3 && it[3].isDigit() }
            .associate { l ->
                val parts = l.trim().split(Regex("\\s+"))
                parts[0].removePrefix("cpu").toInt() to parts.drop(1).map { it.toLong() }.toLongArray()
            }

        /** The busy fraction between two readings: everything but idle and iowait, the 4th and 5th counters. */
        fun busyFraction(before: LongArray, now: LongArray): Double {
            val total = now.sum() - before.sum()
            if (total <= 0) return Double.NaN
            val idle = (now[3] - before[3]) + (if (now.size > 4) now[4] - before[4] else 0)
            return (total - idle).toDouble() / total
        }
    }
}
