package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter: conditions on a measure's key, read strictly and applied loosely - a row is shown when either end of
 * its interval passes, and in a diff when either side does; a parent op stays while a child op of it is shown; and
 * every row left out is named where it would have been.
 */
class FilterTest {

    private val ms = 1_000_000L

    @Test
    fun `a condition is read by its key, its comparison and its unit`() {
        val wall = parseCondition("wall >= 100ms")
        assertTrue(wall.column == WALL && !wall.change && wall.op == ">=" && wall.bound == 100.0 * ms, wall.text)
        assertEquals(1.5e9, parseCondition("wall<1.5s").bound)
        val change = parseCondition(" change( wall ) <= -5% ")
        assertTrue(change.change && change.op == "<=" && change.bound == -5.0 && change.text == "change( wall ) <= -5%", change.text)
        assertEquals("wall >= 100ms and change(wall) <= -5%", Filter.of("wall >= 100ms", "change(wall) <= -5%").toString())
    }

    @Test
    fun `a condition it cannot read says what it expected`() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { Filter.of(text) }.message!!
        assertTrue("is not a filter condition" in message("wall 100ms"))
        assertTrue("'parentShare' is not a key a filter knows" in message("parentShare >= 5%"))
        assertTrue("`wall` is a time" in message("wall >= 5%"))
        assertTrue("`change(wall)` is in % of before" in message("change(wall) <= -5ms"))
    }

    /** Loose: `>=` and `>` test an interval's upper end, `<=` and `<` its lower one. */
    @Test
    fun `an interval passes when either end does`() {
        val atLeast = parseCondition("wall >= 5ms")
        assertTrue(atLeast.mayPass(2.0 * ms, 6.0 * ms))
        assertFalse(atLeast.mayPass(2.0 * ms, 4.0 * ms))
        val under = parseCondition("wall < 5ms")
        assertTrue(under.mayPass(3.0 * ms, 6.0 * ms))
        assertFalse(under.mayPass(5.0 * ms, 7.0 * ms))
        // One run is the interval of its one value.
        assertTrue(atLeast.passes(Estimate(listOf(5.0 * ms))))
        assertFalse(atLeast.passes(Estimate(emptyList())), "a figure that is not there does not pass")
    }

    @Test
    fun `a profile shows what passes, keeps a parent op as context, and names the rest under its parent op`() = inTemp { dir ->
        // root 10 ms: a 6 ms with a1 1 ms under it, and b 1 ms.
        save(dir, 0, listOf(node("root", listOf(10 * ms), listOf(node("a", listOf(6 * ms), listOf(node("a1", listOf(ms)))), node("b", listOf(ms))))))
        val profile = Profile.load(dir)

        val big = profile.render(filter = Filter.of("wall >= 5ms"))
        assertTrue("Filter: wall >= 5ms. 2 rows are not shown; each is named under its parent op" in big, big)
        assertTrue(big.lines().any { it.startsWith("  a ") }, big)
        assertTrue(big.lines().none { it.startsWith("    a1 ") || it.startsWith("  b ") }, big)
        assertTrue(big.lines().any { it == "    not shown: a1" } && big.lines().any { it == "  not shown: b" }, big)
        assertTrue(FILTER_CONTEXT_NOTE !in big, "every row shown passes: $big")

        // Only the small ones pass: root and a stay as their context.
        val small = profile.render(filter = Filter.of("wall <= 2ms"))
        assertTrue(small.lines().any { it.startsWith("root ") } && small.lines().any { it.startsWith("    a1 ") }, small)
        assertTrue(FILTER_CONTEXT_NOTE in small, small)

        // A hidden op's rows go with it, and its name says how many.
        val none = profile.render(filter = Filter.of("wall >= 20ms"))
        assertTrue(none.lines().any { it == "not shown: root (with 3 rows below it)" }, none)
    }

    @Test
    fun `no filter shows every row and says nothing`() = inTemp { dir ->
        save(dir, 0, listOf(node("root", listOf(10 * ms), listOf(node("b", listOf(ms))))))
        val text = Profile.load(dir).render()
        assertTrue("Filter:" !in text && text.lines().none { it.trimStart().startsWith("not shown:") }, text)
        assertTrue(text.lines().any { it.startsWith("  b ") }, text)
    }

    @Test
    fun `a condition a table cannot test leaves it as it is, and says why`() = inTemp { dir ->
        save(dir, 0, listOf(node("root", listOf(10 * ms))))
        val text = Profile.load(dir).render(filter = Filter.of("change(wall) <= -5%"))
        // Under each table: the first reason, in both.
        assertEquals(2, text.lines().count { it == "Filter: change(wall) <= -5% is not applied here: a profile has no change" }, text)
    }

    /** A diff row passes a condition on a value when either side may. */
    @Test
    fun `a diff shows a row when either side passes`() = inTemp { dir ->
        val before = dir.resolve("before").also { Files.createDirectories(it) }
        val after = dir.resolve("after").also { Files.createDirectories(it) }
        save(before, 0, listOf(node("root", listOf(100 * ms), listOf(node("grew", listOf(2 * ms)), node("fell", listOf(6 * ms)), node("small", listOf(2 * ms))))))
        save(after, 0, listOf(node("root", listOf(100 * ms), listOf(node("grew", listOf(6 * ms)), node("fell", listOf(2 * ms)), node("small", listOf(4 * ms))))))
        val text = TickSnick.diff(Profile.load(before), Profile.load(after), filter = Filter.of("wall >= 5ms"))
        assertTrue(text.lines().any { it.startsWith("  grew ") } && text.lines().any { it.startsWith("  fell ") }, text)
        assertTrue(text.lines().none { it.startsWith("  small ") } && text.lines().any { it == "  not shown: small" }, text)
    }

    /** A condition on a change tests the change's own interval, in % of before; a row on one side only has none. */
    @Test
    fun `a diff shows a row by its change`() = inTemp { dir ->
        val before = dir.resolve("before").also { Files.createDirectories(it) }
        val after = dir.resolve("after").also { Files.createDirectories(it) }
        save(before, 0, listOf(node("root", listOf(100 * ms), listOf(node("faster", listOf(10 * ms)), node("same", listOf(10 * ms)), node("gone", listOf(10 * ms))))))
        save(after, 0, listOf(node("root", listOf(100 * ms), listOf(node("faster", listOf(8 * ms)), node("same", listOf(10 * ms))))))
        val text = TickSnick.diff(Profile.load(before), Profile.load(after), filter = Filter.of("change(wall) <= -5%"))
        assertTrue(text.lines().any { it.startsWith("  faster ") }, text)
        assertTrue(text.lines().any { it.startsWith("  not shown: ") && "same" in it && "gone" in it }, text)
        // root did not move, and stays as the context of `faster`.
        assertTrue(text.lines().any { it.startsWith("root ") } && FILTER_CONTEXT_NOTE in text, text)
    }

    @Test
    fun `the command takes conditions, and says what is wrong with one`() = inTemp { dir ->
        save(dir, 0, listOf(node("root", listOf(10 * ms), listOf(node("b", listOf(ms))))))
        val out = System.out
        val err = System.err
        val printed = java.io.ByteArrayOutputStream()
        val errors = java.io.ByteArrayOutputStream()
        try {
            System.setOut(java.io.PrintStream(printed, true, "UTF-8"))
            System.setErr(java.io.PrintStream(errors, true, "UTF-8"))
            assertEquals(0, Cli.run(arrayOf("--filter", "wall >= 5ms", "render", dir.toString())))
            assertEquals(2, Cli.run(arrayOf("--filter", "wall >= 5%", "render", dir.toString())))
        } finally {
            System.setOut(out)
            System.setErr(err)
        }
        assertTrue("Filter: wall >= 5ms" in printed.toString("UTF-8"), printed.toString("UTF-8"))
        assertTrue("`wall` is a time" in errors.toString("UTF-8"), errors.toString("UTF-8"))
    }

    private fun inTemp(body: (Path) -> Unit) {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            body(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun save(dir: Path, i: Int, tree: List<CoarseNode>) =
        RunStore.save(profile(tree), dir.resolve("2026-10-25T16-1$i-00$RUN_EXTENSION"))

    private fun node(name: String, durations: List<Long>, children: List<CoarseNode> = emptyList()) = CoarseNode(
        0, name, durations.size.toLong(), durations.sum(), durations.min(), durations.max(), Spans.of(durations),
        10, 10, LongArray(MAX_OPERATIONS + 1), 0, children,
    )

    private fun profile(tree: List<CoarseNode>) = Run(
        operations = listOf(OperationStat(0, "work", 10, 10, 0, 0, 0, 0, 10)), idleHits = 0, ticks = 1_001,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
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
