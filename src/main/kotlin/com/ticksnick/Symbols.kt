package com.ticksnick

/** The report's symbols in ASCII, the default: every console and every pipe prints them. */
internal val ASCII_SYMBOLS = Symbols(plusMinus = "+-", micro = "us", arrow = "->", dash = " - ", ellipsis = "...", times = "x", down = "", up = "", less = "<")

/** The same symbols in Unicode, for a caller who says the text will be read as Unicode. */
internal val UNICODE_SYMBOLS = Symbols(plusMinus = "±", micro = "µs", arrow = "→", dash = " — ", ellipsis = "…", times = "×", down = "↓", up = "↑", less = "<")

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
    /**
     * Before a change that went down, `↓-8.2..-62%`, `↓-40%`: nothing in ASCII. It repeats what the numbers say, for
     * reading a column at a glance, so ASCII loses nothing but speed.
     */
    val down: String,
    /** Before a change that went up, `↑180..300%`, `↑1.0pp`: nothing in ASCII, where one value takes its `+` instead. */
    val up: String,
    /**
     * Before a number that is not 0 but rounds to 0 at its places, `<0.1`: the same in both, being the convention
     * everywhere and having no better Unicode form (`≈` loses the bound, `≲` reads "about or below").
     */
    val less: String,
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
