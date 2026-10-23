import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.LockSupport;

import static java.lang.foreign.ValueLayout.*;

/**
 * Does reading a running thread's cycle counter from another thread slow that thread down?
 * A worker spins and counts its work; a reader reads the worker's cycle counter at a fixed rate,
 * spinning between reads so the machine is equally loaded at every rate, 0 included. Rates are
 * interleaved over several rounds so a moving clock hits every rate alike. Then the cost of one
 * read against a running and against a sleeping thread, and of the last-system-call query.
 *   java --enable-native-access=ALL-UNNAMED InterruptCost.java
 */
public class InterruptCost {
    static final Linker LINKER = Linker.nativeLinker();
    static final Arena ARENA = Arena.global();
    static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", ARENA);
    static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll", ARENA);
    static final MethodHandle GET_CURRENT_THREAD_ID = LINKER.downcallHandle(K32.find("GetCurrentThreadId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle OPEN_THREAD = LINKER.downcallHandle(K32.find("OpenThread").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    static final MethodHandle QUERY_THREAD_CYCLE_TIME = LINKER.downcallHandle(K32.find("QueryThreadCycleTime").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle NT_QUERY_INFORMATION_THREAD = LINKER.downcallHandle(NTDLL.find("NtQueryInformationThread").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    static final MemorySegment OUT = ARENA.allocate(JAVA_LONG), LAST = ARENA.allocate(16, 8), RET = ARENA.allocate(JAVA_INT);

    static long cycles(MemorySegment h) throws Throwable {
        int ok = (int) QUERY_THREAD_CYCLE_TIME.invokeExact(h, OUT);
        if (ok == 0) throw new IllegalStateException("QueryThreadCycleTime");
        return OUT.get(JAVA_LONG, 0);
    }

    static int lastCall(MemorySegment h) throws Throwable {
        return (int) NT_QUERY_INFORMATION_THREAD.invokeExact(h, 21, LAST, 16, RET);
    }

    static volatile boolean stop;
    static volatile long work;          // the worker's progress, in units of 1000 iterations
    static volatile int workerTid, sleeperTid;
    static volatile long sink;
    static double flatOutRate;

    static final int[] RATES = {0, 1_000, 10_000, 100_000, Integer.MAX_VALUE};
    static final int ROUNDS = 4;
    static final long PHASE_NANOS = 2_000_000_000L;
    /** Reader modes: read the cycle counter, or ask for the last system call. */
    static final int CYCLES = 0, LASTCALL = 1;

    public static void main(String[] args) throws Throwable {
        Thread worker = new Thread(() -> {
            try { workerTid = (int) GET_CURRENT_THREAD_ID.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); }
            long x = 1, w = 0;
            while (!stop) {
                for (int i = 0; i < 1000; i++) x = x * 6364136223846793005L + 1442695040888963407L;
                work = ++w;
            }
            sink = x;
        }, "worker");
        Thread sleeper = new Thread(() -> {
            try { sleeperTid = (int) GET_CURRENT_THREAD_ID.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); }
            while (!stop) LockSupport.parkNanos(1_000_000_000L);
        }, "sleeper");
        worker.setDaemon(true); sleeper.setDaemon(true);
        worker.start(); sleeper.start();
        while (workerTid == 0 || sleeperTid == 0) Thread.onSpinWait();
        MemorySegment wh = (MemorySegment) OPEN_THREAD.invokeExact(0x0848, 0, workerTid);
        MemorySegment sh = (MemorySegment) OPEN_THREAD.invokeExact(0x0848, 0, sleeperTid);

        // Warm-up: the JIT, the clock, both loops.
        phase(wh, CYCLES, 10_000, 1_000_000_000L);

        // The worker's work per second at each rate, rates interleaved, forwards then backwards.
        double[][] perSec = new double[RATES.length][ROUNDS];
        double[] lastCallPerSec = new double[ROUNDS], zeroBeside = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            for (int k = 0; k < RATES.length; k++) {
                int i = (r % 2 == 0) ? k : RATES.length - 1 - k;
                perSec[i][r] = phase(wh, CYCLES, RATES[i], PHASE_NANOS);
            }
            lastCallPerSec[r] = phase(wh, LASTCALL, 100_000, PHASE_NANOS);
            zeroBeside[r] = phase(wh, CYCLES, 0, PHASE_NANOS);
        }

        double base = 0;
        for (int r = 0; r < ROUNDS; r++) base += perSec[0][r] + zeroBeside[r];
        base /= 2 * ROUNDS;
        System.out.printf(java.util.Locale.ROOT, "worker work at rate 0: %.1f M units/s (rounds: %s; beside the query: %s)%n",
                base / 1e6, row(perSec[0]), row(zeroBeside));
        for (int i = 1; i < RATES.length; i++)
            report("cycle reads", RATES[i] == Integer.MAX_VALUE ? (int) flatOutRate : RATES[i], perSec[i], base);
        report("last-call queries", 100_000, lastCallPerSec, base);

        // What one call costs the caller, against a running and a sleeping target.
        System.out.printf(java.util.Locale.ROOT, "cost of one call: cycles of a running thread %.0f ns, of a sleeping one %.0f ns; "
                        + "last-call query on a running thread %.0f ns, on a sleeping one %.0f ns%n",
                callNanos(wh, CYCLES), callNanos(sh, CYCLES), callNanos(wh, LASTCALL), callNanos(sh, LASTCALL));
        stop = true;
    }

    static String row(double[] v) {
        StringBuilder b = new StringBuilder();
        for (double d : v) b.append(String.format(java.util.Locale.ROOT, "%.1f ", d / 1e6));
        return b.toString().trim();
    }

    static void report(String what, int rate, double[] perSec, double base) {
        double mean = 0;
        for (double d : perSec) mean += d;
        mean /= perSec.length;
        double loss = 1 - mean / base;
        System.out.printf(java.util.Locale.ROOT, "%,7d %s/s: %.1f M units/s (rounds: %s), %.2f%% less work, %.2f us of the worker's time per call%n",
                rate, what, mean / 1e6, row(perSec), loss * 100, loss / rate * 1e6);
    }

    /** One phase: the reader calls at [rate] for [nanos], spinning between calls; returns the worker's work per second. */
    static double phase(MemorySegment h, int mode, int rate, long nanos) throws Throwable {
        long gap = rate == 0 ? 0 : Math.max(1, 1_000_000_000L / rate); long calls = 0;
        long start = System.nanoTime(), end = start + nanos, w0 = work, next = start;
        while (true) {
            long now = System.nanoTime();
            if (now >= end) break;
            if (rate > 0 && now >= next) {
                if (mode == CYCLES) sink += cycles(h); else sink += lastCall(h);
                calls++;
                next += gap;
                if (next < now) next = now + gap;
            } else Thread.onSpinWait();
        }
        long w1 = work, t = System.nanoTime();
        if (rate == Integer.MAX_VALUE) flatOutRate = calls * 1e9 / (t - start);
        return (w1 - w0) * 1e9 / (t - start);
    }

    static double callNanos(MemorySegment h, int mode) throws Throwable {
        for (int i = 0; i < 20_000; i++) if (mode == CYCLES) sink += cycles(h); else sink += lastCall(h);
        int n = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) if (mode == CYCLES) sink += cycles(h); else sink += lastCall(h);
        return (System.nanoTime() - t0) / (double) n;
    }
}
