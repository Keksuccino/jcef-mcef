// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.OS;
import org.cef.callback.CefCommandLine;
import org.cef.handler.CefAppHandlerAdapter;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Locale;
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
    static final String WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE = "WebAuthenticationUseInsecureSoftwareUnexportableKeys";
    static final String WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE = "ReportKeyCredentialManagerSupportWin";
    private static final String ENABLE_FEATURES_SWITCH = "enable-features";
    private static final String DISABLE_FEATURES_SWITCH = "disable-features";
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
            public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
                super.onBeforeCommandLineProcessing(processType, commandLine);
                configureCommandLine(processType, commandLine, OS.isWindows(), System.getProperty("os.arch", ""));
            }

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

    static void configureCommandLine(String processType, CefCommandLine commandLine, boolean windows, String architecture) {
        if (!processType.isEmpty() || !windows || !isArm64Architecture(architecture)) return;

        // Chromium 151 eagerly initializes PasskeyUnlockManager during browser startup. Enabling
        // software unexportable keys makes EnclaveManager post its support result asynchronously
        // without entering Windows KeyCredentialManager.IsSupportedAsync, whose ngcksp.dll path
        // can terminate native Windows ARM64 CI startup with NTE_BAD_KEYSET (0x80090016). This
        // deliberately changes WebAuthn unexportable-key capability semantics only inside the
        // Windows ARM64 JUnit browser process.
        appendCommaSeparatedSwitchValue(commandLine, ENABLE_FEATURES_SWITCH, WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE);

        // Chromium 151 enables this metrics-only WinRT probe by default. On GitHub's native
        // Windows ARM64 runners KeyCredentialManager.IsSupportedAsync can enter ngcksp.dll and
        // terminate startup with NTE_BAD_KEYSET (0x80090016). Keep this independent reporter
        // defense as well. Production command lines are unaffected because this handler belongs
        // exclusively to the JUnit bootstrap.
        appendCommaSeparatedSwitchValue(commandLine, DISABLE_FEATURES_SWITCH, WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);
    }

    private static void appendCommaSeparatedSwitchValue(CefCommandLine commandLine, String switchName, String requiredValue) {
        String values = commandLine.getSwitchValue(switchName);
        String updatedValues = appendCommaSeparatedValue(values, requiredValue);
        if (updatedValues.equals(values)) return;
        commandLine.removeSwitch(switchName);
        commandLine.appendSwitchWithValue(switchName, updatedValues);
    }

    static boolean isArm64Architecture(String architecture) {
        if (architecture == null) return false;
        String normalizedArchitecture = architecture.trim().toLowerCase(Locale.ROOT);
        return normalizedArchitecture.equals("aarch64") || normalizedArchitecture.equals("arm64");
    }

    static String appendCommaSeparatedValue(String values, String requiredValue) {
        if (values == null || values.isBlank()) return requiredValue;
        for (String value : values.split(",")) {
            if (value.trim().equals(requiredValue)) return values;
        }
        return values.endsWith(",") ? values + requiredValue : values + "," + requiredValue;
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
