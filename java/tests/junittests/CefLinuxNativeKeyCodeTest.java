// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cef.browser.CefBrowser_N;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@NativeCefTest
class CefLinuxNativeKeyCodeTest {
    private static final Method RESOLVE = getResolveMethod();
    private static final int[] LETTER_XKB_CODES = {38, 56, 54, 40, 26, 41, 42, 43, 31, 44, 45, 46,
            58, 57, 32, 33, 24, 27, 39, 28, 30, 55, 25, 53, 29, 52};
    private static final int[] FUNCTION_XKB_CODES = {67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 95, 96,
            191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202};
    private static final int[] NUMPAD_DIGIT_XKB_CODES = {90, 87, 88, 89, 83, 84, 85, 79, 80, 81};
    private static final int STANDARD = KeyEvent.KEY_LOCATION_STANDARD;

    @Test
    void mapsEveryLetterAndDigitForAwtAndGlfw() {
        for (int index = 0; index < LETTER_XKB_CODES.length; index++) {
            assertFallback(LETTER_XKB_CODES[index], KeyEvent.VK_A + index, STANDARD, true);
            assertFallback(LETTER_XKB_CODES[index], 'A' + index, STANDARD, false);
        }
        int[] digitCodes = {19, 10, 11, 12, 13, 14, 15, 16, 17, 18};
        for (int digit = 0; digit <= 9; digit++) {
            assertFallback(digitCodes[digit], KeyEvent.VK_0 + digit, STANDARD, true);
            assertFallback(digitCodes[digit], '0' + digit, STANDARD, false);
        }
    }

    @Test
    void mapsEveryStandardPunctuationKeyForAwtAndGlfw() {
        int[] awtKeys = {KeyEvent.VK_SPACE, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS,
                KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH,
                KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE, KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_COMMA,
                KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH};
        int[] glfwKeys = {' ', '-', '=', '[', ']', '\\', ';', '\'', '`', ',', '.', '/'};
        int[] expected = {65, 20, 21, 34, 35, 51, 47, 48, 49, 59, 60, 61};
        assertFallbacks(expected, awtKeys, STANDARD, true);
        assertFallbacks(expected, glfwKeys, STANDARD, false);
    }

    @Test
    void mapsAwtModifierLocationsAndGlfwModifierIdentities() {
        assertFallback(50, KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT, true);
        assertFallback(62, KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT, true);
        assertFallback(37, KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_LEFT, true);
        assertFallback(105, KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT, true);
        assertFallback(64, KeyEvent.VK_ALT, KeyEvent.KEY_LOCATION_LEFT, true);
        assertFallback(108, KeyEvent.VK_ALT, KeyEvent.KEY_LOCATION_RIGHT, true);
        assertFallback(133, KeyEvent.VK_META, KeyEvent.KEY_LOCATION_LEFT, true);
        assertFallback(134, KeyEvent.VK_META, KeyEvent.KEY_LOCATION_RIGHT, true);
        assertFallback(133, KeyEvent.VK_WINDOWS, KeyEvent.KEY_LOCATION_LEFT, true);
        assertFallback(134, KeyEvent.VK_WINDOWS, KeyEvent.KEY_LOCATION_RIGHT, true);
        assertFallback(108, KeyEvent.VK_ALT_GRAPH, STANDARD, true);

        int[] glfwModifiers = {340, 341, 342, 343, 344, 345, 346, 347};
        int[] expected = {50, 37, 64, 133, 62, 105, 108, 134};
        assertFallbacks(expected, glfwModifiers, STANDARD, false);
    }

    @Test
    void mapsNavigationAndEditingKeysForBothDomains() {
        int[] awtKeys = {KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_TAB,
                KeyEvent.VK_ENTER, KeyEvent.VK_INSERT, KeyEvent.VK_DELETE, KeyEvent.VK_RIGHT,
                KeyEvent.VK_LEFT, KeyEvent.VK_DOWN, KeyEvent.VK_UP, KeyEvent.VK_PAGE_UP,
                KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_CAPS_LOCK,
                KeyEvent.VK_SCROLL_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_PRINTSCREEN,
                KeyEvent.VK_PAUSE, KeyEvent.VK_CONTEXT_MENU, KeyEvent.VK_HELP};
        int[] glfwKeys = {256, 259, 258, 257, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 280,
                281, 282, 283, 284, 348};
        int[] awtExpected = {9, 22, 23, 36, 118, 119, 114, 113, 116, 111, 112, 117, 110, 115, 66,
                78, 77, 107, 127, 135, 146};
        int[] glfwExpected = {9, 22, 23, 36, 118, 119, 114, 113, 116, 111, 112, 117, 110, 115, 66,
                78, 77, 107, 127, 135};
        assertFallbacks(awtExpected, awtKeys, STANDARD, true);
        assertFallbacks(glfwExpected, glfwKeys, STANDARD, false);
    }

    @Test
    void mapsEverySupportedFunctionKeyAndRejectsGlfwF25() {
        for (int index = 0; index < FUNCTION_XKB_CODES.length; index++) {
            int awtKey = index < 12 ? KeyEvent.VK_F1 + index : KeyEvent.VK_F13 + index - 12;
            assertFallback(FUNCTION_XKB_CODES[index], awtKey, STANDARD, true);
            assertFallback(FUNCTION_XKB_CODES[index], 290 + index, STANDARD, false);
        }
        assertFallback(0, 314, STANDARD, false);
    }

    @Test
    void mapsEveryKeypadIdentityAndAwtKeypadDirections() {
        for (int digit = 0; digit <= 9; digit++) {
            assertFallback(NUMPAD_DIGIT_XKB_CODES[digit], KeyEvent.VK_NUMPAD0 + digit, KeyEvent.KEY_LOCATION_NUMPAD, true);
            assertFallback(NUMPAD_DIGIT_XKB_CODES[digit], 320 + digit, STANDARD, false);
        }
        int[] awtOperators = {KeyEvent.VK_MULTIPLY, KeyEvent.VK_ADD, KeyEvent.VK_SEPARATOR,
                KeyEvent.VK_SUBTRACT, KeyEvent.VK_DECIMAL, KeyEvent.VK_DIVIDE};
        int[] glfwOperators = {330, 331, 332, 333, 334, 335, 336};
        int[] awtExpected = {63, 86, 129, 82, 91, 106};
        int[] glfwExpected = {91, 106, 63, 82, 86, 104, 125};
        assertFallbacks(awtExpected, awtOperators, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallbacks(glfwExpected, glfwOperators, STANDARD, false);
        assertFallback(104, KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(80, KeyEvent.VK_KP_UP, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(88, KeyEvent.VK_KP_DOWN, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(83, KeyEvent.VK_KP_LEFT, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(85, KeyEvent.VK_KP_RIGHT, KeyEvent.KEY_LOCATION_NUMPAD, true);
    }

    @Test
    void mapsAwtNavigationBlockKeysByKnownNumpadLocation() {
        int[] keys = {KeyEvent.VK_CLEAR, KeyEvent.VK_HOME, KeyEvent.VK_UP, KeyEvent.VK_PAGE_UP,
                KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_END, KeyEvent.VK_DOWN,
                KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_INSERT, KeyEvent.VK_DELETE, KeyEvent.VK_EQUALS};
        int[] expected = {84, 79, 80, 81, 83, 85, 87, 88, 89, 90, 91, 125};
        assertFallbacks(expected, keys, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(0, KeyEvent.VK_TAB, KeyEvent.KEY_LOCATION_NUMPAD, true);
        assertFallback(0, KeyEvent.VK_SPACE, KeyEvent.KEY_LOCATION_NUMPAD, true);
    }

    @Test
    void leavesTypedAndAmbiguousLogicalKeysWithoutFabricatedPositions() {
        assertEquals(0, resolve(0, KeyEvent.VK_UNDEFINED, KeyEvent.KEY_LOCATION_UNKNOWN, true, true));
        assertEquals(0, resolve(0, 'A', STANDARD, true, false));
        for (int deadKey = KeyEvent.VK_DEAD_GRAVE; deadKey <= KeyEvent.VK_DEAD_SEMIVOICED_SOUND;
                deadKey++)
            assertFallback(0, deadKey, STANDARD, true);
        int[] ambiguousAwt = {
                KeyEvent.VK_CANCEL, KeyEvent.VK_CLEAR, KeyEvent.VK_COMPOSE, 0x7FFFFFFF};
        int[] unsupportedGlfw = {-1, 161, 162, 314, 0x7FFFFFFF};
        assertFallbacks(new int[ambiguousAwt.length], ambiguousAwt, STANDARD, true);
        assertFallbacks(new int[unsupportedGlfw.length], unsupportedGlfw, STANDARD, false);
    }

    @Test
    void preservesPositiveSuppliedNativeCodesBeforeApplyingFallbacks() {
        assertEquals(77, resolve(77, KeyEvent.VK_M, STANDARD, false, true));
        assertEquals(211, resolve(211, 'M', STANDARD, false, false));
        assertEquals(222, resolve(222, KeyEvent.VK_UNDEFINED, KeyEvent.KEY_LOCATION_UNKNOWN, true, true));
        assertEquals(58, resolve(0, KeyEvent.VK_M, STANDARD, false, true));
        assertEquals(58, resolve(Long.MAX_VALUE, KeyEvent.VK_M, STANDARD, false, true));
    }

    private static void assertFallbacks(int[] expected, int[] keys, int location, boolean awt) {
        assertEquals(expected.length, keys.length);
        for (int index = 0; index < keys.length; index++)
            assertFallback(expected[index], keys[index], location, awt);
    }

    private static void assertFallback(int expected, int keyCode, int location, boolean awt) {
        assertEquals(expected, resolve(0, keyCode, location, false, awt), "keyCode=" + keyCode + ", location=" + location + ", awt=" + awt);
    }

    private static int resolve(long suppliedNativeKeyCode, int keyCode, int keyLocation, boolean typed, boolean awt) {
        try {
            return ((Integer) RESOLVE.invoke(null, Long.valueOf(suppliedNativeKeyCode), Integer.valueOf(keyCode), Integer.valueOf(keyLocation), Boolean.valueOf(typed), Boolean.valueOf(awt))).intValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method getResolveMethod() {
        try {
            Method method = CefBrowser_N.class.getDeclaredMethod("N_ResolveLinuxNativeKeyCodeForTesting", long.class, int.class, int.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
