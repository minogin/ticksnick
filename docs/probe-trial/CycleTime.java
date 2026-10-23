import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import static java.lang.foreign.ValueLayout.*;

/**
 * Can a 1 ms sampler attribute CPU per label on Windows with QueryThreadCycleTime?
 * 1. resolution and cost of the call; 2. a worker alternating BUSY and SLEEP phases, a sampler at 1 ms
 * charging each cycle delta to the phase it reads; 3. the same with getThreadCpuTime for contrast.
 */
public class CycleTime {
    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    static final MethodHandle GET_CURRENT_THREAD_ID = fn("GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle OPEN_THREAD = fn("OpenThread", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    static final MethodHandle QUERY_THREAD_CYCLE_TIME = fn("QueryThreadCycleTime", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final int THREAD_QUERY_LIMITED_INFORMATION = 0x0800;

    static MethodHandle fn(String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(KERNEL32.find(name).orElseThrow(), d);
    }

    static final MemorySegment OUT = Arena.global().allocate(JAVA_LONG);

    static long cycles(MemorySegment handle) throws Throwable {
        int ok = (int) QUERY_THREAD_CYCLE_TIME.invokeExact(handle, OUT);
        if (ok == 0) throw new IllegalStateException("QueryThreadCycleTime failed");
        return OUT.get(JAVA_LONG, 0);
    }

    static final long PROBE_NS = Long.getLong("probe", 1000);
    static volatile int phase;           // 1 busy, 2 asleep
    static volatile int workerTid;
    static volatile boolean stop;
    static volatile long busyNanosTruth, sleepNanosTruth;

    static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    public static void main(String[] args) throws Throwable {
        long busyNs = Long.parseLong(args.length > 0 ? args[0] : "300000");
        long sleepNs = Long.parseLong(args.length > 1 ? args[1] : "300000");
        int seconds = 5;

        Thread worker = new Thread(() -> {
            try { workerTid = (int) GET_CURRENT_THREAD_ID.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); }
            long busy = 0, slept = 0;
            while (!stop) {
                phase = 1;
                long t0 = System.nanoTime(); spin(busyNs); long t1 = System.nanoTime();
                phase = 2;
                java.util.concurrent.locks.LockSupport.parkNanos(sleepNs); long t2 = System.nanoTime();
                busy += t1 - t0; slept += t2 - t1;
            }
            busyNanosTruth = busy; sleepNanosTruth = slept;
        }, "worker");
        worker.start();
        while (workerTid == 0) Thread.onSpinWait();
        MemorySegment handle = (MemorySegment) OPEN_THREAD.invokeExact(THREAD_QUERY_LIMITED_INFORMATION, 0, workerTid);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long javaId = worker.threadId();

        // Cost of one cross-thread read.
        for (int i = 0; i < 20_000; i++) cycles(handle);
        long c0 = System.nanoTime();
        for (int i = 0; i < 100_000; i++) cycles(handle);
        double costNs = (System.nanoTime() - c0) / 100_000.0;
        long m0 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) mx.getThreadCpuTime(javaId);
        double mxCostNs = (System.nanoTime() - m0) / 10_000.0;

        // The sampler: a tick every 1 ms, spinning; each delta charged to the phase read at the tick.
        long[] cyclesBy = new long[3], cpuBy = new long[3], ticksBy = new long[3];
        long zeroCycleDeltas = 0, zeroCpuDeltas = 0, ticks = 0;
        long[] probeTicks = new long[3], probeRunning = new long[3]; long probeMixed = 0;
        long prevCycles = cycles(handle), prevCpu = mx.getThreadCpuTime(javaId);
        long next = System.nanoTime() + 1_000_000, end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            while (System.nanoTime() < next) Thread.onSpinWait();
            next += 1_000_000;
            int p = phase;
            long c = cycles(handle), cpu = mx.getThreadCpuTime(javaId);
            long dc = c - prevCycles, dcpu = cpu - prevCpu;
            prevCycles = c; prevCpu = cpu;
            cyclesBy[p] += dc; cpuBy[p] += dcpu; ticksBy[p]++; ticks++;
            if (dc == 0) zeroCycleDeltas++;
            // The probe: is the thread on a CPU right now? Two reads ~1 us apart, the phase read around them.
            int pa = phase; long a = cycles(handle); spin(PROBE_NS); long b = cycles(handle); int pb = phase;
            if (pa != pb) probeMixed++;
            else { probeTicks[pa]++; if (b > a) probeRunning[pa]++; }
            if (dcpu == 0) zeroCpuDeltas++;
        }
        stop = true;
        worker.join();

        double busyShare = busyNanosTruth / (double) (busyNanosTruth + sleepNanosTruth);
        System.out.printf("phases: busy %.2f ms, asleep %.2f ms requested; worker really busy %.1f%% of the time%n",
                busyNs / 1e6, sleepNs / 1e6, 100 * busyShare);
        System.out.printf("cost: QueryThreadCycleTime %.0f ns a call, getThreadCpuTime %.0f ns a call%n", costNs, mxCostNs);
        System.out.printf("ticks %,d: zero deltas - cycles %.1f%%, getThreadCpuTime %.1f%%%n",
                ticks, 100.0 * zeroCycleDeltas / ticks, 100.0 * zeroCpuDeltas / ticks);
        System.out.printf("ticks landing in busy %.1f%% (state sampling would say this much was work)%n",
                100.0 * ticksBy[1] / ticks);
        long totalCycles = cyclesBy[1] + cyclesBy[2], totalCpu = cpuBy[1] + cpuBy[2];
        System.out.printf("cycles charged to busy %.1f%%, to asleep %.1f%%  (ideal: 100 / 0)%n",
                100.0 * cyclesBy[1] / totalCycles, 100.0 * cyclesBy[2] / totalCycles);
        System.out.printf("getThreadCpuTime charged to busy %.1f%%, to asleep %.1f%%%n",
                100.0 * cpuBy[1] / totalCpu, 100.0 * cpuBy[2] / totalCpu);
        System.out.printf("probe (%d ns): running when busy %.1f%%, running when asleep %.1f%%  (ideal: 100 / 0); %d probes straddled a phase change%n",
                PROBE_NS, 100.0 * probeRunning[1] / probeTicks[1], 100.0 * probeRunning[2] / probeTicks[2], probeMixed);
        System.out.printf("on-CPU estimate from cycles: %.1f GHz x busy time = sanity: %.2f G cycles over %.2f s busy%n",
                totalCycles / (busyNanosTruth / 1e9) / 1e9, totalCycles / 1e9, busyNanosTruth / 1e9);
    }
}
