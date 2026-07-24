// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefFindHandler;
import org.cef.handler.CefFindHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@NativeCefTest
class CefFindHandlerTest {
    private static final String READY_TITLE = "JCEF find handler ready";
    private static final String READY_SCRIPT = "document.title = '" + READY_TITLE + "';";
    private static final String TEST_CONTENT =
            "<html><head><title>Find handler loading</title></head><body>"
            + "<p>JcefUniqueMarker</p>"
            + "<p>JcefRepeatedMarker JcefRepeatedMarker JcefRepeatedMarker</p>"
            + "<p>JcefCaseMarker jcefcasemarker</p>"
            + "<p>FirstDelegateMarker ReplacementDelegateMarker</p>"
            + "</body></html>";
    private static final SearchSpec[] SEARCH_SPECS = {
            new SearchSpec("JcefUniqueMarker", false, 1),
            new SearchSpec("JcefRepeatedMarker", false, 3),
            new SearchSpec("JcefMissingMarker", false, 0),
            new SearchSpec("JcefCaseMarker", true, 1),
            new SearchSpec("jcefcasemarker", false, 2),
    };

    @Test
    void reportsFinalResultsForWindowedBrowser() {
        runResultMatrix("http://find-handler.test/windowed.html", false);
    }

    @Test
    void reportsFinalResultsForImmediatelyCreatedOffscreenBrowser() {
        runResultMatrix("http://find-handler.test/offscreen.html", true);
    }

    @Test
    void firstHandlerWinsAndCanBeReplacedAfterBrowserCreation() {
        HandlerReplacementController controller = new HandlerReplacementController("http://find-handler.test/replacement.html");
        TestFrame frame = createFrame(controller, false);

        frame.awaitCompletion();
        controller.assertCompleted();
    }

    private static void runResultMatrix(String url, boolean offscreen) {
        ResultMatrixController controller = new ResultMatrixController(url);
        TestFrame frame = createFrame(controller, offscreen);

        frame.awaitCompletion();
        controller.assertCompleted();
    }

    private static TestFrame createFrame(FindTestController controller, boolean offscreen) {
        Supplier<TestFrame> factory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                controller.attachTerminator(this::terminateTest);
                controller.attachClient(client_);
                controller.registerFindHandlers(client_);
                CefDisplayHandlerAdapter displayHandler = new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        controller.onTitleChange(browser, title);
                    }
                };
                client_.addDisplayHandler(displayHandler);
                addResource(controller.getUrl(), TEST_CONTENT, "text/html");

                if (offscreen) {
                    browser_ = client_.createBrowser(controller.getUrl(), true, false);
                    assertNotNull(browser_);
                    controller.attachBrowser(browser_);
                    browser_.createImmediately();
                } else {
                    createBrowser(controller.getUrl());
                    controller.attachBrowser(browser_);
                }

                super.setupTest();
            }

            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                if (browser == browser_ && !isLoading) controller.onLoadComplete(browser);
            }
        };
        return TestFrame.createOnEventDispatchThread(factory);
    }

    private abstract static class FindTestController {
        private final String url_;
        private final AtomicReference<CefBrowser> browser_ = new AtomicReference<CefBrowser>();
        private final AtomicReference<CefClient> client_ = new AtomicReference<CefClient>();
        private final AtomicReference<Runnable> terminator_ = new AtomicReference<Runnable>();
        private final AtomicReference<Thread> cefUiThread_ = new AtomicReference<Thread>();
        private final AtomicReference<Throwable> callbackFailure_ =
                new AtomicReference<Throwable>();
        private final AtomicBoolean loadCompleted_ = new AtomicBoolean();
        private final AtomicBoolean readyReceived_ = new AtomicBoolean();
        private final AtomicBoolean findNativeRefObserved_ = new AtomicBoolean();
        private final AtomicBoolean finished_ = new AtomicBoolean();

        FindTestController(String url) {
            url_ = url;
        }

        final String getUrl() {
            return url_;
        }

        final void attachTerminator(Runnable terminator) {
            assertTrue(terminator_.compareAndSet(null, terminator));
        }

        final void attachClient(CefClient client) {
            assertTrue(client_.compareAndSet(null, client));
        }

        final void attachBrowser(CefBrowser browser) {
            CefBrowser attached = browser_.get();
            if (attached == null) browser_.compareAndSet(null, browser);
            assertSame(browser, browser_.get());
        }

        final void onLoadComplete(CefBrowser browser) {
            if (!loadCompleted_.compareAndSet(false, true)) return;
            try {
                attachBrowser(browser);
                assertTrue(cefUiThread_.compareAndSet(null, Thread.currentThread()));
                CefFrame frame = browser.getMainFrame();
                assertNotNull(frame);
                try {
                    frame.executeJavaScript(READY_SCRIPT, url_, 1);
                } finally {
                    frame.dispose();
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        final void onTitleChange(CefBrowser browser, String title) {
            if (!READY_TITLE.equals(title) || !readyReceived_.compareAndSet(false, true)) return;
            try {
                assertCallbackContext(browser);
                beginSearches(browser);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        final void assertCallbackContext(CefBrowser browser) {
            assertSame(browser_.get(), browser);
            assertSame(cefUiThread_.get(), Thread.currentThread(), "Find callbacks must use the same CEF UI callback thread as loading callbacks");
        }

        final void assertFindCallbackContext(CefBrowser browser) {
            assertCallbackContext(browser);
            assertTrue(client_.get().getNativeRef("CefFindHandler") != 0);
            findNativeRefObserved_.set(true);
        }

        final CefClient getClient() {
            return client_.get();
        }

        final void complete() {
            if (!finished_.compareAndSet(false, true)) return;
            CefBrowser browser = browser_.get();
            try {
                if (browser != null) browser.stopFinding(true);
            } catch (Throwable failure) {
                callbackFailure_.compareAndSet(null, failure);
            } finally {
                Runnable terminator = terminator_.get();
                if (terminator != null) terminator.run();
            }
        }

        final void fail(Throwable failure) {
            callbackFailure_.compareAndSet(null, failure);
            complete();
        }

        final void assertControllerCompleted() {
            Throwable failure = callbackFailure_.get();
            if (failure != null) throw new AssertionError("Find handler callback failed", failure);
            assertTrue(loadCompleted_.get());
            assertTrue(readyReceived_.get());
            assertTrue(findNativeRefObserved_.get());
            assertTrue(finished_.get());
            awaitNativeRefCleared(client_.get(), "CefFindHandler");
        }

        abstract void registerFindHandlers(CefClient client);

        abstract void beginSearches(CefBrowser browser);
    }

    private static void awaitNativeRefCleared(CefClient client, String identifier) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // TestFrame completion is posted from the application onBeforeClose callback. Native
        // callback unwinding then performs CefClient's terminal cleanup, so immediate OSR shutdown
        // can expose a short, legitimate race here. Wait on the synchronized native-ref state
        // instead of weakening the lifecycle assertion or introducing timing sleeps.
        while (client.getNativeRef(identifier) != 0) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError(identifier + " native reference was not released during terminal cleanup");
            Thread.yield();
        }
    }

    private static final class ResultMatrixController extends FindTestController {
        private final List<FindResult> finalResults_ = new CopyOnWriteArrayList<FindResult>();
        private final AtomicInteger currentSearch_ = new AtomicInteger(-1);
        private final AtomicReference<Integer> currentIdentifier_ = new AtomicReference<Integer>();
        private final CefFindHandler handler_ = new CefFindHandlerAdapter() {
            @Override
            public void onFindResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
                handleFindResult(browser, identifier, count, selectionRect, activeMatchOrdinal, finalUpdate);
            }
        };

        ResultMatrixController(String url) {
            super(url);
        }

        @Override
        void registerFindHandlers(CefClient client) {
            client.addFindHandler(handler_);
        }

        @Override
        void beginSearches(CefBrowser browser) {
            startSearch(browser, 0);
        }

        private void startSearch(CefBrowser browser, int index) {
            currentIdentifier_.set(null);
            currentSearch_.set(index);
            SearchSpec spec = SEARCH_SPECS[index];
            browser.find(spec.text_, true, spec.matchCase_, false);
        }

        private void handleFindResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
            try {
                assertFindCallbackContext(browser);
                assertTrue(identifier >= 0);
                assertTrue(count >= 0);
                assertNotNull(selectionRect);
                assertTrue(activeMatchOrdinal >= 0);

                Integer knownIdentifier = currentIdentifier_.get();
                if (knownIdentifier == null)
                    currentIdentifier_.compareAndSet(null, Integer.valueOf(identifier));
                assertEquals(identifier, currentIdentifier_.get().intValue());
                if (!finalUpdate) return;

                int index = currentSearch_.get();
                finalResults_.add(new FindResult(identifier, count, selectionRect, activeMatchOrdinal, true));
                if (index + 1 < SEARCH_SPECS.length) {
                    startSearch(browser, index + 1);
                } else {
                    complete();
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        void assertCompleted() {
            assertControllerCompleted();
            assertEquals(SEARCH_SPECS.length, finalResults_.size());

            int previousIdentifier = -1;
            for (int index = 0; index < SEARCH_SPECS.length; index++) {
                SearchSpec spec = SEARCH_SPECS[index];
                FindResult result = finalResults_.get(index);
                assertTrue(result.identifier_ > previousIdentifier);
                assertEquals(spec.expectedCount_, result.count_);
                assertTrue(result.finalUpdate_);
                assertNotNull(result.selectionRect_);
                if (result.count_ == 0) {
                    assertEquals(0, result.activeMatchOrdinal_);
                } else {
                    assertTrue(result.activeMatchOrdinal_ < result.count_);
                }
                previousIdentifier = result.identifier_;
            }

            assertEquals(0, finalResults_.get(0).activeMatchOrdinal_);
            assertEquals(0, finalResults_.get(3).activeMatchOrdinal_);
        }
    }

    private static final class HandlerReplacementController extends FindTestController {
        private final AtomicInteger firstCallbacks_ = new AtomicInteger();
        private final AtomicInteger ignoredCallbacks_ = new AtomicInteger();
        private final AtomicInteger replacementCallbacks_ = new AtomicInteger();
        private final AtomicBoolean firstFinalReceived_ = new AtomicBoolean();
        private final AtomicBoolean replacementFinalReceived_ = new AtomicBoolean();
        private final AtomicReference<FindResult> firstResult_ = new AtomicReference<FindResult>();
        private final AtomicReference<FindResult> replacementResult_ =
                new AtomicReference<FindResult>();
        private final CefFindHandler firstHandler_ = this::onFirstResult;
        private final CefFindHandler ignoredHandler_ = this::onIgnoredResult;
        private final CefFindHandler replacementHandler_ = this::onReplacementResult;

        HandlerReplacementController(String url) {
            super(url);
        }

        @Override
        void registerFindHandlers(CefClient client) {
            client.addFindHandler(firstHandler_);
            client.addFindHandler(ignoredHandler_);
        }

        @Override
        void beginSearches(CefBrowser browser) {
            browser.find("FirstDelegateMarker", true, true, false);
        }

        private void onFirstResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
            firstCallbacks_.incrementAndGet();
            try {
                assertFalse(firstFinalReceived_.get(), "The removed first handler received a callback for the replacement search");
                assertFindCallbackContext(browser);
                assertBasicResult(identifier, count, selectionRect, activeMatchOrdinal);
                if (!finalUpdate) return;

                assertTrue(firstFinalReceived_.compareAndSet(false, true));
                firstResult_.set(new FindResult(identifier, count, selectionRect, activeMatchOrdinal, true));
                CefClient client = getClient();
                client.removeFindHandler();
                client.addFindHandler(replacementHandler_);
                browser.find("ReplacementDelegateMarker", true, true, false);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void onIgnoredResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
            ignoredCallbacks_.incrementAndGet();
            fail(new AssertionError("A second addFindHandler call replaced the first handler"));
        }

        private void onReplacementResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
            replacementCallbacks_.incrementAndGet();
            try {
                assertFindCallbackContext(browser);
                assertBasicResult(identifier, count, selectionRect, activeMatchOrdinal);
                if (!finalUpdate) return;

                assertTrue(replacementFinalReceived_.compareAndSet(false, true));
                replacementResult_.set(new FindResult(identifier, count, selectionRect, activeMatchOrdinal, true));
                complete();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private static void assertBasicResult(int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal) {
            assertTrue(identifier >= 0);
            assertTrue(count >= 0);
            assertNotNull(selectionRect);
            assertTrue(activeMatchOrdinal >= 0);
        }

        void assertCompleted() {
            assertControllerCompleted();
            assertTrue(firstCallbacks_.get() > 0);
            assertEquals(0, ignoredCallbacks_.get());
            assertTrue(replacementCallbacks_.get() > 0);
            assertTrue(firstFinalReceived_.get());
            assertTrue(replacementFinalReceived_.get());

            FindResult first = firstResult_.get();
            FindResult replacement = replacementResult_.get();
            assertNotNull(first);
            assertNotNull(replacement);
            assertEquals(1, first.count_);
            assertEquals(0, first.activeMatchOrdinal_);
            assertTrue(first.finalUpdate_);
            assertNotNull(first.selectionRect_);
            assertEquals(1, replacement.count_);
            assertEquals(0, replacement.activeMatchOrdinal_);
            assertTrue(replacement.finalUpdate_);
            assertNotNull(replacement.selectionRect_);
            assertTrue(replacement.identifier_ > first.identifier_);
        }
    }

    private static final class SearchSpec {
        final String text_;
        final boolean matchCase_;
        final int expectedCount_;

        SearchSpec(String text, boolean matchCase, int expectedCount) {
            text_ = text;
            matchCase_ = matchCase;
            expectedCount_ = expectedCount;
        }
    }

    private static final class FindResult {
        final int identifier_;
        final int count_;
        final Rectangle selectionRect_;
        final int activeMatchOrdinal_;
        final boolean finalUpdate_;

        FindResult(int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
            identifier_ = identifier;
            count_ = count;
            selectionRect_ = selectionRect;
            activeMatchOrdinal_ = activeMatchOrdinal;
            finalUpdate_ = finalUpdate;
        }
    }
}
