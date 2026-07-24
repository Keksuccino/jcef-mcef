// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefRequestContextSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefColorVariant;
import org.cef.browser.CefContentSettingType;
import org.cef.browser.CefContentSettingValue;
import org.cef.browser.CefRegistration;
import org.cef.browser.CefRequestContext;
import org.cef.browser.CefSettingObserver;
import org.cef.callback.CefCompletionCallback;
import org.cef.callback.CefResolveCallback;
import org.cef.callback.CefResolveResult;
import org.cef.network.CefCookieManager;
import org.junit.jupiter.api.Test;

import java.awt.BorderLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefRequestContextNativeTest {
    private static final long CALLBACK_TIMEOUT_SECONDS = 15;

    @Test
    void rejectsPreferenceReadsOutsideTheCefUiThread() throws Exception {
        CefRequestContext context = CefRequestContext.getGlobalContext();
        assertNotNull(context);

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Runnable verification = () -> {
            try {
                assertThrows(IllegalStateException.class, () -> context.hasPreference("homepage"));
                assertThrows(IllegalStateException.class, () -> context.getPreference("homepage"));
                assertThrows(IllegalStateException.class, () -> context.getAllPreferences(false));
                assertThrows(IllegalStateException.class, () -> context.canSetPreference("homepage"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        };
        Thread worker = new Thread(verification, "request-context-non-ui-test");
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        if (failure.get() != null)
            throw new AssertionError("Non-UI preference read contract failed", failure.get());
    }

    @Test
    void createsSharesMaintainsAndResolvesWithAnIncognitoContext() throws Exception {
        CefRequestContextSettings settings = new CefRequestContextSettings();
        settings.accept_language_list = "en-US,en";
        settings.cookieable_schemes_list = "custom";

        CefRequestContext context = CefRequestContext.createContext(settings, null);
        CefRequestContext shared = null;
        CefRequestContext isolated = null;
        CefCookieManager cookieManager = null;
        try {
            assertNotNull(context);
            assertFalse(context.isGlobal());
            assertTrue(context.isSame(context));
            assertTrue(context.isSharingWith(context));
            assertEquals("", context.getCachePath());

            shared = CefRequestContext.createContext(context, null);
            isolated = CefRequestContext.createContext(new CefRequestContextSettings(), null);
            assertNotNull(shared);
            assertNotNull(isolated);
            // IsSame may change after CEF's asynchronous BrowserContext association. Sharing is
            // the stable contract promised by CreateContext(existingContext, handler).
            assertTrue(context.isSharingWith(shared));
            assertFalse(context.isSame(isolated));
            assertFalse(context.isSharingWith(isolated));

            AtomicInteger cookieReadyCalls = new AtomicInteger();
            CountDownLatch cookieReady = new CountDownLatch(1);
            CefCompletionCallback cookieReadyCallback = () -> {
                cookieReadyCalls.incrementAndGet();
                cookieReady.countDown();
            };
            cookieManager = context.getCookieManager(cookieReadyCallback);
            assertNotNull(cookieManager);
            await(cookieReady, "cookie manager initialization");
            assertEquals(1, cookieReadyCalls.get());

            assertTrue(context.registerSchemeHandlerFactory("http", null, (browser, frame, schemeName, request) -> null));
            assertTrue(context.clearSchemeHandlerFactories());

            AtomicInteger resolveCalls = new AtomicInteger();
            AtomicReference<CefResolveResult> resolveResult = new AtomicReference<CefResolveResult>();
            CountDownLatch resolved = new CountDownLatch(1);
            CefResolveCallback resolveCallback = result -> {
                resolveCalls.incrementAndGet();
                resolveResult.set(result);
                resolved.countDown();
            };
            context.resolveHost("http://localhost", resolveCallback);
            await(resolved, "localhost resolution");
            assertEquals(1, resolveCalls.get());
            assertNotNull(resolveResult.get());
            assertTrue(resolveResult.get().isSuccess(), resolveResult.get().toString());
            assertFalse(resolveResult.get().getResolvedIpAddresses().isEmpty());

            AtomicInteger maintenanceCalls = new AtomicInteger();
            CountDownLatch maintenance = new CountDownLatch(4);
            context.clearCertificateExceptions(completion(maintenanceCalls, maintenance));
            context.clearHttpCache(completion(maintenanceCalls, maintenance));
            context.clearHttpAuthCredentials(completion(maintenanceCalls, maintenance));
            context.closeAllConnections(completion(maintenanceCalls, maintenance));
            await(maintenance, "request-context maintenance callbacks");
            assertEquals(4, maintenanceCalls.get());
        } finally {
            if (cookieManager != null) cookieManager.dispose();
            if (isolated != null) isolated.dispose();
            if (shared != null) shared.dispose();
            if (context != null) context.dispose();
        }

        assertThrows(IllegalStateException.class, context::isGlobal);
        assertThrows(IllegalStateException.class, () -> CefRequestContext.createContext(context, null));
    }

    @Test
    void observesAndMutatesSettingsOnTheCefUiThread() {
        String testUrl = "http://request-context.test/settings.html";
        String testContent = "<html><body>request context</body></html>";
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicInteger observerCalls = new AtomicInteger();

        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private CefRequestContext context_;
            private final AtomicReference<CefRegistration> registration_ = new AtomicReference<CefRegistration>();

            @Override
            protected void setupTest() {
                context_ = CefRequestContext.createContext(new CefRequestContextSettings(), null);
                assertNotNull(context_);
                addResource(testUrl, testContent, "text/html");
                browser_ = client_.createBrowser(testUrl, false, false, context_);
                assertNotNull(browser_);
                getContentPane().add(browser_.getUIComponent(), BorderLayout.CENTER);
                pack();
                setSize(800, 600);
                setVisible(true);
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                try {
                    CefSettingObserver observer = (requestingUrl, topLevelUrl, contentType) -> onSettingChanged(contentType);
                    CefRegistration registration = context_.addSettingObserver(observer);
                    assertNotNull(registration);
                    registration_.set(registration);
                    context_.setContentSetting(testUrl, testUrl, CefContentSettingType.IMAGES, CefContentSettingValue.BLOCK);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    terminateTest();
                }
            }

            private void onSettingChanged(CefContentSettingType contentType) {
                if (contentType != CefContentSettingType.IMAGES) return;
                try {
                    assertEquals(1, observerCalls.incrementAndGet());
                    assertEquals(CefContentSettingValue.BLOCK, context_.getContentSetting(testUrl, testUrl, CefContentSettingType.IMAGES));
                    assertEquals(Integer.valueOf(CefContentSettingValue.BLOCK.getValue()), context_.getWebsiteSetting(testUrl, testUrl, CefContentSettingType.IMAGES));

                    CefRegistration registration = registration_.getAndSet(null);
                    assertNotNull(registration);
                    registration.dispose();
                    registration.dispose();

                    context_.setWebsiteSetting(testUrl, testUrl, CefContentSettingType.IMAGES, Integer.valueOf(CefContentSettingValue.ALLOW.getValue()));
                    assertEquals(Integer.valueOf(CefContentSettingValue.ALLOW.getValue()), context_.getWebsiteSetting(testUrl, testUrl, CefContentSettingType.IMAGES));
                    assertEquals(CefContentSettingValue.ALLOW, context_.getContentSetting(testUrl, testUrl, CefContentSettingType.IMAGES));

                    context_.setChromeColorScheme(CefColorVariant.DARK, 0);
                    assertEquals(CefColorVariant.DARK, context_.getChromeColorSchemeMode());
                    assertEquals(0, context_.getChromeColorSchemeColor());
                    assertEquals(CefColorVariant.SYSTEM, context_.getChromeColorSchemeVariant());

                    int userColor = 0xFF336699;
                    context_.setChromeColorScheme(CefColorVariant.NEUTRAL, userColor);
                    assertEquals(CefColorVariant.DARK, context_.getChromeColorSchemeMode());
                    assertEquals(userColor, context_.getChromeColorSchemeColor());
                    assertEquals(CefColorVariant.NEUTRAL, context_.getChromeColorSchemeVariant());
                    context_.setChromeColorScheme(CefColorVariant.SYSTEM, 0);
                    assertEquals(CefColorVariant.SYSTEM, context_.getChromeColorSchemeMode());
                    assertEquals(0, context_.getChromeColorSchemeColor());
                    // CEF 151 may preserve the material variant or reset it with the user color,
                    // depending on the platform ThemeService implementation.
                    CefColorVariant resetVariant = context_.getChromeColorSchemeVariant();
                    assertTrue(resetVariant == CefColorVariant.SYSTEM || resetVariant == CefColorVariant.NEUTRAL);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    terminateTest();
                }
            }

            @Override
            protected void cleanupTest() {
                CefRegistration registration = registration_.getAndSet(null);
                if (registration != null) registration.dispose();
                if (context_ != null) context_.dispose();
                super.cleanupTest();
            }
        });

        frame.awaitCompletion();
        if (failure.get() != null)
            throw new AssertionError("Request-context UI callback failed", failure.get());
        assertEquals(1, observerCalls.get());
    }

    private static CefCompletionCallback completion(AtomicInteger calls, CountDownLatch latch) {
        return () -> {
            calls.incrementAndGet();
            latch.countDown();
        };
    }

    private static void await(CountDownLatch latch, String operation) throws InterruptedException {
        assertTrue(latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for " + operation);
    }
}
