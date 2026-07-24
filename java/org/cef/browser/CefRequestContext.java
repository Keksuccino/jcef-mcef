// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.CefRequestContextSettings;
import org.cef.callback.CefCompletionCallback;
import org.cef.callback.CefResolveCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefRequestContextHandler;
import org.cef.network.CefCookieManager;

import java.util.Map;
import java.util.Objects;

/**
 * A request context provides request handling for a set of related browser
 * objects. A request context is specified when creating a new browser object
 * via the CefClient.createBrowser method. Browser objects with different
 * request contexts will never be hosted in the same render process. Browser
 * objects with the same request context may or may not be hosted in the same
 * render process depending on the process model. Browser objects created
 * indirectly via the JavaScript window.open function or targeted links will
 * share the same render process and the same request context as the source
 * browser. When running in single-process mode there is only a single render
 * process (the main process) and so all browsers created in single-process mode
 * will share the same request context. This will be the first request context
 * passed into the CefClient.createBrowser method and all other request
 * context objects will be ignored.
 */
public abstract class CefRequestContext {
    // This CTOR can't be called directly. Call method create() instead.
    CefRequestContext() {}

    /**
     * Returns the global context object.
     */
    public static final CefRequestContext getGlobalContext() {
        return CefRequestContext_N.getGlobalContextNative();
    }

    /**
     * Creates a new context object with the specified handler.
     */
    public static final CefRequestContext createContext(CefRequestContextHandler handler) {
        return CefRequestContext_N.createNative(new CefRequestContextSettings(), handler);
    }

    /** Creates a new context object with the specified settings and optional handler. */
    public static final CefRequestContext createContext(CefRequestContextSettings settings, CefRequestContextHandler handler) {
        return CefRequestContext_N.createNative(Objects.requireNonNull(settings, "settings"), handler);
    }

    /** Creates a new context object that shares storage with {@code other}. */
    public static final CefRequestContext createContext(CefRequestContext other, CefRequestContextHandler handler) {
        return CefRequestContext_N.createSharedNative(Objects.requireNonNull(other, "other"), handler);
    }

    public abstract void dispose();

    /**
     * Returns true if this object is the global context.
     */
    public abstract boolean isGlobal();

    /** Returns true if this object points to the same native context as {@code other}. */
    public abstract boolean isSame(CefRequestContext other);

    /** Returns true if this object shares storage with {@code other}. */
    public abstract boolean isSharingWith(CefRequestContext other);

    /**
     * Returns the handler for this context if any.
     */
    public abstract CefRequestContextHandler getHandler();

    /** Returns the cache path, or an empty string for an incognito context. */
    public abstract String getCachePath();

    /** Returns this context's cookie manager after optionally initializing its storage. */
    public abstract CefCookieManager getCookieManager(CefCompletionCallback callback);

    /** Returns this context's cookie manager. */
    public final CefCookieManager getCookieManager() {
        return getCookieManager(null);
    }

    /** Registers, replaces or removes a context-specific scheme handler factory. */
    public abstract boolean registerSchemeHandlerFactory(String schemeName, String domainName, CefSchemeHandlerFactory factory);

    /** Clears every scheme handler factory registered with this context. */
    public abstract boolean clearSchemeHandlerFactories();

    /** Clears remembered certificate exceptions and then invokes {@code callback}, if provided. */
    public abstract void clearCertificateExceptions(CefCompletionCallback callback);

    public final void clearCertificateExceptions() {
        clearCertificateExceptions(null);
    }

    /** Clears the HTTP cache and then invokes {@code callback}, if provided. */
    public abstract void clearHttpCache(CefCompletionCallback callback);

    public final void clearHttpCache() {
        clearHttpCache(null);
    }

    /** Clears remembered HTTP authentication credentials. */
    public abstract void clearHttpAuthCredentials(CefCompletionCallback callback);

    public final void clearHttpAuthCredentials() {
        clearHttpAuthCredentials(null);
    }

    /** Closes all active and idle connections owned by this context. */
    public abstract void closeAllConnections(CefCompletionCallback callback);

    public final void closeAllConnections() {
        closeAllConnections(null);
    }

    /** Resolves {@code origin}; the callback runs asynchronously on the CEF UI thread. */
    public abstract void resolveHost(String origin, CefResolveCallback callback);

    /**
     * Returns the website setting for the specified URLs, or {@code null} when no value is
     * configured. This method must be called on the browser-process UI thread.
     */
    public abstract Object getWebsiteSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType);

    /** Sets or removes a website setting. A {@code null} value removes the configured value. */
    public abstract void setWebsiteSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType, Object value);

    /**
     * Returns the content setting for the specified URLs. This method must be called on the
     * browser-process UI thread.
     */
    public abstract CefContentSettingValue getContentSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType);

    /** Sets a content setting in the default scope for the specified URLs. */
    public abstract void setContentSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType, CefContentSettingValue value);

    /**
     * Adds a setting observer. The observer remains registered until the returned registration is
     * disposed. This method must be called on the browser-process UI thread.
     */
    public abstract CefRegistration addSettingObserver(CefSettingObserver observer);

    /** Applies the Chrome color variant and ARGB user color to all contexts sharing storage. */
    public abstract void setChromeColorScheme(CefColorVariant variant, int userColor);

    /** Returns the effective Chrome color scheme mode on the browser-process UI thread. */
    public abstract CefColorVariant getChromeColorSchemeMode();

    /** Returns the effective ARGB user color on the browser-process UI thread. */
    public abstract int getChromeColorSchemeColor();

    /** Returns the effective Chrome color variant on the browser-process UI thread. */
    public abstract CefColorVariant getChromeColorSchemeVariant();

    /**
     * Returns true if a preference with the specified |name| exists.
     * <p>
     * This method must be called on the browser process UI thread, otherwise it throws {@link
     * IllegalStateException}. It is easiest to ensure the correct calling thread by using a
     * callback method invoked by the browser process UI thread, such as
     * CefLifeSpanHandler.onAfterCreated(CefBrowser), to configure the preferences.
     */
    public abstract boolean hasPreference(String name);

    /**
     * Returns the value for the preference with the specified |name|. Returns
     * NULL if the preference does not exist.
     * This method must be called on the browser process UI thread, otherwise it throws {@link
     * IllegalStateException}.
     */
    public abstract Object getPreference(String name);

    /**
     * Returns all preferences as a dictionary. If |includeDefaults| is true then
     * preferences currently at their default value will be included. The returned
     * object can be modified but modifications will not persist. This method must
     * be called on the browser process UI thread, otherwise it throws {@link
     * IllegalStateException}.
     */
    public abstract Map<String, Object> getAllPreferences(boolean includeDefaults);

    /**
     * Returns true if the preference with the specified |name| can be modified
     * using setPreference. As one example preferences set via the command-line
     * usually cannot be modified. This method must be called on the browser
     * process UI thread, otherwise it throws {@link IllegalStateException}.
     */
    public abstract boolean canSetPreference(String name);

    /**
     * Set the |value| associated with preference |name|. Returns null if the
     * value is set successfully, an error string otherwise. If |value| is NULL the
     * preference will be restored to its default value. If setting the preference
     * fails then a detailed description of the problem will be returned.
     * This method must be called on the browser process UI thread, otherwise it will always return
     * an error string.
     */
    public abstract String setPreference(String name, Object value);
}
