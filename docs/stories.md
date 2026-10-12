# Stories: findings for the articles, the talks and the workshop

Findings the profiler made about *programs*, kept for publication. Not findings about the profiler
itself - those are [findings.md](findings.md) - and not the argument for the project - that is
[case.md](case.md). An entry here is something a reader would want to know about a program, found
with this tool. Each says where it was seen and where the numbers are, so an article can be written
from the sources and not from memory.

**Two kinds, and the second is the one that sells.**

1. **Nice ones**: interesting on their own, found with the profiler. A standard tool could have found
   them too, perhaps with more work.
2. **Top ones**: could not have been made with a standard tool - a flame graph, JFR, a stack sampler -
   because the information is not in a stack. These carry the case for the tool.

Newest first within each section. An entry that is a conjecture says so.

---

## Top ones: not possible with a stack profiler

### A BFS costs ten to twenty times more per level, until the last one
*2026-10-12, the graph benchmark, `C:\idea\graph`. Numbers in its `docs/profiler-notes.md`.*

A coarse family `level[0..5]`, one label per depth of the traversal. Level 4 is 74.8% of the query
and level 5, with six times the nodes, is 19.8%: the cost of a level is its edges out, and the last
level has none. The same frames run at every depth, so a flame graph shows one BFS; the depth is a
loop variable, not a frame.

### The resizes, found by a column: a quarter of a 13 ns step is in executions over a millisecond
*2026-10-12, the graph benchmark. Numbers in its `docs/profiler-notes.md`.*

`Over 1t` is the share of an operation's time inside executions that outlived a sampling tick.
`next.add(toId)` on an `ArrayList` is 13 ns a call and reads 27.5% there; `visited.add` on a
`HashSet` is 227 ns and reads 11%, 0.8 s of the query. Those are the doublings: copying a
reference array of up to 13 million, and rehashing a set of 16 million. A stack profiler sees
`ArrayList.grow` and `HashMap.resize` as frames with a share; it cannot say that share is a handful
of executions a hundred thousand times longer than the rest. Conjecture on the cause; the number
is measured.

### A BFS level is made of different steps at different depths
*2026-10-12, the graph benchmark. Numbers in its `docs/profiler-notes.md`.*

The coarse family `level[0..5]` crossed with the fine steps: level 5 is 95% node lookup, level 4
is 81% hash insert, level 1 is 100% hash insert. One run, one table. A flame graph has the same
frames at every depth and no way to split them by a loop variable.

### The identity that matters is a field, not a frame
*Lucene trial, [trial-lucene.md](trial-lucene.md); the general case in [case.md](case.md).*

A `BooleanQuery` with four `TermQuery` clauses runs one `TermScorer` through one method four times
over; *which term* is a field of an object. A flame graph identifies three of the eight clauses of
an eight-clause query. A label per clause gives all eight.

### Many classes behind one inherited method
*Calcite trial, [trial-calcite.md](trial-calcite.md); [case.md](case.md).*

Query planning fires rules through one inherited `onMatch`; the frames name the method, the rule is
the receiver's class. Recoverable from a stack in principle, wrongly in practice. To be extracted
from the trial with its numbers.

### A number read as CPU was 55x what the machine had spent
*PostgreSQL trial, [trial-jdbc.md](trial-jdbc.md).*

A thread blocked in a socket read is RUNNABLE to the JVM, so every thread-state instrument calls it
work. The profiler's CPU-clock bound caught the contradiction and the column was fixed. The story
is less "the tool found a bug in the program" than "the tool found a bug in itself and says so", and
that may be the more honest article.

## Nice ones: interesting on their own

### A lookup and an insert cost the same: one miss into a boxed hash table
*2026-10-12, the graph benchmark, one query at depth 5. Numbers in its `docs/profiler-notes.md`.*

Fine labels on the two steps of the BFS, `node` around `graph.node(id)` on a boxed
`HashMap<Long, Node>` and `addUnvisited` around the edge loop with `times = node.targets.size`:

| step | share | calls | per call |
|---|---|---|---|
| `addUnvisited`, the edge loop | 66.0% | 32,374,539 edges | 203 ns per edge |
| `node`, the lookup | 32.0% | 16,038,234 nodes | 199 ns per lookup |
| the filter and the loop itself | 2.0% | 16,038,234 nodes | 12 ns per node |

A later run split the edge loop: `visited.add` alone is 227 ns per edge and the `ArrayList` append
13 ns, so the hash insert is the whole of it. One `visited.add` of a boxed `Long` into a set growing
to 16 million costs the same as one lookup in a map of 20 million: about 200 ns, one random access into a hash table that does not fit in cache,
with the pointer chase and the boxing behind each. The edge loop wins on count, not on unit cost -
and the count is 32 million, not 250 million: only levels 0 to 4 expand, 2.6 million of the 16
million visited nodes, and the 13 million at level 5 are looked up and never expanded.

The first reading of this was wrong - "a lookup is half the cost of twelve inserts, six times
dearer per unit" - because the edge count was assumed from the graph's average degree instead of
counted. The `times` label counted it.

Also in that report, not yet explained: 13.8% of the edge loop's time is inside executions that
lasted over a tick, against a machine floor of 2.8%. One node's edge loop is 413 ns, so an
execution over a millisecond is something else - a `HashSet` rehash at 16 million entries is the
candidate. Conjecture.

### GC pauses are a tenth of the run, and the sampler stops with everything else
*2026-10-01, the graph benchmark, `docs/profiler-notes.md` there; the measurement in
[findings.md](findings.md).*

5.7 s of a 63 s run was stop-the-world collection, confirmed to within 1% against the JVM's own
safepoint log by the one fact that a Java sampler cannot sample while stopped. A 16 GB heap with
10 GB live falls into a 1.7 to 2.6 s full collection inside a measured query, which is why query
times vary. The finding is about G1 on big boxed graphs as much as about the profiler.

### An operation holding 46% of the time was worth 275x when removed
*Lucene trial, [trial-lucene.md](trial-lucene.md); the legend cites it.*

A share is where time went, not what removing the operation would save: the operation was creating
work for everything else as well as doing its own. The two numbers are different questions and the
gap was two orders of magnitude.

---

## To extract

Entries the trials and findings hold and this file does not yet: the Calcite numbers, the Netty
event-loop shape (`epoll_wait` as RUNNABLE, the bound collapsing to "unbounded"), and the Lucene
placement finding where a share moved fourteen points and the thread-time did not.
