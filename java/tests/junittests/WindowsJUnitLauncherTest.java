// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class WindowsJUnitLauncherTest {
    @Test
    void removesEveryExactChromiumProcessSwitch() {
        String processSwitch = WindowsJUnitLauncher.DISABLE_BEST_EFFORT_TASKS_SWITCH;
        String[] arguments = {processSwitch, "execute", processSwitch, "--select-class=ExampleTest"};
        assertArrayEquals(new String[] {"execute", "--select-class=ExampleTest"}, WindowsJUnitLauncher.filterJUnitArguments(arguments));
    }

    @Test
    void preservesSimilarAndUnrelatedArgumentsInOrder() {
        String[] arguments = {"execute", "--disable-best-effort-tasks=true", "--DISABLE-BEST-EFFORT-TASKS", "disable-best-effort-tasks", "--details=summary"};
        assertArrayEquals(arguments, WindowsJUnitLauncher.filterJUnitArguments(arguments));
    }

    @Test
    void acceptsAnEmptyArgumentList() {
        assertArrayEquals(new String[0], WindowsJUnitLauncher.filterJUnitArguments(new String[0]));
    }
}
