// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefPaintElementType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

class CefBrowserPresentationStateContractTest {
    @Test
    void mapsExactCef151PaintElementValuesAndRejectsUnknownValues() {
        assertEquals(0, CefPaintElementType.PET_VIEW.getValue());
        assertEquals(1, CefPaintElementType.PET_POPUP.getValue());
        assertSame(CefPaintElementType.PET_VIEW, CefPaintElementType.fromValue(0));
        assertSame(CefPaintElementType.PET_POPUP, CefPaintElementType.fromValue(1));
        assertThrows(IllegalArgumentException.class, () -> CefPaintElementType.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefPaintElementType.fromValue(2));
        assertThrows(IllegalArgumentException.class, () -> CefPaintElementType.fromValue(Integer.MAX_VALUE));
    }

    @Test
    void exposesPresentationStateApiAndKeepsSwingViewCompatibilityBridge() throws Exception {
        assertPublicVoidMethod(CefBrowser.class, "setWindowVisibility", boolean.class);
        assertPublicVoidMethod(CefBrowser.class, "notifyScreenInfoChanged");
        assertPublicVoidMethod(CefBrowser.class, "invalidate", CefPaintElementType.class);

        Method compatibilityInvalidate = CefBrowser_N.class.getDeclaredMethod("invalidate");
        assertTrue(Modifier.isProtected(compatibilityInvalidate.getModifiers()));
        assertTrue(Modifier.isFinal(compatibilityInvalidate.getModifiers()));
        assertEquals(void.class, compatibilityInvalidate.getReturnType());

        assertPrivateNativeVoidMethod("N_NotifyScreenInfoChanged");
        assertPrivateNativeVoidMethod("N_Invalidate");
        assertPrivateNativeVoidMethod("N_InvalidatePaintElement", int.class);
    }

    private static void assertPublicVoidMethod(Class<?> owner, String name, Class<?>... parameterTypes) throws Exception {
        Method method = owner.getMethod(name, parameterTypes);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
    }

    private static void assertPrivateNativeVoidMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method method = CefBrowser_N.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isFinal(method.getModifiers()));
        assertTrue(Modifier.isNative(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
    }
}
