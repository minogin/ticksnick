package com.ticksnick;

/**
 * Where a thread that Java calls RUNNABLE really is: on a CPU, waiting for a core, or blocked inside
 * a system call - I/O, or a native call that waits.
 *
 * This is the Java 21 version, and it can ask nothing: reaching the operating system needs the
 * foreign function API, which is final only from Java 22. The jar is multi-release, and on a 22+
 * JVM the class of the same name in {@code META-INF/versions/22} is loaded instead - the source is
 * in {@code src/main/java22}. The two must keep the same methods. See plan.md, "The thread-state
 * probe".
 */
final class ThreadStates {
    private ThreadStates() {
    }

    /** Links the native calls, once. Called by {@code Profiler.start}, never by the hook. */
    static void enable() {
    }

    /** Whether {@link #enable()} succeeded, so slots made from now on record their thread's id. */
    static boolean enabled() {
        return false;
    }

    /** Why there is no probe here, or null when there is one. */
    static String unavailable() {
        return "it needs Java 22 or later, on Windows";
    }

    /** The calling thread's operating-system id, or 0 when it cannot be probed. */
    static int currentThreadId() {
        return 0;
    }

    /** A handle to the thread with this id, or 0. */
    static long open(int osThreadId) {
        return 0;
    }

    static void close(long handle) {
    }

    /** The thread's CPU cycles so far, or -1. */
    static long cycles(long handle) {
        return -1;
    }

    /** Whether the thread is blocked inside a system call right now. */
    static boolean inSystemCall(long handle) {
        return false;
    }
}
