// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.network.CefHeader;
import org.cef.network.CefPostData;
import org.cef.network.CefPostDataElement;
import org.cef.network.CefRequest;
import org.cef.network.CefRequest.CefUrlRequestFlags;
import org.cef.network.CefResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

@NativeCefTest
class CefNetworkValueNativeTest {
    @Test
    void requestRoundTripsDuplicateHeadersAndCurrentAndFutureFlagBits() {
        CefRequest request = CefRequest.create();
        assertNotNull(request);
        try {
            List<CefHeader> headers = List.of(new CefHeader("Set-Cookie", "first=1"),
                    new CefHeader("Set-Cookie", "second=2"), new CefHeader("X-Test", "value"));
            request.setWithHeaderList("https://request.test/path", "POST", null, headers);
            assertEquals("https://request.test/path", request.getURL());
            assertEquals("POST", request.getMethod());

            List<CefHeader> result = new ArrayList<CefHeader>();
            request.getHeaderList(result);
            assertEquals(headers, result);

            Map<String, String> legacyMap = new LinkedHashMap<String, String>();
            request.getHeaderMap(legacyMap);
            assertEquals(2, legacyMap.size());
            assertTrue(legacyMap.containsKey("Set-Cookie"));
            assertEquals("value", legacyMap.get("X-Test"));

            int flags = CefUrlRequestFlags.UR_FLAG_SKIP_CACHE
                    | CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS
                    | CefUrlRequestFlags.UR_FLAG_STOP_ON_REDIRECT | 0x00000400;
            request.setFlags(flags);
            assertEquals(flags, request.getFlags());
            assertEquals(0x00000400, CefUrlRequestFlags.getUnknownFlags(request.getFlags()));
            assertEquals(0L, request.getIdentifier());
        } finally {
            request.dispose();
        }
    }

    @Test
    void responseRoundTripsCharsetUrlRawErrorsAndDuplicateHeaders() {
        CefResponse response = CefResponse.create();
        assertNotNull(response);
        try {
            response.setStatus(207);
            response.setStatusText("Multi-Status");
            response.setMimeType("application/xml");
            response.setCharset("utf-8");
            response.setURL("https://response.test/final");
            response.setError(ErrorCode.ERR_ABORTED);
            assertEquals(207, response.getStatus());
            assertEquals("Multi-Status", response.getStatusText());
            assertEquals("application/xml", response.getMimeType());
            assertEquals("utf-8", response.getCharset());
            assertEquals("https://response.test/final", response.getURL());
            assertEquals(ErrorCode.ERR_ABORTED.getCode(), response.getErrorCode());
            assertSame(ErrorCode.ERR_ABORTED, response.getError());

            int unknownError = -1_234_567;
            response.setErrorCode(unknownError);
            assertEquals(unknownError, response.getErrorCode());
            assertNull(response.getError());
            assertTrue(response.toString().contains("error: " + unknownError));

            List<CefHeader> headers = List.of(new CefHeader("Set-Cookie", "first=1"),
                    new CefHeader("Set-Cookie", "second=2"), new CefHeader("X-Test", "value"));
            response.setHeaderList(headers);
            List<CefHeader> result = new ArrayList<CefHeader>();
            response.getHeaderList(result);
            assertEquals(headers, result);
        } finally {
            response.dispose();
        }
    }

    @Test
    void postDataReportsExcludedStateAndValidatesByteBoundaries() {
        CefPostData postData = CefPostData.create();
        assertNotNull(postData);
        try {
            CefPostDataElement element = CefPostDataElement.create();
            assertNotNull(element);
            try {
                assertFalse(postData.hasExcludedElements());
                assertEquals(0, postData.getElementCount());
                element.setToBytes(3, new byte[] {1, 2, 3});
                assertEquals(CefPostDataElement.Type.PDE_TYPE_BYTES, element.getType());
                assertEquals(3, element.getBytesCount());

                byte[] output = new byte[] {9, 9, 9, 9};
                assertEquals(2, element.getBytes(2, output));
                assertArrayEquals(new byte[] {1, 2, 9, 9}, output);
                element.setToBytes(0, new byte[0]);
                assertEquals(CefPostDataElement.Type.PDE_TYPE_EMPTY, element.getType());
                assertEquals(0, element.getBytesCount());
                assertEquals(0, element.getBytes(0, new byte[0]));
                assertThrows(NullPointerException.class, () -> element.setToBytes(0, null));
                assertThrows(
                        IllegalArgumentException.class, () -> element.setToBytes(-1, new byte[0]));
                assertThrows(
                        IllegalArgumentException.class, () -> element.setToBytes(1, new byte[0]));
                assertThrows(NullPointerException.class, () -> element.getBytes(0, null));
                assertThrows(
                        IllegalArgumentException.class, () -> element.getBytes(-1, new byte[0]));
                assertThrows(
                        IllegalArgumentException.class, () -> element.getBytes(1, new byte[0]));

                element.setToBytes(3, new byte[] {1, 2, 3});
                assertTrue(postData.addElement(element));
                assertEquals(1, postData.getElementCount());
                assertFalse(postData.hasExcludedElements());
                Vector<CefPostDataElement> elements = new Vector<CefPostDataElement>();
                postData.getElements(elements);
                assertEquals(1, elements.size());
                CefPostDataElement retrieved = elements.firstElement();
                try {
                    assertEquals(3, retrieved.getBytesCount());
                } finally {
                    retrieved.dispose();
                }
                assertTrue(postData.removeElement(element));
                assertEquals(0, postData.getElementCount());
            } finally {
                element.dispose();
            }
        } finally {
            postData.dispose();
        }
    }
}
