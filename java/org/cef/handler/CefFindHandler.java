// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;

import java.awt.Rectangle;

/**
 * Implement this interface to receive find results. Methods are called on the CEF UI thread, which
 * is not necessarily the AWT event-dispatch thread.
 */
public interface CefFindHandler {
    /**
     * Reports results for a search started by {@link CefBrowser#find(String, boolean, boolean,
     * boolean)}.
     *
     * <p>{@code identifier} uniquely identifies the active search, {@code count} is the number of
     * matches found so far, and {@code activeMatchOrdinal} is the zero-based active match position.
     * {@code finalUpdate} is true for the final notification for that search.
     *
     * <p>{@code selectionRect} is a Java-owned snapshot in window coordinates. It is independent
     * of CEF's native callback storage and may be retained or modified after this method returns.
     *
     * @param browser the browser that produced the result
     * @param identifier the unique incremental identifier for the active search
     * @param count the number of matches currently identified
     * @param selectionRect the current match bounds in window coordinates
     * @param activeMatchOrdinal the zero-based position of the active match
     * @param finalUpdate whether this is the final notification for the search
     */
    void onFindResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate);
}
