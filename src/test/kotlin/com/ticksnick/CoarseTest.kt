package com.ticksnick

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coarse tier's arithmetic, on the parts that have an answer independent of any measurement.
 *
 * Whether the spans agree with a stopwatch is a question for a real workload, a truth no unit test
 * can supply. What is here is the part that must hold before a single sample is taken: that the histogram brackets what it is
 * given, that a percentile is exact or within its precision, that nesting restores the right
 * parent, and that a leaked context is caught by the balance check rather than silently swallowing
 * a whole request's samples.
 */
class CoarseTest {

    // --- the histogram --------------------------------------------------------------

    @Test
    fun `every bucket brackets the values that land in it`() {
        // Every octave boundary, either side of it, plus the linear region at the bottom.
        val probes = buildList {
            addAll(0L..20L)
            for (shift in 3..40) {
                val base = 1L shl shift
                addAll(listOf(base - 1, base, base + 1, base + base / 3, 2 * base - 1))
            }
        }
        for (v in probes) {
            val b = SpanHistogram.bucketOf(v)
            assertTrue(b in 0 until SpanHistogram.BUCKETS, "$v landed outside the histogram at $b")
            assertTrue(
                SpanHistogram.lowerBound(b) <= v && v <= SpanHistogram.upperBound(b),
                "$v is not inside bucket $b = [${SpanHistogram.lowerBound(b)}, ${SpanHistogram.upperBound(b)}]"
            )
        }
    }

    @Test
    fun `buckets are ordered and do not overlap`() {
        for (b in 0 until SpanHistogram.BUCKETS - 1) {
            assertEquals(
                SpanHistogram.upperBound(b) + 1, SpanHistogram.lowerBound(b + 1),
                "bucket $b and ${b + 1} are not adjacent"
            )
        }
    }

    /**
     * Past [EXACT_SPANS] executions a percentile comes from the histogram, reported at the middle of
     * its bucket: off the truth by at most [SpanHistogram.PRECISION], either way.
     */
    @Test
    fun `a histogram percentile is within its precision of the truth`() {
        val values = (1..10_000).map { it.toLong() * 37 }
        val hist = LongArray(SpanHistogram.BUCKETS)
        for (v in values) hist[SpanHistogram.bucketOf(v)]++
        val sorted = values.sorted()
        for (p in listOf(0.5, 0.9, 0.99, 1.0)) {
            val exact = sorted[Math.ceil(p * sorted.size).toInt() - 1].toDouble()
            val got = SpanHistogram.percentile(hist, values.size.toLong(), p)
            assertTrue(
                abs(got / exact - 1) <= SpanHistogram.PRECISION + 1e-12,
                "p$p reported $got, more than ${SpanHistogram.PRECISION} off the true $exact"
            )
        }
    }

    /**
     * Three batches of about 30.5 s fall in one 12.5% bucket, where p50, p90 and p99 would all
     * print the slowest. Few executions are kept one by one, so the median of three is the middle
     * one, to the nanosecond.
     */
    @Test
    fun `few executions give exact percentiles`() {
        val batches = listOf(30_467_200_900L, 30_445_132_200L, 30_616_088_500L)
        val s = Spans.of(batches)
        assertTrue(s.isExact)
        assertEquals(30_467_200_900.0, s.percentile(0.50))
        assertEquals(30_616_088_500.0, s.percentile(1.0))
        val values = (1..EXACT_SPANS).map { it.toLong() * 977 }
        val all = Spans.of(values.shuffled(java.util.Random(7)))
        assertTrue(all.isExact, "still exact at $EXACT_SPANS executions")
        for (p in listOf(0.5, 0.9, 0.99)) {
            assertEquals(values[Math.ceil(p * values.size).toInt() - 1].toDouble(), all.percentile(p), "p$p")
        }
    }

    @Test
    fun `one execution past the exact ones switches to the histogram and keeps the count`() {
        val values = (1..EXACT_SPANS + 1).map { it.toLong() * 977 }
        val s = Spans.of(values)
        assertFalse(s.isExact)
        assertEquals(values.size.toLong(), s.count)
        val truth = values[Math.ceil(0.5 * values.size).toInt() - 1].toDouble()
        assertTrue(abs(s.percentile(0.5) / truth - 1) <= SpanHistogram.PRECISION + 1e-12)
    }

    @Test
    fun `merging exact durations past the limit switches to the histogram`() {
        val a = Spans.of((1..EXACT_SPANS).map { it.toLong() })
        val b = Spans.of(listOf(5L, 6L))
        b.addAll(a)
        assertFalse(b.isExact)
        assertEquals(EXACT_SPANS + 2L, b.count)
        val c = Spans.of(listOf(1L, 2L))
        c.addAll(Spans.of(listOf(3L)))
        assertTrue(c.isExact)
        assertEquals(2.0, c.percentile(0.5))
    }

    /** A p99 of three batches is the slowest batch under another name; printed as `-`. */
    @Test
    fun `a percentile needs one execution in 1 over 1 minus p above it`() {
        fun statOf(values: List<Long>) = CoarseStat(
            0, "req", values.size.toLong(), values.sum(), values.min(), values.max(), Spans.of(values),
            0, 0, 0, 0, 0, 0, LongArray(MAX_OPERATIONS + 1),
        )
        val one = statOf(listOf(7L))
        assertTrue(one.percentileNanos(0.50).isNaN(), "a p50 of one execution")
        val nine = statOf((1L..9L).toList())
        assertEquals(5.0, nine.percentileNanos(0.50))
        assertTrue(nine.percentileNanos(0.90).isNaN(), "a p90 of nine executions")
        val ten = statOf((1L..10L).toList())
        assertEquals(9.0, ten.percentileNanos(0.90))
        assertTrue(ten.percentileNanos(0.99).isNaN(), "a p99 of ten executions")
        assertEquals(99.0, statOf((1L..100L).toList()).percentileNanos(0.99))
    }

    /** The middle of a bucket can pass the exact extremes when every execution sits at one edge. */
    @Test
    fun `a percentile is never reported outside the measured minimum and maximum`() {
        val v = 713_200L
        val values = List(EXACT_SPANS + 1) { v }
        val c = CoarseStat(
            0, "req", values.size.toLong(), values.sum(), v, v, Spans.of(values),
            0, 0, 0, 0, 0, 0, LongArray(MAX_OPERATIONS + 1),
        )
        assertTrue(
            SpanHistogram.middle(SpanHistogram.bucketOf(v)) != v.toDouble(),
            "the bucket's middle is the value, so this test is no longer testing anything"
        )
        for (p in listOf(0.50, 0.90, 0.99, 1.0)) assertEquals(v.toDouble(), c.percentileNanos(p), "p$p")
    }

    @Test
    fun `nothing recorded has no percentile rather than a wrong one`() {
        assertTrue(SpanHistogram.percentile(LongArray(SpanHistogram.BUCKETS), 0, 0.5).isNaN())
        assertTrue(Spans().percentile(0.5).isNaN())
    }

    // --- contexts -------------------------------------------------------------------

    @Test
    fun `nesting restores the parent and not the empty slot`() {
        val outer = Profiler.registerCoarse("test-outer")
        val inner = Profiler.registerCoarse("test-inner")
        val slot = Profiler.slot()
        assertNull(slot.contextOpaque())
        op(outer) {
            val a = assertNotNull(slot.contextOpaque())
            assertEquals(outer.id, a.type)
            assertEquals(0, a.depth)
            op(inner) {
                val b = assertNotNull(slot.contextOpaque())
                assertEquals(inner.id, b.type)
                assertEquals(1, b.depth)
                assertEquals(a, b.parent)
            }
            // Restored to the parent, not cleared. Clearing is the bug that would bill the rest of
            // the outer operation to nothing at all.
            assertEquals(a, slot.contextOpaque())
        }
        assertNull(slot.contextOpaque())
    }

    @Test
    fun `a body that throws still closes the block form`() {
        val t = Profiler.registerCoarse("test-throwing")
        val slot = Profiler.slot()
        runCatching { op<Unit>(t) { throw IllegalStateException("boom") } }
        assertNull(slot.contextOpaque(), "the finally did not restore the slot")
    }

    /**
     * The hazard the non-lexical form carries, and the check that exists for it. A coarse context
     * left open is worse than a fine label left open by exactly the amount a request is bigger than
     * an operation: every sample the thread takes afterwards is billed to it.
     */
    @Test
    fun `expectBalanced catches a leaked context and closes it`() {
        val t = Profiler.registerCoarse("test-leaked")
        val slot = Profiler.slot()
        Profiler.enter(t)
        assertNotNull(slot.contextOpaque())
        assertTrue(!Profiler.expectBalanced(), "a leaked context reported as balanced")
        assertNull(slot.contextOpaque(), "the leak was reported but not stopped")
        assertTrue(Profiler.expectBalanced(), "the reset did not take")
    }

    @Test
    fun `an unmatched exit is reported rather than a crash`() {
        // An exit with nothing to close names what the caller tried to close and counts an
        // imbalance - not an exception, because a measurement problem must not become a crash in
        // somebody else's process.
        val t = Profiler.registerCoarse("test-unmatched")
        val slot = Profiler.slot()
        assertNull(slot.contextOpaque())
        Profiler.exit(t)
        assertNull(slot.contextOpaque())
        assertTrue(Profiler.expectBalanced())
    }

    // --- what the report works out from it -------------------------------------------

    // --- `working` against what the machine actually gave it ------------------------
    //
    // `working` is built on thread state, and Java reports a thread stopped inside a native call as
    // RUNNABLE, so the column can read many times more CPU than the process spent. These pin the
    // bound on it, in both directions — because the direction that costs more is the false
    // accusation, and the slack that prevents it is not obvious.

    @Test
    fun `working is bounded by the CPU the machine was measured to give it`() {
        // Eight threads stopped in a socket read, reported as 2.83 working,
        // on a process the operating system says was on a CPU 1.02% of the time.
        val c = stat(
            count = 860, sumNanos = 860 * 17_440_000, hits = 3_830, running = 2_830,
            instanceTicks = 1_000, activeTicks = 1_000,
        )
        val r = report(listOf(c), labelledDuty = 0.0102)
        assertEquals(3.83, c.inside, 1e-9)
        assertEquals(2.83, c.working, 1e-9)
        assertTrue(r.workingIsContradicted(c), "a column claiming 2.83 threads on a 1% duty cycle was believed")
        assertTrue(r.workingCeilingOf(c) < 0.05, "the ceiling should be about 0.04, was ${r.workingCeilingOf(c)}")
    }

    /**
     * Why the slack exists, and it is load-bearing rather than caution.
     *
     * `inside` 6.64 and `working` 6.40 against a labelled duty cycle of 96.40% puts the ceiling at
     * **exactly 6.40**. A strict test — anything above the ceiling is a fault — would accuse an
     * entirely correct run of the native-call defect above. The ceiling is a run-wide figure
     * applied to one operation, and one operation may honestly be more CPU-bound than the run
     * around it.
     */
    @Test
    fun `a CPU-bound operation sitting on its ceiling is not accused`() {
        val c = stat(
            count = 3_663, sumNanos = 3_663 * 4_090_000, hits = 6_640, running = 6_400,
            instanceTicks = 1_000, activeTicks = 1_000,
        )
        val r = report(listOf(c), labelledDuty = 0.9640)
        // 6.64 x 0.9640 = 6.40096 — the point is that `working` of 6.40 sits *on* that, not that
        // the two are bit-identical.
        assertEquals(6.40, r.workingCeilingOf(c), 0.01)
        assertTrue(c.working <= r.workingCeilingOf(c) * Profile.WORKING_CEILING_SLACK)
        assertTrue(!r.workingIsContradicted(c), "an operation at its ceiling was accused")
    }

    @Test
    fun `the slack is a factor, and it decides on both sides of itself`() {
        // Expressed against the constant rather than against its value: the value is generous,
        // because a program with no native call in it can honestly read twice its ceiling, and a
        // test pinning the value fails on every tuning of it while telling you nothing about the
        // behaviour.
        val duty = 0.1
        fun contradictedAt(working: Double): Boolean {
            val c = stat(
                count = 100, sumNanos = 100_000_000, hits = 1_000, running = (working * 1_000).toLong(),
                instanceTicks = 1_000, activeTicks = 1_000,
            )
            return report(listOf(c), labelledDuty = duty).workingIsContradicted(c)
        }
        // inside is 1.0 here, so the ceiling is the duty and the threshold is duty x slack.
        val threshold = duty * Profile.WORKING_CEILING_SLACK
        assertTrue(!contradictedAt(threshold * 0.9), "just inside the slack and was accused")
        assertTrue(contradictedAt(threshold * 1.1), "just outside the slack and was believed")
    }

    @Test
    fun `no duty cycle means no accusation`() {
        // A run too short for a window says so rather than inventing a ceiling. Silence is the only
        // honest answer: without the operating system's figure there is nothing to contradict.
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 1_000, running = 1_000, instanceTicks = 100)
        val r = report(listOf(c), labelledDuty = 0.0, dutyAvailable = false)
        assertTrue(r.workingCeilingOf(c).isNaN())
        assertTrue(!r.workingIsContradicted(c), "an unavailable duty cycle was read as a contradiction")
    }

    @Test
    fun `the report names the operation whose working cannot be supported`() {
        val bad = stat(
            count = 100, sumNanos = 100_000_000, hits = 4_000, running = 3_000,
            instanceTicks = 1_000, activeTicks = 1_000, name = "query",
        )
        val text = report(listOf(bad), labelledDuty = 0.01).render()
        assertTrue("READS HIGHER THAN THE MEASURED TIME ON CPU" in text, "the warning did not appear")
        assertTrue("query" in text, "the warning did not name the operation")
        // The block carries both readings: the figure, and what the measured time on CPU allows.
        assertTrue(
            Regex("""on a CPU 3\.00, but the measured time on CPU bounds it at 0\.0\d""").containsMatchIn(text),
            "the warning did not print value over ceiling"
        )
    }

    @Test
    fun `a report with nothing contradicted says nothing about it`() {
        val ok = stat(count = 100, sumNanos = 100_000_000, hits = 1_000, running = 990, instanceTicks = 1_000)
        val text = report(listOf(ok), labelledDuty = 0.99).render()
        assertTrue("READS HIGHER THAN THE MEASURED CPU DUTY" !in text, "a clean run was warned about")
    }

    // --- the stale share, and its denominator ---------------------------------------

    /**
     * Numerator and denominator count the same population, coarse thread-time. A stale entry
     * counted per coarse *type* on the chain, over only the samples inside a *fine* label, is two
     * different populations: the ratio is not a share of anything and can pass 100%.
     */
    @Test
    fun `the stale share is of coarse thread-time and cannot exceed all of it`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 0, running = 0, instanceTicks = 1, staleHits = 997)
        val r = report(listOf(c), labelled = 975, staleContextHits = 997, coarseSampleHits = 1_000)
        assertEquals(0.997, r.staleContextShare, 1e-9)
        assertTrue(r.staleContextShare <= 1.0, "a share of thread-time exceeded all of it")
    }

    @Test
    fun `no coarse samples is a stale share of zero rather than a division by zero`() {
        val r = report(emptyList(), staleContextHits = 0, coarseSampleHits = 0)
        assertEquals(0.0, r.staleContextShare)
    }

    // --- the legend, which is four lines unless you ask -----------------------------

    @Test
    fun `the default legend is the traps and nothing else`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 100, instanceTicks = 100)
        val text = report(listOf(c)).render()
        assertTrue("NOT CPU" in text, "the traps are not printed")
        assertTrue("full notes: docs/output.md" in text, "there is no way to find the rest")
        // The reference half is the long part, and it is in output.md.
        assertTrue("noise is 1/sqrt(hits)" !in text, "the full legend printed by default")
        // The legend section itself.
        val legendLines = text.lines().dropWhile { !it.startsWith("HOW TO READ THIS") }
            .filter { it.isNotBlank() && !it.startsWith("=") }   // the report's closing rule is not legend
        assertTrue(legendLines.size <= 7, "the default legend is ${legendLines.size} lines, not a handful")
    }

    @Test
    fun `asking for the legend gets the whole thing`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 100, instanceTicks = 100)
        val text = report(listOf(c)).render(legend = true)
        assertTrue("NOT CPU" in text, "the traps went missing")
        assertTrue("noise is 1/sqrt(hits)" in text, "the full legend was not printed")
        assertTrue("create work for everything else" in text, "the counterfactual argument was not printed")
        assertTrue("the coarse table is a tree" in text, "the coarse half was not printed")
    }

    private fun stat(
        count: Long, sumNanos: Long, hits: Long, running: Long,
        inclusive: Long = hits, runningInclusive: Long = running,
        instanceTicks: Long = inclusive, activeTicks: Long = instanceTicks,
        staleHits: Long = 0, name: String = "req", id: Int = 0,
    ) = CoarseStat(
        id, name, count, sumNanos, 0, 0, Spans(),
        hits, running, inclusive, runningInclusive, instanceTicks, activeTicks,
        LongArray(MAX_OPERATIONS + 1), staleHits,
    )

    /** A node of the coarse tree with the same figures as [stat], and [children] under it. */
    private fun node(
        c: CoarseStat, paused: Long = 0, fine: LongArray = LongArray(MAX_OPERATIONS + 1),
        children: List<CoarseNode> = emptyList(),
        count: Long = c.count, sumNanos: Long = c.spanSumNanos, inclusive: Long = c.inclusiveHits,
        instanceTicks: Long = 0, coveredTicks: Long = 0,
    ) = CoarseNode(
        c.type, c.name, count, sumNanos, c.spanMinNanos, c.spanMaxNanos, Spans(),
        inclusive, inclusive, fine, paused, children,
        instanceTicks = instanceTicks, coveredTicks = coveredTicks,
    )

    /**
     * 200 requests, each forking 4 branches that run 3.4 at a time, each branch working through 3
     * leaves one after another. A leaf's wall is divided by the branches' 3.4 as well as by its own
     * 1, so it is 70% of a branch - what a branch spends in leaves - and not the 90% that dividing by
     * how many leaves were open anywhere would give.
     */
    @Test
    fun `wall divides by how many ran at a time under the same parent, at the row and above`() {
        val req = stat(count = 200, sumNanos = 4_000_000_000, hits = 1, running = 1, name = "request", id = 0)
        val br = stat(count = 800, sumNanos = 12_000_000_000, hits = 1, running = 1, name = "branch", id = 1)
        val lf = stat(count = 2_400, sumNanos = 8_400_000_000, hits = 1, running = 1, name = "leaf", id = 2)
        val leaf = node(lf, instanceTicks = 1_000, coveredTicks = 1_000)
        val branch = node(br, children = listOf(leaf), instanceTicks = 3_400, coveredTicks = 1_000)
        val request = node(req, children = listOf(branch), instanceTicks = 1_000, coveredTicks = 1_000)
        val r = report(listOf(req, br, lf), tree = listOf(request))
        val walls = r.wallsOf()
        assertEquals(4.0e9, walls.getValue(request), 1.0)
        assertEquals(12e9 / 3.4, walls.getValue(branch), 1.0)
        assertEquals(8.4e9 / 3.4, walls.getValue(leaf), 1.0)
        val text = r.render()
        assertTrue(Regex("""branch\s+800\s+x4\.0\s+3\.4\s+3\.53 s\s+88%""").containsMatchIn(text), text)
        assertTrue(Regex("""leaf\s+2,400\s+x3\.0\s+2\.47 s\s+70%""").containsMatchIn(text), text)
    }

    /**
     * A report with a 1 ms step, so that a hit is a millisecond and the arithmetic in these tests
     * can be done in one's head. [labelled] sets the denominator every coarse share is taken over.
     */
    private fun report(
        coarse: List<CoarseStat>,
        labelled: Long = 1_000_000,
        labelledDuty: Double = 0.99,
        dutyAvailable: Boolean = true,
        staleContextHits: Long = 0,
        coarseSampleHits: Long = 0,
        /** The tree; by default every operation is a root with no children. */
        tree: List<CoarseNode> = coarse.map { node(it) },
    ) = Profile(
        operations = listOf(OperationStat(0, "fine", labelled, labelled, 0, 0, 0, 0, labelled)),
        idleHits = 0,
        ticks = 1_000_001,
        samplingSpanNanos = 1_000_000_000_000,
        durationNanos = 1_000_000_000_000,
        threads = 8,
        duty = DutyReport(
            labelledDuty = labelledDuty, invisibleOffCpu = 0.0, labelledFraction = 1.0,
            reason = if (dutyAvailable) null else "no window completed",
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000,
            windows = if (dutyAvailable) 20 else 0, threads = 8,
            cpuNanos = (labelledDuty * 1_000_000_000).toLong(), wallNanos = 1_000_000_000,
            minWindowDuty = labelledDuty, maxWindowDuty = labelledDuty,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = coarse,
        staleContextHits = staleContextHits,
        coarseSampleHits = coarseSampleHits,
        coarseTree = tree,
    )

    @Test
    fun `inside is one when every occupied execution has one thread in it`() {
        // What a same-thread run must produce, by construction: instanceTicks moves in lockstep with
        // hits because a context never leaves the thread that made it. With propagation this is the
        // number that moves, so pinning it here keeps the same-thread case honest.
        val c = stat(count = 100, sumNanos = 1_000_000, hits = 400, running = 400)
        assertEquals(1.0, c.inside)
    }

    @Test
    fun `inside and in flight factorise the threads inside`() {
        // 8 threads inside, as two executions of four threads each — the case the fine tier cannot
        // tell from eight serial ones. inclusive / activeTicks = 8 = inFlight x inside.
        //
        // The identity is on `inside` and not on `working`, and that is why the pair exists: inFlight
        // counts an execution whether or not its threads are running, so only the half that counts
        // threads the same way can factorise it.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 800, running = 800,
            instanceTicks = 200, activeTicks = 100,
        )
        assertEquals(4.0, c.inside)
        assertEquals(2.0, c.inFlight)
        assertEquals(
            c.inclusiveHits.toDouble() / c.activeTicks, c.inFlight * c.inside,
            "the identity threads = in flight x inside does not hold"
        )
    }

    @Test
    fun `working excludes the threads that were parked`() {
        // Five threads in the execution, four of them on a CPU — one driver fanning work out to four
        // helpers and then blocking on the join. Both numbers are wanted: five is what the request
        // ties up, four is what splitting it bought.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 500, running = 400,
            instanceTicks = 100, activeTicks = 100,
        )
        assertEquals(5.0, c.inside)
        assertEquals(4.0, c.working)
    }

    @Test
    fun `working is inside less the waiting share`() {
        // The relation the report's legend states, so a reader who does the arithmetic by hand gets
        // the printed number back. It is also why the two columns sit either side of `waiting`.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 800, running = 600,
            instanceTicks = 200, activeTicks = 100,
        )
        val waiting = c.waitingHits.toDouble() / c.inclusiveHits
        assertEquals(c.working, c.inside * (1 - waiting), 1e-12)
    }

    // --- the tier boundary, checked rather than documented ---------------------------

    @Test
    fun `the floor is flat below twenty percent share and rises above it`() {
        // The share term only binds above ~20%, which is why the flat 800 ns is the rule in
        // practice and the share term is the exception.
        assertEquals(800.0, coarseFloorNanos(0.01))
        assertEquals(800.0, coarseFloorNanos(0.20))
        assertEquals(2000.0, coarseFloorNanos(0.50))
        assertEquals(4000.0, coarseFloorNanos(1.0))
    }

    @Test
    fun `a coarse label on a 200 ns operation is reported`() {
        val r = report(coarse = listOf(stat(count = 1_000_000, sumNanos = 200_000_000, hits = 200, running = 200)))
        assertEquals(listOf("req"), r.coarseTooSmall().map { it.name })
        val msg = coarseTooSmallMessage("req", 1_000_000, 200.0, r.coarseFloorNanosOf(r.coarse[0]))
        // "200 ns" and not "200.0 ns": durations print to three significant digits, so the fourth
        // one - which for a sampled or bucketed number is noise - never reaches the page.
        assertTrue(msg.contains("200 ns"), "the message does not name the measured duration: $msg")
        assertTrue(msg.contains("registerFine(\"req\")"), "the message does not say what to do instead: $msg")
        assertTrue(msg.lines().size == 2, "two lines - the fact and the fix, then the reason: $msg")
    }

    @Test
    fun `a legitimate coarse label is not accused`() {
        // A millisecond apiece at a 0.1% share. Nothing here is borderline and the check must stay
        // silent — a floor check that cries wolf is worse than none.
        val r = report(coarse = listOf(stat(count = 1_000, sumNanos = 1_000_000_000, hits = 1_000, running = 1_000)))
        assertTrue(r.coarseTooSmall().isEmpty())
    }

    /**
     * The share term is the only sampled input, and it is taken a standard error low before it can
     * accuse. An operation sitting just the wrong side of the line must not be told off on the
     * strength of counting noise — this check prints an accusation, and manufacturing one is the
     * failure this project spends its effort on.
     */
    @Test
    fun `the share condition does not accuse on counting noise alone`() {
        // 2.5 us apiece, 25 hits out of 36 labelled: a 69.4% share, so the raw rule would demand
        // 2778 ns and accuse. 25 hits is a 20% standard error, and at 55.6% the rule demands 2222 ns
        // and lets it go. The operation is not exonerated — its share is simply not established.
        val noisy = stat(count = 400, sumNanos = 1_000_000, hits = 25, running = 25)
        assertTrue(
            report(coarse = listOf(noisy), labelled = 36).coarseTooSmall().isEmpty(),
            "accused an operation whose share is not established"
        )
        // The same operation with the same share, measured a thousand times over, is accused —
        // it is the uncertainty that spared it, not the share being innocent.
        val settled = stat(count = 400_000, sumNanos = 1_000_000_000, hits = 25_000, running = 25_000)
        assertEquals(
            listOf("req"), report(coarse = listOf(settled), labelled = 36_000).coarseTooSmall().map { it.name }
        )
    }

    // --- work escaping its context ---------------------------------------------------

    /**
     * A fan-out, in miniature: a coarse span on the caller, and most of the labelled work
     * happening on pool threads that never saw the context. The span's own busy time is a fraction
     * of it and the rest reads as waiting — silent, and in the contaminating direction, which is why
     * this counter exists at all.
     */
    @Test
    fun `labelled work outside every span is measured and its operations named`() {
        val fanned = OperationStat(0, "fine", 1000, 1000, 0, 0, 0, 0, 1000)
        val r = Profile(
            operations = listOf(fanned),
            idleHits = 0, ticks = 1_000_001, samplingSpanNanos = 1_000_000_000_000,
            durationNanos = 1_000_000_000_000, threads = 8,
            duty = DutyReport(
                labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
                resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 20, threads = 8,
                cpuNanos = 990_000_000, wallNanos = 1_000_000_000,
                minWindowDuty = 0.99, maxWindowDuty = 0.99, anomalies = 0, maxSampleNanos = 200_000,
            ),
            // 250 of the operation's 1000 samples fell under the span; 750 did not.
            coarse = listOf(
                stat(count = 100, sumNanos = 100_000_000, hits = 250, running = 250)
                    .also { it.fine[0] = 250 }
            ),
            labelledOutsideCoarse = 750,
        )
        assertEquals(0.75, r.labelledOutsideCoarseShare)
        assertEquals(listOf("fine"), r.outsideCoarseSuspects().map { it.name })
        val text = r.render()
        assertTrue(text.contains("NO coarse span"), "the report does not mention it")
        assertTrue(text.contains("ESCAPING"), "the report does not name the dangerous reading")
    }

    @Test
    fun `an operation fully covered by a span is not named`() {
        val covered = OperationStat(0, "fine", 250, 250, 0, 0, 0, 0, 250)
        val r = report(
            coarse = listOf(
                stat(count = 100, sumNanos = 100_000_000, hits = 250, running = 250)
                    .also { it.fine[0] = 250 }
            ),
        )
        // Hits come from `report`'s own filler operation, so the covered one is checked directly:
        // naming an operation whose samples are all accounted for would be a false accusation.
        assertTrue(covered.hits <= r.coarse[0].fine[0])
    }

    /**
     * A span whose every sample fell inside a child label has no own time. It is not a row of
     * zeros and it is not "never sampled": it is one line saying its time was in the operations
     * above. For example `query` inside `runBatch` inside `run`.
     */
    @Test
    fun `a coarse operation with no own time is one line under the table, not a zero row`() {
        val batch = stat(count = 2, sumNanos = 2_000_000_000, hits = 0, running = 0, inclusive = 2000, runningInclusive = 2000, name = "runBatch")
        val text = report(listOf(batch), labelled = 2000).render()
        assertTrue(text.lines().none { it.startsWith("runBatch *") }, "a zero row was printed: $text")
        assertTrue("runBatch *: coarse, with no own time - every sample inside it was in an operation above" in text, text)
        assertTrue("* a coarse operation" in text, "the star is used and not explained")
        assertFalse("never sampled" in text, "a sampled span was folded away as never sampled")
    }

    /**
     * The table is the tree: `query` nested in `batch` nested in `run`, each taking all of the
     * time of the one around it. Three rows, indented, each once, the fine steps under the leaf
     * only, the pauses on the rows they were charged to.
     */
    @Test
    fun `nested coarse operations are one table drawn as a tree with the fine steps on the leaves`() {
        val run = stat(count = 1, sumNanos = 3_000_000_000, hits = 0, running = 0, inclusive = 3000, runningInclusive = 3000, name = "run", id = 0)
        val batch = stat(count = 1, sumNanos = 3_000_000_000, hits = 0, running = 0, inclusive = 3000, runningInclusive = 3000, name = "batch", id = 1)
        val query = stat(count = 3, sumNanos = 3_000_000_000, hits = 0, running = 0, inclusive = 3000, runningInclusive = 3000, name = "query", id = 2)
        val queryFine = LongArray(MAX_OPERATIONS + 1).also { it[0] = 3000 }   // every sample under query was inside the one fine operation
        val tree = listOf(node(run, paused = 200_000_000, children = listOf(node(batch, paused = 200_000_000, children = listOf(node(query, paused = 200_000_000, fine = queryFine))))))
        val text = report(listOf(run, batch, query), labelled = 3000, tree = tree).render()
        val lines = text.lines()
        val iRun = lines.indexOfFirst { it.startsWith("run ") }
        val iBatch = lines.indexOfFirst { it.startsWith("  batch ") }
        val iQuery = lines.indexOfFirst { it.startsWith("    query ") }
        assertTrue(iRun in 0 until iBatch && iBatch < iQuery, "the tree is not drawn in order: $text")
        assertEquals(1, lines.count { it.trim().startsWith("query ") }, "an operation is printed more than once: $text")
        val fineRow = lines[iQuery + 1].split(Regex(" +")).filter { it.isNotEmpty() }
        assertEquals(listOf("-", "fine", "own", "3.00", "s", "100%"), fineRow, "the leaf carries no split row: ${lines[iQuery + 1]}")
        assertTrue(text.contains("- rows split a leaf's time by the innermost label"), "the split rows have no key: $text")
        assertFalse(lines[iQuery].trim().startsWith("fine "), "the fine row is not under its leaf")
        assertTrue(lines[iBatch + 1].trim().startsWith("query "), "a parent repeats its children's breakdown: ${lines[iBatch + 1]}")
        assertTrue("200 ms" in lines[iQuery], "the pause charged to the row is not on it: ${lines[iQuery]}")
        assertFalse(" was: " in text, "the flat lines are still printed")
        assertFalse("all callers together" in text, "a one-caller tree got the shared-operation block")
    }

    /**
     * Each child row says how many times it ran per execution of its parent and what part of the
     * parent's total it is; a root has neither, and a child run once per parent leaves the loop
     * count blank.
     */
    @Test
    fun `a child row carries its executions per parent and its share of the parent`() {
        val run = stat(count = 1, sumNanos = 4_000_000_000, hits = 0, running = 0, inclusive = 4000, runningInclusive = 4000, name = "run", id = 0)
        val resolve = stat(count = 12, sumNanos = 4_000_000_000, hits = 0, running = 0, inclusive = 4000, runningInclusive = 4000, name = "resolve", id = 1)
        val select = stat(count = 12, sumNanos = 2_400_000_000, hits = 0, running = 0, inclusive = 2400, runningInclusive = 2400, name = "select", id = 2)
        val via = stat(count = 36, sumNanos = 1_200_000_000, hits = 0, running = 0, inclusive = 1200, runningInclusive = 1200, name = "via", id = 3)
        val tree = listOf(node(run, children = listOf(node(resolve, children = listOf(node(select), node(via))))))
        val lines = report(listOf(run, resolve, select, via), labelled = 4000, tree = tree).render().lines()
        val header = lines.first { it.startsWith("Coarse operation") }
        val xAt = header.indexOf("x parent")
        val shareAt = header.indexOf("% parent")
        assertTrue(xAt > 0 && shareAt > 0, "the columns have no header: $header")
        fun cells(prefix: String): Pair<String, String> =
            lines.first { it.startsWith(prefix) }.let { it.substring(xAt, xAt + 8).trim() to it.substring(shareAt, shareAt + 8).trim() }
        assertEquals("" to "", cells("run "), "a root has no parent")
        assertEquals("x12.0" to "100%", cells("  resolve "))
        assertEquals("" to "60%", cells("    select "), "once per resolve is not a loop count")
        assertEquals("x3.0" to "30%", cells("    via "))
    }

    /** An operation under two callers is a row under each and, once, the figure over both. */
    @Test
    fun `an operation with two callers is drawn under each and summed once`() {
        val load = stat(count = 1, sumNanos = 1_000_000_000, hits = 0, running = 0, inclusive = 1000, runningInclusive = 1000, name = "load", id = 0)
        val query = stat(count = 1, sumNanos = 2_000_000_000, hits = 0, running = 0, inclusive = 2000, runningInclusive = 2000, name = "query", id = 1)
        val parse = stat(count = 2, sumNanos = 3_000_000_000, hits = 0, running = 0, inclusive = 3000, runningInclusive = 3000, name = "parse", id = 2)
        val tree = listOf(
            node(query, children = listOf(node(parse, count = 1, sumNanos = 2_000_000_000, inclusive = 2000))),
            node(load, children = listOf(node(parse, count = 1, sumNanos = 1_000_000_000, inclusive = 1000))),
        )
        val text = report(listOf(load, query, parse), labelled = 3000, tree = tree).render()
        val rows = text.substringAfter("COARSE OPERATIONS").substringBefore("COARSE AND FINE OPERATIONS").lines().filter { it.trim().startsWith("parse ") }
        assertEquals(3, rows.size, "parse should be under each caller and once over both: $text")
        assertTrue("all callers together" in text, "the figure over both callers is missing: $text")
        assertTrue(rows.any { "2.00 s" in it } && rows.any { "1.00 s" in it }, "the per-caller figures are not per caller: $rows")
    }

    @Test
    fun `nothing is said when no coarse label was placed`() {
        val r = report(coarse = emptyList())
        assertTrue(r.outsideCoarseSuspects().isEmpty())
        assertTrue(!r.render().contains("NO coarse span"))
    }

    @Test
    fun `waiting is what the span has and the samples do not`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 40)
        assertEquals(60L, c.waitingHits)
        assertEquals(1_000_000.0, c.meanSpanNanos)
    }
}
