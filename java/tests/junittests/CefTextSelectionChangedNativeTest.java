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
import org.cef.browser.CefPaintElementType;
import org.cef.browser.CefPaintEvent;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final String SELECTION_PAINT_READY_TITLE =
            "jcef-text-selection:selection-paint-ready";
    private static final String SELECTION_MOVE_ACK_TITLE = "jcef-text-selection:selection-move-ack";
    private static final String SELECTION_CLICK_APPLIED_TITLE =
            "jcef-text-selection:selection-click-applied";
    private static final String COLLAPSE_PAINT_READY_TITLE =
            "jcef-text-selection:collapse-paint-ready";
    private static final String COLLAPSE_MOVE_ACK_TITLE = "jcef-text-selection:collapse-move-ack";
    private static final String COLLAPSE_CLICK_APPLIED_TITLE =
            "jcef-text-selection:collapse-click-applied";
    private static final String FOCUS_RELEASED_TITLE = "jcef-text-selection:focus-released";
    private static final int SELECTION_INPUT_X = 40;
    private static final int COLLAPSE_INPUT_X = 56;
    private static final int INPUT_Y = 40;
    private static final int SENTINEL_SAMPLE_INSET = 8;
    private static final int SELECTION_SENTINEL_RED = 17;
    private static final int SELECTION_SENTINEL_GREEN = 34;
    private static final int SELECTION_SENTINEL_BLUE = 51;
    private static final int COLLAPSE_SENTINEL_RED = 68;
    private static final int COLLAPSE_SENTINEL_GREEN = 85;
    private static final int COLLAPSE_SENTINEL_BLUE = 102;
    private static final String SELECTION_SENTINEL_CSS = "rgb(" + SELECTION_SENTINEL_RED + ","
            + SELECTION_SENTINEL_GREEN + "," + SELECTION_SENTINEL_BLUE + ")";
    private static final String COLLAPSE_SENTINEL_CSS = "rgb(" + COLLAPSE_SENTINEL_RED + ","
            + COLLAPSE_SENTINEL_GREEN + "," + COLLAPSE_SENTINEL_BLUE + ")";
    private static final long SELECTION_SENTINEL_BGRA =
            toBgra(SELECTION_SENTINEL_RED, SELECTION_SENTINEL_GREEN, SELECTION_SENTINEL_BLUE);
    private static final long COLLAPSE_SENTINEL_BGRA =
            toBgra(COLLAPSE_SENTINEL_RED, COLLAPSE_SENTINEL_GREEN, COLLAPSE_SENTINEL_BLUE);
    private static final String EXPECTED_TEXT = "\uD83D\uDE00\0\u00E9";
    private static final CefRange EXPECTED_RANGE = new CefRange(1, 5);
    private static final CefRange COLLAPSED_RANGE = new CefRange(5, 5);
    private static final String TEST_CONTENT =
            "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%;background:rgb(1,2,3)}textarea{margin:24px;width:360px;height:60px;font:24px sans-serif}</style></head><body><textarea id='selection'></textarea><script>(()=>{const input=document.getElementById('selection');const selectionPhase='selection',collapsePhase='collapse';input.value=String.fromCharCode(65,0xD83D,0xDE00,0,0xE9,90);const armPhase=(phase,color,title)=>{input.dataset.phase=phase;document.body.style.backgroundColor=color;requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title=title;}));};input.addEventListener('mousemove',event=>{if(!event.isTrusted)return;const phase=input.dataset.phase;if(phase===selectionPhase&&event.clientX==="
            + SELECTION_INPUT_X + "&&event.clientY===" + INPUT_Y + ")document.title='"
            + SELECTION_MOVE_ACK_TITLE
            + "';else if(phase===collapsePhase&&event.clientX===" + COLLAPSE_INPUT_X
            + "&&event.clientY===" + INPUT_Y + ")document.title='" + COLLAPSE_MOVE_ACK_TITLE
            + "';});input.addEventListener('click',event=>{if(!event.isTrusted||event.button!==0)return;const phase=input.dataset.phase;if(phase===selectionPhase&&event.clientX==="
            + SELECTION_INPUT_X + "&&event.clientY===" + INPUT_Y
            + "){input.setSelectionRange(1,5,'forward');document.title='"
            + SELECTION_CLICK_APPLIED_TITLE + "';}else if(phase===collapsePhase&&event.clientX==="
            + COLLAPSE_INPUT_X + "&&event.clientY===" + INPUT_Y
            + "){input.setSelectionRange(5,5,'none');document.title='"
            + COLLAPSE_CLICK_APPLIED_TITLE
            + "';}});window.beginTextSelection=()=>{const awaitFocus=()=>{input.focus();if(document.hasFocus()&&document.activeElement===input){armPhase(selectionPhase,'"
            + SELECTION_SENTINEL_CSS + "','" + SELECTION_PAINT_READY_TITLE
            + "');return;}requestAnimationFrame(awaitFocus);};awaitFocus();};window.prepareTextSelectionCollapse=()=>{armPhase(collapsePhase,'"
            + COLLAPSE_SENTINEL_CSS + "','" + COLLAPSE_PAINT_READY_TITLE
            + "');};window.prepareTextSelectionBlur=()=>{const awaitBlur=()=>{if(!document.hasFocus()){document.title='"
            + FOCUS_RELEASED_TITLE
            + "';return;}requestAnimationFrame(awaitBlur);};awaitBlur();};document.title='"
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
                        } else {
                            selectionBrowser.handleInputTitle(title);
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
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                if (browser == secondBrowser.get()) {
                    FocusTransfer transfer = focusTransfer.get();
                    if (transfer != null) transfer.onSecondCreated(browser);
                }
                super.onAfterCreated(browser);
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
            FocusTransfer transfer = focusTransfer.get();
            if (transfer != null && await(transfer.cancelPendingSecondCreationAndCloseStartedBrowser(), "second-browser cleanup handoff", diagnostics).booleanValue()) await(secondClosed);
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

    private static long toBgra(int red, int green, int blue) {
        return blue | (long) green << 8 | (long) red << 16 | 0xFFL << 24;
    }

    private static long sampleSentinelPixel(CefPaintEvent event) {
        int sampleX = event.getWidth() - SENTINEL_SAMPLE_INSET;
        int sampleY = event.getHeight() - SENTINEL_SAMPLE_INSET;
        if (sampleX < 0 || sampleY < 0) return -1;
        ByteBuffer frame = event.getRenderedFrame();
        if (frame == null) return -1;
        // Sample relative to the actual backing frame so this remains below the textarea even if
        // OSR pixel density changes. The opaque body makes the exact BGRA value deterministic.
        long offset = ((long) sampleY * event.getWidth() + sampleX) * 4;
        if (offset < 0 || offset + 4 > frame.limit()) return -1;
        int index = (int) offset;
        return Byte.toUnsignedLong(frame.get(index)) | Byte.toUnsignedLong(frame.get(index + 1)) << 8 | Byte.toUnsignedLong(frame.get(index + 2)) << 16 | Byte.toUnsignedLong(frame.get(index + 3)) << 24;
    }

    private static String formatPixel(long pixel) {
        if (pixel < 0) return "<unavailable>";
        String hex = Long.toHexString(pixel).toUpperCase();
        return "0x" + "0".repeat(8 - hex.length()) + hex;
    }

    private static String formatText(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\0", "\\0").replace("\r", "\\r").replace("\n", "\\n");
        return "{utf16Length=" + text.length() + ", value=\"" + escaped + "\"}";
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
        private final AtomicBoolean firstSelectionQueued_ = new AtomicBoolean();
        private final AtomicBoolean firstSelectionDispatched_ = new AtomicBoolean();
        private final AtomicBoolean blurQueued_ = new AtomicBoolean();
        private final AtomicBoolean blurDispatched_ = new AtomicBoolean();
        private final AtomicBoolean rendererBlurConfirmed_ = new AtomicBoolean();
        private final AtomicBoolean secondCreationQueued_ = new AtomicBoolean();
        private final AtomicBoolean secondCreationDispatched_ = new AtomicBoolean();
        private final AtomicBoolean secondCreationReturned_ = new AtomicBoolean();
        private final AtomicBoolean secondCreationCancelled_ = new AtomicBoolean();
        private final AtomicBoolean secondCreated_ = new AtomicBoolean();
        private final AtomicBoolean secondCloseQueued_ = new AtomicBoolean();
        private final AtomicBoolean secondCloseDispatched_ = new AtomicBoolean();
        private final AtomicBoolean secondSelectionQueued_ = new AtomicBoolean();
        private final AtomicBoolean secondSelectionDispatched_ = new AtomicBoolean();

        FocusTransfer(SelectionBrowser first, SelectionBrowser second) {
            first_ = first;
            second_ = second;
        }

        void onPageReady(SelectionBrowser browser) {
            if (browser == first_) {
                firstPageReady_.set(true);
                if (!firstSelectionQueued_.compareAndSet(false, true)) return;
                try {
                    SwingUtilities.invokeLater(this::dispatchFirstSelection);
                } catch (Throwable failure) {
                    fail(failure);
                }
            } else if (browser == second_) {
                secondPageReady_.set(true);
                if (!secondCreationDispatched_.get()) {
                    fail(new AssertionError("The second text-selection renderer became ready before its sequenced creation"));
                    return;
                }
                if (!secondSelectionQueued_.compareAndSet(false, true)) return;
                try {
                    SwingUtilities.invokeLater(this::dispatchSecondSelection);
                } catch (Throwable failure) {
                    fail(failure);
                }
            } else {
                fail(new AssertionError("Text-selection readiness was reported by an unknown browser"));
            }
        }

        void begin() {
            if (!blurQueued_.compareAndSet(false, true)) return;
            try {
                // Phase completion is already deferred beyond both native acknowledgements. Keep
                // the focus release on a further EDT turn so completion and blur never reenter CEF.
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
            if (!rendererBlurConfirmed_.compareAndSet(false, true) || !secondCreationQueued_.compareAndSet(false, true)) return;
            try {
                // The title proves Blink consumed the first browser's focus loss. Only now create
                // the second OSR browser, after returning from this native title callback, so its
                // initial focus request cannot race the first browser's selection input.
                SwingUtilities.invokeLater(this::dispatchSecondCreation);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        void onSecondCreated(CefBrowser browser) {
            if (browser != second_) {
                fail(new AssertionError("Text-selection creation was reported by the wrong second browser"));
                return;
            }
            secondCreated_.set(true);
            if (!secondCreationCancelled_.get()) return;
            try {
                enqueueSecondClose();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        CompletableFuture<Boolean> cancelPendingSecondCreationAndCloseStartedBrowser() {
            CompletableFuture<Boolean> completion = new CompletableFuture<Boolean>();
            if (SwingUtilities.isEventDispatchThread()) {
                cancelPendingSecondCreationAndCloseStartedBrowserOnEdt(completion);
                return completion;
            }
            try {
                SwingUtilities.invokeLater(() -> cancelPendingSecondCreationAndCloseStartedBrowserOnEdt(completion));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
            return completion;
        }

        String diagnostics() {
            return "{firstPageReady=" + firstPageReady_.get() + ", secondPageReady=" + secondPageReady_.get() + ", firstSelectionQueued=" + firstSelectionQueued_.get() + ", firstSelectionDispatched=" + firstSelectionDispatched_.get() + ", blurQueued=" + blurQueued_.get() + ", blurDispatched=" + blurDispatched_.get() + ", rendererBlurConfirmed=" + rendererBlurConfirmed_.get() + ", secondCreationQueued=" + secondCreationQueued_.get() + ", secondCreationDispatched=" + secondCreationDispatched_.get() + ", secondCreationReturned=" + secondCreationReturned_.get() + ", secondCreationCancelled=" + secondCreationCancelled_.get() + ", secondCreated=" + secondCreated_.get() + ", secondCloseQueued=" + secondCloseQueued_.get() + ", secondCloseDispatched=" + secondCloseDispatched_.get() + ", secondSelectionQueued=" + secondSelectionQueued_.get() + ", secondSelectionDispatched=" + secondSelectionDispatched_.get() + "}";
        }

        private void dispatchFirstSelection() {
            try {
                firstSelectionDispatched_.set(true);
                first_.requestSelectionWhenReady();
            } catch (Throwable failure) {
                fail(failure);
            }
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

        private void dispatchSecondCreation() {
            try {
                if (secondCreationCancelled_.get()) return;
                secondCreationDispatched_.set(true);
                second_.createImmediately();
                secondCreationReturned_.set(true);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void cancelPendingSecondCreationAndCloseStartedBrowserOnEdt(CompletableFuture<Boolean> completion) {
            try {
                // Creation and cancellation are serialized on the EDT. A queued creation observes
                // cancellation, while an accepted request is closed on a later EDT turn.
                secondCreationCancelled_.set(true);
                if (secondCreationReturned_.get() || secondCreated_.get()) enqueueSecondClose();
                completion.complete(Boolean.valueOf(secondCreated_.get()));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
                fail(failure);
            }
        }

        private void enqueueSecondClose() {
            if (!secondCloseQueued_.compareAndSet(false, true)) return;
            try {
                // onAfterCreated is a native callback. Always close from a later EDT turn so CEF's
                // creation callback can release its native ownership before close re-enters CEF.
                SwingUtilities.invokeLater(this::dispatchSecondClose);
            } catch (RuntimeException | Error failure) {
                secondCloseQueued_.set(false);
                throw failure;
            }
        }

        private void dispatchSecondClose() {
            try {
                secondCloseDispatched_.set(true);
                second_.close(true);
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

    private static final class InputPhase {
        private final String name_;
        private final String paintReadyTitle_;
        private final String moveAckTitle_;
        private final String clickAppliedTitle_;
        private final long sentinelBgra_;
        private final int inputX_;
        private final int inputY_;
        private final String expectedText_;
        private final CefRange expectedRange_;
        private final CompletableFuture<CallbackSnapshot> completion_;
        private final AtomicBoolean rendererPaintReady_ = new AtomicBoolean();
        private final AtomicBoolean invalidationQueued_ = new AtomicBoolean();
        private final AtomicBoolean invalidationDispatched_ = new AtomicBoolean();
        private final AtomicBoolean sentinelPaintAcknowledged_ = new AtomicBoolean();
        private final AtomicBoolean moveQueued_ = new AtomicBoolean();
        private final AtomicBoolean moveDispatched_ = new AtomicBoolean();
        private final AtomicBoolean moveAcknowledged_ = new AtomicBoolean();
        private final AtomicBoolean clickQueued_ = new AtomicBoolean();
        private final AtomicBoolean clickDispatched_ = new AtomicBoolean();
        private final AtomicBoolean clickAcknowledged_ = new AtomicBoolean();
        private final AtomicBoolean expectedCallbackReceived_ = new AtomicBoolean();
        private final AtomicBoolean completionQueued_ = new AtomicBoolean();
        private final AtomicBoolean completionDispatched_ = new AtomicBoolean();
        private final AtomicInteger candidatePaints_ = new AtomicInteger();
        private final AtomicReference<String> lastPaintSize_ =
                new AtomicReference<String>("<none>");
        private final AtomicLong lastPaintPixel_ = new AtomicLong(-1);
        private final AtomicReference<CallbackSnapshot> expectedCallback_ =
                new AtomicReference<CallbackSnapshot>();

        InputPhase(String name, String paintReadyTitle, String moveAckTitle, String clickAppliedTitle, long sentinelBgra, int inputX, int inputY, String expectedText, CefRange expectedRange, CompletableFuture<CallbackSnapshot> completion) {
            name_ = name;
            paintReadyTitle_ = paintReadyTitle;
            moveAckTitle_ = moveAckTitle;
            clickAppliedTitle_ = clickAppliedTitle;
            sentinelBgra_ = sentinelBgra;
            inputX_ = inputX;
            inputY_ = inputY;
            expectedText_ = expectedText;
            expectedRange_ = expectedRange;
            completion_ = completion;
        }

        boolean acknowledgeSentinelPaint(CefPaintEvent event, long sampledPixel) {
            if (!rendererPaintReady_.get() || sentinelPaintAcknowledged_.get()) return false;
            candidatePaints_.incrementAndGet();
            lastPaintSize_.set(event.getWidth() + "x" + event.getHeight());
            lastPaintPixel_.set(sampledPixel);
            return sampledPixel == sentinelBgra_
                    && sentinelPaintAcknowledged_.compareAndSet(false, true);
        }

        boolean accepts(String selectedText, CefRange selectedRange) {
            return expectedText_.equals(selectedText) && expectedRange_.equals(selectedRange);
        }

        String diagnostics() {
            return "{name=" + name_ + ", rendererPaintReady=" + rendererPaintReady_.get()
                    + ", invalidationQueued=" + invalidationQueued_.get()
                    + ", invalidationDispatched=" + invalidationDispatched_.get()
                    + ", sentinelPaintAcknowledged=" + sentinelPaintAcknowledged_.get()
                    + ", candidatePaints=" + candidatePaints_.get() + ", lastPaint="
                    + lastPaintSize_.get() + "/" + formatPixel(lastPaintPixel_.get())
                    + ", expectedSentinel=" + formatPixel(sentinelBgra_) + ", moveQueued="
                    + moveQueued_.get() + ", moveDispatched=" + moveDispatched_.get()
                    + ", moveAcknowledged=" + moveAcknowledged_.get() + ", clickQueued="
                    + clickQueued_.get() + ", clickDispatched=" + clickDispatched_.get()
                    + ", clickAcknowledged=" + clickAcknowledged_.get()
                    + ", expectedCallbackReceived=" + expectedCallbackReceived_.get()
                    + ", completionQueued=" + completionQueued_.get() + ", completionDispatched="
                    + completionDispatched_.get() + ", done=" + completion_.isDone() + "}";
        }
    }

    private static final class SelectionBrowser extends CefBrowserOsr {
        private final String url_;
        private final CompletableFuture<CallbackSnapshot> selection_;
        private final CompletableFuture<CallbackSnapshot> collapse_;
        private final InputPhase selectionPhase_;
        private final InputPhase collapsePhase_;
        private final AtomicBoolean pageReady_ = new AtomicBoolean();
        private final AtomicBoolean startRequested_ = new AtomicBoolean();
        private final AtomicBoolean selectionStartQueued_ = new AtomicBoolean();
        private final AtomicBoolean selectionStartDispatched_ = new AtomicBoolean();
        private final AtomicBoolean collapseRequested_ = new AtomicBoolean();
        private final AtomicInteger popupPaints_ = new AtomicInteger();
        private final AtomicInteger callbackCount_ = new AtomicInteger();
        private final AtomicReference<String> lastCallbackText_ =
                new AtomicReference<String>("<none>");
        private final AtomicReference<String> lastCallbackRange_ =
                new AtomicReference<String>("<none>");
        private final AtomicReference<Runnable> beforeSelectionStart_ =
                new AtomicReference<Runnable>();
        private volatile Runnable afterCollapse_ = () -> {};

        SelectionBrowser(CefClient client, String url, CompletableFuture<CallbackSnapshot> selection, CompletableFuture<CallbackSnapshot> collapse) {
            super(client, url, false, null);
            url_ = url;
            selection_ = selection;
            collapse_ = collapse;
            selectionPhase_ = new InputPhase("selection", SELECTION_PAINT_READY_TITLE, SELECTION_MOVE_ACK_TITLE, SELECTION_CLICK_APPLIED_TITLE, SELECTION_SENTINEL_BGRA, SELECTION_INPUT_X, INPUT_Y, EXPECTED_TEXT, EXPECTED_RANGE, selection);
            collapsePhase_ = new InputPhase("collapse", COLLAPSE_PAINT_READY_TITLE, COLLAPSE_MOVE_ACK_TITLE, COLLAPSE_CLICK_APPLIED_TITLE, COLLAPSE_SENTINEL_BGRA, COLLAPSE_INPUT_X, INPUT_Y, "", COLLAPSED_RANGE, collapse);
            updateViewGeometry(0, 0, VIEW_WIDTH, VIEW_HEIGHT, new Point(0, 0));
            addOnPaintListener(this::handlePaint);
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

        void handleInputTitle(String title) {
            try {
                if (selectionPhase_.paintReadyTitle_.equals(title))
                    acknowledgePaintReady(selectionPhase_);
                else if (collapsePhase_.paintReadyTitle_.equals(title))
                    acknowledgePaintReady(collapsePhase_);
                else if (selectionPhase_.moveAckTitle_.equals(title))
                    acknowledgeMove(selectionPhase_);
                else if (collapsePhase_.moveAckTitle_.equals(title))
                    acknowledgeMove(collapsePhase_);
                else if (selectionPhase_.clickAppliedTitle_.equals(title))
                    acknowledgeClick(selectionPhase_);
                else if (collapsePhase_.clickAppliedTitle_.equals(title))
                    acknowledgeClick(collapsePhase_);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void acknowledgePaintReady(InputPhase phase) {
            if (!phase.rendererPaintReady_.compareAndSet(false, true)
                    || !phase.invalidationQueued_.compareAndSet(false, true))
                return;
            // A renderer title can precede compositor presentation. Force a later view paint only
            // after this native title callback has returned, then accept only the exact phase
            // color.
            enqueue(() -> dispatchInvalidation(phase));
        }

        private void dispatchInvalidation(InputPhase phase) {
            try {
                phase.invalidationDispatched_.set(true);
                invalidate(CefPaintElementType.PET_VIEW);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void handlePaint(CefPaintEvent event) {
            try {
                if (event.getBrowser() != this) throw new AssertionError("Text-selection paint crossed browser-specific render handlers");
                if (event.getPopup()) {
                    popupPaints_.incrementAndGet();
                    return;
                }
                // The frame is CEF-owned and valid only during this callback. Sample it here, but
                // defer all browser input until a later EDT turn after the native callback unwinds.
                long sampledPixel = sampleSentinelPixel(event);
                if (selectionPhase_.acknowledgeSentinelPaint(event, sampledPixel))
                    enqueueMove(selectionPhase_);
                if (collapsePhase_.acknowledgeSentinelPaint(event, sampledPixel))
                    enqueueMove(collapsePhase_);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void enqueueMove(InputPhase phase) {
            if (!phase.moveQueued_.compareAndSet(false, true)) return;
            enqueue(() -> dispatchMove(phase));
        }

        private void dispatchMove(InputPhase phase) {
            try {
                phase.moveDispatched_.set(true);
                sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, phase.inputX_, phase.inputY_, 0, 0, 0));
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void acknowledgeMove(InputPhase phase) {
            if (!phase.sentinelPaintAcknowledged_.get()) throw new AssertionError("Renderer acknowledged " + phase.name_ + " mouse movement before the exact sentinel paint");
            if (!phase.moveAcknowledged_.compareAndSet(false, true)
                    || !phase.clickQueued_.compareAndSet(false, true))
                return;
            // The trusted renderer mousemove is the input-routing barrier. Return from its title
            // callback before injecting the ordered press/release pair on a later EDT turn.
            enqueue(() -> dispatchClick(phase));
        }

        private void dispatchClick(InputPhase phase) {
            try {
                phase.clickDispatched_.set(true);
                // CefMouseEvent mirrors GLFW here: action 1 presses, action 0 releases, and button
                // 0 is left. CEF 151 publishes this selection through its OSR user-input update
                // path.
                sendMouseEvent(new CefMouseEvent(1, phase.inputX_, phase.inputY_, 1, 0, CefMouseEvent.BUTTON1_MASK));
                sendMouseEvent(new CefMouseEvent(0, phase.inputX_, phase.inputY_, 1, 0, 0));
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void acknowledgeClick(InputPhase phase) {
            if (!phase.moveAcknowledged_.get()) throw new AssertionError("Renderer acknowledged " + phase.name_ + " click before its trusted mousemove");
            phase.clickAcknowledged_.compareAndSet(false, true);
            maybeEnqueuePhaseCompletion(phase);
        }

        private void maybeEnqueueSelectionStart() {
            if (!pageReady_.get() || !startRequested_.get()
                    || !selectionStartQueued_.compareAndSet(false, true))
                return;
            // The first page-ready edge is delivered by OnTitleChange on CEF's UI thread. Always
            // return before crossing back through SetFocus and renderer JavaScript dispatch.
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

        private void maybeEnqueuePhaseCompletion(InputPhase phase) {
            if (!phase.clickAcknowledged_.get() || phase.expectedCallback_.get() == null
                    || !phase.completionQueued_.compareAndSet(false, true))
                return;
            // The renderer title and selection callback are independent native callbacks. Advancing
            // only after both, on a later EDT turn, prevents the next phase title from coalescing.
            enqueue(() -> dispatchPhaseCompletion(phase));
        }

        private void dispatchPhaseCompletion(InputPhase phase) {
            try {
                CallbackSnapshot snapshot = phase.expectedCallback_.get();
                if (snapshot == null || !phase.clickAcknowledged_.get()) throw new AssertionError("Text-selection phase completion lost its renderer or CEF acknowledgement");
                if (phase.completion_.isDone()) return;
                if (phase == selectionPhase_) {
                    collapseRequested_.set(true);
                    execute(this, "window.prepareTextSelectionCollapse();");
                } else if (phase == collapsePhase_) {
                    afterCollapse_.run();
                } else {
                    throw new AssertionError("Unknown text-selection input phase");
                }
                phase.completionDispatched_.set(true);
                phase.completion_.complete(snapshot);
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
            return "{url=" + url_ + ", pageReady=" + pageReady_.get()
                    + ", startRequested=" + startRequested_.get()
                    + ", selectionStartQueued=" + selectionStartQueued_.get()
                    + ", selectionStartDispatched=" + selectionStartDispatched_.get()
                    + ", collapseRequested=" + collapseRequested_.get() + ", popupPaints="
                    + popupPaints_.get() + ", callbackCount=" + callbackCount_.get()
                    + ", lastCallbackText=" + lastCallbackText_.get() + ", lastCallbackRange="
                    + lastCallbackRange_.get() + ", selection=" + selectionPhase_.diagnostics()
                    + ", collapse=" + collapsePhase_.diagnostics() + "}";
        }

        @Override
        public void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {
            try {
                if (browser != this) throw new AssertionError("Text-selection callback crossed browser-specific render handlers");
                if (selectedText == null || selectedRange == null)
                    throw new AssertionError("Text-selection snapshots must be non-null");
                callbackCount_.incrementAndGet();
                lastCallbackText_.set(formatText(selectedText));
                lastCallbackRange_.set(selectedRange.toString());
                if (!selectionStartDispatched_.get()) return;
                CallbackSnapshot snapshot = new CallbackSnapshot(browser, selectedText, selectedRange, isOnCefUiThread());
                InputPhase phase = null;
                if (!collapseRequested_.get() && selectionPhase_.accepts(selectedText, selectedRange)) phase = selectionPhase_;
                else if (collapseRequested_.get() && collapsePhase_.accepts(selectedText, selectedRange)) phase = collapsePhase_;
                if (phase == null || !phase.expectedCallback_.compareAndSet(null, snapshot)) return;
                phase.expectedCallbackReceived_.set(true);
                maybeEnqueuePhaseCompletion(phase);
            } catch (Throwable failure) {
                fail(failure);
            }
        }
    }
}
