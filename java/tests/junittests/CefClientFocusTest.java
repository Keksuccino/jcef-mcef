// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefFocusHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;

@NativeCefTest
class CefClientFocusTest {
    @Test
    void gotFocusNotificationDoesNotReissueFocusCommand() {
        CefClient client = CefApp.getInstance().createClient();
        AtomicInteger focusCommands = new AtomicInteger();
        AtomicInteger focusNotifications = new AtomicInteger();
        CefBrowser browser = createBrowser(focusCommands);
        client.addFocusHandler(new CefFocusHandlerAdapter() {
            @Override
            public void onGotFocus(CefBrowser notifiedBrowser) {
                assertSame(browser, notifiedBrowser);
                focusNotifications.incrementAndGet();
            }
        });

        try {
            client.onGotFocus(browser);
            assertEquals(1, focusNotifications.get());
            assertEquals(0, focusCommands.get(),
                    "A focus notification must not feed a new focus command back into CEF");
        } finally {
            client.dispose();
        }
    }

    private static CefBrowser createBrowser(AtomicInteger focusCommands) {
        JPanel component = new JPanel();
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[] {CefBrowser.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getUIComponent")) return component;
                    if (method.getName().equals("setFocus")) {
                        focusCommands.incrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("toString")) return "FocusTestBrowser";
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return Character.valueOf('\0');
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0.0F);
        return Double.valueOf(0.0D);
    }
}
