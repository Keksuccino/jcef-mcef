// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.browser.CefDevToolsClient.DevToolsException;
import org.cef.callback.CefDragData;
import org.cef.callback.CefNativeAdapter;
import org.cef.callback.CefPdfPrintCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.callback.CefStringVisitor;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.event.CefTouchEvent;
import org.cef.handler.CefClientHandler;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefWindowHandler;
import org.cef.input.CefCompositionUnderline;
import org.cef.misc.CefPdfPrintSettings;
import org.cef.misc.CefRange;
import org.cef.misc.EventFlags;
import org.cef.network.CefRequest;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.SwingUtilities;

/**
 * This class represents all methods which are connected to the
 * native counterpart CEF.
 * The visibility of this class is "package". To create a new
 * CefBrowser instance, please use CefBrowserFactory.
 */
public abstract class CefBrowser_N extends CefNativeAdapter implements CefBrowser {
    // javac exports these values to the generated JNI header, where native UI-thread queries
    // consume them directly. -1 distinguishes failure from the two valid boolean results.
    private static final int BOOLEAN_QUERY_FAILED = -1;
    private static final int BOOLEAN_QUERY_FALSE = 0;
    private static final int BOOLEAN_QUERY_TRUE = 1;
    private static final Duration LEGACY_ZOOM_QUERY_TIMEOUT = Duration.ofSeconds(1);

    private final CefBrowserCreationController creationController_ =
            new CefBrowserCreationController();
    private final CefBrowserQueryController queryController_ = new CefBrowserQueryController();
    private final CefAwtKeyRepeatTracker awtKeyRepeatTracker_ = new CefAwtKeyRepeatTracker();
    private final CefClient client_;
    private final String url_;
    private final CefRequestContext request_context_;
    private volatile CefBrowser_N parent_ = null;
    private volatile Point inspectAt_ = null;
    private CefBrowser_N devTools_ = null;
    private boolean devToolsClosing_ = false;
    private volatile CefDevToolsClient devToolsClient_ = null;
    private boolean closeAllowed_ = false;
    private boolean closeContinuationRequested_ = false;
    private boolean closeContinuationPending_ = false;
    private Component closeContinuationComponent_ = null;
    private volatile boolean isClosed_ = false;
    private volatile boolean isClosing_ = false;
    private final CefBrowserSettings settings_;

    protected CefBrowser_N(CefClient client, String url, CefRequestContext context,
            CefBrowser_N parent, Point inspectAt, CefBrowserSettings settings) {
        client_ = client;
        url_ = url;
        request_context_ = context;
        parent_ = parent;
        inspectAt_ = inspectAt;
        if (settings != null)
            settings_ = settings.clone();
        else
            settings_ = new CefBrowserSettings();
    }

    /**
     * Clones and validates settings before a native-backed browser superclass is constructed.
     * This ordering prevents Swing subclasses from deferring the exception to a later paint and
     * avoids constructing browser-wrapper state for invalid settings.
     */
    static CefBrowserSettings copyAndValidateSettings(CefBrowserSettings settings, boolean osr, boolean transparent) {
        CefBrowserSettings snapshot = settings == null ? new CefBrowserSettings() : settings.clone();
        snapshot.validate(osr, transparent);
        return snapshot;
    }

    protected String getUrl() {
        return url_;
    }

    @Override
    public CefRequestContext getRequestContext() {
        return request_context_ != null ? request_context_ : CefRequestContext.getGlobalContext();
    }

    protected CefBrowser_N getParentBrowser() {
        return parent_;
    }

    protected Point getInspectAt() {
        return inspectAt_;
    }

    protected boolean isClosed() {
        return isClosed_;
    }

    @Override
    public CefClient getClient() {
        return client_;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return null;
    }

    @Override
    public CefWindowHandler getWindowHandler() {
        return null;
    }

    @Override
    public synchronized void setCloseAllowed() {
        closeAllowed_ = true;
        clearCloseContinuation();
    }

    @Override
    public synchronized boolean doClose() {
        if (closeAllowed_) {
            // Allow the close to proceed.
            return false;
        }

        // Headless MCEF browsers have no AWT parent, while windowed browsers
        // retain the upstream behavior of asking their containing window to close.
        Component uiComponent = getUIComponent();
        if (uiComponent == null) {
            // Returning true promises CEF that this callback sent a custom close notification.
            // Headless browsers have no window owner to notify, so CEF itself must complete the
            // close after this callback returns.
            return false;
        }

        // The CEF UI thread and AWT event thread run concurrently with the multi-threaded message
        // loop. Dispatching the second window-close event here can therefore re-enter CEF before
        // the native DoClose callback has returned. Native consumes this request and posts the
        // continuation back to the CEF UI queue, which creates the required ordering boundary.
        if (!closeContinuationPending_) {
            closeContinuationRequested_ = true;
            closeContinuationComponent_ = uiComponent;
        }

        // Cancel the close.
        return true;
    }

    /** Called from native code before the CEF {@code DoClose} callback returns. */
    private synchronized boolean prepareCloseContinuation(boolean closeCancelled) {
        boolean shouldPost = closeCancelled && closeContinuationRequested_
                && !closeContinuationPending_ && !isClosing_ && !isClosed_;
        closeContinuationRequested_ = false;
        if (shouldPost) {
            closeContinuationPending_ = true;
        } else if (!closeContinuationPending_) {
            closeContinuationComponent_ = null;
        }
        return shouldPost;
    }

    /** Called on the CEF UI thread after the native {@code DoClose} callback has returned. */
    private void continueCloseAfterDoClose() {
        synchronized (this) {
            if (!closeContinuationPending_ || closeAllowed_ || isClosing_ || isClosed_) {
                clearCloseContinuation();
                return;
            }
        }

        SwingUtilities.invokeLater(() -> {
            Component uiComponent;
            synchronized (CefBrowser_N.this) {
                if (!closeContinuationPending_ || closeAllowed_ || isClosing_ || isClosed_) {
                    clearCloseContinuation();
                    return;
                }
                uiComponent = closeContinuationComponent_;
                clearCloseContinuation();
            }

            Component parent = SwingUtilities.getRoot(uiComponent);
            if (parent instanceof Window) {
                parent.dispatchEvent(new WindowEvent((Window) parent, WindowEvent.WINDOW_CLOSING));
            } else {
                completeCloseWithoutWindowOwner();
            }
        });
    }

    /** Called from native code if the ordered CEF UI continuation cannot be posted. */
    private synchronized void abortCloseContinuation() {
        clearCloseContinuation();
    }

    private void clearCloseContinuation() {
        closeContinuationRequested_ = false;
        closeContinuationPending_ = false;
        closeContinuationComponent_ = null;
    }

    @Override
    public void onBeforeClose() {
        CefBrowser_N parent;
        boolean closeDevTools;
        CefDevToolsClient devToolsClient;
        synchronized (this) {
            isClosed_ = true;
            clearCloseContinuation();
            parent = parent_;
            parent_ = null;
            closeDevTools = parent == null && creationController_.isCreated();
            devToolsClient = devToolsClient_;
            devToolsClient_ = null;
        }
        queryController_.close();
        clearAwtKeyRepeatState();
        // Request contexts are caller-owned and may be shared by multiple browsers. Closing one
        // browser only releases CEF's native browser reference; it must not invalidate the Java
        // context wrapper for its owner or siblings.
        if (parent != null)
            parent.onDevToolsClosed(this);
        else if (closeDevTools)
            closeDevTools();
        if (devToolsClient != null) devToolsClient.close();
    }

    @Override
    public void openDevTools() {
        openDevTools(null);
    }

    @Override
    public void openDevTools(Point inspectAt) {
        CefBrowser_N devToolsToCreate = null;
        boolean showExisting = false;
        synchronized (this) {
            if (isClosing_ || isClosed_ || devToolsClosing_) return;
            if (devTools_ == null || devTools_.isClosed()) {
                devTools_ = createDevToolsBrowser(client_, url_, request_context_, this, inspectAt);
                devToolsToCreate = devTools_;
            } else if (devTools_.creationController_.isCreated()) {
                showExisting = true;
            } else if (devTools_.creationController_.isNew()) {
                devTools_.inspectAt_ = inspectAt;
                devToolsToCreate = devTools_;
            }
        }
        if (devToolsToCreate != null)
            devToolsToCreate.createImmediately();
        else if (showExisting)
            showDevTools(inspectAt);
    }

    private synchronized void onDevToolsClosed(CefBrowser_N devTools) {
        if (devTools_ == devTools) devTools_ = null;
        devToolsClosing_ = false;
    }

    private void onDevToolsCreated(CefBrowser_N devTools) {
        boolean closeImmediately;
        synchronized (this) {
            closeImmediately = devTools_ != devTools || devToolsClosing_ || isClosing_ || isClosed_;
        }
        if (closeImmediately) closeDevToolsNative();
    }

    private synchronized void onDevToolsCreationFailed(CefBrowser_N devTools) {
        if (devTools_ == devTools) devTools_ = null;
        devToolsClosing_ = false;
    }

    private void showDevTools(Point inspectAt) {
        try {
            N_ShowDevTools(inspectAt);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public synchronized CefDevToolsClient getDevToolsClient() {
        if (!creationController_.isCreated() || isClosing_ || isClosed_) {
            return null;
        }
        if (devToolsClient_ == null || devToolsClient_.isClosed()) {
            devToolsClient_ = new CefDevToolsClient(this);
        }
        return devToolsClient_;
    }

    CompletableFuture<Integer> executeDevToolsMethod(String method, String parametersAsJson) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        N_ExecuteDevToolsMethod(method, parametersAsJson, new IntCallback() {
            @Override
            public void onComplete(int generatedMessageId) {
                if (generatedMessageId <= 0) {
                    future.completeExceptionally(new DevToolsException(
                            String.format("Failed to execute DevTools method %s", method)));
                } else {
                    future.complete(generatedMessageId);
                }
            }
        });
        return future;
    }

    CefRegistration addDevToolsMessageObserver(CefDevToolsMessageObserver observer) {
        return N_AddDevToolsMessageObserver(observer);
    }

    protected abstract CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt);

    /**
     * Create a new browser.
     */
    protected void createBrowser(CefClientHandler clientHandler, long windowHandle, String url,
            boolean osr, boolean transparent, Component canvas, CefRequestContext context) {
        settings_.validate(osr, transparent);
        if (!creationController_.begin(getNativeRef("CefBrowser") != 0, isClosing_ || isClosed_))
            return;
        if (!client_.onBrowserCreationStarted(this)) {
            isClosing_ = true;
            clearAwtKeyRepeatState();
            creationController_.failed();
            return;
        }
        boolean accepted = false;
        try {
            accepted = N_CreateBrowser(
                    clientHandler, windowHandle, url, osr, transparent, canvas, context, settings_);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        } finally {
            if (!accepted) {
                creationController_.failed();
                client_.onBrowserCreationFailed(this);
            }
        }
    }

    /**
     * Called async from the (native) main UI thread.
     */
    private void notifyBrowserCreated() {
        creationController_.succeeded();
        if (isClosing_) {
            closeNative(true);
            return;
        }
        CefBrowser_N parent = parent_;
        if (parent != null) parent.onDevToolsCreated(this);
    }

    /**
     * Called asynchronously when native creation cannot be started or CEF rejects the request.
     * Returning to NEW is important because window realization and delayed layout can retry with a
     * valid parent later.
     */
    private void notifyBrowserCreationFailed() {
        creationController_.failed();
        client_.onBrowserCreationFailed(this);
        CefBrowser_N parent = parent_;
        if (parent != null) {
            parent.onDevToolsCreationFailed(this);
            parent_ = null;
        }
    }

    /**
     * Create a new browser as dev tools
     */
    protected final void createDevTools(CefBrowser_N parent, CefClientHandler clientHandler,
            long windowHandle, boolean osr, boolean transparent, Component canvas,
            Point inspectAt) {
        if (!creationController_.begin(getNativeRef("CefBrowser") != 0, isClosing_ || isClosed_))
            return;
        if (!client_.onBrowserCreationStarted(this)) {
            isClosing_ = true;
            clearAwtKeyRepeatState();
            creationController_.failed();
            return;
        }
        boolean accepted = false;
        try {
            accepted = N_CreateDevTools(
                    parent, clientHandler, windowHandle, osr, transparent, canvas, inspectAt);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        } finally {
            if (!accepted) {
                creationController_.failed();
                client_.onBrowserCreationFailed(this);
            }
        }
    }

    /**
     * Returns the native window handle for the specified native surface handle.
     */
    protected final long getWindowHandle(long surfaceHandle) {
        try {
            return N_GetWindowHandle(surfaceHandle);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return 0;
    }

    @Override
    public boolean isValid() {
        if (!isNativeBrowserStateQueryAvailable()) return false;
        try {
            return N_IsValid();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean canGoBack() {
        try {
            return N_CanGoBack();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void goBack() {
        try {
            N_GoBack();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public boolean canGoForward() {
        try {
            return N_CanGoForward();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void goForward() {
        try {
            N_GoForward();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public boolean isLoading() {
        try {
            return N_IsLoading();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void reload() {
        try {
            N_Reload();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void reloadIgnoreCache() {
        try {
            N_ReloadIgnoreCache();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void stopLoad() {
        try {
            N_StopLoad();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public int getIdentifier() {
        try {
            return N_GetIdentifier();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return -1;
        }
    }

    @Override
    public boolean isSame(CefBrowser that) {
        Objects.requireNonNull(that, "that");
        if (!isNativeBrowserStateQueryAvailable()) return false;
        try {
            return N_IsSame(that);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public CefFrame getMainFrame() {
        try {
            return N_GetMainFrame();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public CefFrame getFocusedFrame() {
        try {
            return N_GetFocusedFrame();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public CefFrame getFrameByIdentifier(String identifier) {
        try {
            return N_GetFrameByIdentifier(identifier);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public CefFrame getFrameByName(String name) {
        try {
            return N_GetFrameByName(name);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public Vector<String> getFrameIdentifiers() {
        try {
            return N_GetFrameIdentifiers();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public Vector<String> getFrameNames() {
        try {
            return N_GetFrameNames();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return null;
        }
    }

    @Override
    public int getFrameCount() {
        try {
            return N_GetFrameCount();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            return -1;
        }
    }

    @Override
    public boolean isPopup() {
        try {
            return N_IsPopup();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean hasDocument() {
        try {
            return N_HasDocument();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void viewSource() {
        try {
            N_ViewSource();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void getSource(CefStringVisitor visitor) {
        try {
            N_GetSource(visitor);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void getText(CefStringVisitor visitor) {
        try {
            N_GetText(visitor);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void loadRequest(CefRequest request) {
        try {
            N_LoadRequest(request);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void loadURL(String url) {
        try {
            N_LoadURL(url);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void executeJavaScript(String code, String url, int line) {
        try {
            N_ExecuteJavaScript(code, url, line);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public String getURL() {
        try {
            return N_GetURL();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return "";
    }

    @Override
    public boolean isWindowRenderingDisabled() {
        if (!isNativeBrowserStateQueryAvailable()) return false;
        try {
            return N_IsWindowRenderingDisabled();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void close(boolean force) {
        synchronized (this) {
            if (isClosing_ || isClosed_) {
                clearAwtKeyRepeatState();
                return;
            }
            if (force) isClosing_ = true;
        }
        if (force) queryController_.close();
        clearAwtKeyRepeatState();

        closeNative(force);
    }

    private void completeCloseWithoutWindowOwner() {
        synchronized (this) {
            if (isClosed_) {
                clearAwtKeyRepeatState();
                return;
            }

            // DoClose already runs after before-unload handling. No custom owner exists to finish
            // the close promised by returning true, so allow the next callback and issue the
            // required continuation directly even if the original call already marked us closing.
            closeAllowed_ = true;
            isClosing_ = true;
        }
        queryController_.close();
        clearAwtKeyRepeatState();
        closeNative(true);
    }

    private void closeNative(boolean force) {
        synchronized (this) {
            // Native creation publishes the handle before notifyBrowserCreated marks the
            // controller CREATED. Serializing close admission prevents JNI entry for
            // never-created browsers. Native admission repeats the lifecycle check and converts
            // the raw handle to an owning reference before using it.
            if (isClosed_ || !creationController_.isCreated() || getNativeRef("CefBrowser") == 0)
                return;
        }
        try {
            N_Close(force);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void setFocus(boolean enable) {
        if (!enable) clearAwtKeyRepeatState();
        if (!isNativeInputEligible()) return;
        try {
            N_SetFocus(enable);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void imeSetComposition(String text, List<CefCompositionUnderline> underlines, CefRange replacementRange, CefRange selectionRange) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(underlines, "underlines");
        Objects.requireNonNull(replacementRange, "replacementRange");
        Objects.requireNonNull(selectionRange, "selectionRange");
        CefCompositionUnderline[] underlineSnapshot = copyAndValidateImeUnderlines(text, underlines);
        if (!isNativeInputEligible()) return;
        try {
            N_ImeSetComposition(text, underlineSnapshot, replacementRange, selectionRange);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void imeCommitText(String text, CefRange replacementRange, int relativeCursorPosition) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(replacementRange, "replacementRange");
        if (!isNativeInputEligible()) return;
        try {
            N_ImeCommitText(text, replacementRange, relativeCursorPosition);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void imeFinishComposingText(boolean keepSelection) {
        if (!isNativeInputEligible()) return;
        try {
            N_ImeFinishComposingText(keepSelection);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void imeCancelComposition() {
        if (!isNativeInputEligible()) return;
        try {
            N_ImeCancelComposition();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void sendTouchEvent(CefTouchEvent event) {
        Objects.requireNonNull(event, "event");
        if (!isNativeInputEligible()) return;
        try {
            N_SendTouchEvent(event.getId(), event.getX(), event.getY(), event.getRadiusX(), event.getRadiusY(), event.getRotationAngle(), event.getPressure(), event.getType().getValue(), event.getModifiers(), event.getPointerType().getValue());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    private static CefCompositionUnderline[] copyAndValidateImeUnderlines(String text, List<CefCompositionUnderline> underlines) {
        CefCompositionUnderline[] snapshot = underlines.toArray(new CefCompositionUnderline[0]);
        long textLength = text.length();
        for (int index = 0; index < snapshot.length; index++) {
            CefCompositionUnderline underline = Objects.requireNonNull(snapshot[index], "underlines[" + index + "]");
            CefRange range = Objects.requireNonNull(underline.getRange(), "underlines[" + index + "].range");
            if (!range.isValid()) throw new IllegalArgumentException("underlines[" + index + "].range must be valid");
            if (range.isReversed()) throw new IllegalArgumentException("underlines[" + index + "].range must be forward");
            if (range.getFrom() > textLength || range.getTo() > textLength) throw new IllegalArgumentException("underlines[" + index + "].range exceeds the composition text's UTF-16 length " + textLength);
        }
        return snapshot;
    }

    @Override
    public void sendCaptureLostEvent() {
        if (!isNativeInputEligible()) return;
        try {
            N_SendCaptureLostEvent();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void setWindowVisibility(boolean visible) {
        try {
            N_SetWindowVisibility(visible);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void notifyScreenInfoChanged() {
        try {
            N_NotifyScreenInfoChanged();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void invalidate(CefPaintElementType type) {
        Objects.requireNonNull(type, "type");
        try {
            N_InvalidatePaintElement(type.getValue());
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<Boolean> canZoom(CefZoomCommand command) {
        Objects.requireNonNull(command, "command");
        return executeBooleanQuery("zoom capability query", callback -> N_CanZoom(command.getValue(), callback));
    }

    @Override
    public void zoom(CefZoomCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (this) {
            if (!isNativeBrowserAvailable()) return;
            try {
                N_Zoom(command.getValue());
            } catch (UnsatisfiedLinkError ule) {
                ule.printStackTrace();
            }
        }
    }

    @Override
    public CompletableFuture<Double> getDefaultZoomLevel() {
        return executeDoubleQuery("default zoom level query", this::N_GetDefaultZoomLevel);
    }

    @Override
    public double getZoomLevel() {
        CompletableFuture<Double> zoomLevel = getZoomLevelAsync();
        try {
            return zoomLevel.get(LEGACY_ZOOM_QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).doubleValue();
        } catch (InterruptedException exception) {
            zoomLevel.cancel(false);
            Thread.currentThread().interrupt();
        } catch (TimeoutException exception) {
            // The legacy contract returns 0.0 after a bounded wait. Canceling also releases the
            // query controller's ownership if the CEF UI task has not won completion already.
            zoomLevel.cancel(false);
        } catch (ExecutionException exception) {
            // Preserve the legacy 0.0 failure sentinel for an unsuccessful asynchronous query.
        }
        return 0.0;
    }

    @Override
    public CompletableFuture<Double> getZoomLevelAsync() {
        return executeDoubleQuery("current zoom level query", this::N_GetZoomLevelAsync);
    }

    @Override
    public void setZoomLevel(double zoomLevel) {
        synchronized (this) {
            if (!isNativeBrowserAvailable()) return;
            try {
                N_SetZoomLevel(zoomLevel);
            } catch (UnsatisfiedLinkError ule) {
                ule.printStackTrace();
            }
        }
    }

    @Override
    public void runFileDialog(FileDialogMode mode, String title, String defaultFilePath,
            Vector<String> acceptFilters, int selectedAcceptFilter,
            CefRunFileDialogCallback callback) {
        try {
            N_RunFileDialog(
                    mode, title, defaultFilePath, acceptFilters, selectedAcceptFilter, callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void startDownload(String url) {
        try {
            N_StartDownload(url);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void print() {
        try {
            N_Print();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void printToPDF(
            String path, CefPdfPrintSettings settings, CefPdfPrintCallback callback) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path was null or empty");
        }
        try {
            N_PrintToPDF(path, settings, callback);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void find(String searchText, boolean forward, boolean matchCase, boolean findNext) {
        try {
            N_Find(searchText, forward, matchCase, findNext);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void stopFinding(boolean clearSelection) {
        try {
            N_StopFinding(clearSelection);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public void closeDevTools() {
        boolean closeNative = false;
        synchronized (this) {
            if (devToolsClosing_) return;
            if (devTools_ == null) {
                // A DevTools browser may have been opened outside this wrapper. Closing the parent
                // host is harmless when none exists and is the only way to close that native view.
                closeNative = creationController_.isCreated() && !isClosing_ && !isClosed_;
            } else if (devTools_.creationController_.isNew()) {
                // openDevTools() invokes createImmediately outside this lock. Mark the throwaway
                // wrapper first so that racing creation observes the ending lifecycle and stops.
                devTools_.isClosing_ = true;
                devTools_.clearAwtKeyRepeatState();
                devTools_.parent_ = null;
                devTools_ = null;
                closeNative = creationController_.isCreated() && !isClosing_ && !isClosed_;
            } else {
                devTools_.clearAwtKeyRepeatState();
                devToolsClosing_ = true;
                closeNative = true;
            }
        }
        if (closeNative) closeDevToolsNative();
    }

    private void closeDevToolsNative() {
        try {
            N_CloseDevTools();
        } catch (UnsatisfiedLinkError ule) {
            synchronized (this) {
                devToolsClosing_ = false;
            }
            ule.printStackTrace();
        }
    }

    @Override
    public void replaceMisspelling(String word) {
        try {
            N_ReplaceMisspelling(word);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Notify that the browser was resized.
     * @param width The new width of the browser
     * @param height The new height of the browser
     */
    protected final void wasResized(int width, int height) {
        try {
            N_WasResized(width, height);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Invalidate the main view. Swing OSR uses this compatibility entry point when a popup closes.
     */
    protected final void invalidate() {
        try {
            N_Invalidate();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Send an MCEF key event. Its IDs, key codes, modifier bits and scan code use the legacy GLFW
     * contract represented by {@link CefKeyEvent}; they are not AWT values.
     * @param e The MCEF/GLFW event to send.
     */
    protected final void sendKeyEvent(CefKeyEvent e) {
        if (e == null || isClosing_ || isClosed_) return;
        try {
            N_SendKeyEvent(e);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Send a Swing key event using the original AWT IDs, key codes and modifier masks.
     * @param e The AWT event to send.
     */
    protected final void sendAwtKeyEvent(java.awt.event.KeyEvent e) {
        if (e == null) return;
        if (!isNativeInputEligible()) {
            clearAwtKeyRepeatState();
            return;
        }
        boolean repeated = awtKeyRepeatTracker_.update(e);
        if (!isNativeInputEligible()) {
            clearAwtKeyRepeatState();
            return;
        }
        try {
            N_SendKeyEventAwt(e, repeated);
        } catch (UnsatisfiedLinkError ule) {
            clearAwtKeyRepeatState();
            ule.printStackTrace();
        }
    }

    private boolean isNativeInputEligible() {
        // Native publishes the browser reference before transitioning the synchronized creation
        // controller to CREATED. Reading the controller first therefore also makes that reference
        // visible and prevents input rejected during NEW/PENDING from poisoning repeat state.
        return !isClosing_ && !isClosed_ && creationController_.isCreated()
                && getNativeRef("CefBrowser") != 0;
    }

    /** Forwards focus from a retained AWT listener only while its native browser is fully live. */
    protected final boolean sendAwtFocusEvent(boolean enable) {
        if (!enable) clearAwtKeyRepeatState();
        if (!isNativeInputEligible()) return false;
        setFocus(enable);
        return true;
    }

    private void clearAwtKeyRepeatState() {
        awtKeyRepeatTracker_.clear();
    }

    /**
     * Send an MCEF mouse event. Its event IDs and modifier bits use the legacy DTO contract, while
     * button values are normalized as 0=left, 1=middle and 2=right. They are not raw AWT or GLFW
     * button values.
     * @param e The MCEF event to send.
     */
    protected final void sendMouseEvent(CefMouseEvent e) {
        if (e == null || isClosing_ || isClosed_) return;
        try {
            N_SendMouseEvent(e);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Send a Swing mouse event using the original AWT IDs, button codes and modifier masks.
     * @param e The AWT event to send.
     */
    protected final void sendAwtMouseEvent(java.awt.event.MouseEvent e) {
        if (e == null || isClosing_ || isClosed_) return;
        try {
            N_SendMouseEventAwt(e);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Send an MCEF mouse-wheel event. Its modifier bits use the legacy GLFW contract represented by
     * {@link CefMouseWheelEvent}; they are not AWT values.
     * @param e The MCEF/GLFW event to send.
     */
    protected final void sendMouseWheelEvent(CefMouseWheelEvent e) {
        if (e == null || isClosing_ || isClosed_) return;
        try {
            N_SendMouseWheelEvent(e);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Send a Swing mouse-wheel event using the original AWT scroll and modifier semantics.
     * @param e The AWT event to send.
     */
    protected final void sendAwtMouseWheelEvent(java.awt.event.MouseWheelEvent e) {
        if (e == null || isClosing_ || isClosed_) return;
        try {
            N_SendMouseWheelEventAwt(e);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method when the user drags the mouse into the web view (before
     * calling DragTargetDragOver/DragTargetLeave/DragTargetDrop).
     * |drag_data| should not contain file contents as this type of data is not
     * allowed to be dragged into the web view. File contents can be removed using
     * CefDragData::ResetFileContents (for example, if |drag_data| comes from
     * CefRenderHandler::StartDragging).
     * This method is only used when window rendering is disabled.
     * @param modifiers A bitwise combination of {@link EventFlags} values. These are already CEF
     *        flags, not AWT or GLFW modifier masks.
     */
    protected final void dragTargetDragEnter(
            CefDragData dragData, Point pos, int modifiers, int allowedOps) {
        if (dragData == null || pos == null || isClosing_ || isClosed_) return;
        try {
            N_DragTargetDragEnter(dragData, pos, modifiers, allowedOps);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method each time the mouse is moved across the web view during
     * a drag operation (after calling DragTargetDragEnter and before calling
     * DragTargetDragLeave/DragTargetDrop).
     * This method is only used when window rendering is disabled.
     * @param modifiers A bitwise combination of {@link EventFlags} values. These are already CEF
     *        flags, not AWT or GLFW modifier masks.
     */
    protected final void dragTargetDragOver(Point pos, int modifiers, int allowedOps) {
        if (pos == null || isClosing_ || isClosed_) return;
        try {
            N_DragTargetDragOver(pos, modifiers, allowedOps);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method when the user drags the mouse out of the web view (after
     * calling DragTargetDragEnter).
     * This method is only used when window rendering is disabled.
     */
    protected final void dragTargetDragLeave() {
        if (isClosing_ || isClosed_) return;
        try {
            N_DragTargetDragLeave();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method when the user completes the drag operation by dropping
     * the object onto the web view (after calling DragTargetDragEnter).
     * The object being dropped is |drag_data|, given as an argument to
     * the previous DragTargetDragEnter call.
     * This method is only used when window rendering is disabled.
     * @param modifiers A bitwise combination of {@link EventFlags} values. These are already CEF
     *        flags, not AWT or GLFW modifier masks.
     */
    protected final void dragTargetDrop(Point pos, int modifiers) {
        if (pos == null || isClosing_ || isClosed_) return;
        try {
            N_DragTargetDrop(pos, modifiers);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method when the drag operation started by a
     * CefRenderHandler.startDragging call has ended either in a drop or
     * by being cancelled. |x| and |y| are mouse coordinates relative to the
     * upper-left corner of the view. If the web view is both the drag source
     * and the drag target then all DragTarget* methods should be called before
     * DragSource* methods.
     * This method is only used when window rendering is disabled.
     */
    protected final void dragSourceEndedAt(Point pos, int operation) {
        if (pos == null || isClosing_ || isClosed_) return;
        try {
            N_DragSourceEndedAt(pos, operation);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method when the drag operation started by a
     * CefRenderHandler.startDragging call has completed. This method may be
     * called immediately without first calling DragSourceEndedAt to cancel a
     * drag operation. If the web view is both the drag source and the drag
     * target then all DragTarget* methods should be called before DragSource*
     * methods.
     * This method is only used when window rendering is disabled.
     */
    protected final void dragSourceSystemDragEnded() {
        if (isClosing_ || isClosed_) return;
        try {
            N_DragSourceSystemDragEnded();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    protected final void updateUI(Rectangle contentRect, Rectangle browserRect) {
        try {
            N_UpdateUI(contentRect, browserRect);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    protected final void setParent(long windowHandle, Component canvas) {
        if (isClosing_ || isClosed_) return;

        try {
            N_SetParent(windowHandle, canvas);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    /**
     * Call this method if the browser frame was moved.
     * This fixes positioning of select popups and dismissal on window move/resize.
     */
    protected final void notifyMoveOrResizeStarted() {
        try {
            N_NotifyMoveOrResizeStarted();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    public void setWindowlessFrameRate(int frameRate) {
        try {
            N_SetWindowlessFrameRate(frameRate);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    public CompletableFuture<Integer> getWindowlessFrameRate() {
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            N_GetWindowlessFrameRate(future::complete);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            future.complete(0);
        }
        return future;
    }

    @Override
    public void setAudioMuted(boolean muted) {
        try {
            N_SetAudioMuted(muted);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<Boolean> isAudioMuted() {
        return executeBooleanQuery("audio mute query", this::N_IsAudioMuted);
    }

    @Override
    public CompletableFuture<Boolean> hasDevTools() {
        return executeBooleanQuery("DevTools association query", this::N_HasDevTools);
    }

    @Override
    public CompletableFuture<Boolean> isFullscreen() {
        return executeBooleanQuery("fullscreen query", this::N_IsFullscreen);
    }

    @Override
    public void exitFullscreen(boolean willCauseResize) {
        synchronized (this) {
            if (!isNativeBrowserAvailable()) return;
        }
        try {
            N_ExitFullscreen(willCauseResize);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    private CompletableFuture<Boolean> executeBooleanQuery(String operation, IntQueryStarter starter) {
        QueryStarter<Boolean> queryStarter = completion -> {
            IntCallback callback = result -> completeBooleanQuery(operation, completion, result);
            starter.start(callback);
        };
        return executeQuery(operation, queryStarter);
    }

    private CompletableFuture<Double> executeDoubleQuery(String operation, DoubleQueryStarter starter) {
        QueryStarter<Double> queryStarter = completion -> {
            DoubleCallback callback = (success, value) -> completeDoubleQuery(operation, completion, success, value);
            starter.start(callback);
        };
        return executeQuery(operation, queryStarter);
    }

    private static void completeBooleanQuery(String operation, QueryCompletion<Boolean> completion, int result) {
        if (result == BOOLEAN_QUERY_FALSE) {
            completion.complete(Boolean.FALSE);
        } else if (result == BOOLEAN_QUERY_TRUE) {
            completion.complete(Boolean.TRUE);
        } else if (result == BOOLEAN_QUERY_FAILED) {
            completion.fail(new IllegalStateException("Failed to execute browser " + operation));
        } else {
            completion.fail(new IllegalStateException("Unexpected browser " + operation + " result: " + result));
        }
    }

    private static void completeDoubleQuery(String operation, QueryCompletion<Double> completion, boolean success, double value) {
        if (success) {
            completion.complete(Double.valueOf(value));
        } else {
            completion.fail(new IllegalStateException("Failed to execute browser " + operation));
        }
    }

    private <T> CompletableFuture<T> executeQuery(String operation, QueryStarter<T> starter) {
        CefBrowserQueryController.Query<T> query;
        synchronized (this) {
            query = queryController_.begin(operation, isNativeBrowserAvailable());
        }
        if (!queryController_.isPending(query)) return query.future();

        QueryCompletion<T> completion = new QueryCompletion<T>(queryController_, query);
        try {
            // Native admission repeats the lifecycle check while converting the raw JNI handle to
            // an owning CefRef. Close may win between the Java and native checks; either close or
            // the native failure callback then claims the query, and the other terminal path is
            // ignored by the exact-once controller.
            starter.start(completion);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            completion.fail(ule);
        } catch (RuntimeException | Error exception) {
            completion.fail(exception);
        } finally {
            // A direct CEF UI-thread query can invoke its callback before JNI returns. Defer
            // publication until the native stack has unwound so user continuations cannot reenter
            // browser lifecycle code from inside admission.
            completion.finishAdmission();
        }
        return query.future();
    }

    private boolean isNativeBrowserAvailable() {
        return !isClosing_ && !isClosed_ && creationController_.isCreated() && getNativeRef("CefBrowser") != 0;
    }

    /**
     * Identity and render-mode queries follow CEF's native lifetime, which ends at
     * OnBeforeClose return rather than when Java requests a force-close. Native admission repeats
     * this check while promoting the raw handle to an owning reference so pointer clearing cannot
     * race the query.
     */
    private synchronized boolean isNativeBrowserStateQueryAvailable() {
        return !isClosed_ && creationController_.isCreated() && getNativeRef("CefBrowser") != 0;
    }

    private interface IntCallback {
        void onComplete(int value);
    }

    private interface DoubleCallback {
        void onComplete(boolean success, double value);
    }

    private interface IntQueryStarter {
        void start(IntCallback callback);
    }

    private interface DoubleQueryStarter {
        void start(DoubleCallback callback);
    }

    private interface QueryStarter<T> {
        void start(QueryCompletion<T> completion);
    }

    private static final class QueryCompletion<T> {
        private final CefBrowserQueryController controller_;
        private final CefBrowserQueryController.Query<T> query_;
        private boolean admitting_ = true;
        private Runnable deferredTerminalAction_;

        private QueryCompletion(CefBrowserQueryController controller, CefBrowserQueryController.Query<T> query) {
            controller_ = controller;
            query_ = query;
        }

        private void complete(T value) {
            acceptTerminalAction(controller_.prepareCompletion(query_, value));
        }

        private void fail(Throwable failure) {
            acceptTerminalAction(controller_.prepareFailure(query_, failure));
        }

        private void finishAdmission() {
            Runnable terminalAction;
            synchronized (this) {
                admitting_ = false;
                terminalAction = deferredTerminalAction_;
                deferredTerminalAction_ = null;
            }
            if (terminalAction != null) terminalAction.run();
        }

        private void acceptTerminalAction(Runnable terminalAction) {
            if (terminalAction == null) return;
            synchronized (this) {
                if (admitting_) {
                    deferredTerminalAction_ = terminalAction;
                    return;
                }
            }
            terminalAction.run();
        }
    }

    private static native Map<String, Object> N_ConvertBrowserSettingsForTesting(CefBrowserSettings settings, boolean osr, boolean transparent);
    private static native Object[] N_ConvertImeCompositionForTesting(String text, CefCompositionUnderline[] underlines, CefRange replacementRange, CefRange selectionRange);
    private static native boolean N_IsOnCefUiThreadForTesting();
    private static native int N_ResolveLinuxNativeKeyCodeForTesting(long suppliedNativeKeyCode, int keyCode, int keyLocation, boolean typed, boolean awt);
    private static native int N_ResolveWindowsNativeKeyCodeForTesting(long suppliedScanCode, int mappedScanCode, boolean extended);
    private final native boolean N_CreateBrowser(CefClientHandler clientHandler, long windowHandle,
            String url, boolean osr, boolean transparent, Component canvas,
            CefRequestContext context, CefBrowserSettings settings);
    private final native boolean N_CreateDevTools(CefBrowser parent, CefClientHandler clientHandler,
            long windowHandle, boolean osr, boolean transparent, Component canvas, Point inspectAt);
    private final native void N_ExecuteDevToolsMethod(
            String method, String parametersAsJson, IntCallback callback);
    private final native CefRegistration N_AddDevToolsMessageObserver(
            CefDevToolsMessageObserver observer);
    private final native long N_GetWindowHandle(long surfaceHandle);
    private final native boolean N_IsValid();
    private final native boolean N_CanGoBack();
    private final native void N_GoBack();
    private final native boolean N_CanGoForward();
    private final native void N_GoForward();
    private final native boolean N_IsLoading();
    private final native void N_Reload();
    private final native void N_ReloadIgnoreCache();
    private final native void N_StopLoad();
    private final native int N_GetIdentifier();
    private final native boolean N_IsSame(CefBrowser that);
    private final native CefFrame N_GetMainFrame();
    private final native CefFrame N_GetFocusedFrame();
    private final native CefFrame N_GetFrameByIdentifier(String identifier);
    private final native CefFrame N_GetFrameByName(String name);
    private final native Vector<String> N_GetFrameIdentifiers();
    private final native Vector<String> N_GetFrameNames();
    private final native int N_GetFrameCount();
    private final native boolean N_IsPopup();
    private final native boolean N_HasDocument();
    private final native void N_ViewSource();
    private final native void N_GetSource(CefStringVisitor visitor);
    private final native void N_GetText(CefStringVisitor visitor);
    private final native void N_LoadRequest(CefRequest request);
    private final native void N_LoadURL(String url);
    private final native void N_ExecuteJavaScript(String code, String url, int line);
    private final native String N_GetURL();
    private final native boolean N_IsWindowRenderingDisabled();
    private final native void N_Close(boolean force);
    private final native void N_SetFocus(boolean enable);
    private final native void N_ImeSetComposition(String text, CefCompositionUnderline[] underlines, CefRange replacementRange, CefRange selectionRange);
    private final native void N_ImeCommitText(String text, CefRange replacementRange, int relativeCursorPosition);
    private final native void N_ImeFinishComposingText(boolean keepSelection);
    private final native void N_ImeCancelComposition();
    private final native void N_SendTouchEvent(int id, float x, float y, float radiusX, float radiusY, float rotationAngle, float pressure, int type, int modifiers, int pointerType);
    private final native void N_SetWindowVisibility(boolean visible);
    private final native void N_NotifyScreenInfoChanged();
    private final native void N_CanZoom(int command, IntCallback callback);
    private final native void N_Zoom(int command);
    private final native void N_GetDefaultZoomLevel(DoubleCallback callback);
    // Retain this exact ()D JNI descriptor for compatibility with older CefBrowser_N bytecode.
    private final native double N_GetZoomLevel();
    private final native void N_GetZoomLevelAsync(DoubleCallback callback);
    private final native void N_SetZoomLevel(double zoomLevel);
    private final native void N_RunFileDialog(FileDialogMode mode, String title,
            String defaultFilePath, Vector<String> acceptFilters, int selectedAcceptFilter,
            CefRunFileDialogCallback callback);
    private final native void N_StartDownload(String url);
    private final native void N_Print();
    private final native void N_PrintToPDF(
            String path, CefPdfPrintSettings settings, CefPdfPrintCallback callback);
    private final native void N_Find(
            String searchText, boolean forward, boolean matchCase, boolean findNext);
    private final native void N_StopFinding(boolean clearSelection);
    private final native void N_ShowDevTools(Point inspectAt);
    private final native void N_CloseDevTools();
    private final native void N_HasDevTools(IntCallback callback);
    private final native void N_ReplaceMisspelling(String word);
    private final native void N_WasResized(int width, int height);
    private final native void N_Invalidate();
    private final native void N_InvalidatePaintElement(int type);
    private final native void N_SendCaptureLostEvent();
    private final native void N_SendKeyEvent(CefKeyEvent e);
    private final native void N_SendKeyEventAwt(java.awt.event.KeyEvent e, boolean repeated);
    private final native void N_SendMouseEvent(CefMouseEvent e);
    private final native void N_SendMouseEventAwt(java.awt.event.MouseEvent e);
    private final native void N_SendMouseWheelEvent(CefMouseWheelEvent e);
    private final native void N_SendMouseWheelEventAwt(java.awt.event.MouseWheelEvent e);
    private final native void N_DragTargetDragEnter(
            CefDragData dragData, Point pos, int modifiers, int allowed_ops);
    private final native void N_DragTargetDragOver(Point pos, int modifiers, int allowed_ops);
    private final native void N_DragTargetDragLeave();
    private final native void N_DragTargetDrop(Point pos, int modifiers);
    private final native void N_DragSourceEndedAt(Point pos, int operation);
    private final native void N_DragSourceSystemDragEnded();
    private final native void N_UpdateUI(Rectangle contentRect, Rectangle browserRect);
    private final native void N_SetParent(long windowHandle, Component canvas);
    private final native void N_NotifyMoveOrResizeStarted();
    private final native void N_SetWindowlessFrameRate(int frameRate);
    private final native void N_GetWindowlessFrameRate(IntCallback frameRateCallback);
    private final native void N_SetAudioMuted(boolean muted);
    private final native void N_IsAudioMuted(IntCallback callback);
    private final native void N_IsFullscreen(IntCallback callback);
    private final native void N_ExitFullscreen(boolean willCauseResize);
}
