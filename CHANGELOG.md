# Changelog

Versions 0.1.0 to 0.4.0 are on Maven Central. This file starts with 0.5.0.

## Unreleased

### How a figure is written

- A unit sticks to its figure, and the one space is between a value and its interval: `3.27s +-77%`,
  `57 +-0.3%`, `-2.46 +-6.17s`, `+5.3 +-0.6pp`.
- A change that could be noise has its `?` on the value: `-27? +-67%`, `+5.3? +-0.6pp`.
- `Profile.render(unicode = true)`, `TickSnick.diff(before, after, unicode = true)` and the CLI's
  `--unicode` print `±`, `µs`, `→`, `—`, `…` and `×` in place of `+-`, `us`, `->`, ` - `, `...` and `x`.
  ASCII stays the default.

### Runs and profiles

- `Profiler.stop()` returns a `Run`, which was `Profile`. `TickSnick.load(folder)` returns a `Profile`,
  which was `ProfileRuns`. A profile is how a program behaves, and a run is one measurement of it.
- Saved runs are `.tsr` files and start with `ticksnick-run 3`. Older files are refused.
- `Profiler.start(stepMillis = ...)` is now `tickMillis`.

### Tags and setup

- `Profiler.start(tags = mapOf("warmups" to 2))` and `Profiler.tag(name, value)` say what a run cannot
  say by itself.
- A run saves its setup: Java version, JVM, OS, CPU, processors, heap, collector, `-X` and `-XX` flags,
  and the JVM's identity.
- Every report prints `Tags` and `Setup` lines under its warnings. A diff prints every tag and what
  differs in the setup.
- A folder whose runs differ in a tag, the setup, the sampling interval, the wait strategy or
  `allocations` gets one `CAREFUL` line, last, and runs of one JVM get another.

### One set of tables

- A report and a diff have the same columns, names and forms, however many runs are behind them. The
  report over runs gains `Per parent`, `Parallel`, `Paused`, `Max`, `Runnable / Waiting`, the `-` rows
  under coarse rows, and the fine table's other columns.
- A report prints the same lines however many runs are behind it. One run gains `Runs`, the run list and
  its root row; several gain `Cores`, `Sampling` and `Sampled`.
- A diff has everything a report has. A row is three lines: before, after, and the change.
- `and N more` is a row, with the calls and time of the rows it folds.
- Work outside a span, a label not in the tree, a closed execution and `on a CPU` over what the CPU
  allows are warnings now, so they reach a report of several runs and a diff.
- The coarse table has groups, `Load` and `Long`, as the fine table has. `Runnable / Waiting` moved into
  `Load`, before `Mean`.
- The fine table has no `Trust` group: `Hits` and `Noise` are gone. The `+-` includes sampling noise, and
  the `Sampled` line says how many samples there are.
- `Execs` is `Long calls`, and `Per call` is `Thread-time per call` in every table.
- `Allocated` is a group of two columns: `Total`, which adds up down the tree, and `Per execution`.
- `+-` is on what says how much, `Runnable / Waiting` included, and not on percentiles, `Max`, or the
  `Long` and `Spread` groups. `Allocated` is counted and has none: the mean of the runs, plainly.
- A change's interval is in percentage points: `-22% +-2.2 pp`.
- `faster`, `slower` and `unclear` are gone. A change that could be noise is marked `?`, held to 5%
  divided by the number of changes in the diff, with a note under the table.
- A figure and its `+-` in one unit say the unit once, at the right: `52 +-0.4%`, `117 +-0.444 s
  (0.4%)`, and in a diff `-22 +-2.2%` and `+5.3 +-0.6 pp`. A `+-` that is a percent of its figure
  stays as it was: `42.5 s +-1.1%`. A change's `?` goes last: `-7.7 +-9.5%?`.

### Warnings

- The report over runs and both diffs show each run's own warnings, which only the report of one run
  did. The same warning from several runs is one line that says how many.
- A diff shows the warnings of both its profiles, each naming its profile.
- The coarse floor is a fixed 2 us: 5% of the most a coarse execution may cost with nothing opted
  into, 100 ns. Each registered fine label adds up to 0.5 ns and counting allocated bytes up to 30 ns,
  and the floor rises by 20 times that. The warning about an operation under the floor says what it is
  made of: `needs 2.61 us here (2.00 us, + 600 ns for Allocated, + 10.0 ns for 1 fine label)`.

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

### The sampler on small machines

- With fewer than 4 processors the sampler parks between samples by default instead of spinning,
  which held one of them. `wait = WaitStrategy.SPIN` still spins.
- The report has a `Sampling` line: the interval achieved, and whether the sampler held a processor
  or left it free. A spinning sampler on fewer than 4 processors is named with the remedy.

### Fine calls per coarse execution

- Fine calls are counted on the coarse path they ran under. A `-` row shows its calls in
  `Executions`, its calls per execution of the row in `Per parent`, and its own time per call in
  `Mean`. A label called on a path and never sampled there gets a row with its calls.
- `x parent` is now `Per parent`, and its figures have no `x`: `5,262`, and `3` for what was `x3.0`.
- `in all, under N operations` is gone. A row on more than one parent says `(3 parents)`, and its whole
  is a row of `Under multiple parents` right under the tree, coarse and fine sorted together by Wall.
- Diffs show the `-` rows under coarse rows, and a `Per parent` column. What a diff shows has the
  name and form it has in the report.
- Each coarse execution costs a little more for every registered fine label, and the coarse floor
  check takes the number of fine labels into account.

### Bytes allocated per coarse execution

- `Profiler.start(allocations = true)` counts the bytes allocated under each coarse path, on every
  thread that runs under it. Off by default.
- The report, the report over runs and both diffs gain an `Allocated` column, bytes per execution
  with coarse children included. Without the setting the tables are unchanged.
- Each coarse execution costs more with it on, and the coarse floor check takes that into account.
  A diff warns when only one side counted.
- On a JVM that cannot report allocated bytes the session runs without them and the report says so.

### Fixed

- Calls made right after `Profiler.start()` are counted. `start()` returns once the sampler has
  taken its starting counts; a session shorter than the sampler thread's start-up counted no calls.
- A long operation name no longer pushes its row out of line. Each table's name column grows to the
  longest name in it, and the rules grow with it. Names are not cut.
- A fine label no longer shows in a coarse row's own time where it never ran. The sampler read a
  thread's fine label and its coarse execution one after the other, and a thread that closed both in
  between was charged to the parent: a row with no calls, and an extra parent in `(N parents)`. The
  two are now read as one instant.

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
