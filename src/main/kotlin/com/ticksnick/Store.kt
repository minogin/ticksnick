package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The first line of a profile file, with the format's version. */
const val PROFILE_FORMAT = "ticksnick-profile 1"

/** The file extension a saved profile gets, and the one [Profile.diffAgainstLatest] looks for. */
const val PROFILE_EXTENSION = ".tsp"

/**
 * A [Profile] as a file: the data of one session, so that it can be rendered again later and
 * compared with another.
 *
 * **Text, one figure per line, `key<TAB>value`.** Readable, diffable with any tool, and written
 * without a dependency - the library has none and keeps none. A twenty-operation session is a few
 * kilobytes, so there is nothing for a binary format to buy. Arrays are written sparse, `index:value`
 * pairs, because a 328-bucket histogram has a dozen non-empty buckets; a loader that finds an
 * index past its array drops it, so a change in a bucket count between versions does not break a
 * file. Strings are escaped for tab, newline and backslash. The tree is written in pre-order with a
 * parent index, so a node's parent is always loaded before it.
 *
 * Every field of every constructor is here and nothing derived is: a newer renderer re-renders an
 * old file with whatever it has learned since.
 */
object ProfileStore {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    fun save(p: Profile, path: Path) {
        val out = StringBuilder()
        fun line(key: String, value: Any) = out.append(key).append('\t').append(value.toString()).append('\n')
        fun text(key: String, value: String?) = line(key, escape(value))
        fun sparse(key: String, a: LongArray) =
            line(key, a.indices.filter { a[it] != 0L }.joinToString(" ") { "$it:${a[it]}" })

        out.append(PROFILE_FORMAT).append('\n')
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

        val z = p.pause
        line("pause.measured", z.measured)
        line("pause.pausedNanos", z.pausedNanos)
        line("pause.pauses", z.pauses)
        line("pause.longestNanos", z.longestNanos)
        line("pause.gcNanos", z.gcNanos)
        line("pause.confirmedNanos", z.confirmedNanos)

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
            sparse(k + "hist", c.hist)
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
            sparse(k + "hist", n.hist)
            line(k + "inclusiveHits", n.inclusiveHits)
            line(k + "runningInclusiveHits", n.runningInclusiveHits)
            sparse(k + "fine", n.fine)
            line(k + "pausedNanos", n.pausedNanos)
        }
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, out.toString())
    }

    fun load(path: Path): Profile {
        val lines = Files.readAllLines(path)
        require(lines.isNotEmpty() && lines[0].startsWith("ticksnick-profile ")) { "$path is not a TickSnick profile" }
        require(lines[0] == PROFILE_FORMAT) { "$path is format '${lines[0]}', this build reads '$PROFILE_FORMAT'" }
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

        val duty = DutyReport(
            labelledDuty = dbl("duty.labelledDuty"), invisibleOffCpu = dbl("duty.invisibleOffCpu"),
            labelledFraction = dbl("duty.labelledFraction"), reason = text("duty.reason"),
            resolutionNanos = long("duty.resolutionNanos"), windowNanos = long("duty.windowNanos"),
            windows = int("duty.windows"), threads = int("duty.threads"),
            cpuNanos = long("duty.cpuNanos"), wallNanos = long("duty.wallNanos"),
            minWindowDuty = dbl("duty.minWindowDuty"), maxWindowDuty = dbl("duty.maxWindowDuty"),
            anomalies = int("duty.anomalies"), maxSampleNanos = long("duty.maxSampleNanos"),
            pausedNanos = long("duty.pausedNanos"),
        )
        val pause = PauseReport(
            measured = bool("pause.measured"), pausedNanos = long("pause.pausedNanos"),
            pauses = long("pause.pauses"), longestNanos = long("pause.longestNanos"),
            gcNanos = long("pause.gcNanos"), confirmedNanos = long("pause.confirmedNanos"),
        )
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
                long(k + "spanMinNanos"), long(k + "spanMaxNanos"), sparse(k + "hist", SpanHistogram.BUCKETS),
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
                spanMaxNanos = long(k + "spanMaxNanos"), hist = sparse(k + "hist", SpanHistogram.BUCKETS),
                inclusiveHits = long(k + "inclusiveHits"), runningInclusiveHits = long(k + "runningInclusiveHits"),
                fine = sparse(k + "fine", MAX_OPERATIONS + 1), pausedNanos = long(k + "pausedNanos"),
                children = (childrenOf[i] ?: emptyList<Int>()).map { build(it) },
            )
        }
        val tree = (childrenOf[-1] ?: emptyList<Int>()).map { build(it) }

        return Profile(
            operations = operations, idleHits = long("idleHits"), ticks = long("ticks"),
            samplingSpanNanos = long("samplingSpanNanos"), durationNanos = long("durationNanos"),
            threads = int("threads"), duty = duty, failure = text("failure"), imbalances = int("imbalances"),
            stateSampled = bool("stateSampled"), idleWaitingHits = long("idleWaitingHits"),
            openAtEnd = int("openAtEnd"), untrackedSlots = int("untrackedSlots"), reclaimedSlots = int("reclaimedSlots"),
            coarse = coarse, openContextsAtEnd = int("openContextsAtEnd"),
            labelledOutsideCoarse = long("labelledOutsideCoarse"), staleContextHits = long("staleContextHits"),
            coarseSampleHits = long("coarseSampleHits"), pause = pause, coarseTree = tree,
        )
    }

    /** The name a profile saved now gets: the time, then the label if there is one. */
    fun fileName(label: String, now: LocalDateTime = LocalDateTime.now()): String =
        now.format(STAMP) + (if (label.isEmpty()) "" else " $label") + PROFILE_EXTENSION

    /**
     * The newest profile in [dir] by name, which is by time since the name starts with it; with
     * [label], the newest saved under that label.
     */
    fun latest(dir: Path, label: String? = null): Path? {
        if (!Files.isDirectory(dir)) return null
        // The name without its extension and without the `-N` a second save in one second gets:
        // what is left is `<time> <label>`, and sorting that puts `-2` after the original.
        fun stem(p: Path): String = p.fileName.toString().removeSuffix(PROFILE_EXTENSION)
        fun labelled(p: Path): String = stem(p).replace(Regex("-[0-9]+$"), "")
        return Files.list(dir).use { s ->
            s.filter { it.fileName.toString().endsWith(PROFILE_EXTENSION) }
                .filter { label == null || labelled(it).endsWith(" $label") }
                .sorted(compareBy { stem(it) })
                .reduce { _, b -> b }.orElse(null)
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
