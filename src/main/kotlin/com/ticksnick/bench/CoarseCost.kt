package com.ticksnick.bench

import com.ticksnick.Profiler
import com.ticksnick.op
import java.util.Locale

/** Executions per timed lap. Enough for a lap to be milliseconds, so one clock read does not matter. */
const val COARSE_COST_LAP = 200_000

/** Interleaved rounds. The median of these is the figure; the spread is printed beside it. */
const val COARSE_COST_ROUNDS = 15

/** Iterations of the body under every label, about 60 ns: long enough for the label to be a fraction. */
const val COARSE_COST_BODY = 20

/**
 * What one coarse execution costs, measured directly: a labelled loop against the same loop bare.
 *
 * findings.md carried the ~40 ns the tier boundary is derived from as "two thirds measured", the
 * context allocation being assumed. This mode measures the whole thing, and it exists because the
 * calling-context tree put more work on the coarse hook - a node lookup at entry and a record into
 * the node at exit - and a change to the hot path is not accepted on an argument.
 *
 * Three arms, interleaved, order rotated every round, because a sequential comparison on this
 * machine aliases clock drift onto the effect:
 *
 * - **bare**: the body alone.
 * - **flat**: the body under one coarse label, nested in nothing.
 * - **deep**: the body under one coarse label inside three open ones - the common shape, where
 *   entry has a parent to look up under.
 *
 * The sampler is on, as it would be in use, so whatever it does to the context's cache line is in
 * the figure. Prices one thread: contention is not what a per-thread recording path has.
 */
internal fun runCoarseCost() {
    val outer = Array(3) { Profiler.registerCoarse("outer$it") }
    val leaf = Profiler.registerCoarse("leaf")
    Profiler.start(strict = false)
    try {
        println("--- What one coarse execution costs: bare vs flat vs deep, ${COARSE_COST_ROUNDS} interleaved rounds ---")
        // Warm-up: every arm, until C2 has had its say.
        repeat(5) { bareLap(); flatLap(leaf); deepLap(outer, leaf) }
        val bare = DoubleArray(COARSE_COST_ROUNDS)
        val flat = DoubleArray(COARSE_COST_ROUNDS)
        val deep = DoubleArray(COARSE_COST_ROUNDS)
        for (r in 0 until COARSE_COST_ROUNDS) {
            for (k in 0 until 3) {
                when ((r + k) % 3) {
                    0 -> bare[r] = bareLap()
                    1 -> flat[r] = flatLap(leaf)
                    else -> deep[r] = deepLap(outer, leaf)
                }
            }
        }
        fun stats(a: DoubleArray): String {
            val s = a.sorted()
            return String.format(Locale.ROOT, "%6.1f ns (min %5.1f, max %5.1f)", s[s.size / 2], s.first(), s.last())
        }
        println("  bare body:            " + stats(bare))
        println("  flat, one label:      " + stats(flat))
        println("  deep, three outside:  " + stats(deep))
        val flatCost = flat.sorted()[COARSE_COST_ROUNDS / 2] - bare.sorted()[COARSE_COST_ROUNDS / 2]
        val deepCost = deep.sorted()[COARSE_COST_ROUNDS / 2] - bare.sorted()[COARSE_COST_ROUNDS / 2]
        println(String.format(Locale.ROOT, "  one coarse execution: %.1f ns flat, %.1f ns deep (median minus median)", flatCost, deepCost))
    } finally {
        Profiler.stop()
    }
}

/**
 * Something C2 cannot fold. An LCG was tried first and C2 composed its twenty affine steps into
 * one: the bare arm read 0.1 ns. A xorshift step is not affine and cannot be composed.
 */
private var sink = 0L

private fun body(seed: Long): Long {
    var x = seed
    for (i in 0 until COARSE_COST_BODY) {
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
    }
    return x
}

private fun bareLap(): Double {
    var s = sink
    val t0 = System.nanoTime()
    for (i in 0 until COARSE_COST_LAP) s = body(s)
    val t1 = System.nanoTime()
    sink = s
    return (t1 - t0).toDouble() / COARSE_COST_LAP
}

private fun flatLap(leaf: com.ticksnick.CoarseOp): Double {
    var s = sink
    val t0 = System.nanoTime()
    for (i in 0 until COARSE_COST_LAP) s = op(leaf) { body(s) }
    val t1 = System.nanoTime()
    sink = s
    return (t1 - t0).toDouble() / COARSE_COST_LAP
}

private fun deepLap(outer: Array<com.ticksnick.CoarseOp>, leaf: com.ticksnick.CoarseOp): Double {
    var s = sink
    var ns = 0L
    op(outer[0]) {
        op(outer[1]) {
            op(outer[2]) {
                val t0 = System.nanoTime()
                for (i in 0 until COARSE_COST_LAP) s = op(leaf) { body(s) }
                ns = System.nanoTime() - t0
            }
        }
    }
    sink = s
    return ns.toDouble() / COARSE_COST_LAP
}
