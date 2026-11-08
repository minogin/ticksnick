package com.ticksnick

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run's body: timed through a session, saved with the run, and the limits read at it. */
class BodyTest {

    /** A session long enough for a timing has a body, and `nanoTime` in bodies, both plausible. */
    @Test
    fun `a session times a body and nanoTime`() {
        Profiler.start(strict = false)
        Thread.sleep(300)
        val run = Profiler.stop()
        assertTrue(run.bodyTimed, "no body timed in 300 ms")
        assertTrue(run.bodyNanos in 1.0..1_000.0, "a body of ${run.bodyNanos} ns")
        assertTrue(run.nanoTimeBodies in 0.001..1_000.0, "nanoTime of ${run.nanoTimeBodies} bodies")
    }

    /** Saved and loaded, a run keeps its body, so it renders with the same floors later. */
    @Test
    fun `the body survives a save`() {
        Profiler.start(strict = false)
        Thread.sleep(300)
        val run = Profiler.stop()
        val loaded = Run.load(run.save(Files.createTempDirectory("body")))
        assertEquals(run.bodyNanos, loaded.bodyNanos, 0.0)
        assertEquals(run.nanoTimeBodies, loaded.nanoTimeBodies, 0.0)
        assertEquals(run.fineFloorNanos, loaded.fineFloorNanos, 0.0)
    }
}
