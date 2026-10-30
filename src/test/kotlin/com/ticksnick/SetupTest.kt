package com.ticksnick

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a run says about itself, tags and setup, and what a profile and a diff do with it: a folder
 * that mixes them gets a warning, two folders that differ only show it, and every warning about a
 * run reaches the report of its profile and every diff of it.
 */
class SetupTest {

    @Test
    fun `values read as one, as the different ones, and past a few as a range or a count`() {
        assertEquals("2", valuesOf(listOf("2", "2")))
        assertEquals("1, 2", valuesOf(listOf("1", "2", "1")))
        assertEquals("3-10", valuesOf(listOf("10", "3", "7", "4")))
        assertEquals("a, b, c and 1 more", valuesOf(listOf("a", "b", "c", "d")))
    }

    @Test
    fun `a line gives every name, and a run without one reads as a dash`() {
        val runs = listOf(mapOf("warmups" to "1", "batches" to "3"), mapOf("warmups" to "2", "batches" to "3"), mapOf("warmups" to "2"))
        assertEquals("warmups: 1, 2; batches: 3, -", factsLine(runs))
        assertEquals(listOf("warmups", "batches"), mixedNames(runs))
        assertEquals(emptyList(), mixedNames(listOf(mapOf("a" to "1"), mapOf("a" to "1"))))
    }

    @Test
    fun `a diff line says what differs and, when asked, what does not`() {
        val before = listOf(mapOf("step" to "naive", "batches" to "3"))
        val after = listOf(mapOf("step" to "centralEdges", "batches" to "3"))
        assertEquals("step: naive -> centralEdges; batches: 3 =", factsDiffLine(before, after, all = true))
        assertEquals("step: naive -> centralEdges", factsDiffLine(before, after, all = false))
    }

    @Test
    fun `a session keeps its tags, reads its setup, and starts the next one clean`() {
        Profiler.start(tags = mapOf("warmups" to 2, "batches" to 3))
        Profiler.tag("batches", 5)
        Profiler.tag("roots", "small")
        val run = Profiler.stop()
        assertEquals(mapOf("warmups" to "2", "batches" to "5", "roots" to "small"), run.tags)
        assertTrue(run.setup.jvm != null, "no JVM identity")
        assertTrue("Java" in run.setup.facts && "Heap" in run.setup.facts, run.setup.facts.toString())
        assertFalse(run.setup.facts.values.any { "-D" in it }, run.setup.facts.toString())

        Profiler.start()
        assertEquals(emptyMap(), Profiler.stop().tags)
        assertFailsWith<IllegalStateException> { Profiler.tag("late", 1) }
    }

    @Test
    fun `tags and setup survive the file`() = inTemp { dir ->
        val file = dir.resolve("a$RUN_EXTENSION")
        val saved = run(tags = mapOf("roots" to "org.apache.*", "odd\tname" to "a\nb"), setup = setup("18 GB", "1@1"))
        RunStore.save(saved, file)
        val loaded = RunStore.load(file)
        assertEquals(saved.tags, loaded.tags)
        assertEquals(listOf("roots", "odd\tname"), loaded.tags.keys.toList())
        assertEquals(saved.setup.facts, loaded.setup.facts)
        assertEquals("1@1", loaded.setup.jvm)
    }

    @Test
    fun `a run saved without tags or setup loads with none`() = inTemp { dir ->
        val file = dir.resolve("a$RUN_EXTENSION")
        RunStore.save(run(), file)
        val text = Files.readAllLines(file).filter { !it.startsWith("tags\t") && !it.startsWith("setup") }
        Files.write(file, text)
        val loaded = RunStore.load(file)
        assertEquals(emptyMap(), loaded.tags)
        assertEquals(emptyMap(), loaded.setup.facts)
    }

    @Test
    fun `a report shows its tags and setup under the warnings, above the numbers`() = inTemp { dir ->
        val folder = dir.resolve("naive")
        run(tags = mapOf("warmups" to "2"), setup = setup("18 GB", "1@1")).save(folder)
        val lines = TickSnick.load(folder).render().lines()
        val tags = lines.indexOfFirst { it.startsWith("Tags") }
        val setup = lines.indexOfFirst { it.startsWith("Setup") }
        assertTrue(lines.indexOfFirst { it.startsWith("CAREFUL") } in 0 until tags && tags < setup, lines.joinToString("\n"))
        assertTrue(setup < lines.indexOfFirst { it.startsWith("Wall time") }, lines.joinToString("\n"))
        assertEquals("warmups: 2", lines[tags].removePrefix("Tags").trim())
        assertTrue("Heap: 18 GB" in lines[setup], lines[setup])
    }

    @Test
    fun `runs that agree say so once and warn about nothing`() = inTemp { dir ->
        save(dir, "a", run(tags = mapOf("warmups" to "2"), setup = setup("18 GB", "1@1")))
        save(dir, "b", run(tags = mapOf("warmups" to "2"), setup = setup("18 GB", "2@2")))
        val text = Profile.load(dir).render()
        assertTrue(text.lines().any { it.startsWith("Tags") && it.endsWith("warmups: 2") }, text)
        assertTrue("CAREFUL" !in text, text)
    }

    @Test
    fun `runs that differ show their values and get one line, last`() = inTemp { dir ->
        save(dir, "a", run(tags = mapOf("warmups" to "1", "batches" to "3"), setup = setup("12 GB", "1@1"), imbalances = 1))
        save(dir, "b", run(tags = mapOf("warmups" to "2", "batches" to "10"), setup = setup("18 GB", "2@2")))
        val lines = Profile.load(dir).render().lines()
        assertTrue(lines.any { it.startsWith("Tags") && it.endsWith("warmups: 1, 2; batches: 3, 10") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("Setup") && "Heap: 12 GB, 18 GB" in it }, lines.joinToString("\n"))
        val mix = lines.filter { "they differ in" in it }
        assertEquals(1, mix.size, lines.joinToString("\n"))
        assertTrue("warmups, batches, Heap" in mix[0], mix[0])
        assertTrue(lines.indexOf(mix[0]) > lines.indexOfFirst { "labels: 1 were still open" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `how a run measured counts as setup`() = inTemp { dir ->
        save(dir, "a", run(setup = setup("18 GB", "1@1"), allocations = true, askedStepNanos = 1_000_000))
        save(dir, "b", run(setup = setup("18 GB", "2@2"), askedStepNanos = 2_000_000))
        val mix = Profile.load(dir).render().lines().first { "they differ in" in it }
        assertTrue("Sampling, Allocated" in mix, mix)
    }

    @Test
    fun `a run saved without a setup does not count as differing`() = inTemp { dir ->
        save(dir, "a", run(setup = setup("18 GB", "1@1")))
        save(dir, "b", run())
        val text = Profile.load(dir).render()
        assertFalse("they differ in" in text, text)
    }

    @Test
    fun `two runs of one JVM are called out, the interval counts them as independent`() = inTemp { dir ->
        save(dir, "a", run(setup = setup("18 GB", "1@1")))
        save(dir, "b", run(setup = setup("18 GB", "1@1")))
        save(dir, "c", run(setup = setup("18 GB", "2@2")))
        val text = Profile.load(dir).render()
        assertTrue("2 of 3 came from one JVM" in text, text)
    }

    @Test
    fun `a run's own warning reaches the report over runs, once, with how many said it`() = inTemp { dir ->
        save(dir, "a", run(imbalances = 1))
        save(dir, "b", run(imbalances = 1))
        save(dir, "c", run(imbalances = 2))
        val lines = Profile.load(dir).render().lines()
        assertEquals(1, lines.count { "labels: 1 were still open" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "labels: 1 were still open" in it && "(2 of 3 runs)" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "labels: 2 were still open" in it && "(1 of 3 runs)" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `a run that stopped early is said in the report over runs`() = inTemp { dir ->
        save(dir, "a", run(failure = "a label leaked"))
        save(dir, "b", run())
        val text = Profile.load(dir).render()
        assertTrue("profiling stopped: a label leaked" in text && "(1 of 2 runs)" in text, text)
    }

    @Test
    fun `a diff repeats each side's warnings, naming the side`() = inTemp { dir ->
        val before = dir.resolve("before")
        val after = dir.resolve("after")
        save(before, "a", run(imbalances = 1, tags = mapOf("warmups" to "1")))
        save(before, "b", run(tags = mapOf("warmups" to "2")))
        save(after, "a", run(imbalances = 2))
        save(after, "b", run(imbalances = 2))
        val text = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertTrue("(1 of 2 runs in 'before')" in text, text)
        assertTrue("(2 of 2 runs in 'after')" in text, text)
        assertTrue("runs in 'before': they differ in warmups" in text, text)
    }

    @Test
    fun `a long section cannot hide that a profile is mixed`() = inTemp { dir ->
        val before = dir.resolve("before")
        val after = dir.resolve("after")
        for (i in 1..MAX_CAREFUL + 2) {
            save(before, "a$i", run(imbalances = i, tags = mapOf("warmups" to "$i")))
            save(after, "a$i", run(imbalances = 100 + i, tags = mapOf("batches" to "$i")))
        }
        val text = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertTrue("more" in text.lines().first { it.contains("... and") }, text)
        assertTrue("runs in 'before': they differ in warmups" in text, text)
        assertTrue("runs in 'after': they differ in batches" in text, text)
        val report = TickSnick.load(before).render()
        assertTrue("runs: they differ in warmups" in report, report)
        assertTrue(report.lines().indexOfFirst { "... and" in it } < report.lines().indexOfFirst { "they differ in" in it }, report)
    }

    @Test
    fun `the diff of one run on each side repeats their warnings too`() = inTemp { dir ->
        val before = dir.resolve("before")
        val after = dir.resolve("after")
        run(imbalances = 1).save(before)
        run(imbalances = 2).save(after)
        val text = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertTrue("(in 'before')" in text && "(in 'after')" in text, text)
    }

    @Test
    fun `a difference between two profiles is shown and is not a warning`() = inTemp { dir ->
        val before = dir.resolve("before")
        val after = dir.resolve("after")
        for (name in listOf("a", "b")) {
            save(before, name, run(tags = mapOf("step" to "naive", "batches" to "3"), setup = setup("12 GB", "1$name")))
            save(after, name, run(tags = mapOf("step" to "centralEdges", "batches" to "3"), setup = setup("18 GB", "2$name")))
        }
        val text = TickSnick.diff(TickSnick.load(before), TickSnick.load(after))
        assertTrue(text.lines().any { it.startsWith("Tags") && it.endsWith("step: naive -> centralEdges; batches: 3 =") }, text)
        assertTrue(text.lines().any { it.startsWith("Setup") && it.endsWith("Heap: 12 GB -> 18 GB") }, text)
        assertFalse("they differ in" in text, text)
    }

    @Test
    fun `a diff has no setup line when the setups agree or a side has none`() = inTemp { dir ->
        val before = dir.resolve("before")
        val after = dir.resolve("after")
        val none = dir.resolve("none")
        for (name in listOf("a", "b")) {
            save(before, name, run(setup = setup("18 GB", "1$name")))
            save(after, name, run(setup = setup("18 GB", "2$name")))
            save(none, name, run())
        }
        assertFalse(TickSnick.diff(TickSnick.load(before), TickSnick.load(after)).lines().any { it.startsWith("Setup") })
        assertFalse(TickSnick.diff(TickSnick.load(before), TickSnick.load(none)).lines().any { it.startsWith("Setup") })
    }

    private fun inTemp(body: (Path) -> Unit) {
        val dir = Files.createTempDirectory("ticksnick")
        try {
            body(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun save(dir: Path, name: String, run: Run) {
        Files.createDirectories(dir)
        RunStore.save(run, dir.resolve("$name$RUN_EXTENSION"))
    }

    private fun setup(heap: String, jvm: String) = Setup(mapOf("Java" to "21", "Heap" to heap), jvm)

    private fun run(
        tags: Map<String, String> = emptyMap(),
        setup: Setup = Setup.NONE,
        imbalances: Int = 0,
        failure: String? = null,
        allocations: Boolean = false,
        askedStepNanos: Long = 0,
    ) = Run(
        operations = listOf(OperationStat(0, "work", 10, 10, 0, 0, 0, 0, 10)), idleHits = 0, ticks = 1_001,
        samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 1,
        duty = Duty(
            labelledDuty = 0.999, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 1,
            cpuNanos = 999_000_000, wallNanos = 1_000_000_000, minWindowDuty = 0.999, maxWindowDuty = 0.999,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        failure = failure,
        imbalances = imbalances,
        coarse = listOf(CoarseStat(0, "runBatch", 1, 1_000_000_000, 1_000_000_000, 1_000_000_000, Spans(), 10, 10, 10, 10, 10, 10, LongArray(MAX_OPERATIONS + 1))),
        coarseTree = listOf(
            CoarseNode(0, "runBatch", 1, 1_000_000, 1_000_000, 1_000_000, Spans.of(listOf(1_000_000L)), 10, 10, LongArray(MAX_OPERATIONS + 1), 0, emptyList())
        ),
        askedStepNanos = askedStepNanos,
        allocations = allocations,
        setup = setup,
        tags = tags,
    )
}
