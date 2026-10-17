package com.ticksnick

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The clock: reading the OS's output, the busy weighting, and what the report and the diff say. */
class ClockTest {

    private fun report(vararg samples: Double) = ClockReport(samples, ofBase = true, notMeasured = null)

    @Test
    fun `typeperf columns pair each core's clock with its busy time and skip the totals`() {
        val header = "\"(PDH-CSV 4.0)\",\"\\\\HOST\\Processor Information(0,0)\\% Processor Performance\"," +
                "\"\\\\HOST\\Processor Information(0,1)\\% Processor Performance\"," +
                "\"\\\\HOST\\Processor Information(0,_Total)\\% Processor Performance\"," +
                "\"\\\\HOST\\Processor Information(_Total)\\% Processor Performance\"," +
                "\"\\\\HOST\\Processor Information(0,0)\\% Processor Time\"," +
                "\"\\\\HOST\\Processor Information(0,1)\\% Processor Time\"," +
                "\"\\\\HOST\\Processor Information(0,_Total)\\% Processor Time\"," +
                "\"\\\\HOST\\Processor Information(_Total)\\% Processor Time\""
        val columns = WindowsClock.typeperfColumns(header)
        assertEquals(listOf(1 to 5, 2 to 6), columns)
        // Core 0 busy at 205%, core 1 idle at 90%: the run's clock is core 0's.
        val line = "\"10/17/2026 20:01:52.123\",\"205.0\",\"90.0\",\"150.0\",\"150.0\",\"100.0\",\"0.0\",\"50.0\",\"50.0\""
        assertEquals(205.0, WindowsClock.typeperfSample(line, columns), 1e-9)
        // The first line typeperf prints has no busy time yet: a gap, not a zero.
        val first = "\"10/17/2026 20:01:51.123\",\"205.0\",\"90.0\",\"150.0\",\"150.0\",\" \",\" \",\" \",\" \""
        assertTrue(WindowsClock.typeperfSample(first, columns).isNaN())
    }

    @Test
    fun `a second with the cores together under half a core busy is a gap`() {
        assertTrue(ClockSource.weighted(doubleArrayOf(200.0, 90.0), doubleArrayOf(0.2, 0.2)).isNaN())
        assertEquals(150.0, ClockSource.weighted(doubleArrayOf(200.0, 100.0), doubleArrayOf(0.5, 0.5)), 1e-9)
    }

    @Test
    fun `proc stat gives each core's busy fraction`() {
        val before = LinuxClock.procStat(listOf("cpu  9 0 9 9 0", "cpu0 100 0 0 100 0 0 0", "cpu1 0 0 0 200 0 0 0"))
        val now = LinuxClock.procStat(listOf("cpu  9 0 9 9 0", "cpu0 175 0 0 125 0 0 0", "cpu1 0 0 0 300 0 0 0"))
        assertEquals(setOf(0, 1), now.keys)
        assertEquals(0.75, LinuxClock.busyFraction(before.getValue(0), now.getValue(0)), 1e-9)
        assertEquals(0.0, LinuxClock.busyFraction(before.getValue(1), now.getValue(1)), 1e-9)
    }

    @Test
    fun `a steady clock says nothing, a moving one says how far, a missing one says why`() {
        assertEquals(emptyList(), report(200.0, 205.0, 198.0, 202.0, 201.0, 199.0, 203.0).careful())
        val moved = report(205.0, 206.0, 204.0, 205.0, 205.0, 150.0, 135.0, 135.0, 136.0, 134.0, 135.0).careful()
        assertEquals(1, moved.size)
        assertTrue("205% of base at the start, 135% at the end" in moved[0], moved[0])
        // One second caught in a collection is not a trend.
        assertEquals(emptyList(), report(200.0, 200.0, 200.0, 200.0, 120.0, 200.0, 200.0, 200.0, 200.0, 200.0).careful())
        // Too few samples to say anything.
        assertEquals(emptyList(), report(205.0, 135.0).careful())
        val mac = ClockReport(DoubleArray(0), ofBase = true, notMeasured = "macOS reads the CPU clock only as root")
        assertEquals(listOf("clock not measured: macOS reads the CPU clock only as root"), mac.careful())
        assertEquals(emptyList(), ClockReport.NOT_RECORDED.careful())
    }

    @Test
    fun `the diff compares the two runs' clocks and warns when they differ`() {
        val fast = report(205.0, 205.0, Double.NaN, 205.0)
        val slow = report(135.0, 135.0, 135.0)
        val differ = ClockReport.diffLines(fast, slow)
        assertEquals(2, differ.size)
        assertTrue("205% -> 135% of base" in differ[0], differ[0])
        assertTrue("1.52x" in differ[1], differ[1])
        assertEquals(1, ClockReport.diffLines(fast, report(200.0, 200.0)).size)
        assertTrue("- -> 135%" in ClockReport.diffLines(ClockReport.NOT_RECORDED, slow)[0])
        assertEquals(emptyList(), ClockReport.diffLines(ClockReport.NOT_RECORDED, ClockReport.NOT_RECORDED))
    }

    @Test
    fun `the clock survives a save and a load, and a file without one loads as not recorded`() {
        val id = Profiler.registerFine("clock-store")
        Profiler.start(strict = false)
        val until = System.nanoTime() + 50_000_000
        while (System.nanoTime() < until) op(id) { Thread.onSpinWait() }
        val profile = Profiler.stop()
        val file = Files.createTempFile("clock", PROFILE_EXTENSION)
        try {
            ProfileStore.save(profile, file)
            val without = Files.readAllLines(file).filterNot { it.startsWith("clock.") }
            Files.write(file, without)
            assertEquals(0, ProfileStore.load(file).clock.samples.size)

            Files.write(file, without + listOf("clock.ofBase\ttrue", "clock.notMeasured\t\\0", "clock.samples\t205.0 - 135.0"))
            val clock = ProfileStore.load(file).clock
            assertEquals(3, clock.samples.size)
            assertEquals(205.0, clock.samples[0])
            assertTrue(clock.samples[1].isNaN())
            assertEquals(170.0, clock.mean, 1e-9)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
