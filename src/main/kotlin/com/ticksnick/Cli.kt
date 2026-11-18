package com.ticksnick

import java.nio.file.Path
import kotlin.system.exitProcess

internal const val USAGE =
    "usage: [options] render <folder> | [options] diff <before folder> <after folder>; " +
            "options: --unicode, --view brief|full, --lens parallelism|waiting|memory|latency (repeatable), " +
            "--filter \"<condition>\" (repeatable), such as --filter \"wall >= 100ms\" or --filter \"change(wall) <= -5%\""

/**
 * The library jar as a command, for the two things a folder of runs is for - [TickSnick.load] and
 * render it, or [TickSnick.diff] two:
 *
 * ```
 * ./gradlew :cli --args="render profiles/presized"                # every run in the folder, the brief view
 * ./gradlew :cli --args="diff profiles/naive profiles/presized"   # before, after: each change judged on its 95% interval
 * ./gradlew :cli --args="--lens latency render profiles/presized" # brief, with the Latency lens open
 * ./gradlew :cli --args="--view full render profiles/presized"    # every lens, every interval
 * ./gradlew :cli --args="--filter 'wall >= 100ms' render profiles/presized"   # only ops of 100 ms or more, each other named
 * java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli diff profiles/naive profiles/presized
 * ```
 *
 * Not a separate artifact: this class costs a dependent nothing, where a second artifact is a
 * second thing to publish, version and explain. The jar's only dependency is the Kotlin stdlib,
 * which is why `java -jar` alone cannot run it and the Gradle task exists.
 */
object Cli {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != 0) exitProcess(code)
    }

    /** [main] without the exit, for the tests: 0 when it printed a report, 2 with a message on stderr. */
    internal fun run(all: Array<String>): Int = try {
        // --unicode: the report in Unicode symbols, for a terminal or file that reads it as such.
        // --view brief|full: what the tables show, brief when not said. --lens <name>: a lens to open, once for each.
        // --filter <condition>: which rows, once for each condition, every one to hold. All of them first, in any order.
        var unicode = false
        var view = View.BRIEF
        val lenses = LinkedHashSet<Lens>()
        val conditions = ArrayList<String>()
        var args = all.toList()
        while (args.firstOrNull()?.startsWith("--") == true) {
            val value = args.getOrNull(1)
            when (args[0]) {
                "--unicode" -> unicode = true
                "--view" -> view = View.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw IllegalArgumentException(USAGE)
                "--lens" -> lenses += Lens.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw IllegalArgumentException(USAGE)
                "--filter" -> conditions += value ?: throw IllegalArgumentException(USAGE)
                else -> throw IllegalArgumentException(USAGE)
            }
            args = args.drop(if (args[0] == "--unicode") 1 else 2)
        }
        val filter = if (conditions.isEmpty()) Filter.NONE else Filter.of(*conditions.toTypedArray())
        when {
            args.size == 2 && args[0] == "render" -> println(TickSnick.load(Path.of(args[1])).render(unicode, view, lenses, filter))
            args.size == 3 && args[0] == "diff" ->
                println(TickSnick.diff(TickSnick.load(Path.of(args[1])), TickSnick.load(Path.of(args[2])), unicode, view, lenses, filter))
            else -> throw IllegalArgumentException(USAGE)
        }
        0
    } catch (e: IllegalArgumentException) {
        // A folder that is not one, or holds no run, or a file of another format: the message, not a stack trace.
        System.err.println(e.message)
        if (e.message != USAGE) System.err.println(USAGE)
        2
    }
}
