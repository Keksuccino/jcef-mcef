// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import org.cef.handler.CefLoadHandler.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable result delivered by {@link CefResolveCallback}. */
public final class CefResolveResult {
    private final int errorCode_;
    private final List<String> resolvedIpAddresses_;

    /**
     * Creates a result from the exact native CEF error value and resolved addresses. This
     * constructor is public to make callback behavior straightforward to test and adapt.
     */
    public CefResolveResult(int errorCode, List<String> resolvedIpAddresses) {
        errorCode_ = errorCode;
        resolvedIpAddresses_ = List.copyOf(Objects.requireNonNull(resolvedIpAddresses, "resolvedIpAddresses"));
    }

    /** Returns the exact {@code cef_errorcode_t} integer, including codes unknown to this JCEF. */
    public int getErrorCode() {
        return errorCode_;
    }

    /** Returns the known JCEF error enum, or empty if a newer native runtime reports a new code. */
    public Optional<ErrorCode> getError() {
        return Optional.ofNullable(ErrorCode.findByCode(errorCode_));
    }

    /** Returns true only for {@link ErrorCode#ERR_NONE}. */
    public boolean isSuccess() {
        return errorCode_ == ErrorCode.ERR_NONE.getCode();
    }

    /** Returns an immutable snapshot of the resolved IP address strings. */
    public List<String> getResolvedIpAddresses() {
        return resolvedIpAddresses_;
    }

    @Override
    public String toString() {
        return "CefResolveResult{errorCode=" + errorCode_ + ", resolvedIpAddresses=" + resolvedIpAddresses_ + '}';
    }
}
