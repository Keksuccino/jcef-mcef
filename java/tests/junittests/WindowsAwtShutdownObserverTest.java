// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class WindowsAwtShutdownObserverTest {
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long THREAD_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final StackTraceElement TOOLKIT_FRAME = new StackTraceElement("sun.awt.windows.WToolkit", "eventLoop", "WToolkit.java", 354);
    private static final StackTraceElement AWT_SHUTDOWN_FRAME = new StackTraceElement("sun.awt.AWTAutoShutdown", "run", "AWTAutoShutdown.java", 310);

    @Test
    void passiveRegistrationIsWindowsGatedAndTransientSnapshotsDoNotLoseCoverage() throws Exception {
        WindowsAwtShutdownObserver.RegistrationController nonWindowsController = new WindowsAwtShutdownObserver.RegistrationController();
        AtomicInteger nonWindowsFactories = new AtomicInteger();
        WindowsAwtShutdownObserver.ObserverFactory nonWindowsFactory = () -> {
            nonWindowsFactories.incrementAndGet();
            return new Thread();
        };
        assertTrue(nonWindowsController.registerAtEntry(false, 0, 1_000, TimeUnit.SECONDS.toNanos(30), () -> 1_000, nonWindowsFactory, hook -> {}, new PrintWriter(new ByteArrayOutputStream())));
        assertEquals(0, nonWindowsFactories.get());
        assertEquals(0, nonWindowsController.registrationCount());

        WindowsAwtShutdownObserver.RegistrationController controller = new WindowsAwtShutdownObserver.RegistrationController();
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Thread> registeredHooks = new ArrayList<Thread>();
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        PrintWriter diagnostics = new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8);
        assertTrue(controller.registerAtEntry(true, 0, nanoTime.get(), TimeUnit.SECONDS.toNanos(30), nanoTime::get, Thread::new, registeredHooks::add, diagnostics));
        assertEquals(1, registeredHooks.size());
        assertFalse(controller.awtObserved());
        assertTrue(controller.validateCapture());
        assertEquals(0, controller.observerReserveNanos());

        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        BlockedThread nameOnlyShutdown = startBlockedThread("AWT-Shutdown");
        try {
            controller.observeSnapshot(snapshotOf(toolkit));
            controller.observeSnapshot(snapshotOf(nameOnlyShutdown));
            assertFalse(controller.awtObserved());
            assertNull(controller.capturedToolkitThread());
            assertEquals(0, controller.observerReserveNanos());
            assertTrue(controller.validateCapture());
            controller.observeSnapshot(snapshotOf(toolkit, TOOLKIT_FRAME));
            assertTrue(controller.awtObserved());
            assertEquals(toolkit.thread(), controller.capturedToolkitThread());
            long reservedNanos = controller.observerReserveNanos();
            assertEquals(WindowsAwtShutdownObserver.OBSERVER_WAIT_NANOS, reservedNanos);
            controller.observeSnapshot(Map.of());
            assertTrue(controller.awtObserved());
            assertEquals(toolkit.thread(), controller.capturedToolkitThread());
            assertTrue(controller.validateCapture());
            assertEquals(reservedNanos, controller.observerReserveNanos());
            assertEquals("", diagnosticBytes.toString(StandardCharsets.UTF_8));
        } finally {
            releaseAndJoin(toolkit);
            releaseAndJoin(nameOnlyShutdown);
        }
    }

    @Test
    void observedAwtWithoutValidatedToolkitFailsCaptureAtCompletion() throws Exception {
        WindowsAwtShutdownObserver.RegistrationController controller = new WindowsAwtShutdownObserver.RegistrationController();
        AtomicLong nanoTime = new AtomicLong(1_000);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        PrintWriter diagnostics = new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8);
        assertTrue(controller.registerAtEntry(true, 0, nanoTime.get(), TimeUnit.SECONDS.toNanos(30), nanoTime::get, Thread::new, hook -> {}, diagnostics));
        BlockedThread blocker = startBlockedThread("AWT-Shutdown");
        try {
            controller.observeSnapshot(snapshotOf(blocker, AWT_SHUTDOWN_FRAME));
            controller.observeSnapshot(Map.of());
            assertFalse(controller.validateCapture());
            assertFalse(controller.validateCapture());
            String diagnosticText = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertTrue(diagnosticText.contains("failed to capture a live AWT-Windows"), diagnosticText);
            assertEquals(1, countOccurrences(diagnosticText, "failed to capture a live AWT-Windows"));
        } finally {
            releaseAndJoin(blocker);
        }
    }

    @Test
    void rawValidatedToolkitIdentitySurvivesTerminationBeforeObservation() throws Exception {
        WindowsAwtShutdownObserver.RegistrationController controller = new WindowsAwtShutdownObserver.RegistrationController();
        AtomicLong nanoTime = new AtomicLong(1_000);
        assertTrue(controller.registerAtEntry(true, 0, nanoTime.get(), TimeUnit.SECONDS.toNanos(2), nanoTime::get, Thread::new, hook -> {}, new PrintWriter(new ByteArrayOutputStream())));
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);

        controller.observeSnapshot(snapshotOf(toolkit, TOOLKIT_FRAME));

        assertTrue(controller.awtObserved());
        assertEquals(toolkit.thread(), controller.capturedToolkitThread());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_250), controller.observerReserveNanos());
        assertTrue(controller.validateCapture());
    }

    @Test
    void passiveHookRegistersOnceWhileValidatedToolkitIdentityCanBeUpdated() throws Exception {
        WindowsAwtShutdownObserver.RegistrationController controller = new WindowsAwtShutdownObserver.RegistrationController();
        BlockedThread first = startBlockedThread("AWT-Windows");
        BlockedThread second = startBlockedThread("AWT-Windows");
        AtomicInteger observerFactories = new AtomicInteger();
        List<Thread> registeredHooks = new ArrayList<Thread>();
        PrintWriter diagnostics = new PrintWriter(new ByteArrayOutputStream());
        WindowsAwtShutdownObserver.ObserverFactory observerFactory = () -> {
            observerFactories.incrementAndGet();
            return new Thread();
        };

        try {
            assertTrue(controller.registerAtEntry(true, 37, 1_000, TimeUnit.SECONDS.toNanos(30), () -> 1_000, observerFactory, registeredHooks::add, diagnostics));
            assertTrue(controller.registerAtEntry(true, 37, 2_000, TimeUnit.SECONDS.toNanos(30), () -> 2_000, observerFactory, registeredHooks::add, diagnostics));
            controller.observeSnapshot(snapshotOf(first, TOOLKIT_FRAME));
            assertEquals(first.thread(), controller.capturedToolkitThread());
            controller.observeSnapshot(snapshotOf(second, TOOLKIT_FRAME));
            assertEquals(second.thread(), controller.capturedToolkitThread());
            assertEquals(1, observerFactories.get());
            assertEquals(1, registeredHooks.size());
            assertEquals(1, controller.registrationCount());
        } finally {
            releaseAndJoin(first);
            releaseAndJoin(second);
        }
    }

    @Test
    void hookRegistrationFailureFailsClosedWithDiagnostics() {
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        PrintWriter diagnostics = new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8);
        WindowsAwtShutdownObserver.RegistrationController controller = new WindowsAwtShutdownObserver.RegistrationController();
        assertFalse(controller.registerAtEntry(true, 29, 1_000, TimeUnit.SECONDS.toNanos(30), () -> 1_000, Thread::new, hook -> { throw new SecurityException("injected hook denial"); }, diagnostics));
        assertEquals(0, controller.registrationCount());
        String diagnosticText = diagnosticBytes.toString(StandardCharsets.UTF_8);
        assertTrue(WindowsAwtShutdownObserver.containsFailureDiagnostics(diagnosticText), diagnosticText);
        assertTrue(diagnosticText.contains("Failed to register the passive observer"), diagnosticText);
        assertTrue(diagnosticText.contains("injected hook denial"), diagnosticText);
        assertTrue(diagnosticText.contains("status 29"), diagnosticText);
    }

    @Test
    void observerCanStartBeforeToolkitShutdownAndRemainWrapSafe() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        BlockedThread blocker = startBlockedThread("AWT-Shutdown");
        AtomicReference<BlockedThread> activeBlocker = new AtomicReference<BlockedThread>();
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 10 * MILLISECOND_NANOS);
        List<Thread> waitedThreads = new ArrayList<Thread>();
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        AtomicReference<Throwable> toolkitShutdownFailure = new AtomicReference<Throwable>();
        WindowsAwtShutdownObserver.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            waitedThreads.add(thread);
            waitBudgets.add(timeoutNanos);
            if (thread == toolkit.thread()) {
                Thread toolkitShutdown = new Thread(() -> { try { activeBlocker.set(blocker); toolkit.release().countDown(); } catch (RuntimeException failure) { toolkitShutdownFailure.set(failure); } }, "ToolkitShutdown");
                toolkitShutdown.start();
                toolkitShutdown.join(THREAD_TIMEOUT_MILLIS);
                assertFalse(toolkitShutdown.isAlive());
                toolkit.thread().join(THREAD_TIMEOUT_MILLIS);
                assertFalse(toolkit.thread().isAlive());
                nanoTime.addAndGet(20 * MILLISECOND_NANOS);
                return;
            }
            assertEquals(blocker.thread(), thread);
            assertFalse(thread.isInterrupted());
            releaseAndJoin(blocker);
            activeBlocker.set(null);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        WindowsAwtShutdownObserver.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(() -> snapshotOf(activeBlocker.get(), AWT_SHUTDOWN_FRAME), nanoTime::get, waiter, stabilizationWaiter, new PrintWriter(new ByteArrayOutputStream()));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), 100 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertNull(toolkitShutdownFailure.get());
            assertEquals(List.of(toolkit.thread(), blocker.thread()), waitedThreads);
            assertEquals(List.of(100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(List.of(20 * MILLISECOND_NANOS), stabilizationBudgets);
            assertFalse(blocker.thread().isInterrupted());
        } finally {
            releaseAndJoin(toolkit);
            releaseAndJoin(blocker);
        }
    }

    @Test
    void alreadyTerminatedToolkitStillRequiresDefaultStableEmptyInterval() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        WindowsAwtShutdownObserver.ThreadWaiter unexpectedWaiter = (thread, timeoutNanos) -> {
            throw new AssertionError("No thread should be waited");
        };
        WindowsAwtShutdownObserver.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(Map::of, nanoTime::get, unexpectedWaiter, stabilizationWaiter, new PrintWriter(new ByteArrayOutputStream()));

        WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), TimeUnit.SECONDS.toNanos(1), WindowsAwtShutdownObserver.AWT_QUIESCENCE_STABILITY_NANOS, hooks);

        assertEquals(List.of(WindowsAwtShutdownObserver.AWT_QUIESCENCE_STABILITY_NANOS), stabilizationBudgets);
    }

    @Test
    void missingToolkitCaptureRequiresObservedShutdownActivityUntilTheDeadline() {
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Long> pollingBudgets = new ArrayList<Long>();
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        WindowsAwtShutdownObserver.ThreadWaiter unexpectedWaiter = (thread, timeoutNanos) -> {
            throw new AssertionError("No thread should be waited");
        };
        WindowsAwtShutdownObserver.StabilizationWaiter pollingWaiter = timeoutNanos -> {
            pollingBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(Map::of, nanoTime::get, unexpectedWaiter, pollingWaiter, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        WindowsAwtShutdownObserver.observe(null, nanoTime.get(), 25 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);

        assertEquals(List.of(10 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, 5 * MILLISECOND_NANOS), pollingBudgets);
        String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("waiting to observe ToolkitShutdown"), diagnostics);
    }

    @Test
    void missingToolkitCaptureCanUseObservedToolkitShutdownAsPassiveFallback() throws Exception {
        BlockedThread toolkitShutdown = startBlockedThread("ToolkitShutdown");
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> active.get() ? snapshotOf(toolkitShutdown) : Map.of();
        WindowsAwtShutdownObserver.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(toolkitShutdown.thread(), thread);
            assertFalse(thread.isInterrupted());
            releaseAndJoin(toolkitShutdown);
            active.set(false);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        WindowsAwtShutdownObserver.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(snapshots, nanoTime::get, waiter, stabilizationWaiter, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            WindowsAwtShutdownObserver.observe(null, nanoTime.get(), 100 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(20 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals("", diagnosticBytes.toString(StandardCharsets.UTF_8));
            assertFalse(toolkitShutdown.thread().isInterrupted());
        } finally {
            releaseAndJoin(toolkitShutdown);
        }
    }

    @Test
    void lateAndReplacementBlockersResetStabilityWithoutAwtActionsOrInterruption() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>();
        AtomicReference<BlockedThread> first = new AtomicReference<BlockedThread>();
        AtomicReference<BlockedThread> replacement = new AtomicReference<BlockedThread>();
        AtomicBoolean firstValidated = new AtomicBoolean();
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        WindowsAwtShutdownObserver.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            waitBudgets.add(timeoutNanos);
            assertFalse(thread.isInterrupted());
            if (thread == first.get().thread() && !firstValidated.get()) {
                assertEquals(WindowsAwtShutdownObserver.AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS, timeoutNanos);
                firstValidated.set(true);
                nanoTime.addAndGet(MILLISECOND_NANOS);
                return;
            }
            if (thread == first.get().thread()) {
                releaseAndJoin(first.get());
                BlockedThread replacementBlocker = startBlockedThread("AWT-Shutdown");
                replacement.set(replacementBlocker);
                active.set(replacementBlocker);
                nanoTime.addAndGet(10 * MILLISECOND_NANOS);
                return;
            }
            assertEquals(replacement.get().thread(), thread);
            releaseAndJoin(replacement.get());
            active.set(null);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        WindowsAwtShutdownObserver.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            if (first.get() == null) {
                BlockedThread firstBlocker = startBlockedThread("AWT-Shutdown");
                first.set(firstBlocker);
                active.set(firstBlocker);
                nanoTime.addAndGet(25 * MILLISECOND_NANOS);
                return;
            }
            nanoTime.addAndGet(timeoutNanos);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(() -> active.get() == first.get() && !firstValidated.get() ? snapshotOf(active.get()) : snapshotOf(active.get(), AWT_SHUTDOWN_FRAME), nanoTime::get, waiter, stabilizationWaiter, new PrintWriter(new ByteArrayOutputStream()));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), TimeUnit.SECONDS.toNanos(1), WindowsAwtShutdownObserver.AWT_QUIESCENCE_STABILITY_NANOS, hooks);
            assertEquals(3, waitBudgets.size());
            assertEquals(WindowsAwtShutdownObserver.AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS, waitBudgets.get(0));
            assertEquals(List.of(WindowsAwtShutdownObserver.AWT_QUIESCENCE_STABILITY_NANOS, WindowsAwtShutdownObserver.AWT_QUIESCENCE_STABILITY_NANOS), stabilizationBudgets);
            assertFalse(first.get().thread().isInterrupted());
            assertFalse(replacement.get().thread().isInterrupted());
        } finally {
            if (first.get() != null) releaseAndJoin(first.get());
            if (replacement.get() != null) releaseAndJoin(replacement.get());
        }
    }

    @Test
    void validatedBlockerTakesPriorityOverCoexistingNameOnlyCandidate() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        BlockedThread validated = startBlockedThread("AWT-Shutdown");
        BlockedThread nameOnly = startBlockedThread("AWT-Shutdown");
        AtomicInteger stage = new AtomicInteger();
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Thread> waitedThreads = new ArrayList<Thread>();
        List<Long> waitBudgets = new ArrayList<Long>();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> {
            if (stage.get() == 0) {
                Map<Thread, StackTraceElement[]> snapshot = new LinkedHashMap<Thread, StackTraceElement[]>();
                snapshot.put(nameOnly.thread(), nameOnly.thread().getStackTrace());
                snapshot.put(validated.thread(), new StackTraceElement[] {AWT_SHUTDOWN_FRAME});
                return snapshot;
            }
            if (stage.get() == 1) return snapshotOf(nameOnly);
            return Map.of();
        };
        WindowsAwtShutdownObserver.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            waitedThreads.add(thread);
            waitBudgets.add(timeoutNanos);
            assertFalse(thread.isInterrupted());
            if (stage.getAndIncrement() == 0) {
                assertEquals(validated.thread(), thread);
                releaseAndJoin(validated);
                nanoTime.addAndGet(10 * MILLISECOND_NANOS);
                return;
            }
            assertEquals(nameOnly.thread(), thread);
            assertEquals(WindowsAwtShutdownObserver.AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS, timeoutNanos);
            releaseAndJoin(nameOnly);
            nanoTime.addAndGet(MILLISECOND_NANOS);
        };
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(snapshots, nanoTime::get, waiter, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), new PrintWriter(new ByteArrayOutputStream()));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), TimeUnit.SECONDS.toNanos(1), 20 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(validated.thread(), nameOnly.thread()), waitedThreads);
            assertEquals(TimeUnit.SECONDS.toNanos(1), waitBudgets.get(0));
            assertEquals(WindowsAwtShutdownObserver.AWT_SHUTDOWN_VALIDATION_RESCAN_NANOS, waitBudgets.get(1));
            assertFalse(validated.thread().isInterrupted());
            assertFalse(nameOnly.thread().isInterrupted());
        } finally {
            releaseAndJoin(validated);
            releaseAndJoin(nameOnly);
        }
    }

    @Test
    void persistentNameOnlyCandidateUsesShortResnapshotsUntilBoundedTimeout() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        BlockedThread nameOnly = startBlockedThread("AWT-Shutdown");
        AtomicLong nanoTime = new AtomicLong(1_000);
        List<Long> waitBudgets = new ArrayList<Long>();
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(() -> snapshotOf(nameOnly), nanoTime::get, (thread, timeoutNanos) -> { assertEquals(nameOnly.thread(), thread); waitBudgets.add(timeoutNanos); nanoTime.addAndGet(timeoutNanos); }, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), 25 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(10 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, 5 * MILLISECOND_NANOS), waitBudgets);
            assertFalse(nameOnly.thread().isInterrupted());
            assertTrue(diagnosticBytes.toString(StandardCharsets.UTF_8).contains("post-toolkit AWT shutdown blockers"));
        } finally {
            releaseAndJoin(nameOnly);
        }
    }

    @Test
    void observerTimeoutDiagnosesAndReturnsWithoutInterruptingTheBlocker() throws Exception {
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        BlockedThread blocker = startBlockedThread("AWT-Shutdown");
        AtomicLong nanoTime = new AtomicLong(1_000);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        List<Long> waitBudgets = new ArrayList<Long>();
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(() -> snapshotOf(blocker, AWT_SHUTDOWN_FRAME), nanoTime::get, (thread, timeoutNanos) -> { waitBudgets.add(timeoutNanos); nanoTime.addAndGet(timeoutNanos); }, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), 100 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertEquals(List.of(100 * MILLISECOND_NANOS), waitBudgets);
            assertFalse(blocker.thread().isInterrupted());
            assertTrue(diagnostics.contains("Timed out after 100 ms"), diagnostics);
            assertTrue(diagnostics.contains("post-toolkit AWT shutdown blockers"), diagnostics);
            assertTrue(diagnostics.contains("AWT-Shutdown"), diagnostics);
        } finally {
            releaseAndJoin(blocker);
        }
    }

    @Test
    void interruptedStabilizationContinuesAndRestoresObserverInterruptStatus() throws Exception {
        assertFalse(Thread.interrupted());
        BlockedThread toolkit = startBlockedThread("AWT-Windows");
        releaseAndJoin(toolkit);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger stabilizationWaits = new AtomicInteger();
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(Map::of, nanoTime::get, (thread, timeoutNanos) -> {}, timeoutNanos -> { if (stabilizationWaits.getAndIncrement() == 0) throw new InterruptedException("injected observer interruption"); nanoTime.addAndGet(timeoutNanos); }, new PrintWriter(new ByteArrayOutputStream()));

        try {
            WindowsAwtShutdownObserver.observe(toolkit.thread(), nanoTime.get(), TimeUnit.SECONDS.toNanos(1), 20 * MILLISECOND_NANOS, hooks);
            assertEquals(2, stabilizationWaits.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidObserverDurationsAreRejected() {
        WindowsAwtShutdownObserver.ObserverHooks hooks = new WindowsAwtShutdownObserver.ObserverHooks(Map::of, () -> 1_000, (thread, timeoutNanos) -> {}, timeoutNanos -> {}, new PrintWriter(new ByteArrayOutputStream()));

        assertThrows(IllegalArgumentException.class, () -> WindowsAwtShutdownObserver.observe(null, 1_000, 0, 1, hooks));
        assertThrows(IllegalArgumentException.class, () -> WindowsAwtShutdownObserver.observe(null, 1_000, -1, 1, hooks));
        assertThrows(IllegalArgumentException.class, () -> WindowsAwtShutdownObserver.observe(null, 1_000, 1, 0, hooks));
        assertThrows(IllegalArgumentException.class, () -> WindowsAwtShutdownObserver.observe(null, 1_000, 1, -1, hooks));
    }

    private static Map<Thread, StackTraceElement[]> snapshotOf(BlockedThread blocked, StackTraceElement... stack) {
        if (blocked == null) return Map.of();
        StackTraceElement[] capturedStack = stack.length == 0 ? blocked.thread().getStackTrace() : stack;
        return Map.of(blocked.thread(), capturedStack);
    }

    private static BlockedThread startBlockedThread(String name) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> awaitRelease(started, release), name);
        thread.start();
        boolean startedInTime = started.await(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (!startedInTime) {
            release.countDown();
            thread.interrupt();
            thread.join(THREAD_TIMEOUT_MILLIS);
        }
        assertTrue(startedInTime, "Thread did not start: " + name);
        return new BlockedThread(thread, release);
    }

    private static void awaitRelease(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseAndJoin(BlockedThread blocked) throws InterruptedException {
        blocked.release().countDown();
        blocked.thread().join(THREAD_TIMEOUT_MILLIS);
        assertFalse(blocked.thread().isAlive(), "Thread did not terminate: " + blocked.thread().getName());
    }

    private static int countOccurrences(String text, String target) {
        int occurrences = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            occurrences++;
            index += target.length();
        }
        return occurrences;
    }

    private record BlockedThread(Thread thread, CountDownLatch release) {}
}
