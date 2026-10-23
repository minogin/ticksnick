import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static java.lang.foreign.ValueLayout.*;

/**
 * Can a 1 ms sampler tell apart, per sample: on a CPU / waiting for a core / Java-level waiting (park) /
 * blocked in I/O that Java reports as RUNNABLE? Three tracked workers with a known truth, on an idle and on a
 * full machine. Method A, every tick: Java state + the cycle probe + "is any core idle now". Method B, every
 * 10th tick: Windows' own thread state from NtQuerySystemInformation.
 *   java --enable-native-access=ALL-UNNAMED Probe.java [crowd threads]
 */
public class Probe {
    static final Linker LINKER = Linker.nativeLinker();
    static final Arena ARENA = Arena.global();
    static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", ARENA);
    static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll", ARENA);
    static final MethodHandle GET_CURRENT_THREAD_ID = fn(K32, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle GET_CURRENT_PROCESS_ID = fn(K32, "GetCurrentProcessId", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle OPEN_THREAD = fn(K32, "OpenThread", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    static final MethodHandle QUERY_THREAD_CYCLE_TIME = fn(K32, "QueryThreadCycleTime", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle QUERY_IDLE_PROCESSOR_CYCLE_TIME = fn(K32, "QueryIdleProcessorCycleTime", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GET_THREAD_IO_PENDING_FLAG = fn(K32, "GetThreadIOPendingFlag", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MemorySegment FLAG = ARENA.allocate(JAVA_INT);
    static boolean ioPending(MemorySegment handle) throws Throwable {
        int ok = (int) GET_THREAD_IO_PENDING_FLAG.invokeExact(handle, FLAG);
        if (ok == 0) throw new IllegalStateException("GetThreadIOPendingFlag");
        return FLAG.get(JAVA_INT, 0) != 0;
    }
    static final MethodHandle NT_QUERY_INFORMATION_THREAD = fn(NTDLL, "NtQueryInformationThread", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    static final MemorySegment LAST_CALL = ARENA.allocate(32, 8), LAST_RET = ARENA.allocate(JAVA_INT);
    /** The system call the thread is blocked in, or -1 when the query fails (the thread is not in one). ThreadLastSystemCall = 21. */
    static int lastSystemCall(MemorySegment handle) throws Throwable {
        int status = (int) NT_QUERY_INFORMATION_THREAD.invokeExact(handle, 21, LAST_CALL, 16, LAST_RET);
        if (status != 0) { lastStatus = status; return -1; }
        return LAST_CALL.get(JAVA_SHORT, 8) & 0xFFFF;
    }
    static volatile int lastStatus;
    static final MethodHandle NT_QUERY_SYSTEM_INFORMATION = fn(NTDLL, "NtQuerySystemInformation", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(), d);
    }

    static final int CPUS = Runtime.getRuntime().availableProcessors();
    static final MemorySegment OUT = ARENA.allocate(JAVA_LONG);
    static final MemorySegment IDLE_LEN = ARENA.allocate(JAVA_INT);
    static final MemorySegment IDLE_A = ARENA.allocate(JAVA_LONG, CPUS), IDLE_B = ARENA.allocate(JAVA_LONG, CPUS);
    static final int NT_BUF = 16 << 20;
    static final MemorySegment NT = ARENA.allocate(NT_BUF, 8), NT_RET = ARENA.allocate(JAVA_INT);

    static long cycles(MemorySegment handle) throws Throwable {
        int ok = (int) QUERY_THREAD_CYCLE_TIME.invokeExact(handle, OUT);
        if (ok == 0) throw new IllegalStateException("QueryThreadCycleTime");
        return OUT.get(JAVA_LONG, 0);
    }

    static void idle(MemorySegment into) throws Throwable {
        IDLE_LEN.set(JAVA_INT, 0, (int) into.byteSize());
        int ok = (int) QUERY_IDLE_PROCESSOR_CYCLE_TIME.invokeExact(IDLE_LEN, into);
        if (ok == 0) throw new IllegalStateException("QueryIdleProcessorCycleTime");
    }

    static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    static volatile boolean stop;

    /** A tracked worker: its Java thread, its Windows id and a handle to read its cycles. */
    static final class Worker {
        final String name; final Thread thread; volatile int tid; MemorySegment handle;
        // [method][category] counts; method 0 = A every tick, 1 = A on the ticks B ran, 2 = B
        final long[][] counts = new long[5][CATEGORIES.length];
        final java.util.TreeMap<Integer, Long> calls = new java.util.TreeMap<>();
        Worker(String name, Runnable body) {
            this.name = name;
            this.thread = new Thread(() -> {
                try { tid = (int) GET_CURRENT_THREAD_ID.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); }
                body.run();
            }, name);
            this.thread.setDaemon(true);
        }
    }

    static final String[] CATEGORIES = {"cpu", "core-wait", "java-wait", "io/native", "other"};
    static final int CPU = 0, CORE_WAIT = 1, JAVA_WAIT = 2, IO = 3, OTHER = 4;

    public static void main(String[] args) throws Throwable {
        int crowd = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int seconds = 6;
        int pid = (int) GET_CURRENT_PROCESS_ID.invokeExact();

        // The socket the io worker blocks on: a feeder writes one byte every 2 ms.
        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        Socket client = new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket accepted = server.accept();
        Thread feeder = new Thread(() -> {
            try (OutputStream out = accepted.getOutputStream()) {
                while (!stop) { out.write(1); out.flush(); Thread.sleep(2); }
            } catch (Exception ignored) { }
        }, "feeder");
        feeder.setDaemon(true);

        // The channel the select worker waits on, NIO style: a second feeder writes one byte every 2 ms.
        java.nio.channels.ServerSocketChannel nioServer = java.nio.channels.ServerSocketChannel.open()
                .bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
        java.nio.channels.SocketChannel nioClient = java.nio.channels.SocketChannel.open(nioServer.getLocalAddress());
        java.nio.channels.SocketChannel nioAccepted = nioServer.accept();
        nioClient.configureBlocking(false);
        java.nio.channels.Selector selector = java.nio.channels.Selector.open();
        nioClient.register(selector, java.nio.channels.SelectionKey.OP_READ);
        Thread nioFeeder = new Thread(() -> {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            try { while (!stop) { one.clear(); one.put((byte) 1).flip(); nioAccepted.write(one); Thread.sleep(2); } } catch (Exception ignored) { }
        }, "nio-feeder");
        nioFeeder.setDaemon(true);
        nioFeeder.start();

        List<Worker> workers = List.of(
                new Worker("select", () -> {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64);
                    try {
                        while (!stop) {
                            selector.select();
                            selector.selectedKeys().clear();
                            buf.clear(); nioClient.read(buf);
                        }
                    } catch (Exception ignored) { }
                }),
                new Worker("busy", () -> { while (!stop) Thread.onSpinWait(); }),
                new Worker("park", () -> { while (!stop) LockSupport.parkNanos(1_000_000); }),
                new Worker("io", () -> {
                    try (InputStream in = client.getInputStream()) { while (!stop && in.read() >= 0) { } } catch (Exception ignored) { }
                }));
        List<Thread> crowdThreads = new ArrayList<>();
        for (int i = 0; i < crowd; i++) {
            Thread t = new Thread(() -> { while (!stop) Thread.onSpinWait(); }, "crowd-" + i);
            t.setDaemon(true); crowdThreads.add(t);
        }
        feeder.start();
        for (Worker w : workers) w.thread.start();
        for (Thread t : crowdThreads) t.start();
        for (Worker w : workers) {
            while (w.tid == 0) Thread.onSpinWait();
            w.handle = (MemorySegment) OPEN_THREAD.invokeExact(0x0848, 0, w.tid);
        }
        Thread.sleep(500);

        long idleNanos = 0, cycleNanos = 0, pendingNanos = 0, lastNanos = 0;
        long probeNanos = 0, probes = 0, ntNanos = 0, ntCalls = 0, ntThreads = 0;
        long[] a = new long[workers.size()], b = new long[workers.size()];
        long next = System.nanoTime() + 1_000_000, end = System.nanoTime() + seconds * 1_000_000_000L;
        int tick = 0;
        while (System.nanoTime() < end) {
            while (System.nanoTime() < next) Thread.onSpinWait();
            next += 1_000_000;
            tick++;
            boolean runB = tick % 10 == 0;

            long p0 = System.nanoTime();
            Thread.State[] state = new Thread.State[workers.size()];
            for (int i = 0; i < workers.size(); i++) state[i] = workers.get(i).thread.getState();
            long d1 = System.nanoTime();
            idle(IDLE_A);
            idleNanos += System.nanoTime() - d1;
            long c0 = System.nanoTime();
            for (int i = 0; i < workers.size(); i++) a[i] = cycles(workers.get(i).handle);
            cycleNanos += System.nanoTime() - c0;
            spin(1000);
            for (int i = 0; i < workers.size(); i++) b[i] = cycles(workers.get(i).handle);
            long i0 = System.nanoTime();
            boolean[] pending = new boolean[workers.size()];
            for (int i = 0; i < workers.size(); i++) pending[i] = ioPending(workers.get(i).handle);
            pendingNanos += System.nanoTime() - i0;
            long l0 = System.nanoTime();
            int[] last = new int[workers.size()];
            for (int i = 0; i < workers.size(); i++) last[i] = lastSystemCall(workers.get(i).handle);
            lastNanos += System.nanoTime() - l0;
            long d0 = System.nanoTime();
            idle(IDLE_B);
            idleNanos += System.nanoTime() - d0;
            boolean coreIdle = false;
            for (int c = 0; c < CPUS; c++) if (IDLE_B.getAtIndex(JAVA_LONG, c) > IDLE_A.getAtIndex(JAVA_LONG, c)) coreIdle = true;
            probeNanos += System.nanoTime() - p0; probes++;

            int[] nt = null;
            if (runB) {
                long n0 = System.nanoTime();
                int status = (int) NT_QUERY_SYSTEM_INFORMATION.invokeExact(5, NT, NT_BUF, NT_RET);
                if (status != 0) throw new IllegalStateException("NtQuerySystemInformation 0x" + Integer.toHexString(status));
                nt = new int[workers.size()];
                java.util.Arrays.fill(nt, -1);
                long off = 0;
                while (true) {
                    int nextOff = NT.get(JAVA_INT, off);
                    int threads = NT.get(JAVA_INT, off + 4);
                    long processId = NT.get(JAVA_LONG, off + 80);
                    if (processId == pid) {
                        ntThreads = threads;
                        for (int t = 0; t < threads; t++) {
                            long th = off + 256 + t * 80L;
                            long threadId = NT.get(JAVA_LONG, th + 48);
                            int threadState = NT.get(JAVA_INT, th + 68);
                            for (int i = 0; i < workers.size(); i++) if (workers.get(i).tid == threadId) nt[i] = threadState;
                        }
                        break;
                    }
                    if (nextOff == 0) break;
                    off += nextOff;
                }
                ntNanos += System.nanoTime() - n0; ntCalls++;
            }

            for (int i = 0; i < workers.size(); i++) {
                Worker w = workers.get(i);
                boolean running = b[i] > a[i];
                int catA;
                if (state[i] != Thread.State.RUNNABLE) catA = JAVA_WAIT;
                else if (running) catA = CPU;
                else catA = coreIdle ? IO : CORE_WAIT;
                w.counts[0][catA]++;
                int catC;
                if (state[i] != Thread.State.RUNNABLE) catC = JAVA_WAIT;
                else if (running) catC = CPU;
                else catC = pending[i] ? IO : CORE_WAIT;
                w.counts[3][catC]++;
                int catD;
                if (state[i] != Thread.State.RUNNABLE) catD = JAVA_WAIT;
                else if (running) catD = CPU;
                else catD = last[i] >= 0 ? IO : CORE_WAIT;
                w.counts[4][catD]++;
                if (!running) w.calls.merge(last[i], 1L, Long::sum);
                if (nt != null) {
                    w.counts[1][catA]++;
                    // Windows KTHREAD_STATE: 1 Ready, 2 Running, 3 Standby, 5 Waiting, 7 DeferredReady
                    int s = nt[i];
                    int catB;
                    if (s == 2) catB = CPU;
                    else if (s == 1 || s == 3 || s == 7) catB = CORE_WAIT;
                    else if (s == 5) catB = state[i] == Thread.State.RUNNABLE ? IO : JAVA_WAIT;
                    else catB = OTHER;
                    w.counts[2][catB]++;
                }
            }
        }
        stop = true;
        for (Worker w : workers) LockSupport.unpark(w.thread);
        client.close(); accepted.close(); server.close();

        System.out.printf("crowd %d busy threads on %d CPUs, %d s%n", crowd, CPUS, seconds);
        System.out.printf("cost: method A %.1f us a tick for 3 threads (1 us of it the gap); NtQuerySystemInformation %.0f us a call (%d threads in this process)%n",
                probeNanos / 1e3 / probes, ntNanos / 1e3 / ntCalls, ntThreads);
        System.out.printf("cost per call: idle counters %.1f us, cycle read %.2f us, io-pending flag %.2f us, last system call %.2f us (last failure 0x%x)%n",
                idleNanos / 1e3 / (2.0 * probes), cycleNanos / 1e3 / (probes * workers.size()), pendingNanos / 1e3 / (probes * workers.size()), lastNanos / 1e3 / (probes * workers.size()), lastStatus);
        String[] methods = {"A every tick", "A on B's ticks", "B (Windows state)", "C io-pending flag", "D last system call"};
        for (Worker w : workers) {
            for (int m = 0; m < 5; m++) {
                long total = 0; for (long c : w.counts[m]) total += c;
                StringBuilder sb = new StringBuilder(String.format("  %-5s %-18s", w.name, methods[m]));
                for (int c = 0; c < CATEGORIES.length; c++) sb.append(String.format("  %s %5.1f%%", CATEGORIES[c], 100.0 * w.counts[m][c] / total));
                System.out.println(sb);
            }
            System.out.println("  " + w.name + " off-CPU samples by system call (-1 = query failed): " + w.calls);
        }
    }
}
