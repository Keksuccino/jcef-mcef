// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class MacJUnitLauncherTest {
    @Test
    void removesOnlyRecognizedPersistencePairs() {
        String[] arguments = {"-ApplePersistenceIgnoreState", "YES", "execute",
                "-ApplePersistenceIgnoreState", "no", "--select-class=ExampleTest"};
        assertArrayEquals(new String[] {"execute", "--select-class=ExampleTest"}, MacJUnitLauncher.filterAppleArguments(arguments));
    }

    @Test
    void preservesMalformedPersistenceArguments() {
        String[] arguments = {
                "execute", "-ApplePersistenceIgnoreState", "--select-class=ExampleTest"};
        assertArrayEquals(arguments, MacJUnitLauncher.filterAppleArguments(arguments));
    }

    @Test
    void preservesUnrelatedArgumentsInOrder() {
        String[] arguments = {"execute", "-AppleDockName", "JCEF", "--details=summary"};
        assertArrayEquals(arguments, MacJUnitLauncher.filterAppleArguments(arguments));
    }
}
