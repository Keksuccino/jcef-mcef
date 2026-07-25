#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

DISTRIB_ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = Path(__file__).resolve().parent
if str(DISTRIB_ROOT) not in sys.path:
  sys.path.insert(0, str(DISTRIB_ROOT))
if str(TEST_ROOT) not in sys.path:
  sys.path.insert(0, str(TEST_ROOT))

from distribution_archive_test_util import canonical_distribution_files  # noqa: E402
from distribution import DistributionError, TARGETS  # noqa: E402
from make_distrib import MANIFEST_SCHEMA  # noqa: E402
from make_distrib import _build_runtime_file_inventory  # noqa: E402
from make_distrib import _capture_runtime_file_paths  # noqa: E402
from make_distrib import _copy_documentation_and_licenses  # noqa: E402
from make_distrib import _copy_runtime, _create_archive  # noqa: E402
from make_distrib import _is_link_like  # noqa: E402
from make_distrib import _normalize_java_cef_commit  # noqa: E402
from make_distrib import _require_clean_source_checkout  # noqa: E402
from make_distrib import _require_java_cef_commit  # noqa: E402
from make_distrib import _resolve_java_cef_commit  # noqa: E402
from make_distrib import _validate_native_source_commit  # noqa: E402
from make_distrib import _validate_readme_source_commit  # noqa: E402
from make_distrib import _verify_created_archive  # noqa: E402
from make_distrib import _write_distribution_manifest  # noqa: E402
from verify_distribution_archive import VerificationError  # noqa: E402
from verify_distribution_archive import TARGET_JOGAMP_JARS  # noqa: E402
from verify_distribution_archive import TARGET_RUNTIME_ENTRIES  # noqa: E402
from verify_distribution_archive import verify_distribution_archive  # noqa: E402

JAVA_CEF_COMMIT = '0123456789abcdef0123456789abcdef01234567'


def write_file(path, contents):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_bytes(contents)


def git(repository, *arguments):
  return subprocess.run(
      ['git', '-C', str(repository)] + list(arguments),
      check=True,
      stdout=subprocess.PIPE,
      stderr=subprocess.PIPE,
      text=True).stdout.strip()


def create_git_checkout(root):
  root.mkdir()
  git(root, 'init', '--quiet')
  write_file(root / 'tracked.txt', b'tracked source\n')
  git(root, 'add', 'tracked.txt')
  git(root, '-c', 'user.name=JCEF Tests', '-c',
      'user.email=jcef-tests@example.invalid', 'commit', '--quiet', '-m',
      'test source')
  return git(root, 'rev-parse', '--verify', 'HEAD^{commit}')


class JavaCefCommitTest(unittest.TestCase):

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_resolves_exact_detached_head(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      repository = Path(temporary_directory) / 'repository'
      expected = create_git_checkout(repository)
      git(repository, 'checkout', '--quiet', '--detach', expected)

      self.assertEqual(expected, _resolve_java_cef_commit(repository))
      self.assertRegex(expected, r'^[0-9a-f]{40}$')

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_resolves_linked_worktree_with_git_file(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      repository = root / 'repository'
      expected = create_git_checkout(repository)
      worktree = root / 'linked-worktree'
      git(repository, 'worktree', 'add', '--quiet', '--detach',
          str(worktree), expected)

      self.assertTrue((worktree / '.git').is_file())
      self.assertEqual(expected, _resolve_java_cef_commit(worktree))

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_repository_environment_cannot_redirect_source_identity(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      expected_repository = root / 'expected'
      redirected_repository = root / 'redirected'
      expected = create_git_checkout(expected_repository)
      create_git_checkout(redirected_repository)
      write_file(redirected_repository / 'second.txt', b'second commit\n')
      git(redirected_repository, 'add', 'second.txt')
      git(redirected_repository, '-c', 'user.name=JCEF Tests', '-c',
          'user.email=jcef-tests@example.invalid', 'commit', '--quiet', '-m',
          'different source')

      with mock.patch.dict(os.environ,
                           {'GIT_DIR': str(redirected_repository / '.git')}):
        self.assertEqual(expected,
                         _resolve_java_cef_commit(expected_repository))

  def test_normalizes_uppercase_exact_commit(self):
    self.assertEqual(
        JAVA_CEF_COMMIT,
        _normalize_java_cef_commit('  {}\n'.format(JAVA_CEF_COMMIT.upper())))

  def test_rejects_malformed_commit_identities(self):
    malformed = (None, b'a' * 40, '', 'a' * 39, 'a' * 41, 'g' * 40,
                 '{}\n{}'.format('a' * 20, 'b' * 20), 'HEAD', 'unknown')
    for value in malformed:
      with self.subTest(value=value):
        with self.assertRaisesRegex(DistributionError, '40|exactly'):
          _normalize_java_cef_commit(value)

  def test_missing_git_source_identity_fails_closed(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      with self.assertRaisesRegex(DistributionError,
                                  'Unable to inspect Java CEF source'):
        _resolve_java_cef_commit(Path(temporary_directory))

  def test_malformed_git_source_identity_fails_closed(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      repository = Path(temporary_directory)
      with mock.patch(
          'make_distrib._git_output',
          side_effect=(str(repository), 'not-a-full-commit')):
        with self.assertRaisesRegex(DistributionError,
                                    'exactly 40 hexadecimal'):
          _resolve_java_cef_commit(repository)

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_subdirectory_cannot_borrow_parent_checkout_identity(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      repository = Path(temporary_directory) / 'repository'
      create_git_checkout(repository)
      nested = repository / 'nested'
      nested.mkdir()

      with self.assertRaisesRegex(DistributionError, 'different checkout'):
        _resolve_java_cef_commit(nested)

  def test_changed_source_commit_is_rejected(self):
    with mock.patch(
        'make_distrib._resolve_java_cef_commit', return_value='f' * 40):
      with self.assertRaisesRegex(DistributionError,
                                  'changed during distribution creation'):
        _require_java_cef_commit(Path('/unused'), JAVA_CEF_COMMIT)

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_tracked_staged_and_untracked_changes_are_rejected(self):
    for dirty_state in ('tracked', 'staged', 'untracked'):
      with self.subTest(dirty_state=dirty_state):
        with tempfile.TemporaryDirectory() as temporary_directory:
          repository = Path(temporary_directory) / 'repository'
          create_git_checkout(repository)
          _require_clean_source_checkout(repository)
          if dirty_state == 'tracked':
            write_file(repository / 'tracked.txt', b'modified source\n')
          elif dirty_state == 'staged':
            write_file(repository / 'tracked.txt', b'staged source\n')
            git(repository, 'add', 'tracked.txt')
          else:
            write_file(repository / 'untracked.txt', b'untracked source\n')

          with self.assertRaisesRegex(DistributionError,
                                      'source checkout is dirty'):
            _require_clean_source_checkout(repository)

  @unittest.skipUnless(shutil.which('git'), 'Git is required')
  def test_ignored_build_output_does_not_make_checkout_dirty(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      repository = Path(temporary_directory) / 'repository'
      create_git_checkout(repository)
      write_file(repository / '.gitignore', b'ignored-output/\n')
      git(repository, 'add', '.gitignore')
      git(repository, '-c', 'user.name=JCEF Tests', '-c',
          'user.email=jcef-tests@example.invalid', 'commit', '--quiet', '-m',
          'ignore generated output')
      write_file(repository / 'ignored-output/artifact.bin', b'generated')

      _require_clean_source_checkout(repository)


class ArtifactProvenanceTest(unittest.TestCase):

  def test_native_header_requires_one_exact_matching_commit(self):
    valid_header = '#define JCEF_COMMIT_HASH "{}"\n'.format(JAVA_CEF_COMMIT)
    cases = {
        'missing': None,
        'malformed': '#define JCEF_COMMIT_HASH "short"\n',
        'duplicate': valid_header + valid_header,
        'mismatch': '#define JCEF_COMMIT_HASH "{}"\n'.format('f' * 40),
    }
    with tempfile.TemporaryDirectory() as temporary_directory:
      valid_root = Path(temporary_directory) / 'valid'
      write_file(valid_root / 'native/jcef_version.h',
                 valid_header.encode('ascii'))
      _validate_native_source_commit(valid_root, JAVA_CEF_COMMIT)

    for case_name, header in cases.items():
      with self.subTest(case_name=case_name):
        with tempfile.TemporaryDirectory() as temporary_directory:
          repository = Path(temporary_directory)
          if header is not None:
            write_file(repository / 'native/jcef_version.h',
                       header.encode('ascii'))
          with self.assertRaisesRegex(DistributionError,
                                      'missing|exactly one|malformed|mismatch'):
            _validate_native_source_commit(repository, JAVA_CEF_COMMIT)

  def test_readme_requires_one_full_matching_revision(self):
    valid_readme = 'JCEF URL: source\n                  @{}\n'.format(
        JAVA_CEF_COMMIT)
    cases = {
        'missing':
            None,
        'malformed':
            'JCEF URL: source\n                  @short\n',
        'duplicate':
            valid_readme + '                  @{}\n'.format(JAVA_CEF_COMMIT),
        'mismatch':
            'JCEF URL: source\n                  @{}\n'.format('f' * 40),
    }
    with tempfile.TemporaryDirectory() as temporary_directory:
      readme_path = Path(temporary_directory) / 'README.txt'
      write_file(readme_path, valid_readme.encode('ascii'))
      _validate_readme_source_commit(readme_path, JAVA_CEF_COMMIT)

    for case_name, readme in cases.items():
      with self.subTest(case_name=case_name):
        with tempfile.TemporaryDirectory() as temporary_directory:
          readme_path = Path(temporary_directory) / 'README.txt'
          if readme is not None:
            write_file(readme_path, readme.encode('ascii'))
          with self.assertRaisesRegex(DistributionError,
                                      'missing|exactly one|malformed|mismatch'):
            _validate_readme_source_commit(readme_path, JAVA_CEF_COMMIT)


class RuntimeManifestTest(unittest.TestCase):

  def write_manifest(self,
                     destination,
                     target,
                     runtime_entries,
                     captured_runtime_paths,
                     commit=JAVA_CEF_COMMIT):
    _write_distribution_manifest(destination, target, runtime_entries,
                                 captured_runtime_paths, commit,
                                 ('jcef.jar', 'jcef-tests.jar'), ())
    return json.loads((destination / 'DISTRIBUTION-MANIFEST.json').read_text(
        encoding='utf-8'))

  def test_schema_two_inventory_is_sorted_recursive_and_exact(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      contents = {
          'locales/zh-CN.pak': b'zh locale',
          'libjcef.so': b'jcef library',
          'locales/en-US.pak': b'english locale',
      }
      for relative_path in reversed(tuple(contents)):
        write_file(destination / relative_path, contents[relative_path])
      runtime_entries = ('locales', 'libjcef.so')
      captured = _capture_runtime_file_paths(destination, runtime_entries)

      manifest = self.write_manifest(destination, target, runtime_entries,
                                     captured, JAVA_CEF_COMMIT.upper())

      self.assertEqual(2, MANIFEST_SCHEMA)
      self.assertEqual(2, manifest['manifest_schema'])
      self.assertEqual(JAVA_CEF_COMMIT, manifest['java_cef_commit'])
      self.assertEqual(target.name, manifest['archive_root'])
      self.assertEqual(target.name, manifest['target'])
      self.assertEqual(17, manifest['java_release'])
      self.assertIn('cef_version', manifest)
      self.assertIn('cef_api_version', manifest)
      self.assertEqual(sorted(runtime_entries), manifest['runtime_entries'])
      self.assertEqual(
          sorted(contents),
          [item['path'] for item in manifest['runtime_files']])
      self.assertEqual(['locales'], manifest['distribution_directories'])
      self.assertEqual(sorted(contents), [item['path'] for item in manifest['distribution_files']])
      for item in manifest['runtime_files']:
        expected_contents = contents[item['path']]
        self.assertEqual(len(expected_contents), item['size'])
        self.assertEqual(
            hashlib.sha256(expected_contents).hexdigest(), item['sha256'])

  def test_distribution_inventory_covers_empty_nonruntime_files_and_explicit_directories(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime.bin', b'runtime')
      write_file(destination / 'docs/empty.txt', b'')
      (destination / 'tests').mkdir()
      captured = _capture_runtime_file_paths(destination, ('runtime.bin',))

      manifest = self.write_manifest(destination, target, ('runtime.bin',), captured)

      self.assertEqual(['docs', 'tests'], manifest['distribution_directories'])
      inventory = {item['path']: item for item in manifest['distribution_files']}
      self.assertEqual({'docs/empty.txt', 'runtime.bin'}, set(inventory))
      self.assertEqual(0, inventory['docs/empty.txt']['size'])
      self.assertEqual(hashlib.sha256(b'').hexdigest(), inventory['docs/empty.txt']['sha256'])
      self.assertNotIn('DISTRIBUTION-MANIFEST.json', inventory)

  def test_distribution_inventory_reserves_casefolded_manifest_path(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime.bin', b'runtime')
      write_file(destination / 'distribution-manifest.JSON', b'collision')
      captured = _capture_runtime_file_paths(destination, ('runtime.bin',))

      with self.assertRaisesRegex(DistributionError, 'case-colliding path'):
        self.write_manifest(destination, TARGETS['linux_amd64'], ('runtime.bin',), captured)

  def test_manifest_bytes_are_deterministic_across_creation_order_and_mtime(
      self):
    target = TARGETS['linux_arm64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      first = root / 'first'
      second = root / 'second'
      first.mkdir()
      second.mkdir()
      contents = {
          'locales/de.pak': b'de',
          'locales/en-US.pak': b'en',
          'libjcef.so': b'library',
      }
      for relative_path in contents:
        write_file(first / relative_path, contents[relative_path])
      for relative_path in reversed(tuple(contents)):
        write_file(second / relative_path, contents[relative_path])
        os.utime(second / relative_path, (1700000000, 1700000000))
      first_runtime_entries = ('locales', 'libjcef.so')
      second_runtime_entries = ('libjcef.so', 'locales')

      first_captured = _capture_runtime_file_paths(first, first_runtime_entries)
      second_captured = _capture_runtime_file_paths(second,
                                                    second_runtime_entries)
      self.write_manifest(first, target, first_runtime_entries, first_captured)
      self.write_manifest(second, target, second_runtime_entries,
                          second_captured)

      self.assertEqual((first / 'DISTRIBUTION-MANIFEST.json').read_bytes(),
                       (second / 'DISTRIBUTION-MANIFEST.json').read_bytes())

  def test_macos_app_root_covers_helpers_locales_and_refreshed_java(self):
    target = TARGETS['macos_arm64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      app_root = destination / 'jcef_app.app'
      paths = {
          'jcef_app.app/Contents/Info.plist':
              b'app metadata',
          ('jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/'
           'Info.plist'):
               b'helper metadata',
          ('jcef_app.app/Contents/Frameworks/Chromium Embedded Framework.'
           'framework/Resources/fr.lproj/locale.pak'):
               b'french locale',
          'jcef_app.app/Contents/Java/jcef.jar':
              b'old embedded jar',
      }
      for relative_path, contents in paths.items():
        write_file(destination / relative_path, contents)
      runtime_entries = ('jcef_app.app',)
      captured = _capture_runtime_file_paths(destination, runtime_entries)
      final_jar = b'newly refreshed embedded jar bytes'
      (app_root / 'Contents' / 'Java' / 'jcef.jar').write_bytes(final_jar)

      manifest = self.write_manifest(destination, target, runtime_entries,
                                     captured)

      self.assertEqual(['jcef_app.app'], manifest['runtime_entries'])
      inventory = {item['path']: item for item in manifest['runtime_files']}
      self.assertEqual(set(paths), set(inventory))
      self.assertEqual(
          len(final_jar),
          inventory['jcef_app.app/Contents/Java/jcef.jar']['size'])
      self.assertEqual(
          hashlib.sha256(final_jar).hexdigest(),
          inventory['jcef_app.app/Contents/Java/jcef.jar']['sha256'])
      for relative_path in inventory:
        containing_entries = [
            entry for entry in manifest['runtime_entries']
            if relative_path == entry or relative_path.startswith(entry + '/')
        ]
        self.assertEqual(1, len(containing_entries))
      actual_app_files = {
          path.relative_to(destination).as_posix()
          for path in app_root.rglob('*') if path.is_file()
      }
      self.assertEqual(actual_app_files, set(inventory))

  def test_macos_copy_declares_app_as_single_runtime_root(self):
    target = TARGETS['macos_arm64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      native_output = root / 'native'
      destination = root / 'destination'
      cef_root = root / 'cef'
      destination.mkdir()
      framework_relative = ('jcef_app.app/Contents/Frameworks/'
                            'Chromium Embedded Framework.framework')
      write_file(native_output / framework_relative / 'Versions/A/old', b'old')
      write_file(cef_root / 'Release' / 'Chromium Embedded Framework.framework'
                 / 'Resources/en.lproj/locale.pak', b'locale')

      runtime_entries = _copy_runtime(native_output, destination, cef_root,
                                      target)

      self.assertEqual(('jcef_app.app',), runtime_entries)
      self.assertFalse((destination / framework_relative / 'Versions').exists())
      self.assertTrue((destination / framework_relative /
                       'Resources/en.lproj/locale.pak').is_file())

  def test_jogamp_license_copy_is_exact_and_missing_file_fails_closed(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      repository = root / 'repository'
      cef_root = root / 'cef'
      destination = root / 'distribution'
      destination.mkdir()
      write_file(repository / 'out/docs/index.html', b'docs')
      write_file(repository / 'java/tests/sample.txt', b'tests')
      write_file(repository / 'LICENSE.txt', b'jcef license')
      write_file(repository / 'third_party/jogamp/gluegen.LICENSE.txt', b'gluegen license')
      write_file(repository / 'third_party/jogamp/jogl.LICENSE.txt', b'jogl license')
      write_file(repository / 'third_party/jogamp/unexpected.LICENSE.txt', b'unexpected')
      write_file(cef_root / 'LICENSE.txt', b'cef license')
      write_file(cef_root / 'CREDITS.html', b'credits')

      _copy_documentation_and_licenses(repository, destination, cef_root, target)

      self.assertTrue((destination / 'gluegen.LICENSE.txt').is_file())
      self.assertTrue((destination / 'jogl.LICENSE.txt').is_file())
      self.assertFalse((destination / 'unexpected.LICENSE.txt').exists())

      (repository / 'third_party/jogamp/jogl.LICENSE.txt').unlink()
      second_destination = root / 'second-distribution'
      second_destination.mkdir()
      with self.assertRaisesRegex(DistributionError, 'Required JogAmp license is missing'):
        _copy_documentation_and_licenses(repository, second_destination, cef_root, target)

  def test_runtime_file_set_must_not_gain_or_lose_paths_after_capture(self):
    for mutation in ('add', 'remove'):
      with self.subTest(mutation=mutation):
        with tempfile.TemporaryDirectory() as temporary_directory:
          destination = Path(temporary_directory)
          write_file(destination / 'runtime/first.bin', b'first')
          write_file(destination / 'runtime/second.bin', b'second')
          entries = ('runtime',)
          captured = _capture_runtime_file_paths(destination, entries)
          if mutation == 'add':
            write_file(destination / 'runtime/third.bin', b'third')
          else:
            (destination / 'runtime/second.bin').unlink()

          with self.assertRaisesRegex(DistributionError,
                                      'file set changed after staging'):
            _build_runtime_file_inventory(destination, entries, captured)

  def test_duplicate_and_overlapping_runtime_entries_are_rejected(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime/file.bin', b'file')
      with self.assertRaisesRegex(DistributionError, 'Duplicate runtime entry'):
        _capture_runtime_file_paths(destination, ('runtime', 'runtime'))
      with self.assertRaisesRegex(DistributionError, 'overlap at file'):
        _capture_runtime_file_paths(destination, ('runtime',
                                                  'runtime/file.bin'))

  def test_duplicate_captured_file_is_rejected(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime.bin', b'file')
      with self.assertRaisesRegex(DistributionError,
                                  'Duplicate captured runtime path'):
        _build_runtime_file_inventory(destination, ('runtime.bin',),
                                      ('runtime.bin', 'runtime.bin'))

  def test_windows_reparse_point_is_treated_as_a_link_on_python_39(self):
    status = mock.Mock(
        st_mode=stat.S_IFDIR,
        st_file_attributes=stat.FILE_ATTRIBUTE_REPARSE_POINT)
    path = mock.Mock(spec=[])

    self.assertTrue(_is_link_like(path, status))

  def test_missing_empty_and_nonregular_runtime_entries_are_rejected(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      (destination / 'empty-directory').mkdir()
      (destination / 'empty-file').touch()
      with self.assertRaisesRegex(DistributionError, 'Unable to inspect'):
        _capture_runtime_file_paths(destination, ('missing',))
      with self.assertRaisesRegex(DistributionError, 'contains no regular'):
        _capture_runtime_file_paths(destination, ('empty-directory',))
      with self.assertRaisesRegex(DistributionError, 'must be non-empty'):
        _capture_runtime_file_paths(destination, ('empty-file',))
      if hasattr(os, 'mkfifo'):
        os.mkfifo(destination / 'named-pipe')
        with self.assertRaisesRegex(DistributionError,
                                    'only directories and regular files'):
          _capture_runtime_file_paths(destination, ('named-pipe',))

  @unittest.skipIf(os.name == 'nt', 'Windows symlink creation is restricted')
  def test_runtime_symlinks_and_symlink_parents_are_rejected(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory) / 'distribution'
      external = Path(temporary_directory) / 'external'
      destination.mkdir()
      write_file(external / 'file.bin', b'external')
      (destination / 'linked-file').symlink_to(external / 'file.bin')
      (destination / 'linked-directory').symlink_to(
          external, target_is_directory=True)

      for entry in ('linked-file', 'linked-directory',
                    'linked-directory/file.bin'):
        with self.subTest(entry=entry):
          with self.assertRaisesRegex(DistributionError, 'symbolic links'):
            _capture_runtime_file_paths(destination, (entry,))

  @unittest.skipIf(os.name == 'nt', 'Windows symlink creation is restricted')
  def test_distribution_inventory_rejects_nonruntime_link(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      destination = root / 'distribution'
      destination.mkdir()
      write_file(destination / 'runtime.bin', b'runtime')
      write_file(root / 'external.txt', b'external')
      (destination / 'documentation-link').symlink_to(root / 'external.txt')
      captured = _capture_runtime_file_paths(destination, ('runtime.bin',))

      with self.assertRaisesRegex(DistributionError, 'Distribution tree.*symbolic links'):
        self.write_manifest(destination, TARGETS['linux_amd64'], ('runtime.bin',), captured)

  @unittest.skipIf(os.name == 'nt', 'Backslash is a path separator on Windows')
  def test_unsafe_descendant_name_is_rejected(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime' / 'unsafe\\name.bin', b'unsafe')
      with self.assertRaisesRegex(DistributionError, 'Unsafe runtime path'):
        _capture_runtime_file_paths(destination, ('runtime',))

  def test_unsafe_runtime_roots_are_rejected(self):
    unsafe_paths = ('', '.', '..', '../outside', '/absolute', 'a//b', 'a/./b',
                    'a/../b', 'a\\b', 'C:/absolute', 'file:stream', 'a\0b',
                    'a\nb', 'a\x7fb')
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      for unsafe_path in unsafe_paths:
        with self.subTest(unsafe_path=unsafe_path):
          with self.assertRaisesRegex(DistributionError, 'Unsafe runtime path'):
            _capture_runtime_file_paths(destination, (unsafe_path,))

  def test_manifest_rejects_malformed_commit(self):
    with tempfile.TemporaryDirectory() as temporary_directory:
      destination = Path(temporary_directory)
      write_file(destination / 'runtime.bin', b'runtime')
      captured = _capture_runtime_file_paths(destination, ('runtime.bin',))
      with self.assertRaisesRegex(DistributionError, 'exactly 40 hexadecimal'):
        self.write_manifest(destination, TARGETS['linux_amd64'],
                            ('runtime.bin',), captured, 'short')

  def test_manifest_creation_refuses_existing_file_or_symlink(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      for existing_kind in ('file', 'symlink'):
        if existing_kind == 'symlink' and os.name == 'nt':
          continue
        with self.subTest(existing_kind=existing_kind):
          destination = root / existing_kind
          destination.mkdir()
          write_file(destination / 'runtime.bin', b'runtime')
          captured = _capture_runtime_file_paths(destination, ('runtime.bin',))
          manifest_path = destination / 'DISTRIBUTION-MANIFEST.json'
          if existing_kind == 'file':
            manifest_path.write_bytes(b'existing manifest')
            protected_path = manifest_path
            expected = b'existing manifest'
          else:
            protected_path = root / 'external-manifest.json'
            protected_path.write_bytes(b'external manifest')
            manifest_path.symlink_to(protected_path)
            expected = b'external manifest'

          with self.assertRaisesRegex(DistributionError,
                                      'Refusing to replace existing'):
            self.write_manifest(destination, target, ('runtime.bin',), captured)
          self.assertEqual(expected, protected_path.read_bytes())


class ArchiveCreationSourceTest(unittest.TestCase):

  def test_producer_manifest_and_tar_match_verifier_for_all_six_targets(self):
    for target_name in TARGET_RUNTIME_ENTRIES:
      with self.subTest(target=target_name):
        target = TARGETS[target_name]
        with tempfile.TemporaryDirectory() as temporary_directory:
          root = Path(temporary_directory)
          distribution = root / target_name
          distribution.mkdir()
          for relative_path, contents in canonical_distribution_files(target_name).items():
            write_file(distribution / relative_path, contents)
          runtime_entries = TARGET_RUNTIME_ENTRIES[target_name]
          captured = _capture_runtime_file_paths(distribution, runtime_entries)
          _write_distribution_manifest(distribution, target, runtime_entries, captured, JAVA_CEF_COMMIT, ('jcef.jar', 'jcef-tests.jar'), TARGET_JOGAMP_JARS[target_name])
          archive_path = root / '{}.tar.gz'.format(target_name)

          _create_archive(distribution, archive_path, target)
          verify_distribution_archive(archive_path, target_name, JAVA_CEF_COMMIT)

  @mock.patch('make_distrib.verify_distribution_archive')
  def test_created_archive_is_verified_against_target_and_source_commit(self, verifier):
    target = TARGETS['macos_arm64']
    archive_path = Path('/tmp/macos_arm64.tar.gz')
    _verify_created_archive(archive_path, target, JAVA_CEF_COMMIT)
    verifier.assert_called_once_with(archive_path, target.name, JAVA_CEF_COMMIT)

  @mock.patch('make_distrib.verify_distribution_archive')
  def test_created_archive_verification_failure_blocks_packaging(self, verifier):
    verifier.side_effect = VerificationError('inventory mismatch')
    with self.assertRaisesRegex(DistributionError, 'schema-2 byte verification.*inventory mismatch'):
      _verify_created_archive(Path('/tmp/linux_amd64.tar.gz'), TARGETS['linux_amd64'], JAVA_CEF_COMMIT)

  def test_archive_creation_emits_only_verifier_canonical_metadata(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      distribution.mkdir()
      write_file(distribution / 'plain.txt', b'plain')
      write_file(distribution / 'executable.sh', b'#!/bin/sh\n')
      (distribution / 'executable.sh').chmod(0o755)
      write_file(distribution / ('long-' + 'x' * 110), b'long path')
      archive_path = root / '{}.tar.gz'.format(target.name)

      _create_archive(distribution, archive_path, target)

      with tarfile.open(archive_path, mode='r:gz') as archive:
        members = archive.getmembers()
      self.assertTrue(any(member.pax_headers for member in members))
      for member in members:
        self.assertEqual((0, 0, 'root', 'root', 946684800), (member.uid, member.gid, member.uname, member.gname, member.mtime))
        self.assertTrue(set(member.pax_headers).issubset({'path'}), member.pax_headers)
        if member.isdir():
          self.assertEqual(0o755, member.mode & 0o7777)
        else:
          self.assertIn(member.mode & 0o7777, (0o644, 0o755))

  @unittest.skipIf(os.name == 'nt', 'Windows symlink creation is restricted')
  def test_archive_creation_rejects_source_symlink_before_writing(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      distribution.mkdir()
      write_file(root / 'external.bin', b'external')
      (distribution / 'linked.bin').symlink_to(root / 'external.bin')
      archive_path = root / 'distribution.tar.gz'

      with self.assertRaisesRegex(DistributionError,
                                  'must not contain symbolic links'):
        _create_archive(distribution, archive_path, target)
      self.assertFalse(archive_path.exists())

  @unittest.skipUnless(hasattr(os, 'mkfifo'), 'FIFO creation is unavailable')
  def test_archive_creation_rejects_nonregular_source_before_writing(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      distribution.mkdir()
      os.mkfifo(distribution / 'named-pipe')
      archive_path = root / 'distribution.tar.gz'

      with self.assertRaisesRegex(DistributionError,
                                  'only directories and regular files'):
        _create_archive(distribution, archive_path, target)
      self.assertFalse(archive_path.exists())

  @unittest.skipIf(os.name == 'nt', 'Backslash is a path separator on Windows')
  def test_archive_creation_rejects_unsafe_source_name_before_writing(self):
    target = TARGETS['linux_amd64']
    with tempfile.TemporaryDirectory() as temporary_directory:
      root = Path(temporary_directory)
      distribution = root / target.name
      distribution.mkdir()
      write_file(distribution / 'unsafe\\name.bin', b'unsafe')
      archive_path = root / 'distribution.tar.gz'

      with self.assertRaisesRegex(DistributionError, 'Unsafe runtime path'):
        _create_archive(distribution, archive_path, target)
      self.assertFalse(archive_path.exists())


if __name__ == '__main__':
  unittest.main()
