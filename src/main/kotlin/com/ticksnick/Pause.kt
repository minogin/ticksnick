package com.ticksnick

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.max

/**
 * Adds up the time the sampling thread was itself stopped.
 *
 * A spinning sampler wakes within microseconds of the moment it asked for, so a tick that runs a
 * whole step late was not late by its own doing: something stopped the thread. In a JVM that is
 * almost always a stop-the-world pause, which stops every other thread too, and almost always a
 * garbage collection. Measured against the JVM's own safepoint log on a run with 26 pauses, the
 * lateness summed here came to 5.75 s against 5.72 s - see docs/findings.md.
 *
 * **Everything late is summed, whatever caused it.** The sampler cannot tell a collection from any
 * other safepoint, or from the scheduler taking its core away, and it does not try: the name the
 * report prints is the observation - *paused* - and the cause is offered as a hint beside it, with
 * the JVM's own collection count ([GcPauseClock]) as the check on that hint.
 *
 * One subtraction and one compare per tick, on the sampling thread. Nothing here is on the hot path.
 */
internal class PauseTracker(
    /**
     * How late a tick has to be before it counts. One whole step: below that a tick was late but
     * none was lost, and lateness of that size is also what a busy machine does to any thread.
     */
    private val thresholdNanos: Long,
) {
    var pausedNanos: Long = 0; private set
    var pauses: Long = 0; private set
    var longestNanos: Long = 0; private set

    /** Called on every tick with when it was due and when it actually ran. */
    fun tick(dueNanos: Long, nowNanos: Long) {
        val late = nowNanos - dueNanos
        if (late < thresholdNanos) return
        pausedNanos += late
        pauses++
        if (late > longestNanos) longestNanos = late
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
 * Read twice a session, at start and at stop. It is the check on [PauseTracker], which measures
 * the same thing from the outside and cannot say what caused it.
 */
internal object GcPauseClock {
    /** Nanoseconds of stop-the-world collection since the JVM started, or -1 if it cannot be read. */
    fun pauseNanos(): Long = try {
        val millis = gcPauseMillis(ManagementFactory.getGarbageCollectorMXBeans().map { it.name to it.collectionTime })
        if (millis < 0) -1L else millis * 1_000_000L
    } catch (_: Throwable) {
        // No java.management module, or a JVM that refuses. Either way the answer is "not known".
        -1L
    }
}

/**
 * How long the session spent with every thread stopped. See [PauseTracker].
 */
class PauseReport internal constructor(
    /**
     * False when the sampler was not spinning. A parked or sleeping sampler is late on every tick
     * by the scheduler's doing, so its lateness says nothing about the threads it is watching.
     */
    val measured: Boolean,
    /** Total lateness of the ticks that ran at least one whole step late. */
    val pausedNanos: Long,
    val pauses: Long,
    val longestNanos: Long,
    /** Stop-the-world collection time by the JVM's own count over the session, or -1 if unreadable. */
    val gcNanos: Long,
) {
    /**
     * The header rows, or none at all on a run with nothing to report.
     *
     * A quiet run prints nothing rather than `Paused 0`: the row exists to explain a stretched step
     * and a low time-on-CPU, and where neither happened it would be a line that teaches the reader
     * to skip it.
     *
     * @param spanNanos the sampling span, which is what the paused time is a share of.
     */
    fun lines(spanNanos: Long): List<String> {
        if (!measured) return listOf(
            row("Paused", "not measured - only a spinning sampler can tell a pause from its own lateness")
        ) + listOfNotNull(if (gcNanos > 0) subRow("GC", gcCount()) else null)
        if (pauses == 0L && gcNanos <= 0) return emptyList()
        if (pauses == 0L) return listOf(
            row("Paused", "none the sampler could see - a pause shorter than one step is not resolved"),
            subRow("GC", gcCount()),
        )
        val out = ArrayList<String>()
        out += row(
            "Paused", String.format(
                Locale.ROOT, "%s (%s) in %s, longest %s - every thread stopped, usually GC",
                duration(pausedNanos.toDouble()),
                percent(if (spanNanos > 0) pausedNanos * 100.0 / spanNanos else Double.NaN),
                Report.plural(pauses, "pause"), duration(longestNanos.toDouble())
            )
        )
        out += subRow(
            "GC", when {
                gcNanos < 0 -> "not counted - this JVM's collectors are not ones the profiler can read"
                // The two are the same quantity measured from opposite sides, and they agree to a
                // percent when the pauses were collections. Said only when they do not: a gap is
                // the one case where the hint on the row above is wrong.
                pausedNanos - gcNanos > slack(pausedNanos) -> gcCount() + String.format(
                    Locale.ROOT, " - the other %s was not GC: another JVM pause, or the sampler kept off its core",
                    duration((pausedNanos - gcNanos).toDouble())
                )
                gcNanos - pausedNanos > slack(gcNanos) ->
                    gcCount() + " - more than the sampler saw: a pause shorter than one step is not resolved"
                else -> gcCount()
            }
        )
        return out
    }

    private fun gcCount(): String = duration(gcNanos.toDouble()) + " by the JVM's own count"

    /** How far apart the two counts may be before the report says so. */
    private fun slack(nanos: Long): Long = max(nanos / 5, 50_000_000L)

    internal companion object {
        /** A session nothing is known about: a report built by hand, with no sampler behind it. */
        val NONE = PauseReport(measured = true, pausedNanos = 0, pauses = 0, longestNanos = 0, gcNanos = -1)
    }
}
