// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Coordinates orderly completion of test JVMs that may have initialized AWT. */
final class TestProcessExitCoordinator {
    static final String AWT_MODULE_OPEN_ARGUMENT = "--add-opens=java.desktop/sun.awt=ALL-UNNAMED";
    private static final int COORDINATOR_FAILURE_STATUS = 1;
    private static final long THREAD_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String AWT_SHUTDOWN_THREAD_NAME = "AWT-Shutdown";
    private static final String AWT_EVENT_QUEUE_THREAD_PREFIX = "AWT-EventQueue-";
    private static final String AWT_AUTO_SHUTDOWN_CLASS = "sun.awt.AWTAutoShutdown";
    private static final String AWT_EVENT_DISPATCH_THREAD_CLASS = "java.awt.EventDispatchThread";

    @FunctionalInterface
    interface ThreadWaiter {
        void waitFor(Thread thread, long timeoutNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface ShutdownAction {
        void run() throws Exception;
    }

    static final class Hooks {
        final Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot_;
        final LongSupplier nanoTime_;
        final ThreadWaiter threadWaiter_;
        final ShutdownAction awtShutdownAction_;
        final IntConsumer exitAction_;
        final PrintWriter diagnostics_;

        Hooks(Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot, LongSupplier nanoTime, ThreadWaiter threadWaiter, ShutdownAction awtShutdownAction, IntConsumer exitAction, PrintWriter diagnostics) {
            threadSnapshot_ = Objects.requireNonNull(threadSnapshot, "threadSnapshot");
            nanoTime_ = Objects.requireNonNull(nanoTime, "nanoTime");
            threadWaiter_ = Objects.requireNonNull(threadWaiter, "threadWaiter");
            awtShutdownAction_ = Objects.requireNonNull(awtShutdownAction, "awtShutdownAction");
            exitAction_ = Objects.requireNonNull(exitAction, "exitAction");
            diagnostics_ = Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    private TestProcessExitCoordinator() {}

    static void finish(int exitStatus) {
        finish(exitStatus, THREAD_WAIT_NANOS, TestProcessExitCoordinator::stopAwtEventDispatchThreads);
    }

    static void finish(int exitStatus, long waitTimeoutNanos) {
        finish(exitStatus, waitTimeoutNanos, TestProcessExitCoordinator::stopAwtEventDispatchThreads);
    }

    static void finish(int exitStatus, long waitTimeoutNanos, ShutdownAction awtShutdownAction) {
        Hooks hooks = new Hooks(Thread::getAllStackTraces, System::nanoTime, TestProcessExitCoordinator::join, awtShutdownAction, System::exit, new PrintWriter(System.err, true));
        finish(exitStatus, waitTimeoutNanos, hooks);
    }

    static void finish(int exitStatus, long waitTimeoutNanos, Hooks hooks) {
        if (waitTimeoutNanos <= 0) throw new IllegalArgumentException("waitTimeoutNanos must be positive");
        Objects.requireNonNull(hooks, "hooks");

        Thread currentThread = Thread.currentThread();
        boolean interrupted = false;
        boolean coordinatorFailed = false;
        long startNanos = hooks.nanoTime_.getAsLong();
        boolean awtShutdownRequested = false;
        boolean awtShutdownPosted = false;
        while (true) {
            // Cleanup can hand work to newly-created threads, so every completed wait must be
            // followed by a fresh JVM-wide snapshot instead of relying on one startup snapshot.
            Map<Thread, StackTraceElement[]> pendingThreads = snapshotPendingThreads(hooks.threadSnapshot_.get(), currentThread);
            if (pendingThreads.isEmpty()) break;

            long remainingNanos = remainingNanos(waitTimeoutNanos, startNanos, hooks.nanoTime_.getAsLong());
            if (remainingNanos <= 0) {
                printTimeoutDiagnostics(hooks.diagnostics_, exitStatus, waitTimeoutNanos, pendingThreads);
                coordinatorFailed = true;
                break;
            }

            if (!awtShutdownRequested && containsAwtInfrastructure(pendingThreads)) {
                // Do not resolve AppContext on an AWT-free path: loading internal AWT classes
                // solely for shutdown could itself create the infrastructure we are avoiding.
                awtShutdownRequested = true;
                try {
                    hooks.awtShutdownAction_.run();
                    awtShutdownPosted = true;
                } catch (Exception failure) {
                    // An internal-access or shutdown-action failure must not jump straight to
                    // System.exit: the same AWT/native race that motivated this coordinator would
                    // recur. Keep waiting to the shared deadline, then fail with a nonzero status.
                    printAwtEventDispatchFailureDiagnostics(hooks.diagnostics_, exitStatus, failure);
                    coordinatorFailed = true;
                }
                // The action may have terminated or created threads. Resnapshot before applying
                // the remaining budget so a now-quiescent process is never reported as timed out.
                continue;
            }

            // AppContext stops EDTs but not AWTAutoShutdown when a native toolkit remains marked
            // busy. Only after its events were posted, interrupt blockers verified by both name and
            // a fresh AWTAutoShutdown.run stack. Java 17 clears blockerThread under mainLock in its
            // finally block; later rescans also catch replacement blockers created during cleanup.
            if (awtShutdownPosted && !tryInterruptAwtShutdownThreads(pendingThreads, hooks.diagnostics_, exitStatus)) {
                coordinatorFailed = true;
                awtShutdownPosted = false;
            }

            Thread pendingThread = pendingThreads.keySet().iterator().next();
            try {
                hooks.threadWaiter_.waitFor(pendingThread, remainingNanos);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (interrupted) currentThread.interrupt();
        if (exitStatus != 0 || coordinatorFailed) hooks.exitAction_.accept(exitStatus != 0 ? exitStatus : COORDINATOR_FAILURE_STATUS);
    }

    private static Map<Thread, StackTraceElement[]> snapshotPendingThreads(Map<Thread, StackTraceElement[]> snapshot, Thread currentThread) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<Thread, StackTraceElement[]> pendingThreads = new LinkedHashMap<Thread, StackTraceElement[]>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == null || thread == currentThread || thread.isDaemon() || thread.getState() == Thread.State.TERMINATED || !thread.isAlive()) continue;
            StackTraceElement[] stack = entry.getValue();
            pendingThreads.put(thread, stack == null ? new StackTraceElement[0] : stack);
        }
        return pendingThreads;
    }

    private static boolean containsAwtInfrastructure(Map<Thread, StackTraceElement[]> pendingThreads) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : pendingThreads.entrySet()) {
            Thread thread = entry.getKey();
            if (isAwtShutdownThread(thread, entry.getValue()) || thread.getName().startsWith(AWT_EVENT_QUEUE_THREAD_PREFIX)) return true;
            for (StackTraceElement frame : entry.getValue()) {
                String className = frame.getClassName();
                if (AWT_EVENT_DISPATCH_THREAD_CLASS.equals(className)) return true;
            }
        }
        return false;
    }

    private static boolean tryInterruptAwtShutdownThreads(Map<Thread, StackTraceElement[]> pendingThreads, PrintWriter diagnostics, int exitStatus) {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry : pendingThreads.entrySet()) {
                if (isAwtShutdownThread(entry.getKey(), entry.getValue())) entry.getKey().interrupt();
            }
            return true;
        } catch (SecurityException failure) {
            printAwtBlockerInterruptionFailureDiagnostics(diagnostics, exitStatus, failure);
            return false;
        }
    }

    private static boolean isAwtShutdownThread(Thread thread, StackTraceElement[] stack) {
        if (!AWT_SHUTDOWN_THREAD_NAME.equals(thread.getName())) return false;
        for (StackTraceElement frame : stack) {
            if (AWT_AUTO_SHUTDOWN_CLASS.equals(frame.getClassName()) && "run".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    static void stopAwtEventDispatchThreads() throws Exception {
        Class<?> appContextClass = Class.forName("sun.awt.AppContext");
        Method stopEventDispatchThreads = appContextClass.getDeclaredMethod("stopEventDispatchThreads");
        if (!stopEventDispatchThreads.trySetAccessible()) throw new IllegalAccessException("Java must be started with " + AWT_MODULE_OPEN_ARGUMENT);
        invokeShutdownMethod(stopEventDispatchThreads);
    }

    static void invokeShutdownMethod(Method shutdownMethod) throws Exception {
        try {
            shutdownMethod.invoke(null);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw exception;
        }
    }

    private static long remainingNanos(long waitTimeoutNanos, long startNanos, long currentNanos) {
        // nanoTime values can wrap; subtracting samples before comparing their short elapsed
        // interval is the supported wrap-safe form.
        return waitTimeoutNanos - (currentNanos - startNanos);
    }

    private static void join(Thread thread, long timeoutNanos) throws InterruptedException {
        long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
        int additionalNanos = (int) (timeoutNanos - TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        thread.join(timeoutMillis, additionalNanos);
    }

    private static void printTimeoutDiagnostics(PrintWriter diagnostics, int exitStatus, long waitTimeoutNanos, Map<Thread, StackTraceElement[]> pendingThreads) {
        long waitTimeoutMillis = TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos);
        diagnostics.println("Timed out after " + waitTimeoutMillis + " ms waiting for non-daemon Java threads before test process completion with status " + exitStatus + ":");
        printThreadDiagnostics(diagnostics, pendingThreads);
    }

    private static void printAwtEventDispatchFailureDiagnostics(PrintWriter diagnostics, int exitStatus, Exception failure) {
        diagnostics.println("Failed to request orderly AWT event-dispatch shutdown before test process completion with status " + exitStatus + ":");
        failure.printStackTrace(diagnostics);
        diagnostics.flush();
    }

    private static void printAwtBlockerInterruptionFailureDiagnostics(PrintWriter diagnostics, int exitStatus, SecurityException failure) {
        diagnostics.println("Failed to interrupt the AWT shutdown blocker after event-dispatch shutdown with status " + exitStatus + ":");
        failure.printStackTrace(diagnostics);
        diagnostics.flush();
    }

    private static void printThreadDiagnostics(PrintWriter diagnostics, Map<Thread, StackTraceElement[]> pendingThreads) {
        List<Map.Entry<Thread, StackTraceElement[]>> entries = new ArrayList<Map.Entry<Thread, StackTraceElement[]>>(pendingThreads.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getKey().getId()));
        for (Map.Entry<Thread, StackTraceElement[]> entry : entries) {
            Thread thread = entry.getKey();
            diagnostics.println("  Thread \"" + thread.getName() + "\" id=" + thread.getId() + " state=" + thread.getState());
            StackTraceElement[] stack = entry.getValue();
            if (stack.length == 0) {
                diagnostics.println("    <no Java stack available>");
                continue;
            }
            for (StackTraceElement frame : stack) diagnostics.println("    at " + frame);
        }
        diagnostics.flush();
    }
}
