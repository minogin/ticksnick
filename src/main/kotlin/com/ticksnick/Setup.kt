package com.ticksnick

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** How many different values of one name a line lists before it gives their range or counts the rest. */
internal const val MAX_VALUES_LISTED = 3

/** The argument with which OpenJ9 names its own options file. */
private const val OPTIONS_FILE = "-Xoptionsfile="

/**
 * The JVM options a run keeps in `Flags`: only those that change how the program runs - the heap, the
 * collector, processors and memory pages, the compiler. Opt-in: an option not here is left out, so a
 * path, a log's name or an agent's setting never reaches a run file nor makes runs differ. `-X` options
 * by prefix; `-XX` options by name, in either form, `+` or `-`, with any value, `*` matching any run of
 * characters. See [keptFlag].
 */
private val KEPT_X = listOf("-Xms", "-Xmx", "-Xmn", "-Xss", "-Xint", "-Xcomp", "-Xgcpolicy")
private val KEPT_XX = listOf(
    "MaxRAMPercentage", "InitialRAMPercentage", "NewRatio", "NewSize", "MaxNewSize",
    "Use*GC", "ZGenerational", "ParallelGCThreads", "ConcGCThreads", "MaxGCPauseMillis", "G1*", "AlwaysPreTouch",
    "ActiveProcessorCount", "UseNUMA", "UseLargePages", "UseTransparentHugePages",
    "TieredCompilation", "TieredStopAtLevel", "CICompilerCount", "UseCompressedOops",
)

/** Whether [arg], one of the JVM's arguments, is kept in `Flags`. See [KEPT_X]. */
internal fun keptFlag(arg: String): Boolean {
    if (!arg.startsWith("-XX:")) return KEPT_X.any { arg.startsWith(it) }
    val name = arg.removePrefix("-XX:").removePrefix("+").removePrefix("-").substringBefore('=')
    return KEPT_XX.any { p ->
        if ('*' !in p) name == p else name.startsWith(p.substringBefore('*')) && name.endsWith(p.substringAfter('*'))
    }
}

/**
 * The collector, as the JVM's flag names it, from the name of one of its beans. Parallel's,
 * Serial's and OpenJ9's beans have names of their own, `PS Scavenge`, `Copy` and `scavenge` for
 * gencon; the others start with the collector's: `G1 Young Generation` is G1, `ZGC Minor Cycles` ZGC,
 * `Shenandoah Pauses` Shenandoah.
 */
internal fun collectorName(bean: String): String = when (bean) {
    "PS Scavenge", "PS MarkSweep" -> "Parallel"
    "Copy", "MarkSweepCompact" -> "Serial"
    "scavenge" -> "gencon"
    else -> bean.substringBefore(' ')
}

/**
 * The JVM's arguments less those it added itself. OpenJ9 adds the options of its own
 * `options.default`, after `-Xoptionsfile=` naming that file: a local path, and none of them the
 * user's. [read] reads the file; one that cannot be read leaves its options in, the path still out.
 */
internal fun userArguments(args: List<String>, read: (Path) -> String = { Files.readString(it) }): List<String> {
    val named = args.firstOrNull { it.startsWith(OPTIONS_FILE) } ?: return args
    val added = runCatching { read(Path.of(named.removePrefix(OPTIONS_FILE))) }.getOrDefault("")
        .let { text -> Regex("\"([^\"]*)\"|(\\S+)").findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet() }
    return args.filter { it != named && it !in added }
}

/**
 * What a run can say by itself about how and where it ran, read at `start()`. A value is compared
 * between the runs of a folder, so it has to stay the same from one run to the next when nothing changed.
 *
 * **Flags are the options that change how the program runs**, kept by name ([keptFlag]). `-D` can hold a password, and
 * a file is meant to be kept and shared; a path, a log's name or an agent's port is not a fact about the run.
 * The program's own arguments are left out for the same reason: what matters in them is a tag.
 *
 * @param facts name to value, in the order they print.
 * @param jvm the JVM's identity, which no two JVMs share: two runs with the same one are not independent.
 */
internal class Setup(val facts: Map<String, String>, val jvm: String?) {
    companion object {
        /** A run saved without a setup, or built by hand: nothing to show and nothing to compare. */
        val NONE = Setup(emptyMap(), null)

        fun read(): Setup {
            val runtime = ManagementFactory.getRuntimeMXBean()
            val facts = LinkedHashMap<String, String>()
            fun fact(name: String, value: String?) {
                if (!value.isNullOrBlank()) facts[name] = value
            }
            fact("Java", System.getProperty("java.version"))
            fact("JVM", System.getProperty("java.vm.name"))
            fact("OS", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            fact("CPU", cpuModel())
            fact("Processors", Runtime.getRuntime().availableProcessors().toString())
            fact("Heap", bytes(Runtime.getRuntime().maxMemory().toDouble()))
            fact("GC", ManagementFactory.getGarbageCollectorMXBeans().firstOrNull()?.name?.let(::collectorName))
            fact("Flags", userArguments(runtime.inputArguments).filter(::keptFlag).joinToString(" "))
            return Setup(facts, "${runtime.pid}@${runtime.startTime}")
        }

        private fun cpuModel(): String? = System.getenv("PROCESSOR_IDENTIFIER") ?: runCatching {
            Files.readAllLines(Path.of("/proc/cpuinfo")).firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
        }.getOrNull()
    }
}

/** One name's values over the runs: the one value, the different ones, or past a few their range or a count. */
internal fun valuesOf(values: List<String>): String {
    val different = values.distinct()
    if (different.size <= MAX_VALUES_LISTED) return different.joinToString(", ")
    if (different.all { it.toDoubleOrNull() != null }) {
        val sorted = different.sortedBy { it.toDouble() }
        return rangeText(sorted.first(), sorted.last())
    }
    return different.take(MAX_VALUES_LISTED).joinToString(", ") + " and ${different.size - MAX_VALUES_LISTED} more"
}

private fun namesIn(runs: List<Map<String, String>>): List<String> = runs.flatMap { it.keys }.distinct()

private fun valuesOf(name: String, runs: List<Map<String, String>>): String = valuesOf(runs.map { it[name] ?: UNKNOWN })

/** `warmups: 2; batches: 1, 3`: every name in [runs], one map per run, with `?` for a run that lacks it. */
internal fun factsLine(runs: List<Map<String, String>>): String =
    namesIn(runs).joinToString("; ") { "$it: ${valuesOf(it, runs)}" }

/** The names [runs] do not agree on. */
internal fun mixedNames(runs: List<Map<String, String>>): List<String> =
    namesIn(runs).filter { name -> runs.map { it[name] }.distinct().size > 1 }

/** `step: naive -> centralEdges; batches: 3 =`: what differs, and with [all] what does not. */
internal fun factsDiffLine(before: List<Map<String, String>>, after: List<Map<String, String>>, all: Boolean): String =
    namesIn(before + after).mapNotNull { name ->
        val b = valuesOf(name, before)
        val a = valuesOf(name, after)
        if (b != a) "$name: $b ${sym.arrow} $a" else if (all) "$name: $b =" else null
    }.joinToString("; ")

/** The `Tags` and `Setup` lines of a report: one map per run, nothing for what none of them has. */
internal fun StringBuilder.factLines(tags: List<Map<String, String>>, setups: List<Map<String, String>>) {
    if (tags.any { it.isNotEmpty() }) appendLine(row("Tags", factsLine(tags)))
    if (setups.any { it.isNotEmpty() }) appendLine(row("Setup", factsLine(setups)))
}

/**
 * The same lines of a diff, where a difference is shown and is not a warning: it is what a diff is
 * for, and which one differs is the question it answers. Every tag, since the user chose them, and
 * of the setup only what differs.
 */
internal fun StringBuilder.factDiffLines(
    tagsBefore: List<Map<String, String>>, tagsAfter: List<Map<String, String>>,
    setupsBefore: List<Map<String, String>>, setupsAfter: List<Map<String, String>>,
) {
    factsDiffLine(tagsBefore, tagsAfter, all = true).takeIf { it.isNotEmpty() }?.let { appendLine(row("Tags", it)) }
    // Only where both sides have one: a side saved without a setup does not differ, it does not say.
    if (setupsBefore.isEmpty() || setupsAfter.isEmpty()) return
    factsDiffLine(setupsBefore, setupsAfter, all = false).takeIf { it.isNotEmpty() }?.let { appendLine(row("Setup", it)) }
}
