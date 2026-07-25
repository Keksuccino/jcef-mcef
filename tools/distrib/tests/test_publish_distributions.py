#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

DISTRIB_ROOT = Path(__file__).resolve().parents[1]
PUBLISHER = DISTRIB_ROOT / 'publish_distributions.sh'
COMMIT_SHA = '0123456789abcdef0123456789abcdef01234567'
WRONG_SHA = '89abcdef0123456789abcdef0123456789abcdef'
REPOSITORY = 'Keksuccino/jcef-mcef'
TAG_NAME = 'java-cef-{}'.format(COMMIT_SHA)
RELEASE_TITLE = 'JCEF distributions {}'.format(COMMIT_SHA)
RELEASE_BODY = 'Automated JCEF distributions for commit {};managed-by=tools/distrib/publish_distributions.sh;schema=1'.format(
    COMMIT_SHA)
TARGETS = ('linux_amd64', 'linux_arm64', 'macos_amd64', 'macos_arm64',
           'windows_amd64', 'windows_arm64')
ARCHIVE_NAMES = tuple('{}.tar.gz'.format(target) for target in TARGETS)
CHECKSUM_NAMES = tuple('{}.tar.gz.sha256'.format(target) for target in TARGETS)
ASSET_NAMES = tuple(
    name for pair in zip(ARCHIVE_NAMES, CHECKSUM_NAMES) for name in pair)
TOKEN = 'github-actions-test-token'
MODIFYING_OPERATIONS = frozenset(
    ('create-ref', 'create-release', 'delete-release', 'upload-release',
     'publish-release'))

FAKE_GH = r'''#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import sys


def fail(message, status=90):
  print(message, file=sys.stderr)
  raise SystemExit(status)


def load_state():
  return json.loads(Path(os.environ['FAKE_GH_STATE']).read_text(encoding='utf-8'))


def save_state(state):
  Path(os.environ['FAKE_GH_STATE']).write_text(json.dumps(state, sort_keys=True), encoding='utf-8')


def flag_value(arguments, flag):
  if flag not in arguments:
    fail('missing flag: ' + flag)
  index = arguments.index(flag)
  if index + 1 >= len(arguments):
    fail('missing value for flag: ' + flag)
  return arguments[index + 1]


def field_value(arguments, field):
  prefix = field + '='
  for index, argument in enumerate(arguments):
    if argument in ('-f', '-F') and index + 1 < len(arguments) and arguments[index + 1].startswith(prefix):
      return arguments[index + 1][len(prefix):]
  fail('missing field: ' + field)


def require_repository(arguments):
  if flag_value(arguments, '--repo') != os.environ['FAKE_EXPECTED_REPOSITORY']:
    fail('unexpected repository')


def asset_bytes(encoded):
  return base64.b64decode(encoded.encode('ascii'))


arguments = sys.argv[1:]
if not arguments:
  fail('missing gh command')
unsafe_shell_environment = [name for name in os.environ if name in ('BASH_ENV', 'ENV', 'SHELLOPTS', 'BASHOPTS', 'CDPATH', 'GLOBIGNORE', 'BASH_XTRACEFD', 'PS4') or name.startswith('BASH_FUNC_')]
if unsafe_shell_environment:
  fail('unsafe shell environment reached fake gh: {!r}'.format(unsafe_shell_environment))
unexpected_credentials = [name for name in ('GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT') if name in os.environ]
if unexpected_credentials:
  fail('unexpected credentials reached fake gh: {!r}'.format(unexpected_credentials))
expected_token = os.environ.get('FAKE_EXPECTED_TOKEN', '')
if 'GITHUB_TOKEN' in os.environ:
  fail('publisher leaked GITHUB_TOKEN')
if expected_token:
  if os.environ.get('GH_TOKEN') != expected_token:
    fail('publisher did not isolate the supplied token')
elif 'GH_TOKEN' in os.environ:
  fail('publisher did not use the authenticated gh credential store')

state = load_state()
operation = ''
endpoint = ''
if arguments[0] == 'api':
  if len(arguments) > 1 and arguments[1] == 'graphql':
    operation = 'inspect-latest'
    if field_value(arguments, 'owner') + '/' + field_value(arguments, 'name') != os.environ['FAKE_EXPECTED_REPOSITORY']:
      fail('unexpected GraphQL repository')
    if 'latestRelease{tagName}' not in field_value(arguments, 'query') or '--jq' not in arguments:
      fail('malformed latest-release query')
  elif len(arguments) > 1 and arguments[1] == 'user':
    operation = 'inspect-authenticated-user'
    jq_filter = flag_value(arguments, '--jq')
    if '.login | type' not in jq_filter:
      fail('authenticated-login inspection must preserve JSON type')
  else:
    endpoint = next((argument for argument in arguments if argument.startswith('repos/')), '')
    if not endpoint.startswith('repos/' + os.environ['FAKE_EXPECTED_REPOSITORY'] + '/'):
      fail('unexpected API repository')
    if endpoint.endswith('/immutable-releases'):
      operation = 'inspect-immutability'
      jq_filter = flag_value(arguments, '--jq')
      if '.enabled | type' not in jq_filter or '.enabled | tostring' not in jq_filter:
        fail('immutable-release inspection must preserve JSON type')
    elif '/releases?' in endpoint:
      operation = 'list-releases'
    elif '/git/matching-refs/tags/' in endpoint:
      operation = 'list-tag-refs'
    elif '/commits/' in endpoint:
      operation = 'resolve-tag'
    elif endpoint.endswith('/git/refs') and '--method' in arguments and flag_value(arguments, '--method') == 'POST':
      operation = 'create-ref'
elif arguments[:2] == ['release', 'view']:
  require_repository(arguments)
  json_fields = flag_value(arguments, '--json')
  if json_fields == 'assets':
    operation = 'view-assets'
  elif 'isImmutable' in json_fields.split(','):
    operation = 'view-metadata'
  else:
    fail('release metadata must include isImmutable')
elif arguments[:2] == ['release', 'create']:
  require_repository(arguments)
  operation = 'create-release'
elif arguments[:2] == ['release', 'delete']:
  require_repository(arguments)
  operation = 'delete-release'
elif arguments[:2] == ['release', 'upload']:
  require_repository(arguments)
  operation = 'upload-release'
elif arguments[:2] == ['release', 'edit']:
  require_repository(arguments)
  operation = 'publish-release'
else:
  fail('unsupported gh arguments: ' + repr(arguments))
if not operation:
  fail('unsupported gh arguments: ' + repr(arguments))
if operation in ('list-releases', 'list-tag-refs') and '--paginate' not in arguments:
  fail('inspection query must be paginated')

record = {'arguments': arguments, 'operation': operation, 'github_token_present': 'GITHUB_TOKEN' in os.environ, 'gh_token_matches': (os.environ.get('GH_TOKEN') == expected_token if expected_token else 'GH_TOKEN' not in os.environ)}
with Path(os.environ['FAKE_GH_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps(record, sort_keys=True) + '\n')
if operation == os.environ.get('FAKE_GH_FAIL_OPERATION'):
  fail('injected operation failure', 72)

release = state.get('release')
if operation == 'inspect-immutability':
  print(state.get('immutable_status', 'boolean|true'))
elif operation == 'inspect-authenticated-user':
  print(state.get('authenticated_login', 'github-actions[bot]'))
elif operation == 'inspect-latest':
  print(state.get('latest_status', 'null'))
elif operation == 'list-releases':
  if release is not None and release['tag'] == os.environ['FAKE_EXPECTED_TAG']:
    print(release['id'])
elif operation == 'list-tag-refs':
  if state.get('tag_sha') is not None:
    print('refs/tags/' + os.environ['FAKE_EXPECTED_TAG'])
elif operation == 'resolve-tag':
  if state.get('tag_sha') is None:
    fail('tag does not exist', 1)
  print(state['tag_sha'])
elif operation == 'create-ref':
  if state.get('tag_sha') is not None:
    fail('tag already exists', 1)
  if field_value(arguments, 'ref') != 'refs/tags/' + os.environ['FAKE_EXPECTED_TAG']:
    fail('unexpected tag ref')
  state['tag_sha'] = field_value(arguments, 'sha')
  save_state(state)
  print('{}')
elif operation == 'view-metadata':
  if release is None:
    fail('release does not exist', 1)
  values = (release['tag'], release['target'], str(release['draft']).lower(), str(release['immutable']).lower(), str(release['prerelease']).lower(), release['title'], release['body'], release['author'])
  print('|'.join(values))
elif operation == 'view-assets':
  if release is None:
    fail('release does not exist', 1)
  for name in sorted(release['assets']):
    contents = asset_bytes(release['assets'][name])
    print('{}|{}|uploaded|sha256:{}'.format(name, len(contents), hashlib.sha256(contents).hexdigest()))
elif operation == 'create-release':
  if release is not None:
    fail('release already exists', 1)
  if arguments[2] != os.environ['FAKE_EXPECTED_TAG'] or state.get('tag_sha') is None:
    fail('draft requires the exact existing tag')
  required_flags = ('--draft', '--verify-tag', '--latest=false')
  if not all(flag in arguments for flag in required_flags):
    fail('draft safety flag missing')
  target = flag_value(arguments, '--target')
  title = flag_value(arguments, '--title')
  body = flag_value(arguments, '--notes')
  release = {'id': state['next_id'], 'tag': arguments[2], 'target': target, 'draft': True, 'immutable': False, 'prerelease': False, 'title': title, 'body': body, 'author': state.get('authenticated_login', 'github-actions[bot]'), 'assets': {}}
  state['next_id'] += 1
  state['release'] = release
  save_state(state)
elif operation == 'delete-release':
  if release is None or not release['draft'] or '--yes' not in arguments or '--cleanup-tag' in arguments:
    fail('unsafe draft deletion')
  state['release'] = None
  save_state(state)
elif operation == 'upload-release':
  if release is None or not release['draft'] or arguments[2] != release['tag']:
    fail('assets can only be uploaded to the exact draft')
  repository_index = arguments.index('--repo')
  upload_paths = arguments[3:repository_index]
  if not upload_paths:
    fail('no upload paths')
  for path_text in upload_paths:
    path = Path(path_text)
    name = path.name
    should_fail = name == os.environ.get('FAKE_GH_FAIL_UPLOAD')
    if should_fail and os.environ.get('FAKE_GH_FAIL_AFTER_WRITE') != '1':
      fail('injected upload failure', 73)
    if name in release['assets']:
      fail('asset already exists', 1)
    release['assets'][name] = base64.b64encode(path.read_bytes()).decode('ascii')
    state['release'] = release
    save_state(state)
    if name == os.environ.get('FAKE_GH_SIGNAL_UPLOAD'):
      os.kill(os.getppid(), signal.SIGTERM)
      raise SystemExit(0)
    if should_fail:
      fail('injected upload failure after write', 73)
  mutation = os.environ.get('FAKE_MUTATE_AFTER_UPLOAD')
  if mutation == 'metadata':
    release['title'] = 'tampered during upload'
    state['release'] = release
    save_state(state)
  elif mutation == 'immutability':
    state['immutable_status'] = 'boolean|false'
    save_state(state)
  elif mutation == 'identity':
    release['id'] = 999
    state['release'] = release
    save_state(state)
  elif mutation == 'author':
    state['authenticated_login'] = 'different-user'
    save_state(state)
elif operation == 'publish-release':
  if release is None or not release['draft'] or arguments[2] != release['tag']:
    fail('only the exact draft can be published')
  required_flags = ('--draft=false', '--verify-tag', '--latest=false')
  if not all(flag in arguments for flag in required_flags):
    fail('publish safety flag missing')
  if flag_value(arguments, '--target') != release['target']:
    fail('publish target mismatch')
  if flag_value(arguments, '--title') != release['title'] or flag_value(arguments, '--notes') != release['body'] or '--prerelease=false' not in arguments:
    fail('publish metadata mismatch')
  release['draft'] = False
  release['immutable'] = state.get('immutable_after_publish', True)
  if state.get('latest_after_publish'):
    state['latest_status'] = 'tag|' + release['tag']
  state['release'] = release
  save_state(state)
'''

FAKE_LOCAL_TOOL = r'''#!PYTHON_EXECUTABLE
import os
from pathlib import Path
import sys

SENSITIVE_ENVIRONMENT = ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'GH_HOST', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT')
leaked = {name: os.environ.get(name) for name in SENSITIVE_ENVIRONMENT if name in os.environ}
if leaked:
  print('publisher leaked credentials to local tool: {!r}'.format(leaked), file=sys.stderr)
  raise SystemExit(96)
with Path(os.environ['FAKE_LOCAL_TOOL_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(Path(sys.argv[0]).name + '\n')
real_path = os.environ['FAKE_REAL_LOCAL_TOOL']
os.execv(real_path, [real_path] + sys.argv[1:])
'''


@unittest.skipUnless(os.name == 'posix' and Path('/bin/bash').is_file(),
                     'publisher integration tests require POSIX /bin/bash')
class PublishDistributionsTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)
    self.artifact_directory = self.root / 'artifacts'
    self.fake_bin = self.root / 'bin'
    self.log_path = self.root / 'gh.log'
    self.local_tool_log = self.root / 'local-tool.log'
    self.shell_injection_log = self.root / 'shell-injection.log'
    self.state_path = self.root / 'state.json'
    self.artifact_directory.mkdir()
    self.fake_bin.mkdir()
    self.fake_gh = self.fake_bin / 'gh'
    self.fake_gh.write_text(FAKE_GH, encoding='utf-8')
    self.fake_gh.chmod(0o755)
    real_local_tool = shutil.which('sha256sum') or shutil.which('shasum')
    self.assertIsNotNone(real_local_tool)
    self.real_local_tool = real_local_tool
    self.fake_local_tool = self.fake_bin / Path(real_local_tool).name
    self.fake_local_tool.write_text(FAKE_LOCAL_TOOL.replace('PYTHON_EXECUTABLE', sys.executable, 1), encoding='utf-8')
    self.fake_local_tool.chmod(0o755)
    self.create_artifacts()
    self.write_state({'next_id': 1, 'tag_sha': None, 'release': None})

  def tearDown(self):
    self.temporary_directory.cleanup()

  def create_artifacts(self):
    for index, target in enumerate(TARGETS):
      archive_name = '{}.tar.gz'.format(target)
      archive_path = self.artifact_directory / archive_name
      archive_path.write_bytes(('archive-{}-{}'.format(index,
                                                       target)).encode('ascii'))
      digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
      line_ending = b'\r\n' if target.startswith('windows_') else b'\n'
      checksum = '{}  {}'.format(digest, archive_name).encode('ascii')
      (self.artifact_directory /
       '{}.sha256'.format(archive_name)).write_bytes(checksum + line_ending)

  def write_state(self, state):
    self.state_path.write_text(
        json.dumps(state, sort_keys=True), encoding='utf-8')

  def read_state(self):
    return json.loads(self.state_path.read_text(encoding='utf-8'))

  def environment(self, **updates):
    environment = os.environ.copy()
    for name in tuple(environment):
      if name in ('BASH_ENV', 'ENV', 'GITHUB_TOKEN', 'GH_TOKEN',
                  'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'GH_HOST',
                  'ENV_TOKEN_SOURCE',
                  'ENV_TOKEN_CONTENT') or name.startswith('BASH_FUNC_'):
        environment.pop(name, None)
    environment.update({
        'FAKE_EXPECTED_REPOSITORY':
            REPOSITORY,
        'FAKE_EXPECTED_TAG':
            TAG_NAME,
        'FAKE_EXPECTED_TOKEN':
            TOKEN,
        'FAKE_GH_LOG':
            str(self.log_path),
        'FAKE_GH_STATE':
            str(self.state_path),
        'FAKE_LOCAL_TOOL_LOG':
            str(self.local_tool_log),
        'FAKE_REAL_LOCAL_TOOL':
            self.real_local_tool,
        'FAKE_SHELL_INJECTION_LOG':
            str(self.shell_injection_log),
        'GITHUB_TOKEN':
            TOKEN,
        'PATH':
            '{}{}{}'.format(self.fake_bin, os.pathsep,
                            environment.get('PATH', ''))
    })
    environment.update(updates)
    return environment

  def keyring_environment(self, **updates):
    environment = self.environment(FAKE_EXPECTED_TOKEN='')
    environment.pop('GITHUB_TOKEN', None)
    environment.pop('GH_TOKEN', None)
    environment.update(updates)
    return environment

  def run_publisher(self,
                    commit_sha=COMMIT_SHA,
                    environment=None,
                    artifact_directory=None,
                    cwd=None):
    return subprocess.run(
        [
            str(PUBLISHER), commit_sha,
            str(artifact_directory or self.artifact_directory)
        ],
        check=False,
        capture_output=True,
        text=True,
        env=environment or self.environment(),
        cwd=cwd)

  def read_log(self):
    if not self.log_path.exists():
      return []
    return [
        json.loads(line)
        for line in self.log_path.read_text(encoding='utf-8').splitlines()
    ]

  def operations(self):
    return [record['operation'] for record in self.read_log()]

  def canonical_assets(self):
    return {
        name: base64.b64encode(
            (self.artifact_directory / name).read_bytes()).decode('ascii')
        for name in ASSET_NAMES
    }

  def set_release(self,
                  draft,
                  asset_names=(),
                  tag_sha=COMMIT_SHA,
                  target=COMMIT_SHA,
                  title=RELEASE_TITLE,
                  body=RELEASE_BODY,
                  author='github-actions[bot]',
                  immutable=None,
                  immutable_status='boolean|true',
                  latest_status='null',
                  overrides=None):
    assets = {name: self.canonical_assets()[name] for name in asset_names}
    for name, contents in (overrides or {}).items():
      assets[name] = base64.b64encode(contents).decode('ascii')
    immutable = not draft if immutable is None else immutable
    release = {
        'id': 1,
        'tag': TAG_NAME,
        'target': target,
        'draft': draft,
        'immutable': immutable,
        'prerelease': False,
        'title': title,
        'body': body,
        'author': author,
        'assets': assets
    }
    self.write_state({
        'next_id': 2,
        'tag_sha': tag_sha,
        'release': release,
        'immutable_status': immutable_status,
        'latest_status': latest_status
    })

  def assert_no_modifying_calls(self):
    self.assertFalse(
        any(operation in MODIFYING_OPERATIONS
            for operation in self.operations()))

  def assert_exact_published_release(self, author='github-actions[bot]'):
    state = self.read_state()
    self.assertEqual(COMMIT_SHA, state['tag_sha'])
    self.assertIsNotNone(state['release'])
    self.assertFalse(state['release']['draft'])
    self.assertTrue(state['release']['immutable'])
    self.assertEqual(TAG_NAME, state['release']['tag'])
    self.assertEqual(COMMIT_SHA, state['release']['target'])
    self.assertEqual(RELEASE_TITLE, state['release']['title'])
    self.assertEqual(RELEASE_BODY, state['release']['body'])
    self.assertEqual(author, state['release']['author'])
    self.assertEqual(self.canonical_assets(), state['release']['assets'])

  def test_fresh_publication_creates_exact_tag_and_atomic_release(self):
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    records = self.read_log()
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in records))
    upload_records = [
        record for record in records if record['operation'] == 'upload-release'
    ]
    self.assertEqual(2, len(upload_records))
    self.assertEqual(
        list(ARCHIVE_NAMES), [
            Path(path).name
            for path in upload_records[0]['arguments'][3:upload_records[0][
                'arguments'].index('--repo')]
        ])
    self.assertEqual(
        list(CHECKSUM_NAMES), [
            Path(path).name
            for path in upload_records[1]['arguments'][3:upload_records[1][
                'arguments'].index('--repo')]
        ])
    create_arguments = next(record['arguments'] for record in records
                            if record['operation'] == 'create-release')
    publish_arguments = next(record['arguments'] for record in records
                             if record['operation'] == 'publish-release')
    self.assertIn('--latest=false', create_arguments)
    self.assertIn('--draft', create_arguments)
    self.assertIn('--verify-tag', create_arguments)
    self.assertIn('--latest=false', publish_arguments)
    self.assertIn('--draft=false', publish_arguments)
    operations = self.operations()
    self.assertEqual('inspect-immutability', operations[0])
    self.assertLess(
        operations.index('inspect-immutability'),
        operations.index('inspect-authenticated-user'))
    self.assertLess(
        operations.index('inspect-authenticated-user'),
        operations.index('create-ref'))
    self.assertLess(
        operations.index('create-ref'), operations.index('create-release'))
    self.assertLess(
        operations.index('upload-release'), operations.index('publish-release'))
    self.assertLess(
        operations.index('publish-release'), operations.index('inspect-latest'))

  def test_authenticated_gh_keyring_identity_owns_the_release_without_token_environment(
      self):
    state = self.read_state()
    state['authenticated_login'] = 'Keksuccino'
    self.write_state(state)
    result = self.run_publisher(environment=self.keyring_environment())
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release(author='Keksuccino')
    records = self.read_log()
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in records))
    self.assertIn('inspect-authenticated-user', self.operations())

  def test_explicit_gh_token_is_isolated_after_local_validation(self):
    environment = self.environment()
    environment.pop('GITHUB_TOKEN')
    environment['GH_TOKEN'] = TOKEN
    result = self.run_publisher(environment=environment)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in self.read_log()))

  def test_gh_token_precedence_matches_the_gh_cli(self):
    result = self.run_publisher(environment=self.environment(GITHUB_TOKEN='must-not-be-used', GH_TOKEN=TOKEN, GH_ENTERPRISE_TOKEN='must-not-leak', GITHUB_ENTERPRISE_TOKEN='must-not-leak', ENV_TOKEN_SOURCE='must-not-remain-exported', ENV_TOKEN_CONTENT='must-not-remain-exported'))
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertTrue(self.local_tool_log.exists())
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in self.read_log()))

  def test_privileged_startup_blocks_bash_env_and_exported_gh_function(self):
    bash_environment = self.root / 'malicious-bash-env'
    bash_environment.write_text("printf 'BASH_ENV executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"\ngh() { printf 'BASH_ENV gh function executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"; return 97; }\nexport -f gh\n", encoding='utf-8')
    environment = self.environment(BASH_ENV=str(bash_environment))
    environment[
        'BASH_FUNC_gh%%'] = '() { printf \'exported gh function executed\\n\' >> "$FAKE_SHELL_INJECTION_LOG"; return 98; }'
    result = self.run_publisher(environment=environment)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertFalse(self.shell_injection_log.exists())
    self.assert_exact_published_release()

  def test_non_privileged_bash_invocation_is_rejected_before_gh(self):
    result = subprocess.run(['/bin/bash', str(PUBLISHER), COMMIT_SHA, str(self.artifact_directory)], check=False, capture_output=True, text=True, env=self.environment())
    self.assertNotEqual(0, result.returncode)
    self.assertIn('execute publish_distributions.sh directly', result.stderr)
    self.assertFalse(self.log_path.exists())

  def test_artifact_directory_with_leading_dash_basename_is_option_safe(self):
    leading_dash_directory = self.root / '-artifacts'
    self.artifact_directory.rename(leading_dash_directory)
    self.artifact_directory = leading_dash_directory
    result = self.run_publisher(
        artifact_directory=Path('-artifacts'), cwd=self.root)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()

  def test_exact_published_release_is_idempotent(self):
    self.set_release(False, ASSET_NAMES)
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertIn('already published', result.stdout)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()
    self.assertEqual('inspect-immutability', self.operations()[0])
    self.assertIn('inspect-latest', self.operations())

  def test_immutable_release_preflight_rejects_disabled_or_malformed_state_without_modification(
      self):
    for immutable_status in ('boolean|false', 'string|true', 'invalid', ''):
      with self.subTest(immutable_status=repr(immutable_status)):
        self.write_state({
            'next_id': 1,
            'tag_sha': None,
            'release': None,
            'immutable_status': immutable_status
        })
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Immutable releases must be enabled', result.stderr)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assertEqual(['inspect-immutability'], self.operations())
        self.assert_no_modifying_calls()

  def test_immutable_release_preflight_inspection_failure_is_non_mutating(self):
    before = self.state_path.read_bytes()
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_OPERATION='inspect-immutability'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to inspect immutable-release configuration',
                  result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assertEqual(['inspect-immutability'], self.operations())
    self.assert_no_modifying_calls()

  def test_authenticated_login_failure_or_malformed_value_is_non_mutating(self):
    cases = (({
        'FAKE_GH_FAIL_OPERATION': 'inspect-authenticated-user'
    }, None), ({}, ''), ({}, 'unexpected|login'), ({}, 'unexpected\nlogin'),)
    for environment_updates, authenticated_login in cases:
      with self.subTest(
          environment_updates=environment_updates,
          authenticated_login=repr(authenticated_login)):
        state = {'next_id': 1, 'tag_sha': None, 'release': None}
        if authenticated_login is not None:
          state['authenticated_login'] = authenticated_login
        self.write_state(state)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher(environment=self.environment(
            **environment_updates))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assertEqual(['inspect-immutability', 'inspect-authenticated-user'],
                         self.operations())
        self.assert_no_modifying_calls()

  def test_nonimmutable_published_release_is_rejected_without_modification(
      self):
    self.set_release(False, ASSET_NAMES, immutable=False)
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Published release is not immutable', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_published_release_marked_latest_is_rejected_without_modification(
      self):
    self.set_release(
        False, ASSET_NAMES, latest_status='tag|{}'.format(TAG_NAME))
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('unexpectedly marked as latest', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_latest_release_inspection_failure_is_non_mutating(self):
    self.set_release(False, ASSET_NAMES)
    before = self.state_path.read_bytes()
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_OPERATION='inspect-latest'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to inspect the latest release', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_malformed_latest_release_state_is_non_mutating(self):
    for latest_status in ('invalid', 'tag|', ''):
      with self.subTest(latest_status=repr(latest_status)):
        self.set_release(False, ASSET_NAMES, latest_status=latest_status)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Latest-release query returned malformed state',
                      result.stderr)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assert_no_modifying_calls()

  def test_partial_or_mismatched_published_release_fails_without_modification(
      self):
    cases = ((ASSET_NAMES[:-1], None), (ASSET_NAMES, {
        ARCHIVE_NAMES[2]: b'wrong archive'
    }))
    for asset_names, overrides in cases:
      with self.subTest(
          asset_names=len(asset_names), mismatch=overrides is not None):
        self.set_release(False, asset_names, overrides=overrides)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('does not exactly match', result.stderr)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assert_no_modifying_calls()

  def test_published_metadata_or_tag_mismatch_is_strictly_non_mutating(self):
    cases = ({
        'target': WRONG_SHA
    }, {
        'author': 'someone-else'
    }, {
        'body': 'wrong marker'
    }, {
        'tag_sha': WRONG_SHA
    })
    for updates in cases:
      with self.subTest(updates=updates):
        self.set_release(False, ASSET_NAMES, **updates)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assert_no_modifying_calls()

  def test_incomplete_owned_draft_is_replaced_without_retargeting_tag(self):
    self.set_release(True, ARCHIVE_NAMES[:2])
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertIn('delete-release', self.operations())
    self.assertNotIn('create-ref', self.operations())
    self.assertLess(self.operations().index('delete-release'),
                    self.operations().index('create-release'))

  def test_complete_owned_draft_publishes_without_reupload(self):
    self.set_release(True, ASSET_NAMES)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertIn('publish-release', self.operations())
    self.assertNotIn('delete-release', self.operations())
    self.assertNotIn('upload-release', self.operations())

  def test_owned_draft_without_tag_recreates_exact_ref_before_recovery(self):
    self.set_release(True, ARCHIVE_NAMES[:1], tag_sha=None)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertIn('create-ref', self.operations())

  def test_exact_existing_tag_without_release_is_reused(self):
    self.write_state({'next_id': 1, 'tag_sha': COMMIT_SHA, 'release': None})
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertNotIn('create-ref', self.operations())

  def test_unowned_or_unexpected_draft_fails_without_modification(self):
    cases = ({
        'author': 'someone-else'
    }, {
        'body': 'wrong marker'
    }, {
        'immutable': True
    }, {
        'overrides': {
            'unexpected.txt': b'unexpected'
        }
    }, {
        'overrides': {
            'unexpected.txt': b'unexpected'
        },
        'tag_sha': None
    })
    for updates in cases:
      with self.subTest(updates=updates):
        asset_names = ARCHIVE_NAMES[:1]
        self.set_release(True, asset_names, **updates)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state_path.read_bytes())
        self.assert_no_modifying_calls()

  def test_wrong_existing_tag_without_release_fails_without_mutation(self):
    self.write_state({'next_id': 1, 'tag_sha': WRONG_SHA, 'release': None})
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('resolves to', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_inspection_failure_is_not_treated_as_absence(self):
    before = self.state_path.read_bytes()
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_OPERATION='list-releases'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to inspect releases', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_archive_upload_failure_leaves_invisible_draft_without_checksums(
      self):
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_UPLOAD=ARCHIVE_NAMES[2]))
    self.assertNotEqual(0, result.returncode)
    state = self.read_state()
    self.assertTrue(state['release']['draft'])
    self.assertEqual(set(ARCHIVE_NAMES[:2]), set(state['release']['assets']))
    self.assertNotIn('publish-release', self.operations())
    self.assertFalse(
        any(name.endswith('.sha256') for name in state['release']['assets']))

  def test_checksum_upload_failure_is_recovered_on_rerun(self):
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_UPLOAD=CHECKSUM_NAMES[2], FAKE_GH_FAIL_AFTER_WRITE='1'))
    self.assertNotEqual(0, result.returncode)
    self.assertTrue(self.read_state()['release']['draft'])
    self.assertNotIn('publish-release', self.operations())
    self.log_path.unlink()
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertIn('delete-release', self.operations())

  def test_publish_failure_leaves_complete_draft_for_direct_retry(self):
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_OPERATION='publish-release'))
    self.assertNotEqual(0, result.returncode)
    self.assertTrue(self.read_state()['release']['draft'])
    self.assertEqual(
        set(ASSET_NAMES), set(self.read_state()['release']['assets']))
    self.log_path.unlink()
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertNotIn('upload-release', self.operations())
    self.assertNotIn('delete-release', self.operations())

  def test_final_preflight_rejects_repository_or_draft_changes_during_upload(self):
    cases = (('metadata', 'Release ownership marker mismatch'),
             ('immutability', 'Immutable releases must be enabled'),
             ('identity',
              'Draft release identity changed'), ('author',
                                                  'Release author mismatch'))
    for mutation, expected_error in cases:
      with self.subTest(mutation=mutation):
        self.write_state({'next_id': 1, 'tag_sha': None, 'release': None})
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher(environment=self.environment(FAKE_MUTATE_AFTER_UPLOAD=mutation))
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected_error, result.stderr)
        self.assertTrue(self.read_state()['release']['draft'])
        self.assertNotIn('publish-release', self.operations())

  def test_post_publish_nonimmutable_state_is_detected(self):
    state = self.read_state()
    state['immutable_after_publish'] = False
    self.write_state(state)
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Published release is not immutable', result.stderr)
    self.assertFalse(self.read_state()['release']['draft'])
    self.assertFalse(self.read_state()['release']['immutable'])
    self.assertIn('publish-release', self.operations())

  def test_post_publish_latest_state_is_detected(self):
    state = self.read_state()
    state['latest_after_publish'] = True
    self.write_state(state)
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('unexpectedly marked as latest', result.stderr)
    self.assertFalse(self.read_state()['release']['draft'])
    self.assertTrue(self.read_state()['release']['immutable'])
    self.assertLess(self.operations().index('publish-release'),
                    self.operations().index('inspect-latest'))

  def test_interruption_during_upload_leaves_invisible_recoverable_draft(self):
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_SIGNAL_UPLOAD=ARCHIVE_NAMES[1]))
    self.assertEqual(143, result.returncode)
    state = self.read_state()
    self.assertTrue(state['release']['draft'])
    self.assertEqual(set(ARCHIVE_NAMES[:2]), set(state['release']['assets']))
    self.assertNotIn('publish-release', self.operations())

  def test_checksum_line_endings_require_exact_lf_or_crlf(self):
    checksum_path = self.artifact_directory / CHECKSUM_NAMES[0]
    canonical_line = checksum_path.read_bytes().rstrip(b'\n')
    invalid_contents = (canonical_line + b'\r', canonical_line,
                        canonical_line + b'\r\r\n',
                        canonical_line + b'\nextra\n',
                        canonical_line + b'\x00\n', canonical_line + b'\x01\n')
    for contents in invalid_contents:
      with self.subTest(contents=repr(contents[-12:])):
        self.create_artifacts()
        checksum_path.write_bytes(contents)
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.read_log())

  def test_invalid_commit_and_local_target_set_fail_before_gh(self):
    invalid_shas = ('a' * 39, 'a' * 41, 'A' * 40, 'g' * 40, '{}\n'.format(
        'a' * 40))
    for invalid_sha in invalid_shas:
      with self.subTest(commit_sha=repr(invalid_sha)):
        result = self.run_publisher(commit_sha=invalid_sha)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.read_log())
    missing_path = self.artifact_directory / CHECKSUM_NAMES[-1]
    missing_path.unlink()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertEqual([], self.read_log())
    self.create_artifacts()
    (self.artifact_directory / 'unexpected.txt').write_text(
        'unexpected', encoding='ascii')
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertEqual([], self.read_log())

  def test_canonical_archive_or_checksum_symlink_is_rejected_before_gh(self):
    replacement = self.root / 'replacement'
    replacement.write_bytes(b'replacement')
    for asset_name in (ARCHIVE_NAMES[0], CHECKSUM_NAMES[0]):
      with self.subTest(asset_name=asset_name):
        asset_path = self.artifact_directory / asset_name
        asset_path.unlink()
        asset_path.symlink_to(replacement)
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.read_log())
        asset_path.unlink()
        self.create_artifacts()

  def test_wrong_target_name_or_digest_fails_before_gh(self):
    checksum_path = self.artifact_directory / CHECKSUM_NAMES[1]
    checksum_path.rename(
        self.artifact_directory / 'linux_aarch64.tar.gz.sha256')
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertEqual([], self.read_log())
    self.create_artifacts()
    checksum_path.write_text(
        '{}  {}\n'.format('0' * 64, ARCHIVE_NAMES[1]), encoding='ascii')
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertEqual([], self.read_log())

  def test_whitespace_token_directory_or_gh_is_rejected(self):
    result = self.run_publisher(environment=self.environment(
        GITHUB_TOKEN=' \n\t'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('GITHUB_TOKEN must contain a non-whitespace token when set',
                  result.stderr)
    self.assertEqual([], self.read_log())
    result = self.run_publisher(artifact_directory=self.root / 'missing')
    self.assertNotEqual(0, result.returncode)
    self.assertIn('does not exist', result.stderr)
    self.assertEqual([], self.read_log())
    no_gh_bin = self.root / 'no-gh-bin'
    no_gh_bin.mkdir()
    for command_name in ('sha256sum', 'shasum', 'cmp', 'wc', 'tr'):
      source = shutil.which(command_name)
      if source is not None:
        destination = no_gh_bin / Path(source).name
        if not destination.exists():
          destination.symlink_to(source)
    result = self.run_publisher(environment=self.environment(
        PATH=str(no_gh_bin)))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('gh is required', result.stderr)
    self.assertEqual([], self.read_log())


if __name__ == '__main__':
  unittest.main()
