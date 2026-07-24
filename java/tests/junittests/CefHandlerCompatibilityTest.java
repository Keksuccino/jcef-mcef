// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.LongRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class CefHandlerCompatibilityTest {
    @Test
    void legacyDownloadHandlerOwnsCallbackDecision() {
        AtomicBoolean called = new AtomicBoolean();
        CefDownloadHandler handler = new CefDownloadHandler() {
            @Override
            @Deprecated
            public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                called.set(true);
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {}
        };

        assertTrue(handler.onBeforeDownloadWithDecision(null, null, "file", null));
        assertTrue(called.get());
    }

    @Test
    void legacyDownloadAdapterOverrideAlsoOwnsCallbackDecision() {
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

    @Test
    void modernDownloadHandlerRetainsExplicitDefaultHandlingDecision() {
        CefDownloadHandler handler = new CefDownloadHandler() {
            @Override
            public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                return false;
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {}
        };

        assertFalse(handler.onBeforeDownloadWithDecision(null, null, "file", null));
    }

    @Test
    void legacyResourceHandlerFallsBackToOldOpenAndReadCallbacks() {
        CefResourceHandler handler = new LegacyResourceHandler();
        BoolRef handleRequest = new BoolRef(true);
        IntRef bytesRead = new IntRef(0);
        LongRef bytesSkipped = new LongRef(0);

        assertFalse(handler.open(null, handleRequest, null));
        assertFalse(handleRequest.get());
        assertFalse(handler.read(new byte[0], 0, bytesRead, null));
        assertEquals(-1, bytesRead.get());
        assertFalse(handler.skip(1, bytesSkipped, null));
        assertEquals(-2L, bytesSkipped.get());
    }

    @Test
    void resourceResponseLengthSupportsLongAndLegacyOverrides() {
        LongRef longLength = new LongRef(0);
        new LongResponseLengthResourceHandler().getResponseHeaders(null, longLength, null);
        assertEquals((long) Integer.MAX_VALUE + 42, longLength.get());

        LongRef legacyLength = new LongRef(0);
        new LegacyResourceHandler().getResponseHeaders(null, legacyLength, null);
        assertEquals(321L, legacyLength.get());
    }

    @Test
    void legacyDisplayHandlerCanIgnoreAddedNotifications() {
        CefDisplayHandler handler = new LegacyDisplayHandler();
        handler.onFaviconURLChange(null, List.of("https://example.test/icon.svg"));
        handler.onFullscreenModeChange(null, true);
        handler.onLoadingProgressChange(null, 0.5);
    }

    private static final class LegacyResourceHandler implements CefResourceHandler {
        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            return false;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            responseLength.set(321);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            return false;
        }

        @Override
        public void cancel() {}
    }

    private static final class LongResponseLengthResourceHandler extends CefResourceHandlerAdapter {
        @Override
        public void getResponseHeaders(CefResponse response, LongRef responseLength, StringRef redirectUrl) {
            responseLength.set((long) Integer.MAX_VALUE + 42);
        }
    }

    private static final class LegacyDisplayHandler implements CefDisplayHandler {
        @Override
        public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {}

        @Override
        public void onTitleChange(CefBrowser browser, String title) {}

        @Override
        public boolean onTooltip(CefBrowser browser, String text) {
            return false;
        }

        @Override
        public void onStatusMessage(CefBrowser browser, String value) {}

        @Override
        public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
            return false;
        }

        @Override
        public boolean onCursorChange(CefBrowser browser, int cursorType) {
            return false;
        }
    }
}
