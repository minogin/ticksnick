package com.ticksnick.sandbox

import com.ticksnick.Profiler
import com.ticksnick.op
import com.ticksnick.propagating
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// The knobs. Try THREADS below BRANCHES, or above it, and watch the parallelism lines move.

/** Pool size: how many branches can run at once. */
const val THREADS = 2

/** Requests, one after the other on the main thread. */
const val REQUESTS = 200

/** Branches each request forks onto the pool, then waits for. */
const val BRANCHES = 4

/** Leaves each branch works through, one after the other on its own thread. */
const val LEAVES = 3

/** Iterations of busy arithmetic per unit of work: sets how long everything takes. */
const val WORK = 2_000_000

/**
 * A small branching program to read parallel reports on: `gradlew :sandbox:branching`.
 *
 * Each request runs on the main thread and forks [BRANCHES] branches onto a pool of [THREADS]; each
 * branch prepares - the n-th for n units of work - then works through [LEAVES] leaves; the request waits for all of them and merges.
 * Coarse labels are the tree, fine labels are the work inside it.
 */
fun main() {
    val request = Profiler.registerCoarse("request")
    val branch = Profiler.registerCoarse("branch")
    val leaf = Profiler.registerCoarse("leaf")
    val prepare = Profiler.registerFine("prepare")
    val compute = Profiler.registerFine("compute")
    val merge = Profiler.registerFine("merge")

    // propagating(): the request's context goes with each task, so a branch is under its request.
    val pool = Executors.newFixedThreadPool(THREADS).propagating()
    var sink = 0L

    Profiler.start()
    repeat(REQUESTS) {
        op(request) {
            val branches = (1..BRANCHES).map { b ->
                pool.submit(Callable {
                    op(branch) {
                        // Each branch prepares for a different time, so the branches of one request
                        // are out of step, as real ones are: their leaves do not line up.
                        var s = op(prepare) { burn(WORK * b) }
                        repeat(LEAVES) { s += op(leaf) { op(compute) { burn(2 * WORK) } } }
                        s
                    }
                })
            }
            sink += branches.sumOf { it.get() }
            sink += op(merge) { burn(WORK) }
        }
    }
    val profile = Profiler.stop()
    pool.shutdown()

    println(profile.render())
    println("(sink: $sink)")
}

/** Arithmetic the JIT cannot delete, [n] steps of it. */
private fun burn(n: Int): Long {
    var s = 1L
    for (i in 0 until n) s = s * 6364136223846793005L + i
    return s
}
