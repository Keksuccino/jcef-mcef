// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Restores executable permission bits that archive extractors may omit from CEF helpers. */
final class CefHelperExecutableSupport {
    private CefHelperExecutableSupport() {}

    static void prepare(Path configuredPath, boolean macintosh, boolean linux) {
        if (!macintosh && !linux) return;
        Path root = configuredPath.normalize().toAbsolutePath();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("Configured jcef.path could not be resolved safely: " + root, exception);
        }
        for (Path helper : helperPaths(root, macintosh, linux)) {
            prepareHelper(root, realRoot, helper);
        }
    }

    private static List<Path> helperPaths(Path root, boolean macintosh, boolean linux) {
        List<Path> helpers = new ArrayList<Path>();
        if (linux) helpers.add(root.resolve("jcef_helper"));
        if (macintosh) {
            Path frameworks = root.resolve("jcef_app.app/Contents/Frameworks");
            addMacHelper(helpers, frameworks, "jcef Helper");
            addMacHelper(helpers, frameworks, "jcef Helper (Renderer)");
            addMacHelper(helpers, frameworks, "jcef Helper (GPU)");
            addMacHelper(helpers, frameworks, "jcef Helper (Plugin)");
            addMacHelper(helpers, frameworks, "jcef Helper (Alerts)");
        }
        return helpers;
    }

    private static void addMacHelper(List<Path> helpers, Path frameworks, String name) {
        helpers.add(frameworks.resolve(name + ".app/Contents/MacOS").resolve(name));
    }

    private static void prepareHelper(Path root, Path realRoot, Path unresolvedHelper) {
        Path helper = unresolvedHelper.normalize().toAbsolutePath();
        if (!helper.startsWith(root))
            throw new IllegalStateException("CEF helper path escapes configured jcef.path: " + helper);
        if (!Files.exists(helper, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("CEF helper is not a regular non-symbolic-link file: " + helper);

        // Checking only the final path component is insufficient because a helper's
        // parent directory may itself be a symbolic link. Resolve the existing helper
        // and operate on that canonical path so permission repair cannot escape the
        // configured CEF root through an archive-created parent link.
        Path realHelper;
        try {
            realHelper = helper.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("CEF helper path could not be resolved safely: " + helper, exception);
        }
        if (!realHelper.startsWith(realRoot))
            throw new IllegalStateException("CEF helper resolves outside configured jcef.path: " + helper);
        if (!Files.isRegularFile(realHelper, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("CEF helper is not a regular non-symbolic-link file: " + helper);
        if (Files.isExecutable(realHelper)) return;

        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            permissions.addAll(Files.getPosixFilePermissions(realHelper, LinkOption.NOFOLLOW_LINKS));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(realHelper, permissions);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            if (!Files.isExecutable(realHelper))
                throw new IllegalStateException("CEF helper is not executable and its permissions could not be repaired: " + helper, exception);
        }
        if (!Files.isExecutable(realHelper))
            throw new IllegalStateException("CEF helper remains non-executable after permission repair: " + helper);
    }
}
