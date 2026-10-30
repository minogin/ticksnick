package com.ticksnick

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** How many fine operations a coarse leaf lists as rows under it; the rest are one line. */
internal const val FINE_ROWS_SHOWN = 6

/** What marks a row of a leaf's split by innermost label, so it does not read as a child. ASCII, as everything printed is. */
internal const val SPLIT_MARK = "-"

/**
 * Spaces between a fine row's last column and its note - a fine label's total under every row it is
 * under - so the note reads as a note and not as a value under the `Paused` heading.
 */
internal const val NOTE_MARGIN = 6

/**
 * How much more a fine label's total must be than its largest row before the note is drawn: a
 * second row of a few milliseconds is not worth a note. `2.32 s in all, under 2 operations` beside
 * a 2.32 s row says nothing.
 */
internal const val NOTE_MIN_GAIN = 1.1

/**
 * Executions at once - or, for forked work with no label of its own, threads per execution - from
 * which a coarse row gets its *at once* line: at least this, and at least this many times its
 * parent's. Sampled figures, so a tenth is the row repeating its parent rather than a fork of its own.
 */
internal const val PARALLEL_SHOWN = 1.1

/** Below this share of its row, a fine row under a coarse one is counted in "and N more" rather than drawn. */
internal const val OWN_ROW_MIN = 0.005

/** How far the achieved step may be from the one asked before the `Sampling` line names both. */
internal const val ASKED_STEP_TOLERANCE = 0.05

/**
 * What one coarse execution costs with no fine label registered: the context, two `nanoTime` calls
 * and the accumulate. See [coarseContextNanos].
 */
internal const val COARSE_BASE_NANOS = 45.0

/**
 * What each registered fine label adds to a coarse execution: the pass that moves its calls onto
 * the path they ran under, at every boundary. See `OpSlot.flushCalls`.
 */
internal const val COARSE_PER_FINE_LABEL_NANOS = 0.17

/**
 * The most a coarse label may cost of the operation it wraps: about what two runs of the same code
 * already differ by, so a label at the floor moves its operation no further than running it again.
 */
internal const val COARSE_MAX_OVERHEAD = 0.05

/** What counting allocated bytes adds to a coarse execution, when a session asks for it: two reads of the thread's total. */
internal const val COARSE_ALLOCATIONS_NANOS = 27.0

/** The width of the `Allocated` column of a coarse table, its separating space included. */
internal const val ALLOCATED_COLUMN = 11

/** The most all the coarse executions together may cost of the run. */
internal const val COARSE_MAX_RUN_SHARE = 0.01

/** The first column of a table's row format: its name, left-aligned, at the width written. */
private val NAME_COLUMN = Regex("^%-(\\d+)s")

/** The second line of a leaked-label warning: the usual cause. */
internal const val NO_FINALLY = "a label placed with enter/exit has no finally: a body that throws leaves it set"

/**
 * How much wider than written in [format] a table's name column must be to hold [names]. The width
 * written is a minimum, so a table of short names reads as it always did. A name is never cut: it
 * is what the reader searches the code for, and a name wider than its column would push the rest of
 * its own row out of line.
 */
internal fun nameOverflow(format: String, names: Iterable<String>): Int =
    ((names.maxOfOrNull { it.length } ?: 0) - NAME_COLUMN.find(format)!!.groupValues[1].toInt()).coerceAtLeast(0)

/** [format] with its name column wider by [extra]. See [nameOverflow]. */
internal fun widened(format: String, extra: Int): String =
    NAME_COLUMN.replace(format) { "%-${it.groupValues[1].toInt() + extra}s" }

/** Bytes to three significant digits, as [duration] prints a time: `48 B`, `2.10 MB`. Powers of 1,024. */
internal fun bytes(v: Double): String {
    if (v.isNaN()) return "-"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var x = v
    var u = 0
    while (x >= 1024 && u < units.size - 1) {
        x /= 1024
        u++
    }
    fun digits(of: Double) = if (u == 0 || of >= 100) 0 else if (of >= 10) 1 else 2
    // Rounded before it is printed, so that 9.999 reads 10.0 and 1,023.9 KB reads 1.00 MB.
    x = BigDecimal(x).setScale(digits(x), RoundingMode.HALF_UP).toDouble()
    if (x >= 1024 && u < units.size - 1) {
        x = 1.0
        u++
    }
    return String.format(Locale.ROOT, "%.${digits(x)}f %s", x, units[u])
}

/** `before -> after` of two byte figures, a dash on a side that has none. */
internal fun bytesPair(a: Double, b: Double): String = bytes(a) + " -> " + bytes(b)

/** The `CAREFUL` line of a comparison where only one side counted allocated bytes. */
internal fun allocationsCareful(before: List<Profile>, after: List<Profile>): List<String> {
    val b = before.any { it.allocations }
    val a = after.any { it.allocations }
    return if (b == a) emptyList() else listOf(
        "allocations: counted on one side only - each coarse execution there cost about " +
                "${COARSE_ALLOCATIONS_NANOS.toInt()} ns more, which a short operation shows in its time"
    )
}

/** See [Profile.impliedUpperNanosOf]. Shared so the run-time check and the report cannot disagree. */
internal fun impliedUpperNanos(hits: Long, calls: Long, stepNanos: Double): Double =
    if (calls == 0L) Double.NaN else (hits + 2 * sqrt(hits.toDouble()) + 3) * stepNanos / calls

/**
 * The `Per parent` figure, in every table that has one: [count] per execution of the row above. `340`,
 * or `2.5` when the executions did not all make the same number; blank with no completed execution.
 */
internal fun perParentText(count: Long, parentCount: Long): String = when {
    parentCount <= 0L -> ""
    count % parentCount == 0L -> String.format(Locale.ROOT, "%,d", count / parentCount)
    count.toDouble() / parentCount < 100 -> String.format(Locale.ROOT, "%.1f", count.toDouble() / parentCount)
    else -> String.format(Locale.ROOT, "%,.0f", count.toDouble() / parentCount)
}

/** `Per parent` on both sides of a diff: `340 =` when they agree, `340 -> 68` when not, blank when neither has one. */
internal fun perParentPair(before: String, after: String): String = when {
    before.isEmpty() && after.isEmpty() -> ""
    before == after -> "$before ="
    else -> "${before.ifEmpty { "-" }} -> ${after.ifEmpty { "-" }}"
}

/** The same for a coarse row, which is blank in the report at once per parent, so here too when both sides are. */
internal fun coarsePerParentPair(before: String, after: String): String =
    if (before == "1" && after == "1") "" else perParentPair(before, after)

/** Whether a label is on something too small for the instrument to describe. See [Profile.tooSmall]. */
internal fun isTooSmall(hits: Long, calls: Long, stepNanos: Double): Boolean =
    calls > 0 && impliedUpperNanos(hits, calls, stepNanos) * Profile.FLOOR_BIAS_ALLOWANCE < Profile.FLOOR_NANOS

/**
 * What to tell someone whose label is below the floor. Both reasons, because the second is the one
 * they cannot check for themselves.
 */
internal fun tooSmallMessage(name: String, calls: Long, upperNanos: Double): String = String.format(
    // Three lines: the fact, the reason, the fix. Same shape as the coarse floor's message.
    Locale.ROOT,
    "%s: %,d calls at under %s each, below the %.0f ns floor - label the enclosing loop instead, " +
            "op(%s, times = n) { }, or drop it if its share has already answered the question%n" +
            "the hook is a visible fraction of it, the sampler reads it 5-9%% low, and C2 can move work across adjacent short labels",
    name, calls, duration(upperNanos), Profile.FLOOR_NANOS, name
)

/** What one coarse execution costs with [fineLabels] fine labels registered. */
internal fun coarseContextNanos(fineLabels: Int): Double = COARSE_BASE_NANOS + COARSE_PER_FINE_LABEL_NANOS * fineLabels

/**
 * The shortest a coarse operation may be, given how much of the run it holds and what one execution
 * costs, [contextNanos]. See [Profile.coarseTooSmall].
 *
 * The tier boundary, as arithmetic rather than a rule of thumb. Two conditions,
 * protecting different things and neither implying the other:
 *
 * 1. `cost x executions <= 1% of the run` protects **the program** — that you are measuring what
 *    you started with. In terms of share this is `d >= 100 x cost x share`.
 * 2. `cost <= 5% of the operation's own duration` protects **the number** — that it describes the
 *    code and not the instrument. This is the flat `d >= 20 x cost`, and it is what binds below
 *    about 20% share, which is nearly always.
 *
 * A 40 ns operation called rarely passes the first comfortably and is then reported at 80 ns: the
 * program is undisturbed and the measurement is 100% wrong. That is why both are needed.
 */
internal fun coarseFloorNanos(share: Double, contextNanos: Double): Double =
    maxOf(contextNanos / COARSE_MAX_OVERHEAD, contextNanos / COARSE_MAX_RUN_SHARE * share)

/**
 * What to tell someone whose coarse label is on something too small to carry a context.
 *
 * Names the measured duration rather than an inferred one, and says which of the two conditions
 * bound, because the remedies differ: below the flat floor the label is simply in the wrong tier,
 * while an operation that fails only the share condition is big enough to measure and too big a
 * fraction of the run to afford measuring.
 */
internal fun coarseTooSmallMessage(name: String, count: Long, meanNanos: Double, requiredNanos: Double, contextNanos: Double): String {
    // Which of the two conditions bound decides what the reader is being told, and they are
    // different complaints with different remedies. Saying "the number would describe the
    // instrument" about an operation whose per-execution overhead is a comfortable 4% is simply
    // false, and a warning that misdiagnoses is worse than one that does not fire.
    //
    // Three lines: the fact, the reason, the fix.
    //
    // The fix names the registration, not a call: `op` takes both tiers, so the call site is already
    // right.
    val shareBound = requiredNanos > contextNanos / COARSE_MAX_OVERHEAD
    val overhead = contextNanos / meanNanos * 100
    val why = if (shareBound) String.format(
        Locale.ROOT,
        "accurate per execution (%.1f%% overhead), but %,d contexts at %.0f ns are over 1%% of the run",
        overhead, count, contextNanos
    ) else String.format(
        Locale.ROOT,
        "a context costs about %.0f ns, which is %.0f%% of an operation this size",
        contextNanos, overhead
    )
    return String.format(
        Locale.ROOT,
        "%s: %,d coarse executions of %s, a coarse label needs %s here - register it fine, registerFine(\"%s\"), " +
                "and keep the op(...) calls; or put the coarse label around a batch of these%n%s",
        name, count, duration(meanNanos), duration(requiredNanos), name, why
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
internal fun staleContextMessage(worst: String, hits: Long, share: Double): String = String.format(
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
 * were meant to nest.
 */
internal fun mismatchMessage(closed: String, open: String): String = String.format(
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
internal fun leakMessage(open: String, thread: String = Thread.currentThread().name): String = String.format(
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
 * All three conditions matter. On a quiet machine the floor falls to 1%, so a single long execution
 * out of two thousand samples clears three times it and means nothing whatever. A ratio against a
 * small number is not evidence. The floor on the share and the minimum count are what keep the
 * answer honest when the machine is behaving perfectly.
 *
 * One function so that every caller that asks this question gets the same answer.
 */
internal fun isSuspect(hits: Long, stuckHits: Long, stuckInstances: Long, machineFloor: Double): Boolean =
    hits > 0 && stuckInstances >= Profile.SUSPECT_MIN_INSTANCES &&
            stuckHits.toDouble() / hits > maxOf(machineFloor * Profile.SUSPECT_OVER_BASELINE, Profile.SUSPECT_MIN_SHARE)

/**
 * A duration in whatever unit keeps it readable. The fine tier spans four orders of magnitude, and
 * a column of nanoseconds makes 1.5 ms and 9 µs look like the same kind of number, which is the
 * one distinction this column exists to draw.
 */
internal fun duration(nanos: Double): String {
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
internal fun threeDigits(value: Double): String =
    if (value.isNaN()) "-" else threeSignificantString(value)

/**
 * A percentage to three significant digits: `100%`, `40.3%`, `5.80%`, `0.512%`.
 *
 * The same rule as [duration] and for the same reason - the fourth digit of a sampled share is
 * noise, and printing it invites a reader to compare two numbers that are not different. Sorting
 * and every arithmetic use the full value; this is only what is shown.
 */
internal fun percent(value: Double): String = when {
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
 * The same function as [duration], kept only as a name: at the call sites it says which of the two
 * quantities is being printed, thread-time or wall-time, which are easy to confuse.
 */
internal fun threadTime(nanos: Double): String = duration(nanos)

/**
 * `key            value`, with every value starting at the same column.
 *
 * Keys rather than prose: a reader scans the left column to find a fact, and the key says what kind
 * of thing the line is - a fact, a caveat or a verdict.
 */
internal fun row(key: String, value: String): String = String.format(Locale.ROOT, "%-13s %s", key, value)

/** A row that qualifies the one above it — a reason, a bound, a verdict. Values still align. */
internal fun subRow(key: String, value: String): String =
    String.format(Locale.ROOT, "  %-11s %s", key, value)

/** One operation's line in a [Profile]. */
internal class OperationStat internal constructor(
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
     * rather than a convention. A fine operation is atomic and never leaves the
     * thread that entered it — a body that suspends or hands off is by construction not fine — so
     * one thread inside the label *is* one execution of it, and the two counts are the same count.
     * The parallelism of a **single** execution across threads is not a quantity this tier can hold at
     * all: it needs an execution to have an identity that a second thread can be handed, which is
     * exactly what a coarse context is and what an integer in a slot can never be.
     *
     * So this is one factor of a product, and the other factor is **parallelism** in the work-span
     * sense, `work / span` for one execution, which only the coarse tier can measure:
     *
     *     threads inside = executions in flight x parallelism per execution
     *
     * Here the second factor is 1 by construction, so the product collapses and either reading
     * gives the same number. The column is still not called "threads", because beside a coarse
     * column the two factors come apart.
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
 * The [p]-th percentile of [count] executions' [spans], or NaN when there are **fewer than
 * `1 / (1 - p)` executions: 2 for a p50, 10 for a p90, 100 for a p99.** Below that the percentile is
 * the slowest execution or close to it, and printing it under a percentile's name says more than
 * the data does - three batches printed a p99. [Spans] is exact there anyway; this is about what a
 * percentile of so few means, not about rounding.
 *
 * Clamped to the exact [min] and [max], which the middle of a bucket can otherwise pass by a little.
 */
internal fun spanPercentile(spans: Spans, count: Long, min: Long, max: Long, p: Double): Double {
    val needed = if (p >= 1.0) 1L else Math.round(1.0 / (1.0 - p))
    if (count < needed) return Double.NaN
    val v = spans.percentile(p)
    return if (v.isNaN()) v else v.coerceIn(min.toDouble(), max.toDouble())
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
internal class CoarseNode internal constructor(
    val type: Int,
    val name: String,
    /** Executions completed at this path. Exact. */
    val count: Long,
    val spanSumNanos: Long,
    val spanMinNanos: Long,
    val spanMaxNanos: Long,
    internal val spans: Spans,
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
    /** Ticks with an execution on this path open, one per execution per tick. See [parallel]. */
    val instanceTicks: Long = 0,
    /** Ticks with a parent execution holding at least one execution on this path open, one per parent per tick. */
    val coveredTicks: Long = 0,
    /**
     * This path's own time by fine label, last entry no label: samples whose innermost context was an
     * execution here, and of those, one per execution per tick. Empty in a profile saved without
     * them, which draws a leaf's fine rows from [fine] instead. See [Profile.ownRowsOf].
     */
    internal val ownFine: LongArray = LongArray(0),
    internal val ownFineCovered: LongArray = LongArray(0),
    /**
     * Fine calls made directly under an execution on this path, by fine id: counted, not sampled.
     * Empty in a profile saved without them.
     */
    internal val calls: LongArray = LongArray(0),
    /** Bytes allocated directly under executions at this path, outside its coarse children, on every thread. Counted. */
    val allocated: Long = 0,
) {
    val meanSpanNanos: Double get() = if (count == 0L) Double.NaN else spanSumNanos.toDouble() / count

    /**
     * **How many executions on this path ran at a time under the same parent execution,** on average
     * over the ticks when the parent had at least one open. 4 branches of one request on a pool of 2
     * read 2.0; leaves worked through one after another in each branch read 1.0, however many
     * branches run beside each other. Per parent and not overall, because overall is the wrong
     * divisor: a `select` inside `resolve` can be open 5.5 at a time across ten threads, and never
     * two at a time in one `resolve`. A root's parent is the run, so for a root it is how many
     * ran at a time at all. Sampled; NaN for a path never caught open.
     */
    val parallel: Double get() = if (coveredTicks == 0L) Double.NaN else instanceTicks.toDouble() / coveredTicks

    /** As [CoarseStat.percentileNanos]. */
    fun percentileNanos(p: Double): Double = spanPercentile(spans, count, spanMinNanos, spanMaxNanos, p)

    /** Every node of the subtree, this one first. */
    fun flatten(): List<CoarseNode> = listOf(this) + children.flatMap { it.flatten() }
}

/** One of the slowest executions of a coarse operation: the key it was opened with, and its wall time. */
internal data class SlowExecution(val key: String, val nanos: Long)

/**
 * One coarse operation's line in a [Profile].
 *
 * The asymmetry with [OperationStat] is the point of the tier. **Span is measured**, exactly, by two
 * timestamps per execution — it is the only quantity in this whole tool that is not sampled, and it
 * is the one a fine label can never produce. Everything about *where the time went* is still
 * sampled, because that is the only way to see inside an execution without pricing it.
 */
internal class CoarseStat internal constructor(
    val type: Int,
    val name: String,
    /** Executions that completed inside the session. Exact. */
    val count: Long,
    val spanSumNanos: Long,
    val spanMinNanos: Long,
    val spanMaxNanos: Long,
    internal val spans: Spans,
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
     * A percentile of the measured durations: exact up to [EXACT_SPANS] executions, within
     * [SpanHistogram.PRECISION] past them, and never outside [spanMinNanos]..[spanMaxNanos], which
     * are exact. NaN when there are too few executions for it - see [spanPercentile].
     */
    fun percentileNanos(p: Double): Double = spanPercentile(spans, count, spanMinNanos, spanMaxNanos, p)

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
     * **1.0 by construction when contexts do not cross threads.** Without propagation a context
     * lives on the thread that made it, so every occupied instance is occupied by exactly one. With
     * propagation, a request that keeps 4.24 helpers busy while its driver parks on the join reads
     * 5.24.
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
     * seven drivers against eight helpers leaves nothing to fan out to and it falls back to about 1.
     * And it reads *high* as a speedup, because it is `work / span` of the run it measured and
     * parallelising usually costs extra work. A search that takes 14.04 ms on one thread and 4.01 ms
     * on eight - a 3.50x speedup - can read **6.32** here, the difference being the 1.80x more total
     * CPU the parallel run spends. Re-running at another thread count is the only thing that
     * measures what parallelism bought.
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
    internal val operations: List<OperationStat>,
    internal val idleHits: Long,
    internal val ticks: Long,
    internal val samplingSpanNanos: Long,
    internal val durationNanos: Long,
    internal val threads: Int,
    internal val duty: DutyReport,
    /**
     * Why the session stopped early, or null. Non-null means the numbers below describe a run the
     * profiler could not have measured correctly, and they are shown only so the reader can see
     * what led to the verdict.
     */
    internal val failure: String? = null,
    /** Times a thread was found holding a label open where the caller said it should not be. */
    internal val imbalances: Int = 0,
    /** Whether each hit also recorded its thread's state. Without it the verdicts fall back. */
    internal val stateSampled: Boolean = false,
    /** Of [idleHits], those where the thread was not runnable. See Sampler.idleWaitingHits. */
    internal val idleWaitingHits: Long = 0,
    /** Threads still inside a hand-placed label when the session ended. */
    internal val openAtEnd: Int = 0,
    /**
     * Threads that arrived past the slot ceiling and are invisible to the long-execution detector.
     *
     * Captured at `stop()` rather than read from [Profiler] at render time, for two reasons. The
     * live figure is a process-lifetime counter that never decreases, so in a process that profiles
     * repeatedly every later report would warn about threads that arrived during an earlier
     * session; and reading live global state while rendering means a report could describe thread
     * churn that happened *after* the session it claims to be about.
     */
    internal val untrackedSlots: Int = 0,
    /**
     * Threads that exited without calling `Profiler.release()` and had to be reclaimed.
     *
     * Reported rather than silently absorbed. A user who is quietly rescued learns nothing, and the
     * rescue depends on a garbage collection - in a process that does not collect, the slots stay in
     * the walk reading empty and every share is taken over a denominator inflated by dead threads.
     */
    internal val reclaimedSlots: Int = 0,
    /** The coarse tier's half of the report, empty when no coarse label was placed. */
    internal val coarse: List<CoarseStat> = emptyList(),
    /**
     * Coarse executions still open when the session ended, which is a leak by definition — nothing
     * can close them now, and their spans are missing from [coarse] for exactly that reason.
     */
    internal val openContextsAtEnd: Int = 0,
    /**
     * Labelled samples that fell under no coarse span. See [labelledOutsideCoarseShare], and
     * `Sampler.labelledOutsideCoarse` for what it is for.
     */
    internal val labelledOutsideCoarse: Long = 0,
    /** Samples caught under a coarse execution that had already been closed. See [CoarseStat.staleHits]. */
    internal val staleContextHits: Long = 0,
    /** Samples caught inside a coarse execution at all — the population [staleContextHits] is part of. */
    internal val coarseSampleHits: Long = 0,
    /** How long every thread was stopped, which is what stretches [stepNanos] past the requested step. */
    internal val pause: PauseReport = PauseReport.NONE,
    /** The coarse tree: the roots, each with what ran under it. Empty when no coarse label was placed. */
    internal val coarseTree: List<CoarseNode> = emptyList(),
    /** The CPU clock over the session; [ClockReport.NOT_RECORDED] for a profile saved before it was. */
    internal val clock: ClockReport = ClockReport.NOT_RECORDED,
    /** How the sampler waited; null for a profile saved before it was recorded. See [samplingLine]. */
    internal val wait: WaitStrategy? = null,
    /** The step asked for at `start()`, in nanoseconds; 0 for a profile saved before it was recorded. */
    internal val askedStepNanos: Long = 0,
    /** Whether this session counted the bytes allocated under each coarse path. */
    internal val allocations: Boolean = false,
    /** Whether it was asked to on a JVM that cannot say. */
    internal val allocationsMissing: Boolean = false,
) {
    /** False when a fatal finding stopped the session. */
    internal val ok: Boolean get() = failure == null

    /**
     * How often the sampler sampled, and what that cost the program: a spinning sampler holds a
     * processor to keep the step, a parked one frees it and samples later than asked. Said with
     * the remedy when a spinning sampler holds one of few processors. Null when not recorded.
     */
    internal fun samplingLine(): String? {
        val w = wait ?: return null
        if (stepNanos.isNaN()) return null
        val n = duty.processors
        val processors = if (n > 0) "one of the $n processors" else "a processor"
        return if (w == WaitStrategy.SPIN) {
            "every ${stepText(stepNanos)}: the sampler keeps $processors busy to hold the interval" +
                    if (n in 1 until SPIN_MIN_PROCESSORS) " - start(wait = PARK) to free it" else ""
        } else {
            val asked = if (askedStepNanos > 0 && abs(stepNanos / askedStepNanos - 1) > ASKED_STEP_TOLERANCE)
                " (${stepText(askedStepNanos.toDouble())} asked)" else ""
            "every ${stepText(stepNanos)}$asked: the sampler sleeps between samples, so as not to take $processors"
        }
    }

    private fun stepText(nanos: Double): String =
        String.format(Locale.ROOT, if (nanos >= 1_000_000) "%.1f ms" else "%.2f ms", nanos / 1e6)

    /** Samples inside a fine label. Self time by construction: the hook keeps the innermost id. */
    internal val fineHits: Long get() = operations.sumOf { it.hits }

    /** Samples inside a coarse span doing its own work, summed over the coarse operations. */
    internal val coarseSelfHits: Long get() = coarse.sumOf { it.selfHits }

    /**
     * Every sample that landed inside some label, of either tier.
     *
     * Fine hits and coarse self hits are disjoint - a sample with a fine label open is one of the
     * first, one inside a span with no fine label is one of the second - so this partitions, and
     * one share column can rank both tiers. The fine sum alone would make a run labelled only with
     * coarse spans report **zero coverage**.
     */
    internal val labelledHits: Long get() = fineHits + coarseSelfHits

    /** Samples inside no label at all: [idleHits] is "no fine label", which includes span work. */
    internal val unlabelledHits: Long get() = (idleHits - coarseSelfHits).coerceAtLeast(0)

    /** Every sample taken: the denominator of a share. */
    internal val allHits: Long get() = labelledHits + unlabelledHits

    /**
     * A share of *all* samples, so it is a share of active time: 30% means 30% of the time the
     * program was not paused, and the `(no operation)` row in the table is the rest.
     *
     * Over all samples rather than labelled ones, because that is what a reader expects: adding a
     * label moves samples out of `(no operation)` and nothing else, where a share of labelled
     * samples would move every row. The absolute column is still the one to compare between runs -
     * see the note on [occupancyNanosOf].
     */
    internal fun shareOf(op: OperationStat): Double =
        if (allHits == 0L) 0.0 else op.hits.toDouble() / allHits

    /** Error chance alone would produce at this hit count — anything smaller is not measurable. */
    internal fun noiseFloorOf(op: OperationStat): Double =
        if (op.hits > 0) 1.0 / sqrt(op.hits.toDouble()) else Double.NaN

    /** The sampling span with the confirmed pauses taken out: the time every sample was taken in. */
    internal val activeNanos: Long get() = samplingSpanNanos - pause.confirmedPausedIn(samplingSpanNanos)

    /**
     * What one sample is worth in thread-time: the step the sampler achieved over *active* time.
     *
     * Not the wall step over the whole span, which a pause stretches: 1.108 ms on a run that asked
     * for 1 ms would make every thread-time and time per call read about 10% high, with the pauses
     * spread over every operation in proportion to its samples. No sample is taken in a pause, so
     * the step a sample stands for is the active one.
     */
    internal val stepNanos: Double get() = if (ticks > 1) activeNanos.toDouble() / (ticks - 1) else Double.NaN

    /**
     * About [occupancyNanosOf]: an operation's occupancy as thread-time rather than as a fraction,
     * and the column to compare between two runs.
     *
     * A share moves when anything else in the run moves - another operation's time, the idle time,
     * the run's length. Occupancy moves only when this operation does, so a change in one clause
     * shows in that clause's row and nowhere else.
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
     * is empty still gets its bars: it says *these belong together* without naming why.
     *
     * Groups are given as column ranges into [widths], the widths of the **data** row, because the
     * band has to sit over the numbers.
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
    internal fun runnableSplit(waiting: Double): String =
        percent((1 - waiting) * 100) + " / " + percent(waiting * 100)

    internal fun occupancyNanosOf(op: OperationStat): Double = op.hits * stepNanos

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
    internal fun elapsedNanosOf(op: OperationStat): Double = op.activeTicks * stepNanos

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
     * **The denominator is per operation**, not the run's thread high-water mark, which is the pool
     * size only on a workload where every thread does everything. With a `main` thread beside a
     * pool, an operation that never left `main` would print `1.00 / 5`, measured against four
     * threads that could not have entered it. A count of the threads that actually called it makes
     * it `1.00 / 1`: *this operation is serial by construction*. The run-wide count is in the header
     * line, where it is a fact about the run and is not pretending to be about a row.
     */
    internal fun inFlightOf(op: OperationStat): String {
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
    internal fun workersOf(op: OperationStat): String =
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
    internal fun poolOf(op: OperationStat): String = if (op.threads <= 0) "-" else op.threads.toString()

    /**
     * Operations every thread queues through one at a time.
     *
     * `Workers 1` beside `Pool 8` is a serialization point: eight threads run it and no two are
     * ever inside together, which is the contention term of a scalability model showing up in a
     * table of shares. It is a *warning* and not a verdict because `Workers` is sampled - a burst
     * between two ticks is a burst nobody saw - so it asks for enough hits that a second thread
     * would have been caught if there had been one.
     */
    internal fun serialised(): List<OperationStat> = operations
        .filter { it.threads > 1 && it.peakInside == 1 && it.hits >= MIN_HITS_FOR_SERIAL }
        .sortedByDescending { it.threads }

    /** Thread-time spent inside any label. */
    internal val labelledNanos: Double get() = labelledHits * stepNanos

    /** Of [labelledHits], those that caught a thread that was not runnable. Zero without state. */
    internal val labelledWaitingHits: Long
        get() = operations.sumOf { it.waitingHits } + coarse.sumOf { it.selfHits - it.selfRunningHits }

    /** Of [unlabelledHits], those whose thread was not runnable. See [labelledWaitingHits]. */
    internal val unlabelledWaitingHits: Long
        get() = (idleWaitingHits - coarse.sumOf { it.selfHits - it.selfRunningHits }).coerceAtLeast(0)

    /**
     * Coverage with both sides restricted to occupancy where the thread was actually runnable.
     *
     * The plain coverage figure divides labelled samples by *every* sample, and on a workload with
     * idle threads that reads as "the operations miss most of the run" when what it means is "most of
     * the run was nobody doing anything". When 79.2% of the unlabelled samples caught a thread that
     * was not runnable, 49.8% coverage means something quite different.
     *
     * Both sides, not just the unlabelled one: a label can be held across a wait - an update under a
     * lock can spend 73% of itself parked - so leaving that inside the numerator while removing it
     * from the denominator would be the same mismatch of denominators, pointing the other way. NaN when state was not sampled and the question cannot be
     * asked.
     */
    internal val runnableCoverage: Double
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
    internal val observedNanos: Double get() = (labelledHits + unlabelledHits) * stepNanos

    /** Occupancy that was not CPU, in samples. The whole run's supply of stalling. */
    internal val offCpuSamples: Double
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
    internal fun runningFloorOf(op: OperationStat): Double {
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
    internal fun impliedUpperNanosOf(op: OperationStat): Double = impliedUpperNanos(op.hits, op.calls, stepNanos)

    /**
     * Operations labelled on something too small for the instrument to describe.
     *
     * The bound is inflated by [FLOOR_BIAS_ALLOWANCE] first: the sampler reads short operations
     * 5-9% low below 45 ns, and that bias points the wrong way
     * here, since it would make an innocent operation look smaller than it is.
     */
    internal fun tooSmall(): List<OperationStat> = operations
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
    internal fun impliedNanosOf(op: OperationStat): Double =
        if (op.calls == 0L) Double.NaN else op.hits * stepNanos / op.calls

    /**
     * The run-wide rate of executions caught spanning a tick. Reported, but *not* what an operation
     * is judged against — see [machineFloor].
     */
    internal val stuckBaseline: Double
        get() = if (fineHits == 0L) 0.0 else operations.sumOf { it.stuckHits }.toDouble() / fineHits

    /**
     * How much of an operation's long-running time the *machine* can account for, and therefore the
     * floor an operation has to clear before it has anything to answer for.
     *
     * **A baseline must not contain the effect it is used to detect.** That rules out the two
     * obvious candidates on their own:
     *
     * 1. *The run-wide rate of long executions* assumes long executions are the exception. Where
     *    most labels genuinely are coarse that rate can be over 50%, so three times it is
     *    unreachable and nothing can ever be named.
     * 2. *The non-CPU fraction from the duty cycle* is right for preemption and GC, which are what
     *    the machine does to everything - but a thread blocked on a lock is also off the CPU, so a
     *    blocking operation raises this floor and hides itself behind it: 88.61% of its occupancy in
     *    executions over a tick, against a floor of 35.43% that it created itself.
     *
     * So the floor is *the lower of the duty-cycle figure and what a typical operation experienced* -
     * [typicalStuckShare]. The machine can only be blamed for what both estimates agree on, and the
     * median across operations cannot be moved by the one operation that blocks.
     *
     * Being conservative about blaming the machine means being liberal about naming operations,
     * which is why [SUSPECT_MIN_SHARE] and [SUSPECT_MIN_INSTANCES] exist: they are what stops a
     * near-zero floor turning noise into an accusation.
     */
    internal val machineFloor: Double
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
     * The median rather than the mean, because the mean is the run-wide rate [machineFloor] rules
     * out: one operation holding most of the occupancy drags it up until nothing can clear it.
     */
    internal fun typicalStuckShare(): Double {
        val rates = operations.filter { it.hits >= MEDIAN_MIN_HITS }.map { it.stuckShare }.sorted()
        if (rates.isEmpty()) return Double.NaN
        return if (rates.size % 2 == 1) rates[rates.size / 2]
        else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
    }

    /**
     * Operations whose executions span a tick far more often than the run as a whole — the ones
     * that are not the size their label claims.
     *
     * Both conditions are provisional tolerances, not yet settled by measurement. The second
     * condition exists so that a single GC pause, which can freeze one operation across several
     * ticks, cannot on its own accuse it.
     */
    internal fun suspect(): List<OperationStat> = operations
        .filter { isSuspect(it.hits, it.stuckHits, it.stuckInstances, machineFloor) }
        .sortedByDescending { it.stuckShare }

    /**
     * What to do about an operation whose executions outlive a tick - which is not one answer.
     *
     * Labels flagged by this signal can still have *correct* shares, agreeing with an independent
     * stack profiler to about a percentage point, so stopping the run over them would throw away a
     * good measurement. A long execution is never fatal. What differs is the advice, and
     * [runningFloorOf] decides which advice applies.
     */
    internal fun verdictFor(op: OperationStat): String {
        // Sampled state answers this directly and per operation, so it is asked first. The
        // aggregate below is only the fallback for a session that turned state sampling off.
        if (stateSampled && op.stuckHits > 0) {
            val waiting = op.stuckWaitingHits.toDouble() / op.stuckHits
            return when {
                // "Runnable", not "on a core" — the distinction is the whole known limit of this
                // signal. A thread the scheduler preempted is still runnable, so this rules out
                // waiting on another thread and does not rule out waiting for a core. The duty
                // cycle above bounds that second kind, and only in aggregate.
                waiting <= WAITING_NONE -> String.format(
                    Locale.ROOT,
                    "%.1f%% of those long samples caught the thread runnable - it is not waiting on anything, " +
                            "so the share is honest and the operation wants a coarse label for its per-execution " +
                            "statistics (runnable is not the same as scheduled: see the duty cycle above)",
                    (1 - waiting) * 100
                )

                // The claim the aggregate test cannot make: a measurement of this operation rather
                // than an alibi drawn from the whole run's budget.
                waiting >= WAITING_MOSTLY -> String.format(
                    Locale.ROOT,
                    "%.1f%% of those long samples caught the thread parked or blocked - it is *waiting*, " +
                            "not working. Read this share as thread-time: it does not add up across threads the " +
                            "way CPU does, and %s of wall clock had anyone inside it at all",
                    waiting * 100, threadTime(elapsedNanosOf(op))
                )

                else -> String.format(
                    Locale.ROOT,
                    "%.1f%% of those long samples caught the thread parked - part working, part waiting, " +
                            "and the split is measured rather than bounded",
                    waiting * 100
                )
            }
        }

        val running = runningFloorOf(op)
        return when {
            running.isNaN() ->
                "with no duty cycle there is no way to say whether it was working or waiting"

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
                "working or waiting cannot be told - state sampling is off, and the run's whole off-CPU time, %.1f%% " +
                        "of the thread-time, is enough to account for all of it: read this share as thread-time, not " +
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
     * The legend stays - a reader with a terminal and no browser needs the short version - so the
     * duplication is deliberate and the drift is the risk.
     *
     * The rule: **a line you add here needs a paragraph there, and a line you reword here needs
     * that paragraph reworded.** `output.md` carries the same warning pointing back at this file.
     */
    /**
     * Thread-time actually on a CPU inside one execution of [c], on average — **summed over every
     * thread that was in it**, which is why it can be larger than the span.
     *
     * `busy/exec = working x mean span`, so an execution fanned across six threads reports six spans
     * of thread-time: a 4.01 ms search with `working` 6.32 reports 25.31 ms here.
     *
     * That makes `mean - busy/exec` the waiting **only while `inside` is 1**, that is, while no
     * context crosses a thread. Use [CoarseStat.waitingHits] as a share for the general answer; the
     * subtraction is a shortcut that fan-out invalidates, and the report's legend says so.
     */
    internal fun busyPerExecutionNanos(c: CoarseStat): Double =
        if (c.count == 0L) Double.NaN else c.runningInclusiveHits * stepNanos / c.count

    /**
     * The most of [c]'s `working` the measured CPU duty cycle can support.
     *
     * `working` is built on [Thread.getState], and **thread state cannot see through a native
     * call**: a thread stopped inside a socket read is `RUNNABLE` as far as Java is concerned, so it
     * is counted as working. On a workload that waits on something outside the process that is not a
     * small error. A client waiting on a database over a socket can read `working` **2.85** while
     * the operating system says the whole process used 1.03 s of CPU in a 20 s run - crediting
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
    internal fun workingCeilingOf(c: CoarseStat): Double =
        if (!duty.available) Double.NaN else c.inside * duty.labelledDuty

    /**
     * Whether [c]'s `working` claims more CPU than the machine was measured to have given it.
     *
     * The factor is slack for the run-wide-versus-per-type mismatch above, and it is deliberately
     * generous: the failure this catches is a factor of fifty, not a factor of two, so nothing is
     * gained by being strict and a false accusation costs the reader their trust in the column.
     */
    internal fun workingIsContradicted(c: CoarseStat): Boolean {
        val ceiling = workingCeilingOf(c)
        return ceiling.isFinite() && c.working.isFinite() && c.working > ceiling * WORKING_CEILING_SLACK
    }

    /** What a row's executions are divided by: its parallelism, or 1 where it is under [PARALLEL_SHOWN] or unknown. */
    private fun factorOf(n: CoarseNode): Double = n.parallel.let { if (it.isFinite() && it >= PARALLEL_SHOWN) it else 1.0 }

    /**
     * **Each row's wall time: the part of its root's wall time the row accounts for.** Its measured
     * durations summed, divided by its own parallelism and by every parallelism above it. 800
     * branches summing to 12.3 s, 3.4 at a time under each request, are 3.65 s of the requests' 4.06
     * s; the leaves inside them, one at a time in each branch, are 8.60 s divided by the same 3.4,
     * 2.55 s, which is 70% of the branches - the share each branch spends in leaves.
     *
     * Not the time a row was open anywhere: that could put `select` at 98% of `resolve` when it is
     * 54% of each, because across ten threads some `select` is nearly always open. And not the summed
     * durations, which read 988% of the parent once a fork runs ten at a time. A row whose
     * executions never overlap under one parent, nor above it, gets its
     * summed durations exactly; only a parallelism makes it an estimate, and only through that figure.
     */
    internal fun wallsOf(): Map<CoarseNode, Double> = divisors.mapValues { (n, d) -> n.spanSumNanos / d }

    /** Each node's divisor: its own parallelism times every one above it. See [wallsOf]. */
    private val divisors: Map<CoarseNode, Double> by lazy {
        val out = HashMap<CoarseNode, Double>()
        fun walk(n: CoarseNode, divisor: Double) {
            val d = divisor * factorOf(n)
            out[n] = d
            for (c in n.children) walk(c, d)
        }
        for (r in coarseTree) walk(r, 1.0)
        out
    }

    /**
     * One fine label in a coarse row's own time: its wall, how many threads ran it at a time in one
     * execution, its share of the row; its calls on the row's path, counted, -1 for unlabelled time
     * or a profile saved without them; and its own thread-time per call, NaN without either.
     */
    internal class OwnRow internal constructor(
        val name: String, val wallNanos: Double, val parallel: Double, val share: Double,
        val calls: Long = -1, val perCallNanos: Double = Double.NaN,
    )

    /**
     * **The fine labels in a coarse row's own time,** outside its coarse children, heaviest first - or
     * null for a profile saved without them.
     *
     * Each is treated as a coarse child would be: its wall is the ticks an execution of the row had it
     * running, one per execution per tick, times the step, divided by the row's divisor - so a label
     * run on four threads at once inside one execution counts its time once, and a row whose
     * executions ran side by side is divided as the row is. Its parallel is how many threads ran it
     * at a time in one execution. A thread waiting with no label while others work under the same
     * execution - a caller parked on a join - is not counted: that time is theirs.
     *
     * Not the leaf's samples split by label and applied to its wall, which holds only while every
     * part of the leaf runs at the same parallelism: a leaf half `compute` on its own thread and half
     * `assist` on two helpers would read compute 25%, the parked wait 26%, assist 49%. Own time only:
     * a fine label inside another shows its own part.
     *
     * A label with calls on the path and no sample there is a row too, at no time: too short or too
     * rare to be caught, and still run here. Without it a label that ran under two rows would look
     * as if it had one.
     */
    internal fun ownRowsOf(n: CoarseNode): List<OwnRow>? {
        if (n.ownFineCovered.isEmpty()) return null
        val d = divisors[n] ?: return null
        val wall = n.spanSumNanos / d
        val counted = n.calls.isNotEmpty()
        fun callsOf(f: Int): Long = if (counted && f < MAX_OPERATIONS) n.calls[f] else -1L
        return (0..MAX_OPERATIONS).filter { n.ownFineCovered[it] > 0 || callsOf(it) > 0 }.map { f ->
            val name = if (f == NO_OP_INDEX) "unlabelled" else operations.firstOrNull { it.id == f }?.name ?: "#$f"
            val calls = callsOf(f)
            if (n.ownFineCovered[f] == 0L) OwnRow(name, 0.0, Double.NaN, 0.0, calls)
            else {
                val w = n.ownFineCovered[f] * stepNanos / d
                OwnRow(
                    name, w, n.ownFine[f].toDouble() / n.ownFineCovered[f], if (wall > 0) w / wall else Double.NaN,
                    calls, if (calls > 0) n.ownFine[f] * stepNanos / calls else Double.NaN,
                )
            }
        }.sortedByDescending { it.wallNanos }
    }

    /** Every name the coarse table draws in its first column, indents and marks included. See [nameOverflow]. */
    internal fun coarseRowNames(): List<String> = buildList {
        fun walk(n: CoarseNode, depth: Int) {
            val indent = "  ".repeat(depth)
            add(indent + n.name)
            val own = ownRowsOf(n)
            val fine = when {
                own != null -> drawnOwnRows(own).map { it.name }
                n.children.isEmpty() -> operations.filter { n.fine[it.id] > 0 }.map { it.name }
                else -> emptyList()
            }
            for (f in fine) add("$indent  $SPLIT_MARK $f")
            for (c in n.children) walk(c, depth + 1)
        }
        for (r in coarseTree) walk(r, 0)
        // The block of operations under more than one caller.
        coarseTree.flatMap { it.flatten() }.groupBy { it.type }.values.filter { it.size > 1 }.forEach { add("  " + it[0].name) }
    }

    private val coarseExtra by lazy { nameOverflow(COARSE_ROW, coarseRowNames()) }
    private val coarseRow by lazy { widened(COARSE_ROW, coarseExtra) }
    private val fineExtra by lazy { nameOverflow(FINE_ROW, rows().filter { it.hits > 0 }.map { it.label } + NO_OPERATION) }
    private val fineRow by lazy { widened(FINE_ROW, fineExtra) }
    private val fineColumns by lazy { FINE_COLUMNS.copyOf().also { it[0] += fineExtra } }

    /** The report-level rules and banners: the widest table on this page. */
    private val pageWidth by lazy { maxOf(WIDTH + fineExtra, coarseRuleWidth) }

    private val coarseRuleWidth by lazy { COARSE_WIDTH + coarseExtra + if (allocations) ALLOCATED_COLUMN else 0 }

    /**
     * Which of [own] are drawn as rows, the rest being one `and N more` line: the heaviest, up to
     * [FINE_ROWS_SHOWN], from [OWN_ROW_MIN] of the row up; and the smaller ones with calls as well
     * when they all fit, so a label run a few times here - the one that would otherwise look as if it
     * had a single parent - is a row while there are few of them.
     */
    internal fun drawnOwnRows(own: List<OwnRow>): List<OwnRow> {
        val big = own.filter { !(it.share < OWN_ROW_MIN) }.take(FINE_ROWS_SHOWN)
        val small = own.filter { it !in big && it.calls > 0 }
        return if (big.size + small.size <= FINE_ROWS_SHOWN) big + small.sortedByDescending { it.calls } else big
    }

    /** How much of the observed thread-time happened anywhere under [c]. */
    internal fun shareOf(c: CoarseStat): Double =
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
    internal fun coarseFloorNanosOf(c: CoarseStat): Double {
        val noise = if (c.inclusiveHits <= 0L) 1.0 else 1.0 / sqrt(c.inclusiveHits.toDouble())
        return coarseFloorNanos((shareOf(c) * (1 - noise)).coerceAtLeast(0.0), coarseContextNanos)
    }

    /** What one coarse execution cost in this run, which rises with the fine labels registered. See [coarseContextNanos]. */
    internal val coarseContextNanos: Double
        get() = coarseContextNanos(operations.size) + if (allocations) COARSE_ALLOCATIONS_NANOS else 0.0

    /**
     * Bytes allocated per execution of [n], its coarse children included, on every thread that ran
     * under it. Per execution, so it can be set against another run of a different size; a fork's
     * bytes count, since each thread's lands on the path it ran on and the rows add up here.
     */
    internal fun allocatedPerExecution(n: CoarseNode): Double =
        if (n.count == 0L) Double.NaN else n.flatten().sumOf { it.allocated }.toDouble() / n.count

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
     * A warning and never fatal, like the fine floor: a check that stops a run has to be right about
     * a machine it has never seen.
     */
    internal fun coarseTooSmall(): List<CoarseStat> = coarse
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
    internal val labelledOutsideCoarseShare: Double
        get() = if (fineHits == 0L) 0.0 else labelledOutsideCoarse.toDouble() / fineHits

    /**
     * Of labelled thread-time, the share billed to coarse executions that had already finished.
     *
     * The sibling of [labelledOutsideCoarseShare] and the opposite fault. That one is attribution
     * **lost** — work that reached no span — and it is recoverable by wrapping the hand-off. This is
     * attribution **invented**, and it is the direction the whole design is built to avoid, which is
     * why it is the one thing the sampler stops a strict session over on its own.
     */
    internal val staleContextShare: Double
        get() = if (coarseSampleHits == 0L) 0.0 else staleContextHits.toDouble() / coarseSampleHits

    /** The fine operations most of that time was in — where to look if it should have been covered. */
    internal fun outsideCoarseSuspects(): List<OperationStat> {
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
     * After every number and not between the tables: numbers interleaved with prose read as a wall
     * of text. Explaining a column and interleaving the explanation with the data are not the same
     * decision.
     *
     * Kept short by naming columns so the name carries its own caveat: a name needs no paragraph,
     * and unlike a paragraph it still works on the tenth run.
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
            appendLine("  full notes: docs/output.md")
            return
        }
        appendLine()
        appendLine("a share is where time went. It is not what removing the operation would save: an operation")
        appendLine("  can create work for everything else as well as doing its own. The two numbers are")
        appendLine("  different questions and the gap can be orders of magnitude")
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
            appendLine("    only numbers here that are not sampled. Percentiles are exact up to $EXACT_SPANS executions and")
            appendLine("    within 0.8% past them; a p90 needs 10 executions and a p99 100, and shows - with fewer")
            appendLine("  wall is the part of its root's wall time a row took: its durations summed, divided by")
            appendLine("    how many ran at a time under each parent, at its level and above. % parent is of the")
            appendLine("    parent's wall, and the rest of 100% is the parent's own time outside this child")
            appendLine("  parallel is how many of a row's executions ran at a time under the same parent execution,")
            appendLine("    blank at 1. Read it beside Per parent: 4 with 2.0 is four started, two at a time")
            appendLine("  '- name  own' under a row: a fine label in its own time, outside its coarse children, with")
            appendLine("    its own Parallel when one execution ran it on several threads at a time")
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
        // collects is the span that allocated, or the one after it.
        //
        // The table is the tree, with no separate flat ranking. Inclusive time is monotone up a
        // path, so the heaviest operation anywhere is at the end of the first branch - the ranking
        // holds.
        val pausedInSpan = pause.confirmedPausedIn(samplingSpanNanos)
        appendLine(
            // Wall time, so each row says what part of its parent it took: `branch` 88% of the request,
            // and the rest the request's own merge. Summed durations would read 988% of the parent
            // once a fork runs ten at a time. See wallsOf.
            "COARSE OPERATIONS - wall time, inclusive, nested as it ran" +
                    if (pausedInSpan > 0) "; a pause is charged to every operation it interrupted" else ""
        )
        appendLine(
            String.format(
                Locale.ROOT, coarseRow.replace("%,11d", "%11s"), "Coarse operation", "Executions", "Per parent", "Parallel", "Wall v", "% parent", "Paused", "Mean",
                if (allocations) String.format(Locale.ROOT, "%${ALLOCATED_COLUMN}s", "Allocated") else "",
                "p50", "p90", "p99", "Max", "Runnable / Waiting"
            )
        )
        appendLine("-".repeat(coarseRuleWidth))
        val byType = coarse.associateBy { it.type }
        val nodesOf = coarseTree.flatMap { it.flatten() }.groupBy { it.type }
        val walls = wallsOf()
        fun wall(n: CoarseNode): Double = walls[n] ?: n.spanSumNanos.toDouble()
        // The tree's order: heaviest wall first, so the row to read next is the top one.
        val heaviest = compareByDescending<CoarseNode> { wall(it) }.thenByDescending { it.inclusiveHits }

        // Only in a session that asked for it: a table nobody asked to be wider stays as it is.
        fun allocatedCell(text: String): String =
            if (allocations) String.format(Locale.ROOT, "%${ALLOCATED_COLUMN}s", text) else ""

        fun waitSplit(inclusive: Long, running: Long): String =
            runnableSplit(if (inclusive == 0L) 0.0 else (inclusive - running).toDouble() / inclusive)

        fun shareText(share: Double): String = if (share < 0.5) "<1%" else String.format(Locale.ROOT, "%.0f%%", share)

        // Against the row above: how many executions per execution of the parent - the loop count -
        // and what part of the parent's wall time this row is. Left blank where there is nothing to
        // read: a root has no parent, and a child run exactly once per parent execution is 1. Under
        // 100%, with the rest the parent's own time outside this child. Wall, not summed durations.
        fun perParent(n: CoarseNode, parent: CoarseNode?): String = when {
            parent == null || n.count == parent.count -> ""
            else -> perParentText(n.count, parent.count)
        }

        fun shareOfParent(n: CoarseNode, parent: CoarseNode?): String = when {
            parent == null || parent.spanSumNanos == 0L || n.count == 0L -> ""
            else -> shareText(wall(n) * 100.0 / wall(parent))
        }

        // Beside `Per parent`, so the two read as one sentence: 4 per request, 2.0 at a time - more
        // threads would help. Blank at 1, which is most rows. One decimal: the second of a sampled
        // average is noise. On the row that runs in parallel, not on the forking row, which is not
        // itself parallel.
        fun parallelText(n: CoarseNode): String =
            if (factorOf(n) > 1.0) String.format(Locale.ROOT, "%.1f", n.parallel) else ""

        var anySplit = false
        var anyOwn = false
        var anyCalls = false

        // A fine label under more than one row: each row is its time under that row alone, so the tree
        // never shows its total, and a label spread over three walks reads smaller than one that leads
        // a single walk: `merge` 1.08 s can lead a row while `check` is 2.11 s across three. The
        // total goes once, beside the label's largest row. Unlabelled is
        // not a label.
        //
        // With calls counted, every row the label was called under counts, sampled or not, so the
        // number of operations is exact.
        val spread = coarseTree.flatMap { it.flatten() }
            .flatMap { n -> ownRowsOf(n).orEmpty().filter { it.name != "unlabelled" }.map { it.name to (n to it) } }
            .groupBy({ it.first }, { it.second })
            .filterValues { rows -> rows.size > 1 && rows.sumOf { it.second.wallNanos } >= NOTE_MIN_GAIN * rows.maxOf { it.second.wallNanos } }
        val noteAt: Map<Pair<CoarseNode, String>, String> = spread.map { (name, rows) ->
            val calls = if (rows.all { it.second.calls >= 0 }) String.format(Locale.ROOT, " and %,d calls", rows.sumOf { it.second.calls }) else ""
            (rows.maxBy { it.second.wallNanos }.first to name) to
                    "${duration(rows.sumOf { it.second.wallNanos })}$calls in all, under ${rows.size} operations"
        }.toMap()

        // A profile saved without own time: a leaf's samples split by label and applied to its wall.
        // See ownRowsOf for why that is only a fallback.
        fun StringBuilder.legacySplit(n: CoarseNode, indent: String, timed: Boolean) {
            if (n.children.isNotEmpty() || n.inclusiveHits <= 0) return
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
                        Locale.ROOT, coarseRow.replace("%,11d", "%11s"),
                        "$indent  $SPLIT_MARK $name", "own", "", "",
                        if (timed) duration(wall(n) * share) else "-",
                        shareText(share * 100), "", "", allocatedCell(""), "", "", "", "", ""
                    ).trimEnd()
                )
            }
            val rest = parts.drop(FINE_ROWS_SHOWN)
            if (rest.isNotEmpty()) appendLine(
                "$indent  $SPLIT_MARK and ${rest.size} more, " + shareText(rest.sumOf { it.second } * 100.0 / n.inclusiveHits) + " between them"
            )
        }

        fun StringBuilder.node(n: CoarseNode, depth: Int, parent: CoarseNode?) {
            val indent = "  ".repeat(depth)
            // An execution still open when the session ended has no span: its row keeps the
            // samples and the children, and says `-` where the clock would go.
            val timed = n.count > 0
            appendLine(
                String.format(
                    Locale.ROOT, coarseRow,
                    indent + n.name, n.count, perParent(n, parent), parallelText(n),
                    if (timed) duration(wall(n)) else "-",
                    shareOfParent(n, parent),
                    if (n.pausedNanos > 0) duration(n.pausedNanos.toDouble()) else "-",
                    if (timed) duration(n.meanSpanNanos) else "-",
                    allocatedCell(bytes(allocatedPerExecution(n))),
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
            // The row's own time by fine label, beside its coarse children and sorted with them, so
            // the rows under a row are what it was made of: `request` is its branches and its merge.
            // A fine label counts as a coarse child would - its wall once per execution however many
            // threads ran it, its Parallel how many did - since fine and coarse rows answer the same
            // question here. Rows under half a percent are counted, not drawn. See ownRowsOf.
            val own = ownRowsOf(n)
            if (own == null) {
                legacySplit(n, indent, timed)
                for (child in n.children.sortedWith(heaviest)) node(child, depth + 1, n)
            } else {
                val drawn = drawnOwnRows(own)
                if (drawn.isNotEmpty()) anyOwn = true
                if (drawn.any { it.calls >= 0 }) anyCalls = true
                val items: List<Pair<Double, Any>> = n.children.map { wall(it) to it as Any } + drawn.map { it.wallNanos to it as Any }
                for ((_, item) in items.sortedWith(compareByDescending<Pair<Double, Any>> { it.first })) {
                    if (item is CoarseNode) node(item, depth + 1, n)
                    else {
                        val r = item as OwnRow
                        // Counted where it can be: its calls on this path, and per execution of the
                        // row, which holds whatever the size of the run. A row called and never
                        // sampled has its calls and no time.
                        val caught = r.wallNanos > 0
                        appendLine(
                            String.format(
                                Locale.ROOT, coarseRow.replace("%,11d", "%11s"),
                                "$indent  $SPLIT_MARK ${r.name}",
                                if (r.calls >= 0) String.format(Locale.ROOT, "%,d", r.calls) else "own",
                                if (r.calls >= 0) perParentText(r.calls, n.count) else "",
                                if (r.parallel >= PARALLEL_SHOWN) String.format(Locale.ROOT, "%.1f", r.parallel) else "",
                                if (timed && caught) duration(r.wallNanos) else "-",
                                if (r.share.isNaN() || !caught) "" else shareText(r.share * 100), "",
                                if (r.perCallNanos.isNaN()) "" else duration(r.perCallNanos), allocatedCell(""), "", "", "", "", ""
                            ).trimEnd() + (noteAt[n to r.name]?.let { " ".repeat(NOTE_MARGIN) + it } ?: "")
                        )
                    }
                }
                val rest = own.filter { it !in drawn }
                val restCalls = rest.sumOf { it.calls.coerceAtLeast(0) }
                if (rest.isNotEmpty()) appendLine(
                    "$indent  $SPLIT_MARK and ${rest.size} more, " + shareText(rest.sumOf { it.share.takeIf { s -> !s.isNaN() } ?: 0.0 } * 100) + " between them" +
                            if (restCalls > 0) String.format(Locale.ROOT, ", %,d calls", restCalls) else ""
                )
            }
        }
        for (root in coarseTree.sortedWith(heaviest)) {
            node(root, 0, null)
        }
        appendLine("-".repeat(coarseRuleWidth))
        // The key, once and next to the rows that need it, as the star's is under the own-time table.
        if (anySplit) appendLine(
            "  $SPLIT_MARK rows split a leaf's time by the innermost label, each sample once - they add up to 100%;" +
                    " a fine label with labels inside it shows only its own part"
        )
        if (anyOwn) appendLine(
            "  $SPLIT_MARK rows are fine labels in a row's own time, outside its coarse children; Parallel on one is how many" +
                    " threads ran it at a time in one execution; a fine label with labels inside it shows only its own part"
        )
        if (anyCalls) appendLine(
            "  on a $SPLIT_MARK row, Executions is its calls on that path and Per parent per execution of the row above, both counted;" +
                    " Mean is its own thread-time per call, sampled; - for a label called there and never caught"
        )
        if (allocations) appendLine(
            "  Allocated: bytes per execution, its coarse children and every thread under it included; counted, not sampled"
        )
        if (noteAt.isNotEmpty()) appendLine(
            "  'in all, under N operations' beside a fine row: that label's time, and calls where counted, under every row it is under, on its largest"
        )
        // An operation with more than one caller is a row under each, and those rows are its
        // figures under that caller alone. Its percentiles over everything are a different
        // question - "how long does a parse take" - and they are answered here, once, only when
        // the question exists.
        fun wallOf(c: CoarseStat): Double = nodesOf[c.type]!!.sumOf { wall(it) }
        val shared = seen.filter { (nodesOf[it.type]?.size ?: 0) > 1 }.sortedByDescending { wallOf(it) }
        if (shared.isNotEmpty()) {
            appendLine("  Under more than one caller - all callers together:")
            for (c in shared) {
                val paused = nodesOf[c.type]!!.sumOf { it.pausedNanos }
                appendLine(
                    String.format(
                        Locale.ROOT, coarseRow,
                        "  " + c.name, c.count, "", "",
                        duration(wallOf(c)), "",
                        if (paused > 0) duration(paused.toDouble()) else "-",
                        duration(c.meanSpanNanos),
                        allocatedCell(bytes(nodesOf[c.type]!!.sumOf { n -> n.flatten().sumOf { it.allocated } }.toDouble() / c.count)),
                        duration(c.percentileNanos(0.50)),
                        duration(c.percentileNanos(0.90)), duration(c.percentileNanos(0.99)),
                        duration(c.spanMaxNanos.toDouble()),
                        waitSplit(c.inclusiveHits, c.runningInclusiveHits)
                    )
                )
            }
            appendLine("-".repeat(coarseRuleWidth))
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
        // The column cannot see a native wait, and on a workload that does nothing else that is not a
        // rounding error. Named per type, with both readings, because the number is not useless —
        // it is right on a CPU-bound operation and it is the *reader* who knows which they have.
        val contradicted = coarse.filter { it.count > 0 && workingIsContradicted(it) }
        if (contradicted.isNotEmpty()) {
            appendLine()
            appendLine("!".repeat(pageWidth))
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
            appendLine("!".repeat(pageWidth))
        }
        // The opposite fault to the outside-coarse line, and the more serious one. That one is
        // attribution lost and says so; this is attribution invented, and it is stated with the
        // remedy because there are two quite different causes with opposite fixes.
        if (staleContextHits > 0) {
            val worst = coarse.maxByOrNull { it.staleHits }
            appendLine()
            appendLine("!".repeat(pageWidth))
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
            appendLine("!".repeat(pageWidth))
        }
    }

    /**
     * One line of the operations table: a fine operation, or a coarse operation's own work.
     *
     * The two tiers share a table because a coarse label collects everything a fine one does and
     * more, and moving an operation between tiers should not change the report's shape - only a
     * change in the program should. What makes one table honest is that the hits partition: a fine operation's are the
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
    internal inner class OperationRow internal constructor(
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
    internal fun rows(): List<OperationRow> =
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
     * Every warning of the report, for its `CAREFUL` section right under the first line (see
     * [carefulBlock]).
     *
     * All in one place and before any table, not where each is computed: printed there, a warning
     * about a fine operation would sit under the coarse table and read as one about a coarse one.
     */
    private fun warnings(): List<String> =
        // Ordered by what each costs the reader: numbers that are missing or inflated first, then
        // what limits the whole run, then labels in the wrong tier, which are honest measurements
        // of the wrong-sized thing.
        buildList {
            // A leaked label does not look like an error. It looks like a finding: one operation
            // quietly accumulating everybody else's samples, with a plausible number beside it.
            if (imbalances > 0) add(
                "labels: $imbalances were still open at a point the caller said should be quiescent - " +
                        "everything after each leak was billed to the leaked operation\n" + NO_FINALLY
            )
            if (openAtEnd > 0) add(
                "labels: $openAtEnd threads were still inside a hand-placed one when sampling stopped - " +
                        "nothing can close it now\n" + NO_FINALLY
            )
            if (allocationsMissing) add("allocations: this JVM cannot report allocated bytes, so there is no Allocated column")
            if (untrackedSlots > 0) add(
                "threads: $untrackedSlots arrived past the $MAX_SLOTS-slot ceiling and were NOT SAMPLED - " +
                        "their thread-time is missing from every number, including the denominators"
            )
            // No warning for threads that exited without Profiler.release(): the sampler skips a
            // thread that has exited and the slot is reclaimed within a second, so nothing above is
            // distorted.
            if (openContextsAtEnd > 0) add(
                "coarse labels: $openContextsAtEnd executions were still open when sampling stopped - their spans " +
                        "are missing, and every sample taken inside them was billed to them"
            )
            // What limits the whole run: the pauses, the cores, the clock - the clock only when it
            // moved or could not be read, a steady one being the healthy case.
            addAll(pause.careful() + duty.careful(activeNanos, reclaimedSlots) + clock.careful())
            for (op in suspect()) add(
                String.format(
                    Locale.ROOT,
                    "%s: %,d executions lasted over a tick (%s of its thread-time against a %s machine floor, %s per call) -",
                    op.name, op.stuckInstances, percent(op.stuckShare * 100), percent(machineFloor * 100),
                    duration(impliedNanosOf(op))
                ) + "\n" + verdictFor(op)
            )
            // The `Workers` and `Pool` columns, read together and said out loud.
            for (op in serialised()) add(
                "${op.name}: ${plural(op.threads.toLong(), "thread")} ran it and no two were ever inside it " +
                        "at once - every thread that enters waits for the last one to leave: a serialization point, " +
                        "not parallel work"
            )
            // The other end of the same question, and a warning rather than a verdict: a label
            // below the floor cannot move a ranking, so the cost of being wrong about one is the
            // loudest failure this tool has - stopping a run that was fine. Every offender is named;
            // the reader decides.
            for (op in tooSmall()) add(tooSmallMessage(op.name, op.calls, impliedUpperNanosOf(op)))
            // The same boundary from the other side. Unlike the fine floor this one is exact,
            // because the duration it compares is measured rather than inferred — see
            // coarseTooSmall.
            for (c in coarseTooSmall()) add(
                coarseTooSmallMessage(c.name, c.count, c.meanSpanNanos, coarseFloorNanosOf(c), coarseContextNanos)
            )
        }

    /**
     * @param legend every column explained, at the bottom. Off by default: see [renderLegend].
     * @param careful warnings from the caller, in a `CAREFUL` section under the logo: [ProfileRuns]'s
     * line that one run has no interval.
     */
    internal fun render(legend: Boolean = false, careful: List<String> = emptyList()): String = buildString {
        if (failure != null) {
            appendLine("!".repeat(pageWidth))
            appendLine("PROFILING STOPPED - these numbers were not going to be right:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(pageWidth))
        }
        // One long rule at the top and one after the legend: the report lands in a console full of
        // the program's own output, and its first and last lines should say so. No rules between
        // sections - the headings and the tables' own rules do that, and a rule beside a table's
        // rule is a doubled separator. The logo is not here: it is printed once per JVM by the
        // first TickSnick call, see [Logo].
        appendLine("=".repeat(pageWidth))
        // Every warning, before any number: see [carefulBlock] and [warnings].
        carefulBlock(careful + warnings())
        // One line: wall time split into active and paused. The report shows what matters for
        // optimising performance or says the report is unreliable. The split is the first - GC
        // tuning is about those figures. Mechanisms (the step, the clock, the windows) and lines
        // that would only say "fine" are left out: silence says "fine", and a `CAREFUL` line says
        // what is unreliable, before the numbers it is about and not in a footnote after them.
        appendLine(pause.headerLine(samplingSpanNanos))
        // Always, under the wall time: who had the cores, the program's threads being only some of it.
        duty.coresLine()?.let { appendLine(row("Cores", it)) }
        // The one mechanism shown: whether the sampler held a processor, which the reader can change.
        samplingLine()?.let { appendLine(row("Sampling", it)) }
        // The tree first: the overview - what ran inside what, how often, what part of its parent -
        // and the own-time table after it for the detail. The tree is what a reader reads; the
        // table is what they look up.
        renderCoarse()
        appendLine()
        // FINE, not bare OPERATIONS. `OPERATIONS` beside `COARSE OPERATIONS` makes one tier the
        // default and the other the exception, the asymmetry `registerFine`/`registerCoarse` avoids
        // in the API. The heading carries the one thing a reader has to hold between the two tables. A coarse
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
        // Both tiers, sorted together further down. Computed here because everything from the
        // band downwards is printed only if some row survived, of either tier.
        // A coarse operation with no own time is not a row: three rows of zeros at the bottom are
        // not digits. It is one line under the table, worded for what happened - all of its time
        // was inside other operations, which is the ordinary state of a span around labelled work -
        // and not as "never sampled", which it was not: `query` inside `runBatch` inside `run`,
        // each taking all of the time of the one around it.
        val allRows = rows()
        val contained = allRows.filter { it.coarse && it.hits == 0L && it.inclusiveHits > 0 }
        val (seen, unseen) = allRows.filter { it !in contained }.partition { it.hits > 0 }
        // Time inside no label, as a row rather than a `Coverage` line: it is the time the reader
        // has no information about, and it belongs in the ranking with everything else. Its
        // `Runnable / Waiting` is the first question about it - idle threads, or work nobody
        // labelled - and the row answers it.
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
                // arithmetic, and a band of their own would only repeat its first column. `Long`
                // sits right after them so the sentence continues - so many calls at this much
                // each, of which these ran long - and `Spread` follows, because how the time lay
                // across threads is a different question and on one thread a column of ones.
                fineColumns, FINE_BREAKS,
                1..5 to "Load", 6..8 to "Long", 9..12 to "Spread", 13..14 to "Trust"
            )
        )
        if (anyRow) appendLine(
            String.format(
                // `v` marks the column the table is sorted by: at two rows a reader cannot tell a
                // sorted table from an unsorted one by looking. ASCII, not an arrow glyph: a Windows
                // console is not UTF-8 by default.
                //
                // The header uses the same column widths as the data rows. Any difference is
                // invisible until a bar is drawn through both, and then it comes out crooked.
                Locale.ROOT, fineRow,
                // `Thread-time%` and `Thread-time` are one quantity, relative and absolute, and
                // neither is CPU. `Thread-time` against `Wall-time` is the point of having both:
                // eight threads computing five seconds is forty thread-seconds and the sum is real;
                // a hundred threads parked one second on a lock is also a hundred, and there the
                // sum is fiction.
                //
                // Both halves of the split, summing to 100%, rather than the waiting half alone:
                // two shares that add to the whole cannot be read as an addition to it.
                //
                // `Runnable`, not `Run` or `CPU`. The `-able` is the whole distinction: it means
                // *not blocked*, not *executing*. A preempted thread reads runnable - 14-18% of
                // wall time on a workload that never blocks - and so does a thread stopped in a
                // native call, which can put the figure 55x above what the machine spent. The left
                // half is an upper bound on work; only the time-on-CPU block above can say how much
                // of it was real.
                //
                // `Concurrency` and not `Parallelism`: the number counts threads INSIDE the label
                // whether they are running or parked, which is what concurrency means; parallelism
                // needs them executing. Two threads inside a label at 7.2% runnable are concurrency
                // 2.00 and a parallelism of about 0.14.
                //
                // `Concurrency`, `Workers`, `Pool` - three columns and no slash between them. A
                // denominator on the concurrency answers a question about the deployment from
                // inside a row about an operation: a pool of four that never runs more than three
                // tasks at once would print `3.00 / 4`, where the 4 is a ThreadPoolExecutor detail
                // - it opens a new core thread on every submit until the core size is reached. And
                // `Concurrency / Peak` reads as the name of one quantity, when a fractional mean and
                // an integer maximum are not two views of one whole the way `Runnable / Waiting` is.
                //
                // Each row is then read off its own headings with no arithmetic: *concurrency 1.1,
                // so concurrency here is poor; workers 2, but I dispatched 3, so something is off;
                // pool 4, which is what I configured.* `Max`, `Peak` and `Most` name a statistic and
                // leave the reader to supply the noun; `Workers` names what was counted.
                "Operation", "Thread-time% v", "Thread-time", "Runnable / Waiting",
                // `Thread-time per call` sits beside `Calls` because it is hits x step / calls: both
                // operands are columns on the same row, and it inherits the caveat `Thread-time`
                // carries. Not "implied": every number in the table except `Calls` is sampled.
                "Calls", "Thread-time per call",
                // The long executions, read off the calls they are part of: how many ran over a
                // tick, the thread-time inside them, and the longest. The count tells a resize
                // from a lock; the time is what fixing it is worth; the longest tells two dozen
                // of ten milliseconds from one of two seconds.
                "Execs", "Time", "Longest",
                "Wall-time", "Concurrency", "Workers", "Pool", "Hits", "Noise"
            )
        )
        if (anyRow) appendLine(rule(fineColumns, FINE_BREAKS))
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes. Folded, not dropped: the
        // count says how many there were, and a *called* operation with no samples is a real
        // finding, since it means the label is on something too small to see.
        // Partitioned above the header rather than here, because whether the header prints at
        // all depends on it. See where `seen` is declared.
        // A table with no rows is two rules around nothing. Say what happened instead: an empty
        // table is nearly always a run too short for the sampler to catch, and that is worth
        // naming rather than leaving the reader to infer from a blank.
        // It says "run for longer" and not "or lower stepMillis": below
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
                    Locale.ROOT, fineRow,
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
                    r.hits.toString(), percent(r.noiseFloor() * 100),
                )
            )
        }
        if (anyRow) appendLine(rule(fineColumns, FINE_BREAKS))
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
            // answer is usually the whole point. Capped at four, since a run can fold twenty-five.
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
        // of everything above. Not in the header: it says nothing a reader acts on before the
        // numbers, and something after them, when a share looks too close to call. "mean (jittered)": the interval is drawn +/-25% around the step so the sampler
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
        appendLine("-".repeat(pageWidth))
        // Said in the output rather than only in a document: the gap between a share and what
        // removing the operation would save can be a factor of 275, and nothing else on the screen
        // hints at it.
        renderLegend(legend)
        appendLine("=".repeat(pageWidth))
    }

    /**
     * Writes this profile as a new run in [folder], named by the time it is saved -
     * `<folder>/<time>.tsp`, see [ProfileStore.saveNew] - and returns the file. A folder holds the
     * runs of one version of the code, and is what [TickSnick.load] reads: the only way to look at a
     * profile is to save it and load its folder.
     */
    fun save(folder: java.nio.file.Path): java.nio.file.Path =
        ProfileStore.saveNew(this, folder).also { Logo.once() }

    companion object {
        /**
         * How far above the run-wide rate an operation has to sit before it is named, how many
         * long executions it needs behind that rate, and the floor below which the rate is not
         * worth mentioning at all.
         *
         * All three are provisional, not yet settled by measurement - an operation contending on a
         * lock at a known rate would settle them. Kept here together so that this is the one place
         * that changes.
         */
        internal const val SUSPECT_OVER_BASELINE = 3.0
        internal const val SUSPECT_MIN_INSTANCES = 20L
        internal const val SUSPECT_MIN_SHARE = 0.02

        /**
         * Below this, a label is not describing the operation it names.
         *
         * Measured rather than chosen: the sampled share of operations of known duration against
         * their true share, over a hundredfold range of durations. From 70 ns upward every leaf lands within ±2.4% and
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
         * CPU-bound than the run around it. Generous on purpose: the failure this exists to catch
         * reads around **55x** for a client waiting on a socket, so strictness buys nothing and a
         * false accusation costs the reader their trust in the column.
         *
         * Not much lower: a program of two threads and a `Thread.sleep`, with no native call in it,
         * can read 2.2x. That gap is preemption - a thread that is runnable but waiting for a core
         * reads `RUNNABLE` without being on one, 14-18% of wall time on a workload that never blocks
         * (see `Sampler.kt`). A threshold below that measures the scheduler, not the program.
         *
         * 4.0 still catches the case this exists for by a factor of thirteen. What it gives up is a
         * genuine native-wait problem under 4x, a band the scheduler fills with noise anyway.
         */
        internal const val WORKING_CEILING_SLACK = 4.0

        internal const val FLOOR_NANOS = 50.0

        /** The fine table's row for samples inside no label. */
        internal const val NO_OPERATION = "(no operation)"

        /**
         * Below this share, labelled time outside every coarse span is not worth a word.
         *
         * Something under a fraction of a percent of the work cannot be where the work went, and a
         * check that chased it would spend its credibility on false positives. A request span that
         * covers a whole pipeline still has a handful of samples land outside it, during connection
         * setup and at the tail of a write: **0.0%, three milliseconds**, not worth six lines of
         * warning.
         */
        internal const val OUTSIDE_COARSE_MIN_SHARE = 0.01

        /** Below this many ticks, an empty table is the run being short rather than a finding. */
        internal const val MIN_TICKS_FOR_A_TABLE = 50

        // The report-level rules and the warning banners, matching the widest table on the page.
        // The fine table has no width constant of its own - it is computed from its columns and
        // bars, because a hand-kept number drifts and the rules come to print at different lengths
        // on one page.
        internal const val WIDTH = 168

        /** The coarse table alone, drawn to the table rather than to a round number. */
        internal const val COARSE_WIDTH = 167

        /**
         * One row of the coarse table: name, executions, per parent and parallel, wall and its share of
         * the parent, paused, mean, the `Allocated` cell - padded by the caller, and empty unless asked
         * for - four durations, the split.
         */
        internal const val COARSE_ROW = "%-30s %,11d %10s %8s %10s %8s %10s %10s%s %10s %10s %10s %10s %18s"

        /** One row of the fine table, heading and data. The widths are [FINE_COLUMNS]. */
        internal const val FINE_ROW = "%-26s | %14s %11s %18s %13s %20s | %7s %8s %8s | %9s %11s %7s %5s | %8s %6s"

        /**
         * The fine table's data-row widths, in order, so the group band can be laid over them.
         *
         * Operation, thread-time% and thread-time, runnable/waiting, calls, thread-time per call,
         * the three long-execution columns, wall-time, concurrency, workers, pool, hits, noise.
         */
        internal val FINE_COLUMNS = intArrayOf(26, 14, 11, 18, 13, 20, 7, 8, 8, 9, 11, 7, 5, 8, 6)

        /** After which columns a group bar comes down. */
        internal val FINE_BREAKS = intArrayOf(0, 5, 8, 12)

        /**
         * Hits below which `Peak 1` is not evidence of anything.
         *
         * A peak of one drawn from a handful of samples is what a *short* operation looks like, not
         * what a serialized one looks like. At 25 hits a second thread present a tenth of the time
         * would have been caught nine times in ten.
         */
        internal const val MIN_HITS_FOR_SERIAL = 25

        /**
         * `1 thread` rather than `1 threads`.
         *
         * A report that cannot count is a report a reader trusts slightly less, and the places this
         * matters are exactly the small runs where every other number is already marginal.
         */
        /** Reads a profile written by [save]. */
        internal fun load(path: java.nio.file.Path): Profile = ProfileStore.load(path)

        internal fun plural(n: Long, noun: String): String =
            if (n == 1L) "1 $noun" else String.format(Locale.ROOT, "%,d ${noun}s", n)

        /** The short-operation bias, so the check cannot accuse an operation of the sampler's own error. */
        internal const val FLOOR_BIAS_ALLOWANCE = 1.2

        /** Hits below which an operation's rate of long executions is too noisy to enter the median. */
        internal const val MEDIAN_MIN_HITS = 100L

        /**
         * How much of an operation's long-running time must be *certainly* on CPU before the report
         * says it is working rather than waiting.
         *
         * A judgement, and a cautious one, because the bound behind it is already conservative — it
         * charges the operation with every stall in the whole run. A slow CPU-bound operation can
         * clear it at 91%; one that does nothing but wait on a lock comes nowhere near.
         */
        internal const val RUNNING_CERTAIN = 0.5

        /**
         * Below this share of waiting among an operation's long samples, it was working.
         *
         * Not zero, because one sample in a thousand catching a GC pause or a page fault is not
         * the operation waiting on anything, and a verdict that hedged over it would hedge over
         * every operation on a real machine.
         */
        internal const val WAITING_NONE = 0.05

        /** Above this share, calling it waiting rather than working is the honest description. */
        internal const val WAITING_MOSTLY = 0.5
    }
}

