// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cef.browser.CefBrowser_N;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@NativeCefTest
class CefWindowsNativeKeyCodeTest {
    private static final Method RESOLVE = getResolveMethod();

    @Test
    void returnsChromiumOemScanCodesWithoutWindowMessageBits() {
        assertEquals(0x32, resolve(0, 0x32, false));
        assertEquals(0xE04B, resolve(0, 0xE04B, true));
        assertEquals(0x4B, resolve(0, 0xE04B, false));
        assertEquals(0x45, resolve(0, 0x45, false));
    }

    @Test
    void preservesSuppliedScanCodesAndAddsRequiredExtendedPrefix() {
        assertEquals(0x32, resolve(0x32, 0x1E, false));
        assertEquals(0xE04B, resolve(0x4B, 0x1E, true));
        assertEquals(0xE04B, resolve(0xE04B, 0x1E, true));
        assertEquals(0xE04B, resolve(0xE04B, 0x1E, false));
        assertEquals(0xE145, resolve(0xE145, 0x45, false));
        assertEquals(0x4B, resolve(0x4B, 0xE04B, false));
    }

    @Test
    void normalizesGlfwExtendedScanCodes() {
        assertEquals(0xE04B, resolve(0x14B, 0, false));
        assertEquals(0xE01D, resolve(0x11D, 0, false));
        assertEquals(0xE037, resolve(0x137, 0, false));
    }

    @Test
    void rejectsUnboundedSuppliedValuesBeforeNarrowing() {
        assertEquals(0x32, resolve(Long.MAX_VALUE, 0x32, false));
        assertEquals(0xE04B, resolve(Long.MAX_VALUE, 0x4B, true));
        assertEquals(0, resolve(0, -1, false));
    }

    private static int resolve(long suppliedScanCode, int mappedScanCode, boolean extended) {
        try {
            return ((Integer) RESOLVE.invoke(null, Long.valueOf(suppliedScanCode), Integer.valueOf(mappedScanCode), Boolean.valueOf(extended))).intValue();
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
            Method method = CefBrowser_N.class.getDeclaredMethod("N_ResolveWindowsNativeKeyCodeForTesting", long.class, int.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
