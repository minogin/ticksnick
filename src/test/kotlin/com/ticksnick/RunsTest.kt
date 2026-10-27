package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Several runs of one version read together: the interval arithmetic, and the report over a folder. */
class RunsTest {

    /** Three runs, each run's mean batch: 6.58 +- 1.35 s, worked by hand. */
    @Test
    fun `the interval is over the runs, with Student's t`() {
        val e = Estimate(listOf(6.08, 6.50, 7.16))
        assertEquals(6.58, e.mean, 1e-9)
        assertTrue(abs(e.half - 1.35) < 0.01, "half-width ${e.half}")
        assertEquals("+-21%", plusMinus(e))
        assertTrue(Estimate(listOf(6.08)).half.isNaN(), "one run has no interval")
        assertEquals("-", plusMinus(Estimate(listOf(6.08))))
        assertEquals(2.0395, t95(31), 1e-4)
        assertEquals(1.9840, t95(100), 1e-4)
        assertTrue(t95(30) > t95(31) && t95(31) > t95(100) && t95(100) > 1.96)
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
                ProfileStore.save(profile(listOf(node("runBatch", b, children))), dir.resolve("2026-10-25T16-1$i-00$PROFILE_EXTENSION"))
            }
            val text = ProfileRuns.load(dir).render()
            assertTrue("3 runs of '${dir.fileName}'" in text, text)
            assertTrue("runBatch, each execution" in text, text)
            assertTrue("6.22 s  5.96 s  5.90 s" in text, "the first run's batches, in order: $text")
            val row = text.lines().first { it.startsWith("runBatch") }
            // The runs' mean batches are 6.03, 6.50 and 7.16 s: 6.56 s.
            assertTrue("6.56 s" in row && "+-" in row, row)
            assertTrue(text.lines().any { it.trim().startsWith("rare (1 of 3 runs)") }, text)
            assertTrue(Regex("""runBatch +6.56 s \+- [0-9.]+ s \([0-9.]+%\) per execution""").containsMatchIn(text), "the batch, quotable: $text")
            assertTrue("CAREFUL" !in text, "nothing to be careful about: $text")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runs that did not run the root row the same number of times are called out`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            ProfileStore.save(profile(listOf(node("runBatch", listOf(6L, 6L, 6L)))), dir.resolve("a$PROFILE_EXTENSION"))
            ProfileStore.save(profile(listOf(node("runBatch", listOf(7L)))), dir.resolve("b$PROFILE_EXTENSION"))
            val text = ProfileRuns.load(dir).render()
            assertTrue(text.lines()[3].startsWith("CAREFUL") && "runBatch: the runs ran it 3, 1 times" in text.lines()[3], text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one run renders as a profile, CAREFUL that its times have no interval`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            profile(listOf(node("runBatch", listOf(1_000L, 2_000L)))).save(dir.resolve("naive"))
            val lines = TickSnick.load(dir.resolve("naive")).render().lines()
            val careful = lines.indexOfFirst { it.startsWith("CAREFUL") }
            assertTrue(careful in 1..lines.indexOfFirst { it.startsWith("Wall time") }, "the CAREFUL line is not above the numbers:\n" + lines.joinToString("\n"))
            assertTrue("times: 1 run of 'naive', no interval" in lines[careful], lines[careful])
            assertTrue(lines.any { "COARSE OPERATIONS - wall time" in it }, "not a profile report")
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

    private fun profile(tree: List<CoarseNode>) = Profile(
        operations = listOf(OperationStat(0, "work", 10, 10, 0, 0, 0, 0, 10)), idleHits = 0, ticks = 1_001,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
        duty = DutyReport(
            labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 990_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.99, maxWindowDuty = 0.99,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = listOf(
            CoarseStat(0, "runBatch", 3, 1, 1, 1, Spans(), 10, 10, 10, 10, 10, 10, LongArray(MAX_OPERATIONS + 1))
        ),
        coarseTree = tree,
    )
}
