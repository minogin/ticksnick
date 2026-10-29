# Changelog

Versions 0.1.0 to 0.4.0 are on Maven Central. This file starts with 0.5.0.

## Unreleased

### Callable from Java

- `Profiler` and `TickSnick` are called statically: `Profiler.start()`, not `Profiler.INSTANCE`.
  `start` has overloads that drop arguments from the right.
- Handles are `int` in Java. `registerFine`, `registerCoarse` and the families' `get` return one.
- `enterFine`, `exitFine`, `enterCoarse` and `exitCoarse` are `enter` and `exit` under Java names.
  `enterFine(op, times)` is new, in Kotlin `enter(op, times)`.
- `Profiler.fine(op)`, `fine(op, times)` and `coarse(op)` enter and return a `FineScope` or a
  `CoarseScope` for try-with-resources. `close()` exits. Nothing is allocated.
- `registerFine(name, Kind.class)` and `registerCoarse(name, Kind.class)` register an enum family.
- The Kotlin API is unchanged.

### Fixed

- Calls made right after `Profiler.start()` are counted. `start()` returns once the sampler has
  taken its starting counts; a session shorter than the sampler thread's start-up counted no calls.

## 0.5.0 - 2026-10-27

### Runs are saved to folders and read as a set

- `Profile.save(folder)` writes the run as a new file in the folder, named by the time, and returns
  the file. Two runs saved in the same second, from two threads or two JVMs, get two files.
- `TickSnick.load(folder).render()` reports every run in the folder as one. Each figure is the mean
  of the runs with its 95% interval (Student's t), with one sample per run. The report lists the
  runs with their wall time, clock and the durations of the root operation.
- `TickSnick.diff(before, after)` compares two folders. Each change has a 95% interval (Welch's t).
  A row says `faster` or `slower` only when the whole interval is on one side of zero, and
  `unclear` otherwise.
- A folder of one run renders as a single report, with a warning that its times have no interval.
- In the report over runs, a fine label that was called in a run but never sampled counts as 0 in
  that run, not as missing.

### Breaking changes from 0.4.0

- `Profile.save(path)` is now `Profile.save(folder)`. Use `TickSnick.load` and `TickSnick.diff` in
  place of `Profile.load`, `Profile.render`, `diffFrom` and `diffAgainstLatest`.
- The public API is what a user calls:
  - `Profiler`: `start`, `stop`, `registerFine`, `registerCoarse`, `enter`, `exit`,
    `expectBalanced` and `release`.
  - `op`, `captureCoarse`, `withCoarse` and the `propagating` wrappers.
  - `TickSnick`, `ProfileRuns.render` and `WaitStrategy`.
  - `Profile`, whose only member is `save`.

  Everything else is internal.
- The command line takes folders: `render <folder>` and `diff <before> <after>`.
- Profiles are saved in format 2. Files saved by 0.4.0 and earlier are refused, not misread.

### The report

- All warnings are in one `CAREFUL` section under the report's first line, worst first. Each has the
  same shape: what it is about, what happened, and what it means or what to do.
- The logo is printed once per JVM and is not part of any report.
- Percentiles are exact up to 2,624 executions of an operation. Past that they are within 0.8%.
- A GC pause on a row run by several threads is counted once, not once per thread.
- Past 30 degrees of freedom, the interval uses the t distribution, not 1.96.

### Fixes

- A report taken while an operation recorded its 2,625th execution could lose that operation's
  durations.
- The command line prints a message and exits with code 2 on a bad argument, an empty folder or a
  file of another format, with no stack trace.
