// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefResourceHandler;
import org.cef.network.CefRequest;
import org.cef.network.CefURLRequest;

/**
 * Class that creates CefResourceHandler instances for handling scheme requests.
 * The methods of this class will always be called on the IO thread.
 */
public interface CefSchemeHandlerFactory {
    /**
     * Return a new resource handler instance to handle the request or NULL to allow default
     * handling of the request.
     *
     * @param browser The corresponding browser. This is {@code null} for standalone requests
     *         created by {@link CefURLRequest#create(CefRequest, CefURLRequestClient)}. A request
     *         created by {@link CefFrame#createURLRequest(CefRequest, CefURLRequestClient)} has an
     *         associated browser, but this Java value is still {@code null} for a native popup
     *         browser that has no corresponding Java browser object.
     * @param frame The associated frame. This is {@code null} for a standalone request and
     *         non-null for a frame-associated request, including one from a native popup browser.
     *         Instance only valid within the scope of this method.
     * @param schemeName Name of the scheme being created.
     * @param request The request itself. Cannot be modified in this callback. Instance only valid
     *         within the scope of this method.
     */
    public CefResourceHandler create(
            CefBrowser browser, CefFrame frame, String schemeName, CefRequest request);
}
