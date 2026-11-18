# Reading the report

TickSnick prints three kinds of report:

- **One run**: `TickSnick.load(folder).render()` on a folder that holds one saved run.
- **Several runs**: the same call on a folder of several runs. Each figure is the mean of the runs,
  with its 95% interval.
- **A diff**: `TickSnick.diff(before, after)` on two folders.

Each is brief by default; "Two views" below.

**A cell holds a figure, `?` or nothing.** `?` is a figure TickSnick could not measure, or measured
too little of to say: a p90 with under 10 executions, a label never sampled where it was called, a
figure whose interval is as wide as the figure itself. More runs, more executions or another setup
would give it. A blank cell has nothing to look at: the
column's usual value (`Per parent` 1, `Paused` 0) or a column that does not apply to the row. A 0 is
a measured nothing.

The examples come from a program that resolves Java classpaths over the Maven Central dependency
graph. It runs on four threads in three batches of about 27 s each. Its coarse labels are `runBatch`,
`resolveRoot`, `resolve`, `select`, `via` and `reach`; the rest are fine labels.

## Two views

Every report comes in two views of the same figures:

- **Brief**, the default: the everyday look. The tables show the basis - each op's time, its
  share, how many times it ran and the price of one - and the lenses opened, and name the ones left out.
  A lens is opened by name: `render(lenses = setOf(Lens.LATENCY))`, the same on `TickSnick.diff`, or
  `--lens latency` on the command line, once for each lens.
- **Full**: every lens and every interval, and a diff three lines a row. `render(view = View.FULL)`,
  `TickSnick.diff(before, after, view = View.FULL)`, or `--view full` on the command line.

A view only leaves out, so the brief view never says what the full one contradicts. The brief one drops a
certain figure's interval, the header lines' included, and writes a certain or small change as one value;
nothing else differs. The sections after this one show the full view, since it has every column.

The brief view of a report over three runs, on a smaller workload:

```
NESTED WALL TIME - Each execution is timed from its start to its end, with the child ops that ran in it. An op is placed under its parent op. A pause counts in every op it interrupted. Each figure is the mean of the runs, with its 95% interval
Lenses not shown: Parallelism, Waiting, Memory, Latency - open one with --lens; --view full shows every lens and every interval
                                   |    Time    |    Share    |          Work           |    Cost
Op                                 |     Wall v |    % parent | Executions   Per parent |       Mean
-----------------------------------+------------+-------------+-------------------------+-----------
runBatch                           |      6.34s |             |          1              |      6.34s
  resolveRoot                      |      6.34s |        100% |      5,262        5,262 |     4.79ms
    resolve                        |      6.33s |        100% |     15,786            3 |     1.59ms
      select                       |      3.99s |         63% |     15,786              |     1.00ms
        - merge                    |      2.59s |         65% | 24,148,250        1,530 |      427ns
        - visit (3 parents)        | 370..560ms |         12% | 24,148,250        1,530 |     76.7ns
        - passedOn (3 parents)     | 210..430ms |          8% |  7,953,133          504 | 110..210ns
        - unlabelled               |  83..230ms |   1.5..6.3% |                         |
        - dependencies (3 parents) |   24..59ms |  0.77..1.3% |  7,953,133          504 |   12..29ns
      via                          | 0.75..2.1s |     12..33% |     15,786              | 190..530us
```

- **The basis** is four groups of columns, each a question: **Time** (`Wall`, `Thread-time`), how long;
  **Share** (`% parent`, `Thread-time%`), which part dominates; **Work** (`Executions`, `Per parent`,
  `Calls`), how many times; and **Cost** (`Mean`, `Thread-time per call`), the price of one. The other
  groups are **lenses**, each a kind of problem: Parallelism, Waiting, Memory (pauses and allocation) and
  Latency (percentiles, `Max`, long calls). Each table says right under its heading what its view leaves
  out and what the full view is: `Lenses not shown: ... - open one with --lens; the full view, every lens
  and every interval, is --view full`; with every lens open, `All lenses shown; ...`; in the full view,
  `This is the full view, ...`, with the way back. In both views each group is named over its columns,
  and the groups come in this order, the basis first.
- **A certain figure is written without its interval:** `6.34s`, where the full view writes `6.34s
  +-5.0%`, in the tables and on the header lines alike: `Wall time 6.34s = ...`. It is usable as
  one value. A loose figure keeps its range, `370..560ms`, and an unknown one is `?`, as in the full view.
  So the form alone says how sure a figure is: plain is certain, a range loose, `?` unknown, and each table
  ends with that key. One run prints plain too, so the line over each table says it may not be, and the
  key is left out; two runs get a line of their own ("Several runs").
- **Left out with the lenses:** the `slowest:` lists, which Latency opens, and the notes under a table on
  columns it does not show.

The brief view of a diff is one line a row, here on the same smaller workload, before and after a change
to the code:

```
OWN THREAD-TIME - Each op's own time, in thread-time. An op is matched between the sides by its name, and the rows are sorted by the change. Each side is the mean of its runs, and each change has its 95% interval
Lenses not shown: Parallelism, Waiting, Latency - open one with --lens; --view full shows every lens and every interval
                           |                Time                 |                   Share                    | Work  |         Cost
Op                         |                       Thread-time v |                               Thread-time% | Calls | Thread-time per call
---------------------------+-------------------------------------+--------------------------------------------+-------+---------------------
dependencies               |      5.90s -> 250..410ms       -94% |         14.5% -> 0.85..1.3%          -13pp |     = |                 -94%
visit                      |      8.10s -> 4.43s       -35..-55% |         20.0% -> 14.3%        -2.8..-8.5pp |     = |            -35..-55%
runBatch *                 |      7.80s -> 5.83s       -20..-31% |         19.2% -> 18.9%              -0.4pp |     = |            -20..-31%
resolve *                  |      560ms -> 1.89s       180..300% |         1.38% -> 6.11%          3.8..5.7pp |     = |            180..300%
passedOn                   |      3.47s -> 2.2..3.6s   1.3..-35% |         8.55% -> 7.2..11%                ? |     = |            1.3..-35%
merge                      |      10.1s -> 10.3s               ? |         24.8% -> 33.3%           4.3..13pp |     = |                    ?
---------------------------+-------------------------------------+--------------------------------------------+-------+---------------------
  A plain figure (3.4) has its 95% interval within 20% of it. Otherwise a range (2.2..7.0) shows the two ends of the interval. ? is unknown and needs more runs
  A change written as one value (-40%) is certain, its interval within 20% of it, or small, within 5% of before. A range shows the two ends of the change's interval. = means it did not move. ? means it is unknown: the change is within the noise between runs, and more runs would show it
```

- **Time and Share** read `before -> after` and the change, the two sides aligned on the arrow, and so do
  an open lens's columns: they are levels, how parallel or how long the tail, and `Workers 2 -> 4` says
  what `+100%` alone does not. **Work and Cost**, the change only. A side is written as any figure is. A
  row on one side only has no arrow: its side alone, in its place; a blank side has no arrow either.
- **A change that moved is one value when it is certain or small:** `-94%`, `-0.4pp`, with `--unicode`
  `↓-94%` and `↓-0.4pp`, its middle where the full view writes its range. How much exactly changes no
  decision about something under 5% of before. Otherwise it keeps its range, `-35..-55%`. `=` did not
  move and `?` is unknown, as in the full view ("The diff"). A diff's tables end with a second key, to
  their changes.
- **Every heading is right-aligned** over its column, a `before -> after` column's too.

## One run

```
Runs          1 run of 'naive'. A single run gives no figure an interval

WARNINGS
  Runs: only 1 in 'naive', so no figure has an interval - run at least 3 times

Tags          warmups: 2; batches: 3; roots: org.apache.*
Setup         Java: 21.0.4; JVM: OpenJDK 64-Bit Server VM; OS: Windows 11 amd64; CPU: Intel64 Family 6 Model 197 Stepping 2, GenuineIntel; Processors: 14; Heap: 18.0 GB; GC: G1; Flags: -Xmx18g; Sampling: 1.00ms; Wait: SPIN
              run  file                          wall time   CPU clock
              1    2026-10-26T18-16-43.tsr            117s         99%
Wall time     117s = 104s active + 13.0s paused (11.1% of the wall time, in 57 pauses, the longest 1.98s)
Cores         Of 14 processors, on average 2.85 ran the profiled threads and 2.67 ran everything else: GC 1.73 (1.20 of it in pauses), the sampler 0.88, JIT 0.07
Sampling      A sample every 1.0ms. To hold that pace, the sampler occupies one of the 14 processors
```

### WARNINGS

Every warning is in this one section, right under the report's first line, worst first. If there is
none, the section is absent. At most ten are listed, and the rest are counted.

Each warning says what happened, why it matters and what to do: **its subject, a colon, what
happened, a dash, what it means or what to do**. The subject is a name the report shows (a label in
backticks, a column such as `Thread-time%`, a line such as `Runs` or `CPU clock`), or there is none.
A second line, further in, gives the cause.

The common ones:

- `Runs: only 1 in 'naive', so no figure has an interval` - one JVM cannot say how much the next one will differ. Save at
  least 3 runs into the same folder: two give an interval too wide to say much. A line right over each
  table says the same.
- `CPU clock: ran at 140%..230% during the run` (or `across the runs`) - the CPU's frequency changed, so
  durations drift with it. Shares are not affected. Turbo off holds it still.
- ``The labels, mostly `visit`, may add as much as P to the work inside them and W to the wall time`` - every
  fine call and coarse execution at its budget, against the time the labels hold, and against the run's
  wall time: the most the wall can grow, where the labels sit on the run's critical path. Said above
  1% of the work. Fewer, larger labels cost less.
- `` `visit`: its calls are shorter than the recommended 50ns `` - the label is a visible part of what it
  measures. Label the loop around it, with `op(handle, times = n) { }`. The floor is 2 bodies at the
  run's body, rounded up.
- `` `request`: its executions, 1.20us on average, are shorter than the recommended 3.0us `` - the op is too short
  for a coarse label. Register it fine; the `op` calls stay the same. What raises the floor is named.
- `` `parse`: entered and not exited before `stop()` `` - a label set with `enter` was still open when the
  session stopped, so the time after its missed `exit` may have gone to it. Put `exit` in a `finally`.
- `Thread-time%: may be off by up to 1.10pp` and the `Runnable` warnings - some time inside labels read
  as Runnable may have been waiting, so a share of time may differ from a share of CPU. The second line
  splits it: waiting for a free core (run fewer threads) or in I/O, native calls or other programs
  (label the wait).
- `Paused: 1.85s of pauses, 2.10% of the run, are counted as work in the times below` - GC pauses
  TickSnick could not tell from work.
- `Runs: they differ in Heap, so each mean mixes runs that are not alike` - the runs of a folder disagree on a tag, the
  or the setup, so they are not one profile. Split the folder. It is
  always the last warning.
- `Runs: 2 of 4 shared a JVM` - two sessions of one JVM are not independent, so every interval is too narrow.

Warnings go up. A report over several runs shows each run's own warnings, and the same warning from
several runs is one line ending in `(2 of 4 runs)`, its figures as ranges, `1.04pp..1.29pp`. A diff
shows the warnings of both its profiles, `(2 of 4 runs in 'naive')`.

A run that **failed** is not a warning: a label left open at `expectBalanced()`, an `exit` that names
another label, work billed to a closed execution or an error in the sampler stopped it, since its
figures would be false. It is saved and left out of its profile, and the report opens with a `FAILED`
banner naming it; when no run is left, the banner is the whole report.

### Tags and Setup

```
Tags          warmups: 2; batches: 3; roots: org.apache.*
Setup         Java: 21.0.4; JVM: OpenJDK 64-Bit Server VM; OS: Windows 11 amd64; CPU: Intel64 Family 6 Model 197 Stepping 2, GenuineIntel; Processors: 14; Heap: 18.0 GB; GC: G1; Flags: -Xmx18g; Sampling: 1.00ms; Wait: SPIN
```

**Tags** are what the program said about the run. **Setup** is what the run read by itself, and how it
measured. When runs differ, each name lists its values: `warmups: 1, 2; batches: 3..10`. A run with no
value for a name shows `?`, except `Allocated`, which a run leaves out when it did not count: `not counted`.

### The header

**Wall time** is the length of the session: **active** time plus **paused** time. Paused time is
when every thread was stopped, mostly by GC: ticks where the sampler woke a whole tick late and
the JVM confirms a collection. No samples are taken in a pause, so the own table covers active time
only. The wall table is timed, so an execution's time includes any pause inside it.

**Cores** is how many cores were busy on average, and with what: the threads that entered a label,
the rest of the process (GC, the sampler, the JIT) and its parts. Here four workers kept 2.85 cores
busy, while GC took 1.73.

**Sampling** is how often a sample was taken, and what the sampler cost the program. With 4 or more
processors it spins on one of them to hold the pace, as here. With fewer it sleeps between samples,
which leaves the processor to the program and makes the tick longer and less regular:

```
Sampling      A sample every 1.6ms (1.0ms asked). The sampler sleeps between samples, so it takes no processor
```

A sampler told to spin on fewer than 4 processors gets the remedy at the end of the line:
`start(wait = PARK) to free it`.

The CPU's clock is recorded through the run, as a share of its base frequency, or in MHz where the OS
gives no base. The run list gives each run's mean; `WARNINGS` says when it moved during a run or across
the runs.

### Nested wall time

```
NESTED WALL TIME - Each execution is timed from its start to its end, with the child ops that ran in it. An op is placed under its parent op. A pause counts in every op it interrupted
1 run: the figures below have no interval, so any of them may be loose or unknown - run at least 3 times to find out
The full view shows every lens and every interval; --view brief shows the basis, and --lens adds a lens to it
                                   |  Time  |  Share   |          Work           |  Cost  | Parallelism | Waiting | Memory |             Latency
Op                                 | Wall v | % parent | Executions   Per parent |   Mean |    Parallel | Waiting | Paused |    p50      p90      p99     Max
-----------------------------------+--------+----------+-------------------------+--------+-------------+---------+--------+---------------------------------
runBatch                           |  82.0s |          |          3              |  27.3s |             |   20.0% |  4.20s |  27.4s        ?        ?   27.6s
  resolveRoot                      |  82.0s |     100% |     92,667       30,889 | 3.53ms |         4.0 |         |  4.20s |  602us   7.50ms   61.6ms   434ms
    resolve                        |  82.0s |     100% |    278,001            3 | 1.18ms |             |         |  4.20s |  146us   2.57ms   19.0ms   358ms
      select                       |  42.5s |      52% |    278,001              |  611us |             |         |  3.90s | 62.7us   1.27ms   11.6ms   348ms
      via                          |  28.1s |      34% |    278,001              |  404us |             |         |  2.99s | 47.9us    856us   6.06ms   339ms
      reach                        |  10.1s |      12% |    278,001              |  145us |             |         |  1.15s | 27.8us    362us   1.65ms   166ms
```

The table is a tree: each op sits under its parent op, and its figures cover the
executions at that place in the tree only. The same label in two places is two rows.

The columns come in groups, each named over its columns and each a question, in the same order as in
the own table below: **Time**, **Share**, **Work** and **Cost**, then the lenses **Parallelism**,
**Waiting**, **Memory** and **Latency**.

- **Wall** (Time) - the part of its root op's wall time the op took: its durations summed, then
  divided by how many ran at a time, at its level and above. The table is sorted by it (`v`).
- **% parent** (Share) - Wall as a share of the parent op's Wall. What the child ops leave out of 100% is
  time the parent op spent outside them.
- **Executions** (Work) - how many times it ran at this place. Measured, not sampled.
- **Per parent** (Work) - executions per execution of the parent op: 30,889 `resolveRoot` per
  `runBatch`, three `resolve` per `resolveRoot`. Blank at one.
- **Mean** (Cost) - measured per execution.
- **Parallel** (Parallelism) - how many of its executions ran at the same time under one execution of
  the parent op. Blank when they ran one at a time. Read it beside `Per parent`: `30,889` with `4.0` is
  30,889 started, four at a time.
- **Waiting** - of the samples inside the op, the share whose thread was parked, blocked or
  waiting: a part of the op's time, not added to it. Blank where nothing waited. `runBatch` is 20%
  waiting: the main thread waits for the workers.
- **Paused** (Memory) - GC pauses that fell inside its executions. A pause is charged to every
  execution it interrupted, not to the one that caused it.
- **p50, p90, p99, Max** (Latency) - measured per execution. Percentiles are exact up to 2,624
  executions and within 0.8% after. A p90 needs 10 executions and a p99 100; with fewer, a row run
  once included, it shows `?`.

**The `-` rows under an op** are the fine ops in its own time, outside its coarse child ops:

```
                               |  Time  |  Share   |          Work           |  Cost  | Parallelism | Waiting | Memory |            Latency
Op                             | Wall v | % parent | Executions   Per parent |   Mean |    Parallel | Waiting | Paused |    p50      p90   p99      Max
-------------------------------+--------+----------+-------------------------+--------+-------------+---------+--------+-------------------------------
batch                          |  1.14s |          |          1              |  1.14s |             |         |        |      ?        ?     ?    1.14s
  plan                         |  984ms |      87% |         10           10 | 98.4ms |             |         |        | 98.3ms   98.6ms     ?   99.5ms
    - rule                     |  682ms |      69% |      3,400          340 |  201us |             |         |        |
    - visit (2 parents)        |  205ms |      21% |      1,000          100 |  205us |             |         |        |
    - rare (2 parents)         | 97.2ms |      10% |        500           50 |  194us |             |         |        |
    - tiny                     | 2.00ms |      <1% |         20            2 |  100us |             |         |        |
  cleanup                      |  150ms |      13% |         10           10 | 15.0ms |             |         |        | 15.0ms   15.0ms     ?   15.1ms
    - visit (2 parents)        | 97.2ms |      65% |        100           10 |  972us |             |         |        |
    - unlabelled               | 51.1ms |      34% |                         |        |             |         |        |
    - rare (2 parents)         |      ? |        ? |         10            1 |      ? |             |         |        |
-------------------------------+--------+----------+-------------------------+--------+-------------+---------+--------+-------------------------------
Under multiple parents - each op here is summed over all its parent ops
Op                             | Wall v |   % root | Executions              |   Mean |             |         |        |
  - visit                      |  303ms |      27% |      1,100              |  275us |             |         |        |
  - rare                       | 97.2ms |       9% |        510              |  191us |             |         |        |
-------------------------------+--------+----------+-------------------------+--------+-------------+---------+--------+-------------------------------
```

- **Executions** - the label's calls at this place, counted. **Per parent** - calls per execution of
  its parent op: 340 `rule` per `plan`. It does not depend on how many plans the run made, so it
  can be compared between runs of different sizes.
- **Wall, % parent** - its time, sampled, and its share of its parent op's Wall, as a coarse child op's is.
- **Mean** - its own time per call: `visit` is 205 us under `plan` and 972 us under `cleanup`.
- A label called at a place and never sampled there still has its row, with `?` for its time:
  `rare` ran once per `cleanup`, too briefly to be caught.
- `unlabelled` is time under no fine label.
- **Every fact about a row is in one parenthesis on its name:** `(2 parents)` when an op has
  more than one parent op, with a row under each and its part there, and `(2 of 3 runs)` when some
  runs do not have it. A diff writes the same with `->` where the sides differ: `(3 parents, 3 -> 0
  runs)`.
- **Under multiple parents**, right under the tree and above the keys, has each such op, coarse or
  fine, over all of its rows: `visit` 303 ms and 1,100 calls, under `plan` and `cleanup` together. It is
  sorted by Wall, so a label spread over three rows ranks by its whole, and not by its largest part. Its
  heading names only what it shows, in the same places, and `% root` stands where `% parent` does: the share
  of its root op's Wall, `batch` here (of all root ops together when there are several), since an op over all
  its parent ops has no one parent op to be a share of.
- **Every op you labelled has its row**, however small: `tiny` at under 1% of `plan` too. TickSnick does not
  decide which ops matter - a row that is missing would read as never called. For fewer rows, use a filter
  ("Filtering the rows", below).

A diff has these same columns, names and forms. It shows `Allocated` when a side counted it, and warns
when only one did.

A coarse label suits an op from 100 bodies, about 2.5 us on a fast machine, with no fine label
registered: under it the part of
TickSnick's hook that runs inside the execution makes its share of the samples read high, and a very short
one loses samples. A body is a fixed piece of CPU work that TickSnick times once a second during the
run, so the floor follows the machine and its clock. The hook is mostly its two `System.nanoTime()`
calls, so where `nanoTime` is slow the floor rises with it. Counting the calls at each place adds 0.48
bodies to the floor for every registered fine label, and `Allocated` 25.6 bodies: at 25 ns a body, about
2.7 us with 20 fine labels, 3.4 us with `Allocated` too, 5.5 us with 250 labels, before rounding up. An op under the floor is still
measured; a warning says it is under the recommended floor, rounded up, and what raises it.

**`Allocated`** and **Per execution**, in the Memory group after `Paused`, appear only in a run started with
`allocations = true`: the bytes allocated under the op, with its coarse child ops and every thread that ran
under it, counted, not sampled. **Allocated** is all its executions together, so it follows the tree as `Wall`
does: a parent op's is its own and its child ops', exactly. **Per execution** is that over `Executions`, which
compares runs of different sizes, as `Mean` does. It is bytes allocated, not memory held: an op can
allocate gigabytes and hold none of it. What it measures is the collector's work - a young collection comes
every time the young generation fills - and not how much memory the program needs. Work on a virtual thread is
not counted: the ops it ran under, and every op above them, read `?`, and a warning says so. The label's own
context, one per execution, is left out, so an execution that allocates nothing reads 0; the first execution
at a place still includes a few KB of the label's own, which matters only for an op that allocates very
little. A thread counts from its first coarse boundary in the session, so an execution already open at `start`
misses what it allocates up to there, and one still open at `stop` misses what came after its last.

**`slowest:`** appears under an op opened with a key (`op(X, key = { id }) { }`): its
longest executions at that place, with their keys.

### Own thread-time

```
OWN THREAD-TIME - Each op's own time, in thread-time, out of 104s of active time
1 run: the figures below have no interval, so any of them may be loose or unknown - run at least 3 times to find out
The full view shows every lens and every interval; --view brief shows the basis, and --lens adds a lens to it
                           |     Time      |    Share     |    Work     |         Cost         |               Parallelism                | Waiting |             Latency
Op                         | Thread-time v | Thread-time% |       Calls | Thread-time per call | Wall-time   Concurrency   Workers   Pool | Waiting | Long calls   Long time   Longest
---------------------------+---------------+--------------+-------------+----------------------+------------------------------------------+---------+---------------------------------
onCentral                  |          117s |        28.1% | 569,233,860 |                205ns |     65.5s          1.78         4     12 |         |          8      10.0ms    2.00ms
runBatch *                 |         77.8s |        18.8% |           3 |                25.9s |     77.8s          1.00         2      1 |   99.8% |
merge                      |         58.4s |        14.1% | 199,138,728 |                293ns |     43.1s          1.36         4     12 |         |          5      7.01ms    2.00ms
visit                      |         58.2s |        14.0% | 562,928,865 |                103ns |     43.5s          1.34         4     12 |         |
(no op)                    |         26.4s |        6.37% |             |                      |                                          |  0.950% |
passedOn                   |         24.9s |        6.00% | 222,178,167 |                112ns |     21.9s          1.14         4     12 |         |
```

One row per op, across the whole run, in its **own** time: time inside it and outside its child ops.
A row marked `*` is a coarse op's own time; its whole time is in the wall table
above. `(no op)` is time on profiled threads under no label.

The groups are the wall table's, in the same order. **Time, Share, Work, Cost** - what the time went to.

- **Thread-time** (Time) and **Thread-time%** (Share) - the same figure in seconds, summed across
  threads, and as a share of all samples. It is time inside the label, running or waiting, not CPU time.
  The share is of active time; compare the seconds between runs. The table is sorted by the seconds.
- **Calls** (Work) - exact, counted by the label.
- **Thread-time per call** (Cost) - thread-time divided by calls: the cost of one call, measured
  without timing any.

**Parallelism** - how the time was spread over threads.

- **Wall-time** - clock time with at least one thread inside the label. Not summed across threads.
- **Concurrency** - Thread-time / Wall-time: how many threads were inside at once, on average, while
  any was. Inside is not running: two threads asleep in the same label are a concurrency of 2. High
  concurrency on a waiting label is a queue.
- **Workers** - the most threads seen inside at one tick. A lower bound, since overlaps between ticks
  are not seen.
- **Pool** - every thread that ever called the label, exact.

**Waiting** - the part of that thread-time whose thread was parked, blocked or waiting, not added to
it; blank where nothing waited. The rest could run, which is an upper bound on work: a thread waiting
for a core, or inside a native call, also reads runnable.

**Latency** - calls of the label that lasted over a sampling tick: how many (**Long calls**), the time
they held (**Long time**), and the **Longest**. A fine call is counted and not timed, so this is what
stands in for the wall table's percentiles, and a coarse row is blank there. A short op with a
few long calls is usually a rehash, a lock or a GC pause. Blank means they hold no more than preemption
and pauses alone would explain; the footnote, `Long calls: calls over a tick hold ...`, says how much
that is.

### Sampled

```
Sampled       414,466 samples: one sample of each thread at each tick, from 103,684 ticks 1.001ms apart on average (the interval is jittered) and 5 threads
```

How many samples the own table is made of: the ticks, the mean time between them, and the threads
read on each. An op's share of them carries an error of about `1/sqrt(samples)` from sampling alone: 96
samples is 10%, 100,000 is 0.3%.

## Several runs

A folder is one profile: same code, same workload, same setup. Each file in it is one run, one JVM.
One run is a folder of one, so a report prints the same lines however many runs are behind it.
With two runs or more, each figure is the mean of the runs, with its 95% interval:

```
Runs          4 runs of 'naive', each in its own JVM. Every figure below is the mean of the 4 runs, with a 95% interval that comes from how much the runs differ (Student's t, 3 degrees of freedom)
              run  file                          wall time   CPU clock
              1    2026-10-26T18-16-43.tsr            117s         99%
              2    2026-10-26T18-23-49.tsr            117s         99%
              3    2026-10-26T18-30-57.tsr            117s         99%
              4    2026-10-26T18-38-06.tsr            117s         99%
Wall time     117s +-0.4% = 104s +-0.3% active + 13.2s +-2.4% paused (11.3% of the wall time, in 59 pauses, the longest 2.01s)
Cores         Of 14 processors, on average 2.85 ran the profiled threads and 2.70 ran everything else: GC 1.75 (1.22 of it in pauses), the sampler 0.88, JIT 0.06

NESTED WALL TIME - Each execution is timed from its start to its end, with the child ops that ran in it. An op is placed under its parent op. A pause counts in every op it interrupted. Each figure is the mean of the runs, with its 95% interval
The full view shows every lens and every interval; --view brief shows the basis, and --lens adds a lens to it
                                   |     Time     |     Share      |          Work           |     Cost      | Parallelism |    Waiting     |    Memory    |                           Latency
Op                                 |       Wall v |       % parent | Executions   Per parent |          Mean |    Parallel |        Waiting |       Paused |           p50             p90             p99            Max
-----------------------------------+--------------+----------------+-------------------------+---------------+-------------+----------------+--------------+-------------------------------------------------------------
runBatch                           | 82.4s +-0.9% |                |          3              |  27.5s +-0.9% |             | 20.0% +-<0.1pp | 4.29s +-5.2% |  27.5s +-1.2%               ?               ?   27.7s +-1.6%
  resolveRoot                      | 82.4s +-0.9% |  100% +-<0.1pp |     92,667       30,889 | 3.55ms +-0.9% | 4.0 +-<0.1% |                | 4.29s +-5.2% |         602us   7.54ms +-0.8%   61.3ms +-1.4%    405ms +-18%
    resolve                        | 82.4s +-0.9% |  100% +-<0.1pp |    278,001            3 | 1.18ms +-0.9% |             |                | 4.29s +-5.2% |  148us +-1.1%          2.57ms   18.9ms +-1.1%   339ms +-8.5%
      select                       | 42.5s +-1.1% |    52% +-0.4pp |    278,001              |  611us +-1.1% |             |                | 4.01s +-9.1% | 62.8us +-0.6%   1.28ms +-1.2%   11.7ms +-1.0%   330ms +-8.6%
```

**Each run is one sample.** Batches inside one JVM share its compiled code, its heap and the CPU
clock of the moment, so they are not independent. The interval comes from how much whole runs
differ. With three runs t is 4.30, with four 3.18: few runs give a wide interval.

- **The run list** shows each run's wall time and its clock. A run that stands out is visible here
  before it widens every interval below.
- **% parent** is the op's share of its parent op's Wall, taken in each run, with its interval in percentage points. The
  run's speed cancels out, so it shows whether the structure held while the whole run got faster or
  slower.
- **Every figure has an interval**, each a mean over the runs: the basis's and every lens's, the
  percentiles, Max, the counts. How it is written depends on how wide it is against the figure:
  - **up to 20%, certain:** the figure and its interval, `42.5s +-1.1%`, the interval a percent of the
    figure; a percentage takes points, `52% +-0.4pp`;
  - **over 20%, loose:** the interval's two ends, `370..560ms`; a percentage's stop at 100%;
  - **as wide as the figure or more, unknown**, so that it would reach 0: `?`, measured too little to say.

  A number that is not 0 but rounds to 0 at the places it is written in, a figure, an interval or a change,
  is written below one unit of its last place, with its sign: `20.0% +-<0.1pp`, `-<0.1pp`, `-<0.1..3.0pp`,
  `<1`, never `0.0`, which would read as a measured nothing. A real 0 is `0`.

  One run has no interval: the figure alone, `52%`. It prints as a certain one would, so a line right
  over each table says it may not be: `1 run: the figures below have no interval, so any of them may be loose or unknown -
  run at least 3 times to find out`. Two runs have an interval, but at 12.7 times their spread over the square root
  of two it leaves most figures loose or `?`, and a line says why: `2 runs: the interval is too wide to say
  much - run at least 3 times to find out`.
- **A percentile is the mean of the runs' percentiles, and Max the mean of the runs' largest:** the
  largest of all runs would grow with how many runs there are. One run's collection moves its largest,
  so Max is often `?`.
- **Counted figures** - Executions, Per parent, Calls, Pool, Allocated - are figures like any other.
  Runs that agree give an interval of 0, and the count prints plainly; runs that differ give it an
  interval, written by the same rule.
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
Setup         Heap: 12.0 GB -> 18.0 GB
```

A warning about either profile is repeated here, and that includes a profile whose runs are mixed.

```
Diff          naive (4 runs) -> centralEdges (4 runs)
Wall time      117s +-0.4% -> 91.6s +-1.8%  -24..-27s              -20..-23%
  Active       104s +-0.3% -> 79.2s +-1.9%  -23..-26s              -22..-25%
  Paused      13.2s +-2.4% -> 12.4s +-2.3%  -0.46..-1.1s           -3.5..-8.7%

NESTED WALL TIME - Each execution is timed from its start to its end, with the child ops that ran in it. An op is matched between the sides by its place in the tree: its parent op, that op's parent op, up to the root op. Each side is the mean of its runs, and each change has its 95% interval
The full view shows every lens and every interval; --view brief shows the basis, and --lens adds a lens to it
                                             |     Time     |     Share      |          Work           |     Cost      |  Parallelism  |    Waiting     |    Memory    |                           Latency
Op                                           |       Wall v |       % parent | Executions   Per parent |          Mean |      Parallel |        Waiting |       Paused |           p50             p90             p99            Max
---------------------------------------------+--------------+----------------+-------------------------+---------------+---------------+----------------+--------------+-------------------------------------------------------------
      select                                 | 42.5s +-1.1% |    52% +-0.4pp |    278,001              |  611us +-1.1% |               |                | 4.01s +-9.1% | 62.8us +-0.6%   1.28ms +-1.2%   11.7ms +-1.0%   330ms +-8.6%
                                             | 33.1s +-2.9% |    57% +-0.6pp |    278,001              |  475us +-2.9% |               |                | 3.70s +-8.0% | 49.9us +-3.5%    995us +-2.8%   8.75ms +-3.0%   188ms +-1.0%
                                             |    -20..-24% |     4.7..5.9pp |          =            = |     -20..-24% |               |              = |    1.8..-17% |     -18..-23%       -20..-24%       -23..-27%      -34..-52%

OWN THREAD-TIME - Each op's own time, in thread-time. An op is matched between the sides by its name, and the rows are sorted by the change. Each side is the mean of its runs, and each change has its 95% interval
The full view shows every lens and every interval; --view brief shows the basis, and --lens adds a lens to it
                           |     Time      |      Share      |    Work     |         Cost         |                  Parallelism                   |    Waiting     |                 Latency
Op                         | Thread-time v |    Thread-time% |       Calls | Thread-time per call |    Wall-time    Concurrency     Workers   Pool |        Waiting | Long calls        Long time      Longest
---------------------------+---------------+-----------------+-------------+----------------------+------------------------------------------------+----------------+-----------------------------------------
onCentral (4 -> 0 runs)    |   118s +-1.6% |   28.3% +-0.3pp | 569,233,860 |         207ns +-1.6% | 65.9s +-1.4%    1.78 +-0.2%           4     12 |                |          ?                ?            ?
                           |               |                 |             |                      |                                                |                |
                           |               |                 |             |                      |                                                |                |
dependencies               |  23.2s +-2.6% |   5.58% +-0.1pp | 222,178,167 |         104ns +-2.6% | 20.7s +-2.4%    1.12 +-0.4%           4     12 |                |       3..6       0.48..29ms   0.34..11ms
                           |  43.1s +-3.1% |   14.5% +-0.2pp | 222,178,167 |         194ns +-3.1% | 31.6s +-3.0%    1.37 +-0.4%           4     12 |                |          6         17..30ms            ?
                           |       80..91% |      8.7..9.2pp |           = |              80..91% |      48..57%        21..22%           =      = |              = |  -2.0..69%        -35..160%            ?
merge                      |  59.0s +-2.1% |   14.2% +-0.2pp | 199,138,728 |         296ns +-2.1% | 43.6s +-1.9%    1.35 +-0.3%           4     12 |                |          ?                ?            ?
                           |  59.7s +-3.3% |   20.1% +-0.5pp | 199,138,728 |         300ns +-3.3% | 38.3s +-2.8%    1.56 +-0.8%           4     12 |                |          ?                ?            ?
                           |             = |      5.5..6.4pp |           = |                    = |   -9.6..-15%        14..16%           =      = |              = |          ?                ?            ?
```

A diff has everything a report has, with the same names and forms. In the full view a row is three lines:
before, after, and the change, so a diff is as wide as a report and every number sits above the one it
compares with. The brief view's row is one line ("Two views").
`Cores`, `Sampling` and `Sampled` are a line per side, `slowest:` is before's list and then after's,
and a note under a table that only one side has names it.

- **A change is judged on its own interval**, by three things:
  - its **direction** is certain when at most 20% of its interval lies on the other side of zero, so a
    range a hair across zero still went up or down: `-0.1..1.5pp` went up;
  - it is **small** when its whole interval stays within 5% of before: of the time before, or for a
    share its points against the share before;
  - it is **certain** when its interval is within 20% of it, which says how precisely it is known how
    much it moved.

  With a certain direction it **moved**, and is written as its range; with none it **did not move**,
  `=`, when small, as `merge`'s time above, and is **unknown**, `?`, when not, and more runs would
  narrow it. A change from or to a figure that is `?` is `?` too, and two equal sides are `=`. The brief
  view writes a change that moved as one value when it is certain or small ("Two views").
- **Every number is its real value**, and a range runs from the end nearer zero, the least it moved
  first: `-20..-24%` is between 20 and 24% less, `4.7..5.9pp` more of the whole, and `1.3..-35%` down by up
  to 35%, perhaps 1.3% up. A range shows its direction by its order, so it takes no `+`; one value carries
  its sign, `+1.0pp`. With `--unicode` an arrow comes before every change that moved, `↓-20..-24%`,
  `↑4.7..5.9pp`, `↓-40%`: it repeats what the numbers say, for reading a column at a glance. A decrease
  stops at -100%.
- **The rules hold everywhere:** every figure with an interval and every change, in the tables, on the
  header lines and in the headings, in both views. A header line's sides are figures like any other: `117s
  +-0.4%` certain, a loose one as its range, `?` unknown; the brief view leaves out a certain one's `+-`.
  A figure with no interval, or a range of figures one per run (`CPU clock: ran at 140%..230%`), keeps
  its form.
- **Calls and Executions are the check.** `=` means both sides did the same work, so a change in
  time is a change in the code. Two different counts mean different work.
- **A row on one side only** is blank on the other and has no change: `onCentral (4 -> 0 runs)` was
  removed. In the brief view it has no arrow, only its side, in its place.
- **One run on a side** gives the change with no interval: one signed value, `-24%`, with no arrow. A
  warning says so, and a line right over each table names the side: `1 run before: the changes below have no
  interval, so any of them may be loose or unknown - run at least 3 times on each side to find out` (`1 run after`, `1
  run on each side`). The tables' headings then claim no interval. **Two runs on a side** get a line
  too, `2 runs after: the intervals from two runs are too wide to say much - run at least 3 times on each side`,
  unless the other side has one run, whose line says more. A change whose sides each agree across their
  runs is known exactly: one value, with its arrow.
- **`CPU clock`** under the header is each side's mean. When the two differ, a warning says by how
  much: times are then not comparable, while shares still are.

## Filtering the rows

A report shows every op. A **filter** shows only the rows you ask for: conditions, each a key, a comparison and a
bound, every one of which must hold.

```
--filter "wall >= 100ms"                  # the CLI, once for each condition
render(filter = Filter.of("wall >= 100ms"))           # the API; TickSnick.diff takes the same
TickSnick.diff(before, after, filter = Filter.of("change(wall) <= -5%"))
```

- **The keys so far:** `wall`, the Wall column, a time in `ns`, `us`, `ms` or `s`; and in a diff `change(wall)`,
  its change in % of before.
- **A row is shown when it may pass.** A figure passes when either end of its interval does: `wall >= 5ms`
  shows `2..6ms` and hides `2..4ms` (`>=` and `>` test the upper end, `<=` and `<` the lower one, whatever
  order the range is printed in). In a diff a row passes when either side does: `2ms -> 6ms` is shown,
  `2ms -> 4ms` is not. `change(wall)` tests the change's own interval, so a change that may be under -5% is
  shown; an op on one side only has no change, and does not pass.
- **A parent op stays while one of its child ops passes**, so the tree still reads; a note under the table says
  so when it happens.
- **Nothing goes unseen.** Under each table's heading a line says the filter and how many rows it left out:
  `Filter: wall >= 100ms. 6 rows are not shown; each is named under its parent op`. Each parent op ends with
  the rows it hides, `not shown: dependencies`, and a hidden op's own rows go with it,
  `not shown: via (with 5 rows below it)`. A condition a table cannot test leaves that table as it is, and its
  line says why: `Filter: wall >= 100ms is not applied here: this table has no Wall` for the own table,
  `a profile has no change` for `change(wall)` in a report.
- **A loose filter keeps an uncertain change.** A `?` change's interval is wide, so one of its ends passes almost
  any bound: `change(wall) <= -5%` keeps it.

## The foot

Every report ends with a short legend:

```
HOW TO READ THIS
  thread-time is sampled and summed over all threads, in ACTIVE time; it is NOT CPU time
  waiting is part of the thread-time next to it, NOT extra to it
  concurrency is how many threads are INSIDE an op at once, running or parked; it is NOT a speedup
  a share is where time went, NOT what removing the op would save
  every column explained: docs/output.md
```

The last line matters most. A share says where the time went. It does not say what removing the
op would save: an op can create work for everything else as well as doing its own, and
removing it removes that work too. The two numbers answer different questions, and they can be far
apart.
