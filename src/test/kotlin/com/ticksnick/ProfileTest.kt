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
     * interval; the brief view leaves out a certain one's `±`. Three runs each, worked by hand: 422, 486, 490ms is 466 ±94.8ms.
     */
    @Test
    fun `a figure is written by how sure it is`() {
        assertEquals(Sure.CERTAIN, sureness(6.34, 0.32))
        assertEquals(Sure.LOOSE, sureness(466.0, 94.8))
        assertEquals(Sure.UNKNOWN, sureness(173.0, 617.0))
        assertEquals(Sure.PLAIN, sureness(6.08, Double.NaN))

        assertEquals("6.34s +-5.0%", durationWithInterval(Estimate(listOf(6.20e9, 6.37e9, 6.45e9))))
        assertEquals("6.34s", durationWithInterval(Estimate(listOf(6.20e9, 6.37e9, 6.45e9)), View.BRIEF), "brief: a certain one alone")
        assertEquals("370..560ms", durationWithInterval(Estimate(listOf(422e6, 486e6, 490e6))))
        assertEquals("370..560ms", durationWithInterval(Estimate(listOf(422e6, 486e6, 490e6)), View.BRIEF), "never a loose one")
        assertEquals("?", durationWithInterval(Estimate(listOf(56.1e6, 4.39e6, 458e6))), "the interval reaches 0")
        assertEquals("6.08s", durationWithInterval(Estimate(listOf(6.08e9))), "one run: the figure alone")
    }

    /** Every kind of change, row by row: direction, size and certainty, and what each view writes. */
    @Test
    fun `a change is judged on its own interval`() {
        /** [v] ± [half] against [before], as the tables write a percentage or points change in [view]. */
        fun write(v: Double, half: Double, before: Double, unit: String, view: View) =
            Judged(v, half, before).text(view, { percentChangeValue(v, unit) }, { percentChangeRange(maxOf(-100.0, v - half), v + half, unit) })
        // Direction certain, small, certain: up a little, by 1pp.
        assertEquals(Verdict.MOVED, Judged(1.0, 0.14, 50.0).verdict)
        assertEquals("0.9..1.1pp", write(1.0, 0.14, 50.0, "pp", View.FULL), "a change keeps its tenths")
        assertEquals("+1.0pp", write(1.0, 0.14, 50.0, "pp", View.BRIEF))
        // Direction certain, small, loose: brief writes it one value too, its size being under 5% of before.
        assertTrue(Judged(1.0, 0.7, 50.0).small && !Judged(1.0, 0.7, 50.0).certain)
        assertEquals("0.3..1.7pp", write(1.0, 0.7, 50.0, "pp", View.FULL))
        assertEquals("+1.0pp", write(1.0, 0.7, 50.0, "pp", View.BRIEF))
        // Direction certain, large, certain.
        assertEquals("-35..-45%", write(-40.0, 5.0, 100.0, "%", View.FULL))
        assertEquals("-40%", write(-40.0, 5.0, 100.0, "%", View.BRIEF))
        // Direction certain, large, loose: a range in both views.
        assertEquals("-8.2..-62%", write(-35.1, 26.9, 100.0, "%", View.FULL))
        assertEquals("-8.2..-62%", write(-35.1, 26.9, 100.0, "%", View.BRIEF))
        // Direction loose: small did not move, large is not known.
        assertEquals(Verdict.STILL, Judged(0.0, 0.8, 50.0).verdict)
        assertEquals("=", write(0.0, 0.8, 50.0, "pp", View.BRIEF))
        assertEquals("=", write(0.0, 0.8, 50.0, "pp", View.FULL))
        assertEquals("?", write(2.5, 15.5, 100.0, "%", View.FULL), "-13..+18%")
        assertEquals("?", write(0.0, 0.8, 5.0, "pp", View.FULL), "the same points against a small share are not small")
        // Zero is not a sharp line: a little across it keeps the direction, and the end on the far side its sign.
        assertEquals("-0.1..1.5pp", write(0.7, 0.8, 50.0, "pp", View.FULL))
        assertEquals("+0.7pp", write(0.7, 0.8, 50.0, "pp", View.BRIEF))
        assertEquals("5.0..-40pp", write(-17.5, 22.5, 60.0, "pp", View.FULL), "down, across zero: from the end nearer it")
        // The line is 20% of the interval across zero.
        assertEquals(Verdict.MOVED, Judged(30.0, 50.0, 100.0).verdict, "-20..80")
        assertEquals(Verdict.UNKNOWN, Judged(29.0, 50.0, 100.0).verdict, "-21..79")
        // A change from a share of 0 is never small; a decrease is written stopping at -100%.
        assertEquals(Verdict.UNKNOWN, Judged(0.0, 0.01, 0.0).verdict)
        assertEquals("-81..-100%", write(-100.0, 19.0, 100.0, "%", View.FULL))
        withSymbols(unicode = true) {
            assertEquals("↑0.9..1.1pp", write(1.0, 0.14, 50.0, "pp", View.FULL))
            assertEquals("↑1.0pp", write(1.0, 0.14, 50.0, "pp", View.BRIEF), "the arrow shows the rise: no +")
            assertEquals("↓-40%", write(-40.0, 5.0, 100.0, "%", View.BRIEF), "every number its real value")
            assertEquals("↓-8.2..-62%", write(-35.1, 26.9, 100.0, "%", View.BRIEF))
            assertEquals("↑-0.1..1.5pp", write(0.7, 0.8, 50.0, "pp", View.FULL))
            assertEquals("↓5.0..-40pp", write(-17.5, 22.5, 60.0, "pp", View.FULL))
            assertEquals("=", write(0.0, 0.8, 50.0, "pp", View.BRIEF))
        }
    }

    /** Every number its real value, the end nearer zero first: a range shows its direction by its order, so no `+`. */
    @Test
    fun `a change range is written by its real values`() {
        assertEquals("-8.2..-62%", percentChangeRange(-62.0, -8.2, "%"), "a decrease: the least it moved first")
        assertEquals("180..300%", percentChangeRange(175.0, 300.0, "%"))
        assertEquals("4.7..5.9pp", percentChangeRange(4.7, 5.9, "pp"))
        assertEquals("-<0.1..-0.7pp", percentChangeRange(-0.7, -0.02, "pp"), "near zero: below the last place, with its sign")
        assertEquals("<0.1..-<0.1pp", percentChangeRange(-0.04, 0.004, "pp"), "each end on its own")
        // A number that is not 0 but rounds to 0 at its places is below one unit of the last: never a measured 0.
        assertEquals("<0.1", fixed(0.04, 1))
        assertEquals("-<0.01", fixed(-0.004, 2))
        assertEquals("<1", fixed(0.3, 0, grouped = true))
        assertEquals("+<0.1", fixed(0.04, 1, signed = true))
        assertEquals("0.0", fixed(0.0, 1), "a real 0 is 0")
        assertEquals("0.0", fixed(-0.0, 1), "and -0.0 has no sign to show")
        assertEquals("<1 B", bytes(0.3), "bytes under one, not 0 B")
        assertEquals("16 B", bytes(16.0))
        assertEquals("0.1", fixed(0.05, 1), "half rounds up, as before")
        assertEquals("5,262", fixed(5262.0, 0, grouped = true))
        assertEquals("+-<0.1%", relativeText(0.03))
        assertEquals("-<0.1pp", percentChangeValue(-0.04, "pp"))
        assertEquals("-3.7..-5.7s", durationChangeRange(-5.65e9, -3.65e9), "time: both ends in the larger one's unit")
        assertEquals("-0.95..-1.2ms", durationChangeRange(-1.20e6, -0.95e6))
        assertEquals("-0.15..-2.4s", durationChangeRange(-2.36e9, -0.152e9), "two significant digits each end")
        assertEquals("-1.97s", durationChangeValue(-1.97e9))
        assertEquals("+5.0pp", percentChangeValue(5.0, "pp"))
        assertEquals("0.15..2.4s", durationRange(0.152e9, 2.36e9))
        assertEquals("0.95..1.0s", durationRange(0.95e9, 0.996e9), "the unit taken after rounding: 996ms is 1.0s")
        assertEquals("1.2..2.4 MB", bytesRange(1.2 * 1024 * 1024, 2.4 * 1024 * 1024), "bytes: both ends in the larger one's unit")
        assertEquals("0.49..2.0 KB", bytesRange(500.0, 2000.0))
        assertEquals("10", intervalEnd(9.96), "the decade taken after rounding")
        assertEquals("0.7", intervalEnd(0.7, 1), "never more places than the column writes")
        withSymbols(unicode = true) {
            assertEquals("↓-8.2..-62%", percentChangeRange(-62.0, -8.2, "%"))
            assertEquals("↑180..300%", percentChangeRange(175.0, 300.0, "%"))
            assertEquals("↓-<0.1..-0.7pp", percentChangeRange(-0.7, -0.02, "pp"))
            assertEquals("↓-3.7..-5.7s", durationChangeRange(-5.65e9, -3.65e9))
            assertEquals("↓-1.97s", durationChangeValue(-1.97e9))
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
