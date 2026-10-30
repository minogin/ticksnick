package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two versions over their runs: the interval on the change, its verdict, and the report over two folders. */
class ProfileDiffTest {

    /**
     * 10, 11, 12 against 8, 9, 10: a variance of 1 on each side, Welch's 4 degrees of freedom, t 2.776,
     * so -2 +- 2.27 - an interval that includes 0 by a hair. Worked by hand.
     */
    @Test
    fun `the change has Welch's interval, and a verdict only clear of 0`() {
        val before = Estimate(listOf(10.0, 11.0, 12.0))
        val c = Change(before, Estimate(listOf(8.0, 9.0, 10.0)))
        assertEquals(-2.0, c.delta, 1e-9)
        assertTrue(abs(c.half - 2.776 * Math.sqrt(2.0 / 3)) < 1e-9, "half-width ${c.half}")
        assertEquals("unclear", c.verdict)
        assertEquals("faster", Change(before, Estimate(listOf(7.0, 8.0, 9.0))).verdict)
        assertEquals("slower", Change(before, Estimate(listOf(13.0, 14.0, 15.0))).verdict)
        val one = Change(before, Estimate(listOf(8.0)))
        assertTrue(one.half.isNaN(), "one run on a side has no interval")
        assertEquals("", one.verdict)
    }

    /**
     * Unequal spreads and unequal run counts, where Welch's degrees of freedom are not the pooled
     * n1 + n2 - 2 and rounding down is not rounding: worked by hand.
     */
    @Test
    fun `Welch's degrees of freedom follow the spreads, rounded down`() {
        // Variances 1 and 4, three runs each: df = 50/17 = 2.94, down to 2, t 4.303. Pooled or rounded: 2.776 or 3.182.
        val spread = Change(Estimate(listOf(10.0, 11.0, 12.0)), Estimate(listOf(6.0, 8.0, 10.0)))
        assertEquals(4.303 * Math.sqrt(5.0 / 3), spread.half, 1e-9)
        assertEquals("unclear", spread.verdict)
        // Two runs against three: df 2.002, down to 2.
        val uneven = Change(Estimate(listOf(10.0, 10.1)), Estimate(listOf(5.0, 9.0, 13.0)))
        assertEquals(4.303 * Math.sqrt(0.005 / 2 + 16.0 / 3), uneven.half, 1e-9)
    }

    @Test
    fun `two runs, and runs that do not spread at all`() {
        assertEquals(12.706, Estimate(listOf(1.0, 3.0)).half, 1e-9)
        val flat = Estimate(listOf(5.0, 5.0, 5.0))
        assertEquals(0.0, flat.half)
        assertEquals("faster", Change(flat, Estimate(listOf(4.0, 4.0, 4.0))).verdict)
        assertEquals("unclear", Change(flat, Estimate(listOf(5.0, 5.0, 5.0))).verdict)
        // One side flat: all of the spread is the other's, df 2.
        assertEquals(4.303 * Math.sqrt(1.0 / 3), Change(flat, Estimate(listOf(4.0, 5.0, 6.0))).half, 1e-9)
    }

    /**
     * A fine label on one side only reads `-` on the other and gets no verdict; a run where a label
     * was called and never sampled counts at 0, not as a run without it.
     */
    @Test
    fun `fine rows on one side only, and a run that never sampled one`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            val tree = listOf(node("runBatch", listOf(1_000_000_000L)))
            for (i in 0..2) {
                save(before, i, tree, listOf(stat("x", 10L + i, 100), stat("rare", listOf(0L, 2L, 3L)[i], 100)))
                save(after, i, tree, listOf(stat("y", 20L + i, 100), stat("unused", 0, 0)))
            }
            val runs = Profile.load(before)
            val rare = runs.fineLines().getValue("rare")
            assertEquals(3, rare.present, "a run that called it and never sampled it is one of its runs")
            assertEquals(5.0 / 3 * runs.runs[0].stepNanos, rare.time.mean, 1e-6)
            assertTrue("unused" !in Profile.load(after).fineLines(), "a label never called is in no run")

            val text = ProfileDiff(runs, Profile.load(after)).render()
            val x = text.lines().first { it.startsWith("x ") }
            val y = text.lines().first { it.startsWith("y ") }
            assertTrue(x.startsWith("x (3 -> 0)") && "faster" !in x && "slower" !in x && "unclear" !in x, x)
            assertTrue(y.startsWith("y (0 -> 3)") && "faster" !in y && "slower" !in y && "unclear" !in y, y)
            assertTrue(text.lines().first { it.startsWith("rare") }.startsWith("rare (3 -> 0)"), text)
            assertTrue("NaN" !in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `two folders diff over their runs, a verdict per row and a row only one side has`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            listOf(10_000_000_000L, 11_000_000_000L, 12_000_000_000L).forEachIndexed { i, t ->
                save(before, i, listOf(node("runBatch", listOf(t, t), listOf(node("gone", listOf(t / 2)), node("same", listOf(1_000_000L + i))))))
            }
            listOf(7_000_000_000L, 8_000_000_000L, 9_000_000_000L).forEachIndexed { i, t ->
                save(after, i, listOf(node("runBatch", listOf(t, t), listOf(node("same", listOf(1_000_001L - i))))))
            }
            val text = ProfileDiff(Profile.load(before), Profile.load(after)).render()
            assertTrue("before (3 runs) -> after (3 runs)" in text, text)
            val header = text.lines().first { it.startsWith("runBatch ") }
            assertTrue("11.0 s -> 8.00 s" in header && "faster" in header && "per execution" in header, header)
            assertTrue(text.lines().any { it.trim().startsWith("gone (3 -> 0 runs)") }, "a row only before had: $text")
            val same = text.lines().first { it.trim().startsWith("same") }
            assertTrue("unclear" in same, same)
            assertTrue("CAREFUL" !in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one run on a side prints the change with no interval and says so`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            save(before, 0, listOf(node("runBatch", listOf(10L, 10L))))
            listOf(7L, 8L).forEachIndexed { i, t -> save(after, i, listOf(node("runBatch", listOf(t, t)))) }
            val text = ProfileDiff(Profile.load(before), Profile.load(after)).render()
            val lines = text.lines()
            assertTrue(lines[2].isEmpty() && lines[3].startsWith("CAREFUL       times: 1 run of 'before', no interval") && lines[4].isEmpty(), "a section of its own, right under the title: $text")
            val header = text.lines().first { it.startsWith("runBatch ") }
            assertTrue("-25%" in header && "faster" !in header && "unclear" !in header, header)
            assertTrue("NaN" !in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A long name sizes its table in a diff of one run, a diff of several and the report over runs. */
    @Test
    fun `a long name widens its column and no row goes out of line`() {
        val long = "rule:JoinPushThroughJoinRule:right".repeat(2)
        val tree = listOf(node(long, listOf(10L, 20L), listOf(node("short", listOf(5L)))))
        val ops = listOf(stat(long, 10, 10), OperationStat(1, "short", 5, 5, 0, 0, 0, 0, 5))
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            repeat(2) { save(before, it, tree, ops); save(after, it, tree, ops) }
            val b = Profile.load(before)
            val a = Profile.load(after)
            assertInLine(ProfileDiff(b, a).render())
            assertInLine(OneRunDiff(b.runs[0], a.runs[0]).render())
            assertInLine(b.render())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Every table's bars fall at one column, its head and all its rows. */
    private fun assertInLine(text: String) {
        var bar = -1
        for (line in text.lines()) {
            if (line.startsWith("COARSE")) bar = -1
            if ('|' !in line) continue
            if (bar < 0) bar = line.indexOf('|')
            assertEquals(bar, line.indexOf('|'), "out of line: $line\n$text")
        }
    }

    private fun save(dir: Path, i: Int, tree: List<CoarseNode>, ops: List<OperationStat> = listOf(stat("work", 10, 10))) =
        RunStore.save(profile(tree, ops), dir.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))

    private fun stat(name: String, hits: Long, calls: Long) = OperationStat(0, name, hits, calls, 0, 0, 0, 0, hits)

    private fun node(name: String, durations: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, durations.size.toLong(), durations.sum(), durations.min(), durations.max(), Spans.of(durations),
        10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    private fun profile(tree: List<CoarseNode>, ops: List<OperationStat>) = Run(
        operations = ops, idleHits = 0, ticks = 1_001,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
        duty = DutyReport(
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
