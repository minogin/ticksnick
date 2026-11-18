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
 * The shortest a fine operation may be, in bodies, before its figures stop holding: under it the
 * sampler reads it low. See [Run.fineFloorNanos].
 */
internal const val FINE_FLOOR_BODIES = 2.0

/**
 * The shortest a coarse operation may be, in bodies, with nothing opted into and `nanoTime` at
 * [FLOOR_NANOTIME_BODIES]: under it its share of the samples stops holding, since part of the hook
 * runs inside the span and a short execution loses samples. See [coarseFlatFloorBodies].
 */
internal const val COARSE_FLOOR_BODIES = 100.0

/**
 * What one `System.nanoTime()` costs, in bodies, where [COARSE_FLOOR_BODIES] holds. The coarse hook is
 * mostly its two `nanoTime` calls, so where one costs more the floor rises with the hook. See
 * [coarseHookScale].
 */
internal const val FLOOR_NANOTIME_BODIES = 0.89

/**
 * How many times [FLOOR_NANOTIME_BODIES] `nanoTime` must cost before the coarse floor rises with it.
 * Below it the ratio moves with the core the timing ran on, not with a slow clock source.
 */
internal const val NANOTIME_SLOW_FACTOR = 2.0

/** The coarse hook apart from its two `nanoTime` calls, in bodies. See [coarseHookScale]. */
internal const val COARSE_HOOK_REST_BODIES = 0.4

/**
 * One body, in ns, for a run without one, a session too short to time it: what a body takes on a
 * typical developer machine. Every limit of such a run is read at it.
 */
internal const val DEFAULT_BODY_NANOS = 25.0

/**
 * The most one coarse execution may cost with nothing opted into, in bodies: no fine label
 * registered and no allocated bytes counted - the context, two `nanoTime` calls and the accumulate. A
 * budget, not a measurement: a dearer hook has to fit under it. What a session opts into adds its
 * own price. See [coarseContextBodies].
 */
internal const val COARSE_BUDGET_BODIES = 4.0

/**
 * The most each registered fine label may add to a coarse execution, in bodies: the pass that moves
 * its calls onto the path they ran under, at every boundary. See `OpSlot.flushCalls`.
 */
internal const val COARSE_PER_FINE_LABEL_BODIES = 0.024

/**
 * What an opt-in's price is divided by to raise the coarse floor: a label at the floor pays at most
 * this share of its operation for what the session turned on.
 */
internal const val COARSE_MAX_OVERHEAD = 0.05

/** The most counting allocated bytes may add to a coarse execution, in bodies, when a session asks for it: two reads of the thread's total. */
internal const val COARSE_ALLOCATIONS_BODIES = 1.28

/** The most all the coarse executions together may cost of the run. */
internal const val COARSE_MAX_RUN_SHARE = 0.01

/** The most one fine hook entry may cost, in bodies: a budget, as [COARSE_BUDGET_BODIES] is. */
internal const val FINE_BUDGET_BODIES = 0.1

/**
 * Above this share of the time inside labels, what the hooks may have cost the run is a warning:
 * the same 1% as [COARSE_MAX_RUN_SHARE], for every hook together.
 */
internal const val HOOKS_MAX_SHARE = 0.01

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
    val raw = x
    x = BigDecimal(x).setScale(digits(x), RoundingMode.HALF_UP).toDouble()
    if (x >= 1024 && u < units.size - 1) {
        x = 1.0
        u++
    }
    // Under one byte and not 0 - a mean over executions - is below the last place, `<1 B`, as [fixed] writes it.
    return fixed(if (x == 0.0) raw else x, digits(x)) + " " + units[u]
}

/**
 * A loose figure of bytes as a range, `1.2..2.4 MB`: both ends in the larger one's unit, each by [intervalEnd], the
 * unit said once at the right, as [durationRange] writes one of time.
 */
internal fun bytesRange(lo: Double, hi: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var u = 0
    var scale = 1.0
    while (hi / scale >= 1024 && u < units.size - 1) {
        scale *= 1024
        u++
    }
    // Two digits can round the larger end up to 1,024 of its unit: then the next unit.
    if (intervalEnd(hi / scale).toDouble() >= 1024 && u < units.size - 1) {
        scale *= 1024
        u++
    }
    return intervalRange(intervalEnd(lo / scale), intervalEnd(hi / scale), " " + units[u])
}

/** The warning of a comparison where only one side counted allocated bytes. */
internal fun allocationsWarnings(before: List<Run>, after: List<Run>): List<Warning> {
    val b = before.any { it.allocations }
    val a = after.any { it.allocations }
    return if (b == a) emptyList() else listOf(
        Warning(
            Kind.ALLOCATIONS_ONE_SIDE,
            figure(COARSE_ALLOCATIONS_BODIES * (after + before).first { it.allocations }.bodyOrDefaultNanos, ::duration),
        )
    )
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
 *
 * Shared so the run-time check and the report cannot disagree.
 */
internal fun impliedUpperNanos(hits: Long, calls: Long, stepNanos: Double): Double =
    if (calls == 0L) Double.NaN else (hits + 2 * sqrt(hits.toDouble()) + 3) * stepNanos / calls

/**
 * The `Per parent` figure, in every table that has one: [count] per execution of the row above. `340`,
 * or `2.5` when the executions did not all make the same number; blank with no completed execution.
 */
internal fun perParentText(count: Long, parentCount: Long): String =
    if (parentCount <= 0L) "" else perParentText(count.toDouble() / parentCount)

/**
 * The `Per parent` figure from its value, the one rule for it: a whole number as it is, `5,262`; under
 * 1, three significant digits, `0.000300`, never a `0.0` for a rate that is not 0; under 100, one
 * decimal, `2.5`; past that, none.
 */
internal fun perParentText(perParent: Double): String = when {
    perParent == kotlin.math.floor(perParent) -> String.format(Locale.ROOT, "%,d", perParent.toLong())
    perParent < 1 -> java.math.BigDecimal(perParent).round(java.math.MathContext(3)).toPlainString()
    perParent < 100 -> fixed(perParent, 1)
    else -> fixed(perParent, 0, grouped = true)
}

/** Whether a label is on something too small for the instrument to describe. See [Run.tooSmall]. */
internal fun isTooSmall(hits: Long, calls: Long, stepNanos: Double, floorNanos: Double = Run.FLOOR_NANOS): Boolean =
    calls > 0 && impliedUpperNanos(hits, calls, stepNanos) * Run.FLOOR_BIAS_ALLOWANCE < floorNanos

/**
 * What TickSnick's hooks may have cost a run, at their budgets and the run's body: [fineEntries] times
 * a fine hook ran, [coarseExecutions] coarse executions, [costNanos] of thread-time in all, out of
 * [labelledNanos] inside labels and [wallNanos] of the run's wall time; [top] the label that cost the
 * most, where to start. See [Run.hookCost].
 */
internal class HookCost(
    val fineEntries: Long, val coarseExecutions: Long, val costNanos: Double, val labelledNanos: Double, val wallNanos: Double,
    val top: String? = null,
) {
    /** Of the work inside labels: what the hooks add to it. */
    val share: Double get() = costNanos / labelledNanos

    /**
     * Of the wall time: the most the hooks can add to it, all of their cost on the run's critical
     * path. A run whose labelled work is spread over many threads gains much less.
     */
    val wallShare: Double get() = costNanos / wallNanos
}

/** Labels by name in a warning, or how many when the run saved no names: `` `a`, `b` `` or `3 labels`. */
internal fun labelsText(names: List<String>, count: Int): String =
    if (names.isEmpty()) "$count label" + (if (count == 1) "" else "s") else codeList(names)

/**
 * [values] as one figure in [format], or the range `low..high` where they differ, each end with its unit
 * (`41.0ns..41.7ns`, `950us..1.20ms`). Every range the report prints is written here.
 */
internal fun rangeText(values: List<Double>, format: (Double) -> String): String = rangeText(format(values.min()), format(values.max()))

/** A range already written at both ends, `3..10`: the one way a range of figures is put together. See [rangeText]. */
internal fun rangeText(low: String, high: String): String = if (low == high) low else "$low..$high"

/**
 * **An interval as a range**: one figure with its margin, so its two ends in one unit, said once at the right,
 * `1.26..1.73s`, `59..67%`. Unlike [rangeText], whose ends are figures of their own, each with its unit. A change's
 * interval is [changeRange].
 */
internal fun intervalRange(low: String, high: String, unit: String): String = "$low..$high$unit"

/**
 * **A change as a range: the one function** for every change range a diff writes, in its tables and its header
 * (docs/output.md, "The diff"). Every number is its real value, and the range runs from the end nearer
 * zero, the least it moved first, so it shows its direction by its order and takes no `+`: `-8.2..-62%`,
 * `180..300%`, `-0.1..1.5pp`, `5..-40pp`. In Unicode an arrow comes first, `↓-8.2..-62%`, `↑-0.1..1.5pp`: it repeats
 * what the numbers say, for reading a column at a glance. Only a change that moved is written so ([Judged]): [low]
 * and [high] are its interval's ends, its direction its middle's, and [end] writes one end's size, unsigned, in [unit].
 */
internal fun changeRange(low: Double, high: Double, unit: String, end: (Double) -> String): String {
    val (near, far) = if (abs(low) <= abs(high)) low to high else high to low
    fun real(x: Double) = (if (x < 0) "-" else "") + end(abs(x))
    return (if (low + high > 0) sym.up else sym.down) + real(near) + ".." + real(far) + unit
}

/**
 * **A change as one value: the one function**, a change that moved in the brief view when it is certain or small
 * ([Judged.oneValue]): its middle [v], [size] its size as text, unit included. Its real value, so it carries its
 * sign; in ASCII, with no arrow before it, a rise takes its `+`: `-40%`, `+1.0pp`; in Unicode `↓-40%`, `↑1.0pp`.
 */
internal fun changeValue(v: Double, size: String): String =
    if (v < 0) sym.down + "-" + size else sym.up + (if (sym.up.isEmpty()) "+" else "") + size

/** A change in percent or points as [changeRange] writes it, `-18..-30%`, `4.7..5.9pp`: tenths under 10, by [intervalEnd]. */
internal fun percentChangeRange(low: Double, high: Double, unit: String): String =
    changeRange(low, high, unit) { intervalEnd(it, if (it < 9.95) 1 else 0) }

/**
 * **A change with no interval: the one function**, one run on a side: its value with its sign and no arrow, as nothing
 * judged it, in the places [percentChangeValue] writes, tenths under 10: `-24%`, `+12pp`, `+<0.1pp`.
 */
internal fun unjudgedChange(v: Double, unit: String): String = fixed(v, if (abs(v) < 9.95) 1 else 0, signed = true) + unit

/** A change in time with no interval, as [unjudgedChange]: `-100ms`, `+2.13s`. */
internal fun unjudgedDuration(v: Double): String = (if (v < 0) "-" else "+") + duration(abs(v))

/** A change in percent or points as [changeValue] writes it, tenths under 10 as [signedPercent]: `-92%`, `+5.3pp`. */
internal fun percentChangeValue(v: Double, unit: String): String {
    val m = abs(v)
    return changeValue(v, fixed(m, if (m < 9.95) 1 else 0) + unit)
}

/**
 * **An interval's end: at most two significant digits**, a figure's range and a change's alike: `0.15`, `2.4`,
 * `7.3`, `250`, `180`. A range is written only for what is more than 20% unsure, so a third digit claims a
 * precision it does not have. No more than [maxDecimals] places either, where the column writes fewer (a change's
 * tenths, a share of the parent's whole percents). Rounded once, half up; the decade is taken after rounding, so
 * 9.96 is `10`. [v] is not negative.
 */
internal fun intervalEnd(v: Double, maxDecimals: Int = Int.MAX_VALUE): String {
    // The double's shortest decimal, as String.format rounds it everywhere else: 3.65 is 3.7, not 3.6.
    val exact = java.math.BigDecimal.valueOf(v)
    if (v == 0.0) return exact.setScale(minOf(maxDecimals, 1)).toPlainString()
    val two = exact.round(java.math.MathContext(2, java.math.RoundingMode.HALF_UP))
    val exponent = two.precision() - two.scale() - 1
    val places = minOf(maxDecimals, 1 - exponent)
    val end = exact.setScale(places, java.math.RoundingMode.HALF_UP)
    // Past the places the column writes, an end that is not 0 is below their last unit, not 0: `<0.1`.
    return if (end.signum() == 0) belowPlaces(places) else end.toPlainString()
}

/**
 * **A number at a fixed number of decimal places: the one function** for every such number a report writes, a figure,
 * an interval, a change, a share or a count's mean. One that is not 0 but rounds to 0 at its [places] is written as
 * below one unit of its last place, with its sign - `<0.1`, `-<0.01`, `<1` -, never as a measured `0.0`, which it is
 * not. Times, at three significant digits, never round to 0; bytes under one, a mean over executions, are `<1 B`. [grouped] puts in thousands separators,
 * `5,262`; [signed] a `+` before a rise.
 */
internal fun fixed(v: Double, places: Int, grouped: Boolean = false, signed: Boolean = false): String {
    if (v.isNaN()) return UNKNOWN
    if (v == 0.0) return String.format(Locale.ROOT, "%.${places}f", 0.0)  // -0.0 too, which has no sign to show
    if (v != 0.0 && abs(v) < 0.5 * Math.pow(10.0, -places.toDouble())) return (if (v < 0) "-" else if (signed) "+" else "") + belowPlaces(places)
    return String.format(Locale.ROOT, "%${if (signed) "+" else ""}${if (grouped) "," else ""}.${places}f", v)
}

/** Below one unit of the last of [places]: `<0.1`, `<0.01`, `<1`. See [fixed]. */
private fun belowPlaces(places: Int): String = sym.less + if (places <= 0) "1" else "0." + "0".repeat(places - 1) + "1"


/**
 * How much dearer the coarse hook is than where [COARSE_FLOOR_BODIES] holds: the hook is two `nanoTime`
 * calls and [COARSE_HOOK_REST_BODIES], so a `nanoTime` of [nanoTimeBodies] scales it by this. 1 when
 * `nanoTime` was not timed, or costs under [NANOTIME_SLOW_FACTOR] times [FLOOR_NANOTIME_BODIES].
 */
internal fun coarseHookScale(nanoTimeBodies: Double): Double =
    if (nanoTimeBodies.isNaN() || nanoTimeBodies < NANOTIME_SLOW_FACTOR * FLOOR_NANOTIME_BODIES) 1.0
    else (2 * nanoTimeBodies + COARSE_HOOK_REST_BODIES) / (2 * FLOOR_NANOTIME_BODIES + COARSE_HOOK_REST_BODIES)

/** The most one coarse execution may cost, in bodies, with [fineLabels] fine labels registered. */
internal fun coarseContextBodies(fineLabels: Int, allocations: Boolean = false, hookScale: Double = 1.0): Double =
    COARSE_BUDGET_BODIES * hookScale + COARSE_PER_FINE_LABEL_BODIES * fineLabels + if (allocations) COARSE_ALLOCATIONS_BODIES else 0.0

/** The flat coarse floor, in bodies: [COARSE_FLOOR_BODIES] scaled with the hook, and each opt-in's price over [COARSE_MAX_OVERHEAD]. */
internal fun coarseFlatFloorBodies(fineLabels: Int, allocations: Boolean = false, hookScale: Double = 1.0): Double =
    COARSE_FLOOR_BODIES * hookScale +
            (COARSE_PER_FINE_LABEL_BODIES * fineLabels + if (allocations) COARSE_ALLOCATIONS_BODIES else 0.0) / COARSE_MAX_OVERHEAD

/**
 * What raises the flat coarse floor, for the warning about an operation under it, at a body of
 * [bodyNanos], each rounded up as the floor is: `Allocated adds 600ns, 20 fine labels add 200ns`, or
 * nothing when nothing does. Beside the execution's mean, it says whether turning one off would do.
 */
internal fun coarseFloorRaised(fineLabels: Int, allocations: Boolean, bodyNanos: Double = DEFAULT_BODY_NANOS, hookScale: Double = 1.0): String =
    buildList {
        if (hookScale > 1.0) add("a slow `nanoTime` adds ${limitText(recommendedLimit(COARSE_FLOOR_BODIES * (hookScale - 1) * bodyNanos))}")
        if (allocations) add("Allocated adds ${limitText(recommendedLimit(COARSE_ALLOCATIONS_BODIES / COARSE_MAX_OVERHEAD * bodyNanos))}")
        if (fineLabels > 0) add(
            "$fineLabels fine label" + (if (fineLabels == 1) " adds " else "s add ") +
                    limitText(recommendedLimit(COARSE_PER_FINE_LABEL_BODIES * fineLabels / COARSE_MAX_OVERHEAD * bodyNanos))
        )
    }.joinToString(", ")

/**
 * The shortest a coarse operation may be, given how much of the run it holds, the flat floor
 * [flatFloorNanos] and what one execution costs, [contextNanos]. See [Run.coarseTooSmall].
 *
 * Two conditions, protecting different things and neither implying the other:
 *
 * 1. `cost x executions <= 1% of the run` protects **the program** — that you are measuring what
 *    you started with. In terms of share this is `d >= 100 x cost x share`.
 * 2. `d >= the flat floor` protects **the number** — that its share of the samples still holds. It
 *    binds below about 20% share, which is nearly always.
 *
 * A short operation called rarely passes the first comfortably and is then reported at twice its
 * length: the program is undisturbed and the measurement is wrong. That is why both are needed.
 */
internal fun coarseFloorNanos(share: Double, flatFloorNanos: Double, contextNanos: Double): Double =
    maxOf(flatFloorNanos, contextNanos / COARSE_MAX_RUN_SHARE * share)

/**
 * The failure of work billed to executions that had already finished (docs `warnings.md`,
 * `stale-failure`). Names the coarse type, because its numbers were about to be wrong and the reader
 * has no other way to know which.
 */
internal fun staleContextMessage(worst: String, share: Double): String =
    "${shareText(share)} of the coarse time, mostly ${code(worst)}'s, came from tasks that an execution handed off and that ran on" +
            " after the execution ended${sym.dash}wait for the tasks inside the block that starts them"

/**
 * The failure of a label closed while another was open (`exit-mismatch`). Names both, because the
 * pair is the point: one of them covers work that was never inside it.
 */
internal fun mismatchMessage(closed: String, open: String): String =
    "${code("exit($closed)")} while ${code(open)} was open, so time went to the wrong label${sym.dash}" +
            "an exception skipped an `exit`, or an `exit` names the wrong label; put `exit` in a `finally`"

/**
 * The failure of a label left open (`leak`). Names it, because its share is the one now wrong and the
 * reader has no other way to know which.
 */
internal fun leakMessage(open: String, thread: String = Thread.currentThread().name): String =
    "${code(open)} was still open at `expectBalanced()` on thread ${code(thread)}, so the time after the missed `exit` of " +
            "${code(open)} went to ${code(open)}" +
            "${sym.dash}put `exit` in a `finally`"

/**
 * The failure of the sampler itself (`sampler-error`): it stopped on [e], so the rest of the session
 * went unsampled. Not the user's to fix.
 */
internal fun samplerErrorMessage(e: Throwable): String =
    "the sampler stopped on ${code(e.toString())}, so the rest of the run was not sampled${sym.dash}a TickSnick bug, please report it"

/**
 * The failure of a run saved by a session that went on past [leaks] labels left open, which no session
 * does any more: its figures are false all the same, so it loads as failed. Null for none.
 */
internal fun leftOpenFailure(leaks: Int): String? =
    if (leaks <= 0) null
    else "${Run.plural(leaks.toLong(), "label")} ${if (leaks == 1) "was" else "were"} left open, and the run went on past " +
            "${if (leaks == 1) "it" else "them"}${sym.dash}put `exit` in a `finally`"

/**
 * Whether one operation's long executions are evidence of anything, given what the machine was
 * doing to every operation at the same time — see [Run.machineFloor].
 *
 * All three conditions matter. On a quiet machine the floor falls to 1%, so a single long execution
 * out of two thousand samples clears three times it and means nothing whatever. A ratio against a
 * small number is not evidence. The floor on the share and the minimum count are what keep the
 * answer honest when the machine is behaving perfectly.
 *
 * One function so that every caller that asks this question gets the same answer.
 */
internal fun isSuspect(hits: Long, stuckHits: Long, stuckInstances: Long, machineFloor: Double): Boolean =
    hits > 0 && stuckInstances >= Run.SUSPECT_MIN_INSTANCES &&
            stuckHits.toDouble() / hits > maxOf(machineFloor * Run.SUSPECT_OVER_BASELINE, Run.SUSPECT_MIN_SHARE)

/**
 * A duration in whatever unit keeps it readable. The fine tier spans four orders of magnitude, and
 * a column of nanoseconds makes 1.5 ms and 9 µs look like the same kind of number, which is the
 * one distinction this column exists to draw.
 */
internal fun duration(nanos: Double): String {
    if (nanos.isNaN()) return "-"
    val unit = durationUnitOf(nanos)
    return threeSignificantString(nanos / unit.scale) + unit.text
}

/** A recommended limit as [recommendedLimit] makes it, in its two digits: `3.0us`, `60ns`, `150ns`. */
internal fun limitText(nanos: Double): String {
    if (nanos.isNaN()) return "-"
    val unit = durationUnitOf(nanos)
    val v = nanos / unit.scale
    return fixed(v, if (v >= 10) 0 else 1) + unit.text
}

/** The unit [duration] prints [nanos] in. */
private fun durationUnitOf(nanos: Double): DurationUnit {
    var i = 0
    while (i + 1 < DURATION_UNITS.size && nanos >= DURATION_UNITS[i + 1].from) i++
    // Rounding can reach the next unit - 999.7 ms is three significant digits of 1.00 s, not
    // "1000 ms" - so the choice is made again against the value as it will actually print.
    if (i + 1 < DURATION_UNITS.size && threeSignificant(nanos / DURATION_UNITS[i].scale) >= 1000.0) i++
    return DURATION_UNITS[i]
}

/** `6.34s ±5.0%`: a duration and its interval relative to it, the form of a certain figure. */
internal fun durationCertain(value: Double, half: Double): String = duration(value) + " " + relativeText(half / value * 100)

/** `±5.0%`, `±12%`: an interval relative to its figure. */
internal fun relativeText(pct: Double): String =
    sym.plusMinus + fixed(pct, if (pct < 9.95) 1 else 0) + "%"

/** `4.4..460ms`: an interval of durations, both ends in the larger one's unit, each by [intervalEnd]. */
internal fun durationRange(lo: Double, hi: Double): String {
    val unit = intervalUnitOf(maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi)))
    fun one(v: Double) = (if (v < 0) "-" else "") + intervalEnd(kotlin.math.abs(v) / unit.scale)
    return intervalRange(one(lo), one(hi), unit.text)
}

/** A change in time as [changeRange] writes it, both ends in the larger one's unit: `-3.7..-5.7s`. */
internal fun durationChangeRange(low: Double, high: Double): String {
    val unit = intervalUnitOf(maxOf(kotlin.math.abs(low), kotlin.math.abs(high)))
    return changeRange(low, high, unit.text) { intervalEnd(it / unit.scale) }
}

/** A change in time as [changeValue] writes it, three digits as any one duration: `-1.97s`, `↓-1.97s`. */
internal fun durationChangeValue(v: Double): String = changeValue(v, duration(kotlin.math.abs(v)))

/** The unit an interval's larger end of [nanos] is written in: chosen again after two-digit rounding, so 996ms is `1.0s`. */
private fun intervalUnitOf(nanos: Double): DurationUnit {
    val unit = durationUnitOf(nanos)
    val i = DURATION_UNITS.indexOf(unit)
    return if (i + 1 < DURATION_UNITS.size && intervalEnd(nanos / unit.scale).toDouble() >= 1000.0) DURATION_UNITS[i + 1] else unit
}

/** One rung: the value it takes over at, what to divide by, and what to call it. */
private class DurationUnit(val from: Double, val scale: Double, val name: String) {
    /** The unit as the report prints it, `us` in ASCII and `µs` in Unicode. */
    val text: String get() = if (name == "us") sym.micro else name
}

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
    v >= 100 -> fixed(v, 0)
    v >= 10 -> fixed(v, 1)
    else -> fixed(v, 2)
}

/** Three significant digits and no unit, for a quantity that carries its own - `pp`, a ratio. */
internal fun threeDigits(value: Double): String =
    if (value.isNaN()) UNKNOWN else threeSignificantString(value)

/**
 * A percentage to three significant digits: `100%`, `40.3%`, `5.80%`, `0.512%`.
 *
 * The same rule as [duration] and for the same reason - the fourth digit of a sampled share is
 * noise, and printing it invites a reader to compare two numbers that are not different. Sorting
 * and every arithmetic use the full value; this is only what is shown.
 */
internal fun percent(value: Double): String = when {
    value.isNaN() -> UNKNOWN
    // Reserved for a real 100: the pair `100% / 0.003%` reads as a contradiction, and the halves of
    // `Runnable / Wait` are supposed to add up on the page as well as in the arithmetic.
    value >= 100.0 -> "100%"
    value >= 99.95 -> ">99.9%"
    value >= 9.995 -> fixed(value, 1) + "%"
    value >= 0.9995 -> fixed(value, 2) + "%"
    // Not "0.000%", which reads as measured zero: `<0.001%` ([fixed]). A share this small is one the report folds away.
    value > 0 -> fixed(value, 3) + "%"
    else -> "0%"
}

/**
 * `key            value`, with every value starting at the same column.
 *
 * Keys rather than prose: a reader scans the left column to find a fact, and the key says what kind
 * of thing the line is - a fact, a caveat or a verdict.
 */
internal fun row(key: String, value: String): String = String.format(Locale.ROOT, "%-13s %s", key, value)

/** One operation's line in a [Run]. */
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
    /**
     * How many times the hook ran: [calls] less what batch labels counted beyond one, `op(id, times =
     * n)` being one entry and n calls. -1 for a run saved without it, read as [calls].
     */
    internal val entries: Long = -1,
) {
    /** See [entries]: the hook's runs, or [calls] where a run did not record them. */
    val hookEntries: Long get() = if (entries < 0) calls else entries

    /** The fraction of this operation's occupancy spent inside executions longer than a tick. */
    val stuckShare: Double get() = if (hits == 0L) 0.0 else stuckHits.toDouble() / hits

    /** The fraction of this operation's occupancy where its thread was parked, blocked or waiting. */
    val waitingShare: Double get() = if (hits == 0L) 0.0 else waitingHits.toDouble() / hits

}

/** How many executions a run needs for the percentile [p] to mean something: p50 2, p90 10, p99 100. */
internal fun executionsNeeded(p: Double): Long = if (p >= 1.0) 1L else Math.round(1.0 / (1.0 - p))

/** Why a percentile reads [UNKNOWN], and what gives it: the key under the wall table says it where one does. */
internal fun percentilesNeedText(): String =
    "p50, p90 and p99 need ${executionsNeeded(0.50)}, ${executionsNeeded(0.90)} and ${executionsNeeded(0.99)} executions in a run;" +
            " with fewer they show $UNKNOWN"

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
    if (count < executionsNeeded(p)) return Double.NaN
    val v = spans.percentile(p)
    return if (v.isNaN()) v else v.coerceIn(min.toDouble(), max.toDouble())
}

/**
 * One node of the coarse tree: a coarse operation at one path of coarse operations, with the
 * figures of the executions that ran exactly there.
 *
 * The wall table is drawn from these. A [CoarseStat] is the operation over every path, which is
 * what the floor check, the own table's folding and the parallelism line want; a node is what the
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
     * execution here, and of those, one per execution per tick. Empty in a run saved without
     * them, which draws a leaf's fine rows from [fine] instead. See [Run.ownRowsOf].
     */
    internal val ownFine: LongArray = LongArray(0),
    internal val ownFineCovered: LongArray = LongArray(0),
    /**
     * Fine calls made directly under an execution on this path, by fine id: counted, not sampled.
     * Empty in a run saved without them.
     */
    internal val calls: LongArray = LongArray(0),
    /** Bytes allocated directly under executions at this path, outside its coarse children, on every thread. Counted. */
    val allocated: Long = 0,
    /** Whether an execution here ran where the JVM could not count bytes: [allocated] is then not known. */
    val allocatedUnknown: Boolean = false,
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
 * One coarse operation's line in a [Run].
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
     * It is the number that keeps the identity `threads inside = executions in flight x this`
     * exact, because [inFlight] counts an execution whether or not its
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
     * Executions of this operation running at once. It tracks the load, not the code: twice the
     * clients is twice this number.
     */
    val inFlight: Double
        get() = if (activeTicks == 0L) Double.NaN else instanceTicks.toDouble() / activeTicks
}

/**
 * One measurement of a [Profile]: what one sampling session left behind.
 *
 * Shares are over all samples, so a share is of active time; samples that caught a thread outside
 * any operation are the `(no operation)` row. See [OperationRow.shareOf].
 */
class Run internal constructor(
    internal val operations: List<OperationStat>,
    internal val idleHits: Long,
    internal val ticks: Long,
    internal val samplingSpanNanos: Long,
    internal val durationNanos: Long,
    internal val threads: Int,
    internal val duty: Duty,
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
    internal val pause: Pauses = Pauses.NONE,
    /** The coarse tree: the roots, each with what ran under it. Empty when no coarse label was placed. */
    internal val coarseTree: List<CoarseNode> = emptyList(),
    /** The CPU clock over the session; [ClockTrace.NOT_RECORDED] for a run saved before it was. */
    internal val clock: ClockTrace = ClockTrace.NOT_RECORDED,
    /** How the sampler waited; null for a run saved before it was recorded. See [samplingLine]. */
    internal val wait: WaitStrategy? = null,
    /** The step asked for at `start()`, in nanoseconds; 0 for a run saved before it was recorded. */
    internal val askedStepNanos: Long = 0,
    /** Whether this session counted the bytes allocated under each coarse path. */
    internal val allocations: Boolean = false,
    /** Whether it was asked to on a JVM that cannot say. */
    internal val allocationsMissing: Boolean = false,
    /** What the session read about the JVM. [Setup.NONE] for a run saved without one. */
    internal val setup: Setup = Setup.NONE,
    /** What the user said about the session, in the order they said it. */
    internal val tags: Map<String, String> = emptyMap(),
    /** One body, in ns, over the session; NaN for a run that timed none. See [BodyProbe]. */
    internal val bodyNanos: Double = Double.NaN,
    /** One `System.nanoTime()`, in bodies; NaN for a run that did not time it. See [BodyProbe]. */
    internal val nanoTimeBodies: Double = Double.NaN,
    /** The labels behind [openAtEnd], by name; empty for a run saved without them. */
    internal val openAtEndNames: List<String> = emptyList(),
    /** The coarse labels behind [openContextsAtEnd], each with how many were open; empty for a run saved without them. */
    internal val openContextsAtEndByName: Map<String, Int> = emptyMap(),
) {
    /** Whether this run timed a body of its own; without one its limits are read at [DEFAULT_BODY_NANOS]. */
    internal val bodyTimed: Boolean get() = bodyNanos > 0

    /** One body in ns: this run's, or [DEFAULT_BODY_NANOS]. */
    internal val bodyOrDefaultNanos: Double get() = if (bodyTimed) bodyNanos else DEFAULT_BODY_NANOS

    /** The shortest a fine operation may be here, in ns: [FINE_FLOOR_BODIES] at this run's body, as a [recommendedLimit]. */
    internal val fineFloorNanos: Double get() = recommendedLimit(FINE_FLOOR_BODIES * bodyOrDefaultNanos)

    /** See [coarseHookScale]. */
    internal val hookScale: Double get() = coarseHookScale(nanoTimeBodies)

    /**
     * What the hooks may have cost this run, when it is over [HOOKS_MAX_SHARE] of the time inside
     * labels; null under it, or with no labelled time. Every fine hook entry at [FINE_BUDGET_BODIES]
     * and every coarse execution at its budget with what the session opted into, at this run's body:
     * an upper bound, since a hook beside other work often costs less.
     */
    internal fun hookCost(): HookCost? {
        val labelled = labelledNanos
        if (!(labelled > 0)) return null
        val fine = operations.sumOf { it.hookEntries }
        val executions = coarse.sumOf { it.count }
        val perExecution = coarseContextBodies(operations.size, allocations, hookScale)
        val bodies = fine * FINE_BUDGET_BODIES + executions * perExecution
        val top = (operations.map { it.name to it.hookEntries * FINE_BUDGET_BODIES } + coarse.map { it.name to it.count * perExecution })
            .maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
        // Over the wall time the report prints, pauses included, as the sentence says.
        val h = HookCost(fine, executions, bodies * bodyOrDefaultNanos, labelled, samplingSpanNanos.toDouble(), top)
        return h.takeIf { it.share > HOOKS_MAX_SHARE }
    }

    /** The flat coarse floor here, in ns, as a [recommendedLimit]. See [coarseFlatFloorBodies]. */
    internal val coarseFlatFloorNanos: Double
        get() = recommendedLimit(coarseFlatFloorBodies(operations.size, allocations, hookScale) * bodyOrDefaultNanos)

    /** [setup] and how the session measured: what the runs of a folder should agree on, by name. */
    internal val facts: Map<String, String>
        get() = setup.facts + buildMap {
            if (askedStepNanos > 0) put("Sampling", duration(askedStepNanos.toDouble()))
            wait?.let { put("Wait", it.name) }
            if (allocations) put("Allocated", "counted")
        }

    /** False when a fatal finding stopped the session. */
    internal val ok: Boolean get() = failure == null

    /**
     * How often the sampler sampled, and what that cost the program: a spinning sampler holds a
     * processor to keep the step, a parked one frees it and samples later than asked. Said with
     * the remedy when a spinning sampler holds one of few processors. Null when not recorded.
     */
    internal fun samplingLine(stepNanos: Double = this.stepNanos): String? {
        val w = wait ?: return null
        if (stepNanos.isNaN()) return null
        val n = duty.processors
        val processors = if (n > 0) "one of the $n processors" else "a processor"
        return if (w == WaitStrategy.SPIN) {
            "A sample every ${stepText(stepNanos)}. To hold that pace, the sampler occupies $processors" +
                    if (n in 1 until SPIN_MIN_PROCESSORS) "${sym.dash}start(wait = PARK) frees it" else ""
        } else {
            val asked = if (askedStepNanos > 0 && abs(stepNanos / askedStepNanos - 1) > ASKED_STEP_TOLERANCE)
                " (${stepText(askedStepNanos.toDouble())} asked)" else ""
            "A sample every ${stepText(stepNanos)}$asked. The sampler sleeps between samples, so it takes no processor"
        }
    }

    private fun stepText(nanos: Double): String =
        fixed(nanos / 1e6, if (nanos >= 1_000_000) 1 else 2) + "ms"

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

    /** Thread-time spent inside any label. */
    internal val labelledNanos: Double get() = labelledHits * stepNanos

    /** Of [labelledHits], those that caught a thread that was not runnable. Zero without state. */
    internal val labelledWaitingHits: Long
        get() = operations.sumOf { it.waitingHits } + coarse.sumOf { it.selfHits - it.selfRunningHits }

    /** Of [unlabelledHits], those whose thread was not runnable. See [labelledWaitingHits]. */
    internal val unlabelledWaitingHits: Long
        get() = (idleWaitingHits - coarse.sumOf { it.selfHits - it.selfRunningHits }).coerceAtLeast(0)

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
     * Operations labelled on something too small for the instrument to describe.
     *
     * The bound is inflated by [FLOOR_BIAS_ALLOWANCE] first: the sampler reads short operations
     * 5-9% low below 45 ns, and that bias points the wrong way
     * here, since it would make an innocent operation look smaller than it is.
     */
    internal fun tooSmall(): List<OperationStat> = operations
        // Per entry, not per call: the hook runs once for a batch label's n calls.
        .filter { isTooSmall(it.hits, it.hookEntries, stepNanos, fineFloorNanos) }
        .sortedByDescending { it.calls }

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
     * The duty cycle already knows. [Duty.labelledDuty] is measured from `getThreadCpuTime`,
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
    internal fun factorOf(n: CoarseNode): Double = n.parallel.let { if (it.isFinite() && it >= PARALLEL_SHOWN) it else 1.0 }

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
     * or a run saved without them; and its own thread-time per call, NaN without either.
     */
    internal class OwnRow internal constructor(
        val name: String, val wallNanos: Double, val parallel: Double, val share: Double,
        val calls: Long = -1, val perCallNanos: Double = Double.NaN,
    )

    /**
     * **The fine labels in a coarse row's own time,** outside its coarse children, heaviest first - or
     * null for a run saved without them.
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

    /** This run as a folder of one: a run's report is its profile's. */
    private val table: Profile by lazy { Profile("this run", listOf(java.nio.file.Path.of("run.tsr")), listOf(this)) }

    /**
     * A run saved without own time: a leaf's samples split by label and applied to its wall. See
     * [ownRowsOf] for why that is only a fallback.
     */
    internal fun splitRowsOf(n: CoarseNode): List<OwnRow> {
        if (n.children.isNotEmpty() || n.inclusiveHits <= 0) return emptyList()
        val wall = wallsOf()[n] ?: n.spanSumNanos.toDouble()
        return operations.map { it.name to n.fine[it.id] }
            .plus("unlabelled" to n.fine[NO_OP_INDEX])
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (name, hits) -> (hits.toDouble() / n.inclusiveHits).let { OwnRow(name, if (n.count > 0) wall * it else 0.0, Double.NaN, it) } }
    }

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

    /**
     * The share of the run's thread-time [c]'s executions held, from their count and measured mean:
     * once per execution, however many threads ran inside it, which is what timing them costs in
     * proportion to. Its inclusive share counts each thread inside, k times this for a fan-out of k.
     */
    internal fun spanShareOf(c: CoarseStat): Double = if (observedNanos <= 0.0) 0.0 else c.count * c.meanSpanNanos / observedNanos

    /** What timing [c]'s executions cost, as a share of the run's thread-time. See [spanShareOf]. */
    internal fun timingCostOf(c: CoarseStat): Double = if (observedNanos <= 0.0) 0.0 else c.count * coarseContextNanos / observedNanos

    /**
     * The shortest [c] was allowed to be, from the tier boundary. See [coarseFloorNanos].
     *
     * The share is **reduced by its own sampling noise** before the share condition is applied.
     * `d ≥ 10 µs × share` only binds above about 20%, so an operation near that line could be pushed
     * over it by counting error alone — and this check prints an accusation. Taking the share a
     * standard error low means an operation is told off for holding too much of the run only when it
     * confidently does. The flat 2 µs floor needs no such treatment, which is the point of the
     * whole tier: **the duration here is measured, not inferred.**
     */
    internal fun coarseFloorNanosOf(c: CoarseStat): Double {
        val noise = if (c.inclusiveHits <= 0L) 1.0 else 1.0 / sqrt(c.inclusiveHits.toDouble())
        return coarseFloorNanos((spanShareOf(c) * (1 - noise)).coerceAtLeast(0.0), coarseFlatFloorNanos, coarseContextNanos)
    }

    /** What one coarse execution cost in this run, which rises with the fine labels registered. See [coarseContextNanos]. */
    internal val coarseContextNanos: Double
        get() = coarseContextBodies(operations.size, allocations, hookScale) * bodyOrDefaultNanos

    /**
     * Bytes allocated per execution of [n], its coarse children included, on every thread that ran
     * under it. Per execution, so it can be set against another run of a different size; a fork's
     * bytes count, since each thread's lands on the path it ran on and the rows add up here.
     */
    internal fun allocatedPerExecution(n: CoarseNode): Double =
        if (n.count == 0L) Double.NaN else allocatedTotal(n).toDouble() / n.count

    /**
     * Bytes allocated under [n] in all its executions, its coarse children included, on every thread
     * that ran under it. A parent's is its own and its children's, exactly: nothing is estimated. NaN,
     * read as `?`, where any of them ran on a virtual thread, which the JVM does not count.
     */
    internal fun allocatedTotal(n: CoarseNode): Double =
        n.flatten().let { all -> if (all.any { it.allocatedUnknown }) Double.NaN else all.sumOf { it.allocated }.toDouble() }

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
     * why it is the one thing the sampler stops a session over on its own.
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
     * The four lines under every report: the four things that make a reader draw the wrong conclusion,
     * and nothing else. Every column is explained in docs/output.md, which the last line names.
     */
    internal fun StringBuilder.renderLegend() {
        appendLine()
        appendLine("HOW TO READ THIS")
        appendLine("  thread-time is sampled and summed over all threads, in ACTIVE time; it is NOT CPU time")
        appendLine("  waiting is part of the thread-time next to it, NOT extra to it")
        appendLine("  concurrency is how many threads are INSIDE an op at once, running or parked; it is NOT a speedup")
        appendLine("  a share is where time went, NOT what removing the op would save")
        appendLine("  every column explained: docs/output.md")
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
     * execution against a self thread-time per call. Those live in the wall table above, which is
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
        /** `Long calls`, `Long time` and `Longest`: executions over a tick, the thread-time in them, the longest. Fine rows only. */
        val stuckInstances: Long = 0,
        val stuckHits: Long = 0,
        val longestStuckTicks: Long = 0,
    ) {
        /**
         * Whether `Long calls`, `Long time` and `Longest` print for this row: a fine row whose share in long executions
         * is above what preemption and GC alone could give it. At or under the floor the three
         * columns are a dash, and the line under the table says what the floor was.
         */
        val longShown: Boolean
            get() = stuckShare != null && stuckInstances > 0 && stuckShare > machineFloor

        /** The name as printed: a star marks a row whose full span is in the wall table. */
        val label: String get() = if (coarse) "$name *" else name

        /**
         * `Thread-time%`: a share of *all* samples, so a share of active time, and the `(no operation)`
         * row is the rest. Over all samples rather than labelled ones: adding a label moves samples
         * out of `(no operation)` and nothing else, where a share of labelled samples would move
         * every row.
         */
        fun shareOf(): Double = if (allHits == 0L) 0.0 else hits.toDouble() / allHits

        /** NaN with no sample, which is no share, not 0% waiting. */
        val waitingShare: Double get() = if (hits == 0L) Double.NaN else (hits - runningHits).toDouble() / hits

        fun perCallNanos(): Double = if (calls == 0L) Double.NaN else hits * stepNanos / calls

        /** `Concurrency`: threads inside at once, averaged over the ticks where any were. NaN when never seen. */
        val concurrency: Double get() = if (activeTicks == 0L) Double.NaN else hits.toDouble() / activeTicks

        /**
         * `Workers`: the most threads seen inside at one tick, the series whose mean is [concurrency].
         * Detected rather than measured, so a lower bound: a burst between two ticks is not seen.
         * NaN when never seen.
         */
        val workers: Double get() = if (peakInside <= 0) Double.NaN else peakInside.toDouble()

        /**
         * `Pool`: the distinct threads that ever called the operation, counted by the hook, so exact:
         * a thread that entered once and was never sampled still ran it. NaN when none.
         */
        val pool: Double get() = if (threads <= 0) Double.NaN else threads.toDouble()
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
     * [rows], and time inside no label as a row rather than a `Coverage` line: it is the time the
     * reader has no information about, and it belongs in the ranking with everything else. Its
     * `Waiting` is the first question about it - idle threads, or work nobody labelled -
     * and the row answers it.
     */
    internal fun tableRows(): List<OperationRow> = rows() + if (unlabelledHits > 0) listOf(
        OperationRow(
            NO_OPERATION, coarse = false, hits = unlabelledHits, inclusiveHits = unlabelledHits,
            runningHits = if (stateSampled) unlabelledHits - unlabelledWaitingHits else unlabelledHits,
            activeTicks = 0, peakInside = 0, threads = 0, calls = 0, stuckShare = null,
        )
    ) else emptyList()

    /**
     * Every warning of the report, for its `WARNINGS` section right under the first line (see
     * [warningsSection]).
     *
     * All in one place and before any table, not where each is computed: printed there, a warning
     * about a fine operation would sit under the wall table and read as one about a coarse one.
     */
    internal fun warnings(): List<Warning> =
        // The kinds' order is the section's (see Kind); this is only what fires and with what.
        buildList {
            // A leaked label does not look like an error. It looks like a finding: one operation
            // quietly accumulating everybody else's samples, with a plausible number beside it.
            if (openAtEnd > 0) add(Warning(Kind.LABELS_LEFT_OPEN, words(labelsText(openAtEndNames, openAtEnd))))
            if (allocationsMissing) add(Warning(Kind.ALLOCATIONS_UNAVAILABLE))
            if (allocations && coarseTree.any { root -> root.flatten().any { it.allocatedUnknown } }) add(Warning(Kind.ALLOCATIONS_VIRTUAL_THREADS))
            if (untrackedSlots > 0) add(Warning(Kind.THREADS_UNSAMPLED, figure(untrackedSlots.toDouble(), ::countText)))
            // No warning for threads that exited without Profiler.release(): the sampler skips a
            // thread that has exited and the slot is reclaimed within a second, so nothing above is
            // distorted.
            if (openContextsAtEnd > 0) {
                if (openContextsAtEndByName.isEmpty())
                    add(Warning(Kind.COARSE_OPEN_AT_END, words(""), figure(openContextsAtEnd.toDouble(), ::countText)))
                else for ((name, n) in openContextsAtEndByName)
                    add(Warning(Kind.COARSE_OPEN_AT_END, words(code(name)), figure(n.toDouble(), ::countText)))
            }
            // Attribution invented, not lost.
            if (staleContextHits > 0) add(
                Warning(
                    Kind.STALE_TIME, figure(staleContextShare, ::shareText),
                    words(coarse.maxByOrNull { it.staleHits }?.takeIf { it.staleHits > 0 }?.let { code(it.name) } ?: ""),
                )
            )
            addAll(pause.warnings(wait, samplingSpanNanos))
            // Thread state cannot see a native wait. Run-wide from the duty cycle, or a coarse row
            // whose Runnable the CPU time contradicts: one warning, and the worst row, with the share
            // of its own Runnable time, in place of the run-wide one.
            val contradicted = coarse.filter { it.count > 0 && workingIsContradicted(it) }
                .maxByOrNull { it.working / workingCeilingOf(it) }
            val fromDuty = duty.warnings(activeNanos, reclaimedSlots)
            addAll(if (contradicted == null) fromDuty else fromDuty.filter { it.kind != Kind.RUNNABLE_OFF_CPU })
            if (contradicted != null) add(
                Warning(
                    Kind.RUNNABLE_OFF_CPU, figure(1 - workingCeilingOf(contradicted) / contradicted.working, ::shareText),
                    words(code(contradicted.name)),
                )
            )
            addAll(clock.warnings())
            hookCost()?.let { h ->
                add(Warning(Kind.HOOKS_COST, words(h.top?.let(::code) ?: ""), figure(h.share, ::shareText), figure(h.wallShare, ::shareText)))
            }
            // The one signal for work escaping its context, which neither the floor check nor the
            // balance check can see.
            // Only where a coarse label was used: a run with fine labels alone is all under no coarse row.
            if (coarse.any { it.count > 0 || it.inclusiveHits > 0 } && labelledOutsideCoarseShare >= OUTSIDE_COARSE_MIN_SHARE) add(
                Warning(Kind.OUTSIDE_COARSE, figure(labelledOutsideCoarseShare, ::shareText), note(codeList(outsideCoarseSuspects().take(4).map { it.name })))
            )
            // Every public way of opening an execution gives it a path, so a row missing from the tree
            // is a defect here, not a misuse.
            val inTree = coarseTree.flatMap { it.flatten() }.map { it.type }.toSet()
            coarse.filter { (it.count > 0 || it.inclusiveHits > 0) && it.type !in inTree }.takeIf { it.isNotEmpty() }?.let { missing ->
                add(Warning(Kind.COARSE_NOT_IN_TREE, words(codeList(missing.map { it.name }))))
            }
            for (op in suspect()) add(
                Warning(
                    Kind.LONG_CALLS, words(code(op.name)), figure(op.stuckShare, ::shareText), figure(stepNanos, ::duration),
                    words(code("registerCoarse(\"${op.name}\")")),
                )
            )
            // A label below the floor cannot move a ranking, so it is a warning and never a failure:
            // every offender is named; the reader decides.
            for (op in tooSmall()) add(
                Warning(Kind.FINE_UNDER_FLOOR, words(code(op.name)), figure(fineFloorNanos, ::limitText))
            )
            // The same boundary from the other side, exact: the duration it compares is measured.
            for (c in coarseTooSmall()) {
                // Under the flat floor the operation is too short to time, whatever its share; above it, it is
                // under the share floor only, and what the reader needs is what timing it costs.
                add(
                    if (c.meanSpanNanos >= coarseFlatFloorNanos) Warning(
                        Kind.COARSE_OVER_SHARE, words(code(c.name)), figure(timingCostOf(c), ::shareText),
                        words(code("registerFine(\"${c.name}\")")),
                    ) else Warning(
                        Kind.COARSE_UNDER_FLOOR, words(code(c.name)), figure(c.meanSpanNanos, ::duration), figure(coarseFlatFloorNanos, ::limitText),
                        note(coarseFloorRaised(operations.size, allocations, bodyOrDefaultNanos, hookScale)),
                        words(code("registerFine(\"${c.name}\")")),
                    )
                )
            }
        }

    /**
     * This run as a report of its own. See [Profile.report].
     */
    internal fun render(warnings: Warnings = table.warnings()): String = table.report(warnings)

    /**
     * Writes this run as a new file in [folder], named by the time it is saved -
     * `<folder>/<time>.tsr`, see [RunStore.saveNew] - and returns the file. A folder holds the runs
     * of one profile, and is what [TickSnick.load] reads: the only way to look at a run is to save
     * it and load its folder.
     */
    fun save(folder: java.nio.file.Path): java.nio.file.Path =
        RunStore.saveNew(this, folder).also { Logo.once() }

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

        /** The fine floor at [DEFAULT_BODY_NANOS]. See [Run.fineFloorNanos]. */
        internal const val FLOOR_NANOS = FINE_FLOOR_BODIES * DEFAULT_BODY_NANOS

        /** The own table's row for samples inside no label. */
        internal const val NO_OPERATION = "(no op)"

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

        /** Reads a run written by [save]. */
        internal fun load(path: java.nio.file.Path): Run = RunStore.load(path)

        /**
         * `1 thread` rather than `1 threads`.
         *
         * A report that cannot count is a report a reader trusts slightly less, and the places this
         * matters are exactly the small runs where every other number is already marginal.
         */
        internal fun plural(n: Long, noun: String): String =
            if (n == 1L) "1 $noun" else String.format(Locale.ROOT, "%,d ${noun}s", n)

        /** The short-operation bias, so the check cannot accuse an operation of the sampler's own error. */
        internal const val FLOOR_BIAS_ALLOWANCE = 1.2

        /** Hits below which an operation's rate of long executions is too noisy to enter the median. */
        internal const val MEDIAN_MIN_HITS = 100L
    }
}

