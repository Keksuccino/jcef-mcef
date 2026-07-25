// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@NativeCefTest
class CefBrowserHasDevToolsNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 15;

    @Test
    void offscreenBrowserReportsOnlyItsAssociatedDevToolsFrontend() throws Exception {
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<Boolean> initialUiQuery = new CompletableFuture<Boolean>();
        CompletableFuture<Void> frontendCreated = new CompletableFuture<Void>();
        CompletableFuture<Void> frontendClosed = new CompletableFuture<Void>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                browser_ = createOffscreenBrowser("about:blank", null);
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) {
                    browserCreated.complete(browser);
                    captureUiQuery(browser_, false, initialUiQuery, callbackFailure);
                } else {
                    // CEF publishes the parent/frontend association only after the DevTools
                    // child's OnAfterCreated callback returns. Signal here, then let the worker's
                    // query post behind this callback instead of inferring association too early.
                    frontendCreated.complete(null);
                }
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                if (browser != browser_) {
                    frontendClosed.complete(null);
                    return;
                }
                super.onBeforeClose(browser);
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CefBrowser browser = null;
        Throwable failure = null;
        try {
            browser = await(browserCreated);
            assertFalse(await(initialUiQuery).booleanValue());
            assertFalse(await(browser.hasDevTools()).booleanValue());

            CefDevToolsClient protocolClient = browser.getDevToolsClient();
            assertNotNull(protocolClient);
            await(protocolClient.executeDevToolsMethod("Runtime.enable"));
            assertFalse(await(browser.hasDevTools()).booleanValue(), "A DevTools Protocol client is not an associated frontend");

            browser.openDevTools();
            await(frontendCreated);
            assertTrue(await(browser.hasDevTools()).booleanValue());

            browser.closeDevTools();
            await(frontendClosed);
            assertFalse(await(browser.hasDevTools()).booleanValue());
            assertCallbackSucceeded(callbackFailure);
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            if (browser != null) browser.closeDevTools();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        try {
            frame.terminateTest();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        try {
            frame.awaitCompletion();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        if (failure != null) rethrow(failure);

        assertNotNull(browser);
        assertUnavailable(browser.hasDevTools());
        assertCallbackSucceeded(callbackFailure);
    }

    private static Throwable collectFailure(Throwable current, Throwable addition) {
        if (current == null) return addition;
        current.addSuppressed(addition);
        return current;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError("Unexpected DevTools association test failure", failure);
    }

    private static void captureUiQuery(CefBrowser browser, boolean expected, CompletableFuture<Boolean> snapshot, AtomicReference<Throwable> callbackFailure) {
        try {
            CompletableFuture<Boolean> query = browser.hasDevTools();
            if (!query.isDone()) throw new AssertionError("A DevTools association query from a CEF UI callback must complete directly");
            boolean value = query.join().booleanValue();
            if (value != expected) throw new AssertionError("Expected DevTools association state " + expected + " but was " + value);
            snapshot.complete(Boolean.valueOf(value));
        } catch (Throwable failure) {
            Throwable unwrapped = unwrapCompletionFailure(failure);
            callbackFailure.compareAndSet(null, unwrapped);
            snapshot.completeExceptionally(unwrapped);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static void assertCallbackSucceeded(AtomicReference<Throwable> callbackFailure) {
        Throwable failure = callbackFailure.get();
        if (failure != null) throw new AssertionError("DevTools association callback failed", failure);
    }

    private static void assertUnavailable(CompletableFuture<?> future) {
        assertTrue(future.isDone(), "A post-close DevTools association query must be rejected immediately");
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
