// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import org.cef.browser.*;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefDragData;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.callback.CefPrintDialogCallback;
import org.cef.callback.CefPrintJobCallback;
import org.cef.handler.*;
import org.cef.misc.*;
import org.cef.network.CefRequest;
import org.cef.network.CefRequest.Transition;
import org.cef.network.CefRequest.TransitionType;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FocusTraversalPolicy;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Client that owns a browser and renderer.
 */
public class CefClient extends CefClientHandler
        implements CefContextMenuHandler, CefDialogHandler, CefDisplayHandler, CefDownloadHandler,
                   CefDragHandler, CefFindHandler, CefFocusHandler, CefJSDialogHandler, CefKeyboardHandler,
                   CefLifeSpanHandler, CefLoadHandler, CefPrintHandler, CefRenderHandler,
                   CefRequestHandler, CefWindowHandler, CefAudioHandler {
    private final HashMap<Integer, CefBrowser> browser_ = new HashMap<Integer, CefBrowser>();
    private final Set<CefBrowser> pendingBrowserCreations_ = new HashSet<CefBrowser>();
    private final CefApp app_;
    private CefContextMenuHandler contextMenuHandler_ = null;
    private CefDialogHandler dialogHandler_ = null;
    private CefDisplayHandler displayHandler_ = null;
    private CefAudioHandler audioHandler_ = null;
    private CefDownloadHandler downloadHandler_ = null;
    private CefDragHandler dragHandler_ = null;
    // Find callbacks run on CEF UI while application threads may replace the delegate. The atomic
    // first-writer-wins update matches the existing add-handler contract with explicit visibility.
    private final AtomicReference<CefFindHandler> findHandler_ = new AtomicReference<CefFindHandler>();
    private CefFocusHandler focusHandler_ = null;
    private CefJSDialogHandler jsDialogHandler_ = null;
    private CefKeyboardHandler keyboardHandler_ = null;
    private CefLifeSpanHandler lifeSpanHandler_ = null;
    private CefLoadHandler loadHandler_ = null;
    private CefPrintHandler printHandler_ = null;
    private CefRequestHandler requestHandler_ = null;
    private volatile boolean isDisposed_ = false;
    private boolean terminalCleanupStarted_ = false;
    private volatile CefBrowser focusedBrowser_ = null;
    private boolean focusListenerRegistered_ = false;
    private final Object focusListenerLock_ = new Object();
    private final PropertyChangeListener propertyChangeListener_ = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent event) {
            CefBrowser focusedBrowser = focusedBrowser_;
            if (focusedBrowser == null) return;

            Component browserUI = focusedBrowser.getUIComponent();
            if (browserUI != null && isPartOf(event.getOldValue(), browserUI)) {
                focusedBrowser.setFocus(false);
                if (focusedBrowser_ == focusedBrowser) focusedBrowser_ = null;
            }
        }
    };

    /**
     * The CTOR is only accessible within this package.
     * Use CefApp.createClient() to create an instance of
     * this class.
     * @see org.cef.CefApp#createClient()
     */
    CefClient(CefApp app) throws UnsatisfiedLinkError {
        super();
        app_ = app;
    }

    @Override
    public void dispose() {
        Set<CefBrowser> browsersToClose;
        boolean runTerminalCleanup;
        synchronized (browser_) {
            if (isDisposed_) return;
            isDisposed_ = true;
            browsersToClose = new HashSet<CefBrowser>(browser_.values());
            browsersToClose.addAll(pendingBrowserCreations_);
            runTerminalCleanup = claimTerminalCleanupLocked();
        }

        // Browser close may synchronously re-enter onBeforeClose(). Keep both the live map and its
        // iterator out of that callback stack.
        Throwable failure = null;
        for (CefBrowser browser : browsersToClose) failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> browser.close(true));
        if (runTerminalCleanup) failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::runTerminalCleanup);
        CefLifecycleExecutor.rethrowFailure(failure);
    }

    // CefClientHandler

    public CefBrowser createBrowser(String url, boolean isTransparent) {
        return createBrowser(url, isTransparent, null);
    }

    public CefBrowser createBrowser(String url, boolean isTransparent, CefRequestContext context) {
        synchronized (browser_) {
            if (isDisposed_)
                throw new IllegalStateException("Can't create browser. CefClient is disposed");
            return CefBrowserFactory.create(this, url, isTransparent, context);
        }
    }

    public CefBrowser createBrowser(String url, boolean isOffscreenRendered, boolean isTransparent) {
        return createBrowser(url, isOffscreenRendered, isTransparent, null, null);
    }

    public CefBrowser createBrowser(String url, boolean isOffscreenRendered, boolean isTransparent, CefRequestContext context) {
        return createBrowser(url, isOffscreenRendered, isTransparent, context, null);
    }

    public CefBrowser createBrowser(String url, boolean isOffscreenRendered, boolean isTransparent, CefRequestContext context, CefBrowserSettings settings) {
        synchronized (browser_) {
            if (isDisposed_)
                throw new IllegalStateException("Can't create browser. CefClient is disposed");
            return CefBrowserFactory.create(this, url, isOffscreenRendered, isTransparent, context, settings);
        }
    }

    /** Internal browser-bridge hook that atomically rejects native creation after disposal. */
    public final boolean onBrowserCreationStarted(CefBrowser browser) {
        synchronized (browser_) {
            if (isDisposed_) return false;
            pendingBrowserCreations_.add(browser);
            return true;
        }
    }

    /**
     * Internal browser-bridge hook for a native creation request that did not reach
     * OnAfterCreated.
     */
    public final void onBrowserCreationFailed(CefBrowser browser) {
        boolean runTerminalCleanup;
        synchronized (browser_) {
            pendingBrowserCreations_.remove(browser);
            runTerminalCleanup = claimTerminalCleanupLocked();
        }
        if (runTerminalCleanup) runTerminalCleanup();
    }

    @Override
    protected CefBrowser getBrowser(int identifier) {
        synchronized (browser_) {
            return browser_.get(Integer.valueOf(identifier));
        }
    }

    private boolean isPartOf(Object object, Component browserUI) {
        if (browserUI == null) return false;
        if (object == browserUI) return true;
        if (object instanceof Container) {
            for (Component child : ((Container) object).getComponents()) {
                if (isPartOf(child, browserUI)) return true;
            }
        }
        return false;
    }

    private void registerFocusListenerIfNeeded(CefBrowser browser) {
        if (browser.getUIComponent() == null) return;
        synchronized (focusListenerLock_) {
            if (focusListenerRegistered_) return;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(propertyChangeListener_);
            focusListenerRegistered_ = true;
        }
    }

    private void unregisterFocusListener() {
        synchronized (focusListenerLock_) {
            if (!focusListenerRegistered_) return;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(propertyChangeListener_);
            focusListenerRegistered_ = false;
            focusedBrowser_ = null;
        }
    }

    @Override
    protected Object[] getAllBrowser() {
        synchronized (browser_) {
            return browser_.values().toArray();
        }
    }

    @Override
    protected CefContextMenuHandler getContextMenuHandler() {
        return this;
    }

    @Override
    protected CefDialogHandler getDialogHandler() {
        return this;
    };

    @Override
    protected CefDisplayHandler getDisplayHandler() {
        return this;
    }

    @Override
    protected CefAudioHandler getAudioHandler() {
        return this;
    }

    @Override
    protected CefDownloadHandler getDownloadHandler() {
        return this;
    }

    @Override
    protected CefDragHandler getDragHandler() {
        return this;
    }

    @Override
    protected CefFindHandler getFindHandler() {
        // Keep a stable Java relay installed even when no application delegate is registered. CEF
        // may retain native handlers, so detaching this relay during a public remove would prevent
        // a later add from receiving callbacks for an existing browser.
        return this;
    }

    @Override
    protected CefFocusHandler getFocusHandler() {
        return this;
    }

    @Override
    protected CefJSDialogHandler getJSDialogHandler() {
        return this;
    }

    @Override
    protected CefKeyboardHandler getKeyboardHandler() {
        return this;
    }

    @Override
    protected CefLifeSpanHandler getLifeSpanHandler() {
        return this;
    }

    @Override
    protected CefLoadHandler getLoadHandler() {
        return this;
    }

    @Override
    protected CefPrintHandler getPrintHandler() {
        return this;
    }

    @Override
    protected CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefRequestHandler getRequestHandler() {
        return this;
    }

    @Override
    protected CefWindowHandler getWindowHandler() {
        return this;
    }

    // CefContextMenuHandler

    public CefClient addContextMenuHandler(CefContextMenuHandler handler) {
        if (contextMenuHandler_ == null) contextMenuHandler_ = handler;
        return this;
    }

    public void removeContextMenuHandler() {
        contextMenuHandler_ = null;
    }

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
        if (contextMenuHandler_ != null && browser != null)
            contextMenuHandler_.onBeforeContextMenu(browser, frame, params, model);
    }

    @Override
    public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame,
            CefContextMenuParams params, int commandId, int eventFlags) {
        if (contextMenuHandler_ != null && browser != null)
            return contextMenuHandler_.onContextMenuCommand(browser, frame, params, commandId, eventFlags);
        return false;
    }

    @Override
    public void onContextMenuDismissed(CefBrowser browser, CefFrame frame) {
        if (contextMenuHandler_ != null && browser != null)
            contextMenuHandler_.onContextMenuDismissed(browser, frame);
    }

    // CefDialogHandler

    public CefClient addDialogHandler(CefDialogHandler handler) {
        if (dialogHandler_ == null) dialogHandler_ = handler;
        return this;
    }

    public void removeDialogHandler() {
        dialogHandler_ = null;
    }

    @Override
    public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
            String defaultFilePath, Vector<String> acceptFilters, Vector<String> acceptExtensions,
            Vector<String> acceptDescriptions, CefFileDialogCallback callback) {
        if (dialogHandler_ != null && browser != null) {
            return dialogHandler_.onFileDialog(browser, mode, title, defaultFilePath, acceptFilters,
                    acceptExtensions, acceptDescriptions, callback);
        }
        return false;
    }

    // CefDisplayHandler

    public CefClient addDisplayHandler(CefDisplayHandler handler) {
        if (displayHandler_ == null) displayHandler_ = handler;
        return this;
    }

    public void removeDisplayHandler() {
        displayHandler_ = null;
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onAddressChange(browser, frame, url);
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onTitleChange(browser, title);
    }

    @Override
    public void onFaviconURLChange(CefBrowser browser, List<String> iconUrls) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onFaviconURLChange(browser, iconUrls);
    }

    @Override
    public void onFullscreenModeChange(CefBrowser browser, boolean fullscreen) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onFullscreenModeChange(browser, fullscreen);
    }

    @Override
    public boolean onTooltip(CefBrowser browser, String text) {
        if (displayHandler_ != null && browser != null) {
            return displayHandler_.onTooltip(browser, text);
        }
        return false;
    }

    @Override
    public void onStatusMessage(CefBrowser browser, String value) {
        if (displayHandler_ != null && browser != null) {
            displayHandler_.onStatusMessage(browser, value);
        }
    }

    @Override
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
            String message, String source, int line) {
        if (displayHandler_ != null && browser != null) {
            return displayHandler_.onConsoleMessage(browser, level, message, source, line);
        }
        return false;
    }

    @Override
    public void onLoadingProgressChange(CefBrowser browser, double progress) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onLoadingProgressChange(browser, progress);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return dispatchCursorChange(browser, cursorType, null);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType, CefCursorInfo customCursorInfo) {
        return dispatchCursorChange(browser, cursorType, customCursorInfo);
    }

    private boolean dispatchCursorChange(CefBrowser browser, int cursorType, CefCursorInfo customCursorInfo) {
        if (browser == null) {
            return false;
        }

        if (displayHandler_ != null && displayHandler_.onCursorChange(browser, cursorType, customCursorInfo)) {
            return true;
        }

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) {
            return realHandler.onCursorChange(browser, cursorType, customCursorInfo);
        }

        return false;
    }

    // CefDownloadHandler

    public CefClient addDownloadHandler(CefDownloadHandler handler) {
        if (downloadHandler_ == null) downloadHandler_ = handler;
        return this;
    }

    public void removeDownloadHandler() {
        downloadHandler_ = null;
    }

    @Override
    @Deprecated
    public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem,
            String suggestedName, CefBeforeDownloadCallback callback) {
        if (downloadHandler_ != null && browser != null)
            downloadHandler_.onBeforeDownload(browser, downloadItem, suggestedName, callback);
    }

    @Override
    public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem,
            String suggestedName, CefBeforeDownloadCallback callback) {
        if (downloadHandler_ != null && browser != null)
            return downloadHandler_.onBeforeDownloadWithDecision(browser, downloadItem, suggestedName, callback);
        return false;
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
        if (downloadHandler_ != null && browser != null)
            downloadHandler_.onDownloadUpdated(browser, downloadItem, callback);
    }

    // CefDragHandler

    public CefClient addDragHandler(CefDragHandler handler) {
        if (dragHandler_ == null) dragHandler_ = handler;
        return this;
    }

    public void removeDragHandler() {
        dragHandler_ = null;
    }

    @Override
    public boolean onDragEnter(CefBrowser browser, CefDragData dragData, int mask) {
        if (dragHandler_ != null && browser != null)
            return dragHandler_.onDragEnter(browser, dragData, mask);
        return false;
    }

    // CefFindHandler

    public CefClient addFindHandler(CefFindHandler handler) {
        if (handler != null) findHandler_.compareAndSet(null, handler);
        return this;
    }

    public void removeFindHandler() {
        findHandler_.set(null);
    }

    @Override
    public void onFindResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {
        if (browser == null) return;
        CefFindHandler handler = findHandler_.get();
        if (handler != null) handler.onFindResult(browser, identifier, count, selectionRect, activeMatchOrdinal, finalUpdate);
    }

    // CefFocusHandler

    public CefClient addFocusHandler(CefFocusHandler handler) {
        if (focusHandler_ == null) focusHandler_ = handler;
        return this;
    }

    public void removeFocusHandler() {
        focusHandler_ = null;
    }

    @Override
    public void onTakeFocus(CefBrowser browser, boolean next) {
        if (browser == null) return;

        Component browserUI = browser.getUIComponent();
        if (browserUI != null) {
            browser.setFocus(false);
            Container parent = browserUI.getParent();
            if (parent != null) {
                FocusTraversalPolicy policy = null;
                while (parent != null) {
                    policy = parent.getFocusTraversalPolicy();
                    if (policy != null) break;
                    parent = parent.getParent();
                }
                if (policy != null) {
                    Component nextComponent = next ? policy.getComponentAfter(parent, browserUI)
                                                   : policy.getComponentBefore(parent, browserUI);
                    if (nextComponent == null) nextComponent = policy.getDefaultComponent(parent);
                    if (nextComponent != null) nextComponent.requestFocus();
                }
            }
            if (focusedBrowser_ == browser) focusedBrowser_ = null;
        }
        if (focusHandler_ != null) focusHandler_.onTakeFocus(browser, next);
    }

    @Override
    public boolean onSetFocus(final CefBrowser browser, FocusSource source) {
        if (browser == null) return false;

        boolean alreadyHandled = false;
        if (focusHandler_ != null) alreadyHandled = focusHandler_.onSetFocus(browser, source);
        return alreadyHandled;
    }

    @Override
    public void onGotFocus(CefBrowser browser) {
        if (browser == null) return;

        if (browser.getUIComponent() != null) {
            focusedBrowser_ = browser;
        }
        // This is a notification that CEF already received focus. Calling setFocus(true) here
        // feeds the notification back into CefBrowserHost and recursively emits onGotFocus until
        // the AppKit thread exhausts its stack. Genuine AWT focus gains are forwarded by the
        // browser component's FocusListener instead.
        if (focusHandler_ != null) focusHandler_.onGotFocus(browser);
    }

    // CefJSDialogHandler

    public CefClient addJSDialogHandler(CefJSDialogHandler handler) {
        if (jsDialogHandler_ == null) jsDialogHandler_ = handler;
        return this;
    }

    public void removeJSDialogHandler() {
        jsDialogHandler_ = null;
    }

    @Override
    public boolean onJSDialog(CefBrowser browser, String origin_url, JSDialogType dialog_type,
            String message_text, String default_prompt_text, CefJSDialogCallback callback,
            BoolRef suppress_message) {
        if (jsDialogHandler_ != null && browser != null)
            return jsDialogHandler_.onJSDialog(browser, origin_url, dialog_type, message_text,
                    default_prompt_text, callback, suppress_message);
        return false;
    }

    @Override
    public boolean onBeforeUnloadDialog(CefBrowser browser, String message_text, boolean is_reload,
            CefJSDialogCallback callback) {
        if (jsDialogHandler_ != null && browser != null)
            return jsDialogHandler_.onBeforeUnloadDialog(browser, message_text, is_reload, callback);
        return false;
    }

    @Override
    public void onResetDialogState(CefBrowser browser) {
        if (jsDialogHandler_ != null && browser != null)
            jsDialogHandler_.onResetDialogState(browser);
    }

    @Override
    public void onDialogClosed(CefBrowser browser) {
        if (jsDialogHandler_ != null && browser != null) jsDialogHandler_.onDialogClosed(browser);
    }

    // CefKeyboardHandler

    public CefClient addKeyboardHandler(CefKeyboardHandler handler) {
        if (keyboardHandler_ == null) keyboardHandler_ = handler;
        return this;
    }

    public void removeKeyboardHandler() {
        keyboardHandler_ = null;
    }

    @Override
    public boolean onPreKeyEvent(CefBrowser browser, CefKeyEvent event, BoolRef is_keyboard_shortcut) {
        if (keyboardHandler_ != null && browser != null)
            return keyboardHandler_.onPreKeyEvent(browser, event, is_keyboard_shortcut);
        return false;
    }

    @Override
    public boolean onKeyEvent(CefBrowser browser, CefKeyEvent event) {
        if (keyboardHandler_ != null && browser != null)
            return keyboardHandler_.onKeyEvent(browser, event);
        return false;
    }

    // CefLifeSpanHandler

    public CefClient addLifeSpanHandler(CefLifeSpanHandler handler) {
        if (lifeSpanHandler_ == null) lifeSpanHandler_ = handler;
        return this;
    }

    public void removeLifeSpanHandler() {
        lifeSpanHandler_ = null;
    }

    @Override
    public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String target_url, String target_frame_name) {
        if (isDisposed_) return true;
        if (lifeSpanHandler_ != null && browser != null)
            return lifeSpanHandler_.onBeforePopup(browser, frame, target_url, target_frame_name);
        return false;
    }

    @Override
    public void onAfterCreated(CefBrowser browser) {
        if (browser == null) return;

        Integer identifier = browser.getIdentifier();
        boolean closeImmediately;
        synchronized (browser_) {
            pendingBrowserCreations_.remove(browser);
            browser_.put(identifier, browser);
            closeImmediately = isDisposed_;
        }
        if (!closeImmediately) registerFocusListenerIfNeeded(browser);
        if (closeImmediately) {
            try {
                if (lifeSpanHandler_ != null) lifeSpanHandler_.onAfterCreated(browser);
            } finally {
                // A disposed client must never retain a browser created by a request that was
                // already in flight, even if application callback code throws.
                browser.close(true);
            }
        } else if (lifeSpanHandler_ != null) {
            lifeSpanHandler_.onAfterCreated(browser);
        }
    }

    @Override
    public void onAfterParentChanged(CefBrowser browser) {
        if (browser == null) return;
        if (lifeSpanHandler_ != null) lifeSpanHandler_.onAfterParentChanged(browser);
    }

    @Override
    public boolean doClose(CefBrowser browser) {
        if (browser == null) return false;
        if (lifeSpanHandler_ != null) return lifeSpanHandler_.doClose(browser);
        return browser.doClose();
    }

    @Override
    public void onBeforeClose(CefBrowser browser) {
        if (browser == null) return;
        int identifier = browser.getIdentifier();
        try {
            if (lifeSpanHandler_ != null) lifeSpanHandler_.onBeforeClose(browser);
        } finally {
            try {
                browser.onBeforeClose();
            } finally {
                // Native JNI callback dispatch clears application exceptions. Removing the map
                // entry in a finally block guarantees that those exceptions cannot strand the
                // client in SHUTTING_DOWN forever.
                cleanupBrowser(identifier);
            }
        }
    }

    private void cleanupBrowser(int identifier) {
        boolean runTerminalCleanup;
        synchronized (browser_) {
            if (identifier >= 0) browser_.remove(identifier);
            runTerminalCleanup = claimTerminalCleanupLocked();
        }
        if (runTerminalCleanup) runTerminalCleanup();
    }

    private boolean claimTerminalCleanupLocked() {
        if (!isDisposed_ || !browser_.isEmpty() || !pendingBrowserCreations_.isEmpty()
                || terminalCleanupStarted_)
            return false;
        terminalCleanupStarted_ = true;
        return true;
    }

    private void runTerminalCleanup() {
        Throwable failure = null;
        try {
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::unregisterFocusListener);
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeContextMenuHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeDialogHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeDisplayHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeAudioHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeDownloadHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeDragHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeFindHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeFocusHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeJSDialogHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeKeyboardHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeLifeSpanHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeLoadHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removePrintHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeRenderHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeRequestHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> removeWindowHandler(this));
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::disposeNativeClient);
        } finally {
            // CefApp owns the global shutdown decision. Always release this client from that
            // accounting even if a native handler-removal or client-disposal call fails.
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, () -> app_.clientWasDisposed(this));
        }
        CefLifecycleExecutor.rethrowFailure(failure);
    }

    private void disposeNativeClient() {
        super.dispose();
    }

    // CefLoadHandler

    public CefClient addLoadHandler(CefLoadHandler handler) {
        if (loadHandler_ == null) loadHandler_ = handler;
        return this;
    }

    public void removeLoadHandler() {
        loadHandler_ = null;
    }

    @Override
    public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, TransitionType transitionType) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadStart(browser, frame, transitionType);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, Transition transition) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadStart(browser, frame, transition);
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadEnd(browser, frame, httpStatusCode);
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
            String errorText, String failedUrl) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadError(browser, frame, errorCode, errorText, failedUrl);
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, int errorCode, String errorText, String failedUrl) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadError(browser, frame, errorCode, errorText, failedUrl);
    }

    // CefPrintHandler

    public CefClient addPrintHandler(CefPrintHandler handler) {
        if (printHandler_ == null) printHandler_ = handler;
        return this;
    }

    public void removePrintHandler() {
        printHandler_ = null;
    }

    @Override
    public void onPrintStart(CefBrowser browser) {
        if (printHandler_ != null && browser != null) printHandler_.onPrintStart(browser);
    }

    @Override
    public void onPrintSettings(CefBrowser browser, CefPrintSettings settings, boolean getDefaults) {
        if (printHandler_ != null && browser != null)
            printHandler_.onPrintSettings(browser, settings, getDefaults);
    }

    @Override
    public boolean onPrintDialog(CefBrowser browser, boolean hasSelection, CefPrintDialogCallback callback) {
        if (printHandler_ != null && browser != null)
            return printHandler_.onPrintDialog(browser, hasSelection, callback);
        return false;
    }

    @Override
    public boolean onPrintJob(CefBrowser browser, String documentName, String pdfFilePath,
            CefPrintJobCallback callback) {
        if (printHandler_ != null && browser != null)
            return printHandler_.onPrintJob(browser, documentName, pdfFilePath, callback);
        return false;
    }

    @Override
    public void onPrintReset(CefBrowser browser) {
        if (printHandler_ != null && browser != null) printHandler_.onPrintReset(browser);
    }

    @Override
    public Dimension getPdfPaperSize(CefBrowser browser, int deviceUnitsPerInch) {
        if (printHandler_ != null && browser != null)
            return printHandler_.getPdfPaperSize(browser, deviceUnitsPerInch);
        return null;
    }

    // CefMessageRouter

    @Override
    public synchronized void addMessageRouter(CefMessageRouter messageRouter) {
        super.addMessageRouter(messageRouter);
    }

    @Override
    public synchronized void removeMessageRouter(CefMessageRouter messageRouter) {
        super.removeMessageRouter(messageRouter);
    }

    // CefRenderHandler

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        if (browser == null) return new Rectangle(0, 0, 0, 0);

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) return realHandler.getViewRect(browser);
        return new Rectangle(0, 0, 0, 0);
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        if (browser == null) return new Point(0, 0);

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) return realHandler.getScreenPoint(browser, viewPoint);
        return new Point(0, 0);
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.onPopupShow(browser, show);
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.onPopupSize(browser, size);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null)
            realHandler.onPaint(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public void onImeCompositionRangeChanged(CefBrowser browser, CefRange selectedRange, Rectangle[] characterBounds) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null)
            realHandler.onImeCompositionRangeChanged(browser, selectedRange, characterBounds);
    }

    @Override
    public void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null)
            realHandler.onTextSelectionChanged(browser, selectedText, selectedRange);
    }

    // Paint listeners are owned by each browser's render handler. CefClient only routes paint
    // callbacks and intentionally has no client-wide listener collection.
    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        if (browser == null || dragData == null) return false;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler == null) return false;

        // Native callback wrappers are invalidated when StartDragging returns. Transfer a clone so
        // MCEF can retain it until its emulated drag completes; a handler that returns normally
        // owns the clone and must dispose it after immediate use or when retained use finishes.
        CefDragData ownedDragData = dragData.clone();
        if (ownedDragData == null) return false;
        try {
            return realHandler.startDragging(browser, ownedDragData, mask, x, y);
        } catch (RuntimeException | Error error) {
            // Ownership transfers only after a normal return, so this path is safe to release here.
            ownedDragData.dispose();
            throw error;
        }
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.updateDragCursor(browser, operation);
    }

    // CefRequestHandler

    public CefClient addRequestHandler(CefRequestHandler handler) {
        if (requestHandler_ == null) requestHandler_ = handler;
        return this;
    }

    public void removeRequestHandler() {
        requestHandler_ = null;
    }

    @Override
    public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
            boolean user_gesture, boolean is_redirect) {
        if (requestHandler_ != null && browser != null)
            return requestHandler_.onBeforeBrowse(browser, frame, request, user_gesture, is_redirect);
        return false;
    }

    @Override
    public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String target_url, boolean user_gesture) {
        if (isDisposed_) return true;
        if (requestHandler_ != null && browser != null)
            return requestHandler_.onOpenURLFromTab(browser, frame, target_url, user_gesture);
        return false;
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
            CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
            BoolRef disableDefaultHandling) {
        if (requestHandler_ != null && browser != null) {
            return requestHandler_.getResourceRequestHandler(browser, frame, request, isNavigation,
                    isDownload, requestInitiator, disableDefaultHandling);
        }
        return null;
    }

    @Override
    public boolean getAuthCredentials(CefBrowser browser, String origin_url, boolean isProxy,
            String host, int port, String realm, String scheme, CefAuthCallback callback) {
        if (requestHandler_ != null && browser != null)
            return requestHandler_.getAuthCredentials(browser, origin_url, isProxy, host, port, realm, scheme, callback);
        return false;
    }

    @Override
    public boolean onCertificateError(CefBrowser browser, ErrorCode cert_error, String request_url, CefCallback callback) {
        if (requestHandler_ != null)
            return requestHandler_.onCertificateError(browser, cert_error, request_url, callback);
        return false;
    }

    @Override
    public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int error_code, String error_string) {
        if (requestHandler_ != null)
            requestHandler_.onRenderProcessTerminated(browser, status, error_code, error_string);
    }

    // CefWindowHandler

    @Override
    public Rectangle getRect(CefBrowser browser) {
        if (browser == null) return new Rectangle(0, 0, 0, 0);

        CefWindowHandler realHandler = browser.getWindowHandler();
        if (realHandler != null) return realHandler.getRect(browser);
        return new Rectangle(0, 0, 0, 0);
    }

    @Override
    public void onMouseEvent(CefBrowser browser, int event, int screenX, int screenY, int modifier, int button) {
        if (browser == null) return;

        CefWindowHandler realHandler = browser.getWindowHandler();
        if (realHandler != null)
            realHandler.onMouseEvent(browser, event, screenX, screenY, modifier, button);
    }

    @Override
    public boolean getScreenInfo(CefBrowser arg0, CefScreenInfo arg1) {
        return false;
    }

    // CefAudioHandler

    public CefClient addAudioHandler(CefAudioHandler handler) {
        if (audioHandler_ == null) audioHandler_ = handler;
        return this;
    }

    public void removeAudioHandler() {
        audioHandler_ = null;
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        if (audioHandler_ != null) return audioHandler_.getAudioParameters(browser, params);
        return false;
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        if (audioHandler_ != null) audioHandler_.onAudioStreamStarted(browser, params, channels);
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
        if (audioHandler_ != null) audioHandler_.onAudioStreamPacket(browser, data, frames, pts);
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        if (audioHandler_ != null) audioHandler_.onAudioStreamStopped(browser);
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        if (audioHandler_ != null) audioHandler_.onAudioStreamError(browser, text);
    }
}
