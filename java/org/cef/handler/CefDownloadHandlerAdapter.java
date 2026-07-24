// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;

/**
 * An abstract adapter class for receiving download events.
 * The methods in this class are empty.
 * This class exists as convenience for creating handler objects.
 */
public abstract class CefDownloadHandlerAdapter implements CefDownloadHandler {
    private static final ClassValue<Boolean> LEGACY_BEFORE_DOWNLOAD_OVERRIDDEN =
            new ClassValue<Boolean>() {
                @Override
                protected Boolean computeValue(Class<?> type) {
                    try {
                        return type.getMethod("onBeforeDownload", CefBrowser.class, CefDownloadItem.class, String.class, CefBeforeDownloadCallback.class).getDeclaringClass() != CefDownloadHandlerAdapter.class;
                    } catch (NoSuchMethodException exception) {
                        throw new ExceptionInInitializerError(exception);
                    }
                }
            };

    @Override
    @Deprecated
    public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {}

    /**
     * Preserve callback ownership for subclasses that override the deprecated hook while allowing
     * a genuinely no-op adapter to select CEF's default behavior.
     */
    @Override
    public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        if (!LEGACY_BEFORE_DOWNLOAD_OVERRIDDEN.get(getClass())) {
            return false;
        }
        onBeforeDownload(browser, downloadItem, suggestedName, callback);
        return true;
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {}
}
