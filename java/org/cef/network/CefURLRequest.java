// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.network;

import org.cef.browser.CefRequestContext;
import org.cef.callback.CefURLRequestClient;
import org.cef.handler.CefLoadHandler.ErrorCode;

/**
 * Class used to make a URL request. Requests created by this class's static
 * factory methods are not associated with a browser instance, so no CefClient
 * callbacks will be executed. Requests created by
 * {@link org.cef.browser.CefFrame#createURLRequest(CefRequest, CefURLRequestClient)}
 * are associated with that frame and browser instead. This JCEF bridge creates
 * browser-process requests on the CEF UI thread and synchronously marshals all
 * request access to that thread, regardless of the Java caller thread.
 *
 * <p>The native URL-request lifecycle makes a late {@link #dispose()} or stale
 * token access harmless after CEF shutdown. It does not own generic native
 * wrappers: callers must dispose the {@link CefRequest} supplied at creation and
 * every {@link CefResponse} obtained from {@link #getResponse()} before the
 * {@link org.cef.CefApp} shutdown sequence begins (normally before disposing the
 * app).</p>
 */
public abstract class CefURLRequest {
    public static enum Status {
        UR_UNKNOWN,
        UR_SUCCESS,
        UR_IO_PENDING,
        UR_CANCELED,
        UR_FAILED,
        UR_NUM_VALUES,
    }

    // This CTOR can't be called directly. Call method create() instead.
    CefURLRequest() {}

    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }

    /**
     * Create a new URL request. Only GET, POST, HEAD, DELETE and PUT request
     * methods are supported. Multiple post data elements are not supported and
     * elements of type PDE_TYPE_FILE are supported because JCEF creates these
     * requests in the browser process. The |request| object will be marked as
     * read-only after calling this method. The caller remains responsible for
     * disposing {@code request} before {@link org.cef.CefApp} shutdown begins.
     */
    public static final CefURLRequest create(CefRequest request, CefURLRequestClient client) {
        return CefURLRequest_N.createNative(request, client);
    }

    /**
     * Create a new URL request using {@code requestContext}. Passing {@code null}
     * uses the global request context and is equivalent to {@link #create(CefRequest,
     * CefURLRequestClient)}. The caller remains responsible for disposing
     * {@code request} before {@link org.cef.CefApp} shutdown begins.
     */
    public static final CefURLRequest create(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext) {
        return CefURLRequest_N.createNative(request, client, requestContext);
    }

    /**
     * Removes the native reference from an unused object.
     */
    public abstract void dispose();

    /**
     * Returns the request object used to create this URL request. The returned
     * object is read-only and should not be modified.
     */
    public abstract CefRequest getRequest();

    /**
     * Returns the client.
     */
    public abstract CefURLRequestClient getClient();

    /**
     * Returns the request status.
     */
    public abstract Status getRequestStatus();

    /**
     * Returns the request error if status is UR_CANCELED or UR_FAILED, or 0
     * otherwise.
     */
    public ErrorCode getRequestError() {
        return ErrorCode.findByCode(getRequestErrorCode());
    }

    /** Returns the exact raw request error, including values unknown to this JCEF version. */
    public abstract int getRequestErrorCode();

    /**
     * Returns the response, or NULL if no response information is available.
     * Response information will only be available after the upload has completed.
     * The returned object is read-only and should not be modified. It is a generic
     * native wrapper and must be disposed before {@link org.cef.CefApp} shutdown
     * begins.
     */
    public abstract CefResponse getResponse();

    /**
     * Returns true if the response body was served from the cache. This includes
     * responses for which revalidation was required.
     *
     * <p>The default preserves compatibility with implementations compiled before
     * this method was added. Native JCEF URL requests override it with the exact
     * CEF result.</p>
     */
    public boolean responseWasCached() {
        return false;
    }

    /**
     * Cancel the request.
     */
    public abstract void cancel();
}
