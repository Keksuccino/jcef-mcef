// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefColor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.input.CefCompositionUnderline;
import org.cef.input.CefCompositionUnderlineStyle;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefImeCompositionNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final String TEST_URL = "http://test.com/osr-ime.html";
    private static final String TITLE_PREFIX = "ime:";
    private static final CefColor BLACK = CefColor.fromArgb(0xFF000000);
    private static final CefColor TRANSPARENT = CefColor.fromArgb(0x00000000);
    private static final String TEST_CONTENT = "<!doctype html><html><head><meta charset='utf-8'></head><body><input id='ime' autocomplete='off'><script>(()=>{const input=document.getElementById('ime');const encode=value=>{const units=[];for(let index=0;index<value.length;index++)units.push(value.charCodeAt(index).toString(16).padStart(4,'0'));return units.join('-');};window.prepareImePhase=(phase,value,start,end)=>{input.value=value;input.focus();input.setSelectionRange(start,end);requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='ime:ready:'+phase;}));};window.reportImePhase=phase=>{requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='ime:result:'+phase+':'+encode(input.value)+':'+input.selectionStart+':'+input.selectionEnd;}));};document.title='ime:page-ready';})();</script></body></html>";

    private enum FlowPhase { PAGE_READY, CANCEL_READY, CANCEL_RESULT, FINISH_READY, FINISH_RESULT, COMMIT_READY, COMMIT_RESULT, EMPTY_READY, EMPTY_RESULT, MAC_REPLACE_READY, MAC_REPLACE_RESULT, MAC_CURSOR_READY, MAC_CURSOR_RESULT, COMPLETE }

    @Test
    void offscreenImeMutatesDomInOrderFromCefAndOrdinaryJavaThreads() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<String> lastTitle = new AtomicReference<String>("<none>");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean workerStarted = new AtomicBoolean();
        CompletableFuture<Void> workerFinished = new CompletableFuture<Void>();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private FlowPhase phase_ = FlowPhase.PAGE_READY;
            private String processedTitle_ = "";

            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || !title.startsWith(TITLE_PREFIX) || phase_ == FlowPhase.COMPLETE) return;
                        lastTitle.set(title);
                        if (title.equals(processedTitle_)) return;
                        processedTitle_ = title;
                        try {
                            handleImeTitle(title);
                        } catch (Throwable throwable) {
                            failFlow(throwable);
                        }
                    }
                });
                addResource(TEST_URL, TEST_CONTENT, "text/html");
                browser_ = createOffscreenBrowser(TEST_URL, null);
                super.setupTest();
            }

            private void handleImeTitle(String title) {
                switch (phase_) {
                    case PAGE_READY:
                        assertEquals("ime:page-ready", title);
                        phase_ = FlowPhase.CANCEL_READY;
                        browser_.setFocus(true);
                        execute("(()=>{const awaitFocus=()=>{const input=document.getElementById('ime');input.focus();if(document.hasFocus()&&document.activeElement===input){window.prepareImePhase('cancel','',0,0);return;}requestAnimationFrame(awaitFocus);};awaitFocus();})();");
                        break;
                    case CANCEL_READY:
                        assertEquals("ime:ready:cancel", title);
                        phase_ = FlowPhase.CANCEL_RESULT;
                        setComposition(browser_, "かな");
                        browser_.imeCancelComposition();
                        report("cancel");
                        break;
                    case CANCEL_RESULT:
                        assertResult(title, "cancel", "", 0);
                        phase_ = FlowPhase.FINISH_READY;
                        prepare("finish", "", 0, 0);
                        break;
                    case FINISH_READY:
                        assertEquals("ime:ready:finish", title);
                        phase_ = FlowPhase.FINISH_RESULT;
                        setComposition(browser_, "first");
                        setComposition(browser_, "最新😀");
                        browser_.imeFinishComposingText(false);
                        report("finish");
                        break;
                    case FINISH_RESULT:
                        assertResult(title, "finish", "最新😀", null);
                        phase_ = FlowPhase.COMMIT_READY;
                        prepare("commit", "", 0, 0);
                        break;
                    case COMMIT_READY:
                        assertEquals("ime:ready:commit", title);
                        phase_ = FlowPhase.COMMIT_RESULT;
                        workerStarted.set(true);
                        Thread worker = new Thread(() -> commitFromWorker(workerFinished), "jcef-ime-commit-worker");
                        worker.setDaemon(true);
                        worker.start();
                        break;
                    case COMMIT_RESULT:
                        assertResult(title, "commit", "A😀B", 4);
                        phase_ = FlowPhase.EMPTY_READY;
                        prepare("empty", "", 0, 0);
                        break;
                    case EMPTY_READY:
                        assertEquals("ime:ready:empty", title);
                        phase_ = FlowPhase.EMPTY_RESULT;
                        browser_.imeCommitText("", CefRange.INVALID, Integer.MAX_VALUE);
                        report("empty");
                        break;
                    case EMPTY_RESULT:
                        assertResult(title, "empty", "", 0);
                        if (isMac()) {
                            phase_ = FlowPhase.MAC_REPLACE_READY;
                            prepare("mac-replace", "abcdef", 6, 6);
                        } else {
                            completeFlow();
                        }
                        break;
                    case MAC_REPLACE_READY:
                        assertEquals("ime:ready:mac-replace", title);
                        phase_ = FlowPhase.MAC_REPLACE_RESULT;
                        browser_.imeCommitText("X", new CefRange(1, 3), 0);
                        report("mac-replace");
                        break;
                    case MAC_REPLACE_RESULT:
                        // Chromium's explicit replacement path preserves the existing selection,
                        // adjusted for the replacement length. This moves cursor 6 to 5 here.
                        assertResult(title, "mac-replace", "aXdef", 5);
                        phase_ = FlowPhase.MAC_CURSOR_READY;
                        prepare("mac-cursor", "abcdef", 6, 6);
                        break;
                    case MAC_CURSOR_READY:
                        assertEquals("ime:ready:mac-cursor", title);
                        phase_ = FlowPhase.MAC_CURSOR_RESULT;
                        browser_.imeCommitText("XYZ", CefRange.INVALID, -2);
                        report("mac-cursor");
                        break;
                    case MAC_CURSOR_RESULT:
                        // With no replacement range, Chromium applies the relative offset after
                        // insertion: 6 + 3 - 2 = 7. A nonzero value makes JNI forwarding visible.
                        assertResult(title, "mac-cursor", "abcdefXYZ", 7);
                        completeFlow();
                        break;
                    case COMPLETE:
                        break;
                }
            }

            private void commitFromWorker(CompletableFuture<Void> completion) {
                try {
                    browser_.imeCommitText("A😀B", CefRange.INVALID, 0);
                    report("commit");
                    completion.complete(null);
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                    failFlow(throwable);
                }
            }

            private void prepare(String phase, String value, int selectionStart, int selectionEnd) {
                execute("window.prepareImePhase('" + phase + "','" + value + "'," + selectionStart + "," + selectionEnd + ");");
            }

            private void report(String phase) {
                execute("window.reportImePhase('" + phase + "');");
            }

            private void execute(String script) {
                CefFrame mainFrame = browser_.getMainFrame();
                if (mainFrame == null) throw new AssertionError("OSR browser has no main frame during IME flow");
                try {
                    mainFrame.executeJavaScript(script, TEST_URL, 1);
                } finally {
                    mainFrame.dispose();
                }
            }

            private void completeFlow() {
                phase_ = FlowPhase.COMPLETE;
                completed.set(true);
                terminateTest();
            }

            private void failFlow(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                phase_ = FlowPhase.COMPLETE;
                terminateTest();
            }
        });

        frame.awaitCompletion();
        if (workerStarted.get()) workerFinished.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNull(failure.get(), () -> "OSR IME flow failed at title " + lastTitle.get() + ": " + failure.get());
        assertTrue(completed.get(), () -> "OSR IME flow did not complete; last title=" + lastTitle.get());
    }

    @Test
    @WindowedCefTest
    void windowedBrowserRejectsImeBeforeCefDebugAssertionsAndLeavesFocusedInputUnchanged() {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean exercised = new AtomicBoolean();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private int phase_;
            private String processedTitle_ = "";

            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || !title.startsWith(TITLE_PREFIX) || title.equals(processedTitle_)) return;
                        processedTitle_ = title;
                        try {
                            if (phase_ == 0) {
                                assertEquals("ime:page-ready", title);
                                phase_ = 1;
                                browser.setFocus(true);
                                executeWindowed("(()=>{const awaitFocus=()=>{const input=document.getElementById('ime');input.focus();if(document.hasFocus()&&document.activeElement===input){window.prepareImePhase('windowed','sentinel',8,8);return;}requestAnimationFrame(awaitFocus);};awaitFocus();})();");
                            } else if (phase_ == 1) {
                                assertEquals("ime:ready:windowed", title);
                                phase_ = 2;
                                setComposition(browser, "windowed");
                                browser.imeCommitText("ignored", CefRange.INVALID, 0);
                                browser.imeFinishComposingText(false);
                                browser.imeCancelComposition();
                                executeWindowed("window.reportImePhase('windowed');");
                            } else {
                                assertResult(title, "windowed", "sentinel", 8);
                                exercised.set(true);
                                terminateTest();
                            }
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            terminateTest();
                        }
                    }
                });
                addResource(TEST_URL, TEST_CONTENT, "text/html");
                createBrowser(TEST_URL);
                super.setupTest();
            }

            private void executeWindowed(String script) {
                CefFrame mainFrame = browser_.getMainFrame();
                if (mainFrame == null) throw new AssertionError("Windowed browser has no main frame during IME guard test");
                try {
                    mainFrame.executeJavaScript(script, TEST_URL, 1);
                } finally {
                    mainFrame.dispose();
                }
            }
        });

        frame.awaitCompletion();
        assertNull(failure.get(), () -> "Windowed IME guard failed: " + failure.get());
        assertTrue(exercised.get());
    }

    @Test
    void imeCallsRemainSafeAcrossBoundedOffscreenCloseRace() throws Exception {
        CompletableFuture<CefBrowser> created = new CompletableFuture<CefBrowser>();
        CompletableFuture<Void> beforeClose = new CompletableFuture<Void>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicInteger iterations = new AtomicInteger();
        AtomicInteger iterationsAfterBeforeClose = new AtomicInteger();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                browser_ = new CefBrowserOsr(client_, "about:blank", false, null);
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) created.complete(browser);
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                if (browser == browser_) beforeClose.complete(null);
                super.onBeforeClose(browser);
            }
        });

        CefBrowser browser = created.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CountDownLatch firstIteration = new CountDownLatch(1);
        CountDownLatch iterationAfterBeforeClose = new CountDownLatch(1);
        CompletableFuture<Void> workerFinished = new CompletableFuture<Void>();
        AtomicBoolean stopRequested = new AtomicBoolean();
        Thread worker = new Thread(() -> runCloseRace(browser, beforeClose, firstIteration, iterationAfterBeforeClose, stopRequested, iterations, iterationsAfterBeforeClose, workerFinished, failure), "jcef-ime-close-race");
        worker.setDaemon(true);
        worker.start();
        assertTrue(firstIteration.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "IME close-race worker did not begin");
        try {
            frame.terminateTest();
            frame.awaitCompletion();
            beforeClose.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(iterationAfterBeforeClose.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "IME close-race worker did not continue through OnBeforeClose");
            // The application callback runs before CefBrowser_N marks the browser closed and
            // native clears its handle. Keep issuing calls until admission observes that later
            // transition, otherwise this race can silently degrade into pre-close traffic only.
            awaitBrowserInvalid(browser);
        } finally {
            stopRequested.set(true);
        }
        workerFinished.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(iterations.get() > 0);
        assertTrue(iterationsAfterBeforeClose.get() > 0, "IME calls did not span the OnBeforeClose lifecycle transition");
        assertNull(failure.get(), () -> "IME close race failed: " + failure.get());
        assertFalse(browser.isValid());
        setComposition(browser, "closed");
        browser.imeCommitText("closed", CefRange.INVALID, Integer.MIN_VALUE);
        browser.imeFinishComposingText(true);
        browser.imeCancelComposition();
    }

    private static void awaitBrowserInvalid(CefBrowser browser) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FUTURE_TIMEOUT_SECONDS);
        while (browser.isValid()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for IME browser teardown");
            Thread.onSpinWait();
        }
    }

    private static void runCloseRace(CefBrowser browser, CompletableFuture<Void> beforeClose, CountDownLatch firstIteration, CountDownLatch iterationAfterBeforeClose, AtomicBoolean stopRequested, AtomicInteger iterations, AtomicInteger iterationsAfterBeforeClose, CompletableFuture<Void> workerFinished, AtomicReference<Throwable> failure) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FUTURE_TIMEOUT_SECONDS);
            int iteration = 0;
            while (!stopRequested.get()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for the IME close race to finish");
                setComposition(browser, "race");
                browser.imeFinishComposingText((iteration & 1) == 0);
                browser.imeCommitText("r", new CefRange(CefRange.MAX_VALUE, 0), iteration);
                browser.imeCancelComposition();
                iterations.incrementAndGet();
                if (beforeClose.isDone()) {
                    iterationsAfterBeforeClose.incrementAndGet();
                    iterationAfterBeforeClose.countDown();
                }
                firstIteration.countDown();
                iteration++;
            }
            workerFinished.complete(null);
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
            firstIteration.countDown();
            iterationAfterBeforeClose.countDown();
            workerFinished.completeExceptionally(throwable);
        }
    }

    private static void setComposition(CefBrowser browser, String text) {
        CefCompositionUnderline underline = new CefCompositionUnderline(new CefRange(0, text.length()), BLACK, TRANSPARENT, false, CefCompositionUnderlineStyle.SOLID);
        browser.imeSetComposition(text, List.of(underline), CefRange.INVALID, new CefRange(text.length(), text.length()));
    }

    private static void assertResult(String title, String phase, String value, Integer expectedCursor) {
        String[] fields = title.split(":", -1);
        assertEquals(6, fields.length, "Malformed renderer IME acknowledgement");
        assertEquals("ime", fields[0]);
        assertEquals("result", fields[1]);
        assertEquals(phase, fields[2]);
        assertEquals(utf16Hex(value), fields[3]);
        if (expectedCursor != null) {
            assertEquals(expectedCursor.intValue(), Integer.parseInt(fields[4]));
            assertEquals(expectedCursor.intValue(), Integer.parseInt(fields[5]));
        }
    }

    private static String utf16Hex(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            if (result.length() > 0) result.append('-');
            result.append(String.format(Locale.ROOT, "%04x", Integer.valueOf(value.charAt(index))));
        }
        return result.toString();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").startsWith("Mac");
    }
}
