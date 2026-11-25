package com.ticksnick

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/** The furthest [OpSlot.unwindTo] walks: past the depth any program nests, short of a cycle. */
internal const val MAX_UNWIND = 1 shl 16

/** A thread's own context, the one it starts with. */
@PublishedApi
internal const val OWN = 0

/** A context made by `within`, for one task working within an execution it borrowed. */
@PublishedApi
internal const val WITHIN = 1

/** A context made by `newContext`, carried across threads by hand with `using`. */
@PublishedApi
internal const val NEW = 2

/**
 * Everything one line of work has open: its coarse executions, the innermost and its parents, down
 * to the one it borrowed, if any, which it never closes.
 *
 * Every thread has one of its own. Work that runs within an execution of another thread gets one for
 * its run, from [within]. A line of work that moves between threads - an async request whose stages
 * run on different pools - gets one from [Profiler.newContext], and takes it onto each thread it runs
 * on with [using], for the length of a block; [end] ends it.
 *
 * **A label closes as the last one open in its own context**, and a context closes everything it
 * opened before it ends. A label closed anywhere else, or left open when its context ends, fails the
 * run: its time went to the wrong label.
 *
 * A context is on one thread at a time.
 */
class Context @PublishedApi internal constructor(
    /** [OWN], [WITHIN] or [NEW]: what made it, and how a failure names it. */
    @JvmField @field:JvmSynthetic internal val kind: Int,
    /**
     * The execution it borrowed: the parent of whatever it opens first. Another context opened it, and
     * only that one closes it. Null for none.
     */
    @JvmField @field:JvmSynthetic @PublishedApi internal val bottom: Execution?,
    /** For a thread's own context, its thread's slot, to name the thread by. */
    @JvmField @field:JvmSynthetic internal val slot: OpSlot? = null,
) {
    /** Its innermost open execution while it is on no thread. While it is on one, that slot's is the one. */
    @JvmField @field:JvmSynthetic internal var innermost: Execution? = bottom

    /** The slot it is on, or null. Taken by compare-and-set, so it is never on two threads at once. */
    @JvmField @field:JvmSynthetic internal var on: OpSlot? = null

    /** How many times it is on its thread, nested: `using` inside `using`. Written by that thread only. */
    @JvmField @field:JvmSynthetic internal var mounts: Int = 0

    /** What the slot held before this context came onto it, put back when it comes off. */
    @JvmField @field:JvmSynthetic internal var savedContext: Context? = null
    @JvmField @field:JvmSynthetic internal var savedExecution: Execution? = null

    /** The thread's hand-placed fine labels when it came on: one entered after it and still open at its end crossed the move. */
    @JvmField @field:JvmSynthetic internal var savedDepth: Int = 0

    /** What [mount] returns, made on the first. */
    private var handle: Mount? = null

    /**
     * Puts this context on the calling thread until [Mount.close], for an adapter whose two hooks are not
     * one block: a coroutine's resume and suspend, a framework's before and after. Prefer [using].
     */
    fun mount(): Mount {
        val slot = Profiler.slot()
        if (!slot.mount(this)) return Mount.REFUSED
        return handle ?: Mount(this).also { handle = it }
    }

    /**
     * Ends this context: whatever it opened must be closed by now. A label still open fails the run, as
     * it would at the end of `within`: from its missed `exit` on, its time went to it. Call it where the
     * line of work is over, off every thread.
     */
    fun end() {
        val slot = Profiler.slot()
        val top = if (on === slot && slot.context === this) slot.executionOpaque() else innermost
        val left = Profiler.leftOpen(top, bottom) ?: return
        Profiler.failLeftOpen(left, "`end()` of ${describe()}")
        innermost = bottom
        if (on === slot && slot.context === this) slot.unwindTo(bottom)
    }

    /** Takes a context off its thread. Closing it twice does nothing. */
    class Mount internal constructor(private val context: Context?) : AutoCloseable {
        override fun close() {
            val c = context ?: return
            c.on?.unmount(c)
        }

        internal companion object {
            /** For a mount that was refused: there is nothing to take off. */
            val REFUSED = Mount(null)
        }
    }

    internal companion object {
        @JvmStatic
        val ON: VarHandle = MethodHandles.lookup().findVarHandle(Context::class.java, "on", OpSlot::class.java)
    }
}

/** How a failure names a context's strand: `thread 'main'`, `a task working within 'search'`. */
internal fun Context.describe(): String = when (kind) {
    OWN -> "thread ${code(slot?.thread?.get()?.name ?: "?")}"
    WITHIN -> "a task working within ${bottom?.let { code(Profiler.coarseNameOf(it.type)) } ?: "no op"}"
    else -> "the context made by `newContext`"
}

/**
 * Runs [body] on this thread as a task working within [parent], an execution of another thread: what
 * it opens is placed under [parent], and the samples that catch it count in [parent] too.
 *
 * [parent] comes from [Profiler.currentExecution] on the thread that hands the work over. The task
 * gets a context of its own, which borrows [parent] and never closes it; anything it opens must be
 * closed by the end of the block, and [parent] must stay open until then - wait for the task inside
 * the block that started it. Null means the task runs within nothing.
 *
 * `Executor.propagating()` does this for every task of a pool.
 */
inline fun <T> within(parent: Execution?, body: () -> T): T {
    val slot = Profiler.slot()
    val c = Context(WITHIN, parent)
    slot.mount(c)
    try {
        return body()
    } finally {
        slot.unmount(c)
    }
}

/**
 * Runs [body] on this thread with [context] on it: what the block opens goes to [context], and an
 * `exit` here closes what [context] has open, whichever thread opened it. Taking it off at the end
 * keeps what it has open in it, for the next thread; [Context.end] ends it.
 */
inline fun <T> using(context: Context, body: () -> T): T {
    val slot = Profiler.slot()
    val mounted = slot.mount(context)
    try {
        return body()
    } finally {
        if (mounted) slot.unmount(context)
    }
}

/**
 * Puts [c] on this thread: saves what the thread had on, and shows [c]'s innermost execution. False,
 * and the run fails, when [c] is already on a thread: a context is one line of work, on one thread at
 * a time.
 */
@PublishedApi
internal fun OpSlot.mount(c: Context): Boolean {
    if (c.on === this && context === c) {
        c.mounts++
        return true
    }
    if (c.kind != WITHIN && !Context.ON.compareAndSet(c, null, this)) {
        Profiler.failInUse(c, this)
        return false
    }
    if (c.kind == WITHIN) c.on = this
    c.mounts = 1
    val e = executionOpaque()
    // The calls and bytes so far belong to what the thread was inside.
    flushCounts(e)
    c.savedContext = context
    c.savedExecution = e
    c.savedDepth = depth
    context = c
    setExecutionOpaque(c.innermost)
    return true
}

/**
 * Takes [c] off this thread and puts back what it had on before. A `within` context ends here, so
 * whatever it opened must be closed; any context must be taken off on the thread it went on, and a
 * fine label entered on it must be closed on it.
 */
@PublishedApi
internal fun OpSlot.unmount(c: Context) {
    if (Thread.currentThread().threadId() != threadId) {
        // A coroutine moved the block to another thread: this slot is not that thread's to write.
        Profiler.failMoved(c, this)
        return
    }
    if (c.on !== this) return
    if (context === c && --c.mounts > 0) return
    if (context !== c) {
        // A `mount()` inside the block was never closed: take it off too, then fail.
        Profiler.failMountLeftOn(c, context)
        while (context !== c && context !== own) {
            val inner = context
            inner.innermost = executionOpaque()
            context = inner.savedContext ?: own
            setExecutionOpaque(inner.savedExecution)
            inner.mounts = 0
            Context.ON.setRelease(inner, null)
        }
    }
    val e = executionOpaque()
    if (c.kind == WITHIN) {
        val left = Profiler.leftOpen(e, c.bottom)
        if (left != null) {
            Profiler.failLeftOpen(left, "the end of `within`")
            unwindTo(c.bottom)
        }
    }
    if (depth > c.savedDepth) {
        Profiler.failLeftOpen(Profiler.nameOf(entered[depth - 1]), if (c.kind == WITHIN) "the end of `within`" else "the end of `using`")
        setOpaque(stack[c.savedDepth])
        depth = c.savedDepth
    }
    val inner = executionOpaque()
    flushCounts(inner)
    c.innermost = if (c.kind == WITHIN) c.bottom else inner
    context = c.savedContext ?: own
    setExecutionOpaque(c.savedExecution)
    c.savedContext = null
    c.savedExecution = null
    c.mounts = 0
    if (c.kind == WITHIN) c.on = null else Context.ON.setRelease(c, null)
}

/**
 * Drops this thread's executions down to [bottom], each marked closed, none recorded: after a failure,
 * so the labels that follow are not billed to one left open.
 */
internal fun OpSlot.unwindTo(bottom: Execution?) {
    var e = executionOpaque()
    var n = 0
    while (e != null && e !== bottom && n++ < MAX_UNWIND) {
        e.markClosed()
        e = e.parent
    }
    flushCounts(executionOpaque())
    setExecutionOpaque(bottom)
}
