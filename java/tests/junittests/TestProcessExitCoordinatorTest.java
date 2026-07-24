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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class TestProcessExitCoordinatorTest {
    private static final int UNSET_EXIT_STATUS = Integer.MIN_VALUE;
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
    void awtSuccessRequestsShutdownOnceAndWaitsForDynamicThreads() throws Exception {
        BlockedThread awt = startBlockedThread("AWT-EventQueue-test", false);
        AtomicReference<BlockedThread> active = new AtomicReference<BlockedThread>(awt);
        AtomicReference<BlockedThread> late = new AtomicReference<BlockedThread>();
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 10);
        AtomicInteger shutdownActions = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(UNSET_EXIT_STATUS);
        List<Thread> waitedThreads = new ArrayList<Thread>();
        List<Long> waitBudgets = new ArrayList<Long>();
        Supplier<Map<Thread, StackTraceElement[]>> snapshots = () -> active.get() != null && active.get() == late.get() ? snapshotOfAwtShutdown(active.get()) : snapshotOf(active.get());
        TestProcessExitCoordinator.ThreadWaiter waiter = (thread, timeoutNanos) -> {
            BlockedThread expected = active.get();
            assertEquals(expected.thread(), thread);
            waitedThreads.add(thread);
            waitBudgets.add(timeoutNanos);
            releaseAndJoin(expected);
            if (expected == awt) {
                BlockedThread lateCleanup = startBlockedThread("AWT-Shutdown", false);
                late.set(lateCleanup);
                active.set(lateCleanup);
            } else {
                active.set(null);
            }
            nanoTime.addAndGet(25);
        };
        TestProcessExitCoordinator.Hooks hooks = new TestProcessExitCoordinator.Hooks(snapshots, nanoTime::get, waiter, shutdownActions::incrementAndGet, exitStatus::set, new PrintWriter(new ByteArrayOutputStream()));

        try {
            TestProcessExitCoordinator.finish(0, 100, hooks);
            assertEquals(List.of(awt.thread(), late.get().thread()), waitedThreads);
            assertEquals(List.of(100L, 75L), waitBudgets);
            assertEquals(1, shutdownActions.get());
            assertTrue(late.get().thread().isInterrupted());
            assertEquals(UNSET_EXIT_STATUS, exitStatus.get());
        } finally {
            releaseAndJoin(awt);
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
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), hooks);
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
            TestProcessExitCoordinator.finish(0, TimeUnit.SECONDS.toNanos(1), hooks);
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
            TestProcessExitCoordinator.finish(0, 100, hooks);
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
        assertTrue(result.output().contains("Timed out after 100 ms"), result.output());
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
            TestProcessExitCoordinator.finish(requestedStatus, TimeUnit.SECONDS.toNanos(1), hooks);
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
