// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import org.cef.network.CefURLRequest;

/**
 * Interface that should be implemented by the CefURLRequest client. The
 * methods of this class will be called on the same thread that created the
 * request unless otherwise documented.
 */
public interface CefURLRequestClient extends CefNative {
    /**
     * Notifies the client that the request has completed. Use the
     * CefURLRequest::GetRequestStatus method to determine if the request was
     * successful or not.
     */
    void onRequestComplete(CefURLRequest request);

    /**
     * Notifies the client of upload progress. |current| denotes the number of
     * bytes sent so far and |total| is the total size of uploading data (or -1 if
     * chunked upload is enabled). This method will only be called if the
     * UR_FLAG_REPORT_UPLOAD_PROGRESS flag is set on the request.
     *
     * @deprecated Override the {@code long} overload to receive CEF's full 64-bit byte counts.
     */
    @Deprecated
    default void onUploadProgress(CefURLRequest request, int current, int total) {}

    /**
     * Notifies the client of upload progress without truncating CEF's 64-bit byte counts.
     * Implementations that only override the legacy {@code int} overload remain supported;
     * values outside that overload's range are saturated instead of wrapping.
     */
    default void onUploadProgress(CefURLRequest request, long current, long total) {
        onUploadProgress(request, toLegacyProgress(current), toLegacyProgress(total));
    }

    /**
     * Notifies the client of download progress. |current| denotes the number of
     * bytes received up to the call and |total| is the expected total size of the
     * response (or -1 if not determined).
     *
     * @deprecated Override the {@code long} overload to receive CEF's full 64-bit byte counts.
     */
    @Deprecated
    default void onDownloadProgress(CefURLRequest request, int current, int total) {}

    /**
     * Notifies the client of download progress without truncating CEF's 64-bit byte counts.
     * Implementations that only override the legacy {@code int} overload remain supported;
     * values outside that overload's range are saturated instead of wrapping.
     */
    default void onDownloadProgress(CefURLRequest request, long current, long total) {
        onDownloadProgress(request, toLegacyProgress(current), toLegacyProgress(total));
    }

    /**
     * Called when some part of the response is read. |data| contains the current
     * bytes received since the last call. This method will not be called if the
     * UR_FLAG_NO_DOWNLOAD_DATA flag is set on the request.
     */
    void onDownloadData(CefURLRequest request, byte[] data, int data_length);

    /**
     * Called on the IO thread when the browser needs credentials from the user.
     * |isProxy| indicates whether the host is a proxy server. |host| contains the
     * hostname and |port| contains the port number. Return true to continue the
     * request and call CefAuthCallback::Continue() when the authentication
     * information is available. Return false to cancel the request. This method
     * will only be called for requests initiated from the browser process.
     */
    boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback);

    private static int toLegacyProgress(long value) {
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }
}
