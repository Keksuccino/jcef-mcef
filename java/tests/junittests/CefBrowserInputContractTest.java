// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.EventFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

class CefBrowserInputContractTest {
    @Test
    void keepsDistinctAwtAndMcefInputBridges() throws Exception {
        assertProtectedFinalMethod("sendKeyEvent", CefKeyEvent.class);
        assertProtectedFinalMethod("sendAwtKeyEvent", java.awt.event.KeyEvent.class);
        assertProtectedFinalMethod("sendMouseEvent", CefMouseEvent.class);
        assertProtectedFinalMethod("sendAwtMouseEvent", java.awt.event.MouseEvent.class);
        assertProtectedFinalMethod("sendMouseWheelEvent", CefMouseWheelEvent.class);
        assertProtectedFinalMethod("sendAwtMouseWheelEvent", java.awt.event.MouseWheelEvent.class);

        assertPrivateNativeMethod("N_SendKeyEvent", CefKeyEvent.class);
        assertPrivateNativeMethod("N_SendKeyEventAwt", java.awt.event.KeyEvent.class, boolean.class);
        assertPrivateNativeMethod("N_SendMouseEvent", CefMouseEvent.class);
        assertPrivateNativeMethod("N_SendMouseEventAwt", java.awt.event.MouseEvent.class);
        assertPrivateNativeMethod("N_SendMouseWheelEvent", CefMouseWheelEvent.class);
        assertPrivateNativeMethod("N_SendMouseWheelEventAwt", java.awt.event.MouseWheelEvent.class);
    }

    @Test
    void preservesMcefEventDtoSourceApi() {
        CefKeyEvent key = new CefKeyEvent(CefKeyEvent.KEY_PRESS, 65, 'a', 0x5);
        key.scancode = 42L;
        assertEquals(CefKeyEvent.KEY_PRESS, key.id);
        assertEquals(65, key.keyCode);
        assertEquals('a', key.keyChar);
        assertEquals(0x5, key.modifiers);
        assertEquals(42L, key.scancode);
        assertEquals(key.id, key.getID());
        assertEquals(key.keyCode, key.getKeyCode());
        assertEquals(key.keyChar, key.getKeyChar());
        assertEquals(key.modifiers, key.getModifiers());
        assertEquals(0, CefKeyEvent.KEY_RELEASE);
        assertEquals(1, CefKeyEvent.KEY_PRESS);
        assertEquals(2, CefKeyEvent.KEY_TYPE);
        assertEquals(3, CefKeyEvent.KEY_REPEAT);

        CefMouseEvent mouse = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, 11, 12, 2, 1, CefMouseEvent.BUTTON2_MASK);
        assertEquals(CefMouseEvent.MOUSE_MOVED, mouse.id);
        assertEquals(11, mouse.x);
        assertEquals(12, mouse.y);
        assertEquals(2, mouse.clickCount);
        assertEquals(1, mouse.button);
        assertEquals(CefMouseEvent.BUTTON2_MASK, mouse.modifiers);
        assertEquals(mouse.id, mouse.getID());
        assertEquals(mouse.x, mouse.getX());
        assertEquals(mouse.y, mouse.getY());
        assertEquals(mouse.clickCount, mouse.getClickCount());
        assertEquals(mouse.button, mouse.getButton());
        assertEquals(mouse.modifiers, mouse.getModifiers());
        assertEquals(503, CefMouseEvent.MOUSE_MOVED);
        assertEquals(505, CefMouseEvent.MOUSE_EXIT);
        assertEquals(0x10, CefMouseEvent.BUTTON1_MASK);
        assertEquals(0x20, CefMouseEvent.BUTTON2_MASK);
        assertEquals(0x40, CefMouseEvent.BUTTON3_MASK);

        CefMouseWheelEvent wheel =
                new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, 21, 22, -2.5, 0x3);
        wheel.amount = 4;
        assertEquals(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, wheel.id);
        assertEquals(21, wheel.x);
        assertEquals(22, wheel.y);
        assertEquals(-2.5, wheel.delta);
        assertEquals(0x3, wheel.modifiers);
        assertEquals(4, wheel.amount);
        assertEquals(wheel.id, wheel.getScrollType());
        assertEquals(wheel.x, wheel.getX());
        assertEquals(wheel.y, wheel.getY());
        assertEquals(wheel.delta, wheel.getWheelRotation());
        assertEquals(wheel.modifiers, wheel.getModifiers());
        assertEquals(-10.0, wheel.getUnitsToScroll());
        assertEquals(0, CefMouseWheelEvent.WHEEL_UNIT_SCROLL);
        assertEquals(1, CefMouseWheelEvent.WHEEL_BLOCK_SCROLL);
    }

    @Test
    void exposesExactCefEventFlagValues() {
        int[] actual = {EventFlags.EVENTFLAG_CAPS_LOCK_ON, EventFlags.EVENTFLAG_SHIFT_DOWN,
                EventFlags.EVENTFLAG_CONTROL_DOWN, EventFlags.EVENTFLAG_ALT_DOWN,
                EventFlags.EVENTFLAG_LEFT_MOUSE_BUTTON, EventFlags.EVENTFLAG_MIDDLE_MOUSE_BUTTON,
                EventFlags.EVENTFLAG_RIGHT_MOUSE_BUTTON, EventFlags.EVENTFLAG_COMMAND_DOWN,
                EventFlags.EVENTFLAG_NUM_LOCK_ON, EventFlags.EVENTFLAG_IS_KEY_PAD,
                EventFlags.EVENTFLAG_IS_LEFT, EventFlags.EVENTFLAG_IS_RIGHT,
                EventFlags.EVENTFLAG_ALTGR_DOWN, EventFlags.EVENTFLAG_IS_REPEAT,
                EventFlags.EVENTFLAG_PRECISION_SCROLLING_DELTA,
                EventFlags.EVENTFLAG_SCROLL_BY_PAGE};
        assertEquals(0, EventFlags.EVENTFLAG_NONE);
        for (int bit = 0; bit < actual.length; ++bit)
            assertEquals(1 << bit, actual[bit], "EventFlags bit " + bit);
    }

    @Test
    void keepsWindowsAwtNumpadOverrideAheadOfEncodedScanDetection() throws Exception {
        String source = readNativeBrowserSource();
        int functionStart = source.indexOf("bool IsWindowsExtendedKey(");
        assertTrue(functionStart >= 0);
        int numpadOverride =
                source.indexOf("key_location == awt::kKeyLocationNumpad", functionStart);
        int encodedScanDetection = source.indexOf("(scan_code & 0xFF00U)", functionStart);
        assertTrue(numpadOverride > functionStart && encodedScanDetection > numpadOverride);
        String overrideSource = source.substring(numpadOverride, encodedScanDetection);
        assertTrue(overrideSource.contains("awt::kVkEnter"));
        assertTrue(overrideSource.contains("awt::kVkDivide"));
        assertTrue(overrideSource.contains("awt::kVkNumLock"));
        assertFalse(overrideSource.contains("awt::kVkLeft"));
        assertFalse(overrideSource.contains("awt::kVkRight"));
        assertFalse(overrideSource.contains("awt::kVkUp"));
        assertFalse(overrideSource.contains("awt::kVkDown"));
        assertFalse(overrideSource.contains("awt::kVkInsert"));
        assertFalse(overrideSource.contains("awt::kVkDelete"));
    }

    @Test
    void keepsLinuxGlfwPrintableIdentityAheadOfCustomCharacterFallback() throws Exception {
        String source = readNativeBrowserSource();
        int functionStart = source.indexOf("unsigned int GetGlfwLinuxKeySym(");
        assertTrue(functionStart >= 0);
        int printableIdentity =
                source.indexOf("IsGlfwStandardPrintableKey(key_code)", functionStart);
        int specialKeySwitch = source.indexOf("switch (key_code)", functionStart);
        assertTrue(printableIdentity > functionStart && specialKeySwitch > printableIdentity);
        String identitySource = source.substring(printableIdentity, specialKeySwitch);
        assertTrue(identitySource.contains("return static_cast<unsigned int>(key_code)"));
    }

    @Test
    void modelsMcefPrintableInputAsPhysicalCharacterPhysicalSequence() {
        CefKeyEvent[] unmodifiedSequence = CefBrowserOsrInputTest.createMcefPrintableKeySequence('X', 53L, 'x', 0);
        CefKeyEvent[] shiftCapsSequence = CefBrowserOsrInputTest.createMcefPrintableKeySequence('Q', 24L, 'q', 0x11);

        assertEquals(3, unmodifiedSequence.length);
        assertLegacyKeyEvent(unmodifiedSequence[0], CefKeyEvent.KEY_PRESS, 'X', 'X', 0, 53L);
        assertLegacyKeyEvent(unmodifiedSequence[1], CefKeyEvent.KEY_TYPE, 'x', 'x', 0, 0L);
        assertLegacyKeyEvent(unmodifiedSequence[2], CefKeyEvent.KEY_RELEASE, 'X', 'X', 0, 53L);
        assertEquals(3, shiftCapsSequence.length);
        assertLegacyKeyEvent(shiftCapsSequence[0], CefKeyEvent.KEY_PRESS, 'Q', 'Q', 0x11, 24L);
        assertLegacyKeyEvent(shiftCapsSequence[1], CefKeyEvent.KEY_TYPE, 'q', 'q', 0, 0L);
        assertLegacyKeyEvent(shiftCapsSequence[2], CefKeyEvent.KEY_RELEASE, 'Q', 'Q', 0x11, 24L);
    }

    @Test
    void keepsJcefLinuxCharacterIntentIndependentOfCefDomKeyTranslation() throws Exception {
        // The public CEF API exposes only the post-translation browser event. Keep a deterministic
        // source contract for JCEF's pre-CEF character intent so the accepted CEF 151 Linux quirk
        // cannot hide a regression in JCEF's own mapping.
        String browserSource = readNativeBrowserSource();
        String rawCharacterSource = sourceBetween(browserSource, "char16_t GetGlfwRawCharacter(", "char16_t GetAwtPhysicalUnmodifiedCharacter(");
        String linuxKeySymSource = sourceBetween(browserSource, "unsigned int GetLinuxKeySym(", "#endif  // defined(OS_LINUX)");
        String sendKeySource = sourceBetween(browserSource, "void SendJavaKeyEvent(", "void SendJavaMouseEvent(");
        String platformSource = readNativeSource("key_event_platform_util.cpp");
        String linuxNativeFallbackSource = sourceBetween(platformSource, "int GetLinuxXkbKeyCodeFallback(", "bool IsBoundedPositiveCode(");

        assertTrue(rawCharacterSource.contains("shift != caps_lock"));
        assertTrue(rawCharacterSource.contains("key_code + ('a' - 'A')"));
        assertTrue(linuxKeySymSource.contains("if (typed)"));
        assertTrue(linuxKeySymSource.contains("return GetUnicodeKeySym(key_char);"));
        assertTrue(sendKeySource.contains("if (typed) {\n    unmodified_character = key_char;"));
        assertTrue(sendKeySource.contains("cef_event.character = key_char;"));
        assertTrue(linuxNativeFallbackSource.contains("if (typed)\n    return linux_xkb::kUnknown;"));
        assertTrue(browserSource.contains("std::string_view(CEF_VERSION) == \"151.2.3+g89cd581+chromium-151.0.7922.34\""));
        assertTrue(browserSource.contains("CEF_COMMIT_NUMBER == 3553"));
        assertTrue(browserSource.contains("CefBrowserPlatformDelegateNativeLinux::TranslateUiKeyEvent"));
        assertTrue(browserSource.contains("XKeysymForWindowsKeyCode"));
        assertTrue(browserSource.contains("89cd5813e47d84c68e56ced336c2c01b7dc77b8d"));
        assertTrue(browserSource.contains("CHROME_VERSION_MAJOR == 151"));
        assertTrue(browserSource.contains("CHROME_VERSION_BUILD == 7922"));
        assertTrue(browserSource.contains("CHROME_VERSION_PATCH == 34"));
    }

    @Test
    void normalizesSourceContractLineEndings(@TempDir Path tempDirectory) throws Exception {
        Path sourcePath = tempDirectory.resolve("source.cpp");
        Files.writeString(sourcePath, "first\r\nsecond\rthird\nfourth");
        assertEquals("first\nsecond\nthird\nfourth", readSource(sourcePath));
    }

    @Test
    void keepsWindowsAwtWheelInversionAndCloseCheckOnCefUiThread() throws Exception {
        String source = readNativeBrowserSource();
        int functionStart = source.indexOf("void SendWindowsAwtMouseWheelEvent(");
        assertTrue(functionStart >= 0);
        int uiGate = source.indexOf("if (!CefCurrentlyOn(TID_UI))", functionStart);
        int postTask = source.indexOf("CefPostTask(TID_UI", uiGate);
        int validityCheck = source.indexOf("!browser.get() || !browser->IsValid()", postTask);
        int hostLookup = source.indexOf("browser->GetHost()", validityCheck);
        int hostValidityCheck = source.indexOf("if (!host)", hostLookup);
        int inversion = source.indexOf("GetWindowsCefWheelDelta(target_delta, horizontal)", hostValidityCheck);
        int hostSend = source.indexOf("host->SendMouseWheelEvent(event, delta_x, delta_y)", inversion);
        assertTrue(uiGate > functionStart && postTask > uiGate && validityCheck > postTask);
        assertTrue(hostLookup > validityCheck && hostValidityCheck > hostLookup);
        assertTrue(inversion > hostValidityCheck && hostSend > inversion);
        String postSource = source.substring(postTask, validityCheck);
        assertTrue(postSource.contains("browser, event, target_delta, horizontal"));
        String functionSignature = source.substring(functionStart, uiGate);
        assertTrue(functionSignature.contains("CefRefPtr<CefBrowser> browser"));
        assertTrue(functionSignature.contains("CefMouseEvent event"));
    }

    @Test
    void identifiesLiveWheelEventsWithoutDependingOnDroppedAxisOrdering() {
        String page = CefBrowserOsrInputTest.createInputPage(new WindowsWheelTestSupport.Delivery(false, true));
        assertTrue(page.contains("const expectVerticalWheelDelivery=false;"));
        assertTrue(page.contains("const expectHorizontalWheelDelivery=true;"));
        assertTrue(page.contains("e.clientX===90&&e.clientY===100"));
        assertTrue(page.contains("e.clientX===110&&e.clientY===120"));
        assertTrue(page.contains("e.clientX===130&&e.clientY===140"));
        assertTrue(page.contains("e.clientX===150&&e.clientY===160"));
        assertTrue(page.contains("e.clientX===170&&e.clientY===180"));
        assertTrue(page.contains("e.clientX===190&&e.clientY===200"));
        assertFalse(page.contains("wheelIndex"));
    }

    @Test
    void tracksAwtRepeatAcrossPressedTypedReleaseLocationAndClearLifecycles() throws Exception {
        Class<?> trackerClass = Class.forName("org.cef.browser.CefAwtKeyRepeatTracker");
        Constructor<?> constructor = trackerClass.getDeclaredConstructor();
        Method update = trackerClass.getDeclaredMethod("update", KeyEvent.class);
        Method clear = trackerClass.getDeclaredMethod("clear");
        constructor.setAccessible(true);
        update.setAccessible(true);
        clear.setAccessible(true);
        Object tracker = constructor.newInstance();
        Canvas source = new Canvas();
        KeyEvent typedWithoutPress = new KeyEvent(source, KeyEvent.KEY_TYPED, 1L, 0, KeyEvent.VK_UNDEFINED, 'z', KeyEvent.KEY_LOCATION_UNKNOWN);
        KeyEvent firstA = new KeyEvent(source, KeyEvent.KEY_PRESSED, 2L, 0, KeyEvent.VK_A, 'a', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent repeatA = new KeyEvent(source, KeyEvent.KEY_PRESSED, 3L, 0, KeyEvent.VK_A, 'a', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent typedA = new KeyEvent(source, KeyEvent.KEY_TYPED, 4L, 0, KeyEvent.VK_UNDEFINED, 'a', KeyEvent.KEY_LOCATION_UNKNOWN);
        KeyEvent typedContinuation = new KeyEvent(source, KeyEvent.KEY_TYPED, 5L, 0, KeyEvent.VK_UNDEFINED, '\u0301', KeyEvent.KEY_LOCATION_UNKNOWN);
        KeyEvent releaseA = new KeyEvent(source, KeyEvent.KEY_RELEASED, 6L, 0, KeyEvent.VK_A, 'a', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent firstB = new KeyEvent(source, KeyEvent.KEY_PRESSED, 7L, 0, KeyEvent.VK_B, 'b', KeyEvent.KEY_LOCATION_STANDARD);
        KeyEvent leftShift =
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 8L, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT);
        KeyEvent rightShift =
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 9L, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT);

        assertFalse(updateRepeatTracker(update, tracker, typedWithoutPress));
        assertFalse(updateRepeatTracker(update, tracker, firstA));
        assertTrue(updateRepeatTracker(update, tracker, repeatA));
        assertTrue(updateRepeatTracker(update, tracker, typedA));
        assertTrue(updateRepeatTracker(update, tracker, typedContinuation));
        assertFalse(updateRepeatTracker(update, tracker, releaseA));
        assertFalse(updateRepeatTracker(update, tracker, typedA));
        assertFalse(updateRepeatTracker(update, tracker, firstA));
        assertTrue(updateRepeatTracker(update, tracker, repeatA));
        assertFalse(updateRepeatTracker(update, tracker, firstB));
        assertFalse(updateRepeatTracker(update, tracker, typedA));

        clear.invoke(tracker);
        assertFalse(updateRepeatTracker(update, tracker, leftShift));
        assertFalse(updateRepeatTracker(update, tracker, rightShift));
        assertTrue(updateRepeatTracker(update, tracker, leftShift));
        clear.invoke(tracker);
        assertFalse(updateRepeatTracker(update, tracker, typedA));
        assertFalse(updateRepeatTracker(update, tracker, leftShift));
    }

    @Test
    void nullInputIsIgnoredBeforeBrowserCreation() {
        InputHarness browser = new InputHarness();
        assertDoesNotThrow(() -> browser.sendAwt((KeyEvent) null));
        assertDoesNotThrow(() -> browser.sendAwt((MouseEvent) null));
        assertDoesNotThrow(() -> browser.sendAwt((MouseWheelEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy((CefKeyEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy((CefMouseEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy((CefMouseWheelEvent) null));
        assertDoesNotThrow(browser::sendLegacyNullsWithoutCasts);
        assertDoesNotThrow(() -> browser.dragEnter(null, new Point()));
        assertDoesNotThrow(() -> browser.dragOver(null));
        assertDoesNotThrow(() -> browser.dragDrop(null));
    }

    @Test
    void closedBrowserIgnoresAwtLegacyAndDragInput() {
        InputHarness browser = new InputHarness();
        Canvas source = new Canvas();
        KeyEvent key = new KeyEvent(source, KeyEvent.KEY_PRESSED, 1L, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_A, 'A');
        MouseEvent mouse = new MouseEvent(source, MouseEvent.MOUSE_PRESSED, 2L, InputEvent.BUTTON1_DOWN_MASK, 3, 4, 1, false, MouseEvent.BUTTON1);
        MouseWheelEvent wheel = new MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, 3L, 0, 5, 6, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 2, 1);
        CefKeyEvent legacyKey = new CefKeyEvent(CefKeyEvent.KEY_PRESS, 65, 'a', 0x1);
        CefMouseEvent legacyMouse = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, 3, 4, 1, 0, CefMouseEvent.BUTTON1_MASK);
        CefMouseWheelEvent legacyWheel =
                new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, 5, 6, 1.0, 0x1);

        browser.onBeforeClose();

        assertDoesNotThrow(() -> browser.sendAwt(key));
        assertDoesNotThrow(() -> browser.sendAwt(mouse));
        assertDoesNotThrow(() -> browser.sendAwt(wheel));
        assertDoesNotThrow(() -> browser.sendAwt((KeyEvent) null));
        assertDoesNotThrow(() -> browser.sendAwt((MouseEvent) null));
        assertDoesNotThrow(() -> browser.sendAwt((MouseWheelEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy(legacyKey));
        assertDoesNotThrow(() -> browser.sendLegacy(legacyMouse));
        assertDoesNotThrow(() -> browser.sendLegacy(legacyWheel));
        assertDoesNotThrow(() -> browser.sendLegacy((CefKeyEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy((CefMouseEvent) null));
        assertDoesNotThrow(() -> browser.sendLegacy((CefMouseWheelEvent) null));
        assertDoesNotThrow(() -> browser.dragEnter(null, null));
        assertDoesNotThrow(() -> browser.dragOver(null));
        assertDoesNotThrow(browser::dragLeave);
        assertDoesNotThrow(() -> browser.dragDrop(null));
    }

    private static void assertProtectedFinalMethod(String name, Class<?> parameterType) throws Exception {
        Method method = CefBrowser_N.class.getDeclaredMethod(name, parameterType);
        assertTrue(Modifier.isProtected(method.getModifiers()));
        assertTrue(Modifier.isFinal(method.getModifiers()));
    }

    private static void assertPrivateNativeMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method method = CefBrowser_N.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isFinal(method.getModifiers()));
        assertTrue(Modifier.isNative(method.getModifiers()));
    }

    private static void assertLegacyKeyEvent(CefKeyEvent event, int id, int keyCode, char keyChar, int modifiers, long scanCode) {
        assertEquals(id, event.id);
        assertEquals(keyCode, event.keyCode);
        assertEquals(keyChar, event.keyChar);
        assertEquals(modifiers, event.modifiers);
        assertEquals(scanCode, event.scancode);
    }

    private static String readNativeBrowserSource() throws Exception {
        return readNativeSource("CefBrowser_N.cpp");
    }

    private static String readNativeSource(String fileName) throws Exception {
        Path sourcePath = Path.of(System.getProperty("user.dir"), "native", fileName);
        assertTrue(Files.isRegularFile(sourcePath), "Run source contract tests from the repository root");
        return readSource(sourcePath);
    }

    private static String readSource(Path sourcePath) throws Exception {
        // Git may materialize source files with CRLF on Windows, while source contract literals use LF.
        return Files.readString(sourcePath).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sourceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "Missing source marker: " + startMarker);
        assertTrue(end > start, "Missing source marker after " + startMarker + ": " + endMarker);
        return source.substring(start, end);
    }

    private static boolean updateRepeatTracker(Method update, Object tracker, KeyEvent event) throws Exception {
        return (Boolean) update.invoke(tracker, event);
    }

    private static final class InputHarness extends CefBrowserOsr {
        private InputHarness() {
            super(null, "about:blank", false, null);
        }

        private void sendAwt(KeyEvent event) {
            sendAwtKeyEvent(event);
        }

        private void sendAwt(MouseEvent event) {
            sendAwtMouseEvent(event);
        }

        private void sendAwt(MouseWheelEvent event) {
            sendAwtMouseWheelEvent(event);
        }

        private void sendLegacy(CefKeyEvent event) {
            sendKeyEvent(event);
        }

        private void sendLegacy(CefMouseEvent event) {
            sendMouseEvent(event);
        }

        private void sendLegacy(CefMouseWheelEvent event) {
            sendMouseWheelEvent(event);
        }

        private void sendLegacyNullsWithoutCasts() {
            sendKeyEvent(null);
            sendMouseEvent(null);
            sendMouseWheelEvent(null);
        }

        private void dragEnter(CefDragData data, Point point) {
            dragTargetDragEnter(data, point, EventFlags.EVENTFLAG_SHIFT_DOWN, CefDragData.DragOperations.DRAG_OPERATION_COPY);
        }

        private void dragOver(Point point) {
            dragTargetDragOver(point, EventFlags.EVENTFLAG_CONTROL_DOWN, CefDragData.DragOperations.DRAG_OPERATION_MOVE);
        }

        private void dragLeave() {
            dragTargetDragLeave();
        }

        private void dragDrop(Point point) {
            dragTargetDrop(point, EventFlags.EVENTFLAG_ALT_DOWN);
        }
    }
}
