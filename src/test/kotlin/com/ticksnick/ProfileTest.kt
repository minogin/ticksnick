package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Several runs of one version read together: the interval arithmetic, and the report over a folder. */
class ProfileTest {

    /** Three runs, each run's mean batch: 6.58 +- 1.35 s, worked by hand. */
    @Test
    fun `the interval is over the runs, with Student's t`() {
        val e = Estimate(listOf(6.08, 6.50, 7.16))
        assertEquals(6.58, e.mean, 1e-9)
        assertTrue(abs(e.half - 1.35) < 0.01, "half-width ${e.half}")
        assertTrue(Estimate(listOf(6.08)).half.isNaN(), "one run has no interval")
        assertEquals(2.0395, t95(31), 1e-4)
        assertEquals(1.9840, t95(100), 1e-4)
        assertTrue(t95(30) > t95(31) && t95(31) > t95(100) && t95(100) > 1.96)
    }

    /**
     * The one rule: `±` up to 20% of the figure, the interval's ends past it, `?` from 100%, the figure alone with no
     * interval; a change by the same lines. Three runs each, worked by hand: 422, 486, 490ms is 466 ±94.8ms.
     */
    @Test
    fun `a figure is written by how sure it is`() {
        assertEquals(Sure.CERTAIN, sureness(6.34, 0.32))
        assertEquals(Sure.LOOSE, sureness(466.0, 94.8))
        assertEquals(Sure.UNKNOWN, sureness(173.0, 617.0))
        assertEquals(Sure.PLAIN, sureness(6.08, Double.NaN))
        assertEquals(Sure.CERTAIN, sureness(-24.0, 3.0), "a change by the same lines, sign aside")
        assertEquals(Sure.LOOSE, sureness(-35.0, 27.0))
        assertEquals(Sure.UNKNOWN, sureness(2.4, 15.0), "a change crossing zero is unknown")
        assertEquals(Sure.UNKNOWN, sureness(-0.35, 0.35), "and so is one reaching it")
        assertEquals(Sure.LOOSE, sureness(-0.36, 0.34), "one side of zero, however near: its direction is known")

        assertEquals("6.34s +-5.0%", durationWithInterval(Estimate(listOf(6.20e9, 6.37e9, 6.45e9))))
        assertEquals("370..560ms", durationWithInterval(Estimate(listOf(422e6, 486e6, 490e6))))
        assertEquals("?", durationWithInterval(Estimate(listOf(56.1e6, 4.39e6, 458e6))), "the interval reaches 0")
        assertEquals("6.08s", durationWithInterval(Estimate(listOf(6.08e9))), "one run: the figure alone")
    }

    /** A change's direction once, the end nearer zero first; a range reaching or crossing zero is `?` and never written. */
    @Test
    fun `a change range is written by its direction`() {
        assertEquals("-(8.2..62)%", percentChangeRange(-62.0, -8.2, "%"), "a decrease: the least it moved first")
        assertEquals("+(180..300)%", percentChangeRange(175.0, 300.0, "%"))
        assertEquals("+(4.7..5.9)pp", percentChangeRange(4.7, 5.9, "pp"))
        assertEquals("-(0.0..0.7)pp", percentChangeRange(-0.7, -0.02, "pp"), "near zero, one side: the arrow keeps the sign")
        assertFailsWith<IllegalArgumentException>("across zero is unknown") { percentChangeRange(-13.0, 18.0, "%") }
        assertFailsWith<IllegalArgumentException>("and so is reaching it") { percentChangeRange(-0.7, 0.0, "pp") }
        assertEquals("-(3.7..5.7)s", durationChangeRange(-5.65e9, -3.65e9), "time: both ends in the larger one's unit")
        assertEquals("-(0.95..1.2)ms", durationChangeRange(-1.20e6, -0.95e6))
        assertEquals("-(0.15..2.4)s", durationChangeRange(-2.36e9, -0.152e9), "two significant digits each end")
        assertEquals("0.15..2.4s", durationRange(0.152e9, 2.36e9))
        assertEquals("0.95..1.0s", durationRange(0.95e9, 0.996e9), "the unit taken after rounding: 996ms is 1.0s")
        assertEquals("10", intervalEnd(9.96), "the decade taken after rounding")
        assertEquals("0.7", intervalEnd(0.7, 1), "never more places than the column writes")
        withSymbols(unicode = true) {
            assertEquals("↓8.2..62%", percentChangeRange(-62.0, -8.2, "%"))
            assertEquals("↑180..300%", percentChangeRange(175.0, 300.0, "%"))
            assertEquals("↓0.0..0.7pp", percentChangeRange(-0.7, -0.02, "pp"))
            assertEquals("↓3.7..5.7s", durationChangeRange(-5.65e9, -3.65e9))
        }
    }

    @Test
    fun `a folder of runs renders with intervals, its batches listed, and a row some runs lack`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val batches = listOf(
                listOf(6_222_817_000L, 5_959_911_000L, 5_904_665_100L),
                listOf(5_760_000_000L, 5_920_000_000L, 7_820_000_000L),
                listOf(7_060_000_000L, 7_510_000_000L, 6_910_000_000L),
            )
            batches.forEachIndexed { i, b ->
                val children = if (i == 0) listOf(node("rare", listOf(1_000_000L))) else emptyList()
                RunStore.save(profile(listOf(node("runBatch", b, children))), dir.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))
            }
            val text = Profile.load(dir).render()
            assertTrue("3 runs of '${dir.fileName}'" in text, text)
            assertTrue("runBatch, each execution" in text, text)
            assertTrue("6.22s  5.96s  5.90s" in text, "the first run's batches, in order: $text")
            val row = text.lines().first { it.startsWith("runBatch") }
            // The runs' mean batches are 6.03, 6.50 and 7.16 s: 6.56 s, ±1.41 s with three runs, 21%: a range.
            assertTrue("5.2..8.0s" in row, row)
            assertTrue(text.lines().any { it.trim().startsWith("rare (1 of 3 runs)") }, text)
            // Over 20% of the figure with three runs: the interval's own ends.
            assertTrue(Regex("""runBatch +[0-9.]+\.\.[0-9.]+s per execution""").containsMatchIn(text), "the batch, quotable: $text")
            assertTrue("WARNINGS" !in text, "nothing to warn about: $text")
            assertTrue("no interval, so any" !in text, "three runs have their intervals: $text")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runs that did not run the root row the same number of times are called out`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            RunStore.save(profile(listOf(node("runBatch", listOf(6L, 6L, 6L)))), dir.resolve("a$RUN_EXTENSION"))
            RunStore.save(profile(listOf(node("runBatch", listOf(7L)))), dir.resolve("b$RUN_EXTENSION"))
            val text = Profile.load(dir).render()
            // The same workload ran the root the same number of times, so a count that differs is a mixed folder.
            assertTrue(text.lines()[3] == "WARNINGS", text)
            assertTrue(text.lines().any { it.startsWith("  Runs: they differ in `runBatch` executions, so each mean mixes them") }, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one run renders as a profile, warned that its figures have no interval`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            profile(listOf(node("runBatch", listOf(1_000L, 2_000L)))).save(dir.resolve("naive"))
            val lines = TickSnick.load(dir.resolve("naive")).render().lines()
            val heading = lines.indexOf("WARNINGS")
            assertTrue(heading in 1..lines.indexOfFirst { it.startsWith("Wall time") }, "the warnings are not above the numbers:\n" + lines.joinToString("\n"))
            assertEquals("  Runs: 1 in 'naive', no interval - run at least 3 times", lines[heading + 1])
            assertTrue(lines.any { "NESTED WALL TIME - each row" in it }, "not a profile report")
            // A figure of one run prints plain, as a certain one does: right over each table, a line says it is not.
            for (table in listOf("NESTED WALL TIME", "OWN THREAD-TIME")) assertEquals(
                "1 run: no interval, so any figure below may be loose or unknown - run at least 3 times to tell",
                lines[lines.indexOfFirst { it.startsWith(table) } + 1], "right under $table:\n" + lines.joinToString("\n"),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file is not a folder of runs`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val file = profile(listOf(node("runBatch", listOf(1_000L)))).save(dir)
            assertFailsWith<IllegalArgumentException> { TickSnick.load(file) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun node(name: String, durations: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, durations.size.toLong(), durations.sum(), durations.min(), durations.max(), Spans.of(durations),
        10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    private fun profile(tree: List<CoarseNode>) = Run(
        operations = listOf(OperationStat(0, "work", 10, 10, 0, 0, 0, 0, 10)), idleHits = 0, ticks = 1_001,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
        duty = Duty(
            labelledDuty = 0.999, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 999_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.999, maxWindowDuty = 0.999,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = listOf(
            CoarseStat(0, "runBatch", 3, 3_000_000_000, 1_000_000_000, 1_000_000_000, Spans(), 10, 10, 10, 10, 10, 10, LongArray(MAX_OPERATIONS + 1))
        ),
        coarseTree = tree,
    )
}
