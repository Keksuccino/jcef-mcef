// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefBrowserSettings;
import org.cef.CefState;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.junit.jupiter.api.Test;

import java.awt.BorderLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefBrowserSettingsIntegrationTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final int WINDOWLESS_FRAME_RATE = 240;
    private static final String JAVASCRIPT_DISABLED_TITLE = "JAVASCRIPT_DISABLED";
    private static final String JAVASCRIPT_EXECUTED_TITLE = "JAVASCRIPT_EXECUTED";
    private static final String JAVASCRIPT_TEST_URL =
            "http://browser-settings.test/javascript-disabled.html";

    @Test
    void mcefStyleImmediateOsrBrowserPreservesFrameRateAboveSixty() throws Exception {
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                CefBrowserSettings settings = new CefBrowserSettings();
                settings.windowless_frame_rate = WINDOWLESS_FRAME_RATE;
                // MCEF subclasses CefBrowserOsr and invokes createImmediately(), so construct the
                // same browser type directly instead of exercising the Swing OSR factory path.
                browser_ = new CefBrowserOsr(client_, "about:blank", false, null, settings);
                assertNotNull(browser_);
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) browserCreated.complete(browser);
            }
        });

        try {
            CefBrowser browser = await(browserCreated);
            assertEquals(WINDOWLESS_FRAME_RATE, await(browser.getWindowlessFrameRate()));
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    @Test
    @WindowedCefTest
    void rejectedSettingsDoNotPoisonTheClientCreationLifecycle() throws Exception {
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                CefBrowserSettings invalidSettings = new CefBrowserSettings();
                invalidSettings.windowless_frame_rate = -1;
                assertThrows(IllegalArgumentException.class, () -> client_.createBrowser("about:invalid-settings", false, false, null, invalidSettings));
                createBrowser("about:blank");
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) browserCreated.complete(browser);
            }
        });

        try {
            assertNotNull(await(browserCreated));
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    @Test
    @WindowedCefTest
    void windowedBrowserDisablesInlineJavaScriptThroughBrowserSettings() throws Exception {
        CompletableFuture<String> finalTitle = new CompletableFuture<String>();
        AtomicBoolean loadingStopped = new AtomicBoolean();
        AtomicReference<String> loadedUrl = new AtomicReference<String>();
        AtomicReference<String> latestTitle = new AtomicReference<String>();
        AtomicReference<String> documentSource = new AtomicReference<String>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_) return;
                        latestTitle.set(title);
                        completeObservation();
                    }
                });

                String testContent = "<html><head><title>" + JAVASCRIPT_DISABLED_TITLE
                        + "</title></head><body>JavaScript disabled<script>document.body.setAttribute('data-'+'js','executed');document.title='"
                        + JAVASCRIPT_EXECUTED_TITLE + "';</script></body></html>";
                addResource(JAVASCRIPT_TEST_URL, testContent, "text/html");

                CefBrowserSettings settings = new CefBrowserSettings();
                settings.javascript = CefState.DISABLED;
                browser_ = client_.createBrowser(JAVASCRIPT_TEST_URL, false, false, null, settings);
                // Browser wrappers own a validated snapshot; later caller mutations must not race
                // asynchronous native creation or alter its settings.
                settings.javascript = CefState.ENABLED;
                assertNotNull(browser_);
                getContentPane().add(browser_.getUIComponent(), BorderLayout.CENTER);
                pack();
                setSize(800, 600);
                setVisible(true);
                super.setupTest();
            }

            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                if (browser != browser_) return;
                loadingStopped.set(!isLoading);
                if (!isLoading) completeObservation();
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame cefFrame, int httpStatusCode) {
                if (browser != browser_ || !cefFrame.isMain()) return;
                try {
                    loadedUrl.set(cefFrame.getURL());
                    cefFrame.getSource(this::recordDocumentSource);
                } catch (Throwable failure) {
                    finalTitle.completeExceptionally(failure);
                }
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame cefFrame, CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
                if (browser != browser_ || !cefFrame.isMain()) return;
                finalTitle.completeExceptionally(new AssertionError("Unexpected load error " + errorCode + " for " + failedUrl + ": " + errorText));
            }

            private void recordDocumentSource(String source) {
                try {
                    documentSource.set(source);
                    completeObservation();
                } catch (Throwable failure) {
                    finalTitle.completeExceptionally(failure);
                }
            }

            private void completeObservation() {
                try {
                    String title = latestTitle.get();
                    if (loadingStopped.get() && loadedUrl.get() != null
                            && documentSource.get() != null && title != null)
                        finalTitle.complete(title);
                } catch (Throwable failure) {
                    finalTitle.completeExceptionally(failure);
                }
            }
        });

        try {
            assertEquals(JAVASCRIPT_DISABLED_TITLE, await(finalTitle));
            assertEquals(JAVASCRIPT_TEST_URL, loadedUrl.get());
            assertTrue(documentSource.get().contains("<title>" + JAVASCRIPT_DISABLED_TITLE + "</title>"));
            assertFalse(documentSource.get().contains("data-js="));
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
