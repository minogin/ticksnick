package com.ticksnick

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bytes allocated under each coarse path, asked for at `start`. Each array below is a known size, so
 * a row can be checked against it: within [SLACK] of the arrays, which leaves room for what the
 * label itself allocates and nothing else.
 *
 * Every path is run once first. The first execution on a path carries a few KB of the profiler's
 * own, and the first fork of a JVM some tens of KB of class loading, which are real and not what
 * these check.
 */
class AllocationsTest {

    private companion object {
        const val MB = 1 shl 20

        /** Bytes per execution beyond the arrays that a row may read. */
        const val SLACK = 4_096.0
    }

    @BeforeTest
    fun warmUp() {
        nested(1)
        fork()
    }

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling: the test stopped it itself.
        }
    }

    @Volatile
    private var sink: ByteArray? = null

    private val outer = Profiler.registerCoarse("alloc.outer")
    private val inner = Profiler.registerCoarse("alloc.inner")

    private fun Profile.node(name: String): CoarseNode = coarseTree.flatMap { it.flatten() }.first { it.name == name }

    private fun assertBytes(want: Double, got: Double, what: String) {
        assertTrue(got >= want && got <= want + SLACK, "$what: $got bytes, want $want to ${want + SLACK}")
    }

    private fun nested(times: Int = 5) = repeat(times) {
        op(outer) {
            sink = ByteArray(MB)
            op(inner) { sink = ByteArray(2 * MB) }
        }
    }

    private fun fork() = op(outer) {
        val ctx = captureCoarse()
        thread { withCoarse(ctx) { sink = ByteArray(MB) } }.join()
    }

    @Test
    fun `a row counts what ran under it, its coarse children included`() {
        Profiler.start(strict = false, allocations = true)
        nested()
        val p = Profiler.stop()

        assertBytes(2.0 * MB, p.allocatedPerExecution(p.node("alloc.inner")), "inner")
        assertBytes(3.0 * MB, p.allocatedPerExecution(p.node("alloc.outer")), "outer")
    }

    /** The thread that opened the execution does not allocate this; a thread working under it does. */
    @Test
    fun `bytes allocated on a thread that carries the context count under the row`() {
        Profiler.start(strict = false, allocations = true)
        repeat(5) {
            fork()
        }
        val p = Profiler.stop()

        assertBytes(MB.toDouble(), p.allocatedPerExecution(p.node("alloc.outer")), "the fork")
    }

    @Test
    fun `a session that did not ask counts nothing and draws no column`() {
        Profiler.start(strict = false)
        nested()
        val p = Profiler.stop()

        assertEquals(0L, p.node("alloc.outer").allocated)
        assertFalse("Allocated" in p.render(), "a column nobody asked for")
    }

    /** A thread's total runs from its birth, and what it allocated before the session is not the session's. */
    @Test
    fun `a session starts from what the thread had already allocated`() {
        Profiler.start(strict = false, allocations = true)
        nested(1)
        Profiler.stop()
        sink = ByteArray(8 * MB)
        Profiler.start(strict = false, allocations = true)
        nested(1)
        val p = Profiler.stop()

        assertBytes(3.0 * MB, p.allocatedPerExecution(p.node("alloc.outer")), "the second session")
    }

    @Test
    fun `the column is drawn in line, saved, and diffed`() {
        Profiler.start(strict = false, allocations = true)
        nested()
        val a = Profiler.stop()
        Profiler.start(strict = false)
        nested()
        val off = Profiler.stop()

        val lines = a.render().lines()
        val head = lines.first { it.startsWith("Coarse operation") }
        val end = head.indexOf("Allocated") + "Allocated".length
        assertEquals(' ', head[head.indexOf("Allocated") - 1])
        val row = lines.first { it.startsWith("alloc.outer ") && "|" !in it }
        assertEquals("3.00 MB", row.substring(end - 7, end), row)

        val folder = Files.createTempDirectory("alloc")
        val loaded = Profile.load(a.save(folder))
        assertTrue(loaded.allocations)
        assertEquals(a.node("alloc.outer").allocated, loaded.node("alloc.outer").allocated)

        val both = ProfileDiff(a, a).render()
        assertTrue("3.00 MB -> 3.00 MB" in both, both)
        val oneSide = ProfileDiff(off, a).render()
        assertTrue("CAREFUL" in oneSide && "allocations: counted on one side only" in oneSide, oneSide)
        assertTrue("- -> 3.00 MB" in oneSide, oneSide)
    }

    /** With two runs a side a diff goes through a different table, and so does the report over runs. */
    @Test
    fun `the tables over runs show it`() {
        val before = Files.createTempDirectory("alloc-before")
        val after = Files.createTempDirectory("alloc-after")
        repeat(2) {
            Profiler.start(strict = false, allocations = true)
            nested()
            Profiler.stop().save(before)
            Profiler.start(strict = false, allocations = true)
            nested()
            Profiler.stop().save(after)
        }
        val runs = TickSnick.load(before)
        val across = runs.render()
        assertTrue(across.lines().any { it.startsWith("alloc.outer") && it.endsWith("3.00 MB") }, across)
        val diff = TickSnick.diff(runs, TickSnick.load(after))
        assertTrue(diff.lines().any { it.startsWith("alloc.outer") && "3.00 MB -> 3.00 MB" in it }, diff)
    }

    @Test
    fun `counting them raises the coarse floor`() {
        val base = Profile.load(emptySave(false)).coarseContextNanos
        val counted = Profile.load(emptySave(true)).coarseContextNanos
        assertEquals(COARSE_ALLOCATIONS_NANOS, counted - base, 1e-9)
    }

    private fun emptySave(allocations: Boolean): java.nio.file.Path {
        Profiler.start(strict = false, allocations = allocations)
        return Profiler.stop().save(Files.createTempDirectory("alloc-floor"))
    }
}
