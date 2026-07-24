// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.OS;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefFrame;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

@NativeCefTest
class CefBrowserOsrInputTest {
    private static final int INPUT_TIMEOUT_SECONDS = 30;
    private static final int KEY_MODIFIERS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final String PAGE_READY_TITLE = "jcef-awt-input-page-ready";
    private static final String INPUT_READY_TITLE = "jcef-awt-input-focus-ready";
    private static final String SUCCESS_TITLE = "jcef-awt-input-complete";

    @Test
    void componentBackedOsrForwardsAwtInputWithoutGraphicsBindingsAndStopsAfterClose() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.lwjgl.glfw.GLFW", false, CefBrowserOsrInputTest.class.getClassLoader()));

        String testUrl = "http://test.com/osr-awt-input.html";
        AtomicBoolean pageInputComplete = new AtomicBoolean();
        AtomicBoolean postCloseInputSafe = new AtomicBoolean();
        CountDownLatch postCloseInputComplete = new CountDownLatch(1);
        CountDownLatch inputTimeoutTriggered = new CountDownLatch(1);
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<String> lastTitle = new AtomicReference<String>("<none>");
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        ScheduledExecutorService timeoutExecutor = newTimeoutExecutor();
        TestFrame[] frame = new TestFrame[1];
        ScheduledFuture<?> timeout = null;
        try {
            Runnable createFrame = () -> {
                frame[0] = new TestFrame() {
                    private final AtomicBoolean loaded_ = new AtomicBoolean();
                    private final AtomicBoolean painted_ = new AtomicBoolean();
                    private final AtomicBoolean inputReady_ = new AtomicBoolean();
                    private final AtomicBoolean inputDispatched_ = new AtomicBoolean();
                    private final AtomicBoolean terminationRequested_ = new AtomicBoolean();
                    private Component inputComponent_;

                    @Override
                    protected void setupTest() {
                        client_.addDisplayHandler(new InputDisplayHandler(() -> browser_, lastTitle, pageInputComplete, this::focusInputPage, this::markInputReady, this::maybeFinish));
                        addResource(testUrl, createInputPage(), "text/html");
                        AwtInputBrowser browser = new AwtInputBrowser(client_, testUrl);
                        browser_ = browser;
                        inputComponent_ = browser.getUIComponent();
                        browser.addOnPaintListener(event -> markPainted());
                        browser.createImmediately();
                        super.setupTest();
                    }

                    @Override
                    public void onAfterCreated(CefBrowser browser) {
                        super.onAfterCreated(browser);
                        if (browser == browser_) ((AwtInputBrowser) browser).notifyInitialSize();
                    }

                    @Override
                    public void onLoadEnd(CefBrowser browser, CefFrame cefFrame, int httpStatusCode) {
                        if (browser != browser_ || !cefFrame.isMain()) return;
                        loaded_.set(true);
                        maybeDispatchInput();
                    }

                    @Override
                    protected void cleanupTest() {
                        SwingUtilities.invokeLater(this::verifyClosedInputIsIgnored);
                        super.cleanupTest();
                    }

                    private void markPainted() {
                        painted_.set(true);
                        maybeDispatchInput();
                    }

                    private void focusInputPage() {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                for (FocusListener listener : inputComponent_.getFocusListeners())
                                    listener.focusGained(new FocusEvent(inputComponent_, FocusEvent.FOCUS_GAINED));
                                CefFrame mainFrame = browser_.getMainFrame();
                                if (mainFrame == null)
                                    throw new AssertionError("OSR browser has no main frame after page readiness");
                                try {
                                    // BrowserHost.SetFocus crosses the CEF UI/renderer boundary asynchronously.
                                    // Do not dispatch synthetic input until the renderer confirms both document
                                    // focus and the intended active element, otherwise slower CI hosts can lose
                                    // the entire first input sequence even though JavaScript already executed.
                                    mainFrame.executeJavaScript("(()=>{const target=document.getElementById('i');const awaitFocus=()=>{target.focus();if(document.hasFocus()&&document.activeElement===target){document.title='" + INPUT_READY_TITLE + "';return;}setTimeout(awaitFocus,10);};awaitFocus();})();", testUrl, 1);
                                } finally {
                                    mainFrame.dispose();
                                }
                            } catch (Throwable throwable) {
                                failure.compareAndSet(null, throwable);
                                requestTermination();
                            }
                        });
                    }

                    private void markInputReady() {
                        inputReady_.set(true);
                        maybeDispatchInput();
                    }

                    private void maybeDispatchInput() {
                        if (!loaded_.get() || !painted_.get() || !inputReady_.get() || !inputDispatched_.compareAndSet(false, true)) return;
                        SwingUtilities.invokeLater(this::sendSyntheticInput);
                    }

                    private void sendSyntheticInput() {
                        try {
                            dispatchKeySequence(inputComponent_);
                            dispatchMouseSequence(inputComponent_);
                            dispatchLegacyInput(browser_);
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            requestTermination();
                        }
                    }

                    private void maybeFinish() {
                        if (pageInputComplete.get()) requestTermination();
                    }

                    private void requestTermination() {
                        if (terminationRequested_.compareAndSet(false, true)) terminateTest();
                    }

                    private void verifyClosedInputIsIgnored() {
                        MenuSelectionManager menuManager = MenuSelectionManager.defaultManager();
                        MenuElement[] selectedPath = createMenuSelectionPath();
                        menuManager.setSelectedPath(selectedPath);
                        try {
                            int focusCalls = ((AwtInputBrowser) browser_).getForwardedFocusCallCount();
                            dispatchClosedInput(inputComponent_);
                            assertTrue(focusCalls == ((AwtInputBrowser) browser_).getForwardedFocusCallCount(), "Retained focus listeners forwarded input after browser close");
                            assertArrayEquals(selectedPath, menuManager.getSelectedPath(), "Retained focus listeners cleared global menu selection after browser close");
                            postCloseInputSafe.set(true);
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        } finally {
                            menuManager.clearSelectedPath();
                            postCloseInputComplete.countDown();
                        }
                    }
                };
            };
            SwingUtilities.invokeAndWait(createFrame);
            Runnable timeoutAction = () -> {
                timedOut.set(true);
                failure.compareAndSet(null, new AssertionError("Timed out waiting for AWT input; last page title=" + lastTitle.get()));
                inputTimeoutTriggered.countDown();
                frame[0].terminateTest();
            };
            timeout = timeoutExecutor.schedule(timeoutAction, INPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                frame[0].awaitCompletion();
            } catch (AssertionError completionTimeout) {
                // TestFrame has the same bounded deadline. If its guard wins the race, wait only
                // for our scheduled signal so the assertion below retains the last renderer title.
                if (!inputTimeoutTriggered.await(5, TimeUnit.SECONDS)) throw completionTimeout;
            }
        } finally {
            if (timeout != null) timeout.cancel(false);
            timeoutExecutor.shutdownNow();
            assertTrue(timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(postCloseInputComplete.await(5, TimeUnit.SECONDS), "Post-close listener verification did not finish");
        assertNull(failure.get(), () -> "OSR input failed: " + failure.get());
        assertFalse(timedOut.get(), "The bounded OSR input deadline expired");
        assertTrue(pageInputComplete.get(), () -> "Page input was incomplete; last title=" + lastTitle.get());
        assertTrue(postCloseInputSafe.get(), "Listeners did not safely ignore input after browser close");
    }

    private static ScheduledExecutorService newTimeoutExecutor() {
        return Executors.newSingleThreadScheduledExecutor(CefBrowserOsrInputTest::newTimeoutThread);
    }

    private static Thread newTimeoutThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "JCEF-OSR-input-timeout");
        thread.setDaemon(true);
        return thread;
    }

    private static void dispatchKeySequence(Component component) {
        KeyEvent pressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 1L, KEY_MODIFIERS, KeyEvent.VK_M, 'M', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent repeated = new KeyEvent(component, KeyEvent.KEY_PRESSED, 2L, KEY_MODIFIERS, KeyEvent.VK_M, 'M', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent typed = new KeyEvent(component, KeyEvent.KEY_TYPED, 3L, KEY_MODIFIERS, KeyEvent.VK_UNDEFINED, 'M', KeyEvent.KEY_LOCATION_UNKNOWN);
        KeyEvent released = new KeyEvent(component, KeyEvent.KEY_RELEASED, 4L, KEY_MODIFIERS, KeyEvent.VK_M, 'M', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent pressedAfterRelease = new KeyEvent(component, KeyEvent.KEY_PRESSED, 5L, KEY_MODIFIERS, KeyEvent.VK_M, 'M', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent releasedAfterReset = new KeyEvent(component, KeyEvent.KEY_RELEASED, 6L, KEY_MODIFIERS, KeyEvent.VK_M, 'M', KeyEvent.KEY_LOCATION_STANDARD);
        for (KeyListener listener : component.getKeyListeners()) listener.keyPressed(pressed);
        for (KeyListener listener : component.getKeyListeners()) listener.keyPressed(repeated);
        for (KeyListener listener : component.getKeyListeners()) listener.keyTyped(typed);
        for (KeyListener listener : component.getKeyListeners()) listener.keyReleased(released);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(pressedAfterRelease);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(releasedAfterReset);

        KeyEvent focusResetFirst = new KeyEvent(component, KeyEvent.KEY_PRESSED, 7L, 0, KeyEvent.VK_N, 'N', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent focusResetSecond = new KeyEvent(component, KeyEvent.KEY_PRESSED, 8L, 0, KeyEvent.VK_N, 'N', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent focusResetReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 9L, 0, KeyEvent.VK_N, 'N', KeyEvent.KEY_LOCATION_STANDARD);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(focusResetFirst);
        for (FocusListener listener : component.getFocusListeners())
            listener.focusLost(new FocusEvent(component, FocusEvent.FOCUS_LOST));
        for (FocusListener listener : component.getFocusListeners())
            listener.focusGained(new FocusEvent(component, FocusEvent.FOCUS_GAINED));
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(focusResetSecond);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(focusResetReleased);

        assertFalse(component.getFocusTraversalKeysEnabled(), "OSR input component must deliver traversal keys to CEF");
        KeyEvent tabPressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 10L, 0, KeyEvent.VK_TAB, '\t', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent tabReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 11L, 0, KeyEvent.VK_TAB, '\t', KeyEvent.KEY_LOCATION_STANDARD);
        // The intentionally unrealized Canvas keeps this test independent of JOGL and platform
        // graphics bindings. AWT's focus manager intercepts dispatched Tab events before they reach
        // component listeners, so invoke the production-installed listeners directly for this key.
        for (KeyListener listener : component.getKeyListeners()) listener.keyPressed(tabPressed);
        for (KeyListener listener : component.getKeyListeners()) listener.keyReleased(tabReleased);

        KeyEvent unicodePressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 12L, 0, KeyEvent.VK_O, '\u03A9', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent unicode = new KeyEvent(component, KeyEvent.KEY_TYPED, 13L, 0, KeyEvent.VK_UNDEFINED, '\u03A9', KeyEvent.KEY_LOCATION_UNKNOWN);
        KeyEvent unicodeReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 14L, 0, KeyEvent.VK_O, '\u03A9', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent leftArrowPressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 15L, 0, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent leftArrowReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 16L, 0, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent leftShiftPressed =
                new KeyEvent(component, KeyEvent.KEY_PRESSED, 17L, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT);
        KeyEvent leftShiftReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 18L, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT);
        KeyEvent rightControlPressed =
                new KeyEvent(component, KeyEvent.KEY_PRESSED, 19L, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT);
        KeyEvent rightControlReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 20L, 0, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT);
        KeyEvent numpadPressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 21L, 0, KeyEvent.VK_NUMPAD1, '1', KeyEvent.KEY_LOCATION_NUMPAD);
        KeyEvent numpadReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 22L, 0, KeyEvent.VK_NUMPAD1, '1', KeyEvent.KEY_LOCATION_NUMPAD);
        KeyEvent keypadLeftPressed = new KeyEvent(component, KeyEvent.KEY_PRESSED, 23L, 0, KeyEvent.VK_KP_LEFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD);
        KeyEvent keypadLeftReleased = new KeyEvent(component, KeyEvent.KEY_RELEASED, 24L, 0, KeyEvent.VK_KP_LEFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_NUMPAD);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(unicodePressed);
        for (KeyListener listener : component.getKeyListeners()) listener.keyTyped(unicode);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(unicodeReleased);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(leftArrowPressed);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(leftArrowReleased);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(leftShiftPressed);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(leftShiftReleased);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(rightControlPressed);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(rightControlReleased);
        for (KeyListener listener : component.getKeyListeners()) listener.keyPressed(numpadPressed);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(numpadReleased);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyPressed(keypadLeftPressed);
        for (KeyListener listener : component.getKeyListeners())
            listener.keyReleased(keypadLeftReleased);
    }

    private static void dispatchMouseSequence(Component component) {
        int allMouseModifiers = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
                | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK
                | InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK
                | InputEvent.BUTTON3_DOWN_MASK;
        MouseEvent moved = new MouseEvent(component, MouseEvent.MOUSE_MOVED, 4L, allMouseModifiers, 30, 40, 0, false, MouseEvent.NOBUTTON);
        for (MouseMotionListener listener : component.getMouseMotionListeners())
            listener.mouseMoved(moved);

        dispatchMouseButton(component, 5L, 50, 60, MouseEvent.BUTTON2, InputEvent.BUTTON2_DOWN_MASK);
        dispatchMouseButton(component, 7L, 70, 80, MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK);

        MouseWheelEvent vertical = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 10L, 0, 90, 100, 90, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 4, 1, 0.4);
        MouseWheelEvent horizontal = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 11L, InputEvent.SHIFT_DOWN_MASK, 110, 120, 110, 120, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 4, 1, 0.4);
        MouseWheelEvent page = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 12L, 0, 130, 140, 130, 140, 0, false, MouseWheelEvent.WHEEL_BLOCK_SCROLL, 1, 1, 0.4);
        MouseWheelEvent pageMagnitude = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 13L, 0, 150, 160, 150, 160, 0, false, MouseWheelEvent.WHEEL_BLOCK_SCROLL, 1, 3, 2.6);
        MouseWheelEvent minimum = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 14L, 0, 170, 180, 170, 180, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 0, -0.001);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(vertical);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(horizontal);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(page);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(pageMagnitude);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(minimum);
    }

    private static void dispatchMouseButton(Component component, long when, int x, int y, int button, int downMask) {
        MouseEvent pressed = new MouseEvent(component, MouseEvent.MOUSE_PRESSED, when, downMask, x, y, 1, false, button);
        MouseEvent released = new MouseEvent(component, MouseEvent.MOUSE_RELEASED, when + 1L, 0, x, y, 1, false, button);
        for (MouseListener listener : component.getMouseListeners()) listener.mousePressed(pressed);
        for (MouseListener listener : component.getMouseListeners())
            listener.mouseReleased(released);
    }

    private static void dispatchClosedInput(Component component) {
        KeyEvent key = new KeyEvent(component, KeyEvent.KEY_PRESSED, 11L, 0, KeyEvent.VK_Z, 'z');
        MouseEvent mouse = new MouseEvent(component, MouseEvent.MOUSE_MOVED, 12L, 0, 1, 1, 0, false, MouseEvent.NOBUTTON);
        MouseWheelEvent wheel = new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, 13L, 0, 1, 1, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
        FocusEvent focusLost = new FocusEvent(component, FocusEvent.FOCUS_LOST);
        FocusEvent focusGained = new FocusEvent(component, FocusEvent.FOCUS_GAINED);
        for (KeyListener listener : component.getKeyListeners()) listener.keyPressed(key);
        for (MouseMotionListener listener : component.getMouseMotionListeners())
            listener.mouseMoved(mouse);
        for (MouseWheelListener listener : component.getMouseWheelListeners())
            listener.mouseWheelMoved(wheel);
        for (FocusListener listener : component.getFocusListeners()) listener.focusLost(focusLost);
        for (FocusListener listener : component.getFocusListeners()) listener.focusGained(focusGained);
    }

    private static MenuElement[] createMenuSelectionPath() {
        JMenu menu = new JMenu("OSR input lifecycle");
        JMenuItem item = new JMenuItem("retained selection");
        menu.add(item);
        return new MenuElement[] {menu, menu.getPopupMenu(), item};
    }

    private static void dispatchLegacyInput(CefBrowser browser) throws Exception {
        Method keyMethod = CefBrowser_N.class.getDeclaredMethod("sendKeyEvent", CefKeyEvent.class);
        keyMethod.setAccessible(true);
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_TYPE, 88, 'x', 0x1));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_REPEAT, 82, (char) 82, 0x2));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 82, (char) 82, 0x2));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 258, (char) 258, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 258, (char) 258, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 344, (char) 344, 0x1));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 344, (char) 344, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 320, (char) 320, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 320, (char) 320, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 67, (char) 67, 0x30));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 67, (char) 67, 0x30));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 65, (char) 65, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 65, (char) 65, 0));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 66, (char) 66, 0x1));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 66, (char) 66, 0x1));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 68, (char) 68, 0x10));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 68, (char) 68, 0x10));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_PRESS, 69, (char) 69, 0x11));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_RELEASE, 69, (char) 69, 0x11));
        keyMethod.invoke(browser, new CefKeyEvent(CefKeyEvent.KEY_TYPE, 81, 'q', 0x11));

        Method mouseMethod =
                CefBrowser_N.class.getDeclaredMethod("sendMouseEvent", CefMouseEvent.class);
        mouseMethod.setAccessible(true);
        mouseMethod.invoke(browser, new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, 10, 10, 0, 0, CefMouseEvent.BUTTON1_MASK | CefMouseEvent.BUTTON2_MASK));
    }

    private static String createInputPage() {
        int wheelScale = OS.isWindows() ? 120 : 40;
        int preciseMagnitude = (int) Math.round(0.4 * wheelScale);
        int minimumMagnitude = Math.max(1, (int) Math.round(0.001 * wheelScale));
        int pageDeltaMode = OS.isMacintosh() ? 0 : 2;
        boolean expectDomRepeat = !OS.isMacintosh();
        boolean expectDomNumLock = !OS.isMacintosh();
        // CEF's macOS bridge constructs synthetic NSEvents with isARepeat:NO, so the Java tracker
        // contract covers inference there while this live path still requires both key deliveries.
        // It maps EVENTFLAG_NUM_LOCK_ON to Cocoa's numeric-pad modifier, which preserves keypad
        // identity but does not surface as the DOM NumLock toggle state.
        // The same bridge normalizes EVENTFLAG_SCROLL_BY_PAGE to DOM pixel mode; Aura preserves
        // DOM_DELTA_PAGE. In both cases the rounded page magnitude remains observable.
        return "<!doctype html><html><head><meta charset=utf-8><style>html,body{width:100%;height:100%;margin:0}</style></head><body><input id=i><input id=tabTarget><script>"
                + "const expectDomRepeat=" + expectDomRepeat + ";"
                + "const expectDomNumLock=" + expectDomNumLock + ";"
                + "const seen={mFirst:false,mRepeat:false,mReset:false,nFirst:false,nReset:false,tab:false,tabFocus:false,unicode:false,arrowDown:false,arrowUp:false,keypadLeftDown:false,keypadLeftUp:false,leftShift:false,rightControl:false,numpad:false,legacyTyped:false,legacyTypedExact:false,legacyRepeat:false,legacyTab:false,legacyRight:false,legacyKeypad:false,legacyLocks:false,legacyLower:false,legacyShift:false,legacyCaps:false,legacyShiftCaps:false,move:false,legacyMove:false,middleDown:false,middleUp:false,rightDown:false,rightUp:false,vertical:false,horizontal:false,page:false,pageMagnitude:false,minimum:false};"
                + "let mIndex=0,nIndex=0,tabIndex=0,wheelIndex=0;"
                + "const report=()=>{const missing=Object.keys(seen).filter(k=>!seen[k]);document.title=missing.length?'missing:'+missing.join(','):'"
                + SUCCESS_TITLE + "';};"
                + "addEventListener('keydown',e=>{if(e.code==='KeyM'){const mods=e.shiftKey&&e.ctrlKey&&e.altKey&&e.metaKey;if(mIndex===0&&!e.repeat&&mods)seen.mFirst=true;if(mIndex===1&&(!expectDomRepeat||e.repeat)&&mods)seen.mRepeat=true;if(mIndex===2&&!e.repeat&&mods)seen.mReset=true;mIndex++;}if(e.code==='KeyN'){if(nIndex===0&&!e.repeat)seen.nFirst=true;if(nIndex===1&&!e.repeat)seen.nReset=true;nIndex++;}if(e.code==='Tab'&&e.location===0){if(tabIndex===0)seen.tab=true;if(tabIndex===1)seen.legacyTab=true;tabIndex++;}if(e.key==='ArrowLeft'&&e.location===0)seen.arrowDown=true;if(e.code==='Numpad4'&&e.key==='ArrowLeft'&&e.location===3)seen.keypadLeftDown=true;if(e.key==='Shift'&&e.location===1)seen.leftShift=true;if(e.key==='Control'&&e.location===2)seen.rightControl=true;if(e.code==='Numpad1'&&e.location===3)seen.numpad=true;if(e.code==='KeyR'&&e.ctrlKey&&(!expectDomRepeat||e.repeat))seen.legacyRepeat=true;if(e.code==='ShiftRight'&&e.location===2)seen.legacyRight=true;if(e.code==='Numpad0'&&e.location===3)seen.legacyKeypad=true;if(e.code==='KeyC'&&e.getModifierState('CapsLock')&&(!expectDomNumLock||e.getModifierState('NumLock')))seen.legacyLocks=true;if(e.code==='KeyA'&&e.key==='a'&&!e.shiftKey&&!e.getModifierState('CapsLock'))seen.legacyLower=true;if(e.code==='KeyB'&&e.key==='B'&&e.shiftKey&&!e.getModifierState('CapsLock'))seen.legacyShift=true;if(e.code==='KeyD'&&e.key==='D'&&!e.shiftKey&&e.getModifierState('CapsLock'))seen.legacyCaps=true;if(e.code==='KeyE'&&e.key==='e'&&e.shiftKey&&e.getModifierState('CapsLock'))seen.legacyShiftCaps=true;report();},true);"
                + "addEventListener('keyup',e=>{if(e.key==='ArrowLeft'&&e.location===0)seen.arrowUp=true;if(e.code==='Numpad4'&&e.key==='ArrowLeft'&&e.location===3)seen.keypadLeftUp=true;report();},true);"
                + "addEventListener('keypress',e=>{if(e.key==='\\u03A9')seen.unicode=true;if(e.key==='x')seen.legacyTyped=true;if(e.key==='q'&&e.shiftKey&&e.getModifierState('CapsLock'))seen.legacyTypedExact=true;report();},true);"
                + "addEventListener('beforeinput',e=>{if(e.data==='\\u03A9')seen.unicode=true;if(e.data==='x')seen.legacyTyped=true;report();},true);"
                + "addEventListener('input',e=>{if(e.data==='\\u03A9')seen.unicode=true;if(e.data==='x')seen.legacyTyped=true;report();},true);"
                + "addEventListener('mousemove',e=>{if(e.shiftKey&&e.ctrlKey&&e.altKey&&e.metaKey&&e.buttons===7)seen.move=true;if(!e.shiftKey&&!e.ctrlKey&&!e.altKey&&!e.metaKey&&e.buttons===5)seen.legacyMove=true;report();});"
                + "addEventListener('mousedown',e=>{if(e.button===1)seen.middleDown=true;if(e.button===2)seen.rightDown=true;report();});"
                + "addEventListener('mouseup',e=>{if(e.button===1)seen.middleUp=true;if(e.button===2)seen.rightUp=true;report();});"
                + "addEventListener('contextmenu',e=>e.preventDefault());"
                + "addEventListener('wheel',e=>{if(wheelIndex===0&&e.deltaMode===0&&e.deltaX===0&&e.deltaY==="
                + preciseMagnitude
                + ")seen.vertical=true;if(wheelIndex===1&&e.deltaMode===0&&e.shiftKey&&e.deltaX==="
                + preciseMagnitude
                + "&&e.deltaY===0)seen.horizontal=true;if(wheelIndex===2&&e.deltaMode==="
                + pageDeltaMode
                + "&&e.deltaX===0&&e.deltaY===1)seen.page=true;if(wheelIndex===3&&e.deltaMode==="
                + pageDeltaMode
                + "&&e.deltaX===0&&e.deltaY===3)seen.pageMagnitude=true;if(wheelIndex===4&&e.deltaMode===0&&e.deltaX===0&&e.deltaY===-"
                + minimumMagnitude
                + ")seen.minimum=true;wheelIndex++;e.preventDefault();report();},{passive:false});"
                + "const input=document.getElementById('i');document.getElementById('tabTarget').addEventListener('focus',()=>{seen.tabFocus=true;input.focus();report();});"
                + "document.title='" + PAGE_READY_TITLE + "';"
                + "</script></body></html>";
    }

    private static final class AwtInputBrowser extends CefBrowserOsr {
        private static final int WIDTH = 800;
        private static final int HEIGHT = 600;
        private final Component inputComponent_ = new Canvas();
        private final AtomicInteger forwardedFocusCalls_ = new AtomicInteger();

        private AwtInputBrowser(CefClient client, String url) {
            super(client, url, false, null);
            updateViewGeometry(0, 0, WIDTH, HEIGHT, new Point(0, 0));
            installAwtInputListeners(inputComponent_);
        }

        @Override
        public Component getUIComponent() {
            return inputComponent_;
        }

        @Override
        public void setFocus(boolean enable) {
            forwardedFocusCalls_.incrementAndGet();
            super.setFocus(enable);
        }

        private int getForwardedFocusCallCount() {
            return forwardedFocusCalls_.get();
        }

        private void notifyInitialSize() {
            wasResized(WIDTH, HEIGHT);
        }
    }

    private static final class InputDisplayHandler extends CefDisplayHandlerAdapter {
        private final Supplier<CefBrowser> browser_;
        private final AtomicReference<String> lastTitle_;
        private final AtomicBoolean complete_;
        private final AtomicBoolean pageReady_ = new AtomicBoolean();
        private final AtomicBoolean inputReady_ = new AtomicBoolean();
        private final Runnable onPageReady_;
        private final Runnable onInputReady_;
        private final Runnable onComplete_;

        private InputDisplayHandler(Supplier<CefBrowser> browser, AtomicReference<String> lastTitle, AtomicBoolean complete, Runnable onPageReady, Runnable onInputReady, Runnable onComplete) {
            browser_ = browser;
            lastTitle_ = lastTitle;
            complete_ = complete;
            onPageReady_ = onPageReady;
            onInputReady_ = onInputReady;
            onComplete_ = onComplete;
        }

        @Override
        public void onTitleChange(CefBrowser browser, String title) {
            if (browser != browser_.get()) return;
            lastTitle_.set(title);
            if (PAGE_READY_TITLE.equals(title) && pageReady_.compareAndSet(false, true)) {
                onPageReady_.run();
                return;
            }
            if (INPUT_READY_TITLE.equals(title) && inputReady_.compareAndSet(false, true)) {
                onInputReady_.run();
                return;
            }
            if (SUCCESS_TITLE.equals(title) && complete_.compareAndSet(false, true))
                onComplete_.run();
        }
    }
}
