# TickSnick

A profiler for the JVM that measures operations you name, down to a few nanoseconds each, and says how
much each one costs and where its parent's time went.

You wrap the code you care about in a label. A labelled operation can be a 100 ns hash lookup called
half a billion times, or a 30-second batch. TickSnick reports, per label:

- **its share of the time and its cost per call**, by sampling, not by timing each call. A label costs
  about 2 ns, so the cheapest operations can carry one.
- **for longer operations, every execution timed**: counts, percentiles, the slowest executions, and
  a tree of which operation ran inside which, across threads.
- **how far to trust each number**: how much the runs differ, GC pauses, the CPU clock, threads that
  waited rather than ran.

Apache-2.0. Java 21 or later. Its only dependency is the Kotlin standard library.

```kotlin
dependencies { implementation("com.ticksnick:ticksnick:0.5.0") }
```

## Quick start

```kotlin
import com.ticksnick.Profiler
import com.ticksnick.TickSnick
import com.ticksnick.op
import java.nio.file.Path

// Once, at startup: register each operation and keep the handle.
val BATCH = Profiler.registerCoarse("batch")   // long: every execution timed
val PARSE = Profiler.registerFine("parse")     // short: sampled

fun main() {
    Profiler.start()
    repeat(3) {
        op(BATCH) {
            for (line in input) op(PARSE) { parse(line) }
        }
    }
    Profiler.stop().save(Path.of("profiles", "baseline"))   // a new run in the folder

    println(TickSnick.load(Path.of("profiles", "baseline")).render())
}
```

After a change, save the next runs into a new folder, then compare the two folders:

```kotlin
val before = TickSnick.load(Path.of("profiles", "baseline"))
val after = TickSnick.load(Path.of("profiles", "presized"))
println(TickSnick.diff(before, after))
```

[docs/output.md](docs/output.md) explains every part of the report.

## Fine and coarse labels

There are two kinds of label, and you choose one when you register the operation. Wrapping code looks
the same for both: `op(X) { ... }`.

**Fine** (`registerFine`) is for short operations, from about 50 ns up on a fast machine. Entering a fine label writes
its id to a slot of the current thread. A sampling thread reads every thread's slot once a
millisecond and counts what it finds. The share of samples is the share of time. Per call cost comes
from the time and an exact call count. No call is timed, so the label costs about as much as a field
write.

**Coarse** (`registerCoarse`) is for operations of a few microseconds or more: a request, a batch, a
phase of an algorithm. Each execution is timed at both ends, about 50 ns. You get exact counts,
percentiles and a tree of which operation ran inside which. A coarse operation also shows which fine
labels its own time went to, and how many times each was called per execution.

Below its floor a fine label is a visible part of what it measures. The report says so and names the
label. The floors are counted in bodies, a fixed piece of CPU work that TickSnick times once a second
during the run, so they follow the machine and its clock: a fine label needs 2 bodies, about 50 ns on a
fast machine, and a coarse one 100 bodies, about 2.5 us. Label the enclosing loop instead, and pass the number of operations inside:

```kotlin
op(LOOKUP, times = keys.size) { for (k in keys) table.find(k) }
```

### Families, keys and chains

```kotlin
// One row per index, or per enum constant.
val LEVEL = Profiler.registerCoarse("level", arity = 6)
for (depth in 0..5) op(LEVEL[depth]) { expand(depth) }

val RESOLVE = Profiler.registerCoarse<Kind>("resolve")
op(RESOLVE[kind]) { resolve(root, kind) }

// Keep the slowest executions with a key, so the report can name them.
op(REQUEST, key = { request.id }) { handle(request) }

// In the middle of a chain: only the block is labelled.
list.op(FILTER) { filter { it.valid } }
    .op(SORT) { sorted() }
```

### Threads

A coarse operation that hands work to other threads keeps it as its own when the tasks carry its
context:

```kotlin
val pool = Executors.newFixedThreadPool(8).propagating()

op(BATCH) {
    roots.map { root -> pool.submit(Callable { op(RESOLVE) { resolve(root) } }) }.forEach { it.get() }
}
```

`Runnable.propagating()` and `Callable.propagating()` wrap a single task. `captureCoarse()` and
`withCoarse(ctx) { }` do the same by hand, for threads you start yourself.

A thread's slot is freed when the thread ends. `Profiler.release()` frees it at once.

### A boundary that is not a block

```kotlin
Profiler.enter(RULE)
// ... a callback later ...
Profiler.exit(RULE)

Profiler.expectBalanced()   // where no label should be open
```

`enter` and `exit` have no `finally`, so an exception between them leaves the label set. TickSnick
counts that as a leak and stops the session at the first one: every sample after it would be charged
to the wrong operation. The run has failed: its report says why and shows no figures, and a profile
leaves it out. TickSnick never reports figures it knows are false, so there is no switch to profile
anyway. Where the labels are in code you cannot fix, label one level up, where a `finally` fits.

### Settings

`Profiler.start()` takes:

- `tickMillis` (1.0): the time between ticks, a tick being one wake-up of the sampler. Sampling every
  thread takes a few microseconds, so it is set by how many threads there are, not by the resolution
  you want.
- `wait` (`SPIN` with 4 or more processors, `PARK` below): how the sampler waits between samples.
  `SPIN` takes a core and keeps the interval exact. `PARK` and `SLEEP` leave the core free, and the
  interval grows longer and less regular. The report's `Sampling` line says which one ran.
- `jitter` (0.25): each interval varies at random by up to this fraction, so sampling does not fall
  in step with a loop in the program.
- `sampleState` (true): record whether each sampled thread was running or waiting.
- `allocations` (false): count the bytes allocated under each coarse operation, for the report's
  `Allocated` column. It adds about 30 ns to each coarse execution. It needs a JVM that reports
  them, as HotSpot and OpenJ9 do; on one that does not, the session runs without and the report says so.
  Work on virtual threads is not counted: its rows read `-`.
- `tags` (none): what the run cannot say by itself, see [Tags and setup](#tags-and-setup).

### From Java

The same jar. In Java a handle is an `int`, and a block is a try-with-resources:

```java
static final int BATCH = Profiler.registerCoarse("batch");
static final int PARSE = Profiler.registerFine("parse");

Profiler.start();
try (var b = Profiler.coarse(BATCH)) {
    for (var line : input) try (var p = Profiler.fine(PARSE)) { parse(line); }
}
Profiler.stop().save(Path.of("profiles", "baseline"));
System.out.println(TickSnick.load(Path.of("profiles", "baseline")).render());
```

- A fine and a coarse handle are both `int`, so Java cannot tell them apart. The calls name the
  kind instead: `fine` and `coarse`, `enterFine`/`exitFine` and `enterCoarse`/`exitCoarse`. Passing
  one kind to the other's call compiles and reports the wrong operation.
- Opening a scope allocates nothing: each operation has one, made at startup.
- `Profiler.fine(LOOKUP, keys.size())` is `op(LOOKUP, times = keys.size) { }`.
- Families: `LEVEL.get(depth)`, and `Profiler.registerCoarse("resolve", Kind.class)` for an enum.
- Settings by position, from the left: `Profiler.start(1.0, WaitStrategy.PARK)`.
- Tags with `Profiler.tag("warmups", 2)` after `start`.
- `enterFine` and `exitFine` around a block need a `finally`, as a lock does.

## Runs and profiles

A **profile** is how a program behaves: same code, same workload, same setup. A **run** is one
measurement of it, what one session leaves behind. One JVM differs from the next: the CPU clock, the
JIT and the heap layout all vary, and one run cannot say how much. So a profile is built from several
runs, and on disk it is a folder of them.

- `Profiler.stop()` returns the run. `save(folder)` writes it as a new `.tsr` file in the folder,
  named by the time. A save never overwrites another run.
- **A folder holds one profile.** Save a run that differs in code, workload or setup to a new folder.
  If a run should not count, move it out of the folder. Each run should come from its own JVM.
- `TickSnick.load(folder).render()` reports the profile: every figure is the mean of the runs, with
  its 95% interval.
- `TickSnick.diff(before, after)` compares two profiles and gives each change its 95% interval. A
  change that could be noise is marked `?`. Which of code, workload or setup differs between the two
  is the question it answers.

Reports are ASCII. `render(unicode = true)` and `diff(before, after, unicode = true)` print `±`, `µs`,
`→`, `—`, `…` and `×` instead, for output that is read as Unicode; the CLI takes `--unicode`. On Windows,
output through a pipe (Gradle, a script) is written in the platform's charset unless the JVM has
`-Dstdout.encoding=UTF-8`.

A single run renders as a report of its own. It warns that its times have no interval. Its shares of
the parent are still reliable.

## What it costs

On a fast current machine, where a body (TickSnick's unit of CPU work) takes about 25 ns:

- **The sampler takes a core.** With 4 or more processors it spins between samples, so one core is
  busy for the whole session. A program that leaves a core free loses nothing to it; one that uses
  every core loses that core. `wait = PARK` frees it and samples less exactly.
- **A fine label:** about 1-3 ns a call, at most 2.5 ns by design. Beside other work it is often
  hidden altogether. A batch label, `op(X, times = n)`, costs one call for its n.
- **A coarse label:** about 45-55 ns an execution, at most 100 ns, and 56 bytes for its context, which
  `Allocated` leaves out. Most of it is two `System.nanoTime()` calls, so it costs more where
  `nanoTime` is slow, as on some VMs.
  `Allocated` adds about 30 ns, and each registered fine label about 0.5 ns.
- **A hand-off** (`captureCoarse` and `withCoarse`): about 7 ns.
- **In the background:** a body timed once a second, under 0.1% of a core; the CPU clock read from the
  OS once a second, on Windows by a `typeperf` process; and a few ms at `stop()`.
- **On your program** the cost is per label entered, not per second of work: a fine label on a 50 ns
  operation is about 5% of it. The report warns where a label is under its floor, and when the hooks
  may add more than 1% to the work inside labels, with the most they can add to the wall time.

## Tags and setup

A run reads its own setup at `start`: the Java version, the JVM, the OS, the CPU, the processors, the
heap, the collector and the JVM flags that change how it runs: heap, collector, processors and
compiler, and no others, so no path or secret. It cannot see what the program did before `start`,
or which workload it was given. Tags say that:

```kotlin
Profiler.start(tags = mapOf("warmups" to 2, "batches" to 3, "roots" to "org.apache.*"))
Profiler.tag("threads", pool.size)   // known later, or from Java
```

A report prints both under its warnings. TickSnick warns but does not refuse: when the runs of a
folder differ in a tag or in the setup, the report lists the values, `warmups: 1, 2; batches: 3..10`,
and one warning names them. A diff shows what differs between its two profiles, and warns only
when one of them is mixed. Tags and setup are saved in the `.tsr` file, so nothing secret belongs in a
tag.

The same from the command line, with the jar and the Kotlin standard library on the classpath:

```
java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli render profiles/baseline
java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli diff profiles/baseline profiles/presized
```

In a clone of this repository: `./gradlew cli --args="render profiles/baseline"`.

## What it does not do

- **It sees only what you label.** Time outside every label shows as `(no operation)`, with no
  breakdown. Use a stack profiler first to find where to put labels.
- **A share is not a saving.** An operation that takes 40% of the time can be worth far more than
  40% if removing it would also remove work it causes elsewhere. It can also be worth less, if the
  work moves somewhere else.
- **Coroutines and virtual threads are not tested.** A fine label belongs to the thread that set it.
  A coroutine that suspends inside a label and resumes on another thread leaves the label behind.
- Up to 256 operations, and up to 1024 threads at once. A thread past that is not sampled, and the
  report says so.
- The CPU clock is recorded on Windows and Linux. On macOS it needs root, so the report says it was
  not measured.
- **GraalVM Native Image is not supported.** It compiles the program ahead of time, and much of the
  JVM's management interface, which TickSnick reads its checks from, is missing or limited there.
  GraalVM in its usual JIT mode is HotSpot.

## Contribution

Pull requests are not accepted. Bug reports and ideas are welcome in Issues.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
