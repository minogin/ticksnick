package com.ticksnick

import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Work handed to a pool, run within the execution that handed it over.
 *
 * An [Execution] lives in the slot of the thread that made it, so work handed to a pool leaves it
 * behind: the helper threads run under no execution at all, and the caller's span reports the time
 * they spent as *waiting*. A search fanned across eight threads can leave close to 90% of its
 * labelled thread-time outside every span, and a request's own span can account for well under 1%
 * of the work that request did.
 *
 * **The whole mechanism is [Profiler.currentExecution] on the thread that hands the work over, and
 * [within] on the one that runs it.** Everything in this file is a wrapper that puts those two calls
 * in the right places for you.
 *
 * **A hand-off never creates an execution.** The task borrows one that already exists; it records no
 * span of it, and counts no execution. Only `op` and `enter` open one, and only the context that
 * opened it closes it. That is what keeps four helpers on one request reading as one execution
 * occupied by four threads rather than as four executions.
 *
 * **Opt in, deliberately.** Nothing here happens unless you ask for it, and a hand-off you forget to
 * wrap fails in the recoverable direction: the work loses its attribution and shows up in the
 * report's *"labelled thread-time inside NO coarse span"* line, which names the operations it caught.
 * The alternative — decorating pools behind your back — fails the other way, silently billing work
 * to whichever execution happened to be open, and losing attribution is recoverable where inventing
 * it is not.
 *
 * These read the thread, so inside a coroutine they hand over what the thread is inside, which
 * TickSnick does not follow yet.
 */

/**
 * This task, to run within the coarse execution the **calling** thread is inside right now.
 *
 * Read here, when you wrap, not later when the task runs — by then the wrapping thread's execution
 * is not reachable and the pool thread's is empty. So wrap where you fork.
 */
fun Runnable.propagating(): Runnable {
    val parent = Profiler.currentExecution()
    return Runnable { within(parent) { run() } }
}

/** As [Runnable.propagating]. Read on the thread that wraps. */
fun <T> Callable<T>.propagating(): Callable<T> {
    val parent = Profiler.currentExecution()
    return Callable { within(parent) { call() } }
}

/**
 * This executor, with every task it is given run within the execution its submitter was inside.
 *
 * **Prefer this to wrapping tasks one at a time.** Wrapping the pool once cannot be forgotten;
 * wrapping tasks means every new call site is another chance to forget, and a forgotten hand-off is
 * silent at the call site and only visible much later in the report's outside-every-span line.
 *
 * The execution is read inside `execute`, which runs on the submitting thread, so it is whatever that
 * thread was inside at the moment it handed the work over.
 */
fun Executor.propagating(): Executor = Executor { command -> execute(command.propagating()) }

/**
 * As [Executor.propagating], across the whole of [ExecutorService].
 *
 * Every method that accepts work wraps it — `execute`, all three `submit`s, both `invokeAll`s and
 * both `invokeAny`s. Everything else is forwarded untouched: `shutdown`, `awaitTermination` and
 * their neighbours carry no task and have nothing to propagate.
 *
 * Given a [ScheduledExecutorService] this returns one, and its `schedule` methods deliberately do
 * **not** propagate — see [ScheduledExecutorService.propagating].
 */
fun ExecutorService.propagating(): ExecutorService =
    if (this is ScheduledExecutorService) PropagatingScheduledExecutorService(this)
    else PropagatingExecutorService(this)

/**
 * As [ExecutorService.propagating], and the delayed methods are **left alone on purpose**.
 *
 * `submit`, `execute` and the `invoke` family propagate as they do everywhere else. `schedule`,
 * `scheduleAtFixedRate` and `scheduleWithFixedDelay` do not, because a task that runs in five
 * minutes will almost always outlive the execution that scheduled it, and crediting it there is the
 * failure this whole design guards against: not attribution lost, which the report can tell you
 * about, but attribution **invented**, which nothing can. A repeating task has no honest owner at
 * all after its first firing.
 *
 * If you genuinely want a delayed task inside a span that will still be open when it runs, wrap the
 * task itself with [Runnable.propagating] — at that call site the delay is right there in the same
 * expression, where the decision belongs.
 */
fun ScheduledExecutorService.propagating(): ScheduledExecutorService =
    PropagatingScheduledExecutorService(this)

/** Delegates everything, wrapping only what carries work. See [ExecutorService.propagating]. */
private open class PropagatingExecutorService(
    private val d: ExecutorService,
) : ExecutorService by d {

    override fun execute(command: Runnable) = d.execute(command.propagating())

    override fun <T : Any?> submit(task: Callable<T>): Future<T> = d.submit(task.propagating())

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> =
        d.submit(task.propagating(), result)

    override fun submit(task: Runnable): Future<*> = d.submit(task.propagating())

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
        d.invokeAll(tasks.map { it.propagating() })

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): MutableList<Future<T>> = d.invokeAll(tasks.map { it.propagating() }, timeout, unit)

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T =
        d.invokeAny(tasks.map { it.propagating() })

    override fun <T : Any?> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T = d.invokeAny(tasks.map { it.propagating() }, timeout, unit)
}

/** The scheduled variant. The delayed methods forward untouched — see [ScheduledExecutorService.propagating]. */
private class PropagatingScheduledExecutorService(
    private val d: ScheduledExecutorService,
) : PropagatingExecutorService(d), ScheduledExecutorService {

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        d.schedule(command, delay, unit)

    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
        d.schedule(callable, delay, unit)

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = d.scheduleAtFixedRate(command, initialDelay, period, unit)

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = d.scheduleWithFixedDelay(command, initialDelay, delay, unit)
}
