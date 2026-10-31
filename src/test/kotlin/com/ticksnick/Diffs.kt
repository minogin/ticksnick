package com.ticksnick

import java.nio.file.Path

/** One run against one, each as a profile of its own. */
internal fun diffOf(before: Run, after: Run, beforeName: String = "before", afterName: String = "after"): String = ProfileDiff(
    Profile(beforeName, listOf(Path.of("$beforeName.tsr")), listOf(before)),
    Profile(afterName, listOf(Path.of("$afterName.tsr")), listOf(after)),
).render()

/** The three lines of the row [name] of a diff's table: before, after, the change. */
internal fun diffRow(text: String, name: String): List<String> {
    val lines = text.lines()
    val i = lines.indexOfFirst { it.trimStart().removePrefix("- ").startsWith("$name ") && '|' in it }
    require(i >= 0) { "no row $name in $text" }
    return lines.subList(i, i + 3)
}
