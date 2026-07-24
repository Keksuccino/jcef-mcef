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
import java.util.concurrent.TimeUnit;

class CefBrowserOsrAwtInputDependencyTest {
    @TempDir
    Path tempDirectory_;

    @Test
    void componentBridgeLoadsAndInstallsWithoutJoglOrLwjgl() throws Exception {
        Path output = tempDirectory_.resolve("awt-input-no-graphics-process.log");
        List<String> command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(ChildProcessSupport.codeSourceClassPathFor(CefBrowserOsrAwtInputNoGraphicsProcess.class, CefBrowserOsr.class));
        command.add(CefBrowserOsrAwtInputNoGraphicsProcess.class.getName());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(exited, "Graphics-independent AWT bridge fixture timed out; output:\n" + readOutput(output));
        assertEquals(0, process.exitValue(), "Graphics-independent AWT bridge fixture failed; output:\n" + readOutput(output));
    }

    private static String readOutput(Path output) throws Exception {
        return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
    }
}
