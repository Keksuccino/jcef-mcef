// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefFrame;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

@NativeCefTest
class CefTextSelectionChangedNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    private static final int VIEW_WIDTH = 640;
    private static final int VIEW_HEIGHT = 180;
    private static final String FIRST_URL = "http://text-selection.test/first.html";
    private static final String SECOND_URL = "http://text-selection.test/second.html";
    private static final String PAGE_READY_TITLE = "jcef-text-selection:page-ready";
    private static final String SELECTION_CLICK_READY_TITLE = "jcef-text-selection:selection-click-ready";
    private static final String COLLAPSE_CLICK_READY_TITLE = "jcef-text-selection:collapse-click-ready";
    private static final String EXPECTED_TEXT = "\uD83D\uDE00\0\u00E9";
    private static final CefRange EXPECTED_RANGE = new CefRange(1, 5);
    private static final CefRange COLLAPSED_RANGE = new CefRange(5, 5);
    private static final String TEST_CONTENT =
            "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%}textarea{margin:24px;width:360px;height:60px;font:24px sans-serif}</style></head><body><textarea id='selection'></textarea><script>(()=>{const input=document.getElementById('selection');input.value=String.fromCharCode(65,0xD83D,0xDE00,0,0xE9,90);input.addEventListener('click',()=>input.dataset.collapse==='true'?input.setSelectionRange(5,5,'none'):input.setSelectionRange(1,5,'forward'));window.beginTextSelection=()=>{const awaitFocus=()=>{input.focus();if(document.hasFocus()&&document.activeElement===input){requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='"
            + SELECTION_CLICK_READY_TITLE + "';}));return;}requestAnimationFrame(awaitFocus);};awaitFocus();};window.prepareTextSelectionCollapse=()=>{input.dataset.collapse='true';document.title='" + COLLAPSE_CLICK_READY_TITLE + "';};document.title='"
            + PAGE_READY_TITLE + "';})();</script></body></html>";
    private static final Method IS_ON_CEF_UI_THREAD = getCefUiThreadMethod();

    private record CallbackSnapshot(CefBrowser browser, String selectedText, CefRange selectedRange, boolean cefUiThread) {}

    @Test
    void deliversExactOwnedUtf16SelectionsAndIsolatesBrowserHandlersAcrossLifecycle() throws Exception {
        CompletableFuture<CallbackSnapshot> firstSelection = new CompletableFuture<CallbackSnapshot>();
        CompletableFuture<CallbackSnapshot> firstCollapse = new CompletableFuture<CallbackSnapshot>();
        CompletableFuture<CallbackSnapshot> secondSelection = new CompletableFuture<CallbackSnapshot>();
        CompletableFuture<CallbackSnapshot> secondCollapse = new CompletableFuture<CallbackSnapshot>();
        CompletableFuture<Void> secondClosed = new CompletableFuture<Void>();
        AtomicReference<SelectionBrowser> secondBrowser = new AtomicReference<SelectionBrowser>();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                CefDisplayHandlerAdapter displayHandler = new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (!(browser instanceof SelectionBrowser)) return;
                        SelectionBrowser selectionBrowser = (SelectionBrowser) browser;
                        if (PAGE_READY_TITLE.equals(title)) {
                            selectionBrowser.markPageReady();
                        } else if (SELECTION_CLICK_READY_TITLE.equals(title) || COLLAPSE_CLICK_READY_TITLE.equals(title)) {
                            selectionBrowser.clickInput();
                        }
                    }
                };
                client_.addDisplayHandler(displayHandler);
                addResource(FIRST_URL, TEST_CONTENT, "text/html");
                addResource(SECOND_URL, TEST_CONTENT, "text/html");
                browser_ = new SelectionBrowser(client_, FIRST_URL, firstSelection, firstCollapse);
                SelectionBrowser second = new SelectionBrowser(client_, SECOND_URL, secondSelection, secondCollapse);
                SelectionBrowser first = (SelectionBrowser) browser_;
                first.setAfterCollapse(() -> enqueueFocusTransfer(first, second));
                secondBrowser.set(second);
                first.requestSelectionWhenReady();
                browser_.createImmediately();
                second.createImmediately();
                super.setupTest();
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                if (browser == secondBrowser.get()) {
                    secondClosed.complete(null);
                } else {
                    super.onBeforeClose(browser);
                }
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CallbackSnapshot retainedFirstSelection = null;
        CallbackSnapshot retainedFirstCollapse = null;
        CallbackSnapshot retainedSecondSelection = null;
        CallbackSnapshot retainedSecondCollapse = null;
        Throwable failure = null;
        try {
            retainedFirstSelection = await(firstSelection);
            retainedFirstCollapse = await(firstCollapse);
            retainedSecondSelection = await(secondSelection);
            retainedSecondCollapse = await(secondCollapse);
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            SelectionBrowser second = secondBrowser.get();
            if (second != null) {
                second.close(true);
                await(secondClosed);
            }
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        try {
            frame.terminateTest();
            frame.awaitCompletion();
        } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
        }
        if (failure != null) rethrow(failure);

        assertSelectionSnapshot(retainedFirstSelection, frame.browser_);
        assertCollapsedSnapshot(retainedFirstCollapse, frame.browser_);
        assertSelectionSnapshot(retainedSecondSelection, secondBrowser.get());
        assertCollapsedSnapshot(retainedSecondCollapse, secondBrowser.get());

        // Both browsers are closed above. These callback-scoped native values must therefore have
        // been copied into independent Java snapshots before CEF released its selection payloads.
        assertEquals(EXPECTED_TEXT, retainedFirstSelection.selectedText());
        assertEquals(1, retainedFirstSelection.selectedRange().getFrom());
        assertEquals(5, retainedFirstSelection.selectedRange().getTo());
        assertEquals("", retainedFirstCollapse.selectedText());
        assertEquals(COLLAPSED_RANGE, retainedFirstCollapse.selectedRange());
    }

    private static void assertSelectionSnapshot(CallbackSnapshot snapshot, CefBrowser expectedBrowser) {
        assertNotNull(snapshot);
        assertSame(expectedBrowser, snapshot.browser());
        assertEquals(EXPECTED_TEXT, snapshot.selectedText());
        assertEquals(4, snapshot.selectedText().length(), "Java length must count the supplementary character as two UTF-16 code units");
        assertEquals(EXPECTED_RANGE, snapshot.selectedRange());
        assertTrue(snapshot.cefUiThread(), "Text-selection callbacks must execute on CEF's browser-process UI thread");
    }

    private static void assertCollapsedSnapshot(CallbackSnapshot snapshot, CefBrowser expectedBrowser) {
        assertNotNull(snapshot);
        assertSame(expectedBrowser, snapshot.browser());
        assertEquals("", snapshot.selectedText());
        assertEquals(COLLAPSED_RANGE, snapshot.selectedRange());
        assertTrue(snapshot.cefUiThread(), "Collapsed-selection callbacks must execute on CEF's browser-process UI thread");
    }

    private static Throwable collectFailure(Throwable current, Throwable addition) {
        if (current == null) return addition;
        current.addSuppressed(addition);
        return current;
    }

    private static void enqueueFocusTransfer(SelectionBrowser first, SelectionBrowser second) {
        try {
            // OnTextSelectionChanged is a native callback on CEF's UI thread. Defer both sides of
            // the browser focus handoff until that callback has returned instead of reentering CEF
            // from the first browser's collapsed-selection notification.
            SwingUtilities.invokeLater(() -> transferFocus(first, second));
        } catch (Throwable failure) {
            failFocusTransfer(first, second, failure);
        }
    }

    private static void transferFocus(SelectionBrowser first, SelectionBrowser second) {
        try {
            first.setFocus(false);
            second.requestSelectionWhenReady();
        } catch (Throwable failure) {
            failFocusTransfer(first, second, failure);
        }
    }

    private static void failFocusTransfer(SelectionBrowser first, SelectionBrowser second, Throwable failure) {
        AssertionError handoffFailure = new AssertionError("Unable to transfer OSR focus between text-selection browsers", failure);
        first.fail(handoffFailure);
        second.fail(handoffFailure);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError("Unexpected text-selection test failure", failure);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void execute(CefBrowser browser, String script) {
        CefFrame mainFrame = browser.getMainFrame();
        if (mainFrame == null) throw new AssertionError("OSR browser has no main frame during text-selection setup");
        try {
            mainFrame.executeJavaScript(script, browser.getURL(), 1);
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

    private static final class SelectionBrowser extends CefBrowserOsr {
        private final CompletableFuture<CallbackSnapshot> selection_;
        private final CompletableFuture<CallbackSnapshot> collapse_;
        private final AtomicBoolean pageReady_ = new AtomicBoolean();
        private final AtomicBoolean startRequested_ = new AtomicBoolean();
        private final AtomicBoolean selectionRequested_ = new AtomicBoolean();
        private final AtomicBoolean collapseRequested_ = new AtomicBoolean();
        private volatile Runnable afterCollapse_ = () -> {};

        SelectionBrowser(CefClient client, String url, CompletableFuture<CallbackSnapshot> selection, CompletableFuture<CallbackSnapshot> collapse) {
            super(client, url, false, null);
            selection_ = selection;
            collapse_ = collapse;
            updateViewGeometry(0, 0, VIEW_WIDTH, VIEW_HEIGHT, new Point(0, 0));
        }

        void markPageReady() {
            pageReady_.set(true);
            maybeRequestSelection();
        }

        void requestSelectionWhenReady() {
            startRequested_.set(true);
            maybeRequestSelection();
        }

        void setAfterCollapse(Runnable afterCollapse) {
            afterCollapse_ = afterCollapse;
        }

        void clickInput() {
            // CefMouseEvent mirrors GLFW here: action 1 presses, action 0 releases, and button 0 is
            // the left button. Keep the ordered pair because CEF 151 publishes this programmatic
            // textarea selection through its OSR user-input update path.
            sendMouseEvent(new CefMouseEvent(1, 40, 40, 1, 0, CefMouseEvent.BUTTON1_MASK));
            sendMouseEvent(new CefMouseEvent(0, 40, 40, 1, 0, 0));
        }

        private void maybeRequestSelection() {
            if (!pageReady_.get() || !startRequested_.get()
                    || !selectionRequested_.compareAndSet(false, true))
                return;
            try {
                setFocus(true);
                execute(this, "window.beginTextSelection();");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        void fail(Throwable failure) {
            selection_.completeExceptionally(failure);
            collapse_.completeExceptionally(failure);
        }

        @Override
        public void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {
            if (!selectionRequested_.get()) return;
            try {
                if (browser != this) throw new AssertionError("Text-selection callback crossed browser-specific render handlers");
                if (selectedText == null || selectedRange == null) throw new AssertionError("Text-selection snapshots must be non-null");
                if (!selection_.isDone() && EXPECTED_TEXT.equals(selectedText)
                        && EXPECTED_RANGE.equals(selectedRange)) {
                    selection_.complete(new CallbackSnapshot(browser, selectedText, selectedRange, isOnCefUiThread()));
                    collapseRequested_.set(true);
                    execute(this, "window.prepareTextSelectionCollapse();");
                    return;
                }
                if (collapseRequested_.get() && !collapse_.isDone() && selectedText.isEmpty()
                        && COLLAPSED_RANGE.equals(selectedRange)) {
                    if (collapse_.complete(new CallbackSnapshot(browser, selectedText, selectedRange, isOnCefUiThread()))) afterCollapse_.run();
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }
    }
}
