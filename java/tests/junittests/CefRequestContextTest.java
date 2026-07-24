// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefRequestContextSettings;
import org.cef.browser.CefColorVariant;
import org.cef.browser.CefContentSettingType;
import org.cef.browser.CefContentSettingValue;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefResolveResult;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class CefRequestContextTest {
    @Test
    void clonesEveryRequestContextSetting() {
        CefRequestContextSettings settings = new CefRequestContextSettings();
        settings.cache_path = "/absolute/cache";
        settings.persist_session_cookies = true;
        settings.accept_language_list = "de-DE,de,en-US";
        settings.cookieable_schemes_list = "custom,other";
        settings.cookieable_schemes_exclude_defaults = true;

        CefRequestContextSettings copy = settings.clone();

        assertNotSame(settings, copy);
        assertEquals(settings.cache_path, copy.cache_path);
        assertEquals(settings.persist_session_cookies, copy.persist_session_cookies);
        assertEquals(settings.accept_language_list, copy.accept_language_list);
        assertEquals(settings.cookieable_schemes_list, copy.cookieable_schemes_list);
        assertEquals(settings.cookieable_schemes_exclude_defaults, copy.cookieable_schemes_exclude_defaults);

        copy.cache_path = "/different/cache";
        assertEquals("/absolute/cache", settings.cache_path);
    }

    @Test
    void pinsContentSettingTypesToTheCef151Range() {
        CefContentSettingType[] values = CefContentSettingType.values();
        assertEquals(131, values.length);
        for (int nativeValue = 0; nativeValue < values.length; nativeValue++) {
            assertEquals(nativeValue, values[nativeValue].getValue());
            assertEquals(values[nativeValue], CefContentSettingType.fromValue(nativeValue));
        }

        assertEquals(18, CefContentSettingType.PERSISTENT_STORAGE.getValue());
        assertEquals(60, CefContentSettingType.INSECURE_PRIVATE_NETWORK_DEPRECATED.getValue());
        assertEquals(77, CefContentSettingType.PRIVATE_NETWORK_GUARD_DEPRECATED.getValue());
        assertEquals(85, CefContentSettingType.THIRD_PARTY_STORAGE_PARTITIONING_DEPRECATED.getValue());
        assertEquals(93, CefContentSettingType.TOP_LEVEL_TPCD_ORIGIN_TRIAL_DEPRECATED.getValue());
        assertEquals(102, CefContentSettingType.SUB_APP_INSTALLATION_PROMPTS.getValue());
        assertEquals(108, CefContentSettingType.TRACKING_PROTECTION_DEPRECATED.getValue());
        assertEquals(127, CefContentSettingType.LOCAL_NETWORK.getValue());
        assertEquals(130, CefContentSettingType.INLINE_CUE_MENU.getValue());
        assertThrows(IllegalArgumentException.class, () -> CefContentSettingType.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefContentSettingType.fromValue(131));
    }

    @Test
    void pinsContentValuesAndColorVariantsToTheCef151Ranges() {
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), Arrays.stream(CefContentSettingValue.values()).map(CefContentSettingValue::getValue).toList());
        assertEquals(CefContentSettingValue.DETECT_IMPORTANT_CONTENT_DEPRECATED, CefContentSettingValue.fromValue(5));
        assertThrows(IllegalArgumentException.class, () -> CefContentSettingValue.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefContentSettingValue.fromValue(6));

        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6), Arrays.stream(CefColorVariant.values()).map(CefColorVariant::getValue).toList());
        assertEquals(CefColorVariant.EXPRESSIVE, CefColorVariant.fromValue(6));
        assertThrows(IllegalArgumentException.class, () -> CefColorVariant.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefColorVariant.fromValue(7));
    }

    @Test
    void resolveResultPreservesUnknownErrorsAndOwnsAnImmutableAddressSnapshot() {
        List<String> mutableAddresses = new ArrayList<String>(Arrays.asList("127.0.0.1", "::1"));
        CefResolveResult success = new CefResolveResult(ErrorCode.ERR_NONE.getCode(), mutableAddresses);
        mutableAddresses.clear();

        assertTrue(success.isSuccess());
        assertEquals(ErrorCode.ERR_NONE, success.getError().orElseThrow());
        assertEquals(Arrays.asList("127.0.0.1", "::1"), success.getResolvedIpAddresses());
        assertThrows(UnsupportedOperationException.class, () -> success.getResolvedIpAddresses().add("192.0.2.1"));

        CefResolveResult unknown = new CefResolveResult(-123456, List.of());
        assertFalse(unknown.isSuccess());
        assertEquals(-123456, unknown.getErrorCode());
        assertTrue(unknown.getError().isEmpty());
        assertThrows(NullPointerException.class, () -> new CefResolveResult(0, null));
        assertThrows(NullPointerException.class, () -> new CefResolveResult(0, Arrays.asList("127.0.0.1", null)));
    }

    @Test
    void validatesRequiredArgumentsBeforeCrossingJni() {
        CefRequestContext context = newUninitializedContext();

        assertThrows(NullPointerException.class, () -> CefRequestContext.createContext((CefRequestContextSettings) null, null));
        assertThrows(NullPointerException.class, () -> CefRequestContext.createContext((CefRequestContext) null, null));
        assertThrows(NullPointerException.class, () -> context.isSame(null));
        assertThrows(NullPointerException.class, () -> context.isSharingWith(null));
        assertThrows(NullPointerException.class, () -> context.registerSchemeHandlerFactory(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> context.registerSchemeHandlerFactory("  ", null, null));
        assertThrows(NullPointerException.class, () -> context.resolveHost("http://localhost", null));
        assertThrows(IllegalArgumentException.class, () -> context.resolveHost("", result -> {}));
        assertThrows(NullPointerException.class, () -> context.getWebsiteSetting(null, "", CefContentSettingType.IMAGES));
        assertThrows(NullPointerException.class, () -> context.getWebsiteSetting("", null, CefContentSettingType.IMAGES));
        assertThrows(NullPointerException.class, () -> context.getWebsiteSetting("", "", null));
        assertThrows(NullPointerException.class, () -> context.setContentSetting("", "", CefContentSettingType.IMAGES, null));
        assertThrows(NullPointerException.class, () -> context.addSettingObserver(null));
        assertThrows(NullPointerException.class, () -> context.setChromeColorScheme(null, 0));
        assertThrows(NullPointerException.class, () -> context.hasPreference(null));
        assertThrows(NullPointerException.class, () -> context.getPreference(null));
        assertThrows(NullPointerException.class, () -> context.canSetPreference(null));
        assertThrows(NullPointerException.class, () -> context.setPreference(null, null));
    }

    @Test
    void rejectsUseAfterDisposeAndKeepsDisposeIdempotent() {
        CefRequestContext context = newUninitializedContext();

        assertDoesNotThrow(context::dispose);
        assertDoesNotThrow(context::dispose);
        assertThrows(IllegalStateException.class, context::isGlobal);
        assertThrows(IllegalStateException.class, context::getHandler);
        assertThrows(IllegalStateException.class, context::getCachePath);
        assertThrows(IllegalStateException.class, context::getCookieManager);
        assertThrows(IllegalStateException.class, context::clearSchemeHandlerFactories);
        assertThrows(IllegalStateException.class, context::clearCertificateExceptions);
        assertThrows(IllegalStateException.class, context::clearHttpCache);
        assertThrows(IllegalStateException.class, context::clearHttpAuthCredentials);
        assertThrows(IllegalStateException.class, context::closeAllConnections);
        assertThrows(IllegalStateException.class, context::getChromeColorSchemeMode);
        assertThrows(IllegalStateException.class, context::getChromeColorSchemeColor);
        assertThrows(IllegalStateException.class, context::getChromeColorSchemeVariant);
    }

    private static CefRequestContext newUninitializedContext() {
        try {
            Class<?> nativeClass = Class.forName("org.cef.browser.CefRequestContext_N");
            Constructor<?> constructor = nativeClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (CefRequestContext) constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
