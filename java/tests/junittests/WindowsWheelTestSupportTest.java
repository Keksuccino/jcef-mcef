// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

class WindowsWheelTestSupportTest {
    @Test
    void parsesStringAndDwordRegistryValues() {
        assertEquals(OptionalLong.of(3L), WindowsWheelTestSupport.parseScrollUnits("HKEY_CURRENT_USER\\Control Panel\\Desktop\r\n    WheelScrollLines    REG_SZ    3\r\n", "WheelScrollLines"));
        assertEquals(OptionalLong.of(10L), WindowsWheelTestSupport.parseScrollUnits("HKEY_CURRENT_USER\\Control Panel\\Desktop\r\n    WheelScrollChars    REG_DWORD    0x0000000a\r\n", "WheelScrollChars"));
        assertEquals(OptionalLong.of(0xFFFFFFFFL), WindowsWheelTestSupport.parseScrollUnits("    WheelScrollLines    REG_SZ    -1\r\n", "WheelScrollLines"));
        assertEquals(OptionalLong.of(0xFFFFFFFFL), WindowsWheelTestSupport.parseScrollUnits("    WheelScrollLines    REG_SZ    4294967295\r\n", "WheelScrollLines"));
        assertEquals(OptionalLong.of(0xFFFFFFFFL), WindowsWheelTestSupport.parseScrollUnits("    WheelScrollLines    REG_DWORD    0xffffffff\r\n", "WheelScrollLines"));
    }

    @Test
    void rejectsMissingMalformedAndOutOfRangeRegistryValues() {
        assertTrue(WindowsWheelTestSupport.parseScrollUnits("", "WheelScrollLines").isEmpty());
        assertTrue(WindowsWheelTestSupport.parseScrollUnits("    WheelScrollLines    REG_SZ    invalid\r\n", "WheelScrollLines").isEmpty());
        assertTrue(WindowsWheelTestSupport.parseScrollUnits("    WheelScrollLines    REG_SZ    4294967296\r\n", "WheelScrollLines").isEmpty());
        assertTrue(WindowsWheelTestSupport.parseScrollUnits("    OtherValue    REG_SZ    3\r\n", "WheelScrollLines").isEmpty());
    }

    @Test
    void requiresDeliveryUnlessTheSettingExplicitlyDisablesOrUsesPageScroll() {
        assertTrue(WindowsWheelTestSupport.expectsDelivery(OptionalLong.empty()));
        assertTrue(WindowsWheelTestSupport.expectsDelivery(OptionalLong.of(1L)));
        assertTrue(WindowsWheelTestSupport.expectsDelivery(OptionalLong.of(0xFFFFFFFEL)));
        assertFalse(WindowsWheelTestSupport.expectsDelivery(OptionalLong.of(0L)));
        assertFalse(WindowsWheelTestSupport.expectsDelivery(OptionalLong.of(0xFFFFFFFFL)));
    }
}
