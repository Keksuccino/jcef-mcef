#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DISTRIB_ROOT = Path(__file__).resolve().parents[1]
PUBLISHER = DISTRIB_ROOT / 'publish_distributions.sh'
COMMIT_SHA = '0123456789abcdef0123456789abcdef01234567'
TARGETS = ('linux_amd64', 'linux_arm64', 'macos_amd64', 'macos_arm64',
           'windows_amd64', 'windows_arm64')
S3_CONFIG = '[default]\naccess_key = unit-test\nsecret_key = unit-test\n'


BASH = shutil.which('bash')

FAKE_S3CMD = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import sys


def fail(message, status=90):
  print(message, file=sys.stderr)
  raise SystemExit(status)


arguments = sys.argv[1:]
if not arguments or not arguments[0].startswith('--config='):
  fail('missing --config')
config_path = Path(arguments.pop(0).split('=', 1)[1]).resolve()
if not arguments:
  fail('missing command')
command = arguments.pop(0)
home_path = Path(os.environ['HOME']).resolve()
try:
  config_path.relative_to(home_path)
  config_outside_home = False
except ValueError:
  config_outside_home = True
config_mode = stat.S_IMODE(config_path.stat().st_mode) if config_path.exists() else None
config_matches = (config_path.exists() and config_path.read_text(encoding='utf-8') ==
                  os.environ['FAKE_EXPECTED_CONFIG'])
record = {
    'arguments': arguments,
    'command': command,
    'config': str(config_path),
    'config_matches': config_matches,
    'config_mode': config_mode,
    'config_outside_home': config_outside_home,
    's3_cfg_in_environment': 'S3_CFG' in os.environ,
}
with Path(os.environ['FAKE_S3_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps(record, sort_keys=True) + '\n')
if not config_matches or config_mode != 0o600 or not config_outside_home:
  fail('invalid temporary credential configuration')
if command == os.environ.get('FAKE_S3_FAIL_COMMAND'):
  fail('injected command failure', 72)

remote_root = Path(os.environ['FAKE_S3_ROOT'])


def object_path(uri):
  if not uri.startswith('s3://'):
    fail('invalid URI: ' + uri)
  return remote_root / uri[len('s3://'):]


if command == 'ls':
  prefix_uri = arguments[-1]
  prefix_path = object_path(prefix_uri)
  if prefix_path.is_dir():
    for path in sorted(path for path in prefix_path.rglob('*') if path.is_file()):
      uri = 's3://' + path.relative_to(remote_root).as_posix()
      print('2026-01-01 00:00 {:>10} {}'.format(path.stat().st_size, uri))
elif command == 'get':
  source_path = object_path(arguments[-2])
  if not source_path.is_file():
    fail('missing remote object', 12)
  destination_path = Path(arguments[-1])
  destination_path.parent.mkdir(parents=True, exist_ok=True)
  shutil.copyfile(source_path, destination_path)
elif command == 'put':
  source_path = Path(arguments[-2])
  destination_uri = arguments[-1]
  destination_path = object_path(destination_uri)
  fail_name = os.environ.get('FAKE_S3_FAIL_PUT')
  should_fail = bool(fail_name and destination_uri.endswith('/' + fail_name))
  if should_fail and os.environ.get('FAKE_S3_FAIL_AFTER_WRITE') != '1':
    fail('injected put failure', 71)
  destination_path.parent.mkdir(parents=True, exist_ok=True)
  shutil.copyfile(source_path, destination_path)
  signal_name = os.environ.get('FAKE_S3_SIGNAL_PUT')
  if signal_name and destination_uri.endswith('/' + signal_name):
    os.kill(os.getppid(), signal.SIGTERM)
  if should_fail:
    fail('injected put failure after write', 71)
elif command == 'del':
  path = object_path(arguments[-1])
  if path.exists():
    path.unlink()
else:
  fail('unsupported command: ' + command)
'''


@unittest.skipUnless(os.name == 'posix', 'the production publisher runs only on Ubuntu')
class PublishDistributionsTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)
    self.artifact_directory = self.root / 'artifacts'
    self.remote_directory = self.root / 'remote'
    self.fake_bin = self.root / 'bin'
    self.home_directory = self.root / 'home'
    self.log_path = self.root / 's3cmd.log'
    self.artifact_directory.mkdir()
    self.remote_directory.mkdir()
    self.fake_bin.mkdir()
    self.home_directory.mkdir()
    self.fake_s3cmd = self.fake_bin / 's3cmd'
    self.fake_s3cmd.write_text(FAKE_S3CMD, encoding='utf-8')
    self.fake_s3cmd.chmod(0o755)
    self.create_artifacts()

  def tearDown(self):
    self.temporary_directory.cleanup()

  def create_artifacts(self):
    for index, target in enumerate(TARGETS):
      archive_name = '{}.tar.gz'.format(target)
      archive_path = self.artifact_directory / archive_name
      archive_path.write_bytes(('archive-{}-{}'.format(index, target)).encode('ascii'))
      digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
      line_ending = b'\r\n' if target.startswith('windows_') else b'\n'
      checksum = '{}  {}'.format(digest, archive_name).encode('ascii')
      (self.artifact_directory / '{}.sha256'.format(archive_name)).write_bytes(checksum + line_ending)

  def environment(self, **updates):
    environment = os.environ.copy()
    defaults = {
        'FAKE_EXPECTED_CONFIG':
            S3_CONFIG,
        'FAKE_S3_LOG':
            str(self.log_path),
        'FAKE_S3_ROOT':
            str(self.remote_directory),
        'HOME':
            str(self.home_directory),
        'PATH':
            '{}{}{}'.format(self.fake_bin, os.pathsep, environment.get('PATH', '')),
        'S3_CFG':
            S3_CONFIG,
    }
    environment.update(defaults)
    environment.update(updates)
    return environment

  def run_publisher(self, commit_sha=COMMIT_SHA, environment=None, artifact_directory=None, cwd=None):
    if BASH is None:
      raise RuntimeError('bash is required to test the distribution publisher')
    command = [BASH, str(PUBLISHER), commit_sha, str(artifact_directory or self.artifact_directory)]
    return subprocess.run(command, check=False, capture_output=True, text=True, env=environment or self.environment(), cwd=cwd)

  def read_log(self):
    if not self.log_path.exists():
      return []
    return [
        json.loads(line)
        for line in self.log_path.read_text(encoding='utf-8').splitlines()
    ]

  def remote_path(self, name):
    return (self.remote_directory / 'mcef-us-1' / 'java-cef-builds' / COMMIT_SHA
            / name)

  def copy_to_remote(self, name, contents=None):
    destination = self.remote_path(name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if contents is None:
      shutil.copyfile(self.artifact_directory / name, destination)
    else:
      destination.write_bytes(contents)

  def remote_snapshot(self):
    publication_root = self.remote_path('unused').parent
    if not publication_root.exists():
      return {}
    return {
        path.relative_to(publication_root).as_posix(): path.read_bytes()
        for path in sorted(publication_root.iterdir()) if path.is_file()
    }

  def assert_no_modifying_calls(self):
    self.assertFalse(any(record['command'] in ('put', 'del') for record in self.read_log()))

  def test_fresh_publication_overwrites_partial_archives_and_orders_completion_markers_last(self):
    self.copy_to_remote('linux_amd64.tar.gz', b'old-archive')
    self.copy_to_remote('macos_arm64.tar.gz', b'another-old-archive')

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    records = self.read_log()
    self.assertEqual('ls', records[0]['command'])
    put_names = [
        record['arguments'][-1].rsplit('/', 1)[-1] for record in records
        if record['command'] == 'put'
    ]
    expected_archives = ['{}.tar.gz'.format(target) for target in TARGETS]
    expected_checksums = [
        '{}.tar.gz.sha256'.format(target) for target in TARGETS
    ]
    self.assertEqual(expected_archives + expected_checksums, put_names)
    for name in expected_archives + expected_checksums:
      self.assertEqual((self.artifact_directory / name).read_bytes(), self.remote_path(name).read_bytes())

    config_paths = {record['config'] for record in records}
    self.assertEqual(1, len(config_paths))
    config_path = Path(next(iter(config_paths)))
    self.assertFalse(config_path.exists())
    self.assertFalse(config_path.is_relative_to(self.home_directory))
    self.assertTrue(all(record['config_mode'] == 0o600 for record in records))
    self.assertTrue(all(record['config_matches'] for record in records))
    self.assertTrue(all(record['config_outside_home'] for record in records))
    self.assertFalse(any(record['s3_cfg_in_environment'] for record in records))

  def test_exact_matching_remote_publication_is_idempotent(self):
    for target in ('windows_amd64', 'windows_arm64'):
      checksum_path = self.artifact_directory / '{}.tar.gz.sha256'.format(target)
      self.assertTrue(checksum_path.read_bytes().endswith(b'\r\n'))
    for target in TARGETS:
      self.copy_to_remote('{}.tar.gz'.format(target))
      self.copy_to_remote('{}.tar.gz.sha256'.format(target))
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assertIn('already published', result.stdout)
    self.assertEqual(before, self.remote_snapshot())
    self.assertEqual(['ls'] + ['get'] * len(TARGETS) * 2, [record['command'] for record in self.read_log()])
    self.assert_no_modifying_calls()

  def test_corrupt_remote_archive_fails_exact_idempotency_check(self):
    for target in TARGETS:
      self.copy_to_remote('{}.tar.gz'.format(target))
      self.copy_to_remote('{}.tar.gz.sha256'.format(target))
    self.copy_to_remote('macos_amd64.tar.gz', b'corrupt')
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Remote archive does not match', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assert_no_modifying_calls()

  def test_unexpected_remote_object_fails_without_modification(self):
    self.copy_to_remote('unexpected.txt', b'unexpected')
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('unexpected object', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assert_no_modifying_calls()

  def test_partial_remote_checksums_fail_without_modification(self):
    for target in TARGETS[:2]:
      self.copy_to_remote('{}.tar.gz'.format(target), b'old-archive')
      self.copy_to_remote('{}.tar.gz.sha256'.format(target))
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('only 2 of 6 checksums', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assert_no_modifying_calls()

  def test_remote_listing_failure_is_not_treated_as_remote_absence(self):
    self.copy_to_remote('linux_amd64.tar.gz', b'old-archive')
    before = self.remote_snapshot()

    result = self.run_publisher(environment=self.environment(FAKE_S3_FAIL_COMMAND='ls'))

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to inspect existing publication state', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assertEqual(['ls'], [record['command'] for record in self.read_log()])
    self.assert_no_modifying_calls()

  def test_mismatched_remote_checksum_fails_without_modification(self):
    for target in TARGETS:
      self.copy_to_remote('{}.tar.gz'.format(target))
      self.copy_to_remote('{}.tar.gz.sha256'.format(target))
    self.copy_to_remote('macos_amd64.tar.gz.sha256', b'not-the-local-checksum\n')
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('does not match', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assert_no_modifying_calls()

  def test_complete_remote_checksums_with_missing_archive_fail_without_modification(self):
    for target in TARGETS:
      self.copy_to_remote('{}.tar.gz.sha256'.format(target))
    for target in TARGETS[1:]:
      self.copy_to_remote('{}.tar.gz'.format(target))
    before = self.remote_snapshot()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('without its archive', result.stderr)
    self.assertEqual(before, self.remote_snapshot())
    self.assert_no_modifying_calls()

  def test_archive_failure_never_starts_checksum_publication(self):
    failed_name = 'macos_amd64.tar.gz'
    result = self.run_publisher(environment=self.environment(FAKE_S3_FAIL_PUT=failed_name))

    self.assertNotEqual(0, result.returncode)
    records = self.read_log()
    put_names = [
        record['arguments'][-1].rsplit('/', 1)[-1] for record in records
        if record['command'] == 'put'
    ]
    self.assertEqual(['linux_amd64.tar.gz', 'linux_arm64.tar.gz', failed_name], put_names)
    self.assertFalse(any(name.endswith('.sha256') for name in put_names))
    self.assertFalse(any(record['command'] == 'del' for record in records))
    self.assertFalse(any(name.endswith('.sha256') for name in self.remote_snapshot()))

  def test_checksum_failure_deletes_every_checksum_but_retains_archives(self):
    failed_name = 'macos_arm64.tar.gz.sha256'
    result = self.run_publisher(environment=self.environment(FAKE_S3_FAIL_AFTER_WRITE='1', FAKE_S3_FAIL_PUT=failed_name))

    self.assertNotEqual(0, result.returncode)
    records = self.read_log()
    put_names = [
        record['arguments'][-1].rsplit('/', 1)[-1] for record in records
        if record['command'] == 'put'
    ]
    expected_puts = ['{}.tar.gz'.format(target) for target in TARGETS] + ['{}.tar.gz.sha256'.format(target) for target in TARGETS[:4]]
    self.assertEqual(expected_puts, put_names)
    delete_names = [
        record['arguments'][-1].rsplit('/', 1)[-1] for record in records
        if record['command'] == 'del'
    ]
    self.assertEqual(['{}.tar.gz.sha256'.format(target) for target in TARGETS], delete_names)
    remote = self.remote_snapshot()
    self.assertEqual({'{}.tar.gz'.format(target) for target in TARGETS}, set(remote))

  def test_interruption_during_checksum_upload_uses_the_same_cleanup(self):
    interrupted_name = 'linux_amd64.tar.gz.sha256'

    result = self.run_publisher(environment=self.environment(FAKE_S3_SIGNAL_PUT=interrupted_name))

    self.assertEqual(143, result.returncode)
    records = self.read_log()
    delete_names = [
        record['arguments'][-1].rsplit('/', 1)[-1] for record in records
        if record['command'] == 'del'
    ]
    self.assertEqual(['{}.tar.gz.sha256'.format(target) for target in TARGETS], delete_names)
    self.assertEqual({'{}.tar.gz'.format(target) for target in TARGETS}, set(self.remote_snapshot()))

  def test_invalid_commit_sha_is_rejected_before_s3(self):
    for invalid_sha in ('a' * 39, 'a' * 41, 'A' * 40, 'g' * 40, '{}\n'.format('a' * 40)):
      with self.subTest(commit_sha=repr(invalid_sha)):
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher(commit_sha=invalid_sha)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('40 lowercase hexadecimal', result.stderr)
        self.assertEqual([], self.read_log())

  def test_missing_or_extra_canonical_file_is_rejected_before_s3(self):
    missing_path = self.artifact_directory / 'windows_arm64.tar.gz.sha256'
    missing_path.unlink()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('exactly the 12 canonical', result.stderr)
    self.assertEqual([], self.read_log())

    self.create_artifacts()
    (self.artifact_directory / 'unexpected.txt').write_text('unexpected', encoding='ascii')
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('exactly the 12 canonical', result.stderr)
    self.assertEqual([], self.read_log())

  def test_wrong_target_name_with_exact_file_count_is_rejected_before_s3(self):
    checksum = self.artifact_directory / 'linux_arm64.tar.gz.sha256'
    checksum.rename(self.artifact_directory / 'linux_aarch64.tar.gz.sha256')

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Missing canonical regular checksum', result.stderr)
    self.assertEqual([], self.read_log())

  def test_invalid_checksum_is_rejected_before_credentials_or_s3(self):
    checksum_path = self.artifact_directory / 'windows_arm64.tar.gz.sha256'
    checksum_path.write_text('{}  windows_arm64.tar.gz\n'.format('0' * 64), encoding='ascii')

    result = self.run_publisher(environment=self.environment(S3_CFG=''))

    self.assertNotEqual(0, result.returncode)
    self.assertIn('SHA-256 validation failed', result.stderr)
    self.assertEqual([], self.read_log())

  def test_checksum_line_endings_require_exact_lf_or_crlf(self):
    archive_name = 'linux_amd64.tar.gz'
    archive_path = self.artifact_directory / archive_name
    checksum_path = self.artifact_directory / '{}.sha256'.format(archive_name)
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    canonical_line = '{}  {}'.format(digest, archive_name).encode('ascii')
    invalid_endings = (b'\r', b'', b'\r\r\n', b'\nextra\n')
    for ending in invalid_endings:
      with self.subTest(ending=ending):
        checksum_path.write_bytes(canonical_line + ending)
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.read_log())

  def test_missing_credentials_and_artifact_directory_are_rejected(self):
    result = self.run_publisher(environment=self.environment(S3_CFG=' \n\t'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('S3_CFG is required', result.stderr)
    self.assertEqual([], self.read_log())

    missing_directory = self.root / 'missing'
    result = self.run_publisher(artifact_directory=missing_directory)
    self.assertNotEqual(0, result.returncode)
    self.assertIn('does not exist', result.stderr)
    self.assertEqual([], self.read_log())

  def test_option_shaped_relative_artifact_directory_is_supported(self):
    option_directory = self.root / '-P'
    self.artifact_directory.rename(option_directory)
    self.artifact_directory = option_directory

    result = self.run_publisher(artifact_directory='-P', cwd=self.root)

    self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
  unittest.main()
