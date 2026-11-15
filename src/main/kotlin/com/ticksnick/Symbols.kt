package com.ticksnick

/** The report's symbols in ASCII, the default: every console and every pipe prints them. */
internal val ASCII_SYMBOLS = Symbols(plusMinus = "+-", micro = "us", arrow = "->", dash = " - ", ellipsis = "...", times = "x", down = "-(", up = "+(", directedClose = ")")

/** The same symbols in Unicode, for a caller who says the text will be read as Unicode. */
internal val UNICODE_SYMBOLS = Symbols(plusMinus = "±", micro = "µs", arrow = "→", dash = " — ", ellipsis = "…", times = "×", down = "↓", up = "↑", directedClose = "")

/**
 * The symbols a report is written in. They are chosen before any text is made, since `±` is one
 * character and `+-` two: a table measured in one form and printed in the other would not line up.
 */
internal class Symbols(
    val plusMinus: String,
    val micro: String,
    val arrow: String,
    val dash: String,
    val ellipsis: String,
    val times: String,
    /** Before a change range below zero, `↓8.2..62%`, and in ASCII `-(8.2..62)%`, closed by [directedClose]. */
    val down: String,
    /** Before a change range above zero, `↑175..300%`, and in ASCII `+(175..300)%`. */
    val up: String,
    /** After a directed change range's numbers, before its unit: `)` in ASCII, nothing in Unicode. */
    val directedClose: String,
)

private val current: ThreadLocal<Symbols> = ThreadLocal.withInitial { ASCII_SYMBOLS }

/** The symbols of the report being rendered on this thread; ASCII outside a render. */
internal val sym: Symbols get() = current.get()

/**
 * Renders [block] in Unicode or ASCII. The library does not guess from the output's charset: a pipe
 * can decode differently from what Java encodes, and only the caller knows where the text goes.
 */
internal fun <T> withSymbols(unicode: Boolean, block: () -> T): T {
    val before = current.get()
    current.set(if (unicode) UNICODE_SYMBOLS else ASCII_SYMBOLS)
    try {
        return block()
    } finally {
        current.set(before)
    }
}
