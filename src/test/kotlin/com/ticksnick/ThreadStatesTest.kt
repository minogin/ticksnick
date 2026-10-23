package com.ticksnick

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The probe through the sampler: threads held in known states inside labels, and what the report's
 * split says about each.
 *
 * Runs only where there is a probe - Windows on Java 22+, which is `gradlew probeTest`. Under `test`,
 * on Java 21 from the classes directories, the probe is the Java 21 class and every case returns at
 * once. Each state is held for hundreds of samples, as in [SamplerCoarseTest], so no result rests on
 * one tick.
 */
class ThreadStatesTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling: the test stopped it itself, or never started.
        }
    }

    private val holdNanos = 500_000_000L

    @Volatile
    private var sink: Long = 0

    private fun spinUntil(stop: AtomicBoolean) {
        var s = sink or 1L
        while (!stop.get()) {
            var i = 0
            while (i < 4096) {
                s = s * 31 + i
                i++
            }
        }
        sink = s
    }

    /** Whether there is a probe here at all. */
    private fun probeHere(): Boolean {
        ThreadStates.enable()
        return ThreadStates.enabled()
    }

    /** A label's samples as on a CPU, waiting for a core, in a system call and parked, as fractions. */
    private fun split(p: Profile, name: String): DoubleArray {
        val op = p.operations.first { it.name == name }
        val h = op.hits.toDouble()
        assertTrue(op.hits > 100, "$name: only ${op.hits} samples")
        val cpu = op.hits - op.waitingHits - op.coreHits - op.ioHits
        return doubleArrayOf(cpu / h, op.coreHits / h, op.ioHits / h, op.waitingHits / h)
    }

    private fun started(name: String, body: () -> Unit): Thread =
        Thread({ try { body() } finally { Profiler.release() } }, name).also { it.start() }

    @Test
    fun `a spinning thread is on a CPU, a socket read in a system call, a park waiting`() {
        if (!probeHere()) return
        val busy = Profiler.registerFine("probe.busy")
        val read = Profiler.registerFine("probe.read")
        val park = Profiler.registerFine("probe.park")
        val stop = AtomicBoolean(false)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use { accepted ->
                    Profiler.start(strict = false)
                    val ready = CountDownLatch(3)
                    val threads = listOf(
                        started("busy") { op(busy) { ready.countDown(); spinUntil(stop) } },
                        started("read") {
                            op(read) {
                                ready.countDown()
                                try { client.getInputStream().read() } catch (_: java.io.IOException) { }
                            }
                        },
                        started("park") { op(park) { ready.countDown(); while (!stop.get()) LockSupport.parkNanos(10_000_000) } },
                    )
                    ready.await()
                    Thread.sleep(holdNanos / 1_000_000)
                    val p = Profiler.stop()
                    stop.set(true)
                    accepted.close()
                    client.close()
                    threads.forEach { it.join() }

                    assertTrue(p.stateProbed, "the session did not probe")
                    val b = split(p, "probe.busy")
                    val r = split(p, "probe.read")
                    val k = split(p, "probe.park")
                    assertTrue(b[0] > 0.8, "a spinning thread on a CPU only ${b[0]}")
                    assertTrue(r[2] > 0.8, "a socket read in a system call only ${r[2]}")
                    assertTrue(k[3] > 0.8, "a parked thread waiting only ${k[3]}")
                }
            }
        }
    }

    @Test
    fun `twice as many spinning threads as cores wait for a core`() {
        if (!probeHere()) return
        val crowd = Profiler.registerFine("probe.crowd")
        val stop = AtomicBoolean(false)
        val n = 2 * Runtime.getRuntime().availableProcessors()
        Profiler.start(strict = false)
        val ready = CountDownLatch(n)
        val threads = (1..n).map { started("crowd-$it") { op(crowd) { ready.countDown(); spinUntil(stop) } } }
        ready.await()
        Thread.sleep(holdNanos / 1_000_000)
        val p = Profiler.stop()
        stop.set(true)
        threads.forEach { it.join() }

        // Half the threads on a core at any moment, and the sampler takes one of the cores.
        val c = split(p, "probe.crowd")
        assertTrue(c[1] > 0.3, "a crowd of $n spinning threads waited for a core only ${c[1]}")
        assertTrue(c[2] < 0.05, "a crowd that makes no system call was in one ${c[2]}")
    }
}
