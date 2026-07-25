// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefPointerType;
import org.cef.event.CefTouchEvent;
import org.cef.event.CefTouchEventType;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.misc.EventFlags;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@NativeCefTest
class CefTouchEventNativeTest {
    private static final int VIEW_WIDTH = 320;
    private static final int VIEW_HEIGHT = 200;
    private static final int TOUCH_ID = 27;
    private static final int WORKER_TIMEOUT_SECONDS = 5;
    private static final String TEST_URL = "http://touch-input.test/index.html";
    private static final String TITLE_PREFIX = "jcef-touch:";
    private static final String TEST_CONTENT = "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%;overflow:hidden}#target{position:absolute;inset:0;touch-action:none;user-select:none}</style></head><body><div id='target'></div><script>(()=>{const target=document.getElementById('target');let phase=0,pointerDown=null,touchCount=0,touchGeometry=null;const point=e=>Math.round(e.clientX)+':'+Math.round(e.clientY);const publishDown=()=>{if(!pointerDown||touchCount!==1||!touchGeometry)return;phase=1;document.title='jcef-touch:down:'+pointerDown.pointerType+':'+pointerDown.pressure+':'+pointerDown.point+':'+pointerDown.shift+':touchstart:'+touchCount+':'+touchGeometry;};const report=e=>{if(e.target!==target){document.title='jcef-touch:unexpected-target:'+e.type;return;}if(e.type==='pointermove'&&phase===0&&e.pointerType==='mouse')return;const shift=e.shiftKey?'shift':'no-shift';if(e.type==='pointerdown'&&phase===0){pointerDown={pointerType:e.pointerType,pressure:e.pressure.toFixed(3),point:point(e),shift};publishDown();return;}if(e.type==='pointermove'&&phase===1){phase=2;document.title='jcef-touch:move:'+e.pointerType+':'+e.pressure.toFixed(3)+':'+point(e)+':'+shift;return;}if(e.type==='pointerup'&&phase===2){phase=3;document.title='jcef-touch:up:'+e.pointerType+':'+point(e)+':'+shift;return;}document.title='jcef-touch:unexpected:'+e.type+':'+phase;};target.addEventListener('mousemove',e=>{if(phase===0)document.title='jcef-touch:input-ready:'+point(e);});target.addEventListener('pointerdown',report);target.addEventListener('pointermove',report);target.addEventListener('pointerup',report);target.addEventListener('pointercancel',report);target.addEventListener('touchstart',e=>{if(e.target!==target){document.title='jcef-touch:unexpected-target:touchstart';return;}if(phase===0){touchCount=e.touches.length;const touch=e.touches[0];touchGeometry=touch?touch.radiusX.toFixed(3)+':'+touch.radiusY.toFixed(3)+':'+touch.rotationAngle.toFixed(3):'none';publishDown();}});document.title='jcef-touch:ready';})();</script></body></html>";
    private static final Method NATIVE_SEND_TOUCH_EVENT = getNativeSendTouchEvent();

    private enum Phase { READY, FOCUS_READY, INPUT_READY, DOWN, MOVE, UP, COMPLETE }

    @Test
    void deliversRendererAcknowledgedPenSequenceAndIgnoresPostCloseInput() {
        assertTouchEventsEnabled();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<String> lastTitle = new AtomicReference<String>("<none>");
        AtomicReference<TouchBrowser> browserReference = new AtomicReference<TouchBrowser>();
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicBoolean firstPaint = new AtomicBoolean();
        AtomicBoolean focusReady = new AtomicBoolean();
        AtomicBoolean inputProbeStarted = new AtomicBoolean();
        CountDownLatch inputProbeFinished = new CountDownLatch(1);
        CountDownLatch touchWorkersFinished = new CountDownLatch(3);
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            private Phase phase_ = Phase.READY;
            private String processedTitle_ = "";

            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser != browser_ || !title.startsWith(TITLE_PREFIX) || title.equals(processedTitle_) || phase_ == Phase.COMPLETE) return;
                        processedTitle_ = title;
                        lastTitle.set(title);
                        try {
                            handleTitle(browser, title);
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            phase_ = Phase.COMPLETE;
                            terminateTest();
                        }
                    }
                });
                addResource(TEST_URL, TEST_CONTENT, "text/html");
                TouchBrowser browser = new TouchBrowser(client_, TEST_URL);
                browser_ = browser;
                browserReference.set(browser);
                browser.addOnPaintListener(event -> markPainted(browser));
                browser.createImmediately();
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) ((TouchBrowser) browser).notifyInitialSize();
            }

            private void handleTitle(CefBrowser browser, String title) {
                switch (phase_) {
                    case READY:
                        assertEquals("jcef-touch:ready", title);
                        phase_ = Phase.FOCUS_READY;
                        browser.setFocus(true);
                        awaitRendererFocus(browser);
                        break;
                    case FOCUS_READY:
                        assertEquals("jcef-touch:focus-ready:320:200:target", title);
                        phase_ = Phase.INPUT_READY;
                        focusReady.set(true);
                        maybeStartInputProbe(browser);
                        break;
                    case INPUT_READY:
                        assertEquals("jcef-touch:input-ready:20:20", title);
                        assertTrue(firstPaint.get());
                        assertTrue(browser.isWindowRenderingDisabled());
                        assertNativeValidation(browser);
                        phase_ = Phase.DOWN;
                        sendTouchFromWorker(browser, new CefTouchEvent(TOUCH_ID, 80.0f, 70.0f, 3.0f, 4.0f, 0.25f, 0.35f, CefTouchEventType.PRESSED, EventFlags.EVENTFLAG_SHIFT_DOWN, CefPointerType.PEN), "jcef-touch-press-worker");
                        break;
                    case DOWN:
                        assertEquals("jcef-touch:down:pen:0.350:80:70:shift:touchstart:1:3.000:4.000:14.324", title);
                        phase_ = Phase.MOVE;
                        sendTouchFromWorker(browser, new CefTouchEvent(TOUCH_ID, 105.0f, 90.0f, 5.0f, 6.0f, 0.5f, 0.75f, CefTouchEventType.MOVED, EventFlags.EVENTFLAG_SHIFT_DOWN, CefPointerType.PEN), "jcef-touch-move-worker");
                        break;
                    case MOVE:
                        assertEquals("jcef-touch:move:pen:0.750:105:90:shift", title);
                        phase_ = Phase.UP;
                        sendTouchFromWorker(browser, new CefTouchEvent(TOUCH_ID, 105.0f, 90.0f, 5.0f, 6.0f, 0.5f, 0.0f, CefTouchEventType.RELEASED, EventFlags.EVENTFLAG_SHIFT_DOWN, CefPointerType.PEN), "jcef-touch-release-worker");
                        break;
                    case UP:
                        assertEquals("jcef-touch:up:pen:105:90:shift", title);
                        phase_ = Phase.COMPLETE;
                        delivered.set(true);
                        terminateTest();
                        break;
                    case COMPLETE:
                        break;
                }
            }

            private void markPainted(CefBrowser browser) {
                firstPaint.set(true);
                maybeStartInputProbe(browser);
            }

            private void maybeStartInputProbe(CefBrowser browser) {
                if (!firstPaint.get() || !focusReady.get() || !inputProbeStarted.compareAndSet(false, true)) return;
                Runnable operation = () -> {
                    try {
                        ((TouchBrowser) browser).sendInputProbe();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        terminateTest();
                    } finally {
                        inputProbeFinished.countDown();
                    }
                };
                Thread worker = new Thread(operation, "jcef-touch-input-probe-worker");
                worker.setDaemon(true);
                worker.start();
            }

            private void awaitRendererFocus(CefBrowser browser) {
                CefFrame frame = browser.getMainFrame();
                if (frame == null) throw new AssertionError("OSR browser has no main frame while awaiting renderer focus");
                try {
                    frame.executeJavaScript("(()=>{const target=document.getElementById('target');target.tabIndex=0;const awaitFocus=()=>{target.focus();if(document.hasFocus()&&document.activeElement===target){const hit=document.elementFromPoint(80,70);document.title='jcef-touch:focus-ready:'+innerWidth+':'+innerHeight+':'+(hit?hit.id:'none');return;}requestAnimationFrame(awaitFocus);};awaitFocus();})();", TEST_URL, 1);
                } finally {
                    frame.dispose();
                }
            }

            private void sendTouchFromWorker(CefBrowser browser, CefTouchEvent event, String workerName) {
                Runnable operation = () -> {
                    try {
                        browser.sendTouchEvent(event);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        terminateTest();
                    } finally {
                        touchWorkersFinished.countDown();
                    }
                };
                Thread worker = new Thread(operation, workerName);
                worker.setDaemon(true);
                worker.start();
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        awaitFrameAndAlwaysTerminate(frame, () -> "title=" + lastTitle.get() + ", paint=" + firstPaint.get() + ", focus=" + focusReady.get() + ", inputProbe=" + inputProbeStarted.get() + ", eventWorkersRemaining=" + touchWorkersFinished.getCount());
        assertNull(failure.get(), () -> "OSR touch flow failed at title " + lastTitle.get() + ": " + failure.get());
        assertTrue(await(inputProbeFinished), "Touch input-route probe worker did not finish");
        assertTrue(await(touchWorkersFinished), "Touch event workers did not finish");
        assertTrue(delivered.get(), () -> "OSR touch flow did not complete; last title=" + lastTitle.get());
        TouchBrowser browser = browserReference.get();
        assertFalse(browser.isValid());
        assertDoesNotThrow(() -> browser.sendTouchEvent(new CefTouchEvent(TOUCH_ID, 10.0f, 10.0f, CefTouchEventType.PRESSED)));
        assertDoesNotThrow(() -> browser.sendTouchEvent(new CefTouchEvent(TOUCH_ID, 20.0f, 20.0f, CefTouchEventType.MOVED)));
        assertDoesNotThrow(() -> browser.sendTouchEvent(new CefTouchEvent(TOUCH_ID, 20.0f, 20.0f, CefTouchEventType.RELEASED)));
    }

    private static void awaitFrameAndAlwaysTerminate(TestFrame frame, Supplier<String> diagnostics) {
        AssertionError completionFailure = null;
        try {
            frame.awaitCompletion();
        } catch (AssertionError error) {
            completionFailure = new AssertionError("Touch flow did not complete; " + diagnostics.get(), error);
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
        if (completionFailure != null) throw completionFailure;
    }

    private static void assertTouchEventsEnabled() {
        org.cef.callback.CefCommandLine commandLine = org.cef.callback.CefCommandLine.getGlobalCommandLine();
        try {
            assertTrue(commandLine.isValid());
            assertTrue(commandLine.hasSwitch(TouchTestCommandLine.TOUCH_EVENTS_SWITCH));
            assertEquals(TouchTestCommandLine.TOUCH_EVENTS_ENABLED, commandLine.getSwitchValue(TouchTestCommandLine.TOUCH_EVENTS_SWITCH));
        } finally {
            commandLine.dispose();
        }
    }

    private static Method getNativeSendTouchEvent() {
        try {
            Method method = org.cef.browser.CefBrowser_N.class.getDeclaredMethod("N_SendTouchEvent", int.class, float.class, float.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void assertNativeValidation(CefBrowser browser) {
        assertNativeRejects(browser, -1, 10.0f, 10.0f, 1.0f, 1.0f, 0.0f, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, Float.NaN, 10.0f, 1.0f, 1.0f, 0.0f, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, -1.0f, 1.0f, 0.0f, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, -Float.MIN_VALUE, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, (float) Math.PI, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, 0.0f, 1.01f, CefTouchEventType.PRESSED.getValue(), 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, 0.0f, 0.5f, Integer.MAX_VALUE, 0, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, 0.0f, 0.5f, CefTouchEventType.PRESSED.getValue(), 0x10000, CefPointerType.PEN.getValue());
        assertNativeRejects(browser, TOUCH_ID, 10.0f, 10.0f, 1.0f, 1.0f, 0.0f, 0.5f, CefTouchEventType.PRESSED.getValue(), 0, Integer.MAX_VALUE);
    }

    private static void assertNativeRejects(CefBrowser browser, int id, float x, float y, float radiusX, float radiusY, float rotationAngle, float pressure, int type, int modifiers, int pointerType) {
        try {
            NATIVE_SEND_TOUCH_EVENT.invoke(browser, id, x, y, radiusX, radiusY, rotationAngle, pressure, type, modifiers, pointerType);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalArgumentException) return;
            throw new AssertionError("Native touch validation threw an unexpected exception", cause);
        }
        throw new AssertionError("Native touch validation accepted malformed primitive input");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the touch press worker", exception);
        }
    }

    private static final class TouchBrowser extends CefBrowserOsr {
        TouchBrowser(CefClient client, String url) {
            super(client, url, false, null);
            updateViewGeometry(0, 0, VIEW_WIDTH, VIEW_HEIGHT, new Point(0, 0));
        }

        private void notifyInitialSize() {
            wasResized(VIEW_WIDTH, VIEW_HEIGHT);
        }

        private void sendInputProbe() {
            sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, 20, 20, 0, 0, 0));
        }
    }
}
