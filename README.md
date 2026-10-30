# TickSnick

A profiler for the JVM that measures operations you name, down to a few nanoseconds each, and says how
much each one costs and where its parent's time went.

You wrap the code you care about in a label. A labelled operation can be a 100 ns hash lookup called
half a billion times, or a 30-second batch. TickSnick reports, per label:

- **its share of the time and its cost per call**, by sampling, not by timing each call. A label costs
  about 2 ns, so the cheapest operations can carry one.
- **for longer operations, every execution timed**: counts, percentiles, the slowest executions, and
  a tree of which operation ran inside which, across threads.
- **how far to trust each number**: the sampling noise, GC pauses, the CPU clock, threads that waited
  rather than ran.

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

**Fine** (`registerFine`) is for short operations, from about 50 ns up. Entering a fine label writes
its id to a slot of the current thread. A sampling thread reads every thread's slot once a
millisecond and counts what it finds. The share of samples is the share of time. Per call cost comes
from the time and an exact call count. No call is timed, so the label costs about as much as a field
write.

**Coarse** (`registerCoarse`) is for operations of a few microseconds or more: a request, a batch, a
phase of an algorithm. Each execution is timed at both ends, about 50 ns. You get exact counts,
percentiles and a tree of which operation ran inside which. A coarse operation also shows which fine
labels its own time went to, and how many times each was called per execution.

Below 50 ns a fine label is a visible part of what it measures. The report says so and names the
label. Label the enclosing loop instead, and pass the number of operations inside:

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
counts that as a leak and, by default, stops the session at the first one: every sample after it would
be charged to the wrong operation. Pass `Profiler.start(strict = false)` when the labels are in code
you cannot fix. The leaks are still counted and reported.

### Settings

`Profiler.start()` takes:

- `stepMillis` (1.0): the sampling interval. Sampling every thread takes a few microseconds, so the
  interval is set by how many threads there are, not by the resolution you want.
- `wait` (`SPIN` with 4 or more processors, `PARK` below): how the sampler waits between samples.
  `SPIN` takes a core and keeps the interval exact. `PARK` and `SLEEP` leave the core free, and the
  interval grows longer and less regular. The report's `Sampling` line says which one ran.
- `jitter` (0.25): each interval varies at random by up to this fraction, so sampling does not fall
  in step with a loop in the program.
- `strict` (true): stop at the first leaked label, see above.
- `sampleState` (true): record whether each sampled thread was running or waiting.

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
- `enterFine` and `exitFine` around a block need a `finally`, as a lock does.

## Runs and folders

A JVM run differs from the next one: the CPU clock, the JIT and the heap layout all vary. One run
cannot say how much. So TickSnick compares sets of runs, not single runs.

- `Profile.save(folder)` writes the run as a new file in the folder, named by the time. A save never
  overwrites another run.
- **A folder holds one version of the code.** Save changed code to a new folder. If a run should not
  count, move it out of the folder.
- `TickSnick.load(folder).render()` reports a folder of several runs as one: every figure is the
  mean of the runs, with its 95% interval.
- `TickSnick.diff(before, after)` gives each change its 95% interval. It says `faster` or `slower`
  only when the whole interval is on one side of zero.

A single run renders as a report of its own. It warns that its times have no interval. Its shares of
the parent are still reliable.

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

## Contribution

Pull requests are not accepted. Bug reports and ideas are welcome in Issues.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
