// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

class CefHelperExecutableSupportTest {
    private static final Method PREPARE = getPrepareMethod();

    @TempDir
    Path tempDirectory_;

    @BeforeEach
    void requirePosixFilePermissions() throws Exception {
        assumeTrue(Files.getFileStore(tempDirectory_)
                           .supportsFileAttributeView(PosixFileAttributeView.class));
    }

    @Test
    void repairsOnlyKnownMacAndLinuxHelperExecutables() throws Exception {
        Path linuxHelper = createFile(tempDirectory_.resolve("jcef_helper"));
        Path alertsHelper = createFile(tempDirectory_.resolve(
                "jcef_app.app/Contents/Frameworks/jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)"));
        Path unknownHelper =
                createFile(tempDirectory_.resolve("jcef_app.app/Contents/Frameworks/unknown"));
        removeExecutePermissions(linuxHelper);
        removeExecutePermissions(alertsHelper);
        removeExecutePermissions(unknownHelper);

        prepare(tempDirectory_, true, true);

        assertTrue(Files.isExecutable(linuxHelper));
        assertTrue(Files.isExecutable(alertsHelper));
        assertFalse(Files.isExecutable(unknownHelper));
    }

    @Test
    void rejectsKnownHelperSymbolicLinksWithoutFollowingThem() throws Exception {
        Path outside = createFile(tempDirectory_.resolve("outside-helper"));
        Path helper = tempDirectory_.resolve("jcef_helper");
        Files.createSymbolicLink(helper, outside);

        assertThrows(IllegalStateException.class, () -> prepare(tempDirectory_, false, true));
    }

    @Test
    void rejectsHelpersReachedThroughParentSymbolicLinksOutsideTheConfiguredRoot()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory_.resolve("cef-root"));
        Path outsideFrameworks =
                Files.createDirectory(tempDirectory_.resolve("outside-frameworks"));
        Path alertsHelper = createFile(outsideFrameworks.resolve(
                "jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)"));
        removeExecutePermissions(alertsHelper);
        Path contents = Files.createDirectories(root.resolve("jcef_app.app/Contents"));
        Files.createSymbolicLink(contents.resolve("Frameworks"), outsideFrameworks);

        assertThrows(IllegalStateException.class, () -> prepare(root, true, false));
        assertFalse(Files.isExecutable(alertsHelper));
    }

    @Test
    void ignoresHelperLayoutsForOtherPlatforms() throws Exception {
        Path helper = createFile(tempDirectory_.resolve("jcef_helper"));
        removeExecutePermissions(helper);

        prepare(tempDirectory_, false, false);

        assertFalse(Files.isExecutable(helper));
    }

    private static Path createFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.createFile(path);
    }

    private static void removeExecutePermissions(Path path) throws Exception {
        Files.setPosixFilePermissions(
                path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static Method getPrepareMethod() {
        try {
            Class<?> support = Class.forName("org.cef.CefHelperExecutableSupport");
            Method method =
                    support.getDeclaredMethod("prepare", Path.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void prepare(Path path, boolean macintosh, boolean linux) {
        try {
            PREPARE.invoke(null, path, Boolean.valueOf(macintosh), Boolean.valueOf(linux));
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }
}
