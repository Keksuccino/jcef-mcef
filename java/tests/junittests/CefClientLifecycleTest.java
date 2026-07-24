// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@NativeCefTest
class CefClientLifecycleTest {
    @Test
    void disposalClosesSnapshotOutsideTheBrowserMapLockAndToleratesReentrantClose() {
        CefClient client = CefApp.getInstance().createClient();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicBoolean closeHeldBrowserMapLock = new AtomicBoolean();
        Object browserMap = getBrowserMap(client);
        CefBrowser first = createBrowser(client, 101, closeCalls, closeHeldBrowserMapLock, browserMap);
        CefBrowser second = createBrowser(client, 102, closeCalls, closeHeldBrowserMapLock, browserMap);
        client.onAfterCreated(first);
        client.onAfterCreated(second);

        client.dispose();
        client.dispose();

        assertEquals(2, closeCalls.get());
        assertFalse(closeHeldBrowserMapLock.get());
        assertThrows(IllegalStateException.class, () -> client.createBrowser("about:blank", true));
        assertFalse(client.onBrowserCreationStarted(first));
    }

    @Test
    void throwingBeforeCloseHandlerCannotStrandBrowserOrClient() {
        CefClient client = CefApp.getInstance().createClient();
        AtomicInteger closeCalls = new AtomicInteger();
        Object browserMap = getBrowserMap(client);
        CefBrowser first = createBrowser(client, 201, closeCalls, new AtomicBoolean(), browserMap);
        CefBrowser second = createBrowser(client, 202, closeCalls, new AtomicBoolean(), browserMap);
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onBeforeClose(CefBrowser ignored) {
                throw new IllegalStateException("expected before-close failure");
            }
        });
        client.onAfterCreated(first);
        client.onAfterCreated(second);

        IllegalStateException failure = assertThrows(IllegalStateException.class, client::dispose);

        assertEquals(2, closeCalls.get());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(isBrowserMapEmpty(client));
    }

    @Test
    void throwingAfterCreatedHandlerCannotSkipDisposedBrowserClose() {
        CefClient client = CefApp.getInstance().createClient();
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onAfterCreated(CefBrowser ignored) {
                throw new IllegalStateException("expected after-created failure");
            }
        });
        client.dispose();

        AtomicInteger closeCalls = new AtomicInteger();
        Object browserMap = getBrowserMap(client);
        CefBrowser browser = createBrowser(client, 203, closeCalls, new AtomicBoolean(), browserMap);

        assertThrows(IllegalStateException.class, () -> client.onAfterCreated(browser));

        assertEquals(1, closeCalls.get());
        assertTrue(isBrowserMapEmpty(client));
    }

    private static CefBrowser createBrowser(CefClient client, int identifier, AtomicInteger closeCalls, AtomicBoolean lockHeld, Object browserMap) {
        AtomicBoolean closed = new AtomicBoolean();
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getIdentifier"))
                        return Integer.valueOf(identifier);
                    if (method.getName().equals("close")) {
                        lockHeld.compareAndSet(false, Thread.holdsLock(browserMap));
                        if (closed.compareAndSet(false, true)) {
                            closeCalls.incrementAndGet();
                            client.onBeforeClose((CefBrowser) proxy);
                        }
                        return null;
                    }
                    if (method.getName().equals("toString"))
                        return "LifecycleTestBrowser-" + identifier;
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object getBrowserMap(CefClient client) {
        try {
            java.lang.reflect.Field field = CefClient.class.getDeclaredField("browser_");
            field.setAccessible(true);
            return field.get(client);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean isBrowserMapEmpty(CefClient client) {
        return ((java.util.Map<?, ?>) getBrowserMap(client)).isEmpty();
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
