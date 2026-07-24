// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable name/value pair used by the duplicate-preserving HTTP header APIs. */
public final class CefHeader {
    private final String name_;
    private final String value_;

    public CefHeader(String name, String value) {
        name_ = Objects.requireNonNull(name, "name");
        value_ = Objects.requireNonNull(value, "value");
    }

    public String getName() {
        return name_;
    }

    public String getValue() {
        return value_;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof CefHeader)) return false;
        CefHeader other = (CefHeader) object;
        return name_.equals(other.name_) && value_.equals(other.value_);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name_, value_);
    }

    @Override
    public String toString() {
        return name_ + ": " + value_;
    }

    static String[] toNativePairs(List<CefHeader> headers) {
        Objects.requireNonNull(headers, "headers");
        if (headers.size() > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Too many headers for a Java array");
        }

        String[] pairs = new String[headers.size() * 2];
        for (int index = 0; index < headers.size(); index++) {
            CefHeader header = Objects.requireNonNull(headers.get(index), "headers[" + index + "]");
            pairs[index * 2] = header.name_;
            pairs[index * 2 + 1] = header.value_;
        }
        return pairs;
    }

    static void addNativePairs(List<CefHeader> headers, String[] pairs) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(pairs, "pairs");
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("Native header array must contain name/value pairs");
        }
        List<CefHeader> converted = new ArrayList<CefHeader>(pairs.length / 2);
        for (int index = 0; index < pairs.length; index += 2)
            converted.add(new CefHeader(pairs[index], pairs[index + 1]));
        headers.addAll(converted);
    }
}
