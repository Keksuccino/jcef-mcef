// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@NativeCefTest
class CefBrowserFullscreenNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    private static final String ENTER_FULLSCREEN_PARAMETERS = "{\"expression\":\"(async () => { await document.documentElement.requestFullscreen(); return document.fullscreenElement !== null; })()\",\"awaitPromise\":true,\"returnByValue\":true,\"userGesture\":true}";

    private record DirectQuerySnapshot(Thread cefUiThread, boolean fullscreen) {}

    @Test
    @WindowedCefTest
    void windowedBrowserSupportsFullscreenQueriesAndExit() throws Exception {
        assertFullscreenOperations(false);
    }

    @Test
    void offscreenBrowserSupportsFullscreenQueriesAndExit() throws Exception {
        assertFullscreenOperations(true);
    }

    private static void assertFullscreenOperations(boolean offscreen) throws Exception {
        String testUrl = offscreen ? "http://fullscreen-osr.test/index.html"
                                   : "http://fullscreen-windowed.test/index.html";
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<DirectQuerySnapshot> initialDirectQuery = new CompletableFuture<DirectQuerySnapshot>();
        CompletableFuture<DirectQuerySnapshot> enteredDirectQuery = new CompletableFuture<DirectQuerySnapshot>();
        CompletableFuture<DirectQuerySnapshot> exitedDirectQuery = new CompletableFuture<DirectQuerySnapshot>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                CefDisplayHandlerAdapter displayHandler = new CefDisplayHandlerAdapter() {
                    @Override
                    public void onFullscreenModeChange(CefBrowser browser, boolean fullscreen) {
                        if (browser != browser_) return;
                        try {
                            DirectQuerySnapshot snapshot = captureDirectQuery(browser, fullscreen);
                            if (fullscreen) {
                                enteredDirectQuery.complete(snapshot);
                            } else if (enteredDirectQuery.isDone()) {
                                exitedDirectQuery.complete(snapshot);
                            }
                        } catch (Throwable failure) {
                            recordCallbackFailure(callbackFailure, enteredDirectQuery, exitedDirectQuery, failure);
                        }
                    }
                };
                client_.addDisplayHandler(displayHandler);
                addResource(testUrl, "<html><body>Fullscreen bridge test</body></html>", "text/html");
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
                if (browser != browser_ || !frame.isMain() || initialDirectQuery.isDone()) return;
                try {
                    initialDirectQuery.complete(captureDirectQuery(browser, false));
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, unwrapCompletionFailure(failure));
                    initialDirectQuery.completeExceptionally(unwrapCompletionFailure(failure));
                }
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CefBrowser browser = null;
        try {
            browser = await(browserCreated);
            DirectQuerySnapshot initial = await(initialDirectQuery);
            assertFalse(initial.fullscreen());
            assertNotSame(initial.cefUiThread(), Thread.currentThread(), "Posted checks must run outside the CEF UI callback thread");
            assertFalse(await(browser.isFullscreen()).booleanValue());

            CefDevToolsClient devToolsClient = browser.getDevToolsClient();
            assertNotNull(devToolsClient);
            String evaluateResult = await(devToolsClient.executeDevToolsMethod("Runtime.evaluate", ENTER_FULLSCREEN_PARAMETERS));
            assertTrue(evaluateResult.contains("\"value\":true"), "Fullscreen request did not resolve true: " + evaluateResult);

            DirectQuerySnapshot entered = await(enteredDirectQuery);
            assertSame(initial.cefUiThread(), entered.cefUiThread());
            assertTrue(entered.fullscreen());
            assertTrue(await(browser.isFullscreen()).booleanValue());

            // This harness keeps the browser view dimensions unchanged while leaving renderer
            // fullscreen, so the resize hint must be false for the transition itself.
            browser.exitFullscreen(false);
            DirectQuerySnapshot exited = await(exitedDirectQuery);
            assertSame(initial.cefUiThread(), exited.cefUiThread());
            assertFalse(exited.fullscreen());
            assertFalse(await(browser.isFullscreen()).booleanValue());

            // Repeating the command while already outside fullscreen is a defined no-op. Use the
            // opposite resize hint so both boolean JNI boundaries are exercised in each mode.
            browser.exitFullscreen(true);
            assertFalse(await(browser.isFullscreen()).booleanValue());
            assertCallbackSucceeded(callbackFailure);
        } finally {
            if (browser != null) browser.exitFullscreen(false);
            frame.terminateTest();
            frame.awaitCompletion();
        }

        assertNotNull(browser);
        assertUnavailable(browser.isFullscreen());
        browser.exitFullscreen(false);
        browser.exitFullscreen(true);
        assertCallbackSucceeded(callbackFailure);
    }

    private static DirectQuerySnapshot captureDirectQuery(CefBrowser browser, boolean expectedFullscreen) {
        CompletableFuture<Boolean> query = browser.isFullscreen();
        if (!query.isDone()) throw new AssertionError("A fullscreen query from a CEF UI callback must complete directly");
        boolean fullscreen = query.join().booleanValue();
        assertEquals(expectedFullscreen, fullscreen);
        return new DirectQuerySnapshot(Thread.currentThread(), fullscreen);
    }

    private static void recordCallbackFailure(AtomicReference<Throwable> callbackFailure, CompletableFuture<?> enteredDirectQuery, CompletableFuture<?> exitedDirectQuery, Throwable failure) {
        Throwable unwrapped = unwrapCompletionFailure(failure);
        callbackFailure.compareAndSet(null, unwrapped);
        enteredDirectQuery.completeExceptionally(unwrapped);
        exitedDirectQuery.completeExceptionally(unwrapped);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static void assertCallbackSucceeded(AtomicReference<Throwable> callbackFailure) {
        Throwable failure = callbackFailure.get();
        if (failure != null) throw new AssertionError("Fullscreen callback failed", failure);
    }

    private static void assertUnavailable(CompletableFuture<?> future) {
        assertNotNull(future);
        assertTrue(future.isDone(), "A post-close fullscreen query must be rejected immediately");
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
