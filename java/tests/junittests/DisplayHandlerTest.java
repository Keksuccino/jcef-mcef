// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
    private final String firstIconUrl_ = "http://test.com/favicon-first.svg";
    private final String secondIconUrl_ = "http://test.com/favicon-second.svg";
    private final String updatedIconUrl_ = "http://test.com/favicon-updated.svg";
    private final String iconContent_ =
            "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16'></svg>";

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
    void onFaviconURLChange() {
        // Chromium owns favicon-candidate ordering. Record the delivered order, then require the
        // changed candidate to replace the original URL at the same position in the next snapshot.
        AtomicReference<List<String>> initialSnapshot = new AtomicReference<List<String>>();
        AtomicReference<List<String>> initialIconUrls = new AtomicReference<List<String>>();
        AtomicReference<List<String>> expectedUpdatedIconUrls = new AtomicReference<List<String>>();
        AtomicBoolean gotUpdatedCallback = new AtomicBoolean();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onFaviconURLChange(CefBrowser browser, List<String> iconUrls) {
                        try {
                            assertSame(browser_, browser);
                            if (iconUrls.size() == 2 && iconUrls.contains(firstIconUrl_) && iconUrls.contains(secondIconUrl_)) {
                                if (initialSnapshot.compareAndSet(null, iconUrls)) {
                                    initialIconUrls.set(List.copyOf(iconUrls));
                                    expectedUpdatedIconUrls.set(replaceIconUrl(iconUrls, firstIconUrl_, updatedIconUrl_));
                                    browser.executeJavaScript("document.getElementById('first-icon').href='" + updatedIconUrl_ + "';", testUrl_, 1);
                                }
                                return;
                            }
                            List<String> expectedIconUrls = expectedUpdatedIconUrls.get();
                            if (expectedIconUrls == null || !expectedIconUrls.equals(iconUrls) || !gotUpdatedCallback.compareAndSet(false, true)) return;

                            assertEquals(initialIconUrls.get(), initialSnapshot.get());
                            assertNotSame(initialSnapshot.get(), iconUrls);
                            terminateTest();
                        } catch (Throwable failure) {
                            callbackFailure.compareAndSet(null, failure);
                            terminateTest();
                        }
                    }
                });

                addResource(testUrl_, "<html><head><link id='first-icon' rel='icon' href='" + firstIconUrl_ + "'><link rel='icon' href='" + secondIconUrl_ + "'></head><body>Test!</body></html>", "text/html");
                addResource(firstIconUrl_, iconContent_, "image/svg+xml");
                addResource(secondIconUrl_, iconContent_, "image/svg+xml");
                addResource(updatedIconUrl_, iconContent_, "image/svg+xml");
                createBrowser(testUrl_);

                super.setupTest();
            }
        });

        frame.awaitCompletion();
        assertTrue(initialSnapshot.get() != null, "Expected the initial ordered favicon URL snapshot");
        assertCallbackSucceeded(gotUpdatedCallback, callbackFailure);
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

    private static List<String> replaceIconUrl(List<String> iconUrls, String oldUrl, String newUrl) {
        ArrayList<String> updatedIconUrls = new ArrayList<String>(iconUrls);
        int index = updatedIconUrls.indexOf(oldUrl);
        assertTrue(index >= 0);
        updatedIconUrls.set(index, newUrl);
        return List.copyOf(updatedIconUrls);
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
