// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefCommandLine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class TestSetupExtensionTest {
    @Test
    void recognizesJavaArm64ArchitectureAliases() {
        assertTrue(TestSetupExtension.isArm64Architecture("aarch64"));
        assertTrue(TestSetupExtension.isArm64Architecture("ARM64"));
        assertTrue(TestSetupExtension.isArm64Architecture(" arm64 "));
        assertFalse(TestSetupExtension.isArm64Architecture("amd64"));
        assertFalse(TestSetupExtension.isArm64Architecture(null));
    }

    @Test
    void preservesCommaSeparatedValuesAndAvoidsDuplicates() {
        String feature = TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE;
        assertEquals(feature, TestSetupExtension.appendCommaSeparatedValue("", feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing", feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing," + feature, feature));
        assertEquals("Existing, " + feature, TestSetupExtension.appendCommaSeparatedValue("Existing, " + feature, feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing,", feature));
    }

    @Test
    void configuresOnlyTheWindowsArm64BrowserProcessMitigations() {
        CommandLineRecorder armBrowser = new CommandLineRecorder("ExistingEnabledFeature", "ExistingDisabledFeature");
        TestSetupExtension.configureCommandLine("", armBrowser.commandLine(), true, "aarch64");
        assertCommandLine(armBrowser, "ExistingEnabledFeature," + TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, "ExistingDisabledFeature," + TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, 2, 2);

        CommandLineRecorder armRenderer = new CommandLineRecorder("ExistingEnabledFeature", "ExistingDisabledFeature");
        TestSetupExtension.configureCommandLine("renderer", armRenderer.commandLine(), true, "aarch64");
        assertCommandLine(armRenderer, "ExistingEnabledFeature", "ExistingDisabledFeature", 0, 0);

        CommandLineRecorder x64Browser = new CommandLineRecorder("ExistingEnabledFeature", "ExistingDisabledFeature");
        TestSetupExtension.configureCommandLine("", x64Browser.commandLine(), true, "amd64");
        assertCommandLine(x64Browser, "ExistingEnabledFeature", "ExistingDisabledFeature", 0, 0);

        CommandLineRecorder linuxArmBrowser = new CommandLineRecorder("ExistingEnabledFeature", "ExistingDisabledFeature");
        TestSetupExtension.configureCommandLine("", linuxArmBrowser.commandLine(), false, "aarch64");
        assertCommandLine(linuxArmBrowser, "ExistingEnabledFeature", "ExistingDisabledFeature", 0, 0);
    }

    @Test
    void updatesTheTwoFeatureListsIndependently() {
        CommandLineRecorder telemetryAlreadyDisabled = new CommandLineRecorder("ExistingEnabledFeature", TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);
        TestSetupExtension.configureCommandLine("", telemetryAlreadyDisabled.commandLine(), true, "arm64");
        assertCommandLine(telemetryAlreadyDisabled, "ExistingEnabledFeature," + TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, 1, 1);

        CommandLineRecorder softwareKeysAlreadyEnabled = new CommandLineRecorder(TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, "ExistingDisabledFeature");
        TestSetupExtension.configureCommandLine("", softwareKeysAlreadyEnabled.commandLine(), true, "arm64");
        assertCommandLine(softwareKeysAlreadyEnabled, TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, "ExistingDisabledFeature," + TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, 1, 1);
    }

    @Test
    void leavesExistingWindowsArm64MitigationsUntouched() {
        CommandLineRecorder recorder = new CommandLineRecorder(TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);
        TestSetupExtension.configureCommandLine("", recorder.commandLine(), true, "arm64");
        assertCommandLine(recorder, TestSetupExtension.WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE, TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, 0, 0);
    }

    private static void assertCommandLine(CommandLineRecorder recorder, String enabledFeatures, String disabledFeatures, int removals, int appends) {
        assertEquals(enabledFeatures, recorder.enabledFeatures());
        assertEquals(disabledFeatures, recorder.disabledFeatures());
        assertEquals(removals, recorder.removals());
        assertEquals(appends, recorder.appends());
    }

    private static final class CommandLineRecorder {
        private static final String ENABLE_FEATURES_SWITCH = "enable-features";
        private static final String DISABLE_FEATURES_SWITCH = "disable-features";
        private final Map<String, String> switches_ = new LinkedHashMap<String, String>();
        private final AtomicInteger removals_ = new AtomicInteger();
        private final AtomicInteger appends_ = new AtomicInteger();
        private final CefCommandLine commandLine_;

        private CommandLineRecorder(String enabledFeatures, String disabledFeatures) {
            switches_.put(ENABLE_FEATURES_SWITCH, enabledFeatures);
            switches_.put(DISABLE_FEATURES_SWITCH, disabledFeatures);
            commandLine_ = (CefCommandLine) Proxy.newProxyInstance(CefCommandLine.class.getClassLoader(), new Class<?>[] {CefCommandLine.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("getSwitchValue")) return switches_.getOrDefault(arguments[0], "");
                if (method.getName().equals("removeSwitch")) {
                    removals_.incrementAndGet();
                    switches_.remove(arguments[0]);
                    return null;
                }
                if (method.getName().equals("appendSwitchWithValue")) {
                    appends_.incrementAndGet();
                    switches_.put((String) arguments[0], (String) arguments[1]);
                    return null;
                }
                throw new UnsupportedOperationException("Unexpected CefCommandLine method " + method.getName());
            });
        }

        private CefCommandLine commandLine() {
            return commandLine_;
        }

        private String enabledFeatures() {
            return switches_.get(ENABLE_FEATURES_SWITCH);
        }

        private String disabledFeatures() {
            return switches_.get(DISABLE_FEATURES_SWITCH);
        }

        private int removals() {
            return removals_.get();
        }

        private int appends() {
            return appends_.get();
        }
    }
}
