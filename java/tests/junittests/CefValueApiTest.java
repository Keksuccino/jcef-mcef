// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefBinaryValue;
import org.cef.callback.CefDictionaryValue;
import org.cef.callback.CefListValue;
import org.cef.callback.CefValue;
import org.cef.callback.CefValueType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

class CefValueApiTest {
    @Test
    void valueTypeMatchesCef151AndFailsClosedForFutureValues() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, java.util.Arrays.stream(CefValueType.values()).mapToInt(CefValueType::getValue).toArray());
        for (CefValueType type : CefValueType.values()) {
            assertEquals(type, CefValueType.fromValue(type.getValue()));
        }
        assertEquals(CefValueType.VTYPE_INVALID, CefValueType.fromValue(-1));
        assertEquals(CefValueType.VTYPE_INVALID, CefValueType.fromValue(10));
        assertEquals(CefValueType.VTYPE_INVALID, CefValueType.fromValue(Integer.MAX_VALUE));
    }

    @Test
    void exposesTheCompleteCef151ValueSurface() throws Exception {
        assertMethod(CefValue.class, "create", CefValue.class);
        assertCommonMethods(CefValue.class, CefValue.class);
        assertMethod(CefValue.class, "getType", CefValueType.class);
        assertMethod(CefValue.class, "getBinary", CefBinaryValue.class);
        assertMethod(CefValue.class, "getDictionary", CefDictionaryValue.class);
        assertMethod(CefValue.class, "getList", CefListValue.class);
        assertMethod(CefValue.class, "setNull", boolean.class);
        assertMethod(CefValue.class, "setBinary", boolean.class, CefBinaryValue.class);
        assertMethod(CefValue.class, "setDictionary", boolean.class, CefDictionaryValue.class);
        assertMethod(CefValue.class, "setList", boolean.class, CefListValue.class);

        assertMethod(CefBinaryValue.class, "create", CefBinaryValue.class, byte[].class);
        assertMethod(CefBinaryValue.class, "create", CefBinaryValue.class, byte[].class, int.class, int.class);
        assertCommonMethods(CefBinaryValue.class, CefBinaryValue.class);
        assertMethod(CefBinaryValue.class, "getSize", long.class);
        assertMethod(CefBinaryValue.class, "getData", int.class, byte[].class, int.class, int.class, long.class);
        assertMethod(CefBinaryValue.class, "getData", byte[].class);

        assertMethod(CefDictionaryValue.class, "create", CefDictionaryValue.class);
        assertCommonMethods(CefDictionaryValue.class, CefDictionaryValue.class);
        assertMethod(CefDictionaryValue.class, "copy", CefDictionaryValue.class, boolean.class);
        assertMethod(CefDictionaryValue.class, "getSize", long.class);
        assertMethod(CefDictionaryValue.class, "getKeys", List.class);
        assertContainerMethods(CefDictionaryValue.class, String.class);

        assertMethod(CefListValue.class, "create", CefListValue.class);
        assertCommonMethods(CefListValue.class, CefListValue.class);
        assertMethod(CefListValue.class, "setSize", boolean.class, long.class);
        assertMethod(CefListValue.class, "getSize", long.class);
        assertContainerMethods(CefListValue.class, long.class);
    }

    @Test
    void validatesBinaryRangesBeforeCrossingJni() {
        assertThrows(NullPointerException.class, () -> CefBinaryValue.create(null));
        assertThrows(IllegalArgumentException.class, () -> CefBinaryValue.create(new byte[0]));
        assertThrows(IndexOutOfBoundsException.class, () -> CefBinaryValue.create(new byte[4], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> CefBinaryValue.create(new byte[4], 3, 2));

        CefBinaryValue value = newUnbound("org.cef.callback.CefBinaryValue_N", CefBinaryValue.class);
        assertThrows(NullPointerException.class, () -> value.getData(null, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> value.getData(new byte[4], -1, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> value.getData(new byte[4], 3, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> value.getData(new byte[4], 0, 1, -1));
        assertThrows(IllegalStateException.class, () -> value.getData(new byte[4], 0));
    }

    @Test
    void validatesDictionaryAndListArgumentsBeforeCrossingJni() {
        CefDictionaryValue dictionary = newUnbound("org.cef.callback.CefDictionaryValue_N", CefDictionaryValue.class);
        assertThrows(NullPointerException.class, () -> dictionary.hasKey(null));
        assertThrows(NullPointerException.class, () -> dictionary.setString("key", null));
        assertThrows(NullPointerException.class, () -> dictionary.setValue("key", null));
        assertThrows(IllegalStateException.class, () -> dictionary.hasKey("key"));

        CefListValue list = newUnbound("org.cef.callback.CefListValue_N", CefListValue.class);
        assertThrows(IllegalArgumentException.class, () -> list.setSize(-1));
        assertThrows(IllegalArgumentException.class, () -> list.getType(-1));
        assertThrows(IllegalArgumentException.class, () -> list.setNull(-1));
        assertThrows(NullPointerException.class, () -> list.setString(0, null));
        assertThrows(NullPointerException.class, () -> list.setValue(0, null));
        assertThrows(IllegalStateException.class, () -> list.getType(0));
    }

    @Test
    void unboundWrappersHaveDeterministicIdempotentLifecycle() {
        List<CefValue> values = List.of(newUnbound("org.cef.callback.CefValue_N", CefValue.class));
        List<CefBinaryValue> binaries = List.of(newUnbound("org.cef.callback.CefBinaryValue_N", CefBinaryValue.class));
        List<CefDictionaryValue> dictionaries = List.of(newUnbound("org.cef.callback.CefDictionaryValue_N", CefDictionaryValue.class));
        List<CefListValue> lists = List.of(newUnbound("org.cef.callback.CefListValue_N", CefListValue.class));

        assertFalse(values.get(0).isValid());
        assertFalse(binaries.get(0).isValid());
        assertFalse(dictionaries.get(0).isValid());
        assertFalse(lists.get(0).isValid());
        assertDoesNotThrow(() -> {
            values.get(0).dispose();
            values.get(0).close();
            binaries.get(0).dispose();
            binaries.get(0).dispose();
            dictionaries.get(0).close();
            dictionaries.get(0).dispose();
            lists.get(0).dispose();
            lists.get(0).close();
        });
        assertTrue(AutoCloseable.class.isAssignableFrom(CefValue.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(CefBinaryValue.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(CefDictionaryValue.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(CefListValue.class));
    }

    private static void assertCommonMethods(Class<?> type, Class<?> copyType) throws Exception {
        assertMethod(type, "dispose", void.class);
        assertMethod(type, "close", void.class);
        assertMethod(type, "isValid", boolean.class);
        assertMethod(type, "isOwned", boolean.class);
        assertMethod(type, "isSame", boolean.class, type);
        assertMethod(type, "isEqual", boolean.class, type);
        if (type != CefDictionaryValue.class) assertMethod(type, "copy", copyType);
    }

    private static void assertContainerMethods(Class<?> type, Class<?> locatorType) throws Exception {
        assertMethod(type, "clear", boolean.class);
        assertMethod(type, "remove", boolean.class, locatorType);
        assertMethod(type, "getType", CefValueType.class, locatorType);
        assertMethod(type, "getValue", CefValue.class, locatorType);
        assertMethod(type, "getBool", boolean.class, locatorType);
        assertMethod(type, "getInt", int.class, locatorType);
        assertMethod(type, "getDouble", double.class, locatorType);
        assertMethod(type, "getString", String.class, locatorType);
        assertMethod(type, "getBinary", CefBinaryValue.class, locatorType);
        assertMethod(type, "getDictionary", CefDictionaryValue.class, locatorType);
        assertMethod(type, "getList", CefListValue.class, locatorType);
        assertMethod(type, "setValue", boolean.class, locatorType, CefValue.class);
        assertMethod(type, "setNull", boolean.class, locatorType);
        assertMethod(type, "setBool", boolean.class, locatorType, boolean.class);
        assertMethod(type, "setInt", boolean.class, locatorType, int.class);
        assertMethod(type, "setDouble", boolean.class, locatorType, double.class);
        assertMethod(type, "setString", boolean.class, locatorType, String.class);
        assertMethod(type, "setBinary", boolean.class, locatorType, CefBinaryValue.class);
        assertMethod(type, "setDictionary", boolean.class, locatorType, CefDictionaryValue.class);
        assertMethod(type, "setList", boolean.class, locatorType, CefListValue.class);
    }

    private static void assertMethod(Class<?> type, String name, Class<?> returnType, Class<?>... parameters) throws Exception {
        Method method = type.getMethod(name, parameters);
        assertEquals(returnType, method.getReturnType());
    }

    private static <T> T newUnbound(String className, Class<T> type) {
        try {
            Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
            constructor.setAccessible(true);
            return type.cast(constructor.newInstance());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
