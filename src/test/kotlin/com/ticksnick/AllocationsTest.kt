package com.ticksnick

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
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

        /** Bytes per fork beyond the array: the thread's context and span, with its nodes kept out. */
        const val ONE_FORK = 1_024.0

        /** What a thread's first execution on a path may add: its nodes, made after the read at entry. */
        const val FIRST_ON_THREAD = 32_768.0
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

    private fun Run.node(name: String): CoarseNode = coarseTree.flatMap { it.flatten() }.first { it.name == name }

    private fun assertBytes(want: Double, got: Double, what: String) {
        assertTrue(got >= want && got <= want + SLACK, "$what: $got bytes, want $want to ${want + SLACK}")
    }

    private fun spin(nanos: Long) {
        val until = System.nanoTime() + nanos
        while (System.nanoTime() < until) Thread.onSpinWait()
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
        Profiler.start(allocations = true)
        nested()
        val p = Profiler.stop()

        assertBytes(2.0 * MB, p.allocatedPerExecution(p.node("alloc.inner")), "inner")
        assertBytes(3.0 * MB, p.allocatedPerExecution(p.node("alloc.outer")), "outer")
    }

    /**
     * An execution that allocates nothing reads no bytes: TickSnick's own context is taken out, and a
     * borrowed context's walk allocates nothing. They read 57 and 217 bytes an execution before.
     */
    @Test
    fun `an execution that allocates nothing reads no bytes, TickSnick's own left out`() {
        Profiler.start(allocations = true)
        op(outer) {
            val ctx = captureCoarse()
            thread { withCoarse(ctx) { repeat(100_000) { op(inner) { } } }; Profiler.release() }.join()
        }
        repeat(100_000) { op(inner) { } }
        val p = Profiler.stop()
        val nodes = p.coarseTree.flatMap { it.flatten() }.filter { it.name == "alloc.inner" }
        assertEquals(2, nodes.size)
        for (n in nodes) assertTrue(p.allocatedPerExecution(n) < 8.0, "${n.count} executions read ${p.allocatedPerExecution(n)} bytes each")
    }

    /**
     * On a virtual thread the JVM reads -1 for the bytes a thread allocated: the path, and every one
     * above it, read as not known, `-`, and a warning says why - never a 0 for work that did allocate.
     */
    @Test
    fun `bytes on a virtual thread are not known, never 0`() {
        Profiler.start(allocations = true)
        repeat(3) {
            op(outer) {
                val ctx = captureCoarse()
                Thread.ofVirtual().start {
                    withCoarse(ctx) { op(inner) { sink = ByteArray(MB) } }
                    Profiler.release()
                }.join()
            }
        }
        val p = Profiler.stop()

        assertTrue(p.allocatedPerExecution(p.node("alloc.inner")).isNaN(), "a virtual thread's bytes read as a figure")
        assertTrue(p.allocatedPerExecution(p.node("alloc.outer")).isNaN(), "the parent of a virtual thread's work read as a figure")
        assertTrue(p.warnings().any { it.kind == Kind.ALLOCATIONS_VIRTUAL_THREADS }, p.render())
    }

    /** The thread that opened the execution does not allocate this; a thread working under it does. */
    @Test
    fun `bytes allocated on a thread that carries the context count under the row`() {
        Profiler.start(allocations = true)
        repeat(5) {
            fork()
        }
        val p = Profiler.stop()

        // Tighter than SLACK, and it has to be: a new thread makes its nodes at the boundary, and the
        // read comes first so that they are not charged. Read after them, this is about twice as much.
        val over = p.allocatedPerExecution(p.node("alloc.outer")) - MB
        assertTrue(over in 0.0..ONE_FORK, "the fork: $over bytes over")
    }

    @Test
    fun `a session that did not ask counts nothing and draws no column`() {
        Profiler.start()
        nested()
        val p = Profiler.stop()

        assertEquals(0L, p.node("alloc.outer").allocated)
        assertFalse("Allocated" in p.render(), "a column nobody asked for")
    }

    /** A thread's total runs from its birth, and what it allocated before the session is not the session's. */
    @Test
    fun `a session starts from what the thread had already allocated`() {
        Profiler.start(allocations = true)
        nested(1)
        Profiler.stop()
        sink = ByteArray(8 * MB)
        Profiler.start(allocations = true)
        nested(1)
        val p = Profiler.stop()

        assertBytes(3.0 * MB, p.allocatedPerExecution(p.node("alloc.outer")), "the second session")
    }

    @Test
    fun `the column is drawn in line, saved, and diffed`() {
        Profiler.start(allocations = true)
        nested()
        val a = Profiler.stop()
        Profiler.start()
        nested()
        val off = Profiler.stop()

        val lines = a.render().lines()
        val head = lines.first { it.startsWith("Operation") }
        val end = head.indexOf("Per execution") + "Per execution".length
        assertEquals(' ', head[head.indexOf("Per execution") - 1])
        assertTrue(lines[lines.indexOf(head) - 1].contains("Allocated"), "the group has no name")
        val row = lines.dropWhile { it != head }.first { it.startsWith("alloc.outer ") }
        assertEquals("3.00 MB", row.substring(end - 7, end), row)

        val folder = Files.createTempDirectory("alloc")
        val loaded = Run.load(a.save(folder))
        assertTrue(loaded.allocations)
        assertEquals(a.node("alloc.outer").allocated, loaded.node("alloc.outer").allocated)

        val both = diffRow(diffOf(a, a), "alloc.outer")
        assertTrue("3.00 MB" in both[0] && "3.00 MB" in both[1], both.toString())
        val oneSide = diffOf(off, a)
        assertTrue("WARNINGS" in oneSide && "  Allocated: counted on one side only - it adds about" in oneSide, oneSide)
        val outer = diffRow(oneSide, "alloc.outer")
        assertTrue("MB" !in outer[0] && "3.00 MB" in outer[1], outer.toString())
    }

    /** With two runs a side a diff goes through a different table, and so does the report over runs. */
    @Test
    fun `the tables over runs show it`() {
        val before = Files.createTempDirectory("alloc-before")
        val after = Files.createTempDirectory("alloc-after")
        repeat(2) {
            Profiler.start(allocations = true)
            nested()
            Profiler.stop().save(before)
            Profiler.start(allocations = true)
            nested()
            Profiler.stop().save(after)
        }
        val runs = TickSnick.load(before)
        val across = runs.render()
        assertTrue(across.lines().any { it.startsWith("alloc.outer") && "3.00 MB" in it && "MB +-" !in it }, across)
        val row = diffRow(TickSnick.diff(runs, TickSnick.load(after)), "alloc.outer")
        assertTrue("3.00 MB" in row[0] && "3.00 MB" in row[1] && "MB +-" !in row[0], row.toString())
    }

    // --- the other ways into a coarse execution ------------------------------------------

    @Test
    fun `the keyed form counts what ran under it`() {
        val keyed = Profiler.registerCoarse("alloc.keyed")
        fun once() = op(keyed, key = { "k" }) { sink = ByteArray(MB) }
        once()
        Profiler.start(allocations = true)
        repeat(5) { once() }
        val p = Profiler.stop()

        assertBytes(MB.toDouble(), p.allocatedPerExecution(p.node("alloc.keyed")), "keyed")
    }

    @Test
    fun `enter and exit count what ran between them, nested`() {
        fun once() {
            Profiler.enter(outer)
            sink = ByteArray(MB)
            Profiler.enter(inner)
            sink = ByteArray(2 * MB)
            Profiler.exit(inner)
            Profiler.exit(outer)
        }
        once()
        Profiler.start(allocations = true)
        repeat(5) { once() }
        val p = Profiler.stop()

        assertBytes(2.0 * MB, p.allocatedPerExecution(p.node("alloc.inner")), "inner")
        assertBytes(3.0 * MB, p.allocatedPerExecution(p.node("alloc.outer")), "outer")
    }

    @Test
    fun `a scope counts what ran inside it`() {
        fun once() = Profiler.coarse(outer).use { sink = ByteArray(MB) }
        once()
        Profiler.start(allocations = true)
        repeat(5) { once() }
        val p = Profiler.stop()

        assertBytes(MB.toDouble(), p.allocatedPerExecution(p.node("alloc.outer")), "scope")
    }

    /** The leak is caught at the check: what ran up to it was the execution's, and what ran after is nobody's. */
    @Test
    fun `a leaked execution keeps what ran under it up to the check`() {
        Profiler.start(allocations = true)
        op(outer) { sink = ByteArray(MB) }
        Profiler.enter(outer)
        sink = ByteArray(MB)
        assertFalse(Profiler.expectBalanced())
        sink = ByteArray(4 * MB)
        val p = Profiler.stop()

        assertBytes(2.0 * MB, p.node("alloc.outer").allocated.toDouble(), "the leak")
    }

    // --- a JVM that cannot say ------------------------------------------------------------

    @Test
    fun `a JVM that cannot report them runs without and says so`() {
        Profiler.allocationsAvailable = false
        val p = try {
            Profiler.start(allocations = true)
            nested()
            Profiler.stop()
        } finally {
            Profiler.allocationsAvailable = AllocationClock.available
        }
        assertFalse(p.allocations)
        assertEquals(0L, p.node("alloc.outer").allocated)
        fun checked(text: String) {
            assertTrue("  Allocated: not shown - this JVM cannot count allocated bytes per thread; HotSpot can" in text, text)
            assertFalse("Per execution" in text.lines().first { it.startsWith("Operation") }, text)
        }
        checked(p.render())
        val loaded = Run.load(p.save(Files.createTempDirectory("alloc-missing")))
        assertTrue(loaded.allocationsMissing, "lost on saving")
        checked(loaded.render())
    }

    /** One line on the normal path, where the warning must not appear. */
    @Test
    fun `a JVM that can report them does not warn`() {
        Profiler.start(allocations = true)
        nested()
        assertFalse("cannot count" in Profiler.stop().render())
    }

    // --- the table ------------------------------------------------------------------------

    @Test
    fun `the block of shared operations shows them, over every caller`() {
        val other = Profiler.registerCoarse("alloc.other")
        fun both() {
            op(outer) { op(inner) { sink = ByteArray(2 * MB) } }
            op(other) { op(inner) { sink = ByteArray(2 * MB) } }
        }
        both()
        Profiler.start(allocations = true)
        repeat(3) { both() }
        val lines = Profiler.stop().render().lines()

        val head = lines.first { it.startsWith("Operation") }
        val end = head.indexOf("Per execution") + "Per execution".length
        val block = lines.indexOf(SHARED_HEADING)
        val row = lines.drop(block).first { it.startsWith("  alloc.inner") }
        assertEquals("2.00 MB", row.substring(end - 7, end), row)
    }

    @Test
    fun `a fine row stays in line and leaves the column empty`() {
        val fine = Profiler.registerFine("alloc.fine")
        Profiler.start(allocations = true)
        repeat(30) { op(outer) { op(fine) { spin(2_000_000) } } }
        val lines = Profiler.stop().render().lines()

        val head = lines.indexOfFirst { it.startsWith("Operation") }
        val row = lines.first { it.trimStart().startsWith("- alloc.fine") }
        assertEquals(lines[head].indexOf("Mean") + "Mean".length, row.trimEnd().length, "Mean out of line, or a cell in the column: $row")
    }

    @Test
    fun `the rule reaches the end of the table`() {
        Profiler.start(allocations = true)
        nested()
        val lines = Profiler.stop().render().lines()

        val head = lines.indexOfFirst { it.startsWith("Operation") }
        val row = lines.drop(head).first { it.startsWith("alloc.outer ") }
        assertEquals(row.length, lines[head + 1].length, "the rule and its table")
    }

    // --- executions open across the ends of a session --------------------------------------

    /** Undercounted, never overcounted: 8 MB from before the session stays out of it. */
    @Test
    fun `an execution open at the start counts from the thread's first boundary`() {
        Profiler.enter(outer)
        sink = ByteArray(8 * MB)
        Profiler.start(allocations = true)
        sink = ByteArray(MB)
        Profiler.exit(outer)
        op(outer) { sink = ByteArray(MB) }
        val p = Profiler.stop()

        assertEquals(2L, p.node("alloc.outer").count)
        assertBytes(MB.toDouble(), p.node("alloc.outer").allocated.toDouble(), "only what came after the boundary")
    }

    @Test
    fun `an execution open at the stop does not disturb the rows or the next session`() {
        Profiler.start(allocations = true)
        op(outer) { sink = ByteArray(MB) }
        Profiler.enter(outer)
        sink = ByteArray(4 * MB)
        val p = Profiler.stop()
        Profiler.exit(outer)

        assertEquals(1, p.openContextsAtEnd)
        assertBytes(MB.toDouble(), p.allocatedPerExecution(p.node("alloc.outer")), "the one that closed")

        Profiler.start(allocations = true)
        nested(1)
        val next = Profiler.stop()
        assertBytes(3.0 * MB, next.allocatedPerExecution(next.node("alloc.outer")), "the next session")
    }

    // --- several threads --------------------------------------------------------------------

    private fun assertTotal(want: Double, threads: Int, got: Long, what: String) {
        val most = want + threads * FIRST_ON_THREAD
        assertTrue(got >= want && got <= most, "$what: $got bytes, want $want to $most")
    }

    @Test
    fun `threads on one path at once add up`() {
        val threads = 4
        val gate = CountDownLatch(threads)
        Profiler.start(allocations = true)
        List(threads) {
            thread {
                gate.countDown()
                gate.await()
                repeat(5) { op(outer) { sink = ByteArray(MB) } }
            }
        }.forEach { it.join() }
        val p = Profiler.stop()

        assertEquals(20L, p.node("alloc.outer").count)
        assertTotal(20.0 * MB, threads, p.node("alloc.outer").allocated, "threads at once")
    }

    @Test
    fun `forks running at once under one execution add up`() {
        val forks = 4
        Profiler.start(allocations = true)
        repeat(3) {
            op(outer) {
                val ctx = captureCoarse()
                val gate = CountDownLatch(forks)
                List(forks) {
                    thread {
                        withCoarse(ctx) {
                            gate.countDown()
                            gate.await()
                            sink = ByteArray(MB)
                        }
                    }
                }.forEach { it.join() }
            }
        }
        val p = Profiler.stop()

        assertTotal(12.0 * MB, 12, p.node("alloc.outer").allocated, "forks at once")
    }

    @Test
    fun `a thread that has gone keeps what it counted`() {
        Profiler.start(allocations = true)
        repeat(3) {
            thread {
                op(outer) { sink = ByteArray(MB) }
                Profiler.release()
            }.join()
        }
        val p = Profiler.stop()

        assertEquals(3L, p.node("alloc.outer").count)
        assertTotal(3.0 * MB, 3, p.node("alloc.outer").allocated, "released threads")
    }

    // --- comparing ----------------------------------------------------------------------------

    private fun session(allocations: Boolean, body: () -> Unit = { nested() }): Run {
        Profiler.start(allocations = allocations)
        body()
        return Profiler.stop()
    }

    @Test
    fun `a diff over runs says when only one side counted`() {
        val before = Files.createTempDirectory("alloc-on")
        val after = Files.createTempDirectory("alloc-off")
        repeat(2) {
            session(true).save(before)
            session(false).save(after)
        }
        val diff = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertTrue("Allocated: counted on one side only" in diff, diff)
        val row = diffRow(diff, "alloc.outer")
        assertTrue("3.00 MB" in row[0] && "MB" !in row[1], row.toString())
        // Not asked for is not unknown: the side that did not count is blank there, not `?`.
        val head = diff.lines().first { it.startsWith("Operation") }
        val cells = row[1].padEnd(head.length).substring(head.indexOf("Total") - 5, head.indexOf("Per execution") + "Per execution".length)
        assertTrue(cells.isBlank(), "the side that did not count: ${row[1]}")
    }

    @Test
    fun `a diff over runs where neither side counted has no column and no warning`() {
        val before = Files.createTempDirectory("alloc-none-a")
        val after = Files.createTempDirectory("alloc-none-b")
        repeat(2) {
            session(false).save(before)
            session(false).save(after)
        }
        val diff = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertFalse("Allocated" in diff, diff)
        assertFalse("allocations" in diff, diff)
    }

    @Test
    fun `a folder where only some runs counted gives the mean of those`() {
        val folder = Files.createTempDirectory("alloc-some")
        session(true).save(folder)
        session(false).save(folder)
        val text = TickSnick.load(folder).render()
        assertTrue(text.lines().any { it.startsWith("alloc.outer") && "3.00 MB" in it }, text)
    }

    @Test
    fun `a row on one side only shows its bytes on that side`() {
        val gone = Profiler.registerCoarse("alloc.gone")
        val with = { session(true) { nested(); repeat(2) { op(gone) { sink = ByteArray(MB) } } } }
        with()
        val without = { session(true) }
        val one = diffRow(diffOf(with(), without()), "alloc.gone")
        assertTrue("1.00 MB" in one[0] && "MB" !in one[1], one.toString())

        val before = Files.createTempDirectory("alloc-gone-a")
        val after = Files.createTempDirectory("alloc-gone-b")
        repeat(2) {
            with().save(before)
            without().save(after)
        }
        val two = diffRow(TickSnick.diff(TickSnick.load(before), TickSnick.load(after)), "alloc.gone")
        assertTrue("1.00 MB" in two[0] && "MB" !in two[1], two.toString())
    }

    /** A file from before this, or a run that did not ask: the keys are written only when there is something to say. */
    @Test
    fun `a file without the keys loads without the column`() {
        val path = session(true).save(Files.createTempDirectory("alloc-old"))
        val stale = Regex("^(allocations|node\\.\\d+\\.allocated)\t")
        Files.write(path, Files.readAllLines(path).filterNot { stale.containsMatchIn(it) })

        val loaded = Run.load(path)
        assertFalse(loaded.allocations)
        assertFalse("Allocated" in loaded.render())
    }

    // --- the unit -----------------------------------------------------------------------------

    @Test
    fun `bytes print to three figures, in the unit that holds them`() {
        val k = 1024.0
        val cases = listOf(
            0.0 to "0 B", 97.6 to "98 B", 1023.0 to "1023 B", 1023.6 to "1.00 KB", k to "1.00 KB", 1.5 * k to "1.50 KB",
            9.999 * k to "10.0 KB", 10 * k to "10.0 KB", 99.99 * k to "100 KB", 100 * k to "100 KB", 1023.9 * k to "1.00 MB",
            3 * k * k to "3.00 MB", 1.5 * k * k * k to "1.50 GB", k * k * k * k to "1.00 TB", 2048 * k * k * k * k to "2048 TB",
            Double.NaN to "-",
        )
        for ((v, want) in cases) assertEquals(want, bytes(v), "$v")
    }

    @Test
    fun `counting them raises the coarse floor`() {
        assertEquals(COARSE_ALLOCATIONS_BODIES, coarseContextBodies(0, true) - coarseContextBodies(0, false), 1e-12)
        assertTrue(Run.load(emptySave(true)).allocations)
        assertFalse(Run.load(emptySave(false)).allocations)
    }

    private fun emptySave(allocations: Boolean): java.nio.file.Path {
        Profiler.start(allocations = allocations)
        return Profiler.stop().save(Files.createTempDirectory("alloc-floor"))
    }
}
