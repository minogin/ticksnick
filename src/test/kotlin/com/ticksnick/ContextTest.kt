package com.ticksnick

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rule every label follows - **a label closes as the last one open in its own context** - and the
 * failures for each way to break it: closed wrong, left open when its context ends, exited where it
 * was not open and still open at the end.
 *
 * A coroutine's switch is played by hand: an `op` block compiles to `openCoarse` and `closeOp` on the
 * slot it opened on, so opening on one thread and closing on another is what the inlined block does
 * after a coroutine resumes elsewhere.
 */
class ContextTest {

    /** Named threads that stay alive until the test ends, each releasing its slot as it exits. */
    private val workers = mutableListOf<ExecutorService>()

    private fun worker(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                r.run()
            } finally {
                Profiler.release()
            }
        }, name)
    }.also { workers += it }

    private fun <T> ExecutorService.run(body: () -> T): T = submit(Callable { body() }).get()

    @AfterTest
    fun shutDown() {
        workers.forEach { it.shutdown() }
        workers.forEach { it.awaitTermination(5, TimeUnit.SECONDS) }
    }

    /** One session around [body]; its failure, or null. */
    private fun session(body: () -> Unit): String? {
        Profiler.start(tickMillis = 1.0)
        try {
            body()
        } finally {
            return Profiler.stop().failure
        }
    }

    private fun assertFails(failure: String?, vararg parts: String) {
        val f = assertNotNull(failure, "the run did not fail")
        for (p in parts) assertTrue(p in f, "missing [$p] in: $f")
    }

    // ---------------------------------------------------------------- one thread

    @Test
    fun `1 nested op blocks close in order`() {
        val a = Profiler.registerCoarse("ctx:1a")
        val b = Profiler.registerCoarse("ctx:1b")
        val failure = session { worker("w1").run { op(a) { op(b) { } } } }
        assertNull(failure)
    }

    @Test
    fun `2 a crossed exit fails, naming the exit missed`() {
        val a = Profiler.registerCoarse("ctx:2a")
        val b = Profiler.registerCoarse("ctx:2b")
        val failure = session {
            worker("w2").run {
                Profiler.enter(a)
                Profiler.enter(b)
                Profiler.exit(a)
                // Closed with `b` above it, so what follows is not billed to either.
                assertNull(Profiler.currentExecution())
            }
        }
        assertFails(failure, "`ctx:2a` closed while `ctx:2b` was still open", "`exit(ctx:2b)` was missed")
    }

    @Test
    fun `3 an enter left open inside an op fails at the op's close`() {
        val a = Profiler.registerCoarse("ctx:3a")
        val b = Profiler.registerCoarse("ctx:3b")
        val failure = session {
            worker("w3").run {
                op(a) { Profiler.enter(b) }
                assertNull(Profiler.currentExecution(), "the op did not close with what was left above it")
            }
        }
        assertFails(failure, "`ctx:3a` closed while `ctx:3b` was still open", "`exit(ctx:3b)` was missed")
    }

    @Test
    fun `4 an exit with nothing open is ignored`() {
        val a = Profiler.registerCoarse("ctx:4a")
        val f = Profiler.registerFine("ctx:4f")
        val failure = session {
            worker("w4").run {
                Profiler.exit(a)
                Profiler.exit(f)
            }
        }
        assertNull(failure)
    }

    @Test
    fun `an exit inside the op block of the label it closes fails`() {
        val a = Profiler.registerCoarse("ctx:own-block")
        val failure = session { worker("w-own").run { op(a) { Profiler.exit(a) } } }
        assertFails(failure, "`exit(ctx:own-block)` found `ctx:own-block` opened by an `op` block")
    }

    @Test
    fun `an exit inside another label's op block fails`() {
        val a = Profiler.registerCoarse("ctx:crossed-a")
        val b = Profiler.registerCoarse("ctx:crossed-b")
        val failure = session {
            worker("w-crossed").run {
                Profiler.enter(a)
                op(b) { Profiler.exit(a) }
                Profiler.exit(a)
            }
        }
        assertFails(failure, "the `exit` is inside the `op` block of `ctx:crossed-b`")
    }

    // ---------------------------------------------------------------- a fork: within

    @Test
    fun `5 a pool task's own ops under propagating`() {
        val search = Profiler.registerCoarse("ctx:5search")
        val shard = Profiler.registerCoarse("ctx:5shard")
        val pool = worker("w5").propagating()
        val failure = session {
            op(search) {
                val parent = Profiler.currentExecution()
                pool.submit(Callable { op(shard) { assertSame(parent, Profiler.currentExecution()?.parent) } }).get()
            }
        }
        assertNull(failure)
    }

    @Test
    fun `6 a task that enters and never exits fails at the end of within`() {
        val search = Profiler.registerCoarse("ctx:6search")
        val shard = Profiler.registerCoarse("ctx:6shard")
        val pool = worker("w6")
        val failure = session {
            op(search) {
                val parent = Profiler.currentExecution()
                pool.run {
                    within(parent) { Profiler.enter(shard) }
                    assertNull(Profiler.currentExecution(), "the task's thread was left inside something")
                }
            }
        }
        assertFails(failure, "`ctx:6shard` was still open at the end of `within`")
    }

    @Test
    fun `a fine label entered in within and never exited fails at its end`() {
        val search = Profiler.registerCoarse("ctx:within-fine-search")
        val parse = Profiler.registerFine("ctx:within-fine-parse")
        val failure = session {
            op(search) {
                val parent = Profiler.currentExecution()
                worker("w-wf").run {
                    within(parent) { Profiler.enter(parse) }
                    assertEquals(NO_OP, Profiler.slot().getOpaque(), "the fine label stayed on the thread")
                }
            }
        }
        assertFails(failure, "`ctx:within-fine-parse` was still open at the end of `within`")
    }

    @Test
    fun `7 a task closing the op it works within fails, and closes nothing`() {
        val search = Profiler.registerCoarse("ctx:7search")
        val failure = session {
            op(search) {
                val parent = assertNotNull(Profiler.currentExecution())
                worker("w7").run { within(parent) { Profiler.exit(search) } }
                assertSame(parent, Profiler.currentExecution(), "the borrowed execution was closed")
            }
        }
        assertFails(failure, "`ctx:7search` was opened in thread", "closed in a task working within `ctx:7search`", "it is only borrowed there")
    }

    // Row 8, a task still running within an op after it closed, is the stale failure: SamplerCoarseTest.

    // ---------------------------------------------------------------- a move: a coroutine's switch, by hand

    @Test
    fun `9 an op block that ends on another thread fails and writes no other thread's slot`() {
        val download = Profiler.registerCoarse("ctx:9download")
        val other = Profiler.registerCoarse("ctx:9other")
        val first = worker("worker-1")
        val second = worker("worker-3")
        val failure = session {
            val (slot, e) = first.run { Profiler.slot().let { it to it.openCoarse(download.id) } }
            second.run {
                op(other) {
                    slot.closeOp(e)
                    assertEquals(other.id, Profiler.currentExecution()?.type, "the closing thread's slot was changed")
                }
            }
            assertSame(e, first.run { Profiler.currentExecution() }, "the opening thread's slot was written")
            first.run { Profiler.slot().unwindTo(null) }
        }
        assertFails(
            failure,
            "`ctx:9download` opened in thread `worker-1` and closed in thread `worker-3`",
            "a coroutine suspended inside it; TickSnick does not follow coroutines yet",
        )
    }

    @Test
    fun `an op block closing over another op block still open fails as a suspension`() {
        val a = Profiler.registerCoarse("ctx:susp-a")
        val b = Profiler.registerCoarse("ctx:susp-b")
        val failure = session {
            worker("w-susp").run {
                val slot = Profiler.slot()
                val ea = slot.openCoarse(a.id)
                slot.openCoarse(b.id) // another coroutine's op, opened while the first was suspended
                slot.closeOp(ea)
                assertNull(Profiler.currentExecution())
            }
        }
        assertFails(failure, "`ctx:susp-a` closed while `ctx:susp-b` was still open", "a coroutine suspended inside one of them")
    }

    @Test
    fun `11 a coroutine resuming on the same thread is not caught - the known gap`() {
        val a = Profiler.registerCoarse("ctx:11a")
        val b = Profiler.registerCoarse("ctx:11b")
        val failure = session {
            worker("w11").run {
                val slot = Profiler.slot()
                val ea = slot.openCoarse(a.id)
                op(b) { } // another coroutine's whole op, run while the first was suspended
                slot.closeOp(ea)
            }
        }
        assertNull(failure)
    }

    // ---------------------------------------------------------------- enter and exit on different threads

    @Test
    fun `12 an exit where the label is not open, inside something else, fails`() {
        val request = Profiler.registerCoarse("ctx:12request")
        val handler = Profiler.registerCoarse("ctx:12handler")
        val a = worker("w12a")
        val failure = session {
            a.run { Profiler.enter(request) }
            worker("w12b").run { op(handler) { Profiler.exit(request) } }
            a.run { Profiler.exit(request) }
        }
        assertFails(failure, "`ctx:12request` closed in thread `w12b`, where it was not open and `ctx:12handler` was")
    }

    @Test
    fun `13 an exit where the label is not open, inside nothing, fails at stop if it is still open`() {
        val request = Profiler.registerCoarse("ctx:13request")
        val a = worker("w13a")
        val failure = session {
            a.run { Profiler.enter(request) }
            worker("w13b").run { Profiler.exit(request) }
        }
        a.run { Profiler.slot().unwindTo(null) }
        assertFails(
            failure,
            "`ctx:13request` was exited 1 time in a thread or coroutine where it was not open, and is still open in thread `w13a`",
            "carry it across with `using`",
        )
    }

    @Test
    fun `12 a fine exit where the label is not open, inside something else, fails`() {
        val request = Profiler.registerFine("ctx:12f-request")
        val handler = Profiler.registerFine("ctx:12f-handler")
        val a = worker("w12fa")
        val failure = session {
            a.run { Profiler.enter(request) }
            worker("w12fb").run { op(handler) { Profiler.exit(request) } }
            a.run { Profiler.exit(request) }
        }
        assertFails(failure, "`ctx:12f-request` closed in thread `w12fb`, where it was not open and `ctx:12f-handler` was")
    }

    @Test
    fun `13 a fine exit where the label is not open, inside nothing, fails at stop if it is still open`() {
        val request = Profiler.registerFine("ctx:13f-request")
        val a = worker("w13fa")
        val failure = session {
            a.run { Profiler.enter(request) }
            worker("w13fb").run { Profiler.exit(request) }
        }
        a.run { Profiler.exit(request) }
        assertFails(
            failure,
            "`ctx:13f-request` was exited 1 time in a thread or coroutine where it was not open, and is still open in thread `w13fa`",
            "carry it across with `using`",
        )
    }

    @Test
    fun `13 with a context of its own, a request entered on one thread exits on another`() {
        val request = Profiler.registerCoarse("ctx:13ctx-request")
        val render = Profiler.registerCoarse("ctx:13ctx-render")
        val failure = session {
            val ctx = Profiler.newContext(Profiler.currentExecution())
            worker("w13c").run { using(ctx) { Profiler.enter(request) } }
            worker("w13d").run {
                using(ctx) {
                    op(render) { assertEquals(request.id, Profiler.currentExecution()?.parent?.type) }
                }
                assertNull(Profiler.currentExecution(), "the context stayed on the thread")
            }
            worker("w13e").run { using(ctx) { Profiler.exit(request) } }
            ctx.end()
        }
        assertNull(failure)
        val stat = assertNotNull(Profiler.coarseTotals()[request.id])
        assertEquals(1, stat.count, "the request's span was not recorded once")
    }

    @Test
    fun `a context ended with a label still open fails`() {
        val request = Profiler.registerCoarse("ctx:end-request")
        val failure = session {
            val ctx = Profiler.newContext(null)
            worker("w-end").run { using(ctx) { Profiler.enter(request) } }
            ctx.end()
        }
        assertFails(failure, "`ctx:end-request` was still open at `end()` of the context made by `newContext`")
    }

    @Test
    fun `a fine label must not cross the end of using`() {
        val parse = Profiler.registerFine("ctx:using-fine")
        val failure = session {
            val ctx = Profiler.newContext(null)
            worker("w-uf").run { using(ctx) { Profiler.enter(parse) } }
            ctx.end()
        }
        assertFails(failure, "`ctx:using-fine` was still open at the end of `using`")
    }

    @Test
    fun `a context on two threads at once fails`() {
        val failure = session {
            val ctx = Profiler.newContext(null)
            val m = worker("w-in-use-a").run { ctx.mount() }
            worker("w-in-use-b").run { using(ctx) { } }
            workers[0].run { m.close() }
        }
        assertFails(failure, "the context made by `newContext` was put on thread `w-in-use-b` while still on thread `w-in-use-a`")
    }

    @Test
    fun `using nested on one thread is one mount`() {
        val failure = session {
            val ctx = Profiler.newContext(null)
            worker("w-nested").run {
                using(ctx) { using(ctx) { } }
                assertSame(Profiler.slot().own, Profiler.slot().context)
            }
        }
        assertNull(failure)
    }

    @Test
    fun `within that ends on another thread fails and writes no other thread's slot`() {
        val failure = session {
            val (slot, c) = worker("w-moved-a").run {
                val s = Profiler.slot()
                val c = Context(WITHIN, null)
                s.mount(c)
                s to c
            }
            worker("w-moved-b").run { slot.unmount(c) }
            assertSame(c, workers[0].run { Profiler.slot().context }, "the first thread's slot was written")
        }
        assertFails(failure, "`within` began in thread `w-moved-a` and ended in thread `w-moved-b`")
    }

    @Test
    fun `using that ends on another thread fails and writes no other thread's slot`() {
        val failure = session {
            val ctx = Profiler.newContext(null)
            val slot = worker("w-using-moved-a").run { Profiler.slot().also { it.mount(ctx) } }
            worker("w-using-moved-b").run { slot.unmount(ctx) }
            assertSame(ctx, workers[0].run { Profiler.slot().context }, "the first thread's slot was written")
            workers[0].run { slot.unmount(ctx) }
        }
        assertFails(failure, "`using` began in thread `w-using-moved-a` and ended in thread `w-using-moved-b`")
    }

    @Test
    fun `a mount left on inside within fails at its end, and is taken off`() {
        val failure = session {
            val ctx = Profiler.newContext(null)
            worker("w-left-on").run {
                within(null) { ctx.mount() }
                assertSame(Profiler.slot().own, Profiler.slot().context, "the thread was left with a context on it")
            }
        }
        assertFails(failure, "`within` ended while the context made by `newContext` was still on its thread")
    }

    @Test
    fun `an op that ends with a mount left on fails`() {
        val a = Profiler.registerCoarse("ctx:op-left-on")
        val failure = session {
            val ctx = Profiler.newContext(null)
            worker("w-op-left-on").run {
                val m = op(a) { ctx.mount() }
                m.close()
            }
        }
        assertFails(failure, "`ctx:op-left-on` opened in thread `w-op-left-on` and closed in the context made by `newContext`")
    }

    // ---------------------------------------------------------------- expectBalanced and virtual threads

    @Test
    fun `expectBalanced in a task checks only what the task opened`() {
        val search = Profiler.registerCoarse("ctx:eb-search")
        val shard = Profiler.registerCoarse("ctx:eb-shard")
        val failure = session {
            op(search) {
                val parent = Profiler.currentExecution()
                worker("w-eb").run {
                    within(parent) {
                        assertTrue(Profiler.expectBalanced(), "the borrowed execution read as a leak")
                        Profiler.enter(shard)
                        assertFalse(Profiler.expectBalanced())
                        assertSame(parent, Profiler.currentExecution(), "the reset went past what the task opened")
                    }
                }
            }
        }
        assertFails(failure, "`ctx:eb-shard` was still open at `expectBalanced()` on thread `w-eb`")
    }

    @Test
    fun `15 virtual threads follow the rule as any thread does`() {
        val search = Profiler.registerCoarse("ctx:15search")
        val shard = Profiler.registerCoarse("ctx:15shard")
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val failure = session {
            op(search) {
                val parent = Profiler.currentExecution()
                (1..8).map { pool.submit(Callable { within(parent) { op(shard) { Thread.sleep(1) } } }) }.forEach { it.get() }
            }
        }
        pool.shutdown()
        assertNull(failure)
    }
}
