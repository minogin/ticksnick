package com.ticksnick

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The library jar as a command, for the two things a saved profile is for:
 *
 * ```
 * ./gradlew :cli --args="render 'profiles/2026-10-13T18-42-07 presized.tsp'"
 * ./gradlew :cli --args="diff before.tsp after.tsp"
 * java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli diff before.tsp after.tsp
 * ```
 *
 * Not a separate artifact: this class costs a dependent nothing, where a second artifact is a
 * second thing to publish, version and explain. The jar's only dependency is the Kotlin stdlib,
 * which is why `java -jar` alone cannot run it and the Gradle task exists.
 */
object Cli {
    @JvmStatic
    fun main(args: Array<String>) {
        when {
            args.size == 2 && args[0] == "render" -> println(Profile.load(Path.of(args[1])).render())
            args.size == 3 && args[0] == "diff" -> {
                val a = Path.of(args[1])
                val b = Path.of(args[2])
                println(Profile.load(b).diffFrom(Profile.load(a), a.fileName.toString(), b.fileName.toString()).render())
            }
            else -> {
                System.err.println("usage: render <profile.tsp> | diff <before.tsp> <after.tsp>")
                exitProcess(2)
            }
        }
    }
}
