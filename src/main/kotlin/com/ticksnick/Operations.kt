package com.ticksnick

/**
 * The two kinds of thing you can label, and the one difference between them.
 *
 * **Fine and coarse is a property of the instrument, not of your program.** A fine label is a slot
 * write and a counter, about 2 ns, and it gives a share of time and a call count. A coarse label
 * allocates a context and takes two timestamps, about 40 ns, and it gives everything the fine one
 * does *plus* the measured duration of every execution, percentiles over them, and a breakdown of
 * what ran inside. Coarse is strictly more, so the only question is whether the operation is long
 * enough to afford it — `d >= max(800 ns, 4 us x share)`, and the report tells you when it is not.
 *
 * That is why the choice is made **once, at registration**, and never mentioned again. Everywhere
 * else there is one verb:
 *
 * ```
 * val parse   = Profiler.registerFine("parse")     // FineOp
 * val request = Profiler.registerCoarse("request") // CoarseOp
 *
 * op(parse)   { ... }
 * op(request) { ... }
 * ```
 *
 * **What that buys, beyond reading better.** The report says, in as many words, *"the operation
 * wants a coarse label for its per-execution statistics"*. Acting on that advice is one word in one
 * place, the registration, not every call site, and reversible if the cost turns out not to be worth it.
 *
 * **And it is what makes the two id spaces safe.** Both count from zero, so the first fine
 * operation and the first coarse one are both the integer `0`; as bare ints, passing one for the
 * other would compile, run, and report a plausible wrong answer. Two distinct types cannot be
 * confused, and both erase to a bare `int`, so nothing is paid for it at run time. Java gets no such
 * check: there both are an `int`, which is why its calls name the kind, `enterFine` and `enterCoarse`.
 */

/**
 * A registered fine operation: something short and hot, measured by sampling a slot.
 *
 * Obtained from [Profiler.registerFine] and nowhere else — the constructor is internal, so a handle
 * always names something that was registered. [id] is readable because a caller may reasonably key
 * their own array by it.
 */
@JvmInline
value class FineOp internal constructor(@PublishedApi internal val id: Int) {
    /** `fine#3`, so a number that has left the type system is still unambiguous in a log. */
    override fun toString(): String = "fine#$id"
}

/**
 * A registered coarse operation: a whole logical unit of work, timed exactly at both ends.
 *
 * Obtained from [Profiler.registerCoarse] and nowhere else, for the reason [FineOp] gives.
 */
@JvmInline
value class CoarseOp internal constructor(@PublishedApi internal val id: Int) {
    /** `coarse#0`, and deliberately distinguishable from `fine#0`, which is a different operation. */
    override fun toString(): String = "coarse#$id"
}

/**
 * A family of fine operations indexed by a small integer: `level[depth]`, `byType[type]`.
 *
 * One registration reserves [arity] consecutive ids, so the hot path is `base + i` and a bounds
 * check - no map, no string, no allocation. The index has to be a small number known up front:
 * a depth, a type, a size bucket. Not a node id. See [Profiler.registerFine].
 */
class FineOps internal constructor(internal val base: Int, internal val arity: Int) {
    @JvmName("get")
    operator fun get(i: Int): FineOp {
        if (i < 0 || i >= arity) throw IndexOutOfBoundsException("index $i of a family of $arity")
        return FineOp(base + i)
    }

    override fun toString(): String = "fine#$base[0..${arity - 1}]"
}

/** A family of coarse operations indexed by a small integer. The coarse twin of [FineOps]. */
class CoarseOps internal constructor(internal val base: Int, internal val arity: Int) {
    @JvmName("get")
    operator fun get(i: Int): CoarseOp {
        if (i < 0 || i >= arity) throw IndexOutOfBoundsException("index $i of a family of $arity")
        return CoarseOp(base + i)
    }

    override fun toString(): String = "coarse#$base[0..${arity - 1}]"
}

/**
 * A family of fine operations indexed by an enum: `byKind[kind]`, rows `name[CONSTANT]`. The index
 * is the constant's ordinal, so there is no map and no bounds check, and a wrong type does not
 * compile. See [Profiler.registerFine].
 */
class FineEnumOps<E : Enum<E>> @PublishedApi internal constructor(internal val base: Int) {
    @JvmName("get")
    operator fun get(e: E): FineOp = FineOp(base + e.ordinal)

    override fun toString(): String = "fine#$base[enum]"
}

/** A family of coarse operations indexed by an enum. The coarse twin of [FineEnumOps]. */
class CoarseEnumOps<E : Enum<E>> @PublishedApi internal constructor(internal val base: Int) {
    @JvmName("get")
    operator fun get(e: E): CoarseOp = CoarseOp(base + e.ordinal)

    override fun toString(): String = "coarse#$base[enum]"
}

/**
 * An open label, closed by Java's try-with-resources: `try (var s = Profiler.fine(PARSE)) { ... }`.
 *
 * Java's block form. Java cannot inline Kotlin's `op(PARSE) { }`, so a lambda would be a real call
 * and possibly an allocation on every call. `close()` is the matching exit, so a body that throws
 * cannot leave the label set. A scope holds no state of its own: [Profiler.fine] hands out the same
 * one for an operation every time, on every thread.
 *
 * Two final classes rather than one with a flag, so `close()` is a direct call with no branch on
 * the kind.
 */
class FineScope internal constructor(private val id: Int) : AutoCloseable {
    override fun close() = Profiler.exit(FineOp(id))
}

/** An open coarse execution, closed by try-with-resources. The coarse twin of [FineScope]. */
class CoarseScope internal constructor(private val id: Int) : AutoCloseable {
    override fun close() = Profiler.exit(CoarseOp(id))
}
