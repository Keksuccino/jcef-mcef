// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.callback.CefCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.callback.CefResourceSkipCallback;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.LongRef;
import org.cef.misc.StringRef;
import org.cef.network.CefCookie;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * Implement this interface to handle custom resource requests. This interface is a "new" API and an
 * old API in one: the deprecated methods are part of the old API. The new API allows for parallel
 * processing of requests, because it does not channel all reads through a dedicated IO thread, and
 * it allows for skipping of bytes as part of handling Range requests.
 */
public interface CefResourceHandler {
    /**
     * Begin processing the request.
     * @param request The request itself. Cannot be modified in this callback. Instance only valid
     *         within the scope of this method.
     * @param callback Callback to continue or cancel the request.
     * @return True to handle the request and call CefCallback.Continue() once the response header
     *         information is available.
     * @deprecated Use open() instead
     */
    boolean processRequest(CefRequest request, CefCallback callback);

    /**
     * Open the response stream. This and related (getResponseHeaders, read, skip) methods will be
     * called in sequence but not from a dedicated thread. <p> For backwards compatibility set
     * |handleRequest| to false and return false and the processRequest() method will be called.
     * @param request The request itself. Cannot be modified in this callback. Instance only valid
     *         within the scope of this method.
     * @param handleRequest Set to true to handle/cancel the request immediately
     * @param callback Callback to continue or cancel the request at a later time
     * @return True to handle the request
     */
    default boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
        // Signal the native bridge to fall back to the legacy processRequest callback.
        handleRequest.set(false);
        return false;
    }

    /**
     * Retrieve response header information. If the response length is not known set
     * |responseLength| to -1 and readResponse() will be called until it returns false. If the
     * response length is known set |responseLength| to a positive value and readResponse() will be
     * called until it returns false or the specified number of bytes have been read. Use the
     * |response| object to set the mime type, http status code and other optional header values.
     * @param response The request response that should be returned. Instance only valid within the
     *         scope of this method.
     * @param responseLength Optionally set the response length if known.
     * @param redirectUrl Optionally redirect the request to a new URL.
     */
    default void getResponseHeaders(CefResponse response, LongRef responseLength, StringRef redirectUrl) {
        long initialLength = responseLength.get();
        int compatibleLength;
        if (initialLength > Integer.MAX_VALUE) compatibleLength = Integer.MAX_VALUE;
        else if (initialLength < Integer.MIN_VALUE) compatibleLength = Integer.MIN_VALUE;
        else compatibleLength = (int) initialLength;
        IntRef legacyResponseLength = new IntRef(compatibleLength);
        getResponseHeaders(response, legacyResponseLength, redirectUrl);
        responseLength.set(legacyResponseLength.get());
    }

    /**
     * Legacy response-header callback using a 32-bit content length.
     * @deprecated Override {@link #getResponseHeaders(CefResponse, LongRef, StringRef)} so response
     *         lengths larger than {@link Integer#MAX_VALUE} are preserved.
     */
    @Deprecated
    default void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {}

    /**
     * Read response data. If data is available immediately copy up to |bytesToRead| bytes into
     * |dataOut|, set |bytesRead| to the number of bytes copied, and return true. To read the data
     * at a later time set |bytesRead| to 0, return true and call CefCallback.Continue() when the
     * data is available. To indicate response completion return false.
     * @param dataOut Write data to this buffer.
     * @param bytesToRead Size of the buffer.
     * @param bytesRead Number of bytes written to the buffer.
     * @param callback Callback to execute if data will be available asynchronously.
     * @return True if more data is or will be available.
     * @deprecated Use read() instead
     */
    boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback);

    /**
     * Read response data. If data is available immediately copy up to |bytesToRead| bytes into
     * |dataOut|, set |bytesRead| to the number of bytes copied, and return true. To read the data
     * at a later time set |bytesRead| to 0, return true, fill the buffer returned by
     * {@link CefResourceReadCallback#getBuffer()} and call the callback exactly once when the data
     * is available. To indicate response completion set |bytesRead| to 0 and return false.
     * To indicate failure set |bytesRead| to {@code < 0} (e.g. -2 for ERR_FAILED) and return false.
     * <p>For backwards compatibility set |bytesRead| to -1 and return false and the readResponse()
     * method will be called.
     * @param dataOut Write immediate response data to this buffer. For asynchronous reads use the
     *         callback buffer, which remains valid until the callback is called.
     * @param bytesToRead Size of the buffer.
     * @param bytesRead Number of bytes written to the buffer.
     * @param callback Callback to execute if data will be available asynchronously.
     * @return True if more data is or will be available.
     */
    default boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
        // Signal the native bridge to fall back to the legacy readResponse callback.
        bytesRead.set(-1);
        return false;
    }

    /**
     * Skip response data when requested by a Range header. Skip over and discard |bytesToSkip|
     * bytes of response data. If data is available immediately set |bytesSkipped| to the number of
     * bytes skipped and return true. To read the data at a later time set |bytesSkipped| to 0,
     * return true and execute |callback| exactly once when the data is available. To indicate
     * failure set |bytesSkipped| to {@code < 0} (e.g. -2 for ERR_FAILED) and return false.
     * @param bytesToSkip Number of bytes to skip.
     * @param bytesSkipped Number of bytes skipped.
     * @param callback Callback to execute if data will be skipped asynchronously.
     */
    default boolean skip(long bytesToSkip, LongRef bytesSkipped, CefResourceSkipCallback callback) {
        // Legacy handlers cannot satisfy range skips directly, so report an explicit failure.
        bytesSkipped.set(-2);
        return false;
    }

    /**
     * Request processing has been canceled.
     */
    void cancel();
}
