package com.ticksnick

import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `Cores` line and the clock on real sessions: the arithmetic is tested in RunTest and
 * DutyBoundTest, the plumbing from the clocks to the line only here.
 */
class CoresTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling: the test stopped it itself.
        }
        Profiler.reclaimDeadSlots()
    }

    @Volatile
    private var sink: Long = 0

    private fun spinFor(nanos: Long) {
        val until = System.nanoTime() + nanos
        var s = sink or 1L
        while (System.nanoTime() < until) {
            for (i in 0 until 256) s = s * 31 + i
        }
        sink = s
    }

    /** "profiled threads 1.97" -> 1.97. */
    private fun figure(line: String, name: String): Double =
        Regex("$name (\\d+\\.\\d+)").find(line)?.groupValues?.get(1)?.toDouble() ?: error("no '$name' in: $line")

    /**
     * Two labelled threads spinning for 2.5 s, each started inside the session and gone before it
     * ends, each reading its own CPU clock: the profiled threads' CPU in the report is their sum, less
     * at most one refresh at each thread's start and end - not a window less of each, which is what
     * counting a thread only from its first window boundary to its last would give, about a second here.
     * And the line's parts never add up to more than the processors.
     */
    @Test
    fun `the profiled threads' CPU is what the threads used`() {
        val spin = Profiler.registerFine("cores.spin")
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val used = java.util.concurrent.atomic.AtomicLong()
        Profiler.start(strict = false)
        Thread.sleep(200)
        val workers = (1..2).map {
            thread {
                val from = bean.currentThreadCpuTime
                op(spin) { spinFor(2_500_000_000) }
                used.addAndGet(bean.currentThreadCpuTime - from)
            }
        }
        workers.forEach { it.join() }
        Thread.sleep(200)
        val p = Profiler.stop()

        val counted = p.duty.profiledCpuNanos
        val slack = 2 * 2 * CORES_REFRESH_NANOS + 50_000_000
        assertTrue(
            counted in (used.get() - slack)..(used.get() + 50_000_000),
            "the report counted ${counted / 1e9} s of the threads' ${used.get() / 1e9} s"
        )
        val line = assertNotNull(p.duty.coresLine(), "no Cores line on a 3 s session")
        val processors = Runtime.getRuntime().availableProcessors()
        assertTrue(figure(line, "profiled threads") + figure(line, "the rest") <= processors + 0.3, "more cores than the JVM has: $line")
    }

    /**
     * A session shorter than the clock's first reading says nothing about the clock: `typeperf` is
     * stopped before it prints its header, and that is not "no counter found".
     */
    @Test
    fun `a short session prints no clock warning`() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.startsWith("windows") && !os.startsWith("linux")) return
        val tick = Profiler.registerFine("cores.short")
        Profiler.start(strict = false)
        op(tick) { spinFor(200_000_000) }
        val p = Profiler.stop()
        assertTrue(p.clock.careful().isEmpty(), "a 0.2 s session warned about the clock: ${p.clock.careful()}")
    }
}
