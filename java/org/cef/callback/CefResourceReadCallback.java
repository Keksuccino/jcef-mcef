// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

/**
 * Callback interface used for asynchronous resource reading.
 */
public interface CefResourceReadCallback {
    /**
     * Callback for asynchronous continuation of Read(). If |bytes_read| == 0
     * the response will be considered complete. If |bytes_read| > 0 then Read()
     * will be called again until the request is complete (based on either the
     * result or the expected content length). If |bytes_read| < 0 then the
     * request will fail and the |bytes_read| value will be treated as the error
     * code. Call this method exactly once for each asynchronous Read(); subsequent
     * calls for the same Read() are ignored.
     */
    void Continue(int bytes_read);

    /**
     * Returns the byte buffer to write data into before calling #Continue(int).
     * The buffer is valid only until the first Continue() call.
     */
    public byte[] getBuffer();
}
