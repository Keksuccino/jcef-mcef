// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.OS;
import org.cef.callback.CefCommandLine;

import java.util.List;
import java.util.Locale;

/** Keeps Windows ARM64 CI-only Chromium startup mitigations consistent across test JVMs. */
final class WindowsArm64TestCommandLine {
    static final String DISABLE_BEST_EFFORT_TASKS_SWITCH = "--disable-best-effort-tasks";
    static final String WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE = "WebAuthenticationUseInsecureSoftwareUnexportableKeys";
    static final String WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE = "ReportKeyCredentialManagerSupportWin";
    private static final String ENABLE_FEATURES_SWITCH = "enable-features";
    private static final String DISABLE_FEATURES_SWITCH = "disable-features";

    private WindowsArm64TestCommandLine() {}

    static void appendEarlyProcessSwitch(List<String> command) {
        appendEarlyProcessSwitch(command, OS.isWindows(), System.getProperty("os.arch", ""));
    }

    static void appendEarlyProcessSwitch(List<String> command, boolean windows, String architecture) {
        if (!usesMitigations(windows, architecture) || command.contains(DISABLE_BEST_EFFORT_TASKS_SWITCH)) return;

        // This must be called only after ProcessBuilder's Java main-class token has been added.
        // The Java launcher then treats the switch as an application argument, while Chromium's
        // GetCommandLineW parsing still sees it early enough for browser ThreadPool construction.
        command.add(DISABLE_BEST_EFFORT_TASKS_SWITCH);
    }

    static void configureBrowserProcess(String processType, CefCommandLine commandLine) {
        configureBrowserProcess(processType, commandLine, OS.isWindows(), System.getProperty("os.arch", ""));
    }

    static void configureBrowserProcess(String processType, CefCommandLine commandLine, boolean windows, String architecture) {
        if (!processType.isEmpty() || !usesMitigations(windows, architecture)) return;

        // Chromium 151 eagerly initializes PasskeyUnlockManager during browser startup. Enabling
        // software unexportable keys makes EnclaveManager post its support result asynchronously
        // without entering Windows KeyCredentialManager.IsSupportedAsync, whose ngcksp.dll path
        // can terminate native Windows ARM64 CI startup with NTE_BAD_KEYSET (0x80090016). This
        // deliberately changes WebAuthn unexportable-key capability semantics only inside a
        // Windows ARM64 JUnit browser process.
        appendCommaSeparatedSwitchValue(commandLine, ENABLE_FEATURES_SWITCH, WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE);

        // Keep the independent metrics-only WinRT probe disabled as well. Production command
        // lines are unaffected because this helper is compiled and called only by JUnit fixtures.
        appendCommaSeparatedSwitchValue(commandLine, DISABLE_FEATURES_SWITCH, WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);
    }

    static boolean usesMitigations(boolean windows, String architecture) {
        if (!windows || architecture == null) return false;
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

    private static void appendCommaSeparatedSwitchValue(CefCommandLine commandLine, String switchName, String requiredValue) {
        String values = commandLine.getSwitchValue(switchName);
        String updatedValues = appendCommaSeparatedValue(values, requiredValue);
        if (updatedValues.equals(values)) return;
        commandLine.removeSwitch(switchName);
        commandLine.appendSwitchWithValue(switchName, updatedValues);
    }
}
