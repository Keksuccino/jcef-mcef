// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
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

    private static void assertCallbackSucceeded(AtomicBoolean gotCallback, AtomicReference<Throwable> callbackFailure) {
        assertTrue(gotCallback.get());
        Throwable failure = callbackFailure.get();
        if (failure != null) throw new AssertionError("Display handler callback failed", failure);
    }
}
