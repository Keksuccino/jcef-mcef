// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.junit.platform.console.ConsoleLauncher;
import org.junit.platform.console.command.CommandResult;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes Chromium-only process switches before forwarding arguments to JUnit.
 *
 * <p>Chromium snapshots {@code --disable-best-effort-tasks} when it constructs the browser
 * ThreadPool, before CEF invokes {@code onBeforeCommandLineProcessing}. The Windows ARM64 test
 * runner must therefore put the switch directly on the {@code java.exe} command line. This class
 * keeps that required process token from being rejected as an unknown JUnit option.
 */
public final class WindowsJUnitLauncher {
    static final String DISABLE_BEST_EFFORT_TASKS_SWITCH = WindowsArm64TestCommandLine.DISABLE_BEST_EFFORT_TASKS_SWITCH;

    private WindowsJUnitLauncher() {}

    public static void main(String[] args) {
        CommandResult<?> result = ConsoleLauncher.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), filterJUnitArguments(args));
        TestProcessExitCoordinator.finish(result.getExitCode());
    }

    static String[] filterJUnitArguments(String[] args) {
        List<String> filteredArguments = new ArrayList<String>(args.length);
        for (String argument : args) {
            if (!DISABLE_BEST_EFFORT_TASKS_SWITCH.equals(argument)) filteredArguments.add(argument);
        }
        return filteredArguments.toArray(new String[0]);
    }
}
