// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.CefRequestContextSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.callback.CefPermissionPromptCallback;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefMediaAccessPermissionTypes;
import org.cef.handler.CefPermissionHandlerAdapter;
import org.cef.handler.CefPermissionRequestResult;
import org.cef.handler.CefPermissionRequestTypes;
import org.cef.handler.CefRequestHandler.TerminationStatus;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@NativeCefTest
class CefPermissionHandlerNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    private static final String PROMPT_URL = "https://permission.test/prompt.html";
    private static final String PROMPT_ORIGIN = "https://permission.test/";
    private static final String PROMPT_READY_TITLE = "permission-prompt:ready";
    private static final String PROMPT_SUCCESS_TITLE = "permission-prompt:accepted:true";
    private static final String PROMPT_FAILURE_PREFIX = "permission-prompt:failure:";
    private static final String MEDIA_URL = "https://media-permission.test/media.html";
    private static final String MEDIA_ORIGIN = "https://media-permission.test/";
    private static final String MEDIA_SUCCESS_TITLE = "media-permission:granted:audio,video";
    private static final String MEDIA_FAILURE_PREFIX = "media-permission:failure:";

    private record PromptSnapshot(CefBrowser browser, long promptId, String origin, int permissions, int order) {}

    private record DismissSnapshot(CefBrowser browser, long promptId, int result, int order) {}

    private record MediaSnapshot(CefBrowser browser, String frameUrl, String origin, int permissions) {}

    @Test
    void liveWindowManagementPromptPreservesDismissalIdentityOrderAndOriginalOwner() throws Exception {
        CompletableFuture<PromptSnapshot> prompt = new CompletableFuture<PromptSnapshot>();
        CompletableFuture<DismissSnapshot> dismissal = new CompletableFuture<DismissSnapshot>();
        CompletableFuture<String> pageOutcome = new CompletableFuture<String>();
        CompletableFuture<Thread> continuation = new CompletableFuture<Thread>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<Thread> continuationWorker = new AtomicReference<Thread>();
        AtomicInteger order = new AtomicInteger();
        AtomicInteger continuationOrder = new AtomicInteger();
        AtomicInteger originalDismissCalls = new AtomicInteger();
        AtomicInteger replacementPromptCalls = new AtomicInteger();
        AtomicInteger replacementDismissCalls = new AtomicInteger();
        AtomicBoolean clickDispatched = new AtomicBoolean();
        Supplier<TestFrame> frameSupplier = () -> new TestFrame() {
            private final AtomicBoolean terminationRequested_ = new AtomicBoolean();
            private CefRequestContext requestContext_;

            @Override
            protected void setupTest() {
                CefPermissionHandlerAdapter replacement = new CefPermissionHandlerAdapter() {
                    @Override
                    public boolean onShowPermissionPrompt(CefBrowser browser, long promptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
                        replacementPromptCalls.incrementAndGet();
                        recordFailure(new AssertionError("Replacement delegate unexpectedly received the outstanding prompt"));
                        callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY);
                        return true;
                    }

                    @Override
                    public void onDismissPermissionPrompt(CefBrowser browser, long promptId, int result) {
                        replacementDismissCalls.incrementAndGet();
                        recordFailure(new AssertionError("Replacement delegate received another delegate's dismissal"));
                    }
                };
                CefPermissionHandlerAdapter original = new CefPermissionHandlerAdapter() {
                    @Override
                    public boolean onShowPermissionPrompt(CefBrowser browser, long promptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
                        PromptSnapshot snapshot = new PromptSnapshot(browser, promptId, requestingOrigin, requestedPermissions, order.incrementAndGet());
                        if (!prompt.complete(snapshot)) {
                            callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY);
                            recordFailure(new AssertionError("Window-management prompt was delivered more than once"));
                            return true;
                        }

                        // Replace the public delegate before returning. CefClient must still bind
                        // dismissal to this exact accepting instance after this method unwinds.
                        client_.removePermissionHandler();
                        client_.addPermissionHandler(replacement);
                        Runnable continuationTask = () -> {
                            try {
                                continuationOrder.set(order.incrementAndGet());
                                callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_ACCEPT);
                                callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY);
                                continuation.complete(Thread.currentThread());
                            } catch (Throwable throwable) {
                                continuation.completeExceptionally(throwable);
                                recordFailure(throwable);
                            }
                        };
                        Thread worker = new Thread(continuationTask, "permission-prompt-continuation");
                        worker.setDaemon(true);
                        continuationWorker.set(worker);
                        worker.start();
                        return true;
                    }

                    @Override
                    public void onDismissPermissionPrompt(CefBrowser browser, long promptId, int result) {
                        originalDismissCalls.incrementAndGet();
                        dismissal.complete(new DismissSnapshot(browser, promptId, result, order.incrementAndGet()));
                        maybeTerminate();
                    }
                };
                client_.addPermissionHandler(original);
                CefDisplayHandlerAdapter displayHandler = new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || title == null) return;
                        if (PROMPT_READY_TITLE.equals(title) && clickDispatched.compareAndSet(false, true)) {
                            try {
                                browser.setFocus(true);
                                ((PermissionProbeBrowser) browser).clickPermissionButton();
                            } catch (Throwable throwable) {
                                recordFailure(throwable);
                            }
                        } else if (PROMPT_SUCCESS_TITLE.equals(title)) {
                            pageOutcome.complete(title);
                            maybeTerminate();
                        } else if (title.startsWith(PROMPT_FAILURE_PREFIX)) {
                            recordFailure(new AssertionError("Window-management promise failed: " + title.substring(PROMPT_FAILURE_PREFIX.length())));
                        }
                    }
                };
                client_.addDisplayHandler(displayHandler);
                requestContext_ = CefRequestContext.createContext(new CefRequestContextSettings(), null);
                addResource(PROMPT_URL, promptPage(), "text/html");
                browser_ = new PermissionProbeBrowser(client_, PROMPT_URL, requestContext_);
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame loadedFrame, ErrorCode errorCode, String errorText, String failedUrl) {
                if (browser == browser_ && loadedFrame.isMain())
                    recordFailure(new AssertionError("Prompt page load failed: " + errorCode + ", " + errorText + ", " + failedUrl));
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int errorCode, String errorString) {
                if (browser == browser_)
                    recordFailure(new AssertionError("Prompt renderer terminated: " + status + ", " + errorCode + ", " + errorString));
            }

            @Override
            protected void cleanupTest() {
                try {
                    if (requestContext_ != null) requestContext_.dispose();
                } finally {
                    super.cleanupTest();
                }
            }

            private void recordFailure(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                if (terminationRequested_.compareAndSet(false, true)) terminateTest();
            }

            private void maybeTerminate() {
                if (pageOutcome.isDone() && dismissal.isDone() && continuation.isDone() && terminationRequested_.compareAndSet(false, true))
                    terminateTest();
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameSupplier);

        Supplier<String> diagnostics = () -> "clickDispatched=" + clickDispatched.get() + ", promptDone=" + prompt.isDone() + ", continuationDone=" + continuation.isDone() + ", dismissalDone=" + dismissal.isDone() + ", pageDone=" + pageOutcome.isDone() + ", failure=" + failure.get();
        awaitFrameAndAlwaysTerminate(frame, diagnostics);
        throwIfFailed(failure, "Live window-management permission bridge failed");
        PromptSnapshot shown = await(prompt);
        DismissSnapshot dismissed = await(dismissal);
        Thread continuationThread = await(continuation);
        joinWorker(continuationWorker.get());

        assertSame(frame.browser_, shown.browser());
        assertEquals(PROMPT_ORIGIN, shown.origin());
        assertEquals(CefPermissionRequestTypes.CEF_PERMISSION_TYPE_WINDOW_MANAGEMENT, shown.permissions());
        assertSame(frame.browser_, dismissed.browser());
        assertEquals(shown.promptId(), dismissed.promptId());
        assertEquals(CefPermissionRequestResult.CEF_PERMISSION_RESULT_ACCEPT, dismissed.result());
        assertTrue(shown.order() < continuationOrder.get());
        assertTrue(continuationOrder.get() < dismissed.order());
        assertFalse(continuationThread.getName().isEmpty());
        assertEquals(PROMPT_SUCCESS_TITLE, await(pageOutcome));
        assertEquals(1, originalDismissCalls.get());
        assertEquals(0, replacementPromptCalls.get());
        assertEquals(0, replacementDismissCalls.get());
    }

    @Test
    void liveGetUserMediaUsesExactRawMaskAndAnyThreadOneShotContinuation() throws Exception {
        CompletableFuture<MediaSnapshot> request = new CompletableFuture<MediaSnapshot>();
        CompletableFuture<Thread> continuation = new CompletableFuture<Thread>();
        CompletableFuture<String> pageOutcome = new CompletableFuture<String>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<Thread> continuationWorker = new AtomicReference<Thread>();
        Supplier<TestFrame> frameSupplier = () -> new TestFrame() {
            private final AtomicBoolean terminationRequested_ = new AtomicBoolean();
            private CefRequestContext requestContext_;

            @Override
            protected void setupTest() {
                CefPermissionHandlerAdapter mediaHandler = new CefPermissionHandlerAdapter() {
                    @Override
                    public boolean onRequestMediaAccessPermission(CefBrowser browser, CefFrame requestingFrame, String requestingOrigin, int requestedPermissions, CefMediaAccessCallback callback) {
                        MediaSnapshot snapshot;
                        try {
                            snapshot = new MediaSnapshot(browser, requestingFrame.getURL(), requestingOrigin, requestedPermissions);
                        } catch (Throwable throwable) {
                            recordFailure(throwable);
                            callback.Cancel();
                            return true;
                        }
                        if (!request.complete(snapshot)) {
                            callback.Cancel();
                            recordFailure(new AssertionError("getUserMedia permission was delivered more than once"));
                            return true;
                        }

                        Runnable continuationTask = () -> {
                            try {
                                callback.Continue(requestedPermissions);
                                callback.Cancel();
                                callback.Continue(CefMediaAccessPermissionTypes.CEF_MEDIA_PERMISSION_NONE);
                                continuation.complete(Thread.currentThread());
                                maybeTerminate();
                            } catch (Throwable throwable) {
                                continuation.completeExceptionally(throwable);
                                recordFailure(throwable);
                            }
                        };
                        Thread worker = new Thread(continuationTask, "media-permission-continuation");
                        worker.setDaemon(true);
                        continuationWorker.set(worker);
                        worker.start();
                        return true;
                    }

                    @Override
                    public boolean onShowPermissionPrompt(CefBrowser browser, long promptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
                        callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY);
                        recordFailure(new AssertionError("getUserMedia unexpectedly used the general prompt path"));
                        return true;
                    }
                };
                client_.addPermissionHandler(mediaHandler);
                CefDisplayHandlerAdapter displayHandler = new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || title == null) return;
                        if (MEDIA_SUCCESS_TITLE.equals(title)) {
                            pageOutcome.complete(title);
                            maybeTerminate();
                        } else if (title.startsWith(MEDIA_FAILURE_PREFIX)) {
                            recordFailure(new AssertionError("getUserMedia failed: " + title.substring(MEDIA_FAILURE_PREFIX.length())));
                        }
                    }
                };
                client_.addDisplayHandler(displayHandler);
                requestContext_ = CefRequestContext.createContext(new CefRequestContextSettings(), null);
                addResource(MEDIA_URL, mediaPage(), "text/html");
                browser_ = new PermissionProbeBrowser(client_, MEDIA_URL, requestContext_);
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame loadedFrame, ErrorCode errorCode, String errorText, String failedUrl) {
                if (browser == browser_ && loadedFrame.isMain())
                    recordFailure(new AssertionError("Media page load failed: " + errorCode + ", " + errorText + ", " + failedUrl));
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int errorCode, String errorString) {
                if (browser == browser_)
                    recordFailure(new AssertionError("Media renderer terminated: " + status + ", " + errorCode + ", " + errorString));
            }

            @Override
            protected void cleanupTest() {
                try {
                    if (requestContext_ != null) requestContext_.dispose();
                } finally {
                    super.cleanupTest();
                }
            }

            private void recordFailure(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                if (terminationRequested_.compareAndSet(false, true)) terminateTest();
            }

            private void maybeTerminate() {
                if (pageOutcome.isDone() && continuation.isDone() && terminationRequested_.compareAndSet(false, true))
                    terminateTest();
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameSupplier);

        Supplier<String> diagnostics = () -> "requestDone=" + request.isDone() + ", continuationDone=" + continuation.isDone() + ", pageDone=" + pageOutcome.isDone() + ", failure=" + failure.get();
        awaitFrameAndAlwaysTerminate(frame, diagnostics);
        throwIfFailed(failure, "Live getUserMedia permission bridge failed");
        MediaSnapshot mediaRequest = await(request);
        Thread continuationThread = await(continuation);
        joinWorker(continuationWorker.get());

        assertSame(frame.browser_, mediaRequest.browser());
        assertEquals(MEDIA_URL, mediaRequest.frameUrl());
        assertEquals(MEDIA_ORIGIN, mediaRequest.origin());
        assertEquals(CefMediaAccessPermissionTypes.CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE | CefMediaAccessPermissionTypes.CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE, mediaRequest.permissions());
        assertFalse(continuationThread.getName().isEmpty());
        assertEquals(MEDIA_SUCCESS_TITLE, await(pageOutcome));
    }

    private static String promptPage() {
        return "<!doctype html><html><head><meta charset='utf-8'><title>permission-prompt:loading</title></head>"
                + "<body><button id='request' style='position:absolute;left:0;top:0;width:200px;height:100px'>Request screen details</button>"
                + "<script>document.getElementById('request').addEventListener('click',async event=>{event.preventDefault();try{const details=await window.getScreenDetails();document.title='permission-prompt:accepted:'+(details.screens.length>0);}catch(error){document.title='permission-prompt:failure:'+String(error);}});requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='permission-prompt:ready';}));</script></body></html>";
    }

    private static String mediaPage() {
        return "<!doctype html><html><head><meta charset='utf-8'><title>media-permission:ready</title></head><body>"
                + "<script>(async()=>{try{const stream=await navigator.mediaDevices.getUserMedia({audio:true,video:true});const kinds=stream.getTracks().map(track=>track.kind).sort().join(',');stream.getTracks().forEach(track=>track.stop());document.title='media-permission:granted:'+kinds;}catch(error){document.title='media-permission:failure:'+String(error);}})();</script></body></html>";
    }

    private static void awaitFrameAndAlwaysTerminate(TestFrame frame, Supplier<String> diagnostics) {
        AssertionError completionFailure = null;
        try {
            frame.awaitCompletion();
        } catch (AssertionError error) {
            completionFailure = error;
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
        if (completionFailure != null)
            throw new AssertionError(completionFailure.getMessage() + "; " + diagnostics.get(), completionFailure);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void joinWorker(Thread worker) throws InterruptedException {
        assertTrue(worker != null, "Expected an asynchronous permission continuation worker");
        worker.join(TimeUnit.SECONDS.toMillis(FUTURE_TIMEOUT_SECONDS));
        assertFalse(worker.isAlive(), "Permission continuation worker did not terminate");
    }

    private static void throwIfFailed(AtomicReference<Throwable> failure, String description) {
        Throwable throwable = failure.get();
        if (throwable != null) throw new AssertionError(description, throwable);
    }

    private static final class PermissionProbeBrowser extends CefBrowserOsr {
        private PermissionProbeBrowser(CefClient client, String url, CefRequestContext requestContext) {
            super(client, url, false, requestContext);
            updateViewGeometry(0, 0, 800, 600, new Point(0, 0));
        }

        private void clickPermissionButton() {
            // A real press/release pair establishes Chromium transient user activation. Evaluating
            // the page function directly would not exercise the prompt path reliably.
            sendMouseEvent(new CefMouseEvent(1, 20, 20, 1, 0, CefMouseEvent.BUTTON1_MASK));
            sendMouseEvent(new CefMouseEvent(0, 20, 20, 1, 0, 0));
        }
    }
}
