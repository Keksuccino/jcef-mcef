// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Test the DisplayHandler implementation.
@NativeCefTest
@WindowedCefTest
class DisplayHandlerTest {
    private final String testUrl_ = "http://test.com/test.html";
    private final String testContent_ =
            "<html><head><title>Test Title</title></head><body>Test!</body></html>";

    @Test
    void onTitleChange() {
        AtomicBoolean gotCallback = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (!gotCallback.compareAndSet(false, true)) return;
                        try {
                            assertEquals("Test Title", title);
                        } catch (Throwable failure) {
                            callbackFailure.compareAndSet(null, failure);
                        } finally {
                            terminateTest();
                        }
                    }
                });

                addResource(testUrl_, testContent_, "text/html");

                createBrowser(testUrl_);

                super.setupTest();
            }
        });

        frame.awaitCompletion();
        assertCallbackSucceeded(gotCallback, callbackFailure);
    }

    @Test
    void onAddressChange() {
        AtomicBoolean gotCallback = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                        if (!gotCallback.compareAndSet(false, true)) return;
                        try {
                            assertEquals(testUrl_, url);
                        } catch (Throwable failure) {
                            callbackFailure.compareAndSet(null, failure);
                        } finally {
                            terminateTest();
                        }
                    }
                });

                addResource(testUrl_, testContent_, "text/html");

                createBrowser(testUrl_);

                super.setupTest();
            }
        });

        frame.awaitCompletion();
        assertCallbackSucceeded(gotCallback, callbackFailure);
    }

    @Test
    void onLoadingProgressChange() {
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onLoadingProgressChange(CefBrowser browser, double progress) {
                        callbackCount.incrementAndGet();
                        try {
                            assertSame(browser_, browser);
                            assertTrue(Double.isFinite(progress), "Loading progress must be finite: " + progress);
                            assertTrue(progress >= 0.0 && progress <= 1.0, "Loading progress must be in [0.0, 1.0]: " + progress);
                        } catch (Throwable failure) {
                            callbackFailure.compareAndSet(null, failure);
                        }
                    }
                });

                addResource(testUrl_, testContent_, "text/html");
                createBrowser(testUrl_);

                super.setupTest();
            }

            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                if (browser == browser_ && !isLoading) terminateTest();
            }
        });

        frame.awaitCompletion();
        assertTrue(callbackCount.get() >= 2, "Expected at least two loading-progress callbacks, got " + callbackCount.get());
        assertCallbackSucceeded(callbackFailure);
    }

    private static void assertCallbackSucceeded(AtomicBoolean gotCallback, AtomicReference<Throwable> callbackFailure) {
        assertTrue(gotCallback.get());
        assertCallbackSucceeded(callbackFailure);
    }

    private static void assertCallbackSucceeded(AtomicReference<Throwable> callbackFailure) {
        Throwable failure = callbackFailure.get();
        if (failure != null) throw new AssertionError("Display handler callback failed", failure);
    }
}
