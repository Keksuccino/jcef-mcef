// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;

/**
 * Implement this interface to handle file downloads. The methods of this class
 * will called on the browser process UI thread.
 */
public interface CefDownloadHandler {
    /**
     * Called before a user-initiated download. Return true to proceed or false to cancel.
     *
     * @param browser The browser generating the download.
     * @param url The target download URL.
     * @param requestMethod The target request method, such as GET or POST.
     */
    public default boolean canDownload(CefBrowser browser, String url, String requestMethod) {
        return true;
    }

    /**
     * Legacy callback invoked before a download begins. Implementations own the callback and must
     * execute it to continue the download; otherwise the download is canceled.
     *
     * @param browser The desired browser.
     * @param downloadItem The item to be downloaded. Do not keep a reference to it outside this
     * method.
     * @param suggestedName is the suggested name for the download file.
     * @param callback start the download by calling the Continue method
     * @deprecated Override {@link #onBeforeDownloadWithDecision(CefBrowser, CefDownloadItem,
     *         String, CefBeforeDownloadCallback)} to explicitly select handled versus default CEF
     *         download behavior. This hook remains for source and binary compatibility.
     */
    @Deprecated
    public default void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {}

    /**
     * Called before a download begins. Return true and execute {@code callback} either
     * asynchronously or in this method to continue or cancel the download. Return false to proceed
     * with default handling (cancel with Alloy style, download shelf with Chrome style).
     *
     * <p>The default implementation invokes the legacy callback and returns true because legacy
     * handlers own the continuation decision. New handlers should override this method directly.
     *
     * @param browser The desired browser.
     * @param downloadItem The item to be downloaded. Do not keep a reference to it outside this
     *         method.
     * @param suggestedName is the suggested name for the download file.
     * @param callback start the download by calling the Continue method
     * @return true if the handler owns the callback, or false for default CEF handling
     */
    public default boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        onBeforeDownload(browser, downloadItem, suggestedName, callback);
        return true;
    }

    /**
     * Called when a download's status or progress information has been updated.
     * @param browser The desired browser.
     * @param downloadItem The downloading item.
     * @param callback Execute callback to cancel the download
     */
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback);
}
