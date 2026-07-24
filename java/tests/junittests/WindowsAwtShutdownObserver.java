// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Observes Windows AWT teardown that can begin after the last ordinary thread snapshot. */
final class WindowsAwtShutdownObserver {
    static final long AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    static final long AWT_QUIESCENCE_STABILITY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    static final long OBSERVER_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final String DIAGNOSTIC_PREFIX = "JCEF Windows AWT shutdown observer failure: ";
    private static final long MINIMUM_OBSERVER_RESERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(1_250);
    private static final String OBSERVER_THREAD_NAME = "JCEF-Windows-AWT-Shutdown-Observer";
    private static final String AWT_WINDOWS_THREAD_NAME = "AWT-Windows";
    private static final String TOOLKIT_SHUTDOWN_THREAD_NAME = "ToolkitShutdown";
    private static final String AWT_SHUTDOWN_THREAD_NAME = "AWT-Shutdown";
    private static final String AWT_EVENT_QUEUE_THREAD_PREFIX = "AWT-EventQueue-";
    private static final String WINDOWS_TOOLKIT_CLASS = "sun.awt.windows.WToolkit";
    private static final String AWT_AUTO_SHUTDOWN_CLASS = "sun.awt.AWTAutoShutdown";
    private static final String AWT_EVENT_DISPATCH_THREAD_CLASS = "java.awt.EventDispatchThread";
    private static final RegistrationController PRODUCTION_REGISTRATION = new RegistrationController();

    @FunctionalInterface
    interface ThreadWaiter {
        void waitFor(Thread thread, long timeoutNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface StabilizationWaiter {
        void waitFor(long timeoutNanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface HookRegistrar {
        void register(Thread hook);
    }

    @FunctionalInterface
    interface ObserverFactory {
        Thread create();
    }

    static final class ObserverHooks {
        final Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot_;
        final LongSupplier nanoTime_;
        final ThreadWaiter threadWaiter_;
        final StabilizationWaiter stabilizationWaiter_;
        final PrintWriter diagnostics_;

        ObserverHooks(Supplier<Map<Thread, StackTraceElement[]>> threadSnapshot, LongSupplier nanoTime, ThreadWaiter threadWaiter, StabilizationWaiter stabilizationWaiter, PrintWriter diagnostics) {
            threadSnapshot_ = Objects.requireNonNull(threadSnapshot, "threadSnapshot");
            nanoTime_ = Objects.requireNonNull(nanoTime, "nanoTime");
            threadWaiter_ = Objects.requireNonNull(threadWaiter, "threadWaiter");
            stabilizationWaiter_ = Objects.requireNonNull(stabilizationWaiter, "stabilizationWaiter");
            diagnostics_ = Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    static final class RegistrationController {
        private boolean windows_;
        private boolean registered_;
        private boolean awtObserved_;
        private boolean captureFailureReported_;
        private int registrationCount_;
        private int exitStatus_;
        private long startNanos_;
        private long waitTimeoutNanos_;
        private long observerReserveNanos_;
        private Thread awtWindowsThread_;
        private LongSupplier nanoTime_;
        private PrintWriter diagnostics_;

        synchronized boolean registerAtEntry(boolean windows, int exitStatus, long startNanos, long waitTimeoutNanos, LongSupplier nanoTime, ObserverFactory observerFactory, HookRegistrar hookRegistrar, PrintWriter diagnostics) {
            if (!windows) return true;
            if (registered_) return true;
            Objects.requireNonNull(nanoTime, "nanoTime");
            Objects.requireNonNull(observerFactory, "observerFactory");
            Objects.requireNonNull(hookRegistrar, "hookRegistrar");
            Objects.requireNonNull(diagnostics, "diagnostics");
            if (waitTimeoutNanos <= 0) throw new IllegalArgumentException("waitTimeoutNanos must be positive");

            // Publish all observer state before addShutdownHook. Shutdown may begin concurrently as
            // soon as the hook is registered, and hook ordering is intentionally unspecified.
            windows_ = true;
            exitStatus_ = exitStatus;
            startNanos_ = startNanos;
            waitTimeoutNanos_ = waitTimeoutNanos;
            nanoTime_ = nanoTime;
            diagnostics_ = diagnostics;
            try {
                Thread observer = Objects.requireNonNull(observerFactory.create(), "observer");
                hookRegistrar.register(observer);
            } catch (RuntimeException failure) {
                printRegistrationFailureDiagnostics(diagnostics, exitStatus, failure);
                return false;
            }
            registered_ = true;
            registrationCount_++;
            return true;
        }

        synchronized void observeSnapshot(Map<Thread, StackTraceElement[]> snapshot) {
            if (!windows_ || !registered_) return;
            Objects.requireNonNull(snapshot, "snapshot");
            boolean awtObservedInSnapshot = false;
            Thread validatedToolkitThread = null;
            for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
                Thread thread = entry.getKey();
                if (thread == null) continue;
                StackTraceElement[] stack = entry.getValue() == null ? new StackTraceElement[0] : entry.getValue();
                String threadName = thread.getName();
                // getAllStackTraces captured these identities while they were live. Inspect that
                // raw evidence before consulting mutable liveness so teardown between the snapshot
                // and this callback cannot erase the toolkit identity needed by the shutdown hook.
                if (AWT_WINDOWS_THREAD_NAME.equals(threadName) && isWindowsToolkitThread(stack)) {
                    awtObservedInSnapshot = true;
                    validatedToolkitThread = thread;
                }
                if ((AWT_SHUTDOWN_THREAD_NAME.equals(threadName) && isAwtShutdownThread(stack)) || threadName.startsWith(AWT_EVENT_QUEUE_THREAD_PREFIX) || containsClass(stack, AWT_EVENT_DISPATCH_THREAD_CLASS)) awtObservedInSnapshot = true;
            }
            if (validatedToolkitThread != null) awtWindowsThread_ = validatedToolkitThread;
            if (awtObservedInSnapshot && !awtObserved_) {
                awtObserved_ = true;
                long remainingNanos = Math.max(0, remainingNanos(waitTimeoutNanos_, startNanos_, nanoTime_.getAsLong()));
                long desiredReserveNanos = Math.max(MINIMUM_OBSERVER_RESERVE_NANOS, remainingNanos / 2);
                observerReserveNanos_ = Math.min(remainingNanos, Math.min(OBSERVER_WAIT_NANOS, desiredReserveNanos));
            }
        }

        synchronized long observerReserveNanos() {
            return observerReserveNanos_;
        }

        synchronized boolean validateCapture() {
            if (!windows_ || !registered_ || !awtObserved_ || awtWindowsThread_ != null) return true;
            if (!captureFailureReported_) {
                captureFailureReported_ = true;
                printCaptureFailureDiagnostics(diagnostics_, exitStatus_);
            }
            return false;
        }

        void observeAtShutdown(ObserverHooks hooks) {
            Thread awtWindowsThread;
            boolean awtObserved;
            long observerStartNanos;
            long observerBudgetNanos;
            long totalTimeoutNanos;
            try {
                synchronized (this) {
                    if (!windows_ || !registered_) return;
                    awtObserved = awtObserved_;
                    awtWindowsThread = awtWindowsThread_;
                    observerStartNanos = hooks.nanoTime_.getAsLong();
                    totalTimeoutNanos = waitTimeoutNanos_;
                    long remainingNanos = Math.max(0, remainingNanos(totalTimeoutNanos, startNanos_, observerStartNanos));
                    observerBudgetNanos = Math.min(OBSERVER_WAIT_NANOS, remainingNanos);
                }
            } catch (RuntimeException failure) {
                printObserverFailureDiagnostics(hooks.diagnostics_, failure);
                return;
            }
            if (!awtObserved) return;
            if (observerBudgetNanos <= 0) {
                printObserverBudgetTimeoutDiagnostics(hooks.diagnostics_, totalTimeoutNanos);
                return;
            }
            observe(awtWindowsThread, observerStartNanos, observerBudgetNanos, AWT_QUIESCENCE_STABILITY_NANOS, hooks);
        }

        synchronized int registrationCount() {
            return registrationCount_;
        }

        synchronized Thread capturedToolkitThread() {
            return awtWindowsThread_;
        }

        synchronized boolean awtObserved() {
            return awtObserved_;
        }
    }

    private record ShutdownCandidate(Thread thread, boolean confirmsTeardownActivity, boolean shortResnapshot) {}

    private WindowsAwtShutdownObserver() {}

    static ProductionHandle registerForProduction(int exitStatus, long startNanos, long waitTimeoutNanos, PrintWriter diagnostics) {
        boolean windows;
        try {
            windows = System.getProperty("os.name", "").startsWith("Windows");
        } catch (RuntimeException failure) {
            printRegistrationFailureDiagnostics(diagnostics, exitStatus, failure);
            return new ProductionHandle(PRODUCTION_REGISTRATION, false, false);
        }
        ObserverHooks hooks = new ObserverHooks(Thread::getAllStackTraces, System::nanoTime, WindowsAwtShutdownObserver::join, WindowsAwtShutdownObserver::waitForStabilization, diagnostics);
        ObserverFactory observerFactory = () -> new Thread(() -> PRODUCTION_REGISTRATION.observeAtShutdown(hooks), OBSERVER_THREAD_NAME);
        boolean registered = PRODUCTION_REGISTRATION.registerAtEntry(windows, exitStatus, startNanos, waitTimeoutNanos, System::nanoTime, observerFactory, hook -> Runtime.getRuntime().addShutdownHook(hook), diagnostics);
        return new ProductionHandle(PRODUCTION_REGISTRATION, windows, registered);
    }

    static boolean containsFailureDiagnostics(String output) {
        return output.contains(DIAGNOSTIC_PREFIX);
    }

    static final class ProductionHandle {
        private final RegistrationController controller_;
        private final boolean windows_;
        private final boolean registrationSucceeded_;

        private ProductionHandle(RegistrationController controller, boolean windows, boolean registrationSucceeded) {
            controller_ = controller;
            windows_ = windows;
            registrationSucceeded_ = registrationSucceeded;
        }

        void observeSnapshot(Map<Thread, StackTraceElement[]> snapshot) {
            if (windows_ && registrationSucceeded_) controller_.observeSnapshot(snapshot);
        }

        long observerReserveNanos() {
            return windows_ && registrationSucceeded_ ? controller_.observerReserveNanos() : 0;
        }

        boolean validateCapture() {
            return !windows_ || registrationSucceeded_ && controller_.validateCapture();
        }

        boolean registrationSucceeded() {
            return registrationSucceeded_;
        }
    }

    static void observe(Thread awtWindowsThread, long startNanos, long waitTimeoutNanos, long awtQuiescenceStabilityNanos, ObserverHooks hooks) {
        if (waitTimeoutNanos <= 0) throw new IllegalArgumentException("waitTimeoutNanos must be positive");
        if (awtQuiescenceStabilityNanos <= 0) throw new IllegalArgumentException("awtQuiescenceStabilityNanos must be positive");
        Objects.requireNonNull(hooks, "hooks");

        Thread currentThread = Thread.currentThread();
        boolean interrupted = false;
        try {
            boolean teardownActivityObserved = awtWindowsThread != null;
            if (awtWindowsThread != null) {
                while (awtWindowsThread.isAlive()) {
                    long remainingNanos = remainingNanos(waitTimeoutNanos, startNanos, hooks.nanoTime_.getAsLong());
                    if (remainingNanos <= 0) {
                        printToolkitTimeoutDiagnostics(hooks.diagnostics_, waitTimeoutNanos, awtWindowsThread);
                        return;
                    }
                    try {
                        // Application hooks start concurrently. Waiting for the captured toolkit
                        // identity is safe even when this observer starts before ToolkitShutdown.
                        hooks.threadWaiter_.waitFor(awtWindowsThread, remainingNanos);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }

            boolean awtQuiescenceCandidate = false;
            long awtQuiescenceStartNanos = 0;
            while (true) {
                Map<Thread, StackTraceElement[]> snapshot = Objects.requireNonNull(hooks.threadSnapshot_.get(), "threadSnapshot");
                long currentNanos = hooks.nanoTime_.getAsLong();
                long remainingNanos = remainingNanos(waitTimeoutNanos, startNanos, currentNanos);
                ShutdownCandidate candidate = findShutdownCandidate(snapshot, currentThread);
                if (candidate != null) {
                    if (candidate.confirmsTeardownActivity()) teardownActivityObserved = true;
                    awtQuiescenceCandidate = false;
                    if (remainingNanos <= 0) {
                        printAwtShutdownTimeoutDiagnostics(hooks.diagnostics_, waitTimeoutNanos, snapshot);
                        return;
                    }
                    long candidateWaitNanos = candidate.shortResnapshot() ? Math.min(remainingNanos, AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS) : remainingNanos;
                    try {
                        hooks.threadWaiter_.waitFor(candidate.thread(), candidateWaitNanos);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    continue;
                }

                if (!teardownActivityObserved) {
                    if (remainingNanos <= 0) {
                        printMissingTeardownActivityTimeoutDiagnostics(hooks.diagnostics_, waitTimeoutNanos);
                        return;
                    }
                    try {
                        // A capture failure is already reported before System.exit. Keep this
                        // passive hook alive until it actually observes ToolkitShutdown or a
                        // validated AWTAutoShutdown blocker, rather than treating an early empty
                        // snapshot as proof that the application-hook race has closed.
                        hooks.stabilizationWaiter_.waitFor(Math.min(remainingNanos, AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS));
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    continue;
                }

                if (!awtQuiescenceCandidate) {
                    awtQuiescenceCandidate = true;
                    awtQuiescenceStartNanos = currentNanos;
                }
                long stabilizationRemainingNanos = remainingNanos(awtQuiescenceStabilityNanos, awtQuiescenceStartNanos, currentNanos);
                if (stabilizationRemainingNanos <= 0) return;
                if (remainingNanos <= 0) {
                    printStableQuiescenceTimeoutDiagnostics(hooks.diagnostics_, waitTimeoutNanos);
                    return;
                }
                try {
                    hooks.stabilizationWaiter_.waitFor(Math.min(stabilizationRemainingNanos, remainingNanos));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } catch (RuntimeException failure) {
            printObserverFailureDiagnostics(hooks.diagnostics_, failure);
        } finally {
            if (interrupted) currentThread.interrupt();
        }
    }

    private static ShutdownCandidate findShutdownCandidate(Map<Thread, StackTraceElement[]> snapshot, Thread currentThread) {
        ShutdownCandidate toolkitShutdownCandidate = null;
        ShutdownCandidate unvalidatedAwtCandidate = null;
        for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == null || thread == currentThread || !thread.isAlive() || thread.getState() == Thread.State.TERMINATED) continue;
            String threadName = thread.getName();
            if (TOOLKIT_SHUTDOWN_THREAD_NAME.equals(threadName) && toolkitShutdownCandidate == null) toolkitShutdownCandidate = new ShutdownCandidate(thread, true, false);
            if (!AWT_SHUTDOWN_THREAD_NAME.equals(threadName)) continue;
            StackTraceElement[] stack = entry.getValue() == null ? new StackTraceElement[0] : entry.getValue();
            if (isAwtShutdownThread(stack)) return new ShutdownCandidate(thread, true, false);
            if (unvalidatedAwtCandidate == null) unvalidatedAwtCandidate = new ShutdownCandidate(thread, false, true);
        }
        if (toolkitShutdownCandidate != null) return toolkitShutdownCandidate;
        return unvalidatedAwtCandidate;
    }

    private static boolean isWindowsToolkitThread(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (!WINDOWS_TOOLKIT_CLASS.equals(frame.getClassName())) continue;
            String methodName = frame.getMethodName();
            if ("run".equals(methodName) || "eventLoop".equals(methodName)) return true;
        }
        return false;
    }

    private static boolean isAwtShutdownThread(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (AWT_AUTO_SHUTDOWN_CLASS.equals(frame.getClassName()) && "run".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    private static boolean containsClass(StackTraceElement[] stack, String className) {
        for (StackTraceElement frame : stack) {
            if (className.equals(frame.getClassName())) return true;
        }
        return false;
    }

    private static long remainingNanos(long waitTimeoutNanos, long startNanos, long currentNanos) {
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

    private static void printRegistrationFailureDiagnostics(PrintWriter diagnostics, int exitStatus, RuntimeException failure) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Failed to register the passive observer before test process completion with status " + exitStatus + ":");
        failure.printStackTrace(diagnostics);
        diagnostics.flush();
    }

    private static void printCaptureFailureDiagnostics(PrintWriter diagnostics, int exitStatus) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Observed AWT activity but failed to capture a live AWT-Windows thread with a WToolkit.run/eventLoop stack before test process completion with status " + exitStatus);
        diagnostics.flush();
    }

    private static void printObserverBudgetTimeoutDiagnostics(PrintWriter diagnostics, long waitTimeoutNanos) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "No time remained in the " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos) + " ms process-completion deadline");
        diagnostics.flush();
    }

    private static void printToolkitTimeoutDiagnostics(PrintWriter diagnostics, long waitTimeoutNanos, Thread awtWindowsThread) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Timed out after " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos) + " ms waiting for captured Windows AWT toolkit shutdown:");
        diagnostics.println("  Thread \"" + awtWindowsThread.getName() + "\" id=" + awtWindowsThread.getId() + " state=" + awtWindowsThread.getState());
        diagnostics.flush();
    }

    private static void printAwtShutdownTimeoutDiagnostics(PrintWriter diagnostics, long waitTimeoutNanos, Map<Thread, StackTraceElement[]> snapshot) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Timed out after " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos) + " ms waiting for post-toolkit AWT shutdown blockers:");
        printShutdownCandidates(diagnostics, snapshot);
    }

    private static void printStableQuiescenceTimeoutDiagnostics(PrintWriter diagnostics, long waitTimeoutNanos) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Timed out after " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos) + " ms validating stable post-toolkit AWT quiescence");
        diagnostics.flush();
    }

    private static void printMissingTeardownActivityTimeoutDiagnostics(PrintWriter diagnostics, long waitTimeoutNanos) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Timed out after " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos) + " ms waiting to observe ToolkitShutdown or a validated AWT-Shutdown thread after toolkit capture failed");
        diagnostics.flush();
    }

    private static void printObserverFailureDiagnostics(PrintWriter diagnostics, RuntimeException failure) {
        diagnostics.println(DIAGNOSTIC_PREFIX + "Unexpected exception while waiting for natural quiescence:");
        failure.printStackTrace(diagnostics);
        diagnostics.flush();
    }

    private static void printShutdownCandidates(PrintWriter diagnostics, Map<Thread, StackTraceElement[]> snapshot) {
        List<Map.Entry<Thread, StackTraceElement[]>> entries = new ArrayList<Map.Entry<Thread, StackTraceElement[]>>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
            Thread thread = entry.getKey();
            if (thread != null && (AWT_SHUTDOWN_THREAD_NAME.equals(thread.getName()) || TOOLKIT_SHUTDOWN_THREAD_NAME.equals(thread.getName()))) entries.add(entry);
        }
        entries.sort(Comparator.comparingLong(entry -> entry.getKey().getId()));
        for (Map.Entry<Thread, StackTraceElement[]> entry : entries) {
            Thread thread = entry.getKey();
            diagnostics.println("  Thread \"" + thread.getName() + "\" id=" + thread.getId() + " state=" + thread.getState());
            StackTraceElement[] stack = entry.getValue() == null ? new StackTraceElement[0] : entry.getValue();
            if (stack.length == 0) diagnostics.println("    <no Java stack available>");
            for (StackTraceElement frame : stack) diagnostics.println("    at " + frame);
        }
        diagnostics.flush();
    }
}
