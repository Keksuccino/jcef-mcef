// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.network;

import org.cef.handler.CefLoadHandler.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Class used to represent a web response. The methods of this class may be
 * called on any thread.
 */
public abstract class CefResponse {
    // This CTOR can't be called directly. Call method create() instead.
    CefResponse() {}

    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }

    /**
     * Create a new CefRequest object.
     */
    public static final CefResponse create() {
        return CefResponse_N.createNative();
    }

    /**
     * Removes the native reference from an unused object.
     */
    public abstract void dispose();

    /**
     * Returns true if this object is read-only.
     */
    public abstract boolean isReadOnly();

    /**
     * Get the known response error enum, or {@code null} if native CEF reports a newer raw value.
     */
    public ErrorCode getError() {
        return ErrorCode.findByCode(getErrorCode());
    }

    /**
     * Set the response error code. Use {@link #setErrorCode(int)} when preserving an unknown raw
     * value.
     */
    public void setError(ErrorCode errorCode) {
        setErrorCode(Objects.requireNonNull(errorCode, "errorCode").getCode());
    }

    /** Returns the exact raw {@code cef_errorcode_t}, including future values. */
    public abstract int getErrorCode();

    /** Sets the exact raw {@code cef_errorcode_t}, including future values. */
    public abstract void setErrorCode(int errorCode);

    /**
     * Get the response status code.
     */
    public abstract int getStatus();

    /**
     * Set the response status code.
     */
    public abstract void setStatus(int status);

    /**
     * Get the response status text.
     */
    public abstract String getStatusText();

    /**
     * Set the response status text.
     */
    public abstract void setStatusText(String statusText);

    /**
     * Get the response mime type.
     */
    public abstract String getMimeType();

    /**
     * Set the response mime type.
     */
    public abstract void setMimeType(String mimeType);

    /** Get the response charset. */
    public abstract String getCharset();

    /** Set the response charset. */
    public abstract void setCharset(String charset);

    /**
     * Get the value for the specified response header field. Use getHeaderMap instead if there
     * might be multiple values.
     * @param name The header name.
     * @return The header value.
     */
    public abstract String getHeaderByName(String name);

    /**
     * Set the value for the specified response header field.
     * @param name The header name.
     * @param value The header value.
     * @param overwrite If true any existing values will be replaced with the new value. If false
     *         any existing values will not be overwritten.
     */
    public abstract void setHeaderByName(String name, String value, boolean overwrite);

    /**
     * Get response header fields into a map. Duplicate names are collapsed; use
     * {@link #getHeaderList(List)} when duplicate values matter.
     */
    public abstract void getHeaderMap(Map<String, String> headerMap);

    /**
     * Set response header fields from a map. Use {@link #setHeaderList(List)} for duplicate names.
     */
    public abstract void setHeaderMap(Map<String, String> headerMap);

    /**
     * Append all response headers. CEF's native multimap determines global key ordering; values
     * with equivalent names retain their relative order.
     */
    public abstract void getHeaderList(List<CefHeader> headerList);

    /**
     * Set all response headers. CEF's native multimap determines global key ordering, while values
     * with equivalent names retain their relative input order.
     */
    public abstract void setHeaderList(List<CefHeader> headerList);

    /** Get the resolved URL after redirects or HSTS rewriting. */
    public abstract String getURL();

    /** Set the resolved URL after redirects or HSTS rewriting. */
    public abstract void setURL(String url);

    @Override
    public String toString() {
        String returnValue = "\nHTTP-Response:";

        int errorCode = getErrorCode();
        ErrorCode error = ErrorCode.findByCode(errorCode);
        returnValue += "\n  error: " + (error == null ? errorCode : error);
        returnValue += "\n  readOnly: " + isReadOnly();
        returnValue += "\n    HTTP/1.1 " + getStatus() + " " + getStatusText();
        returnValue += "\n    Content-Type: " + getMimeType() + "; charset=" + getCharset();
        returnValue += "\n    URL: " + getURL();

        List<CefHeader> headerList = new ArrayList<CefHeader>();
        getHeaderList(headerList);
        for (CefHeader header : headerList) {
            returnValue += "    " + header.getName() + "=" + header.getValue() + "\n";
        }

        return returnValue;
    }
}
