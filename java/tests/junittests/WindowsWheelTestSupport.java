// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Describes whether ambient Windows wheel settings allow live test events to be delivered. */
final class WindowsWheelTestSupport {
    private static final String DESKTOP_REGISTRY_KEY = "HKCU\\Control Panel\\Desktop";
    private static final String VERTICAL_SCROLL_VALUE = "WheelScrollLines";
    private static final String HORIZONTAL_SCROLL_VALUE = "WheelScrollChars";
    private static final long WINDOWS_PAGE_SCROLL = 0xFFFFFFFFL;
    private static final long REGISTRY_QUERY_TIMEOUT_SECONDS = 2L;

    record Delivery(boolean verticalExpected, boolean horizontalExpected) {}

    private WindowsWheelTestSupport() {}

    static Delivery detectDelivery() {
        if (!OS.isWindows()) return new Delivery(true, true);
        return new Delivery(expectsDelivery(queryScrollUnits(VERTICAL_SCROLL_VALUE)), expectsDelivery(queryScrollUnits(HORIZONTAL_SCROLL_VALUE)));
    }

    static OptionalLong parseScrollUnits(String output, String valueName) {
        if (output == null || valueName == null) return OptionalLong.empty();
        Pattern valuePattern = Pattern.compile("(?im)^\\s*" + Pattern.quote(valueName) + "\\s+REG_(?:SZ|DWORD)\\s+(\\S+)\\s*$");
        Matcher match = valuePattern.matcher(output);
        if (!match.find()) return OptionalLong.empty();
        String value = match.group(1);
        try {
            long units;
            if (value.startsWith("0x") || value.startsWith("0X")) {
                units = Long.parseUnsignedLong(value.substring(2), 16);
            } else {
                units = Long.parseLong(value);
                if (units == -1L) units = WINDOWS_PAGE_SCROLL;
            }
            return units >= 0L && units <= WINDOWS_PAGE_SCROLL ? OptionalLong.of(units) : OptionalLong.empty();
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    static boolean expectsDelivery(OptionalLong scrollUnits) {
        if (scrollUnits.isEmpty()) return true;
        long units = scrollUnits.getAsLong();
        return units != 0L && units != WINDOWS_PAGE_SCROLL;
    }

    private static OptionalLong queryScrollUnits(String valueName) {
        Process process = null;
        try {
            process = new ProcessBuilder("reg.exe", "query", DESKTOP_REGISTRY_KEY, "/v", valueName).redirectErrorStream(true).start();
            if (!process.waitFor(REGISTRY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(REGISTRY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return OptionalLong.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? parseScrollUnits(output, valueName) : OptionalLong.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return OptionalLong.empty();
        } catch (IOException | SecurityException ignored) {
            return OptionalLong.empty();
        }
    }
}
