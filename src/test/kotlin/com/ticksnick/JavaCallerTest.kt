package com.ticksnick

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [JavaCaller]. That file compiling is most of the test; the counts check that each Java form
 * entered the operation it named, and that `times` reached the counter.
 */
class JavaCallerTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling, which is the normal case.
        }
    }

    @Test
    fun `every Java form counts what it entered`() {
        val profile = JavaCaller.run(3)
        fun calls(name: String) = profile.operations.single { it.name == name }.calls
        fun count(name: String) = profile.coarse.single { it.name == name }.count

        assertEquals(6, calls("java:rule"))
        assertEquals(45, calls("java:lookup"))
        assertEquals(3, calls("java:slot[0]"))
        assertEquals(3, calls("java:step[CHECK]"))
        assertEquals(3, count("java:plan"))
        assertEquals(3, count("java:level[1]"))
        assertEquals(3, count("java:phase[CHECK]"))
        assertEquals(0, profile.imbalances)
        assertEquals("3", profile.tags["plans"])
    }

    @Test
    fun `allocations can be asked for from Java`() {
        JavaCaller.allocating(1)
        val profile = JavaCaller.allocating(5)
        val plan = profile.coarseTree.single { it.name == "java:plan" }
        val mb = (1 shl 20).toDouble()
        assertTrue(profile.allocatedPerExecution(plan) in 3 * mb..3 * mb + 4096, "${profile.allocatedPerExecution(plan)}")
    }

    @Test
    fun `Java saves, loads and diffs a folder`() {
        val folder = Files.createTempDirectory("java-caller")
        val report = JavaCaller.report(JavaCaller.run(1), folder)
        assertTrue("java:rule" in report)
    }
}
