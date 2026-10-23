package com.ticksnick

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** How many fine operations a coarse leaf lists as rows under it; the rest are one line. */
const val FINE_ROWS_SHOWN = 6

/** What marks a row of a leaf's split by innermost label, so it does not read as a child. ASCII, as everything printed is. */
const val SPLIT_MARK = "-"

/**
 * Executions at once - or, for forked work with no label of its own, threads per execution - from
 * which a coarse row gets its *at once* line: at least this, and at least this many times its parent's. Sampled figures, so a tenth is the row
 * repeating its parent rather than a fork of its own.
 */
const val PARALLEL_SHOWN = 1.1

/** See [Profile.impliedUpperNanosOf]. Shared so the run-time check and the report cannot disagree. */
fun impliedUpperNanos(hits: Long, calls: Long, stepNanos: Double): Double =
    if (calls == 0L) Double.NaN else (hits + 2 * sqrt(hits.toDouble()) + 3) * stepNanos / calls

/** Whether a label is on something too small for the instrument to describe. See [Profile.tooSmall]. */
fun isTooSmall(hits: Long, calls: Long, stepNanos: Double): Boolean =
    calls > 0 && impliedUpperNanos(hits, calls, stepNanos) * Profile.FLOOR_BIAS_ALLOWANCE < Profile.FLOOR_NANOS

/**
 * What to tell someone whose label is below the floor. Both reasons, because the second is the one
 * they cannot check for themselves.
 */
fun tooSmallMessage(name: String, calls: Long, upperNanos: Double): String = String.format(
    // Three lines: the fact, the reason, the fix. Same shape as the coarse floor's message.
    Locale.ROOT,
    "%s: %,d calls at under %s each, below the %.0f ns floor.%n" +
            "    The hook is a visible fraction of it, the sampler reads it 5-9%% low, and C2 can move work across adjacent short labels.%n" +
            "    Label the enclosing loop instead, op(%s, times = n) { }, or drop it if its share has already answered the question.",
    name, calls, duration(upperNanos), Profile.FLOOR_NANOS, name
)

/**
 * The shortest a coarse operation may be, given how much of the run it holds. See [Profile.coarseTooSmall].
 *
 * The tier boundary from profiler.md, as arithmetic rather than a rule of thumb. Two conditions,
 * protecting different things and neither implying the other:
 *
 * 1. `40 ns x executions <= 1% of the run` protects **the program** — that you are measuring what
 *    you started with. In terms of share this is `d >= 4 us x share`.
 * 2. `40 ns <= 5% of the operation's own duration` protects **the number** — that it describes the
 *    code and not the instrument. This is the flat `d >= 800 ns`, and it is what binds below about
 *    20% share, which is nearly always.
 *
 * A 40 ns operation called rarely passes the first comfortably and is then reported at 80 ns: the
 * program is undisturbed and the measurement is 100% wrong. That is why both are needed.
 */
fun coarseFloorNanos(share: Double): Double =
    maxOf(Profile.COARSE_FLOOR_NANOS, Profile.COARSE_SHARE_NANOS * share)

/**
 * What to tell someone whose coarse label is on something too small to carry a context.
 *
 * Names the measured duration rather than an inferred one, and says which of the two conditions
 * bound, because the remedies differ: below the flat floor the label is simply in the wrong tier,
 * while an operation that fails only the share condition is big enough to measure and too big a
 * fraction of the run to afford measuring.
 */
fun coarseTooSmallMessage(name: String, count: Long, meanNanos: Double, requiredNanos: Double): String {
    // Which of the two conditions bound decides what the reader is being told, and they are
    // different complaints with different remedies. Saying "the number would describe the
    // instrument" about an operation whose per-execution overhead is a comfortable 4% is simply
    // false, and a warning that misdiagnoses is worse than one that does not fire.
    //
    // Three lines: the fact, the reason, the fix. It was five, and Andrey read it on a label he had
    // put on a 40 ns lookup: "shorter, less verbose and more clear".
    //
    // The fix names the registration, not a call: `op` takes both tiers, so the call site is already
    // right. It said `op(name) { }` from before the one verb, and Andrey, 2026-10-16, reading it with
    // `op(PASSED_ON)` in his code: "a bit misleading".
    val shareBound = requiredNanos > Profile.COARSE_FLOOR_NANOS
    val overhead = Profile.COARSE_CONTEXT_NANOS / meanNanos * 100
    val why = if (shareBound) String.format(
        Locale.ROOT,
        "    Accurate per execution (%.1f%% overhead), but %,d contexts at %.0f ns are over 1%% of the run.",
        overhead, count, Profile.COARSE_CONTEXT_NANOS
    ) else String.format(
        Locale.ROOT,
        "    A context costs about %.0f ns, which is %.0f%% of an operation this size.",
        Profile.COARSE_CONTEXT_NANOS, overhead
    )
    return String.format(
        Locale.ROOT,
        "%s: %,d coarse executions of %s; a coarse label needs %s here.%n%s%n" +
                "    Register it fine, registerFine(\"%s\"), and keep the op(...) calls; or put the coarse label around a batch of these.",
        name, count, duration(meanNanos), duration(requiredNanos), why, name
    )
}

/**
 * What to tell someone whose work is being billed to executions that have already finished.
 *
 * Names the coarse type, because that is the one whose numbers were about to be wrong and the reader
 * has no other way to know which. Says what to do, because unlike a leak there are two quite
 * different causes and they want opposite fixes: a task that was never meant to be part of the
 * request, and a request that closed before the work it was waiting for.
 */
fun staleContextMessage(worst: String, hits: Long, share: Double): String = String.format(
    Locale.ROOT,
    "%,d samples caught a thread working inside a coarse execution that had already been%n" +
            "    closed by the thread that opened it - mostly %s. That is %.1f%% of all the%n" +
            "    thread-time spent inside coarse executions.%n" +
            "    The time belongs to an execution which no longer exists, so it is excluded from%n" +
            "    every number %s reports rather than quietly inflating them.%n" +
            "    Either work was handed to another thread and never waited for, so it outlived the%n" +
            "    span carrying it - propagate only what the request actually joins - or a span is%n" +
            "    closed too early, before the work it covers has finished.",
    hits, worst, share * 100, worst
)

/**
 * What to tell someone who closed a label that was not the one open.
 *
 * Names both, because the interesting part is the pair: closing `parse` while `serialize` is open
 * means one of them is covering work that was never inside it, and which one depends on how the two
 * were meant to nest. The no-argument `exit()` this replaced could not see the difference at all.
 */
fun mismatchMessage(closed: String, open: String): String = String.format(
    Locale.ROOT,
    "exit(%s) was called while %s was the operation open on this thread.%n" +
            "    One of them now covers work that was never inside it, and the number that comes " +
            "out of%n" +
            "    that is plausible rather than obviously wrong - which is the direction that costs " +
            "you a day.%n" +
            "    The thread was unwound anyway, so this is one bad label and not every label after " +
            "it.",
    closed, open
)

/**
 * What to tell someone whose label leaked. Names the operation that was open, because that is the
 * one whose share is now wrong and the reader has no other way to know which.
 */
fun leakMessage(open: String, thread: String = Thread.currentThread().name): String = String.format(
    Locale.ROOT,
    "%s was still open on thread %s at a point the caller said should be quiescent.%n" +
            "    Every sample taken on that thread between the leak and this check was billed to " +
            "%s,%n" +
            "    which does not look like an error - it looks like a finding, with a plausible " +
            "number beside it.%n" +
            "    An operation label placed with enter/exit has no finally: a body that throws leaves it set.",
    open, thread, open
)

/**
 * Whether one operation's long executions are evidence of anything, given what the machine was
 * doing to every operation at the same time — see [Profile.machineFloor].
 *
 * All three conditions matter and the third was learned the moment this first ran: on a quiet
 * machine the floor falls to 1%, so a single long execution out of two thousand samples clears
 * three times it and means nothing whatever. A ratio against a small number is not evidence. The
 * floor on the share and the minimum count are what keep the answer honest when the machine is
 * behaving perfectly.
 *
 * One function so that the library's report and the bench's own check cannot drift apart, which
 * they did within an hour of the bench check being written by hand.
 */
fun isSuspect(hits: Long, stuckHits: Long, stuckInstances: Long, machineFloor: Double): Boolean =
    hits > 0 && stuckInstances >= Profile.SUSPECT_MIN_INSTANCES &&
            stuckHits.toDouble() / hits > maxOf(machineFloor * Profile.SUSPECT_OVER_BASELINE, Profile.SUSPECT_MIN_SHARE)

/**
 * A duration in whatever unit keeps it readable. The fine tier spans four orders of magnitude, and
 * a column of nanoseconds makes 1.5 ms and 9 µs look like the same kind of number, which is the
 * one distinction this column exists to draw.
 */
fun duration(nanos: Double): String {
    if (nanos.isNaN()) return "-"
    var i = 0
    while (i + 1 < DURATION_UNITS.size && nanos >= DURATION_UNITS[i + 1].from) i++
    // Rounding can reach the next unit - 999.7 ms is three significant digits of 1.00 s, not
    // "1000 ms" - so the choice is made again against the value as it will actually print.
    if (i + 1 < DURATION_UNITS.size && threeSignificant(nanos / DURATION_UNITS[i].scale) >= 1000.0) i++
    return threeSignificantString(nanos / DURATION_UNITS[i].scale) + " " + DURATION_UNITS[i].name
}

/** One rung: the value it takes over at, what to divide by, and what to call it. */
private class DurationUnit(val from: Double, val scale: Double, val name: String)

/**
 * The ladder, and where it stops.
 *
 * Seconds run to a thousand rather than to sixty, because a minute is the unit a reader has to
 * convert back: `479 s` is comparable with the run length printed above it and `7.98 min` is not.
 * Past a thousand seconds the digits win the argument instead.
 */
private val DURATION_UNITS = arrayOf(
    DurationUnit(0.0, 1.0, "ns"),
    DurationUnit(1e3, 1e3, "us"),
    DurationUnit(1e6, 1e6, "ms"),
    DurationUnit(1e9, 1e9, "s"),
    // Minutes take over at a thousand seconds rather than at sixty, which is the whole point of
    // separating where a unit starts from what it divides by.
    DurationUnit(1e12, 60e9, "min"),
    DurationUnit(6e13, 3600e9, "h"),
)

/** [v] rounded to three significant digits, as a number, for deciding which unit it lands in. */
private fun threeSignificant(v: Double): Double = when {
    v >= 100 -> Math.round(v).toDouble()
    v >= 10 -> Math.round(v * 10) / 10.0
    else -> Math.round(v * 100) / 100.0
}

/** Three significant digits and no more, which is every digit any number here can support. */
private fun threeSignificantString(v: Double): String = when {
    v >= 100 -> String.format(Locale.ROOT, "%.0f", v)
    v >= 10 -> String.format(Locale.ROOT, "%.1f", v)
    else -> String.format(Locale.ROOT, "%.2f", v)
}

/** Three significant digits and no unit, for a quantity that carries its own - `pp`, a ratio. */
fun threeDigits(value: Double): String =
    if (value.isNaN()) "-" else threeSignificantString(value)

/**
 * A percentage to three significant digits: `100%`, `40.3%`, `5.80%`, `0.512%`.
 *
 * The same rule as [duration] and for the same reason - the fourth digit of a sampled share is
 * noise, and printing it invites a reader to compare two numbers that are not different. Sorting
 * and every arithmetic use the full value; this is only what is shown.
 */
fun percent(value: Double): String = when {
    value.isNaN() -> "-"
    // Reserved for a real 100: the pair `100% / 0.003%` reads as a contradiction, and the halves of
    // `Runnable / Wait` are supposed to add up on the page as well as in the arithmetic.
    value >= 100.0 -> "100%"
    value >= 99.95 -> ">99.9%"
    value >= 9.995 -> String.format(Locale.ROOT, "%.1f%%", value)
    value >= 0.9995 -> String.format(Locale.ROOT, "%.2f%%", value)
    value > 0.0005 -> String.format(Locale.ROOT, "%.3f%%", value)
    // Not "0.000%", which reads as measured zero. A share this small is one the report folds away.
    value > 0 -> "<0.001%"
    else -> "0%"
}

/**
 * A total of thread-time, in whatever unit keeps it readable.
 *
 * The same function as [duration], and kept only as a name: at the call sites it says which of the
 * two quantities is being printed, and this report has confused thread-time with wall-time before.
 *
 * They were genuinely two formatters until the unit ladder arrived. [duration] stopped at
 * milliseconds and printed a coarse total of eight minutes as `478505.87 ms`; this one started at
 * milliseconds so that 40 seconds did not come out as `40080.00 ms`. Three significant digits and a
 * full ladder answer both, and there is nothing left for a second implementation to do.
 */
fun threadTime(nanos: Double): String = duration(nanos)

/**
 * `key            value`, with every value starting at the same column.
 *
 * The summary used to be four sentences of prose that began the moment the banner ended. A reader
 * looking for "how many threads" had to read a clause to find it, and a reader looking for whether
 * anything was wrong had no way to tell a fact from a caveat from a verdict — they were all just
 * text. Keys fix both: you scan the left column, and the key says what kind of thing the line is.
 */
internal fun row(key: String, value: String): String = String.format(Locale.ROOT, "%-13s %s", key, value)

/** A row that qualifies the one above it — a reason, a bound, a verdict. Values still align. */
internal fun subRow(key: String, value: String): String =
    String.format(Locale.ROOT, "  %-11s %s", key, value)

/** One operation's line in a [Profile]. */
class OperationStat internal constructor(
    val id: Int,
    val name: String,
    val hits: Long,
    val calls: Long,
    /** Hits that caught an execution already running a tick earlier. */
    val stuckHits: Long,
    /** How many distinct executions those hits represent. */
    val stuckInstances: Long,
    /** Hits where the owning thread was not runnable. Zero when state sampling is off. */
    val waitingHits: Long = 0,
    /** Hits that were both long and waiting: a long execution caught with its thread parked. */
    val stuckWaitingHits: Long = 0,
    /** Ticks at which at least one thread was inside this operation. */
    val activeTicks: Long = 0,
    /** Distinct threads that called this operation during the session. See [Profiler.threadsOf]. */
    val threads: Int = 0,
    /** The most threads seen inside it at one tick. See [Sampler.peakInside]. */
    val peakInside: Int = 0,
    /**
     * The longest execution seen, in ticks: a lower bound on its length, since the sampler counts
     * the ticks it was seen open at and not the clock. See [Sampler.longestStuckTicks].
     */
    val longestStuckTicks: Long = 0,
    /** Long executions by length in ticks, [STUCK_BUCKETS] log-2 buckets. See [Sampler.stuckRunHist]. */
    internal val stuckRuns: LongArray = LongArray(STUCK_BUCKETS),
) {
    /** The fraction of this operation's occupancy spent inside executions longer than a tick. */
    val stuckShare: Double get() = if (hits == 0L) 0.0 else stuckHits.toDouble() / hits

    /** The fraction of this operation's occupancy where its thread was parked, blocked or waiting. */
    val waitingShare: Double get() = if (hits == 0L) 0.0 else waitingHits.toDouble() / hits

    /**
     * **Executions of this operation in flight at once**, averaged over the ticks where any were.
     *
     * It counts executions, not threads serving one of them, and the distinction is structural
     * rather than a convention we have chosen. A fine operation is atomic and never leaves the
     * thread that entered it — a body that suspends or hands off is by construction not fine — so
     * one thread inside the label *is* one execution of it, and the two counts are the same count.
     * The parallelism of a **single** execution across threads is not a quantity this tier can hold at
     * all: it needs an execution to have an identity that a second thread can be handed, which is
     * exactly what a coarse context is and what an integer in a slot can never be.
     *
     * So this is one factor of a product, and the other factor does not exist yet — it is
     * **parallelism** in the work-span sense, `work / span` for one execution, and it needs the
     * coarse tier:
     *
     *     threads inside = executions in flight x parallelism per execution
     *
     * Here the second factor is 1 by construction, so the product collapses and either reading
     * gives the same number. That is exactly why the column must stop being called "threads" now
     * rather than when a coarse column arrives beside it and the two factors come apart. See
     * plan.md, phase 5.
     *
     * **What this number depends on, stated because it is not the code.** Little's law relates the
     * mean over *all* time to the load — `L = lambda x W` — and this column is not that mean: it
     * divides by the ticks where the operation was occupied rather than by every tick, so
     * `column = lambda x W x parallelism / p_active`, and it is capped by the threads there are:
     *
     *     threads inside = min(lambda x W x parallelism, P)
     *
     * Below the cap it tracks the arrival rate; at the cap it reports the pool size and stops
     * tracking the load at all. Twice the clients is twice this number with the code unchanged, so
     * it is a property of the deployment in both regimes. It earns its place because [elapsedNanosOf]
     * cannot be computed without it, and because printed over the thread count — see
     * [Profile.inFlightOf] — the ratio says which of the two regimes produced it.
     *
     * The divisor that turns occupancy into elapsed time: the same hundred thread-seconds of
     * waiting is a convoy at fifteen executions in flight and mild persistent contention at 1.7,
     * and the two want opposite fixes.
     */
    val inFlight: Double get() = if (activeTicks == 0L) Double.NaN else hits.toDouble() / activeTicks
}

/**
 * One node of the coarse tree: a coarse operation at one path of coarse operations, with the
 * figures of the executions that ran exactly there.
 *
 * The coarse table is drawn from these. A [CoarseStat] is the operation over every path, which is
 * what the floor check, the fine table's folding and the parallelism line want; a node is what the
 * tree wants, and the two are the same figures only for an operation with one caller. See
 * `CoarsePath` for where the figures come from.
 */
class CoarseNode internal constructor(
    val type: Int,
    val name: String,
    /** Executions completed at this path. Exact. */
    val count: Long,
    val spanSumNanos: Long,
    val spanMinNanos: Long,
    val spanMaxNanos: Long,
    internal val hist: LongArray,
    /** Samples caught anywhere under an execution at this path. */
    val inclusiveHits: Long,
    /** Of those, the ones whose thread was runnable. */
    val runningInclusiveHits: Long,
    /** Inclusive samples by the fine operation they caught. Last entry: no fine label. */
    internal val fine: LongArray,
    /**
     * Pause time charged here: every pause the sampler saw with an execution of this path open,
     * scaled to what the JVM confirmed. The pause that interrupted the span, which is not always
     * the one the span caused.
     */
    val pausedNanos: Long,
    val children: List<CoarseNode>,
    /** For an operation opened with a key: its slowest executions on this path, longest first. Measured. */
    val slowest: List<SlowExecution> = emptyList(),
) {
    val meanSpanNanos: Double get() = if (count == 0L) Double.NaN else spanSumNanos.toDouble() / count

    /** As [CoarseStat.percentileNanos]: at the top of its bucket, never above the exact maximum. */
    fun percentileNanos(p: Double): Double {
        val v = SpanHistogram.percentile(hist, count, p)
        return if (v.isNaN()) v else minOf(v, spanMaxNanos.toDouble())
    }

    /** Every node of the subtree, this one first. */
    fun flatten(): List<CoarseNode> = listOf(this) + children.flatMap { it.flatten() }
}

/** One of the slowest executions of a coarse operation: the key it was opened with, and its wall time. */
data class SlowExecution(val key: String, val nanos: Long)

/**
 * One coarse operation's line in a [Profile].
 *
 * The asymmetry with [OperationStat] is the point of the tier. **Span is measured**, exactly, by two
 * timestamps per execution — it is the only quantity in this whole tool that is not sampled, and it
 * is the one a fine label can never produce. Everything about *where the time went* is still
 * sampled, because that is the only way to see inside an execution without pricing it.
 */
class CoarseStat internal constructor(
    val type: Int,
    val name: String,
    /** Executions that completed inside the session. Exact. */
    val count: Long,
    val spanSumNanos: Long,
    val spanMinNanos: Long,
    val spanMaxNanos: Long,
    internal val hist: LongArray,
    /** Samples that caught a thread inside this operation and not inside a nested coarse one. */
    val hits: Long,
    /** Of those, the ones whose thread was runnable. */
    val runningHits: Long,
    /** Samples caught anywhere under this operation, itself included. */
    val inclusiveHits: Long,
    /** Of those, the ones whose thread was runnable. `mean span - busy` is the waiting, quantified. */
    val runningInclusiveHits: Long,
    /** Distinct executions occupied at each tick, summed over ticks. See [parallelism]. */
    val instanceTicks: Long,
    /** Ticks at which at least one thread was anywhere under this operation. */
    val activeTicks: Long,
    /** Inclusive samples broken down by the fine operation they caught. Last entry: no fine label. */
    internal val fine: LongArray,
    /**
     * Samples that caught a thread under an execution of this type that had already been closed.
     *
     * **Not included in any other number on this line.** They are work billed to an execution that
     * no longer exists, and folding them in would let [busyPerExecutionNanos] exceed the mean span
     * it is supposed to sit inside — impossible arithmetic that reads as a finding. See
     * `Sampler.coarseStaleHits`.
     */
    val staleHits: Long = 0,
    /** Of [hits], those with no fine label open: the operation's own work. See [Sampler.coarseSelfHits]. */
    val selfHits: Long = 0,
    /** Of [selfHits], the ones whose thread was runnable. */
    val selfRunningHits: Long = 0,
    /** Ticks at which at least one thread was doing this operation's own work. */
    val selfActiveTicks: Long = 0,
    /** The most threads doing this operation's own work at one tick. */
    val selfPeak: Int = 0,
    /** Distinct threads that ran an execution of this operation. See [Profiler.coarseThreadsOf]. */
    val threads: Int = 0,
) {
    /** Mean measured duration of one execution. Exact, not sampled. */
    val meanSpanNanos: Double get() = if (count == 0L) Double.NaN else spanSumNanos.toDouble() / count

    /**
     * A percentile of the measured durations, to within [SpanHistogram.PRECISION] — and never above
     * [spanMaxNanos], which is exact.
     *
     * The histogram reports a percentile at the **top** of the bucket it fell into, because a
     * latency figure may overstate and must not understate. On many executions that rounding
     * disappears into the distribution. On few it does not: a single execution of 713.2 µs lands in
     * the bucket `[655.4, 720.9]` and every percentile prints as **720.9 µs**, against a `max` of
     * 713.2 — a p99 larger than the maximum, which is nonsense on its face and was the first thing a
     * reader noticed.
     *
     * Clamping to the measured maximum fixes it and cannot cost the guarantee: `max` is two
     * timestamps rather than a bucket, and the true p-th percentile of a set is never above its
     * true maximum. So the clamp can only ever move a value *down* to something still at or above
     * the truth. The result is exact whenever the top bucket is the one being asked about, which is
     * every percentile of a single execution.
     */
    fun percentileNanos(p: Double): Double =
        minOf(SpanHistogram.percentile(hist, count, p), spanMaxNanos.toDouble())

    /** Of the samples caught under this operation, those whose thread was parked or blocked. */
    val waitingHits: Long get() = inclusiveHits - runningInclusiveHits

    /**
     * **Threads inside one execution at once**, waiting ones counted in full.
     *
     * This and [working] are the same sum over the same ticks, split by whether the thread was on a
     * CPU, and both are needed because they answer different questions. This one is the **capacity**
     * answer: how many threads one execution ties up. A caller parked on its own join is not doing
     * work, but it is not available for anything else either, so with sixteen threads and five tied
     * up per request you can serve three at once and not four.
     *
     * It is the number that keeps the identity in [Profile.inFlightOf]'s note exact — `threads inside
     * = executions in flight x this` — because [inFlight] counts an execution whether or not its
     * threads are running, and a factorisation has to count both sides the same way. It is also
     * consistent with the rest of the report, where `share`, `occupancy` and `in flight` all count a
     * waiting thread in full.
     *
     * **1.0 by construction until contexts cross threads.** Without propagation a context lives on
     * the thread that made it, so every occupied instance is occupied by exactly one — which made it
     * a known answer to check the instance stamping against before phase 5 could move it. Measured
     * at exactly 1.0000 on the bench with propagation off, and at 5.24 with it on, against a
     * stopwatch that said 4.24 helpers plus the one parked driver.
     */
    val inside: Double
        get() = if (instanceTicks == 0L) Double.NaN else inclusiveHits.toDouble() / instanceTicks

    /**
     * **Threads actually working on one execution at once** — `work / span`, the work-span model's
     * `T1/T8`, and what the literature means by *parallelism*.
     *
     * The **speedup** answer, and the sibling of [inside]: of the threads in the execution, the ones
     * a sample caught on a CPU. This is the one that says what splitting the work bought you, and
     * the one to invert through Amdahl when asking whether more threads would help. A caller parked
     * on a join contributes nothing here, correctly — it made the request no faster.
     *
     * `working = inside x (1 - waiting)`, so the two differ by exactly the waiting share the coarse
     * table already prints. They are shown side by side rather than one being derived by the reader,
     * because the arithmetic is not the point and both readings are wanted at a glance.
     *
     * This is the number the fine tier structurally cannot produce, and the one that is a property
     * of the code rather than of the load — a request that splits four ways splits four ways for one
     * client or a thousand.
     *
     * **Two limits, in opposite directions, and both are real.** It reads *low* when the pool is
     * saturated, because what gets measured is `min(what the code could do, threads actually free)`:
     * on the bench, seven drivers against eight helpers leaves nothing to fan out to and it falls
     * back to about 1. And it reads *high* as a speedup, because it is `work / span` of the run it
     * measured and parallelising usually costs extra work. On Lucene the same search is 14.04 ms on
     * one thread and 4.01 ms on eight — a 3.50x speedup — while this reads **6.32**, the difference
     * being the 1.80x more total CPU the parallel run spends. Re-running at another thread count is
     * the only thing that measures what parallelism bought; see `ideas.md` item 22.
     */
    val working: Double
        get() = if (instanceTicks == 0L) Double.NaN else runningInclusiveHits.toDouble() / instanceTicks

    /**
     * Executions of this operation running at once — the same quantity as [OperationStat.inFlight]
     * and with the same caveat: it tracks the load, not the code.
     */
    val inFlight: Double
        get() = if (activeTicks == 0L) Double.NaN else instanceTicks.toDouble() / activeTicks
}

/**
 * What a sampling session collected.
 *
 * Shares are over *labelled* samples. Samples that caught a thread outside any operation are
 * reported separately as [idleHits] rather than folded into the denominator — mixing them in would
 * make every share depend on how much uninstrumented code happened to be running.
 */
class Profile internal constructor(
    val operations: List<OperationStat>,
    val idleHits: Long,
    val ticks: Long,
    val samplingSpanNanos: Long,
    val durationNanos: Long,
    val threads: Int,
    val duty: DutyReport,
    /**
     * Why the session stopped early, or null. Non-null means the numbers below describe a run the
     * profiler could not have measured correctly, and they are shown only so the reader can see
     * what led to the verdict.
     */
    val failure: String? = null,
    /** Times a thread was found holding a label open where the caller said it should not be. */
    val imbalances: Int = 0,
    /** Whether each hit also recorded its thread's state. Without it the verdicts fall back. */
    val stateSampled: Boolean = false,
    /** Of [idleHits], those where the thread was not runnable. See Sampler.idleWaitingHits. */
    val idleWaitingHits: Long = 0,
    /** Threads still inside a hand-placed label when the session ended. */
    val openAtEnd: Int = 0,
    /**
     * Threads that arrived past the slot ceiling and are invisible to the long-execution detector.
     *
     * Captured at `stop()` rather than read from [Profiler] at render time, which is what it used
     * to do — and that had the two faults this project has now met twice. It is a process-lifetime
     * counter that never decreases, so in a process that profiles repeatedly every later report
     * warned about threads that arrived during an earlier session; and reading live global state
     * while rendering means a report can describe thread churn that happened *after* the session it
     * claims to be about.
     */
    val untrackedSlots: Int = 0,
    /**
     * Threads that exited without calling `Profiler.release()` and had to be reclaimed.
     *
     * Reported rather than silently absorbed. A user who is quietly rescued learns nothing, and the
     * rescue depends on a garbage collection - in a process that does not collect, the slots stay in
     * the walk reading empty and every share is taken over a denominator inflated by dead threads.
     */
    val reclaimedSlots: Int = 0,
    /** The coarse tier's half of the report, empty when no coarse label was placed. */
    val coarse: List<CoarseStat> = emptyList(),
    /**
     * Coarse executions still open when the session ended, which is a leak by definition — nothing
     * can close them now, and their spans are missing from [coarse] for exactly that reason.
     */
    val openContextsAtEnd: Int = 0,
    /**
     * Labelled samples that fell under no coarse span. See [labelledOutsideCoarseShare], and
     * `Sampler.labelledOutsideCoarse` for what it is for.
     */
    val labelledOutsideCoarse: Long = 0,
    /** Samples caught under a coarse execution that had already been closed. See [CoarseStat.staleHits]. */
    val staleContextHits: Long = 0,
    /** Samples caught inside a coarse execution at all — the population [staleContextHits] is part of. */
    val coarseSampleHits: Long = 0,
    /** How long every thread was stopped, which is what stretches [stepNanos] past the requested step. */
    val pause: PauseReport = PauseReport.NONE,
    /** The coarse tree: the roots, each with what ran under it. Empty when no coarse label was placed. */
    val coarseTree: List<CoarseNode> = emptyList(),
    /** The CPU clock over the session; [ClockReport.NOT_RECORDED] for a profile saved before it was. */
    val clock: ClockReport = ClockReport.NOT_RECORDED,
) {
    /** False when a fatal finding stopped the session. See the severity ladder in plan.md. */
    val ok: Boolean get() = failure == null

    /** Samples inside a fine label. Self time by construction: the hook keeps the innermost id. */
    val fineHits: Long get() = operations.sumOf { it.hits }

    /** Samples inside a coarse span doing its own work, summed over the coarse operations. */
    val coarseSelfHits: Long get() = coarse.sumOf { it.selfHits }

    /**
     * Every sample that landed inside some label, of either tier.
     *
     * Fine hits and coarse self hits are disjoint - a sample with a fine label open is one of the
     * first, one inside a span with no fine label is one of the second - so this partitions, and
     * one share column can rank both tiers. Before the tiers shared a table this was the fine sum
     * alone, which made a run labelled only with coarse spans report **zero coverage**.
     */
    val labelledHits: Long get() = fineHits + coarseSelfHits

    /** Samples inside no label at all: [idleHits] is "no fine label", which includes span work. */
    val unlabelledHits: Long get() = (idleHits - coarseSelfHits).coerceAtLeast(0)

    /** Every sample taken: the denominator of a share. */
    val allHits: Long get() = labelledHits + unlabelledHits

    /**
     * A share of *all* samples, so it is a share of active time: 30% means 30% of the time the
     * program was not paused, and the `(no operation)` row in the table is the rest.
     *
     * It was a share of labelled samples until 2026-10-11, which kept a share still when idle time
     * changed and moved every row whenever a label was added. Over all samples it is the other way
     * round, and that is the one a reader expects: adding a label moves samples out of
     * `(no operation)` and nothing else. The absolute column is still the one to compare between
     * runs - see the note on [occupancyNanosOf].
     */
    fun shareOf(op: OperationStat): Double =
        if (allHits == 0L) 0.0 else op.hits.toDouble() / allHits

    /** Error chance alone would produce at this hit count — anything smaller is not measurable. */
    fun noiseFloorOf(op: OperationStat): Double =
        if (op.hits > 0) 1.0 / sqrt(op.hits.toDouble()) else Double.NaN

    /** The sampling span with the confirmed pauses taken out: the time every sample was taken in. */
    val activeNanos: Long get() = samplingSpanNanos - pause.confirmedPausedIn(samplingSpanNanos)

    /**
     * What one sample is worth in thread-time: the step the sampler achieved over *active* time.
     *
     * Over the whole span it was the wall step, and a pause stretched it: 1.108 ms on a run that
     * asked for 1 ms, so every thread-time and time per call read about 10% high, with the pauses
     * spread over every operation in proportion to its samples and the one that allocated its way
     * into the collection charged no more than one that allocated nothing. No sample is taken in a
     * pause, so the step a sample stands for is the active one.
     */
    val stepNanos: Double get() = if (ticks > 1) activeNanos.toDouble() / (ticks - 1) else Double.NaN

    /**
     * This operation's occupancy as thread-time rather than as a fraction — and it is the column to
     * compare between two runs.
     *
     * [shareOf] is a share of *labelled* samples, which is the right denominator (see the note on
     * [Profile]) and a treacherous number to compare across runs: it re-scales every row at once
     * whenever the set of labels changes.
     *
     * Measured on the Lucene trial, two runs of each placement, where the only difference is that
     * one labels the factory that builds a clause's scorer and the other does not:
     *
     * ```
     *                 share                    occupancy
     *   phrase   58.4% 57.2%  ->  42.9% 43.6%   38.2 s 41.5 s  ->  41.0 s 40.1 s
     *   prefix   30.9% 32.3%  ->  48.6% 47.7%   20.2 s 23.5 s  ->  46.5 s 44.0 s
     * ```
     *
     * By share, the phrase clause appears to have become **fourteen points cheaper** — a change
     * with a story attached, and the story is false. Its occupancy does not separate by placement at
     * all: 38–41 seconds in every run, which is this machine's run-to-run spread. The clause that
     * actually changed doubled, and the coverage gained by the fix (65–73 s → 92–96 s) is that one
     * clause and nothing else.
     *
     * A misplaced label is invisible in the share column and obvious in this one.
     */
    /**
     * The band of group labels printed over a table's column heads.
     *
     * Ruled rather than centred text alone: the point of the band is to say where a group starts
     * and stops, and unruled words leave a reader to guess the boundaries from spacing.
     *
     * Groups are given as column ranges into [widths], which are the widths of the **data** row -
     * the band has to sit over the numbers, and the header row borrows two characters from the
     * first column to fit `Thread-time% v`. A range with an empty label is not a group; a span left
     * out entirely simply has no band over it.
     */
    /** Where each column starts, and where the group bars fall, given [FINE_COLUMNS]. */
    private fun layout(widths: IntArray, breaks: IntArray): Pair<IntArray, IntArray> {
        val starts = IntArray(widths.size)
        val bars = ArrayList<Int>()
        var pos = 0
        for (i in widths.indices) {
            starts[i] = pos
            pos += widths[i] + 1
            if (i in breaks && i < widths.size - 1) {
                bars += pos
                pos += 2
            }
        }
        return starts to bars.toIntArray()
    }

    /** The full width of a table with these columns and these bars. */
    private fun widthOf(widths: IntArray, breaks: IntArray): Int {
        val (starts, _) = layout(widths, breaks)
        return starts.last() + widths.last()
    }

    /** The rule under a grouped header: dashes, with a cross where each bar comes down. */
    private fun rule(widths: IntArray, breaks: IntArray): String {
        val (_, bars) = layout(widths, breaks)
        val line = CharArray(widthOf(widths, breaks)) { '-' }
        for (b in bars) line[b] = '+'
        return String(line)
    }

    /**
     * The band of group labels over a table's column heads.
     *
     * The labels are centred and unruled because the bars below them already say where each group
     * starts and stops - dashes as well would be the same boundary drawn twice. A group whose label
     * is empty still gets its bars: an unlabelled span says *these belong together and we have not
     * settled why*, which is the honest state of `Calls` and `Impl/call`.
     */
    private fun band(widths: IntArray, breaks: IntArray, vararg groups: Pair<IntRange, String>): String {
        val (starts, bars) = layout(widths, breaks)
        val line = CharArray(widthOf(widths, breaks)) { ' ' }
        for ((range, name) in groups) {
            val from = starts[range.first]
            val span = starts[range.last] + widths[range.last] - from
            if (name.isEmpty() || span < name.length) continue
            val pad = (span - name.length) / 2
            for (i in name.indices) line[from + pad + i] = name[i]
        }
        for (b in bars) line[b] = '|'
        return String(line).trimEnd()
    }

    /**
     * The two halves of an operation's thread-time, as `7.2% / 92.8%`, summing to 100.
     *
     * Printed as a pair because a reader could not tell whether `waiting` was a part of the
     * thread-time beside it or an addition to it, and no unit settles that - seconds against
     * seconds is as ambiguous as a percentage. Two shares that add to the whole are not readable
     * as an addition to it.
     *
     * The left half is **runnable**, which is not *working*: it counts a preempted thread and a
     * thread stopped inside a native call, both of which the JVM reports as `RUNNABLE`. It is an
     * upper bound on the work, and the time-on-CPU block is the only thing that bounds it in turn.
     */
    fun runnableSplit(waiting: Double): String =
        percent((1 - waiting) * 100) + " / " + percent(waiting * 100)

    fun occupancyNanosOf(op: OperationStat): Double = op.hits * stepNanos

    /**
     * How much *wall clock* had at least one thread inside this operation — as against
     * [occupancyNanosOf], which sums across threads.
     *
     * The distinction is the whole point and it only matters when threads wait. Eight threads
     * computing for five seconds is forty thread-seconds of occupancy and the sum is real: on one
     * thread it would genuinely take forty seconds. A hundred threads parked one second on one lock
     * is also a hundred thread-seconds, and there the sum is fiction — the clock advanced one
     * second and removing the lock would save one second, not a hundred. Elapsed is the number that
     * says so, and it costs one counter per operation.
     *
     * It is not latency: it is the union of every execution's interval, so it says the operation had
     * *somebody* inside it for this long and nothing about any single execution. And it is not a
     * counterfactual — see the warning at the foot of the report.
     */
    fun elapsedNanosOf(op: OperationStat): Double = op.activeTicks * stepNanos

    /**
     * [OperationStat.inFlight] printed **over the threads that ever entered it**, as `3.28/8`.
     *
     * The denominator is not decoration and it is not there to save the reader an arithmetic step.
     * The numerator alone invites the one reading it cannot support — *"this operation used 3.28
     * threads"*, as though that were a property of the operation — when it is a property of the
     * deployment, and of two different parts of it depending on which regime the run is in:
     *
     * - **below saturation** it tracks the arrival rate, so twice the clients is twice the number
     *   and the code is unchanged;
     * - **at the ceiling** it stops tracking the load at all and reports how many threads there
     *   were, because there is nothing left to spread the work onto.
     *
     * The ratio is the only part of the column that survives that objection: it says which of the
     * two regimes produced the number. `3.28/8` is *tracking your load*; `7.9/8` is *every thread
     * that runs this label is inside it at once*, which is a finding.
     *
     * **The denominator is per operation, and used not to be.** It was the run's own thread
     * high-water mark, which is the pool size only on a workload where every thread does
     * everything — the bench, which is where the column was designed. The first workload here with
     * a `main` thread beside a pool broke it immediately: an operation that never left `main`
     * printed `1.00 / 5`, measured against four threads that could not have entered it. A count of
     * the threads that actually called it makes `1.00 / 1` say what it should have said all along,
     * which is *this operation is serial by construction*. The run-wide count still exists, in the
     * header line, where it is a fact about the run and is not pretending to be about a row.
     */
    fun inFlightOf(op: OperationStat): String {
        val n = op.inFlight
        return if (n.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", n)
    }

    /**
     * The `Workers` column: **how many threads were ever actually working on this at once.**
     *
     * The question it answers is the one a reader brings with them - *I dispatched three, how many
     * ran?* - and nothing else in the table can answer it. `Concurrency` cannot: a mean of 1.1 is
     * consistent with two workers or eight. `Pool` cannot: the threads exist either way. A row
     * reading `1.10  2  4` says the pool is four, at most two were ever in here together, and on
     * average barely one - and if three were dispatched, something is off.
     *
     * Mechanically it is `max(k)` where `k` is the threads found inside at one tick, the same
     * series whose mean is `Concurrency`. Detected rather than measured, so it is a lower bound: a
     * burst that begins and ends between two ticks is a burst nobody saw.
     */
    fun workersOf(op: OperationStat): String =
        if (op.peakInside <= 0) "-" else op.peakInside.toString()

    /**
     * The `Pool` column: distinct threads that ever *called* the operation.
     *
     * Counted by the hook rather than sampled, so unlike the two columns beside it this one is
     * exact - it counts callers, and a thread that entered once and was never sampled still ran it.
     *
     * It is the pool, and that holds in the cases that look like exceptions: a thread-per-task
     * workload really does have ten thousand of them, which is what virtual threads are for, and a
     * label fed by two pools really is run by both. What it counts is the threads that *were used*,
     * which is why a `ThreadPoolExecutor` of four reaches 4 while never running more than three
     * tasks at once - it opens a new core thread on every submit until the core size is reached.
     */
    fun poolOf(op: OperationStat): String = if (op.threads <= 0) "-" else op.threads.toString()

    /**
     * Operations every thread queues through one at a time.
     *
     * `Workers 1` beside `Pool 8` is a serialization point: eight threads run it and no two are
     * ever inside together, which is the contention term of a scalability model showing up in a
     * table of shares. It is a *warning* and not a verdict because `Workers` is sampled - a burst
     * between two ticks is a burst nobody saw - so it asks for enough hits that a second thread
     * would have been caught if there had been one.
     */
    fun serialised(): List<OperationStat> = operations
        .filter { it.threads > 1 && it.peakInside == 1 && it.hits >= MIN_HITS_FOR_SERIAL }
        .sortedByDescending { it.threads }

    /** Thread-time spent inside any label. */
    val labelledNanos: Double get() = labelledHits * stepNanos

    /** Of [labelledHits], those that caught a thread that was not runnable. Zero without state. */
    val labelledWaitingHits: Long
        get() = operations.sumOf { it.waitingHits } + coarse.sumOf { it.selfHits - it.selfRunningHits }

    /** Of [unlabelledHits], those whose thread was not runnable. See [labelledWaitingHits]. */
    val unlabelledWaitingHits: Long
        get() = (idleWaitingHits - coarse.sumOf { it.selfHits - it.selfRunningHits }).coerceAtLeast(0)

    /**
     * Coverage with both sides restricted to occupancy where the thread was actually runnable.
     *
     * The plain coverage figure divides labelled samples by *every* sample, and on a workload with
     * idle threads that reads as "the operations miss most of the run" when what it means is "most of
     * the run was nobody doing anything". On Lucene 79.2% of the unlabelled samples caught a thread
     * that was not runnable, which turns 49.8% coverage into something quite different.
     *
     * Both sides, not just the unlabelled one: a label can be held across a wait — the bench's
     * `lockedUpdate` spends 73% of itself parked — so leaving that inside the numerator while
     * removing it from the denominator would be the same mismatch of denominators this fix exists
     * to remove, pointing the other way. NaN when state was not sampled and the question cannot be
     * asked.
     */
    val runnableCoverage: Double
        get() {
            if (!stateSampled) return Double.NaN
            val labelled = labelledHits - labelledWaitingHits
            val idle = unlabelledHits - unlabelledWaitingHits
            return if (labelled + idle <= 0) Double.NaN else labelled.toDouble() / (labelled + idle)
        }

    /**
     * Thread-time the sampler observed at all — every slot read on every tick, labelled or not.
     *
     * The honest denominator for "how much of this run do my operations account for", and the number
     * that makes a coverage figure actionable: *"operations cover 94 s of 181"* is a sentence you can do
     * something about in a way that *"52%"* is not.
     */
    val observedNanos: Double get() = (labelledHits + unlabelledHits) * stepNanos

    /** Occupancy that was not CPU, in samples. The whole run's supply of stalling. */
    val offCpuSamples: Double
        get() = if (duty.available) (1 - duty.duty) * (labelledHits + unlabelledHits) else Double.NaN

    /**
     * How much of an operation's long-running time was certainly spent *running*, as a fraction.
     *
     * The question this answers is the one the long-execution signal cannot answer on its own, and
     * it decides what the reader should do about it. An execution that outlived a tick was either
     * waiting for something or working for a millisecond, and those want opposite responses: the
     * first means the share is occupancy and not CPU, the second means the share is honest and the
     * operation merely belongs in the coarse tier.
     *
     * The answer is arithmetic on two numbers already in the report. The whole run had only
     * [offCpuSamples] of stalling in it, from every cause together, so an operation whose long
     * executions occupy more than that must have been *running* for the difference — whatever the
     * rest of the run was doing, and without attributing a single sample to anybody.
     *
     * A lower bound and deliberately a loose one: it charges the operation with every stall in the
     * run, including stalls that happened somewhere else entirely. When it still comes out high, it
     * is certain.
     */
    fun runningFloorOf(op: OperationStat): Double {
        val offCpu = offCpuSamples
        if (offCpu.isNaN() || op.stuckHits == 0L) return Double.NaN
        return 1 - min(1.0, offCpu / op.stuckHits)
    }

    /**
     * The most an execution can plausibly have cost, from the sampling alone.
     *
     * The hit count is a count of rare events, so its upper confidence bound is
     * `hits + 2√hits + 3` — and that is `3` when nothing was ever sampled, which is the rule of
     * three and the reason an operation the sampler never caught is still measurable. Seeing it
     * zero times in *n* samples means its whole occupancy is under three ticks however large *n*
     * was, so its cost per call is under `3 × tick / calls`. Forty million calls at a 1 ms tick
     * puts that under 0.075 ns, a fraction of one cycle.
     *
     * This is what makes "too small" provable rather than suspected: if even the upper bound is
     * below the floor, no amount of missing evidence can rescue the label.
     */
    fun impliedUpperNanosOf(op: OperationStat): Double = impliedUpperNanos(op.hits, op.calls, stepNanos)

    /**
     * Operations labelled on something too small for the instrument to describe.
     *
     * The bound is inflated by [FLOOR_BIAS_ALLOWANCE] first: the sampler reads short operations
     * 5-9% low below 45 ns — measured against the configured truth — and that bias points the wrong way
     * here, since it would make an innocent operation look smaller than it is.
     */
    fun tooSmall(): List<OperationStat> = operations
        .filter { isTooSmall(it.hits, it.calls, stepNanos) }
        .sortedByDescending { it.calls }

    /**
     * What one execution appears to have cost, from the sampling alone: `hits × step / calls`.
     *
     * The smell test the person who wrote the code can apply and the tool cannot. An operation
     * known to be 20 ns showing 500 ns here is stalling on something, and no amount of share
     * arithmetic would have said so. It is occupancy per call, not CPU per call, and it inherits
     * the same bound as every other number here.
     */
    fun impliedNanosOf(op: OperationStat): Double =
        if (op.calls == 0L) Double.NaN else op.hits * stepNanos / op.calls

    /**
     * The run-wide rate of executions caught spanning a tick. Reported, but *not* what an operation
     * is judged against — see [machineFloor].
     */
    val stuckBaseline: Double
        get() = if (fineHits == 0L) 0.0 else operations.sumOf { it.stuckHits }.toDouble() / fineHits

    /**
     * How much of an operation's long-running time the *machine* can account for, and therefore the
     * floor an operation has to clear before it has anything to answer for.
     *
     * **The lesson this phase kept relearning: a baseline must not contain the effect it is used to
     * detect.** Three attempts, each broken by a workload the previous one had not met.
     *
     * 1. *The run-wide rate of long executions.* Assumes long executions are the exception. Against
     *    Calcite's planner that rate is 53.52%, because most of those labels genuinely are coarse,
     *    so three times it was unreachable and nothing could ever be named.
     * 2. *The non-CPU fraction from the duty cycle.* Correct for preemption and GC, which are what
     *    the machine does to everything — but a thread blocked on a lock is also off the CPU, so a
     *    blocking operation raises this floor and hides itself behind it. Measured: an operation
     *    with 88.61% of its occupancy in executions over a tick, against a floor of 35.43% that it
     *    had itself created. Not named.
     * 3. *The lower of that and what a typical operation experienced* — [typicalStuckShare]. The
     *    machine can only be blamed for what both estimates agree on, and the median across
     *    operations cannot be moved by the one operation that blocks.
     *
     * Being conservative about blaming the machine means being liberal about naming operations,
     * which is why [SUSPECT_MIN_SHARE] and [SUSPECT_MIN_INSTANCES] exist: they are what stops a
     * near-zero floor turning noise into an accusation.
     */
    val machineFloor: Double
        get() {
            val fromDuty = if (duty.available) 1 - duty.duty else Double.MAX_VALUE
            val typical = typicalStuckShare()
            val fromOperations = if (typical.isNaN()) Double.MAX_VALUE else typical
            val floor = minOf(fromDuty, fromOperations)
            return if (floor == Double.MAX_VALUE) stuckBaseline else floor
        }

    /**
     * What a *typical* operation's rate of long executions was — the median over operations with
     * enough hits to have a meaningful rate.
     *
     * The second estimate of the same quantity as the duty cycle, and it fails where the duty cycle
     * fails, in the opposite direction. Preemption and GC are charged to whatever was executing, in
     * proportion to its occupancy, so they raise *every* operation's rate together and the median
     * sees them. Blocking is concentrated in the operation doing the blocking, so the median does
     * not see it — which is exactly what a floor must not see.
     *
     * The median rather than the mean, because the mean is the run-wide rate that Calcite already
     * disproved: one operation holding most of the occupancy drags it up until nothing can clear it.
     */
    fun typicalStuckShare(): Double {
        val rates = operations.filter { it.hits >= MEDIAN_MIN_HITS }.map { it.stuckShare }.sorted()
        if (rates.isEmpty()) return Double.NaN
        return if (rates.size % 2 == 1) rates[rates.size / 2]
        else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
    }

    /**
     * Operations whose executions span a tick far more often than the run as a whole — the ones
     * that are not the size their label claims.
     *
     * Both conditions are provisional and marked as such: this project sets tolerances from
     * measurement, and the measurements that would settle these are the contended bench operation
     * and the Calcite trial, neither of which has been run against this yet. The second condition
     * exists so that a single GC pause, which can freeze one operation across several ticks,
     * cannot on its own accuse it.
     */
    fun suspect(): List<OperationStat> = operations
        .filter { isSuspect(it.hits, it.stuckHits, it.stuckInstances, machineFloor) }
        .sortedByDescending { it.stuckShare }

    /**
     * What to do about an operation whose executions outlive a tick — which is not one answer, and
     * the trial is why.
     *
     * Calcite's rule labels are all flagged by this signal, and their shares were *correct*: they
     * agreed with an independent stack profiler to about a percentage point and produced the one
     * finding this project has to its name. Stopping a run over them would have destroyed it. So a
     * long execution is never fatal. What differs is the advice, and [runningFloorOf] decides
     * which advice applies.
     */
    fun verdictFor(op: OperationStat): String {
        // Sampled state answers this directly and per operation, so it is asked first. The
        // aggregate below is what this had to make do with before phase 6, and it survives only as
        // the fallback for a session that turned state sampling off.
        if (stateSampled && op.stuckHits > 0) {
            val waiting = op.stuckWaitingHits.toDouble() / op.stuckHits
            return when {
                // "Runnable", not "on a core" — the distinction is the whole known limit of this
                // signal. A thread the scheduler preempted is still runnable, so this rules out
                // waiting on another thread and does not rule out waiting for a core. The duty
                // cycle above bounds that second kind, and only in aggregate.
                waiting <= WAITING_NONE -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread runnable - it is not waiting on anything, " +
                            "so the share is honest and the operation wants a coarse label for its per-execution " +
                            "statistics (runnable is not the same as scheduled: see the duty cycle above)",
                    (1 - waiting) * 100
                )

                // The claim the old aggregate test could never make. It is now a measurement of
                // this operation rather than an alibi drawn from the whole run's budget.
                waiting >= WAITING_MOSTLY -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked or blocked - it is *waiting*, " +
                            "not working. Read this share as thread-time: it does not add up across threads the " +
                            "way CPU does, and %s of wall clock had anyone inside it at all",
                    waiting * 100, threadTime(elapsedNanosOf(op))
                )

                else -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked - part working, part waiting, " +
                            "and the split is measured rather than bounded",
                    waiting * 100
                )
            }
        }

        val running = runningFloorOf(op)
        return when {
            running.isNaN() ->
                "long, and with no duty cycle there is no way to say whether it was working or waiting"

            running >= RUNNING_CERTAIN -> String.format(
                Locale.ROOT,
                "at least %.0f%% of that was on CPU, so it is working and not waiting: the share is honest, and " +
                        "the operation wants a coarse label for its per-execution statistics",
                running * 100
            )

            // Not "it is waiting" — that is a claim this test cannot make without sampled state.
            // The whole run's off-CPU time is a single budget and it is charged in full against
            // every operation separately, so a small operation will always come out ambiguous
            // however innocent it is. What the reader can act on is the size of that budget: at 4%
            // nothing here can be mostly waiting, at 35% something is.
            else -> String.format(
                Locale.ROOT,
                "cannot say which - state sampling is off. The run's whole off-CPU time is %.1f%% of the thread-time, " +
                        "and that is enough to account for all of it. Read this share as thread-time rather than " +
                        "as time on a core",
                (1 - duty.duty) * 100
            )
        }
    }

    /**
     * The whole report as text.
     *
     * ## ⚠ CHANGING THE OUTPUT? UPDATE `docs/output.md` IN THE SAME COMMIT ⚠
     *
     * Three things say what these numbers mean and they must agree: this method, the legend it
     * prints at the foot of every report, and [docs/output.md](../../../../../../docs/output.md).
     * The legend stays — a reader with a terminal and no browser needs the short version — so the
     * duplication is deliberate and the drift is the risk. It has already happened twice in this
     * codebase: a KDoc that described a function it had been moved away from, and a `1c` reword
     * that changed the duty lines and missed the column footer thirty lines below.
     *
     * The rule: **a line you add here needs a paragraph there, and a line you reword here needs
     * that paragraph reworded.** `output.md` carries the same warning pointing back at this file.
     */
    /**
     * Thread-time actually on a CPU inside one execution of [c], on average — **summed over every
     * thread that was in it**, which is why it can be larger than the span.
     *
     * `busy/exec = working x mean span`, so an execution fanned across six threads reports six spans
     * of thread-time. Measured on Lucene: a 4.01 ms search with `working` 6.32 reports 25.31 ms here.
     *
     * That makes `mean - busy/exec` the waiting **only while `inside` is 1**, which was every case
     * this tier had until a context could cross a thread. Use [CoarseStat.waitingHits] as a share
     * for the general answer; the subtraction is a shortcut that fan-out invalidates, and the
     * report's legend now says so.
     */
    fun busyPerExecutionNanos(c: CoarseStat): Double =
        if (c.count == 0L) Double.NaN else c.runningInclusiveHits * stepNanos / c.count

    /**
     * The most of [c]'s `working` the measured CPU duty cycle can support.
     *
     * `working` is built on [Thread.getState], and **thread state cannot see through a native
     * call**: a thread stopped inside a socket read is `RUNNABLE` as far as Java is concerned, so it
     * is counted as working. On a workload that waits on something outside the process that is not a
     * small error. Measured on PostgreSQL over a socket: `working` read **2.85** while the operating
     * system said the whole process used 1.03 s of CPU in a 20 s run — the report was crediting
     * **55x** more CPU than the machine ever spent.
     *
     * The duty cycle already knows. [DutyReport.labelledDuty] is measured from `getThreadCpuTime`,
     * which counts time on a processor and does see through the boundary, so `inside x labelledDuty`
     * is what that much occupancy could at most have been doing.
     *
     * **It is a run-wide figure applied to one operation, not a per-type bound**, exactly as the
     * fine tier's *"at most N pp of any share is a thread waiting"* line already is. One operation
     * can be more CPU-bound than the run it sits in, which is why [workingIsContradicted] allows a
     * factor before it complains rather than treating any excess as a fault.
     */
    fun workingCeilingOf(c: CoarseStat): Double =
        if (!duty.available) Double.NaN else c.inside * duty.labelledDuty

    /**
     * Whether [c]'s `working` claims more CPU than the machine was measured to have given it.
     *
     * The factor is slack for the run-wide-versus-per-type mismatch above, and it is deliberately
     * generous: the failure this catches is a factor of fifty, not a factor of two, so nothing is
     * gained by being strict and a false accusation costs the reader their trust in the column.
     */
    fun workingIsContradicted(c: CoarseStat): Boolean {
        val ceiling = workingCeilingOf(c)
        return ceiling.isFinite() && c.working.isFinite() && c.working > ceiling * WORKING_CEILING_SLACK
    }

    /**
     * Whether [c]'s executions ran side by side more than its parent's did: at least [PARALLEL_SHOWN]
     * at once, and that many times its parent's. Above the parent's, not merely different: `select`
     * inside `resolve` runs 5.5 at once only because it is part of `resolve`'s time.
     */
    private fun sideBySide(c: CoarseStat, parent: CoarseStat?): Boolean = moreThan(c.inFlight, parent?.inFlight)

    /**
     * Whether [c]'s executions each spread over more threads than its parent's, with no child row under
     * [n] running side by side to say so - forked work with no coarse label of its own.
     */
    private fun forksUnlabelled(c: CoarseStat, parent: CoarseStat?, n: CoarseNode): Boolean =
        moreThan(c.inside, parent?.inside) &&
                n.children.none { child -> coarse.firstOrNull { it.type == child.type }?.let { sideBySide(it, c) } == true }

    private fun moreThan(v: Double, parentV: Double?): Boolean =
        v.isFinite() && v >= PARALLEL_SHOWN && (parentV == null || !parentV.isFinite() || v / parentV >= PARALLEL_SHOWN)

    /** How much of the observed thread-time happened anywhere under [c]. */
    fun shareOf(c: CoarseStat): Double =
        if (observedNanos <= 0.0) 0.0 else c.inclusiveHits * stepNanos / observedNanos

    /**
     * The shortest [c] was allowed to be, from the tier boundary. See [coarseFloorNanos].
     *
     * The share is **reduced by its own sampling noise** before the share condition is applied.
     * `d ≥ 4 µs × share` only binds above about 20%, so an operation near that line could be pushed
     * over it by counting error alone — and this check prints an accusation. Taking the share a
     * standard error low means an operation is told off for holding too much of the run only when it
     * confidently does. The flat 800 ns floor needs no such treatment, which is the point of the
     * whole tier: **the duration here is measured, not inferred.**
     */
    fun coarseFloorNanosOf(c: CoarseStat): Double {
        val noise = if (c.inclusiveHits <= 0L) 1.0 else 1.0 / sqrt(c.inclusiveHits.toDouble())
        return coarseFloorNanos((shareOf(c) * (1 - noise)).coerceAtLeast(0.0))
    }

    /**
     * Coarse labels on something too small to carry a context.
     *
     * The counterpart of [tooSmall], and it can be strict where that one cannot. The fine floor has
     * to *infer* an operation's duration from hits ÷ calls, so it carries a statistical bound —
     * `hits + 2√hits + 3` — and a bias allowance, because accusing an innocent label is the failure
     * that matters. Here the duration is **measured**, two timestamps per execution, so the
     * comparison is exact and needs no slack at all. The only sampled input is the share, and that
     * is handled in [coarseFloorNanosOf].
     *
     * A warning and never fatal, for the reason the fine floor was demoted to one: a check that
     * stops a run has to be right about a machine it has never seen.
     */
    fun coarseTooSmall(): List<CoarseStat> = coarse
        .filter { it.count > 0 && it.meanSpanNanos < coarseFloorNanosOf(it) }
        .sortedByDescending { it.count }

    /**
     * Of the thread-time inside a fine label, the fraction that fell under **no** coarse span.
     *
     * The one number that can see work escaping its context. A coarse context stays on the thread
     * that created it, so an operation that hands work to a pool has that work running under the
     * callers' fine labels with no context — and its own span reports the gap as *waiting*. There is
     * no other signal for this in a single run: the floor check sees labels that are too small, the
     * balance check sees contexts left open, and neither sees a context that is simply not where the
     * work is.
     *
     * **Two readings, and the report gives both** because it cannot tell them apart:
     *
     * - *you bracketed part of the program and not the rest* — legitimate, and common;
     * - *work is escaping to threads your context never reached* — silent misattribution, and the
     *   thing worth knowing.
     *
     * What separates them is whether the operations named are ones you expected to be inside a span.
     * That is a question about your program, so the report states the measurement and stops.
     */
    val labelledOutsideCoarseShare: Double
        get() = if (fineHits == 0L) 0.0 else labelledOutsideCoarse.toDouble() / fineHits

    /**
     * Of labelled thread-time, the share billed to coarse executions that had already finished.
     *
     * The sibling of [labelledOutsideCoarseShare] and the opposite fault. That one is attribution
     * **lost** — work that reached no span — and it is recoverable by wrapping the hand-off. This is
     * attribution **invented**, and it is the direction the whole design is built to avoid, which is
     * why it is the one thing the sampler stops a strict session over on its own.
     */
    val staleContextShare: Double
        get() = if (coarseSampleHits == 0L) 0.0 else staleContextHits.toDouble() / coarseSampleHits

    /** The fine operations most of that time was in — where to look if it should have been covered. */
    fun outsideCoarseSuspects(): List<OperationStat> {
        if (coarse.isEmpty()) return emptyList()
        val under = LongArray(MAX_OPERATIONS + 1)
        for (c in coarse) for (i in under.indices) under[i] += c.fine[i]
        // Inclusive rows double-count a sample under nested types, so an operation is only named
        // when its labelled hits exceed what every span between them could account for. Erring
        // towards naming nothing is the right direction for a hint.
        return operations
            .filter { it.hits > 0 && it.hits > under[it.id] }
            .sortedByDescending { it.hits - under[it.id] }
    }

    /**
     * The coarse table, or nothing at all when no coarse label was placed.
     *
     * Printed after the fine one and not instead of it, because the two answer different halves of
     * the same question and the report is worth less if a reader has to choose. Fine sampling says
     * what is hot; a span says how long one execution took. Only the pair says *of the time this
     * request spent, this much was that operation*.
     */
    /**
     * Every explanation, collected, after every number.
     *
     * It used to sit *between* the two tables, so the report read numbers, prose, numbers, prose —
     * with `=` and `-` rules doing duty as both table borders and section breaks and not a blank
     * line anywhere. That was reported the first time somebody who had not written it tried to read
     * one: *"a wall of text, good for AI, very bad for a human"*. Explaining a column and
     * interleaving the explanation with the data are not the same decision, and only the first one
     * had ever been made.
     *
     * It is also shorter than it was, and not by cutting. Two of the paragraphs existed to correct a
     * name that promised the wrong thing — `share`, which is occupancy and not CPU, and `busy/exec`,
     * which summed over threads and so could exceed the span beside it. The first was renamed to
     * `occupancy%` and the second dropped as derivable from `working x mean`. A name that carries
     * its own caveat needs no paragraph, and unlike a paragraph it still works on the tenth run.
     *
     * ⚠ Every line here is also a paragraph in docs/output.md. Reword one, reword the other, in the
     * same commit.
     */
    private fun StringBuilder.renderLegend(full: Boolean) {
        appendLine()
        appendLine("HOW TO READ THIS")
        // Four lines, always. Not a summary of the rest — the four things that will make a reader
        // draw the wrong conclusion, and nothing else. Everything below them is reference, and
        // reference belongs in output.md where it can be read once rather than skipped thirty times.
        appendLine("  thread-time is sampled and summed across threads over ACTIVE time, NOT CPU")
        appendLine("  runnable/waiting splits the thread-time beside it; runnable is NOT the same as working")
        appendLine("  concurrency is threads INSIDE at once, running or parked - not a speedup")
        appendLine("  a share is where time went, NOT what removing the operation would save")
        if (!full) {
            appendLine("  full notes: docs/output.md, or render(legend = true)")
            return
        }
        appendLine()
        appendLine("a share is where time went. It is not what removing the operation would save: in the one")
        appendLine("  trial run against real code, an operation holding 46% of the time was worth 275x when")
        appendLine("  removed, because it was creating work for everything else as well as doing its own. The")
        appendLine("  two numbers are different questions and the gap can be orders of magnitude")
        appendLine("thread-time% and thread-time are the same quantity, relative and absolute: sampled and")
        appendLine("  summed across threads, with waiting counted in full. NOT CPU - a Careful line under the")
        appendLine("  header says when the gap matters. The share is of every sample, so it is a share of active")
        appendLine("  time and '(no operation)' is the rest; the absolute one is the column to compare between")
        appendLine("  two runs")
        appendLine("wall time = active + paused. Paused is time every thread was stopped: ticks the sampler ran")
        appendLine("  a whole step late, as far as the JVM's own count of its collections confirms them. A late")
        appendLine("  tick nobody confirmed stays in active time - the sampler may have been stopped alone. No")
        appendLine("  sample is taken in a pause, so the fine table is ACTIVE time throughout: a pause does not")
        appendLine("  move a share, and thread-time and time per call are scaled to the active step. The coarse")
        appendLine("  table is WALL time, two timestamps per execution, so a span includes the pauses inside it")
        appendLine("runnable / waiting are the two halves of the thread-time beside them and add to 100%: the")
        appendLine("  share of its samples whose thread was parked, blocked or waiting, and the rest. Runnable is")
        appendLine("  NOT working - a thread the scheduler merely preempted reads runnable, and so does one")
        appendLine("  stopped inside a native call, which the JVM cannot see into. So the left half is an UPPER")
        appendLine("  BOUND on the work and the CPU clock is the only thing that bounds it in turn, which is what")
        appendLine("  the Careful line about it says; the right half is waiting that some other thread caused")
        appendLine("wall-time is the clock with at least one thread inside, and it is NOT summed across threads,")
        appendLine("  which thread-time is. Not latency either: it is every execution's interval unioned, so it")
        appendLine("  says nothing about any single one of them")
        appendLine("concurrency is thread-time / wall-time: the MEAN number of threads inside this operation")
        appendLine("  while anybody was. The step cancels - thread-ticks over ticks - so the ratio is a count. It")
        appendLine("  is what turns a big thread-time back into real cost: 100 s of waiting is a convoy to break")
        appendLine("  up at 15 and steady contention to design out at 1.7. NOT parallelism: threads inside are")
        appendLine("  not threads executing, and two threads asleep in the same label is a concurrency of 2 with")
        appendLine("  nothing running at all. Multiply by the runnable half for that")
        appendLine("workers is the most threads ever inside at one tick - the maximum of the series concurrency")
        appendLine("  averages. The column to read against what you DISPATCHED: three submitted and workers 2")
        appendLine("  means something is off. Detected, not measured, so it is a lower bound - an overlap that")
        appendLine("  starts and ends between two ticks is one nobody saw")
        appendLine("pool is the distinct threads that ever CALLED the operation, counted by the hook and exact.")
        appendLine("  workers 1 with pool 8 is eight threads queueing through one at a time, which the warnings")
        appendLine("  above say out loud")
        appendLine("noise is 1/sqrt(hits), the error chance alone gives")
        appendLine("implied/call is hits x step / calls; 'over 1 tick' is thread-time inside executions that")
        appendLine("  outlived a tick")
        if (coarse.any { it.count > 0 || it.inclusiveHits > 0 }) {
            appendLine()
            appendLine("  for coarse operations:")
            appendLine("  executions, mean and the percentiles are MEASURED, two timestamps per execution - the")
            appendLine("    only numbers here that are not sampled. Percentiles round UP to their bucket, at most")
            appendLine("    12.5% high and never low, and never above max, which is exact")
            appendLine("  Total sums the executions' durations, so executions side by side sum past the parent's:")
            appendLine("    % parent over 100% is how many ran at once")
            appendLine("  'parallelism: N executions at a time' under a row: its executions ran N side by side on")
            appendLine("    average, more than its parent's did. Under the rows forked into, not the row that forks")
            appendLine("  'parallelism: its work on N more threads' under a row: work it forked with no coarse label")
            appendLine("  the coarse table is a tree: an operation under the one it ran inside, and its figures are")
            appendLine("    the executions on that path alone; the '-' rows under a leaf split its time by the innermost label, sampled")
            appendLine("  'paused' is charged to every span a pause interrupted - not to the span that caused it")
            if (coarseTree.any { t -> t.flatten().any { it.slowest.isNotEmpty() } }) {
                appendLine("  'slowest:' under a row opened with a key: its longest executions on that path, measured,")
                appendLine("    wall time with any pause in it")
            }
        }
    }

    private fun StringBuilder.renderCoarse() {
        val seen = coarse.filter { it.count > 0 || it.inclusiveHits > 0 }
        if (seen.isEmpty()) return
        appendLine()
        // Wall time, not active: a span is two timestamps, and a request that sat through a 2.6 s
        // collection took 2.6 s longer. The pauses stay in - the caller waited for them - and the
        // `Paused` column says how much of each row they were. Taking them out would make a
        // 9.07 s level look like an 8.5 s one, and where they fell is itself a lead: the span that
        // collects is the span that allocated, or the one after it. Andrey, 2026-10-13.
        //
        // The table is the tree. It was a flat table sorted by total with a tree under it, and the
        // two said the same executions and totals twice. Inclusive time is monotone up a path, so
        // the heaviest operation anywhere is at the end of the first branch - the ranking holds.
        val pausedInSpan = pause.confirmedPausedIn(samplingSpanNanos)
        appendLine(
            // Durations, not wall time: each execution is timed by the clock, but `Total` adds them up,
            // and executions that ran side by side sum past the wall time they shared - 31.5 s of
            // resolveRoot inside a 3.19 s batch. Andrey, 2026-10-23.
            "COARSE OPERATIONS - measured durations, inclusive, nested as it ran" +
                    if (pausedInSpan > 0) "; a pause is charged to every operation it interrupted" else ""
        )
        appendLine(
            String.format(
                Locale.ROOT, COARSE_ROW.replace("%,11d", "%11s"), "Coarse operation", "Executions", "x parent", "Total v", "% parent", "Paused", "Mean",
                "p50", "p90", "p99", "Max", "Runnable / Waiting"
            )
        )
        appendLine("-".repeat(COARSE_WIDTH))
        val byType = coarse.associateBy { it.type }
        val nodesOf = coarseTree.flatMap { it.flatten() }.groupBy { it.type }

        fun waitSplit(inclusive: Long, running: Long): String =
            runnableSplit(if (inclusive == 0L) 0.0 else (inclusive - running).toDouble() / inclusive)

        fun shareText(share: Double): String = if (share < 0.5) "<1%" else String.format(Locale.ROOT, "%.0f%%", share)

        // Against the row above: how many executions per execution of the parent - the loop count -
        // and what part of the parent's total this row is. Left blank where there is nothing to
        // read: a root has no parent, and a child run exactly once per parent execution is x1. The
        // share is not capped at 100%: children forked onto other threads sum past their parent's
        // wall time, and the excess is the parallelism. Andrey, 2026-10-16.
        fun perParent(n: CoarseNode, parent: CoarseNode?): String = when {
            parent == null || parent.count == 0L || n.count == parent.count -> ""
            else -> {
                val x = n.count.toDouble() / parent.count
                if (x < 100) String.format(Locale.ROOT, "x%.1f", x) else String.format(Locale.ROOT, "x%,.0f", x)
            }
        }

        fun shareOfParent(n: CoarseNode, parent: CoarseNode?): String = when {
            parent == null || parent.spanSumNanos == 0L || n.count == 0L -> ""
            else -> {
                shareText(n.spanSumNanos * 100.0 / parent.spanSumNanos)
            }
        }

        var anySplit = false

        fun StringBuilder.node(n: CoarseNode, depth: Int, parent: CoarseNode?) {
            val indent = "  ".repeat(depth)
            // An execution still open when the session ended has no span: its row keeps the
            // samples and the children, and says `-` where the clock would go.
            val timed = n.count > 0
            appendLine(
                String.format(
                    Locale.ROOT, COARSE_ROW,
                    indent + n.name, n.count, perParent(n, parent),
                    if (timed) duration(n.spanSumNanos.toDouble()) else "-",
                    shareOfParent(n, parent),
                    if (n.pausedNanos > 0) duration(n.pausedNanos.toDouble()) else "-",
                    if (timed) duration(n.meanSpanNanos) else "-",
                    if (timed) duration(n.percentileNanos(0.50)) else "-",
                    if (timed) duration(n.percentileNanos(0.90)) else "-",
                    if (timed) duration(n.percentileNanos(0.99)) else "-",
                    if (timed) duration(n.spanMaxNanos.toDouble()) else "-",
                    waitSplit(n.inclusiveHits, n.runningInclusiveHits)
                )
            )
            // Which executions were the slowest, when the operation was opened with a key: a
            // percentile says how slow, and this says which, so the case can be run again.
            n.slowest.forEachIndexed { i, slow ->
                appendLine(
                    indent + "  " + (if (i == 0) "slowest: " else "         ") +
                            String.format(Locale.ROOT, "%9s  %s", duration(slow.nanos.toDouble()), slow.key)
                )
            }
            // The leaf's time split by the innermost label, on the leaves only: a parent's would be
            // its children's over again. **A split, not children** - each sample inside the leaf has
            // one innermost label, so the rows are own time and add up to 100%. That stays true when
            // fine labels nest, and drawing them as children would not: a fine label with labels
            // inside it would read as containing them, at a share that is only its own part. The
            // nesting cannot be seen for free - the outer label lives only in the hook's `prev`, on
            // the thread's stack - so the rows say `own` and carry a `-`, and do not look like one
            // more level of the tree. For a hierarchy, the outer label is made coarse and pays
            // for it. plan.md, "Fine rows in the coarse table are a split", 2026-10-16.
            //
            // The total is the share of the leaf's samples applied to the leaf's measured total, so
            // it agrees with `% parent`. It was one `made of:` line until 2026-10-16.
            if (n.children.isEmpty() && n.inclusiveHits > 0) {
                val parts = operations
                    .map { it.name to n.fine[it.id] }
                    .plus("unlabelled" to n.fine[NO_OP_INDEX])
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                if (parts.isNotEmpty()) anySplit = true
                for ((name, hits) in parts.take(FINE_ROWS_SHOWN)) {
                    val share = hits.toDouble() / n.inclusiveHits
                    appendLine(
                        String.format(
                            Locale.ROOT, COARSE_ROW.replace("%,11d", "%11s"),
                            "$indent  $SPLIT_MARK $name", "own", "",
                            if (timed) duration(n.spanSumNanos * share) else "-",
                            shareText(share * 100), "", "", "", "", "", "", ""
                        ).trimEnd()
                    )
                }
                val rest = parts.drop(FINE_ROWS_SHOWN)
                if (rest.isNotEmpty()) appendLine(
                    "$indent  $SPLIT_MARK and ${rest.size} more, " + shareText(rest.sumOf { it.second } * 100.0 / n.inclusiveHits) + " between them"
                )
            }
            // How many ran side by side, under the rows that did: `branch` 3.90 at once, which is the
            // `% parent` over 100% read out. The row that forks gets nothing - a request that waits on
            // its branches is not itself parallel, and "4.36 threads per execution" there counted the
            // parked caller with the branches. Andrey, 2026-10-23, on `:sandbox:branching`, after a
            // version that printed both. The one exception is forked work with no coarse label of its
            // own, where no child row exists to carry the line: then the fork is said on the parent.
            // Only on a run with more than one thread, where these numbers can be anything but 1.
            val c = byType[n.type]
            if (threads > 1 && c != null) {
                val above = parent?.let { byType[it.type] }
                val line = when {
                    // Executions, the table's own word: a row's execution can run on several threads.
                    // One decimal: the second of a sampled average is noise. No "on a CPU" beside it -
                    // that was Java's RUNNABLE under a stronger name, and the row's Runnable / Waiting
                    // already gives the split. Andrey, 2026-10-23.
                    sideBySide(c, above) ->
                        String.format(Locale.ROOT, "%.1f executions at a time, on average", c.inFlight)
                    forksUnlabelled(c, above, n) ->
                        String.format(Locale.ROOT, "its work on %.1f more threads, on average", c.inside - 1)
                    else -> null
                }
                if (line != null) appendLine("$indent  parallelism: $line")
            }
            for (child in n.children.sortedWith(compareByDescending<CoarseNode> { it.spanSumNanos }.thenByDescending { it.inclusiveHits })) {
                node(child, depth + 1, n)
            }
        }
        for (root in coarseTree.sortedWith(compareByDescending<CoarseNode> { it.spanSumNanos }.thenByDescending { it.inclusiveHits })) {
            node(root, 0, null)
        }
        appendLine("-".repeat(COARSE_WIDTH))
        // The key, once and next to the rows that need it, as the star's is under the own-time table.
        if (anySplit) appendLine(
            "  $SPLIT_MARK rows split a leaf's time by the innermost label, each sample once - they add up to 100%;" +
                    " a fine label with labels inside it shows only its own part"
        )
        // An operation with more than one caller is a row under each, and those rows are its
        // figures under that caller alone. Its percentiles over everything are a different
        // question - "how long does a parse take" - and they are answered here, once, only when
        // the question exists.
        val shared = seen.filter { (nodesOf[it.type]?.size ?: 0) > 1 }.sortedByDescending { it.spanSumNanos }
        if (shared.isNotEmpty()) {
            appendLine("  Under more than one caller - all callers together:")
            for (c in shared) {
                val paused = nodesOf[c.type]!!.sumOf { it.pausedNanos }
                appendLine(
                    String.format(
                        Locale.ROOT, COARSE_ROW,
                        "  " + c.name, c.count, "",
                        duration(c.spanSumNanos.toDouble()), "",
                        if (paused > 0) duration(paused.toDouble()) else "-",
                        duration(c.meanSpanNanos), duration(c.percentileNanos(0.50)),
                        duration(c.percentileNanos(0.90)), duration(c.percentileNanos(0.99)),
                        duration(c.spanMaxNanos.toDouble()),
                        waitSplit(c.inclusiveHits, c.runningInclusiveHits)
                    )
                )
            }
            appendLine("-".repeat(COARSE_WIDTH))
        }
        // A context made by hand rather than by the hook has no path and is in no tree. Its type
        // still has its per-type figures; said here so it is not lost.
        val missing = seen.filter { nodesOf[it.type] == null }
        if (missing.isNotEmpty()) appendLine("  not in the tree, its contexts were made outside the hook: " + missing.joinToString { it.name })
        // The one signal for work escaping its context, which neither the floor check nor the
        // balance check can see. Stated as a measurement with both readings, because it cannot tell
        // "I bracketed part of the program" from "work escaped to another thread" and guessing would
        // be worse than saying so.
        if (labelledOutsideCoarseShare >= OUTSIDE_COARSE_MIN_SHARE) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%n%s of thread-time inside fine operations (%s) was inside NO coarse span.",
                    labelledOutsideCoarseShare * 100, threadTime(labelledOutsideCoarse * stepNanos)
                )
            )
            val suspects = outsideCoarseSuspects().take(4)
            if (suspects.isNotEmpty()) {
                appendLine("  mostly: " + suspects.joinToString(", ") { it.name })
            }
            appendLine("  Two readings and this cannot tell them apart. Either you bracketed part of the program")
            appendLine("  and not the rest - fine - or work is ESCAPING its context onto threads it never reached,")
            appendLine("  and then those threads' time is missing from its busy/exec and shows up as its waiting.")
            appendLine("  Ask whether the operations above are ones you expected to be inside a span.")
        }
        // The opposite fault to the line above, and the more serious one. That one is attribution
        // lost and says so; this is attribution invented, and it is stated with the remedy because
        // there are two quite different causes with opposite fixes.
        // The column cannot see a native wait, and on a workload that does nothing else that is not a
        // rounding error. Named per type, with both readings, because the number is not useless —
        // it is right on a CPU-bound operation and it is the *reader* who knows which they have.
        val contradicted = coarse.filter { it.count > 0 && workingIsContradicted(it) }
        if (contradicted.isNotEmpty()) {
            appendLine()
            appendLine("!".repeat(WIDTH))
            appendLine("`on a CPU` READS HIGHER THAN THE MEASURED TIME ON CPU CAN SUPPORT")
            for (c in contradicted.sortedByDescending { it.working }) {
                appendLine(
                    String.format(
                        Locale.ROOT, "  %-22s on a CPU %.2f, but the measured time on CPU bounds it at %.2f",
                        c.name, c.working, workingCeilingOf(c)
                    )
                )
            }
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  Threads inside these were on a CPU %.2f%% of the time, measured from the operating",
                    duty.labelledDuty * 100
                )
            )
            appendLine("  system's own clock. `on a CPU` is built on thread state, and a thread stopped inside a")
            appendLine("  NATIVE call - a socket read, a file read, an epoll wait - reads RUNNABLE to Java. So it")
            appendLine("  is counted as working, and `waiting` is short by the same amount.")
            appendLine("!".repeat(WIDTH))
        }
        if (staleContextHits > 0) {
            val worst = coarse.maxByOrNull { it.staleHits }
            appendLine()
            appendLine("!".repeat(WIDTH))
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%s of the thread-time inside coarse executions (%s) was inside one that " +
                            "had ALREADY BEEN CLOSED",
                    staleContextShare * 100, threadTime(staleContextHits * stepNanos)
                )
            )
            if (worst != null && worst.staleHits > 0) appendLine("  mostly under: ${worst.name}")
            appendLine("  It is excluded from every number in the table above rather than inflating them.")
            appendLine("  Either work was handed to another thread and never waited for, so it outlived the span")
            appendLine("  carrying it - propagate only what the request actually joins - or a span is closed too")
            appendLine("  early, before the work it covers has finished.")
            appendLine("!".repeat(WIDTH))
        }
    }

    /**
     * One line of the operations table: a fine operation, or a coarse operation's own work.
     *
     * The two tiers share a table because a coarse label collects everything a fine one does and
     * more, and moving an operation between tiers used to move it between tables with different
     * columns - the report's shape changing when the label changed rather than when the program
     * did. What makes one table honest is that the hits partition: a fine operation's are the
     * samples where it was the innermost label, a coarse operation's are the samples inside its
     * span with no fine label open, and no sample is in two rows. So the share column adds to 100%
     * and the sort means something, which an inclusive coarse number could not do - `request` would
     * outrank `work1` while containing it.
     *
     * Every column here is sampled, for both tiers, with one provenance per column. The coarse tier
     * knows exact numbers for two of them - the measured span mean, and wall time as a union of
     * measured intervals - and using them would make a column whose rows are exact or inferred with
     * nothing saying which, and whose values answer different questions: an inclusive wall time per
     * execution against a self thread-time per call. Those live in the coarse table above, which is
     * what it is for.
     */
    inner class OperationRow internal constructor(
        val name: String,
        val coarse: Boolean,
        val hits: Long,
        /** Samples anywhere inside the span, for a coarse row; equal to [hits] for a fine one. */
        val inclusiveHits: Long,
        val runningHits: Long,
        val activeTicks: Long,
        val peakInside: Int,
        val threads: Int,
        val calls: Long,
        /** Null for a coarse row: the long-execution detector is a fine-tier instrument. */
        val stuckShare: Double?,
        /** The `Long` group: executions over a tick, the thread-time in them, the longest. Fine rows only. */
        val stuckInstances: Long = 0,
        val stuckHits: Long = 0,
        val longestStuckTicks: Long = 0,
    ) {
        /**
         * Whether the `Long` group prints for this row: a fine row whose share in long executions
         * is above what preemption and GC alone could give it. At or under the floor the three
         * columns are a dash, and the line under the table says what the floor was.
         */
        val longShown: Boolean
            get() = stuckShare != null && stuckInstances > 0 && stuckShare > machineFloor

        /** The name as printed: a star marks a row whose full span is in the coarse table. */
        val label: String get() = if (coarse) "$name *" else name

        fun shareOf(): Double = if (allHits == 0L) 0.0 else hits.toDouble() / allHits

        val waitingShare: Double get() = if (hits == 0L) 0.0 else (hits - runningHits).toDouble() / hits

        fun noiseFloor(): Double = if (hits <= 0) 0.0 else 1.0 / sqrt(hits.toDouble())

        fun perCallNanos(): Double = if (calls == 0L) Double.NaN else hits * stepNanos / calls

        fun concurrency(): String =
            if (activeTicks == 0L) "-"
            else String.format(Locale.ROOT, "%.2f", hits.toDouble() / activeTicks)

        fun workers(): String = if (peakInside <= 0) "-" else peakInside.toString()

        fun pool(): String = if (threads <= 0) "-" else threads.toString()
    }

    /**
     * Both tiers as one list of rows, unsorted.
     *
     * A coarse operation with no self hits still gets a row, and the fold below counts it: an
     * operation that delegated everything it did is a real thing to know, and its executions and
     * percentiles are in the table underneath either way.
     */
    fun rows(): List<OperationRow> =
        operations.map {
            OperationRow(
                it.name, coarse = false, hits = it.hits, inclusiveHits = it.hits, runningHits = it.hits - it.waitingHits,
                activeTicks = it.activeTicks, peakInside = it.peakInside, threads = it.threads,
                calls = it.calls, stuckShare = it.stuckShare,
                stuckInstances = it.stuckInstances, stuckHits = it.stuckHits, longestStuckTicks = it.longestStuckTicks,
            )
        } + coarse.map {
            OperationRow(
                it.name, coarse = true, hits = it.selfHits, inclusiveHits = it.inclusiveHits, runningHits = it.selfRunningHits,
                activeTicks = it.selfActiveTicks, peakInside = it.selfPeak, threads = it.threads,
                calls = it.count, stuckShare = null,
            )
        }

    /**
     * Every warning in one place, headed by the word and numbered.
     *
     * They used to be printed where each was computed, which put the fine tier's warnings *below
     * the coarse table* — fifteen lines from the number they were about and under the wrong
     * heading, so a warning about a fine operation read as one about a coarse one. A leading `!`
     * did not say "warning" either, and by the severity ladder in plan.md that is what these are:
     * the measurement is honest, but the label is in the wrong tier or the run limits what its
     * numbers mean.
     *
     * Capped at [MAX_WARNINGS], with the remainder counted rather than listed. Calcite would
     * produce one of these per rule, and a section that runs to fifty entries is one the reader
     * learns to scroll past.
     */
    private fun StringBuilder.renderWarnings() {
        // Ordered by what each costs the reader: numbers that are missing or inflated first, then
        // labels in the wrong tier, which are honest measurements of the wrong-sized thing.
        val warnings = buildList {
            if (untrackedSlots > 0) add(
                "$untrackedSlots threads arrived past the $MAX_SLOTS-slot ceiling and were NOT SAMPLED -\n" +
                        "their thread-time is missing from every number above, including the denominators"
            )
            // No warning for threads that exited without Profiler.release(): the sampler skips a
            // thread that has exited and the slot is reclaimed within a second, so nothing above is
            // distorted. It was a warning until 2026-10-23, when a dead thread read as idle until a
            // garbage collection let it go.
            if (openContextsAtEnd > 0) add(
                "$openContextsAtEnd coarse executions were still open when sampling stopped: their spans\n" +
                        "are missing above, and every sample taken inside them was billed to them"
            )
            for (op in suspect()) add(
                String.format(
                    Locale.ROOT,
                    "%s: %,d executions lasted over a tick (%s of its thread-time against a %s machine floor, %s per call)",
                    op.name, op.stuckInstances, percent(op.stuckShare * 100), percent(machineFloor * 100),
                    duration(impliedNanosOf(op))
                ) + "\n" + verdictFor(op)
            )
            // The `Workers` and `Pool` columns, read together and said out loud.
            for (op in serialised()) add(
                "${op.name}: ${plural(op.threads.toLong(), "thread")} ran this operation and no two " +
                        "were ever inside it at once.\nEvery thread that enters waits for the last " +
                        "one to leave, so this is a serialization point rather than parallel work."
            )
            // The other end of the same question, and a warning rather than a verdict: a label
            // below the floor cannot move a ranking, so the cost of being wrong about one is the
            // loudest failure this tool has — stopping a run that was fine. It did exactly that in
            // two consecutive trials. Every offender is named; the reader decides.
            for (op in tooSmall()) add(tooSmallMessage(op.name, op.calls, impliedUpperNanosOf(op)))
            // The same boundary from the other side. Unlike the fine floor this one is exact,
            // because the duration it compares is measured rather than inferred — see
            // coarseTooSmall.
            for (c in coarseTooSmall()) add(
                coarseTooSmallMessage(c.name, c.count, c.meanSpanNanos, coarseFloorNanosOf(c))
            )
        }
        if (warnings.isEmpty()) return
        appendLine()
        appendLine("WARNINGS (${warnings.size})")
        warnings.take(MAX_WARNINGS).forEachIndexed { i, w ->
            // The number is the hanging indent: continuation lines align under the first line's
            // text rather than under its number, whatever indentation the message arrived with.
            val lines = w.lines()
            appendLine(String.format(Locale.ROOT, "  %2d. %s", i + 1, lines.first().trim()))
            for (line in lines.drop(1)) appendLine("      " + line.trim())
        }
        if (warnings.size > MAX_WARNINGS) {
            appendLine("      ... and ${warnings.size - MAX_WARNINGS} more")
        }
    }

    /**
     * @param legend every column explained, at the bottom. Off by default: see [renderLegend].
     */
    fun render(legend: Boolean = false): String = buildString {
        if (failure != null) {
            appendLine("!".repeat(WIDTH))
            appendLine("PROFILING STOPPED - these numbers were not going to be right:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(WIDTH))
        }
        // The report used to open on a rule and a statistics line, so nothing on screen said what
        // the block of text was and a reader scrolling back had no landmark to stop at.
        //
        // Five lines against a report of twenty-three is not free, and it was chosen knowing that.
        // Backslashes are doubled here and nowhere else in this file, which is the one way this
        // block can be broken by a well-meaning edit.
        // One long rule before the logo and one after the legend: the report lands in a console
        // full of the program's own output, and its first and last lines should say so. No rules
        // between sections - the headings and the tables' own rules do that, and a rule beside a
        // table's rule is the doubled separator from the first graph run.
        appendLine("=".repeat(WIDTH))
        appendLine(" _____ ___ ____ _  ______  _   _ ___ ____ _  __")
        appendLine("|_   _|_ _/ ___| |/ / ___|| \\ | |_ _/ ___| |/ /")
        appendLine("  | |  | | |   | ' /\\___ \\|  \\| || | |   | ' /")
        appendLine("  | |  | | |___| . \\ ___) | |\\  || | |___| . \\")
        appendLine("  |_| |___\\____|_|\\_\\____/|_| \\_|___\\____|_|\\_\\")
        appendLine()
        // One line: wall time split into active and paused. Andrey's two criteria for what the
        // report shows, 2026-10-11: it matters for optimising performance, or it says the report is
        // unreliable. The split is the first - GC tuning is about those figures - and everything
        // the header used to carry besides it was either a mechanism (the step, the clock, the
        // windows) or a line that only said "fine" (coverage, time on CPU, a verdict). Silence
        // says "fine" now, and a `Careful` line says what is unreliable, before the numbers it is
        // about and not in a footnote after them.
        appendLine(pause.headerLine(samplingSpanNanos))
        // The clock only when it moved or could not be read: a steady clock is the healthy case, and
        // its level alone says nothing about this run. Two runs' levels are compared in the diff.
        // A line with breaks in it continues under its own text, as a warning does.
        for (c in pause.careful() + duty.careful(activeNanos, reclaimedSlots) + clock.careful()) {
            val lines = c.lines()
            appendLine(row("Careful", lines.first()))
            for (line in lines.drop(1)) appendLine(row("", line))
        }
        // The warnings here, before the tables, for the same reason `Careful` is: a warning is read
        // before the numbers it is about, not found thirty lines after them. They used to close the
        // report, and Andrey met one - a coarse label on a 40 ns lookup - under the second table.
        // A leaked label does not look like an error. It looks like a finding: one operation
        // quietly accumulating everybody else's samples, with a plausible number beside it.
        if (imbalances > 0 || openAtEnd > 0) {
            appendLine()
            if (imbalances > 0) appendLine(
                "! $imbalances operation labels were still open at a point the caller said should be quiescent - " +
                        "everything after each leak was billed to the leaked operation"
            )
            if (openAtEnd > 0) appendLine(
                "! $openAtEnd threads were still inside a hand-placed operation label when sampling stopped, " +
                        "nothing can close now"
            )
            appendLine("  an operation label placed with enter/exit has no finally: a body that throws leaves it set")
        }
        renderWarnings()
        // The tree first: the overview - what ran inside what, how often, what part of its parent -
        // and the own-time table after it for the detail. It was the other way round until
        // 2026-10-16, when Andrey said the tree is what he reads and the table is what he looks up.
        renderCoarse()
        appendLine()
        // FINE, not bare OPERATIONS. `OPERATIONS` beside `COARSE OPERATIONS` makes one tier the
        // default and the other the exception, which is the asymmetry `registerFine`/`registerCoarse`
        // was introduced to remove — reproduced in the output an hour after it was fixed in the API.
        // The heading carries the one thing a reader has to hold between the two tables. A coarse
        // operation appears in both with different numbers - `request` at 5.03% of its own work
        // here and 99.6% including everything it contains above - and without the two words that
        // reads as a contradiction rather than as two questions.
        // The clock the table is over, said once in the heading and not in every column name: a
        // thread-time is a sum over threads and `active` is the span it was summed in, and on one
        // thread the two figures match, which is the reader's check that they have understood it.
        appendLine("COARSE AND FINE OPERATIONS - own time, out of ${duration(activeNanos.toDouble())} active time")
        // The band over the column heads. Eleven columns is more than anyone reads as a list, and
        // the groups are the reading order: how much time went here, how it was spread over
        // threads, and how far the row can be trusted.
        //
        // The third band repeats the name of a column inside it, which is provisional and known to
        // be so - "just name the group Calls for now". The group's natural name is the noun its
        // first column already carries, and the ways out are to rename that column (`Count`, with
        // the band acting as its prefix) or to leave the band empty and let the two heads speak for
        // themselves. Recorded in ideas.md rather than settled here.
        // Both tiers, sorted together further down. Computed here because everything from the
        // band downwards is printed only if some row survived - and until the tiers shared a table
        // that test was "any fine operation was sampled", which printed a headerless table of
        // coarse rows the first time a sandbox labelled everything coarse.
        // A coarse operation with no own time is not a row: three rows of zeros at the bottom are
        // not digits. It is one line under the table, worded for what happened - all of its time
        // was inside other operations, which is the ordinary state of a span around labelled work -
        // and not as "never sampled", which it was not. Andrey's case, 2026-10-12: `query` inside
        // `runBatch` inside `run`, each taking all of the time of the one around it.
        val allRows = rows()
        val contained = allRows.filter { it.coarse && it.hits == 0L && it.inclusiveHits > 0 }
        val (seen, unseen) = allRows.filter { it !in contained }.partition { it.hits > 0 }
        // Time inside no label, as a row rather than a `Coverage` line: it is the time the reader
        // has no information about, and it belongs in the ranking with everything else. Its
        // `Runnable / Waiting` is the first question about it - idle threads, or work nobody
        // labelled - and the row answers it where the header used to.
        val outside = if (unlabelledHits > 0) listOf(
            OperationRow(
                NO_OPERATION, coarse = false, hits = unlabelledHits, inclusiveHits = unlabelledHits,
                runningHits = if (stateSampled) unlabelledHits - unlabelledWaitingHits else unlabelledHits,
                activeTicks = 0, peakInside = 0, threads = 0, calls = 0, stuckShare = null,
            )
        ) else emptyList()
        val anyRow = seen.isNotEmpty() || outside.isNotEmpty()
        if (anyRow) appendLine(
            band(
                // `Load` runs through the calls: thread-time, calls and per call are one line of
                // arithmetic, and the `Calls` band only ever repeated its first column (ideas 29).
                // `Long` sits right after them so the sentence continues - so many calls at this
                // much each, of which these ran long - and `Spread` follows, because how the time
                // lay across threads is a different question and on one thread a column of ones.
                // Andrey, 2026-10-13.
                FINE_COLUMNS, FINE_BREAKS,
                1..5 to "Load", 6..8 to "Long", 9..12 to "Spread", 13..14 to "Trust"
            )
        )
        if (anyRow) appendLine(
            String.format(
                // `v` marks the column the table is sorted by, which nothing said before - and at
                // two rows a reader cannot tell a sorted table from an unsorted one by looking.
                // ASCII, not an arrow glyph: a Windows console is not UTF-8 by default.
                //
                // 22 and 12 rather than 26 and 8 so the wider label still ends where the values
                // end. The header was already two columns out of step with its rows before the
                // marker was added, because `Occupancy%` never fitted the 8 it was given.
                // The bars force the header and the data rows onto the same column widths. They
                // had differed - 20/14 against 26/8 - so that `Thread-time% v` could sit over an
                // 8-wide number without shifting everything after it, and that trick is invisible
                // until a vertical line is drawn through it and comes out crooked.
                Locale.ROOT, "%-26s | %14s %11s %18s %13s %20s | %7s %8s %8s | %9s %11s %7s %5s | %8s %6s",
                // `occupancy%` and not `share`, because it is the same quantity as the column beside
                // it — one relative, one absolute. Calling one of them `share` invented a
                // distinction that does not exist and hid the one that does: neither is CPU. A
                // reader does not misread `occupancy%` as processor time, which is what a paragraph
                // of legend used to be for.
                // `Thread-time` and `Wall-time`, not `occupancy` and `elapsed`. The pair is the
                // point of having both columns - eight threads computing five seconds is forty
                // thread-seconds and the sum is real, a hundred threads parked one second on a lock
                // is also a hundred and there the sum is fiction - and neither old name said which
                // was which. `occupancy` was the worse of the two: the rest of the report already
                // called that quantity thread-time, in `Coverage` and on the coarse lines, so one
                // quantity had two names.
                //
                // The concurrency column is gone. It was `threads`, renamed to `in flight` to stop
                // it being read as one execution spread over several threads, and neither name
                // landed: a reader who wanted exactly this number could not find it under the
                // first name and could not read it under the second.
                //
                // It was `Thread-time / Wall-time` with both operands printed beside it, which is
                // the argument that deleted `busy/exec`. A quantity needing a paragraph before it
                // can be read, and derivable from its neighbours, does not earn a column - and now
                // that those neighbours are named for what they are, the division says it plainly.
                // Both halves of the split, summing to 100%, rather than the waiting half alone.
                // Asked whether waiting was part of thread-time or on top of it, neither the unit
                // nor a header phrasing settles it - but two shares that add to the whole cannot be
                // read as an addition to it. Andrey's suggestion, and better than the "of which
                // waiting" header it replaced.
                //
                // `Runnable`, not `Run` or `CPU`. The `-able` is the whole distinction: it means
                // *not blocked*, not *executing*. A preempted thread reads runnable - 14-18% of
                // wall time on this machine on a bench that never blocks - and so does a thread
                // stopped in a native call, which is how the JDBC trial got a number 55x above what
                // the machine had spent. The left half is an upper bound on work; only the
                // time-on-CPU block above can say how much of it was real.
                // `Concurrency / Threads` rather than leaving it to be divided out of the two
                // columns before it. It came back after being dropped, on the grounds that a report
                // about a threaded program should not make a person do the arithmetic that tells
                // them whether it was threaded - which is right, and is the same standard the
                // `Runnable / Wait` split is held to.
                //
                // `Concurrency` and not `Parallelism`: the number counts threads INSIDE the label
                // whether they are running or parked, which is what concurrency means; parallelism
                // needs them executing. The run that asked for this column is the argument - two
                // threads inside `work1` at 7.2% runnable, so concurrency 2.00 and a parallelism of
                // about 0.14, and calling the 2.00 parallelism would have been flatly false.
                //
                // `Concurrency`, `Workers`, `Pool` - three columns and no slash between them, which
                // took three tries. The concurrency figure used to carry a denominator: first the
                // run's own thread high-water mark, then the threads that ever ran the operation,
                // and both answer a question about the deployment from inside a row about an
                // operation. A pool of four that never runs more than three tasks at once printed
                // `3.00 / 4`, where the 4 is a ThreadPoolExecutor detail - it opens a new core
                // thread on every submit until the core size is reached, idle workers or not.
                //
                // Pairing the mean with the maximum instead was no better: `Concurrency / Peak`
                // reads as the name of one quantity while the number to its left is a mean, and a
                // fractional average and an integer maximum are not two views of one whole the way
                // `Runnable / Wait` is. Three numbers, three headings, each saying what it is.
                //
                // The names are Andrey's, and the argument for them is that each row is then read
                // off its own heading with no arithmetic: *concurrency 1.1, so concurrency here is
                // poor; workers 2, but I dispatched 3, so something is off; pool 4, which is what I
                // configured.* `Max`, `Peak` and `Most` name a statistic and leave the reader to
                // supply the noun; `Workers` names what was counted, and says "detected" rather
                // than "measured", which is what a sampled maximum honestly is.
                "Operation", "Thread-time% v", "Thread-time", "Runnable / Waiting",
                // `Impl/call` sits beside `Calls` because it is hits x step / calls, and it had
                // been stranded among the statistics.
                // `Thread-time per call` and not `Impl/call`, which was Andrey's: "implied" singled
                // this column out as inferred when every number in the table except `Calls` is
                // sampled, and `Impl` reads as implementation. The name states the relationship
                // instead - both operands are columns on the same row - and inherits the caveat
                // that `Thread-time` already carries. It also drops a third meaning for the slash,
                // which reads as "per" here and as "two values" twice to its left.
                "Calls", "Thread-time per call",
                // The long executions, read off the calls they are part of: how many ran over a
                // tick, the thread-time inside them, and the longest. `Over 1t` was the share
                // alone, in Trust, and the resizes on the graph benchmark were read out of it as
                // a finding nobody else could follow: "you make conclusions out of 'Trust'
                // column, which looks more like a secondary column". The count tells a resize
                // from a lock; the time is what fixing it is worth; the longest tells two dozen
                // of ten milliseconds from one of two seconds.
                "Execs", "Time", "Longest",
                "Wall-time", "Concurrency", "Workers", "Pool", "Hits", "Noise"
            )
        )
        if (anyRow) appendLine(rule(FINE_COLUMNS, FINE_BREAKS))
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes — Calcite's report carried twenty-five rules at 0.000%. Folded, not dropped: the
        // count says how many there were, and a *called* operation with no samples is a real
        // finding, since it means the label is on something too small to see.
        // Partitioned above the header rather than here, because whether the header prints at
        // all depends on it. See where `seen` is declared.
        // A table with no rows is two rules around nothing, and the twenty lines of column
        // explanation that used to follow it explained columns that had no data. Say what happened
        // instead: an empty table is nearly always a run too short for the sampler to catch, and
        // that is worth naming rather than leaving the reader to infer from a blank.
        // It says "run for longer" and not "or lower stepMillis", which it used to: below
        // MIN_TICKS_FOR_A_TABLE there is no step that rescues the run. Two ticks would need a step
        // of 0.04 ms to reach fifty, which measures the sampler rather than the program, and fifty
        // samples still says nothing. Lowering the step is real advice for an operation that is
        // called but never caught in a run long enough to be real - that case is the fold below.
        if (!anyRow) {
            appendLine(
                if (labelledHits == 0L && ticks < MIN_TICKS_FOR_A_TABLE)
                    "  nothing was sampled - $ticks ticks is too few for the sampler to catch anything. " +
                            "Run for longer."
                else "  nothing was sampled."
            )
        }
        for (r in (seen + outside).sortedByDescending { it.hits }) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-26s | %14s %11s %18s %13s %20s | %7s %8s %8s | %9s %11s %7s %5s | %8d %6s",
                    r.label, percent(r.shareOf() * 100), threadTime(r.hits * stepNanos),
                    runnableSplit(r.waitingShare),
                    if (r.name == NO_OPERATION) "-" else String.format(Locale.ROOT, "%,d", r.calls),
                    duration(r.perCallNanos()),
                    // A dash for a coarse row rather than figures: the long-execution detector is
                    // a fine-tier instrument, and a coarse operation has percentiles below, which
                    // answer the same question exactly instead of by a threshold. And a dash at
                    // or under the machine floor, where the figures would be preemption.
                    if (r.longShown) String.format(Locale.ROOT, "%,d", r.stuckInstances) else "-",
                    if (r.longShown) threadTime(r.stuckHits * stepNanos) else "-",
                    if (r.longShown) duration(r.longestStuckTicks * stepNanos.toDouble()) else "-",
                    // Nothing counts the ticks with some thread inside no label, so the row has no
                    // wall time rather than a wall time of zero.
                    if (r.name == NO_OPERATION) "-" else threadTime(r.activeTicks * stepNanos),
                    r.concurrency(), r.workers(), r.pool(),
                    r.hits, percent(r.noiseFloor() * 100),
                )
            )
        }
        if (anyRow) appendLine(rule(FINE_COLUMNS, FINE_BREAKS))
        if (contained.isNotEmpty()) {
            appendLine(
                "  " + contained.sortedByDescending { it.inclusiveHits }.joinToString { it.label } +
                        ": coarse, with no own time - every sample inside " +
                        (if (contained.size == 1) "it" else "them") + " was in an operation above"
            )
        }
        if (unseen.isNotEmpty()) {
            val called = unseen.filter { it.calls > 0 }
            // Named, in both branches: "1 operation never sampled" leaves the reader to work out
            // which of their labels it was, and on a short run — where this line fires most — the
            // answer is usually the whole point. Capped at four, since the other reason this line
            // exists is that Calcite folded twenty-five.
            fun names(ops: List<OperationRow>) =
                ops.take(4).joinToString { it.label } + (if (ops.size > 4) ", ..." else "")
            // "called anyway" rather than "N of them did run", which reads as nonsense at N = 1 —
            // and one is the common case on a short run, which is exactly when a reader is most
            // likely to be new to the report.
            appendLine(
                "  ${plural(unseen.size.toLong(), "operation")} never sampled and folded away" +
                        (if (called.isEmpty()) ", and never called: " + names(unseen)
                        else "; called anyway: " + names(called.sortedByDescending { it.calls }))
            )
        }
        // The marker, explained once and next to the rows that carry it. Not in the legend
        // alone: a star with no key is the reader's problem, and the key is one line.
        if (seen.any { it.coarse } || contained.isNotEmpty()) {
            appendLine("  * a coarse operation, showing its OWN work - its full span is in the table above")
        }
        // A note and not a warning: it is the threshold the `Long` group is printed against, so it
        // belongs under the table that has those columns rather than in the warning section. The
        // floor stays over wall time on purpose: a pause inside an execution stretches it past a
        // tick, so GC is a cause of a long execution even though it is out of active time.
        appendLine(
            String.format(
                Locale.ROOT,
                "  Long: executions over a tick hold %s of thread-time run-wide; preemption and GC pauses alone could put it at %s, and a row at or under that shows -",
                percent(stuckBaseline * 100), percent(machineFloor * 100)
            )
        )
        // The run's own facts, after the tables: how much evidence there is, which is the precision
        // of everything above. It was the first two lines of the header and said nothing a reader
        // acts on before the numbers; it says something after them, when a share looks too close
        // to call. "mean (jittered)": the interval is drawn +/-25% around the step so the sampler
        // cannot lock onto a workload with a rhythm of its own, and over a short run the mean of a
        // handful of draws is visibly off the step.
        appendLine()
        appendLine(
            row(
                "Sampled", String.format(
                    Locale.ROOT, "%,d samples - %,d ticks at %.3f ms mean (jittered) x %s, one sample per thread per tick",
                    allHits, ticks, stepNanos / 1e6, plural(threads.toLong(), "thread")
                )
            )
        )
        appendLine("-".repeat(WIDTH))
        // Said in the output rather than in a document, because the one time it mattered it was
        // worth a factor of 275 and nothing on the screen hinted at it.
        renderLegend(legend)
        appendLine("=".repeat(WIDTH))
    }

    /** Writes this profile to [path]. See [ProfileStore]. */
    fun save(path: java.nio.file.Path) = ProfileStore.save(this, path)

    /** This profile against an earlier one: `before -> after`, by operation name and coarse path. */
    fun diffFrom(before: Profile, beforeName: String = "before", afterName: String = "after"): ProfileDiff =
        ProfileDiff(before, this, beforeName, afterName)

    /**
     * Saves this profile into [dir] under the time and [label], and returns the diff against the
     * newest profile that was already there - the previous run, on the usual workflow of change,
     * profile, look - or, with [against], the newest one saved under that label, so a run can
     * always be read against a named baseline. Nothing is overwritten; the directory is the history, and any two of its
     * files can be compared later with `./gradlew :cli --args="diff a b"`.
     */
    fun diffAgainstLatest(dir: java.nio.file.Path, label: String = "", against: String? = null): String {
        val previous = ProfileStore.latest(dir, against)
        // The name is to the second, so two runs inside one second get a suffix rather than one
        // overwriting the other: the directory is the history and nothing in it is lost.
        var file = dir.resolve(ProfileStore.fileName(label))
        var n = 2
        while (java.nio.file.Files.exists(file)) {
            file = dir.resolve(ProfileStore.fileName(label).removeSuffix(PROFILE_EXTENSION) + "-${n++}" + PROFILE_EXTENSION)
        }
        save(file)
        if (previous == null) return "Saved as $file; " +
                (if (against == null) "the first profile here, nothing to compare with yet." else "no profile labelled '$against' here to compare with.")
        return "Saved as $file\n" + diffFrom(load(previous), previous.fileName.toString(), file.fileName.toString()).render()
    }

    companion object {
        /**
         * How far above the run-wide rate an operation has to sit before it is named, how many
         * long executions it needs behind that rate, and the floor below which the rate is not
         * worth mentioning at all.
         *
         * All three are provisional. The measurements that would settle them — a bench operation
         * contending on a real lock at a known rate, and the Calcite trial, which has a rule of
         * ~1.5 ms per firing and one of ~9 µs per firing in the same run — have not been made yet.
         * Written here rather than buried so that the day they are measured, this is the one place
         * that changes.
         */
        const val SUSPECT_OVER_BASELINE = 3.0
        const val SUSPECT_MIN_INSTANCES = 20L
        const val SUSPECT_MIN_SHARE = 0.02

        /**
         * Below this, a label is not describing the operation it names.
         *
         * Measured rather than chosen: each leaf's sampled share against the configured truth, over
         * a hundredfold range of durations. From 70 ns upward every leaf lands within ±2.4% and
         * most of them inside their own noise floor; at 45 ns and below they read 4.5–9.1% low
         * against noise floors of 1.4–1.7%, so three to six times noise and all in the same
         * direction. The break sits between 45 and 70 ns.
         *
         * The hook's own cost is *not* what sets this. At 20 ns the hook is 8.5% of the operation
         * and is correctable in principle, since the call count measures exactly the quantity the
         * correction needs. What is not correctable is the bias, and beneath it the hazard that
         * C2 will shuffle work across the boundaries of adjacent short labels altogether.
         */
        /**
         * How far past its duty-cycle ceiling `working` may read before the report says so.
         *
         * Slack for a run-wide duty cycle being applied to one operation, which can honestly be more
         * CPU-bound than the run around it. Generous on purpose: the failure this exists to catch was
         * measured at **55x** on PostgreSQL over a socket, so strictness buys nothing and a false
         * accusation costs the reader their trust in the column.
         *
         * **Was 1.5, which contradicted every word above it.** It fired on a sandbox run at 2.2x -
         * a program of two threads and a `Thread.sleep`, with no native call anywhere in it - and
         * blamed a socket read. The real gap there is preemption: a thread that is runnable but
         * waiting for a core reads `RUNNABLE` without being on one, and that is 14-18% of wall time
         * on this machine on a bench that never blocks (see `Sampler.kt`). A threshold below that
         * is measuring the scheduler, not the program.
         *
         * 4.0 still catches the case this exists for by a factor of thirteen. What it gives up is a
         * genuine native-wait problem between 1.5x and 4x, which is a band this machine fills with
         * noise anyway.
         */
        const val WORKING_CEILING_SLACK = 4.0

        const val FLOOR_NANOS = 50.0

        /** The fine table's row for samples inside no label. */
        const val NO_OPERATION = "(no operation)"

        /** How many warnings are printed before the rest are counted rather than listed. */
        const val MAX_WARNINGS = 10

        /**
         * What one coarse context costs: an allocation, two `nanoTime` calls and the accumulate.
         *
         * 21.5 ns of it is measured — the timed-wrapper fit from the Lucene trial — and the
         * allocation is not, so this is the tier boundary's working figure rather than a
         * measurement. See findings.md, "What a coarse label will cost per execution".
         */
        const val COARSE_CONTEXT_NANOS = 40.0

        /** `40 ns ≤ 5% of the operation`. The flat floor, and the one that binds below ~20% share. */
        const val COARSE_FLOOR_NANOS = 800.0

        /** `40 ns × executions ≤ 1% of the run`, rewritten as `d ≥ this × share`. */
        const val COARSE_SHARE_NANOS = 4000.0

        /**
         * Below this share, labelled time outside every coarse span is not worth a word.
         *
         * The same reasoning as "negligible operations" in profiler.md: something under a fraction
         * of a percent of the work cannot be where the work went, and a check that chased it would
         * spend its credibility on false positives. Netty is the case that set it — its request span
         * covers the pipeline, and a handful of samples still land outside it during connection
         * setup and at the tail of a write. That came to **0.0%, three milliseconds**, and printed
         * six lines of warning about it, which is alarm for nothing.
         */
        const val OUTSIDE_COARSE_MIN_SHARE = 0.01

        /** How wide the report's rules and column layout are. */
        /** Below this many ticks, an empty table is the run being short rather than a finding. */
        const val MIN_TICKS_FOR_A_TABLE = 50

        // The report-level rules and the warning banners, matching the widest table on the page.
        // The fine table no longer has a width constant of its own - it is computed from its
        // columns and bars, because a hand-kept number is exactly how the rules came to print at
        // three different lengths on one page.
        const val WIDTH = 168

        /**
         * The coarse table alone, which is narrower than [WIDTH] since the three parallelism
         * columns moved to the lines under it.
         *
         * Drawn to the table rather than to a round number. It briefly went the other way - 141,
         * to fit `Total` - on the grounds that the 130 had never been measured against anything,
         * which is true and is in the friction log. Then the parallelism columns left and took
         * more than `Total` had added.
         */
        const val COARSE_WIDTH = 156

        /** One row of the coarse table: name, executions and per parent, total and its share of the parent, paused, five durations, the split. */
        const val COARSE_ROW = "%-30s %,11d %8s %10s %8s %10s %10s %10s %10s %10s %10s %18s"

        /**
         * The fine table, likewise drawn to itself.
         *
         * A run printed rules of three different lengths on one page - 130 around the fine table,
         * 112 around the coarse one, 130 again to close - because the tables had been renamed and
         * resized while the rules stayed pinned to a constant that no longer described either.
         */
        /**
         * The fine table's data-row widths, in order, so the group band can be laid over them.
         *
         * Operation, thread-time% and thread-time, runnable/wait, wall-time,
         * concurrency/threads, calls, implied per call, hits, noise, over one tick.
         */
        val FINE_COLUMNS = intArrayOf(26, 14, 11, 18, 13, 20, 7, 8, 8, 9, 11, 7, 5, 8, 6)

        /** After which columns a group bar comes down. */
        val FINE_BREAKS = intArrayOf(0, 5, 8, 12)

        /**
         * Hits below which `Peak 1` is not evidence of anything.
         *
         * A peak of one drawn from a handful of samples is what a *short* operation looks like, not
         * what a serialized one looks like. At 25 hits a second thread present a tenth of the time
         * would have been caught nine times in ten.
         */
        const val MIN_HITS_FOR_SERIAL = 25

        /**
         * `1 thread` rather than `1 threads`.
         *
         * A report that cannot count is a report a reader trusts slightly less, and the places this
         * matters are exactly the small runs where every other number is already marginal.
         */
        /** Reads a profile written by [save]. */
        fun load(path: java.nio.file.Path): Profile = ProfileStore.load(path)

        internal fun plural(n: Long, noun: String): String =
            if (n == 1L) "1 $noun" else String.format(Locale.ROOT, "%,d ${noun}s", n)

        /** The short-operation bias, so the check cannot accuse an operation of the sampler's own error. */
        const val FLOOR_BIAS_ALLOWANCE = 1.2

        /** Hits below which an operation's rate of long executions is too noisy to enter the median. */
        const val MEDIAN_MIN_HITS = 100L

        /**
         * How much of an operation's long-running time must be *certainly* on CPU before the report
         * says it is working rather than waiting.
         *
         * A judgement, and a cautious one, because the bound behind it is already conservative — it
         * charges the operation with every stall in the whole run. Calcite's slowest rule clears it
         * at 91%; the bench's lock operation, which does nothing but wait, comes nowhere near.
         */
        const val RUNNING_CERTAIN = 0.5

        /**
         * Below this share of waiting among an operation's long samples, it was working.
         *
         * Not zero, because one sample in a thousand catching a GC pause or a page fault is not
         * the operation waiting on anything, and a verdict that hedged over it would hedge over
         * every operation on a real machine.
         */
        const val WAITING_NONE = 0.05

        /** Above this share, calling it waiting rather than working is the honest description. */
        const val WAITING_MOSTLY = 0.5
    }
}

