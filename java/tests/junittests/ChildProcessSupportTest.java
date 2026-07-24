// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

class ChildProcessSupportTest {
    @Test
    void requiredFixturesRemainLoadableWhenJUnitUsesItsJarLauncher() throws Exception {
        Class<?>[] requiredClasses = {CefPreInitializationRetryProcess.class, CefUnicodeFrameworkPathProcess.class, CefApp.class};
        String classPath = ChildProcessSupport.classPathFor(requiredClasses);
        String[] entries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        assertEquals(ChildProcessSupport.codeSourcePath(requiredClasses[0]).toString(), entries[0]);

        URL[] urls = Arrays.stream(entries).map(Path::of).map(Path::toUri).map(ChildProcessSupportTest::toUrl).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            for (Class<?> requiredClass : requiredClasses)
                Class.forName(requiredClass.getName(), false, loader);
        }
    }

    @Test
    void bootstrapClassesWithoutCodeSourcesAreRejected() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> ChildProcessSupport.classPathFor(String.class));
        assertEquals("Class has no code-source location: java.lang.String", exception.getMessage());
    }

    @Test
    void jvmCrashReportDiscoveryIsScopedToTheDedicatedPrefix(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path crashReport = Files.createFile(directory.resolve("hs_err_pid123.log"));
        Files.createFile(directory.resolve("process.log"));

        assertEquals("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log").toAbsolutePath(), ChildProcessSupport.jvmErrorFileArgument(directory));
        assertEquals(List.of(crashReport), ChildProcessSupport.findJvmCrashReports(directory));
    }

    @Test
    void fatalErrorDetectionCoversNativeCrashesAndFatalOutOfMemoryErrors() {
        assertFalse(ChildProcessSupport.containsJvmFatalError("ordinary test output"));
        assertTrue(ChildProcessSupport.containsJvmFatalError("prefix\n# A fatal error has been detected by the Java Runtime Environment:\nsuffix"));
        assertTrue(ChildProcessSupport.containsJvmFatalError("# There is insufficient memory for the Java Runtime Environment to continue."));
    }

    private static URL toUrl(java.net.URI uri) {
        try {
            return uri.toURL();
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalStateException("Invalid class-path URI: " + uri, exception);
        }
    }
}
