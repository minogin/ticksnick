package com.ticksnick

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coarse tree as the **hook** builds it, through a real session: `CoarseTest` draws a tree
 * somebody typed in, and nothing there says the sampler and the hook produce one with the right
 * paths, counts and spans. Every test here opens real contexts and reads `coarseTree` back.
 */
class TreeTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling, which is the normal case when the test stopped it itself.
        }
    }

    @Volatile
    private var sink: Long = 0

    private fun spinFor(nanos: Long) {
        val until = System.nanoTime() + nanos
        var s = sink or 1L
        while (System.nanoTime() < until) {
            var i = 0
            while (i < 1024) {
                s = s * 31 + i
                i++
            }
        }
        sink = s
    }

    private fun start() {
        Profiler.start(tickMillis = 1.0, strict = false)
    }

    private fun Run.root(name: String): CoarseNode? = coarseTree.firstOrNull { it.name == name }
    private fun CoarseNode.child(name: String): CoarseNode? = children.firstOrNull { it.name == name }

    /** Runs [body] on a thread of its own and gives its slot back, as a caller is asked to. */
    private fun onThread(name: String, body: () -> Unit): Thread =
        Thread({
            try {
                body()
            } finally {
                Profiler.release()
            }
        }, name)

    @Test
    fun `the hook builds the tree with counts and spans per path`() {
        val a = Profiler.registerCoarse("tree-a")
        val b = Profiler.registerCoarse("tree-b")
        val c = Profiler.registerCoarse("tree-c")
        start()
        repeat(2) {
            op(a) {
                op(b) { spinFor(2_000_000) }
                op(c) { spinFor(1_000_000) }
            }
        }
        val p = Profiler.stop()

        val ra = assertNotNull(p.root("tree-a"), "a is not a root: ${p.coarseTree.map { it.name }}")
        assertEquals(2L, ra.count)
        val rb = assertNotNull(ra.child("tree-b"))
        val rc = assertNotNull(ra.child("tree-c"))
        assertEquals(2L, rb.count)
        assertEquals(2L, rc.count)
        assertTrue(rb.spanSumNanos >= 4_000_000 && rc.spanSumNanos >= 2_000_000, "spans too short: ${rb.spanSumNanos}, ${rc.spanSumNanos}")
        assertTrue(rb.spanSumNanos + rc.spanSumNanos <= ra.spanSumNanos, "children exceed their parent")
        assertNull(p.root("tree-b"), "b is nested, not a root")
        // The per-type figures are the sum over the tree, so they agree with it.
        assertEquals(2L, p.coarse.first { it.name == "tree-b" }.count)
        assertEquals(rb.spanSumNanos, p.coarse.first { it.name == "tree-b" }.spanSumNanos)
    }

    @Test
    fun `an operation under two callers is one node per caller`() {
        val x = Profiler.registerCoarse("tree-x")
        val y = Profiler.registerCoarse("tree-y")
        val shared = Profiler.registerCoarse("tree-shared")
        start()
        op(x) { op(shared) { spinFor(500_000) } }
        op(y) {
            op(shared) { spinFor(500_000) }
            op(shared) { spinFor(500_000) }
        }
        val p = Profiler.stop()

        assertEquals(1L, assertNotNull(p.root("tree-x")).child("tree-shared")?.count)
        assertEquals(2L, assertNotNull(p.root("tree-y")).child("tree-shared")?.count)
        assertEquals(3L, p.coarse.first { it.name == "tree-shared" }.count, "the per-type count is the sum over callers")
        assertTrue("all callers together" in p.render(), "the shared block is missing:\n${p.render()}")
    }

    @Test
    fun `the same child under two paths is kept apart`() {
        val x = Profiler.registerCoarse("tree-px")
        val y = Profiler.registerCoarse("tree-py")
        val mid = Profiler.registerCoarse("tree-mid")
        val leaf = Profiler.registerCoarse("tree-leaf")
        start()
        op(x) { op(mid) { op(leaf) { spinFor(300_000) } } }
        op(y) { op(mid) { repeat(2) { op(leaf) { spinFor(300_000) } } } }
        val p = Profiler.stop()

        val underX = assertNotNull(p.root("tree-px")?.child("tree-mid")?.child("tree-leaf"))
        val underY = assertNotNull(p.root("tree-py")?.child("tree-mid")?.child("tree-leaf"))
        assertEquals(1L, underX.count, "the leaf under x carries the other path's executions")
        assertEquals(2L, underY.count)
    }

    @Test
    fun `a borrowed context is resolved into the borrowing thread's tree`() {
        val outer = Profiler.registerCoarse("tree-outer")
        val inner = Profiler.registerCoarse("tree-inner")
        start()
        Profiler.enter(outer)
        val ctx = assertNotNull(captureCoarse())
        val worker = onThread("tree-worker") {
            withCoarse(ctx) { op(inner) { spinFor(20_000_000) } }
        }
        worker.start()
        worker.join()
        Profiler.exit(outer)
        val p = Profiler.stop()

        val root = assertNotNull(p.root("tree-outer"))
        val child = assertNotNull(root.child("tree-inner"), "inner is not under outer: ${root.children.map { it.name }}")
        assertEquals(1L, child.count)
        assertTrue(child.inclusiveHits > 5, "the borrowed span was barely sampled: ${child.inclusiveHits}")
        assertNull(p.root("tree-inner"), "inner became a root of its own")
    }

    @Test
    fun `recursion past the depth bound goes to the overflow node`() {
        val r = Profiler.registerCoarse("tree-rec")
        fun rec(n: Int) { op(r) { if (n > 1) rec(n - 1) } }
        start()
        rec(20)
        val p = Profiler.stop()

        var deepest = 0
        var onPath = 0L
        var overflow: CoarseNode? = null
        fun walk(n: CoarseNode, depth: Int) {
            if (n.name == "tree-rec") { onPath += n.count; if (depth > deepest) deepest = depth }
            else if (n.name.startsWith("tree-rec, deeper than")) overflow = n
            for (c in n.children) walk(c, depth + 1)
        }
        for (root in p.coarseTree) walk(root, 1)
        fun dump(n: CoarseNode, d: Int): String =
            "  ".repeat(d) + n.name + " x" + n.count + System.lineSeparator() + n.children.joinToString("") { dump(it, d + 1) }
        assertEquals(MAX_COARSE_DEPTH, deepest, "the tree grew past the bound:" + System.lineSeparator() + p.coarseTree.joinToString("") { dump(it, 0) })
        assertEquals(MAX_COARSE_DEPTH.toLong(), onPath)
        assertEquals(4L, assertNotNull(overflow, "no overflow node").count)
        assertEquals(20L, p.coarse.first { it.name == "tree-rec" }.count, "the per-type count lost the overflow")
    }

    @Test
    fun `a thread that exited before the session ended is still in the tree`() {
        val t = Profiler.registerCoarse("tree-retired")
        start()
        val worker = onThread("tree-retiring") { op(t) { spinFor(5_000_000) } }
        worker.start()
        worker.join()
        val p = Profiler.stop()
        assertEquals(1L, assertNotNull(p.root("tree-retired"), "the retired thread's tree was lost").count)
    }

    @Test
    fun `executions before the session started are not in the tree`() {
        val z = Profiler.registerCoarse("tree-before")
        op(z) { spinFor(1_000_000) }
        start()
        spinFor(20_000_000)
        val p = Profiler.stop()
        assertEquals(0L, p.root("tree-before")?.count ?: 0L, "an execution from before the session was counted")
    }

    @Test
    fun `two threads on the same path merge into one node`() {
        val a = Profiler.registerCoarse("tree-merge-a")
        val b = Profiler.registerCoarse("tree-merge-b")
        start()
        val workers = (1..2).map { onThread("tree-merge-$it") { op(a) { op(b) { spinFor(5_000_000) } } } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val p = Profiler.stop()

        val ra = assertNotNull(p.root("tree-merge-a"))
        assertEquals(2L, ra.count)
        assertEquals(1, ra.children.size, "the two threads' paths did not merge: ${ra.children.map { it.name }}")
        assertEquals(2L, ra.children.single().count)
        assertEquals(1, p.coarseTree.count { it.name == "tree-merge-a" }, "two roots for one path")
    }

    /**
     * A real collection inside a real span: the pause is charged to the span open through it, and
     * to no more than the header says there was. Loose on the amount, as `PauseTest` is, because
     * how long a collection takes is the machine's business.
     */
    @Test
    fun `a pause is charged to the span open through it`() {
        val live = Array(4_000_000) { LongArray(4) }
        val req = Profiler.registerCoarse("tree-paused")
        val work = Profiler.registerFine("tree-paused-work")
        val stop = AtomicBoolean(false)
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = onThread("tree-pause-worker") {
            op(req) {
                while (!stop.get()) op(work) { spinFor(100_000) }
            }
            stopped.countDown()
            release.await()
        }
        start()
        worker.start()
        val p = try {
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
        assertTrue(live.isNotEmpty())
        assertTrue(p.pause.confirmedNanos > 0, "no pause was confirmed, so nothing to charge")
        val node = assertNotNull(p.root("tree-paused"))
        assertTrue(node.pausedNanos > 0, "the span open through the collection carries no pause")
        assertTrue(node.pausedNanos <= p.pause.confirmedPausedIn(p.samplingSpanNanos), "a row carries more pause than the header")
        val row = p.render().substringAfter("COARSE OPERATIONS").lines().first { it.startsWith("tree-paused ") }
        val paused = row.split(Regex("[ ]+"))[3]
        assertTrue(paused != "-", "the Paused column is empty on the row: $row")
    }
    /**
     * An operation opened with a key keeps its five slowest executions on the path, longest first,
     * prints them under its row and saves them with the profile. Durations a millisecond apart, in
     * an order that is neither ascending nor descending.
     */
    @Test
    fun `a keyed operation keeps its slowest executions by key`() {
        val outer = Profiler.registerCoarse("tree-keyed-outer")
        val keyed = Profiler.registerCoarse("tree-keyed")
        val order = listOf(7, 19, 2, 13, 0, 16, 9, 18, 4, 11, 15, 1, 17, 6, 14, 3, 12, 8, 10, 5)
        start()
        op(outer) {
            for (i in order) op(keyed, key = { "k$i" }) { spinFor((i + 1) * 1_000_000L) }
        }
        val p = Profiler.stop()

        val node = assertNotNull(assertNotNull(p.root("tree-keyed-outer")).child("tree-keyed"))
        assertEquals(SLOWEST_KEPT, node.slowest.size)
        assertEquals(node.slowest.sortedByDescending { it.nanos }, node.slowest, "not longest first")
        // A preemption can swap two neighbours a millisecond apart; it cannot bring up a short one.
        val top = (13..19).map { "k$it" }.toSet()
        assertTrue(node.slowest.all { it.key in top }, "not the slowest: ${node.slowest}")
        assertTrue(node.slowest.first().nanos >= 20_000_000, "the longest is shorter than it ran: ${node.slowest.first()}")
        assertTrue(assertNotNull(p.root("tree-keyed-outer")).slowest.isEmpty(), "an operation without a key kept some")

        val text = p.render()
        assertTrue(text.lines().any { it.trim().startsWith("slowest:") && node.slowest.first().key in it }, "no slowest line:\n$text")

        val file = Files.createTempFile("tree-keyed", RUN_EXTENSION)
        try {
            RunStore.save(p, file)
            val loaded = RunStore.load(file)
            assertEquals(node.slowest, assertNotNull(assertNotNull(loaded.root("tree-keyed-outer")).child("tree-keyed")).slowest)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** The key is built only for an execution that makes the slowest so far: in descending order, the first five. */
    @Test
    fun `the key is called only for a slow execution`() {
        val keyed = Profiler.registerCoarse("tree-key-calls")
        var calls = 0
        start()
        for (i in 10 downTo 1) op(keyed, key = { calls++; "k$i" }) { spinFor(i * 2_000_000L) }
        Profiler.stop()
        assertEquals(SLOWEST_KEPT, calls)
    }

    /** Each thread keeps its own slowest; the report keeps the slowest of all of them. */
    @Test
    fun `the slowest are merged across threads`() {
        val keyed = Profiler.registerCoarse("tree-key-threads")
        start()
        val fast = onThread("tree-key-fast") { repeat(SLOWEST_KEPT) { op(keyed, key = { "fast$it" }) { spinFor(1_000_000) } } }
        val slow = onThread("tree-key-slow") { repeat(SLOWEST_KEPT) { op(keyed, key = { "slow$it" }) { spinFor(4_000_000) } } }
        fast.start()
        slow.start()
        fast.join()
        slow.join()
        val p = Profiler.stop()
        val node = assertNotNull(p.root("tree-key-threads"))
        assertEquals(2L * SLOWEST_KEPT, node.count)
        assertTrue(node.slowest.all { it.key.startsWith("slow") }, "the slow thread's were not kept: ${node.slowest}")
    }

    /** A key that throws does not replace the body's result or its exception. */
    @Test
    fun `a key that throws is named`() {
        val keyed = Profiler.registerCoarse("tree-key-throws")
        start()
        val result = op(keyed, key = { error("no key") }) { spinFor(1_000_000); 42 }
        val p = Profiler.stop()
        assertEquals(42, result)
        assertEquals("(the key threw IllegalStateException)", assertNotNull(p.root("tree-key-throws")).slowest.single().key)
    }

    /** A label in the middle of a chain: the block's `this` is the receiver, and only the block is labelled. */
    @Test
    fun `the chain form labels the block and keeps the chain`() {
        // Names the keyed test registered already: the tests share one registry of 64 coarse types.
        val outer = Profiler.registerCoarse("tree-keyed-outer")
        val keyed = Profiler.registerCoarse("tree-keyed")
        val filter = Profiler.registerFine("tree-chain-filter")
        val sort = Profiler.registerFine("tree-chain-sort")
        start()
        val out = op(outer) {
            (1..50_000).map { it * 7 % 1000 }
                .op(filter) { filter { it % 2 == 0 } }
                .op(sort) { sorted() }
                .op(keyed, key = { "k$size" }) { distinct() }
        }
        val p = Profiler.stop()
        assertEquals((1..50_000).map { it * 7 % 1000 }.filter { it % 2 == 0 }.sorted().distinct(), out)
        val root = assertNotNull(p.root("tree-keyed-outer"))
        val k = assertNotNull(root.child("tree-keyed"))
        assertEquals(listOf("k25000"), k.slowest.map { it.key }, "the key is not evaluated on the receiver")
        assertEquals(1L, p.operations.first { it.name == "tree-chain-filter" }.calls)
        assertEquals(1L, p.operations.first { it.name == "tree-chain-sort" }.calls)
    }
}
