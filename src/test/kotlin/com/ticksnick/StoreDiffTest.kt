package com.ticksnick

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private fun session(): Profile {
        val req = Profiler.registerCoarse("store-req")
        val step = Profiler.registerCoarse("store-step")
        val stalls = Profiler.registerFine("store-stalls")
        val smooth = Profiler.registerFine("store-smooth")
        Profiler.start(stepMillis = 1.0, strict = false)
        Thread.sleep(30)
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
        val path = dir.resolve("one$PROFILE_EXTENSION")
        try {
            p.save(path)
            val back = Profile.load(path)
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
        Profiler.start(stepMillis = 1.0, strict = false)
        op(odd) { spinFor(20_000_000) }
        val p = Profiler.stop()
        val path = Files.createTempFile("ticksnick", PROFILE_EXTENSION)
        try {
            p.save(path)
            assertTrue(Profile.load(path).operations.any { it.name == "store\todd\nname" }, "the name was mangled")
        } finally {
            Files.delete(path)
        }
    }

    @Test
    fun `latest is by time, and by label when asked`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            for (n in listOf("2026-01-01T00-00-01 x", "2026-01-01T00-00-02 y", "2026-01-01T00-00-03 x", "notes.txt")) {
                Files.writeString(dir.resolve(if (n.endsWith(".txt")) n else n + PROFILE_EXTENSION), "")
            }
            assertEquals("2026-01-01T00-00-03 x$PROFILE_EXTENSION", ProfileStore.latest(dir)?.fileName.toString())
            assertEquals("2026-01-01T00-00-02 y$PROFILE_EXTENSION", ProfileStore.latest(dir, "y")?.fileName.toString())
            assertNull(ProfileStore.latest(dir, "z"))
            assertNull(ProfileStore.latest(dir.resolve("missing")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diffAgainstLatest saves every run and compares with the newest, or the newest of a label`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val first = p.diffAgainstLatest(dir, "base")
            assertTrue("first profile" in first, first)
            val second = p.diffAgainstLatest(dir, "next")
            assertTrue(" base$PROFILE_EXTENSION -> " in second, "not compared with the base: $second")
            val third = p.diffAgainstLatest(dir, "next", against = "base")
            assertTrue(" base$PROFILE_EXTENSION -> " in third, "against=base was ignored: $third")
            // The third run was saved inside the same second as the second, so it is `next-2`.
            val fourth = p.diffAgainstLatest(dir, "next")
            assertTrue(Regex(" next(-[0-9]+)?$PROFILE_EXTENSION -> ").containsMatchIn(fourth), "the newest was not the previous run: $fourth")
            val none = p.diffAgainstLatest(dir, "next", against = "nothing")
            assertTrue("no profile labelled 'nothing'" in none, none)
            assertEquals(5, Files.list(dir).use { it.count() }, "a run overwrote another")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun profile(ops: List<OperationStat>, tree: List<CoarseNode> = emptyList(), coarse: List<CoarseStat> = emptyList()) = Profile(
        operations = ops, idleHits = 0, ticks = 1_001, samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000,
        threads = 1,
        duty = DutyReport(
            labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 990_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.99, maxWindowDuty = 0.99,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = coarse, coarseTree = tree,
    )

    private fun stat(name: String, hits: Long, calls: Long) = OperationStat(0, name, hits, calls, 0, 0, 0, 0, hits)

    private fun node(name: String, nanos: Long, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, 1, nanos, nanos, nanos, LongArray(SpanHistogram.BUCKETS), 10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    @Test
    fun `a diff shows a side that is missing, calls that differ, and a node only the earlier run had`() {
        val before = profile(listOf(stat("only-before", 100, 1_000), stat("both", 200, 1_000)), tree = listOf(node("root", 1_000_000_000, listOf(node("gone", 500_000_000)))))
        val after = profile(listOf(stat("only-after", 100, 1_000), stat("both", 300, 2_000)), tree = listOf(node("root", 1_000_000_000)))
        val text = after.diffFrom(before, "x", "y").render()
        val row = { name: String -> text.lines().first { it.trim().startsWith("$name ") } }
        assertTrue("-> -" in row("only-before"), "a row only the earlier run had: ${row("only-before")}")
        assertTrue("- ->" in row("only-after"), "a row only the later run had: ${row("only-after")}")
        assertTrue("1,000 -> 2,000" in row("both"), "calls that differ should both print: ${row("both")}")
        assertTrue("+50.0%" in row("both"), "the change is missing: ${row("both")}")
        assertTrue("-> -" in row("gone"), "a coarse node only the earlier run had is gone: $text")
        assertTrue("Diff          x -> y" in text, text)
    }

    @Test
    fun `the command renders a file and diffs two`() {
        val p = session()
        val dir = Files.createTempDirectory("ticksnick")
        val a = dir.resolve("a$PROFILE_EXTENSION")
        val b = dir.resolve("b$PROFILE_EXTENSION")
        p.save(a)
        p.save(b)
        try {
            assertTrue("Wall time" in capture { Cli.main(arrayOf("render", a.toString())) }, "render printed no report")
            val diff = capture { Cli.main(arrayOf("diff", a.toString(), b.toString())) }
            assertTrue("Diff          a$PROFILE_EXTENSION -> b$PROFILE_EXTENSION" in diff, diff)
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
}
