package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two versions over their runs: the interval on the change, whether it could be noise, and the report over two folders. */
class ProfileDiffTest {

    /**
     * 10, 11, 12 against 8, 9, 10: a variance of 1 on each side, Welch's 4 degrees of freedom, t 2.776,
     * so -2 +- 2.27 - an interval that includes 0 by a hair. Worked by hand.
     */
    @Test
    fun `the change has Welch's interval`() {
        val before = Estimate(listOf(10.0, 11.0, 12.0))
        val c = Change(before, Estimate(listOf(8.0, 9.0, 10.0)))
        assertEquals(-2.0, c.delta, 1e-9)
        assertTrue(abs(c.half - 2.776 * Math.sqrt(2.0 / 3)) < 1e-9, "half-width ${c.half}")
        val one = Change(before, Estimate(listOf(8.0)))
        assertTrue(one.half.isNaN(), "one run on a side has no interval")
        assertTrue(!one.isNoise(1), "no interval, nothing to say")
    }

    /** Values from a table of Student's t. */
    @Test
    fun `the tail of Student's t`() {
        assertEquals(0.05, studentTail(12.706, 1), 1e-4)
        assertEquals(0.05, studentTail(4.303, 2), 1e-4)
        assertEquals(0.05, studentTail(3.182, 3), 1e-4)
        assertEquals(0.05, studentTail(2.447, 6), 1e-4)
        assertEquals(0.05, studentTail(2.228, 10), 1e-4)
        assertEquals(0.05 / 80, studentTail(6.51, 6), 1e-5)
        assertEquals(1.0, studentTail(0.0, 6), 1e-12)
    }

    /**
     * 10, 11, 12 against 7, 8, 9: -3 +- 2.27, clear of 0, t 3.67 with 4 degrees of freedom, which is
     * one chance in 47. Enough for one change, and not for 80 of them, where it would be held to one
     * in 1,600. Against 8, 9, 10 the interval itself includes 0.
     */
    @Test
    fun `a change could be noise by how many changes the diff shows`() {
        val before = Estimate(listOf(10.0, 11.0, 12.0))
        val clear = Change(before, Estimate(listOf(7.0, 8.0, 9.0)))
        assertTrue(!clear.isNoise(1))
        assertTrue(clear.isNoise(80))
        assertTrue(Change(before, Estimate(listOf(8.0, 9.0, 10.0))).isNoise(1))
        assertTrue(!Change(before, Estimate(listOf(1.0, 2.0, 3.0))).isNoise(80), "far beyond the spread")
        assertTrue(Change(before, Estimate(listOf(13.0, 14.0, 15.0))).isNoise(80), "up as well as down")
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
        // Two runs against three: df 2.002, down to 2.
        val uneven = Change(Estimate(listOf(10.0, 10.1)), Estimate(listOf(5.0, 9.0, 13.0)))
        assertEquals(4.303 * Math.sqrt(0.005 / 2 + 16.0 / 3), uneven.half, 1e-9)
    }

    @Test
    fun `two runs, and runs that do not spread at all`() {
        assertEquals(12.706, Estimate(listOf(1.0, 3.0)).half, 1e-9)
        val flat = Estimate(listOf(5.0, 5.0, 5.0))
        assertEquals(0.0, flat.half)
        assertTrue(!Change(flat, Estimate(listOf(4.0, 4.0, 4.0))).isNoise(80), "no spread at all: a change is a change")
        assertTrue(!Change(flat, Estimate(listOf(5.0, 5.0, 5.0))).isNoise(80), "and no change is none")
        // One side flat: all of the spread is the other's, df 2.
        assertEquals(4.303 * Math.sqrt(1.0 / 3), Change(flat, Estimate(listOf(4.0, 5.0, 6.0))).half, 1e-9)
    }

    /**
     * A fine label on one side only reads `-` on the other and has no change; a run where a label
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
            val rare = runs.fineTable.rows.first { it.name == "rare" }
            assertEquals(3, rare.present, "a run that called it and never sampled it is one of its runs")
            assertEquals(5.0 / 3 * runs.runs[0].stepNanos, rare.figures.getValue(THREAD_TIME).mean, 1e-6)
            assertTrue(Profile.load(after).fineTable.rows.none { it.name == "unused" }, "a label never called is in no run")

            val text = ProfileDiff(runs, Profile.load(after)).render()
            val x = diffRow(text, "x")
            val y = diffRow(text, "y")
            assertTrue(x[0].startsWith("x (3 -> 0 runs)") && '%' !in x[2], x.toString())
            assertTrue(y[0].startsWith("y (0 -> 3 runs)") && '%' !in y[2], y.toString())
            assertTrue(diffRow(text, "rare")[0].startsWith("rare (3 -> 0 runs)"), text)
            assertTrue("NaN" !in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `two folders diff over their runs, a change per row and a row only one side has`() {
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
            assertTrue("11.0s -> 8.00s" in header && Regex("""-27\? \+-[0-9.]+%""").containsMatchIn(header) && "per execution" in header, header)
            assertTrue(text.lines().any { it.trim().startsWith("gone (3 -> 0 runs)") }, "a row only before had: $text")
            assertTrue("?" in diffRow(text, "same")[2] && NOISE_NOTE in text, text)
            assertTrue("WARNINGS" !in text, text)
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
            assertTrue(lines[2].isEmpty() && lines[3] == "WARNINGS" && lines[4].startsWith("  Runs: 1 in 'before', no interval") && lines[5].isEmpty(), "a section of its own, right under the title: $text")
            val header = text.lines().first { it.startsWith("runBatch ") }
            assertTrue("-25%" in header && "?" !in header && "+-" !in header.substringAfter("-25%"), header)
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
            assertInLine(diffOf(b.runs[0], a.runs[0]))
            assertInLine(b.render())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Every report shape in both symbol sets: ASCII has nothing above 127, Unicode nothing but its six
     * symbols, and each table's bars fall in the same columns in both forms. `+-` in Unicode is one character
     * and `+-` two, so a symbol swapped after the columns were measured would break exactly this.
     */
    @Test
    fun `every report shape lines up in ASCII and in Unicode`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            fun folder(name: String, batches: List<Long>, extra: Boolean): Profile {
                val d = dir.resolve(name).also { Files.createDirectories(it) }
                batches.forEachIndexed { i, t ->
                    val children = listOf(node("left", listOf(t / 3 + i * 1_000_000L), listOf(node("inner", listOf(t / 10)))), node("right", listOf(t / 4))) +
                        if (extra) listOf(node("only", listOf(t / 5))) else emptyList()
                    save(d, i, listOf(node("runBatch", listOf(t, t), children)), listOf(stat("work", 10L + i, 1_000), stat("probe", 3L * (i + 1), 50_000)))
                }
                return Profile.load(d)
            }
            val one = folder("one", listOf(10_000_000_000L), true)
            val three = folder("three", listOf(10_000_000_000L, 11_000_000_000L, 12_000_000_000L), true)
            val other = folder("other", listOf(7_000_000_000L, 9_000_000_000L, 8_500_000_000L), false)
            val otherOne = folder("otherOne", listOf(7_000_000_000L), false)
            val shapes: List<Pair<String, (Boolean) -> String>> = listOf(
                "one run" to { u -> one.render(u) },
                "three runs" to { u -> three.render(u) },
                "diff 1:1" to { u -> TickSnick.diff(one, otherOne, u) },
                "diff 3:1" to { u -> TickSnick.diff(three, otherOne, u) },
                "diff 3:3" to { u -> TickSnick.diff(three, other, u) },
            )
            val unicodeSymbols = UNICODE_SYMBOLS.run { plusMinus + micro + arrow + dash + ellipsis + times }.filter { it.code > 127 }.toSet()
            var sawPlusMinus = false
            for ((what, render) in shapes) {
                val ascii = render(false)
                val unicode = render(true)
                ascii.lines().forEach { line -> assertTrue(line.all { it.code <= 127 }, "$what, ASCII: $line") }
                unicode.lines().forEach { line -> assertTrue(line.all { it.code <= 127 || it in unicodeSymbols }, "$what, Unicode: $line") }
                sawPlusMinus = sawPlusMinus || '±' in unicode
                for (text in listOf(ascii, unicode)) for (table in bars(text)) assertEquals(1, table.distinct().size, "$what: a table out of line:\n$text")
                // The check has teeth: the symbol swapped after the columns were measured breaks it.
                if ('±' in unicode) assertTrue(bars(ascii.replace("+-", "±")).any { it.distinct().size > 1 }, "$what: a late swap went unseen")
            }
            assertTrue(sawPlusMinus, "no report shape printed an interval, so the test checked nothing")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Each table's lines as the columns of their bars, one list per table. */
    private fun bars(text: String): List<List<List<Int>>> {
        val tables = mutableListOf<MutableList<List<Int>>>()
        var current: MutableList<List<Int>>? = null
        for (line in text.lines()) {
            if (line.startsWith("COARSE") || line.isBlank()) current = null
            if ('|' !in line && '+' !in line.trim().take(1)) continue
            val at = line.indices.filter { line[it] == '|' || (line[it] == '+' && line.trim().all { c -> c == '-' || c == '+' }) }
            if (at.isEmpty()) continue
            if (current == null) current = mutableListOf<List<Int>>().also { tables += it }
            current += at
        }
        return tables
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

    /** A report's lines by what each is, for comparing their order and not their figures. */
    private fun shape(report: String): List<String> {
        val keys = listOf("Runs", "Wall time", "runBatch", "COARSE OPERATIONS", "Coarse operation", "COARSE AND FINE", "Operation", "Sampled", "HOW TO READ THIS")
        return report.lines().mapNotNull { line -> keys.firstOrNull { line.startsWith("$it ") || line == it } }
    }

    @Test
    fun `a report prints the same lines for one run and for several`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val one = dir.resolve("one").also { Files.createDirectories(it) }
            val two = dir.resolve("two").also { Files.createDirectories(it) }
            val tree = listOf(node("runBatch", listOf(1_000L, 2_000L)))
            save(one, 0, tree)
            repeat(2) { save(two, it, tree) }
            val alone = Profile.load(one).render()
            val many = Profile.load(two).render()
            assertEquals(shape(alone), shape(many))
            assertTrue("1 run of 'one', one JVM: no interval" in alone, alone)
            assertTrue("runBatch" in alone.lines().first { it.startsWith("runBatch ") }, alone)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a diff has everything a profile has`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            fun keyed(key: String) = CoarseNode(
                0, "top", 1, 1_000, 1_000, 1_000, Spans.of(listOf(1_000L)), 10, 10, LongArray(MAX_OPERATIONS + 1), 0,
                listOf(node("runBatch", listOf(100L)), node("other", listOf(100L), listOf(node("runBatch", listOf(50L))))),
                slowest = listOf(SlowExecution(key, 1_000)),
            )
            save(before, 0, listOf(keyed("was slow")))
            save(after, 0, listOf(keyed("is slow")))
            val text = ProfileDiff(Profile.load(before), Profile.load(after)).render()
            assertTrue(SHARED_HEADING in text, text)
            assertTrue(text.lines().any { it.trimStart().startsWith("runBatch (2 parents)") }, text)
            assertTrue(text.lines().any { "slowest before:" in it && "was slow" in it }, text)
            assertTrue(text.lines().any { "slowest after:" in it && "is slow" in it }, text)
            assertTrue(text.lines().any { it.startsWith("Sampled       before") }, text)
            assertTrue("HOW TO READ THIS" in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** More own rows than are drawn are one row in the same columns, paired by a diff however many it stands for. */
    @Test
    fun `the rows not drawn are one row, on both sides of a diff`() {
        val folded = { n: Int ->
            Row(
                FOLDED_ROWS, mapOf(PARENT_SHARE to Estimate(listOf(0.3))), 1, own = true, foldedRows = n,
            )
        }
        val pairedRows = paired(listOf(folded(3)), listOf(folded(5)))
        assertEquals(1, pairedRows.size)
        assertEquals(3, pairedRows[0].before?.foldedRows)
        assertEquals(5, pairedRows[0].after?.foldedRows)
    }

    /** What a run alone could say about itself reaches a profile of several, and so a diff. */
    @Test
    fun `a warning about one run reaches a profile of several`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val folder = dir.resolve("runs").also { Files.createDirectories(it) }
            repeat(2) { RunStore.save(profile(emptyList(), listOf(stat("work", 10, 10)), stale = 5), folder.resolve("2026-10-25T16-1$it-00$RUN_EXTENSION")) }
            val text = Profile.load(folder).render()
            assertTrue(text.lines().any { it.startsWith("  ") && "of the coarse time came from tasks" in it && "(2 of 2 runs)" in it }, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A `?` on a line over the tables, with none in them, still has its key. */
    @Test
    fun `a header line with a question mark has the note`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            fun spans(folder: Path, vararg nanos: Long) = nanos.forEachIndexed { i, span ->
                RunStore.save(profile(emptyList(), emptyList(), span = span), folder.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))
            }
            spans(before, 1_000_000_000, 1_200_000_000)
            spans(after, 1_050_000_000, 1_250_000_000)
            val text = ProfileDiff(Profile.load(before), Profile.load(after)).render()
            assertTrue("?" in text.lines().first { it.startsWith("Wall time") }, text)
            assertTrue(NOISE_NOTE in text, text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun save(dir: Path, i: Int, tree: List<CoarseNode>, ops: List<OperationStat> = listOf(stat("work", 10, 10))) =
        RunStore.save(profile(tree, ops), dir.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))

    private fun stat(name: String, hits: Long, calls: Long) = OperationStat(0, name, hits, calls, 0, 0, 0, 0, hits)

    private fun node(name: String, durations: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, durations.size.toLong(), durations.sum(), durations.min(), durations.max(), Spans.of(durations),
        10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    private fun profile(tree: List<CoarseNode>, ops: List<OperationStat>, span: Long = 1_000_000_000, stale: Long = 0) = Run(
        operations = ops, idleHits = 0, ticks = 1_001,
        samplingSpanNanos = span, durationNanos = span, threads = 1,
        staleContextHits = stale, coarseSampleHits = 10,
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
