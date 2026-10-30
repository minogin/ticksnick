package com.ticksnick

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile as a file, the diff between two, the directory of them, the command on them - and
 * the `Long` group, since the stall it needs is also what gives the file something to carry.
 */
class StoreDiffTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
        }
    }

    @Volatile
    private var sink: Long = 0

    private fun spinFor(nanos: Long) {
        val until = System.nanoTime() + nanos
        var s = sink or 1L
        while (System.nanoTime() < until) {
            var i = 0
            while (i < 256) {
                s = s * 31 + i
                i++
            }
        }
        sink = s
    }

    /**
     * A real session with a tree, a fine operation that stalled once, and one that never did:
     * 300 ms of short calls, then one call of 60 ms, then a few short ones so the run of stuck
     * ticks is closed and lands in the histogram.
     */
    private fun session(): Run {
        val req = Profiler.registerCoarse("store-req")
        val step = Profiler.registerCoarse("store-step")
        val stalls = Profiler.registerFine("store-stalls")
        val smooth = Profiler.registerFine("store-smooth")
        Profiler.start(tickMillis = 1.0, strict = false)
        op(req) {
            op(step) {
                val until = System.nanoTime() + 300_000_000
                while (System.nanoTime() < until) {
                    op(stalls) { spinFor(2_000) }
                    op(smooth) { spinFor(2_000) }
                }
                op(stalls) { spinFor(60_000_000) }
                repeat(1000) { op(stalls) { spinFor(2_000) } }
            }
        }
        return Profiler.stop()
    }

    @Test
    fun `a stall shows in the Long group and a smooth operation shows dashes`() {
        val p = session()
        val stalls = p.operations.first { it.name == "store-stalls" }
        assertTrue(stalls.stuckInstances >= 1, "the stall was not seen as a long execution")
        assertTrue(stalls.longestStuckTicks >= 30, "longest run of stuck ticks: ${stalls.longestStuckTicks}")
        assertTrue(stalls.stuckRuns.sum() >= 1, "the run was not closed into the histogram")
        val lines = p.render().lines()
        val stallRow = lines.first { it.startsWith("store-stalls ") }
        val smoothRow = lines.first { it.startsWith("store-smooth ") }
        val longOf = { row: String -> row.split("|")[2].trim() }
        assertTrue(longOf(stallRow).first().isDigit(), "the Long group is empty on the stalled row: $stallRow")
        assertEquals("- - -", longOf(smoothRow).split(Regex("\\s+")).joinToString(" "), "the smooth row has figures in Long: $smoothRow")
    }

    @Test
    fun `a real profile with a tree and long runs survives the file`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        val path = dir.resolve("one$RUN_EXTENSION")
        try {
            RunStore.save(p, path)
            val back = Run.load(path)
            assertEquals(p.render(legend = true), back.render(legend = true), "the file lost something")
            assertEquals(p.coarseTree.single().children.single().name, back.coarseTree.single().children.single().name)
            assertEquals(
                p.operations.first { it.name == "store-stalls" }.stuckRuns.toList(),
                back.operations.first { it.name == "store-stalls" }.stuckRuns.toList(),
            )
            assertEquals(p.coarseTree.single().pausedNanos, back.coarseTree.single().pausedNanos)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a name with a tab and a newline survives the file`() {
        val odd = Profiler.registerFine("store\todd\nname")
        Profiler.start(tickMillis = 1.0, strict = false)
        op(odd) { spinFor(20_000_000) }
        val p = Profiler.stop()
        val path = Files.createTempFile("ticksnick", RUN_EXTENSION)
        try {
            RunStore.save(p, path)
            assertTrue(Run.load(path).operations.any { it.name == "store\todd\nname" }, "the name was mangled")
        } finally {
            Files.delete(path)
        }
    }

    @Test
    fun `a folder's runs are its profiles, oldest first`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            for (n in listOf("x/2026-01-01T00-00-03", "x/2026-01-01T00-00-01", "x/2026-01-01T00-00-01-2", "y/2026-01-01T00-00-02")) {
                Files.createDirectories(dir.resolve(n).parent)
                Files.writeString(dir.resolve(n + RUN_EXTENSION), "")
            }
            Files.writeString(dir.resolve("x/notes.txt"), "")
            assertEquals(
                listOf("2026-01-01T00-00-01", "2026-01-01T00-00-01-2", "2026-01-01T00-00-03").map { it + RUN_EXTENSION },
                RunStore.runs(dir.resolve("x")).map { it.fileName.toString() },
            )
            assertEquals(0, RunStore.runs(dir.resolve("y/2026-01-01T00-00-02$RUN_EXTENSION")).size, "a file is not a folder")
            assertEquals(0, RunStore.runs(dir.resolve("missing")).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ten runs in one second keep their order`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val now = java.time.LocalDateTime.of(2026, 10, 27, 12, 0, 0)
            repeat(10) { RunStore.saveNew(profile(emptyList()), dir, now) }
            assertEquals(
                listOf("2026-10-27T12-00-00") + (2..10).map { "2026-10-27T12-00-00-$it" },
                RunStore.runs(dir).map { it.fileName.toString().removeSuffix(RUN_EXTENSION) },
            )
            // Saved at once from several threads, in the same second: every run gets its own file.
            val other = dir.resolve("other")
            val threads = (1..8).map { Thread { RunStore.saveNew(profile(emptyList()), other, now) } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertEquals(8, RunStore.runs(other).size)
            RunStore.runs(other).forEach { RunStore.load(it) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun profile(ops: List<OperationStat>, tree: List<CoarseNode> = emptyList(), coarse: List<CoarseStat> = emptyList()) = Run(
        operations = ops, idleHits = 0, ticks = 1_001, samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000,
        threads = 1,
        duty = Duty(
            labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 990_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.99, maxWindowDuty = 0.99,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = coarse, coarseTree = tree,
    )

    @Test
    fun `durations survive the file, exact ones and a histogram`() {
        val few = listOf(30_467_200_900L, 30_445_132_200L, 30_616_088_500L)
        val many = (1..EXACT_SPANS + 1).map { it * 977L }
        fun nodeOf(name: String, v: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
            0, name, v.size.toLong(), v.sum(), v.min(), v.max(), Spans.of(v), 10, 10,
            LongArray(MAX_OPERATIONS + 1), 0, children,
        )
        val tree = listOf(nodeOf("batch", few, listOf(nodeOf("root", many))))
        val path = Files.createTempFile("ticksnick", RUN_EXTENSION)
        try {
            RunStore.save(profile(listOf(stat("a", 10, 10)), tree), path)
            val back = Run.load(path).coarseTree.single()
            assertTrue(back.spans.isExact)
            assertEquals(few, back.spans.exactDurations()!!.toList(), "the exact durations, in their order")
            assertEquals(few.size.toLong(), back.spans.count)
            assertTrue(!back.children.single().spans.isExact)
            assertEquals(many.size.toLong(), back.children.single().spans.count)
            for (p in listOf(0.5, 0.9, 0.99)) {
                assertEquals(tree.single().percentileNanos(p), back.percentileNanos(p), "batch p$p")
                assertEquals(
                    tree.single().children.single().percentileNanos(p), back.children.single().percentileNanos(p), "root p$p"
                )
            }

            // Exactly as many as are kept one by one: still exact after the file.
            val full = (1..EXACT_SPANS).map { it * 977L }
            RunStore.save(profile(listOf(stat("a", 10, 10)), listOf(nodeOf("full", full))), path)
            val fullBack = Run.load(path).coarseTree.single()
            assertTrue(fullBack.spans.isExact)
            assertEquals(full.size.toLong(), fullBack.spans.count)
            assertEquals(nodeOf("full", full).percentileNanos(0.99), fullBack.percentileNanos(0.99))
        } finally {
            Files.delete(path)
        }
    }

    /** A file of format 1, or not a profile at all, is refused with a message saying which, not read wrong. */
    @Test
    fun `a file of another format is refused, and the command line says why`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            fun folderWith(name: String, text: String): Path = dir.resolve(name).also {
                Files.createDirectories(it)
                Files.writeString(it.resolve("2026-01-01T00-00-00$RUN_EXTENSION"), text)
            }
            val old = folderWith("old", "ticksnick-profile 1\nidleHits\t0\n")
            val e = assertFailsWith<IllegalArgumentException> { TickSnick.load(old) }
            assertTrue("ticksnick-profile 1" in e.message!! && RUN_FORMAT in e.message!!, e.message)
            val junk = folderWith("junk", "garbage\n")
            assertTrue("is not a TickSnick run" in assertFailsWith<IllegalArgumentException> { TickSnick.load(junk) }.message!!)
            val empty = dir.resolve("empty").also { Files.createDirectories(it) }
            assertTrue("no run in" in assertFailsWith<IllegalArgumentException> { TickSnick.load(empty) }.message!!)

            // The command line: the message and the usage on stderr, code 2, no stack trace.
            val err = ByteArrayOutputStream()
            val was = System.err
            System.setErr(PrintStream(err, true))
            try {
                assertEquals(2, Cli.run(arrayOf("render", old.toString())))
                assertEquals(2, Cli.run(arrayOf("render", empty.toString())))
                assertEquals(2, Cli.run(arrayOf("draw")))
            } finally {
                System.setErr(was)
            }
            val text = err.toString()
            assertTrue("ticksnick-profile 1" in text && "no run in" in text && USAGE in text, text)
            assertTrue("\tat " !in text, "a stack trace: $text")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun stat(name: String, hits: Long, calls: Long) = OperationStat(0, name, hits, calls, 0, 0, 0, 0, hits)

    private fun node(name: String, nanos: Long, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, 1, nanos, nanos, nanos, Spans(), 10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    @Test
    fun `a diff shows a side that is missing, calls that differ, and a node only the earlier run had`() {
        val before = profile(listOf(stat("only-before", 100, 1_000), stat("both", 200, 1_000)), tree = listOf(node("root", 1_000_000_000, listOf(node("gone", 500_000_000)))))
        val after = profile(listOf(stat("only-after", 100, 1_000), stat("both", 300, 2_000)), tree = listOf(node("root", 1_000_000_000)))
        val text = OneRunDiff(before, after, "x", "y").render()
        val row = { name: String -> text.lines().first { it.trim().startsWith("$name ") } }
        assertTrue("-> -" in row("only-before"), "a row only the earlier run had: ${row("only-before")}")
        assertTrue("- ->" in row("only-after"), "a row only the later run had: ${row("only-after")}")
        assertTrue("1,000 -> 2,000" in row("both"), "calls that differ should both print: ${row("both")}")
        assertTrue("+50.0%" in row("both"), "the change is missing: ${row("both")}")
        assertTrue("-> -" in row("gone"), "a coarse node only the earlier run had is gone: $text")
        assertTrue("Diff          x -> y" in text, text)
    }

    @Test
    fun `the command renders a folder and diffs two`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        val a = dir.resolve("a")
        val b = dir.resolve("b")
        val fileA = p.save(a)
        val fileB = p.save(b)
        try {
            assertTrue("Wall time" in capture { Cli.main(arrayOf("render", a.toString())) }, "render printed no report")
            val diff = capture { Cli.main(arrayOf("diff", a.toString(), b.toString())) }
            assertTrue("Diff          a/${fileA.fileName} -> b/${fileB.fileName}" in diff, diff)
            assertTrue(ONE_RUN_DIFF_CAREFUL in diff, diff)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun capture(body: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true))
        try {
            body()
        } finally {
            System.setOut(old)
        }
        return out.toString()
    }

    /** The optional keys: a file written without any of them must still load. */
    private val newKeys = Regex(
        "^(duty\\.(runnableDuty|processCpuNanos|samplerCpuNanos|jitNanos|pausedProcessCpuNanos|profiledCpuNanos|" +
                "hiddenWhenFull|hiddenWhenSpare|spanNanos|processors)|node\\.\\d+\\.(instanceTicks|coveredTicks|ownFine|ownFineCovered|calls|allocated)|allocations|clock\\.)"
    )

    /** [p] saved, then loaded again with every optional key taken out. */
    private fun asOld(p: Run, dir: Path): Run {
        val path = dir.resolve("old$RUN_EXTENSION")
        RunStore.save(p, path)
        val lines = Files.readAllLines(path).filterNot { newKeys.containsMatchIn(it) }
        Files.write(path, lines)
        return Run.load(path)
    }

    /**
     * A profile without the optional keys loads, renders without them, and claims nothing it was
     * not given: no Cores line, the Careful line on the share duty, rows as their summed durations,
     * a leaf split by its samples.
     */
    @Test
    fun `a profile from before the new keys loads and renders the old way`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val old = asOld(p, dir)
            assertTrue(old.duty.runnableDuty.isNaN(), "runnableDuty invented for an old file")
            assertNull(old.duty.coresLine(), "a Cores line for a file that has no figures")
            assertTrue(old.coarseTree.flatMap { it.flatten() }.all { it.parallel.isNaN() && old.ownRowsOf(it) == null })
            val text = old.render()
            assertTrue(text.lines().none { it.startsWith("Cores") }, "the old file's report has a Cores line")
            assertTrue("store-step" in text, "the old file's tree is missing")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A run whose coarse rows no tick caught open - a short one - diffs on wall time like any other,
     * with no warning about the file's format: format 1 files are refused, so a file that loads is
     * not one.
     */
    @Test
    fun `a diff against a run no tick caught open compares wall time and says nothing`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val short = asOld(p, dir)
            val diff = OneRunDiff(short, p, "short", "new").render()
            assertTrue("saved before" !in diff && "summed durations" !in diff, diff)
            assertTrue(diff.lines().any { it.startsWith("store-req") }, diff)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
