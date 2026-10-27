package com.ticksnick

import java.util.concurrent.atomic.AtomicBoolean

/** The logo, five lines. Raw, so the backslashes are the ones printed. */
private val LOGO = listOf(
    """ _____ ___ ____ _  ______  _   _ ___ ____ _  __""",
    """|_   _|_ _/ ___| |/ / ___|| \ | |_ _/ ___| |/ /""",
    """  | |  | | |   | ' /\___ \|  \| || | |   | ' /""",
    """  | |  | | |___| . \ ___) | |\  || | |___| . \""",
    """  |_| |___\____|_|\_\____/|_| \_|___\____|_|\_\""",
).joinToString("\n")

/**
 * **Printed to stdout once per JVM, by the first TickSnick call** - registering a label, `start`,
 * `stop`, `save`, `load`, `diff`, `render` - and never part of a returned report. A banner belongs to the program starting, as Spring Boot's and JMH's do, not to the
 * values it hands back: a report is the same String whether it goes to the console or a file, and
 * whatever was rendered before it. The calls on the hot path - `op`, `enter`, `exit`, `withCoarse`,
 * `captureCoarse`, the `propagating` wrappers - do not check.
 */
internal object Logo {
    private val shown = AtomicBoolean(false)

    fun once() {
        if (!shown.get() && shown.compareAndSet(false, true)) println(LOGO + "\n")
    }
}
