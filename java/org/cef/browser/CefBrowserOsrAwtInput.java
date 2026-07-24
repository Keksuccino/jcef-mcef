// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Objects;

import javax.swing.MenuSelectionManager;

/** Installs the AWT event listeners shared by all component-backed OSR implementations. */
final class CefBrowserOsrAwtInput {
    private CefBrowserOsrAwtInput() {}

    static void install(Component component, CefBrowser_N browser) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(browser, "browser");

        MouseAdapter mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                browser.sendAwtMouseEvent(event);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                browser.sendAwtMouseWheelEvent(event);
            }
        };
        component.addMouseListener(mouseListener);
        component.addMouseMotionListener(mouseListener);
        component.addMouseWheelListener(mouseListener);
        component.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent event) {
                browser.sendAwtKeyEvent(event);
            }

            @Override
            public void keyPressed(KeyEvent event) {
                browser.sendAwtKeyEvent(event);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                browser.sendAwtKeyEvent(event);
            }
        });
        component.setFocusable(true);
        component.setFocusTraversalKeysEnabled(false);
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                browser.sendAwtFocusEvent(false);
            }

            @Override
            public void focusGained(FocusEvent event) {
                if (browser.sendAwtFocusEvent(true)) MenuSelectionManager.defaultManager().clearSelectedPath();
            }
        });
    }
}
