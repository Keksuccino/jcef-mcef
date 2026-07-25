// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.network;

import org.cef.browser.CefRequestContext;
import org.cef.callback.CefNative;
import org.cef.callback.CefURLRequestClient;
import org.cef.handler.CefLoadHandler.ErrorCode;

class CefURLRequest_N extends CefURLRequest implements CefNative {
    // Opaque value owned by the loaded native ABI. Current native builds use a registry token;
    // older compatible native builds may still use their legacy handle representation.
    private volatile long N_CefHandle = 0;
    // Creation can complete and reenter a disposing Java callback before an off-UI factory caller
    // resumes. Track the native factory result independently so that race cannot turn a
    // successfully delivered request into a null Java return value. The token remains an
    // ordinary-success fallback for older native libraries, which cannot report a request already
    // disposed during creation.
    private volatile boolean N_CreationSucceeded = false;
    private final CefRequest request_;
    private final CefURLRequestClient client_;

    @Override
    public void setNativeRef(String identifer, long nativeRef) {
        N_CefHandle = nativeRef;
    }

    @Override
    public long getNativeRef(String identifer) {
        return N_CefHandle;
    }

    CefURLRequest_N(CefRequest request, CefURLRequestClient client) {
        super();
        request_ = request;
        client_ = client;
    }

    public static final CefURLRequest createNative(CefRequest request, CefURLRequestClient client) {
        // keep a reference to the request and client objects.
        CefURLRequest_N result = new CefURLRequest_N(request, client);
        try {
            result.N_Create(request, client);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return completeCreation(result);
    }

    public static final CefURLRequest createNative(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext) {
        if (requestContext == null) return createNative(request, client);

        // CefRequestContext_N.dispose() uses the same monitor. Keep it locked until JNI has
        // acquired its own CefRefPtr so disposal cannot invalidate the raw native handle between
        // Java validation and native ownership acquisition.
        synchronized (requestContext) {
            CefURLRequest_N result = new CefURLRequest_N(request, client);
            try {
                result.N_CreateWithContext(request, client, requestContext);
            } catch (UnsatisfiedLinkError ule) {
                ule.printStackTrace();
            }
            return completeCreation(result);
        }
    }

    private static CefURLRequest completeCreation(CefURLRequest_N result) {
        if (!result.N_CreationSucceeded && result.N_CefHandle == 0) return null;
        return result;
    }

    @Override
    public void dispose() {
        try {
            N_Dispose(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    @Override
    public CefRequest getRequest() {
        return request_;
    }

    @Override
    public CefURLRequestClient getClient() {
        return client_;
    }

    @Override
    public Status getRequestStatus() {
        try {
            return N_GetRequestStatus(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public int getRequestErrorCode() {
        try {
            return N_GetRequestErrorCode(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return ErrorCode.ERR_FAILED.getCode();
    }

    @Override
    public CefResponse getResponse() {
        try {
            return N_GetResponse(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean responseWasCached() {
        try {
            return N_ResponseWasCached(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return false;
    }

    @Override
    public void cancel() {
        try {
            N_Cancel(N_CefHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    private final native void N_Create(CefRequest request, CefURLRequestClient client);
    private final native void N_CreateWithContext(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext);
    private final native void N_Dispose(long self);
    private final native Status N_GetRequestStatus(long self);
    private final native int N_GetRequestErrorCode(long self);
    private final native CefResponse N_GetResponse(long self);
    private final native boolean N_ResponseWasCached(long self);
    private final native void N_Cancel(long self);
    static native boolean N_RunDisposedCreationRaceForTesting(CefURLRequest_N request);
    static native boolean N_RunTokenRegistryConcurrencyForTesting();
    static native boolean N_RunPendingDispatchAbandonmentForTesting();
    static native boolean N_RunURLRequestLifecycleForTesting(CefURLRequest_N request);
}
