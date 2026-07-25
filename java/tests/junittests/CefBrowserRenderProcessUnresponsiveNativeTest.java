// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

@NativeCefTest
class CefBrowserRenderProcessUnresponsiveNativeTest {
    private static final int CONCURRENT_QUERY_COUNT = 16;
    private static final int CLOSE_RACE_QUERY_COUNT = 12;
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final String CLOSED_QUERY_MESSAGE = "Browser closed before render process responsiveness query completed";

    private record CloseRaceSnapshot(int admittedQueries, int canceledQueries, int closeFailedQueries, int terminalNotifications) {}

    @Test
    @WindowedCefTest
    void responsiveWindowedBrowserReportsFalseFromCefUiAndWorkerThreads() throws Exception {
        assertResponsiveBrowser(false);
    }

    @Test
    void responsiveOffscreenBrowserReportsFalseFromCefUiAndWorkerThreads() throws Exception {
        assertResponsiveBrowser(true);
    }

    @Test
    void forceCloseCancelsQueuedOffscreenQueriesAndIgnoresLateNativeCallbacks() throws Exception {
        String testUrl = "http://render-responsiveness-close-race.test/index.html";
        ExecutorService workers = newWorkerExecutor("jcef-render-responsiveness-close-race", 4);
        CompletableFuture<CloseRaceSnapshot> raceSnapshot = new CompletableFuture<CloseRaceSnapshot>();
        CompletableFuture<Void> beforeClose = new CompletableFuture<Void>();
        AtomicBoolean raceStarted = new AtomicBoolean();
        AtomicBoolean workerCloseCompleted = new AtomicBoolean();
        AtomicInteger terminalNotifications = new AtomicInteger();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                addResource(testUrl, "<html><body>Render responsiveness close race</body></html>", "text/html");
                browser_ = createOffscreenBrowser(testUrl, null);
                super.setupTest();
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                super.onLoadEnd(browser, frame, httpStatusCode);
                if (browser != browser_ || !frame.isMain() || !raceStarted.compareAndSet(false, true)) return;
                CloseRaceSnapshot snapshot = null;
                Throwable raceFailure = null;
                try {
                    snapshot = runQueuedCloseRace(browser, workers, workerCloseCompleted, terminalNotifications);
                } catch (Throwable failure) {
                    raceFailure = unwrapCompletionFailure(failure);
                }
                try {
                    // CefBrowserHost::CloseBrowser must not be invoked reentrantly from this load
                    // callback. Keep the UI callback active to hold posted queries in the queue,
                    // but issue close from a worker so CEF can schedule teardown after we return.
                    if (!workerCloseCompleted.get()) closeFromWorker(browser, workers, workerCloseCompleted);
                } catch (Throwable closeFailure) {
                    raceFailure = collectFailure(raceFailure, unwrapCompletionFailure(closeFailure));
                }
                if (raceFailure == null) raceSnapshot.complete(snapshot);
                else raceSnapshot.completeExceptionally(raceFailure);
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                if (browser != browser_) return;
                beforeClose.complete(null);
                super.onBeforeClose(browser);
                SwingUtilities.invokeLater(this::dispose);
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        Throwable failure = null;
        try {
            CloseRaceSnapshot snapshot = await(raceSnapshot);
            assertEquals(CLOSE_RACE_QUERY_COUNT, snapshot.admittedQueries());
            assertEquals(1, snapshot.canceledQueries());
            assertEquals(CLOSE_RACE_QUERY_COUNT - 1, snapshot.closeFailedQueries());
            assertEquals(CLOSE_RACE_QUERY_COUNT, snapshot.terminalNotifications());
            await(beforeClose);
            frame.awaitCompletion();
            assertEquals(CLOSE_RACE_QUERY_COUNT, terminalNotifications.get(), "Late native callbacks must not publish a second terminal result");
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            shutdownExecutor(workers);
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        try {
            if (!beforeClose.isDone()) frame.terminateTest();
            frame.awaitCompletion();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        if (failure != null) rethrow(failure);
    }

    private static void assertResponsiveBrowser(boolean offscreen) throws Exception {
        String testUrl = offscreen ? "http://render-responsiveness-osr.test/index.html" : "http://render-responsiveness-windowed.test/index.html";
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<Boolean> cefUiQuery = new CompletableFuture<Boolean>();
        AtomicReference<Thread> cefUiThread = new AtomicReference<Thread>();
        ExecutorService workers = newWorkerExecutor("jcef-render-responsiveness-worker", 4);
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                addResource(testUrl, "<html><body>Responsive renderer</body></html>", "text/html");
                if (offscreen) {
                    browser_ = createOffscreenBrowser(testUrl, null);
                } else {
                    createBrowser(testUrl);
                }
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) browserCreated.complete(browser);
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                super.onLoadEnd(browser, frame, httpStatusCode);
                if (browser != browser_ || !frame.isMain() || cefUiQuery.isDone()) return;
                cefUiThread.compareAndSet(null, Thread.currentThread());
                captureResponsiveUiQuery(browser, cefUiQuery);
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CefBrowser browser = null;
        Throwable failure = null;
        try {
            browser = await(browserCreated);
            assertFalse(await(cefUiQuery).booleanValue());
            assertNotNull(cefUiThread.get());

            List<CompletableFuture<Boolean>> workerQueries = new ArrayList<CompletableFuture<Boolean>>(CONCURRENT_QUERY_COUNT);
            for (int queryIndex = 0; queryIndex < CONCURRENT_QUERY_COUNT; queryIndex++) {
                workerQueries.add(queryFromWorker(workers, browser, cefUiThread));
            }
            for (CompletableFuture<Boolean> query : workerQueries) {
                assertFalse(await(query).booleanValue());
            }
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            shutdownExecutor(workers);
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        try {
            frame.terminateTest();
            frame.awaitCompletion();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        if (failure != null) rethrow(failure);

        assertNotNull(browser);
        assertClosedQuery(browser.isRenderProcessUnresponsive(), CLOSED_QUERY_MESSAGE);
    }

    private static CloseRaceSnapshot runQueuedCloseRace(CefBrowser browser, ExecutorService workers, AtomicBoolean workerCloseCompleted, AtomicInteger terminalNotifications) throws Exception {
        ConcurrentLinkedQueue<CompletableFuture<Boolean>> admittedQueries = new ConcurrentLinkedQueue<CompletableFuture<Boolean>>();
        AtomicReference<Throwable> admissionFailure = new AtomicReference<Throwable>();
        CountDownLatch admissionComplete = new CountDownLatch(CLOSE_RACE_QUERY_COUNT);
        for (int queryIndex = 0; queryIndex < CLOSE_RACE_QUERY_COUNT; queryIndex++) {
            workers.execute(() -> {
                try {
                    admittedQueries.add(browser.isRenderProcessUnresponsive());
                } catch (Throwable failure) {
                    admissionFailure.compareAndSet(null, failure);
                } finally {
                    admissionComplete.countDown();
                }
            });
        }

        assertTrue(admissionComplete.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Worker queries did not enter JNI while the CEF UI callback was active");
        Throwable workerFailure = admissionFailure.get();
        if (workerFailure != null) throw new AssertionError("Worker query admission failed", workerFailure);
        List<CompletableFuture<Boolean>> queries = new ArrayList<CompletableFuture<Boolean>>(admittedQueries);
        assertEquals(CLOSE_RACE_QUERY_COUNT, queries.size());
        for (CompletableFuture<Boolean> query : queries) {
            assertFalse(query.isDone(), "A worker query queued behind the active CEF UI callback must remain pending");
            query.whenComplete((value, failure) -> terminalNotifications.incrementAndGet());
        }

        CompletableFuture<Boolean> canceled = queries.get(0);
        assertTrue(canceled.cancel(false));
        closeFromWorker(browser, workers, workerCloseCompleted);

        assertTrue(canceled.isCancelled());
        for (int queryIndex = 1; queryIndex < queries.size(); queryIndex++) {
            assertClosedQuery(queries.get(queryIndex), CLOSED_QUERY_MESSAGE);
        }
        assertEquals(CLOSE_RACE_QUERY_COUNT, terminalNotifications.get());
        assertClosedQuery(browser.isRenderProcessUnresponsive(), CLOSED_QUERY_MESSAGE);
        return new CloseRaceSnapshot(queries.size(), 1, queries.size() - 1, terminalNotifications.get());
    }

    private static void closeFromWorker(CefBrowser browser, ExecutorService workers, AtomicBoolean workerCloseCompleted) throws Exception {
        await(CompletableFuture.runAsync(() -> browser.close(true), workers));
        workerCloseCompleted.set(true);
    }

    private static CompletableFuture<Boolean> queryFromWorker(ExecutorService workers, CefBrowser browser, AtomicReference<Thread> cefUiThread) {
        return CompletableFuture.supplyAsync(() -> {
            assertNotSame(cefUiThread.get(), Thread.currentThread(), "Responsiveness query must originate outside the CEF UI callback thread");
            try {
                return await(browser.isRenderProcessUnresponsive());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, workers);
    }

    private static void captureResponsiveUiQuery(CefBrowser browser, CompletableFuture<Boolean> snapshot) {
        try {
            CompletableFuture<Boolean> query = browser.isRenderProcessUnresponsive();
            if (!query.isDone()) throw new AssertionError("A responsiveness query from a CEF UI callback must complete directly");
            boolean unresponsive = query.join().booleanValue();
            if (unresponsive) throw new AssertionError("A newly loaded responsive renderer was reported as unresponsive");
            snapshot.complete(Boolean.FALSE);
        } catch (Throwable failure) {
            snapshot.completeExceptionally(unwrapCompletionFailure(failure));
        }
    }

    private static void assertClosedQuery(CompletableFuture<?> future, String expectedMessage) {
        assertNotNull(future);
        assertTrue(future.isDone());
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(expectedMessage, failure.getMessage());
    }

    private static ExecutorService newWorkerExecutor(String threadNamePrefix, int threadCount) {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, threadNamePrefix + "-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threadCount, threadFactory);
    }

    private static void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Render responsiveness workers did not terminate");
    }

    private static Throwable collectFailure(Throwable current, Throwable addition) {
        if (current == null) return addition;
        current.addSuppressed(addition);
        return current;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError("Unexpected render responsiveness test failure", failure);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
