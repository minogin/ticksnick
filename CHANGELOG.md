# Changelog

Versions 0.1.0 to 0.4.0 are on Maven Central. This file starts with 0.5.0.

## Unreleased

### How a figure is written

- A cell holds a figure, `?` or nothing. `?` is a figure that could not be measured, or was measured
  too little to say: a percentile with too few executions, a label never sampled where it was called,
  `Allocated` on a virtual thread, a run without a tag's value (a run that did not count `Allocated`
  reads `not counted`). It was `-`, which reads as zero in many
  tables. A blank cell has nothing to look at: the column's usual value or a column that does not
  apply to the row.
- `Paused` 0 is blank, not `-`; `unlabelled` has no `Executions` (was `own`); `(no operation)` shows only
  its time; `Long` within the noise is blank; a row on one side of a diff is blank on the other.
- In a diff, `Per parent` 1 and `Paused` 0 print where the other side has a figure, and the header's
  `Paused` line is left out when neither side paused.
- The key under the coarse table says what a percentile's `?` needs: p50, p90 and p99 need 2, 10 and
  100 executions in a run.
- A change in a diff has no `?` any more, and no note under the tables: its interval is the only word on
  how sure it is. `?` now only means that a figure is not known.
- **A figure is written by how sure it is**, the same in a report and a diff. Up to 20% of the figure,
  the figure and its interval relative to it: `6.34s +-5.0%`, a percentage in points, `63% +-9.2pp`.
  Wider, the interval's two ends: `371..561ms`, a share's stopping at 100%. As wide as the figure or more,
  so that it would reach 0: `?`. The forms `35 +-3s` and `63 +-9.2%` are gone.
- **A change in a diff is always a range**, the ends of its 95% interval: `-30..-18%`, `+4.7..+5.9pp`,
  `-5.65..-3.65s` in the header; a decrease stops at -100%. A change from or to a figure that is `?` is
  `?` too.
- **`Waiting` replaces `Runnable / Waiting`**: the part of a row's time spent waiting, blank where
  nothing waited. Runnable was 100% less it.

### The fine table

- `Thread-time` comes first and `Thread-time%` second, in a report and a diff, and a report is sorted by
  `Thread-time`: a diff is read by how much time changed. In one run the two sort the same.

### The coarse table

- `Under multiple parents` has a heading of its own, in the table's places, naming only the columns it
  shows, and a `% root` column where `% parent` stands: each operation's share of the root's Wall.

## 0.6.0 - 2026-11-12

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
- `op` is inline, so code that calls it must be compiled against this version.
- A missing folder says so, `no profile at 'runs/x': no such folder`, and every message quotes its path.

### Tags and setup

- `Profiler.start(tags = mapOf("warmups" to 2))` and `Profiler.tag(name, value)` say what a run cannot
  say by itself.
- A run saves its setup: Java version, JVM, OS, CPU, processors, heap, collector, the JVM flags that
  change how it runs (heap, collector, processors and memory pages, compiler) and no others, and the
  JVM's identity.
- Every report prints `Tags` and `Setup` lines under its warnings. A diff prints every tag and what
  differs in the setup.
- A folder whose runs differ in a tag, the setup, the tick, the wait strategy or `allocations` gets one
  warning, last and never cut by the cap of ten, and runs of one JVM get another.

### One set of tables

- A report and a diff have the same columns, names and forms, however many runs are behind them. The
  report over runs gains `Per parent`, `Parallel`, `Paused`, `Max`, `Runnable / Waiting`, the `-` rows
  under coarse rows, and the fine table's other columns.
- A report prints the same lines however many runs are behind it. One run gains `Runs`, the run list and
  its root row; several gain `Cores`, `Sampling` and `Sampled`.
- A diff has everything a report has. A row is three lines: before, after, and the change.
- `and N more` is a row, with the time of the rows it folds: what adds up. Their calls, of different
  operations, are left blank.
- Fine-label time under no coarse row, a label not in the tree, a closed execution and `on a CPU` over
  what the CPU allows are warnings now, so they reach a report of several runs and a diff.
- The coarse table has groups, `Load` and `Long`, as the fine table has. `Runnable / Waiting` moved into
  `Load`, before `Mean`.
- The fine table has no `Trust` group: `Hits` and `Noise` are gone. The `Sampled` line says how many
  samples there are.
- `Execs` is `Long calls`, and `Per call` is `Thread-time per call` in every table.
- The line under the fine table names both kinds of label it leaves out: `2 operations called but never
  sampled: parse, visit; 1 never called: emit`.
- `+-` is on what says how much, `Runnable / Waiting` included, and not on percentiles, `Max`, or the
  `Long` and `Spread` groups. `Allocated` is counted and has none: the mean of the runs, plainly.
- `faster`, `slower` and `unclear` are gone. A change that could be noise is marked `?`, held to 5%
  divided by the number of changes in the diff, with a note under the table.

### Warnings

- A report of several runs and a diff show each run's own warnings, as a report of one run did. The
  same warning from several runs is one line that says how many, its figures as ranges: `Paused:
  1.20s..1.45s of pauses, 3.10%..3.60% of the run, is counted as work in the times below (2 of 3 runs)`.
- A diff shows the warnings of both its profiles, each naming its profile.
- The warnings are a `WARNINGS` section under a heading, each two spaces in and a cause line further
  in, in place of `CAREFUL` on every line. Every warning is reworded to say what happened, why it matters
  and what to do, for someone new to TickSnick; its subject is a name the report shows, and code is in
  backticks: `` `visit`: 12.0% of its time is in calls over 1.00ms, which its per-call time hides -
  register it coarse, `registerCoarse("visit")` ``. Several became one: the pause warnings, the
  one-run warnings of a report and a diff, the checks of Runnable against CPU time, the clock moving
  during a run and across runs. A root operation run a different number of times is named among what
  the runs differ in. Two are gone: threads that ran a label one at a time, which the `Workers` and
  `Pool` columns show, and CPU clock readings dropped for going backwards.
- A label still open at `stop()` is named, `` `parse`: entered and never exited by `stop()` ``, and so
  is a coarse execution still open; a run saves their names.
- A failed run, stopped by a leaked label, a crossed `exit`, work billed to a closed execution or an
  error in the sampler, is left out of its profile: the report opens with `FAILED: 1 of 4 runs, left
  out of the profile` and each failed run's file and failure. When every run failed there is no
  profile, and the report is that banner alone; so is a diff with such a side. A failure is not a warning, and is not repeated as one.
- `Profiler.start(strict = false)` is gone: a leaked label, a crossed `exit` and work billed to closed
  executions always fail the run, since its figures would be false.
- The floors are recommended limits, rounded up to two digits, the second 0 or 5, and the check uses
  the rounded figure: `` `request`: its executions, 1.20us, are under the recommended 3.5us (Allocated
  adds 650ns) ``. The coarse floor is 100 bodies, about 2.5 us; each registered fine label and counting
  allocated bytes raise it, and the warning names what raised it.
- The run list's `clock` and a diff's `Clock` line are `CPU clock`.

### Callable from Java

- `Profiler` and `TickSnick` are called statically: `Profiler.start()`, not `Profiler.INSTANCE`.
  `start` has overloads that drop arguments from the right.
- Handles are `int` in Java. `registerFine`, `registerCoarse` and the families' `get` return one.
- `enterFine`, `exitFine`, `enterCoarse` and `exitCoarse` are `enter` and `exit` under Java names.
  `enterFine(op, times)` is new, in Kotlin `enter(op, times)`.
- `Profiler.fine(op)`, `fine(op, times)` and `coarse(op)` enter and return a `FineScope` or a
  `CoarseScope` for try-with-resources. `close()` exits. Nothing is allocated.
- `registerFine(name, Kind.class)` and `registerCoarse(name, Kind.class)` register an enum family.
- Calling from Java changes nothing in Kotlin.

### The sampler on small machines

- With fewer than 4 processors the sampler parks between samples by default instead of spinning,
  which held one of them. `wait = WaitStrategy.SPIN` still spins.
- The report has a `Sampling` line: the tick achieved, and whether the sampler held a processor or
  left it free. A spinning sampler on fewer than 4 processors is named with the remedy.

### Fine calls per coarse execution

- Fine calls are counted on the coarse path they ran under. A `-` row shows its calls in
  `Executions`, its calls per execution of the row in `Per parent`, and its own time per call in
  `Mean`. A label called on a path and never sampled there gets a row with its calls, and `-` for its
  time; in a run where it was not sampled it counts at 0 in `Mean`, as in `Wall`.
- `x parent` is now `Per parent`, and its figures have no `x`: `5,262`, and `3` for what was `x3.0`.
- `in all, under N operations` is gone. A row on more than one parent says `(3 parents)`, and its whole
  is a row of `Under multiple parents` right under the tree, coarse and fine sorted together by Wall. A
  recursive operation counts once there, its nested executions inside its whole.
- Diffs show the `-` rows under coarse rows, and a `Per parent` column. What a diff shows has the
  name and form it has in the report.
- Each coarse execution costs a little more for every registered fine label, and the coarse floor
  check takes the number of fine labels into account.

### Bytes allocated per coarse execution

- `Profiler.start(allocations = true)` counts the bytes allocated under each coarse path, on every
  thread that runs under it. Off by default.
- Every report and diff gains an `Allocated` group in the coarse table: `Total`, the row's coarse
  children and every thread under it included, which adds up down the tree, and `Per execution`.
  Without the setting the tables are unchanged.
- Each coarse execution costs more with it on, and the coarse floor check takes that into account.
  A diff warns when only one side counted.
- On a JVM that cannot report allocated bytes the session runs without them and the report says so.
  On a virtual thread, where the JVM gives none, a row and every row above it read `-`, never 0, and
  a warning says why.
- `Allocated` counts the program's bytes only: the label's own context, one per execution, is left
  out, and an execution under a context borrowed by `propagating()` adds nothing of TickSnick's. An
  execution that allocates nothing reads 0.

### Floors that follow the machine

- A body, a fixed piece of CPU work, is timed once a second during a session, and `System.nanoTime()`
  against it once at `stop()`. Both are saved with the run.
- The fine floor is 2 bodies and the coarse floor 100 bodies, read at the run's body: about 50 ns and
  2.5 us on a fast machine, more on a slower one or at a lower clock. The coarse floor rises with a
  slow `nanoTime`. A session too short to time a body is read at 25 ns a body.
- A label under its floor in several runs of a profile is one warning, its floor a range where the runs
  differ: `` `visit`: its calls are under the recommended 60ns..70ns ... (2 of 3 runs) ``.
- ``The labels, mostly `parse`, may add up to P to the work inside them and W to the wall time - fewer,
  larger labels cost less`` warns when the hooks, at their budgets and the run's body, may add more than
  1% to the work inside labels; W is the most they can add to the wall time, all on the run's critical
  path. A batch label counts as one hook entry for its n calls; a run saves how many times each fine
  hook ran.
- The README says what TickSnick costs: the sampler's core, each label, a hand-off, and what runs in the
  background.

### Fixed

- Calls made right after `Profiler.start()` are counted. `start()` returns once the sampler has
  taken its starting counts; a session shorter than the sampler thread's start-up counted no calls.
- A long operation name no longer pushes its row out of line. Each table's name column grows to the
  longest name in it, and the rules grow with it. Names are not cut.
- A fine label no longer shows in a coarse row's own time where it never ran. The sampler read a
  thread's fine label and its coarse execution one after the other, and a thread that closed both in
  between was charged to the parent: a row with no calls, and an extra parent in `(N parents)`. The
  two are now read as one instant.
- The check of `Runnable` against CPU time reads a thread from when it is first seen. When another
  thread had labelled before `start()`, a thread that started labelling after it was left out for up to
  a second, so a shorter session checked only the earlier, idle thread.
- `expectBalanced()` judges only labels placed with `enter`. A lexical `op` around it, or a context a
  task borrowed with `propagating()`, read as a leak. A leak is named by the label `enter` opened.
- Hand-placed labels nest to any depth: past 64, `exit` no longer undoes the wrong level. An `exit`
  with no label open is ignored.
- With `wait = PARK` or `SLEEP`, no pause is counted, charged to a row or taken out of the wall time:
  the sampler's lateness is the scheduler's, and the report says pauses were not measured.
- No `Thread-time%` bound where nothing is labelled fine, or where every labelled sample waited.
- An error in the sampler fails the run; it ended sampling and left a run that read as whole.
- `stop()` on an interrupted thread finishes the session. `start()` refuses a `tickMillis` of 0 or
  less and a `jitter` of 1 or more.
- A batch label, `op(x, times = n)`, is judged against the floor per entry, as its hook runs.
- `Runnable / Waiting` leaves out a run that did not read the state or never caught the row. A diff's
  change from 0 is blank, not `+Infinity%`.
- `stop()` no longer throws `NullPointerException` while another thread is inside a coarse operation
  opened with a key.
- A coarse operation that hands work to other threads is no longer told it is too short to time: the
  1% check counts each execution once, not once per thread inside it. One under the floor is told so,
  whatever its share.
- No warning that fine-label time is under no coarse row in a run that uses no coarse label.
- The `Thread-time%` bound sets each thread's samples against its CPU time over the same windows.
- Work billed to a closed execution fails a run shorter than a second, or in its last second, as it
  fails a longer one.
- A damaged run file is refused with a message.

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
