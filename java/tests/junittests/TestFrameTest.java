// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefRequestContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

// Test the TestFrame implementation.
@NativeCefTest
class TestFrameTest {
    private boolean gotSetupTest_ = false;
    private boolean gotCleanupTest_ = false;
    private boolean gotLoadingStateChange_ = false;

    @Test
    @WindowedCefTest
    void minimal() throws Exception {
        final String testUrl = "http://test.com/test.html";
        TestFrame[] frame = new TestFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new TestFrame() {
                @Override
                protected void setupTest() {
                    assertFalse(gotSetupTest_);
                    gotSetupTest_ = true;

                    addResource(testUrl, "<html><body>Test!</body></html>", "text/html");

                    createBrowser(testUrl);

                    super.setupTest();
                }

                @Override
                protected void cleanupTest() {
                    assertFalse(gotCleanupTest_);
                    gotCleanupTest_ = true;

                    super.cleanupTest();
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                        boolean canGoBack, boolean canGoForward) {
                    if (!isLoading) {
                        assertFalse(gotLoadingStateChange_);
                        gotLoadingStateChange_ = true;
                        // Queue visibility callbacks from AWT immediately before close. On macOS
                        // these callbacks cross to AppKit asynchronously and must tolerate the
                        // browser's native view being destroyed before they are delivered.
                        SwingUtilities.invokeLater(() -> {
                            for (int i = 0; i < 100; ++i) {
                                browser.setWindowVisibility((i & 1) == 0);
                            }
                            terminateTest();
                        });
                    }
                }
            };
        });

        frame[0].awaitCompletion();

        assertTrue(gotSetupTest_);
        assertTrue(gotLoadingStateChange_);
        assertTrue(gotCleanupTest_);
    }

    @Test
    @WindowedCefTest
    void repeatedDevToolsOpenReusesWrapperAndCloseClearsIt() throws Exception {
        final String testUrl = "http://test.com/devtools.html";
        AtomicBoolean openRequested = new AtomicBoolean();
        AtomicInteger devToolsCreated = new AtomicInteger();
        AtomicInteger devToolsClosed = new AtomicInteger();
        TestFrame[] frame = new TestFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new TestFrame() {
                @Override
                protected void setupTest() {
                    addResource(testUrl, "<html><body>DevTools</body></html>", "text/html");
                    createBrowser(testUrl);
                    super.setupTest();
                }

                @Override
                public void onAfterCreated(CefBrowser browser) {
                    super.onAfterCreated(browser);
                    if (browser != browser_) {
                        devToolsCreated.incrementAndGet();
                        SwingUtilities.invokeLater(() -> {
                            browser_.openDevTools();
                            browser_.closeDevTools();
                        });
                    }
                }

                @Override
                public void onBeforeClose(CefBrowser browser) {
                    if (browser != browser_) {
                        devToolsClosed.incrementAndGet();
                        SwingUtilities.invokeLater(this::terminateTest);
                        return;
                    }
                    super.onBeforeClose(browser);
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                        boolean canGoBack, boolean canGoForward) {
                    if (browser == browser_ && !isLoading
                            && openRequested.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater(browser_::openDevTools);
                    }
                }
            };
        });

        frame[0].awaitCompletion();

        assertEquals(1, devToolsCreated.get());
        assertEquals(1, devToolsClosed.get());
    }

    @Test
    void closingPendingDevToolsCreationClosesTheEventualChild() throws Exception {
        final String testUrl = "http://test.com/devtools-pending.html";
        AtomicBoolean openRequested = new AtomicBoolean();
        AtomicInteger devToolsCreated = new AtomicInteger();
        AtomicInteger devToolsClosed = new AtomicInteger();
        TestFrame[] frame = new TestFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new TestFrame() {
                @Override
                protected void setupTest() {
                    addResource(testUrl, "<html><body>Pending DevTools</body></html>", "text/html");
                    browser_ = createOffscreenBrowser(testUrl, null);
                    super.setupTest();
                }

                @Override
                public void onAfterCreated(CefBrowser browser) {
                    super.onAfterCreated(browser);
                    if (browser != browser_) devToolsCreated.incrementAndGet();
                }

                @Override
                public void onBeforeClose(CefBrowser browser) {
                    if (browser != browser_) {
                        devToolsClosed.incrementAndGet();
                        browser_.close(true);
                        return;
                    }
                    super.onBeforeClose(browser);
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                        boolean canGoBack, boolean canGoForward) {
                    if (browser == browser_ && !isLoading
                            && openRequested.compareAndSet(false, true)) {
                        // This callback is on CEF UI. OSR DevTools creation is accepted inline but
                        // OnAfterCreated remains asynchronous, so close exercises the PENDING path.
                        browser_.openDevTools();
                        browser_.closeDevTools();
                    }
                }
            };
        });

        frame[0].awaitCompletion();

        assertEquals(1, devToolsCreated.get());
        assertEquals(1, devToolsClosed.get());
    }

    @Test
    void closingOneBrowserDoesNotDisposeCallerOwnedSharedRequestContext() throws Exception {
        final String firstUrl = "http://test.com/context-first.html";
        final String secondUrl = "http://test.com/context-second.html";
        AtomicBoolean secondCreated = new AtomicBoolean();
        AtomicBoolean firstLoaded = new AtomicBoolean();
        AtomicBoolean firstCloseRequested = new AtomicBoolean();
        AtomicBoolean contextRemainedUsable = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        TestFrame[] frame = new TestFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new TestFrame() {
                private CefRequestContext context_;
                private CefBrowser secondBrowser_;

                @Override
                protected void setupTest() {
                    context_ = CefRequestContext.createContext(null);
                    addResource(firstUrl, "<html><body>First</body></html>", "text/html");
                    addResource(secondUrl, "<html><body>Second</body></html>", "text/html");
                    browser_ = createOffscreenBrowser(firstUrl, context_);
                    secondBrowser_ = createOffscreenBrowser(secondUrl, context_);
                    super.setupTest();
                }

                @Override
                public void onAfterCreated(CefBrowser browser) {
                    super.onAfterCreated(browser);
                    if (browser == secondBrowser_) {
                        secondCreated.set(true);
                        maybeCloseFirstBrowser();
                    }
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                        boolean canGoBack, boolean canGoForward) {
                    if (browser == browser_ && !isLoading) {
                        firstLoaded.set(true);
                        maybeCloseFirstBrowser();
                    }
                }

                private void maybeCloseFirstBrowser() {
                    if (firstLoaded.get() && secondCreated.get()
                            && firstCloseRequested.compareAndSet(false, true))
                        SwingUtilities.invokeLater(() -> browser_.close(true));
                }

                @Override
                public void onBeforeClose(CefBrowser browser) {
                    if (browser == browser_) {
                        // Run after CefClient has forwarded this callback to CefBrowser_N, where
                        // the historical bug disposed the shared wrapper unconditionally.
                        SwingUtilities.invokeLater(() -> {
                            try {
                                context_.getCachePath();
                                contextRemainedUsable.set(true);
                            } catch (Throwable throwable) {
                                failure.compareAndSet(null, throwable);
                            } finally {
                                secondBrowser_.close(true);
                            }
                        });
                        return;
                    }
                    if (browser == secondBrowser_) {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                context_.dispose();
                            } catch (Throwable throwable) {
                                failure.compareAndSet(null, throwable);
                            } finally {
                                dispose();
                                cleanupTest();
                            }
                        });
                        return;
                    }
                    super.onBeforeClose(browser);
                }
            };
        });

        frame[0].awaitCompletion();

        assertTrue(secondCreated.get());
        assertTrue(contextRemainedUsable.get());
        if (failure.get() != null)
            throw new AssertionError(
                    "Shared request context became unusable after closing one browser",
                    failure.get());
    }
}
