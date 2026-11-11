package com.ticksnick

import java.util.Locale

/**
 * One gap of a warning, filled by a run. Its type says how the gaps of several runs merge into one
 * warning: see [mergeWarnings].
 */
internal sealed class Gap {
    /** A figure, `1.04pp`. Over runs, the range `low..high`. NaN is a figure the run does not have. */
    class Figure(val value: Double, val format: (Double) -> String) : Gap()

    /** A range already within one run, `140%..230%`. Over runs, the outer range, lowest low to highest high. */
    class Range(val low: Double, val high: Double, val format: (Double) -> String) : Gap()

    /** Words: a name, a reason. Part of what makes two warnings the same; empty is a part not printed. */
    class Words(val text: String) : Gap()
}

/** A figure gap. */
internal fun figure(value: Double, format: (Double) -> String): Gap = Gap.Figure(value, format)

/** A words gap. */
internal fun words(text: String): Gap = Gap.Words(text)

/** A count, `9,835,008`. */
internal fun countText(n: Double): String = String.format(Locale.ROOT, "%,.0f", n)

/** A share in percent, as [percent] writes it, from a fraction. */
internal fun shareText(fraction: Double): String = percent(fraction * 100)

/** Code in a message: a label, a call, an API name, in backticks so it reads apart from the sentence. */
internal fun code(text: String): String = "`$text`"

/** Labels by name, each in backticks: `` `a`, `b` ``. */
internal fun codeList(names: List<String>): String = names.joinToString(", ") { code(it) }

/**
 * **Every kind of warning**, each one wording, in the order the `WARNINGS` section prints them, worst
 * first. `docs/warnings.md` of the project is their authority. [text] puts the gaps, already written,
 * into the sentence; an empty gap is a part not printed. A second line is under the first.
 */
internal enum class Kind(val text: (List<String>) -> String) {
    // Numbers missing or false.
    LABELS_LEFT_OPEN({ g ->
        "${g[0]}: entered and never exited by `stop()`${sym.dash}an exception skipped the `exit`, so later time went to it; put `exit` in a `finally`"
    }),
    ALLOCATIONS_UNAVAILABLE({ _ -> "Allocated: not shown${sym.dash}this JVM cannot count allocated bytes per thread; HotSpot can" }),
    THREADS_UNSAMPLED({ g ->
        "${g[0]} threads were not sampled, past the ${countText(MAX_SLOTS.toDouble())} TickSnick follows at once${sym.dash}" +
                "their time is missing from every figure; `Profiler.release()` frees the slot of a thread done labelling"
    }),
    COARSE_OPEN_AT_END({ g ->
        if (g[0].isEmpty()) "${g[1]} coarse executions still open at `stop()`, left out of their rows${sym.dash}call `stop()` after the work is done"
        else "${g[0]}: ${g[1]} executions still open at `stop()`, left out of its row${sym.dash}call `stop()` after the work is done"
    }),
    STALE_TIME({ g ->
        if (g[1].isEmpty()) "${g[0]} of the coarse time came from tasks handed off that ran on after their execution ended, and is left out of its row${sym.dash}wait for the tasks inside its block"
        else "${g[0]} of the coarse time, mostly ${g[1]}'s, came from tasks it handed off that ran on after it ended, and is left out of its row${sym.dash}wait for the tasks inside its block"
    }),

    // Limits on the whole run.
    PAUSES_NOT_MEASURED({ g ->
        "Paused: not measured with `wait = ${g[0]}`, so GC pauses are inside the times below${sym.dash}`wait = SPIN` measures them but keeps a core busy"
    }),
    PAUSES_IN_TIMES({ g -> "Paused: ${g[0]} of pauses, ${g[1]} of the run, is counted as work in the times below" }),
    RUNNABLE_UNCHECKED({ g -> "Runnable: not checked against CPU time (${g[0]})${sym.dash}it may include threads waiting in native calls" }),
    RUNNABLE_OFF_CPU({ g ->
        "Runnable: ${g[0]} of it inside labels was not on a CPU${if (g[1].isEmpty()) "" else ", mostly under ${g[1]}"}${sym.dash}" +
                "a thread waiting in a native call, such as a socket read, shows as Runnable; label the call to give the wait its own row"
    }),
    // The cause line: both causes with their shares, or the one alone, a 0% cause having nothing to do.
    THREAD_TIME_BOUND({ g ->
        val none = shareText(0.0)
        val core = "waited for a free core${sym.dash}run fewer threads"
        val io = "waited in I/O, native calls or other programs${sym.dash}label the wait"
        "Thread-time%: may be off by up to ${g[0]}${sym.dash}some Runnable time inside labels may have been waiting" + when {
            g[1].isEmpty() -> ""
            g[2] == none -> "\nAll of it $core"
            g[1] == none -> "\nAll of it $io"
            else -> "\n${g[1]} of it $core; ${g[2]} $io"
        }
    }),
    CLOCK_NOT_MEASURED({ g -> "CPU clock: not measured (${g[0]})${sym.dash}times may drift unseen; shares hold" }),
    CLOCK_MOVED({ g -> "CPU clock${g[0]}: ran at ${g[1]} ${g[2]}${sym.dash}times drift with it, shares hold; turbo off holds it still" }),
    HOOKS_COST({ g ->
        "The labels${if (g[0].isEmpty()) "" else ", mostly ${g[0]},"} may add up to ${g[1]} to the work inside them and ${g[2]} to the wall time${sym.dash}" +
                "fewer, larger labels cost less"
    }),

    // Labels in the wrong place or tier.
    OUTSIDE_COARSE({ g ->
        "${g[0]} of the fine labels' time${if (g[1].isEmpty()) "" else ", mostly ${g[1]},"} is under no coarse row${sym.dash}" +
                "expected where no coarse label encloses it; for other threads, wrap their tasks with `propagating()`"
    }),
    COARSE_NOT_IN_TREE({ g -> "${g[0]} ${if (", " in g[0]) "have" else "has"} no row in the coarse table${sym.dash}a TickSnick bug, please report it" }),
    LONG_CALLS({ g ->
        "${g[0]}: ${g[1]} of its time is in calls over ${g[2]}, which its per-call time hides${sym.dash}register it coarse, ${g[3]}"
    }),
    FINE_UNDER_FLOOR({ g ->
        "${g[0]}: its calls are under the recommended ${g[1]}, so the label is a visible part of their time${sym.dash}" +
                "label the loop around it, ${g[2]}, or drop it"
    }),
    COARSE_UNDER_FLOOR({ g ->
        "${g[0]}: its executions, ${g[1]}, are under the recommended ${g[2]}${if (g[3].isEmpty()) "" else " (${g[3]})"}, " +
                "so timing is a visible part of them${sym.dash}register it fine, ${g[4]}, or label a batch of them"
    }),
    COARSE_OVER_SHARE({ g ->
        "${g[0]}: timing its executions costs ${g[1]} of the run${sym.dash}they are too short and too many to time each one; " +
                "register it fine, ${g[2]}, or put the coarse label around a batch of them"
    }),

    // The runs of a profile together.
    ONE_RUN({ g -> "Runs: 1 in '${g[0]}', no interval${sym.dash}shares hold, durations need a second run" }),
    RUNS_ONE_JVM({ g -> "Runs${g[0]}: ${g[1]} of ${g[2]} shared a JVM${sym.dash}the ${sym.plusMinus} is too narrow; give each run its own JVM" }),
    RUNS_MIXED({ g ->
        "Runs${g[0]}: they differ in ${g[1]}, so each mean mixes them${sym.dash}split the folder by them; the run list shows which run has what"
    }),

    // The two sides of a diff.
    CLOCK_BETWEEN_SIDES({ g ->
        "CPU clock: ${g[0]} ${sym.arrow} ${g[1]} between the sides${sym.dash}a change in time of up to ${g[2]} may be the machine, not the code; shares hold"
    }),
    ROOT_COUNT_SIDES({ g -> "${g[0]}: ran ${g[1]} times before, ${g[2]} after${sym.dash}compare its time per execution, not in total" }),
    ALLOCATIONS_ONE_SIDE({ g ->
        "Allocated: counted on one side only${sym.dash}it adds about ${g[0]} to each coarse execution there; count it on both sides or neither"
    }),
}

/** One warning: its [kind] and the gaps a run filled, in the order its sentence takes them. */
internal class Warning(val kind: Kind, val gaps: List<Gap>) {
    constructor(kind: Kind, vararg gaps: Gap) : this(kind, gaps.toList())

    /** This warning alone, as printed. */
    fun text(): String = mergeOne(listOf(this))
}

/**
 * **The one merge of warnings, for every kind.** Each run's own warnings in [perRun]: the same kind
 * with the same words is one warning, its figures as ranges over the runs, its ranges as the outer
 * range, and `(k of n runs)` on it when there is more than one run; [where] names the profile in a
 * diff. In the order of [Kind], and within a kind in the order they came. Paired with the kind, so the
 * caller can order them among others.
 */
internal fun mergeWarnings(perRun: List<List<Warning>>, where: String = ""): List<Pair<Kind, String>> {
    val groups = LinkedHashMap<List<Any>, MutableList<Warning>>()
    val runsOf = HashMap<List<Any>, MutableSet<Int>>()
    perRun.forEachIndexed { run, ws ->
        for (w in ws) {
            val key = keyOf(w)
            groups.getOrPut(key) { ArrayList() }.add(w)
            runsOf.getOrPut(key) { HashSet() }.add(run)
        }
    }
    return groups.entries.sortedBy { it.value[0].kind.ordinal }.map { (key, ws) ->
        val count = runsOf[key]!!.size
        val place = when {
            perRun.size > 1 -> " ($count of ${perRun.size} runs$where)"
            where.isEmpty() -> ""
            else -> " (${where.trim()})"
        }
        val lines = mergeOne(ws).lines()
        ws[0].kind to (listOf(lines.first() + place) + lines.drop(1)).joinToString("\n")
    }
}

/** What makes two warnings the same one: the kind, its words, and which figures it has. */
private fun keyOf(w: Warning): List<Any> = listOf<Any>(w.kind) + w.gaps.map {
    when (it) {
        is Gap.Words -> "w:" + it.text
        is Gap.Figure -> if (it.value.isNaN()) "absent" else "figure"
        is Gap.Range -> "range"
    }
}

/** Warnings of one kind and the same words, as one sentence: see [mergeWarnings]. */
private fun mergeOne(ws: List<Warning>): String {
    val first = ws[0]
    val texts = first.gaps.indices.map { i ->
        when (val gap = first.gaps[i]) {
            is Gap.Words -> gap.text
            is Gap.Figure -> {
                val values = ws.map { (it.gaps[i] as Gap.Figure).value }.filter { !it.isNaN() }
                if (values.isEmpty()) "" else rangeText(values, gap.format)
            }
            is Gap.Range -> {
                val ranges = ws.map { it.gaps[i] as Gap.Range }
                rangeText(listOf(ranges.minOf { it.low }, ranges.maxOf { it.high }), gap.format)
            }
        }
    }
    return first.kind.text(texts)
}

/**
 * A recommended limit, a floor, rounded up to two digits, the second 0 or 5: 2.61us is 3.0us, 56ns
 * 60ns, 123ns 150ns. It is a recommendation, not a measurement, so three digits would claim a
 * precision it does not have; up, never to the nearest, so that the check, which uses it too, and
 * the warning agree: an execution of 2.55us is never "under the recommended 2.5us".
 */
internal fun recommendedLimit(nanos: Double): Double {
    if (nanos.isNaN() || nanos <= 0.0) return nanos
    val step = Math.pow(10.0, Math.floor(Math.log10(nanos)) - 1)
    return Math.ceil(nanos / step / 5 - 1e-9) * 5 * step
}
