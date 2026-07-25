// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.callback.CefPermissionPromptCallback;
import org.cef.handler.CefPermissionHandler;
import org.cef.handler.CefPermissionHandlerAdapter;
import org.cef.handler.CefPermissionRequestResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

@NativeCefTest
class CefPermissionHandlerClientTest {
    @Test
    void stableRelayPreservesRawValuesAndRoutesDismissalToTheAcceptingDelegate() throws Exception {
        CefClient client = CefApp.getInstance().createClient();
        CefBrowser browser = proxy(CefBrowser.class, "PermissionClientBrowser");
        CefFrame frame = proxy(CefFrame.class, "PermissionClientFrame");
        CefMediaAccessCallback mediaCallback = new CefMediaAccessCallback() {
            @Override
            public void Continue(int allowedPermissions) {}

            @Override
            public void Cancel() {}
        };
        CefPermissionPromptCallback promptCallback = result -> {};
        RecordingPermissionHandler first = new RecordingPermissionHandler(true, true);
        RecordingPermissionHandler ignoredSecond = new RecordingPermissionHandler(true, true);
        RecordingPermissionHandler replacement = new RecordingPermissionHandler(true, true);
        int mediaMask = 0x80000005;
        int promptMask = 0xE0000100;
        long promptId = Long.MIN_VALUE;

        try {
            Method getter = CefClient.class.getDeclaredMethod("getPermissionHandler");
            getter.setAccessible(true);
            assertSame(client, getter.invoke(client));
            assertFalse(client.onRequestMediaAccessPermission(browser, frame, "https://permission.test", mediaMask, mediaCallback));
            assertFalse(client.onShowPermissionPrompt(browser, promptId, "https://permission.test", promptMask, promptCallback));

            client.addPermissionHandler(first).addPermissionHandler(ignoredSecond);
            assertTrue(client.onRequestMediaAccessPermission(browser, frame, "https://permission.test", mediaMask, mediaCallback));
            assertSame(browser, first.mediaBrowser);
            assertSame(frame, first.mediaFrame);
            assertSame(mediaCallback, first.mediaCallback);
            assertEquals("https://permission.test", first.mediaOrigin);
            assertEquals(mediaMask, first.mediaMask);
            assertEquals(0, ignoredSecond.mediaCalls.get());

            assertTrue(client.onShowPermissionPrompt(browser, promptId, "https://permission.test", promptMask, promptCallback));
            assertEquals(promptId, first.promptId);
            assertEquals(promptMask, first.promptMask);
            assertSame(promptCallback, first.promptCallback);

            client.removePermissionHandler();
            client.addPermissionHandler(replacement);
            client.onDismissPermissionPrompt(browser, promptId, CefPermissionRequestResult.CEF_PERMISSION_RESULT_DISMISS);
            assertEquals(1, first.dismissCalls.get());
            assertEquals(promptId, first.dismissedPromptId);
            assertEquals(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DISMISS, first.dismissResult);
            assertEquals(0, replacement.dismissCalls.get());
            client.onDismissPermissionPrompt(browser, promptId, CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY);
            assertEquals(1, first.dismissCalls.get());

            assertTrue(client.onShowPermissionPrompt(browser, -1L, "https://permission.test", promptMask, promptCallback));
            client.onDismissPermissionPrompt(browser, -1L, CefPermissionRequestResult.CEF_PERMISSION_RESULT_ACCEPT);
            assertEquals(1, replacement.dismissCalls.get());
            assertEquals(-1L, replacement.dismissedPromptId);
        } finally {
            client.dispose();
        }
    }

    @Test
    void rejectedOrInvalidRequestsDoNotCreatePromptOwnership() {
        CefClient client = CefApp.getInstance().createClient();
        CefBrowser browser = proxy(CefBrowser.class, "RejectedPermissionBrowser");
        CefFrame frame = proxy(CefFrame.class, "RejectedPermissionFrame");
        CefMediaAccessCallback mediaCallback = new CefMediaAccessCallback() {
            @Override
            public void Continue(int allowedPermissions) {}

            @Override
            public void Cancel() {}
        };
        CefPermissionPromptCallback promptCallback = result -> {};
        RecordingPermissionHandler guarded = new RecordingPermissionHandler(true, true);
        RecordingPermissionHandler rejecting = new RecordingPermissionHandler(false, false);
        RecordingPermissionHandler replacement = new RecordingPermissionHandler(true, true);

        try {
            client.addPermissionHandler(guarded);
            assertFalse(client.onRequestMediaAccessPermission(null, frame, "https://permission.test", 1, mediaCallback));
            assertFalse(client.onRequestMediaAccessPermission(browser, null, "https://permission.test", 1, mediaCallback));
            assertFalse(client.onRequestMediaAccessPermission(browser, frame, "https://permission.test", 1, null));
            assertFalse(client.onShowPermissionPrompt(null, Long.MAX_VALUE, "https://permission.test", 1, promptCallback));
            assertFalse(client.onShowPermissionPrompt(browser, Long.MAX_VALUE, "https://permission.test", 1, null));
            assertEquals(0, guarded.mediaCalls.get());
            assertEquals(0, guarded.promptCalls.get());

            client.removePermissionHandler();
            client.addPermissionHandler(rejecting);
            assertFalse(client.onRequestMediaAccessPermission(browser, frame, "https://permission.test", 1, mediaCallback));
            assertFalse(client.onShowPermissionPrompt(browser, Long.MAX_VALUE, "https://permission.test", 1, promptCallback));
            assertEquals(1, rejecting.mediaCalls.get());
            assertEquals(1, rejecting.promptCalls.get());

            client.removePermissionHandler();
            client.addPermissionHandler(replacement);
            assertEquals(0, replacement.mediaCalls.get());
            assertEquals(0, replacement.promptCalls.get());
            client.onDismissPermissionPrompt(browser, Long.MAX_VALUE, CefPermissionRequestResult.CEF_PERMISSION_RESULT_IGNORE);
            assertEquals(0, rejecting.dismissCalls.get());
            assertEquals(0, replacement.dismissCalls.get());
            client.removePermissionHandler();
            assertFalse(client.onRequestMediaAccessPermission(browser, frame, "https://permission.test", 1, mediaCallback));
            assertFalse(client.onShowPermissionPrompt(browser, Long.MAX_VALUE, "https://permission.test", 1, promptCallback));
        } finally {
            client.dispose();
        }
    }

    private static final class RecordingPermissionHandler extends CefPermissionHandlerAdapter {
        private final boolean handleMedia;
        private final boolean handlePrompt;
        private final AtomicInteger mediaCalls = new AtomicInteger();
        private final AtomicInteger promptCalls = new AtomicInteger();
        private final AtomicInteger dismissCalls = new AtomicInteger();
        private CefBrowser mediaBrowser;
        private CefFrame mediaFrame;
        private String mediaOrigin;
        private int mediaMask;
        private CefMediaAccessCallback mediaCallback;
        private long promptId;
        private int promptMask;
        private CefPermissionPromptCallback promptCallback;
        private long dismissedPromptId;
        private int dismissResult;

        private RecordingPermissionHandler(boolean handleMedia, boolean handlePrompt) {
            this.handleMedia = handleMedia;
            this.handlePrompt = handlePrompt;
        }

        @Override
        public boolean onRequestMediaAccessPermission(CefBrowser browser, CefFrame frame, String requestingOrigin, int requestedPermissions, CefMediaAccessCallback callback) {
            mediaCalls.incrementAndGet();
            mediaBrowser = browser;
            mediaFrame = frame;
            mediaOrigin = requestingOrigin;
            mediaMask = requestedPermissions;
            mediaCallback = callback;
            return handleMedia;
        }

        @Override
        public boolean onShowPermissionPrompt(CefBrowser browser, long receivedPromptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
            promptCalls.incrementAndGet();
            promptId = receivedPromptId;
            promptMask = requestedPermissions;
            promptCallback = callback;
            return handlePrompt;
        }

        @Override
        public void onDismissPermissionPrompt(CefBrowser browser, long receivedPromptId, int result) {
            dismissCalls.incrementAndGet();
            dismissedPromptId = receivedPromptId;
            dismissResult = result;
        }
    }

    private static <T> T proxy(Class<T> type, String description) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("toString")) return description;
            return defaultValue(method.getReturnType());
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
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
