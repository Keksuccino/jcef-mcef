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
        String feature = TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE;
        assertEquals(feature, TestSetupExtension.appendCommaSeparatedValue("", feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing", feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing," + feature, feature));
        assertEquals("Existing, " + feature, TestSetupExtension.appendCommaSeparatedValue("Existing, " + feature, feature));
        assertEquals("Existing," + feature, TestSetupExtension.appendCommaSeparatedValue("Existing,", feature));
    }

    @Test
    void disablesOnlyTheWindowsArm64BrowserProcessProbe() {
        CommandLineRecorder armBrowser = new CommandLineRecorder("ExistingFeature");
        TestSetupExtension.configureCommandLine("", armBrowser.commandLine(), true, "aarch64");
        assertEquals("ExistingFeature," + TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, armBrowser.disabledFeatures());
        assertEquals(1, armBrowser.removals());
        assertEquals(1, armBrowser.appends());

        CommandLineRecorder armRenderer = new CommandLineRecorder("ExistingFeature");
        TestSetupExtension.configureCommandLine("renderer", armRenderer.commandLine(), true, "aarch64");
        assertEquals("ExistingFeature", armRenderer.disabledFeatures());

        CommandLineRecorder x64Browser = new CommandLineRecorder("ExistingFeature");
        TestSetupExtension.configureCommandLine("", x64Browser.commandLine(), true, "amd64");
        assertEquals("ExistingFeature", x64Browser.disabledFeatures());

        CommandLineRecorder linuxArmBrowser = new CommandLineRecorder("ExistingFeature");
        TestSetupExtension.configureCommandLine("", linuxArmBrowser.commandLine(), false, "aarch64");
        assertEquals("ExistingFeature", linuxArmBrowser.disabledFeatures());
    }

    @Test
    void leavesAnExistingWindowsArm64ProbeDisableUntouched() {
        CommandLineRecorder recorder = new CommandLineRecorder(TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);
        TestSetupExtension.configureCommandLine("", recorder.commandLine(), true, "arm64");
        assertEquals(TestSetupExtension.WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE, recorder.disabledFeatures());
        assertEquals(0, recorder.removals());
        assertEquals(0, recorder.appends());
    }

    private static final class CommandLineRecorder {
        private static final String DISABLE_FEATURES_SWITCH = "disable-features";
        private final Map<String, String> switches_ = new LinkedHashMap<String, String>();
        private final AtomicInteger removals_ = new AtomicInteger();
        private final AtomicInteger appends_ = new AtomicInteger();
        private final CefCommandLine commandLine_;

        private CommandLineRecorder(String disabledFeatures) {
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
