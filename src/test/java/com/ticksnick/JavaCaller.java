package com.ticksnick;

import java.nio.file.Path;

/**
 * The public API as a Java caller writes it. It is here to compile: a Kotlin change that makes a call
 * unreachable from Java stops this file compiling. {@code JavaCallerTest} runs it.
 */
public final class JavaCaller {
    enum Kind { PARSE, CHECK }

    static final int PLAN = Profiler.registerCoarse("java:plan");
    static final int RULE = Profiler.registerFine("java:rule");
    static final int LOOKUP = Profiler.registerFine("java:lookup");
    static final CoarseOps LEVEL = Profiler.registerCoarse("java:level", 2);
    static final FineOps SLOT = Profiler.registerFine("java:slot", 2);
    static final CoarseEnumOps<Kind> PHASE = Profiler.registerCoarse("java:phase", Kind.class);
    static final FineEnumOps<Kind> STEP = Profiler.registerFine("java:step", Kind.class);

    static long sink;
    static volatile byte[] held;

    private JavaCaller() {
    }

    /** One session through every Java form, {@code plans} times over. */
    static Run run(int plans) {
        Profiler.start(1.0, WaitStrategy.PARK);
        Profiler.tag("plans", plans);
        for (int i = 0; i < plans; i++) {
            try (var plan = Profiler.coarse(PLAN)) {
                try (var rule = Profiler.fine(RULE)) {
                    sink += i;
                }
                // The pair a callback places, with the finally a block needs.
                Profiler.enterFine(RULE);
                try {
                    sink += i;
                } finally {
                    Profiler.exitFine(RULE);
                }
                try (var lookup = Profiler.fine(LOOKUP, 10)) {
                    for (int k = 0; k < 10; k++) sink += k;
                }
                Profiler.enterFine(LOOKUP, 5);
                Profiler.exitFine(LOOKUP);
                try (var level = Profiler.coarse(LEVEL.get(1))) {
                    try (var slot = Profiler.fine(SLOT.get(0))) {
                        sink++;
                    }
                }
                Profiler.enterCoarse(PHASE.get(Kind.CHECK));
                try (var step = Profiler.fine(STEP.get(Kind.CHECK))) {
                    sink++;
                }
                Profiler.exitCoarse(PHASE.get(Kind.CHECK));
            }
        }
        if (!Profiler.expectBalanced()) throw new AssertionError("a label was left open");
        return Profiler.stop();
    }

    /** The setting from Java, through a scope and a pair: 1 MB in the plan and 2 MB in a level inside it. */
    static Run allocating(int plans) {
        Profiler.start(1.0, WaitStrategy.PARK, 0.25, true, true);
        for (int i = 0; i < plans; i++) {
            try (var plan = Profiler.coarse(PLAN)) {
                held = new byte[1 << 20];
                Profiler.enterCoarse(LEVEL.get(0));
                held = new byte[2 << 20];
                Profiler.exitCoarse(LEVEL.get(0));
            }
        }
        return Profiler.stop();
    }

    /**
     * Work handed to other threads by hand: {@code within} for a task working within the plan, a
     * context and {@code using} for a label entered on one thread and exited on another.
     */
    static Run handOff() throws Exception {
        Profiler.start(1.0, WaitStrategy.PARK);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            try (var plan = Profiler.coarse(PLAN)) {
                Execution parent = Profiler.currentExecution();
                pool.submit(() -> Profiler.within(parent, () -> {
                    try (var level = Profiler.coarse(LEVEL.get(0))) {
                        sink++;
                    }
                })).get();
                sink += pool.submit(() -> Profiler.within(parent, () -> 42)).get();
                Context request = Profiler.newContext(parent);
                Profiler.using(request, () -> Profiler.enterCoarse(PHASE.get(Kind.PARSE)));
                pool.submit(() -> Profiler.using(request, () -> Profiler.exitCoarse(PHASE.get(Kind.PARSE)))).get();
                try (var mounted = request.mount()) {
                    sink++;
                }
                request.end();
            }
        } finally {
            pool.shutdown();
        }
        return Profiler.stop();
    }

    /** Saves {@code profile} into {@code folder} and renders the folder, alone and against itself. */
    static String report(Run profile, Path folder) {
        profile.save(folder);
        Profile runs = TickSnick.load(folder);
        return runs.render() + TickSnick.diff(runs, runs);
    }
}
