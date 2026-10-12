# Reading the report

`Profiler.stop().render()` returns one block of text. This says what every part of it means and,
where a line is a warning, what to do about it.

> **If you change the report, change this file, and change the legend `render()` prints — all
> three.** The legend and this document say the same things on purpose, because a reader with a
> terminal and no browser still needs the short version. Two copies of anything drift: a KDoc in
> this project spent a day describing a function it had been moved away from. `Report.render()`
> carries a marker pointing here.

Everything below is a real run — `./gradlew :run --args="--demo --seconds=12"`, which profiles a toy
workload using nothing but the public API. It is worth reading before your own, because it contains
a mistake on purpose.

---

## The header: one line, and a warning only when there is one

```
Wall time     12.0 s = 12.0 s active + 3.00 ms paused (0.025%)
Careful       Runnable overstates CPU work by up to 2.53 pp of any share: blocking I/O, native calls or a busy machine
```

**Two criteria decide what the report prints**, Andrey's, 2026-10-11: it matters for optimising
performance, or it says the report is unreliable. Nothing else. The header used to be thirteen lines
- samples, the step, coverage, time on CPU, windows, a bound, a verdict, the clock - and after a
break away from the tool Andrey could not say what `Windows` or `Time on CPU` was *for*. Most of it
was mechanism, and the rest only said "fine". Silence says "fine" now.

**`Wall time` is the run split into the part that ran and the part that was stopped.** On the graph
benchmark, the run this was built for:

```
Wall time     66.6 s = 60.2 s active + 6.42 s paused (9.64%) in 21 pauses, longest 2.61 s
```

- **Paused** is time every thread was stopped. The sampler spins, so it wakes within microseconds of
  when it asked to; a tick that runs a whole step late was *stopped*, and a JVM thread is only ever
  stopped together with every other one. Only what the JVM's own count of its collections confirms
  is subtracted; lateness nobody confirmed stays in active time, since the sampler may have been
  stopped alone. The count and the longest pause are on the line because they are the figures GC
  tuning is about, and they are left off when the JVM confirmed less than one of the pauses the
  sampler saw, rather than print a line that contradicts itself.
- **Active** is the rest, and the fine table is over it: no sample is taken in a pause, so every
  share, every thread-time and every time per call is a share of active time. The table heading
  says so.

The line is printed on every run, `+ 0 paused` included, so that the word is on the page before the
table heading uses it.

**The five words, and why a reader cares about each.** `wall = active + paused` is the run;
`runnable / waiting` is per operation; `on CPU` is the check.

| word | what it is | why it matters |
|---|---|---|
| **paused** | the JVM stopped every thread | how much the collector costs. Tuning it or cutting allocation attacks this directly |
| **active** | the rest of wall time; the only time samples exist in | the time the code is responsible for. 30% in the table means 30% of this |
| **waiting** | the thread sat on a lock, a sleep or a park, by JVM state | locks and parked I/O. Removing a wait costs no CPU, only a design change |
| **runnable** | the JVM thinks the thread is working | CPU work, the thing a better algorithm speeds up. The catch: a blocking socket or file read is a native call, and the JVM calls it RUNNABLE |
| **on CPU** | the OS clock says the thread was executing | the check on runnable. Not a phase to optimise, and printed only when it disagrees |

`run` was the word for active until 2026-10-11, and it read as a second spelling of `runnable`.

**`Careful` is one line per thing that makes the numbers below less than they look**, each naming
the column it hits, before the numbers and not in a footnote after them. A healthy run has none.
What can appear:

| line | what happened | what to do |
|---|---|---|
| *Runnable overstates CPU work by up to N pp of any share: blocking I/O, native calls or a busy machine* | the OS clock says the threads were on a CPU less than `Runnable` claims, by more than a point | the left half of `Runnable / Waiting` is an upper bound; the gap is I/O the JVM cannot see, or the scheduler. Under 5 pp the ranking holds; the line says when a share is only where threads *sat* |
| *Runnable is unchecked against the CPU clock: ...* | no per-thread CPU clock, or no one-second window closed: the run was shorter than a second, or the threads exited first | read `Runnable` as an upper bound with nothing bounding it. Run for longer, or call `Profiler.release()` from threads that finish |
| *Runnable is unchecked: N% of thread-time was off the CPU while reading runnable ... Put a label around the waiting* | more invisible off-CPU time than labelled time - Netty's event loops in `epoll_wait` - so the bound swallows everything | label the waiting, not only the work. A selector loop with a label on it turns invisible off-CPU into labelled occupancy and the bound becomes tight again |
| *the sampler lost N s the JVM did not count as GC ... counted as active* | late ticks with no collection behind them: another kind of JVM pause, or the sampler kept off its core | active time is high by up to N s; nothing in the table is charged for it |
| *the JVM counts N s of GC, more than the sampler saw ... inside active* | pauses shorter than one step, which lose no tick | the same, the other way: a little GC is inside active |
| *pauses not measured: a parked sampler ...* | the sampler was not spinning, so its lateness says nothing | `wait = SPIN` if the split matters |
| *N thread CPU readings went backwards and were dropped* | the OS clock misbehaved | rare; the bound is taken on the rest |

**What the `Runnable` check is.** The sampler counts a thread as inside an operation whether it is
running, blocked, parked or descheduled - that is **occupancy**, not CPU. Occupancy counts waiting in
full, which is the right behaviour for *"why is this slow"*. The catch is that occupancy is only
additive across threads when it is CPU: a hundred threads parked one second on one lock is a hundred
thread-seconds of occupancy and one second of real cost. So the profiler reads each thread's CPU
clock once a second - the **duty cycle**, the term the design docs, the trials and the code use -
and the gap between CPU and runnable occupancy, worst case landed on one operation, is the bound the
`Careful` line prints. With duty `d`, an operation reported at share `s` has a true CPU share
somewhere in `[(s - (1 - d)) / d, s / d]`, and the widest either end can sit from `s` is
`(1 - d) / d`: one number that holds for every row at once. In a starvation test the aggregate duty
read 19.40% and the duty inside the labels 96.94%, and only the second is about the numbers in the
table, which is why the bound is taken over labelled time where it can be.

**What is no longer printed, and where it went.** The sample count, the step and the thread count
are the `Sampled` line after the tables - they are the precision of what is above, and matter when
a share looks too close to call, not before. Time outside every operation is a row, `(no
operation)`, with its own `Runnable / Waiting`. `Coverage`, `Time on CPU 99%`, the verdict, the
per-window range and the clock's resolution are gone: the first three said "fine", the last two were
mechanism. The figures are still in `DutyReport` and the bench prints them.

---

## The shape of the report

Sections, in order, each with a heading and a blank line before it:

```
(banner)            the title
Wall time           one line, active + paused; then a Careful line per thing that is unreliable
ALL OPERATIONS      the fine table over active time, or one line saying why it is empty
COARSE OPERATIONS   the coarse table over wall time, present only if you placed a coarse label
Sampled             samples, ticks, step, threads: the precision of everything above
(warnings)          anything the run wants to tell you about itself
HOW TO READ THIS    five lines: the things that will make you draw the wrong conclusion
```

**The legend is five lines, not thirty-seven.** `render()` prints only what will actively mislead
you — that `thread-time` is summed across threads and is not CPU, that `waiting` is waiting another
thread caused, that `wall-time` is *not* summed and dividing the two gives the threads inside at
once, that a share is not a counterfactual, and that *on a CPU* bounds the speedup from above rather
than being it. Everything else — how `noise` is computed, the convoy arithmetic, what the `was:`
lines are — is reference, and reference belongs here, where it is read once instead of skipped thirty
times.

`render(legend = true)` prints the full text, and this document is the same content at length.

### One word for the thing, another for putting it there

An **operation** is the named thing you registered. An **operation label** is the act of putting it
in the code. The report keeps them apart:

- anything that **counts or measures** says *operation* — `3 inside an operation`, `Outside`,
  `operations cover 5.58 s of 5.99 s`, `98.32% inside operations`
- anything about **placement** says *operation label* — a leaked one, one below the floor, one placed
  with `enter`/`exit` and never closed

Bare "label" survives only where the operation is already the subject and no ambiguity is possible —
*"the operation wants a coarse label for its per-execution statistics"*.

The report used to say "label" in both senses, and taught `Operation` as a column header before using
the other word three lines later. "Label" also already means something else in this field: in
async-profiler and pyroscope it is a thread-local tag, which is not this.

### What a sample is, and whether time outside every operation is bad

**A sample is one photograph of one thread's label slot**, taken once per tick. So the count comes
from `ticks x threads` — with the caveat that a thread which registers partway through the run
contributes fewer, which is why the `Sampled` line after the tables says *one sample per thread per
tick* rather than printing an equation. A short run makes the gap obvious: 7 ticks and 1 thread produced 4 samples, because the
slot is created on the first labelled call and the earlier ticks saw nothing to photograph.

**The step printed there is the one achieved, and it is jittered on purpose** — hence *mean
(jittered)*. Each interval is drawn within ±25% of the step you asked for, so the sampler cannot lock
onto a workload whose own rhythm is near the same period, catching the same phase every time. Over a
long run the mean lands on the step; over a very short one it visibly does not, and that is arithmetic
rather than a fault: 5 ticks means the mean of 4 draws, which printed 0.969 ms against a 1 ms request.

**Unlabelled is not a fault by itself.** It is a row in the table, `(no operation)`, ranked with
everything else, and its `Runnable / Waiting` splits it into the two cases that matter:

- **waiting** — threads sitting idle. Normal for a pool between requests, and nothing to fix. A run
  can be 95% outside every operation and perfectly healthy if that is where it is.
- **runnable with no label** — the machine was doing real work you have not labelled. This is the
  number worth reading. Either you instrumented part of the program deliberately, or a label is in
  the wrong place, or work escaped the context that should have carried it.

The row has no calls, no wall time and no concurrency, because nothing counts the ticks with some
thread inside no label; it has hits and a share, which is what it is for.

**The header is key-value rows, not prose**, and the left column says what kind of line it is:
`Wall time` is a fact, `Careful` is a caveat. *Is this a problem?* is answerable by looking at the
left column.

**Both tables are sorted, and a `v` on the column head says which one by** — `Occupancy% v` in the
fine table, `Total v` in the coarse one. On the column rather than in a heading sentence, because a
sorted table and an unsorted one look identical when there are two rows, and because the answer is
not the obvious one: a fine operation called a million times but always brief sits *below* one called
twice that occupied real time.

**`Total` is the ranking that answers *what do I fix first*** — the summed measured spans, exact
rather than sampled, since the coarse tier times every one of them. It is **inclusive**: a parent
span contains everything opened inside it, so the outermost context sorts first and its total is the
sum of its children plus its own work. Once your spans nest, the number you actually want is self
time, which this does not yet measure — [ideas.md](ideas.md) item 28.

**The explanations are last on purpose.** They used to sit *between* the two tables, so the report
read numbers, prose, numbers, prose — reported the first time somebody who had not written it tried
to read one: *"a wall of text, good for AI, very bad for a human."* Explaining a column and
interleaving that explanation with the data are two different decisions, and only the first had been
made.

**Columns get renamed or removed rather than explained.** `share` became `occupancy%` and then
`thread-time%`, `elapsed` became `wall-time`, and the concurrency ratio was deleted outright. The
`share` was the same quantity as the absolute column beside it, and calling one of them a *share*
invented a distinction that does not exist while hiding the one that does — neither is CPU. Both then
became `thread-time%` and `thread-time`, because that is what the rest of the report had been calling
the quantity all along, and one thing with two names is worse than either.

`busy/exec` was dropped: it is `working × mean`, both of which are printed, and its name did not say
it summed over the threads in an execution, which is why it could exceed the span next to it and look
like a defect. The concurrency ratio went the same way and for the same reason — `thread-time ÷
wall-time`, with both operands in the table.

A name that carries its own caveat needs no paragraph, and unlike a paragraph it still works on the
tenth run. A column that needs a paragraph *and* divides two of its neighbours does not need to
exist.

## Three significant digits, and units that follow the number

Every duration and every percentage in the report is printed to **three significant digits**, in
whatever unit keeps it there: `124 ns`, `36.6 ns`, `1.15 us`, `58.4 s`, `478 s`, and past a thousand
seconds `3.42 h`. Percentages the same: `100%`, `38.4%`, `5.03%`, `0.234%`, and `<0.001%` for a share
too small to write, which is not the same statement as `0%`.

The fourth digit is never evidence. A sampled share carries a noise floor printed two columns to its
right - `0.234%` here - and the coarse tier's percentiles come from a histogram that reports the top
of the bucket a value fell into, about 1%. `44.250%` invited a reader to compare two operations that
differ by less than the error on either.

**Sorting and every calculation use the full value.** This is only what is shown.

**A rounded value moves up a unit rather than borrowing a fourth digit**: 999.7 ms prints as `1.00 s`,
never `1000 ms`. And `100%` is reserved for a real 100, so a `runnable / wait` pair reads
`>99.9% / 0.003%` rather than appearing not to add up.

Seconds run to a thousand before minutes take over, because a minute is a unit the reader has to
convert back: `479 s` sits next to the run length and `7.98 min` does not. This replaced two
formatters that disagreed about where to stop - one printed an eight-minute coarse total as
`478505.87 ms`, the other existed to stop 40 seconds coming out as `40080.00 ms`.

## The operations table

```
                           |                    Load                    |               Spread                |               Calls                |          Trust
Operation                  | Thread-time% v Thread-time Runnable / Waiting | Wall-time Concurrency Workers  Pool |         Calls Thread-time per call |     Hits  Noise Over 1t
---------------------------+--------------------------------------------+-------------------------------------+------------------------------------+------------------------
flushBatch                 |          38.4%       183 s       100% / 0% |    58.4 s        3.14       8     8 | 1,483,486,016               124 ns |   183317 0.234%  0.034%
validateRecord             |          37.7%       180 s       100% / 0% |    58.4 s        3.08       8     8 | 1,483,486,016               121 ns |   180044 0.236%  0.074%
parseRecord                |          11.4%      54.3 s       100% / 0% |    36.4 s        1.49       6     8 | 1,483,486,016              36.6 ns |    54298 0.429%  0.004%
indexRecord                |          7.58%      36.3 s       100% / 0% |    26.8 s        1.36       6     8 | 1,483,486,016              24.5 ns |    36240 0.525%      0%
request *                  |          5.03%      24.1 s       100% / 0% |    20.0 s        1.20       7     8 |    20,998,546              1.15 us |    24033 0.645%       -
---------------------------+--------------------------------------------+-------------------------------------+------------------------------------+------------------------
  * a coarse operation, showing its OWN work - its full span is in the table below
```

**Both tiers are in one table, and the shares add to 100%.** A coarse label collects everything a
fine one does and more, so an operation that is promoted should not vanish into a different table
with different columns - the report's shape would be changing with the label rather than with the
program. What makes one table honest is that the hits partition: a fine row's samples are the ones
where it was the innermost label, a starred coarse row's are the samples inside its span with **no
fine label open** - its own work - and no sample is counted twice. `request` above did 2.542% of the
labelled work itself and delegated the rest to the four operations above it, which is the same
2.5% the *was made of* line reports under the coarse table.

An **inclusive** coarse number could not share this column: `request` covers 99.5% of the run's
labelled time, so it would outrank every operation it contains and the column would sum to about
200%. The inclusive view is the coarse table's job, below.

**The columns are grouped, with bars between the groups and a band naming them** — `Load`
(thread-time% through runnable/wait), `Spread` (wall-time and concurrency), `Trust` (hits, noise,
over 1t). Eleven columns is more than anyone reads as a flat list, and the groups are the reading
order: how much time went here, how it was spread over threads, how far the row can be trusted.

The band labels are centred and unruled, because the bars below already say where each group starts
and stops — dashes as well would draw the same boundary twice.

The `Calls` band repeats the name of a column inside it, which is **provisional** — the group's
natural name is the noun its first column already carries. [ideas.md](ideas.md) item 29 has the ways
out.

| column | what it is | what to watch for |
|---|---|---|
| **thread-time%** | this operation's slice of all **labelled** samples, and what the table is sorted by | the denominator is labelled samples, not every sample — so adding a label somewhere else does not move this one |
| **thread-time** | `hits × step`, summed across threads | **absolute**, so unlike `thread-time%` it does not move when a label is added, moved or removed. This is the column to compare between two runs |
| **runnable / wait** | the two halves of the `thread-time` beside it, summing to 100% | printed as a pair because a share on its own does not say whether it is *part of* thread-time or *on top of* it, and no unit settles that. **`runnable` is not `working`** — see below |
| **wall-time** | wall clock with at least one thread inside | not latency: it is every execution's interval unioned, so it says the operation had *somebody* in it for this long and nothing about any single execution |
| **concurrency** | `thread-time ÷ wall-time` — **the mean number of threads inside this operation** while anybody was. The step cancels: the numerator is thread-ticks and the denominator is ticks, so the ratio is a count | **not parallelism, and not a property of your code** — see below. It is what turns a big thread-time back into real cost: 100 s of waiting at a concurrency of 15 is a convoy to break up; at 1.7 it is steady contention to design out |
| **workers** | **how many threads were ever actually working on this at once** — the most found inside at one tick | the column to compare against what you *dispatched*: three submitted and `workers 2` means something is off, and neither of its neighbours can tell you that - a `concurrency` of 1.1 is consistent with two workers or eight, and `pool` counts threads that exist rather than threads that overlapped. Detected rather than measured, so it is a **lower bound**: a burst that starts and ends between two ticks is a burst nobody saw |
| **pool** | the distinct threads that ever **called** this operation. Counted by the hook, so unlike its two neighbours this one is exact | `workers 1` with `pool 8` is a serialization point - eight threads queueing through one at a time - and the report says so in a warning. It counts the pool's threads *that were used*: a `ThreadPoolExecutor` opens a new core thread on every submit until the core size is reached, idle workers or not, so a pool of four running three tasks at a time still reaches 4. On a thread-per-task workload it reads ten thousand, which is what that deployment is | **not parallelism, and not a property of your code** — see below. It is what turns a big thread-time back into real cost: 100 s of waiting at a concurrency of 15 is a convoy to break up; at 1.7 it is steady contention to design out |
| **calls** | exact, counted by the hook | a share cannot tell *200M calls at 8 ns* from *1000 calls at 1.6 ms*, and those want opposite fixes |
| **hits** | samples that caught this operation | the evidence behind the share |
| **noise** | `1/√hits` — the error chance alone gives | **if two rows differ by less than their noise, they are not ranked, they are tied** |
| **thread-time per call** | `thread-time ÷ calls`, both of them columns on the same row | the smell test you can apply and the tool cannot: an operation you know is 20 ns showing 500 ns is stalling on something. Inferred from sampling — the fine tier never times an individual call, which is what makes it cheap and what the coarse tier exists to do |
| **over 1t** | occupancy inside executions that outlived a tick | for a label claiming nanoseconds this is four orders of magnitude out. See the verdicts below |

Operations that were never sampled are folded into one line rather than printed as a screen of
zeroes — but a *called* operation with no samples is itself a finding, so the count and the names
are kept.

### `runnable` is not `working`, and the gap has cost this project a defect

`thread-time − wait` is time the JVM called `RUNNABLE`. That is **not** time on a processor, and two
things sit in the gap:

- **A preempted thread** — ready to run, no core free. Measured at **14–18% of wall time** on this
  machine, on a bench that never blocks.
- **A thread stopped inside a native call** — a blocking socket read, an `epoll_wait`. The JVM
  cannot see into these and reports `RUNNABLE`. This is not hypothetical: it is the PostgreSQL
  trial's defect, where a number read as CPU was **55× more than the machine had spent**
  ([trial-jdbc.md](trial-jdbc.md)).

So the left half is an **upper bound on the work**, never the work. The CPU check behind the
`Careful` line in the header is measured with a different instrument — `getThreadCpuTime`, not
thread state — and it is the only thing that can bound this in turn. When the line says *unchecked*,
nothing does.

That is also why the column says `runnable` and not `run`: the `-able` is the distinction. Runnable
means *not blocked*, not *executing*.

### `concurrency` is not parallelism, and not a property of your code

`thread-time ÷ wall-time` is how many threads were inside an operation at the same time — `183 s`
over `58.4 s` is 3.14. **Do the division for the reader**: a report about a threaded program should
not make a person compute whether it was threaded. That is why the column exists, after two failed
names (`threads`, then `in flight`) and a spell with no column at all.

**Why it matters:** `flushBatch` at `183 s` of thread-time is not three minutes of your life — the
clock advanced `58.4 s` while it ran. Delete it entirely and you save the wall-time, not the
thread-time.

**It is `concurrency`, not `parallelism`**, and the difference is not pedantry. The number counts
threads *inside* the label whether they are running or parked. Two threads asleep in the same label
is a concurrency of **2** with nothing executing at all — which is exactly what a sandbox run
produced: `work1` at `2.00 / 2` concurrency and `7.2%` runnable, so a real parallelism near `0.14`.
Multiply the two columns if you want that number; the report does not print it, because `runnable` is
itself only an upper bound on executing.

**And it is a property of your load.** It tracks arrival rate below saturation — twice the clients,
twice the number, not a line changed — and at the ceiling it stops tracking load and sits at the pool
size. That is what `workers` and `pool` are for, and why they are beside it: `3.14` with `workers 8`
of a `pool` of 8 is *the pool is pinned inside this label*, which is a finding about the pool rather
than the operation, while the same 3.27 with `workers 4` is a label that never used more than half
of what it had.

**One more thing it is not:** one execution split across threads. A fine operation is an integer in a
thread's slot and never leaves the thread that entered it, so three threads inside the label means
three separate calls, not one call going three times faster. Only a coarse context can be split, and
that is what the `parallelism:` line under the coarse table reports.

---

## The coarse table

Present only if you placed a coarse label. It answers the question the table above structurally
cannot: **how long did one execution take.**

```
Coarse operation           Executions    Total v       Mean        p50        p90        p99        Max Runnable / Waiting
-----------------------------------------------------------------------------------------------------------------------
request                     3,203,348    95.78 s    29.9 us    20.5 us    45.1 us   180.2 us   17.01 ms   100.0% / 0.0%
-----------------------------------------------------------------------------------------------------------------------
  request: 90.834% of thread-time inside operations, 95.78 s occupancy
  request parallelism: 1.00 thread per execution, 1.00 of it on a CPU; 7.96 executions at once over 8 threads
  request was: flushBatch 38.7%, validateRecord 37.9%, parseRecord 10.3%, unlabelled 7.2%, indexRecord 5.9%
```

| column | what it is | what to watch for |
|---|---|---|
| **executions** | completed executions, counted exactly | not sampled. If this disagrees with what you think ran, a label is leaking |
| **total** | the measured spans summed, and what the table is sorted by | the ranking for *what do I fix first*: exact, not sampled. **Inclusive** — a parent contains its children, so the outermost span always leads. Self time would be the honest key once spans nest ([ideas.md](ideas.md) item 28) |
| **mean, p50, p90, p99, max** | **measured**, two timestamps per execution | the only numbers in the whole report that are not sampled. Percentiles come from a log-bucket histogram: **at most 12.5% high, never low** |
| **runnable / wait** | the same split as the fine table, over the samples caught anywhere inside the span | `0.0%` waiting here because the demo never blocks. On anything with I/O or a lock the right half is the finding. `runnable` is an upper bound on work, not work |
| **`… parallelism:` line** | the two parallelism questions, in words | see below — they are different questions and used to be adjacent columns |
| **`… was:` line** | the cross-tabulation | which fine operations ran under this one. Neither tier produces this alone |

### The two parallelisms, which are not the same question

The `parallelism:` line carries three numbers, and the split matters more than any of them:

```
  request parallelism: 1.00 thread per execution, 0.08 of it on a CPU; 2.00 executions at once over 2 threads
```

- **`n thread per execution`** — **a property of your code.** Does one execution of this operation
  use more than one thread? `1.00` means no: it runs on the thread that opened it. This only moves
  above 1 when work is handed to a pool wrapped with `.propagating()`.
- **`n of it on a CPU`** — of those threads, the ones a sample caught running. `working = inside ×
  (1 − waiting)`, so it is what splitting the work actually *bought*: a caller parked on a join
  contributes nothing here, correctly. Printed as `0.08 (at most 0.31)` when the measured
  time on CPU says the machine cannot have supported the figure.
- **`n executions at once over k threads`** — **a property of your load,** not your code. How many
  of these were in the system simultaneously. Add threads or clients and it moves; change the code
  and it may not.

They were three columns named `inside`, `working` and `in flight` until 2026-08-31, when a reader
looked for parallelism, could not find it, and turned out to have been staring at all three. The word
was nowhere in the report and nothing said the first two answer a different question from the third —
so `inside 1.00` reads as *"no parallelism"* in a program running as parallel as its threads allow.

**The line is not printed on a single-threaded run**, where all three numbers are fixed by
construction — one thread per execution, one execution at once, over the one thread there was — and
the only one that could vary, `on a CPU`, is exactly `1 − waiting`, which is a column in the table.
The same reason zero-hit fine operations are folded away.

**Why percentiles exist here and nowhere else.** A share is a fraction of time and has no
distribution — that is why the v0.1.0 notes say *no percentiles, ever*, and for the fine tier it
remains true. A coarse execution has two timestamps, so it has a duration, so a thousand of them
have a distribution. The histogram is 320 log buckets, 2.5 KB per type per thread, and a percentile
is reported at the **top** of its bucket so it can be high but never low. Erring upward is the right
direction for a latency figure.

**Why the label goes on a batch and not a pass.** In the demo above one pass is about 700 ns and a
context costs tens of nanoseconds to allocate and stamp, so the label goes around a batch. That is
the tier boundary — `d ≥ max(800 ns, 4 µs × share)` — and it is why the fine tier exists at all.
Put a coarse label on something too small and you are measuring the instrument.

### `inside` and `working` are one measurement answering two questions

They are the same sum over the same ticks, split by whether the thread was on a CPU. Both are here
because the two answers lead to different decisions, and picking one would have thrown away a
question somebody needs.

Take a request that fans out to helpers while its caller waits on the join. Measured on the bench,
one driver against eight helpers:

```
inside   5.24     five threads are in this execution
working  4.24     four of them are doing something
```

**`working` is the speedup answer.** Splitting this request made it about 4× faster than doing it
serially. This is `work ÷ span` — the work-span model's `T₁/T∞`, and what the literature means by
*parallelism*. Invert it through Amdahl to ask whether more threads would help. The caller parked on
its own join contributes nothing here, correctly: it made the request no faster.

**`inside` is the capacity answer.** That sleeping caller is a real thread and is not available for
anything else. With sixteen threads and five tied up per request you can serve three requests at
once, not four. This is also the number that keeps the identity exact —

```
threads inside a coarse type = executions in flight  ×  inside
```

— because `in flight` counts an execution whether or not its threads are running, and a
factorisation has to count both sides the same way.

**They differ by exactly the `waiting` column**, which is why the three sit together:
`working = inside × (1 − waiting)`. A wide gap between them means the request is waiting on itself.

**And `working` is what relates `busy/exec` to the span:** `busy/exec = working × mean`. On a
fanned-out operation `busy/exec` is therefore *larger* than the span — six threads inside a 4 ms
search is 25 ms of thread-time, which is right and looks alarming the first time. The old shortcut
`mean − busy/exec = waiting` only ever held because nothing could cross a thread; the `waiting`
column is the reading that holds either way.

**Both read 1.00 until a context crosses a thread**, as in the demo above: every occupied execution
is occupied by the one thread that created it. That made it a known answer to calibrate the instance
stamping against before propagation existed, and it is still what pins the same-thread case now that
it does.

**Two caveats on `working`, and both are real limits rather than defects.**

*It reads low when the pool is saturated.* What gets measured is
`min(what the code could do, threads actually free)`. On the bench, seven drivers against the same
eight helpers leaves nothing to fan out to and `working` falls back to about 1. That is the truth
about *that run*, not about the code.

*It reads high on anything that waits outside the JVM, and the report will tell you so.*
`working` is built on `Thread.getState`, and **Java reports a thread inside a native call as
`RUNNABLE`** — a socket read, a file read, an `epoll` wait. Measured on PostgreSQL over a socket, the
column read **2.85** while the operating system said the whole process used 1.03 s of CPU in a 20 s
run: **55× more CPU credited than the machine ever spent**. When the measured duty cycle cannot
support the number, the column prints it over its ceiling —

```
inside   working
  3.83  2.83/0.04     ← reads 2.83, the duty cycle supports 0.04
```

— followed by a warning block naming each type. `inside` is unaffected: it counts threads in the
execution whatever they were doing.

*And it reads high as a speedup.* `working` is `work ÷ span` **of the run it measured**, and
parallelising usually costs extra work — per-slice setup, cache pressure, an all-core clock below
single-core boost. Measured on Lucene, the same search at one thread and at eight:

```
1 thread    span 14.04 ms   busy/exec 14.04 ms   working 1.00
8 threads   span  4.01 ms   busy/exec 25.31 ms   working 6.32
```

The speedup is **3.50×**. `working` says **6.32**, because the eight-thread run spends 1.80× more
total CPU to answer the same query. So `working` bounds the speedup from above and can overstate it
by a lot. Only re-running at a different thread count measures what parallelism actually bought —
`ideas.md` item 22, which this column does not replace.

### `N% of the thread-time inside coarse executions was inside one that had ALREADY BEEN CLOSED`

The sibling of the line below and the **opposite fault**, which is why they are stated separately.
That one is attribution *lost* — work that reached no span at all — and wrapping the hand-off brings
it back. This one is attribution *invented*: a thread still working under a request that has already
finished, so the time is billed to an execution that no longer exists.

It happens when work is handed to another thread and never waited for. The request closes, the work
carries on, and everything about it looks plausible — the operation name is right, the numbers are
the right shape, and nothing else in the report can tell. The balance check reads a thread's own slot
and finds it clean; the floor check reads sizes; the line below reads work with no context.

**That time is excluded from every number in the coarse table** rather than folded in. Crediting it
would let `busy/exec` exceed the `mean` span it is supposed to sit inside, which cannot happen and
would read as a finding rather than as a fault.

Two causes, and they want opposite fixes:

- **work was forked and not joined** — propagate only what the request actually waits for. A task
  the request does not join is not part of it, however much it feels like it
- **the span is closed too early** — `Profiler.exit(op)` is running before the work it covers has finished

Under `strict` this stops the session, which is the same treatment a leaked label gets and for the
same reason: both report a number that is not merely imprecise but false. It needs a share *and* a
minimum count to fire, because a helper finishing a few microseconds late is a harmless race and
stopping a correct run over three samples is the loudest wrong answer this tool can give.

### `N% of labelled thread-time was inside NO coarse span`

The one line that can see **work escaping its context**. Nothing else in a single run can: the floor
check sees labels that are too small, the balance check sees contexts left open, and neither sees a
context that is simply not where the work is.

It has two readings and **the report gives both, because it cannot tell them apart**:

- *you bracketed part of your program coarsely and not the rest* — legitimate, and common;
- *work is escaping onto threads your context never reached* — in which case those threads' time is
  missing from the operation's `busy/exec` and shows up as its `waiting`.

What separates them is whether the operations it names are ones you expected to be inside a span.
That is a question about your program, so the report states the measurement and stops.

Measured across the three trials, which is how the threshold was set:

| | outside every span | verdict |
|---|---|---|
| Calcite — one thread, everything under `plan` | silent | correct |
| Netty — synchronous pipeline | 0.0%, 3 ms | below the 1% floor, silent |
| **Lucene at 8 threads** — search fans across a pool | **88.5%**, naming `clause:prefix`, `clause:phrase`, … | the escaped work |
| Lucene at 1 thread — same code, no hand-off | silent | correct |

**It is more sensitive than the `waiting` column**, and that is the point. On the same Lucene run
`waiting` reads 24.5% while this reads 88.5%, because the calling thread is doing plenty of work
itself — the gap in the span understates how much went elsewhere.

Below 1% it says nothing, for the same reason the report does not chase negligible operations: at
that size it cannot be where the work went, and a check that cried wolf there would spend its
credibility.

### The one thing to know before trusting `waiting`

**If your operation hands work to other threads, `waiting` counts that work as waiting.** A context
lives on the thread that created it, so `busy/exec` only ever counts samples taken on that thread.
Measured on Lucene, whose search fans out across a pool — same code, same label:

| threads | mean | busy/exec | waiting |
|---|---|---|---|
| 1 | 14.88 ms | 14.87 ms | **0.0%** |
| 8 | 4.10 ms | 3.10 ms | **24.5%** |

Neither number is wrong: the calling thread really is blocked for a quarter of the search. What the
report cannot say is that the *request* was not idle — it was working, on threads the context never
reached. Until propagation exists, read `waiting` as *"the thread holding this context was not
running"*, which is what it measures, and not as *"this operation was idle"*.

Same-thread operations — a query planner, a synchronous handler chain — are unaffected, and both read
0.0% correctly.

---

## The warnings, and what to do about each

### `! … below the 50 ns floor`

```
  ! parseRecord: 288,514,362 calls at under 29.3 ns each, below the 50 ns floor.
    Label the enclosing loop instead and divide by the iteration count.
```

The label is on something too small for the instrument to describe: the hook is a visible fraction
of it, the sampler reads short operations 5–9% low, and C2 can move work across the boundaries of
adjacent short labels without leaving a trace in the numbers. In the demo this cost an operation
**95% of itself**.

**What to do:** `op(id, times = n) { … }` around the enclosing loop, and the report speaks in your
units. **Not fatal** — it warns and the run finishes, because the check is machine-dependent: the
same label reads 17.8 ns at one thread and 55.4 ns at eight on one laptop.

### `! … under the N a coarse label needs here`

The tier boundary, checked. A coarse label costs about 40 ns per execution to allocate, timestamp
and stamp, and there are two ways that can be too much — **which one bound decides what you are being
told**, because the remedies differ:

| bound | the complaint | example |
|---|---|---|
| `d ≥ 800 ns` | the number would describe the instrument as much as your code | a label on a 200 ns operation |
| `d ≥ 4 µs × share` | the *program* you measured is not the one you started with | a 1 µs operation holding 62% of the run: accurate per execution, but the contexts alone cost over 1% of everything |

**Unlike the fine floor above, this check is exact.** That one has to *infer* an operation's duration
from `hits ÷ calls`, so it carries a statistical bound and a bias allowance to avoid accusing an
innocent label. Here the duration is measured, so no slack is needed. The only sampled input is the
share, and it is taken a standard error low before the second condition can fire.

**What to do:** use a fine label — `op(id) { }` — or move the coarse label outward to a batch of
these. A warning, never fatal.

### `! N executions lasted over a tick`

An operation was caught still running a whole millisecond later. Either it was waiting, or it really
does take that long and belongs in a coarser label — opposite responses, so the report says which:

- *…and N% of those long samples caught the thread parked or blocked — it is waiting, not working.*
- *…and N% caught the thread runnable — the share is honest and the operation wants a coarse label
  for its per-execution statistics.*

Judged against a **machine floor**, so an operation is only named if it is well above what the
machine was doing to everything at once.

### `! N labels were still open at a point the caller said should be quiescent`

**Read this one first when it appears, because it invalidates rows above it.** In the specimen it
says 35,923 — and `flushBatch` sits at the top of the table with 44.25%.

That 44% is manufactured. The demo leaks `flushBatch` on purpose, every thousandth pass, and every
sample taken on that thread after the leak was billed to it. Nothing about the number looks wrong:
it is plausible, it is stable, it is at the top. This line is the only thing in the report that says
so.

**What to do:** `op(id) { }` has a `finally` and cannot leak — prefer it. Where you must use
`enter`/`exit`, call `Profiler.expectBalanced()` at a point the thread should be quiescent. Under
`strict` (the default) the first leak stops the session outright.

### `! N threads exited without Profiler.release() and were reclaimed`

A slot left behind by a dead thread reads as an *idle* thread forever and inflates the denominator
every share is taken over. The sampler reclaims those once a second — but only after a garbage
collection has cleared the thread, so in a process that does not collect they accumulate.

**What to do:** call `Profiler.release()` when a thread finishes.

### `! N threads arrived past the 1024-slot ceiling and were NOT SAMPLED`

Their occupancy is missing from every number above, including the denominators. The sampler watches
at most 1024 threads at once; indexes are recycled as threads die, so this is a limit on
*simultaneous* threads, not on how many the process creates.

### `PROFILING STOPPED`

The session ended early and the numbers below it are evidence for the verdict, not a result. One
condition causes it: a leaked label under `strict`. Pass `strict = false` for labels on code you do
not own and cannot fix — the leak is then still counted and still printed.

---

## The last word, which is not a warning

```
A share is where time went. It is not what removing the operation would save.
```

Printed at the foot of every report because the one time it mattered it was worth a factor of 275.
In the Calcite trial an operation holding 46% of the time turned out to be worth **275× when
removed**, because it was creating work for every other operation as well as doing its own. A
profiler that prints a ranked list invites exactly one response — delete the top row — and the two
questions can be orders of magnitude apart.
