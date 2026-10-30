package com.ticksnick

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * Ceiling on distinct coarse types. Same reasoning as [MAX_OPERATIONS] and a much smaller number:
 * coarse labels go on logical operations — serving a query, expanding a frontier — and fifty is
 * already a large application's worth. The sampler indexes flat arrays by this.
 */
internal const val MAX_COARSE_TYPES = 64

/**
 * How far up the parent chain the sampler walks when crediting inclusive occupancy.
 *
 * A bound and not a limit on nesting: contexts nest as deeply as the caller likes, and past this
 * depth only the *inclusive* credit for the outermost ancestors is given up. Sixteen is far past
 * any sane nesting of logical operations, and the walk happens once per occupied slot per tick.
 */
internal const val MAX_COARSE_DEPTH = 16

/**
 * How many of its slowest executions a coarse operation opened with a key keeps, per path. They
 * are printed under its row, longest first, and saved with the run.
 */
internal const val SLOWEST_KEPT = 5

/**
 * How many executions a node keeps exactly before [Spans] switches to the histogram: as many as the
 * histogram has buckets, so the exact durations never take more memory than the histogram would.
 */
internal const val EXACT_SPANS = SpanHistogram.BUCKETS

/** Durations of a node's first exact array: a node that runs a handful of times stays this small. */
private const val FIRST_EXACT = 4

/**
 * One execution of a coarse operation.
 *
 * Allocated on entry, published into the calling thread's slot, and read from the sampling thread
 * with no lock and no fence. That is safe for exactly one reason: every identity field is `val`, so
 * the end of the constructor carries a freeze, and a thread that sees the reference is guaranteed to
 * see the fields. It is the same bargain the fine slot makes — a read may be a few nanoseconds
 * stale, and never torn.
 *
 * **Never recycled.** A pool would break that freeze and, worse, would let the sampler credit a
 * sample to a context that had since become a different execution. Silent misattribution is the
 * failure mode this whole design guards against first, and an allocation here costs a
 * fraction of the ~40 ns the tier already spends.
 */
class CoarseContext @PublishedApi internal constructor(
    /** Which coarse operation this is an execution of. */
    @JvmField @PublishedApi internal val type: Int,
    /** The execution this one is nested inside, or null. The stack, as a chain of few long-lived objects. */
    @JvmField @PublishedApi internal val parent: CoarseContext?,
    /** `System.nanoTime()` at entry. Half of the only exactly-measured quantity in the report. */
    @JvmField @PublishedApi internal val startNanos: Long,
    /**
     * Where this execution sits in the opening thread's calling-context tree, or null for a
     * context made outside the hook. Set at entry so that the sampler credits the node with one
     * read and exit records the span into it with none. See [CoarsePath].
     */
    @JvmField internal val path: CoarsePath? = null,
) {
    /** How many ancestors this context has. Kept so the sampler's walk is bounded without counting. */
    @JvmField
    internal val depth: Int = if (parent == null) 0 else parent.depth + 1

    /**
     * The tick at which the sampler last counted this instance, so an instance occupied by three
     * threads at one tick counts once and not three times.
     *
     * Written and read **only by the sampling thread**, so it needs no ordering of any kind. It is
     * the one field the sampler touches, and touching it dirties a cache line the owning thread
     * will want again at exit — one miss per instance per tick, against an object that already cost
     * an allocation. See [Sampler].
     */
    @JvmField
    internal var tickStamp: Long = -1L

    /**
     * As [tickStamp], for the count per path rather than per type: [tickStamp] is set only for the
     * first context of a type in a chain, so a type nested in itself would be counted once.
     */
    @JvmField
    internal var nodeStamp: Long = -1L

    /**
     * Whether the thread that opened this execution has closed it.
     *
     * **The one thing a borrowed context cannot tell you by looking at it.** A context that has been
     * handed to a pool outlives the slot it came from: the owner closes its span and moves on, and
     * any thread still mounted on it goes on being sampled into an execution that has finished. That
     * is not attribution lost, which the report can already name — it is attribution *invented*, and
     * without this flag nothing in the output would say so.
     *
     * Written once by the owning thread the moment it restores its slot, read by the sampler for
     * every occupied context it visits. Opaque on both sides for the reason [OpSlot.current] is: a
     * plain write here is a dead store from the writer's point of view and the JIT would be within
     * its rights to drop it, while a volatile one buys ordering nothing here needs and costs a fence
     * per coarse execution. A read a few nanoseconds stale is the same bargain every other field in
     * this design makes.
     */
    @JvmField
    internal var closed: Boolean = false

    internal companion object {
        @JvmStatic
        val CLOSED: VarHandle = MethodHandles.lookup()
            .findVarHandle(CoarseContext::class.java, "closed", Boolean::class.javaPrimitiveType)
    }
}

/**
 * Marks this execution finished, so a thread still mounted on it can be detected.
 *
 * `@PublishedApi` because [coarse] is inline and closes the context in a `finally` the compiler
 * writes — the same reason [OpSlot.recordSpan] carries it.
 */
@PublishedApi
internal fun CoarseContext.markClosed() = CoarseContext.CLOSED.setOpaque(this, true)

/** Whether the owner has closed this execution. Read by the sampler, once per occupied context. */
internal fun CoarseContext.isClosed(): Boolean = CoarseContext.CLOSED.getOpaque(this) as Boolean

/**
 * A log-bucket histogram of span durations: fixed memory, any percentile to a known precision.
 * Only for a node past [EXACT_SPANS] executions; below that [Spans] keeps every duration.
 *
 * 64 sub-buckets per octave, over a range of 1 ns to about 19 hours: 2,624 buckets, 21 KB.
 *
 * A percentile is reported at the **middle** of the bucket that contains it, so it can be off by
 * [PRECISION] either way: under 0.8%, below the noise between runs. Not the top of a coarser
 * bucket: that is never below the truth but can be 12.5% above it with 8 sub-buckets, the same
 * order as the differences an optimisation step is after.
 *
 * Forty lines rather than a dependency, which is the whole of the argument for writing it.
 *
 * To check the profiler against timings of your own, put them through [Spans] the way the
 * profiler does, so that both sides are rounded alike and what remains is the profiler.
 */
internal object SpanHistogram {
    /** Sub-buckets per octave, as a power of two. */
    const val SUB_BITS = 6
    const val SUB = 1 shl SUB_BITS
    const val OCTAVES = 40
    const val BUCKETS = (OCTAVES + 1) * SUB

    /**
     * The most a reported percentile can be off the true one, either way, as a fraction: half the
     * widest bucket in an octave, which runs from `64x` to `65x`.
     */
    const val PRECISION = 1.0 / (2 * SUB)

    /** Which bucket a duration falls in. Linear below [SUB], logarithmic above it. */
    fun bucketOf(v: Long): Int {
        if (v < SUB) return if (v < 0) 0 else v.toInt()
        val hb = 63 - java.lang.Long.numberOfLeadingZeros(v)
        val shift = hb - SUB_BITS
        if (shift >= OCTAVES) return BUCKETS - 1
        val sub = ((v ushr shift) and (SUB - 1).toLong()).toInt()
        return ((shift + 1) shl SUB_BITS) + sub
    }

    /** The smallest duration that lands in bucket [i]. */
    fun lowerBound(i: Int): Long =
        if (i < SUB) i.toLong() else (SUB.toLong() + (i and (SUB - 1))) shl ((i shr SUB_BITS) - 1)

    /** The largest duration that lands in bucket [i]. */
    fun upperBound(i: Int): Long = if (i >= BUCKETS - 1) Long.MAX_VALUE else lowerBound(i + 1) - 1

    /** The middle of bucket [i], where a percentile that falls in it is reported. */
    fun middle(i: Int): Double = (lowerBound(i).toDouble() + upperBound(i).toDouble()) / 2

    /**
     * The [p]-th percentile of [counts], reported at the middle of its bucket. `p` in 0..1.
     *
     * Rank is `ceil(p x n)` and at least 1, the nearest-rank convention [Spans] uses on exact
     * durations too, so the two agree up to the rounding.
     */
    fun percentile(counts: LongArray, total: Long, p: Double): Double {
        if (total <= 0L) return Double.NaN
        val rank = maxOf(1L, Math.ceil(p * total).toLong())
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= rank) return middle(i)
        }
        return middle(counts.size - 1)
    }
}

/**
 * The durations of one node's executions, for its percentiles: **every one, exactly, until there
 * are [EXACT_SPANS] of them**, and a [SpanHistogram] from then on.
 *
 * Exact first because the executions that are few are the ones a reader reads one by one: the
 * batches of a benchmark, the requests of a short test. Three batches of about 30 s can fall in one
 * bucket, and a histogram would print the same p50, p90 and p99 for them. Past [EXACT_SPANS] a percentile
 * is a figure about a distribution, and the histogram's [SpanHistogram.PRECISION] is below what a
 * run can tell apart anyway.
 *
 * **Memory follows the executions.** The exact array grows by doubling and is never larger than
 * the histogram that replaces it, so a node executed three times holds a few longs rather than a
 * 21 KB histogram. That is what makes a thread per request affordable: each
 * virtual thread has a tree of its own, and most of its nodes run once.
 *
 * Written by the owning thread only, read when the report is taken, with no fence - the bargain
 * every span field here makes. A merge into a report's tree reads the array and its length once
 * each, so a read racing a write can miss the newest execution and never read past the array.
 */
internal class Spans internal constructor() {
    /** The durations while there are at most [EXACT_SPANS], unordered; null before the first and after the switch. */
    private var exact: LongArray? = null
    private var kept = 0
    private var hist: LongArray? = null

    /** How many durations are here. */
    val count: Long
        get() {
            // The exact side before the histogram, the other order from [toHistogram]'s writes: see there.
            val k = kept
            return hist?.sum() ?: k.toLong()
        }

    /** Whether the durations are still kept one by one. */
    val isExact: Boolean get() = hist == null

    fun record(nanos: Long) {
        val h = hist
        if (h != null) {
            h[SpanHistogram.bucketOf(nanos)]++
            return
        }
        var e = exact
        if (e == null) {
            e = LongArray(FIRST_EXACT)
            exact = e
        } else if (kept == e.size) {
            if (kept == EXACT_SPANS) {
                toHistogram()[SpanHistogram.bucketOf(nanos)]++
                return
            }
            e = e.copyOf(minOf(2 * e.size, EXACT_SPANS))
            exact = e
        }
        // The duration before the count, so a report never reads a slot not written yet.
        e[kept] = nanos
        kept++
    }

    /** Adds every duration of [o] here. */
    fun addAll(o: Spans) {
        val oe = o.exact
        val ok = o.kept
        val oh = o.hist
        if (oh != null) {
            val h = hist ?: toHistogram()
            for (i in h.indices) h[i] += oh[i]
            return
        }
        if (oe == null) return
        for (i in 0 until minOf(ok, oe.size)) record(oe[i])
    }

    /** Drops every duration, and the memory with them. */
    fun clear() {
        exact = null
        kept = 0
        hist = null
    }

    /**
     * The [p]-th percentile, `p` in 0..1, by nearest rank: exact while [isExact], within
     * [SpanHistogram.PRECISION] after. NaN with nothing recorded.
     */
    fun percentile(p: Double): Double {
        val e = exact
        val k = kept
        val h = hist
        if (h != null) return SpanHistogram.percentile(h, h.sum(), p)
        if (e == null) return Double.NaN
        val sorted = e.copyOf(minOf(k, e.size)).also { it.sort() }
        if (sorted.isEmpty()) return Double.NaN
        val rank = maxOf(1, Math.ceil(p * sorted.size).toInt())
        return sorted[minOf(rank, sorted.size) - 1].toDouble()
    }

    /** For [RunStore]: the exact durations, or null once they are a histogram. */
    internal fun exactDurations(): LongArray? {
        val e = exact
        val k = kept
        return if (hist != null) null else e?.copyOf(minOf(k, e.size)) ?: LongArray(0)
    }

    /** For [RunStore]: the histogram, or null while the durations are exact. */
    internal fun histogram(): LongArray? = hist

    /** Moves the exact durations into a new histogram, and frees them. */
    private fun toHistogram(): LongArray {
        val h = LongArray(SpanHistogram.BUCKETS)
        val e = exact
        if (e != null) for (i in 0 until kept) h[SpanHistogram.bucketOf(e[i])]++
        // Published before the exact array goes, so a report that reads the exact side first and the
        // histogram last finds one of them; read the other way, it can find neither and lose every duration.
        hist = h
        exact = null
        kept = 0
        return h
    }

    companion object {
        /** A node's durations from a list, for tests and for putting timings of your own through the same rounding. */
        fun of(values: Iterable<Long>): Spans = Spans().also { s -> values.forEach { s.record(it) } }

        /** A node's durations as a saved histogram. See [RunStore]. */
        internal fun ofHistogram(counts: LongArray): Spans = Spans().also { it.hist = counts }
    }
}

/**
 * One node of a calling-context tree: a coarse type at a path of coarse types.
 *
 * A node is a whole path, not a type under its parent. A table keyed by parent would know that
 * `tokenize` ran under `parse`, and not whether it was the `parse` under `query` or the one under
 * `load`: with a shared operation the figures under each of its callers would be one slot, printed
 * twice. Here every row the report draws is a stat of its own.
 *
 * **One tree per thread, single writer, merged by path when the report is taken.** Entry steps to a child, creating it the first time; exit
 * records the span into the node the context carries. A context borrowed from another thread
 * carries a node of that thread's tree, so the borrowing thread resolves the path in its own tree
 * instead - the rare case, and a walk of at most [MAX_COARSE_DEPTH].
 *
 * The sampler writes its own fields on a node - hits, the fine cross-tabulation, the pauses - and
 * nothing else does. The owning thread writes the span fields and nothing else does. Two writers,
 * disjoint fields, no fence: a read a few nanoseconds stale is the bargain everything here makes.
 */
@PublishedApi
internal class CoarsePath(
    /** The coarse type at this node, [ROOT] for the root of a tree. */
    @JvmField val type: Int,
    @JvmField val parent: CoarsePath?,
    /** The slot whose tree this is, or null for a merged tree. */
    @JvmField val owner: OpSlot?,
    /**
     * Whether this is a type's overflow node: where its executions past [MAX_COARSE_DEPTH] are
     * recorded, one per type under the root, so the per-type totals stay exact and the row can
     * say whose executions they were. Nothing is nested under it.
     */
    @JvmField val past: Boolean = false,
) {
    @JvmField val depth: Int = if (parent == null) 0 else parent.depth + 1

    /** This node's path, the same id in every thread's tree. See [PathIds]. */
    @JvmField val pathId: Int = if (parent == null) PathIds.ROOT else PathIds.of(parent.pathId, type, past)

    /** Children by type. Allocated on the first child. */
    @JvmField var children: Array<CoarsePath?>? = null

    /** On a root only: the overflow node per type, allocated on the first execution past the bound. */
    @JvmField var overflow: Array<CoarsePath?>? = null

    // The span half: written by the owning thread.
    @JvmField var count: Long = 0
    @JvmField var sumNanos: Long = 0
    @JvmField var minNanos: Long = Long.MAX_VALUE
    @JvmField var maxNanos: Long = 0
    @JvmField val spans = Spans()

    // The sampled half: written by the sampling thread.
    @JvmField var inclusiveHits: Long = 0
    @JvmField var runningInclusiveHits: Long = 0
    /** Ticks with an execution on this path open, one per execution per tick. See `CoarseNode.parallel`. */
    @JvmField var instanceTicks: Long = 0
    /** Ticks with a parent execution holding at least one execution on this path open, one per parent per tick. */
    @JvmField var coveredTicks: Long = 0
    /** Inclusive hits by the fine operation they caught, last entry no fine label. Allocated on the first hit. */
    @JvmField var fine: LongArray? = null
    /** Fine calls made directly under this path, by fine id. Written by the owner at coarse boundaries. */
    @JvmField var calls: LongArray? = null
    /** Bytes allocated directly under this path, outside its coarse children. Written as [calls] is. */
    @JvmField var allocated: Long = 0
    /**
     * Samples whose innermost context was an execution on this path, by fine label, last entry no
     * label: this path's own time, outside its coarse children. Unlabelled samples of a thread parked
     * while other threads work under the same execution - a join - are left out. See `CoarseNode.ownRows`.
     */
    @JvmField var ownFine: LongArray? = null
    /** Of those, one per execution per tick: the executions on this path with that label running. */
    @JvmField var ownFineCovered: LongArray? = null
    /**
     * Pause time charged to this node: every pause the sampler saw, charged once to every path
     * open at the tick that followed it, on the node of one of the threads that had it open. See
     * `Sampler.chargePause`.
     */
    @JvmField var pausedNanos: Long = 0

    // The slowest executions of an operation opened with a key: written by the owning thread,
    // unordered. Allocated on the first keyed execution.
    @JvmField var slowNanos: LongArray? = null
    @JvmField var slowKeys: Array<String?>? = null
    @JvmField var slowCount: Int = 0
    /**
     * What an execution has to beat to be among the slowest: -1 until [SLOWEST_KEPT] are kept, then
     * the shortest of them. The one compare a keyed execution pays when it is not slow.
     */
    @PublishedApi @JvmField internal var slowFloor: Long = -1

    fun child(type: Int): CoarsePath {
        val cs = children ?: arrayOfNulls<CoarsePath>(MAX_COARSE_TYPES).also { children = it }
        return cs[type] ?: CoarsePath(type, this, owner).also { cs[type] = it }
    }

    /** The overflow node for [type] under this root. See [past]. */
    fun deeper(type: Int): CoarsePath {
        val os = overflow ?: arrayOfNulls<CoarsePath>(MAX_COARSE_TYPES).also { overflow = it }
        return os[type] ?: CoarsePath(type, this, owner, past = true).also { os[type] = it }
    }

    fun record(nanos: Long) {
        count++
        sumNanos += nanos
        if (nanos < minNanos) minNanos = nanos
        if (nanos > maxNanos) maxNanos = nanos
        spans.record(nanos)
    }

    /** Keeps one execution of [nanos] opened with [key] if it is among the slowest. */
    @PublishedApi
    internal fun recordSlow(nanos: Long, key: String) {
        val ns = slowNanos ?: LongArray(SLOWEST_KEPT).also { slowNanos = it }
        val ks = slowKeys ?: arrayOfNulls<String>(SLOWEST_KEPT).also { slowKeys = it }
        if (slowCount < SLOWEST_KEPT) {
            ns[slowCount] = nanos
            ks[slowCount] = key
            slowCount++
            if (slowCount < SLOWEST_KEPT) return
        } else {
            var shortest = 0
            for (i in 1 until SLOWEST_KEPT) if (ns[i] < ns[shortest]) shortest = i
            if (nanos <= ns[shortest]) return
            ns[shortest] = nanos
            ks[shortest] = key
        }
        slowFloor = ns.min()
    }

    /** The slowest executions kept here, longest first. */
    fun slowest(): List<SlowExecution> =
        (0 until slowCount).map { SlowExecution(slowKeys!![it]!!, slowNanos!![it]) }.sortedByDescending { it.nanos }

    /** Adds [o] and everything under it into this node and the matching nodes under it. */
    fun mergeFrom(o: CoarsePath) {
        count += o.count
        sumNanos += o.sumNanos
        if (o.minNanos < minNanos) minNanos = o.minNanos
        if (o.maxNanos > maxNanos) maxNanos = o.maxNanos
        spans.addAll(o.spans)
        allocated += o.allocated
        inclusiveHits += o.inclusiveHits
        runningInclusiveHits += o.runningInclusiveHits
        instanceTicks += o.instanceTicks
        coveredTicks += o.coveredTicks
        pausedNanos += o.pausedNanos
        val of = o.fine
        if (of != null) {
            val f = fine ?: LongArray(MAX_OPERATIONS + 1).also { fine = it }
            for (i in f.indices) f[i] += of[i]
        }
        val oo = o.ownFine
        if (oo != null) {
            val f = ownFine ?: LongArray(MAX_OPERATIONS + 1).also { ownFine = it }
            for (i in f.indices) f[i] += oo[i]
        }
        val oc = o.ownFineCovered
        if (oc != null) {
            val f = ownFineCovered ?: LongArray(MAX_OPERATIONS + 1).also { ownFineCovered = it }
            for (i in f.indices) f[i] += oc[i]
        }
        val ocalls = o.calls
        if (ocalls != null) {
            val f = calls ?: LongArray(MAX_OPERATIONS).also { calls = it }
            for (i in f.indices) f[i] += ocalls[i]
        }
        for (i in 0 until o.slowCount) recordSlow(o.slowNanos!![i], o.slowKeys!![i]!!)
        o.children?.let { ocs -> for (t in ocs.indices) ocs[t]?.let { child(t).mergeFrom(it) } }
        o.overflow?.let { oos -> for (t in oos.indices) oos[t]?.let { deeper(t).mergeFrom(it) } }
    }

    /** Clears every figure here and below, keeping the shape. See `Profiler.resetCoarse`. */
    fun reset() {
        count = 0
        sumNanos = 0
        minNanos = Long.MAX_VALUE
        maxNanos = 0
        spans.clear()
        allocated = 0
        inclusiveHits = 0
        runningInclusiveHits = 0
        instanceTicks = 0
        coveredTicks = 0
        fine?.fill(0L)
        ownFine?.fill(0L)
        ownFineCovered?.fill(0L)
        calls?.fill(0L)
        pausedNanos = 0
        slowNanos?.fill(0L)
        slowKeys?.fill(null)
        slowCount = 0
        slowFloor = -1
        children?.forEach { it?.reset() }
        overflow?.forEach { it?.reset() }
    }

    /** Whether any span or sample was recorded here or below. */
    fun isEmpty(): Boolean {
        if (count > 0 || inclusiveHits > 0) return false
        children?.forEach { if (it != null && !it.isEmpty()) return false }
        overflow?.forEach { if (it != null && !it.isEmpty()) return false }
        return true
    }

    /** Every node under this one, children and overflow nodes, for a walk that wants them all. */
    fun below(): List<CoarsePath> =
        (children?.filterNotNull() ?: emptyList()) + (overflow?.filterNotNull() ?: emptyList())

    companion object {
        const val ROOT = -1
    }
}

/**
 * One coarse type's totals over every path and every thread: what the per-type statistics are
 * taken from. Built from the tree when the report is taken, never written on the hot path.
 */
internal class CoarseAgg(@JvmField val type: Int) {
    @JvmField var count: Long = 0
    @JvmField var sumNanos: Long = 0
    @JvmField var minNanos: Long = Long.MAX_VALUE
    @JvmField var maxNanos: Long = 0
    @JvmField val spans = Spans()

    fun mergeFrom(o: CoarsePath) {
        count += o.count
        sumNanos += o.sumNanos
        if (o.minNanos < minNanos) minNanos = o.minNanos
        if (o.maxNanos > maxNanos) maxNanos = o.maxNanos
        spans.addAll(o.spans)
    }
}

/**
 * One id per path of coarse types, shared by every thread's tree: `request > parse` is the same id
 * in all of them. The trees are per thread and merged by path when the report is taken, so anything
 * that must count once per path - a pause, which stops every thread at once - needs a name for the
 * path that is not one thread's node. Assigned when a node is first created, which is once per
 * thread per path, never on the hot path.
 */
internal object PathIds {
    const val ROOT = 0
    private val ids = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val next = java.util.concurrent.atomic.AtomicInteger(ROOT + 1)

    /** The id of the path [type] under [parent], an overflow node apart when [past]. */
    fun of(parent: Int, type: Int, past: Boolean): Int =
        ids.computeIfAbsent((parent.toLong() shl 32) or ((type + 1).toLong() shl 1) or (if (past) 1L else 0L)) {
            next.getAndIncrement()
        }
}

/**
 * Marks the calling thread as inside an execution of coarse operation [type] for the duration of
 * [body], and measures how long that took.
 *
 * This is the form to reach for. Its `finally` is written by the compiler and cannot leak, and the
 * span it records is exactly the interval the caller wrapped. [Profiler.enter] exists for the
 * boundary that is not a block.
 *
 * **What it costs is not the fine tier's ~2 ns.** An allocation, two `nanoTime` calls and a handful
 * of writes come to tens of nanoseconds, which is why the tier boundary exists at all: an operation
 * under about a microsecond cannot afford this and belongs in the fine tier.
 */
inline fun <T> op(op: CoarseOp, body: () -> T): T {
    val slot = Profiler.slot()
    val parent = slot.contextOpaque()
    slot.flushCounts(parent)
    val ctx = CoarseContext(op.id, parent, System.nanoTime(), slot.pathFor(parent, op.id))
    slot.setContextOpaque(ctx)
    try {
        return body()
    } finally {
        // Restored before the span is recorded, so the recording itself is never billed to the
        // execution it is closing.
        slot.setContextOpaque(parent)
        // Marked the moment this thread leaves it, and before the span is recorded: from here on,
        // any thread still mounted on this context is working under an execution that is over.
        //
        // After the slot is restored, deliberately. The sampler treats a closed context as stale
        // only if the slot still holds it, so marking first would leave a window — one opaque store
        // wide — in which this thread's own exit looks like an escape. **No test covers this**: the
        // window is a nanosecond or two against a 1 ms sampling step, so swapping these two lines
        // leaves the suite green.
        ctx.markClosed()
        slot.recordSpan(ctx, System.nanoTime() - ctx.startNanos)
        slot.flushCounts(ctx)
    }
}

/**
 * The same, and keeps the [SLOWEST_KEPT] slowest executions under the [key] each was opened with,
 * so the report can say which ones they were: `op(resolveRoot, key = { root }) { ... }`.
 *
 * [key] is called only for an execution that makes the slowest so far, and after the end timestamp,
 * so it is never part of the span it names. An execution that is not slow pays one compare more
 * than the plain form. The few that are - about `SLOWEST_KEPT x ln(executions / SLOWEST_KEPT)` in a
 * run in random order - pay for the key's `toString` and five longs scanned.
 *
 * The time is the execution's wall time, pauses included, so a key can be slowest only because a
 * collection landed in it.
 */
inline fun <T> op(op: CoarseOp, key: () -> Any?, body: () -> T): T {
    val slot = Profiler.slot()
    val parent = slot.contextOpaque()
    slot.flushCounts(parent)
    val ctx = CoarseContext(op.id, parent, System.nanoTime(), slot.pathFor(parent, op.id))
    slot.setContextOpaque(ctx)
    try {
        return body()
    } finally {
        // As the plain form, then the one compare.
        slot.setContextOpaque(parent)
        ctx.markClosed()
        val nanos = System.nanoTime() - ctx.startNanos
        val node = slot.recordSpan(ctx, nanos)
        if (node != null && nanos > node.slowFloor) node.recordSlow(nanos, keyOf(key))
        slot.flushCounts(ctx)
    }
}

/** The chain form of the coarse [op]: `rows.op(SORT) { sortedBy { it.key } }`. See the fine one. */
inline fun <T, R> T.op(op: CoarseOp, body: T.() -> R): R = com.ticksnick.op(op) { body() }

/** The chain form of the keyed coarse [op]; the key sees the receiver too: `rows.op(SORT, key = { size }) { sorted() }`. */
inline fun <T, R> T.op(op: CoarseOp, key: T.() -> Any?, body: T.() -> R): R = com.ticksnick.op(op, key = { key() }) { body() }

/** A key that throws is named rather than allowed to replace whatever the body threw. */
@PublishedApi
internal inline fun keyOf(key: () -> Any?): String =
    try {
        key().toString()
    } catch (e: Exception) {
        "(the key threw ${e.javaClass.simpleName})"
    }
