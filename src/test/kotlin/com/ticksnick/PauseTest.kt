package com.ticksnick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The time every thread was stopped: how it is added up, how much of it the JVM confirms, which of
 * the JVM's counters may be summed for that, and what the report promises to say.
 *
 * The arithmetic is tested here. That the lateness of a tick *is* a pause is not something a unit
 * test can show - that is the 5.75 s against the JVM's 5.72 s in docs/findings.md - and the live
 * tests at the bottom only show the wiring carries a real pause through to the report.
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
    private val ms = 1_000_000L
    private val second = 1_000_000_000L

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

    // ---------------------------------------------------------------- what the JVM confirms

    /** A tracker that has seen one pause of [lost], with the JVM's count at [gcAtStart] when it began. */
    private fun tracker(lost: Long, gcAtStart: Long = 0) =
        PauseTracker(step, gcAtStart).also { if (lost > 0) it.tick(dueNanos = 0, nowNanos = lost) }

    @Test
    fun `a pause the JVM also counted is confirmed`() {
        val t = tracker(lost = 300 * ms, gcAtStart = 40 * ms)
        assertEquals(298 * ms, t.settle(gcNowNanos = 338 * ms))
        assertEquals(298 * ms, t.confirmedNanos)
    }

    /**
     * The negative control, and the reason anything is confirmed at all.
     *
     * A sampler that lost its core while the workers ran on has lateness and no collection behind
     * it. Taking that out of wall time would make the bound on every share tighter than the truth,
     * which is the one direction a bound must not be wrong in.
     */
    @Test
    fun `lateness with no collection behind it confirms nothing`() {
        val t = tracker(lost = 5 * second, gcAtStart = 40 * ms)
        assertEquals(0, t.settle(gcNowNanos = 40 * ms))
        assertEquals(0, t.confirmedNanos)
        assertEquals(5 * second, t.pausedNanos, "what the sampler saw is still reported")
    }

    @Test
    fun `no more is confirmed than the sampler saw, and no more than the JVM counted`() {
        assertEquals(1 * second, tracker(lost = 5 * second).apply { settle(1 * second) }.confirmedNanos)
        assertEquals(1 * second, tracker(lost = 1 * second).apply { settle(5 * second) }.confirmedNanos)
    }

    /** Collections under a step lose no tick, so the sampler has nothing to match them against. */
    @Test
    fun `collection the sampler could not see is not confirmed`() {
        val t = tracker(lost = 0)
        assertEquals(0, t.settle(300 * ms))
        assertEquals(0, t.confirmedNanos)
    }

    @Test
    fun `an unreadable collector confirms nothing`() {
        assertEquals(0, tracker(lost = 5 * second, gcAtStart = -1).settle(5 * second))
        assertEquals(0, tracker(lost = 5 * second, gcAtStart = 0).settle(-1))
    }

    /** Each call hands on only what is new, so a window is never charged for an earlier one's pause. */
    @Test
    fun `settling returns what was confirmed since the last time`() {
        val t = PauseTracker(step, gcAtStartNanos = 0)
        t.tick(dueNanos = 0, nowNanos = 100 * ms)
        assertEquals(100 * ms, t.settle(100 * ms))
        assertEquals(0, t.settle(100 * ms), "nothing happened in between")
        t.tick(dueNanos = second, nowNanos = second + 50 * ms)
        assertEquals(50 * ms, t.settle(150 * ms))
        assertEquals(150 * ms, t.confirmedNanos)
    }

    /**
     * The two halves of one pause can land either side of a call: the JVM counts a collection when
     * it ends, the sampler on its next tick. Matched cumulatively, it is confirmed one call late
     * rather than never.
     */
    @Test
    fun `a pause counted by the JVM before the sampler's next tick is confirmed afterwards`() {
        val t = PauseTracker(step, gcAtStartNanos = 0)
        assertEquals(0, t.settle(200 * ms))
        t.tick(dueNanos = 0, nowNanos = 200 * ms)
        assertEquals(200 * ms, t.settle(200 * ms))
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

    private fun pause(
        paused: Long, count: Long, longest: Long, gc: Long,
        confirmed: Long = if (gc < 0) 0 else minOf(paused, gc), measured: Boolean = true,
    ) = PauseReport(measured, paused, count, longest, gc, confirmed)

    /** `Time on CPU` is a share of run time, so run time has to be on the page whatever happened. */
    @Test
    fun `wall time is split on a run with no pause too`() {
        for (quiet in listOf(PauseReport.NONE, pause(0, 0, 0, gc = 0))) {
            val lines = quiet.lines(60 * second)
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines[0].startsWith("Wall time"), lines[0])
            assertTrue("60.0 s = 60.0 s run + 0 paused" in lines[0], lines[0])
        }
    }

    @Test
    fun `wall time is run plus paused, and the pauses are counted under it`() {
        val lines = pause(5_780_000_000, 22, 1_850_000_000, gc = 5_790_000_000).lines(66 * second)
        assertEquals(3, lines.size, lines.toString())
        assertTrue("66.0 s = 60.2 s run + 5.78 s paused (8.76%)" in lines[0], lines[0])
        assertTrue(lines[1].trim().startsWith("Paused"), lines[1])
        for (part in listOf("22 pauses", "1.85 s")) assertTrue(part in lines[1], "$part in: ${lines[1]}")
        assertTrue("5.79 s" in lines[2], lines[2])
    }

    /** They are one quantity seen from two sides, so agreement needs no comment. */
    @Test
    fun `the two counts agreeing is not remarked on`() {
        val gc = pause(5_750_000_000, 26, 1_815_000_000, gc = 5_720_000_000).lines(62_700_000_000)[2]
        assertFalse("not GC" in gc, gc)
        assertFalse("more than" in gc, gc)
    }

    /**
     * The split shows what was confirmed, so the page agrees with itself: the run time on the first
     * line is the run time the duty cycle divides by. What the sampler lost beyond that is named,
     * and said to be in run time, rather than dropped.
     */
    @Test
    fun `paused time the JVM did not count as collection stays in run time and is called out`() {
        val lines = pause(5 * second, 10, 1 * second, gc = 1 * second).lines(60 * second)
        assertTrue("60.0 s = 59.0 s run + 1.00 s paused" in lines[0], lines[0])
        assertTrue("the other 4.00 s the sampler lost was not GC" in lines[2], lines[2])
        assertTrue("run time" in lines[2], lines[2])
    }

    @Test
    fun `collection the sampler could not see is called out`() {
        val gc = pause(1 * second, 10, 200 * ms, gc = 3 * second).lines(60 * second)[2]
        assertTrue("more than the sampler saw" in gc, gc)
        // And when every pause was under a step, so the sampler saw none at all.
        val none = pause(0, 0, 0, gc = 300 * ms).lines(60 * second)
        assertTrue("+ 0 paused" in none[0], none[0])
        assertTrue("300 ms" in none[1], none[1])
        assertTrue("run time" in none[1], none[1])
    }

    /** Never a number where the evidence is missing: the same rule the bound follows. */
    @Test
    fun `an unreadable collector subtracts nothing and says what the sampler saw`() {
        val lines = pause(5 * second, 10, 1 * second, gc = -1).lines(60 * second)
        assertTrue("60.0 s = 60.0 s run + 0 paused" in lines[0], lines[0])
        assertTrue("5.00 s in 10 pauses" in lines[1], lines[1])
        assertTrue("not confirmed" in lines[1], lines[1])
        assertTrue("not counted" in lines[2], lines[2])
        assertFalse("by the JVM's own count" in lines[2], lines[2])
    }

    /** A parked sampler is late on every tick, and none of that lateness is anybody's pause. */
    @Test
    fun `a sampler that was not spinning claims no pauses`() {
        val lines = pause(9 * second, 9_000, 20 * ms, gc = -1, measured = false).lines(60 * second)
        assertEquals(2, lines.size, lines.toString())
        assertTrue("not measured" in lines[1], lines[1])
        assertFalse(lines.any { "9.00 s" in it }, lines.toString())
    }

    // ---------------------------------------------------------------- time on CPU, of run time

    private fun duty(cpu: Long, wall: Long, paused: Long, labelled: Double = Double.NaN) = DutyReport(
        labelledDuty = labelled, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
        resolutionNanos = 15_625_000, windowNanos = second, windows = 60, threads = 1,
        cpuNanos = cpu, wallNanos = wall, minWindowDuty = 0.2, maxWindowDuty = 1.0,
        anomalies = 0, maxSampleNanos = 200_000, pausedNanos = paused,
    )

    /**
     * The run this was built for: 59.56 s of CPU over 66.0 s of wall, 5.78 s of it paused. Against
     * wall time that is 90.24% and a bound of 10.8 pp; against the time anything was running it is
     * 98.9% and 1.1 pp.
     */
    @Test
    fun `time on CPU is taken over run time`() {
        val d = duty(cpu = 59_560_000_000, wall = 66_000_000_000, paused = 5_780_000_000)
        assertEquals(0.9024, d.duty, 0.0001, "the wall-clock figure must not move: the machine floor reads it")
        assertEquals(0.9890, d.runDuty, 0.0001)
        assertEquals(1.11, d.boundPp, 0.01)
        val line = d.lines(66_000_000_000, 0).first()
        assertTrue("98.90% of run time" in line, line)
        assertFalse("wall time" in line, line)
    }

    @Test
    fun `with no pause run time is wall time and nothing moves`() {
        val d = duty(cpu = 59_560_000_000, wall = 66_000_000_000, paused = 0)
        assertEquals(d.duty, d.runDuty, 1e-12)
        assertEquals(10.8, d.boundPp, 0.05)
    }

    /** The CPU counter moves in 15.6 ms steps and the pauses are subtracted exactly. */
    @Test
    fun `time on CPU never reads over a hundred percent`() {
        val d = duty(cpu = 1_010_000_000, wall = 2_000_000_000, paused = 1_000_000_000)
        assertEquals(1.0, d.runDuty, 1e-12)
        assertEquals(0.0, d.boundPp, 1e-9)
    }

    // ---------------------------------------------------------------- end to end

    /** Somewhere for the spin loop to land, so nothing can be optimised away. */
    @Volatile
    private var sink: Long = 0

    /**
     * A real collection, forced in the middle of a real session, reaches the report - as a pause
     * the sampler saw, as one the JVM confirmed, and as wall time taken out of the duty cycle of a
     * thread that was working throughout.
     *
     * `System.gc()` is a stop-the-world collection on every collector this is likely to run under,
     * and with a quarter of a gigabyte live it holds the world for many steps. The assertions are
     * deliberately loose - that it was seen, and which way each figure moved - because how long a
     * collection takes is the machine's business and not a property of this code.
     */
    @Test
    fun `a forced collection shows up as a pause and comes out of run time`() {
        val live = Array(4_000_000) { LongArray(4) }
        val work = Profiler.registerFine("pauseTestWork")
        val stop = AtomicBoolean(false)
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Working inside a label until told to stop, then alive and outside every label until the
        // session has ended: the last duty window is taken at stop(), and a thread that has already
        // exited reads -1 from its CPU clock and is left out of it.
        val worker = Thread {
            try {
                while (!stop.get()) {
                    op(work) {
                        var s = sink or 1L
                        var i = 0
                        while (i < 4096) {
                            s = s * 31 + i
                            i++
                        }
                        sink = s
                    }
                }
                stopped.countDown()
                release.await()
            } finally {
                Profiler.release()
            }
        }
        Profiler.start()
        worker.start()
        val report = try {
            Thread.sleep(150)
            repeat(4) {
                System.gc()
                Thread.sleep(100)
            }
            stop.set(true)
            stopped.await()
            Profiler.stop()
        } finally {
            stop.set(true)
            release.countDown()
            worker.join()
        }
        assertTrue(live.size > 0)

        val p = report.pause
        assertTrue(p.measured)
        assertTrue(p.pauses >= 1, "pauses seen: ${p.pauses}")
        assertTrue(p.pausedNanos >= 1_000_000, "paused: ${p.pausedNanos} ns")
        assertTrue(p.longestNanos <= p.pausedNanos)
        assertTrue(p.gcNanos > 0, "the JVM counted no collection: ${p.gcNanos}")
        assertTrue(p.confirmedNanos > 0, "nothing was confirmed")
        assertTrue(p.confirmedNanos <= p.pausedNanos && p.confirmedNanos <= p.gcNanos)

        val rendered = report.render()
        assertTrue("Wall time" in rendered && "paused (" in rendered, rendered)

        // Only where the platform has a per-thread CPU clock to take a window with.
        val d = report.duty
        if (d.available) {
            assertTrue(d.pausedNanos > 0, "no pause reached the duty windows")
            assertTrue(d.pausedNanos <= d.wallNanos)
            assertTrue(d.runDuty > d.duty, "run ${d.runDuty} against wall ${d.duty}")
            assertTrue("of run time" in rendered, rendered)
        }
    }
}
