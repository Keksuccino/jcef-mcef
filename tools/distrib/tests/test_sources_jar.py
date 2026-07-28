#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import hashlib
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DISTRIB_ROOT = REPOSITORY_ROOT / 'tools' / 'distrib'
if str(DISTRIB_ROOT) not in sys.path:
  sys.path.insert(0, str(DISTRIB_ROOT))

from sources_jar import ARCHIVE_NAME  # noqa: E402
from sources_jar import DESCRIPTOR_TRAVERSAL_SUPPORTED  # noqa: E402
from sources_jar import FIXED_TIMESTAMP  # noqa: E402
from sources_jar import MAX_ARCHIVE_OVERHEAD  # noqa: E402
from sources_jar import MAX_ARCHIVE_SIZE  # noqa: E402
from sources_jar import MAX_SOURCE_SIZE  # noqa: E402
from sources_jar import MAX_SOURCE_TREE_DEPTH  # noqa: E402
from sources_jar import MAX_TOTAL_SOURCE_SIZE  # noqa: E402
from sources_jar import REGULAR_SOURCE_MODE  # noqa: E402
from sources_jar import SourcesJarError  # noqa: E402
from sources_jar import build_sources_jar  # noqa: E402
from sources_jar import verify_sources_jar  # noqa: E402


@unittest.skipUnless(DESCRIPTOR_TRAVERSAL_SUPPORTED, 'secure descriptor-relative source traversal is unavailable')
class SourcesJarTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)
    self.source_directory = self.root / 'java' / 'org' / 'cef'
    self.source_directory.mkdir(parents=True)
    self.write_source('CefApp.java', b'package org.cef;\nclass CefApp {}\n')
    self.write_source('browser/CefBrowser.java', b'package org.cef.browser;\ninterface CefBrowser {}\n')
    self.write_source('browser/mac/CefBrowserWindowMac.java', b'package org.cef.browser.mac;\nclass CefBrowserWindowMac {}\n')
    self.output = self.root / 'output' / ARCHIVE_NAME

  def tearDown(self):
    self.temporary_directory.cleanup()

  def write_source(self, relative_path, contents):
    path = self.source_directory / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(contents)
    return path

  def expected_members(self):
    return [
        ('org/cef/CefApp.java', (self.source_directory / 'CefApp.java').read_bytes()),
        ('org/cef/browser/CefBrowser.java', (self.source_directory / 'browser' / 'CefBrowser.java').read_bytes()),
        ('org/cef/browser/mac/CefBrowserWindowMac.java', (self.source_directory / 'browser' / 'mac' / 'CefBrowserWindowMac.java').read_bytes()),
    ]

  def write_archive(self, members, compression=zipfile.ZIP_STORED, timestamp=FIXED_TIMESTAMP, mode=REGULAR_SOURCE_MODE):
    self.output.parent.mkdir(parents=True, exist_ok=True)
    with warnings.catch_warnings():
      warnings.simplefilter('ignore', UserWarning)
      with zipfile.ZipFile(self.output, 'w', compression=compression, allowZip64=False) as archive:
        for name, contents in members:
          info = zipfile.ZipInfo(name, date_time=timestamp)
          info.compress_type = compression
          info.create_system = 3
          info.external_attr = mode << 16
          archive.writestr(info, contents, compress_type=compression)

  def assert_verification_fails(self):
    with self.assertRaises(SourcesJarError):
      verify_sources_jar(self.root, self.output)

  def test_build_is_byte_reproducible_and_ide_compatible(self):
    (self.source_directory / 'README.txt').write_text('not a Java source', encoding='utf-8')
    (self.root / 'java' / 'tests').mkdir()
    (self.root / 'java' / 'tests' / 'Example.java').write_text('class Example {}', encoding='utf-8')
    first = self.root / 'first' / ARCHIVE_NAME
    second = self.root / 'second' / ARCHIVE_NAME

    self.assertEqual(3, build_sources_jar(self.root, first))
    for source in self.source_directory.rglob('*.java'):
      os.utime(source, (1_000_000_000, 1_000_000_000))
    self.assertEqual(3, build_sources_jar(self.root, second))

    self.assertEqual(first.read_bytes(), second.read_bytes())
    self.assertEqual('6599eda68d8366c93b8687b6f8cb679a1c1a24d81945369046f0ff197b6f4f62', hashlib.sha256(first.read_bytes()).hexdigest())
    self.assertEqual(3, verify_sources_jar(self.root, first))
    with zipfile.ZipFile(first) as archive:
      entries = archive.infolist()
      self.assertEqual([name for name, _ in self.expected_members()], [entry.filename for entry in entries])
      self.assertTrue(all(entry.filename.startswith('org/cef/') and entry.filename.endswith('.java') for entry in entries))
      self.assertTrue(all(entry.date_time == FIXED_TIMESTAMP for entry in entries))
      self.assertTrue(all(entry.compress_type == zipfile.ZIP_STORED for entry in entries))
      self.assertTrue(all(entry.external_attr >> 16 == REGULAR_SOURCE_MODE for entry in entries))
      self.assertNotIn('META-INF/MANIFEST.MF', archive.namelist())
      self.assertFalse(any(name.startswith('java/') or name.startswith('tests/') or name.endswith('.class') for name in archive.namelist()))

  def test_current_repository_production_sources_build_and_verify(self):
    output = self.root / 'repository' / ARCHIVE_NAME
    expected_count = len(tuple((REPOSITORY_ROOT / 'java' / 'org' / 'cef').rglob('*.java')))

    self.assertGreater(expected_count, 0)
    self.assertEqual(expected_count, build_sources_jar(REPOSITORY_ROOT, output))
    self.assertEqual(expected_count, verify_sources_jar(REPOSITORY_ROOT, output))
    with zipfile.ZipFile(output) as archive:
      self.assertEqual(expected_count, len(archive.infolist()))
      self.assertIn('org/cef/CefApp.java', archive.namelist())
      self.assertIn('org/cef/browser/mac/CefBrowserWindowMac.java', archive.namelist())

  def test_verifier_rejects_membership_content_order_and_metadata_changes(self):
    canonical = self.expected_members()
    cases = (('missing', canonical[:-1], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('extra', canonical + [('org/cef/Extra.java', b'class Extra {}\n')], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('test-source', canonical + [('tests/Example.java', b'class Example {}')], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('unsafe-path', canonical + [('../Escape.java', b'class Escape {}')], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('backslash', canonical + [('org\\cef\\Escape.java', b'class Escape {}')], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('wrong-order', tuple(reversed(canonical)), zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('duplicate', canonical + [canonical[0]], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('modified', [(canonical[0][0], b'changed')] + canonical[1:], zipfile.ZIP_STORED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('compressed', canonical, zipfile.ZIP_DEFLATED, FIXED_TIMESTAMP, REGULAR_SOURCE_MODE), ('timestamp', canonical, zipfile.ZIP_STORED, (2026, 1, 1, 0, 0, 0), REGULAR_SOURCE_MODE), ('permissions', canonical, zipfile.ZIP_STORED, FIXED_TIMESTAMP, stat.S_IFREG | 0o600),)
    for name, members, compression, timestamp, mode in cases:
      with self.subTest(name=name):
        self.write_archive(members, compression, timestamp, mode)
        self.assert_verification_fails()

  def test_verifier_rejects_malformed_oversized_and_noncanonical_containers(self):
    self.output.parent.mkdir(parents=True)
    self.output.write_bytes(b'not a ZIP archive')
    self.assert_verification_fails()

    build_sources_jar(self.root, self.output)
    with self.output.open('ab') as stream:
      stream.write(b'trailing bytes')
    self.assert_verification_fails()

    with self.output.open('wb') as stream:
      stream.truncate(MAX_ARCHIVE_SIZE + 1)
    self.assert_verification_fails()

  def test_noncanonical_bytes_are_rejected_before_the_zip_parser_runs(self):
    self.output.parent.mkdir(parents=True)
    self.output.write_bytes(b'PK' + b'noncanonical central directory bytes')
    real_zip_file = zipfile.ZipFile

    def guarded_zip_file(file, *arguments, **keywords):
      mode = keywords.get('mode', arguments[0] if arguments else 'r')
      if mode == 'r':
        self.fail('untrusted noncanonical bytes reached ZipFile')
      return real_zip_file(file, *arguments, **keywords)

    with mock.patch('sources_jar.zipfile.ZipFile', guarded_zip_file):
      with self.assertRaisesRegex(SourcesJarError, 'deterministic archive format'):
        verify_sources_jar(self.root, self.output)

  def test_source_file_symlink_and_fifo_replacement_races_are_rejected(self):
    victim = self.source_directory / 'CefApp.java'
    outside = self.root / 'outside.java'
    outside.write_bytes(b'x' * victim.stat().st_size)
    real_open = os.open
    raced = False

    def symlink_race(path, flags, *arguments, **keywords):
      nonlocal raced
      if not raced and path == victim.name and keywords.get('dir_fd') is not None:
        raced = True
        victim.unlink()
        victim.symlink_to(outside)
      return real_open(path, flags, *arguments, **keywords)

    with mock.patch('sources_jar.os.open', symlink_race):
      with self.assertRaises(SourcesJarError):
        build_sources_jar(self.root, self.output)
    self.assertTrue(raced)

    victim.unlink()
    victim.write_bytes(b'package org.cef;\nclass CefApp {}\n')
    if not hasattr(os, 'mkfifo') or not hasattr(os, 'O_NONBLOCK'):
      return
    raced = False

    def fifo_race(path, flags, *arguments, **keywords):
      nonlocal raced
      if not raced and path == victim.name and keywords.get('dir_fd') is not None:
        raced = True
        victim.unlink()
        os.mkfifo(victim)
        self.assertTrue(flags & os.O_NONBLOCK)
      return real_open(path, flags, *arguments, **keywords)

    with mock.patch('sources_jar.os.open', fifo_race):
      with self.assertRaises(SourcesJarError):
        build_sources_jar(self.root, self.output)
    self.assertTrue(raced)

  def test_source_directory_symlink_replacement_race_is_rejected(self):
    if not DESCRIPTOR_TRAVERSAL_SUPPORTED:
      self.skipTest('descriptor-relative source traversal is unavailable')
    outside_directory = self.root / 'outside-sources'
    outside_directory.mkdir()
    (outside_directory / 'Injected.java').write_bytes(b'package injected; class Injected {}\n')
    original_directory = self.source_directory.with_name('cef-original')
    real_open = os.open
    raced = False

    def directory_race(path, flags, *arguments, **keywords):
      nonlocal raced
      if not raced and path == 'cef' and keywords.get('dir_fd') is not None and flags & os.O_DIRECTORY:
        raced = True
        self.source_directory.rename(original_directory)
        self.source_directory.symlink_to(outside_directory, target_is_directory=True)
      return real_open(path, flags, *arguments, **keywords)

    with mock.patch('sources_jar.os.open', directory_race):
      with self.assertRaises(SourcesJarError):
        build_sources_jar(self.root, self.output)
    self.assertTrue(raced)
    self.assertFalse(self.output.exists())

  def test_secure_temporary_descriptor_prevents_symlink_clobbering(self):
    outside = self.root / 'outside'
    outside.write_bytes(b'must remain unchanged')
    real_mkstemp = tempfile.mkstemp

    def raced_mkstemp(*arguments, **keywords):
      descriptor, name = real_mkstemp(*arguments, **keywords)
      temporary_path = Path(name)
      temporary_path.unlink()
      temporary_path.symlink_to(outside)
      return descriptor, name

    with mock.patch('sources_jar.tempfile.mkstemp', raced_mkstemp):
      with self.assertRaisesRegex(SourcesJarError, 'Temporary sources JAR changed'):
        build_sources_jar(self.root, self.output)
    self.assertEqual(b'must remain unchanged', outside.read_bytes())

  def test_builder_does_not_require_unix_fchmod(self):
    with mock.patch.object(os, 'fchmod', None, create=True):
      self.assertEqual(3, build_sources_jar(self.root, self.output))
    self.assertEqual(3, verify_sources_jar(self.root, self.output))

  def test_source_and_archive_limits_are_internally_consistent(self):
    self.assertEqual(MAX_ARCHIVE_SIZE, MAX_TOTAL_SOURCE_SIZE + MAX_ARCHIVE_OVERHEAD)

  def test_source_tree_depth_and_total_entries_are_bounded(self):
    directory = self.source_directory
    for index in range(MAX_SOURCE_TREE_DEPTH + 1):
      directory = directory / 'depth-{}'.format(index)
      directory.mkdir()
    with self.assertRaisesRegex(SourcesJarError, 'directory-depth limit'):
      build_sources_jar(self.root, self.output)

    with mock.patch('sources_jar.MAX_SOURCE_TREE_DEPTH', MAX_SOURCE_TREE_DEPTH + 2):
      with mock.patch('sources_jar.MAX_SOURCE_TREE_ENTRY_COUNT', 3):
        with self.assertRaisesRegex(SourcesJarError, 'entry-count limit'):
          build_sources_jar(self.root, self.output)

  def test_empty_oversized_symlinked_and_case_colliding_sources_are_rejected(self):
    empty_root = self.root / 'empty'
    (empty_root / 'java' / 'org' / 'cef').mkdir(parents=True)
    with self.assertRaises(SourcesJarError):
      build_sources_jar(empty_root, empty_root / ARCHIVE_NAME)

    oversized_root = self.root / 'oversized'
    oversized_source = oversized_root / 'java' / 'org' / 'cef' / 'Huge.java'
    oversized_source.parent.mkdir(parents=True)
    with oversized_source.open('wb') as stream:
      stream.truncate(MAX_SOURCE_SIZE + 1)
    with self.assertRaises(SourcesJarError):
      build_sources_jar(oversized_root, oversized_root / ARCHIVE_NAME)

    original_source = self.source_directory / 'CefApp.java'
    original_contents = original_source.read_bytes()
    case_collision = self.write_source('cefapp.java', b'package org.cef; class cefapp {}\n')
    if case_collision.samefile(original_source):
      original_source.write_bytes(original_contents)
    else:
      with self.assertRaises(SourcesJarError):
        build_sources_jar(self.root, self.output)
      case_collision.unlink()

    symlink = self.source_directory / 'Linked.java'
    try:
      symlink.symlink_to(self.source_directory / 'CefApp.java')
    except (NotImplementedError, OSError):
      self.skipTest('symbolic links are unavailable')
    with self.assertRaises(SourcesJarError):
      build_sources_jar(self.root, self.output)

  def test_source_traversal_errors_are_not_silently_ignored(self):
    with mock.patch('sources_jar.os.scandir', side_effect=PermissionError('unreadable nested source directory')):
      with self.assertRaisesRegex(SourcesJarError, 'Unable to traverse'):
        build_sources_jar(self.root, self.output)

  def test_builder_and_verifier_enforce_the_release_filename(self):
    with self.assertRaises(SourcesJarError):
      build_sources_jar(self.root, self.root / 'sources.jar')


class SourcesJarUnsupportedPlatformTest(unittest.TestCase):

  def test_builder_and_verifier_fail_closed_without_descriptor_traversal(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      source = root / 'java' / 'org' / 'cef' / 'CefApp.java'
      source.parent.mkdir(parents=True)
      source.write_bytes(b'package org.cef; class CefApp {}\n')
      archive = root / ARCHIVE_NAME
      with mock.patch('sources_jar.DESCRIPTOR_TRAVERSAL_SUPPORTED', False):
        with self.assertRaisesRegex(SourcesJarError, 'unavailable on this platform'):
          build_sources_jar(root, archive)
        archive.write_bytes(b'not a JAR')
        with self.assertRaisesRegex(SourcesJarError, 'unavailable on this platform'):
          verify_sources_jar(root, archive)


if __name__ == '__main__':
  unittest.main()
