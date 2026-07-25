// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefPdfPrintCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.callback.CefStringVisitor;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefWindowHandler;
import org.cef.misc.CefPdfPrintSettings;
import org.cef.network.CefRequest;

import java.awt.Component;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;

/**
 * Interface representing a browser.
 */
public interface CefBrowser {
    /**
     * Call to immediately create the underlying browser object. By default the
     * browser object will be created when the parent container is displayed for
     * the first time.
     */
    public void createImmediately();

    /**
     * Get the underlying UI component, or {@code null} for a headless browser.
     * @return The underlying UI component.
     */
    public Component getUIComponent();

    /**
     * Get the client associated with this browser.
     * @return The browser client.
     */
    public CefClient getClient();

    /**
     * Get an implementation of CefRenderHandler if any.
     * @return An instance of CefRenderHandler or null.
     */
    public CefRenderHandler getRenderHandler();

    /**
     * Retrieves the request context used by this browser instance. May be the
     * global request context if this browser does not have a specific request
     * context.
     */
    public CefRequestContext getRequestContext();

    /**
     * Get an implementation of CefWindowHandler if any.
     * @return An instance of CefWindowHandler or null.
     */
    public CefWindowHandler getWindowHandler();

    //
    // The following methods are forwarded to CefBrowser.
    //

    /**
     * Tests if the browser can navigate backwards.
     * @return true if the browser can navigate backwards.
     */
    public boolean canGoBack();

    /**
     * Go back.
     */
    public void goBack();

    /**
     * Tests if the browser can navigate forwards.
     * @return true if the browser can navigate forwards.
     */
    public boolean canGoForward();

    /**
     * Go forward.
     */
    public void goForward();

    /**
     * Tests if the browser is currently loading.
     * @return true if the browser is currently loading.
     */
    public boolean isLoading();

    /**
     * Reload the current page.
     */
    public void reload();

    /**
     * Reload the current page ignoring any cached data.
     */
    public void reloadIgnoreCache();

    /**
     * Stop loading the page.
     */
    public void stopLoad();

    /**
     * Returns the unique browser identifier.
     * @return The browser identifier
     */
    public int getIdentifier();

    /**
     * Returns the main (top-level) frame for the browser window.
     * @return The main frame
     */
    public CefFrame getMainFrame();

    /**
     * Returns the focused frame for the browser window.
     * @return The focused frame
     */
    public CefFrame getFocusedFrame();

    /**
     * Returns the frame with the specified identifier, or NULL if not found.
     * @param identifier The unique frame identifier
     * @return The frame or NULL if not found
     */
    public CefFrame getFrameByIdentifier(String identifier);

    /**
     * Returns the frame with the specified name, or NULL if not found.
     * @param name The specified name
     * @return The frame or NULL if not found
     */
    public CefFrame getFrameByName(String name);

    /**
     * Returns the identifiers of all existing frames.
     * @return All identifiers of existing frames.
     */
    public Vector<String> getFrameIdentifiers();

    /**
     * Returns the names of all existing frames.
     * @return The names of all existing frames.
     */
    public Vector<String> getFrameNames();

    /**
     * Returns the number of frames that currently exist.
     * @return The number of frames
     */
    public int getFrameCount();

    /**
     * Tests if the window is a popup window.
     * @return true if the window is a popup window.
     */
    public boolean isPopup();

    /**
     * Tests if a document has been loaded in the browser.
     * @return true if a document has been loaded in the browser.
     */
    public boolean hasDocument();

    //
    // The following methods are forwarded to the mainFrame.
    //

    /**
     * Save this frame's HTML source to a temporary file and open it in the
     * default text viewing application. This method can only be called from the
     * browser process.
     */
    public void viewSource();

    /**
     * Retrieve this frame's HTML source as a string sent to the specified
     * visitor.
     *
     * @param visitor
     */
    public void getSource(CefStringVisitor visitor);

    /**
     * Retrieve this frame's display text as a string sent to the specified
     * visitor.
     *
     * @param visitor
     */
    public void getText(CefStringVisitor visitor);

    /**
     * Load the request represented by the request object.
     *
     * @param request The request object.
     */
    public void loadRequest(CefRequest request);

    /**
     * Load the specified URL in the main frame.
     * @param url The URL to load.
     */
    public void loadURL(String url);

    /**
     * Execute a string of JavaScript code in this frame. The url
     * parameter is the URL where the script in question can be found, if any.
     * The renderer may request this URL to show the developer the source of the
     * error. The line parameter is the base line number to use for error
     * reporting.
     *
     * @param code The code to be executed.
     * @param url The URL where the script in question can be found.
     * @param line The base line number to use for error reporting.
     */
    public void executeJavaScript(String code, String url, int line);

    /**
     * Emits the URL currently loaded in this frame.
     * @return the URL currently loaded in this frame.
     */
    public String getURL();

    // The following methods are forwarded to CefBrowserHost.

    /**
     * Request that the browser close.
     * @param force force the close.
     */
    public void close(boolean force);

    /**
     * Allow the browser to close.
     */
    public void setCloseAllowed();

    /**
     * Called from CefClient.doClose.
     */
    public boolean doClose();

    /**
     * Called from CefClient.onBeforeClose.
     */
    public void onBeforeClose();

    /**
     * Set or remove keyboard focus to/from the browser window.
     * @param enable set to true to give the focus to the browser
     **/
    public void setFocus(boolean enable);

    /**
     * Notify a windowless browser that its host lost mouse capture so Chromium can release captured
     * input state. This method may be called on any browser-process thread. Calls made before
     * native browser creation or while the browser is closing or closed have no effect.
     */
    public void sendCaptureLostEvent();

    /**
     * Set whether the browser is visible. For windowless browsers, rendering and paint callbacks
     * stop while hidden. For windowed browsers this controls the containing native window on macOS
     * and has no effect on other platforms.
     * @param visible {@code true} to show the browser or {@code false} to hide it
     */
    public void setWindowVisibility(boolean visible);

    /**
     * Notify the browser that screen information has changed. This is used by windowless browsers
     * and windowed browsers with an external root window.
     */
    public void notifyScreenInfoChanged();

    /**
     * Invalidate a windowless browser surface and request an asynchronous paint callback.
     *
     * @param type the view or popup surface to invalidate
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public void invalidate(CefPaintElementType type);

    /**
     * Returns whether this browser can currently execute {@code command}.
     *
     * <p>This is a momentary capability snapshot, not an atomic guard for a later {@link
     * #zoom(CefZoomCommand)} call. Browser state may change between the query and command.
     * The native query runs on the CEF UI thread. Code that drives CEF's external message pump
     * must not block that pump while waiting for the returned future.
     *
     * @param command the zoom command to query
     * @return a future containing whether CEF reports the command available for the current zoom
     *         state; the future completes exceptionally if the native browser is unavailable or
     *         the CEF UI query cannot be scheduled
     * @throws NullPointerException if {@code command} is {@code null}
     */
    public default CompletableFuture<Boolean> canZoom(CefZoomCommand command) {
        Objects.requireNonNull(command, "command");
        return unsupportedZoomFuture("canZoom");
    }

    /**
     * Executes one Chromium zoom step or resets the browser to its configured default zoom level.
     * CEF applies the command immediately on its UI thread and otherwise queues it asynchronously.
     *
     * @param command the zoom command to execute
     * @throws NullPointerException if {@code command} is {@code null}
     * @throws UnsupportedOperationException if this browser implementation does not support CEF
     *         zoom commands
     */
    public default void zoom(CefZoomCommand command) {
        Objects.requireNonNull(command, "command");
        throw new UnsupportedOperationException("zoom is not supported by this browser");
    }

    /**
     * Returns the configured default zoom level. The value is usually 0.0 but may be configured to
     * another value.
     *
     * <p>The native query runs on the CEF UI thread. Code that drives CEF's external message pump
     * must not block that pump while waiting for the returned future.
     *
     * @return a future containing the configured default zoom level; the future completes
     *         exceptionally if the native browser is unavailable or the CEF UI query cannot be
     *         scheduled
     */
    public default CompletableFuture<Double> getDefaultZoomLevel() {
        return unsupportedZoomFuture("getDefaultZoomLevel");
    }

    /**
     * Get the current zoom level synchronously. The configured default is usually 0.0.
     *
     * <p>Prefer {@link #getZoomLevelAsync()} away from the CEF UI thread. This compatibility method
     * can wait for the externally driven CEF message pump and returns 0.0 if the query fails or
     * times out.
     *
     * @return The current zoom level, or 0.0 if the synchronous native query fails.
     */
    public double getZoomLevel();

    /**
     * Returns the current zoom level as a future.
     *
     * <p>Native JCEF browsers schedule a CEF UI-thread query without blocking the caller. The
     * default implementation preserves compatibility with existing third-party {@code CefBrowser}
     * implementations by invoking and wrapping the synchronous {@link #getZoomLevel()} method.
     * Code that drives CEF's external message pump must not block that pump while waiting for a
     * native browser's returned future.
     *
     * @return a future containing the current zoom level; the future completes exceptionally if
     *         the native browser is unavailable or the CEF UI query cannot be scheduled
     */
    public default CompletableFuture<Double> getZoomLevelAsync() {
        try {
            return CompletableFuture.completedFuture(Double.valueOf(getZoomLevel()));
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Change the zoom level to the specified value. Specify 0.0 to reset the zoom level to the
     * configured default. CEF applies the change immediately on its UI thread and otherwise queues
     * it asynchronously.
     *
     * @param zoomLevel The zoom level to be set.
     */
    public void setZoomLevel(double zoomLevel);

    private static <T> CompletableFuture<T> unsupportedZoomFuture(String method) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(method + " is not supported by this browser"));
    }

    /**
     * Call to run a file chooser dialog. Only a single file chooser dialog may be
     * pending at any given time.The dialog will be initiated asynchronously on
     * the UI thread.
     *
     * @param mode represents the type of dialog to display.
     * @param title  to be used for the dialog and may be empty to show the
     * default title ("Open" or "Save" depending on the mode).
     * @param defaultFilePath is the path with optional directory and/or file name
     * component that should be initially selected in the dialog.
     * @param acceptFilters are used to restrict the selectable file types and may
     * any combination of (a) valid lower-cased MIME types (e.g. "text/*" or
     * "image/*"), (b) individual file extensions (e.g. ".txt" or ".png"), or (c)
     * combined description and file extension delimited using "|" and ";" (e.g.
     * "Image Types|.png;.gif;.jpg").
     * @param selectedAcceptFilter is the 0-based index of the filter that should
     * be selected by default.
     * @param callback will be executed after the dialog is dismissed or
     * immediately if another dialog is already pending.
     */
    public void runFileDialog(FileDialogMode mode, String title, String defaultFilePath,
            Vector<String> acceptFilters, int selectedAcceptFilter,
            CefRunFileDialogCallback callback);

    /**
     * Download the file at url using CefDownloadHandler.
     *
     * @param url URL to download that file.
     */
    public void startDownload(String url);

    /**
     * Print the current browser contents.
     */
    public void print();

    /**
     * Print the current browser contents to a PDF.
     *
     * @param path The path of the file to write to (will be overwritten if it
     *      already exists).  Cannot be null.
     * @param settings The pdf print settings to use.  If null then defaults
     *      will be used.
     * @param callback Called when the pdf print job has completed.
     */
    public void printToPDF(String path, CefPdfPrintSettings settings, CefPdfPrintCallback callback);

    /**
     * Search for some kind of text on the page.
     *
     * @param searchText to be searched for.
     * @param forward indicates whether to search forward or backward within the page.
     * @param matchCase indicates whether the search should be case-sensitive.
     * @param findNext indicates whether this is the first request or a follow-up.
     */
    public void find(String searchText, boolean forward, boolean matchCase, boolean findNext);

    /**
     * Cancel all searches that are currently going on.
     * @param clearSelection Set to true to reset selection.
     */
    public void stopFinding(boolean clearSelection);

    /**
     * Get an instance of the DevTools to be displayed in its own window.
     */
    public void openDevTools();

    /**
     * Open an instance of the DevTools to be displayed in its own window.
     *
     * @param inspectAt a position in the UI which should be inspected.
     */
    public void openDevTools(Point inspectAt);

    /**
     * Close the DevTools.
     */
    public void closeDevTools();

    /**
     * Get an instance of a client that can be used to leverage the DevTools
     * protocol. Only one instance per browser is available.
     *
     * @see {@link CefDevToolsClient}
     * @return DevTools client, or null if this browser is not yet created
     *   or if it is closed or closing
     */
    public CefDevToolsClient getDevToolsClient();

    /**
     * If a misspelled word is currently selected in an editable node calling
     * this method will replace it with the specified |word|.
     *
     * @param word replace selected word with this word.
     */
    public void replaceMisspelling(String word);

    /**
     * Captures a screenshot-like image of the currently displayed content and returns it.
     * <p>
     * If executed on the AWT Event Thread, this returns an immediately resolved {@link
     * java.util.concurrent.CompletableFuture}. If executed from another thread, the {@link
     * java.util.concurrent.CompletableFuture} returned is resolved as soon as the screenshot
     * has been taken (which must happen on the event thread).
     * <p>
     * The generated screenshot can either be returned as-is, containing all natively-rendered
     * pixels, or it can be scaled to match the logical width and height of the window.
     * This distinction is only relevant in case of differing logical and physical resolutions
     * (for example with HiDPI/Retina displays, which have a scaling factor of for example 2
     * between the logical width of a window (ex. 400px) and the actual number of pixels in
     * each row (ex. 800px with a scaling factor of 2)).
     *
     * @param nativeResolution whether to return an image at full native resolution (true)
     *      or a scaled-down version whose width and height are equal to the logical size
     *      of the screenshotted browser window
     * @return the screenshot image
     * @throws UnsupportedOperationException if not supported
     */
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution);

    /**
     * Set the maximum rate in frames per second (fps) that {@code CefRenderHandler::onPaint}
     * will be called for a windowless browser. The actual fps may be
     * lower if the browser cannot generate frames at the requested rate. The
     * minimum value is 1, and the maximum value is 60 (default 30).
     *
     * @param frameRate the maximum frame rate
     * @throws UnsupportedOperationException if not supported
     */
    public void setWindowlessFrameRate(int frameRate);

    /**
     * Returns the maximum rate in frames per second (fps) that {@code CefRenderHandler::onPaint}
     * will be called for a windowless browser. The actual fps may be lower if the browser cannot
     * generate frames at the requested rate. The minimum value is 1, and the maximum value is 60
     * (default 30).
     *
     * @return the framerate, 0 if an error occurs
     * @throws UnsupportedOperationException if not supported
     */
    public CompletableFuture<Integer> getWindowlessFrameRate();

    /**
     * Set whether this browser's audio is muted.
     *
     * @param muted true to mute browser audio
     */
    public void setAudioMuted(boolean muted);

    /**
     * Returns whether this browser's audio is muted.
     *
     * <p>The native query runs on the CEF UI thread. Code that drives CEF's external message pump
     * must not block that pump while waiting for the returned future.
     *
     * @return a future containing the current mute state; the future completes exceptionally if
     *         the native browser is unavailable or the UI-thread query cannot be scheduled
     */
    public CompletableFuture<Boolean> isAudioMuted();
}
