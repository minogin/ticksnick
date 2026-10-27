package com.ticksnick

import java.nio.file.Path
import kotlin.system.exitProcess

internal const val USAGE = "usage: render <folder> | diff <before folder> <after folder>"

/**
 * The library jar as a command, for the two things a folder of runs is for - [TickSnick.load] and
 * render it, or [TickSnick.diff] two:
 *
 * ```
 * ./gradlew :cli --args="render profiles/presized"                # every run in the folder, with intervals
 * ./gradlew :cli --args="diff profiles/naive profiles/presized"   # before, after: the change +- 95%
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
    internal fun run(args: Array<String>): Int = try {
        when {
            args.size == 2 && args[0] == "render" -> println(TickSnick.load(Path.of(args[1])).render())
            args.size == 3 && args[0] == "diff" ->
                println(TickSnick.diff(TickSnick.load(Path.of(args[1])), TickSnick.load(Path.of(args[2]))))
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
