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
import java.util.concurrent.TimeoutException;
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
    private static final String FOCUS_RELEASED_TITLE = "jcef-text-selection:focus-released";
    private static final String EXPECTED_TEXT = "\uD83D\uDE00\0\u00E9";
    private static final CefRange EXPECTED_RANGE = new CefRange(1, 5);
    private static final CefRange COLLAPSED_RANGE = new CefRange(5, 5);
    private static final String TEST_CONTENT =
            "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%}textarea{margin:24px;width:360px;height:60px;font:24px sans-serif}</style></head><body><textarea id='selection'></textarea><script>(()=>{const input=document.getElementById('selection');input.value=String.fromCharCode(65,0xD83D,0xDE00,0,0xE9,90);input.addEventListener('click',()=>input.dataset.collapse==='true'?input.setSelectionRange(5,5,'none'):input.setSelectionRange(1,5,'forward'));window.beginTextSelection=()=>{const awaitFocus=()=>{input.focus();if(document.hasFocus()&&document.activeElement===input){requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='"
            + SELECTION_CLICK_READY_TITLE + "';}));return;}requestAnimationFrame(awaitFocus);};awaitFocus();};window.prepareTextSelectionCollapse=()=>{input.dataset.collapse='true';document.title='" + COLLAPSE_CLICK_READY_TITLE + "';};window.prepareTextSelectionBlur=()=>{const awaitBlur=()=>{if(!document.hasFocus()){document.title='"
            + FOCUS_RELEASED_TITLE + "';return;}requestAnimationFrame(awaitBlur);};awaitBlur();};document.title='" + PAGE_READY_TITLE + "';})();</script></body></html>";
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
        AtomicReference<FocusTransfer> focusTransfer = new AtomicReference<FocusTransfer>();
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
                            FocusTransfer transfer = focusTransfer.get();
                            if (transfer != null) {
                                transfer.onPageReady(selectionBrowser);
                            } else {
                                AssertionError failure = new AssertionError("Renderer acknowledged a text-selection page before the focus-transfer controller was installed");
                                selectionBrowser.fail(failure);
                                SelectionBrowser second = secondBrowser.get();
                                if (second != null) second.fail(failure);
                            }
                        } else if (FOCUS_RELEASED_TITLE.equals(title)) {
                            FocusTransfer transfer = focusTransfer.get();
                            if (transfer != null) {
                                transfer.onRendererBlurred(selectionBrowser);
                            } else {
                                AssertionError failure = new AssertionError("Renderer acknowledged text-selection blur before the focus-transfer controller was installed");
                                selectionBrowser.fail(failure);
                                SelectionBrowser second = secondBrowser.get();
                                if (second != null) second.fail(failure);
                            }
                        } else if (SELECTION_CLICK_READY_TITLE.equals(title)) {
                            selectionBrowser.enqueueSelectionClick();
                        } else if (COLLAPSE_CLICK_READY_TITLE.equals(title)) {
                            selectionBrowser.enqueueCollapseClick();
                        }
                    }
                };
                client_.addDisplayHandler(displayHandler);
                addResource(FIRST_URL, TEST_CONTENT, "text/html");
                addResource(SECOND_URL, TEST_CONTENT, "text/html");
                browser_ = new SelectionBrowser(client_, FIRST_URL, firstSelection, firstCollapse);
                SelectionBrowser second = new SelectionBrowser(client_, SECOND_URL, secondSelection, secondCollapse);
                SelectionBrowser first = (SelectionBrowser) browser_;
                secondBrowser.set(second);
                FocusTransfer transfer = new FocusTransfer(first, second);
                focusTransfer.set(transfer);
                first.setAfterCollapse(transfer::begin);
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
        Supplier<String> diagnostics = () -> selectionDiagnostics(frame, secondBrowser.get(), focusTransfer.get());

        CallbackSnapshot retainedFirstSelection = null;
        CallbackSnapshot retainedFirstCollapse = null;
        CallbackSnapshot retainedSecondSelection = null;
        CallbackSnapshot retainedSecondCollapse = null;
        Throwable failure = null;
        try {
            retainedFirstSelection = await(firstSelection, "first selection", diagnostics);
            retainedFirstCollapse = await(firstCollapse, "first collapsed selection", diagnostics);
            retainedSecondSelection = await(secondSelection, "second selection", diagnostics);
            retainedSecondCollapse = await(secondCollapse, "second collapsed selection", diagnostics);
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

    private static String selectionDiagnostics(TestFrame frame, SelectionBrowser second, FocusTransfer transfer) {
        SelectionBrowser first = frame.browser_ instanceof SelectionBrowser ? (SelectionBrowser) frame.browser_ : null;
        return "focusTransfer=" + (transfer == null ? "<unavailable>" : transfer.diagnostics()) + ", first=" + (first == null ? "<unavailable>" : first.diagnostics()) + ", second=" + (second == null ? "<unavailable>" : second.diagnostics());
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError("Unexpected text-selection test failure", failure);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> T await(CompletableFuture<T> future, String phase, Supplier<String> diagnostics) throws Exception {
        try {
            return await(future);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Timed out waiting for " + phase + "; " + diagnostics.get(), timeout);
        }
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

    private static final class FocusTransfer {
        private final SelectionBrowser first_;
        private final SelectionBrowser second_;
        private final AtomicBoolean firstPageReady_ = new AtomicBoolean();
        private final AtomicBoolean secondPageReady_ = new AtomicBoolean();
        private final AtomicBoolean initialSelectionRequested_ = new AtomicBoolean();
        private final AtomicBoolean blurQueued_ = new AtomicBoolean();
        private final AtomicBoolean blurDispatched_ = new AtomicBoolean();
        private final AtomicBoolean rendererBlurConfirmed_ = new AtomicBoolean();
        private final AtomicBoolean secondSelectionQueued_ = new AtomicBoolean();
        private final AtomicBoolean secondSelectionDispatched_ = new AtomicBoolean();

        FocusTransfer(SelectionBrowser first, SelectionBrowser second) {
            first_ = first;
            second_ = second;
        }

        void onPageReady(SelectionBrowser browser) {
            if (browser == first_) {
                firstPageReady_.set(true);
            } else if (browser == second_) {
                secondPageReady_.set(true);
            } else {
                fail(new AssertionError("Text-selection readiness was reported by an unknown browser"));
                return;
            }
            if (!firstPageReady_.get() || !secondPageReady_.get() || !initialSelectionRequested_.compareAndSet(false, true)) return;
            // Every OSR initial navigation requests focus. Wait until both renderer documents have
            // acknowledged readiness, then explicitly release the second browser immediately
            // before focusing the first so late navigation focus cannot steal the first click.
            first_.requestSelectionWhenReady(() -> second_.setFocus(false));
        }

        void begin() {
            if (!blurQueued_.compareAndSet(false, true)) return;
            try {
                // OnTextSelectionChanged is a native callback on CEF's UI thread. Return from it
                // before requesting the first browser's asynchronous renderer focus release.
                SwingUtilities.invokeLater(this::dispatchBlur);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        void onRendererBlurred(SelectionBrowser browser) {
            if (browser != first_) {
                fail(new AssertionError("Text-selection focus release was reported by the wrong browser"));
                return;
            }
            if (!rendererBlurConfirmed_.compareAndSet(false, true) || !secondSelectionQueued_.compareAndSet(false, true)) return;
            try {
                // The title proves Blink consumed the first browser's focus loss. Defer once more
                // so the second browser cannot reenter CEF from this title callback.
                SwingUtilities.invokeLater(this::dispatchSecondSelection);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        String diagnostics() {
            return "{firstPageReady=" + firstPageReady_.get() + ", secondPageReady=" + secondPageReady_.get() + ", initialSelectionRequested=" + initialSelectionRequested_.get() + ", blurQueued=" + blurQueued_.get() + ", blurDispatched=" + blurDispatched_.get() + ", rendererBlurConfirmed=" + rendererBlurConfirmed_.get() + ", secondSelectionQueued=" + secondSelectionQueued_.get() + ", secondSelectionDispatched=" + secondSelectionDispatched_.get() + "}";
        }

        private void dispatchBlur() {
            try {
                first_.setFocus(false);
                blurDispatched_.set(true);
                execute(first_, "window.prepareTextSelectionBlur();");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void dispatchSecondSelection() {
            try {
                secondSelectionDispatched_.set(true);
                second_.requestSelectionWhenReady();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void fail(Throwable failure) {
            AssertionError handoffFailure = new AssertionError("Unable to transfer OSR focus between text-selection browsers", failure);
            first_.fail(handoffFailure);
            second_.fail(handoffFailure);
        }
    }

    private static final class SelectionBrowser extends CefBrowserOsr {
        private final String url_;
        private final CompletableFuture<CallbackSnapshot> selection_;
        private final CompletableFuture<CallbackSnapshot> collapse_;
        private final AtomicBoolean pageReady_ = new AtomicBoolean();
        private final AtomicBoolean startRequested_ = new AtomicBoolean();
        private final AtomicBoolean selectionStartQueued_ = new AtomicBoolean();
        private final AtomicBoolean selectionStartDispatched_ = new AtomicBoolean();
        private final AtomicBoolean selectionClickQueued_ = new AtomicBoolean();
        private final AtomicBoolean selectionClickDispatched_ = new AtomicBoolean();
        private final AtomicBoolean collapseRequested_ = new AtomicBoolean();
        private final AtomicBoolean collapseClickQueued_ = new AtomicBoolean();
        private final AtomicBoolean collapseClickDispatched_ = new AtomicBoolean();
        private final AtomicReference<Runnable> beforeSelectionStart_ = new AtomicReference<Runnable>();
        private volatile Runnable afterCollapse_ = () -> {};

        SelectionBrowser(CefClient client, String url, CompletableFuture<CallbackSnapshot> selection, CompletableFuture<CallbackSnapshot> collapse) {
            super(client, url, false, null);
            url_ = url;
            selection_ = selection;
            collapse_ = collapse;
            updateViewGeometry(0, 0, VIEW_WIDTH, VIEW_HEIGHT, new Point(0, 0));
        }

        void markPageReady() {
            pageReady_.set(true);
            maybeEnqueueSelectionStart();
        }

        void requestSelectionWhenReady() {
            requestSelectionWhenReady(() -> {});
        }

        void requestSelectionWhenReady(Runnable beforeSelectionStart) {
            if (!beforeSelectionStart_.compareAndSet(null, beforeSelectionStart)) return;
            startRequested_.set(true);
            maybeEnqueueSelectionStart();
        }

        void setAfterCollapse(Runnable afterCollapse) {
            afterCollapse_ = afterCollapse;
        }

        void enqueueSelectionClick() {
            enqueueClickInput(selectionClickQueued_, selectionClickDispatched_);
        }

        void enqueueCollapseClick() {
            enqueueClickInput(collapseClickQueued_, collapseClickDispatched_);
        }

        private void clickInput() {
            // CefMouseEvent mirrors GLFW here: action 1 presses, action 0 releases, and button 0 is
            // the left button. Keep the ordered pair because CEF 151 publishes this programmatic
            // textarea selection through its OSR user-input update path.
            sendMouseEvent(new CefMouseEvent(1, 40, 40, 1, 0, CefMouseEvent.BUTTON1_MASK));
            sendMouseEvent(new CefMouseEvent(0, 40, 40, 1, 0, 0));
        }

        private void maybeEnqueueSelectionStart() {
            if (!pageReady_.get() || !startRequested_.get()
                    || !selectionStartQueued_.compareAndSet(false, true))
                return;
            // The first page-ready edge is delivered by OnTitleChange on CEF's UI thread. Always
            // return from that native callback before crossing back through SetFocus and renderer
            // JavaScript dispatch. The same hop also keeps the second browser's path identical.
            enqueue(this::dispatchSelectionStart);
        }

        private void dispatchSelectionStart() {
            try {
                selectionStartDispatched_.set(true);
                beforeSelectionStart_.get().run();
                setFocus(true);
                execute(this, "window.beginTextSelection();");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void enqueueClickInput(AtomicBoolean queued, AtomicBoolean dispatched) {
            if (!queued.compareAndSet(false, true)) return;
            // A ready title proves that Blink reached the expected focus/selection phase, but its
            // OnTitleChange callback must still unwind before browser-process input is injected.
            enqueue(() -> dispatchClickInput(dispatched));
        }

        private void dispatchClickInput(AtomicBoolean dispatched) {
            try {
                clickInput();
                dispatched.set(true);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void enqueue(Runnable operation) {
            try {
                SwingUtilities.invokeLater(operation);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        void fail(Throwable failure) {
            selection_.completeExceptionally(failure);
            collapse_.completeExceptionally(failure);
        }

        String diagnostics() {
            return "{url=" + url_ + ", pageReady=" + pageReady_.get() + ", startRequested=" + startRequested_.get() + ", selectionStartQueued=" + selectionStartQueued_.get() + ", selectionStartDispatched=" + selectionStartDispatched_.get() + ", selectionClickQueued=" + selectionClickQueued_.get() + ", selectionClickDispatched=" + selectionClickDispatched_.get() + ", collapseRequested=" + collapseRequested_.get() + ", collapseClickQueued=" + collapseClickQueued_.get() + ", collapseClickDispatched=" + collapseClickDispatched_.get() + ", selectionDone=" + selection_.isDone() + ", collapseDone=" + collapse_.isDone() + "}";
        }

        @Override
        public void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {
            if (!selectionStartDispatched_.get()) return;
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
