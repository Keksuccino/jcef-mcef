// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Native CEF belongs exclusively to the child process in this test. Applying NativeCefTest here
// would load the framework in the JUnit process before the Unicode-path fixture can exercise it.
@Tag(NativeCefTest.TAG)
@EnabledOnOs(OS.MAC)
class CefUnicodeFrameworkPathTest {
    @TempDir
    Path tempDirectory_;

    @Test
    void startupLoadsFrameworkThroughSupplementaryUnicodePath() throws Exception {
        String configuredJcefPath = System.getProperty("jcef.path");
        assertNotNull(configuredJcefPath, "Native test launcher must configure jcef.path");

        Path framework = Path.of(configuredJcefPath, "jcef_app.app", "Contents", "Frameworks", "Chromium Embedded Framework.framework").toAbsolutePath();
        assertTrue(Files.isDirectory(framework), "CEF framework not found at " + framework);
        Path unicodeFramework = tempDirectory_.resolve("Chromium \uD83D\uDE80 Framework.framework");
        Files.createSymbolicLink(unicodeFramework, framework);

        try {
            Path output = tempDirectory_.resolve("unicode-framework-process.log");
            List<String> command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("--enable-native-access=ALL-UNNAMED");
            copyProperty(command, "jcef.path");
            copyProperty(command, "java.library.path");
            command.add("-cp");
            command.add(ChildProcessSupport.classPathFor(CefUnicodeFrameworkPathProcess.class, CefApp.class));
            command.add(CefUnicodeFrameworkPathProcess.class.getName());
            command.add("--framework-dir-path=" + unicodeFramework);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            assertTrue(exited, "Unicode framework fixture timed out; output:\n" + readOutput(output));
            assertEquals(0, process.exitValue(), "Unicode framework fixture failed; output:\n" + readOutput(output));
        } finally {
            Files.deleteIfExists(unicodeFramework);
        }
    }

    private static void copyProperty(List<String> command, String name) {
        String value = System.getProperty(name);
        if (value != null) command.add("-D" + name + "=" + value);
    }

    private static String readOutput(Path output) throws Exception {
        return Files.exists(output) ? new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
                                    : "";
    }
}
