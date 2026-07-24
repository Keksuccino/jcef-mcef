// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItem.DownloadInterruptReason;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

class CefDownloadApiTest {
    @Test
    void interruptReasonsMatchCef151AndMapWithoutOrdinals() {
        int[] values = Arrays.stream(DownloadInterruptReason.values()).mapToInt(DownloadInterruptReason::getValue).toArray();
        int[] expected = {-1, 0, 1, 2, 3, 5, 6, 7, 10, 11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 30,
                31, 33, 34, 35, 36, 37, 38, 39, 40, 41, 50};

        assertArrayEquals(expected, values);
        for (DownloadInterruptReason reason : DownloadInterruptReason.values()) {
            if (reason != DownloadInterruptReason.UNKNOWN) {
                assertSame(reason, DownloadInterruptReason.fromValue(reason.getValue()));
            }
        }
    }

    @Test
    void unknownAndReservedInterruptReasonsUseExplicitSentinel() {
        assertSame(DownloadInterruptReason.UNKNOWN, DownloadInterruptReason.fromValue(-2));
        assertSame(DownloadInterruptReason.UNKNOWN, DownloadInterruptReason.fromValue(4));
        assertSame(DownloadInterruptReason.UNKNOWN, DownloadInterruptReason.fromValue(32));
        assertSame(DownloadInterruptReason.UNKNOWN, DownloadInterruptReason.fromValue(51));
        assertSame(DownloadInterruptReason.UNKNOWN, DownloadInterruptReason.fromValue(Integer.MAX_VALUE));
    }

    @Test
    void exposesCef151DownloadItemAdditions() throws Exception {
        assertEquals(boolean.class, CefDownloadItem.class.getMethod("isInterrupted").getReturnType());
        assertEquals(boolean.class, CefDownloadItem.class.getMethod("isPaused").getReturnType());
        assertEquals(DownloadInterruptReason.class, CefDownloadItem.class.getMethod("getInterruptReason").getReturnType());
        assertEquals(int.class, CefDownloadItem.class.getMethod("getInterruptReasonValue").getReturnType());
        assertEquals(String.class, CefDownloadItem.class.getMethod("getOriginalURL").getReturnType());
    }

    @Test
    void noOpAdapterDeclinesBeforeDownloadCallbackOwnership() {
        CefDownloadHandler handler = new CefDownloadHandlerAdapter() {};

        assertFalse(handler.onBeforeDownloadWithDecision(null, null, "file", null));
        assertTrue(handler.canDownload(null, "https://example.test/file", "GET"));
    }

    @Test
    void legacyAdapterOverrideRetainsCallbackOwnership() {
        AtomicBoolean called = new AtomicBoolean();
        CefDownloadHandler handler = new CefDownloadHandlerAdapter() {
            @Override
            @Deprecated
            public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                called.set(true);
            }
        };

        assertTrue(handler.onBeforeDownloadWithDecision(null, null, "file", null));
        assertTrue(called.get());
    }
}
