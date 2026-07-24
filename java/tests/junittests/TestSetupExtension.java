// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// All test cases must install this extension for CEF to be properly initialized
// and shut down.
//
// For example:
//
//   @ExtendWith(TestSetupExtension.class)
//   class FooTest {
//        @Test
//        void testCaseThatRequiresCEF() {}
//   }
//
// This code is based on https://stackoverflow.com/a/51556718.
public class TestSetupExtension implements BeforeAllCallback, AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static boolean initialized_ = false;
    private static Throwable initializationFailure_ = null;

    private final CountDownLatch shutdownComplete_ = new CountDownLatch(1);
    private CefApp app_;

    // CefApp initializes native CEF on the first createClient() call. Retaining
    // this client keeps that initialization alive for native-only tests that do
    // not create their own browser client.
    private CefClient bootstrapClient_;

    @Override
    public void beforeAll(ExtensionContext context) {
        synchronized (TestSetupExtension.class) {
            if (initialized_) {
                if (initializationFailure_ != null) {
                    throw new ExtensionConfigurationException("JCEF test initialization previously failed", initializationFailure_);
                }
                return;
            }

            initialized_ = true;
            try {
                initialize(context);
            } catch (RuntimeException | Error exception) {
                initializationFailure_ = exception;
                throw exception;
            }
        }
    }

    // Executed before any tests are run.
    private void initialize(ExtensionContext context) {
        TestSetupContext.initialize(context);

        if (TestSetupContext.debugPrint()) {
            System.out.println("TestSetupExtension.initialize");
        }

        // Register a callback hook for when the root test context is shut down.
        context.getRoot().getStore(GLOBAL).put("jcef_test_setup", this);

        // Perform startup initialization on platforms that require it.
        if (!CefApp.startup(null)) {
            throw new ExtensionConfigurationException("CEF startup initialization failed");
        }

        CefApp.addAppHandler(new CefAppHandlerAdapter(null) {
            @Override
            public void stateHasChanged(org.cef.CefApp.CefAppState state) {
                if (state == CefAppState.TERMINATED) {
                    // Signal completion of CEF shutdown.
                    shutdownComplete_.countDown();
                }
            }
        });

        // Creating the singleton alone leaves CEF in NEW state. A retained
        // client forces synchronous N_Initialize/CefInitialize before any test
        // can construct a native CEF object such as CefDragData.
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = TestSetupContext.windowlessRenderingEnabled();
        app_ = CefApp.getInstance(settings);
        bootstrapClient_ = app_.createClient();
        if (CefApp.getState() != CefAppState.INITIALIZED) {
            throw new ExtensionConfigurationException("CEF initialization failed with state " + CefApp.getState());
        }
    }

    // Executed after all tests have completed.
    @Override
    public void close() throws Exception {
        if (TestSetupContext.debugPrint()) {
            System.out.println("TestSetupExtension.close");
        }

        if (app_ == null) return;
        app_.dispose();

        // Native shutdown normally completes synchronously, while the state
        // callback is posted to AWT. Keep the wait bounded so a lifecycle
        // regression cannot hang the test process indefinitely.
        try {
            if (!shutdownComplete_.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for CEF shutdown after " + SHUTDOWN_TIMEOUT_SECONDS + " seconds; state=" + CefApp.getState());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for CEF shutdown", e);
        }
        bootstrapClient_ = null;
        app_ = null;
    }
}
