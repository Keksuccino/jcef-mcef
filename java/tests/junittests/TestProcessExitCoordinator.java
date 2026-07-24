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
    private static final long AWT_NATURAL_SHUTDOWN_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long AWT_QUIESCENCE_STABILITY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final String AWT_SHUTDOWN_THREAD_NAME = "AWT-Shutdown";
    private static final String AWT_EVENT_QUEUE_THREAD_PREFIX = "AWT-EventQueue-";
    private static final String AWT_AUTO_SHUTDOWN_CLASS = "sun.awt.AWTAutoShutdown";
    private static final String AWT_EVENT_DISPATCH_THREAD_CLASS = "java.awt.EventDispatchThread";

    @FunctionalInterface
    interface ThreadWaiter {
        void waitFor(Thread thread, long timeoutNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface StabilizationWaiter {
        void waitFor(long timeoutNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface RawSnapshotObserver {
        void observe(Map<Thread, StackTraceElement[]> snapshot);
    }

    @FunctionalInterface
    interface CompletionValidator {
        boolean validate();
    }

    @FunctionalInterface
    interface ShutdownAction {
        void run() throws Exception;
    }

    private record PendingThreadSnapshot(Map<Thread, StackTraceElement[]> threads, boolean awtObserved) {}

    static final class Hooks {
        final Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot_;
        final LongSupplier nanoTime_;
        final ThreadWaiter threadWaiter_;
        final StabilizationWaiter stabilizationWaiter_;
        final RawSnapshotObserver rawSnapshotObserver_;
        final LongSupplier observerReserveNanos_;
        final CompletionValidator completionValidator_;
        final ShutdownAction awtShutdownAction_;
        final IntConsumer exitAction_;
        final PrintWriter diagnostics_;

        Hooks(Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot, LongSupplier nanoTime, ThreadWaiter threadWaiter, ShutdownAction awtShutdownAction, IntConsumer exitAction, PrintWriter diagnostics) {
            this(threadSnapshot, nanoTime, threadWaiter, TestProcessExitCoordinator::waitForStabilization, awtShutdownAction, exitAction, diagnostics);
        }

        Hooks(Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot, LongSupplier nanoTime, ThreadWaiter threadWaiter, StabilizationWaiter stabilizationWaiter, ShutdownAction awtShutdownAction, IntConsumer exitAction, PrintWriter diagnostics) {
            this(threadSnapshot, nanoTime, threadWaiter, stabilizationWaiter, snapshot -> {}, () -> 0, () -> true, awtShutdownAction, exitAction, diagnostics);
        }

        Hooks(Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot, LongSupplier nanoTime, ThreadWaiter threadWaiter, StabilizationWaiter stabilizationWaiter, RawSnapshotObserver rawSnapshotObserver, LongSupplier observerReserveNanos, CompletionValidator completionValidator, ShutdownAction awtShutdownAction, IntConsumer exitAction, PrintWriter diagnostics) {
            threadSnapshot_ = Objects.requireNonNull(threadSnapshot, "threadSnapshot");
            nanoTime_ = Objects.requireNonNull(nanoTime, "nanoTime");
            threadWaiter_ = Objects.requireNonNull(threadWaiter, "threadWaiter");
            stabilizationWaiter_ = Objects.requireNonNull(stabilizationWaiter, "stabilizationWaiter");
            rawSnapshotObserver_ = Objects.requireNonNull(rawSnapshotObserver, "rawSnapshotObserver");
            observerReserveNanos_ = Objects.requireNonNull(observerReserveNanos, "observerReserveNanos");
            completionValidator_ = Objects.requireNonNull(completionValidator, "completionValidator");
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
        validateFinishArguments(waitTimeoutNanos, AWT_NATURAL_SHUTDOWN_GRACE_NANOS, AWT_QUIESCENCE_STABILITY_NANOS);
        Objects.requireNonNull(awtShutdownAction, "awtShutdownAction");
        PrintWriter diagnostics = new PrintWriter(System.err, true);
        long startNanos = System.nanoTime();
        WindowsAwtShutdownObserver.ProductionHandle observer = WindowsAwtShutdownObserver.registerForProduction(exitStatus, startNanos, waitTimeoutNanos, diagnostics);
        Hooks hooks = new Hooks(Thread::getAllStackTraces, System::nanoTime, TestProcessExitCoordinator::join, TestProcessExitCoordinator::waitForStabilization, observer::observeSnapshot, observer::observerReserveNanos, observer::validateCapture, awtShutdownAction, System::exit, diagnostics);
        finish(exitStatus, waitTimeoutNanos, AWT_NATURAL_SHUTDOWN_GRACE_NANOS, AWT_QUIESCENCE_STABILITY_NANOS, hooks, startNanos, !observer.registrationSucceeded());
    }

    static void finish(int exitStatus, long waitTimeoutNanos, Hooks hooks) {
        finish(exitStatus, waitTimeoutNanos, AWT_NATURAL_SHUTDOWN_GRACE_NANOS, AWT_QUIESCENCE_STABILITY_NANOS, hooks);
    }

    static void finish(int exitStatus, long waitTimeoutNanos, long maximumAwtGraceNanos, long awtQuiescenceStabilityNanos, Hooks hooks) {
        validateFinishArguments(waitTimeoutNanos, maximumAwtGraceNanos, awtQuiescenceStabilityNanos);
        Objects.requireNonNull(hooks, "hooks");
        finish(exitStatus, waitTimeoutNanos, maximumAwtGraceNanos, awtQuiescenceStabilityNanos, hooks, hooks.nanoTime_.getAsLong(), false);
    }

    private static void finish(int exitStatus, long waitTimeoutNanos, long maximumAwtGraceNanos, long awtQuiescenceStabilityNanos, Hooks hooks, long startNanos, boolean initiallyFailed) {
        Thread currentThread = Thread.currentThread();
        boolean interrupted = false;
        boolean coordinatorFailed = initiallyFailed;
        boolean awtObserved = false;
        long awtGraceStartNanos = 0;
        long awtGraceBudgetNanos = 0;
        boolean awtQuiescenceCandidate = false;
        long awtQuiescenceStartNanos = 0;
        boolean awtShutdownRequested = false;
        boolean awtShutdownPosted = false;
        long postAwtShutdownGraceStartNanos = 0;
        long postAwtShutdownGraceBudgetNanos = 0;
        while (true) {
            // Cleanup can hand work to newly-created threads, so every completed wait must be
            // followed by a fresh JVM-wide snapshot instead of relying on one startup snapshot.
            Map<Thread, StackTraceElement[]> rawSnapshot = hooks.threadSnapshot_.get();
            hooks.rawSnapshotObserver_.observe(rawSnapshot);
            PendingThreadSnapshot snapshot = snapshotPendingThreads(rawSnapshot, currentThread);
            Map<Thread, StackTraceElement[]> pendingThreads = snapshot.threads();
            long currentNanos = hooks.nanoTime_.getAsLong();
            long remainingNanos = remainingNanos(waitTimeoutNanos - hooks.observerReserveNanos_.getAsLong(), startNanos, currentNanos);
            if (snapshot.awtObserved() && !awtObserved) {
                awtObserved = true;
                awtGraceStartNanos = currentNanos;
                // Reserve at least half of the then-remaining deadline for the forced fallback and
                // subsequent cleanup, regardless of the configured maximum natural grace.
                awtGraceBudgetNanos = Math.min(maximumAwtGraceNanos, Math.max(0, remainingNanos) / 2);
            }
            if (pendingThreads.isEmpty()) {
                if (!awtObserved || awtQuiescenceStabilityNanos == 0) break;
                if (!awtQuiescenceCandidate) {
                    awtQuiescenceCandidate = true;
                    awtQuiescenceStartNanos = currentNanos;
                }
                long stabilizationRemainingNanos = remainingNanos(awtQuiescenceStabilityNanos, awtQuiescenceStartNanos, currentNanos);
                if (stabilizationRemainingNanos <= 0) break;
                long totalRemainingNanos = remainingNanos;
                if (totalRemainingNanos <= 0) {
                    printAwtQuiescenceTimeoutDiagnostics(hooks.diagnostics_, exitStatus, waitTimeoutNanos);
                    coordinatorFailed = true;
                    break;
                }
                try {
                    // Thread creation has no JVM-wide notification API. This deadline-capped wait
                    // covers handoffs that begin while the coordinator is still running. The
                    // passive Windows shutdown hook covers the separate application-hook race.
                    hooks.stabilizationWaiter_.waitFor(Math.min(stabilizationRemainingNanos, totalRemainingNanos));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                continue;
            }
            awtQuiescenceCandidate = false;

            if (remainingNanos <= 0) {
                printTimeoutDiagnostics(hooks.diagnostics_, exitStatus, waitTimeoutNanos, pendingThreads);
                coordinatorFailed = true;
                break;
            }

            Thread awtThread = findAwtInfrastructureThread(pendingThreads);
            Thread unvalidatedAwtShutdownThread = awtObserved ? findUnvalidatedAwtShutdownThread(pendingThreads) : null;
            if (awtThread == null && unvalidatedAwtShutdownThread != null) {
                try {
                    // A newly-started AWT-Shutdown thread may be snapshot-visible before its
                    // AWTAutoShutdown.run frame. Rescan it promptly, but never use its name alone
                    // to load AppContext or interrupt the thread.
                    hooks.threadWaiter_.waitFor(unvalidatedAwtShutdownThread, Math.min(remainingNanos, AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                continue;
            }
            if (!awtShutdownRequested && awtThread != null) {
                long graceRemainingNanos = remainingNanos(awtGraceBudgetNanos, awtGraceStartNanos, currentNanos);
                if (graceRemainingNanos > 0) {
                    try {
                        hooks.threadWaiter_.waitFor(awtThread, Math.min(graceRemainingNanos, remainingNanos));
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    continue;
                }
                // Do not resolve AppContext on an AWT-free path: loading internal AWT classes
                // solely for shutdown could itself create the infrastructure we are avoiding.
                awtShutdownRequested = true;
                try {
                    hooks.awtShutdownAction_.run();
                    awtShutdownPosted = true;
                    postAwtShutdownGraceStartNanos = hooks.nanoTime_.getAsLong();
                    long postAwtShutdownRemainingNanos = remainingNanos(waitTimeoutNanos - hooks.observerReserveNanos_.getAsLong(), startNanos, postAwtShutdownGraceStartNanos);
                    postAwtShutdownGraceBudgetNanos = Math.min(maximumAwtGraceNanos, Math.max(0, postAwtShutdownRemainingNanos) / 2);
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
            // busy. Give both the EDT and blocker another natural grace after AppContext posts its
            // shutdown events. Interrupting the blocker sooner can make AWTAutoShutdown clear its
            // blockerThread and skip AppContext shutdown while the toolkit is still busy, leaving
            // no guaranteed replacement blocker until a future busy-state transition.
            if (awtShutdownPosted && awtThread != null) {
                long postAwtShutdownGraceRemainingNanos = remainingNanos(postAwtShutdownGraceBudgetNanos, postAwtShutdownGraceStartNanos, currentNanos);
                if (postAwtShutdownGraceRemainingNanos > 0) {
                    try {
                        hooks.threadWaiter_.waitFor(awtThread, Math.min(postAwtShutdownGraceRemainingNanos, remainingNanos));
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    continue;
                }
            }

            // Only after the post-AppContext grace expires may a blocker be interrupted, and only
            // when both its exact name and a fresh AWTAutoShutdown.run stack validate its identity.
            // Later rescans also catch replacement blockers created during cleanup.
            if (awtShutdownPosted && !tryInterruptAwtShutdownThreads(pendingThreads, hooks.diagnostics_, exitStatus)) {
                coordinatorFailed = true;
                awtShutdownPosted = false;
            }

            if (unvalidatedAwtShutdownThread != null) {
                try {
                    hooks.threadWaiter_.waitFor(unvalidatedAwtShutdownThread, Math.min(remainingNanos, AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                continue;
            }

            Thread pendingThread = pendingThreads.keySet().iterator().next();
            try {
                hooks.threadWaiter_.waitFor(pendingThread, remainingNanos);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (!hooks.completionValidator_.validate()) coordinatorFailed = true;
        if (interrupted) currentThread.interrupt();
        if (exitStatus != 0 || coordinatorFailed) hooks.exitAction_.accept(exitStatus != 0 ? exitStatus : COORDINATOR_FAILURE_STATUS);
    }

    private static void validateFinishArguments(long waitTimeoutNanos, long maximumAwtGraceNanos, long awtQuiescenceStabilityNanos) {
        if (waitTimeoutNanos <= 0) throw new IllegalArgumentException("waitTimeoutNanos must be positive");
        if (maximumAwtGraceNanos < 0) throw new IllegalArgumentException("maximumAwtGraceNanos must not be negative");
        if (awtQuiescenceStabilityNanos < 0) throw new IllegalArgumentException("awtQuiescenceStabilityNanos must not be negative");
    }

    private static PendingThreadSnapshot snapshotPendingThreads(Map<Thread, StackTraceElement[]> snapshot, Thread currentThread) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<Thread, StackTraceElement[]> pendingThreads = new LinkedHashMap<Thread, StackTraceElement[]>();
        boolean awtObserved = false;
        for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue() == null ? new StackTraceElement[0] : entry.getValue();
            // The thread can terminate between getAllStackTraces() and the liveness checks below.
            // Retain the captured AWT identity so an apparently empty filtered snapshot still gets
            // stable-empty verification and cannot miss a replacement AWTAutoShutdown blocker.
            if (isAwtInfrastructureThread(thread, stack)) awtObserved = true;
            if (thread == null || thread == currentThread || thread.isDaemon() || thread.getState() == Thread.State.TERMINATED || !thread.isAlive()) continue;
            pendingThreads.put(thread, stack);
        }
        return new PendingThreadSnapshot(pendingThreads, awtObserved);
    }

    private static Thread findAwtInfrastructureThread(Map<Thread, StackTraceElement[]> pendingThreads) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : pendingThreads.entrySet()) {
            Thread thread = entry.getKey();
            if (isAwtInfrastructureThread(thread, entry.getValue())) return thread;
        }
        return null;
    }

    private static boolean isAwtInfrastructureThread(Thread thread, StackTraceElement[] stack) {
        if (thread == null) return false;
        if (isAwtShutdownThread(thread, stack) || thread.getName().startsWith(AWT_EVENT_QUEUE_THREAD_PREFIX)) return true;
        for (StackTraceElement frame : stack) {
            if (AWT_EVENT_DISPATCH_THREAD_CLASS.equals(frame.getClassName())) return true;
        }
        return false;
    }

    private static Thread findUnvalidatedAwtShutdownThread(Map<Thread, StackTraceElement[]> pendingThreads) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : pendingThreads.entrySet()) {
            Thread thread = entry.getKey();
            if (AWT_SHUTDOWN_THREAD_NAME.equals(thread.getName()) && !isAwtShutdownThread(thread, entry.getValue())) return thread;
        }
        return null;
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
            if (cause instanceof Error) throw(Error) cause;
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
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

    private static void waitForStabilization(long timeoutNanos) throws InterruptedException {
        long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
        int additionalNanos = (int) (timeoutNanos - TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        Thread.sleep(timeoutMillis, additionalNanos);
    }

    private static void printTimeoutDiagnostics(PrintWriter diagnostics, int exitStatus, long waitTimeoutNanos, Map<Thread, StackTraceElement[]> pendingThreads) {
        long waitTimeoutMillis = TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos);
        diagnostics.println("Timed out after " + waitTimeoutMillis + " ms waiting for non-daemon Java threads before test process completion with status " + exitStatus + ":");
        printThreadDiagnostics(diagnostics, pendingThreads);
    }

    private static void printAwtQuiescenceTimeoutDiagnostics(PrintWriter diagnostics, int exitStatus, long waitTimeoutNanos) {
        long waitTimeoutMillis = TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos);
        diagnostics.println("Timed out after " + waitTimeoutMillis + " ms validating stable AWT quiescence before test process completion with status " + exitStatus);
        diagnostics.flush();
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
