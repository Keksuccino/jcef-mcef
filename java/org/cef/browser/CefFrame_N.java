// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.callback.CefNativeAdapter;
import org.cef.callback.CefStringVisitor;
import org.cef.network.CefRequest;

import java.util.Objects;

/**
 * This class represents all methods which are connected to the
 * native counterpart CEF.
 * The visibility of this class is "package".
 */
class CefFrame_N extends CefNativeAdapter implements CefFrame {
    CefFrame_N() {}

    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }

    @Override
    public void dispose() {
        try {
            N_Dispose(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public String getIdentifier() {
        try {
            return N_GetIdentifier(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public String getURL() {
        try {
            return N_GetURL(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public String getName() {
        try {
            return N_GetName(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean isMain() {
        try {
            return N_IsMain(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isValid() {
        try {
            return N_IsValid(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isFocused() {
        try {
            return N_IsFocused(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return false;
        }
    }

    @Override
    public CefFrame getParent() {
        try {
            return N_GetParent(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public CefBrowser getBrowser() {
        try {
            return N_GetBrowser(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public void viewSource() {
        try {
            N_ViewSource(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void getSource(CefStringVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        try {
            N_GetSource(getNativeRef(null), visitor);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void getText(CefStringVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        try {
            N_GetText(getNativeRef(null), visitor);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void loadRequest(CefRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            N_LoadRequest(getNativeRef(null), request);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void loadURL(String url) {
        Objects.requireNonNull(url, "url");
        try {
            N_LoadURL(getNativeRef(null), url);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void executeJavaScript(String code, String url, int line) {
        try {
            N_ExecuteJavaScript(getNativeRef(null), code, url, line);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void undo() {
        try {
            N_Undo(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void redo() {
        try {
            N_Redo(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void cut() {
        try {
            N_Cut(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void copy() {
        try {
            N_Copy(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void paste() {
        try {
            N_Paste(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void pasteAndMatchStyle() {
        try {
            N_PasteAndMatchStyle(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void delete() {
        try {
            N_Delete(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void selectAll() {
        try {
            N_SelectAll(getNativeRef(null));
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    private final native void N_Dispose(long self);
    private final native String N_GetIdentifier(long self);
    private final native String N_GetURL(long self);
    private final native String N_GetName(long self);
    private final native boolean N_IsMain(long self);
    private final native boolean N_IsValid(long self);
    private final native boolean N_IsFocused(long self);
    private final native CefFrame N_GetParent(long self);
    private final native CefBrowser N_GetBrowser(long self);
    private final native void N_ViewSource(long self);
    private final native void N_GetSource(long self, CefStringVisitor visitor);
    private final native void N_GetText(long self, CefStringVisitor visitor);
    private final native void N_LoadRequest(long self, CefRequest request);
    private final native void N_LoadURL(long self, String url);
    private final native void N_ExecuteJavaScript(long self, String code, String url, int line);
    private final native void N_Undo(long self);
    private final native void N_Redo(long self);
    private final native void N_Cut(long self);
    private final native void N_Copy(long self);
    private final native void N_Paste(long self);
    private final native void N_PasteAndMatchStyle(long self);
    private final native void N_Delete(long self);
    private final native void N_SelectAll(long self);
}
