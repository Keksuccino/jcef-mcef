// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.CefColor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.input.CefCompositionUnderline;
import org.cef.input.CefCompositionUnderlineStyle;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@NativeCefTest
class CefImeCompositionRangeChangedNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final int VIEW_WIDTH = 640;
    private static final int VIEW_HEIGHT = 180;
    private static final String TEST_URL = "http://ime-range.test/index.html";
    private static final String PAGE_READY_TITLE = "jcef-ime-range:page-ready";
    private static final String FOCUSED_TITLE = "jcef-ime-range:focused";
    private static final String TEST_CONTENT = "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%}#ime{margin:24px;width:300px;height:40px;font:24px sans-serif}</style></head><body><input id='ime' autocomplete='off'><script>(()=>{const input=document.getElementById('ime');window.focusImeRangeInput=()=>{const awaitFocus=()=>{input.focus();if(document.hasFocus()&&document.activeElement===input){requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='" + FOCUSED_TITLE + "';}));return;}requestAnimationFrame(awaitFocus);};awaitFocus();};document.title='" + PAGE_READY_TITLE + "';})();</script></body></html>";
    private static final CefColor BLACK = CefColor.fromArgb(0xFF000000);
    private static final CefColor TRANSPARENT = CefColor.fromArgb(0x00000000);
    private static final Method IS_ON_CEF_UI_THREAD = getCefUiThreadMethod();

    private record CallbackSnapshot(CefBrowser browser, CefRange selectedRange, Rectangle[] characterBounds, boolean cefUiThread) {}

    @Test
    void deliversOwnedImeRangeAndBoundsSnapshotsOnTheCefUiThread() throws Exception {
        CompletableFuture<CallbackSnapshot> callback = new CompletableFuture<CallbackSnapshot>();
        AtomicBoolean compositionRequested = new AtomicBoolean();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private String processedTitle_ = "";

            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || title == null || title.equals(processedTitle_)) return;
                        processedTitle_ = title;
                        try {
                            if (PAGE_READY_TITLE.equals(title)) {
                                browser.setFocus(true);
                                execute(browser, "window.focusImeRangeInput();");
                            } else if (FOCUSED_TITLE.equals(title) && compositionRequested.compareAndSet(false, true)) {
                                CefCompositionUnderline underline = new CefCompositionUnderline(new CefRange(0, 1), BLACK, TRANSPARENT, false, CefCompositionUnderlineStyle.SOLID);
                                browser.imeSetComposition("か", List.of(underline), CefRange.INVALID, new CefRange(0, 1));
                            }
                        } catch (Throwable failure) {
                            callback.completeExceptionally(failure);
                        }
                    }
                });
                addResource(TEST_URL, TEST_CONTENT, "text/html");
                browser_ = new ImeRangeBrowser(client_, TEST_URL, compositionRequested, callback);
                browser_.createImmediately();
                super.setupTest();
            }
        });

        CallbackSnapshot snapshot = null;
        try {
            snapshot = callback.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            CefBrowser browser = frame.browser_;
            try {
                if (browser != null) browser.imeCancelComposition();
            } finally {
                frame.terminateTest();
                frame.awaitCompletion();
            }
        }

        assertNotNull(snapshot);
        assertSame(frame.browser_, snapshot.browser());
        assertEquals(new CefRange(0, 1), snapshot.selectedRange());
        assertTrue(snapshot.cefUiThread(), "IME range callback must execute on CEF's browser-process UI thread");
        Rectangle[] retainedBounds = snapshot.characterBounds();
        assertNotNull(retainedBounds);
        assertEquals(1, retainedBounds.length);
        Rectangle retainedCharacter = retainedBounds[0];
        assertNotNull(retainedCharacter);
        assertTrue(retainedCharacter.x >= 0 && retainedCharacter.x < VIEW_WIDTH, () -> "Unexpected character x coordinate: " + retainedCharacter);
        assertTrue(retainedCharacter.y >= 0 && retainedCharacter.y < VIEW_HEIGHT, () -> "Unexpected character y coordinate: " + retainedCharacter);
        assertTrue(retainedCharacter.width > 0, () -> "Expected positive BMP character width: " + retainedCharacter);
        assertTrue(retainedCharacter.height > 0, () -> "Expected positive character height: " + retainedCharacter);

        // The browser is closed above, so these reads and mutation occur strictly after CEF has
        // returned from the native callback and released its callback-scoped range/vector values.
        assertEquals(0, snapshot.selectedRange().getFrom());
        assertEquals(1, snapshot.selectedRange().getTo());
        Rectangle original = new Rectangle(retainedCharacter);
        retainedCharacter.translate(1, 1);
        assertEquals(original.x + 1, retainedBounds[0].x);
        assertEquals(original.y + 1, retainedBounds[0].y);
    }

    private static void execute(CefBrowser browser, String script) {
        CefFrame mainFrame = browser.getMainFrame();
        assertNotNull(mainFrame, "OSR browser has no main frame during IME range setup");
        try {
            mainFrame.executeJavaScript(script, TEST_URL, 1);
        } finally {
            mainFrame.dispose();
        }
    }

    private static boolean isOnCefUiThread() {
        try {
            return ((Boolean) IS_ON_CEF_UI_THREAD.invoke(null)).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Unable to invoke CEF UI-thread probe", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("CEF UI-thread probe failed", cause);
        }
    }

    private static Method getCefUiThreadMethod() {
        try {
            Method method = CefBrowser_N.class.getDeclaredMethod("N_IsOnCefUiThreadForTesting");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class ImeRangeBrowser extends CefBrowserOsr {
        private final AtomicBoolean compositionRequested_;
        private final CompletableFuture<CallbackSnapshot> callback_;

        ImeRangeBrowser(CefClient client, String url, AtomicBoolean compositionRequested, CompletableFuture<CallbackSnapshot> callback) {
            super(client, url, false, null);
            compositionRequested_ = compositionRequested;
            callback_ = callback;
            updateViewGeometry(0, 0, VIEW_WIDTH, VIEW_HEIGHT, new Point(0, 0));
        }

        @Override
        public void onImeCompositionRangeChanged(CefBrowser browser, CefRange selectedRange, Rectangle[] characterBounds) {
            if (!compositionRequested_.get() || callback_.isDone()) return;
            callback_.complete(new CallbackSnapshot(browser, selectedRange, characterBounds, isOnCefUiThread()));
        }
    }
}
