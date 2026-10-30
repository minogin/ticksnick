package com.ticksnick

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A second in which the JVM used at least this share of its processors counts as full: a thread
 * that read `Runnable` and was not on a CPU then was most likely waiting for a core. Under it, a
 * core was spare, and the thread was off the CPU for some other reason.
 */
internal const val FULL_PROCESSORS = 0.9

/**
 * The share of the waiting `Runnable` hides that has to be on one side - full seconds or spare
 * ones - before the Careful line names that side alone as the cause.
 */
internal const val ONE_CAUSE = 0.8

/**
 * How often the profiled threads' CPU clocks are read for the `Cores` line: a thread that starts or
 * exits between two windows loses at most this much of its CPU to "GC" instead of a whole window.
 * A read per thread, about a microsecond, ten times a second.
 */
internal const val CORES_REFRESH_NANOS = 100_000_000L

/**
 * One thread's labelled samples in one window that read `Runnable` while its CPU clock says it was
 * not running: the run's formula in [labelledDuty], `min(l - wl, f - w)`, in samples. [hits] the
 * window's samples of the thread, [labelled] those inside a label, [waiting] those not runnable,
 * [labelledWaiting] both; [cpuNanos] and [wallNanos] the thread's CPU and wall time in the window.
 */
internal fun hiddenSamples(hits: Long, labelled: Long, waiting: Long, labelledWaiting: Long, cpuNanos: Long, wallNanos: Long): Double {
    if (hits <= 0 || wallNanos <= 0) return 0.0
    val f = min(1.0, max(0.0, 1.0 - cpuNanos.toDouble() / wallNanos))
    return min(max(0.0, (labelled - labelledWaiting).toDouble()), max(0.0, f * hits - waiting))
}

/** Whether a window's JVM CPU filled its processors: at least [FULL_PROCESSORS] of [processors] x [windowNanos]. */
internal fun processorsFull(processCpuNanos: Long, processors: Int, windowNanos: Long): Boolean =
    processCpuNanos >= FULL_PROCESSORS * processors * windowNanos

/**
 * How much of the JVM CPU seen during late ticks to keep as CPU during pauses: the share of that
 * lateness the JVM confirmed as its own collections. A tick is late also when the scheduler took
 * the sampler's core, and the workers ran on through that - their CPU is not the collector's.
 */
internal fun pausedShareOf(lateNanos: Long, confirmedNanos: Long): Double =
    if (lateNanos <= 0 || confirmedNanos <= 0) 0.0 else min(1.0, confirmedNanos.toDouble() / lateNanos)

/**
 * Per-thread CPU time, and what it is worth on this platform.
 *
 * Everything here is checked rather than assumed. `getThreadCpuTime` is optional in the JVM spec,
 * can be supported but switched off, and — on Windows, where it comes from `GetThreadTimes` — is
 * updated on scheduler ticks of the order of 15 ms. A number read at a resolution coarser than the
 * window it is measured over is worse than no number at all, so the resolution is *measured* at
 * startup and the duty cycle reports itself unavailable where the clock cannot support the window.
 */
internal object ThreadCpuClock {
    private val bean = ManagementFactory.getThreadMXBean()

    val supported: Boolean = bean.isThreadCpuTimeSupported

    /** Supported is not enough: it can be switched off, and it is then a -1 from every call. */
    val enabled: Boolean = supported && run {
        if (!bean.isThreadCpuTimeEnabled) {
            try {
                bean.isThreadCpuTimeEnabled = true
            } catch (_: Exception) {
                // Left to the check below. An unsupported operation here is simply "no".
            }
        }
        bean.isThreadCpuTimeEnabled
    }

    /**
     * CPU nanoseconds this thread has consumed, or -1 if the thread is gone or the clock is off.
     * Thread ids rather than references throughout, so nothing here can pin a dead thread alive.
     */
    fun cpuNanos(threadId: Long): Long = if (enabled) bean.getThreadCpuTime(threadId) else -1L

    /** The calling thread's CPU nanoseconds, or -1: the sampler reading itself. */
    fun currentCpuNanos(): Long = if (enabled) bean.currentThreadCpuTime else -1L

    /**
     * The smallest step this clock will actually take, measured.
     *
     * Spins on the calling thread — so CPU time advances at wall rate — and watches for the
     * counter to change. What comes back is the granularity we can *use*: on a platform where the
     * counter is fine-grained it is bounded below by the cost of the call itself, which is the
     * honest answer, since a resolution finer than one read is not a resolution anyone can exploit.
     *
     * Returns -1 if the clock did not move twice, which means we know nothing about it and have to
     * say so rather than guess.
     */
    fun probeResolutionNanos(): Long {
        if (!enabled) return -1L
        val deadline = System.nanoTime() + PROBE_LIMIT_NANOS
        var last = bean.currentThreadCpuTime
        var smallest = Long.MAX_VALUE
        var changes = 0
        while (changes < PROBE_CHANGES && System.nanoTime() < deadline) {
            val c = bean.currentThreadCpuTime
            if (c > last) {
                smallest = min(smallest, c - last)
                last = c
                changes++
            }
        }
        // One change tells us only that the clock moves at all: the first interval is truncated by
        // wherever the probe happened to start inside it, so it is not a granularity.
        return if (changes >= 2) smallest else -1L
    }

    private const val PROBE_LIMIT_NANOS = 300_000_000L
    private const val PROBE_CHANGES = 8
}

/**
 * The whole JVM's CPU time: every thread, the GC's and the JIT's included, which no thread clock
 * reaches. Minus the profiled threads and the sampler, it is what else ran on the cores. -1 where
 * the JVM does not offer it.
 */
internal object ProcessCpuClock {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    fun cpuNanos(): Long = os?.processCpuTime ?: -1L
}

/**
 * The bytes the calling thread has allocated in its life, a running total, where the JVM offers it.
 * `com.sun.management` is not part of the Java platform, so its absence is an answer and not a
 * failure, and supported is not enough: as with [ThreadCpuClock], it can be switched off.
 */
internal object AllocationClock {
    private val bean: com.sun.management.ThreadMXBean? = try {
        ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    } catch (_: LinkageError) {
        null
    }

    val available: Boolean = bean != null && bean.isThreadAllocatedMemorySupported && enable(bean)

    private fun enable(b: com.sun.management.ThreadMXBean): Boolean {
        if (!b.isThreadAllocatedMemoryEnabled) {
            try {
                b.isThreadAllocatedMemoryEnabled = true
            } catch (_: Exception) {
                // Left to the check below. An unsupported operation here is simply "no".
            }
        }
        return b.isThreadAllocatedMemoryEnabled
    }

    /** Call only where [available]. */
    fun currentBytes(): Long = bean!!.currentThreadAllocatedBytes
}

/**
 * Time the JIT spent compiling, summed over its threads, in nanoseconds; -1 where the JVM does not
 * keep it. Elapsed rather than CPU - the platform offers nothing closer - so it is high by however
 * long a compiler thread waited for a core while compiling.
 */
internal object JitClock {
    private val bean = ManagementFactory.getCompilationMXBean()

    fun nanos(): Long =
        if (bean != null && bean.isCompilationTimeMonitoringSupported) bean.totalCompilationTime * 1_000_000 else -1L
}

/**
 * How much of the threads' occupancy was CPU — the bound on every share at once.
 *
 * The sampler counts a slot as inside its operation whether the thread is running, blocked, parked
 * or descheduled. That is occupancy, not CPU. This measures the gap between the two in aggregate:
 * summed CPU delta over summed wall delta across the registered threads. It does not say *which*
 * operation was stalling — it says how much stalling there was in total, and that single number
 * bounds the error on every share, whatever caused it.
 *
 * Sampled at a low rate, never per tick. `getThreadCpuTime` is a native call per thread and its
 * underlying counter moves in scheduler ticks; asking it every millisecond would cost real time
 * and return quantisation noise.
 *
 * **The quantisation telescopes.** Each window's delta is the difference of two readings of one
 * cumulative counter, so a rounding error at a window boundary enters one window positive and the
 * next negative. Across the run they cancel, and the aggregate is accurate to about one granule
 * per thread over the whole span however ragged the per-window figures look.
 */
internal class DutyCycle(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    /** Previous reading per thread id. Rebuilt each window, so dead threads drop out by themselves. */
    private var prevCpu = HashMap<Long, Long>()
    private var prevWall = 0L
    private var nextAt = Long.MIN_VALUE

    /**
     * The same two sums as [cpuNanos] and [wallNanos], kept per thread rather than only added up.
     *
     * Adding up first would make the bound vacuous. It is applied to shares that are over
     * *labelled* samples, so computed over *every registered thread* it lets a pool thread that sat
     * outside every label all run push the bound up without appearing in a single share it bounds:
     * a low duty and an unbounded error, while the threads the numbers came from were almost
     * always on a CPU.
     *
     * Indexed by slot index, not by thread id, because that is what the sampler's own per-slot
     * counters use and the two have to be paired thread by thread. A thread that arrived past the
     * [MAX_SLOTS] ceiling has no index and is absent from both sides, which is consistent if not
     * complete — the report says how many those were. A slot handed on to a new thread after the
     * old one died accumulates both, which is wrong in the fourth decimal and not worth a
     * generation counter.
     */
    private val cpuByIndex = LongArray(MAX_SLOTS)
    private val wallByIndex = LongArray(MAX_SLOTS)

    private var windows = 0
    private var cpuNanos = 0L
    private var wallNanos = 0L

    /**
     * Confirmed pause inside the windows counted so far, summed per thread like [wallNanos], so that
     * `wallNanos - pausedNanos` is the run time of exactly the threads [cpuNanos] was read from.
     */
    private var pausedNanos = 0L

    /**
     * Over the same windows: the whole JVM's CPU, the sampler's own, and the windows' wall time
     * counted once rather than per thread - so each divided by [spanNanos] is a number of cores.
     * [processCpuNanos] goes to -1 for good the first time either clock cannot be read.
     */
    private var processCpuNanos = 0L
    private var samplerCpuNanos = 0L
    private var jitNanos = 0L
    private var spanNanos = 0L
    private var prevProcess = -1L
    private var prevSampler = -1L
    private var prevJit = -1L

    /**
     * The JVM's CPU while every thread was stopped, read at every tick by [everyTick]: what the
     * collector itself used in its pauses, so the report can say how much of "GC and the rest" that is.
     */
    private var pausedProcessCpuNanos = 0L
    private var prevTickProcess = -1L
    private var prevTickNanos = 0L

    /**
     * Since the last window: the JVM's CPU during late ticks and the lateness itself, kept as pause
     * CPU only in the share the window's confirmed pause covers. See [pausedShareOf].
     */
    private var pendingPausedCpu = 0.0
    private var pendingLate = 0L

    /**
     * The profiled threads' CPU for the `Cores` line, read every [CORES_REFRESH_NANOS] rather than
     * once a window: the JVM's clock counts a thread's whole life, and a thread seen only from its
     * first window boundary to its last put up to two windows of its CPU under "GC" - a third of a
     * worker's CPU on a pool made per three-second batch. Kept apart from [cpuNanos], which feeds the
     * bound and must cover exactly the windows its wall time does.
     */
    private var coresLatest = HashMap<Long, Long>()
    private var pendingProfiledCpu = 0L
    private var profiledCpuNanos = 0L
    private var nextRefresh = 0L

    /**
     * The sampler's own per-slot counts, and their values at the last window: so each window knows
     * its own samples, labelled, waiting, and labelled waiting, per thread. See [watch].
     */
    private var hits: LongArray? = null
    private var labelled: LongArray? = null
    private var waiting: LongArray? = null
    private var labelledWaiting: LongArray? = null
    private val prevHits = LongArray(MAX_SLOTS)
    private val prevLabelled = LongArray(MAX_SLOTS)
    private val prevWaiting = LongArray(MAX_SLOTS)
    private val prevLabelledWaiting = LongArray(MAX_SLOTS)

    /**
     * Labelled samples that read `Runnable` while the CPU clock says the thread was not running,
     * split by whether the JVM's processors were full that second: the waiting for a core, and
     * the rest - blocking I/O, native calls, other programs. In samples, worst case per thread and
     * window, as [labelledDuty] takes it for the run.
     */
    private var hiddenWhenFull = 0.0
    private var hiddenWhenSpare = 0.0
    private val processors = Runtime.getRuntime().availableProcessors()

    /**
     * Hands over the sampler's per-slot counters, so each window can take its own share of them.
     * Only with the thread state read: without it, waiting and running cannot be told apart.
     */
    fun watch(hits: LongArray, labelled: LongArray, waiting: LongArray, labelledWaiting: LongArray) {
        this.hits = hits
        this.labelled = labelled
        this.waiting = waiting
        this.labelledWaiting = labelledWaiting
    }

    /** Confirmed pause since the last window boundary, waiting for a window to be taken out of. */
    private var pendingPause = 0L
    private var minDuty = Double.NaN
    private var maxDuty = Double.NaN
    private var maxThreads = 0
    private var maxSampleNanos = 0L
    private var anomalies = 0

    /** Why there will be no number, or null if there will be one. */
    private val reason: String? = when {
        !ThreadCpuClock.supported -> "this JVM does not support per-thread CPU time"
        !ThreadCpuClock.enabled -> "per-thread CPU time is supported but disabled and would not enable"
        resolution < 0 -> "the per-thread CPU clock did not move during the probe"
        resolution * 10 > windowNanos -> String.format(
            Locale.ROOT, "clock resolution %.3f ms is too coarse for a %.3f s window",
            resolution / 1e6, windowNanos / 1e9
        )

        else -> null
    }

    private val available: Boolean get() = reason == null

    /** The shortest window worth taking: below this the clock's own steps dominate the answer. */
    private val floorNanos = max(windowNanos / 10, max(resolution, 0) * 10)

    /**
     * Called every tick; takes a sample only once the window has elapsed.
     *
     * Until the first sample finds a thread to measure, the window is not started: an empty walk
     * would otherwise consume a window and push the first countable boundary a whole window later,
     * out past the end of a short run.
     */
    fun tick(now: Long, confirmedPause: Long = 0) {
        if (!due(now)) return
        sample(now, confirmedPause)
        nextAt = if (prevWall == 0L) now else now + windowNanos
    }

    /**
     * Whether [tick] would take a sample now. Asked first by the sampler, so that the JVM's
     * collection count is read once a window rather than once a tick.
     */
    fun due(now: Long): Boolean = available && now >= nextAt

    /**
     * This window's labelled samples of slot [idx] that read `Runnable` while its CPU clock says it
     * was not running: the run's formula in [labelledDuty], `min(l - wl, f - w)`, over one window.
     * 0 without the counters.
     */
    private fun hiddenIn(idx: Int, cpu: Long, wall: Long): Double {
        val h = hits ?: return 0.0
        return hiddenSamples(
            h[idx] - prevHits[idx], labelled!![idx] - prevLabelled[idx], waiting!![idx] - prevWaiting[idx],
            labelledWaiting!![idx] - prevLabelledWaiting[idx], cpu, wall,
        )
    }

    /**
     * Called on every tick with the lateness [PauseTracker.tick] counted: the JVM's CPU between this
     * tick and the last, when this one ran late, is CPU spent while the JVM was stopped. Only the
     * late part of the gap is taken, so the step of ordinary running before the pause is left out.
     * One read of the process clock, about 240 ns on Windows: 0.02% of a 1 ms step.
     */
    fun everyTick(now: Long, lateNanos: Long) {
        if (!available) return
        val p = ProcessCpuClock.cpuNanos()
        if (lateNanos > 0 && p >= 0 && prevTickProcess >= 0 && prevWall != 0L && now > prevTickNanos) {
            pendingPausedCpu += (p - prevTickProcess) * (lateNanos.toDouble() / (now - prevTickNanos))
            pendingLate += lateNanos
        }
        prevTickProcess = p
        prevTickNanos = now
        if (now >= nextRefresh) {
            refreshThreads()
            nextRefresh = now + CORES_REFRESH_NANOS
        }
    }

    /** Reads every profiled thread's CPU clock and adds what each used since its last reading. See [coresLatest]. */
    private fun refreshThreads() {
        val next = HashMap<Long, Long>(coresLatest.size * 2 + 8)
        Profiler.forEachSlot { s ->
            val c = ThreadCpuClock.cpuNanos(s.threadId)
            if (c < 0) return@forEachSlot
            val last = coresLatest[s.threadId]
            if (last != null && c >= last) pendingProfiledCpu += c - last
            next[s.threadId] = c
        }
        coresLatest = next
    }

    /**
     * A final sample, so the last partial window is not silently dropped. Refused when too little
     * time has passed to be worth reading, which is the rule the windows themselves follow.
     */
    fun finish(now: Long, confirmedPause: Long = 0) {
        if (!available || prevWall == 0L || now - prevWall < floorNanos) return
        sample(now, confirmedPause)
    }

    /**
     * @param confirmedPause time every thread was stopped since the last call, by
     *   [PauseTracker.settle] - the part of the wall clock in which no sample was taken.
     */
    private fun sample(now: Long, confirmedPause: Long) {
        // The shares this bounds are over samples, and no sample is taken while the JVM is stopped.
        // So the wall time a thread's CPU is divided by must leave the pauses out too, or the bound
        // charges the shares for time that is not in them, ten points on threads that were almost
        // always on a CPU. Only the per-thread figures that feed the bound are corrected; the
        // aggregate keeps the whole wall clock, because the machine-wide readings taken from it are
        // about everything that happened to a thread, pauses included.
        pendingPause += confirmedPause
        val paused = if (prevWall == 0L) 0L else min(pendingPause, now - prevWall)
        // What this walk costs the sampler, measured rather than assumed: it runs on the sampling
        // thread, and a sampler that holds a 1 ms step is not something to spend without counting.
        val entered = System.nanoTime()
        // Read beside the thread clocks, so all three cover the same window.
        val process = ProcessCpuClock.cpuNanos()
        val sampler = ThreadCpuClock.currentCpuNanos()
        val jit = JitClock.nanos()
        refreshThreads()
        val next = HashMap<Long, Long>(Profiler.slotCeiling() * 2 + 8)
        var cpu = 0L
        var counted = 0
        var hidden = 0.0
        Profiler.forEachSlot { s ->
            val c = ThreadCpuClock.cpuNanos(s.threadId)
            // -1 means the thread died between the walk and the read. It contributes to neither
            // side: a thread with no CPU baseline would otherwise add wall time and no CPU, which
            // is precisely the direction that would invent a stall that never happened.
            if (c < 0) return@forEachSlot
            next[s.threadId] = c
            val p = prevCpu[s.threadId] ?: return@forEachSlot
            val d = c - p
            if (d < 0) anomalies++ else {
                cpu += d
                counted++
                // Wall time per thread is the same interval for all of them; it is kept per slot
                // anyway so that the two arrays can be divided one by the other without a second
                // count of who had a baseline in which window.
                val idx = s.index
                if (idx >= 0) {
                    cpuByIndex[idx] += d
                    wallByIndex[idx] += now - prevWall - paused
                    hidden += hiddenIn(idx, d, now - prevWall - paused)
                }
            }
        }
        if (prevWall != 0L && counted > 0) {
            // Wall time counted once per thread that has a baseline, so numerator and denominator
            // cover exactly the same set of threads over exactly the same interval.
            val wall = (now - prevWall) * counted
            cpuNanos += cpu
            wallNanos += wall
            pausedNanos += paused * counted
            if (process >= 0 && prevProcess >= 0 && hidden > 0) {
                if (processorsFull(process - prevProcess, processors, now - prevWall)) hiddenWhenFull += hidden
                else hiddenWhenSpare += hidden
            }
            if (process < 0 || sampler < 0 || prevProcess < 0 || prevSampler < 0) processCpuNanos = -1L
            else if (processCpuNanos >= 0) {
                processCpuNanos += process - prevProcess
                samplerCpuNanos += sampler - prevSampler
                // No JIT clock is not a reason to give up the rest: it goes to -1 and the line leaves it out.
                if (jit < 0 || prevJit < 0) jitNanos = -1L else if (jitNanos >= 0) jitNanos += jit - prevJit
                profiledCpuNanos += pendingProfiledCpu
                pausedProcessCpuNanos += (pendingPausedCpu * pausedShareOf(pendingLate, confirmedPause)).toLong()
                spanNanos += now - prevWall
            }
            windows++
            val d = cpu.toDouble() / wall
            if (windows == 1) {
                minDuty = d
                maxDuty = d
            } else {
                minDuty = min(minDuty, d)
                maxDuty = max(maxDuty, d)
            }
            if (counted > maxThreads) maxThreads = counted
        }
        // Each window keeps only what happened in it; outside a counted window it is dropped, as the
        // process clock's own delta is.
        pendingProfiledCpu = 0L
        pendingPausedCpu = 0.0
        pendingLate = 0L
        prevCpu = next
        prevProcess = process
        prevSampler = sampler
        prevJit = jit
        hits?.copyInto(prevHits)
        labelled?.copyInto(prevLabelled)
        waiting?.copyInto(prevWaiting)
        labelledWaiting?.copyInto(prevLabelledWaiting)
        // A sample that found nothing to measure is not a window boundary, and taking it as one
        // costs a whole window.
        //
        // The first sample fires at start(), before any worker has registered a slot - threads are
        // usually created after the profiler is started - so it establishes no baselines. Taken as
        // a boundary, it would leave the first window unable to close, make the second sample only
        // the baseline, and leave finish() the first sample able to count anything. finish() runs at
        // stop(), which is exactly when short-lived workers have already exited and their CPU clock
        // reads -1, so a run under about two windows would never be measured at all. A pool thread
        // outlives the run and hides this; short-lived workers do not.
        //
        // Leaving `prevWall` alone here means the next sample measures from the last boundary that
        // meant something, so no wall time is attributed without the CPU time that goes with it.
        // Only before the first baseline exists. Once the window grid has started, an empty walk
        // is a boundary like any other - every thread having died is not a reason to walk the slots
        // on every tick for the rest of the run.
        if (next.isEmpty() && prevWall == 0L) return
        prevWall = now
        pendingPause = 0
        val cost = System.nanoTime() - entered
        if (cost > maxSampleNanos) maxSampleNanos = cost
    }

    /**
     * Everything the sampler collected, plus the bound on every share that [labelledDuty] works out
     * from it. The arithmetic lives there and not here, so that it can be checked without starting
     * a sampler.
     */
    fun report(
        slotHits: LongArray,
        slotLabelled: LongArray,
        slotWaiting: LongArray,
        slotLabelledWaiting: LongArray,
        stateSampled: Boolean,
    ): DutyReport = run {
        val b =
            if (available) labelledDuty(
                slotHits, slotLabelled, slotWaiting, slotLabelledWaiting,
                cpuByIndex, wallByIndex, stateSampled
            )
            else LabelledDuty(Double.NaN, Double.NaN, Double.NaN)
        DutyReport(
        labelledDuty = b.duty,
        runnableDuty = b.runnableDuty,
        invisibleOffCpu = b.invisibleOffCpu,
        labelledFraction = b.labelledFraction,
        reason = reason,
        resolutionNanos = resolution,
        windowNanos = windowNanos,
        windows = windows,
        threads = maxThreads,
        cpuNanos = cpuNanos,
        wallNanos = wallNanos,
        minWindowDuty = minDuty,
        maxWindowDuty = maxDuty,
        anomalies = anomalies,
        maxSampleNanos = maxSampleNanos,
        pausedNanos = pausedNanos,
        processCpuNanos = if (spanNanos > 0) processCpuNanos else -1L,
        samplerCpuNanos = samplerCpuNanos,
        jitNanos = jitNanos,
        pausedProcessCpuNanos = pausedProcessCpuNanos,
        profiledCpuNanos = if (spanNanos > 0) profiledCpuNanos else -1L,
        hiddenWhenFull = hiddenWhenFull,
        hiddenWhenSpare = hiddenWhenSpare,
        spanNanos = spanNanos,
        processors = Runtime.getRuntime().availableProcessors(),
        )
    }

    companion object {
        /**
         * Probed once, lazily, on whatever thread asks first. It costs up to 300 ms of spinning on
         * a platform with a coarse clock, so it must not sit on a path that runs per sampler.
         */
        val resolution: Long by lazy { ThreadCpuClock.probeResolutionNanos() }

        /**
         * One second. The window has to be far longer than the clock's granularity — 15.6 ms on
         * Windows — and far shorter than a run, and there is nothing here a finer window would
         * answer: this is an aggregate bound, not a time series.
         */
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
    }
}

/** What [labelledDuty] came to. The second and third explain the first; the fourth is what the Careful line judges. */
internal class LabelledDuty(
    val duty: Double,
    val invisibleOffCpu: Double,
    val labelledFraction: Double,
    /** See [DutyReport.runnableDuty]. */
    val runnableDuty: Double = Double.NaN,
)

/**
 * How much of *labelled* occupancy was CPU — the number the report's shares are bounded by.
 *
 * A bound and not a measurement, and it has to be: a thread can be parked *inside* a label. A
 * label placed around a lock acquisition catches most of its hits with the thread not runnable,
 * so "labelled therefore running" is false and the cheap version of this is not available.
 *
 * What is available is an interval, and how tight it is depends on whether thread state was
 * sampled. For thread *i*, as fractions of that thread's own occupancy:
 *
 * - `f` — off the CPU altogether, from the clock. Everything below has to fit inside it.
 * - `l` — inside some label.
 * - `w` — caught not runnable, and `wl` the part of that which was also inside a label. Both
 *   measured per sample rather than inferred.
 *
 * **Without state**, all that can be said is `min(f, l)`: no more stall inside labels than there is
 * stall, and no more than there are labels. Sound, and on a thread pool useless — a pool thread
 * parks between tasks and works inside a label, so both terms are large and the bound assumes the
 * parking happened inside the label. A true duty near 98% can print under 50%, with a 100 pp
 * error on shares that are fine.
 *
 * **With state** the visible half is not an assumption at all. `wl` *is* the waiting that was
 * inside a label. What is left over, `f − w`, is off-CPU the state read cannot see — a preempted
 * thread and a thread in a native call both read `RUNNABLE` — and that part still has to be assumed
 * worst case, all of it inside labels. So `min(l, wl + max(0, f − w))`.
 *
 * Still an upper bound, and a tight one when most of the off-CPU time is observably not runnable:
 * if 30 of 31 points of off-CPU read as waiting, only 1 is left to assume the worst about.
 *
 * A free function over six arrays rather than a method, because everything it does is arithmetic
 * and arithmetic should be checkable without starting a sampler. The regimes it has to get right
 * are in `DutyBoundTest`.
 *
 * @param slotHits every sample taken at that slot, labelled or not. Its length bounds the walk, so
 *   a caller with three threads passes arrays of three.
 * @param slotLabelled those of them that were inside some operation.
 * @param slotWaiting those that caught the thread not runnable, and [slotLabelledWaiting] the ones
 *   that were also inside a label.
 * @param cpuNanos, wallNanos per slot, from the duty walk.
 */
internal fun labelledDuty(
    slotHits: LongArray,
    slotLabelled: LongArray,
    slotWaiting: LongArray,
    slotLabelledWaiting: LongArray,
    cpuNanos: LongArray,
    wallNanos: LongArray,
    stateSampled: Boolean,
): LabelledDuty {
    var labelled = 0.0
    var stall = 0.0
    var invisible = 0.0
    var total = 0.0
    // The same, over labelled samples that read RUNNABLE only: what the Careful line judges.
    var labelledRunnable = 0.0
    var runnableStall = 0.0
    for (i in slotHits.indices) {
        val hits = slotHits[i]
        val wall = wallNanos[i]
        if (hits == 0L || wall <= 0L) continue
        val l = slotLabelled[i].toDouble() / hits
        // Clamped: the CPU counter moves in scheduler ticks, so a window can read a hair over
        // wall or a hair under zero, and a fraction outside 0..1 is quantisation and not news.
        val f = min(1.0, max(0.0, 1.0 - cpuNanos[i].toDouble() / wall))
        labelled += slotLabelled[i]
        total += hits
        stall += if (!stateSampled) min(f, l) * hits
        else {
            val w = slotWaiting[i].toDouble() / hits
            val wl = slotLabelledWaiting[i].toDouble() / hits
            // Off the CPU while reading RUNNABLE: the scheduler, or a native call the JVM cannot
            // see into. Tracked because it is the term that can make the bound useless, and a
            // reader who is told that is in a different position from one who is not.
            invisible += max(0.0, f - w) * hits
            // Only the off-CPU the state read cannot see, and no more of it than there is labelled
            // time reading RUNNABLE. The visible waiting is left out: each row's Waiting shows it.
            labelledRunnable += slotLabelled[i] - slotLabelledWaiting[i]
            runnableStall += min(max(0.0, l - wl), max(0.0, f - w)) * hits
            // Capped by `f` as well, so that using the state read can never produce a *worse*
            // bound than ignoring it. Not runnable implies not on the CPU, so `wl` is a subset of
            // `f` by definition and the cap binds only when the two instruments disagree — the
            // clock moves in 15.6 ms steps and the state read does not, so they will.
            min(l, min(f, wl + max(0.0, f - w))) * hits
        }
    }
    if (labelled <= 0.0 || total <= 0.0) return LabelledDuty(Double.NaN, Double.NaN, Double.NaN)
    // Clamped for the same reason `f` is, one loop up.
    // `labelled` accumulates exact counts while `stall` reaches the same quantity through a divide
    // and a multiply — `min(…, l) * hits` where `l = slotLabelled / hits` — so when `l` is the
    // binding term and is a fraction no double holds exactly, `stall` can exceed `labelled` by a
    // ULP and the duty comes out a hair below zero. Unclamped, that prints as a bound of minus
    // several quintillion pp.
    return LabelledDuty(
        ((labelled - stall) / labelled).coerceIn(0.0, 1.0),
        invisible / total,
        labelled / total,
        if (!stateSampled || labelledRunnable <= 0.0) Double.NaN
        else ((labelledRunnable - runnableStall) / labelledRunnable).coerceIn(0.0, 1.0),
    )
}

/** What the duty measurement came to, and the bound it puts on every share in the report. */
internal class DutyReport internal constructor(
    /**
     * The fraction of *labelled* occupancy that was CPU, worst case. NaN when it could not be
     * computed, and the aggregate [duty] then stands in — pessimistic rather than wrong.
     * See [labelledDuty].
     */
    val labelledDuty: Double,
    /**
     * Of the labelled time that read `RUNNABLE`, the part sure to have been on a CPU, worst case.
     * NaN without the state read, and the Careful line then judges [shareDuty].
     *
     * [labelledDuty] counts the waiting the state read saw inside labels as possibly not CPU, which
     * is right for a share read as CPU, and so it stays the ceiling on `working`. But the Careful
     * line is about `Runnable`, and that waiting is not hidden in it: each row's `Waiting` shows it.
     * A thread parked in `Future.get()` inside a label is almost all waiting, in plain sight, and
     * counted in [labelledDuty] it can make up half the bound.
     */
    val runnableDuty: Double = Double.NaN,
    /**
     * Occupancy that was off the CPU while the thread read `RUNNABLE`, as a fraction of all of it.
     *
     * The scheduler preempting a thread, and a thread inside a native call the JVM cannot see into.
     * This is the part of the stall that has to be assumed worst case, and when it is larger than
     * the labelled fraction the bound collapses to "all of the labelled time could have been
     * waiting" — which is true, useless, and worth saying in words instead of as a number.
     */
    val invisibleOffCpu: Double,
    /** Sampled occupancy that was inside some label, over the threads the bound could be taken on. */
    val labelledFraction: Double,
    val reason: String?,
    val resolutionNanos: Long,
    val windowNanos: Long,
    val windows: Int,
    val threads: Int,
    val cpuNanos: Long,
    val wallNanos: Long,
    val minWindowDuty: Double,
    val maxWindowDuty: Double,
    val anomalies: Int,
    /** The dearest of the walks, so what this costs the sampling thread is a number and not a hope. */
    val maxSampleNanos: Long,
    /**
     * Of [wallNanos], the part in which every thread was stopped - confirmed pauses, summed per
     * thread the way [wallNanos] is. See [PauseTracker.settle] for what confirmed means.
     */
    val pausedNanos: Long = 0,
    /**
     * The whole JVM's CPU over the windows, -1 where it could not be read, and the sampler's own.
     * With [cpuNanos], the profiled threads', and [spanNanos], the windows' wall time once, they say
     * how many cores went where: see [coresLine].
     */
    val processCpuNanos: Long = -1,
    val samplerCpuNanos: Long = 0,
    /** The JIT's compile time over the windows, -1 where it could not be read. See [JitClock]. */
    val jitNanos: Long = -1,
    /** The JVM's CPU while all threads were stopped, as far as the JVM confirmed the stops as its own. See [pausedShareOf]. */
    val pausedProcessCpuNanos: Long = 0,
    /**
     * The profiled threads' CPU over the same windows, read ten times a second so a thread's start and
     * end are not lost (see `DutyCycle.coresLatest`); -1 in a saved profile that lacks it, where
     * [cpuNanos] stands in.
     */
    val profiledCpuNanos: Long = -1,
    /**
     * The waiting `Runnable` hides, in samples, in seconds when the JVM's processors were full and
     * in seconds when a core was spare. What the Careful line names as the cause. 0 and 0 when not
     * measured, and the line then names both causes.
     */
    val hiddenWhenFull: Double = 0.0,
    val hiddenWhenSpare: Double = 0.0,
    val spanNanos: Long = 0,
    /** What the JVM was told it has: `-XX:ActiveProcessorCount`, or the machine's. 0 if not recorded. */
    val processors: Int = 0,
) {
    /** True when there is a number to read: a measurement was attempted and it succeeded. */
    val available: Boolean get() = reason == null && windows > 0 && wallNanos > 0

    /**
     * The fraction of wall time that was CPU, over every registered thread, pauses included.
     *
     * What the machine did to a thread, all of it - so a collection counts against it. The figure
     * the report prints and bounds the shares with is [activeDuty].
     */
    val duty: Double get() = if (wallNanos > 0) cpuNanos.toDouble() / wallNanos else Double.NaN

    /**
     * The fraction of *active time* that was CPU: [duty] with the confirmed pauses taken out of the
     * wall clock.
     *
     * Capped at 1, because the CPU counter moves in scheduler ticks and the pauses are subtracted
     * exactly, so a short run can read a hair over.
     */
    val activeDuty: Double
        get() = if (wallNanos - pausedNanos > 0) min(1.0, cpuNanos.toDouble() / (wallNanos - pausedNanos))
        else Double.NaN

    /**
     * The duty the shares are actually bounded by: [labelledDuty] where it exists, and the
     * aggregate where it does not.
     *
     * Everything that judges a *share* uses this. Everything that describes what the *machine* was
     * doing to every thread at once — [Profile.machineFloor], [Profile.offCpuSamples] — keeps using
     * [duty], because there the whole process is the subject and an idle thread belongs in it.
     */
    val shareDuty: Double get() = if (labelledDuty.isNaN()) activeDuty else labelledDuty

    /**
     * True when the bound has run out of evidence rather than found something.
     *
     * The invisible off-CPU has to be assumed to be inside the labels, and when there is more of it
     * than there is labelled time, that assumption swallows the whole of it: the bound comes out at
     * "every labelled sample could have been a wait". Event loops are the typical case: labels over
     * a small part of the threads' time, and `epoll_wait` off the CPU while reading `RUNNABLE`.
     */
    val unbounded: Boolean
        get() = !labelledDuty.isNaN() && !invisibleOffCpu.isNaN() &&
                labelledDuty <= 0.0 && invisibleOffCpu > 0.0

    /**
     * The worst a share can be wrong by, in percentage points, if every stall there was landed on
     * one operation.
     *
     * With duty `d`, an operation reported at occupancy share `s` has a true CPU share somewhere in
     * `[(s - (1 - d)) / d, s / d]`, clamped to 0..1 — the low end if all the stalling was its own,
     * the high end if none of it was. The widest either end can sit from `s`, over all `s`, is
     * `(1 - d) / d`, and that is the number worth printing: it holds for every operation at once
     * and needs nothing to be known about where the stalling went.
     */
    val boundPp: Double get() = min(100.0, (1 - shareDuty) / shareDuty * 100)

    /** The duty the Careful line judges: [runnableDuty] where the state was read, [shareDuty] where not. */
    val carefulDuty: Double get() = if (runnableDuty.isNaN()) shareDuty else runnableDuty

    /** [boundPp] over [carefulDuty]: how far `Runnable` may overstate CPU work. */
    val runnableBoundPp: Double get() = min(100.0, (1 - carefulDuty) / carefulDuty * 100)

    /** What a reported occupancy share could really be, as CPU. */
    fun boundsFor(share: Double): Pair<Double, Double> =
        max(0.0, (share - (1 - shareDuty)) / shareDuty) to min(1.0, share / shareDuty)

    /**
     * Where the JVM's cores went, or null without the figures: the processors, the profiled
     * threads, and the rest of the JVM split into GC, the sampler and the JIT, largest first, with
     * how much of GC was spent while every thread was stopped. "GC" is what no clock names: the
     * JVM's CPU less the others, so threads with no label are in it too, which on a profiled
     * program is next to nothing.
     *
     * The `Cores` line under `Wall time`, always, so a run with too few processors for its threads
     * shows it at a glance: five workers on six cores getting two of them while the JVM's own
     * threads take three and a half is a finding only the JVM's own clock shows. The profiled
     * threads come first, then the rest and its parts.
     */
    fun coresLine(): String? {
        if (processCpuNanos < 0 || spanNanos <= 0) return null
        val span = spanNanos.toDouble()
        val profiled = if (profiledCpuNanos >= 0) profiledCpuNanos else cpuNanos
        val jit = if (jitNanos >= 0) min(jitNanos, max(0L, processCpuNanos - profiled - samplerCpuNanos)) else 0L
        val gc = max(0L, processCpuNanos - profiled - samplerCpuNanos - jit)
        val parts = listOfNotNull(
            gc to (if (pausedProcessCpuNanos > 0)
                String.format(Locale.ROOT, "GC %.2f (%.2f in pauses)", gc / span, min(pausedProcessCpuNanos, gc) / span)
            else String.format(Locale.ROOT, "GC %.2f", gc / span)),
            samplerCpuNanos to String.format(Locale.ROOT, "sampler %.2f", samplerCpuNanos / span),
            if (jitNanos >= 0) jit to String.format(Locale.ROOT, "JIT %.2f", jit / span) else null,
        ).sortedByDescending { it.first }
        return String.format(
            Locale.ROOT, "%sprofiled threads %.2f, the rest %.2f: %s",
            if (processors > 0) "$processors processors: " else "",
            profiled / span, (gc + samplerCpuNanos + jit) / span, parts.joinToString(", ") { it.second }
        )
    }

    /**
     * The CPU check as a one-line figure for a caller that wants one; the report does not print it.
     */
    fun summary(): String =
        if (!available) "Time on CPU not measured: ${reason ?: "no window closed"}"
        else String.format(
            Locale.ROOT, "Time on CPU %.2f%% of active time, bound %s pp, %d windows over %d threads",
            activeDuty * 100, threeDigits(boundPp), windows, threads
        )

    /**
     * What is wrong with `Runnable`, if anything: `Careful` lines under the header, none on a
     * healthy run.
     *
     * `Runnable` is the JVM's word and an upper bound on work - a thread the scheduler preempted
     * reads runnable, and so does one stopped in a native call. The CPU clock is the only thing that
     * says how much of it was real, and these lines are printed only when it says the gap matters,
     * or when it could not be asked. Nothing is printed when the bound is under [CAREFUL_PP]: silence
     * says "fine".
     *
     * @param activeNanos how long the session sampled for, pauses out, so a failure can name the
     *   condition it found rather than assume the run was too short.
     * @param reclaimedSlots threads that exited during the run. The other way to get no window:
     *   the walk reads -1 from a dead thread's CPU clock, so it counts nothing.
     */
    fun careful(activeNanos: Long, reclaimedSlots: Int): List<String> {
        val out = ArrayList<String>()
        if (reason != null) out += "Runnable: unchecked against the CPU clock, $reason - $UNCHECKED"
        // Three ways to finish with no window, and the message names the one that happened. Telling
        // a 1.7 s run it was shorter than a 1 s window would contradict the report's own numbers,
        // which is worse than saying nothing.
        else if (!available) out += "Runnable: unchecked against the CPU clock, " + when {
            activeNanos in 1..<windowNanos -> String.format(
                Locale.ROOT, "the %.1f s run is shorter than the %.3f s check window",
                activeNanos / 1e9, windowNanos / 1e9
            )
            // The clock reads -1 for a thread that has exited, so a walk that finds only dead
            // threads counts nothing - and the last walk happens at stop(), which is when
            // short-lived workers have already finished.
            reclaimedSlots > 0 -> String.format(
                Locale.ROOT, "no window closed, %d of the threads being measured exited before the run ended",
                reclaimedSlots
            )
            else -> String.format(Locale.ROOT, "no window closed in the %.1f s run", activeNanos / 1e9)
        } + " - $UNCHECKED"
        // The bound has not found anything; it has run out of evidence, and that is a different
        // sentence. It also names the fix, which is a label around the waiting rather than only
        // around the work.
        else if (unbounded) out += String.format(
            Locale.ROOT,
            "Runnable: unchecked, %.1f%% of thread-time was off the CPU while reading runnable (a native " +
                    "call, an event loop in a poll, or the scheduler) and operations cover %.1f%% of these " +
                    "threads, so all of it could be inside them - put a label around the waiting, not only the work",
            invisibleOffCpu * 100, labelledFraction * 100
        )
        // Said as what it means for the column a reader looks at, then the number behind it, then
        // what to do: in the report's own words, with a remedy beside each cause.
        // Broken into lines as a warning is, since it is four times the width of the others.
        else if (runnableBoundPp > CAREFUL_PP) out += String.format(
            Locale.ROOT,
            "Thread-time%%: may be off by up to %s pp as a share of CPU time - " +
                    "only %s of the time inside labels read as Runnable is sure to have been on a CPU, the rest may have been waiting%n",
            threeDigits(runnableBoundPp), percent(carefulDuty * 100)
        ) + causeLine() + (if (carefulDuty < OCCUPANCY_ONLY)
            "\nRead a share as where threads SIT, not where cycles GO, and do not add two up" else "")
        if (anomalies > 0) out += "thread CPU clock: $anomalies readings went backwards - dropped"
        return out
    }

    /**
     * What the waiting `Runnable` hides most likely was, from the seconds it happened in: full
     * processors mean threads waiting for a core, spare ones mean something else. The "use fewer
     * threads" advice is only worth giving where it is measured, and a full second is measured,
     * while a spare one cannot tell I/O from another program on the cores.
     */
    private fun causeLine(): String {
        val all = hiddenWhenFull + hiddenWhenSpare
        val full = if (all > 0) hiddenWhenFull / all else Double.NaN
        val cores = if (processors > 0) "$processors processors" else "processors"
        return when {
            full.isNaN() -> "Likely causes: too many threads for the cores (use fewer),\n" +
                    "or blocking I/O or native calls (label them, so their waiting is a row of its own)"
            full >= ONE_CAUSE -> "The JVM's $cores were full when it happened: threads waited for a core.\n" +
                    "Give the JVM more processors, or run fewer threads"
            full <= 1 - ONE_CAUSE -> "The JVM had cores to spare when it happened, so it was not waiting for one:\n" +
                    "blocking I/O or native calls (label them, so their waiting is a row of its own), or other programs on the same cores"
            else -> String.format(
                Locale.ROOT,
                "%s of it when the JVM's %s were full - threads waiting for a core: more processors or fewer threads -%n" +
                        "and %s with cores to spare: blocking I/O, native calls (label them) or other programs on the same cores",
                percent(full * 100), cores, percent((1 - full) * 100)
            )
        }
    }

    companion object {
        /**
         * Above this the non-CPU fraction cannot reorder the top of a report: the bound is then
         * under a percentage point, and real gaps between ranked operations are wider than that.
         * A judgement about how to read the number rather than a measured threshold — which is why
         * the bound itself is printed above this line, and this line only names it.
         */
        const val RANKING_SAFE = 0.95
        const val OCCUPANCY_ONLY = 0.75

        /**
         * What the row is about, printed only when there is no number to say it instead.
         *
         * Both failure paths share it because a reader who hits either one is in the same position:
         * they have been told a measurement is missing without being told what it would have meant.
         */
        private const val UNCHECKED =
            "read Runnable as an upper bound on work, with nothing saying how much of it was waiting"

        /**
         * Below this bound nothing is said: a point of any share is under the gap between any two
         * rows worth acting on. A busy machine can read several points from preemption alone, and
         * then the line is right to print.
         */
        const val CAREFUL_PP = 1.0
    }
}
