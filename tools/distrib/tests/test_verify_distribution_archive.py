#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import copy
import gzip
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

DISTRIB_ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = Path(__file__).resolve().parent
if str(DISTRIB_ROOT) not in sys.path:
  sys.path.insert(0, str(DISTRIB_ROOT))
if str(TEST_ROOT) not in sys.path:
  sys.path.insert(0, str(TEST_ROOT))

from distribution_archive_test_util import build_tar_gz  # noqa: E402
from distribution_archive_test_util import build_valid_archive  # noqa: E402
from distribution_archive_test_util import canonical_jar_files  # noqa: E402
from distribution_archive_test_util import canonical_manifest  # noqa: E402
from distribution_archive_test_util import canonical_members  # noqa: E402
from distribution_archive_test_util import canonical_runtime_files  # noqa: E402
from distribution_archive_test_util import canonical_static_files  # noqa: E402
from distribution_archive_test_util import directory_member  # noqa: E402
from distribution_archive_test_util import file_member  # noqa: E402
from distribution_archive_test_util import special_member  # noqa: E402
from distribution_archive_test_util import TEST_COMMIT  # noqa: E402
from verify_distribution_archive import MANIFEST_NAME  # noqa: E402
from verify_distribution_archive import JOGAMP_LICENSE_FILES  # noqa: E402
from verify_distribution_archive import OPTIONAL_RUNTIME_ENTRIES  # noqa: E402
from verify_distribution_archive import TARGET_JOGAMP_JARS  # noqa: E402
from verify_distribution_archive import TARGET_REQUIRED_RUNTIME_FILES  # noqa: E402
from verify_distribution_archive import TARGET_RUNTIME_ENTRIES  # noqa: E402
from verify_distribution_archive import TARGET_TOP_LEVEL_DIRECTORIES  # noqa: E402
from verify_distribution_archive import TARGET_TOP_LEVEL_FILES  # noqa: E402
from verify_distribution_archive import VerificationError  # noqa: E402
from verify_distribution_archive import VerificationLimits  # noqa: E402
from verify_distribution_archive import _stable_file_status_fields  # noqa: E402
from verify_distribution_archive import verify_distribution_archive  # noqa: E402

TARGETS = tuple(TARGET_RUNTIME_ENTRIES)


def _gzip_bytes(contents):
  output = io.BytesIO()
  with gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as stream:
    stream.write(contents)
  return output.getvalue()


def _pax_record(key, value):
  body = '{}={}\n'.format(key, value).encode('utf-8')
  length = len(body) + 2
  while True:
    record = str(length).encode('ascii') + b' ' + body
    if len(record) == length:
      return record
    length = len(record)


def _control_header(member_type, contents=b'', name='././@PaxHeader', declared_size=None):
  member = tarfile.TarInfo(name)
  member.type = member_type
  member.size = len(contents) if declared_size is None else declared_size
  header = member.tobuf(format=tarfile.USTAR_FORMAT)
  padding = b'\0' * ((-len(contents)) % tarfile.BLOCKSIZE)
  return header + contents + padding


def _replace_first_tar_header_field(contents, start, value):
  # Raw ustar field replacement is intentional: TarInfo normalizes unsupported
  # high mode bits and ignores device numbers for non-device members when it
  # serializes. Recompute the first header checksum after changing those bytes.
  raw_tar = bytearray(gzip.decompress(contents))
  raw_tar[start:start + len(value)] = value
  raw_tar[148:156] = b'        '
  checksum = sum(raw_tar[:tarfile.BLOCKSIZE])
  raw_tar[148:156] = ('{:06o}\0 '.format(checksum)).encode('ascii')
  return _gzip_bytes(bytes(raw_tar))


def _first_tar_zero_offset(raw_tar):
  last_member_end = 0
  with tarfile.open(fileobj=io.BytesIO(raw_tar), mode='r:') as archive:
    for member in archive:
      padded_size = ((member.size + tarfile.BLOCKSIZE - 1) // tarfile.BLOCKSIZE) * tarfile.BLOCKSIZE
      last_member_end = max(last_member_end, member.offset_data + padded_size)
  return last_member_end


class VerifyDistributionArchiveTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)

  def tearDown(self):
    self.temporary_directory.cleanup()

  def archive_path(self, target='linux_amd64'):
    return self.root / '{}.tar.gz'.format(target)

  def verify_bytes(self, contents, target='linux_amd64', commit=TEST_COMMIT, limits=None):
    path = self.archive_path(target)
    path.write_bytes(contents)
    verify_distribution_archive(path, target, commit, limits)
    return path

  def assert_rejected(self, contents, pattern=None, target='linux_amd64', commit=TEST_COMMIT, limits=None):
    with self.assertRaisesRegex(VerificationError, pattern or '.'):
      self.verify_bytes(contents, target, commit, limits)

  def archive_with_manifest(self, target='linux_amd64', mutate=None, manifest_bytes=None, runtime_files=None, jar_files=None, static_files=None):
    manifest = canonical_manifest(target, TEST_COMMIT, runtime_files, jar_files, static_files)
    if mutate is not None:
      mutate(manifest)
    members = canonical_members(target, TEST_COMMIT, manifest, manifest_bytes, runtime_files, jar_files, static_files)
    return build_tar_gz(members)

  def test_valid_complete_contract_archive_for_every_publication_target(self):
    for target in TARGETS:
      with self.subTest(target=target):
        self.verify_bytes(build_valid_archive(target), target)

  def test_file_status_comparison_uses_portable_windows_fields(self):
    self.assertEqual(('st_size', 'st_mtime_ns'), _stable_file_status_fields('nt'))
    self.assertEqual(('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns'), _stable_file_status_fields('posix'))

  def test_independent_six_target_runtime_and_jogamp_contract(self):
    common = {
        'chrome_100_percent.pak', 'chrome_200_percent.pak', 'icudtl.dat',
        'locales', 'resources.pak', 'v8_context_snapshot.bin'
    }
    linux = common | {
        'chrome-sandbox', 'jcef_helper', 'libEGL.so', 'libGLESv2.so',
        'libcef.so', 'libjcef.so', 'libvk_swiftshader.so', 'libvulkan.so.1',
        'vk_swiftshader_icd.json'
    }
    windows = common | {
        'chrome_elf.dll', 'd3dcompiler_47.dll', 'jcef.dll', 'jcef_helper.exe',
        'libEGL.dll', 'libGLESv2.dll', 'libcef.dll', 'vk_swiftshader.dll',
        'vk_swiftshader_icd.json', 'vulkan-1.dll'
    }
    expected_entries = {
        'linux_amd64':
            tuple(sorted(linux)),
        'linux_arm64':
            tuple(sorted(linux)),
        'macos_amd64': ('jcef_app.app',),
        'macos_arm64': ('jcef_app.app',),
        'windows_amd64':
            tuple(sorted(windows | {'dxcompiler.dll', 'dxil.dll'})),
        'windows_arm64':
            tuple(sorted(windows)),
    }
    self.assertEqual(expected_entries, TARGET_RUNTIME_ENTRIES)
    self.assertNotIn('dxcompiler.dll', TARGET_RUNTIME_ENTRIES['windows_arm64'])
    self.assertNotIn('dxil.dll', TARGET_RUNTIME_ENTRIES['windows_arm64'])

    expected_jogamp = {
        'linux_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                        'gluegen-rt-natives-linux-amd64.jar',
                        'jogl-all-natives-linux-amd64.jar'),
        'linux_arm64': ('gluegen-rt.jar', 'jogl-all.jar',
                        'gluegen-rt-natives-linux-aarch64.jar',
                        'jogl-all-natives-linux-aarch64.jar'),
        'macos_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                        'gluegen-rt-natives-macosx-universal.jar',
                        'jogl-all-natives-macosx-universal.jar'),
        'macos_arm64': ('gluegen-rt.jar', 'jogl-all.jar',
                        'gluegen-rt-natives-macosx-universal.jar',
                        'jogl-all-natives-macosx-universal.jar'),
        'windows_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                          'gluegen-rt-natives-windows-amd64.jar',
                          'jogl-all-natives-windows-amd64.jar'),
        'windows_arm64': (),
    }
    self.assertEqual(expected_jogamp, TARGET_JOGAMP_JARS)

    expected_directories = {
        'linux_amd64': {'docs', 'locales', 'tests'},
        'linux_arm64': {'docs', 'locales', 'tests'},
        'macos_amd64': {'docs', 'jcef_app.app', 'tests'},
        'macos_arm64': {'docs', 'jcef_app.app', 'tests'},
        'windows_amd64': {'docs', 'locales', 'tests'},
        'windows_arm64': {'docs', 'locales', 'tests'},
    }
    expected_checks = {
        'linux_amd64': 'java17_check.sh',
        'linux_arm64': 'java17_check.sh',
        'macos_amd64': 'java17_check.sh',
        'macos_arm64': 'java17_check.sh',
        'windows_amd64': 'java17_check.bat',
        'windows_arm64': 'java17_check.bat',
    }
    expected_launchers = {
        'linux_amd64': {'compile.sh', 'run.sh'},
        'linux_arm64': {'compile.sh', 'run.sh'},
        'macos_amd64': {'compile.sh'},
        'macos_arm64': {'compile.sh'},
        'windows_amd64': {'compile.bat', 'run.bat'},
        'windows_arm64': {'compile.bat', 'run.bat'},
    }
    common_files = {'CEF-LICENSE.txt', 'CREDITS.html', MANIFEST_NAME, 'LICENSE.txt', 'README.txt', 'jcef.jar', 'jcef-tests.jar'}
    for target in TARGETS:
      expected_files = set(expected_entries[target]) - expected_directories[target]
      expected_files.update(common_files)
      expected_files.update(expected_jogamp[target])
      expected_files.update(expected_launchers[target])
      expected_files.add(expected_checks[target])
      if expected_jogamp[target]:
        expected_files.update(JOGAMP_LICENSE_FILES)
      self.assertEqual(expected_files, set(TARGET_TOP_LEVEL_FILES[target]), target)
      self.assertEqual(expected_directories[target], set(TARGET_TOP_LEVEL_DIRECTORIES[target]), target)
      if target.startswith(('linux_', 'windows_')):
        self.assertIn('locales/en-US.pak', TARGET_REQUIRED_RUNTIME_FILES[target])

    helper_names = ('jcef Helper', 'jcef Helper (Alerts)', 'jcef Helper (GPU)',
                    'jcef Helper (Plugin)', 'jcef Helper (Renderer)')
    for target, architecture in (('macos_amd64', 'x86_64'), ('macos_arm64',
                                                             'arm64')):
      app = 'jcef_app.app/Contents'
      framework = app + '/Frameworks/Chromium Embedded Framework.framework'
      expected_mac = {
          app + '/Info.plist',
          app + '/Java/libjcef.dylib',
          app + '/MacOS/JavaAppLauncher',
          app + '/_CodeSignature/CodeResources',
          framework + '/Chromium Embedded Framework',
          framework + '/_CodeSignature/CodeResources',
          framework + '/Libraries/libEGL.dylib',
          framework + '/Libraries/libGLESv2.dylib',
          framework + '/Libraries/libvk_swiftshader.dylib',
          framework + '/Libraries/vk_swiftshader_icd.json',
          framework + '/Resources/Info.plist',
          framework + '/Resources/chrome_100_percent.pak',
          framework + '/Resources/chrome_200_percent.pak',
          framework + '/Resources/en.lproj/locale.pak',
          framework + '/Resources/icudtl.dat',
          framework + '/Resources/resources.pak',
          framework +
          '/Resources/v8_context_snapshot.{}.bin'.format(architecture),
      }
      for helper in helper_names:
        helper_contents = '{}/Frameworks/{}.app/Contents'.format(app, helper)
        expected_mac.update((helper_contents + '/Info.plist', helper_contents + '/MacOS/' + helper, helper_contents + '/_CodeSignature/CodeResources'))
      expected_mac.update((app + '/Java/' + jar_name for jar_name in ('jcef.jar', 'jcef-tests.jar') + expected_jogamp[target]))
      self.assertEqual(expected_mac, set(TARGET_REQUIRED_RUNTIME_FILES[target]))

  def test_optional_linux_minigbm_is_valid_only_when_declared_and_inventoried(self):
    target = 'linux_arm64'
    runtime_files = canonical_runtime_files(target)
    runtime_files['libminigbm.so'] = b'minigbm'
    manifest = canonical_manifest(target, TEST_COMMIT, runtime_files)
    manifest['runtime_entries'] = sorted(manifest['runtime_entries'] + ['libminigbm.so'])
    contents = build_tar_gz(canonical_members(target, TEST_COMMIT, manifest, runtime_files=runtime_files))
    self.verify_bytes(contents, target)

    manifest['runtime_entries'].remove('libminigbm.so')
    self.assert_rejected(build_tar_gz(canonical_members(target, TEST_COMMIT, manifest, runtime_files=runtime_files)), 'Optional runtime entry presence mismatch', target)

  def test_archive_filename_target_and_commit_are_exact(self):
    contents = build_valid_archive('linux_amd64')
    wrong_path = self.root / 'renamed.tar.gz'
    wrong_path.write_bytes(contents)
    with self.assertRaisesRegex(VerificationError, 'filename'):
      verify_distribution_archive(wrong_path, 'linux_amd64', TEST_COMMIT)
    self.assert_rejected(contents, 'publication commit', commit='f' * 40)
    with self.assertRaisesRegex(VerificationError, 'lowercase hexadecimal'):
      verify_distribution_archive(self.archive_path(), 'linux_amd64', TEST_COMMIT.upper())

  def test_archive_input_must_be_regular_nonlink_and_bounded(self):
    path = self.archive_path()
    path.symlink_to(self.root / 'missing')
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(path, 'linux_amd64', TEST_COMMIT)
    path.unlink()
    path.mkdir()
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(path, 'linux_amd64', TEST_COMMIT)
    path.rmdir()
    contents = build_valid_archive('linux_amd64')
    self.assert_rejected(contents, 'compressed size', limits=VerificationLimits(max_archive_bytes=len(contents) - 1))

  def test_standalone_jcef_jar_must_exactly_match_packaged_jar(self):
    target = 'linux_amd64'
    archive_path = self.verify_bytes(build_valid_archive(target), target)
    standalone_path = self.root / 'jcef-rinku.jar'
    standalone_path.write_bytes(canonical_jar_files(target)['jcef.jar'])
    verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)

    packaged_jar = canonical_jar_files(target)['jcef.jar']
    mismatches = (b'different Java classes', bytes([packaged_jar[0] ^ 1]) + packaged_jar[1:])
    for mismatch in mismatches:
      with self.subTest(size=len(mismatch)):
        standalone_path.write_bytes(mismatch)
        with self.assertRaisesRegex(VerificationError, 'does not byte-match'):
          verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)

  def test_standalone_jcef_jar_requires_canonical_regular_file(self):
    target = 'linux_amd64'
    archive_path = self.verify_bytes(build_valid_archive(target), target)
    packaged_jar = canonical_jar_files(target)['jcef.jar']
    wrong_name = self.root / 'jcef.jar'
    wrong_name.write_bytes(packaged_jar)
    with self.assertRaisesRegex(VerificationError, 'filename'):
      verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=wrong_name)

    standalone_path = self.root / 'jcef-rinku.jar'
    standalone_path.symlink_to(wrong_name)
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)
    standalone_path.unlink()
    standalone_path.mkdir()
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)
    standalone_path.rmdir()
    standalone_path.write_bytes(b'')
    with self.assertRaisesRegex(VerificationError, 'empty'):
      verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)

  @unittest.skipUnless(hasattr(os, 'mkfifo'), 'FIFO creation requires POSIX')
  def test_standalone_jcef_jar_fifo_is_rejected_without_opening(self):
    target = 'linux_amd64'
    archive_path = self.verify_bytes(build_valid_archive(target), target)
    standalone_path = self.root / 'jcef-rinku.jar'
    os.mkfifo(str(standalone_path))
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(archive_path, target, TEST_COMMIT, standalone_jcef_jar=standalone_path)

  @unittest.skipUnless(hasattr(os, 'mkfifo'), 'FIFO creation requires POSIX')
  def test_fifo_archive_input_is_rejected_without_opening(self):
    path = self.archive_path()
    os.mkfifo(str(path))
    with self.assertRaisesRegex(VerificationError, 'regular non-link'):
      verify_distribution_archive(path, 'linux_amd64', TEST_COMMIT)

  def test_duplicate_safe_manifest_rejects_duplicate_keys_and_unsupported_numbers(self):
    duplicate = b'{"target":"linux_amd64","target":"linux_amd64"}'
    self.assert_rejected(self.archive_with_manifest(manifest_bytes=duplicate), 'duplicate object key')
    for value in ('1.0', 'NaN', '123456789012345678901'):
      with self.subTest(value=value):
        manifest_bytes = ('{"manifest_schema":' + value + '}').encode('ascii')
        self.assert_rejected(self.archive_with_manifest(manifest_bytes=manifest_bytes), 'Manifest')

  def test_manifest_must_be_nonempty_bounded_utf8_json(self):
    cases = ((b'', 'empty'), (b'\xff', 'UTF-8'), (b'\xef\xbb\xbf{}', 'JSON'),
             (b'{', 'JSON'))
    for contents, pattern in cases:
      with self.subTest(contents=contents):
        self.assert_rejected(self.archive_with_manifest(manifest_bytes=contents), pattern)
    valid = self.archive_with_manifest()
    manifest_size = next((len(member['contents']) for member in canonical_members('linux_amd64') if member['name'].endswith('/' + MANIFEST_NAME)))
    self.verify_bytes(valid, limits=VerificationLimits(max_manifest_bytes=manifest_size))
    self.assert_rejected(valid, 'Manifest size', limits=VerificationLimits(max_manifest_bytes=manifest_size - 1))

  def test_manifest_requires_exact_schema_types_versions_and_fields(self):
    mutations = (
        lambda value: value.__setitem__('manifest_schema', True),
        lambda value: value.__setitem__('manifest_schema', 3),
        lambda value: value.__setitem__('java_release', True),
        lambda value: value.__setitem__('java_release', 21),
        lambda value: value.__setitem__('cef_api_version', 15100),
        lambda value: value.__setitem__('cef_version', 'wrong'),
        lambda value: value.__setitem__('target', 'linux_arm64'),
        lambda value: value.__setitem__('archive_root', 'other'),
        lambda value: value.__setitem__('java_cef_commit', TEST_COMMIT.upper()),
        lambda value: value.__setitem__('extra', None),
        lambda value: value.pop('jcef_jars'),)
    for index, mutation in enumerate(mutations):
      with self.subTest(case=index):
        self.assert_rejected(self.archive_with_manifest(mutate=mutation), 'Manifest')

  def test_complete_distribution_inventory_is_sorted_bounded_and_self_excluding(self):
    target = 'linux_amd64'
    manifest = canonical_manifest(target)
    file_paths = [item['path'] for item in manifest['distribution_files']]
    self.assertEqual(sorted(file_paths), file_paths)
    self.assertNotIn(MANIFEST_NAME, file_paths)
    self.assertEqual(sorted(manifest['distribution_directories']), manifest['distribution_directories'])

    def extra_file_key(value):
      value['distribution_files'][0]['extra'] = None

    def negative_file_size(value):
      value['distribution_files'][0]['size'] = -1

    def uppercase_file_hash(value):
      value['distribution_files'][0]['sha256'] = 'F' * 64

    def duplicate_directory(value):
      value['distribution_directories'].append(value['distribution_directories'][-1])

    mutations = (
        lambda value: value.__setitem__('distribution_files', list(reversed(value['distribution_files']))),
        lambda value: value.__setitem__('distribution_files', 'files'),
        lambda value: value['distribution_files'].pop(),
        lambda value: value['distribution_files'].append({'path': 'missing.bin', 'sha256': '0' * 64, 'size': 1}),
        lambda value: value['distribution_files'].append({'path': MANIFEST_NAME, 'sha256': '0' * 64, 'size': 1}),
        extra_file_key,
        negative_file_size,
        uppercase_file_hash,
        lambda value: value.__setitem__('distribution_directories', list(reversed(value['distribution_directories']))),
        lambda value: value.__setitem__('distribution_directories', 'directories'),
        lambda value: value['distribution_directories'].pop(),
        duplicate_directory,
    )
    for index, mutation in enumerate(mutations):
      with self.subTest(case=index):
        self.assert_rejected(self.archive_with_manifest(target, mutate=mutation), 'distribution|Distribution')

    def add_case_collision(value):
      item = copy.deepcopy(next(item for item in value['distribution_files'] if item['path'] == 'README.txt'))
      item['path'] = 'readme.TXT'
      value['distribution_files'].append(item)
      value['distribution_files'].sort(key=lambda entry: entry['path'])

    self.assert_rejected(self.archive_with_manifest(target, mutate=add_case_collision), 'case collision')

  def test_distribution_inventory_rejects_unlisted_missing_and_tampered_members(self):
    target = 'linux_amd64'
    members = canonical_members(target)
    members.append(file_member(target + '/docs/unlisted.bin', b'unlisted'))
    self.assert_rejected(build_tar_gz(members), 'absent from distribution inventory', target)

    members = [member for member in canonical_members(target) if member['name'] != target + '/README.txt']
    self.assert_rejected(build_tar_gz(members), 'missing distribution inventory member', target)

    members = canonical_members(target)
    readme = next(member for member in members if member['name'] == target + '/README.txt')
    readme['contents'] = b'tampered readme'
    self.assert_rejected(build_tar_gz(members), 'Distribution file byte metadata mismatch', target)

  def test_exact_top_level_contract_rejects_missing_extra_empty_and_wrong_kind(self):
    target = 'windows_arm64'
    static_files = canonical_static_files(target)
    static_files.pop('README.txt')
    self.assert_rejected(self.archive_with_manifest(target, static_files=static_files), 'missing canonical top-level', target)

    static_files = canonical_static_files(target)
    static_files['unexpected.bin'] = b'unexpected'
    self.assert_rejected(self.archive_with_manifest(target, static_files=static_files), 'unexpected top-level', target)

    static_files = canonical_static_files(target)
    static_files['README.txt'] = b''
    self.assert_rejected(self.archive_with_manifest(target, static_files=static_files), 'empty or has wrong kind', target)

    manifest = canonical_manifest(target)
    manifest['distribution_files'] = [item for item in manifest['distribution_files'] if item['path'] != 'README.txt']
    manifest['distribution_directories'] = sorted(manifest['distribution_directories'] + ['README.txt'])
    members = [member for member in canonical_members(target, manifest=manifest) if member['name'] != target + '/README.txt']
    members.append(directory_member(target + '/README.txt'))
    self.assert_rejected(build_tar_gz(members), 'top-level file.*wrong kind', target)

    target = 'linux_amd64'
    manifest = canonical_manifest(target)
    manifest['distribution_files'] = [item for item in manifest['distribution_files'] if item['path'] != 'docs/index.html']
    members = [member for member in canonical_members(target, manifest=manifest) if member['name'] != target + '/docs/index.html']
    self.assert_rejected(build_tar_gz(members), 'top-level directory contains no regular files', target)

  def test_jogamp_license_top_level_contract_is_exact_for_every_target(self):
    for target in TARGETS:
      if TARGET_JOGAMP_JARS[target]:
        for license_name in JOGAMP_LICENSE_FILES:
          with self.subTest(target=target, license=license_name, condition='missing'):
            static_files = canonical_static_files(target)
            static_files.pop(license_name)
            self.assert_rejected(self.archive_with_manifest(target, static_files=static_files), 'missing canonical top-level', target)
      else:
        with self.subTest(target=target, condition='unexpected'):
          static_files = canonical_static_files(target)
          static_files[JOGAMP_LICENSE_FILES[0]] = b'unexpected license'
          self.assert_rejected(self.archive_with_manifest(target, static_files=static_files), 'unexpected top-level', target)

  def test_target_jar_lists_and_jogl_capability_are_exactly_typed(self):
    mutations = (
        lambda value: value.__setitem__('jcef_jars', ['jcef-tests.jar', 'jcef.jar']),
        lambda value: value.__setitem__('jogamp_jars', []),
        lambda value: value.__setitem__('jogl_swing_osr_supported', 1),
        lambda value: value.__setitem__('jogl_swing_osr_supported', False),
    )
    for index, mutation in enumerate(mutations):
      with self.subTest(case=index):
        self.assert_rejected(self.archive_with_manifest(mutate=mutation), 'Manifest')

  def test_every_declared_top_level_jar_must_be_nonempty_regular_file(self):
    target = 'linux_amd64'
    for jar_name in canonical_jar_files(target):
      with self.subTest(jar=jar_name, condition='missing'):
        jar_files = canonical_jar_files(target)
        jar_files.pop(jar_name)
        self.assert_rejected(self.archive_with_manifest(target, jar_files=jar_files), 'missing|empty', target)
      with self.subTest(jar=jar_name, condition='empty'):
        jar_files = canonical_jar_files(target)
        jar_files[jar_name] = b''
        self.assert_rejected(self.archive_with_manifest(target, jar_files=jar_files), 'missing|empty', target)

  def test_every_macos_minimum_runtime_leaf_is_required(self):
    for target in ('macos_amd64', 'macos_arm64'):
      for required_path in TARGET_REQUIRED_RUNTIME_FILES[target]:
        with self.subTest(target=target, path=required_path):
          runtime_files = canonical_runtime_files(target)
          runtime_files.pop(required_path)
          contents = self.archive_with_manifest(target, runtime_files=runtime_files)
          self.assert_rejected(contents, 'required non-empty runtime file', target)

  def test_runtime_entries_must_be_sorted_unique_nonoverlapping_and_target_exact(self):

    def duplicate(value):
      value['runtime_entries'].append(value['runtime_entries'][-1])

    def overlap(value):
      value['runtime_entries'] = sorted(value['runtime_entries'] + ['locales/en-US.pak'])

    mutations = (
        lambda value: value.__setitem__('runtime_entries', list(reversed(value['runtime_entries']))),
        duplicate,
        overlap,
        lambda value: value['runtime_entries'].pop(),
        lambda value: value.__setitem__('runtime_entries', sorted(value['runtime_entries'] + ['unexpected.bin'])),
        lambda value: value.__setitem__('runtime_entries', 'locales'),
    )
    for index, mutation in enumerate(mutations):
      with self.subTest(case=index):
        self.assert_rejected(self.archive_with_manifest(mutate=mutation), 'runtime_entries|Runtime entry')

  def test_runtime_inventory_requires_exact_items_order_paths_sizes_and_hashes(self):

    def duplicate(value):
      value['runtime_files'].append(copy.deepcopy(value['runtime_files'][-1]))

    def extra_key(value):
      value['runtime_files'][0]['extra'] = None

    def wrong_size(value):
      value['runtime_files'][0]['size'] += 1

    def wrong_hash(value):
      value['runtime_files'][0]['sha256'] = 'F' * 64

    mutations = (
        lambda value: value.__setitem__('runtime_files', list(reversed(value['runtime_files']))),
        duplicate,
        extra_key,
        wrong_size,
        wrong_hash,
        lambda value: value['runtime_files'].pop(),
        lambda value: value.__setitem__('runtime_files', 'files'),
    )
    for index, mutation in enumerate(mutations):
      with self.subTest(case=index):
        self.assert_rejected(self.archive_with_manifest(mutate=mutation), 'runtime_files|Runtime')
    self.assert_rejected(self.archive_with_manifest(mutate=lambda value: value['runtime_files'][0].__setitem__('path', '\ud800')), 'valid Unicode text')

  def test_runtime_inventory_exactly_expands_archive_directories_and_bytes(self):
    target = 'linux_amd64'
    members = canonical_members(target)
    runtime_path = '{}/locales/en-US.pak'.format(target)
    members = [member for member in members if member['name'] != runtime_path]
    self.assert_rejected(build_tar_gz(members), 'distribution inventory|missing declared runtime entry|expands to no files|does not exactly expand', target)

    members = canonical_members(target)
    members.append(file_member('{}/locales/unlisted.pak'.format(target), b'unlisted'))
    self.assert_rejected(build_tar_gz(members), 'distribution inventory|does not exactly expand', target)

    members = canonical_members(target)
    for member in members:
      if member['name'] == runtime_path:
        member['contents'] = b'tampered'
        break
    self.assert_rejected(build_tar_gz(members), 'byte metadata mismatch', target)

  def test_runtime_entry_archive_kinds_are_target_exact(self):
    target = 'linux_amd64'
    members = [
        member for member in canonical_members(target)
        if member['name'] not in (target + '/locales',
                                  target + '/locales/en-US.pak')
    ]
    members.append(file_member(target + '/locales', b'not-a-directory'))
    self.assert_rejected(build_tar_gz(members), 'distribution inventory|wrong archived type', target)

    file_entry = target + '/libcef.so'
    members = [
        member for member in canonical_members(target)
        if member['name'] != file_entry
    ]
    members.extend((directory_member(file_entry), file_member(file_entry + '/nested', b'not-a-library')))
    self.assert_rejected(build_tar_gz(members), 'distribution inventory|wrong archived type', target)

  def test_archive_paths_reject_traversal_absolute_backslash_ads_controls_and_non_nfc(self):
    target = 'linux_amd64'
    unsafe_paths = ('../outside', '/absolute', target + '/../outside',
                    target + '/back\\slash', target + '/file:stream',
                    target + '/control\x01', target + '/e\u0301.txt',
                    target + '//empty', target + '/./dot',)
    for path in unsafe_paths:
      with self.subTest(path=repr(path)):
        members = [directory_member(target), file_member(path, b'x')]
        self.assert_rejected(build_tar_gz(members), 'Archive member|unsafe|normalized', target)

  def test_archive_paths_reject_duplicates_case_collisions_and_missing_parents(self):
    target = 'linux_amd64'
    members = canonical_members(target)
    members.append(file_member('{}/jcef.jar'.format(target), b'duplicate'))
    self.assert_rejected(build_tar_gz(members), 'duplicate normalized path', target)
    members = canonical_members(target)
    members.append(file_member('{}/JCEF.JAR'.format(target), b'collision'))
    self.assert_rejected(build_tar_gz(members), 'case-colliding', target)
    members = [
        member for member in canonical_members(target)
        if member['name'] != target + '/locales'
    ]
    self.assert_rejected(build_tar_gz(members), 'missing or non-directory parent', target)

  def test_archive_root_must_be_single_explicit_canonical_directory(self):
    target = 'linux_amd64'
    members = [
        member for member in canonical_members(target)
        if member['name'] != target
    ]
    self.assert_rejected(build_tar_gz(members), 'explicit directory', target)
    members = canonical_members(target)
    members.append(directory_member('other'))
    self.assert_rejected(build_tar_gz(members), 'outside the canonical', target)

  def test_links_devices_fifos_and_sparse_members_are_rejected(self):
    target = 'linux_amd64'
    types = (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE,
             tarfile.FIFOTYPE, tarfile.GNUTYPE_SPARSE)
    for member_type in types:
      with self.subTest(member_type=member_type):
        members = [
            directory_member(target),
            special_member(target + '/unsafe', member_type, target + '/jcef.jar')
        ]
        self.assert_rejected(build_tar_gz(members), 'sparse|unsupported member type|GNU sparse', target)

  def test_pax_sparse_metadata_is_rejected_before_sparse_map_processing(self):
    target = 'linux_amd64'
    members = [
        directory_member(target),
        file_member(target + '/sparse', b'x', {'GNU.sparse.map': '0,1', 'GNU.sparse.size': '1'})
    ]
    self.assert_rejected(build_tar_gz(members), 'sparse tar metadata', target)

  def test_pax_control_payload_is_bounded_before_stdlib_allocation(self):
    declared_size = 4097
    raw_tar = _control_header(tarfile.XHDTYPE, declared_size=declared_size)
    contents = _gzip_bytes(raw_tar)
    limits = VerificationLimits(max_tar_control_bytes=4096)
    self.assert_rejected(contents, 'control payload size', limits=limits)

  def test_global_and_aggregate_pax_metadata_are_rejected_before_retention(self):
    valid_tar = gzip.decompress(build_valid_archive('linux_amd64'))
    global_control = _control_header(tarfile.XGLTYPE, _pax_record('comment', 'persistent'))
    self.assert_rejected(_gzip_bytes(global_control + valid_tar), 'global PAX')

    payload = _pax_record('path', 'linux_amd64/')
    local_control = _control_header(tarfile.XHDTYPE, payload)
    limits = VerificationLimits(max_tar_control_bytes=len(payload), max_total_tar_control_bytes=len(payload) - 1)
    self.assert_rejected(_gzip_bytes(local_control + valid_tar), 'aggregate limit', limits=limits)

    gnu_long_name = _control_header(tarfile.GNUTYPE_LONGNAME, b'linux_amd64/renamed\0')
    self.assert_rejected(_gzip_bytes(gnu_long_name + valid_tar), 'non-canonical tar control')

  def test_consecutive_and_total_hidden_control_headers_are_bounded(self):
    control = _control_header(tarfile.XHDTYPE)
    visible = gzip.decompress(build_valid_archive('linux_amd64'))
    contents = _gzip_bytes(control + control + visible)
    self.assert_rejected(contents, 'consecutive tar-control-member', limits=VerificationLimits(max_consecutive_tar_control_members=1))
    self.assert_rejected(contents, 'hidden tar-control-member', limits=VerificationLimits(max_tar_control_members=1))

  def test_zero_header_padding_is_bounded_while_scanning_to_physical_eof(self):
    contents = _gzip_bytes(b'\0' * tarfile.BLOCKSIZE * 3)
    self.assert_rejected(contents, 'zero-block limit', limits=VerificationLimits(max_tar_zero_blocks=2))

  def test_canonical_tar_end_record_matches_python_producer_boundary(self):
    contents = build_valid_archive('linux_amd64')
    raw_tar = gzip.decompress(contents)
    first_zero_offset = _first_tar_zero_offset(raw_tar)
    end_after_terminators = first_zero_offset + tarfile.BLOCKSIZE * 2
    expected_size = ((end_after_terminators + tarfile.RECORDSIZE - 1) // tarfile.RECORDSIZE) * tarfile.RECORDSIZE
    self.assertEqual(expected_size, len(raw_tar))
    self.verify_bytes(contents)

  def test_missing_truncated_extra_and_gapped_tar_end_records_are_rejected(self):
    contents = build_valid_archive('linux_amd64')
    raw_tar = gzip.decompress(contents)
    first_zero_offset = _first_tar_zero_offset(raw_tar)
    gapped_tar = bytearray(raw_tar)
    gapped_tar[first_zero_offset + tarfile.BLOCKSIZE * 2:first_zero_offset + tarfile.BLOCKSIZE * 3] = raw_tar[:tarfile.BLOCKSIZE]
    cases = (
        (_gzip_bytes(raw_tar[:first_zero_offset]), 'missing the canonical tar end record'),
        (_gzip_bytes(raw_tar[:first_zero_offset + tarfile.BLOCKSIZE]), 'end padding is not canonical'),
        (contents + _gzip_bytes(b'\0' * tarfile.RECORDSIZE), 'end padding is not canonical'),
        (_gzip_bytes(bytes(gapped_tar)), 'data after its first tar zero block'),
    )
    for malformed, pattern in cases:
      with self.subTest(pattern=pattern):
        self.assert_rejected(malformed, pattern)

  def test_small_pax_path_records_used_by_long_macos_paths_are_accepted(self):
    self.verify_bytes(build_valid_archive('macos_arm64'), 'macos_arm64')

  def test_tar_member_metadata_must_match_canonical_producer_output(self):
    cases = (
        ('uid', 1, 'ownership metadata'),
        ('gid', 1, 'ownership metadata'),
        ('uname', 'builder', 'ownership metadata'),
        ('gname', 'builder', 'ownership metadata'),
        ('linkname', 'unused', 'link-name metadata'),
        ('mtime', 946684801, 'modification time'),
        ('mode', 0o777, 'mode is not canonical'),
        ('pax_headers', {'comment': 'unexpected'}, 'unsupported local PAX metadata'),
    )
    for field, value, pattern in cases:
      with self.subTest(field=field):
        members = canonical_members('linux_amd64')
        members[0][field] = value
        self.assert_rejected(build_tar_gz(members), pattern)

  def test_legacy_regular_file_type_is_rejected_as_noncanonical(self):
    members = canonical_members('linux_amd64')
    regular_member = next(member for member in members if member['type'] == tarfile.REGTYPE)
    regular_member['type'] = tarfile.AREGTYPE
    self.assert_rejected(build_tar_gz(members), 'unsupported member type')

  def test_tar_member_mode_with_non_permission_bits_is_rejected(self):
    contents = _replace_first_tar_header_field(build_valid_archive('linux_amd64'), 100, b'0100755\0')
    self.assert_rejected(contents, 'directory mode is not canonical')

  def test_tar_member_device_numbers_are_rejected(self):
    for field, offset in (('devmajor', 329), ('devminor', 337)):
      with self.subTest(field=field):
        contents = _replace_first_tar_header_field(build_valid_archive('linux_amd64'), offset, b'0000001\0')
        self.assert_rejected(contents, 'device metadata is not canonical')

  def test_decoded_tar_cap_includes_headers_padding_and_hidden_controls(self):
    contents = build_valid_archive('linux_amd64')
    decoded_size = len(gzip.decompress(contents))
    self.verify_bytes(contents, limits=VerificationLimits(max_tar_bytes=decoded_size))
    self.assert_rejected(contents, 'Decoded tar stream', limits=VerificationLimits(max_tar_bytes=decoded_size - 1))

    control = _control_header(tarfile.XHDTYPE, _pax_record('path', 'linux_amd64/'))
    controlled = _gzip_bytes(control + gzip.decompress(contents))
    self.assert_rejected(controlled, 'Decoded tar stream', limits=VerificationLimits(max_tar_bytes=decoded_size))

  def test_concatenated_gzip_tar_after_end_marker_is_not_ignored(self):
    first = build_valid_archive('linux_amd64')
    second = build_tar_gz([directory_member('other'), file_member('other/file', b'x')])
    self.assert_rejected(first + second, 'data after its first tar zero block', limits=VerificationLimits(max_tar_bytes=len(gzip.decompress(first)) + len(gzip.decompress(second))))

  def test_member_extracted_runtime_entry_and_runtime_file_limits_are_enforced_at_boundaries(self):
    target = 'linux_amd64'
    members = canonical_members(target)
    contents = build_tar_gz(members)
    manifest = canonical_manifest(target)
    extracted_size = sum((len(member.get('contents', b'')) for member in members if member.get('type') in (tarfile.REGTYPE, tarfile.AREGTYPE)))
    self.verify_bytes(contents, limits=VerificationLimits(max_members=len(members), max_extracted_bytes=extracted_size, max_runtime_entries=len(TARGET_RUNTIME_ENTRIES[target]), max_runtime_files=len(canonical_runtime_files(target)), max_distribution_files=len(manifest['distribution_files']), max_distribution_directories=len(manifest['distribution_directories'])))
    cases = (VerificationLimits(max_members=len(members) - 1), VerificationLimits(max_extracted_bytes=extracted_size - 1), VerificationLimits(max_runtime_entries=len(TARGET_RUNTIME_ENTRIES[target]) - 1),
             VerificationLimits(max_runtime_files=len(canonical_runtime_files(target)) - 1), VerificationLimits(max_distribution_files=len(manifest['distribution_files']) - 1), VerificationLimits(max_distribution_directories=len(manifest['distribution_directories']) - 1),)
    for limits in cases:
      with self.subTest(limits=vars(limits)):
        self.assert_rejected(contents, 'limit|bounded', limits=limits)

  def test_path_length_and_depth_limits_are_enforced_at_boundaries(self):
    target = 'linux_amd64'
    contents = build_valid_archive(target)
    longest_path = max((member['name'] for member in canonical_members(target)), key=lambda value: len(value.encode('utf-8')))
    deepest_path = max((member['name'] for member in canonical_members(target)), key=lambda value: len(value.split('/')))
    total_path_bytes = sum((len(member['name'].encode('utf-8')) for member in canonical_members(target)))
    self.verify_bytes(contents, limits=VerificationLimits(max_path_bytes=len(longest_path.encode('utf-8')), max_path_depth=len(deepest_path.split('/')), max_total_path_bytes=total_path_bytes))
    self.assert_rejected(contents, 'path-length limit', limits=VerificationLimits(max_path_bytes=len(longest_path.encode('utf-8')) - 1))
    self.assert_rejected(contents, 'path-depth limit', limits=VerificationLimits(max_path_depth=len(deepest_path.split('/')) - 1))
    self.assert_rejected(contents, 'aggregate byte limit', limits=VerificationLimits(max_total_path_bytes=total_path_bytes - 1))

  def test_empty_truncated_and_non_gzip_archives_fail_closed(self):
    path = self.archive_path()
    path.write_bytes(b'')
    with self.assertRaisesRegex(VerificationError, 'empty'):
      verify_distribution_archive(path, 'linux_amd64', TEST_COMMIT)
    for contents in (b'not gzip', build_valid_archive('linux_amd64')[:-10]):
      with self.subTest(size=len(contents)):
        self.assert_rejected(contents, 'stream archive|tar|gzip|end-of-stream')


if __name__ == '__main__':
  unittest.main()
