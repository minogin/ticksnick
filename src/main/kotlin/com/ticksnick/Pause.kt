package com.ticksnick

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Adds up the time the sampling thread was itself stopped.
 *
 * A spinning sampler wakes within microseconds of the moment it asked for, so a tick that runs a
 * whole step late was not late by its own doing: something stopped the thread. In a JVM that is
 * almost always a stop-the-world pause, which stops every other thread too, and almost always a
 * garbage collection. Checked against the JVM's own safepoint log, the lateness summed here agrees
 * with the logged pause time to within about a percent.
 *
 * **Everything late is summed, whatever caused it.** The sampler cannot tell a collection from any
 * other safepoint, or from the scheduler taking its core away, and it does not try: the name the
 * report prints is the observation - *paused* - and the cause is offered as a hint beside it, with
 * the JVM's own collection count ([GcPauseClock]) as the check on that hint.
 *
 * **Seen is not the same as confirmed, and only confirmed time is ever subtracted from anything.**
 * [pausedNanos] is what this thread saw. [confirmedNanos] is the part of it the JVM also counted,
 * and it is the only figure the report takes out of wall time - see [settle].
 *
 * One subtraction and one compare per tick, on the sampling thread. Nothing here is on the hot path.
 */
internal class PauseTracker(
    /**
     * How late a tick has to be before it counts. One whole step: below that a tick was late but
     * none was lost, and lateness of that size is also what a busy machine does to any thread.
     */
    private val thresholdNanos: Long,
    /** [GcPauseClock.pauseNanos] when the session began, or -1 if it cannot be read. */
    private val gcAtStartNanos: Long = -1,
) {
    var pausedNanos: Long = 0; private set
    var pauses: Long = 0; private set
    var longestNanos: Long = 0; private set

    /** Paused time that both this thread saw and the JVM counted. Never more than either. */
    var confirmedNanos: Long = 0; private set

    /**
     * Called on every tick with when it was due and when it actually ran. Returns the lateness
     * counted, or 0 when the tick was on time: the sampler charges it to the spans it finds open.
     */
    fun tick(dueNanos: Long, nowNanos: Long): Long {
        val late = nowNanos - dueNanos
        if (late < thresholdNanos) return 0
        pausedNanos += late
        pauses++
        if (late > longestNanos) longestNanos = late
        return late
    }

    /**
     * Brings [confirmedNanos] up to date and returns how much it grew.
     *
     * The smaller of what the sampler lost and what the JVM says it spent collecting, both since
     * the session began. Each is a floor on the time every thread really was stopped: lateness
     * misses pauses under a step and the head of every longer one, and the JVM's count is
     * collection alone. So the smaller of two floors is a floor, and taking it out of wall time can
     * leave a pause in but can never take running time out - which is the direction a bound on the
     * shares is allowed to be wrong in.
     *
     * It is also what makes a preempted sampler harmless. A sampler that lost its core while the
     * workers ran on has lateness and no collection behind it; the JVM's count does not move, so
     * nothing is confirmed and nothing is subtracted.
     *
     * Cumulative rather than per call, so a pause whose two halves land either side of a call - the
     * JVM counts it when it ends, the sampler on its next tick - is matched on the following one.
     *
     * @param gcNowNanos [GcPauseClock.pauseNanos] now, or -1.
     */
    fun settle(gcNowNanos: Long): Long {
        if (gcAtStartNanos < 0 || gcNowNanos < 0) return 0
        val grew = min(pausedNanos, gcNowNanos - gcAtStartNanos) - confirmedNanos
        if (grew <= 0) return 0
        confirmedNanos += grew
        return grew
    }
}

/** What a [GcPauseClock] bean's collection time means. */
internal enum class GcBeanKind {
    /** Time the application's threads were stopped. */
    PAUSES,

    /** The length of a concurrent cycle, during which the application kept running. */
    CYCLES,

    /** A collector this code has not been taught to read. */
    UNKNOWN,
}

/**
 * Whether a `GarbageCollectorMXBean` of this name reports stopped time.
 *
 * By name, because that is all the platform offers: `getCollectionTime()` is "approximate
 * accumulated collection elapsed time" and what elapsed means is the collector's own business. For
 * the stop-the-world collectors it is pause time. ZGC and Shenandoah publish two beans each, and the
 * one called `Cycles` is the length of a concurrent cycle - seconds during which nothing was
 * stopped. Summing every bean would report those as pauses.
 *
 * `G1 Concurrent GC` (JDK 20 and later) counts as pauses despite its name: it reports the Remark
 * and Cleanup pauses of a concurrent cycle, not the cycle.
 *
 * Anything unrecognised is [GcBeanKind.UNKNOWN], and one unknown bean makes the whole count
 * unavailable. A number that might include a concurrent phase is worse than no number.
 */
internal fun gcBeanKind(name: String): GcBeanKind = when {
    name in PAUSE_BEANS || name.endsWith(" Pauses") -> GcBeanKind.PAUSES
    name.endsWith(" Cycles") -> GcBeanKind.CYCLES
    else -> GcBeanKind.UNKNOWN
}

private val PAUSE_BEANS = setOf(
    "G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC", // G1
    "PS Scavenge", "PS MarkSweep",                                  // Parallel
    "Copy", "MarkSweepCompact",                                     // Serial
)

/**
 * Stopped time in milliseconds summed over the beans that report it, or -1 if it cannot be known:
 * no beans, a collector of unknown kind, or one that declines to report (-1 from the bean itself).
 */
internal fun gcPauseMillis(beans: List<Pair<String, Long>>): Long {
    if (beans.isEmpty()) return -1L
    var sum = 0L
    for ((name, millis) in beans) {
        when (gcBeanKind(name)) {
            GcBeanKind.UNKNOWN -> return -1L
            GcBeanKind.CYCLES -> Unit
            GcBeanKind.PAUSES -> if (millis < 0) return -1L else sum += millis
        }
    }
    return sum
}

/**
 * The JVM's own count of how long garbage collection has stopped the application.
 *
 * Read at start and stop, and once per duty window in between. It is the check on [PauseTracker],
 * which measures the same thing from the outside and cannot say what caused it.
 */
internal object GcPauseClock {
    /** Looked up once: the set of collectors does not change while a JVM runs. */
    private val beans: List<GarbageCollectorMXBean>? by lazy {
        try {
            ManagementFactory.getGarbageCollectorMXBeans()
        } catch (_: Throwable) {
            // No java.management module, or a JVM that refuses. Either way the answer is "not known".
            null
        }
    }

    /** Nanoseconds of stop-the-world collection since the JVM started, or -1 if it cannot be read. */
    fun pauseNanos(): Long = try {
        val millis = gcPauseMillis(beans.orEmpty().map { it.name to it.collectionTime })
        if (millis < 0) -1L else millis * 1_000_000L
    } catch (_: Throwable) {
        -1L
    }
}

/**
 * How long the session spent with every thread stopped. See [PauseTracker].
 */
internal class Pauses internal constructor(
    /**
     * False when the sampler was not spinning. A parked or sleeping sampler is late on every tick
     * by the scheduler's doing, so its lateness says nothing about the threads it is watching.
     */
    val measured: Boolean,
    /** Total lateness of the ticks that ran at least one whole step late: what the sampler saw. */
    val pausedNanos: Long,
    val pauses: Long,
    val longestNanos: Long,
    /** Stop-the-world collection time by the JVM's own count over the session, or -1 if unreadable. */
    val gcNanos: Long,
    /**
     * The part of [pausedNanos] the JVM also counted - the paused time in `wall = active + paused`.
     *
     * The smaller of the two, so that the active time on the first line of the header is the same
     * active time the duty cycle divides by. Lateness nobody confirmed stays in active time: it may
     * have been the sampler alone that was stopped.
     */
    val confirmedNanos: Long = 0,
) {
    /**
     * The header: one line, wall time split into active and paused.
     *
     * Printed on every run, `+ 0 paused` included, so that the word is on the page before the table
     * heading uses it. The JVM's own count, and what the sampler saw but nothing confirmed, go in
     * [careful] lines, and only when they are a warning.
     *
     * @param spanNanos the sampling span: the wall time being split.
     */
    fun headerLine(spanNanos: Long): String {
        val paused = confirmedPausedIn(spanNanos)
        return row(
            "Wall time",
            if (paused == 0L) String.format(
                Locale.ROOT, "%s = %s active + 0 paused",
                duration(spanNanos.toDouble()), duration(spanNanos.toDouble())
            )
            else String.format(
                Locale.ROOT, "%s = %s active + %s paused (%s)%s",
                duration(spanNanos.toDouble()), duration((spanNanos - paused).toDouble()),
                duration(paused.toDouble()), percent(paused * 100.0 / spanNanos),
                // The count and the longest pause are the sampler's, the time is what the JVM
                // confirmed. Usually one quantity from two sides; when the JVM confirmed less than
                // one of the pauses the sampler saw, "3.00 ms paused, longest 22.5 ms" is a line
                // contradicting itself, and the counts are left off rather than capped.
                if (longestNanos <= paused) String.format(
                    Locale.ROOT, " in %s, longest %s", Run.plural(pauses, "pause"), duration(longestNanos.toDouble())
                ) else ""
            )
        )
    }

    /** The paused time taken out of [spanNanos]: what the JVM confirmed, and never more than the span. */
    fun confirmedPausedIn(spanNanos: Long): Long = min(confirmedNanos, spanNanos).coerceAtLeast(0)

    /**
     * What is wrong with the split, if anything. Each is a `Careful` line under the header, and a
     * healthy run has none: a disagreement between the sampler and the JVM is the one case where
     * the first line's figure is not the whole story.
     */
    fun careful(): List<String> {
        if (!measured) return listOf(
            "pauses: not measured - a parked sampler is late on every tick, so none of them reads as a pause" +
                    (if (gcNanos > 0) "; the JVM counts ${duration(gcNanos.toDouble())} of GC, inside active"
                    else "")
        )
        if (pauses == 0L) {
            // Collections the sampler could not see, each shorter than a step. Real pauses, and left
            // in active time all the same: nothing here measured them but the JVM's word for it.
            return if (gcNanos > 0) listOf(
                "pauses: the JVM counts ${duration(gcNanos.toDouble())} of GC in pauses under one step - the " +
                        "sampler cannot see them, so they are inside active"
            ) else emptyList()
        }
        val seen = String.format(
            Locale.ROOT, "%s in %s", duration(pausedNanos.toDouble()), Run.plural(pauses, "pause")
        )
        if (gcNanos < 0) return listOf(
            "pauses: $seen seen by the sampler, not confirmed - this JVM's collectors cannot be read, so counted as active"
        )
        return when {
            // The two are the same quantity measured from opposite sides, and they agree to a
            // percent when the pauses were collections. Said only when they do not.
            pausedNanos - gcNanos > slack(pausedNanos) -> listOf(
                String.format(
                    Locale.ROOT, "pauses: the sampler lost %s the JVM did not count as GC - another kind of pause, or " +
                            "the sampler kept off its core; counted as active",
                    duration((pausedNanos - gcNanos).toDouble())
                )
            )
            gcNanos - pausedNanos > slack(gcNanos) -> listOf(
                "pauses: the JVM counts ${duration(gcNanos.toDouble())} of GC, more than the sampler saw - pauses under " +
                        "one step are not resolved, and are inside active"
            )
            else -> emptyList()
        }
    }

    /** How far apart the two counts may be before the report says so. */
    private fun slack(nanos: Long): Long = max(nanos / 5, 50_000_000L)

    internal companion object {
        /** A session nothing is known about: a report built by hand, with no sampler behind it. */
        val NONE = Pauses(measured = true, pausedNanos = 0, pauses = 0, longestNanos = 0, gcNanos = -1)
    }
}
