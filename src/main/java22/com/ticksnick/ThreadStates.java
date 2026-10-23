package com.ticksnick;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Where a thread that Java calls RUNNABLE really is, asked of Windows. The Java 22+ version of the
 * class; the Java 21 one in {@code src/main/java} can ask nothing, and the two keep the same methods.
 *
 * Two questions, both measured in the probe trial (findings.md, "Telling a runnable thread's
 * state apart"): whether the thread's cycle counter moves over a microsecond - on a CPU - and, if
 * not, whether it is stopped inside a system call - I/O or a native wait - or is ready and waiting
 * for a core. The second is {@code NtQueryInformationThread(ThreadLastSystemCall)}, which is not
 * documented and succeeds only while the thread is in the kernel.
 *
 * Only on Windows. Linking needs {@code --enable-native-access=ALL-UNNAMED}, or the JVM prints a
 * warning, which is why nothing is linked until a session asks for it.
 */
final class ThreadStates {
    /** THREAD_QUERY_LIMITED_INFORMATION, for the cycles, with THREAD_QUERY_INFORMATION and THREAD_GET_CONTEXT, for the system call. */
    private static final int ACCESS = 0x0848;
    /** The THREADINFOCLASS of the last system call. */
    private static final int THREAD_LAST_SYSTEM_CALL = 21;
    /** What the query writes: the first argument and the call number, padded. */
    private static final int LAST_CALL_BYTES = 16;

    private static volatile boolean enabled;
    private static volatile String unavailable = "it is switched off";

    private static MethodHandle getCurrentThreadId;
    private static MethodHandle openThread;
    private static MethodHandle closeHandle;
    private static MethodHandle queryThreadCycleTime;
    private static MethodHandle ntQueryInformationThread;

    // Out-parameters. Only the sampling thread reads a thread's state, and one session samples at a time.
    private static MemorySegment cycles;
    private static MemorySegment lastCall;
    private static MemorySegment returned;

    private ThreadStates() {
    }

    static synchronized void enable() {
        if (enabled) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            unavailable = "it is Windows only for now";
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            Arena arena = Arena.global();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll", arena);
            getCurrentThreadId = linker.downcallHandle(kernel32.find("GetCurrentThreadId").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT));
            openThread = linker.downcallHandle(kernel32.find("OpenThread").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            closeHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            queryThreadCycleTime = linker.downcallHandle(kernel32.find("QueryThreadCycleTime").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            ntQueryInformationThread = linker.downcallHandle(ntdll.find("NtQueryInformationThread").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            cycles = arena.allocate(JAVA_LONG);
            lastCall = arena.allocate(LAST_CALL_BYTES, 8);
            returned = arena.allocate(JAVA_INT);
            unavailable = null;
            enabled = true;
        } catch (Throwable e) {
            unavailable = "the native calls could not be linked: " + e;
        }
    }

    static boolean enabled() {
        return enabled;
    }

    static String unavailable() {
        return unavailable;
    }

    /**
     * 0 for a virtual thread: the id would be its carrier's, which serves other virtual threads in turn,
     * so whatever the probe read would be somebody else's state.
     */
    static int currentThreadId() {
        if (!enabled || Thread.currentThread().isVirtual()) return 0;
        try {
            return (int) getCurrentThreadId.invokeExact();
        } catch (Throwable e) {
            return 0;
        }
    }

    static long open(int osThreadId) {
        if (!enabled || osThreadId == 0) return 0;
        try {
            return ((MemorySegment) openThread.invokeExact(ACCESS, 0, osThreadId)).address();
        } catch (Throwable e) {
            return 0;
        }
    }

    static void close(long handle) {
        if (!enabled || handle == 0) return;
        try {
            int ignored = (int) closeHandle.invokeExact(MemorySegment.ofAddress(handle));
        } catch (Throwable ignored) {
        }
    }

    static long cycles(long handle) {
        try {
            int ok = (int) queryThreadCycleTime.invokeExact(MemorySegment.ofAddress(handle), cycles);
            return ok == 0 ? -1 : cycles.get(JAVA_LONG, 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    static boolean inSystemCall(long handle) {
        try {
            int status = (int) ntQueryInformationThread.invokeExact(
                    MemorySegment.ofAddress(handle), THREAD_LAST_SYSTEM_CALL, lastCall, LAST_CALL_BYTES, returned);
            return status == 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
