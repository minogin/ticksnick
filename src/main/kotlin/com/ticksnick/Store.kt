package com.ticksnick

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The first line of a run file, with the format's version. */
internal const val RUN_FORMAT = "ticksnick-run 3"

/** The file extension a saved run gets, and the one a folder of runs is read by. */
internal const val RUN_EXTENSION = ".tsr"

/** The length of the time in a run's file name, `2026-10-27T12-00-00`; a clash number may follow it. */
private const val STAMP_LENGTH = 19

/**
 * A [Run] as a file: what one session left, so that it can be rendered again later and compared
 * with another.
 *
 * **Text, one figure per line, `key<TAB>value`.** Readable, diffable with any tool, and written
 * without a dependency - the library has none and keeps none. A twenty-operation session is a few
 * kilobytes, so there is nothing for a binary format to buy. Arrays are written sparse, `index:value`
 * pairs, because a 2,624-bucket histogram has a few dozen non-empty buckets; a loader that finds an
 * index past its array drops it. A node's durations are written one by one while they are exact,
 * and as its histogram after. A file of another format version is refused rather than read with
 * the wrong histogram buckets. Strings are escaped for tab, newline and backslash. The tree is
 * written in pre-order with a parent index, so a node's parent is always loaded before it.
 *
 * Every field of every constructor is here and nothing derived is: a newer renderer re-renders an
 * old file with whatever it has learned since.
 */
internal object RunStore {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    fun save(p: Run, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, text(p))
    }

    private fun text(p: Run): String {
        val out = StringBuilder()
        fun line(key: String, value: Any) = out.append(key).append('\t').append(value.toString()).append('\n')
        fun text(key: String, value: String?) = line(key, escape(value))
        fun sparse(key: String, a: LongArray) =
            line(key, a.indices.filter { a[it] != 0L }.joinToString(" ") { "$it:${a[it]}" })
        fun spans(key: String, s: Spans) {
            val e = s.exactDurations()
            if (e != null) line(key + "exact", e.joinToString(" ")) else sparse(key + "hist", s.histogram()!!)
        }

        out.append(RUN_FORMAT).append('\n')
        line("idleHits", p.idleHits)
        line("ticks", p.ticks)
        line("samplingSpanNanos", p.samplingSpanNanos)
        line("durationNanos", p.durationNanos)
        line("threads", p.threads)
        text("failure", p.failure)
        line("imbalances", p.imbalances)
        line("stateSampled", p.stateSampled)
        line("idleWaitingHits", p.idleWaitingHits)
        line("openAtEnd", p.openAtEnd)
        line("untrackedSlots", p.untrackedSlots)
        line("reclaimedSlots", p.reclaimedSlots)
        line("openContextsAtEnd", p.openContextsAtEnd)
        line("labelledOutsideCoarse", p.labelledOutsideCoarse)
        line("staleContextHits", p.staleContextHits)
        line("coarseSampleHits", p.coarseSampleHits)

        val d = p.duty
        line("duty.labelledDuty", d.labelledDuty)
        line("duty.runnableDuty", d.runnableDuty)
        line("duty.invisibleOffCpu", d.invisibleOffCpu)
        line("duty.labelledFraction", d.labelledFraction)
        text("duty.reason", d.reason)
        line("duty.resolutionNanos", d.resolutionNanos)
        line("duty.windowNanos", d.windowNanos)
        line("duty.windows", d.windows)
        line("duty.threads", d.threads)
        line("duty.cpuNanos", d.cpuNanos)
        line("duty.wallNanos", d.wallNanos)
        line("duty.minWindowDuty", d.minWindowDuty)
        line("duty.maxWindowDuty", d.maxWindowDuty)
        line("duty.anomalies", d.anomalies)
        line("duty.maxSampleNanos", d.maxSampleNanos)
        line("duty.pausedNanos", d.pausedNanos)
        line("duty.processCpuNanos", d.processCpuNanos)
        line("duty.samplerCpuNanos", d.samplerCpuNanos)
        line("duty.jitNanos", d.jitNanos)
        line("duty.pausedProcessCpuNanos", d.pausedProcessCpuNanos)
        line("duty.profiledCpuNanos", d.profiledCpuNanos)
        line("duty.hiddenWhenFull", d.hiddenWhenFull)
        line("duty.hiddenWhenSpare", d.hiddenWhenSpare)
        line("duty.spanNanos", d.spanNanos)
        line("duty.processors", d.processors)

        val z = p.pause
        line("pause.measured", z.measured)
        line("pause.pausedNanos", z.pausedNanos)
        line("pause.pauses", z.pauses)
        line("pause.longestNanos", z.longestNanos)
        line("pause.gcNanos", z.gcNanos)
        line("pause.confirmedNanos", z.confirmedNanos)

        // Only when there is a clock; a file without these lines loads as no clock recorded. NaN, a
        // second the machine was idle, is written as `-`.
        val clock = p.clock
        if (clock.samples.isNotEmpty() || clock.notMeasured != null) {
            line("clock.ofBase", clock.ofBase)
            text("clock.notMeasured", clock.notMeasured)
            line("clock.samples", clock.samples.joinToString(" ") { if (it.isNaN()) "-" else it.toString() })
        }

        // Only when recorded; a file without them has no `Sampling` line.
        p.wait?.let { line("sampler.wait", it.name) }
        line("sampler.askedStepNanos", p.askedStepNanos)
        if (p.allocations) line("allocations", true)
        if (p.allocationsMissing) line("allocationsMissing", true)

        line("tags", p.tags.size)
        p.tags.entries.forEachIndexed { i, (name, value) ->
            text("tag.$i.name", name)
            text("tag.$i.value", value)
        }
        line("setup", p.setup.facts.size)
        p.setup.facts.entries.forEachIndexed { i, (name, value) ->
            text("setup.$i.name", name)
            text("setup.$i.value", value)
        }
        text("setup.jvm", p.setup.jvm)

        line("operations", p.operations.size)
        p.operations.forEachIndexed { i, o ->
            val k = "op.$i."
            line(k + "id", o.id)
            text(k + "name", o.name)
            line(k + "hits", o.hits)
            line(k + "calls", o.calls)
            line(k + "stuckHits", o.stuckHits)
            line(k + "stuckInstances", o.stuckInstances)
            line(k + "waitingHits", o.waitingHits)
            line(k + "stuckWaitingHits", o.stuckWaitingHits)
            line(k + "activeTicks", o.activeTicks)
            line(k + "threads", o.threads)
            line(k + "peakInside", o.peakInside)
            line(k + "longestStuckTicks", o.longestStuckTicks)
            sparse(k + "stuckRuns", o.stuckRuns)
        }

        line("coarse", p.coarse.size)
        p.coarse.forEachIndexed { i, c ->
            val k = "coarse.$i."
            line(k + "type", c.type)
            text(k + "name", c.name)
            line(k + "count", c.count)
            line(k + "spanSumNanos", c.spanSumNanos)
            line(k + "spanMinNanos", c.spanMinNanos)
            line(k + "spanMaxNanos", c.spanMaxNanos)
            spans(k + "spans.", c.spans)
            line(k + "hits", c.hits)
            line(k + "runningHits", c.runningHits)
            line(k + "inclusiveHits", c.inclusiveHits)
            line(k + "runningInclusiveHits", c.runningInclusiveHits)
            line(k + "instanceTicks", c.instanceTicks)
            line(k + "activeTicks", c.activeTicks)
            sparse(k + "fine", c.fine)
            line(k + "staleHits", c.staleHits)
            line(k + "selfHits", c.selfHits)
            line(k + "selfRunningHits", c.selfRunningHits)
            line(k + "selfActiveTicks", c.selfActiveTicks)
            line(k + "selfPeak", c.selfPeak)
            line(k + "threads", c.threads)
        }

        // The tree in pre-order, each node with the index of its parent, -1 for a root.
        val nodes = ArrayList<Pair<CoarseNode, Int>>()
        fun walk(n: CoarseNode, parent: Int) {
            val me = nodes.size
            nodes += n to parent
            for (c in n.children) walk(c, me)
        }
        for (r in p.coarseTree) walk(r, -1)
        line("nodes", nodes.size)
        nodes.forEachIndexed { i, (n, parent) ->
            val k = "node.$i."
            line(k + "parent", parent)
            line(k + "type", n.type)
            text(k + "name", n.name)
            line(k + "count", n.count)
            line(k + "spanSumNanos", n.spanSumNanos)
            line(k + "spanMinNanos", n.spanMinNanos)
            line(k + "spanMaxNanos", n.spanMaxNanos)
            spans(k + "spans.", n.spans)
            line(k + "inclusiveHits", n.inclusiveHits)
            line(k + "runningInclusiveHits", n.runningInclusiveHits)
            sparse(k + "fine", n.fine)
            line(k + "pausedNanos", n.pausedNanos)
            line(k + "instanceTicks", n.instanceTicks)
            line(k + "coveredTicks", n.coveredTicks)
            if (n.ownFine.isNotEmpty()) sparse(k + "ownFine", n.ownFine)
            if (n.ownFineCovered.isNotEmpty()) sparse(k + "ownFineCovered", n.ownFineCovered)
            if (n.calls.isNotEmpty()) sparse(k + "calls", n.calls)
            if (n.allocated != 0L) line(k + "allocated", n.allocated)
            // Only where there are any; a node without these lines loads with none.
            if (n.slowest.isNotEmpty()) {
                line(k + "slowest", n.slowest.size)
                n.slowest.forEachIndexed { j, slow ->
                    line(k + "slowest.$j.nanos", slow.nanos)
                    text(k + "slowest.$j.key", slow.key)
                }
            }
        }
        return out.toString()
    }

    fun load(path: Path): Run {
        val lines = Files.readAllLines(path)
        require(lines.isNotEmpty() && lines[0].startsWith("ticksnick-")) { "$path is not a TickSnick run" }
        require(lines[0] == RUN_FORMAT) { "$path is format '${lines[0]}', this build reads '$RUN_FORMAT'" }
        val m = HashMap<String, String>()
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val t = l.indexOf('\t')
            require(t > 0) { "$path: a line without a tab: $l" }
            m[l.substring(0, t)] = l.substring(t + 1)
        }
        fun str(k: String): String = m[k] ?: throw IllegalArgumentException("$path: missing $k")
        fun long(k: String) = str(k).toLong()
        fun int(k: String) = str(k).toInt()
        fun dbl(k: String) = str(k).toDouble()
        fun bool(k: String) = str(k).toBoolean()
        fun text(k: String): String? = unescape(str(k))
        fun sparse(k: String, size: Int): LongArray {
            val a = LongArray(size)
            val v = str(k)
            if (v.isEmpty()) return a
            for (pair in v.split(' ')) {
                val c = pair.indexOf(':')
                val i = pair.substring(0, c).toInt()
                if (i in 0 until size) a[i] = pair.substring(c + 1).toLong()
            }
            return a
        }
        fun spans(k: String): Spans {
            val e = m[k + "exact"] ?: return Spans.ofHistogram(sparse(k + "hist", SpanHistogram.BUCKETS))
            return Spans.of(e.split(' ').filter { it.isNotEmpty() }.map { it.toLong() })
        }

        val duty = DutyReport(
            labelledDuty = dbl("duty.labelledDuty"),
            // Optional: a file without it loads NaN, and the duty warning falls back to the labelled duty.
            runnableDuty = m["duty.runnableDuty"]?.toDouble() ?: Double.NaN,
            invisibleOffCpu = dbl("duty.invisibleOffCpu"),
            labelledFraction = dbl("duty.labelledFraction"), reason = text("duty.reason"),
            resolutionNanos = long("duty.resolutionNanos"), windowNanos = long("duty.windowNanos"),
            windows = int("duty.windows"), threads = int("duty.threads"),
            cpuNanos = long("duty.cpuNanos"), wallNanos = long("duty.wallNanos"),
            minWindowDuty = dbl("duty.minWindowDuty"), maxWindowDuty = dbl("duty.maxWindowDuty"),
            anomalies = int("duty.anomalies"), maxSampleNanos = long("duty.maxSampleNanos"),
            pausedNanos = long("duty.pausedNanos"),
            // Optional, like runnableDuty: a file without these has no cores line.
            processCpuNanos = m["duty.processCpuNanos"]?.toLong() ?: -1L,
            samplerCpuNanos = m["duty.samplerCpuNanos"]?.toLong() ?: 0L,
            jitNanos = m["duty.jitNanos"]?.toLong() ?: -1L,
            pausedProcessCpuNanos = m["duty.pausedProcessCpuNanos"]?.toLong() ?: 0L,
            profiledCpuNanos = m["duty.profiledCpuNanos"]?.toLong() ?: -1L,
            hiddenWhenFull = m["duty.hiddenWhenFull"]?.toDouble() ?: 0.0,
            hiddenWhenSpare = m["duty.hiddenWhenSpare"]?.toDouble() ?: 0.0,
            spanNanos = m["duty.spanNanos"]?.toLong() ?: 0L,
            processors = m["duty.processors"]?.toInt() ?: 0,
        )
        val pause = PauseReport(
            measured = bool("pause.measured"), pausedNanos = long("pause.pausedNanos"),
            pauses = long("pause.pauses"), longestNanos = long("pause.longestNanos"),
            gcNanos = long("pause.gcNanos"), confirmedNanos = long("pause.confirmedNanos"),
        )
        val clock = if ("clock.ofBase" !in m) ClockReport.NOT_RECORDED else ClockReport(
            samples = str("clock.samples").split(' ').filter { it.isNotEmpty() }
                .map { if (it == "-") Double.NaN else it.toDouble() }.toDoubleArray(),
            ofBase = bool("clock.ofBase"),
            notMeasured = text("clock.notMeasured"),
        )
        // Optional: a run saved without them has no tags and no setup, and says nothing about either.
        fun pairs(key: String, each: String): Map<String, String> = (0 until (m[key]?.toInt() ?: 0)).associate { i ->
            (text("$each.$i.name") ?: "") to (text("$each.$i.value") ?: "")
        }
        val setup = pairs("setup", "setup").let { if (it.isEmpty()) Setup.NONE else Setup(it, text("setup.jvm")) }
        val operations = (0 until int("operations")).map { i ->
            val k = "op.$i."
            OperationStat(
                int(k + "id"), text(k + "name") ?: "", long(k + "hits"), long(k + "calls"),
                long(k + "stuckHits"), long(k + "stuckInstances"), long(k + "waitingHits"),
                long(k + "stuckWaitingHits"), long(k + "activeTicks"), int(k + "threads"), int(k + "peakInside"),
                longestStuckTicks = long(k + "longestStuckTicks"),
                stuckRuns = sparse(k + "stuckRuns", STUCK_BUCKETS),
            )
        }
        val coarse = (0 until int("coarse")).map { i ->
            val k = "coarse.$i."
            CoarseStat(
                int(k + "type"), text(k + "name") ?: "", long(k + "count"), long(k + "spanSumNanos"),
                long(k + "spanMinNanos"), long(k + "spanMaxNanos"), spans(k + "spans."),
                long(k + "hits"), long(k + "runningHits"), long(k + "inclusiveHits"), long(k + "runningInclusiveHits"),
                long(k + "instanceTicks"), long(k + "activeTicks"), sparse(k + "fine", MAX_OPERATIONS + 1),
                staleHits = long(k + "staleHits"), selfHits = long(k + "selfHits"),
                selfRunningHits = long(k + "selfRunningHits"), selfActiveTicks = long(k + "selfActiveTicks"),
                selfPeak = int(k + "selfPeak"), threads = int(k + "threads"),
            )
        }
        // The tree: read every node flat, then build from the leaves up, since a node's children
        // are a value of its own.
        val count = int("nodes")
        val parents = IntArray(count) { int("node.$it.parent") }
        val childrenOf = HashMap<Int, MutableList<Int>>()
        for (i in 0 until count) childrenOf.getOrPut(parents[i]) { ArrayList() }.add(i)
        fun build(i: Int): CoarseNode {
            val k = "node.$i."
            return CoarseNode(
                type = int(k + "type"), name = text(k + "name") ?: "", count = long(k + "count"),
                spanSumNanos = long(k + "spanSumNanos"), spanMinNanos = long(k + "spanMinNanos"),
                spanMaxNanos = long(k + "spanMaxNanos"), spans = spans(k + "spans."),
                inclusiveHits = long(k + "inclusiveHits"), runningInclusiveHits = long(k + "runningInclusiveHits"),
                fine = sparse(k + "fine", MAX_OPERATIONS + 1), pausedNanos = long(k + "pausedNanos"),
                // Optional: a file without them reads as never sampled - every row at a parallelism
                // of 1, so its Wall is its Total.
                instanceTicks = m[k + "instanceTicks"]?.toLong() ?: 0L, coveredTicks = m[k + "coveredTicks"]?.toLong() ?: 0L,
                // Optional: without them a node has no own fine rows (see Run.ownRowsOf).
                ownFine = if (k + "ownFine" in m) sparse(k + "ownFine", MAX_OPERATIONS + 1) else LongArray(0),
                ownFineCovered = if (k + "ownFineCovered" in m) sparse(k + "ownFineCovered", MAX_OPERATIONS + 1) else LongArray(0),
                // Optional: without them a fine row under a coarse one has no calls (see Run.ownRowsOf).
                calls = if (k + "calls" in m) sparse(k + "calls", MAX_OPERATIONS) else LongArray(0),
                // Optional: only a session that counted them has any.
                allocated = m[k + "allocated"]?.toLong() ?: 0L,
                children = (childrenOf[i] ?: emptyList<Int>()).map { build(it) },
                slowest = (0 until (m[k + "slowest"]?.toInt() ?: 0)).map {
                    SlowExecution(text(k + "slowest.$it.key") ?: "", long(k + "slowest.$it.nanos"))
                },
            )
        }
        val tree = (childrenOf[-1] ?: emptyList<Int>()).map { build(it) }

        return Run(
            operations = operations, idleHits = long("idleHits"), ticks = long("ticks"),
            samplingSpanNanos = long("samplingSpanNanos"), durationNanos = long("durationNanos"),
            threads = int("threads"), duty = duty, failure = text("failure"), imbalances = int("imbalances"),
            stateSampled = bool("stateSampled"), idleWaitingHits = long("idleWaitingHits"),
            openAtEnd = int("openAtEnd"), untrackedSlots = int("untrackedSlots"), reclaimedSlots = int("reclaimedSlots"),
            coarse = coarse, openContextsAtEnd = int("openContextsAtEnd"),
            labelledOutsideCoarse = long("labelledOutsideCoarse"), staleContextHits = long("staleContextHits"),
            coarseSampleHits = long("coarseSampleHits"), pause = pause, coarseTree = tree, clock = clock,
            wait = m["sampler.wait"]?.let { WaitStrategy.valueOf(it) },
            askedStepNanos = m["sampler.askedStepNanos"]?.toLong() ?: 0L,
            allocations = m["allocations"]?.toBoolean() ?: false,
            allocationsMissing = m["allocationsMissing"]?.toBoolean() ?: false,
            setup = setup,
            tags = pairs("tags", "tag"),
        )
    }

    /**
     * [p] saved as a new run in [folder], the folder created; returns the file. The name is to the
     * second, so a second run inside one second gets `-2` rather than overwriting the first - also
     * when two threads or two JVMs save at once: a name is taken by creating its file, which only one
     * of them can do.
     *
     * **One folder per profile, one file per run.** Every file in a folder is a run of that profile,
     * which is what a report across runs reads - so keeping the folder to same code, same workload,
     * same setup is the user's part: a run that differs goes into a new folder, and a run not to
     * count is moved out.
     */
    fun saveNew(p: Run, folder: Path, now: LocalDateTime = LocalDateTime.now()): Path {
        val text = text(p)
        Files.createDirectories(folder)
        val stamp = now.format(STAMP)
        var n = 1
        while (true) {
            val file = folder.resolve((if (n == 1) stamp else "$stamp-$n") + RUN_EXTENSION)
            try {
                Files.writeString(file, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return file
            } catch (_: FileAlreadyExistsException) {
                n++
            }
        }
    }

    /** Every run in [folder], oldest first; none when there is no such folder. */
    fun runs(folder: Path): List<Path> {
        if (!Files.isDirectory(folder)) return emptyList()
        // By the time in the name, then by its clash number: `-2` after the run it follows, `-10` after `-9`.
        fun stem(p: Path): String = p.fileName.toString().removeSuffix(RUN_EXTENSION)
        fun stamp(p: Path): String = stem(p).take(STAMP_LENGTH)
        fun clash(p: Path): Int = stem(p).drop(STAMP_LENGTH).removePrefix("-").toIntOrNull() ?: 1
        return Files.list(folder).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(RUN_EXTENSION) }
                .sorted(compareBy<Path>({ stamp(it) }, { clash(it) }, { stem(it) })).toList()
        }
    }

    /** A null string is written as a single `\0`, so it comes back as null and not as "". */
    private fun escape(s: String?): String =
        if (s == null) "\\0"
        else s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String? {
        if (s == "\\0") return null
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
