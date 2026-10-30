package com.ticksnick

import java.nio.file.Files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the report works out from what the sampler collected, and what it promises to say about it.
 *
 * The rendering tests assert **invariants, not wording**. A golden file would be the obvious thing
 * and it would be wrong here: the wording of this report is edited often and on purpose, and a
 * test that fails on every rewrite teaches people to regenerate it without reading. What must not change is that a below-floor label always produces a
 * warning, that a bound which found nothing never prints a number, and that a stopped session
 * cannot render as though it were a result.
 */
class RunTest {


    /**
     * Everything the report can print is ASCII.
     *
     * Not a style rule. A Windows console does not run in UTF-8 by default, and an em-dash arrives
     * there as `?` in a black diamond:
     *
     * ```
     * CPU duty cycle: unavailable ? no window completed
     * nothing was sampled ? 4 ticks is too few
     * ```
     *
     * It is not cosmetic: the mangled character always lands exactly where a sentence explains
     * *why*, because that is what a dash is for.
     *
     * The library cannot fix this at the other end — [Run.render] returns a `String` and somebody
     * else decides the encoding it is printed with — so the only reliable answer is to emit nothing
     * that needs an encoding.
     */
    @Test
    fun `every line the report can print is ASCII`() {
        val offenders = mutableListOf<String>()
        fun check(what: String, text: String) {
            for (line in text.lines()) {
                val bad = line.filter { it.code > 127 }
                if (bad.isNotEmpty()) offenders += "$what: [$bad] in: ${line.trim().take(70)}"
            }
        }
        // The message builders, each of which is a path the render only reaches under a fault.
        check("tooSmall", tooSmallMessage("op", 1_000, 25.0))
        check("coarseTooSmall", coarseTooSmallMessage("req", 100, 200.0, 800.0, 40.0))
        check("leak", leakMessage("op", "worker-1"))
        check("mismatch", mismatchMessage("a", "b"))
        check("stale", staleContextMessage("req", 997, 0.42))
        // And a report carrying as many of the optional blocks at once as one can.
        check("render", loadedReport().render())
        check("render(legend)", loadedReport().render(legend = true))
        assertTrue(
            offenders.isEmpty(),
            "non-ASCII in printed output:\n" + offenders.joinToString("\n")
        )
    }

    /** A report with the warnings, the duty block and both tables all present at once. */
    private fun loadedReport(): Run {
        val fine = OperationStat(0, "work", 100, 1_000_000, 60, 3, 20, 10, 100)
        val c = CoarseStat(
            0, "request", 10, 10_000_000, 100, 2_000_000, Spans(),
            80, 40, 80, 40, 40, 40, LongArray(MAX_OPERATIONS + 1) { if (it == 0) 80 else 0 }, 5,
        )
        return Run(
            operations = listOf(fine), idleHits = 50, ticks = 1_000,
            samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 4,
            duty = Duty(
                labelledDuty = 0.02, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
                resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 4,
                cpuNanos = 20_000_000, wallNanos = 1_000_000_000,
                minWindowDuty = 0.02, maxWindowDuty = 0.02, anomalies = 1, maxSampleNanos = 200_000,
            ),
            failure = "something went wrong", imbalances = 2, openAtEnd = 1,
            untrackedSlots = 3, reclaimedSlots = 4, idleWaitingHits = 5,
            coarse = listOf(c), openContextsAtEnd = 1, labelledOutsideCoarse = 60,
            staleContextHits = 20, coarseSampleHits = 100,
            pause = PauseReport(true, 90_000_000, 3, 60_000_000, gcNanos = 10_000_000),
        )
    }

    private fun stat(
        name: String, hits: Long, calls: Long,
        stuck: Long = 0, instances: Long = 0, waiting: Long = 0, active: Long = hits,
    ) = OperationStat(0, name, hits, calls, stuck, instances, waiting, 0, active)

    /** A duty report with no live measurement behind it, for the arithmetic that hangs off one. */
    private fun duty(
        labelled: Double = 0.99,
        aggregate: Double = 0.99,
        invisible: Double = 0.0,
        labelledFraction: Double = 1.0,
        reason: String? = null,
    ) = Duty(
        labelledDuty = labelled,
        invisibleOffCpu = invisible,
        labelledFraction = labelledFraction,
        reason = reason,
        resolutionNanos = 15_625_000,
        windowNanos = 1_000_000_000,
        windows = 20,
        threads = 8,
        cpuNanos = (aggregate * 1e9).toLong(),
        wallNanos = 1_000_000_000,
        minWindowDuty = aggregate,
        maxWindowDuty = aggregate,
        anomalies = 0,
        maxSampleNanos = 200_000,
    )

    private fun report(
        ops: List<OperationStat>,
        idle: Long = 0,
        idleWaiting: Long = 0,
        state: Boolean = true,
        d: Duty = duty(),
        failure: String? = null,
    ) = Run(
        operations = ops,
        idleHits = idle,
        ticks = 20_000,
        samplingSpanNanos = 20_000_000_000,
        durationNanos = 20_000_000_000,
        threads = 8,
        duty = d,
        failure = failure,
        stateSampled = state,
        idleWaitingHits = idleWaiting,
    )

    // ---------------------------------------------------------------- shares and coverage

    /**
     * Shares are over every sample, so a share is a share of active time.
     *
     * Over labelled samples only, a share would stay still when idle time changed and every row
     * would move whenever a label was added. Unlabelled time is a row of its own,
     * `(no operation)`, and the rows add up to the run.
     */
    @Test
    fun `a share is of every sample, and unlabelled time is the rest`() {
        val ops = listOf(stat("a", hits = 300, calls = 1000), stat("b", hits = 100, calls = 1000))
        val quiet = report(ops, idle = 0)
        val noisy = report(ops, idle = 400)
        assertEquals(0.75, quiet.shareOf(ops[0]), 1e-9)
        assertEquals(0.375, noisy.shareOf(ops[0]), 1e-9, "a share must be of every sample")
        val text = noisy.render()
        assertTrue(text.lines().any { it.startsWith("(no operation)") && "50.0%" in it }, text)
        assertFalse(quiet.render().contains("(no operation)"), "a row for nothing")
    }

    /**
     * Coverage over runnable occupancy restricts **both** sides.
     *
     * On a thread pool most unlabelled samples can be a thread that was not runnable - 79% of them
     * on a search workload - which turns "the labels cover half the run" into something quite
     * different. Taking it off only the denominator would be the same mismatch of denominators
     * pointing the other way, because a label can be held across a wait — a locked update can spend
     * 73% of itself parked.
     */
    @Test
    fun `runnable coverage restricts both sides`() {
        // 600 labelled of which 100 parked; 400 unlabelled of which 300 parked.
        val ops = listOf(stat("a", hits = 600, calls = 1000, waiting = 100))
        val r = report(ops, idle = 400, idleWaiting = 300)
        assertEquals(0.6, r.labelledHits.toDouble() / (r.labelledHits + r.idleHits), 1e-9)
        // Runnable: 500 labelled against 100 unlabelled.
        assertEquals(500.0 / 600.0, r.runnableCoverage, 1e-9)
        assertTrue(r.runnableCoverage > 0.6, "restricting to runnable time should raise coverage here")
    }

    /** Without the state read the question cannot be asked, and NaN says so rather than guessing. */
    @Test
    fun `runnable coverage is unavailable without thread state`() {
        val r = report(listOf(stat("a", hits = 600, calls = 1000)), idle = 400, state = false)
        assertTrue(r.runnableCoverage.isNaN())
    }

    /** The noise floor is what chance alone would produce, so it must fall as evidence accumulates. */
    @Test
    fun `the noise floor shrinks with hits`() {
        val small = stat("small", hits = 100, calls = 1000)
        val large = stat("large", hits = 10_000, calls = 1000)
        val r = report(listOf(small, large))
        assertTrue(r.noiseFloorOf(large) < r.noiseFloorOf(small))
    }

    // ---------------------------------------------------------------- the long-execution verdict

    /**
     * A baseline must not contain the effect it is used to detect.
     *
     * The rule takes the *lower* of what the duty cycle blames on the machine and what a typical
     * operation experienced, and the second term is what stops one blocking operation raising the
     * floor it is then measured against. Here: an operation with 88.61% of its occupancy in long
     * executions, against a floor of about 35% it would create itself.
     */
    @Test
    fun `one blocking operation cannot raise the floor it is judged against`() {
        val blocker = stat("blocks", hits = 10_000, calls = 10_000, stuck = 8_861, instances = 500)
        val quiet = List(6) { stat("quiet$it", hits = 10_000, calls = 1_000_000, stuck = 20, instances = 2) }
        val r = report(listOf(blocker) + quiet, d = duty(labelled = 0.6455, aggregate = 0.6455))
        // The duty cycle alone would blame the machine for 35.45 points of it.
        assertTrue(r.machineFloor < 0.35, "the floor is still the duty cycle's, which the blocker moved")
        assertTrue(
            isSuspect(blocker.hits, blocker.stuckHits, blocker.stuckInstances, r.machineFloor),
            "the operation that created the floor hid behind it"
        )
    }

    /** A single long execution on a quiet machine is not evidence, whatever ratio it clears. */
    @Test
    fun `a ratio against a small number is not evidence`() {
        assertFalse(
            isSuspect(hits = 2000, stuckHits = 1, stuckInstances = 1, machineFloor = 0.0001),
            "one long execution out of two thousand was treated as a finding"
        )
    }

    // ---------------------------------------------------------------- rendering invariants

    /** A label below the floor always produces a warning line, and never stops the run. */
    @Test
    fun `a below-floor label always warns`() {
        val tiny = stat("tiny", hits = 559, calls = 31_497_063)
        val r = report(listOf(tiny))
        assertEquals(listOf(tiny), r.tooSmall())
        val text = r.render()
        assertTrue(text.contains("tiny"), "the below-floor label was not named")
        assertTrue(text.contains("floor"), "no floor warning in the report")
        assertTrue(r.ok, "a below-floor label invalidated the report")
    }

    /**
     * When the bound found nothing, the report says so in words and never prints a percentage.
     *
     * Event loops whose waiting is native are the case: 34.4% of thread-time is off the CPU while
     * reading `RUNNABLE`, and labels cover 13.9%. Printing "0.00%" and "100 pp" there
     * would read as a measurement, and a reader would rightly distrust shares that are fine.
     */
    @Test
    fun `an unbounded duty explains itself instead of printing a number`() {
        val d = duty(labelled = 0.0, aggregate = 0.6563, invisible = 0.344, labelledFraction = 0.139)
        assertTrue(d.unbounded, "the event-loop regime is no longer recognised as unbounded")
        val lines = d.careful(activeNanos = 20_000_000_000L, reclaimedSlots = 0).joinToString("\n")
        assertTrue(lines.contains("unchecked") && lines.contains("label around the waiting"), "no diagnosis for an unbounded run")
        assertFalse(lines.contains("may be off by"), "printed a bound it does not have")
    }

    /** When the bound *did* find something, it prints it — and the diagnosis stays away. */
    @Test
    fun `a real bound is printed as a bound`() {
        val d = duty(labelled = 0.9846, aggregate = 0.6927, invisible = 0.014, labelledFraction = 0.595)
        assertFalse(d.unbounded)
        val lines = d.careful(activeNanos = 20_000_000_000L, reclaimedSlots = 0).joinToString("\n")
        assertTrue(lines.contains("off by up to 1.56 pp"), "the bound was not printed")
        assertFalse(lines.contains("unchecked"), "printed the diagnosis over a real bound")
        assertEquals(1.56, d.boundPp, 0.05, "the 1.57 pp bound moved")
    }

    /**
     * Where the cores went, a line of its own: five workers on six processors. 10 s of windows; the workers' 29.4 s, the sampler's 10 s, the JIT's 3 s and 11 s
     * more in the JVM, 6 s of it in pauses, are 2.94, 1.00, 0.30, 1.10 and 0.60 cores.
     */
    @Test
    fun `the cores line says where the cores went`() {
        val d = Duty(
            labelledDuty = 0.5, runnableDuty = 0.588, invisibleOffCpu = 0.4, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 10, threads = 6,
            cpuNanos = 29_400_000_000, wallNanos = 60_000_000_000, minWindowDuty = 0.5, maxWindowDuty = 0.5,
            anomalies = 0, maxSampleNanos = 200_000,
            processCpuNanos = 53_400_000_000, samplerCpuNanos = 10_000_000_000, spanNanos = 10_000_000_000,
            jitNanos = 3_000_000_000, pausedProcessCpuNanos = 6_000_000_000, processors = 6,
        )
        assertEquals(
            "6 processors: profiled threads 2.94, the rest 2.40: GC 1.10 (0.60 in pauses), sampler 1.00, JIT 0.30",
            d.coresLine()
        )
        val careful = d.careful(activeNanos = 10_000_000_000L, reclaimedSlots = 0).single()
        assertFalse("processors" in careful, "the cores line is the header's now, not the warning's: $careful")
        // A profile without the figures has no line.
        assertEquals(null, duty().coresLine())
    }

    private fun dutyWith(
        runnable: Double = Double.NaN,
        cpu: Long = 29_400_000_000,
        process: Long = 53_400_000_000,
        sampler: Long = 10_000_000_000,
        jit: Long = 3_000_000_000,
        paused: Long = 6_000_000_000,
        profiled: Long = -1,
    ) = Duty(
        labelledDuty = 0.5, runnableDuty = runnable, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
        resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 10, threads = 6,
        cpuNanos = cpu, wallNanos = 60_000_000_000, minWindowDuty = 0.5, maxWindowDuty = 0.5,
        anomalies = 0, maxSampleNanos = 200_000,
        processCpuNanos = process, samplerCpuNanos = sampler, spanNanos = 10_000_000_000,
        jitNanos = jit, pausedProcessCpuNanos = paused, profiledCpuNanos = profiled, processors = 6,
    )

    /**
     * The Careful line's number comes from the labelled time that read Runnable, not from all the
     * labelled time: with a share duty of 50% - a main thread parked in plain sight - and 90% of the
     * Runnable part on a CPU, it says 11.1 pp, and with 99.5% it says nothing. Taken from the share
     * duty, the line would print 100 pp in both.
     */
    @Test
    fun `the Careful number is the Runnable part's`() {
        val line = dutyWith(runnable = 0.9).careful(activeNanos = 10_000_000_000L, reclaimedSlots = 0).single()
        assertTrue("off by up to 11.1 pp" in line, line)
        assertTrue("only 90.0% of the time inside labels read as Runnable" in line, line)
        assertTrue(dutyWith(runnable = 0.995).careful(activeNanos = 10_000_000_000L, reclaimedSlots = 0).isEmpty(),
            "a Runnable part on a CPU 99.5% of the time printed a warning")
    }

    /** The Cores line's other branches: no JIT clock, no pause, more counted than the JVM's own clock, the profiled threads' own figure. */
    @Test
    fun `the cores line leaves out what it does not have and never goes negative`() {
        val noJit = dutyWith(jit = -1).coresLine()!!
        assertFalse("JIT" in noJit, noJit)
        assertTrue("GC 1.40 (0.60 in pauses), sampler 1.00" in noJit, noJit)

        val noPause = dutyWith(paused = 0).coresLine()!!
        assertFalse("in pauses" in noPause, noPause)

        // The threads and the sampler read more than the process: quantisation, never negative GC.
        val over = dutyWith(cpu = 50_000_000_000, process = 53_400_000_000, sampler = 10_000_000_000).coresLine()!!
        assertTrue("GC 0.00" in over && "JIT 0.00" in over, over)

        // The profiled threads read ten times a second win over the window figure when present.
        val newer = dutyWith(profiled = 32_400_000_000).coresLine()!!
        assertTrue("profiled threads 3.24, the rest 2.10: sampler 1.00, GC 0.80 (0.60 in pauses)" in newer, newer)
    }

    /**
     * The Careful line names the cause from the seconds the hidden waiting happened in: full
     * processors, threads waiting for a core; spare ones, something else; both, the split.
     */
    @Test
    fun `the Careful line names the cause from full or spare seconds`() {
        fun careful(full: Double, spare: Double) = Duty(
            labelledDuty = 0.5, runnableDuty = 0.588, invisibleOffCpu = 0.4, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 10, threads = 6,
            cpuNanos = 29_400_000_000, wallNanos = 60_000_000_000, minWindowDuty = 0.5, maxWindowDuty = 0.5,
            anomalies = 0, maxSampleNanos = 200_000, processors = 6,
            hiddenWhenFull = full, hiddenWhenSpare = spare,
        ).careful(activeNanos = 10_000_000_000L, reclaimedSlots = 0).single()

        val crowded = careful(full = 9_000.0, spare = 500.0)
        assertTrue("6 processors were full when it happened: threads waited for a core" in crowded, crowded)
        assertFalse("I/O" in crowded, "full seconds blamed I/O: $crowded")

        val io = careful(full = 100.0, spare = 9_000.0)
        assertTrue("cores to spare" in io && "I/O" in io, io)
        assertFalse("fewer threads" in io, "spare seconds advised fewer threads: $io")

        val both = careful(full = 5_000.0, spare = 5_000.0)
        assertTrue("50.0% of it when the JVM's 6 processors were full" in both, both)

        val unknown = careful(full = 0.0, spare = 0.0)
        assertTrue("Likely causes" in unknown, "with nothing measured the line names both causes, as before: $unknown")
    }

    /** Shares are bounded by the labelled duty, not the aggregate one. */
    @Test
    fun `the bound comes from labelled occupancy and not from every thread`() {
        val starved = duty(labelled = 0.9694, aggregate = 0.1940, labelledFraction = 0.2)
        assertEquals(0.9694, starved.shareDuty, 1e-9)
        assertEquals(3.16, starved.boundPp, 0.05, "starvation's recorded 3.16 pp moved")
    }

    /** With no per-thread figure the aggregate stands in: a looser bound, not a wrong one. */
    @Test
    fun `the aggregate stands in when there is no per-thread figure`() {
        val d = duty(labelled = Double.NaN, aggregate = 0.80)
        assertEquals(0.80, d.shareDuty, 1e-9)
    }

    /**
     * A stopped session cannot render as though it were a result. The banner leads, and `ok` is
     * false — everything below it is evidence for the verdict, not a measurement.
     */
    @Test
    fun `a stopped session leads with why`() {
        val r = report(listOf(stat("a", hits = 100, calls = 1000)), failure = leakMessage("leaked", "worker-1"))
        assertFalse(r.ok)
        val text = r.render()
        assertTrue(text.contains("PROFILING STOPPED"), "a stopped session rendered without its banner")
        assertTrue(text.indexOf("PROFILING STOPPED") < text.indexOf("operation"), "the banner did not lead")
        assertTrue(text.contains("leaked"), "the failure did not name the operation")
    }

    /** A leak that did not stop the session is still counted and still printed. */
    @Test
    fun `a non-fatal leak is still reported`() {
        val r = Run(
            operations = listOf(stat("a", hits = 100, calls = 1000)),
            idleHits = 0, ticks = 20_000, samplingSpanNanos = 20_000_000_000,
            durationNanos = 20_000_000_000, threads = 8, duty = duty(),
            imbalances = 3, stateSampled = true,
        )
        assertTrue(r.ok, "a counted leak invalidated a non-strict report")
        assertTrue(r.render().contains("labels: 3 were still open"), "the leak count was not printed")
    }

    // ---------------------------------------------------------------- formatting

    /** Four orders of magnitude in one column: a duration picks the unit that keeps it readable. */
    @Test
    fun `durations pick a readable unit`() {
        assertTrue(duration(20.0).contains("n"), "20 ns should print in nanoseconds")
        assertTrue(duration(9_000.0).contains("u"), "9 us should print in microseconds")
        assertTrue(duration(1_500_000.0).contains("m"), "1.5 ms should print in milliseconds")
        assertEquals("-", duration(Double.NaN))
    }

    /** Thread-time crosses to seconds at a second, so 40 s never prints as "40080.00 ms". */
    @Test
    fun `thread time crosses to seconds at a second`() {
        assertTrue(threadTime(999_000_000.0).endsWith("ms"))
        assertTrue(threadTime(40_080_000_000.0).endsWith("s"))
        assertFalse(threadTime(40_080_000_000.0).contains("ms"))
    }

    @Test
    fun `a profile survives a round trip through a file`() {
        val p = loadedReport()
        val path = Files.createTempFile("ticksnick", RUN_EXTENSION)
        try {
            RunStore.save(p, path)
            val back = Run.load(path)
            assertEquals(p.render(legend = true), back.render(legend = true), "the file lost something")
        } finally {
            Files.delete(path)
        }
    }

    @Test
    fun `a diff matches operations by name and marks unchanged calls`() {
        val text = OneRunDiff(loadedReport(), loadedReport(), "a", "b").render()
        assertTrue("work" in text, "the fine operation is missing: $text")
        assertTrue("1,000,000 =" in text, "equal calls should read as =: $text")
        assertTrue("a -> b" in text, "the names are missing: $text")
    }

    // ---------------------------------------------------------------- the Sampling line

    /** One second of sampling, [ticks] of it, on [processors], with the step asked at 1 ms. */
    private fun sampled(wait: WaitStrategy, processors: Int, ticks: Long) = Run(
        operations = emptyList(), idleHits = 0, ticks = ticks,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
        duty = Duty(
            labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 990_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.99, maxWindowDuty = 0.99,
            anomalies = 0, maxSampleNanos = 200_000, processors = processors,
        ),
        wait = wait, askedStepNanos = 1_000_000,
    )

    /** A parked sampler says it freed a processor, and the step it got against the one asked. */
    @Test
    fun `a parked sampler says what it freed and the step it got`() {
        assertEquals(
            "every 1.6 ms (1.0 ms asked): the sampler sleeps between samples, so as not to take one of the 2 processors",
            sampled(WaitStrategy.PARK, processors = 2, ticks = 626).samplingLine(),
        )
    }

    @Test
    fun `a spinning sampler on enough processors says what it holds, with no remedy`() {
        assertEquals(
            "every 1.0 ms: the sampler keeps one of the 14 processors busy to hold the interval",
            sampled(WaitStrategy.SPIN, processors = 14, ticks = 1_001).samplingLine(),
        )
    }

    @Test
    fun `a spinning sampler on few processors names the remedy`() {
        assertEquals(
            "every 1.0 ms: the sampler keeps one of the 2 processors busy to hold the interval - start(wait = PARK) to free it",
            sampled(WaitStrategy.SPIN, processors = 2, ticks = 1_001).samplingLine(),
        )
    }

    @Test
    fun `the default spins from four processors and parks below`() {
        assertEquals(WaitStrategy.PARK, defaultWait(2))
        assertEquals(WaitStrategy.PARK, defaultWait(3))
        assertEquals(WaitStrategy.SPIN, defaultWait(4))
    }

    /** The strategy and the asked step survive the file, and a file without them has no line. */
    @Test
    fun `the Sampling line survives the file`() {
        val path = Files.createTempFile("ticksnick", RUN_EXTENSION)
        try {
            RunStore.save(sampled(WaitStrategy.PARK, processors = 2, ticks = 626), path)
            val loaded = RunStore.load(path)
            assertEquals(WaitStrategy.PARK, loaded.wait)
            assertEquals(1_000_000, loaded.askedStepNanos)
            assertTrue("Sampling      every 1.6 ms (1.0 ms asked)" in loaded.render())

            RunStore.save(report(emptyList()), path)
            assertEquals(null, RunStore.load(path).samplingLine())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
