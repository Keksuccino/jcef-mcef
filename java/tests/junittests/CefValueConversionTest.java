// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@NativeCefTest
class CefValueConversionTest {
    private static final Method ROUND_TRIP_METHOD = getRoundTripMethod();
    private static final Method IS_RESET_METHOD = getContextMethod("isPreferenceResetForTesting");

    @Test
    void repeatedStartupKeepsTheInitializedContextAlive() {
        // TestSetupExtension owns the one test-process bootstrap. Repeating the public startup call
        // must reuse that bootstrap instead of replacing the native Context under the running app.
        assertTrue(CefApp.startup(null));
        assertTrue(CefApp.startup(null));
        assertEquals(CefAppState.INITIALIZED, CefApp.getState());
    }

    @Test
    void roundTripsNestedValuesWithoutDataLoss() {
        ByteBuffer heapBinary = ByteBuffer.wrap(new byte[] {9, 1, 2, 3, 8});
        heapBinary.position(1);
        heapBinary.limit(4);
        ByteBuffer directBinary = ByteBuffer.allocateDirect(5);
        directBinary.put(new byte[] {7, 4, 5, 6, 0});
        directBinary.flip();
        directBinary.position(1);
        directBinary.limit(4);

        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("null", null);
        nested.put("boolean", Boolean.TRUE);
        nested.put("integer", Integer.valueOf(42));
        nested.put("double", Double.valueOf(3.5));
        nested.put("string", "value");
        nested.put("heapBinary", heapBinary.slice());
        nested.put("directBinary", directBinary.slice());
        nested.put("list", Arrays.asList("nested", null, Integer.valueOf(-7)));

        Object converted = roundTrip(nested);
        Map<?, ?> result = assertInstanceOf(Map.class, converted);
        assertEquals(nested.keySet(), result.keySet());
        assertNull(result.get("null"));
        assertEquals(Boolean.TRUE, result.get("boolean"));
        assertEquals(Integer.valueOf(42), result.get("integer"));
        assertEquals(Double.valueOf(3.5), result.get("double"));
        assertEquals("value", result.get("string"));
        assertArrayEquals(new byte[] {1, 2, 3}, remainingBytes(result.get("heapBinary")));
        assertArrayEquals(new byte[] {4, 5, 6}, remainingBytes(result.get("directBinary")));
        assertEquals(Arrays.asList("nested", null, Integer.valueOf(-7)), result.get("list"));
    }

    @Test
    void rejectsEmptyBinaryValuesThatCefCannotRepresent() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> roundTrip(ByteBuffer.allocate(0)));
        assertEquals("CEF does not support empty binary preference values", exception.getMessage());
    }

    @Test
    void roundTripsTopLevelNull() {
        assertNull(roundTrip(null));
    }

    @Test
    void distinguishesTopLevelResetFromNestedNull() {
        assertTrue(isPreferenceReset(null));
        assertFalse(isPreferenceReset(Arrays.asList((Object) null)));
        assertEquals(Arrays.asList((Object) null), roundTrip(Arrays.asList((Object) null)));
    }

    @Test
    void rejectsNonStringAndNullMapKeys() {
        Map<Object, Object> nonStringKey = new LinkedHashMap<Object, Object>();
        nonStringKey.put(Integer.valueOf(1), "value");
        assertThrows(IllegalArgumentException.class, () -> roundTrip(nonStringKey));

        Map<Object, Object> nullKey = new LinkedHashMap<Object, Object>();
        nullKey.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> roundTrip(nullKey));
    }

    @Test
    void rejectsUnsupportedValuesAndContainerCycles() {
        assertThrows(IllegalArgumentException.class, () -> roundTrip(Long.valueOf(1L)));

        List<Object> cyclicList = new ArrayList<Object>();
        cyclicList.add(cyclicList);
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> roundTrip(cyclicList));
        assertFalse(exception.getMessage().isEmpty());
    }

    private static byte[] remainingBytes(Object value) {
        ByteBuffer buffer = assertInstanceOf(ByteBuffer.class, value).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static Method getRoundTripMethod() {
        return getContextMethod("roundTripPreferenceValueForTesting");
    }

    private static Method getContextMethod(String methodName) {
        try {
            Class<?> contextClass = Class.forName("org.cef.browser.CefRequestContext_N");
            Method method = contextClass.getDeclaredMethod(methodName, Object.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object roundTrip(Object value) {
        return invokeContextMethod(ROUND_TRIP_METHOD, value);
    }

    private static boolean isPreferenceReset(Object value) {
        return ((Boolean) invokeContextMethod(IS_RESET_METHOD, value)).booleanValue();
    }

    private static Object invokeContextMethod(Method method, Object value) {
        try {
            return method.invoke(null, value);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }
}
