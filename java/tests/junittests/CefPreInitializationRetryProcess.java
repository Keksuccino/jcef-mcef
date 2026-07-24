// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.OS;
import org.cef.callback.CefCommandLine;
import org.cef.handler.CefAppHandlerAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separate-process fixture because one production JVM intentionally supports one CEF lifetime. */
public final class CefPreInitializationRetryProcess {
    static final String ROOT_CACHE_ARGUMENT = "--root-cache-path=";

    private CefPreInitializationRetryProcess() {}

    public static void main(String[] args) throws Exception {
        if (!CefApp.startup(null)) throw new IllegalStateException("Initial CEF startup failed");
        CefApp abandoned = CefApp.getInstance();
        abortNativeInitialization(abandoned);
        resetJavaConstructorState(abandoned);

        CountDownLatch terminated = new CountDownLatch(1);
        CefAppHandlerAdapter retryHandler = new CefAppHandlerAdapter(null) {
            @Override
            public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
                super.onBeforeCommandLineProcessing(processType, commandLine);
                WindowsArm64TestCommandLine.configureBrowserProcess(processType, commandLine);
            }

            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) terminated.countDown();
            }
        };
        CefApp.addAppHandler(retryHandler);
        assertRetryHandlerInstalled(retryHandler);
        CefSettings settings = new CefSettings();
        settings.root_cache_path = getRootCachePath(args);
        CefApp retried = CefApp.getInstance(settings);
        CefClient client = retried.createClient();
        client.dispose();
        retried.dispose();

        if (!terminated.await(30, TimeUnit.SECONDS))
            throw new IllegalStateException(
                    "Retried CEF instance did not terminate; state=" + CefApp.getState());

        // CEF initializes AWT infrastructure that can keep this dedicated fixture JVM alive after
        // all native resources are gone. The TERMINATED callback above proves shutdown completed,
        // so explicitly end the otherwise-idle process instead of relying on AWT auto-shutdown.
        System.exit(0);
    }

    private static String getRootCachePath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(ROOT_CACHE_ARGUMENT))
                return arg.substring(ROOT_CACHE_ARGUMENT.length());
        }
        throw new IllegalArgumentException("Missing " + ROOT_CACHE_ARGUMENT + "<path> argument");
    }

    private static void abortNativeInitialization(CefApp app) throws Exception {
        Method abort = CefApp.class.getDeclaredMethod("N_AbortInitialization");
        Method runOnLifecycle =
                CefApp.class.getDeclaredMethod("runOnLifecycleThread", Runnable.class);
        abort.setAccessible(true);
        runOnLifecycle.setAccessible(true);
        Runnable operation = () -> invoke(abort, app);
        invoke(runOnLifecycle, app, operation);
    }

    private static void resetJavaConstructorState(CefApp app) throws Exception {
        Object executor = getField("lifecycleExecutor_").get(app);
        if (executor != null) {
            Method close = executor.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            invoke(close, executor);
        }
        setField(app, "nativeContextActive_", Boolean.FALSE);
        setStaticField("self", null);
        setStaticField("appHandler_", null);
        setStaticField("state_", CefApp.CefAppState.NONE);
        if (OS.isMacintosh()) {
            setStaticField("startupSucceeded_", Boolean.FALSE);
            setStaticField("startupRetryRequired_", Boolean.TRUE);
        }
    }

    private static void assertRetryHandlerInstalled(CefAppHandlerAdapter retryHandler) throws Exception {
        if (getField("appHandler_").get(null) != retryHandler)
            throw new IllegalStateException("Retry command-line handler was discarded after constructor reset");
    }

    private static Field getField(String name) throws Exception {
        Field field = CefApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        getField(name).set(target, value);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        getField(name).set(null, value);
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
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
