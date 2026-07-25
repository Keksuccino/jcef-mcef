#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

from pathlib import Path
import sys
import tempfile
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPOSITORY_ROOT / 'tools'))

from make_jni_headers import normalized_generated_header  # noqa: E402
from make_jni_headers import normalized_header  # noqa: E402


class NormalizedJniHeaderTest(unittest.TestCase):

  def normalize(self, contents, javac_generated=False):
    with tempfile.TemporaryDirectory() as temporary_directory:
      header = Path(temporary_directory) / 'Generated.h'
      header.write_bytes(contents)
      normalizer = (normalized_generated_header
                    if javac_generated else normalized_header)
      return normalizer(header)

  def test_windows_long_suffix_and_crlf_match_portable_header(self):
    windows_header = (
        b'#define org_cef_CefApp_POSITIVE 30i64\r\n'
        b'#define org_cef_CefApp_NEGATIVE -9223372036854775808i64\r\n')
    portable_header = (
        b'#define org_cef_CefApp_POSITIVE 30LL\n'
        b'#define org_cef_CefApp_NEGATIVE -9223372036854775808LL\n')
    normalized_portable = self.normalize(portable_header)
    self.assertEqual(normalized_portable,
                     self.normalize(windows_header, javac_generated=True))
    self.assertNotEqual(normalized_portable, self.normalize(windows_header))

  def test_non_javac_long_suffix_occurrences_remain_byte_exact(self):
    unrelated_content = (b'jlong value = 30i64;\r\n'
                         b'#define HEX_VALUE 0x1ei64\r\n'
                         b'#define EXPRESSION (30i64)\r\n'
                         b'#define TRAILING_COMMENT 30i64 /* keep */\r\n'
                         b' #define INDENTED 30i64\r\n')
    expected = unrelated_content.replace(b'\r\n', b'\n')
    self.assertEqual(expected,
                     self.normalize(unrelated_content, javac_generated=True))

  def test_different_long_constant_values_remain_distinct(self):
    expected = self.normalize(b'#define org_cef_CefApp_TIMEOUT 30LL\n')
    actual = self.normalize(
        b'#define org_cef_CefApp_TIMEOUT 31i64\r\n', javac_generated=True)
    self.assertNotEqual(expected, actual)


if __name__ == '__main__':
  unittest.main()
