# Reading the report

TickSnick prints three kinds of report:

- **One run**: `TickSnick.load(folder).render()` on a folder that holds one saved run.
- **Several runs**: the same call on a folder of several runs. Every figure is the mean of the runs,
  with a 95% interval.
- **A diff**: `TickSnick.diff(before, after)` on two folders.

The examples come from a program that resolves Java classpaths over the Maven Central dependency
graph. It runs on four threads in three batches of about 27 s each. Its coarse labels are `runBatch`,
`resolveRoot`, `resolve`, `select`, `via` and `reach`; the rest are fine labels.

## One run

```
Runs          1 run of 'naive', one JVM: no interval

WARNINGS
  Runs: 1 in 'naive', no interval - shares hold, durations need a second run

              run  file                               wall   CPU clock   runBatch, each execution
              1    2026-10-26T18-16-43.tsr            117s         99%   27.6s   27.4s   27.0s
Wall time      117s = 104s active + 13.0s paused (11.1%) in 57 pauses, longest 1.98s
runBatch       27.3s per execution
Cores         14 processors: profiled threads 2.85, the rest 2.67: GC 1.73 (1.20 in pauses), sampler 0.88, JIT 0.07
Sampling      every 1.0ms: the sampler keeps one of the 14 processors busy to hold the interval
```

### WARNINGS

Every warning is in this one section, right under the report's first line, worst first. If there is
none, the section is absent. At most ten are listed, and the rest are counted.

Each warning says what happened, why it matters and what to do: **its subject, a colon, what
happened, a dash, what it means or what to do**. The subject is a name the report shows (a label in
backticks, a column such as `Thread-time%`, a line such as `Runs` or `CPU clock`), or there is none.
A second line, further in, gives the cause.

The common ones:

- `Runs: 1 in 'naive', no interval` - one JVM cannot say how much the next one will differ. Save
  more runs into the same folder.
- `CPU clock: moved 140%..230% during the run` (or `across the runs`) - the CPU's frequency changed, so
  durations drift with it. Shares are not affected. Turbo off holds it still.
- `The labels, mostly `visit`, may add up to P to the work inside them and W to the wall time` - every
  fine call and coarse execution at its budget, against the time the labels hold, and against the run's
  wall time: the most the wall can grow, where the labels sit on the run's critical path. Said above
  1% of the work. Fewer, larger labels cost less.
- `` `visit`: its calls are under the recommended 50ns `` - the label is a visible part of what it
  measures. Label the loop around it, with `op(visit, times = n) { }`. The floor is 2 bodies at the
  run's body, rounded up.
- `` `request`: its executions, 1.2us, are under the recommended 3.0us `` - the operation is too short
  for a coarse label. Register it fine; the `op` calls stay the same. What raises the floor is named.
- `` `parse`: entered and never exited `` - a label set with `enter` was not closed, so later time
  went to it. Put `exit` in a `finally`.
- `Thread-time%: may be off by up to N pp` and the `Runnable` warnings - some time inside labels read
  as Runnable may have been waiting, so a share of time may differ from a share of CPU. The second line
  splits it: waiting for a free core (run fewer threads) or in I/O, native calls or other programs
  (label the wait).
- `Paused: 1.85s of pauses, 2.1% of the run, is counted as work in the times below` - GC pauses
  TickSnick could not tell from work.
- `Runs: they differ in Heap, so each mean mixes them` - the runs of a folder disagree on a tag, the
  setup, or how many times the root operation ran, so they are not one profile. Split the folder. It is
  always the last warning.
- `Runs: 2 of 4 shared a JVM` - two sessions of one JVM are not independent, so the +- is too narrow.

Warnings go up. A report over several runs shows each run's own warnings, and the same warning from
several runs is one line ending in `(2 of 4 runs)`, its figures as ranges, `1.04pp..1.29pp`. A diff
shows the warnings of both its profiles, `(2 of 4 runs in 'naive')`.

A run that **failed** is not a warning: a label left open or work billed to a closed execution stopped
it, under `strict`. It is saved and left out of its profile, and the report opens with a `FAILED`
banner naming it; when no run is left, the banner is the whole report.

### Tags and Setup

```
Tags          warmups: 2; batches: 3; roots: org.apache.*
Setup         Java: 21.0.4; JVM: OpenJDK 64-Bit Server VM; OS: Windows 11 amd64; Processors: 14; Heap: 18 GB; GC: G1; Flags: -Xmx18g; Sampling: 1.00ms; Wait: SPIN
```

**Tags** are what the program said about the run. **Setup** is what the run read by itself, and how it
measured. When runs differ, each name lists its values: `warmups: 1, 2; batches: 3-10`. A run with no
value for a name shows `-`.

### The header

**Wall time** is the length of the session: **active** time plus **paused** time. Paused time is
when every thread was stopped, mostly by GC: ticks where the sampler woke a whole tick late and
the JVM confirms a collection. No samples are taken in a pause, so the fine table covers active time
only. The coarse table is timed, so an execution's time includes any pause inside it.

**Cores** is how many cores were busy on average, and with what: the threads that entered a label,
the rest of the process (GC, the sampler, the JIT) and its parts. Here four workers kept 2.85 cores
busy, while GC took 1.73.

**Sampling** is how often a sample was taken, and what the sampler cost the program. With 4 or more
processors it spins on one of them to hold the interval, as here. With fewer it sleeps between
samples, which leaves the processor to the program and makes the interval longer and less regular:

```
Sampling      every 1.6ms (1.0ms asked): the sampler sleeps between samples, so as not to take one of the 2 processors
```

A sampler told to spin on fewer than 4 processors gets the remedy at the end of the line:
`start(wait = PARK) to free it`.

The CPU's clock is recorded through the run, as a share of its base frequency. A single report
mentions it only in `WARNINGS`, when it moved; the reports over runs list it for every run.

### Coarse operations

```
COARSE OPERATIONS - wall time, inclusive, nested as it ran; a pause is charged to every operation it interrupted
                               |                                             Load                                             |                    Long
Coarse operation               |  Executions Per parent Parallel     Wall v % parent     Paused Runnable / Waiting       Mean |        p50        p90        p99        Max
runBatch                       |           3                          82.0s               4.20s      80.0% / 20.0%      27.3s |      27.4s          -          -      27.6s
  resolveRoot                  |      92,667     30,889      4.0      82.0s     100%      4.20s          100% / 0%     3.53ms |      602us     7.50ms     61.6ms      434ms
    resolve                    |     278,001          3               82.0s     100%      4.20s          100% / 0%     1.18ms |      146us     2.57ms     19.0ms      358ms
      select                   |     278,001                          42.5s      52%      3.90s          100% / 0%      611us |     62.7us     1.27ms     11.6ms      348ms
      via                      |     278,001                          28.1s      34%      2.99s          100% / 0%      404us |     47.9us      856us     6.06ms      339ms
      reach                    |     278,001                          10.1s      12%      1.15s          100% / 0%      145us |     27.8us      362us     1.65ms      166ms
```

The table is a tree: each operation sits under the one it ran inside, and its figures cover the
executions on that path only. The same label on two paths is two rows.

The columns come in two groups, the same two words as in the fine table below. **Load** says what the
row cost, and **Long** says how long single executions ran.

- **Executions** - how many times it ran on this path. Measured, not sampled.
- **Per parent** - executions per execution of the parent: 30,889 `resolveRoot` per `runBatch`, three
  `resolve` per `resolveRoot`. Blank at one.
- **Parallel** - how many of its executions ran at the same time under one parent execution. Blank
  when they ran one at a time. Read it beside `Per parent`: `30,889` with `4.0` is 30,889 started,
  four at a time.
- **Wall** - the part of the root's wall time this row took: its durations summed, then divided by
  how many ran at a time, at its level and above. The table is sorted by it (`v`).
- **% parent** - Wall as a share of the parent's Wall. What the children leave out of 100% is time
  the parent spent outside them.
- **Paused** - GC pauses that fell inside its executions. A pause is charged to every execution it
  interrupted, not to the one that caused it.
- **Runnable / Waiting** - of the samples inside the row, the share whose thread could run and the
  share whose thread was parked, blocked or waiting. `runBatch` is 20% waiting: the main thread
  waits for the workers.
- **Mean, p50, p90, p99, Max** - measured per execution. Percentiles are exact up to 2,624
  executions and within 0.8% after. A p90 needs 10 executions and a p99 100; with fewer it shows `-`.

**The `-` rows under an operation** are the fine labels in its own time, outside its coarse
children:

```
Coarse operation                Executions Per parent Parallel     Wall v % parent     Paused       Mean
batch                                    1                          1.16s                   -      1.16s
  plan                                  10         10               1.01s      87%          -      101ms
    - rule                           3,400        340               710ms      70%                 209us
    - visit (2 parents)              1,000        100               201ms      20%                 201us
    - rare                             500         50              99.2ms      10%                 198us
    - and 1 more                                                              <1%
  cleanup                               10         10               150ms      13%          -     15.0ms
    - visit (2 parents)                100         10              98.2ms      65%                 982us
    - unlabelled                       own                         49.6ms      33%
    - rare                              10          1                   -
```

- **Executions** - the label's calls on this path, counted. **Per parent** - calls per execution of
  the row above: 340 `rule` per `plan`. It does not depend on how many plans the run made, so it
  can be compared between runs of different sizes.
- **Wall, % parent** - its time, sampled, and its share of the row's own time.
- **Mean** - its own time per call: `visit` is 201 us under `plan` and 982 us under `cleanup`.
- A label called on a path and never sampled there still has its row, with `-` for the time:
  `rare` ran once per `cleanup`, too briefly to be caught.
- `unlabelled` is time under no fine label.
- **Every fact about a row is in one parenthesis on its name:** `(2 parents)` when an operation is on
  more than one row of the tree, each row its part under that parent, and `(2 of 3 runs)` when some
  runs do not have it. A diff writes the same with `->` where the sides differ: `(3 parents, 3 -> 0
  runs)`.
- **Under multiple parents**, right under the tree and above the keys, has each such operation, coarse or fine, over all of its
  rows: `visit` 300 ms and 1,100 calls, under `plan` and `cleanup` together. It is sorted by Wall, so a
  label spread over three rows ranks by its whole, and not by its largest part.
- `and 1 more` folds the small rows into one row, with their calls and time in the same columns. Small rows with calls are drawn while they
  fit within six rows.

A diff has these same columns, names and forms. It shows `Allocated` when a side counted it, and warns
when only one did.

A coarse label suits an operation from 100 bodies, about 2.5 us on a fast machine: under it the part of
TickSnick's hook that runs inside the span makes its share of the samples read high, and a very short
one loses samples. A body is a fixed piece of CPU work that TickSnick times once a second during the
run, so the floor follows the machine and its clock. The hook is mostly its two `System.nanoTime()`
calls, so where `nanoTime` is slow the floor rises with it. Counting the calls per path adds 0.4 bodies
to the floor for every registered fine label, and `Allocated` 24 bodies: 2.7 us with 20 fine labels,
3.3 us with `Allocated` too, 5 us with 250 labels, at 25 ns a body. An operation under the floor is still
measured; a warning says it is under the recommended floor, rounded up, and what raises it.

**`Allocated`** appears only in a run started with `allocations = true`: the bytes created under the
row, with its coarse children and every thread that ran under it, counted, not sampled. **Total** is all
its executions together, so it follows the tree as `Wall` does: a parent's is its own and its children's,
exactly. **Per execution** is that over `Executions`, which compares runs of different sizes, as `Mean`
does. It is bytes allocated, not memory held: an operation can allocate gigabytes and hold none of it.
What it measures is the collector's work - a young collection comes every time the young generation
fills - and not how much memory the program needs. Each execution includes about 100 bytes of the label's own, and the first execution
on a path a few KB more, which matters only for an operation that allocates very little. A thread
counts from its first coarse boundary in the session, so an execution already open at `start` misses
what it allocates up to there, and one still open at `stop` misses what came after its last.

**`slowest:`** appears under an operation registered with a key (`op(X, key = { id }) { }`): its
longest executions on that path, with their keys.

### Coarse and fine operations

```
COARSE AND FINE OPERATIONS - own time, out of 104s active time
                           |                                       Load                                       |             Long             |               Spread
Operation                  | Thread-time% v Thread-time Runnable / Waiting         Calls Thread-time per call | Long calls     Time  Longest | Wall-time Concurrency Workers  Pool
onCentral                  |          28.1%        117s          100% / 0%   569,233,860                205ns |          8   10.0ms   2.00ms |     65.5s        1.78       4    12
runBatch *                 |          18.8%       77.8s     0.198% / 99.8%             3                25.9s |          -        -        - |     77.8s        1.00       2     1
merge                      |          14.1%       58.4s          100% / 0%   199,138,728                293ns |          5   7.01ms   2.00ms |     43.1s        1.36       4    12
visit                      |          14.0%       58.2s          100% / 0%   562,928,865                103ns |          -        -        - |     43.5s        1.34       4    12
(no operation)             |          6.37%       26.4s     99.0% / 0.950%             -                    - |          -        -        - |         -           -       -     -
passedOn                   |          6.00%       24.9s          100% / 0%   222,178,167                112ns |          -        -        - |     21.9s        1.14       4    12
```

One row per label, across the whole run, in its **own** time: time inside it and inside no label
nested in it. A row marked `*` is a coarse operation's own work; its full execution is in the table
above. `(no operation)` is time on profiled threads under no label.

**Load** - what the time went to.

- **Thread-time%** and **Thread-time** - the same figure as a share of all samples and as seconds,
  summed across threads. It is time inside the label, running or waiting, not CPU time. The share
  is of active time; compare the seconds between runs.
- **Runnable / Waiting** - splits that thread-time into samples whose thread could run and samples
  whose thread was parked, blocked or waiting. Runnable is an upper bound on work: a thread waiting
  for a core, or inside a native call, also reads runnable.
- **Calls** - exact, counted by the label.
- **Thread-time per call** - thread-time divided by calls: the cost of one call, measured without
  timing any.

**Long** - calls of the label that lasted over a sampling tick: how many, the time they held, and the
longest. A fine call is counted and not timed, so this is what stands in for the coarse table's
percentiles, and a coarse row shows `-`. A short operation with a few long calls is usually a rehash,
a lock or a GC pause. `-` means they hold no more than preemption and pauses alone would explain; the
footnote says how much that is.

**Spread** - how the time was spread over threads.

- **Wall-time** - clock time with at least one thread inside the label. Not summed across threads.
- **Concurrency** - Thread-time / Wall-time: how many threads were inside at once, on average, while
  any was. Inside is not running: two threads asleep in the same label are a concurrency of 2. High
  concurrency on a waiting label is a queue.
- **Workers** - the most threads seen inside at one tick. A lower bound, since overlaps between ticks
  are not seen.
- **Pool** - every thread that ever called the label, exact.

### Sampled

```
Sampled       414,466 samples - 103,684 ticks at 1.001ms mean (jittered) x 5 threads, one sample per thread per tick
```

How many samples the fine table is made of: the ticks, the mean time between them, and the threads
read on each. A row's share of them carries an error of about `1/sqrt(samples)` from sampling alone: 96
samples is 10%, 100,000 is 0.3%. With two runs or more the `+-` includes it.

## Several runs

A folder is one profile: same code, same workload, same setup. Each file in it is one run, one JVM.
One run is a folder of one, so a report prints the same lines however many runs are behind it.
With two runs or more, each figure is the mean of the runs, and what says how much has a 95% interval:

```
Runs          4 runs of 'naive', one JVM each: every figure below is their mean, +- its 95% interval from how much the runs differ (Student's t, 3 degrees of freedom)
              run  file                               wall   CPU clock   runBatch, each execution
              1    2026-10-26T18-16-43.tsr            117s         99%   27.6s   27.4s   27.0s
              2    2026-10-26T18-23-49.tsr            117s         99%   27.4s   27.1s   27.5s
              3    2026-10-26T18-30-57.tsr            117s         99%   27.8s   27.1s   28.1s
              4    2026-10-26T18-38-06.tsr            117s         99%   27.5s   27.4s   27.6s
Wall time     117 +-0.444s (0.4%) = 104s +-0.3% active + 13.2s +-2.4% paused (11.3%) in 59 pauses, longest 2.01s
runBatch      27.5 +-0.260s (0.9%) per execution
Cores         14 processors: profiled threads 2.85, the rest 2.70: GC 1.75 (1.22 in pauses), sampler 0.88, JIT 0.06

COARSE OPERATIONS - wall time, inclusive, nested as it ran; a pause is charged to every operation it interrupted; each figure the mean of the runs +- 95%
                                   |                                                                      Load                                                                      |                    Long
Coarse operation                   |  Executions Per parent Parallel     Wall v           % parent               Paused           Runnable / Waiting                 Mean           |        p50        p90        p99        Max
runBatch                           |           3                          82.4s +-0.9%                            4.29s +-5.2%           80.0 / 20.0 +-0.0%         27.5s +-0.9%    |      27.5s          -          -      28.1s
  resolveRoot                      |      92,667     30,889      4.0      82.4s +-0.9%         100 +-0.0%         4.29s +-5.2%               100 / 0 +-0.0%        3.55ms +-0.9%    |      602us     7.54ms     61.3ms      449ms
    resolve                        |     278,001          3               82.4s +-0.9%         100 +-0.0%         4.29s +-5.2%               100 / 0 +-0.0%        1.18ms +-0.9%    |      148us     2.57ms     18.9ms      358ms
      select                       |     278,001                          42.5s +-1.1%          52 +-0.4%         4.01s +-9.1%               100 / 0 +-0.0%         611us +-1.1%    |     62.8us     1.28ms     11.7ms      348ms
```

**Each run is one sample.** Batches inside one JVM share its compiled code, its heap and the CPU
clock of the moment, so they are not independent. The interval comes from how much whole runs
differ. With three runs t is 4.30, with four 3.18: few runs give a wide interval.

- **The run list** shows each run's wall time, its clock, and each execution of the root operation.
  A run that stands out is visible here before it widens every interval below.
- **% parent** is each run's share of its own parent, with its interval in percentage points. The
  run's speed cancels out, so it shows whether the structure held while the whole run got faster or
  slower.
- **`+-` goes on what says how much:** Wall, % parent, Paused, Runnable / Waiting, Mean,
  Thread-time%, Thread-time and Thread-time per call. Where the figure and its `+-` share a unit, the
  unit is written once, at the right: `52 +-0.4%` is 51.6% to 52.4%, `117 +-0.444s` is 116.6 to 117.4 s.
  Where they do not, the `+-` is a percent of the figure: `42.5s +-1.1%`. One run has no `+-`, and its
  figures keep their units: `52%`.
- **No `+-` on what says how it ran:** a percentile is the mean of the runs' percentiles, Max is the
  largest of the runs, and the Long and Spread columns have none.
- **Counted figures** print plainly when the runs agree, and `~` their mean when not: Executions, Per
  parent and Calls. The same work gives the same counts, so `~` says the workload differed. Allocated is
  counted too, but runs of the same work never create quite the same bytes: it is their mean, plainly.
- **A row missing from some runs** says in how many it appears, `rare (2 of 3 runs)`, and its figures
  cover those runs. A fine label that was called in a run but never sampled counts as 0 in that
  run.

## The diff

`TickSnick.diff(before, after)` compares two profiles, every run of one folder with every run of the
other: each figure is the mean of its side's runs, and the change has a 95% interval by Welch's t,
which does not assume the two sides vary alike.

What differs between the two is shown and is not a warning, because it is the question the diff
answers. `Tags` lists every tag, and `Setup` only what differs:

```
Tags          step: naive -> centralEdges; roots: org.apache.* =; batches: 3 =
Setup         Heap: 12 GB -> 18 GB
```

A warning about either profile is repeated here, and that includes a profile whose runs are mixed.

```
Diff          naive (4 runs) -> centralEdges (4 runs)
Wall time        117s -> 91.6s   -25.4 +-1.75s         -22 +-1.5%
  Active         104s -> 79.2s   -24.6 +-1.53s         -24 +-1.5%
runBatch        27.5s -> 19.4s   -8.09 +-0.361s        -29 +-1.3%       per execution

COARSE OPERATIONS - wall time, inclusive, matched by path; each side the mean of its runs, the change +- 95%
                                             |                                                                      Load                                                                      |                    Long
Coarse operation                             |  Executions Per parent Parallel     Wall v           % parent               Paused           Runnable / Waiting                 Mean           |        p50        p90        p99        Max
      select                                 |     278,001                          42.5s +-1.1%          52 +-0.4%         4.01s +-9.1%               100 / 0 +-0.0%         611us +-1.1%    |     62.8us     1.28ms     11.7ms      348ms
                                             |     278,001                          33.1s +-2.9%          57 +-0.6%         3.70s +-8.0%               100 / 0 +-0.0%         475us +-2.9%    |     49.9us      995us     8.75ms      190ms
                                             |           =          =                 -22 +-2.2%        +5.3 +-0.6pp        -7.7? +-9.5%                    =                  -22 +-2.2%    |       -21%       -22%       -25%       -45%

COARSE AND FINE OPERATIONS - own thread-time, matched by name, sorted by the change; each side the mean of its runs, the change +- 95%
                           |                                                           Load                                                           |             Long             |               Spread
Operation                  |   Thread-time%           Thread-time           Runnable / Waiting                   Calls Thread-time per call           | Long calls     Time  Longest | Wall-time Concurrency Workers  Pool
onCentral (4 -> 0 runs)    |           28.3 +-0.3%           118s +-1.6%               100 / 0 +-0.0%      569,233,860                207ns +-1.6%    |        ~16   45.5ms   11.0ms |     65.9s        1.78       4    12
                           |              -                     -                            -                       -                    -           |          -        -        - |         -           -       -     -

dependencies               |           5.58 +-0.1%          23.2s +-2.6%               100 / 0 +-0.0%      222,178,167                104ns +-2.6%    |         ~5   14.5ms   9.01ms |     20.7s        1.12       4    12
                           |           14.5 +-0.2%          43.1s +-3.1%               100 / 0 +-0.0%      222,178,167                194ns +-3.1%    |          6   23.5ms   12.0ms |     31.6s        1.37       4    12
                           |           +9.0 +-0.2pp          +86 +-5.5%                     =                       =                  +86 +-5.5%    |       +33%     +62%     +33% |      +52%        +22%       =     =
merge                      |           14.2 +-0.2%          59.0s +-2.1%               100 / 0 +-0.0%      199,138,728                296ns +-2.1%    |         ~8   27.7ms   9.01ms |     43.6s        1.35       4    12
                           |           20.1 +-0.5%          59.7s +-3.3%               100 / 0 +-0.0%      199,138,728                300ns +-3.3%    |         ~4   9.51ms   7.00ms |     38.3s        1.56       4    12
                           |           +5.9 +-0.4pp         +1.1? +-3.2%                    =                       =                 +1.1? +-3.2%   |       -56%     -66%     -22% |      -12%        +15%       =     =
```

A diff has everything a report has, with the same names and forms. A row is three lines: before, after,
and the change, so a diff is as wide as a report and every number sits above the one it compares with.
`Cores`, `Sampling` and `Sampled` are a line per side, `slowest:` is before's list and then after's, `in
all` stands at the end of each side's line, and a note under a table that only one side has names it.

- **The change** is a percentage, `pp` for what is itself a percentage, and `=` for no change. Its `+-`
  is in the change's own unit, written once at the right: `-22 +-2.2%` is between -24.2% and -19.8%,
  `+5.3 +-0.6pp` between +4.7 and +5.9 pp.
- **`?` at the end of a change means it could be noise:** the runs differ too much to tell, given how many
  changes the diff shows. A 95% interval misses zero one time in twenty when nothing changed, so a
  diff of 80 such changes would show four that are not there. So each change is held to 5% divided by
  the number of changes with an interval (Bonferroni), and the whole diff has about a 5% chance of
  showing one false change. `?` does not mean no change: more runs can settle it. No `?` does not mean
  the size is precise: that is what the `+-` says. The note under the table repeats the first part, and
  prints too when the only `?` is on a line over the tables.
- **Calls and Executions are the check.** `=` means both sides did the same work, so a change in
  time is a change in the code. Two different counts mean different work.
- **A row on one side only** reads `-` on the other and has no change: `onCentral (4 -> 0 runs)` was
  removed.
- **One run on a side** gives the change with no interval and no `?`, and a warning says so.
- When the two sides ran at different CPU clocks, a warning says by how much. Times are then not
  comparable; shares still are.

## The foot

Every report ends with a short legend:

```
HOW TO READ THIS
  thread-time is sampled and summed across threads over ACTIVE time, NOT CPU
  runnable/waiting splits the thread-time beside it; runnable is NOT the same as working
  concurrency is threads INSIDE at once, running or parked - not a speedup
  a share is where time went, NOT what removing the operation would save
```

The last line matters most. A share says where the time went. It does not say what removing the
operation would save: an operation can create work for everything else as well as doing its own, and
removing it removes that work too. The two numbers answer different questions, and they can be far
apart.
