// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.browser.CefBrowserOsr;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;

/** Child-process fixture that must run without any third-party graphics bindings. */
public final class CefBrowserOsrAwtInputNoGraphicsProcess {
    private CefBrowserOsrAwtInputNoGraphicsProcess() {}

    public static void main(String[] args) throws Exception {
        assertClassAbsent("com.jogamp.opengl.awt.GLCanvas");
        assertClassAbsent("org.lwjgl.glfw.GLFW");

        ComponentBrowser browser = new ComponentBrowser();
        Component component = browser.getUIComponent();
        require(component.getMouseListeners().length == 1, "Expected one mouse listener");
        require(component.getMouseMotionListeners().length == 1, "Expected one mouse-motion listener");
        require(component.getMouseWheelListeners().length == 1, "Expected one mouse-wheel listener");
        require(component.getKeyListeners().length == 1, "Expected one key listener");
        require(component.getFocusListeners().length == 1, "Expected one focus listener");
        require(component.isFocusable(), "Input component was not made focusable");
        require(!component.getFocusTraversalKeysEnabled(), "Focus traversal keys still intercept OSR input");

        assertDirectFocusIsLifecycleGated(browser, "uncreated");
        assertRetainedFocusIsLifecycleGated(component, "uncreated");
        require(browser.getForwardedFocusCallCount() == 0, "Uncreated browser received retained-listener focus input");
        browser.onBeforeClose();
        assertDirectFocusIsLifecycleGated(browser, "closed");
        assertRetainedFocusIsLifecycleGated(component, "closed");
        require(browser.getForwardedFocusCallCount() == 0, "Closed browser received retained-listener focus input");
    }

    private static void assertDirectFocusIsLifecycleGated(ComponentBrowser browser, String state) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream previousError = System.err;
        try (PrintStream capturedError = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedError);
            browser.callBaseSetFocus(true);
            browser.callBaseSetFocus(false);
        } finally {
            System.setErr(previousError);
        }
        require(errors.size() == 0, "Direct " + state + " focus crossed JNI: " + errors.toString(StandardCharsets.UTF_8));
    }

    private static void assertRetainedFocusIsLifecycleGated(Component component, String state) {
        MenuSelectionManager menuManager = MenuSelectionManager.defaultManager();
        MenuElement[] selectedPath = createMenuSelectionPath();
        menuManager.setSelectedPath(selectedPath);
        try {
            dispatchFocus(component);
            require(Arrays.equals(selectedPath, menuManager.getSelectedPath()), "Retained " + state + " focus cleared global menu selection");
        } finally {
            menuManager.clearSelectedPath();
        }
    }

    private static MenuElement[] createMenuSelectionPath() {
        JMenu menu = new JMenu("OSR input lifecycle");
        JMenuItem item = new JMenuItem("retained selection");
        menu.add(item);
        return new MenuElement[] {menu, menu.getPopupMenu(), item};
    }

    private static void assertClassAbsent(String className) throws Exception {
        try {
            Class.forName(className, false, CefBrowserOsrAwtInputNoGraphicsProcess.class.getClassLoader());
            throw new AssertionError("Graphics binding unexpectedly present: " + className);
        } catch (ClassNotFoundException expected) {
            // This fixture deliberately provides only JCEF's compiled code-source roots.
        }
    }

    private static void dispatchFocus(Component component) {
        FocusEvent gained = new FocusEvent(component, FocusEvent.FOCUS_GAINED);
        FocusEvent lost = new FocusEvent(component, FocusEvent.FOCUS_LOST);
        for (FocusListener listener : component.getFocusListeners()) listener.focusGained(gained);
        for (FocusListener listener : component.getFocusListeners()) listener.focusLost(lost);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ComponentBrowser extends CefBrowserOsr {
        private final Component component_ = new Canvas();
        private int forwardedFocusCalls_;

        private ComponentBrowser() {
            super(null, "about:blank", false, null);
            installAwtInputListeners(component_);
        }

        @Override
        public Component getUIComponent() {
            return component_;
        }

        @Override
        public void setFocus(boolean enable) {
            forwardedFocusCalls_++;
        }

        private void callBaseSetFocus(boolean enable) {
            super.setFocus(enable);
        }

        private int getForwardedFocusCallCount() {
            return forwardedFocusCalls_;
        }
    }
}
