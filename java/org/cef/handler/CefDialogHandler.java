// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefFileDialogCallback;

import java.util.Vector;

/**
 * Implement this interface to handle dialog events. The methods of this class
 * will be called on the browser process UI thread.
 */
public interface CefDialogHandler {
    /**
     * Supported file dialog modes.
     */
    enum FileDialogMode {
        FILE_DIALOG_OPEN, //!< Requires that the file exists before allowing the user to pick it.
        FILE_DIALOG_OPEN_MULTIPLE, //!< Like Open, but allows picking multiple files to open.
        FILE_DIALOG_OPEN_FOLDER, //!< Like Open, but selects a folder to open.
        FILE_DIALOG_SAVE //!< Allows picking a nonexistent file, and prompts to overwrite if the
                         //! file already exists.
    }

    /**
     * Legacy file-dialog callback without MIME expansion and description metadata.
     *
     * <p>Existing handlers may continue to override this method. Native CEF 151 dispatches the
     * extended overload, whose default implementation delegates here exactly once. New handlers
     * should override the extended overload instead so they receive all metadata.
     *
     * @param browser The browser requesting the dialog.
     * @param mode The type of dialog to display.
     * @param title The dialog title, or an empty string for the default title.
     * @param defaultFilePath The initially selected path.
     * @param acceptFilters The accepted MIME types and file extensions.
     * @param callback The one-shot continuation for a custom dialog.
     * @return {@code true} when the handler owns {@code callback}; {@code false} for CEF's dialog.
     * @deprecated Override the extended overload to receive MIME expansion and description lists.
     */
    @Deprecated
    public default boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
            String defaultFilePath, Vector<String> acceptFilters, CefFileDialogCallback callback) {
        return false;
    }

    /**
     * Called to run a file chooser dialog.
     *
     * <p>The default implementation delegates one-way to the legacy overload. Override this method
     * to consume MIME expansion and description metadata. Implementations that transform the
     * parallel filter vectors must preserve their shared index alignment.
     *
     * @param browser
     * @param mode represents the type of dialog to display.
     * @param title to be used for the dialog and may be empty to show the default
     * title ("Open" or "Save" depending on the mode).
     * @param defaultFilePath is the path with optional directory and/or file name
     * component that should be initially selected in the dialog.
     * @param acceptFilters are used to restrict the selectable file types and may
     * any combination of (a) valid lower-cased MIME types (e.g. "text/*" or
     * "image/*"), (b) individual file extensions (e.g. ".txt" or ".png"), or (c)
     * combined description and file extension delimited using "|" and ";" (e.g.
     * "Image Types|.png;.gif;.jpg").
     * @param acceptExtensions provides the semicolon-delimited expansion of MIME
     * types to file extensions (if known, or empty string otherwise).
     * @param acceptDescriptions provides the descriptions for MIME types (if known,
     * or empty string otherwise). For example, the "image/*" mime type might
     * have extensions ".png;.jpg;.bmp;..." and description "Image Files".
     * @param callback is a callback handler for handling own file dialogs.
     *
     * @return To display a custom dialog return true and execute callback.
     * To display the default dialog return false.
     */
    public default boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
            String defaultFilePath, Vector<String> acceptFilters, Vector<String> acceptExtensions,
            Vector<String> acceptDescriptions, CefFileDialogCallback callback) {
        return onFileDialog(browser, mode, title, defaultFilePath, acceptFilters, callback);
    }
}
