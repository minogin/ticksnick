package com.ticksnick

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Calls of a fine label per execution of the coarse operation it ran under, in the coarse tree.
 *
 * A run's total calls cannot be compared with another run's when the two did a different amount of
 * work: 30 plans against 3 reads as ten times fewer calls. Per execution of the parent it can. The
 * workload below has the three cases that matter:
 *
 * - `rule` runs under `plan` only, so its total over the plans would give the right figure;
 * - `visit` runs under `plan` and under `cleanup`, so its total cannot be split by division;
 * - `rare` runs slowly under `plan` and once, in about 100 ns, under `cleanup` - too short ever to
 *   be sampled there, so a label that looks as if it had one parent does not.
 *
 * Every count is known exactly, so each row is checked against it. Two versions, ten batches' worth
 * of plans against two, give the same figure per parent and totals five times apart.
 */
class CallsPerParentTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling: the test stopped it itself.
        }
    }

    @Volatile
    private var sink: Long = 0

    private fun spinFor(nanos: Long) {
        val until = System.nanoTime() + nanos
        var s = sink or 1L
        while (System.nanoTime() < until) {
            for (i in 0 until 256) s = s * 31 + i
        }
        sink = s
    }

    private val us = 1_000L

    private val batch = Profiler.registerCoarse("batch")
    private val plan = Profiler.registerCoarse("plan")
    private val cleanup = Profiler.registerCoarse("cleanup")
    private val rule = Profiler.registerFine("rule")
    private val visit = Profiler.registerFine("visit")
    private val rare = Profiler.registerFine("rare")

    /** One batch of [plans] plans, each followed by a cleanup. About 115 ms per plan. */
    private fun run(plans: Int): Profile {
        Profiler.start(strict = false)
        op(batch) {
            repeat(plans) {
                op(plan) {
                    repeat(340) { op(rule) { spinFor(200 * us) } }
                    repeat(100) { op(visit) { spinFor(200 * us) } }
                    repeat(50) { op(rare) { spinFor(200 * us) } }
                }
                op(cleanup) {
                    spinFor(5_000 * us)
                    repeat(10) { op(visit) { spinFor(1_000 * us) } }
                    op(rare) { spinFor(100) }
                }
            }
        }
        return Profiler.stop()
    }

    /** What each fine row under a coarse row should read: calls on that path, and per parent. */
    private fun expected(plans: Int) = mapOf(
        listOf("batch", "plan", "rule") to ("%,d".format(340 * plans) to "340"),
        listOf("batch", "plan", "visit") to ("%,d".format(100 * plans) to "100"),
        listOf("batch", "plan", "rare") to ("%,d".format(50 * plans) to "50"),
        listOf("batch", "cleanup", "visit") to ("%,d".format(10 * plans) to "10"),
        listOf("batch", "cleanup", "rare") to ("%,d".format(plans) to "1"),
    )

    /**
     * The fine rows of the coarse tree in [report], by path: the `Executions` and `Per parent` cells.
     * A coarse row's path comes from its indent; a fine row is `- name` under the row above it.
     */
    private fun fineRows(report: String): Map<List<String>, Pair<String, String?>> {
        val lines = report.lines()
        val start = lines.indexOfFirst { it.startsWith("Coarse operation") }
        if (start < 0) return emptyMap()
        val out = HashMap<List<String>, Pair<String, String?>>()
        val stack = ArrayList<String>()
        for (line in lines.drop(start + 2)) {
            if (line.isBlank() || line.startsWith("-")) break
            val indent = line.length - line.trimStart().length
            val cells = line.trim().split(Regex(" +"))
            if (cells[0] == "-") {
                if (cells[1] == "and") continue
                out[stack.take(indent / 2) + cells[1]] = cells[2] to cells.getOrNull(3)?.takeIf { it.matches(Regex("[0-9,.]+")) }
            } else {
                while (stack.size > indent / 2) stack.removeAt(stack.size - 1)
                stack.add(cells[0])
            }
        }
        return out
    }

    private fun check(name: String, plans: Int, report: String): List<String> {
        val rows = fineRows(report)
        return expected(plans).mapNotNull { (path, want) ->
            val got = rows[path]
            when {
                got == null -> "$name ${path.joinToString(" / ")}: no row, want ${want.first} ${want.second}"
                got != want -> "$name ${path.joinToString(" / ")}: ${got.first} ${got.second ?: ""}, want ${want.first} ${want.second}"
                else -> null
            }
        }
    }

    /** Two runs a side go through the diff over runs, which is not the diff of one run against one. */
    @Test
    fun `a diff over several runs shows the calls per execution of every fine row`() {
        val before = Files.createTempDirectory("calls-before")
        val after = Files.createTempDirectory("calls-after")
        repeat(2) { run(plans = 4).save(before) }
        repeat(2) { run(plans = 2).save(after) }
        val diff = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        println(diff)

        checkDiff(diff)
    }

    /** A diff of 4 plans a run against 2: each row in the report's form, the calls and their figure per parent. */
    private fun checkDiff(diff: String) {
        val rows = HashMap<Pair<String, String>, List<String>>()
        var coarse = ""
        for (line in diff.lines().takeWhile { !it.startsWith("COARSE AND FINE") }) {
            val cells = line.split("|").map { it.trim() }
            if (cells.size < 5 || cells[0] == "Coarse operation") continue
            val name = cells[0]
            if (!name.startsWith("- ")) coarse = name
            rows[coarse to name.removePrefix("- ")] = cells.takeLast(2)
        }
        val wanted = mapOf(
            ("plan" to "plan") to listOf("4 -> 2", "4 -> 2"), ("cleanup" to "cleanup") to listOf("4 -> 2", "4 -> 2"),
            ("plan" to "rule") to listOf("1,360 -> 680", "340 ="), ("plan" to "visit") to listOf("400 -> 200", "100 ="),
            ("plan" to "rare") to listOf("200 -> 100", "50 ="),
            ("cleanup" to "visit") to listOf("40 -> 20", "10 ="), ("cleanup" to "rare") to listOf("4 -> 2", "1 ="),
            ("cleanup" to "unlabelled") to listOf("own", ""),
        )
        for ((row, want) in wanted) assertEquals(want, rows[row], "$row in:\n$diff")
        assertTrue(diff.lines().any { it.contains("Per parent") }, diff)
        assertFalse(Regex("\\| *x[0-9]").containsMatchIn(diff), "the old form: $diff")
    }

    @Test
    fun `a diff of one run shows the same as the report`() {
        val before = run(plans = 4)
        val after = run(plans = 2)
        checkDiff(ProfileDiff(before, after).render())
    }

    @Test
    fun `calls per execution of the parent are exact on every path, whatever the run's size`() {
        val a = run(plans = 10)
        val b = run(plans = 2)
        val reportA = a.render()
        val reportB = b.render()

        val before = Files.createTempDirectory("calls-a")
        val after = Files.createTempDirectory("calls-b")
        a.save(before)
        b.save(after)
        val diff = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        println("=== A: 10 plans\n$reportA\n=== B: 2 plans\n$reportB\n=== diff A -> B\n$diff")

        val wrong = check("A", 10, reportA) + check("B", 2, reportB)
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }
}
