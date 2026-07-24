#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DOWNLOAD_CEF = REPOSITORY_ROOT / 'cmake' / 'DownloadCEF.cmake'
CONTRACT_TEST = REPOSITORY_ROOT / 'cmake' / 'tests' / 'DownloadCEFContractTest.cmake'
CEF_VERSION = '151.2.3+g89cd581+chromium-151.0.7922.34'
LINUX_SHA1 = '184100929d0c6a320736b4d56b55893ee8b599d1'


def run_contract(**definitions):
  command = ['cmake']
  for key, value in definitions.items():
    command.append('-D{}={}'.format(key, value))
  command.extend(('-DDOWNLOAD_CEF_FILE={}'.format(DOWNLOAD_CEF), '-P',
                  str(CONTRACT_TEST)))
  return subprocess.run(command, check=False, capture_output=True, text=True)


def write_file(path, contents=b'x'):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_bytes(contents)


class DownloadCefCMakeContractTest(unittest.TestCase):

  def write_tar(self, path, entries):
    with tarfile.open(path, 'w:gz') as archive:
      for name in entries:
        member = tarfile.TarInfo(name)
        if name.endswith('/'):
          member.type = tarfile.DIRTYPE
          archive.addfile(member)
        else:
          member.size = 1
          archive.addfile(member, io.BytesIO(b'x'))

  def test_archive_paths_require_one_exact_root(self):
    distribution = 'cef_binary_test_linux64_beta'
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      safe_archive = root / 'safe.tar.gz'
      self.write_tar(safe_archive, (distribution + '/',
                                    distribution + '/README.txt'))
      result = run_contract(
          TEST_CASE='archive_paths',
          ARCHIVE_PATH=safe_archive,
          DISTRIBUTION=distribution,
          EXPECT_VALID='ON')
      self.assertEqual(0, result.returncode, result.stderr)

      wrong_root_archive = root / 'wrong-root.tar.gz'
      self.write_tar(wrong_root_archive, ('cef_binary_other/',
                                          'cef_binary_other/README.txt'))
      result = run_contract(
          TEST_CASE='archive_paths',
          ARCHIVE_PATH=wrong_root_archive,
          DISTRIBUTION=distribution,
          EXPECT_VALID='OFF',
          EXPECT_REASON='outside the exact')
      self.assertEqual(0, result.returncode, result.stderr)

  def test_archive_paths_reject_traversal(self):
    distribution = 'cef_binary_test_linux64_beta'
    with tempfile.TemporaryDirectory() as temporary_directory:
      archive_path = Path(temporary_directory) / 'traversal.tar.gz'
      self.write_tar(archive_path, (distribution + '/',
                                    distribution + '/../escape'))
      result = run_contract(
          TEST_CASE='archive_paths',
          ARCHIVE_PATH=archive_path,
          DISTRIBUTION=distribution,
          EXPECT_VALID='OFF')
      self.assertEqual(0, result.returncode, result.stderr)

  def create_linux_root(self, parent):
    distribution = 'cef_binary_{}_linux64_beta'.format(CEF_VERSION)
    root = parent / distribution
    required_paths = ('README.txt', 'CMakeLists.txt', 'cmake/FindCEF.cmake',
                      'cmake/cef_macros.cmake', 'cmake/cef_variables.cmake',
                      'include/cef_app.h', 'include/cef_api_versions.h',
                      'include/cef_version.h', 'libcef_dll/CMakeLists.txt',
                      'Debug/libcef.so', 'Release/chrome-sandbox',
                      'Release/libcef.so', 'Release/libEGL.so',
                      'Release/libGLESv2.so', 'Release/libvk_swiftshader.so',
                      'Release/v8_context_snapshot.bin',
                      'Resources/chrome_100_percent.pak',
                      'Resources/chrome_200_percent.pak',
                      'Resources/resources.pak', 'Resources/icudtl.dat',
                      'Resources/locales/en-US.pak')
    for relative_path in required_paths:
      write_file(root / relative_path)
    write_file(root / 'README.txt',
               'CEF Version:      {}\n'.format(CEF_VERSION).encode('utf-8'))
    write_file(root / 'include' / 'cef_version.h',
               '#define CEF_VERSION "{}"\n'.format(CEF_VERSION).encode('utf-8'))
    return root, distribution

  def run_distribution_validation(self,
                                  root,
                                  expected_valid,
                                  expected_reason=None):
    definitions = {
        'TEST_CASE': 'distribution',
        'DISTRIBUTION_ROOT': root,
        'PLATFORM': 'linux64',
        'VERSION': CEF_VERSION,
        'CHANNEL': 'beta',
        'ARCHIVE_SHA1': LINUX_SHA1,
        'EXPECT_VALID': 'ON' if expected_valid else 'OFF',
    }
    if expected_reason is not None:
      definitions['EXPECT_REASON'] = expected_reason
    return run_contract(**definitions)

  def test_distribution_requires_exact_archive_provenance(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root, distribution = self.create_linux_root(Path(temporary_directory))
      result = self.run_distribution_validation(root, False,
                                                'provenance marker')
      self.assertEqual(0, result.returncode, result.stderr)

      provenance = ('JCEF_CEF_PROVENANCE_V1\n'
                    'archive={}.tar.bz2\n'
                    'platform=linux64\n'
                    'version={}\n'
                    'channel=beta\n'
                    'sha1={}\n').format(distribution, CEF_VERSION, LINUX_SHA1)
      (root / '.jcef-cef-provenance').write_text(provenance, encoding='ascii')
      result = self.run_distribution_validation(root, True)
      self.assertEqual(0, result.returncode, result.stderr)

      (root / '.jcef-cef-provenance').write_text(
          provenance.replace(LINUX_SHA1, '0' * 40), encoding='ascii')
      result = self.run_distribution_validation(root, False, 'does not match')
      self.assertEqual(0, result.returncode, result.stderr)


class CMakeArchitectureOrderingTest(unittest.TestCase):

  def test_macos_architecture_is_selected_before_project(self):
    contents = (REPOSITORY_ROOT / 'CMakeLists.txt').read_text(encoding='utf-8')
    project_position = contents.index('project(jcef)')
    osx_selection_position = contents.index(
        'set(CMAKE_OSX_ARCHITECTURES "${_requested_project_arch}"')
    compiler_probe_position = contents.index('include(CheckCXXSourceCompiles)')
    self.assertLess(osx_selection_position, project_position)
    self.assertGreater(compiler_probe_position, project_position)
    self.assertIn('check_cxx_source_compiles', contents)
    self.assertIn("Expected canonical architecture x86_64 or arm64", contents)

  def test_configure_probe_uses_real_compiler_target_without_downloading(self):
    if sys.platform != 'darwin':
      self.skipTest('macOS cross-architecture configure contract')
    architecture_probe = (
        REPOSITORY_ROOT / 'cmake' / 'tests' / 'architecture_probe')
    with tempfile.TemporaryDirectory() as temporary_directory:
      for architecture, cef_platform in (('arm64', 'macosarm64'), ('x86_64',
                                                                   'macosx64')):
        build_path = Path(temporary_directory) / architecture
        result = subprocess.run(
            [
                'cmake', '-S',
                str(REPOSITORY_ROOT), '-B',
                str(build_path), '-G', 'Ninja',
                '-DPROJECT_ARCH={}'.format(architecture),
                '-DEXPECTED_CEF_PLATFORM={}'.format(cef_platform),
                '-DCMAKE_MODULE_PATH={}'.format(architecture_probe)
            ],
            check=False,
            capture_output=True,
            text=True)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode)
        self.assertIn('JCEF_ARCHITECTURE_PROBE_OK', output)
        self.assertIn('platform={}'.format(cef_platform), output)
        self.assertIn('project_arch={}'.format(architecture), output)
        self.assertIn('osx_arch={}'.format(architecture), output)


if __name__ == '__main__':
  unittest.main()
