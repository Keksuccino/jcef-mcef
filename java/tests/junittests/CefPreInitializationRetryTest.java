// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Native CEF belongs exclusively to the child process in this test. Applying NativeCefTest here
// would also initialize CEF in the JUnit process and make both processes contend for profile data.
@Tag(NativeCefTest.TAG)
class CefPreInitializationRetryTest {
    @TempDir
    Path tempDirectory_;

    @Test
    void nativePreInitializationAbortCanBeRetriedInSameProcess() throws Exception {
        Path output = tempDirectory_.resolve("retry-process.log");
        List<String> command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        copyProperty(command, "java.awt.headless");
        copyProperty(command, "jcef.path");
        copyProperty(command, "jcef.external_message_pump");
        copyProperty(command, "java.library.path");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CefPreInitializationRetryProcess.class.getName());
        Path rootCache = tempDirectory_.resolve("cef-root-cache").toAbsolutePath();
        command.add(CefPreInitializationRetryProcess.ROOT_CACHE_ARGUMENT + rootCache);
        command.add("-ApplePersistenceIgnoreState");
        command.add("YES");

        Process process = new ProcessBuilder(command)
                                  .redirectErrorStream(true)
                                  .redirectOutput(output.toFile())
                                  .start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(exited, "Retry fixture timed out; output:\n" + readOutput(output));
        assertEquals(
                0, process.exitValue(), "Retry fixture failed; output:\n" + readOutput(output));
    }

    private static void copyProperty(List<String> command, String name) {
        String value = System.getProperty(name);
        if (value != null) command.add("-D" + name + "=" + value);
    }

    private static String readOutput(Path output) throws Exception {
        return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
    }
}
