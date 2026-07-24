// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefCommandLine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Vector;

class CefCommandLineApiTest {
    private static final Method VALIDATE_ARGV = validationMethod();

    @Test
    void exposesTheCef151CommandLineSurface() throws Exception {
        Method create = CefCommandLine.class.getMethod("createCommandLine");
        Method global = CefCommandLine.class.getMethod("getGlobalCommandLine");

        assertTrue(Modifier.isStatic(create.getModifiers()));
        assertTrue(Modifier.isStatic(global.getModifiers()));
        assertEquals(CefCommandLine.class, create.getReturnType());
        assertEquals(CefCommandLine.class, global.getReturnType());
        assertMethod("dispose", void.class);
        assertMethod("isValid", boolean.class);
        assertMethod("isReadOnly", boolean.class);
        assertMethod("copy", CefCommandLine.class);
        assertMethod("initFromArgv", void.class, String[].class);
        assertMethod("initFromString", void.class, String.class);
        assertMethod("reset", void.class);
        assertMethod("getArgv", Vector.class);
        assertMethod("getCommandLineString", String.class);
        assertMethod("getProgram", String.class);
        assertMethod("setProgram", void.class, String.class);
        assertMethod("hasSwitches", boolean.class);
        assertMethod("hasSwitch", boolean.class, String.class);
        assertMethod("getSwitchValue", String.class, String.class);
        assertMethod("getSwitches", Map.class);
        assertMethod("appendSwitch", void.class, String.class);
        assertMethod("appendSwitchWithValue", void.class, String.class, String.class);
        assertMethod("removeSwitch", void.class, String.class);
        assertMethod("hasArguments", boolean.class);
        assertMethod("getArguments", Vector.class);
        assertMethod("appendArgument", void.class, String.class);
        assertMethod("prependWrapper", void.class, String.class);
    }

    @Test
    void validatesArgvBeforeCrossingJni() {
        assertValidationFailure(NullPointerException.class, null);
        assertValidationFailure(IllegalArgumentException.class, new String[0]);
        assertValidationFailure(IllegalArgumentException.class, new String[] {""});
        assertValidationFailure(NullPointerException.class, new String[] {"program", null});
        assertDoesNotThrow(() -> VALIDATE_ARGV.invoke(null, (Object) new String[] {"program", "--flag", "argument"}));
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        Method method = CefCommandLine.class.getMethod(name, parameterTypes);
        assertNotNull(method);
        assertEquals(returnType, method.getReturnType());
    }

    private static void assertValidationFailure(Class<? extends Throwable> expectedType, String[] argv) {
        InvocationTargetException exception = assertThrows(InvocationTargetException.class, () -> VALIDATE_ARGV.invoke(null, (Object) argv));
        assertInstanceOf(expectedType, exception.getCause());
    }

    private static Method validationMethod() {
        try {
            Method method = Class.forName("org.cef.callback.CefCommandLine_N").getDeclaredMethod("validateArgv", String[].class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
