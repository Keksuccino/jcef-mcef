// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.network.CefRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefFrameTest {
    @Test
    void sourceTextAndOwningBrowserRemainValidAfterFrameWrapperDisposal() {
        final String mainUrl = "http://frame.test/source-main.html";
        final String frameUrl = "http://frame.test/source-frame.html";
        final String frameName = "source-frame";
        final String sourceMarker = "frame-source-marker-151";
        final String textMarker = "Frame text marker 151";
        final String mainContent = "<html><body>Main frame<iframe name=\"" + frameName + "\" src=\""
                + frameUrl + "\"></iframe></body></html>";
        final String frameContent =
                "<html><body><p id=\"" + sourceMarker + "\">" + textMarker + "</p></body></html>";
        AtomicReference<String> source = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        TestFrame testFrame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private final AtomicBoolean requested_ = new AtomicBoolean();
            private final AtomicInteger pendingVisitors_ = new AtomicInteger(2);

            @Override
            protected void setupTest() {
                addResource(mainUrl, mainContent, "text/html");
                addResource(frameUrl, frameContent, "text/html");
                createBrowser(mainUrl);
                super.setupTest();
            }

            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                if (isLoading || !requested_.compareAndSet(false, true)) return;

                CefFrame frame = null;
                try {
                    frame = browser.getFrameByName(frameName);
                    assertNotNull(frame);
                    CefFrame activeFrame = frame;
                    assertFalse(activeFrame.isMain());
                    assertEquals(frameUrl, activeFrame.getURL());
                    assertSame(browser, activeFrame.getBrowser());
                    assertThrows(NullPointerException.class, () -> activeFrame.getSource(null));
                    assertThrows(NullPointerException.class, () -> activeFrame.getText(null));
                    assertThrows(NullPointerException.class, () -> activeFrame.loadRequest(null));
                    assertThrows(NullPointerException.class, () -> activeFrame.loadURL(null));
                    activeFrame.getSource(value -> completeVisitor(source, value));
                    activeFrame.getText(value -> completeVisitor(text, value));
                } catch (Throwable throwable) {
                    failTest(throwable);
                } finally {
                    if (frame != null) frame.dispose();
                }
            }

            private void completeVisitor(AtomicReference<String> destination, String value) {
                destination.set(value);
                if (pendingVisitors_.decrementAndGet() == 0) terminateTest();
            }

            private void failTest(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                terminateTest();
            }
        });

        testFrame.awaitCompletion();
        assertNoFailure(failure);
        assertNotNull(source.get());
        assertNotNull(text.get());
        assertTrue(source.get().contains(sourceMarker));
        assertTrue(text.get().contains(textMarker));
    }

    @Test
    void loadURLAndLoadRequestNavigateTheSelectedFrame() {
        final String mainUrl = "http://frame.test/navigation-main.html";
        final String initialUrl = "http://frame.test/initial-frame.html";
        final String loadUrl = "http://frame.test/load-url.html";
        final String requestUrl = "http://frame.test/load-request.html";
        final String frameName = "navigation-frame";
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        TestFrame testFrame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                String mainContent = "<html><body>Main frame<iframe name=\"" + frameName
                        + "\" src=\"" + initialUrl + "\"></iframe></body></html>";
                addResource(mainUrl, mainContent, "text/html");
                addResource(initialUrl, "<html><body>Initial</body></html>", "text/html");
                addResource(loadUrl, "<html><body>Load URL</body></html>", "text/html");
                addResource(requestUrl, "<html><body>Load request</body></html>", "text/html");
                createBrowser(mainUrl);
                super.setupTest();
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame callbackFrame, int httpStatusCode) {
                if (callbackFrame.isMain() || !frameName.equals(callbackFrame.getName())) return;

                try {
                    String currentUrl = callbackFrame.getURL();
                    if (initialUrl.equals(currentUrl) && stage.compareAndSet(0, 1)) {
                        loadWithURL(browser);
                    } else if (loadUrl.equals(currentUrl) && stage.compareAndSet(1, 2)) {
                        loadWithRequest(browser);
                    } else if (requestUrl.equals(currentUrl) && stage.compareAndSet(2, 3)) {
                        assertMainFrameWasNotNavigated(browser);
                        terminateTest();
                    }
                } catch (Throwable throwable) {
                    failTest(throwable);
                }
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
                failTest(new AssertionError("Unexpected load error " + errorCode + " for " + failedUrl + ": " + errorText));
            }

            private void loadWithURL(CefBrowser browser) {
                CefFrame frame = browser.getFrameByName(frameName);
                assertNotNull(frame);
                try {
                    assertFalse(frame.isMain());
                    assertSame(browser, frame.getBrowser());
                    frame.loadURL(loadUrl);
                } finally {
                    frame.dispose();
                }
            }

            private void loadWithRequest(CefBrowser browser) {
                CefFrame frame = browser.getFrameByName(frameName);
                CefRequest request = CefRequest.create();
                try {
                    assertNotNull(frame);
                    assertNotNull(request);
                    request.setURL(requestUrl);
                    request.setMethod("GET");
                    frame.loadRequest(request);
                } finally {
                    if (request != null) request.dispose();
                    if (frame != null) frame.dispose();
                }
            }

            private void assertMainFrameWasNotNavigated(CefBrowser browser) {
                CefFrame mainFrame = browser.getMainFrame();
                try {
                    assertNotNull(mainFrame);
                    assertEquals(mainUrl, mainFrame.getURL());
                } finally {
                    if (mainFrame != null) mainFrame.dispose();
                }
            }

            private void failTest(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                terminateTest();
            }
        });

        testFrame.awaitCompletion();
        assertNoFailure(failure);
        assertEquals(3, stage.get());
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null)
            throw new AssertionError("CEF frame test failed in a callback", throwable);
    }
}
