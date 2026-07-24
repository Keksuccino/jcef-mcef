// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefBinaryValue;
import org.cef.callback.CefDictionaryValue;
import org.cef.callback.CefListValue;
import org.cef.callback.CefValue;
import org.cef.callback.CefValueType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

@NativeCefTest
class CefValueNativeTest {
    @Test
    void valueSupportsEverySimpleTypeAndDeepCopies() {
        try (CefValue value = CefValue.create()) {
            assertTrue(value.isValid());
            assertFalse(value.isOwned());
            assertFalse(value.isReadOnly());
            assertEquals(CefValueType.VTYPE_NULL, value.getType());

            assertTrue(value.setBool(true));
            assertEquals(CefValueType.VTYPE_BOOL, value.getType());
            assertTrue(value.getBool());
            assertTrue(value.setInt(Integer.MIN_VALUE));
            assertEquals(Integer.MIN_VALUE, value.getInt());
            assertTrue(value.setDouble(-0.0));
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(value.getDouble()));
            assertTrue(value.setString("value \uD83D\uDE80"));
            assertEquals("value \uD83D\uDE80", value.getString());

            try (CefValue copy = value.copy()) {
                assertTrue(value.isEqual(copy));
                assertFalse(value.isSame(copy));
                assertTrue(copy.setNull());
                assertEquals(CefValueType.VTYPE_NULL, copy.getType());
                assertEquals(CefValueType.VTYPE_STRING, value.getType());
            }
        }
    }

    @Test
    void binaryRoundTripsCopiesAndSupportsBoundedReads() {
        byte[] source = new byte[] {9, 1, 2, 3, 4, 8};
        try (CefBinaryValue value = CefBinaryValue.create(source, 1, 4)) {
            source[2] = 99;
            assertEquals(4, value.getSize());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, value.getData());

            byte[] destination = new byte[] {7, 7, 7, 7, 7};
            assertEquals(2, value.getData(destination, 2, 2, 1));
            assertArrayEquals(new byte[] {7, 7, 2, 3, 7}, destination);
            assertEquals(0, value.getData(destination, 0, destination.length, value.getSize()));

            try (CefBinaryValue copy = value.copy()) {
                assertTrue(value.isEqual(copy));
                assertFalse(value.isSame(copy));
                assertArrayEquals(value.getData(), copy.getData());
            }

            try (CefValue wrapper = CefValue.create()) {
                assertTrue(wrapper.setBinary(value));
                try (CefBinaryValue roundTrip = wrapper.getBinary()) {
                    assertTrue(roundTrip.isSame(value));
                    assertArrayEquals(new byte[] {1, 2, 3, 4}, roundTrip.getData());
                }
            }
        }
    }

    @Test
    void dictionaryPreservesKeysNullsAndTransferInvalidation() {
        try (CefDictionaryValue dictionary = CefDictionaryValue.create();
                CefBinaryValue transferred = CefBinaryValue.create(new byte[] {1, 2, 3})) {
            assertTrue(dictionary.setNull("null"));
            assertTrue(dictionary.setBool("bool", true));
            assertTrue(dictionary.setInt("int", 42));
            assertTrue(dictionary.setDouble("double", 2.5));
            assertTrue(dictionary.setString("string", "text"));
            assertTrue(dictionary.setBinary("binary", transferred));
            assertFalse(transferred.isValid());
            assertThrows(IllegalStateException.class, transferred::getSize);

            assertEquals(6, dictionary.getSize());
            assertTrue(dictionary.getKeys().containsAll(Arrays.asList("null", "bool", "int", "double", "string", "binary")));
            assertEquals(CefValueType.VTYPE_NULL, dictionary.getType("null"));
            assertTrue(dictionary.getBool("bool"));
            assertEquals(42, dictionary.getInt("int"));
            assertEquals(2.5, dictionary.getDouble("double"));
            assertEquals("text", dictionary.getString("string"));
            assertEquals(CefValueType.VTYPE_INVALID, dictionary.getType("missing"));
            assertNull(dictionary.getValue("missing"));

            try (CefBinaryValue owned = dictionary.getBinary("binary")) {
                assertTrue(owned.isValid());
                assertTrue(owned.isOwned());
                assertArrayEquals(new byte[] {1, 2, 3}, owned.getData());
                assertTrue(dictionary.remove("binary"));
                assertFalse(owned.isValid());
                assertThrows(IllegalStateException.class, owned::getSize);
            }
        }
    }

    @Test
    void listGrowsWithNullsAndSharesComplexValuesThroughCefValue() {
        try (CefListValue list = CefListValue.create();
                CefDictionaryValue child = CefDictionaryValue.create();
                CefValue wrapper = CefValue.create()) {
            assertTrue(child.setString("name", "child"));
            assertTrue(wrapper.setDictionary(child));
            assertTrue(child.isValid());
            assertTrue(list.setValue(2, wrapper));
            assertEquals(3, list.getSize());
            assertEquals(CefValueType.VTYPE_NULL, list.getType(0));
            assertEquals(CefValueType.VTYPE_NULL, list.getType(1));
            assertEquals(CefValueType.VTYPE_DICTIONARY, list.getType(2));
            assertFalse(child.isValid());
            assertTrue(wrapper.isValid());
            assertTrue(wrapper.isOwned());

            try (CefDictionaryValue owned = list.getDictionary(2);
                    CefValue fetched = list.getValue(2)) {
                assertEquals("child", owned.getString("name"));
                assertTrue(owned.isOwned());
                assertTrue(fetched.isSame(wrapper));
                assertTrue(list.setInt(2, 7));
                assertFalse(owned.isValid());
                assertFalse(fetched.isValid());
                assertFalse(wrapper.isValid());
                assertTrue(wrapper.setString("reused"));
                assertTrue(wrapper.isValid());
                assertEquals("reused", wrapper.getString());
            }

            assertFalse(list.remove(99));
            assertEquals(CefValueType.VTYPE_INVALID, list.getType(99));
            assertNull(list.getValue(99));
            assertTrue(list.setSize(1));
            assertEquals(1, list.getSize());
        }
    }
}
