// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Provides shared child-JVM launch paths and fatal-error report discovery. */
final class ChildProcessSupport {
    private static final String JVM_FATAL_ERROR_BANNER = "# A fatal error has been detected by the Java Runtime Environment:";
    private static final String JVM_FATAL_OOM_BANNER = "# There is insufficient memory for the Java Runtime Environment to continue.";
    private static final String JVM_ERROR_FILE_PREFIX = "hs_err_pid";

    private ChildProcessSupport() {}

    static String classPathFor(Class<?>... requiredClasses) {
        Set<String> entries = codeSourceEntries(requiredClasses);

        String inheritedClassPath = System.getProperty("java.class.path", "");
        for (String entry : inheritedClassPath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalStateException("No child-process class path entries were resolved");
        return String.join(File.pathSeparator, entries);
    }

    static String codeSourceClassPathFor(Class<?>... requiredClasses) {
        return String.join(File.pathSeparator, codeSourceEntries(requiredClasses));
    }

    private static Set<String> codeSourceEntries(Class<?>... requiredClasses) {
        Objects.requireNonNull(requiredClasses, "requiredClasses");
        Set<String> entries = new LinkedHashSet<String>();
        for (Class<?> requiredClass : requiredClasses)
            entries.add(codeSourcePath(Objects.requireNonNull(requiredClass, "requiredClass")).toString());
        if (entries.isEmpty()) throw new IllegalStateException("No required class code-source entries were resolved");
        return entries;
    }

    static Path codeSourcePath(Class<?> requiredClass) {
        CodeSource codeSource = requiredClass.getProtectionDomain().getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null) throw new IllegalStateException("Class has no code-source location: " + requiredClass.getName());

        try {
            URI uri = location.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalStateException("Class code source is not a file URI: " + requiredClass.getName() + " -> " + uri);
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid class code-source location for " + requiredClass.getName() + ": " + location, exception);
        }
    }

    static String jvmErrorFileArgument(Path directory) {
        // HotSpot replaces %p with its process ID. A dedicated caller-owned directory keeps the
        // subsequent prefix scan isolated from unrelated JVMs and stale reports.
        return "-XX:ErrorFile=" + directory.resolve(JVM_ERROR_FILE_PREFIX + "%p.log").toAbsolutePath();
    }

    static List<Path> findJvmCrashReports(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(JVM_ERROR_FILE_PREFIX)).sorted().toList();
        }
    }

    static boolean containsJvmFatalError(String processOutput) {
        Objects.requireNonNull(processOutput, "processOutput");
        // HotSpot prints one of these banners to the process output before opening the detailed
        // report. This remains authoritative when ErrorFile falls back to the working directory,
        // the OS temp directory, or cannot be opened anywhere.
        return processOutput.contains(JVM_FATAL_ERROR_BANNER) || processOutput.contains(JVM_FATAL_OOM_BANNER);
    }
}
