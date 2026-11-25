package com.ticksnick

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/** The furthest [OpSlot.unwindTo] walks: past the depth any program nests, short of a cycle. */
internal const val MAX_UNWIND = 1 shl 16

/** Contexts one on another a thread holds room for before its frames grow. */
internal const val MOUNT_FRAMES = 4

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
 * What [Context.on] holds while the thread taking the context off writes its innermost back: a mount
 * that finds it waits for those two stores.
 */
private val LEAVING = Any()

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
 * A context is on one thread at a time, but for the hand-over of [mount].
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

    /**
     * The slot it is on; null for none; [LEAVING] while its thread writes [innermost] back. Taken by
     * compare-and-set, so it is on one thread at a time, but for the hand-over of [mount]. What a
     * thread saved when the context came on is in that thread's frames, not here.
     */
    @JvmField @field:JvmSynthetic internal var on: Any? = null

    /** What [mount] returns, made on the first. */
    private var handle: Mount? = null

    /**
     * Puts this context on the calling thread until [Mount.close], for an adapter whose two hooks are not
     * one block: a coroutine's resume and suspend, a framework's before and after. Prefer [using].
     *
     * It takes the context over from another thread that put it on with `mount()` and has not taken it
     * off yet: a coroutine can resume on a second thread before the first has finished suspending. The
     * first thread runs none of the work by then, and its own late [Mount.close] puts back only that
     * thread. So a context must stay one line of work: two at once would pass it back and forth.
     */
    fun mount(): Mount {
        val slot = Profiler.slot()
        if (!slot.mount(this, byMount = true)) return Mount.REFUSED
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

    /** Takes a context off the thread that closes it, the thread it was put on. */
    class Mount internal constructor(private val context: Context?) : AutoCloseable {
        override fun close() {
            val c = context ?: return
            Profiler.slot().takeOff(c, byMount = true)
        }

        internal companion object {
            /** For a mount that was refused: there is nothing to take off. */
            val REFUSED = Mount(null)
        }
    }

    internal companion object {
        @JvmStatic
        val ON: VarHandle = MethodHandles.lookup().findVarHandle(Context::class.java, "on", Any::class.java)
    }
}

/** A context put on a thread, with what its mount saved, put back when it comes off. Made once per depth, reused. */
internal class Frame {
    @JvmField var context: Context? = null

    /** The execution the thread was inside. */
    @JvmField var savedExecution: Execution? = null

    /** The thread's hand-placed fine labels: one entered after and still open when the context comes off crossed it. */
    @JvmField var savedDepth: Int = 0

    /** Times it was put on again while on top, `using` inside `using`: each comes off before it does. */
    @JvmField var remounts: Int = 0

    /** Put on by `mount()`, so another thread's `mount()` may take it over. */
    @JvmField var byMount: Boolean = false
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
 * Puts [c] on this thread: saves what the thread was inside in a frame, and shows [c]'s innermost
 * execution. False, and the run fails, when [c] is on another thread already: a context is one line
 * of work, on one thread at a time - but for the hand-over a `mount()` [byMount] may make, see
 * [Context.mount].
 */
@PublishedApi
internal fun OpSlot.mount(c: Context, byMount: Boolean = false): Boolean {
    if (context === c && c.on === this) {
        frames[frameCount - 1]!!.remounts++
        return true
    }
    var inner = c.innermost
    if (c.kind == WITHIN) c.on = this
    else while (true) {
        val o = Context.ON.getAcquire(c)
        if (o == null) {
            if (Context.ON.compareAndSet(c, null, this)) {
                inner = c.innermost
                break
            }
        } else if (o === LEAVING) {
            Thread.onSpinWait()
        } else {
            // On another thread. Taken over only from a `mount()` on top there: that thread has stopped
            // running the work, and its slot holds the innermost until it takes the context off, which
            // begins by claiming `on` - so the compare-and-set below fails if that began first.
            val from = o as OpSlot
            if (!byMount || from === this) {
                Profiler.failInUse(c, this)
                return false
            }
            if (!from.onTopByMount(c)) {
                // That thread may have taken it off since `on` was read: look again.
                if (Context.ON.getAcquire(c) !== from) continue
                Profiler.failInUse(c, this)
                return false
            }
            val e = from.executionOpaque()
            if (Context.ON.compareAndSet(c, from, this)) {
                inner = e
                break
            }
        }
    }
    val e = executionOpaque()
    // The calls and bytes so far belong to what the thread was inside.
    flushCounts(e)
    pushFrame(c, e, byMount)
    context = c
    setExecutionOpaque(inner)
    return true
}

/** Takes [c] off this thread at the end of a `within` or `using` block, on the thread it began on. See [takeOff]. */
@PublishedApi
internal fun OpSlot.unmount(c: Context) {
    if (Thread.currentThread().threadId() != threadId) {
        // A coroutine moved the block to another thread: this slot is not that thread's to write.
        Profiler.failMoved(c, this)
        return
    }
    takeOff(c, byMount = false)
}

/**
 * Takes [c] off this thread, the calling one, and puts back what the thread was inside before. A
 * `within` context ends here, so whatever it opened must be closed; a fine label entered while [c]
 * was on must be closed by now. If another thread's `mount()` took [c] over meanwhile, only this
 * thread is put back: [c] is that thread's now.
 */
internal fun OpSlot.takeOff(c: Context, byMount: Boolean) {
    val f = frameOf(c)
    if (f < 0) {
        // Not on this thread: taken off already, or a `mount()` closed on a thread it was not put on.
        val o = c.on
        if (byMount && o is OpSlot) Profiler.failMoved(c, o)
        return
    }
    val top = frameCount - 1
    val frame = frames[f]!!
    if (f == top && frame.remounts > 0) {
        frame.remounts--
        return
    }
    // A `mount()` inside the block was never closed: taken off too.
    if (f < top) Profiler.failMountLeftOn(c, context)
    if (depth > frame.savedDepth) {
        Profiler.failLeftOpen(Profiler.nameOf(entered[depth - 1]), if (c.kind == WITHIN) "the end of `within`" else "the end of `using`")
        setOpaque(stack[frame.savedDepth])
        depth = frame.savedDepth
    }
    var inner = executionOpaque()
    flushCounts(inner)
    for (i in top downTo f) {
        val fi = frames[i]!!
        release(fi.context!!, inner)
        inner = fi.savedExecution
        fi.context = null
        fi.savedExecution = null
    }
    frameCount = f
    context = if (f == 0) own else frames[f - 1]!!.context!!
    setExecutionOpaque(inner)
}

/**
 * Lets [c] go from this thread with [inner] its innermost execution. Claims [Context.on] first: if
 * another thread's `mount()` took [c] over, [c] is that thread's, and nothing of it is written here.
 */
private fun OpSlot.release(c: Context, inner: Execution?) {
    if (c.kind == WITHIN) {
        val left = Profiler.leftOpen(inner, c.bottom)
        if (left != null) {
            Profiler.failLeftOpen(left, "the end of `within`")
            markAllClosed(inner, c.bottom)
        }
        c.innermost = c.bottom
        c.on = null
        return
    }
    if (!Context.ON.compareAndSet(c, this, LEAVING)) return
    c.innermost = inner
    Context.ON.setRelease(c, null)
}

/** Saves what this thread was inside, [saved], as [c] comes on. */
private fun OpSlot.pushFrame(c: Context, saved: Execution?, byMount: Boolean) {
    if (frameCount == frames.size) frames = frames.copyOf(frameCount * 2)
    val f = frames[frameCount] ?: Frame().also { frames[frameCount] = it }
    f.context = c
    f.savedExecution = saved
    f.savedDepth = depth
    f.remounts = 0
    f.byMount = byMount
    frameCount++
}

/** The frame [c] is in on this thread, or -1. */
private fun OpSlot.frameOf(c: Context): Int {
    var i = frameCount - 1
    while (i >= 0 && frames[i]!!.context !== c) i--
    return i
}

/**
 * Whether this thread is letting go of a context another thread's `mount()` took over: a coroutine
 * resumed elsewhere before this thread finished suspending it. The thread runs none of its work, but
 * shows its labels until it takes the context off, and the other thread may close them meanwhile. Read
 * by the sampler, which bills that moment as it bills any suspension's, not as work under a label ended.
 */
internal fun OpSlot.lettingGo(): Boolean {
    val c = context
    return c.kind == NEW && Context.ON.getOpaque(c) !== this
}

/** Whether [c] is on top of this thread, put there by `mount()`. Read by another thread, to take [c] over. */
private fun OpSlot.onTopByMount(c: Context): Boolean {
    val fs = frames
    val n = frameCount
    if (n <= 0 || n > fs.size) return false
    val f = fs[n - 1] ?: return false
    return f.context === c && f.byMount
}

/**
 * Drops this thread's executions down to [bottom], each marked closed, none recorded: after a failure,
 * so the labels that follow are not billed to one left open.
 */
internal fun OpSlot.unwindTo(bottom: Execution?) {
    markAllClosed(executionOpaque(), bottom)
    flushCounts(executionOpaque())
    setExecutionOpaque(bottom)
}

/** Marks the executions from [top] down to [bottom], exclusive, closed. */
private fun markAllClosed(top: Execution?, bottom: Execution?) {
    var e = top
    var n = 0
    while (e != null && e !== bottom && n++ < MAX_UNWIND) {
        e.markClosed()
        e = e.parent
    }
}
