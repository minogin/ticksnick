package com.ticksnick

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coarse names other tests already register: the registry holds 64 coarse types for the life of the
 * test JVM, and the suite is near it. A session resets every figure, so sharing a name shares nothing else.
 */
private const val JOB = "sampler-outer"
private const val PART = "sampler-inner"

/**
 * The fine rows under a coarse row, through a real sampler, where fine labels run in parallel.
 *
 * Each part of each execution is a timed spin, so the true share of every row is known: a row is
 * checked against it, not against what the code happened to print. The executions are a few
 * milliseconds and there are dozens of them, so every row rests on hundreds of samples, and the
 * tolerances are wide enough for a busy machine and narrow enough to fail a split of the row's
 * samples by label, which reads a leaf half on one thread and half on two as 25% / 26% / 49%.
 */
class OwnTimeTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling: the test stopped it itself.
        }
        // The pools' and drivers' threads have exited by now; give their slots back, as a session
        // would within a second, so the registry tests after this one start from a clean ceiling.
        Profiler.reclaimDeadSlots()
    }

    /** Shuts [pool] down and waits for its threads to exit, so their slots can be reclaimed. */
    private fun close(pool: java.util.concurrent.ExecutorService) {
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
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

    private val ms = 1_000_000L

    private fun nodeOf(p: Run, name: String): CoarseNode =
        p.coarseTree.flatMap { it.flatten() }.first { it.name == name }

    private fun ownOf(p: Run, coarse: String, fine: String): Run.OwnRow? =
        p.ownRowsOf(nodeOf(p, coarse))!!.firstOrNull { it.name == fine }

    private fun share(p: Run, coarse: String, fine: String): Double = ownOf(p, coarse, fine)?.share ?: 0.0

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(kotlin.math.abs(actual - expected) <= tolerance, "$what: expected $expected +/- $tolerance, was $actual")

    /**
     * A job half on its own thread and half handed to two helpers, on two drivers at once - a fine
     * label parallel inside one execution, and the executions side by side.
     */
    @Test
    fun `a fine label forked onto helpers counts its time once, and the parked caller not at all`() {
        val job = Profiler.registerCoarse(JOB)
        val a = Profiler.registerFine("own.a")
        val b = Profiler.registerFine("own.b")
        val helpers = Executors.newFixedThreadPool(4).propagating()
        Profiler.start(strict = false)
        val drivers = (1..2).map {
            thread {
                repeat(60) {
                    op(job) {
                        op(a) { spinFor(4 * ms) }
                        (1..2).map { helpers.submit(Callable { op(b) { spinFor(4 * ms) } }) }.forEach { it.get() }
                    }
                }
            }
        }
        drivers.forEach { it.join() }
        val p = Profiler.stop()
        close(helpers)

        assertNear(2.0, nodeOf(p, JOB).parallel, 0.4, "jobs at a time")
        assertNear(0.5, share(p, JOB, "own.a"), 0.12, "a, on the job's own thread")
        assertNear(0.5, share(p, JOB, "own.b"), 0.12, "b, on two helpers at once")
        assertNear(2.0, ownOf(p, JOB, "own.b")!!.parallel, 0.5, "b's threads at a time in one job")
        assertTrue(share(p, JOB, "unlabelled") < 0.1, "the driver parked on its helpers was counted as the job's own time")
    }

    /** A request that forks three coarse parts, waits, then merges on its own thread. */
    @Test
    fun `a fine label beside forked coarse children is the parent's remaining time`() {
        val request = Profiler.registerCoarse(JOB)
        val part = Profiler.registerCoarse(PART)
        val merge = Profiler.registerFine("own.merge")
        val pool = Executors.newFixedThreadPool(3).propagating()
        Profiler.start(strict = false)
        repeat(60) {
            op(request) {
                (1..3).map { pool.submit(Callable { op(part) { spinFor(6 * ms) } }) }.forEach { it.get() }
                op(merge) { spinFor(2 * ms) }
            }
        }
        val p = Profiler.stop()
        close(pool)

        val req = nodeOf(p, JOB)
        val parts = nodeOf(p, PART)
        val walls = p.wallsOf()
        assertNear(3.0, parts.parallel, 0.6, "parts at a time")
        assertNear(0.75, walls.getValue(parts) / walls.getValue(req), 0.1, "parts of the request")
        assertNear(0.25, share(p, JOB, "own.merge"), 0.08, "merge of the request")
        assertTrue(share(p, JOB, "unlabelled") < 0.08, "the request parked on its parts was counted as its own time")
    }

    /** Four threads each running the same request, which is all one fine label: side by side, not inside. */
    @Test
    fun `executions side by side divide their fine rows as they divide themselves`() {
        val request = Profiler.registerCoarse(JOB)
        val work = Profiler.registerFine("own.sidework")
        Profiler.start(strict = false)
        val threads = (1..4).map { thread { repeat(60) { op(request) { op(work) { spinFor(4 * ms) } } } } }
        threads.forEach { it.join() }
        val p = Profiler.stop()

        assertNear(4.0, nodeOf(p, JOB).parallel, 0.8, "requests at a time")
        assertNear(1.0, share(p, JOB, "own.sidework"), 0.12, "the work of the request")
        assertTrue(ownOf(p, JOB, "own.sidework")!!.parallel < PARALLEL_SHOWN, "one thread per request read as parallel")
    }

    /**
     * One fine label under two rows: 4 ms inside the part and 2 ms in the request's own time, so the
     * tree shows it twice and its total, about three quarters of the request, once - beside the part.
     */
    @Test
    fun `a fine label under two rows gets its total once, beside the larger`() {
        val request = Profiler.registerCoarse(JOB)
        val part = Profiler.registerCoarse(PART)
        val work = Profiler.registerFine("own.spread")
        Profiler.start(strict = false)
        repeat(60) {
            op(request) {
                op(part) { op(work) { spinFor(4 * ms) } }
                op(work) { spinFor(2 * ms) }
            }
        }
        val p = Profiler.stop()

        val text = p.render()
        val noted = text.lines().filter { "in all, under 2 operations" in it }
        assertTrue(noted.size == 1, "the total was not drawn once:\n$text")
        assertTrue(noted.single().trimStart().startsWith("- own.spread"), "the note is not on the label's row: ${noted.single()}")
        // Beside the part, the larger of the two: the part's row comes first in the tree, before the request's own.
        val first = text.lines().first { it.trimStart().startsWith("- own.spread") }
        assertTrue("in all" in first, "the note is not beside the larger row:\n$text")
    }

    /** A plain thread does not carry the context: its fine label is under no coarse row at all. */
    @Test
    fun `a fine label on a thread that does not carry the context is not under the request`() {
        val request = Profiler.registerCoarse(JOB)
        val work = Profiler.registerFine("own.plainwork")
        Profiler.start(strict = false)
        repeat(40) { op(request) { thread { op(work) { spinFor(4 * ms) } }.join() } }
        val p = Profiler.stop()

        assertNull(ownOf(p, JOB, "own.plainwork"), "work on a thread with no context was put under the request")
        // Nobody else was under the request, so the caller's wait is the request's own time - and the
        // report's line about work under no coarse span names the label.
        assertNear(1.0, share(p, JOB, "unlabelled"), 0.15, "the request waiting alone")
        assertTrue(p.labelledOutsideCoarse > 0, "work under no coarse span was not counted as such")
        assertFalse(p.render().contains("- own.plainwork"), "the report drew the label under the request")
    }
}
