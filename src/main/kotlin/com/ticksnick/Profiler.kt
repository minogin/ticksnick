package com.ticksnick

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Slot value meaning "this thread is not inside any instrumented operation right now". */
internal const val NO_OP = -1

/**
 * Processors from which the sampler spins by default. Below this a spinning sampler holds a large
 * share of the machine - half of it on two - so it parks instead and samples less punctually.
 */
internal const val SPIN_MIN_PROCESSORS = 4

/**
 * Ceiling on how many distinct operations may be registered.
 *
 * Fixed rather than growable on purpose. The hot path indexes a counter array by operation id, and
 * a growable array would need either an indirection through a volatile reference or a copy that
 * races with the writers. A fixed ceiling costs 2 KB of counters per thread and nothing on the hot
 * path, and 256 hand-placed labels is far past what anyone will write.
 */
internal const val MAX_OPERATIONS = 256

/** Where the sampler counts slots that held no operation. */
internal const val NO_OP_INDEX = MAX_OPERATIONS

/**
 * How many threads the sampler can watch at once.
 *
 * A ceiling on the sampler's parallel arrays — 16 KB for the lot — and indexes are recycled when
 * threads die, so this limits *simultaneous* registered threads and not how many a process may
 * create. A pool of 32 that churns for a week never approaches it.
 *
 * **A thread past the ceiling is not sampled at all**, and the report says how many there were.
 * That keeps the walk bounded: without a bound, a workload that made threads in bulk could stall
 * the sampler outright. A bounded walk with a stated blind spot beats an unbounded one that
 * corrupts every number in the report.
 */
internal const val MAX_SLOTS = 1024

/** How deeply hand-placed `enter` labels may nest on one thread before the stack gives up. */
internal const val MAX_SPAN_DEPTH = 64

/**
 * One thread's slot: the id of the operation that thread is currently inside.
 *
 * The padding is not decoration. A slot is a 12-byte header plus a 4-byte field, so four of them
 * would share a 64-byte cache line, and every worker's write would invalidate its neighbours'
 * copies on other cores — tens of nanoseconds against operations that last twenty. Since all
 * slots have identical layout, the distance between two slots' `current` fields is exactly the
 * object size whatever order HotSpot chooses internally, so making the object large enough is
 * sufficient. 15 longs takes it past 128 bytes, which also defeats adjacent-line prefetching.
 * The cost is under 2 KB for the whole registry.
 */
@Suppress("unused")
@PublishedApi
internal class OpSlot(
    /**
     * This slot's position in the sampler's own parallel arrays, or -1 if the registry was full
     * when the thread arrived, in which case the thread is not sampled.
     *
     * The sampler needs somewhere to remember what it saw here last tick, and that somewhere must
     * not be this object: a write from the sampling thread would invalidate the owner's cache line
     * on every tick, which is exactly the false sharing all the padding below exists to prevent.
     * So the slot carries an index and the sampler keeps its own arrays.
     *
     * Assigned at construction and never changed. Released when the thread dies, and handed to the
     * next thread that arrives — the sampler notices, because the new thread's call counter will
     * not match what the old one left behind.
     */
    @JvmField val index: Int,
) {
    /**
     * Accessed opaquely, not volatile. A volatile store on x86 is not a store — it needs a
     * StoreLoad barrier, a lock-prefixed instruction costing tens of cycles, and the hook does two
     * of them per operation call. That comes to about 20 ns per call, against a hook meant to cost
     * single-digit nanoseconds.
     *
     * Opaque is the right strength. It forbids the JIT from eliminating or reordering the access —
     * a plain field would let dead-store elimination drop the entry write entirely once the body
     * inlines, silently blinding the profiler — while emitting no fence at all, so it compiles to
     * an ordinary MOV. That is the identical instruction a relaxed store in C or Rust produces.
     *
     * The ordering given up here is already given up on the read side: the sampler may see a value
     * a few nanoseconds stale. There is no reason for the write side to be stronger than the read
     * side.
     */
    @JvmField
    var current: Int = NO_OP

    /**
     * The owning thread, as an id rather than a reference — a reference would keep a dead thread
     * and everything it held reachable for as long as the slot lived.
     *
     * Written once at construction, which happens on the owning thread inside the ThreadLocal
     * supplier, so this costs nothing on the hot path and needs no publication guarantee beyond
     * the one final-field-like initialisation already gives the sampler.
     */
    @JvmField
    val threadId: Long = Thread.currentThread().threadId()

    /**
     * The owning thread, weakly, so the sampler can ask what state it is in.
     *
     * Weak and not strong, for the reason [threadId] is an id: a strong reference would keep a dead
     * thread and everything it held reachable for as long as the slot lived. `release()` is called
     * by hand, so a thread that dies without releasing would pin itself forever. A weak reference
     * costs the sampler one indirection per slot per tick and cannot leak at all. The sampler has a
     * core; the process does not have spare heap.
     *
     * Written once at construction on the owning thread, so nothing here reaches the hot path.
     */
    @JvmField
    val thread: java.lang.ref.WeakReference<Thread> =
        java.lang.ref.WeakReference(Thread.currentThread())

    @JvmField var p1: Long = 0
    @JvmField var p2: Long = 0
    @JvmField var p3: Long = 0
    @JvmField var p4: Long = 0
    @JvmField var p5: Long = 0
    @JvmField var p6: Long = 0
    @JvmField var p7: Long = 0
    @JvmField var p8: Long = 0
    @JvmField var p9: Long = 0
    @JvmField var p10: Long = 0
    @JvmField var p11: Long = 0
    @JvmField var p12: Long = 0
    @JvmField var p13: Long = 0
    @JvmField var p14: Long = 0
    @JvmField var p15: Long = 0

    /**
     * Calls per operation on this thread. Plain longs with a single writer — the owning thread —
     * so no fence and no contention.
     *
     * Counting is nearly free here because the expensive part of the hook is finding the
     * per-thread data, and by this point the hook already holds it. And the count is not a luxury: a
     * share on its own cannot tell "200M calls at 8 ns" from "1000 calls at 1.6 ms", and those
     * two want opposite fixes.
     *
     * Padded at both ends. The array is a separate object, so without padding two threads'
     * arrays could straddle a cache line at their boundary, and these are written on every single
     * operation call — exactly the traffic false sharing punishes hardest.
     */
    @JvmField
    val counts = LongArray(MAX_OPERATIONS + 2 * COUNT_PAD)

    /** [counts] as they stood at this thread's last coarse boundary. See [flushCounts]. */
    @JvmField
    val countsAtBoundary = LongArray(MAX_OPERATIONS)

    /**
     * Moves the fine calls made since this thread's last coarse boundary onto the node of [ctx], the
     * coarse execution they ran directly under. Dropped when [ctx] is null: no coarse parent.
     *
     * Called at every change of the innermost coarse execution, so each call lands on exactly one
     * path and the fine hook itself does nothing more. The cost is a pass over the registered fine
     * labels at each boundary, which is why the coarse floor rises with their number.
     *
     * Calls made before a session starts, inside an execution still open when it does, are counted
     * with it: the same bargain the span makes. See `Profiler.resetCoarse`.
     */
    @PublishedApi
    internal fun flushCounts(ctx: CoarseContext?) {
        val n = Profiler.registeredCount()
        val counts = counts
        val last = countsAtBoundary
        // Read before the node, which may be made here: its bytes are not the operation's.
        val session = Profiler.allocationSession
        val allocated = if (session != 0) AllocationClock.currentBytes() else 0L
        val node = if (ctx == null) null else nodeOf(ctx)
        if (session != 0) flushAllocated(node, session, allocated)
        if (node == null) {
            System.arraycopy(counts, COUNT_PAD, last, 0, n)
            return
        }
        val calls = node.calls ?: LongArray(MAX_OPERATIONS).also { node.calls = it }
        // No branch in the loop, so the JIT can vectorize it.
        for (id in 0 until n) {
            val c = counts[id + COUNT_PAD]
            calls[id] += c - last[id]
            last[id] = c
        }
    }

    /** The thread's allocated bytes at its last coarse boundary. */
    private var allocatedAtBoundary = 0L

    /** The session [allocatedAtBoundary] was read in, so a new one starts from there and not from the thread's birth. */
    private var allocatedSession = 0

    /** Moves the bytes this thread allocated since its last coarse boundary onto [node]. See [flushCounts]. */
    private fun flushAllocated(node: CoarsePath?, session: Int, now: Long) {
        if (allocatedSession == session) {
            if (node != null) node.allocated += now - allocatedAtBoundary
        } else {
            allocatedSession = session
        }
        allocatedAtBoundary = now
    }

    fun count(id: Int) {
        counts[id + COUNT_PAD]++
    }

    /** For a label placed around [n] units of work rather than one. See `op(id, times)`. */
    fun count(id: Int, n: Int) {
        counts[id + COUNT_PAD] += n
    }

    /**
     * The stack of operations this thread has entered without leaving, for the non-lexical form.
     *
     * Only `enter`/`exit` touch it — `op(id) { }` keeps its predecessor in a local and restores it
     * in a `finally`, which the compiler writes and nothing can leak past. So depth here is exactly
     * the number of labels placed by hand and not yet closed, which is what makes it a balance
     * check rather than a general call-depth counter.
     *
     * A plain array with a single writer, and read from the sampling thread only when the session
     * ends. Sixty-four is far past any sane nesting of hand-placed labels; past that the label is
     * still set correctly and only the restoring is given up, which is recorded rather than thrown.
     */
    @JvmField
    val stack = IntArray(MAX_SPAN_DEPTH)

    @JvmField
    var depth: Int = 0

    /** Hand-placed labels that overflowed the stack, so exit could not restore what came before. */
    @JvmField
    internal var overflows: Long = 0

    /**
     * The coarse execution this thread is currently inside, or null.
     *
     * Where the two tiers meet. The sampler reads this and [current] in the same visit, so every
     * sample carries the pair *(which physical operation, under which logical one)* — which is the
     * cross-tabulation, and neither tier can produce it alone.
     *
     * Accessed opaquely for the identical reason [current] is: no fence on either side, and the
     * publication safety comes from [CoarseContext]'s final fields rather than from ordering here.
     * A stack is not needed beside it — the context chains to its parent, so restoring on exit is
     * one field write.
     */
    @JvmField
    var context: CoarseContext? = null

    /**
     * This thread's calling-context tree of coarse executions, or null if it has entered none.
     *
     * Lazily allocated so that a program using only the fine tier pays nothing for this — not the
     * root, not the histograms. The span fields are written by the owning thread alone, read when
     * the report is taken. See [CoarsePath].
     */
    @JvmField
    internal var coarseRoot: CoarsePath? = null

    /**
     * The node a new execution of [type] under [parent] records into: one array read when the
     * parent is this thread's own, which is the common case.
     *
     * Not inline, deliberately: it is called from [op], which is, and an inline function that
     * drags a tree walk into every call site would bloat exactly the code the JIT needs to keep
     * small.
     */
    @PublishedApi
    internal fun pathFor(parent: CoarseContext?, type: Int): CoarsePath? {
        if (type < 0 || type >= MAX_COARSE_TYPES) return null
        val root = coarseRoot ?: CoarsePath(CoarsePath.ROOT, null, this).also { coarseRoot = it }
        val under = if (parent == null) root else {
            val p = parent.path
            if (p != null && p.owner === this) p else resolve(root, parent)
        }
        // Past the depth bound everything goes to one overflow node under the root, so recursion
        // cannot grow the tree without limit - and stays there: an execution opened under the
        // overflow node is still past the bound, not the start of a new tree beneath it. The
        // sampler's inclusive walk stops at the same depth.
        return if (under.depth >= MAX_COARSE_DEPTH || under.past) root.deeper(type) else under.child(type)
    }

    /**
     * The node for [ctx]'s path in this thread's own tree, when the context was made on another
     * thread: a context borrowed from a pool's caller, or closed by a thread that did not open it.
     * Walks the chain once and creates the path; rare, and bounded by [MAX_COARSE_DEPTH].
     */
    private fun resolve(root: CoarsePath, ctx: CoarseContext): CoarsePath {
        val types = IntArray(MAX_COARSE_DEPTH)
        var n = 0
        var c: CoarseContext? = ctx
        while (c != null && n < MAX_COARSE_DEPTH) {
            types[n++] = c.type
            c = c.parent
        }
        if (c != null) return root.deeper(ctx.type)
        var node = root
        while (n > 0) node = node.child(types[--n])
        return node
    }

    /**
     * Records one completed coarse execution [ctx] lasting [nanos] into its node, and returns the
     * node, for the keyed form to keep the slowest in; null for a context made outside the hook.
     *
     * Not inline, for the reason [pathFor] is not. A handful of plain writes with a single writer.
     */
    @PublishedApi
    internal fun recordSpan(ctx: CoarseContext, nanos: Long): CoarsePath? {
        val node = nodeOf(ctx) ?: return null
        node.record(nanos)
        return node
    }

    /** This thread's node for [ctx]'s path; null for a context made outside the hook. */
    private fun nodeOf(ctx: CoarseContext): CoarsePath? {
        val p = ctx.path ?: return null
        return if (p.owner === this) p else {
            val root = coarseRoot ?: CoarsePath(CoarsePath.ROOT, null, this).also { coarseRoot = it }
            resolve(root, ctx)
        }
    }

    /** How deeply this thread is nested in coarse executions. Zero when it is inside none. */
    internal fun coarseDepth(): Int = context?.let { it.depth + 1 } ?: 0

    fun countOf(id: Int): Long = counts[id + COUNT_PAD]

    internal fun resetCounts() = counts.fill(0L)

    companion object {
        /** 16 longs — 128 bytes — of padding at each end of the counter array. */
        const val COUNT_PAD = 16

        /**
         * Static final so the JIT can constant-fold it and intrinsify the accessor down to a bare
         * memory instruction. A non-final handle would leave a real method call in the hot path.
         */
        @JvmStatic
        val CURRENT: VarHandle =
            MethodHandles.lookup().findVarHandle(OpSlot::class.java, "current", Int::class.javaPrimitiveType)

        /**
         * For the sampler's read of somebody else's call counter. The owner keeps writing a plain
         * `counts[id]++` — the hot path is not touched — while the reader goes through this, so
         * the JIT cannot hoist the read out of the sampling loop and hand back a value from a
         * minute ago. Coherence does the rest; a few nanoseconds stale is the same bargain the
         * slot itself already makes.
         */
        @JvmStatic
        val COUNTS: VarHandle = MethodHandles.arrayElementVarHandle(LongArray::class.java)

        /** The coarse half of the pair. Static final for the same constant-folding reason as [CURRENT]. */
        @JvmStatic
        val CONTEXT: VarHandle =
            MethodHandles.lookup().findVarHandle(OpSlot::class.java, "context", CoarseContext::class.java)
    }
}

/** Marks this thread as inside operation [id]. No fence — see [OpSlot.current]. */
@PublishedApi
internal fun OpSlot.setOpaque(id: Int) = OpSlot.CURRENT.setOpaque(this, id)

/** Reads the slot as the sampler does: no fence, possibly a few nanoseconds stale. */
@PublishedApi
internal fun OpSlot.getOpaque(): Int = OpSlot.CURRENT.getOpaque(this) as Int

/** How many times this thread has entered [id], read from another thread. See [OpSlot.COUNTS]. */
internal fun OpSlot.countOpaque(id: Int): Long = OpSlot.COUNTS.getOpaque(counts, id + OpSlot.COUNT_PAD) as Long

/** The coarse execution this thread is inside, or null. No fence — see [OpSlot.context]. */
@PublishedApi
internal fun OpSlot.contextOpaque(): CoarseContext? = OpSlot.CONTEXT.getOpaque(this) as CoarseContext?

/** Publishes [ctx] as this thread's current coarse execution. See [OpSlot.context]. */
@PublishedApi
internal fun OpSlot.setContextOpaque(ctx: CoarseContext?) = OpSlot.CONTEXT.setOpaque(this, ctx)

/**
 * The slot registry. A thread gets its slot from a ThreadLocal and is added to the walk list on
 * first access.
 */
object Profiler {
    /**
     * The walk list, as a fixed array indexed by slot index rather than a growable list.
     *
     * Not a `CopyOnWriteArrayList`: that copies the whole array on every add and every remove, so
     * a process that creates threads in bulk pays O(n) per thread and O(n²) over the run, and the
     * sampler walks a list as long as the number of live threads, every millisecond, with no
     * ceiling. Virtual threads make both unbounded: a million short-lived ones is a million array
     * copies and a tick that cannot finish.
     *
     * An `AtomicReferenceArray` gives the same safe publication — each element is a volatile read
     * and write — while registration and release are a single store. The walk is bounded by
     * [MAX_SLOTS] rather than by how many threads the process has made, and in practice by
     * [ceiling], which is how far the indexes have ever reached.
     *
     * **What it costs:** a thread that arrives past the ceiling has no index and is not in the walk
     * at all, and the report says how many such threads there were — see [Run.untrackedSlots].
     * A bounded, stated blind spot in place of an unbounded walk, which would corrupt every number
     * rather than one.
     */
    private val slotByIndex = java.util.concurrent.atomic.AtomicReferenceArray<OpSlot?>(MAX_SLOTS)

    /**
     * One past the highest slot index ever handed out, so the walk skips the tail it has never
     * used. Only ever rises, and it is bounded by [MAX_SLOTS]: with eight worker threads the
     * sampler reads about nine entries per tick, not 1024.
     */
    private val ceiling = AtomicInteger(0)

    /** How far the walk has to go. See [ceiling]. */
    internal fun slotCeiling(): Int = ceiling.get()

    /** The slot at [i], or null if that index is free right now. See [slotByIndex]. */
    internal fun slotAt(i: Int): OpSlot? = slotByIndex.get(i)

    /** Runs [action] over every live slot. Allocation-free, so the sampler may call it per tick. */
    internal inline fun forEachSlot(action: (OpSlot) -> Unit) {
        for (i in 0 until slotCeiling()) action(slotAt(i) ?: continue)
    }

    /**
     * Slot indexes in use, and the ones given back by threads that have died.
     *
     * Recycled rather than ever-growing, because a pool that creates and destroys threads for the
     * life of a process would otherwise walk off the end of the sampler's arrays. Exhausting the
     * ceiling is not fatal: the thread's labels still work, the thread is not sampled, and the
     * report says how many such threads there were.
     */
    private val freeIndexes = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val nextIndex = AtomicInteger(0)

    private fun takeIndex(): Int =
        freeIndexes.poll() ?: nextIndex.getAndIncrement().let { if (it < MAX_SLOTS) it else -1 }

    private val local: ThreadLocal<OpSlot> = ThreadLocal.withInitial {
        val idx = takeIndex()
        val s = OpSlot(idx)
        if (idx >= 0) {
            slotByIndex.set(idx, s)
            // Raised after the slot is published, never before, so the sampler cannot read a null
            // inside the range it has been told to walk.
            ceiling.getAndUpdate { if (idx < it) it else idx + 1 }
        }
        s
    }

    private val names = arrayOfNulls<String>(MAX_OPERATIONS)
    private val ids = ConcurrentHashMap<String, Int>()
    private val nextId = AtomicInteger(0)

    /** Calls from threads that have already exited, folded in so a report does not lose them. */
    private val retiredCounts = LongArray(MAX_OPERATIONS)

    /**
     * How many threads that have already exited had called each operation at least once.
     *
     * Counted at the moment a slot is folded away, because that is the last point at which the
     * thread's own per-operation counters still exist: [retiredCounts] sums them and the identity
     * of who contributed is gone one instruction later. One increment per exiting thread per
     * operation it touched, on the thread's way out and never on the hot path.
     */
    private val retiredThreads = IntArray(MAX_OPERATIONS)

    @Volatile
    private var sampler: Sampler? = null
    /** This session's number if it counts allocated bytes, else 0. Read at every coarse boundary. */
    @Volatile
    @JvmField
    internal var allocationSession = 0

    private var sessions = 0

    private var allocationsMissing = false

    /** Whether this JVM can report allocated bytes. A field, so that a test can be a JVM that cannot. */
    @JvmField
    internal var allocationsAvailable = AllocationClock.available

    private var startedAt: Long = 0
    /** The CPU clock over the session. See [ClockReport]. */
    private var clock: ClockProbe? = null

    /** The JVM's collection-pause count when the current session began, or -1. See [GcPauseClock]. */
    private var gcAtStart: Long = -1

    /**
     * Id for an operation name, assigned on first call and stable thereafter. Idempotent, so it is
     * safe to call from a static initialiser, a lazy holder, or once per call site.
     *
     * Do this at startup, not on the hot path — it takes a map lookup, which is many times the cost
     * of the hook it feeds.
     */
    @JvmStatic
    @JvmName("registerFine")
    fun registerFine(name: String): FineOp = FineOp(registerFineId(name))

    /**
     * A family of [arity] fine operations under one name, indexed on the hot path: `level[depth]`.
     *
     * The ids are consecutive, reserved here in one step, and the rows in the report are
     * `name[0]` to `name[arity - 1]`. The index is a small number known at registration - a depth,
     * a type, a size bucket - and not a value: the family comes out of the same [MAX_OPERATIONS]
     * budget as everything else. Idempotent for the same name and arity; the same name with a
     * different arity is an error, since the ids were already handed out.
     */
    @JvmStatic
    fun registerFine(name: String, arity: Int): FineOps =
        FineOps(reserve(name, arity, ids, arities, nextId, names, MAX_OPERATIONS, "operations"), arity)

    /**
     * A family of fine operations indexed by an enum: `byKind[kind]`, rows `name[COMPILE]` and so
     * on. As the integer family, with the enum's constants as the index and their names in the
     * report; the hot path is `base + ordinal`, and an index of the wrong type does not compile.
     */
    @JvmSynthetic
    inline fun <reified E : Enum<E>> registerFine(name: String): FineEnumOps<E> =
        FineEnumOps(registerFineFamily(name, enumValues<E>().map { it.name }))

    /** The enum family for Java, which has no reified type parameters: `registerFine("byKind", Kind.class)`. */
    @JvmStatic
    fun <E : Enum<E>> registerFine(name: String, type: Class<E>): FineEnumOps<E> =
        FineEnumOps(registerFineFamily(name, type.enumConstants.map { it.name }))

    @PublishedApi
    internal fun registerFineFamily(name: String, labels: List<String>): Int =
        reserve(name, labels.size, ids, arities, nextId, names, MAX_OPERATIONS, "operations", labels)

    /** The raw id, for the internals that index arrays by it. See [registerFine]. */
    internal fun registerFineId(name: String): Int = reserve(name, 1, ids, arities, nextId, names, MAX_OPERATIONS, "operations")

    /** Arity per registered name, so a second registration can be checked against the first. */
    private val arities = ConcurrentHashMap<String, Int>()
    private val coarseArities = ConcurrentHashMap<String, Int>()

    /**
     * [arity] consecutive ids for [name], once. A single operation is a family of one, and keeps
     * its bare name; a family's members are named `name[i]`, or `name[label]` given [labels].
     */
    private fun reserve(
        name: String, arity: Int, ids: ConcurrentHashMap<String, Int>, arities: ConcurrentHashMap<String, Int>,
        next: AtomicInteger, names: Array<String?>, max: Int, what: String, labels: List<String>? = null,
    ): Int {
        Logo.once()
        require(arity > 0) { "a family needs at least one member, not $arity" }
        val base = ids.computeIfAbsent(name) {
            val b = next.getAndAdd(arity)
            check(b + arity <= max) { "more than $max distinct $what registered" }
            if (arity == 1 && labels == null) names[b] = name
            else for (i in 0 until arity) names[b + i] = "$name[${labels?.get(i) ?: i}]"
            arities[name] = arity
            b
        }
        val registered = arities[name]
        check(registered == arity) {
            "$name was registered with arity $registered and is asked for again with arity $arity"
        }
        return base
    }

    internal fun nameOf(id: Int): String = if (id in 0 until MAX_OPERATIONS) names[id] ?: "op#$id" else "op#$id"

    /** What [op] was registered as. */
    internal fun nameOf(op: FineOp): String = nameOf(op.id)

    /** What [op] was registered as. */
    internal fun nameOf(op: CoarseOp): String = coarseNameOf(op.id)

    /**
     * How many operations have been registered, never more than there is room for.
     *
     * Clamped because [registerFine] increments before it checks, so the 257th registration leaves
     * the counter at 257 on its way out through an exception. That exception is an
     * `IllegalStateException` with a clear message, so catching it is a reasonable thing for a
     * caller to do — and without the clamp the *next* `stop()` would walk `0 until 257` across
     * arrays of 256 and die with an index error a long way from the cause.
     */
    internal fun registeredCount(): Int = min(nextId.get(), MAX_OPERATIONS)

    private val coarseNames = arrayOfNulls<String>(MAX_COARSE_TYPES)
    private val coarseIds = ConcurrentHashMap<String, Int>()
    private val nextCoarseId = AtomicInteger(0)

    /**
     * Id for a coarse operation, in an id space of its own.
     *
     * Separate from [registerFine] rather than sharing one space, because the same name can legitimately
     * carry both labels: the fine one measures the operation's *self* time, the coarse one its
     * duration and everything nested under it, and comparing the two is a cross-check worth having.
     */
    @JvmStatic
    @JvmName("registerCoarse")
    fun registerCoarse(name: String): CoarseOp = CoarseOp(registerCoarseId(name))

    /** A family of coarse operations, indexed on the hot path. The coarse twin of [registerFine] with an arity. */
    @JvmStatic
    fun registerCoarse(name: String, arity: Int): CoarseOps = CoarseOps(
        reserve(name, arity, coarseIds, coarseArities, nextCoarseId, coarseNames, MAX_COARSE_TYPES, "coarse operations"),
        arity,
    )

    /** A family of coarse operations indexed by an enum. The coarse twin of the enum [registerFine]. */
    @JvmSynthetic
    inline fun <reified E : Enum<E>> registerCoarse(name: String): CoarseEnumOps<E> =
        CoarseEnumOps(registerCoarseFamily(name, enumValues<E>().map { it.name }))

    /** The enum family for Java. The coarse twin of the `Class` [registerFine]. */
    @JvmStatic
    fun <E : Enum<E>> registerCoarse(name: String, type: Class<E>): CoarseEnumOps<E> =
        CoarseEnumOps(registerCoarseFamily(name, type.enumConstants.map { it.name }))

    @PublishedApi
    internal fun registerCoarseFamily(name: String, labels: List<String>): Int =
        reserve(name, labels.size, coarseIds, coarseArities, nextCoarseId, coarseNames, MAX_COARSE_TYPES, "coarse operations", labels)

    /** The raw id, for the internals that index arrays by it. See [registerCoarse]. */
    internal fun registerCoarseId(name: String): Int =
        reserve(name, 1, coarseIds, coarseArities, nextCoarseId, coarseNames, MAX_COARSE_TYPES, "coarse operations")

    internal fun coarseNameOf(type: Int): String =
        if (type in 0 until MAX_COARSE_TYPES) coarseNames[type] ?: "coarse#$type" else "coarse#$type"

    /** How many coarse types have been registered. Clamped for the reason [registeredCount] is. */
    internal fun coarseCount(): Int = min(nextCoarseId.get(), MAX_COARSE_TYPES)

    /**
     * Span statistics from threads that have already exited, so a pool that recycles does not lose
     * the executions its retired threads measured.
     */
    private var retiredCoarse = CoarsePath(CoarsePath.ROOT, null, null)

    /**
     * Threads that ran an execution of each coarse type and have since exited.
     *
     * The coarse half of [threadsOf], and session-scoped for free: [resetCoarse] clears it at the
     * start of every session, where the fine side has to snapshot and subtract because its counters
     * are cumulative.
     */
    private val retiredCoarseThreads = IntArray(MAX_COARSE_TYPES)

    /** Folds one departing thread's tree into [retiredCoarse]. */
    private fun retire(root: CoarsePath?) {
        if (root == null) return
        synchronized(retiredLock) {
            val ran = BooleanArray(MAX_COARSE_TYPES)
            typesRan(root, ran)
            for (t in 0 until MAX_COARSE_TYPES) if (ran[t]) retiredCoarseThreads[t]++
            retiredCoarse.mergeFrom(root)
        }
    }

    private val retiredLock = Any()

    /** Marks in [ran] every type with a completed execution anywhere under [node]. */
    private fun typesRan(node: CoarsePath, ran: BooleanArray) {
        if (node.type >= 0 && node.count > 0) ran[node.type] = true
        for (c in node.below()) typesRan(c, ran)
    }

    /**
     * Every thread's tree and the retired one, merged by path. Taken once, when the report is.
     * Allocates and walks every slot, which is why it is not something the sampler does per tick.
     */
    internal fun coarseTree(): CoarsePath {
        val out = CoarsePath(CoarsePath.ROOT, null, null)
        synchronized(retiredLock) { out.mergeFrom(retiredCoarse) }
        forEachSlot { s -> s.coarseRoot?.let { out.mergeFrom(it) } }
        return out
    }

    /**
     * Every completed coarse execution this session knows about, live threads and retired ones.
     *
     * Taken once, when the report is. Allocates and walks every slot, which is why it is not
     * something the sampler does per tick.
     */
    internal fun coarseTotals(): Array<CoarseAgg?> = coarseTotals(coarseTree())

    /** The per-type totals of a merged tree: every node of a type, summed. */
    internal fun coarseTotals(tree: CoarsePath): Array<CoarseAgg?> {
        val out = arrayOfNulls<CoarseAgg>(MAX_COARSE_TYPES)
        fun walk(node: CoarsePath) {
            if (node.type >= 0 && node.count > 0) {
                val into = out[node.type] ?: CoarseAgg(node.type).also { out[node.type] = it }
                into.mergeFrom(node)
            }
            for (c in node.below()) walk(c)
        }
        walk(tree)
        return out
    }

    /**
     * Clears every thread's span statistics, so a report describes one session.
     *
     * The fine counters solve this by snapshotting at the start and subtracting — see
     * `Sampler.callsAtStart` — which a minimum and a histogram cannot be made to do. Resetting is
     * the honest alternative, and it costs the same thing everywhere else here costs: an execution
     * that began before [start] and ends inside the session is counted in full. Two instructions
     * wide, in the direction of reporting a span that is real but started slightly early.
     *
     * **Spans recorded with no session running are discarded when the next session starts.** Called
     * from the sampling thread immediately before its first tick, beside the snapshot of the call
     * counters and for the identical reason: *no execution is counted whose samples could not have
     * been taken.* With the reset anywhere else, a warm-up that runs on its own threads before the
     * session would be counted into every coarse figure.
     */
    internal fun resetCoarse() {
        synchronized(retiredLock) {
            retiredCoarse = CoarsePath(CoarsePath.ROOT, null, null)
            for (t in 0 until MAX_COARSE_TYPES) retiredCoarseThreads[t] = 0
        }
        forEachSlot { s -> s.coarseRoot?.reset() }
    }

    /**
     * Distinct threads that ran an execution of coarse type [t]: live ones plus those that exited.
     *
     * The `Pool` column for a coarse row, and exact for the same reason the fine one is - it counts
     * threads that *ran* the operation rather than threads a sample happened to catch inside it.
     */
    internal fun coarseThreadsOf(t: Int): Int =
        synchronized(retiredLock) { retiredCoarseThreads[t] }.let { retired ->
            var live = 0
            val ran = BooleanArray(MAX_COARSE_TYPES)
            forEachSlot { s ->
                val root = s.coarseRoot ?: return@forEachSlot
                ran.fill(false)
                typesRan(root, ran)
                if (ran[t]) live++
            }
            retired + live
        }

    /** Threads inside a coarse execution right now, which at the end of a session is a leak. */
    internal fun openContexts(): Int {
        var n = 0
        forEachSlot { if (it.context != null) n++ }
        return n
    }

    /** The calling thread's slot, registering it on first call. */
    @PublishedApi
    internal fun slot(): OpSlot = local.get()

    /**
     * Enters operation [id] until a matching [exit], for a boundary that is not a block.
     *
     * **`op(id) { }` is the form to reach for first.** It is inline, its `finally` is written by the
     * compiler, and it cannot leak. This exists because almost nothing in third-party code can be
     * wrapped in a block: the boundary a library offers is often two callbacks, one before and one
     * after. The two forms nest in either order.
     *
     * What it costs, and it is not the hook: **there is no `finally` here, so a body that throws
     * leaves the label set** and every later sample on this thread is billed to it. No error, no
     * warning, a plausible wrong number — the contaminating direction. A library's "after"
     * callback is often not inside a `finally`, so this is not a hypothetical. See [expectBalanced].
     */
    @JvmStatic
    @JvmName("enterFine")
    fun enter(op: FineOp) {
        val s = local.get()
        if (s.depth < MAX_SPAN_DEPTH) s.stack[s.depth++] = s.getOpaque() else s.overflows++
        s.setOpaque(op.id)
        s.count(op.id)
    }

    /** The same, for a boundary around [times] units of the operation rather than one. See `op(op, times)`. */
    @JvmStatic
    @JvmName("enterFine")
    fun enter(op: FineOp, times: Int) {
        val s = local.get()
        if (s.depth < MAX_SPAN_DEPTH) s.stack[s.depth++] = s.getOpaque() else s.overflows++
        s.setOpaque(op.id)
        s.count(op.id, times)
    }

    /**
     * Leaves [op], restoring what the thread was inside before it.
     *
     * **It takes what you are closing, and that is not symmetry for its own sake.** A no-argument
     * form could not tell a correct unwind from a crossed one — `enter(a); enter(b);
     * exit(); exit()` closes `b` then `a` whether or not that is what the code meant. Naming the
     * operation lets the library check, and a mismatch is the same category of fault as a leak: a
     * label ends up covering work that was never inside it, and the number that comes out is
     * plausible rather than obviously wrong.
     *
     * A mismatch is reported and counted, never thrown — throwing would turn a measurement problem
     * into a crash in somebody else's process — and it still unwinds, so one mistake does not
     * cascade into every label after it.
     */
    @JvmStatic
    @JvmName("exitFine")
    fun exit(op: FineOp) {
        val s = local.get()
        val current = s.getOpaque()
        if (current != op.id) mismatch(nameOf(op), if (current < 0) "no operation" else nameOf(current))
        s.setOpaque(if (s.depth > 0) s.stack[--s.depth] else NO_OP)
    }

    /**
     * Enters an execution of coarse operation [op] until a matching [exit].
     *
     * For the boundary that is two callbacks rather than a block, which is most of what third-party
     * code offers. It carries the same hazard the fine form does and for the same reason: **there is
     * no `finally` here**, so a body that throws leaves the context open and everything the thread
     * does afterwards is billed to it. See [expectBalanced].
     */
    @JvmStatic
    @JvmName("enterCoarse")
    fun enter(op: CoarseOp) {
        val s = local.get()
        val parent = s.contextOpaque()
        s.flushCounts(parent)
        s.setContextOpaque(CoarseContext(op.id, parent, System.nanoTime(), s.pathFor(parent, op.id)))
    }

    /**
     * Closes the innermost coarse execution on this thread and records its span.
     *
     * Checked against [op], for the reason the fine form gives. Closing when nothing is open is
     * reported rather than thrown: it means the caller closed something it never opened, which is a
     * measurement problem and not a reason to stop somebody else's program.
     */
    @JvmStatic
    @JvmName("exitCoarse")
    fun exit(op: CoarseOp) {
        val s = local.get()
        val ctx = s.contextOpaque()
        if (ctx == null) {
            mismatch(nameOf(op), "no coarse execution")
            return
        }
        if (ctx.type != op.id) mismatch(nameOf(op), coarseNameOf(ctx.type))
        s.setContextOpaque(ctx.parent)
        ctx.markClosed()
        s.recordSpan(ctx, System.nanoTime() - ctx.startNanos)
        s.flushCounts(ctx)
    }

    /** One scope per operation, made once, so opening one allocates nothing. */
    private val fineScopes = Array(MAX_OPERATIONS) { FineScope(it) }
    private val coarseScopes = Array(MAX_COARSE_TYPES) { CoarseScope(it) }

    /**
     * Enters [op] and returns the [FineScope] that leaves it, for Java's try-with-resources:
     * `try (var s = Profiler.fine(PARSE)) { parse(line); }`. In Kotlin, `op(PARSE) { }` does the same.
     */
    @JvmStatic
    @JvmName("fine")
    fun fine(op: FineOp): FineScope {
        val scope = fineScopes[op.id]
        enter(op)
        return scope
    }

    /** The same, for a block of [times] units of the operation. See `op(op, times)`. */
    @JvmStatic
    @JvmName("fine")
    fun fine(op: FineOp, times: Int): FineScope {
        val scope = fineScopes[op.id]
        enter(op, times)
        return scope
    }

    /** Enters an execution of [op] and returns the [CoarseScope] that closes it. The coarse twin of [fine]. */
    @JvmStatic
    @JvmName("coarse")
    fun coarse(op: CoarseOp): CoarseScope {
        val scope = coarseScopes[op.id]
        enter(op)
        return scope
    }

    /** One closing call that did not match what was open. Counted with the leaks, and fatal under strict. */
    private fun mismatch(closed: String, open: String) {
        imbalances.incrementAndGet()
        sampler?.let { if (it.strict) it.fail(mismatchMessage(closed, open)) }
    }

    /** How many hand-placed labels this thread has entered and not left. */
    internal fun depth(): Int = local.get().depth

    /**
     * Asserts that this thread has closed every label it opened, and reports whether it had.
     *
     * Call it wherever the caller knows the thread should be
     * quiescent — between requests, between iterations, at the end of a task — and a leak surfaces
     * there instead of quietly contaminating everything that follows.
     *
     * Resets the stack, so one leak is one report rather than every subsequent check failing too.
     */
    @JvmStatic
    fun expectBalanced(): Boolean {
        val s = local.get()
        // Read before anything is reset, and defensively: a slot can hold NO_OP with a depth above
        // zero if the leak was an `exit` without its `enter` rather than the other way round.
        val ctx = s.contextOpaque()
        val id = s.getOpaque()
        val leaked = when {
            // The coarse half is named first when both leaked, because a coarse context left open
            // collects a whole request's worth of samples rather than one label's.
            ctx != null -> coarseNameOf(ctx.type)
            s.depth > 0 && id >= 0 -> nameOf(id)
            s.depth > 0 -> "an unnamed label"
            else -> return true
        }
        imbalances.incrementAndGet()
        s.flushCounts(ctx)
        s.setContextOpaque(null)
        s.depth = 0
        s.setOpaque(NO_OP)
        // Fatal under strict, and it is the only thing that is. Resetting the slot stops the leak
        // spreading, but it cannot give back the samples already billed to the wrong operation —
        // so continuing means reporting a share that was manufactured by a bug.
        sampler?.let { if (it.strict) it.fail(leakMessage(leaked)) }
        return false
    }

    /**
     * Labels left open at a point the caller said should be quiescent. See [expectBalanced].
     *
     * For the life of the process, because [expectBalanced] is a check a caller may make with no
     * session running at all. What a [Run] carries is this minus [imbalancesAtStart], for the
     * same reason `Sampler.callsAtStart` exists: every number in a report is about one session.
     */
    private val imbalances = AtomicInteger(0)

    /**
     * [imbalances] as it stood when the current session began.
     *
     * Without it a second session in the same process reports the first one's leaks as well as its
     * own, and prints *"N labels were still open at a point the caller said should be quiescent"*
     * about a session in which nothing leaked — which is exactly the failure the message warns
     * about, arriving through the warning itself. An A/B comparison that starts and stops a session
     * per arm in one JVM hits this on every round after the first.
     */
    private var imbalancesAtStart = 0

    /** [untrackedSlots] as it stood when the current session began. See [Run.untrackedSlots]. */
    private var untrackedAtStart = 0

    /** [reclaimedSlots] as it stood when the current session began. */
    private var reclaimedAtStart = 0

    /** Threads still inside a hand-placed label right now, which at the end of a session is a leak. */
    internal fun openSpans(): Int {
        var n = 0
        forEachSlot { if (it.depth > 0) n++ }
        return n
    }

    /**
     * Drops the calling thread's slot. A thread that has finished must not stay in the walk list:
     * its slot reads empty forever, inflating the sampler's denominator and — worse for occupancy
     * work — counting a dead thread as an idle one. A registry that only ever grows is also a
     * plain leak in anything long-lived with a thread pool that recycles.
     *
     * Its call counts are folded into the retired totals, or a pool that recycles threads would
     * silently lose everything the retired ones did — but *after* the slot leaves the walk list,
     * not before. [callsOf] sums the retired totals and the live slots, so a reader landing between
     * the two lines sees this thread's calls in both halves and counts them twice. That inflates
     * calls, deflates the implied duration and the bound built on it, and can push a perfectly good
     * label under the floor and print an accusation.
     *
     * Both orders race; only this one races in a safe direction. Losing the counts for the length
     * of one read makes an operation look *longer* than it is, which cannot manufacture an
     * accusation, and the window is two instructions wide either way.
     */
    @JvmStatic
    fun release() {
        val s = local.get()
        if (s.index >= 0) slotByIndex.set(s.index, null)
        synchronized(retiredCounts) {
            for (id in 0 until MAX_OPERATIONS) {
                val n = s.countOf(id)
                if (n > 0) {
                    retiredCounts[id] += n
                    retiredThreads[id]++
                }
            }
        }
        retire(s.coarseRoot)
        // Removed from the walk list first, so the sampler cannot be reading this slot at the
        // moment its index is handed to somebody else.
        if (s.index >= 0) freeIndexes.add(s.index)
        local.remove()
    }

    /** Threads that died without calling [release] and had to be reclaimed. See [reclaimDeadSlots]. */
    private val reclaimed = AtomicInteger(0)

    internal fun reclaimedSlots(): Int = reclaimed.get()

    /**
     * Reclaims the slots of threads that exited without calling [release].
     *
     * `release()` is the precise way and the documented one, but it is the kind of thing a user
     * forgets exactly once and then cannot see: without this, the slot would stay in the walk and
     * hold its index forever.
     *
     * A thread that is no longer alive is gone - no handshake, no thread scan, and it cannot come
     * back - and a cleared weak reference is one already collected. Reclaiming is then
     * the same three steps as [release] in the same order: leave the walk first, fold the counts
     * second, hand the index back last, so a concurrent [callsOf] undercounts for two instructions
     * rather than double-counting.
     *
     * Called once a second from the sampler, never per tick — this walks the whole ceiling and
     * touches a weak reference per slot, which is not something to spend on a 1 ms budget.
     *
     * The sampler skips a thread that has exited, so a dead slot is never counted as an idle thread
     * in the second before it is reclaimed, and nothing is reported. The count is kept only for the
     * duty cycle's explanation of a run with no CPU window. `release()` is the prompt way, not the
     * required one.
     */
    internal fun reclaimDeadSlots() {
        for (i in 0 until slotCeiling()) {
            val s = slotByIndex.get(i) ?: continue
            // Exited is enough: a thread that is not alive cannot write to its slot again, and Java
            // makes everything it wrote visible to whoever sees isAlive() return false.
            val owner = s.thread.get()
            if (owner != null && owner.isAlive) continue
            if (!slotByIndex.compareAndSet(i, s, null)) continue
            synchronized(retiredCounts) {
                for (id in 0 until MAX_OPERATIONS) {
                    val n = s.countOf(id)
                    if (n > 0) {
                        retiredCounts[id] += n
                        retiredThreads[id]++
                    }
                }
            }
            retire(s.coarseRoot)
            freeIndexes.add(i)
            reclaimed.incrementAndGet()
        }
    }

    /** Slots that arrived after the ceiling and are therefore not sampled. */
    internal fun untrackedSlots(): Int = (nextIndex.get() - MAX_SLOTS).coerceAtLeast(0)

    /**
     * The live slots, as a snapshot. Allocates, so it is for callers outside the tick loop — the
     * sampler and the duty walk use [forEachSlot], which does not.
     */
    internal fun slots(): List<OpSlot> = buildList { forEachSlot { add(it) } }

    /**
     * Distinct threads that have called an operation: live ones plus those that have exited.
     *
     * The denominator of the `Concurrency / Threads` column, and the reason it is a count of
     * *callers* rather than of samples: a thread that entered the operation once and was never
     * caught by the sampler still ran it, and an operation that only ever runs on one thread is a
     * fact about the code rather than about the run. Free, because the per-operation call counters
     * it reads are written by the hook anyway - nothing here is on the hot path.
     */
    internal fun threadsOf(id: Int): Int =
        synchronized(retiredCounts) { retiredThreads[id] }.let { retired ->
            var live = 0
            forEachSlot { if (it.countOf(id) > 0) live++ }
            retired + live
        }

    /** Total calls of an operation: live threads plus those that have already exited. */
    internal fun callsOf(id: Int): Long =
        synchronized(retiredCounts) { retiredCounts[id] }.let { retired ->
            var live = 0L
            forEachSlot { live += it.countOf(id) }
            retired + live
        }

    /**
     * Starts sampling. One sampler at a time.
     *
     * [strict] stops the session if a label leaks or is closed out of order, since either bills
     * samples to the wrong operation. Switch it off to profile code you do not own and cannot fix.
     *
     * [wait] is [WaitStrategy.SPIN] from [SPIN_MIN_PROCESSORS] processors and [WaitStrategy.PARK]
     * below, unless given.
     *
     * [allocations] counts the bytes allocated under each coarse path, for the report's `Allocated`
     * column. Off by default: it adds two reads to every coarse execution. Where the JVM cannot
     * report them the session runs without, and the report says so. A thread counts from its first
     * coarse boundary in the session, so an execution already open at [start] misses what it
     * allocates up to that boundary, and one still open at [stop] misses what came after its last.
     */
    @JvmStatic
    @JvmOverloads
    fun start(
        stepMillis: Double = 1.0,
        wait: WaitStrategy = defaultWait(),
        jitter: Double = 0.25,
        strict: Boolean = true,
        sampleState: Boolean = true,
        allocations: Boolean = false,
    ) {
        Logo.once()
        check(sampler == null) { "already sampling" }
        allocationSession = if (allocations && allocationsAvailable) ++sessions else 0
        allocationsMissing = allocations && !allocationsAvailable
        startedAt = System.nanoTime()
        gcAtStart = GcPauseClock.pauseNanos()
        imbalancesAtStart = imbalances.get()
        untrackedAtStart = untrackedSlots()
        reclaimedAtStart = reclaimedSlots()
        sampler = Sampler((stepMillis * 1_000_000).toLong(), wait, jitter, strict = strict, sampleState = sampleState)
            .also { it.start() }
        clock = ClockProbe().also { it.start() }
    }

    /** Stops sampling and returns what was collected. */
    @JvmStatic
    fun stop(): Run {
        Logo.once()
        val s = checkNotNull(sampler) { "not sampling" }
        s.shutdown()
        sampler = null
        val allocations = allocationSession != 0
        allocationSession = 0
        val clockReport = clock?.stop() ?: ClockReport.NOT_RECORDED
        clock = null
        val duration = System.nanoTime() - startedAt
        // Both ends or neither: a clock that could not be read at one of them has no difference.
        val gcAtStop = GcPauseClock.pauseNanos()
        val gcNanos = if (gcAtStart < 0 || gcAtStop < 0) -1L else gcAtStop - gcAtStart
        val stats = (0 until registeredCount()).map { id ->
            OperationStat(
                id, nameOf(id), s.counters[id], s.sessionCalls(id), s.stuckHits[id], s.stuckInstances[id],
                s.waitingHits[id], s.stuckWaitingHits[id], s.activeTicks[id], s.sessionThreads(id),
                s.peakInside[id],
                longestStuckTicks = s.longestStuckTicks[id],
                stuckRuns = LongArray(STUCK_BUCKETS) { s.stuckRunHist[id * STUCK_BUCKETS + it] },
            )
        }
        // A label still open when the session ends is a leak by definition: nothing can close it
        // now. Counted here rather than left to the user to notice, because the symptom — one
        // operation quietly accumulating everybody else's samples — looks exactly like a finding.
        // Taken before the open-context count, so a context still open at the end is visible in
        // both: its span is missing from the totals precisely because nothing closed it.
        val tree = coarseTree()
        val totals = coarseTotals(tree)
        val pauses = s.pauses(gcNanos)
        val coarse = (0 until coarseCount()).map { t ->
            val a = totals[t]
            CoarseStat(
                t, coarseNameOf(t),
                count = a?.count ?: 0L,
                spanSumNanos = a?.sumNanos ?: 0L,
                spanMinNanos = if (a == null || a.count == 0L) 0L else a.minNanos,
                spanMaxNanos = a?.maxNanos ?: 0L,
                spans = a?.spans ?: Spans(),
                hits = s.coarseHits[t],
                selfHits = s.coarseSelfHits[t],
                selfRunningHits = s.coarseSelfRunningHits[t],
                selfActiveTicks = s.coarseSelfActiveTicks[t],
                selfPeak = s.coarseSelfPeak[t],
                threads = coarseThreadsOf(t),
                runningHits = s.coarseRunningHits[t],
                inclusiveHits = s.coarseInclusiveHits[t],
                runningInclusiveHits = s.coarseRunningInclusiveHits[t],
                instanceTicks = s.coarseInstanceTicks[t],
                activeTicks = s.coarseActiveTicks[t],
                fine = LongArray(MAX_OPERATIONS + 1) { s.pairHits[t * (MAX_OPERATIONS + 1) + it] },
                staleHits = s.coarseStaleHits[t],
            )
        }
        // A node's pauses are what the sampler saw; the header prints what the JVM confirmed. The
        // same scale brings the column under the header, so that the rows cannot add up to more
        // than the line above them says there was.
        val pauseScale = if (pauses.pausedNanos > 0) pauses.confirmedNanos.toDouble() / pauses.pausedNanos else 0.0
        fun nodeOf(n: CoarsePath): CoarseNode = CoarseNode(
            type = n.type,
            name = coarseNameOf(n.type) + (if (n.past) ", deeper than $MAX_COARSE_DEPTH, not followed" else ""),
            count = n.count, spanSumNanos = n.sumNanos,
            spanMinNanos = if (n.count == 0L) 0L else n.minNanos, spanMaxNanos = n.maxNanos,
            spans = n.spans, inclusiveHits = n.inclusiveHits, runningInclusiveHits = n.runningInclusiveHits,
            instanceTicks = n.instanceTicks, coveredTicks = n.coveredTicks,
            // Zeros rather than empty for a path never caught in its own time: empty means a run
            // saved without these, and such a path still has its calls to show.
            ownFine = n.ownFine ?: LongArray(MAX_OPERATIONS + 1), ownFineCovered = n.ownFineCovered ?: LongArray(MAX_OPERATIONS + 1),
            calls = n.calls ?: LongArray(MAX_OPERATIONS),
            allocated = n.allocated,
            fine = n.fine ?: LongArray(MAX_OPERATIONS + 1),
            pausedNanos = (n.pausedNanos * pauseScale).toLong(),
            children = n.children?.filterNotNull()?.filter { !it.isEmpty() }?.map { nodeOf(it) } ?: emptyList(),
            slowest = n.slowest(),
        )
        val coarseTree = tree.below().filter { !it.isEmpty() }.map { nodeOf(it) }
        return Run(
            stats, s.counters[NO_OP_INDEX], s.ticks, s.span, duration, s.maxSlots, s.duty(), s.failure,
            imbalances = imbalances.get() - imbalancesAtStart, openAtEnd = openSpans(), stateSampled = s.sampleState,
            untrackedSlots = (untrackedSlots() - untrackedAtStart).coerceAtLeast(0),
            reclaimedSlots = (reclaimedSlots() - reclaimedAtStart).coerceAtLeast(0),
            idleWaitingHits = s.idleWaitingHits,
            coarse = coarse,
            openContextsAtEnd = openContexts(),
            labelledOutsideCoarse = s.labelledOutsideCoarse,
            staleContextHits = s.staleContextHits,
            coarseSampleHits = s.coarseSampleHits,
            pause = pauses,
            coarseTree = coarseTree,
            clock = clockReport,
            wait = s.wait,
            askedStepNanos = s.stepNanos,
            allocations = allocations,
            allocationsMissing = allocationsMissing,
        )
    }
}

/** The sampler's wait when `start()` is not told: spin while a processor can be spared, park below. */
internal fun defaultWait(processors: Int = Runtime.getRuntime().availableProcessors()): WaitStrategy =
    if (processors >= SPIN_MIN_PROCESSORS) WaitStrategy.SPIN else WaitStrategy.PARK

/**
 * Marks the calling thread as being inside operation [id] for the duration of [body].
 *
 * The previous value is restored rather than cleared — clearing would break nesting, since the
 * caller is still inside its own operation when a nested one returns.
 */
inline fun <T> op(op: FineOp, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(op.id)
    // After the label, deliberately. Before it, the increment would be billed to the caller and
    // would add to the attribution bias; after it, the time lands on the operation it belongs to.
    // That inflates busy operations by calls x counterCost — the one distortion that can be
    // subtracted exactly, since the counter measures precisely the quantity the correction needs.
    slot.count(op.id)
    try {
        return body()
    } finally {
        slot.setOpaque(prev)
    }
}

/**
 * The same, for a block that performs [times] units of the operation rather than one.
 *
 * Below about 50 nanoseconds an operation should not carry a label of its own: the hook is a
 * visible fraction of it, the sampler reads it low, and C2 can move work across the boundaries of
 * adjacent short labels without leaving a trace. The remedy is to label the loop instead — and then
 * the report speaks in loop-executions, which is not the unit anybody thinks in. This says how many
 * units the block contains, so the counts and the implied duration per call come back in the
 * caller's terms.
 *
 * `op(probe, times = keys.size) { for (k in keys) table.find(k) }` reports the probe, not the loop.
 */
inline fun <T> op(op: FineOp, times: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(op.id)
    slot.count(op.id, times)
    try {
        return body()
    } finally {
        slot.setOpaque(prev)
    }
}

/**
 * The same label in the middle of a chain, with the receiver as `this` in the block, as `run` has
 * it: `deps.op(FILTER) { filter { it.scope in scopes } }.sorted()`. Only the block is labelled; the
 * receiver was evaluated before the label opened. A label should not make you break the chain to
 * place it.
 *
 * `this`, not `it`, because the block is usually one more call on the receiver, and `it.filter { it }`
 * nests two `it`s. The price is `run`'s: an unqualified name the receiver also has now means the
 * receiver's. Called without an explicit receiver this resolves to the plain form or to this one on
 * the implicit `this`, and the two do the same, since the block's `this` is that receiver either way.
 */
inline fun <T, R> T.op(op: FineOp, body: T.() -> R): R = com.ticksnick.op(op) { body() }

/** The chain form of [op] with [times]. */
inline fun <T, R> T.op(op: FineOp, times: Int, body: T.() -> R): R = com.ticksnick.op(op, times) { body() }

