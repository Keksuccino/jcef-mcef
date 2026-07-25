// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.callback.CefCommandLine;
import org.cef.event.CefPointerType;
import org.cef.event.CefTouchEvent;
import org.cef.event.CefTouchEventType;
import org.cef.misc.EventFlags;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class CefTouchEventTest {
    private static final int ALL_CEF_EVENT_FLAGS = 0xFFFF;

    @Test
    void exposesExactCef151EnumValues() {
        assertEquals(0, CefTouchEventType.RELEASED.getValue());
        assertEquals(1, CefTouchEventType.PRESSED.getValue());
        assertEquals(2, CefTouchEventType.MOVED.getValue());
        assertEquals(3, CefTouchEventType.CANCELLED.getValue());
        for (CefTouchEventType type : CefTouchEventType.values())
            assertEquals(type, CefTouchEventType.fromValue(type.getValue()));
        assertThrows(IllegalArgumentException.class, () -> CefTouchEventType.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefTouchEventType.fromValue(4));

        assertEquals(0, CefPointerType.TOUCH.getValue());
        assertEquals(1, CefPointerType.MOUSE.getValue());
        assertEquals(2, CefPointerType.PEN.getValue());
        assertEquals(3, CefPointerType.ERASER.getValue());
        assertEquals(4, CefPointerType.UNKNOWN.getValue());
        for (CefPointerType type : CefPointerType.values())
            assertEquals(type, CefPointerType.fromValue(type.getValue()));
        assertThrows(IllegalArgumentException.class, () -> CefPointerType.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefPointerType.fromValue(5));
    }

    @Test
    void preservesEveryImmutableTouchMetadataField() {
        CefTouchEvent event = new CefTouchEvent(73, -12.5f, 900.25f, 4.5f, 7.75f, 2.25f, 0.625f, CefTouchEventType.MOVED, ALL_CEF_EVENT_FLAGS, CefPointerType.ERASER);
        CefTouchEvent equal = new CefTouchEvent(73, -12.5f, 900.25f, 4.5f, 7.75f, 2.25f, 0.625f, CefTouchEventType.MOVED, ALL_CEF_EVENT_FLAGS, CefPointerType.ERASER);
        CefTouchEvent different = new CefTouchEvent(73, -12.5f, 900.25f, 4.5f, 7.75f, 2.25f, 0.5f, CefTouchEventType.MOVED, ALL_CEF_EVENT_FLAGS, CefPointerType.ERASER);

        assertEquals(73, event.getId());
        assertEquals(-12.5f, event.getX());
        assertEquals(900.25f, event.getY());
        assertEquals(4.5f, event.getRadiusX());
        assertEquals(7.75f, event.getRadiusY());
        assertEquals(2.25f, event.getRotationAngle());
        assertEquals(0.625f, event.getPressure());
        assertEquals(CefTouchEventType.MOVED, event.getType());
        assertEquals(ALL_CEF_EVENT_FLAGS, event.getModifiers());
        assertEquals(CefPointerType.ERASER, event.getPointerType());
        assertEquals(event, equal);
        assertEquals(event.hashCode(), equal.hashCode());
        assertNotEquals(event, different);
        assertTrue(event.toString().contains("modifiers=0xffff"));
        assertTrue(Modifier.isFinal(CefTouchEvent.class.getModifiers()));
        for (Field field : CefTouchEvent.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
        }
    }

    @Test
    void convenienceConstructorUsesCefTouchDefaults() {
        CefTouchEvent event = new CefTouchEvent(5, 12.5f, -6.25f, CefTouchEventType.PRESSED);
        assertEquals(5, event.getId());
        assertEquals(12.5f, event.getX());
        assertEquals(-6.25f, event.getY());
        assertEquals(0.0f, event.getRadiusX());
        assertEquals(0.0f, event.getRadiusY());
        assertEquals(0.0f, event.getRotationAngle());
        assertEquals(0.0f, event.getPressure());
        assertEquals(CefTouchEventType.PRESSED, event.getType());
        assertEquals(EventFlags.EVENTFLAG_NONE, event.getModifiers());
        assertEquals(CefPointerType.TOUCH, event.getPointerType());
    }

    @Test
    void validatesTheCompleteCefTouchDomainWithoutClamping() {
        assertDoesNotThrow(() -> new CefTouchEvent(Integer.MIN_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertDoesNotThrow(() -> new CefTouchEvent(Integer.MAX_VALUE, 0.0f, -0.0f, 0.0f, Float.MAX_VALUE, Math.nextDown((float) Math.PI), 1.0f, CefTouchEventType.RELEASED, ALL_CEF_EVENT_FLAGS, CefPointerType.UNKNOWN));

        assertThrows(IllegalArgumentException.class, () -> validEventWithId(-1));
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, invalid, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, invalid, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, invalid, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, invalid, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, invalid, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
            assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, invalid, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        }
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, -Float.MIN_VALUE, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, -Float.MIN_VALUE, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, -Float.MIN_VALUE, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, (float) Math.PI, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, Float.MAX_VALUE, 0.0f, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -Float.MIN_VALUE, CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, Math.nextUp(1.0f), CefTouchEventType.PRESSED, 0, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 1 << 16, CefPointerType.TOUCH));
        assertThrows(IllegalArgumentException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, Integer.MIN_VALUE, CefPointerType.TOUCH));
        assertThrows(NullPointerException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, null, 0, CefPointerType.TOUCH));
        assertThrows(NullPointerException.class, () -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.PRESSED, 0, null));
    }

    @Test
    void keepsThirdPartyBrowserImplementationsSourceCompatible() throws Throwable {
        Method method = CefBrowser.class.getMethod("sendTouchEvent", CefTouchEvent.class);
        assertTrue(method.isDefault());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        InvocationHandler handler = CefTouchEventTest::invokeDefaultBrowserMethod;
        CefBrowser thirdParty = (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
        assertThrows(NullPointerException.class, () -> thirdParty.sendTouchEvent(null));
        assertThrows(UnsupportedOperationException.class, () -> thirdParty.sendTouchEvent(validEventWithId(1)));
    }

    @Test
    void validatesNullBeforeTheNativeLifecycleGate() {
        TouchHarness browser = new TouchHarness();
        assertThrows(NullPointerException.class, () -> browser.sendTouchEvent(null));
        assertDoesNotThrow(() -> browser.sendTouchEvent(validEventWithId(1)));
        browser.onBeforeClose();
        assertThrows(NullPointerException.class, () -> browser.sendTouchEvent(null));
        assertDoesNotThrow(() -> browser.sendTouchEvent(validEventWithId(1)));
    }

    @Test
    void enablesTouchHandlingOnlyForNativeJunitBrowserProcesses() {
        AtomicReference<String> touchEvents = new AtomicReference<String>("disabled");
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger appends = new AtomicInteger();
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("hasSwitch")) return touchEvents.get() != null;
            if (method.getName().equals("getSwitchValue")) return touchEvents.get() == null ? "" : touchEvents.get();
            if (method.getName().equals("removeSwitch")) {
                removals.incrementAndGet();
                touchEvents.set(null);
                return null;
            }
            if (method.getName().equals("appendSwitchWithValue")) {
                appends.incrementAndGet();
                assertEquals(TouchTestCommandLine.TOUCH_EVENTS_SWITCH, arguments[0]);
                touchEvents.set((String) arguments[1]);
                return null;
            }
            throw new AssertionError("Unexpected CefCommandLine call: " + method.getName());
        };
        CefCommandLine commandLine = (CefCommandLine) Proxy.newProxyInstance(CefCommandLine.class.getClassLoader(), new Class<?>[] {CefCommandLine.class}, handler);

        TouchTestCommandLine.configureBrowserProcess("renderer", commandLine);
        assertEquals("disabled", touchEvents.get());
        TouchTestCommandLine.configureBrowserProcess("", commandLine);
        TouchTestCommandLine.configureBrowserProcess("", commandLine);

        assertEquals(TouchTestCommandLine.TOUCH_EVENTS_ENABLED, touchEvents.get());
        assertEquals(1, removals.get());
        assertEquals(1, appends.get());

        touchEvents.set(null);
        TouchTestCommandLine.configureBrowserProcess("", commandLine);
        assertEquals(TouchTestCommandLine.TOUCH_EVENTS_ENABLED, touchEvents.get());
        assertEquals(1, removals.get());
        assertEquals(2, appends.get());
        TouchTestCommandLine.configureBrowserProcess("", commandLine);
        assertEquals(1, removals.get());
        assertEquals(2, appends.get());
    }

    @Test
    void keepsPrimitiveLifecycleSafeWindowlessNativeBridge() throws Exception {
        Method nativeMethod = CefBrowser_N.class.getDeclaredMethod("N_SendTouchEvent", int.class, float.class, float.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class);
        assertTrue(Modifier.isPrivate(nativeMethod.getModifiers()));
        assertTrue(Modifier.isFinal(nativeMethod.getModifiers()));
        assertTrue(Modifier.isNative(nativeMethod.getModifiers()));

        String header = readSource("native", "CefBrowser_N.h");
        String javaSource = readSource("java", "org", "cef", "browser", "CefBrowser_N.java");
        String interfaceSource = readSource("java", "org", "cef", "browser", "CefBrowser.java");
        String nativeSource = readSource("native", "CefBrowser_N.cpp");
        assertTrue(header.contains("Method:    N_SendTouchEvent\n * Signature: (IFFFFFFIII)V"));

        String javaBridge = sourceBetween(javaSource, "public void sendTouchEvent(CefTouchEvent event)", "private static CefCompositionUnderline[] copyAndValidateImeUnderlines");
        int nullValidation = javaBridge.indexOf("Objects.requireNonNull(event, \"event\")");
        int lifecycleGate = javaBridge.indexOf("if (!isNativeInputEligible()) return;");
        int primitiveCall = javaBridge.indexOf("N_SendTouchEvent(event.getId(), event.getX(), event.getY(), event.getRadiusX(), event.getRadiusY(), event.getRotationAngle(), event.getPressure(), event.getType().getValue(), event.getModifiers(), event.getPointerType().getValue());");
        assertTrue(nullValidation >= 0 && lifecycleGate > nullValidation && primitiveCall > lifecycleGate);

        String nativeValidation = sourceBetween(nativeSource, "bool GetTouchEvent(JNIEnv* env", "// These values are stable public ABI constants");
        assertTrue(nativeValidation.contains("id == -1"));
        assertTrue(nativeValidation.contains("std::isfinite"));
        assertTrue(nativeValidation.contains("radius_x < 0.0F || radius_y < 0.0F"));
        assertTrue(nativeValidation.contains("rotation_angle < 0.0F || rotation_angle >= kTouchRotationPiRadians"));
        assertTrue(nativeValidation.contains("pressure < 0.0F || pressure > 1.0F"));
        assertTrue(nativeValidation.contains("unsigned_modifiers & ~kKnownTouchModifiersMask"));
        assertTrue(nativeValidation.contains("GetTouchEventType"));
        assertTrue(nativeValidation.contains("GetPointerType"));

        String nativeBridge = sourceBetween(nativeSource, "Java_org_cef_browser_CefBrowser_1N_N_1SendTouchEvent", "Java_org_cef_browser_CefBrowser_1N_N_1SetWindowVisibility");
        int snapshotValidation = nativeBridge.indexOf("GetTouchEvent(env");
        int retainedLookup = nativeBridge.indexOf("GetLifecycleSafeJNIBrowser(env, obj)");
        int windowlessGuard = nativeBridge.indexOf("GetWindowlessInputHost(browser)");
        int directSend = nativeBridge.indexOf("host->SendTouchEvent(event)");
        assertTrue(snapshotValidation >= 0 && retainedLookup > snapshotValidation);
        assertTrue(windowlessGuard > retainedLookup && directSend > windowlessGuard);
        assertTrue(nativeValidation.contains("rotation_angle) * kTouchRadiansToDegrees"));
        assertFalse(nativeBridge.contains("CefPostTask"));
        assertFalse(nativeBridge.contains("CefPostDelayedTask"));
        assertFalse(nativeBridge.contains("GetDeviceScaleFactor"));
        assertTrue(nativeSource.contains("static_assert(CEF_TET_RELEASED == 0"));
        assertTrue(nativeSource.contains("static_assert(CEF_POINTER_TYPE_UNKNOWN == 4"));
        assertTrue(nativeSource.contains("kKnownTouchModifiersMask = EVENTFLAG_CAPS_LOCK_ON"));
        assertTrue(nativeSource.contains("static_assert(kKnownTouchModifiersMask == 0xFFFFU"));
        assertTrue(nativeSource.contains("CefMotionEventOSR stationary MOVED handling"));
        String normalizedInterfaceSource = interfaceSource.replace("*", "").replaceAll("\\s+", " ");
        assertTrue(normalizedInterfaceSource.contains("tracks at most 16 concurrent contacts"));
        assertTrue(normalizedInterfaceSource.contains("except {@code -1}"));
        assertTrue(normalizedInterfaceSource.contains("caller must serialize events"));
        assertTrue(normalizedInterfaceSource.contains("metadata-only change") && normalizedInterfaceSource.contains("is discarded"));
        assertTrue(normalizedInterfaceSource.contains("documents rotation in radians") && normalizedInterfaceSource.contains("requires degrees"));
        assertTrue(normalizedInterfaceSource.contains("--touch-events=enabled"));
    }

    @Test
    void knownModifierMaskMatchesEveryCef151EventFlag() {
        int allFlags = EventFlags.EVENTFLAG_CAPS_LOCK_ON | EventFlags.EVENTFLAG_SHIFT_DOWN
                | EventFlags.EVENTFLAG_CONTROL_DOWN | EventFlags.EVENTFLAG_ALT_DOWN
                | EventFlags.EVENTFLAG_LEFT_MOUSE_BUTTON | EventFlags.EVENTFLAG_MIDDLE_MOUSE_BUTTON
                | EventFlags.EVENTFLAG_RIGHT_MOUSE_BUTTON | EventFlags.EVENTFLAG_COMMAND_DOWN
                | EventFlags.EVENTFLAG_NUM_LOCK_ON | EventFlags.EVENTFLAG_IS_KEY_PAD
                | EventFlags.EVENTFLAG_IS_LEFT | EventFlags.EVENTFLAG_IS_RIGHT
                | EventFlags.EVENTFLAG_ALTGR_DOWN | EventFlags.EVENTFLAG_IS_REPEAT
                | EventFlags.EVENTFLAG_PRECISION_SCROLLING_DELTA
                | EventFlags.EVENTFLAG_SCROLL_BY_PAGE;
        assertEquals(ALL_CEF_EVENT_FLAGS, allFlags);
        assertDoesNotThrow(() -> new CefTouchEvent(0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CefTouchEventType.CANCELLED, allFlags, CefPointerType.PEN));
    }

    private static CefTouchEvent validEventWithId(int id) {
        return new CefTouchEvent(id, 10.25f, 20.5f, 2.0f, 3.0f, 0.75f, 0.5f, CefTouchEventType.PRESSED, EventFlags.EVENTFLAG_SHIFT_DOWN, CefPointerType.TOUCH);
    }

    private static Object invokeDefaultBrowserMethod(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
        throw new AssertionError("Unexpected abstract CefBrowser call: " + method);
    }

    private static String readSource(String first, String... more) throws Exception {
        Path sourcePath = Path.of(System.getProperty("user.dir"), first).resolve(Path.of("", more));
        assertTrue(Files.isRegularFile(sourcePath), "Run source contract tests from the repository root: " + sourcePath);
        return Files.readString(sourcePath).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sourceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "Missing source marker: " + startMarker);
        assertTrue(end > start, "Missing source marker after " + startMarker + ": " + endMarker);
        return source.substring(start, end);
    }

    private static final class TouchHarness extends CefBrowserOsr {
        private TouchHarness() {
            super(null, "about:blank", false, null);
        }
    }
}
