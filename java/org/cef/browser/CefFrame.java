// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.callback.CefStringVisitor;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefRequest;
import org.cef.network.CefURLRequest;

import java.util.Objects;

/**
 * Interface representing a frame.
 *
 * <p>Native-backed JCEF frames exist in the browser process and their operations may be called on
 * any thread. A Java wrapper can outlive its attachment to the underlying browser frame, so callers
 * should check {@link #isValid()} before invoking operations that modify frame state.
 */
public interface CefFrame {
    /**
     * Removes the native reference from an unused object. The caller must not race this method
     * against another operation on the same Java wrapper.
     */
    void dispose();

    /**
     * Returns the globally unique identifier for this frame or an empty string if the
     * underlying frame does not yet exist.
     * @return The frame identifier
     */
    String getIdentifier();

    /**
     * Emits the URL currently loaded in this frame.
     * @return the URL currently loaded in this frame.
     */
    String getURL();

    /**
     * Returns the name for this frame. If the frame has an assigned name (for
     * example, set via the iframe "name" attribute) then that value will be
     * returned. Otherwise a unique name will be constructed based on the frame
     * parent hierarchy. The main (top-level) frame will always have an empty name
     * value.
     * @return The frame name
     */
    String getName();

    /**
     * Returns true if this is the main (top-level) frame.
     * @return True if this frame is top-level otherwise false.
     */
    boolean isMain();

    /**
     * True if this object is currently attached to a valid frame.
     * @return True if valid otherwise false.
     */
    boolean isValid();

    /**
     * Returns true if this is the focused frame.
     * @return True if valid otherwise false.
     */
    boolean isFocused();

    /**
     * Returns the parent of this frame or NULL if this is the main (top-level)
     * frame.
     * @return The parent frame or NULL if this is the main frame
     */
    CefFrame getParent();

    /**
     * Returns the browser that this frame belongs to. Native popup browsers that do not have a
     * corresponding Java browser object will return {@code null}.
     *
     * @return The browser that owns this frame, or {@code null} if no Java browser object exists.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default CefBrowser getBrowser() {
        throw unsupportedOperation("getBrowser");
    }

    /**
     * Save this frame's HTML source to a temporary file and open it in the default text viewing
     * application. This method can only be called from the browser process.
     *
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void viewSource() {
        throw unsupportedOperation("viewSource");
    }

    /**
     * Retrieve this frame's HTML source as a string sent to the specified visitor. The native
     * bridge retains the visitor until its callback finishes.
     *
     * @param visitor Receives the frame source.
     * @throws NullPointerException if {@code visitor} is {@code null}.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void getSource(CefStringVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        throw unsupportedOperation("getSource");
    }

    /**
     * Retrieve this frame's display text as a string sent to the specified visitor. The native
     * bridge retains the visitor until its callback finishes.
     *
     * @param visitor Receives the frame text.
     * @throws NullPointerException if {@code visitor} is {@code null}.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void getText(CefStringVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        throw unsupportedOperation("getText");
    }

    /**
     * Load the request represented by the request object.
     *
     * <p>This method will fail with CEF's {@code INVALID_INITIATOR_ORIGIN} bad-IPC reason unless
     * the frame has first navigated to the request origin using another mechanism such as
     * {@link #loadURL(String)}.
     *
     * @param request The request to load.
     * @throws NullPointerException if {@code request} is {@code null}.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void loadRequest(CefRequest request) {
        Objects.requireNonNull(request, "request");
        throw unsupportedOperation("loadRequest");
    }

    /**
     * Load the specified URL in this frame.
     *
     * @param url The URL to load.
     * @throws NullPointerException if {@code url} is {@code null}.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void loadURL(String url) {
        Objects.requireNonNull(url, "url");
        throw unsupportedOperation("loadURL");
    }

    /**
     * Create a URL request that CEF treats as originating from this frame and its browser. Unlike
     * {@link CefURLRequest#create(CefRequest, CefURLRequestClient)}, the request participates in
     * this browser's request handling and uses its request context. CEF marks the request read-only
     * when creation begins, including when it subsequently rejects an invalid URL. JCEF performs
     * creation and all request access on the CEF UI thread; authentication callbacks run on CEF's
     * IO thread and all other {@link CefURLRequestClient} callbacks run on the CEF UI thread.
     *
     * @param request The request configuration.
     * @param client Receives progress, data, authentication, and completion callbacks.
     * @return The new URL request, or {@code null} if the frame or request is no longer valid or
     *         CEF cannot create the request.
     * @throws NullPointerException if {@code request} or {@code client} is {@code null}.
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default CefURLRequest createURLRequest(CefRequest request, CefURLRequestClient client) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(client, "client");
        throw unsupportedOperation("createURLRequest");
    }

    /**
     * Execute a string of JavaScript code in this frame. The url
     * parameter is the URL where the script in question can be found, if any.
     * The renderer may request this URL to show the developer the source of the
     * error. The line parameter is the base line number to use for error
     * reporting.
     *
     * @param code The code to be executed.
     * @param url The URL where the script in question can be found.
     * @param line The base line number to use for error reporting.
     */
    public void executeJavaScript(String code, String url, int line);

    /**
     * Execute undo in this frame.
     */
    public void undo();

    /**
     * Execute redo in this frame.
     */
    public void redo();

    /**
     * Execute cut in this frame.
     */
    public void cut();

    /**
     * Execute copy in this frame.
     */
    public void copy();

    /**
     * Execute paste in this frame.
     */
    public void paste();

    /**
     * Execute paste and match style in this frame.
     *
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void pasteAndMatchStyle() {
        throw unsupportedOperation("pasteAndMatchStyle");
    }

    /**
     * Execute delete in this frame.
     *
     * @throws UnsupportedOperationException if the implementation does not support this operation.
     */
    default void delete() {
        throw unsupportedOperation("delete");
    }

    /**
     * Execute selectAll in this frame.
     */
    public void selectAll();

    private static UnsupportedOperationException unsupportedOperation(String operation) {
        return new UnsupportedOperationException("CefFrame." + operation + " is not supported");
    }
}
