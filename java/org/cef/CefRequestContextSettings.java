// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

/** Initialization settings for an individual {@code CefRequestContext}. */
public class CefRequestContextSettings implements Cloneable {
    /**
     * Directory where this context stores cache data. An empty value creates an incognito context.
     * A non-empty value must be an absolute path equal to or below
     * {@link CefSettings#root_cache_path}.
     */
    public String cache_path = null;

    /**
     * Whether session cookies should be persisted. Ignored for incognito contexts and contexts
     * that share the global cache path.
     */
    public boolean persist_session_cookies = false;

    /**
     * Comma-delimited, ordered language codes used for Accept-Language and navigator.language.
     */
    public String accept_language_list = null;

    /** Comma-delimited schemes supported by this context's cookie manager. */
    public String cookieable_schemes_list = null;

    /** Whether the default cookieable schemes (http, https, ws and wss) should be excluded. */
    public boolean cookieable_schemes_exclude_defaults = false;

    public CefRequestContextSettings() {}

    @Override
    public CefRequestContextSettings clone() {
        CefRequestContextSettings copy = new CefRequestContextSettings();
        copy.cache_path = cache_path;
        copy.persist_session_cookies = persist_session_cookies;
        copy.accept_language_list = accept_language_list;
        copy.cookieable_schemes_list = cookieable_schemes_list;
        copy.cookieable_schemes_exclude_defaults = cookieable_schemes_exclude_defaults;
        return copy;
    }
}
