// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.junit.platform.console.ConsoleLauncher;
import org.junit.platform.console.command.CommandResult;

import java.awt.Toolkit;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs JUnit off the macOS AppKit first thread while that thread services the native event loop.
 *
 * <p>CEF and AWT both require the process first thread on macOS. A normal ConsoleLauncher run
 * blocks that thread in test waits, which prevents CEF initialization, browser callbacks, and
 * shutdown work from reaching AppKit. Keep this launcher specific to non-headless macOS runs with
 * {@code -XstartOnFirstThread} and the {@code sun.lwawt.macosx} package opened to this module.
 */
public final class MacJUnitLauncher {
    private static final String APPLE_PERSISTENCE_ARGUMENT = "-ApplePersistenceIgnoreState";
    private static final int FAILURE_EXIT_CODE = 1;

    private MacJUnitLauncher() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            printReflectionFailure(failure);
            System.exit(FAILURE_EXIT_CODE);
        }
    }

    private static void run(String[] args) throws ReflectiveOperationException {
        Toolkit.getDefaultToolkit();

        Class<?> toolkitClass = Class.forName("sun.lwawt.macosx.LWCToolkit");
        Method createRunLoop = accessibleMethod(toolkitClass, "createAWTRunLoopMediator");
        Method runLoop = accessibleMethod(toolkitClass, "doAWTRunLoopImpl", long.class, boolean.class, boolean.class);
        Method stopRunLoop = accessibleMethod(toolkitClass, "stopAWTRunLoop", long.class);
        long mediator = (long) createRunLoop.invoke(null);

        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicInteger exitCode = new AtomicInteger(FAILURE_EXIT_CODE);
        String[] launcherArgs = filterAppleArguments(args);
        Runnable junitTask = () -> {
            try {
                CommandResult<?> result = ConsoleLauncher.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), launcherArgs);
                exitCode.set(result.getExitCode());
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
            } finally {
                if (!stopRunLoop(stopRunLoop, mediator, stopRequested))
                    System.exit(FAILURE_EXIT_CODE);
            }
        };
        Thread junitThread = new Thread(junitTask, "JCEF-JUnit");
        junitThread.setDaemon(false);
        junitThread.start();

        try {
            // The public wrapper selects AWT's private Java run-loop mode. CEF marshals work with
            // performSelectorOnMainThread, so the launcher must explicitly pump
            // NSDefaultRunLoopMode.
            runLoop.invoke(null, mediator, true, false);
        } catch (Throwable failure) {
            printReflectionFailure(failure);
            stopRunLoop(stopRunLoop, mediator, stopRequested);
            System.exit(FAILURE_EXIT_CODE);
        }

        if (junitThread.isAlive() && !stopRequested.get()) {
            System.err.println("The AppKit run loop exited before the JUnit worker completed");
            stopRunLoop(stopRunLoop, mediator, stopRequested);
            System.exit(FAILURE_EXIT_CODE);
        }
        joinUninterruptibly(junitThread);
        System.exit(exitCode.get());
    }

    private static Method accessibleMethod(Class<?> owner, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    static String[] filterAppleArguments(String[] args) {
        List<String> filtered = new ArrayList<String>(args.length);
        for (int index = 0; index < args.length; index++) {
            if (APPLE_PERSISTENCE_ARGUMENT.equals(args[index]) && index + 1 < args.length && isBooleanAppleArgument(args[index + 1])) {
                index++;
                continue;
            }
            filtered.add(args[index]);
        }
        return filtered.toArray(new String[0]);
    }

    private static boolean isBooleanAppleArgument(String value) {
        return "YES".equalsIgnoreCase(value) || "NO".equalsIgnoreCase(value);
    }

    private static boolean stopRunLoop(Method stopRunLoop, long mediator, AtomicBoolean stopRequested) {
        if (!stopRequested.compareAndSet(false, true)) return true;
        try {
            stopRunLoop.invoke(null, mediator);
            return true;
        } catch (Throwable failure) {
            printReflectionFailure(failure);
            return false;
        }
    }

    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void printReflectionFailure(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        cause.printStackTrace(System.err);
    }
}
