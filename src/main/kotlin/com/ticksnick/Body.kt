package com.ticksnick

/** Triple xorshift steps in one body: the fixed piece of CPU work every CPU cost is counted in. */
internal const val BODY_STEPS = 20

/** Bodies in one timing: tens of microseconds, so the two clock reads around it do not matter. */
internal const val BODY_LAP = 1_000

/** Timings each second; the fastest is the one kept, since an interruption only makes a timing longer. */
internal const val BODY_TIMINGS = 5

/** Laps run before the first timing, so the first one times compiled code. */
internal const val BODY_WARMUP_LAPS = 300

/** How often the body is timed during a session. */
internal const val BODY_PERIOD_MILLIS = 1_000L

/** `nanoTime` calls in one timing of its own cost, at `stop()`. */
internal const val NANOTIME_LAP = 2_000

/** Timings of `nanoTime` against a body at `stop()`; the median ratio is the one kept. */
internal const val NANOTIME_TIMINGS = 9

/**
 * One body: [BODY_STEPS] triple xorshift steps on one dependent chain. Pure, so the value must flow
 * out to a field or the work is dead code; a linear map over GF(2), so the JIT cannot fold the steps
 * into fewer.
 */
internal fun body(seed: Long): Long {
    var x = seed
    var i = 0
    while (i < BODY_STEPS) {
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        i++
    }
    return x
}

/**
 * The body as a session saw it: [nanos], one body in ns, the mean over the session's seconds weighted
 * by the labelled samples in each; [nanoTimeBodies], what one `System.nanoTime()` costs, in bodies.
 * NaN for what was not timed.
 */
internal class BodyTrace(val nanos: Double, val nanoTimeBodies: Double) {
    companion object {
        val NOT_TIMED = BodyTrace(Double.NaN, Double.NaN)
    }
}

/**
 * Times a body once a second on a thread of its own, in plain Java, so it works wherever the JVM
 * does: no OS counter is read. What a CPU cost is compared with is counted in bodies, and the run's
 * body turns it into seconds at the speed the run's work ran at.
 *
 * Each second the fastest of [BODY_TIMINGS] timings is kept, weighted by the labelled samples taken
 * since the one before, read through [labelled]: an operation's time is a mean over the clock it ran
 * at, so the body it is compared with is too. The thread lands on whatever core the OS gives it; on a
 * CPU with slower cores a body read there is longer, which raises a floor in seconds, never lowers it.
 */
internal class BodyProbe(private val labelled: () -> Long) {
    private val bodies = ArrayList<Double>()
    private val weights = ArrayList<Long>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var sink = -7046029254386353131L

    private fun lap(): Double {
        var s = sink
        val t0 = System.nanoTime()
        for (i in 0 until BODY_LAP) s = body(s)
        val t1 = System.nanoTime()
        sink = s
        return (t1 - t0).toDouble() / BODY_LAP
    }

    private fun fastest(): Double {
        var best = Double.MAX_VALUE
        repeat(BODY_TIMINGS) { best = minOf(best, lap()) }
        return best
    }

    fun start() {
        running = true
        thread = Thread({
            repeat(BODY_WARMUP_LAPS) { if (running) lap() }
            var seen = labelled()
            while (running) {
                val b = fastest()
                try {
                    Thread.sleep(BODY_PERIOD_MILLIS)
                } catch (_: InterruptedException) {
                    // The stop: the samples since the last read still weigh this timing.
                }
                val now = labelled()
                synchronized(bodies) {
                    bodies += b
                    weights += (now - seen).coerceAtLeast(0)
                }
                seen = now
            }
        }, "ticksnick-body").apply { isDaemon = true; start() }
    }

    /** Stops the timing and times `nanoTime` against a body on the calling thread. */
    fun stop(): BodyTrace {
        running = false
        thread?.interrupt()
        thread?.join(2 * BODY_PERIOD_MILLIS)
        val (b, w) = synchronized(bodies) { bodies.toDoubleArray() to weights.toLongArray() }
        // A session stopped before its first timing has no body, and the report falls back to the
        // default one; nor is `nanoTime` timed, against a body the JIT may not have compiled yet.
        if (b.isEmpty()) return BodyTrace.NOT_TIMED
        val total = w.sum()
        val nanos = if (total == 0L) b.average() else b.indices.sumOf { b[it] * w[it] } / total
        return BodyTrace(nanos, nanoTimeBodies())
    }

    /** One `nanoTime` in bodies: [NANOTIME_TIMINGS] alternating timings, the median ratio. */
    private fun nanoTimeBodies(): Double {
        val ratios = DoubleArray(NANOTIME_TIMINGS) {
            var acc = 0L
            val t0 = System.nanoTime()
            for (i in 0 until NANOTIME_LAP) acc += System.nanoTime()
            val t1 = System.nanoTime()
            sink = sink xor acc
            val call = (t1 - t0).toDouble() / NANOTIME_LAP
            call / lap()
        }
        ratios.sort()
        return ratios[NANOTIME_TIMINGS / 2]
    }
}
