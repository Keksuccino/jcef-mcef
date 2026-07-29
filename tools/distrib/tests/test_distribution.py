#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import io
import os
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

DISTRIB_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = DISTRIB_ROOT.parents[1]
sys.path.insert(0, str(DISTRIB_ROOT))

from distribution import CEF_ARCHIVE_SHA1, CEF_VERSION, DistributionError  # noqa: E402
from distribution import JCEF_RUNTIME_FILES, TARGETS  # noqa: E402
from distribution import RINKU_DEFAULT_MAX_ARCHIVE_BYTES  # noqa: E402
from distribution import RINKU_DEFAULT_MAX_EXTRACTED_BYTES  # noqa: E402
from distribution import cef_runtime_manifest, jogamp_jars  # noqa: E402
from distribution import mac_runtime_requirements, resolve_target  # noqa: E402
from distribution import validate_archive, validate_jar_class_version  # noqa: E402
from distribution import validate_runtime  # noqa: E402
from make_distrib import JAVA_CHECK_NAMES, LAUNCHER_NAMES  # noqa: E402
from make_distrib import _copy_runtime, _copy_templates  # noqa: E402
from make_distrib import _create_archive  # noqa: E402
from make_distrib import _strip_linux_runtime_debug_sections  # noqa: E402


def find_cef_manifest_root():
  candidates = sorted((REPOSITORY_ROOT / 'third_party' /
                       'cef').glob('cef_binary_{}_*'.format(CEF_VERSION)))
  if not candidates:
    raise AssertionError(
        'No exact CEF {} source directory found'.format(CEF_VERSION))
  return candidates[0]


def write_nonempty_file(path):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_bytes(b'x')


class TargetMappingTest(unittest.TestCase):

  def test_all_six_canonical_targets(self):
    self.assertEqual(('linux_amd64', 'linux_arm64', 'macos_amd64',
                      'macos_arm64', 'windows_amd64', 'windows_arm64'),
                     tuple(TARGETS.keys()))
    self.assertEqual(set(TARGETS), set(CEF_ARCHIVE_SHA1))
    self.assertTrue(
        all(
            re.fullmatch(r'[0-9a-f]{40}', digest)
            for digest in CEF_ARCHIVE_SHA1.values()))

  def test_only_canonical_target_names_are_accepted(self):
    for target_name in TARGETS:
      self.assertIs(TARGETS[target_name], resolve_target(target_name))
    for target_name in ('linux64', 'linuxarm64', 'macosx64', 'macosarm64',
                        'win64', 'windows64', 'windowsarm64', 'linux32',
                        'win32'):
      with self.assertRaisesRegex(DistributionError, 'Unsupported'):
        resolve_target(target_name)

  def test_jogamp_native_selection_is_architecture_honest(self):
    self.assertIn('jogl-all-natives-linux-amd64.jar',
                  jogamp_jars(TARGETS['linux_amd64']))
    self.assertIn('jogl-all-natives-linux-aarch64.jar',
                  jogamp_jars(TARGETS['linux_arm64']))
    for target_name in ('macos_amd64', 'macos_arm64'):
      self.assertIn('jogl-all-natives-macosx-universal.jar',
                    jogamp_jars(TARGETS[target_name]))
    self.assertIn('jogl-all-natives-windows-amd64.jar',
                  jogamp_jars(TARGETS['windows_amd64']))
    self.assertEqual((), jogamp_jars(TARGETS['windows_arm64']))


class Cef151ManifestTest(unittest.TestCase):

  @classmethod
  def setUpClass(cls):
    cls.cef_root = find_cef_manifest_root()

  def test_linux_manifest_contains_complete_cef_151_runtime(self):
    binaries, resources = cef_runtime_manifest(self.cef_root,
                                               TARGETS['linux_arm64'])
    self.assertIn('v8_context_snapshot.bin', binaries)
    self.assertIn('libvk_swiftshader.so', binaries)
    self.assertIn('locales', resources)
    self.assertIn('resources.pak', resources)
    self.assertNotIn('snapshot_blob.bin', binaries)

  def test_windows_amd64_manifest_includes_directx_compiler(self):
    binaries, resources = cef_runtime_manifest(self.cef_root,
                                               TARGETS['windows_amd64'])
    self.assertIn('dxcompiler.dll', binaries)
    self.assertIn('dxil.dll', binaries)
    self.assertIn('d3dcompiler_47.dll', binaries)
    self.assertIn('locales', resources)
    for obsolete_name in ('d3dcompiler_43.dll', 'icudt.dll', 'natives_blob.bin',
                          'snapshot_blob.bin'):
      self.assertNotIn(obsolete_name, binaries)

  def test_windows_arm64_follows_canonical_manifest(self):
    binaries, resources = cef_runtime_manifest(self.cef_root,
                                               TARGETS['windows_arm64'])
    self.assertNotIn('dxcompiler.dll', binaries)
    self.assertNotIn('dxil.dll', binaries)
    self.assertIn('locales', resources)

  def test_missing_manifest_runtime_file_fails(self):
    target = TARGETS['windows_amd64']
    binaries, resources = cef_runtime_manifest(self.cef_root, target)
    with tempfile.TemporaryDirectory() as temporary_directory:
      runtime_root = Path(temporary_directory)
      for relative_path in binaries + resources + JCEF_RUNTIME_FILES['windows']:
        if relative_path == 'locales':
          write_nonempty_file(runtime_root / 'locales' / 'en-US.pak')
        else:
          write_nonempty_file(runtime_root / relative_path)
      validate_runtime(
          runtime_root, self.cef_root, target, check_architecture=False)
      (runtime_root / 'dxcompiler.dll').unlink()
      with self.assertRaisesRegex(DistributionError, 'dxcompiler.dll'):
        validate_runtime(
            runtime_root, self.cef_root, target, check_architecture=False)

  def test_obsolete_runtime_file_fails(self):
    target = TARGETS['linux_amd64']
    binaries, resources = cef_runtime_manifest(self.cef_root, target)
    with tempfile.TemporaryDirectory() as temporary_directory:
      runtime_root = Path(temporary_directory)
      for relative_path in binaries + resources + JCEF_RUNTIME_FILES['linux']:
        if relative_path == 'locales':
          write_nonempty_file(runtime_root / 'locales' / 'en-US.pak')
        else:
          write_nonempty_file(runtime_root / relative_path)
      write_nonempty_file(runtime_root / 'snapshot_blob.bin')
      with self.assertRaisesRegex(DistributionError, 'pre-CEF-151'):
        validate_runtime(
            runtime_root, self.cef_root, target, check_architecture=False)

  def test_flat_macos_requirements_match_rinku_archive_layout(self):
    requirements = mac_runtime_requirements(TARGETS['macos_arm64'], 'flat')
    self.assertTrue(all('/Versions/' not in path for path in requirements))
    self.assertIn('jcef_app.app/Contents/Info.plist', requirements)
    self.assertIn('jcef_app.app/Contents/MacOS/JavaAppLauncher', requirements)
    self.assertIn(
        ('jcef_app.app/Contents/Frameworks/Chromium Embedded '
         'Framework.framework/Resources/v8_context_snapshot.arm64.bin'),
        requirements)
    for helper_suffix in ('', ' (Alerts)', ' (GPU)', ' (Plugin)',
                          ' (Renderer)'):
      helper_name = 'jcef Helper{}'.format(helper_suffix)
      self.assertIn(
          'jcef_app.app/Contents/Frameworks/{0}.app/Contents/MacOS/{0}'.format(
              helper_name), requirements)


class LinuxRuntimeStripTest(unittest.TestCase):

  @classmethod
  def setUpClass(cls):
    cls.cef_root = find_cef_manifest_root()

  def create_runtime(self, native_output, target):
    binaries, resources = cef_runtime_manifest(self.cef_root, target)
    entries = binaries + resources + JCEF_RUNTIME_FILES['linux']
    for relative_path in entries:
      if relative_path == 'locales':
        write_nonempty_file(native_output / 'locales' / 'en-US.pak')
      else:
        write_nonempty_file(native_output / relative_path)
    elf_contents = b'\x7fELFdebug-sections'
    for relative_path in ('libcef.so', 'libjcef.so', 'jcef_helper'):
      path = native_output / relative_path
      path.write_bytes(elf_contents)
      path.chmod(0o755)
    return entries, elf_contents

  def test_linux_runtime_copy_strips_only_staged_elf_files(self):
    target = TARGETS['linux_arm64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      native_output = root / 'native'
      destination = root / 'distribution'
      native_output.mkdir()
      destination.mkdir()
      entries, elf_contents = self.create_runtime(native_output, target)
      source_modes = {
          relative_path: (native_output / relative_path).stat().st_mode
          for relative_path in ('libcef.so', 'libjcef.so', 'jcef_helper')
      }
      stripped_paths = []

      def run_strip(command, **unused_kwargs):
        self.assertEqual(['/usr/bin/strip', '--strip-debug'], command[:2])
        path = Path(command[-1])
        stripped_paths.append(path.relative_to(destination).as_posix())
        path.write_bytes(b'\x7fELFstripped')
        path.chmod(0o600)
        return mock.Mock(returncode=0, stdout='', stderr='')

      with mock.patch(
          'make_distrib.shutil.which', return_value='/usr/bin/strip'):
        with mock.patch('make_distrib.subprocess.run', side_effect=run_strip):
          copied_entries = _copy_runtime(native_output, destination,
                                         self.cef_root, target)

      self.assertEqual(entries, copied_entries)
      self.assertEqual(['jcef_helper', 'libcef.so', 'libjcef.so'],
                       stripped_paths)
      for relative_path in stripped_paths:
        self.assertEqual(b'\x7fELFstripped',
                         (destination / relative_path).read_bytes())
        self.assertEqual(source_modes[relative_path],
                         (destination / relative_path).stat().st_mode)
        self.assertEqual(elf_contents,
                         (native_output / relative_path).read_bytes())
      self.assertEqual((native_output / 'resources.pak').read_bytes(),
                       (destination / 'resources.pak').read_bytes())

  def test_missing_strip_fails_before_copying_linux_runtime(self):
    target = TARGETS['linux_arm64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      native_output = root / 'native'
      destination = root / 'distribution'
      native_output.mkdir()
      destination.mkdir()
      self.create_runtime(native_output, target)
      with mock.patch('make_distrib.shutil.which', return_value=None):
        with self.assertRaisesRegex(DistributionError,
                                    'strip was not found on PATH'):
          _copy_runtime(native_output, destination, self.cef_root, target)
      self.assertEqual([], list(destination.iterdir()))

  def test_strip_failure_identifies_staged_elf_and_tool_output(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      runtime_root = Path(temporary_directory)
      elf_path = runtime_root / 'libcef.so'
      elf_path.write_bytes(b'\x7fELFdebug-sections')
      result = mock.Mock(returncode=7, stdout='', stderr='unsupported ELF')
      with mock.patch('make_distrib.subprocess.run', return_value=result):
        with self.assertRaisesRegex(
            DistributionError, r'libcef\.so with exit code 7: unsupported ELF'):
          _strip_linux_runtime_debug_sections(runtime_root, '/usr/bin/strip')

  @unittest.skipIf(os.name == 'nt', 'Windows symlink creation is restricted')
  def test_strip_replacement_link_cannot_change_external_file_mode(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      runtime_root = root / 'runtime'
      runtime_root.mkdir()
      elf_path = runtime_root / 'libcef.so'
      elf_path.write_bytes(b'\x7fELFdebug-sections')
      external_path = root / 'external.so'
      external_path.write_bytes(b'\x7fELFexternal')
      external_path.chmod(0o640)
      external_mode = external_path.stat().st_mode

      def replace_with_link(command, **unused_kwargs):
        path = Path(command[-1])
        path.unlink()
        path.symlink_to(external_path)
        return mock.Mock(returncode=0, stdout='', stderr='')

      with mock.patch(
          'make_distrib.subprocess.run', side_effect=replace_with_link):
        with self.assertRaisesRegex(DistributionError,
                                    'did not leave a regular staged'):
          _strip_linux_runtime_debug_sections(runtime_root, '/usr/bin/strip')
      self.assertEqual(external_mode, external_path.stat().st_mode)


class ArchiveAndJavaTest(unittest.TestCase):

  def write_archive(self, archive_path, target, members):
    with tarfile.open(str(archive_path), 'w:gz') as archive:
      root_info = tarfile.TarInfo(target.name)
      root_info.type = tarfile.DIRTYPE
      archive.addfile(root_info)
      for member_name, member_type, contents in members:
        member = tarfile.TarInfo(member_name)
        member.type = member_type
        if contents is None:
          archive.addfile(member)
        else:
          member.size = len(contents)
          archive.addfile(member, io.BytesIO(contents))

  def test_archive_has_one_canonical_root_and_required_entries(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      distribution.mkdir()
      write_nonempty_file(distribution / 'libjcef.so')
      archive_path = root / '{}.tar.gz'.format(target.name)
      with tarfile.open(str(archive_path), 'w:gz') as archive:
        archive.add(str(distribution), arcname=target.name)
      validate_archive(archive_path, target, ('libjcef.so',))

  def test_archive_links_fail_for_rinku_compatibility(self):
    target = TARGETS['macos_arm64']
    for link_type in (tarfile.SYMTYPE, tarfile.LNKTYPE):
      with self.subTest(link_type=link_type):
        with tempfile.TemporaryDirectory() as temporary_directory:
          archive_path = Path(temporary_directory) / 'linked.tar.gz'
          with tarfile.open(str(archive_path), 'w:gz') as archive:
            root_info = tarfile.TarInfo(target.name)
            root_info.type = tarfile.DIRTYPE
            archive.addfile(root_info)
            link_info = tarfile.TarInfo(target.name + '/framework-link')
            link_info.type = link_type
            link_info.linkname = target.name + '/framework'
            archive.addfile(link_info)
          with self.assertRaisesRegex(DistributionError,
                                      'must not contain links'):
            validate_archive(archive_path, target, ())

  def test_archive_member_outside_canonical_root_fails(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'wrong-root.tar.gz'
      with tarfile.open(str(archive_path), 'w:gz') as archive:
        file_info = tarfile.TarInfo('linux64/libjcef.so')
        file_info.size = 1
        archive.addfile(file_info, io.BytesIO(b'x'))
      with self.assertRaisesRegex(DistributionError, 'outside'):
        validate_archive(archive_path, target, ())

  def test_archive_enforces_exact_rinku_default_size_limits(self):
    self.assertEqual(750 * 1024 * 1024, RINKU_DEFAULT_MAX_ARCHIVE_BYTES)
    self.assertEqual(2_000 * 1024 * 1024, RINKU_DEFAULT_MAX_EXTRACTED_BYTES)
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'limits.tar.gz'
      self.write_archive(archive_path, target, ((target.name + '/libjcef.so',
                                                 tarfile.REGTYPE, b'ab'),))
      with self.assertRaisesRegex(DistributionError, 'compressed size'):
        validate_archive(
            archive_path,
            target, ('libjcef.so',),
            max_archive_bytes=archive_path.stat().st_size - 1)
      with self.assertRaisesRegex(DistributionError, 'extracted size'):
        validate_archive(
            archive_path, target, ('libjcef.so',), max_extracted_bytes=1)

  def test_archive_rejects_unsupported_member_types(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'fifo.tar.gz'
      self.write_archive(archive_path, target, ((target.name + '/named-pipe',
                                                 tarfile.FIFOTYPE, None),))
      with self.assertRaisesRegex(DistributionError, 'unsupported member'):
        validate_archive(archive_path, target, ())

  def test_archive_rejects_duplicate_normalized_paths(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'duplicate.tar.gz'
      self.write_archive(archive_path, target,
                         ((target.name + '/libjcef.so', tarfile.REGTYPE, b'a'),
                          (target.name + '/./libjcef.so', tarfile.REGTYPE,
                           b'b'),))
      with self.assertRaisesRegex(DistributionError, 'duplicate normalized'):
        validate_archive(archive_path, target, ())

  def test_archive_member_count_is_bounded(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'members.tar.gz'
      self.write_archive(archive_path, target, ((target.name + '/libjcef.so',
                                                 tarfile.REGTYPE, b'x'),))
      with self.assertRaisesRegex(DistributionError, 'publication limit'):
        validate_archive(archive_path, target, (), max_members=1)

  def test_required_archive_directory_must_contain_a_nonempty_file(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'empty-directory.tar.gz'
      self.write_archive(archive_path, target, ((target.name + '/locales',
                                                 tarfile.DIRTYPE, None),))
      with self.assertRaisesRegex(DistributionError, 'no non-empty files'):
        validate_archive(archive_path, target, ('locales',), ('locales',))
      populated_archive_path = Path(
          temporary_directory) / 'populated-directory.tar.gz'
      self.write_archive(populated_archive_path, target,
                         ((target.name + '/locales', tarfile.DIRTYPE, None),
                          (target.name + '/locales/en-US.pak', tarfile.REGTYPE,
                           b'locale'),))
      validate_archive(populated_archive_path, target, ('locales',),
                       ('locales',))

  def test_java_17_class_version_is_required(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      jar_path = Path(temporary_directory) / 'jcef.jar'
      with zipfile.ZipFile(str(jar_path), 'w') as archive:
        archive.writestr('org/cef/Test.class',
                         b'\xca\xfe\xba\xbe\x00\x00\x00\x3d')
      validate_jar_class_version(jar_path)
      with zipfile.ZipFile(str(jar_path), 'w') as archive:
        archive.writestr('org/cef/Test.class',
                         b'\xca\xfe\xba\xbe\x00\x00\x00\x3e')
      with self.assertRaisesRegex(DistributionError, 'Java 17'):
        validate_jar_class_version(jar_path)

  def test_distribution_archive_is_byte_reproducible(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      write_nonempty_file(distribution / 'nested' / 'runtime.bin')
      write_nonempty_file(distribution / 'README.txt')
      first_archive = root / 'first.tar.gz'
      second_archive = root / 'second.tar.gz'

      _create_archive(distribution, first_archive, target)
      for path in distribution.rglob('*'):
        os.utime(path, (1700000000, 1700000000))
      _create_archive(distribution, second_archive, target)

      self.assertEqual(first_archive.read_bytes(), second_archive.read_bytes())


class LauncherStaticTest(unittest.TestCase):

  def test_each_distribution_copies_its_java_guard_and_launchers(self):
    for target in TARGETS.values():
      with self.subTest(target=target.name):
        with tempfile.TemporaryDirectory() as temporary_directory:
          destination = Path(temporary_directory)
          _copy_templates(REPOSITORY_ROOT, destination, target)
          required_names = ((JAVA_CHECK_NAMES[target.family],) +
                            LAUNCHER_NAMES[target.family])
          for required_name in required_names:
            self.assertTrue((destination / required_name).is_file())

  def test_generic_launchers_disable_external_message_pump(self):
    for relative_path in ('linux/run.sh', 'windows/run.bat'):
      contents = (DISTRIB_ROOT / relative_path).read_text(encoding='utf-8')
      self.assertIn('-Djcef.external_message_pump=false', contents)

  def test_entry_points_delegate_and_propagate_failures(self):
    shell_script = (REPOSITORY_ROOT / 'tools' / 'make_distrib.sh').read_text(
        encoding='utf-8')
    batch_script = (REPOSITORY_ROOT / 'tools' / 'make_distrib.bat').read_text(
        encoding='utf-8')
    self.assertIn('set -euo pipefail', shell_script)
    self.assertIn('exec python3', shell_script)
    self.assertIn('exit /B %ERRORLEVEL%', batch_script)
    self.assertIn('"%~dp0distrib\\make_distrib.py"', batch_script)
    self.assertNotIn('copy %OUT_BINARY_PATH%', batch_script)


if __name__ == '__main__':
  unittest.main()
