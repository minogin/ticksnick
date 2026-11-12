package com.ticksnick

import java.nio.file.Path

/** One run against one, each as a profile of its own. */
internal fun diffOf(before: Run, after: Run, beforeName: String = "before", afterName: String = "after"): String = ProfileDiff(
    Profile(beforeName, listOf(Path.of("$beforeName.tsr")), listOf(before)),
    Profile(afterName, listOf(Path.of("$afterName.tsr")), listOf(after)),
).render()

/** The `Cores` line of one run. */
internal fun coresOf(d: Duty): String? = coresLine(listOfNotNull(d.cores()))

/** The three lines of the row [name] of a diff's table: before, after, the change. */
internal fun diffRow(text: String, name: String): List<String> {
    val lines = text.lines()
    // A side that does not have the row is blank, so its line can end at the name.
    val i = lines.indices.indexOfFirst { j ->
        lines[j].trimStart().removePrefix("- ").startsWith("$name ") && ('|' in lines[j] || '|' in lines.getOrElse(j + 1) { "" })
    }
    require(i >= 0) { "no row $name in $text" }
    return lines.subList(i, i + 3)
}
