// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.CefSettings.LogSeverity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@NativeCefTest
class CefSettingsNativeTest {
    private static final Method CONVERT_LOG_SEVERITY = getConversionMethod();

    @Test
    void convertsEveryLogSeverityIncludingFatal() {
        assertSeverity(LogSeverity.LOGSEVERITY_DEFAULT, 0);
        assertSeverity(LogSeverity.LOGSEVERITY_VERBOSE, 1);
        assertSeverity(LogSeverity.LOGSEVERITY_INFO, 2);
        assertSeverity(LogSeverity.LOGSEVERITY_WARNING, 3);
        assertSeverity(LogSeverity.LOGSEVERITY_ERROR, 4);
        assertSeverity(LogSeverity.LOGSEVERITY_FATAL, 5);
        assertSeverity(LogSeverity.LOGSEVERITY_DISABLE, 99);
    }

    private static void assertSeverity(LogSeverity severity, int expected) {
        CefSettings settings = new CefSettings();
        settings.log_severity = severity;
        assertEquals(expected, convert(settings));
    }

    private static int convert(CefSettings settings) {
        try {
            return ((Integer) CONVERT_LOG_SEVERITY.invoke(null, settings)).intValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method getConversionMethod() {
        try {
            Method method =
                    CefApp.class.getDeclaredMethod("N_GetLogSeverityForTesting", CefSettings.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
