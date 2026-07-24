// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.CefRequestContextSettings;
import org.cef.callback.CefCompletionCallback;
import org.cef.callback.CefNative;
import org.cef.callback.CefResolveCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefRequestContextHandler;
import org.cef.network.CefCookieManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

class CefRequestContext_N extends CefRequestContext implements CefNative {
    private static final Object CONTEXT_LOCK_TIE = new Object();

    // Used internally to store a pointer to the CEF object.
    private volatile long N_CefHandle = 0;
    private static CefRequestContext_N globalInstance = null;
    private CefRequestContextHandler handler = null;

    @Override
    public synchronized void setNativeRef(String identifier, long nativeRef) {
        N_CefHandle = nativeRef;
    }

    @Override
    public synchronized long getNativeRef(String identifier) {
        return N_CefHandle;
    }

    CefRequestContext_N() {
        super();
    }

    static synchronized CefRequestContext_N getGlobalContextNative() {
        CefRequestContext_N result = null;
        try {
            result = N_GetGlobalContext();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        if (result == null) return null;

        if (globalInstance == null) {
            globalInstance = result;
        } else {
            // Coordinate with dispose() on the instance lock. Otherwise disposal could clear the
            // cached handle after the validity check but before this method returns it.
            synchronized (globalInstance) {
                if (globalInstance.N_CefHandle == 0) {
                    globalInstance = result;
                } else {
                    // Every native lookup owns a new CEF reference. Keep one stable Java singleton
                    // and release the redundant wrapper even if a broken runtime unexpectedly
                    // changes the native address.
                    result.N_CefRequestContext_DTOR();
                }
            }
        }
        return globalInstance;
    }

    static CefRequestContext_N createNative(CefRequestContextSettings settings, CefRequestContextHandler handler) {
        CefRequestContext_N result = null;
        try {
            // Snapshot mutable public settings before crossing JNI so concurrent caller changes
            // cannot produce a partially mixed native structure.
            result = N_CreateContext(settings.clone(), handler);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        if (result != null) result.handler = handler;
        return result;
    }

    static CefRequestContext_N createSharedNative(CefRequestContext other, CefRequestContextHandler handler) {
        CefRequestContext_N nativeOther = requireNativeContext(other, "other");
        synchronized (nativeOther) {
            nativeOther.ensureValid();
            CefRequestContext_N result = null;
            try {
                result = N_CreateContextShared(nativeOther, handler);
            } catch (UnsatisfiedLinkError ule) {
                ule.printStackTrace();
            }
            if (result != null) result.handler = handler;
            return result;
        }
    }

    @Override
    public synchronized void dispose() {
        if (N_CefHandle == 0) return;
        try {
            N_CefRequestContext_DTOR();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            N_CefHandle = 0;
        } finally {
            handler = null;
        }
    }

    @Override
    public synchronized boolean isGlobal() {
        ensureValid();
        try {
            return N_IsGlobal();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean isSame(CefRequestContext other) {
        return compareContexts(other, false);
    }

    @Override
    public boolean isSharingWith(CefRequestContext other) {
        return compareContexts(other, true);
    }

    @Override
    public synchronized CefRequestContextHandler getHandler() {
        ensureValid();
        return handler;
    }

    @Override
    public synchronized String getCachePath() {
        ensureValid();
        try {
            return N_GetCachePath();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return "";
    }

    @Override
    public synchronized CefCookieManager getCookieManager(CefCompletionCallback callback) {
        ensureValid();
        try {
            return N_GetCookieManager(callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public synchronized boolean registerSchemeHandlerFactory(String schemeName, String domainName, CefSchemeHandlerFactory factory) {
        requireNonEmpty(schemeName, "schemeName");
        ensureValid();
        try {
            return N_RegisterSchemeHandlerFactory(schemeName, domainName == null ? "" : domainName, factory);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public synchronized boolean clearSchemeHandlerFactories() {
        ensureValid();
        try {
            return N_ClearSchemeHandlerFactories();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public synchronized void clearCertificateExceptions(CefCompletionCallback callback) {
        ensureValid();
        try {
            N_ClearCertificateExceptions(callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized void clearHttpCache(CefCompletionCallback callback) {
        ensureValid();
        try {
            N_ClearHttpCache(callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized void clearHttpAuthCredentials(CefCompletionCallback callback) {
        ensureValid();
        try {
            N_ClearHttpAuthCredentials(callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized void closeAllConnections(CefCompletionCallback callback) {
        ensureValid();
        try {
            N_CloseAllConnections(callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized void resolveHost(String origin, CefResolveCallback callback) {
        requireNonEmpty(origin, "origin");
        Objects.requireNonNull(callback, "callback");
        ensureValid();
        try {
            N_ResolveHost(origin, callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized Object getWebsiteSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType) {
        Objects.requireNonNull(requestingUrl, "requestingUrl");
        Objects.requireNonNull(topLevelUrl, "topLevelUrl");
        Objects.requireNonNull(contentType, "contentType");
        ensureValid();
        try {
            return N_GetWebsiteSetting(requestingUrl, topLevelUrl, contentType.getValue());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public synchronized void setWebsiteSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType, Object value) {
        Objects.requireNonNull(requestingUrl, "requestingUrl");
        Objects.requireNonNull(topLevelUrl, "topLevelUrl");
        Objects.requireNonNull(contentType, "contentType");
        ensureValid();
        try {
            N_SetWebsiteSetting(requestingUrl, topLevelUrl, contentType.getValue(), value);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized CefContentSettingValue getContentSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType) {
        Objects.requireNonNull(requestingUrl, "requestingUrl");
        Objects.requireNonNull(topLevelUrl, "topLevelUrl");
        Objects.requireNonNull(contentType, "contentType");
        ensureValid();
        try {
            return CefContentSettingValue.fromValue(N_GetContentSetting(requestingUrl, topLevelUrl, contentType.getValue()));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return CefContentSettingValue.DEFAULT;
    }

    @Override
    public synchronized void setContentSetting(String requestingUrl, String topLevelUrl, CefContentSettingType contentType, CefContentSettingValue value) {
        Objects.requireNonNull(requestingUrl, "requestingUrl");
        Objects.requireNonNull(topLevelUrl, "topLevelUrl");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(value, "value");
        ensureValid();
        try {
            N_SetContentSetting(requestingUrl, topLevelUrl, contentType.getValue(), value.getValue());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized CefRegistration addSettingObserver(CefSettingObserver observer) {
        Objects.requireNonNull(observer, "observer");
        ensureValid();
        try {
            return N_AddSettingObserver(observer);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public synchronized void setChromeColorScheme(CefColorVariant variant, int userColor) {
        Objects.requireNonNull(variant, "variant");
        ensureValid();
        try {
            N_SetChromeColorScheme(variant.getValue(), userColor);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized CefColorVariant getChromeColorSchemeMode() {
        ensureValid();
        try {
            return CefColorVariant.fromValue(N_GetChromeColorSchemeMode());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return CefColorVariant.SYSTEM;
    }

    @Override
    public synchronized int getChromeColorSchemeColor() {
        ensureValid();
        try {
            return N_GetChromeColorSchemeColor();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return 0;
    }

    @Override
    public synchronized CefColorVariant getChromeColorSchemeVariant() {
        ensureValid();
        try {
            return CefColorVariant.fromValue(N_GetChromeColorSchemeVariant());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return CefColorVariant.SYSTEM;
    }

    @Override
    public synchronized boolean hasPreference(String name) {
        Objects.requireNonNull(name, "name");
        ensureValid();
        try {
            return N_HasPreference(name);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public synchronized Object getPreference(String name) {
        Objects.requireNonNull(name, "name");
        ensureValid();
        try {
            return N_GetPreference(name);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public synchronized Map<String, Object> getAllPreferences(boolean includeDefaults) {
        ensureValid();
        try {
            return N_GetAllPreferences(includeDefaults);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return new HashMap<String, Object>();
    }

    @Override
    public synchronized boolean canSetPreference(String name) {
        Objects.requireNonNull(name, "name");
        ensureValid();
        try {
            return N_CanSetPreference(name);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public synchronized String setPreference(String name, Object value) {
        Objects.requireNonNull(name, "name");
        ensureValid();
        try {
            return N_SetPreference(name, value);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return ule.getMessage();
        }
    }

    // Native conversion is intentionally exercised without mutating a real CEF preference. Keep
    // this package-private test seam beside the production conversion call site.
    static Object roundTripPreferenceValueForTesting(Object value) {
        return N_RoundTripPreferenceValueForTesting(value);
    }

    static boolean isPreferenceResetForTesting(Object value) {
        return N_IsPreferenceResetForTesting(value);
    }

    private boolean compareContexts(CefRequestContext other, boolean sharing) {
        CefRequestContext_N nativeOther = requireNativeContext(other, "other");
        if (nativeOther == this) {
            synchronized (this) {
                ensureValid();
                return true;
            }
        }

        // Comparisons must keep both wrappers alive until JNI has taken native references. Lock in
        // stable identity order (with a tie lock for the rare hash collision) to avoid an ABBA
        // deadlock when two threads compare the same pair in opposite directions.
        int thisHash = System.identityHashCode(this);
        int otherHash = System.identityHashCode(nativeOther);
        if (thisHash < otherHash)
            return compareContextsLocked(this, nativeOther, nativeOther, sharing);
        if (thisHash > otherHash)
            return compareContextsLocked(nativeOther, this, nativeOther, sharing);
        synchronized (CONTEXT_LOCK_TIE) {
            return compareContextsLocked(this, nativeOther, nativeOther, sharing);
        }
    }

    private boolean compareWithLockedContext(CefRequestContext_N other, boolean sharing) {
        ensureValid();
        other.ensureValid();
        try {
            return sharing ? N_IsSharingWith(other) : N_IsSame(other);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return false;
        }
    }

    private boolean compareContextsLocked(CefRequestContext_N first, CefRequestContext_N second, CefRequestContext_N other, boolean sharing) {
        synchronized (first) {
            synchronized (second) {
                return compareWithLockedContext(other, sharing);
            }
        }
    }

    private static CefRequestContext_N requireNativeContext(CefRequestContext context, String name) {
        Objects.requireNonNull(context, name);
        if (!(context instanceof CefRequestContext_N)) {
            throw new IllegalArgumentException(name + " is not a native JCEF request context");
        }
        return (CefRequestContext_N) context;
    }

    private static void requireNonEmpty(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
    }

    private void ensureValid() {
        if (N_CefHandle == 0) throw new IllegalStateException("CefRequestContext is disposed");
    }

    private static native CefRequestContext_N N_GetGlobalContext();
    private static native CefRequestContext_N N_CreateContext(CefRequestContextSettings settings, CefRequestContextHandler handler);
    private static native CefRequestContext_N N_CreateContextShared(CefRequestContext other, CefRequestContextHandler handler);
    private native boolean N_IsSame(CefRequestContext other);
    private native boolean N_IsSharingWith(CefRequestContext other);
    private native boolean N_IsGlobal();
    private native String N_GetCachePath();
    private native CefCookieManager N_GetCookieManager(CefCompletionCallback callback);
    private native boolean N_RegisterSchemeHandlerFactory(String schemeName, String domainName, CefSchemeHandlerFactory factory);
    private native boolean N_ClearSchemeHandlerFactories();
    private native void N_ClearCertificateExceptions(CefCompletionCallback callback);
    private native void N_ClearHttpCache(CefCompletionCallback callback);
    private native void N_ClearHttpAuthCredentials(CefCompletionCallback callback);
    private native void N_CloseAllConnections(CefCompletionCallback callback);
    private native void N_ResolveHost(String origin, CefResolveCallback callback);
    private native Object N_GetWebsiteSetting(String requestingUrl, String topLevelUrl, int contentType);
    private native void N_SetWebsiteSetting(String requestingUrl, String topLevelUrl, int contentType, Object value);
    private native int N_GetContentSetting(String requestingUrl, String topLevelUrl, int contentType);
    private native void N_SetContentSetting(String requestingUrl, String topLevelUrl, int contentType, int value);
    private native CefRegistration N_AddSettingObserver(CefSettingObserver observer);
    private native void N_SetChromeColorScheme(int variant, int userColor);
    private native int N_GetChromeColorSchemeMode();
    private native int N_GetChromeColorSchemeColor();
    private native int N_GetChromeColorSchemeVariant();
    private native boolean N_HasPreference(String name);
    private native Object N_GetPreference(String name);
    private native Map<String, Object> N_GetAllPreferences(boolean includeDefaults);
    private native boolean N_CanSetPreference(String name);
    private native String N_SetPreference(String name, Object value);
    private static native Object N_RoundTripPreferenceValueForTesting(Object value);
    private static native boolean N_IsPreferenceResetForTesting(Object value);
    private native void N_CefRequestContext_DTOR();
}
