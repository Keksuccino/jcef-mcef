// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class CefBrowserUncreatedCloseTest {
    @TempDir
    Path tempDirectory_;

    @Test
    void forceCloseOfUncreatedBrowserDoesNotResolveNativeCodeOrUseFinalization() throws Exception {
        Path standardOutput = tempDirectory_.resolve("stdout.log");
        Path standardError = tempDirectory_.resolve("stderr.log");
        Path nativeLibraryDirectory = Files.createDirectory(tempDirectory_.resolve("native-library-path"));
        List<String> command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.awt.headless=true");
        command.add("-Djava.library.path=" + nativeLibraryDirectory.toAbsolutePath());
        command.add(ChildProcessSupport.jvmErrorFileArgument(tempDirectory_));
        command.add("-cp");
        command.add(ChildProcessSupport.codeSourceClassPathFor(CefBrowserUncreatedCloseProcess.class, CefBrowserOsr.class));
        command.add(CefBrowserUncreatedCloseProcess.class.getName());

        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(tempDirectory_.toFile()).redirectOutput(standardOutput.toFile()).redirectError(standardError.toFile());
        sanitizeChildEnvironment(processBuilder.environment());
        Process process = processBuilder.start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }

        String output = readOutput(standardOutput);
        String error = readOutput(standardError);
        assertTrue(exited, "Uncreated-browser close fixture timed out; stdout:\n" + output + "\nstderr:\n" + error);
        assertEquals(0, process.exitValue(), "Uncreated-browser close fixture failed; stdout:\n" + output + "\nstderr:\n" + error);
        assertEquals("", error, "Uncreated-browser close crossed JNI or emitted diagnostics");
        assertEquals("", output, "Uncreated-browser close fixture emitted unexpected output");
    }

    private static void sanitizeChildEnvironment(Map<String, String> environment) {
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        // Loader overrides could inject libjcef, while loader diagnostics would violate the
        // required empty stderr even when close correctly stays in Java.
        environment.keySet().removeIf(name -> name.startsWith("DYLD_") || name.startsWith("LD_"));
    }

    private static String readOutput(Path output) throws Exception {
        return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
    }
}
