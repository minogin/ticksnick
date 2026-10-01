package com.ticksnick

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The time every thread was stopped: how it is added up, which of the JVM's counters may be summed
 * as a check on it, and what the report promises to say.
 *
 * The arithmetic is tested here. That the lateness of a tick *is* a pause is not something a unit
 * test can show - that is the 5.75 s against the JVM's 5.72 s in docs/findings.md - and the one live
 * test at the bottom only shows the wiring carries a real pause through to the report.
 */
class PauseTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling, which is the normal case when the test stopped it itself.
        }
    }

    private val step = 1_000_000L

    // ---------------------------------------------------------------- adding it up

    @Test
    fun `ticks that run on time are not pauses`() {
        val t = PauseTracker(step)
        var due = 0L
        repeat(1_000) {
            due += step
            t.tick(due, due + 2_000) // 2 us late, which is a spinning sampler on an ordinary day
        }
        assertEquals(0, t.pauses)
        assertEquals(0, t.pausedNanos)
    }

    /** Late by less than a step loses no tick, and a busy machine does that to any thread. */
    @Test
    fun `lateness under one step is not a pause`() {
        val t = PauseTracker(step)
        t.tick(dueNanos = 5 * step, nowNanos = 5 * step + step - 1)
        assertEquals(0, t.pauses)
        assertEquals(0, t.pausedNanos)
    }

    @Test
    fun `a tick a whole step late is a pause of exactly its lateness`() {
        val t = PauseTracker(step)
        t.tick(dueNanos = 5 * step, nowNanos = 6 * step)
        assertEquals(1, t.pauses)
        assertEquals(step, t.pausedNanos)
        assertEquals(step, t.longestNanos)
    }

    @Test
    fun `pauses add up and the longest is kept`() {
        val t = PauseTracker(step)
        t.tick(dueNanos = 10 * step, nowNanos = 10 * step + 150 * step)
        t.tick(dueNanos = 200 * step, nowNanos = 200 * step + 10)
        t.tick(dueNanos = 300 * step, nowNanos = 300 * step + 1_800 * step)
        t.tick(dueNanos = 2_200 * step, nowNanos = 2_200 * step + 40 * step)
        assertEquals(3, t.pauses)
        assertEquals((150 + 1_800 + 40) * step, t.pausedNanos)
        assertEquals(1_800 * step, t.longestNanos)
    }

    /**
     * Lateness is measured against when the tick was due, not against the step.
     *
     * The interval is jittered by a quarter of a step either way, so a tick due 1.25 ms after the
     * last one and run on time is 0.25 ms "over the step" and not late at all.
     */
    @Test
    fun `a long jittered interval that ran on time is not a pause`() {
        val t = PauseTracker(step)
        var due = 0L
        for (interval in listOf(1_250_000L, 750_000L, 1_249_999L, 1_100_000L)) {
            due += interval
            t.tick(due, due + 1_000)
        }
        assertEquals(0, t.pauses)
    }

    // ---------------------------------------------------------------- the JVM's own count

    @Test
    fun `the stop-the-world collectors are summed`() {
        assertEquals(
            5_720, gcPauseMillis(
                listOf("G1 Young Generation" to 4_050L, "G1 Concurrent GC" to 6L, "G1 Old Generation" to 1_664L)
            )
        )
        assertEquals(30, gcPauseMillis(listOf("PS Scavenge" to 10L, "PS MarkSweep" to 20L)))
        assertEquals(7, gcPauseMillis(listOf("Copy" to 3L, "MarkSweepCompact" to 4L)))
    }

    /**
     * The reason beans are chosen by name. A `Cycles` bean is the length of a concurrent cycle -
     * seconds in which nothing was stopped - and summing it would report those as pauses.
     */
    @Test
    fun `a concurrent cycle is not a pause`() {
        assertEquals(12, gcPauseMillis(listOf("ZGC Cycles" to 9_000L, "ZGC Pauses" to 12L)))
        assertEquals(
            5, gcPauseMillis(
                listOf(
                    "ZGC Minor Cycles" to 700L, "ZGC Minor Pauses" to 2L,
                    "ZGC Major Cycles" to 4_000L, "ZGC Major Pauses" to 3L,
                )
            )
        )
        assertEquals(8, gcPauseMillis(listOf("Shenandoah Cycles" to 6_000L, "Shenandoah Pauses" to 8L)))
    }

    /** A number that might include a concurrent phase is worse than no number. */
    @Test
    fun `one collector of unknown kind makes the count unavailable`() {
        assertEquals(-1, gcPauseMillis(listOf("G1 Young Generation" to 100L, "Some Future Collector" to 5L)))
        assertEquals(GcBeanKind.UNKNOWN, gcBeanKind("Some Future Collector"))
    }

    @Test
    fun `a collector that declines to report makes the count unavailable`() {
        assertEquals(-1, gcPauseMillis(listOf("G1 Young Generation" to -1L)))
        assertEquals(-1, gcPauseMillis(emptyList()))
    }

    // ---------------------------------------------------------------- what the report says

    private val second = 1_000_000_000L

    private fun pause(paused: Long, count: Long, longest: Long, gc: Long, measured: Boolean = true) =
        PauseReport(measured, paused, count, longest, gc)

    /** The row explains a stretched step. Where nothing stretched it, it would only teach skipping. */
    @Test
    fun `a run with no pause prints no row`() {
        assertTrue(PauseReport.NONE.lines(60 * second).isEmpty())
        assertTrue(pause(0, 0, 0, gc = 0).lines(60 * second).isEmpty())
    }

    @Test
    fun `a pause is printed with its total, its share, its count and its longest`() {
        val lines = pause(5_750_000_000, 26, 1_815_000_000, gc = 5_720_000_000).lines(62_700_000_000)
        assertEquals(2, lines.size)
        val paused = lines[0]
        assertTrue(paused.startsWith("Paused"), paused)
        for (part in listOf("5.75 s", "9.17%", "26 pauses", "1.82 s")) assertTrue(part in paused, "$part in: $paused")
        assertTrue("5.72 s" in lines[1], lines[1])
    }

    /** They are one quantity seen from two sides, so agreement needs no comment. */
    @Test
    fun `the two counts agreeing is not remarked on`() {
        val gc = pause(5_750_000_000, 26, 1_815_000_000, gc = 5_720_000_000).lines(62_700_000_000)[1]
        assertFalse("not GC" in gc, gc)
        assertFalse("more than" in gc, gc)
    }

    /** The one case where "usually GC" on the row above is wrong, so the row must take it back. */
    @Test
    fun `paused time the JVM did not count as collection is called out`() {
        val gc = pause(5_000_000_000, 10, 1_000_000_000, gc = 1_000_000_000).lines(60 * second)[1]
        assertTrue("4.00 s was not GC" in gc, gc)
    }

    @Test
    fun `collection the sampler could not see is called out`() {
        val gc = pause(1_000_000_000, 10, 200_000_000, gc = 3_000_000_000).lines(60 * second)[1]
        assertTrue("more than the sampler saw" in gc, gc)
        // And when every pause was under a step, so the sampler saw none at all.
        val none = pause(0, 0, 0, gc = 300_000_000).lines(60 * second)
        assertTrue("none the sampler could see" in none[0], none[0])
        assertTrue("300 ms" in none[1], none[1])
    }

    /** Never a number where the evidence is missing: the same rule the bound follows. */
    @Test
    fun `an unreadable collector says so instead of printing a figure`() {
        val gc = pause(5_000_000_000, 10, 1_000_000_000, gc = -1).lines(60 * second)[1]
        assertTrue("not counted" in gc, gc)
        assertFalse("by the JVM's own count" in gc, gc)
    }

    /** A parked sampler is late on every tick, and none of that lateness is anybody's pause. */
    @Test
    fun `a sampler that was not spinning claims no pauses`() {
        val lines = pause(9_000_000_000, 9_000, 20_000_000, gc = -1, measured = false).lines(60 * second)
        assertEquals(1, lines.size)
        assertTrue("not measured" in lines[0], lines[0])
        assertFalse("9.00 s" in lines[0], lines[0])
    }

    // ---------------------------------------------------------------- end to end

    /**
     * A real collection, forced in the middle of a real session, reaches the report.
     *
     * `System.gc()` is a stop-the-world collection on every collector this is likely to run under,
     * and with a quarter of a gigabyte live it holds the world for many steps. The assertions are
     * deliberately loose - that it was seen, and that the JVM counted one too - because how long a
     * collection takes is the machine's business and not a property of this code.
     */
    @Test
    fun `a forced collection shows up as a pause`() {
        val live = Array(4_000_000) { LongArray(4) }
        Profiler.start()
        try {
            repeat(3) {
                System.gc()
                Thread.sleep(20)
            }
        } finally {
            val report = Profiler.stop()
            Profiler.release()
            assertTrue(live.size > 0)
            assertTrue(report.pause.measured)
            assertTrue(report.pause.pauses >= 1, "pauses seen: ${report.pause.pauses}")
            assertTrue(report.pause.pausedNanos >= 1_000_000, "paused: ${report.pause.pausedNanos} ns")
            assertTrue(report.pause.longestNanos <= report.pause.pausedNanos)
            assertTrue("Paused" in report.render())
        }
    }
}
