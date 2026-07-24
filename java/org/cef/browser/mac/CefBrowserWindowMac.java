// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser.mac;

import org.cef.browser.CefBrowserWindow;

import java.awt.Component;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class CefBrowserWindowMac implements CefBrowserWindow {
    @Override
    public long getWindowHandle(Component comp) {
        while (comp != null) {
            if (comp.isLightweight()) {
                comp = comp.getParent();
                continue;
            }

            long windowHandle = WindowHandleAccessor.getWindowHandle(comp);
            if (windowHandle != 0) return windowHandle;
            comp = comp.getParent();
        }
        return 0;
    }

    /**
     * Isolates access to the macOS AWT peer implementation. These JDK classes are not part of the
     * Java SE API, so referring to their types directly prevents javac from honoring
     * {@code --release 17}. The reflective boundary keeps the production API on Java 17 while the
     * launch scripts explicitly open only the three implementation packages used here.
     */
    private static final class WindowHandleAccessor {
        private static final Object componentAccessor;
        private static final Method getPeer;
        private static final Class<?> lwComponentPeerClass;
        private static final Method getPlatformWindow;
        private static final Class<?> cPlatformWindowClass;
        private static final Class<?> nativeActionClass;
        private static final Method execute;

        static {
            try {
                Class<?> awtAccessorClass = Class.forName("sun.awt.AWTAccessor");
                Method getComponentAccessor = awtAccessorClass.getDeclaredMethod("getComponentAccessor");
                getComponentAccessor.setAccessible(true);
                componentAccessor = getComponentAccessor.invoke(null);

                Class<?> componentAccessorClass = Class.forName("sun.awt.AWTAccessor$ComponentAccessor");
                getPeer = componentAccessorClass.getDeclaredMethod("getPeer", Component.class);
                getPeer.setAccessible(true);

                lwComponentPeerClass = Class.forName("sun.lwawt.LWComponentPeer");
                getPlatformWindow = lwComponentPeerClass.getDeclaredMethod("getPlatformWindow");
                getPlatformWindow.setAccessible(true);

                cPlatformWindowClass = Class.forName("sun.lwawt.macosx.CPlatformWindow");
                nativeActionClass = Class.forName("sun.lwawt.macosx.CFRetainedResource$CFNativeAction");
                execute = cPlatformWindowClass.getMethod("execute", nativeActionClass);
                execute.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private static long getWindowHandle(Component component) {
            try {
                Object peer = getPeer.invoke(componentAccessor, component);
                if (!lwComponentPeerClass.isInstance(peer)) return 0;

                Object platformWindow = getPlatformWindow.invoke(peer);
                if (!cPlatformWindowClass.isInstance(platformWindow)) return 0;

                long[] result = new long[1];
                Object action = Proxy.newProxyInstance(nativeActionClass.getClassLoader(), new Class<?>[] {nativeActionClass}, (proxy, method, arguments) -> {
                            if ("run".equals(method.getName()) && arguments != null
                                    && arguments.length == 1) {
                                result[0] = ((Long) arguments[0]).longValue();
                            }
                            return null;
                        });
                execute.invoke(platformWindow, action);
                return result[0];
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to obtain the macOS AWT window handle. Launch with the java.desktop sun.awt, sun.lwawt and sun.lwawt.macosx packages opened to JCEF.", exception);
            }
        }
    }
}
