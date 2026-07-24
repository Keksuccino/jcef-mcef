// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds child-JVM class paths without relying on JUnit's launcher implementation details. */
final class ChildProcessSupport {
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
}
