// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefBrowserAudioMuteTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;

    @Test
    void windowedAudioMuteCanBeToggledAndRestoredFromOutsideTheCefUiThread() throws Exception {
        assertAudioMuteToggle(false);
    }

    @Test
    void offscreenAudioMuteCanBeToggledAndRestoredFromOutsideTheCefUiThread() throws Exception {
        assertAudioMuteToggle(true);
    }

    private static void assertAudioMuteToggle(boolean offscreen) throws Exception {
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        AtomicReference<Thread> cefUiThread = new AtomicReference<Thread>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                if (offscreen) {
                    browser_ = client_.createBrowser("about:blank", true, false);
                    assertNotNull(browser_);
                    browser_.createImmediately();
                } else {
                    createBrowser("about:blank");
                }
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser != browser_) return;

                cefUiThread.set(Thread.currentThread());
                browserCreated.complete(browser);
            }
        });

        try {
            CefBrowser browser = await(browserCreated);
            assertNotSame(cefUiThread.get(), Thread.currentThread(), "Mute operations must run from the JUnit worker, not the CEF UI callback thread");
            boolean initiallyMuted = await(browser.isAudioMuted());
            try {
                browser.setAudioMuted(!initiallyMuted);
                assertEquals(!initiallyMuted, await(browser.isAudioMuted()));
            } finally {
                browser.setAudioMuted(initiallyMuted);
                assertEquals(initiallyMuted, await(browser.isAudioMuted()), "The test must restore the browser's initial mute state");
            }
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    @Test
    void audioMuteQueryWithoutANativeBrowserCompletesExceptionally() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);

        CompletableFuture<Boolean> result = browser.isAudioMuted();
        assertNotNull(result);
        ExecutionException exception = assertThrows(ExecutionException.class, () -> await(result));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
