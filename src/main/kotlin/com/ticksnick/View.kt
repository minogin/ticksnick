package com.ticksnick

/**
 * **What a report shows**, of the same profile and in the same words: [Profile.render] and [TickSnick.diff]
 * take one. A view only leaves out; how a figure is written is the same in every view, so a brief report
 * never says what the full one contradicts.
 */
enum class View {
    /**
     * The everyday look, and the default. The tables show the basis - each operation's time, its share,
     * how many times it ran, and the price of one -, and the [Lens]es opened, and name the ones left out. A
     * figure measured well enough to be used as one value is written without its interval; a diff is one line a
     * row.
     */
    BRIEF,

    /** Every lens and every interval, a diff three lines a row: for a deep look or a presentation. */
    FULL,
}

/**
 * **A group of measures the brief view shows only when asked**: one question about the operations, opened when it
 * is the problem. [Profile.render] and [TickSnick.diff] take the ones to open; the full view has them all.
 */
enum class Lens {
    /** How an operation spreads over threads: how many at once, how many in all. */
    PARALLELISM,

    /** How much of its time it was parked, blocked or in I/O. */
    WAITING,

    /** What it allocated, and the collector's pauses charged to it. */
    MEMORY,

    /** How its times are spread: the percentiles and the largest, the calls that outlived a tick. */
    LATENCY,
}
