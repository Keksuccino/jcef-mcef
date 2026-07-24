// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class TestProcessExitCoordinatorTest {
    private static final int UNSET_EXIT_STATUS = Integer.MIN_VALUE;
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long TEST_THREAD_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    // Fixture setup and coordinator waits can consume 5 + 5 + 5 seconds consecutively.
    private static final long PROCESS_TIMEOUT_SECONDS = 30;
    private static final StackTraceElement AWT_SHUTDOWN_FRAME = new StackTraceElement("sun.awt.AWTAutoShutdown", "run", "AWTAutoShutdown.java", 310);

    @TempDir
    Path tempDirectory_;

    @Test
    void quiescentSuccessReturnsNaturallyWithoutAwtShutdownOrExit() {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> countEmptySnapshot(snapshots), () -> 1_000, (thread, timeoutNanos) -> {}, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        TestProcessExitCoordinator.finish(0, 100, hooks);

        assertEquals(1, snapshots.get());
        assertEquals(0, shutdownActions.get());
        assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
    }

    @Test
    void fullHookWiringObservesRawDaemonBeforeFilteringAndReservesCoordinatorBudget() throws Exception {
        BlockedThread daemonToolkit = startBlockedThread("AWT-Windows", true);
        BlockedThread ordinary = startBlockedThread("ordinary-cleanup", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicLong observerReserveNanos = new AtomicLong();
        AtomicBoolean rawDaemonObserved = new AtomicBoolean();
        AtomicInteger rawSnapshots = new AtomicInteger();
        AtomicInteger completionValidations = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> Map.of(daemonToolkit.thread(), daemonToolkit.thread().getStackTrace(), ordinary.thread(), ordinary.thread().getStackTrace());
        TestProcessExitCoordinator.RawSnapshotObserver rawSnapshotObserver = snapshot -> {
            rawSnapshots.incrementAndGet();
            assertTrue(snapshot.containsKey(daemonToolkit.thread()));
            assertTrue(daemonToolkit.thread().isDaemon());
            rawDaemonObserved.set(true);
            observerReserveNanos.set(30 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertTrue(rawDaemonObserved.get());
            assertEquals(ordinary.thread(), thread);
            assertEquals(70 * MILLISECOND_NANOS, timeoutNanos);
            releaseAndJoin(ordinary);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.CompletionValidator completionValidator = () -> {
            completionValidations.incrementAndGet();
            return false;
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, nanoTime::get, waiter, timeoutNanos -> {}, rawSnapshotObserver, observerReserveNanos::get, completionValidator, () -> {}, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 0, 0, hooks);
            assertEquals(2, rawSnapshots.get());
            assertEquals(1, completionValidations.get());
            assertEquals(1, exitStatus.get());
        } finally {
            releaseAndJoin(ordinary);
            releaseAndJoin(daemonToolkit);
        }
    }

    @Test
    void completionValidationFailurePreservesExplicitNonzeroStatus() {
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(Map::of, () -> 1_000, (thread, timeoutNanos) -> {}, timeoutNanos -> {}, snapshot -> {}, () -> 0, () -> false, () -> {}, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        TestProcessExitCoordinator.finish(47, 100, 0, 0, hooks);

        assertEquals(47, exitStatus.get());
    }

    @Test
    void naturalAwtTerminationSurvivesUnvalidatedLateActivationWithoutForcedShutdown() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>(awt);
        AtomicReference<BlockedThread> late = new AtomicReference<BlockedThread>();
        AtomicBoolean lateStackValidated = new AtomicBoolean();
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 10 * MILLISECOND_NANOS);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Thread> waitedThreads = new ArrayList<Thread>();
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> active.get() != null && active.get() == late.get() && lateStackValidated.get() ? snapshotOfAwtShutdown(active.get()) : snapshotOf(active.get());
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            BlockedThread expected = active.get();
            assertEquals(expected.thread(), thread);
            waitedThreads.add(thread);
            waitBudgets.add(timeoutNanos);
            if (expected == awt) {
                releaseAndJoin(expected);
                active.set(null);
                nanoTime.addAndGet(20 * MILLISECOND_NANOS);
            } else if (!lateStackValidated.get()) {
                assertEquals(10 * MILLISECOND_NANOS, timeoutNanos);
                assertFalse(expected.thread().isInterrupted());
                lateStackValidated.set(true);
                nanoTime.addAndGet(MILLISECOND_NANOS);
            } else {
                releaseAndJoin(expected);
                active.set(null);
                nanoTime.addAndGet(10 * MILLISECOND_NANOS);
            }
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            if (late.get() == null) {
                BlockedThread lateCleanup = startBlockedThread("AWT-Shutdown", false);
                late.set(lateCleanup);
                active.set(lateCleanup);
                nanoTime.addAndGet(5 * MILLISECOND_NANOS);
                return;
            }
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, nanoTime::get, waiter, stabilizationWaiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(awt.thread(), late.get().thread(), late.get().thread()), waitedThreads);
            assertEquals(List.of(50 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, 24 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(List.of(20 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals(0, shutdownActions.get());
            assertFalse(late.get().thread().isInterrupted());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
            if (late.get() != null) releaseAndJoin(late.get());
        }
    }

    @Test
    void expiredGraceResnapshotsUnvalidatedAwtShutdownBeforeForcedAction() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>(awt);
        AtomicReference<BlockedThread> unvalidated = new AtomicReference<BlockedThread>();
        AtomicBoolean stackValidated = new AtomicBoolean();
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(active.get().thread(), thread);
            waitBudgets.add(timeoutNanos);
            if (active.get() == awt) {
                releaseAndJoin(awt);
                BlockedThread unvalidatedShutdown = startBlockedThread("AWT-Shutdown", false);
                unvalidated.set(unvalidatedShutdown);
                active.set(unvalidatedShutdown);
                nanoTime.addAndGet(10 * MILLISECOND_NANOS);
                return;
            }
            assertFalse(thread.isInterrupted());
            assertEquals(0, shutdownActions.get());
            stackValidated.set(true);
            nanoTime.addAndGet(MILLISECOND_NANOS);
        };
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> active.get() == unvalidated.get() && stackValidated.get() ? snapshotOfAwtShutdown(active.get()) : snapshotOf(active.get());
        TestProcessExitCoordinator.ShutdownAction shutdownAction = () -> {
            assertTrue(stackValidated.get());
            shutdownActions.incrementAndGet();
            releaseAndJoin(unvalidated.get());
            active.set(null);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, nanoTime::get, waiter, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), shutdownAction, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(10 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertFalse(unvalidated.get().thread().isInterrupted());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
            if (unvalidated.get() != null) releaseAndJoin(unvalidated.get());
        }
    }

    @Test
    void exhaustedInitialAwtGraceRequestsForcedShutdownExactlyOnce() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(awt.thread(), thread);
            waitBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.ShutdownAction shutdownAction = () -> {
            shutdownActions.incrementAndGet();
            releaseAndJoin(awt);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(awt), nanoTime::get, waiter, stabilizationWaiter, shutdownAction, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(50 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(List.of(10 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals(1, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void successfulAwtActionGetsSecondGraceBeforeValidatedBlockerInterruption() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-Shutdown", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(awt.thread(), thread);
            waitBudgets.add(timeoutNanos);
            if (waits.incrementAndGet() <= 2) {
                assertFalse(thread.isInterrupted());
                nanoTime.addAndGet(timeoutNanos);
                return;
            }
            thread.join(TEST_THREAD_TIMEOUT_MILLIS);
            assertFalse(thread.isAlive(), "Validated AWT shutdown blocker did not terminate after interruption");
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOfAwtShutdown(awt), nanoTime::get, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 0, hooks);
            assertEquals(List.of(50 * MILLISECOND_NANOS, 25 * MILLISECOND_NANOS, 25 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void blockerCanTerminateNaturallyDuringPostActionGraceWithoutInterruption() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-Shutdown", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(awt.thread(), thread);
            assertFalse(thread.isInterrupted());
            waitBudgets.add(timeoutNanos);
            if (waitBudgets.size() == 1) {
                nanoTime.addAndGet(timeoutNanos);
                return;
            }
            releaseAndJoin(awt);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOfAwtShutdown(awt), nanoTime::get, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 0, hooks);
            assertEquals(List.of(50 * MILLISECOND_NANOS, 25 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertFalse(awt.thread().isInterrupted());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void forcedShutdownResnapshotsUnvalidatedReplacementBeforeInterruptingIt() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>(awt);
        AtomicReference<BlockedThread> late = new AtomicReference<BlockedThread>();
        AtomicBoolean lateStackValidated = new AtomicBoolean();
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Thread> waitedThreads = new ArrayList<Thread>();
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> active.get() != null && active.get() == late.get() && lateStackValidated.get() ? snapshotOfAwtShutdown(active.get()) : snapshotOf(active.get());
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            BlockedThread expected = active.get();
            assertEquals(expected.thread(), thread);
            waitedThreads.add(thread);
            waitBudgets.add(timeoutNanos);
            if (expected == awt) {
                releaseAndJoin(expected);
                BlockedThread lateCleanup = startBlockedThread("AWT-Shutdown", false);
                late.set(lateCleanup);
                active.set(lateCleanup);
                nanoTime.addAndGet(20 * MILLISECOND_NANOS);
            } else if (!lateStackValidated.get()) {
                assertFalse(expected.thread().isInterrupted());
                lateStackValidated.set(true);
                nanoTime.addAndGet(MILLISECOND_NANOS);
            } else {
                assertTrue(expected.thread().isInterrupted() || !expected.thread().isAlive());
                releaseAndJoin(expected);
                active.set(null);
                nanoTime.addAndGet(10 * MILLISECOND_NANOS);
            }
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, nanoTime::get, waiter, stabilizationWaiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 0, 10 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(awt.thread(), late.get().thread(), late.get().thread()), waitedThreads);
            assertEquals(List.of(100 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, 79 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(List.of(10 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals(1, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
            if (late.get() != null) releaseAndJoin(late.get());
        }
    }

    @Test
    void graceStartsAtFirstAwtObservationAndLeavesBudgetForBothFallbackStages() throws Exception {
        BlockedThread ordinary = startBlockedThread("ordinary-cleanup", false);
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>(ordinary);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        List<Long> waitBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(active.get().thread(), thread);
            waitBudgets.add(timeoutNanos);
            if (active.get() == ordinary) {
                releaseAndJoin(ordinary);
                active.set(awt);
                nanoTime.addAndGet(40 * MILLISECOND_NANOS);
                return;
            }
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(active.get()), nanoTime::get, waiter, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 10 * MILLISECOND_NANOS, hooks);
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertEquals(List.of(100 * MILLISECOND_NANOS, 30 * MILLISECOND_NANOS, 15 * MILLISECOND_NANOS, 15 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertEquals(1, exitStatus.get());
            assertTrue(diagnostics.contains("Timed out after 100 ms"), diagnostics);
            assertTrue(diagnostics.contains("AWT-EventQueue-test"), diagnostics);
        } finally {
            releaseAndJoin(ordinary);
            releaseAndJoin(awt);
        }
    }

    @Test
    void defaultNaturalGraceIsFiveSecondsAndPostActionGraceRetainsHalfTheDeadline() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(awt.thread(), thread);
            waitBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(awt), nanoTime::get, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(12), hooks);
            assertEquals(List.of(TimeUnit.SECONDS.toNanos(5), TimeUnit.MILLISECONDS.toNanos(3_500), TimeUnit.MILLISECONDS.toNanos(3_500)), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertEquals(1, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void stableAwtQuiescenceCannotExtendTheSharedDeadline() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(awt.thread(), thread);
            releaseAndJoin(awt);
            nanoTime.addAndGet(90 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(awt), nanoTime::get, waiter, stabilizationWaiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertEquals(List.of(10 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals(0, shutdownActions.get());
            assertEquals(1, exitStatus.get());
            assertTrue(diagnostics.contains("validating stable AWT quiescence"), diagnostics);
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void nonzeroStatusRemainsExactWhenStableAwtQuiescenceExhaustsTheDeadline() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            releaseAndJoin(awt);
            nanoTime.addAndGet(90 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(awt), nanoTime::get, waiter, timeoutNanos -> nanoTime.addAndGet(timeoutNanos), () -> {}, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(29, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(29, exitStatus.get());
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertTrue(diagnostics.contains("validating stable AWT quiescence"), diagnostics);
            assertTrue(diagnostics.contains("status 29"), diagnostics);
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void interruptedStabilizationContinuesAndRestoresInterruptStatus() throws Exception {
        assertFalse(Thread.interrupted());
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger stabilizationWaits = new AtomicInteger();
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            releaseAndJoin(awt);
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            if (stabilizationWaits.getAndIncrement() == 0) throw new InterruptedException("injected stabilization interruption");
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(awt), nanoTime::get, waiter, stabilizationWaiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(2, stabilizationWaits.get());
            assertEquals(0, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            releaseAndJoin(awt);
        }
    }

    @Test
    void terminatedRawAwtSnapshotStillArmsStableQuiescenceForReplacement() throws Exception {
        BlockedThread terminatedAwt = startBlockedThread("AWT-Shutdown", false);
        releaseAndJoin(terminatedAwt);
        AtomicReference<BlockedThread> late = new AtomicReference<BlockedThread>();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Long> waitBudgets = new ArrayList<Long>();
        List<Long> stabilizationBudgets = new ArrayList<Long>();
        Supplier<Map<Thread, StackTraceElement[]>> threadSnapshots = () -> snapshots.getAndIncrement() == 0 ? snapshotOfAwtShutdown(terminatedAwt) : snapshotOfAwtShutdown(late.get());
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            assertEquals(late.get().thread(), thread);
            waitBudgets.add(timeoutNanos);
            releaseAndJoin(late.get());
            nanoTime.addAndGet(10 * MILLISECOND_NANOS);
        };
        TestProcessExitCoordinator.StabilizationWaiter stabilizationWaiter = timeoutNanos -> {
            stabilizationBudgets.add(timeoutNanos);
            if (late.get() == null) {
                late.set(startBlockedThread("AWT-Shutdown", false));
                nanoTime.addAndGet(5 * MILLISECOND_NANOS);
                return;
            }
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(threadSnapshots, nanoTime::get, waiter, stabilizationWaiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100 * MILLISECOND_NANOS, 80 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS, hooks);
            assertEquals(List.of(45 * MILLISECOND_NANOS), waitBudgets);
            assertEquals(List.of(20 * MILLISECOND_NANOS, 20 * MILLISECOND_NANOS), stabilizationBudgets);
            assertEquals(0, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            if (late.get() != null) releaseAndJoin(late.get());
        }
    }

    @Test
    void nonAwtSuccessWaitsWithoutRequestingAwtShutdown() throws Exception {
        BlockedThread pending = startBlockedThread("ordinary-cleanup", false);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> snapshotOf(pending);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> releaseAndJoin(pending);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, System::nanoTime, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), hooks);
            assertEquals(0, shutdownActions.get());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(pending);
        }
    }

    @Test
    void successfulAwtActionInterruptsTheShutdownBlocker() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-Shutdown", false);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> thread.join(TEST_THREAD_TIMEOUT_MILLIS);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOfAwtShutdown(awt), System::nanoTime, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), 0, 0, hooks);
            assertEquals(1, shutdownActions.get());
            assertTrue(awt.thread().isInterrupted());
            assertFalse(awt.thread().isAlive());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void spoofedAwtShutdownNameIsNeitherInterruptedNorTreatedAsAwt() throws Exception {
        BlockedThread spoof = startBlockedThread("AWT-Shutdown", false);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> releaseAndJoin(spoof);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOf(spoof), System::nanoTime, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), hooks);
            assertEquals(0, shutdownActions.get());
            assertFalse(spoof.thread().isInterrupted());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(spoof);
        }
    }

    @Test
    void deniedBlockerInterruptFailsClosedOnceAfterBoundedCleanup() throws Exception {
        BlockedThread awt = startInterruptDeniedThread();
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            waits.incrementAndGet();
            releaseAndJoin(awt);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOfAwtShutdown(awt), System::nanoTime, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), 0, 0, hooks);
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, shutdownActions.get());
            assertEquals(1, waits.get());
            assertEquals(1, exitStatus.get());
            assertTrue(diagnostics.contains("Failed to interrupt the AWT shutdown blocker"), diagnostics);
            assertEquals(1, countOccurrences(diagnostics, "injected interrupt denial"));
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void awtActionQuiescenceAtDeadlineIsNotReportedAsTimeout() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-Shutdown", false);
        AtomicLong nanoTime = new AtomicLong(1_000);
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            throw new AssertionError("Quiescent AWT thread should not be waited after resnapshot");
        };
        TestProcessExitCoordinator.ShutdownAction shutdownAction = () -> {
            releaseAndJoin(awt);
            nanoTime.addAndGet(100);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> snapshotOfAwtShutdown(awt), nanoTime::get, waiter, shutdownAction, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(0, 100, 0, 0, hooks);
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
            assertEquals("", diagnosticBytes.toString(StandardCharsets.UTF_8));
        } finally {
            releaseAndJoin(awt);
        }
    }

    @Test
    void nonzeroStatusRemainsExactAfterQuiescence() {
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(Map::of, () -> 1_000, (thread, timeoutNanos) -> {}, () -> {}, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        TestProcessExitCoordinator.finish(41, 100, hooks);

        assertEquals(41, exitStatus.get());
    }

    @Test
    void invalidCoordinatorDurationsAreRejectedBeforeTakingSnapshots() {
        AtomicInteger snapshots = new AtomicInteger();
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(() -> countEmptySnapshot(snapshots), () -> 1_000, (thread, timeoutNanos) -> {}, () -> {}, status -> {}, new PrintWriter(new ByteArrayOutputStream()));

        assertThrows(IllegalArgumentException.class, () -> TestProcessExitCoordinator.finish(0, 0, hooks));
        assertThrows(IllegalArgumentException.class, () -> TestProcessExitCoordinator.finish(0, -1, hooks));
        assertThrows(IllegalArgumentException.class, () -> TestProcessExitCoordinator.finish(0, 1, -1, 0, hooks));
        assertThrows(IllegalArgumentException.class, () -> TestProcessExitCoordinator.finish(0, 1, 0, -1, hooks));
        assertEquals(0, snapshots.get());
    }

    @Test
    void successfulStatusTimeoutFailsClosedWithDiagnostics() throws Exception {
        TimeoutResult result = coordinateTimeout(0);

        assertEquals(1, result.exitStatus());
        assertTimeoutDiagnostics(result, 0);
    }

    @Test
    void nonzeroStatusRemainsExactAfterTimeout() throws Exception {
        TimeoutResult result = coordinateTimeout(73);

        assertEquals(73, result.exitStatus());
        assertTimeoutDiagnostics(result, 73);
    }

    @Test
    void awtShutdownActionFailureWaitsForQuiescenceThenFailsClosed() throws Exception {
        ActionFailureResult result = coordinateActionFailure(0);

        assertEquals(1, result.exitStatus());
        assertEquals(1, result.waits());
        assertTrue(result.diagnostics().contains("Failed to request orderly AWT event-dispatch shutdown"), result.diagnostics());
        assertTrue(result.diagnostics().contains("injected shutdown failure"), result.diagnostics());
        assertFalse(result.diagnostics().contains("Timed out"), result.diagnostics());
    }

    @Test
    void awtShutdownActionFailurePreservesNonzeroStatus() throws Exception {
        ActionFailureResult result = coordinateActionFailure(29);

        assertEquals(29, result.exitStatus());
        assertEquals(1, result.waits());
        assertTrue(result.diagnostics().contains("completion with status 29"), result.diagnostics());
    }

    @Test
    void reflectedShutdownErrorIsNotConvertedToAnOrderlyFailure() throws Exception {
        Method shutdownMethod = TestProcessExitCoordinatorTest.class.getDeclaredMethod("throwShutdownError");
        assertTrue(shutdownMethod.trySetAccessible());

        AssertionError error = assertThrows(AssertionError.class, () -> TestProcessExitCoordinator.invokeShutdownMethod(shutdownMethod));

        assertEquals("injected shutdown error", error.getMessage());
    }

    @Test
    void interruptedWaitContinuesCleanupAndRestoresInterruptStatus() throws Exception {
        assertFalse(Thread.interrupted());
        BlockedThread pending = startBlockedThread("interrupted-cleanup", false);
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> snapshotOf(pending);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, remainingNanos) -> {
            assertEquals(pending.thread(), thread);
            releaseAndJoin(pending);
            throw new InterruptedException("injected interruption");
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, System::nanoTime, waiter, () -> {}, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), hooks);
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            releaseAndJoin(pending);
        }
    }

    @Test
    void nonzeroSubprocessPreservesExactStatusWithoutCrashReport() throws Exception {
        ProcessResult result = runFixture(TestProcessExitCoordinatorProcess.FAILURE_MODE);

        assertEquals(TestProcessExitCoordinatorProcess.FAILURE_STATUS, result.exitStatus(), result.output());
        assertNoFatalJvmError(result);
    }

    @Test
    void awtInitializedNonzeroSubprocessShutsDownThenPreservesExactStatus() throws Exception {
        ProcessResult result = runFixture(TestProcessExitCoordinatorProcess.AWT_FAILURE_MODE);

        assertEquals(TestProcessExitCoordinatorProcess.FAILURE_STATUS, result.exitStatus(), result.output());
        assertTrue(result.output().contains(TestProcessExitCoordinatorProcess.AWT_SHUTDOWN_MARKER), result.output());
        assertTrue(result.output().contains(TestProcessExitCoordinatorProcess.AWT_QUIESCENCE_MARKER), result.output());
        assertFalse(result.output().contains("Failed to request orderly AWT event-dispatch shutdown"), result.output());
        assertFalse(result.output().contains("Failed to interrupt the AWT shutdown blocker"), result.output());
        assertFalse(result.output().contains("Timed out"), result.output());
        assertNoFatalJvmError(result);
    }

    @Test
    void awtInitializedSuccessReturnsAfterOrderlyShutdownWithoutCrashReport() throws Exception {
        ProcessResult result = runFixture(TestProcessExitCoordinatorProcess.AWT_SUCCESS_MODE);

        assertEquals(0, result.exitStatus(), result.output());
        assertTrue(result.output().contains(TestProcessExitCoordinatorProcess.AWT_SHUTDOWN_MARKER), result.output());
        assertTrue(result.output().contains(TestProcessExitCoordinatorProcess.NATURAL_RETURN_MARKER), result.output());
        assertNoFatalJvmError(result);
    }

    @Test
    void timedOutSubprocessFailsClosedWithoutCrashReport() throws Exception {
        ProcessResult result = runFixture(TestProcessExitCoordinatorProcess.TIMEOUT_MODE);

        assertEquals(1, result.exitStatus(), result.output());
        assertTrue(result.output().contains("Timed out after 100 ms"), result.output());
        assertTrue(result.output().contains("stuck-fixture-cleanup"), result.output());
        assertNoFatalJvmError(result);
    }

    @Test
    void shutdownActionFailureSubprocessWaitsThenFailsClosedWithoutCrashReport() throws Exception {
        ProcessResult result = runFixture(TestProcessExitCoordinatorProcess.AWT_ACTION_FAILURE_MODE);

        assertEquals(1, result.exitStatus(), result.output());
        assertTrue(result.output().contains("injected AWT shutdown action failure"), result.output());
        assertFalse(result.output().contains("Timed out"), result.output());
        assertNoFatalJvmError(result);
    }

    private ProcessResult runFixture(String mode) throws Exception {
        Path processDirectory = Files.createDirectory(tempDirectory_.resolve(mode));
        Path output = processDirectory.resolve("process.log");
        List<String> command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(TestProcessExitCoordinator.AWT_MODULE_OPEN_ARGUMENT);
        boolean headless = !isAwtMode(mode) || !System.getProperty("os.name", "").startsWith("Windows");
        command.add("-Djava.awt.headless=" + headless);
        command.add(ChildProcessSupport.jvmErrorFileArgument(processDirectory));
        command.add("-cp");
        command.add(ChildProcessSupport.codeSourceClassPathFor(TestProcessExitCoordinatorProcess.class));
        command.add(TestProcessExitCoordinatorProcess.class.getName());
        command.add(mode);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String processOutput = Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
        assertTrue(exited, "Fixture timed out; output:\n" + processOutput);
        return new ProcessResult(process.exitValue(), processOutput, ChildProcessSupport.findJvmCrashReports(processDirectory));
    }

    private static TimeoutResult coordinateTimeout(int requestedStatus) throws Exception {
        BlockedThread pending = startBlockedThread("stuck-cleanup", false);
        long timeoutNanos = TimeUnit.SECONDS.toNanos(3);
        AtomicLong nanoTime = new AtomicLong(5_000);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        StackTraceElement frame = new StackTraceElement("example.Worker", "awaitShutdown", "Worker.java", 42);
        Supplier<Map<Thread, StackTraceElement[]>> threadSnapshots = () -> syntheticSnapshot(snapshots, pending.thread(), frame);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, remainingNanos) -> {
            assertEquals(pending.thread(), thread);
            assertEquals(timeoutNanos, remainingNanos);
            waits.incrementAndGet();
            nanoTime.addAndGet(timeoutNanos);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(threadSnapshots, nanoTime::get, waiter, () -> {}, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(requestedStatus, timeoutNanos, hooks);
            return new TimeoutResult(exitStatus.get(), snapshots.get(), waits.get(), diagnosticBytes.toString(StandardCharsets.UTF_8));
        } finally {
            releaseAndJoin(pending);
        }
    }

    private static ActionFailureResult coordinateActionFailure(int requestedStatus) throws Exception {
        BlockedThread awt = startBlockedThread("AWT-Shutdown", false);
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> snapshotOfAwtShutdown(awt);
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            waits.incrementAndGet();
            releaseAndJoin(awt);
        };
        TestProcessExitCoordinator.ShutdownAction shutdownAction = () -> {
            throw new IllegalStateException("injected shutdown failure");
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, System::nanoTime, waiter, shutdownAction, exitStatus::set, new PrintWriter(diagnosticBytes, true, StandardCharsets.UTF_8));

        try {
            TestProcessExitCoordinator.finish(requestedStatus, TimeUnit.SECONDS.toNanos(1), 0, 0, hooks);
            return new ActionFailureResult(exitStatus.get(), waits.get(), diagnosticBytes.toString(StandardCharsets.UTF_8));
        } finally {
            releaseAndJoin(awt);
        }
    }

    private static void assertTimeoutDiagnostics(TimeoutResult result, int requestedStatus) {
        assertEquals(2, result.snapshots());
        assertEquals(1, result.waits());
        assertTrue(result.diagnostics().contains("Timed out after 3000 ms"), result.diagnostics());
        assertTrue(result.diagnostics().contains("completion with status " + requestedStatus), result.diagnostics());
        assertTrue(result.diagnostics().contains("Thread \"stuck-cleanup\""), result.diagnostics());
        assertTrue(result.diagnostics().contains("at example.Worker.awaitShutdown(Worker.java:42)"), result.diagnostics());
    }

    private static void assertNoFatalJvmError(ProcessResult result) {
        assertFalse(ChildProcessSupport.containsJvmFatalError(result.output()), "Fixture reported a fatal JVM error:\n" + result.output());
        assertFalse(WindowsAwtShutdownObserver.containsFailureDiagnostics(result.output()), "Fixture reported a Windows AWT shutdown-observer failure:\n" + result.output());
        assertTrue(result.crashReports().isEmpty(), "Unexpected JVM crash reports: " + result.crashReports() + "\n" + result.output());
    }

    private static boolean isAwtMode(String mode) {
        return TestProcessExitCoordinatorProcess.AWT_SUCCESS_MODE.equals(mode) || TestProcessExitCoordinatorProcess.AWT_FAILURE_MODE.equals(mode) || TestProcessExitCoordinatorProcess.AWT_ACTION_FAILURE_MODE.equals(mode);
    }

    private static void throwShutdownError() {
        throw new AssertionError("injected shutdown error");
    }

    private static Map<Thread, StackTraceElement[]> countEmptySnapshot(AtomicInteger snapshots) {
        snapshots.incrementAndGet();
        return Map.of();
    }

    private static Map<Thread, StackTraceElement[]> snapshotOf(BlockedThread blocked) {
        return blocked == null ? Map.of() : Map.of(blocked.thread(), blocked.thread().getStackTrace());
    }

    private static Map<Thread, StackTraceElement[]> snapshotOfAwtShutdown(BlockedThread blocked) {
        return blocked == null ? Map.of() : Map.of(blocked.thread(), new StackTraceElement[] {AWT_SHUTDOWN_FRAME});
    }

    private static Map<Thread, StackTraceElement[]> syntheticSnapshot(AtomicInteger snapshots, Thread thread, StackTraceElement frame) {
        snapshots.incrementAndGet();
        return Map.of(thread, new StackTraceElement[] {frame});
    }

    private static BlockedThread startBlockedThread(String name, boolean daemon) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable operation = () -> awaitRelease(started, release);
        Thread thread = new Thread(operation, name);
        thread.setDaemon(daemon);
        thread.start();
        boolean startedInTime = started.await(TEST_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (!startedInTime) {
            release.countDown();
            thread.interrupt();
            thread.join(TEST_THREAD_TIMEOUT_MILLIS);
        }
        assertTrue(startedInTime, "Thread did not start: " + name);
        return new BlockedThread(thread, release);
    }

    private static BlockedThread startInterruptDeniedThread() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> awaitRelease(started, release), "AWT-Shutdown") {
            @Override
            public void interrupt() {
                throw new SecurityException("injected interrupt denial");
            }
        };
        thread.start();
        boolean startedInTime = started.await(TEST_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (!startedInTime) {
            release.countDown();
            thread.join(TEST_THREAD_TIMEOUT_MILLIS);
        }
        assertTrue(startedInTime, "Interrupt-denied thread did not start");
        return new BlockedThread(thread, release);
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
        blocked.thread().join(TEST_THREAD_TIMEOUT_MILLIS);
        assertFalse(blocked.thread().isAlive(), "Thread did not terminate: " + blocked.thread().getName());
    }

    private record BlockedThread(Thread thread, CountDownLatch release) {}

    private record TimeoutResult(int exitStatus, int snapshots, int waits, String diagnostics) {}

    private record ActionFailureResult(int exitStatus, int waits, String diagnostics) {}

    private record ProcessResult(int exitStatus, String output, List<Path> crashReports) {}
}
