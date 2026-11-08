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
 * The body as a session saw it: [nanos], one body in ns, the fastest of the session's seconds;
 * [nanoTimeBodies], what one `System.nanoTime()` costs, in bodies. NaN for what was not timed.
 */
internal class BodyTrace(val nanos: Double, val nanoTimeBodies: Double) {
    companion object {
        val NOT_TIMED = BodyTrace(Double.NaN, Double.NaN)
    }
}

/**
 * Times a body once a second on a thread of its own, in plain Java, so it works wherever the JVM
 * does: no OS counter is read. What a CPU cost is compared with is counted in bodies, and the run's
 * body turns it into seconds.
 *
 * Each second the fastest of [BODY_TIMINGS] timings is kept, and the run's body is the fastest of
 * those: the fastest core at the highest clock the session saw. The thread lands on whatever core the
 * OS gives it, and an OS tends to give a thread that wakes once a second a slow one, so a mean would
 * read the floors high and warn about operations that are fine. The fastest reads them a little low
 * where the work ran on slower cores or at a lower clock, which the floors' margin covers.
 */
internal class BodyProbe {
    private val bodies = ArrayList<Double>()
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
            while (running) {
                val b = fastest()
                synchronized(bodies) { bodies += b }
                try {
                    Thread.sleep(BODY_PERIOD_MILLIS)
                } catch (_: InterruptedException) {
                    // The stop.
                }
            }
        }, "ticksnick-body").apply { isDaemon = true; start() }
    }

    /** Stops the timing and times `nanoTime` against a body on the calling thread. */
    fun stop(): BodyTrace {
        running = false
        thread?.interrupt()
        thread?.join(2 * BODY_PERIOD_MILLIS)
        val b = synchronized(bodies) { bodies.toDoubleArray() }
        // A session stopped before its first timing has no body, and the report falls back to the
        // default one; nor is `nanoTime` timed, against a body the JIT may not have compiled yet.
        if (b.isEmpty()) return BodyTrace.NOT_TIMED
        return BodyTrace(b.min(), nanoTimeBodies())
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
