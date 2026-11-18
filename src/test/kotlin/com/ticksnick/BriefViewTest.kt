package com.ticksnick

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The brief view: the basis columns, a certain figure without its interval, a certain change as one value, a
 * diff one line a row aligned on the arrow, and each table's notices. The full view is the rest of the tests.
 */
class BriefViewTest {

    /** 10.0, 10.1, 10.2 s a batch against 8.0, 8.1, 8.2 s: each side certain, ±2.5%, and the change -19.8% ±2.3pp, certain. */
    private val beforeBatches = listOf(10_000_000_000L, 10_100_000_000L, 10_200_000_000L)
    private val afterBatches = listOf(8_000_000_000L, 8_100_000_000L, 8_200_000_000L)

    private fun <T> folders(block: (Path, Path) -> T): T {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            fun runs(folder: Path, batches: List<Long>) = batches.forEachIndexed { i, t ->
                val tree = listOf(node("runBatch", listOf(t), listOf(node("child", listOf(t / 2)))))
                save(folder, i, tree, listOf(stat("work", 100L + i, 1_000)))
            }
            runs(before, beforeBatches)
            runs(after, afterBatches)
            return block(before, after)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `brief is the default, for a report and a diff`() = folders { before, after ->
        val b = Profile.load(before)
        val a = Profile.load(after)
        assertEquals(b.render(false, View.BRIEF), b.render())
        assertEquals(TickSnick.diff(b, a, false, View.BRIEF), TickSnick.diff(b, a))
        assertTrue("Lenses not shown" in b.render() && "Lenses not shown" !in b.render(view = View.FULL))
    }

    @Test
    fun `brief shows the basis columns and names each table's lenses right after its heading`() = folders { before, _ ->
        val lines = Profile.load(before).render().lines()
        val heads = lines.filter { it.startsWith("Operation") }
        assertEquals(2, heads.size, lines.joinToString("\n"))
        val (wall, own) = heads
        for (name in listOf("Executions", "Per parent", "Wall v", "% parent", "Mean")) assertTrue(name in wall, wall)
        for (name in listOf("Parallel", "Paused", "Waiting", "p50", "p90", "p99", "Max", "Allocated")) assertFalse(name in wall, wall)
        for (name in listOf("Thread-time v", "Thread-time%", "Calls", "Thread-time per call")) assertTrue(name in own, own)
        for (name in listOf("Waiting", "Long calls", "Longest", "Wall-time", "Concurrency", "Workers", "Pool")) assertFalse(name in own, own)
        fun after(heading: String) = lines[lines.indexOfFirst { it.startsWith(heading) } + 1]
        val full = "the full view, every lens and every interval, is --view full"
        assertEquals("Lenses not shown: Parallelism, Waiting, Memory, Latency - open one with --lens; $full", after("NESTED WALL TIME"))
        assertEquals("Lenses not shown: Parallelism, Waiting, Latency - open one with --lens; $full", after("OWN THREAD-TIME"))
    }

    /**
     * A lens opened shows its columns in the brief view, after the basis, and the notice names only the ones still
     * closed; with every one open it says so; the full view's says what it is and how to go back. Each says what the
     * full view is.
     */
    @Test
    fun `a lens opened is shown, and the notice says what is left and what the full view is`() = folders { before, after ->
        val b = Profile.load(before)
        val a = Profile.load(after)
        val full = "the full view, every lens and every interval"
        for (text in listOf(b.render(lenses = setOf(Lens.LATENCY)), TickSnick.diff(b, a, lenses = setOf(Lens.LATENCY)))) {
            val lines = text.lines()
            fun after(heading: String) = lines[lines.indexOfFirst { it.startsWith(heading) } + 1]
            val (wall, own) = lines.filter { it.startsWith("Operation") }
            for (name in listOf("p50", "p90", "p99", "Max")) assertTrue(name in wall, wall)
            for (name in listOf("Parallel", "Paused", "Waiting")) assertFalse(name in wall, wall)
            for (name in listOf("Long calls", "Long time", "Longest")) assertTrue(name in own, own)
            assertEquals("Lenses not shown: Parallelism, Waiting, Memory - open one with --lens; $full, is --view full", after("NESTED WALL TIME"))
            assertEquals("Lenses not shown: Parallelism, Waiting - open one with --lens; $full, is --view full", after("OWN THREAD-TIME"))
        }
        val all = b.render(lenses = Lens.entries.toSet()).lines()
        assertEquals("All lenses shown; $full, is --view full", all[all.indexOfFirst { it.startsWith("NESTED WALL TIME") } + 1])
        val fullView = b.render(view = View.FULL).lines()
        assertEquals(
            "This is $full; the brief view, the basis with lenses opened by --lens, is --view brief",
            fullView[fullView.indexOfFirst { it.startsWith("NESTED WALL TIME") } + 1],
        )
    }

    /** An open lens's columns in a brief diff are `before -> after` and the change, as Time and Share; Work's the change only. */
    @Test
    fun `an open lens in a brief diff shows both sides`() = folders { before, after ->
        val text = TickSnick.diff(Profile.load(before), Profile.load(after), lenses = setOf(Lens.LATENCY))
        val row = text.lines().first { it.startsWith("runBatch ") && "|" in it }
        val cells = row.split("|").map { it.trim() }
        assertTrue(cells.last().count { it == '>' } == 4, row)
        assertTrue(cells[3].none { it == '>' }, row)
    }

    @Test
    fun `one run says so first, and then the lenses`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            save(dir, 0, listOf(node("runBatch", listOf(1_000_000L))))
            val lines = Profile.load(dir).render().lines()
            for (heading in listOf("NESTED WALL TIME", "OWN THREAD-TIME")) {
                val at = lines.indexOfFirst { it.startsWith(heading) }
                assertTrue(lines[at + 1].startsWith("1 run: no interval"), lines[at + 1])
                assertTrue(lines[at + 2].startsWith("Lenses not shown: "), lines[at + 2])
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a certain figure is written without its interval, as the full view writes it with one`() = folders { before, _ ->
        val profile = Profile.load(before)
        fun row(text: String) = text.lines().first { it.startsWith("runBatch ") && "|" in it }
        val brief = row(profile.render())
        val full = row(profile.render(view = View.FULL))
        assertTrue("10.1s +-" in full, full)
        assertTrue("10.1s" in brief && "+-" !in brief, brief)
        // The same in Unicode, with nothing but the figure.
        assertFalse("±" in row(profile.render(true)), row(profile.render(true)))
    }

    @Test
    fun `a diff is one line a row, a certain change its one value, the sides on the arrow`() = folders { before, after ->
        val b = Profile.load(before)
        val a = Profile.load(after)
        val ascii = TickSnick.diff(b, a)
        val unicode = TickSnick.diff(b, a, unicode = true)
        val row = ascii.lines().first { it.startsWith("runBatch ") && "|" in it }
        // Before -> after, then the change: certain, so the point estimate with its direction, not a range.
        assertTrue(Regex("""10\.1s -> 8\.10s +-20% """).containsMatchIn(row), row)
        assertTrue(Regex("""10\.1s → 8\.10s +↓-20% """).containsMatchIn(unicode.lines().first { it.startsWith("runBatch ") && "|" in it }), unicode)
        // The full view still gives the certain change its range, by its real values.
        val full = diffRow(TickSnick.diff(b, a, view = View.FULL), "runBatch")[2]
        assertTrue(Regex("""-[0-9.]+\.\.-[0-9.]+%""").containsMatchIn(full), full)
        // One line a row: the child's line follows the root's.
        val lines = ascii.lines()
        val at = lines.indexOf(row)
        assertTrue(lines[at + 1].trimStart().startsWith("child "), lines[at + 1])
        // The arrows of one column fall in one place on every row, before and after aligned on it.
        for (text in listOf(ascii, unicode)) {
            val arrow = if (text === ascii) " -> " else " → "
            val table = text.lines().dropWhile { !it.startsWith("NESTED WALL TIME") }.takeWhile { !it.startsWith("OWN THREAD-TIME") }
            val rows = table.filter { "|" in it && arrow in it }
            assertTrue(rows.size >= 2, table.joinToString("\n"))
            assertEquals(1, rows.map { it.indexOf(arrow) }.distinct().size, rows.joinToString("\n"))
        }
    }

    @Test
    fun `every heading is right-aligned over its column's right edge`() = folders { before, after ->
        val text = TickSnick.diff(Profile.load(before), Profile.load(after))
        val lines = text.lines()
        val head = lines.first { it.startsWith("Operation") }
        val row = lines.first { it.startsWith("runBatch ") && "|" in it }
        // Wall's change ends where its heading does; Mean's, the last column, too.
        assertEquals(head.indexOf("Wall v") + "Wall v".length, row.indexOf("-20%") + "-20%".length, "$head\n$row")
        assertEquals(head.length, row.length, "$head\n$row")
        assertTrue(row.endsWith("-20%"), row)
    }

    @Test
    fun `brief leaves out the slowest lists and the keys to what it does not show`() = folders { before, after ->
        val b = Profile.load(before)
        val a = Profile.load(after)
        for (text in listOf(b.render(), TickSnick.diff(b, a))) {
            assertFalse("Max is the mean of the runs' largest" in text, text)
            assertFalse("  Long calls: calls over a tick" in text, text)
            assertFalse("+- in percentage points" in text, text)
        }
        // A lens opened brings the keys to its own columns.
        for (text in listOf(b.render(view = View.FULL), b.render(lenses = setOf(Lens.LATENCY)))) {
            assertTrue("Max is the mean of the runs' largest" in text && "  Long calls: calls over a tick" in text, text)
        }
    }

    /**
     * The columns go by measure group, Time, Share, Work, Cost and then the lenses, in both tables, both views, a
     * report and a diff; the band over the heads names each group between the same bars as the heads.
     */
    @Test
    fun `the columns go by group and the band over them names each group`() = folders { before, after ->
        val b = Profile.load(before)
        val a = Profile.load(after)
        fun inOrder(line: String, names: List<String>): Boolean =
            names.map { line.indexOf(it) }.let { at -> at.all { it >= 0 } && at.zipWithNext().all { (x, y) -> x < y } }
        val basis = listOf("Time", "Share", "Work", "Cost")
        for (view in View.entries) {
            val full = view == View.FULL
            val wallHeads = listOf("Wall v", "% parent", "Executions", "Per parent", "Mean") +
                    if (full) listOf("Parallel", "Waiting", "Paused", "p50", "p90", "p99", "Max") else emptyList()
            val ownHeads = listOf("Thread-time v", "Thread-time%", "Calls", "Thread-time per call") +
                    if (full) listOf("Wall-time", "Concurrency", "Workers", "Pool", "Waiting", "Long calls", "Long time", "Longest") else emptyList()
            val wallBand = basis + if (full) listOf("Parallelism", "Waiting", "Memory", "Latency") else emptyList()
            val ownBand = basis + if (full) listOf("Parallelism", "Waiting", "Latency") else emptyList()
            for (text in listOf(b.render(view = view), TickSnick.diff(b, a, view = view))) {
                val lines = text.lines()
                // A table's heads have the band right above them; the block under the wall table has none.
                val heads = lines.indices.filter { lines[it].startsWith("Operation") && "|" in lines[it - 1] && !lines[it - 1].startsWith("Operation") }
                assertEquals(2, heads.size, text)
                for ((at, names, band) in listOf(Triple(heads[0], wallHeads, wallBand), Triple(heads[1], ownHeads, ownBand))) {
                    val head = lines[at]
                    val over = lines[at - 1]
                    assertTrue(inOrder(head, names), "$view, the heads out of their groups' order:\n$head")
                    assertTrue(inOrder(over, band), "$view, the band:\n$over\n$head")
                    for (old in listOf("Load", "Spread", "Allocated", "Long ")) assertFalse(old in over, "$view, an old group in the band:\n$over")
                    assertEquals(head.indices.filter { head[it] == '|' }, over.indices.filter { over[it] == '|' }, "$view, the band's bars:\n$over\n$head")
                }
            }
        }
    }

    /** The header lines take brief's figure rule too: a certain figure without its `±`, a certain change its middle. */
    @Test
    fun `brief writes the header lines by its rule too`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            // Each run's wall time its batch: 10.1 s ±2.5% against 8.10 s, certain on both sides, the change -20%.
            beforeBatches.forEachIndexed { i, t -> save(before, i, listOf(node("runBatch", listOf(t))), span = t) }
            afterBatches.forEachIndexed { i, t -> save(after, i, listOf(node("runBatch", listOf(t))), span = t) }
            val b = Profile.load(before)
            fun line(text: String) = text.lines().first { it.startsWith("Wall time ") }
            assertTrue(line(b.render()).startsWith("Wall time     10.1s = "), line(b.render()))
            assertTrue(line(b.render(view = View.FULL)).startsWith("Wall time     10.1s +-2.5% = "), line(b.render(view = View.FULL)))
            val diff = line(TickSnick.diff(b, Profile.load(after)))
            assertTrue(Regex("""10\.1s -> 8\.10s\s+-2\.00s\s+-20%""").containsMatchIn(diff), diff)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Each brief table ends with the key to its forms; a diff's with one to its changes too; the full view has none. */
    @Test
    fun `each brief table ends with the key to its forms`() = folders { before, after ->
        val figures = "  A plain figure (3.4) means its 95% interval is within 20% of it; otherwise a range (2.2..7.0) shows the interval's ends; ? is unknown and requires more runs"
        val changes = "  A change as one value (-40%) is certain (20% interval) or small (5% of before); a range shows its interval's ends; = did not move; ? is unknown: the change is within its sides' run-to-run noise, and more runs would tell"
        val b = Profile.load(before)
        val a = Profile.load(after)
        val report = b.render().lines()
        assertEquals(2, report.count { it == figures }, "one under each table")
        assertTrue(report.none { it == changes })
        // The first of the notes: right after the table's closing rule.
        report.indices.filter { report[it] == figures }.forEach { assertTrue(report[it - 1].startsWith("----"), report[it - 1]) }
        val diff = TickSnick.diff(b, a, unicode = true).lines()
        assertEquals(2, diff.count { it == figures })
        assertEquals(2, diff.count { it == changes }, "the example stays (-40%) in Unicode too")
        assertEquals(diff.indexOf(figures) + 1, diff.indexOf(changes))
        assertTrue(figures !in b.render(view = View.FULL).lines() && changes !in TickSnick.diff(b, a, view = View.FULL).lines())
    }

    /** Where the one-run line is, plain does not mean certain: no key. With two runs it does, and the key stays. */
    @Test
    fun `few runs say so first, one run has no key, and two keep it`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val one = dir.resolve("one").also { Files.createDirectories(it) }
            val two = dir.resolve("two").also { Files.createDirectories(it) }
            save(one, 0, listOf(node("runBatch", listOf(1_000_000L))))
            listOf(1_000_000L, 1_100_000L).forEachIndexed { i, t -> save(two, i, listOf(node("runBatch", listOf(t)))) }
            fun under(text: String, heading: String) = text.lines().let { it.drop(it.indexOfFirst { l -> l.startsWith(heading) } + 1) }
            val single = Profile.load(one).render()
            val pair = Profile.load(two).render()
            for (heading in listOf("NESTED WALL TIME", "OWN THREAD-TIME")) {
                assertTrue(under(single, heading)[0].startsWith("1 run: no interval"), single)
                assertTrue(under(single, heading)[1].startsWith("Lenses not shown: "), single)
                assertEquals("2 runs: the interval is too wide to say much - run at least 3 times to tell", under(pair, heading)[0], pair)
                assertTrue(under(pair, heading)[1].startsWith("Lenses not shown: "), pair)
            }
            assertFalse("A plain figure" in single, single)
            assertTrue("A plain figure" in pair, pair)
            val mixed = TickSnick.diff(Profile.load(one), Profile.load(two))
            val lines = under(mixed, "NESTED WALL TIME")
            // One line: with a side of one run no change has an interval, and the advice is the same.
            assertTrue(lines[0].startsWith("1 run before: ") && lines[1].startsWith("Lenses"), mixed)
            assertFalse("A plain figure" in mixed || "A change as one value" in mixed, mixed)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A row on one side only has no arrow, its side in its place; a side that is there but `?` keeps it. */
    @Test
    fun `a row on one side only has no arrow`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            beforeBatches.forEachIndexed { i, t -> save(before, i, listOf(node("runBatch", listOf(t), listOf(node("gone", listOf(t / 2)))))) }
            afterBatches.forEachIndexed { i, t -> save(after, i, listOf(node("runBatch", listOf(t)))) }
            val text = TickSnick.diff(Profile.load(before), Profile.load(after))
            val gone = text.lines().first { it.trimStart().startsWith("gone ") && "|" in it }
            assertTrue("3 -> 0 runs" in gone && "->" !in gone.substringAfter("|"), gone)
            val root = text.lines().first { it.startsWith("runBatch ") && "|" in it }
            assertEquals(root.indexOf(" -> "), gone.indexOf("5.05s") + "5.05s".length, "the side in its place:\n$root\n$gone")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the command line takes the view`() = folders { before, after ->
        fun out(vararg args: String): String {
            val buffer = ByteArrayOutputStream()
            val old = System.out
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            try {
                assertEquals(0, Cli.run(arrayOf(*args)), args.joinToString(" "))
            } finally {
                System.setOut(old)
            }
            return buffer.toString(Charsets.UTF_8)
        }
        assertTrue("Lenses not shown" in out("render", before.toString()))
        assertFalse("Lenses not shown" in out("--view", "full", "render", before.toString()))
        assertTrue("↓-20%" in out("--view", "brief", "--unicode", "diff", before.toString(), after.toString()))
        assertEquals(2, Cli.run(arrayOf("--view", "wide", "render", before.toString())))
        assertEquals(2, Cli.run(arrayOf("--view")))
        // --lens, once for each lens to open, in any order with the rest.
        val latency = out("--lens", "latency", "render", before.toString())
        assertTrue("p99" in latency && "Concurrency" !in latency, latency)
        val two = out("--lens", "LATENCY", "--unicode", "--lens", "parallelism", "render", before.toString())
        assertTrue("p99" in two && "Concurrency" in two && "Lenses not shown: Waiting, Memory" in two, two)
        assertEquals(2, Cli.run(arrayOf("--lens", "speed", "render", before.toString())))
        assertEquals(2, Cli.run(arrayOf("--lens")))
    }

    /** A column is as wide as its widest cell, in the symbols of the render it is in: one render never sizes another. */
    @Test
    fun `a render in ASCII after one in Unicode lines up as a fresh one does`() = folders { before, _ ->
        val profile = Profile.load(before)
        profile.render(unicode = true, view = View.FULL)
        assertEquals(Profile.load(before).render(view = View.FULL), profile.render(view = View.FULL))
    }

    /** Runs that agree on each side give a change an interval of 0: known exactly, one value with its arrow, the header's too. */
    @Test
    fun `an exact change has its arrow, in the header as in the table`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            val before = dir.resolve("before").also { Files.createDirectories(it) }
            val after = dir.resolve("after").also { Files.createDirectories(it) }
            for (i in 0..2) {
                save(before, i, listOf(node("runBatch", listOf(10_000_000L))), span = 10_000_000L)
                save(after, i, listOf(node("runBatch", listOf(8_000_000L))), span = 8_000_000L)
            }
            for (view in View.entries) {
                val text = TickSnick.diff(Profile.load(before), Profile.load(after), unicode = true, view = view)
                val header = text.lines().first { it.startsWith("Wall time ") }
                assertTrue("↓-2.00ms" in header && "↓-20%" in header, header)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Runs may differ in a count as in any figure: it has its interval, written by the same rule, never a `~`; and
     * `Max` is the mean of the runs' largest, not the largest of all of them, which grows with how many runs there are.
     */
    @Test
    fun `a count that differs across runs has its interval, and Max is the mean of the runs' largest`() {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            // Executions 2, 3 and 4; each run's largest 10.0, 10.1 and 10.2 ms.
            for (i in 0..2) save(dir, i, listOf(node("runBatch", List(1 + i) { 1_000_000L } + (10_000_000L + i * 100_000L))))
            val row = Profile.load(dir).render(view = View.FULL).lines().first { it.startsWith("runBatch ") && "|" in it }
            // Executions 3 +-2.5: its range in whole numbers, as the count is written.
            assertTrue("~" !in row && "1..5" in row, row)
            assertTrue("10.1ms" in row && "10.2ms" !in row, row)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun save(dir: Path, i: Int, tree: List<CoarseNode>, ops: List<OperationStat> = listOf(stat("work", 10, 10)), span: Long = 1_000_000_000) =
        RunStore.save(profile(tree, ops, span), dir.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))

    private fun stat(name: String, hits: Long, calls: Long) = OperationStat(0, name, hits, calls, 0, 0, 0, 0, hits)

    private fun node(name: String, durations: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, durations.size.toLong(), durations.sum(), durations.min(), durations.max(), Spans.of(durations),
        10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    private fun profile(tree: List<CoarseNode>, ops: List<OperationStat>, span: Long = 1_000_000_000) = Run(
        operations = ops, idleHits = 0, ticks = 1_001,
        samplingSpanNanos = span, durationNanos = span, threads = 1,
        coarseSampleHits = 10,
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
