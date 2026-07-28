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
TEST_ROOT = Path(__file__).resolve().parent
if str(DISTRIB_ROOT) not in sys.path:
  sys.path.insert(0, str(DISTRIB_ROOT))
if str(TEST_ROOT) not in sys.path:
  sys.path.insert(0, str(TEST_ROOT))

from distribution_archive_test_util import build_tar_gz  # noqa: E402
from distribution_archive_test_util import build_valid_archive  # noqa: E402
from distribution_archive_test_util import canonical_jar_files  # noqa: E402
from distribution_archive_test_util import canonical_members  # noqa: E402
from distribution_archive_test_util import write_valid_archive  # noqa: E402
from sources_jar import build_sources_jar  # noqa: E402

PUBLISHER = DISTRIB_ROOT / 'publish_distributions.sh'
SOURCE_ROOT = Path(__file__).resolve().parents[3]
COMMIT_SHA = '0123456789abcdef0123456789abcdef01234567'
WRONG_SHA = '89abcdef0123456789abcdef0123456789abcdef'
REPOSITORY = 'Keksuccino/jcef-mcef'
TAG_NAME = 'java-cef-{}'.format(COMMIT_SHA)
RELEASE_TITLE = 'JCEF distributions {}'.format(COMMIT_SHA)
RELEASE_BODY = 'Automated JCEF distributions for commit {};managed-by=tools/distrib/publish_distributions.sh;schema=2'.format(COMMIT_SHA)
TARGETS = ('linux_amd64', 'linux_arm64', 'macos_amd64', 'macos_arm64',
           'windows_amd64', 'windows_arm64')
ARCHIVE_NAMES = tuple('{}.tar.gz'.format(target) for target in TARGETS)
CHECKSUM_NAMES = tuple('{}.tar.gz.sha256'.format(target) for target in TARGETS)
BINARY_JAR_NAME = 'jcef-mcef.jar'
SOURCES_JAR_NAME = 'jcef-mcef-sources.jar'
ASSET_NAMES = tuple(name for pair in zip(ARCHIVE_NAMES, CHECKSUM_NAMES) for name in pair) + (BINARY_JAR_NAME, SOURCES_JAR_NAME)
TOKEN = 'github-actions-test-token'
MODIFYING_OPERATIONS = frozenset(
    ('create-ref', 'create-release', 'delete-release', 'upload-release',
     'publish-release'))


def field_argument(arguments, flag, field):
  prefix = field + '='
  for index, argument in enumerate(arguments):
    if argument == flag and index + 1 < len(
        arguments) and arguments[index + 1].startswith(prefix):
      return arguments[index + 1][len(prefix):]
  raise AssertionError('missing {} field: {}'.format(flag, field))


def flag_argument(arguments, flag):
  try:
    index = arguments.index(flag)
  except ValueError as error:
    raise AssertionError('missing flag: {}'.format(flag)) from error
  if index + 1 >= len(arguments):
    raise AssertionError('missing value for flag: {}'.format(flag))
  return arguments[index + 1]


FAKE_GH = r'''#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
from urllib.parse import parse_qs
from urllib.parse import urlsplit


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


def field_value(arguments, flag, field):
  prefix = field + '='
  for index, argument in enumerate(arguments):
    if argument == flag and index + 1 < len(arguments) and arguments[index + 1].startswith(prefix):
      return arguments[index + 1][len(prefix):]
  fail('missing {} field: {}'.format(flag, field))


def require_positive_id_jq(arguments):
  jq_filter = flag_value(arguments, '--jq')
  required = ('.id | type', '"number"', '.id > 0', '.id | tostring', '"invalid"')
  if not all(fragment in jq_filter for fragment in required):
    fail('release mutation must validate and extract a positive numeric ID')


def requested_release_id(endpoint):
  path = urlsplit(endpoint).path if endpoint.startswith('https://') else endpoint.split('?', 1)[0]
  try:
    return int(path.split('/releases/', 1)[1].split('/', 1)[0])
  except (IndexError, ValueError):
    fail('invalid release ID endpoint')


def asset_bytes(encoded):
  return base64.b64decode(encoded.encode('ascii'))


def release_metadata_is_valid(release):
  if type(release.get('id')) is not int or release['id'] <= 0:
    return False
  string_fields = ('tag', 'target', 'title', 'body', 'author')
  if any(type(release.get(name)) is not str for name in string_fields):
    return False
  if any(type(release.get(name)) is not bool for name in ('draft', 'immutable', 'prerelease')):
    return False
  return not any(separator in release[name] for name in string_fields for separator in ('|', '\r', '\n'))


def print_release_metadata(release):
  if not release_metadata_is_valid(release):
    print('invalid')
    return
  values = (str(release['id']), release['tag'], release['target'], str(release['draft']).lower(), str(release['immutable']).lower(), str(release['prerelease']).lower(), release['title'], release['body'], release['author'])
  print('|'.join(values))


def fail_after_server_write(operation):
  if operation == os.environ.get('FAKE_GH_FAIL_AFTER_WRITE_OPERATION'):
    fail('injected failure after server write', 74)


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
upload_name = ''
if arguments[0] == 'api':
  if len(arguments) > 1 and arguments[1] == 'graphql':
    owner = field_value(arguments, '-F', 'owner')
    name = field_value(arguments, '-F', 'name')
    if owner + '/' + name != os.environ['FAKE_EXPECTED_REPOSITORY']:
      fail('unexpected GraphQL repository')
    query = field_value(arguments, '-f', 'query')
    jq_filter = flag_value(arguments, '--jq')
    if 'latestRelease{tagName}' in query:
      operation = 'inspect-latest'
      if '--jq' not in arguments:
        fail('malformed latest-release query')
    else:
      fail('unexpected GraphQL query')
  elif len(arguments) > 1 and arguments[1] == 'user':
    operation = 'inspect-authenticated-user'
    jq_filter = flag_value(arguments, '--jq')
    if '.login | type' not in jq_filter:
      fail('authenticated-login inspection must preserve JSON type')
  else:
    endpoint = next((argument for argument in arguments if argument.startswith('repos/') or argument.startswith('https://')), '')
    method = flag_value(arguments, '--method') if '--method' in arguments else 'GET'
    if endpoint.startswith('https://'):
      parsed_endpoint = urlsplit(endpoint)
      expected_path_prefix = '/repos/' + os.environ['FAKE_EXPECTED_REPOSITORY'] + '/'
      if parsed_endpoint.scheme != 'https' or parsed_endpoint.netloc != 'uploads.github.com' or not parsed_endpoint.path.startswith(expected_path_prefix):
        fail('unexpected upload API repository')
      query = parse_qs(parsed_endpoint.query, keep_blank_values=True, strict_parsing=True)
      if set(query) != {'name'} or len(query['name']) != 1 or not query['name'][0]:
        fail('upload URL must contain exactly one asset name')
      upload_name = query['name'][0]
      expected_endpoint = 'https://uploads.github.com{0}?name={1}'.format(parsed_endpoint.path, upload_name)
      if endpoint != expected_endpoint or not parsed_endpoint.path.endswith('/assets') or method != 'POST':
        fail('malformed exact-ID upload endpoint')
      operation = 'upload-release'
    else:
      if not endpoint.startswith('repos/' + os.environ['FAKE_EXPECTED_REPOSITORY'] + '/'):
        fail('unexpected API repository')
      if endpoint.endswith('/immutable-releases'):
        operation = 'inspect-immutability'
        jq_filter = flag_value(arguments, '--jq')
        if '.enabled | type' not in jq_filter or '.enabled | tostring' not in jq_filter:
          fail('immutable-release inspection must preserve JSON type')
      elif '/releases?' in endpoint:
        jq_filter = flag_value(arguments, '--jq')
        if '.draft | type' in jq_filter:
          operation = 'list-full-releases'
          required_full_release_checks = ('type != "array"', 'valid_release', '.tag_name | line_safe_string', '.draft | type', '.prerelease | type', '.draft == false', '.prerelease == false', '"tag|" + .tag_name', '"invalid"')
          if not all(fragment in jq_filter for fragment in required_full_release_checks):
            fail('full-release list query must validate every response field')
        else:
          operation = 'list-releases'
          required_shape_checks = ('type != "array"', 'valid_release', '.tag_name | type', '.id | type', '.id > 0', '.id | floor', '.id | tostring', '"invalid"')
          if not all(fragment in jq_filter for fragment in required_shape_checks):
            fail('release list query must validate page, element and ID types')
      elif '/git/matching-refs/tags/' in endpoint:
        operation = 'list-tag-refs'
      elif '/commits/' in endpoint:
        operation = 'resolve-tag'
      elif endpoint.endswith('/git/refs') and method == 'POST':
        operation = 'create-ref'
      elif endpoint.endswith('/releases') and method == 'POST':
        operation = 'create-release'
        require_positive_id_jq(arguments)
      elif '/releases/' in endpoint and method == 'GET' and '--include' in arguments and '--silent' in arguments:
        operation = 'view-release-status'
      elif '/releases/' in endpoint and method == 'GET':
        jq_filter = flag_value(arguments, '--jq')
        operation = 'view-assets' if '.assets[]' in jq_filter else 'view-metadata'
        if operation == 'view-metadata':
          required_types = ('.id | type', '.tag_name | type', '.target_commitish | type', '.draft | type', '.immutable | type', '.prerelease | type', '.name | type', '.body | type', '.author.login | type')
          required_delimiters = ('contains("|")', 'contains("\\r")', 'contains("\\n")')
          if not all(fragment in jq_filter for fragment in required_types + required_delimiters):
            fail('release metadata query must validate JSON types and delimiters')
        else:
          required_asset_checks = ('.assets | type', 'valid_asset', '.name | line_safe_string', '.size | type', '.size >= 0', '.size | floor', '.state | line_safe_string', '.digest == null', '.digest | line_safe_string', '"invalid"')
          if not all(fragment in jq_filter for fragment in required_asset_checks):
            fail('release asset query must validate collection and field types')
      elif '/releases/' in endpoint and method == 'DELETE':
        operation = 'delete-release'
      elif '/releases/' in endpoint and method == 'PATCH':
        operation = 'publish-release'
        require_positive_id_jq(arguments)
else:
  fail('unsupported gh arguments: ' + repr(arguments))
if not operation:
  fail('unsupported gh arguments: ' + repr(arguments))
if operation in ('list-releases', 'list-full-releases', 'list-tag-refs') and '--paginate' not in arguments:
  fail('inspection query must be paginated')

record = {'arguments': arguments, 'operation': operation, 'github_token_present': 'GITHUB_TOKEN' in os.environ, 'gh_token_matches': (os.environ.get('GH_TOKEN') == expected_token if expected_token else 'GH_TOKEN' not in os.environ)}
with Path(os.environ['FAKE_GH_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps(record, sort_keys=True) + '\n')
if operation == os.environ.get('FAKE_GH_FAIL_OPERATION'):
  fail('injected operation failure', int(os.environ.get('FAKE_GH_FAIL_STATUS', '72')))

release = state.get('release')
if operation == 'inspect-immutability':
  print(state.get('immutable_status', 'boolean|true'))
elif operation == 'inspect-authenticated-user':
  print(state.get('authenticated_login', 'github-actions[bot]'))
elif operation == 'inspect-latest':
  if state.get('latest_visibility_delay_remaining', 0) > 0:
    state['latest_visibility_delay_remaining'] -= 1
    save_state(state)
    print(state.get('stale_latest_status', 'null'))
  else:
    print(state.get('latest_status', 'null'))
elif operation == 'list-full-releases':
  if state.get('malformed_full_release_list_response'):
    print('invalid')
  elif state.get('full_release_visibility_delay_remaining', 0) > 0:
    state['full_release_visibility_delay_remaining'] -= 1
    save_state(state)
    print(state.get('stale_full_release_status', ''))
  elif 'full_release_status' in state:
    print(state['full_release_status'])
  elif release is not None and not release['draft'] and not release['prerelease']:
    print('tag|' + release['tag'])
elif operation == 'list-releases':
  if state.get('malformed_release_list_response'):
    print('invalid')
    raise SystemExit(0)
  visible_release = release
  remaining = state.get('list_visibility_delay_remaining', 0)
  if remaining > 0:
    state['list_visibility_delay_remaining'] = remaining - 1
    visible_release = state.get('stale_list_release') if state.get('list_visibility_delay_mode') == 'stale' else None
    save_state(state)
  if visible_release is not None and visible_release['tag'] == os.environ['FAKE_EXPECTED_TAG']:
    print(visible_release['id'])
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
  if field_value(arguments, '-f', 'ref') != 'refs/tags/' + os.environ['FAKE_EXPECTED_TAG']:
    fail('unexpected tag ref')
  state['tag_sha'] = field_value(arguments, '-f', 'sha')
  save_state(state)
  print('{}')
elif operation == 'view-release-status':
  requested_id = requested_release_id(endpoint)
  deleted_release = state.get('deleted_release')
  remaining = state.get('delete_get_stale_remaining', 0)
  if deleted_release is not None and deleted_release['id'] == requested_id and remaining > 0:
    state['delete_get_stale_remaining'] = remaining - 1
    save_state(state)
    print('HTTP/2.0 200 OK')
  elif release is not None and release.get('id') == requested_id:
    print('HTTP/2.0 200 OK')
  else:
    print('HTTP/2.0 404 Not Found')
    raise SystemExit(1)
elif operation == 'view-metadata':
  requested_id = requested_release_id(endpoint)
  if release is None or str(release.get('id')) != str(requested_id):
    fail('release does not exist', 1)
  visible_release = release
  remaining = state.get('create_get_404_remaining', 0)
  if remaining > 0:
    state['create_get_404_remaining'] = remaining - 1
    save_state(state)
    fail('release is not visible by ID', 1)
  remaining = state.get('publish_get_stale_remaining', 0)
  if remaining > 0:
    state['publish_get_stale_remaining'] = remaining - 1
    visible_release = state['prepublish_release']
    save_state(state)
  else:
    remaining = state.get('publish_immutable_delay_remaining', 0)
    if remaining > 0:
      state['publish_immutable_delay_remaining'] = remaining - 1
      visible_release = dict(release)
      visible_release['immutable'] = False
      save_state(state)
  print_release_metadata(visible_release)
elif operation == 'view-assets':
  requested_id = requested_release_id(endpoint)
  if release is None or release.get('id') != requested_id:
    fail('release does not exist', 1)
  if state.get('malformed_assets_response'):
    print('invalid')
    raise SystemExit(0)
  for name in sorted(release['assets']):
    contents = asset_bytes(release['assets'][name])
    print('{}|{}|uploaded|sha256:{}'.format(name, len(contents), hashlib.sha256(contents).hexdigest()))
  if state.pop('replace_id_after_asset_view', False):
    release['id'] = 999
    state['release'] = release
    save_state(state)
elif operation == 'create-release':
  if release is not None:
    fail('release already exists', 1)
  if state.get('tag_sha') is None:
    fail('draft requires the exact existing tag')
  tag = field_value(arguments, '-f', 'tag_name')
  target = field_value(arguments, '-f', 'target_commitish')
  title = field_value(arguments, '-f', 'name')
  body = field_value(arguments, '-f', 'body')
  if tag != os.environ['FAKE_EXPECTED_TAG'] or field_value(arguments, '-F', 'draft') != 'true' or field_value(arguments, '-F', 'prerelease') != 'false' or field_value(arguments, '-f', 'make_latest') != 'true':
    fail('draft safety field missing')
  release = {'id': state['next_id'], 'tag': tag, 'target': target, 'draft': True, 'immutable': False, 'prerelease': False, 'title': title, 'body': body, 'author': state.get('authenticated_login', 'github-actions[bot]'), 'assets': {}}
  state['next_id'] += 1
  state['release'] = release
  state['create_get_404_remaining'] = state.get('create_get_404_calls', 0)
  state['list_visibility_delay_mode'] = 'hidden'
  state['list_visibility_delay_remaining'] = state.get('create_list_delay_calls', 0)
  save_state(state)
  fail_after_server_write(operation)
  print(release['id'])
elif operation == 'delete-release':
  requested_id = requested_release_id(endpoint)
  if release is None or release.get('id') != requested_id or not release['draft']:
    fail('unsafe draft deletion')
  state['deleted_release'] = release
  state['stale_list_release'] = release
  state['release'] = None
  state['delete_get_stale_remaining'] = state.get('delete_get_stale_calls', 0)
  state['list_visibility_delay_mode'] = 'stale'
  state['list_visibility_delay_remaining'] = state.get('delete_list_delay_calls', 0)
  save_state(state)
  fail_after_server_write(operation)
elif operation == 'upload-release':
  requested_id = requested_release_id(endpoint)
  if state.pop('replace_id_before_first_upload', False):
    replacement = dict(release)
    replacement['id'] = 999
    replacement['assets'] = {}
    state['release'] = replacement
    save_state(state)
    release = replacement
  if release is None or release.get('id') != requested_id or not release['draft']:
    fail('assets can only be uploaded to the exact draft ID')
  headers = [arguments[index + 1] for index, argument in enumerate(arguments[:-1]) if argument == '-H']
  if headers != ['Content-Type: application/octet-stream']:
    fail('upload must have the exact binary content type')
  path = Path(flag_value(arguments, '--input'))
  if path.name != upload_name:
    fail('upload URL name does not match the input basename')
  if any(argument in ('-f', '-F') and index + 1 < len(arguments) and arguments[index + 1].startswith('name=') for index, argument in enumerate(arguments)):
    fail('upload name must be encoded in the absolute URL')
  jq_filter = flag_value(arguments, '--jq')
  required_types = ('.name | type', '.size | type', '.state | type', '.digest | type')
  if not all(fragment in jq_filter for fragment in required_types):
    fail('upload response must validate JSON types')
  should_fail = upload_name == os.environ.get('FAKE_GH_FAIL_UPLOAD')
  if should_fail and os.environ.get('FAKE_GH_FAIL_AFTER_WRITE') != '1':
    fail('injected upload failure', 73)
  if upload_name in release['assets']:
    fail('asset already exists', 1)
  contents = path.read_bytes()
  release['assets'][upload_name] = base64.b64encode(contents).decode('ascii')
  state['release'] = release
  save_state(state)
  if upload_name == os.environ.get('FAKE_GH_SIGNAL_UPLOAD'):
    os.kill(os.getppid(), signal.SIGTERM)
    raise SystemExit(0)
  if should_fail:
    fail('injected upload failure after write', 73)
  if len(release['assets']) == 14:
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
  print('{}|{}|uploaded|sha256:{}'.format(upload_name, len(contents), hashlib.sha256(contents).hexdigest()))
elif operation == 'publish-release':
  requested_id = requested_release_id(endpoint)
  if release is None or release.get('id') != requested_id or not release['draft']:
    fail('only the exact draft can be published')
  if field_value(arguments, '-f', 'tag_name') != release['tag'] or field_value(arguments, '-F', 'draft') != 'false' or field_value(arguments, '-F', 'prerelease') != 'false' or field_value(arguments, '-f', 'make_latest') != 'true':
    fail('publish safety field missing')
  if field_value(arguments, '-f', 'target_commitish') != release['target']:
    fail('publish target mismatch')
  if field_value(arguments, '-f', 'name') != release['title'] or field_value(arguments, '-f', 'body') != release['body']:
    fail('publish metadata mismatch')
  state['prepublish_release'] = dict(release)
  release['draft'] = False
  release['immutable'] = state.get('immutable_after_publish', True)
  if not state.get('ignore_make_latest_after_publish'):
    state['latest_status'] = 'tag|' + release['tag']
  state['release'] = release
  state['publish_get_stale_remaining'] = state.get('publish_get_stale_calls', 0)
  state['publish_immutable_delay_remaining'] = state.get('publish_immutable_delay_calls', 0)
  state['list_visibility_delay_mode'] = 'hidden'
  state['list_visibility_delay_remaining'] = state.get('publish_list_delay_calls', 0)
  save_state(state)
  fail_after_server_write(operation)
  print(release['id'])
'''

FAKE_SLEEP = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys

if len(sys.argv) != 2 or sys.argv[1] not in ('1', '2', '4', '8', '16'):
  raise SystemExit(91)
sensitive = ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'GH_HOST', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT')
if any(name in os.environ for name in sensitive):
  raise SystemExit(92)
with Path(os.environ['FAKE_SLEEP_LOG']).open('a', encoding='ascii') as stream:
  stream.write(sys.argv[1] + '\n')
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

FAKE_PYTHON = r'''#!PYTHON_EXECUTABLE
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
if arguments and arguments[0] == '-I':
  sensitive = ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'GH_HOST', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT')
  leaked = {name: os.environ.get(name) for name in sensitive if name in os.environ}
  if leaked:
    print('publisher leaked credentials to archive verifier: {!r}'.format(leaked), file=sys.stderr)
    raise SystemExit(97)
  with Path(os.environ['FAKE_LOCAL_TOOL_LOG']).open('a', encoding='utf-8') as stream:
    stream.write('python3-isolated\n')
  if len(arguments) > 1 and arguments[1] == '-c' and os.environ.get('FAKE_PYTHON_TOO_OLD') == '1':
    raise SystemExit(98)
os.execv('PYTHON_EXECUTABLE', ['PYTHON_EXECUTABLE'] + arguments)
'''


@unittest.skipUnless(os.name == 'posix' and Path('/bin/bash').is_file(), 'publisher integration tests require POSIX /bin/bash')
class PublishDistributionsTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)
    self.artifact_directory = self.root / 'artifacts'
    self.fake_bin = self.root / 'bin'
    self.log_path = self.root / 'gh.log'
    self.local_tool_log = self.root / 'local-tool.log'
    self.sleep_log = self.root / 'sleep.log'
    self.shell_injection_log = self.root / 'shell-injection.log'
    self.state_path = self.root / 'state.json'
    self.artifact_directory.mkdir()
    self.fake_bin.mkdir()
    self.fake_gh = self.fake_bin / 'gh'
    self.fake_gh.write_text(FAKE_GH, encoding='utf-8')
    self.fake_gh.chmod(0o755)
    self.fake_sleep = self.fake_bin / 'sleep'
    self.fake_sleep.write_text(FAKE_SLEEP, encoding='utf-8')
    self.fake_sleep.chmod(0o755)
    real_local_tool = shutil.which('sha256sum') or shutil.which('shasum')
    self.assertIsNotNone(real_local_tool)
    self.real_local_tool = real_local_tool
    self.fake_local_tool = self.fake_bin / Path(real_local_tool).name
    self.fake_local_tool.write_text(
        FAKE_LOCAL_TOOL.replace('PYTHON_EXECUTABLE', sys.executable, 1),
        encoding='utf-8')
    self.fake_local_tool.chmod(0o755)
    self.fake_python = self.fake_bin / 'python3'
    self.fake_python.write_text(
        FAKE_PYTHON.replace('PYTHON_EXECUTABLE', sys.executable),
        encoding='utf-8')
    self.fake_python.chmod(0o755)
    self.create_artifacts()
    self.write_state({'next_id': 1, 'tag_sha': None, 'release': None})

  def tearDown(self):
    self.temporary_directory.cleanup()

  def create_artifacts(self):
    for target in TARGETS:
      archive_name = '{}.tar.gz'.format(target)
      archive_path = self.artifact_directory / archive_name
      write_valid_archive(archive_path, target, COMMIT_SHA)
      digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
      line_ending = b'\r\n' if target.startswith('windows_') else b'\n'
      checksum = '{}  {}'.format(digest, archive_name).encode('ascii')
      (self.artifact_directory /
       '{}.sha256'.format(archive_name)).write_bytes(checksum + line_ending)
    (self.artifact_directory / BINARY_JAR_NAME).write_bytes(canonical_jar_files('linux_amd64')['jcef.jar'])
    build_sources_jar(SOURCE_ROOT, self.artifact_directory / SOURCES_JAR_NAME)

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
        'FAKE_SLEEP_LOG':
            str(self.sleep_log),
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

  def run_publisher(self, commit_sha=COMMIT_SHA, environment=None, artifact_directory=None, source_snapshot_root=SOURCE_ROOT, cwd=None):
    return subprocess.run([str(PUBLISHER), commit_sha, str(artifact_directory or self.artifact_directory), str(source_snapshot_root)], check=False, capture_output=True, text=True, env=environment or self.environment(), cwd=cwd)

  def read_log(self):
    if not self.log_path.exists():
      return []
    return [
        json.loads(line)
        for line in self.log_path.read_text(encoding='utf-8').splitlines()
    ]

  def operations(self):
    return [record['operation'] for record in self.read_log()]

  def sleep_delays(self):
    if not self.sleep_log.exists():
      return []
    return self.sleep_log.read_text(encoding='ascii').splitlines()

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
                  latest_status=None,
                  overrides=None):
    assets = {name: self.canonical_assets()[name] for name in asset_names}
    for name, contents in (overrides or {}).items():
      assets[name] = base64.b64encode(contents).decode('ascii')
    immutable = not draft if immutable is None else immutable
    if latest_status is None:
      latest_status = ('null' if draft else 'tag|{}'.format(TAG_NAME))
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
    local_tools = self.local_tool_log.read_text(encoding='utf-8').splitlines()
    self.assertEqual(8, local_tools.count('python3-isolated'))
    records = self.read_log()
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in records))
    upload_records = [
        record for record in records if record['operation'] == 'upload-release'
    ]
    expected_upload_names = ARCHIVE_NAMES + (BINARY_JAR_NAME, SOURCES_JAR_NAME) + CHECKSUM_NAMES
    self.assertEqual(len(expected_upload_names), len(upload_records))
    for record, expected_name in zip(upload_records, expected_upload_names):
      arguments = record['arguments']
      endpoint = next(
          argument for argument in arguments if argument.startswith('https://'))
      self.assertEqual(
          'https://uploads.github.com/repos/{}/releases/1/assets?name={}'.
          format(REPOSITORY, expected_name), endpoint)
      self.assertEqual('POST', flag_argument(arguments, '--method'))
      self.assertEqual('Content-Type: application/octet-stream',
                       flag_argument(arguments, '-H'))
      self.assertEqual(expected_name,
                       Path(flag_argument(arguments, '--input')).name)
      self.assertNotIn(TAG_NAME, endpoint)
    create_arguments = next(record['arguments'] for record in records
                            if record['operation'] == 'create-release')
    publish_arguments = next(record['arguments'] for record in records
                             if record['operation'] == 'publish-release')
    self.assertEqual('true',
                     field_argument(create_arguments, '-f', 'make_latest'))
    self.assertEqual('true', field_argument(create_arguments, '-F', 'draft'))
    self.assertEqual('false',
                     field_argument(create_arguments, '-F', 'prerelease'))
    self.assertEqual('true',
                     field_argument(publish_arguments, '-f', 'make_latest'))
    self.assertEqual('false', field_argument(publish_arguments, '-F', 'draft'))
    self.assertEqual('false',
                     field_argument(publish_arguments, '-F', 'prerelease'))
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

  def test_delayed_create_visibility_is_retried_by_stable_release_id(self):
    state = self.read_state()
    state['create_get_404_calls'] = 1
    state['create_list_delay_calls'] = 1
    self.write_state(state)

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(['1', '2'], self.sleep_delays())
    create_record = next(record for record in self.read_log()
                         if record['operation'] == 'create-release')
    self.assertIn('repos/{}/releases'.format(REPOSITORY),
                  create_record['arguments'])

  def test_create_visibility_retry_is_bounded(self):
    state = self.read_state()
    state['create_get_404_calls'] = 99
    self.write_state(state)

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Draft release was not visible after creation', result.stderr)
    self.assertEqual(['1', '2', '4', '8', '16'], self.sleep_delays())
    self.assertTrue(self.read_state()['release']['draft'])
    self.assertEqual({}, self.read_state()['release']['assets'])
    self.assertNotIn('upload-release', self.operations())
    self.assertNotIn('publish-release', self.operations())

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
    result = self.run_publisher(environment=self.environment(
        GITHUB_TOKEN='must-not-be-used',
        GH_TOKEN=TOKEN,
        GH_ENTERPRISE_TOKEN='must-not-leak',
        GITHUB_ENTERPRISE_TOKEN='must-not-leak',
        ENV_TOKEN_SOURCE='must-not-remain-exported',
        ENV_TOKEN_CONTENT='must-not-remain-exported'))
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertTrue(self.local_tool_log.exists())
    self.assertTrue(
        all(record['gh_token_matches'] and not record['github_token_present']
            for record in self.read_log()))

  def test_privileged_startup_blocks_bash_env_and_exported_gh_function(self):
    bash_environment = self.root / 'malicious-bash-env'
    bash_environment.write_text(
        "printf 'BASH_ENV executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"\ngh() { printf 'BASH_ENV gh function executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"; return 97; }\nexport -f gh\n",
        encoding='utf-8')
    environment = self.environment(BASH_ENV=str(bash_environment))
    environment[
        'BASH_FUNC_gh%%'] = '() { printf \'exported gh function executed\\n\' >> "$FAKE_SHELL_INJECTION_LOG"; return 98; }'
    result = self.run_publisher(environment=environment)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertFalse(self.shell_injection_log.exists())
    self.assert_exact_published_release()

  def test_non_privileged_bash_invocation_is_rejected_before_gh(self):
    result = subprocess.run(['/bin/bash', str(PUBLISHER), COMMIT_SHA, str(self.artifact_directory), str(SOURCE_ROOT)], check=False, capture_output=True, text=True, env=self.environment())
    self.assertNotEqual(0, result.returncode)
    self.assertIn('execute publish_distributions.sh directly', result.stderr)
    self.assertFalse(self.log_path.exists())

  def test_bare_path_invocation_resolves_only_its_sibling_verification_helpers(self):
    publication_bin = self.root / 'publication-bin'
    publication_bin.mkdir()
    publisher_copy = publication_bin / PUBLISHER.name
    verifier_copy = publication_bin / 'verify_distribution_archive.py'
    sources_jar_helper_copy = publication_bin / 'sources_jar.py'
    shutil.copy2(PUBLISHER, publisher_copy)
    shutil.copy2(DISTRIB_ROOT / verifier_copy.name, verifier_copy)
    shutil.copy2(DISTRIB_ROOT / sources_jar_helper_copy.name, sources_jar_helper_copy)
    publisher_copy.chmod(0o755)
    environment = self.environment()
    environment['PATH'] = '{}{}{}'.format(publication_bin, os.pathsep, environment['PATH'])
    result = subprocess.run([PUBLISHER.name, COMMIT_SHA, str(self.artifact_directory), str(SOURCE_ROOT)], check=False, capture_output=True, text=True, env=environment, cwd=self.root)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()

  def test_publisher_copy_without_sibling_verifier_fails_before_gh(self):
    publisher_copy = self.root / PUBLISHER.name
    shutil.copy2(PUBLISHER, publisher_copy)
    publisher_copy.chmod(0o755)
    result = subprocess.run([str(publisher_copy), COMMIT_SHA, str(self.artifact_directory), str(SOURCE_ROOT)], check=False, capture_output=True, text=True, env=self.environment())
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Required sibling distribution verifier', result.stderr)
    self.assertFalse(self.log_path.exists())

  def test_publisher_copy_without_sibling_sources_jar_helper_fails_before_gh(self):
    publication_bin = self.root / 'publication-bin'
    publication_bin.mkdir()
    publisher_copy = publication_bin / PUBLISHER.name
    verifier_copy = publication_bin / 'verify_distribution_archive.py'
    shutil.copy2(PUBLISHER, publisher_copy)
    shutil.copy2(DISTRIB_ROOT / verifier_copy.name, verifier_copy)
    publisher_copy.chmod(0o755)
    result = subprocess.run([str(publisher_copy), COMMIT_SHA, str(self.artifact_directory), str(SOURCE_ROOT)], check=False, capture_output=True, text=True, env=self.environment())
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Required sibling sources JAR helper', result.stderr)
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

  def test_published_release_is_confirmed_as_latest(self):
    self.set_release(
        False, ASSET_NAMES, latest_status='tag|{}'.format(TAG_NAME))
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()
    self.assertIn('list-full-releases', self.operations())

  def test_published_release_is_latest_with_other_full_releases(self):
    self.set_release(
        False, ASSET_NAMES, latest_status='tag|{}'.format(TAG_NAME))
    state = self.read_state()
    state['full_release_status'] = 'tag|{}\ntag|other'.format(TAG_NAME)
    self.write_state(state)
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()
    self.assertEqual([], self.sleep_delays())

  def test_stale_null_latest_converges_to_target(self):
    self.set_release(False, ASSET_NAMES)
    state = self.read_state()
    state['latest_visibility_delay_remaining'] = 2
    state['stale_latest_status'] = 'null'
    self.write_state(state)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual(['1', '2'], self.sleep_delays())
    self.assertEqual(3, self.operations().count('inspect-latest'))
    self.assert_no_modifying_calls()

  def test_stale_prior_latest_converges_to_target(self):
    self.set_release(False, ASSET_NAMES)
    state = self.read_state()
    state['full_release_status'] = 'tag|{}\ntag|other'.format(TAG_NAME)
    state['latest_visibility_delay_remaining'] = 2
    state['stale_latest_status'] = 'tag|other'
    self.write_state(state)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual(['1', '2'], self.sleep_delays())
    self.assertEqual(3, self.operations().count('inspect-latest'))
    self.assert_no_modifying_calls()

  def test_stable_prior_latest_is_rejected_after_visibility_window(self):
    self.set_release(False, ASSET_NAMES, latest_status='tag|other')
    state = self.read_state()
    state['full_release_status'] = 'tag|{}\ntag|other'.format(TAG_NAME)
    self.write_state(state)
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn(
        'Unable to confirm {} as the latest published full release'.format(
            TAG_NAME), result.stderr)
    self.assertEqual(['1', '2', '4', '8', '16'], self.sleep_delays())
    self.assertEqual(6, self.operations().count('inspect-latest'))
    self.assert_no_modifying_calls()

  def test_full_release_inspection_failure_is_non_mutating(self):
    self.set_release(
        False, ASSET_NAMES, latest_status='tag|{}'.format(TAG_NAME))
    before = self.state_path.read_bytes()
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_OPERATION='list-full-releases'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to inspect published full releases', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assert_no_modifying_calls()

  def test_malformed_full_release_state_is_non_mutating(self):
    for full_release_status in ('invalid', 'tag|', 'tag|other'):
      with self.subTest(full_release_status=repr(full_release_status)):
        self.set_release(
            False, ASSET_NAMES, latest_status='tag|{}'.format(TAG_NAME))
        state = self.read_state()
        state['full_release_status'] = full_release_status
        self.write_state(state)
        before = self.state_path.read_bytes()
        if self.log_path.exists():
          self.log_path.unlink()
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
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

  def test_metadata_json_types_and_delimiters_fail_closed(self):
    mutations = (
        lambda release: release.update(id='1'),
        lambda release: release.update(target=123),
        lambda release: release.update(draft='false'),
        lambda release: release.update(immutable='true'),
        lambda release: release.update(prerelease='false'),
        lambda release: release.update(title=123),
        lambda release: release.update(title=RELEASE_TITLE + '|injected'),
        lambda release: release.update(body=RELEASE_BODY + '\ninserted'),
        lambda release: release.update(author='bad|author'),)
    for mutation in mutations:
      with self.subTest(mutation=mutation):
        self.set_release(False, ASSET_NAMES)
        state = self.read_state()
        mutation(state['release'])
        self.write_state(state)
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

  def test_delayed_delete_visibility_is_retried_after_exact_id_deletion(self):
    self.set_release(True, ARCHIVE_NAMES[:2])
    state = self.read_state()
    state['delete_get_stale_calls'] = 1
    state['delete_list_delay_calls'] = 1
    self.write_state(state)

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(['1', '2'], self.sleep_delays())
    delete_record = next(record for record in self.read_log()
                         if record['operation'] == 'delete-release')
    self.assertIn('repos/{}/releases/1'.format(REPOSITORY),
                  delete_record['arguments'])

  def test_delete_visibility_retry_is_bounded(self):
    self.set_release(True, ARCHIVE_NAMES[:2])
    state = self.read_state()
    state['delete_get_stale_calls'] = 99
    self.write_state(state)

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Unable to confirm removal of incomplete owned draft',
                  result.stderr)
    self.assertEqual(['1', '2', '4', '8', '16'], self.sleep_delays())
    self.assertIsNone(self.read_state()['release'])
    self.assertNotIn('create-release', self.operations())
    self.assertNotIn('upload-release', self.operations())
    self.assertNotIn('publish-release', self.operations())

  def test_replaced_draft_id_is_not_deleted(self):
    self.set_release(True, ARCHIVE_NAMES[:2])
    state = self.read_state()
    state['replace_id_after_asset_view'] = True
    self.write_state(state)

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Draft release identity changed before deletion',
                  result.stderr)
    self.assertEqual(999, self.read_state()['release']['id'])
    self.assertNotIn('delete-release', self.operations())

  def test_complete_owned_draft_publishes_without_reupload(self):
    self.set_release(True, ASSET_NAMES)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertIn('publish-release', self.operations())
    self.assertNotIn('delete-release', self.operations())
    self.assertNotIn('upload-release', self.operations())

  def test_delayed_publish_visibility_is_retried_by_stable_release_id(self):
    self.set_release(True, ASSET_NAMES)
    state = self.read_state()
    state['publish_get_stale_calls'] = 1
    state['publish_immutable_delay_calls'] = 1
    state['publish_list_delay_calls'] = 1
    self.write_state(state)

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(['1', '2', '4'], self.sleep_delays())
    publish_record = next(record for record in self.read_log()
                          if record['operation'] == 'publish-release')
    self.assertIn('repos/{}/releases/1'.format(REPOSITORY),
                  publish_record['arguments'])

  def test_publish_visibility_retry_is_bounded(self):
    self.set_release(True, ASSET_NAMES)
    state = self.read_state()
    state['publish_get_stale_calls'] = 99
    self.write_state(state)

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Release remained a draft after publication', result.stderr)
    self.assertEqual(['1', '2', '4', '8', '16'], self.sleep_delays())
    self.assertFalse(self.read_state()['release']['draft'])
    self.assertTrue(self.read_state()['release']['immutable'])
    self.assertNotIn('upload-release', self.operations())
    self.assertNotIn('delete-release', self.operations())

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

  def test_malformed_release_list_cannot_prove_absence(self):
    for mode in ('non-array', 'string-id', 'zero-id'):
      with self.subTest(mode=mode):
        state = {
            'next_id': 1,
            'tag_sha': None,
            'release': None,
            'malformed_release_list_response': mode
        }
        self.write_state(state)
        if self.log_path.exists():
          self.log_path.unlink()

        result = self.run_publisher()

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(state, self.read_state())
        self.assert_no_modifying_calls()

  def test_malformed_asset_collection_cannot_authorize_draft_deletion(self):
    for mode in ('missing', 'non-array', 'invalid-field'):
      with self.subTest(mode=mode):
        self.set_release(True, ARCHIVE_NAMES[:2])
        state = self.read_state()
        state['malformed_assets_response'] = mode
        self.write_state(state)
        if self.log_path.exists():
          self.log_path.unlink()

        result = self.run_publisher()

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(state, self.read_state())
        self.assertNotIn('delete-release', self.operations())
        self.assertNotIn('create-release', self.operations())

  @unittest.skipUnless(
      shutil.which('jq'), 'direct publisher jq contract tests require jq')
  def test_release_list_and_asset_jq_contracts_reject_malformed_json(self):
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    filters = {}
    for record in self.read_log():
      if record['operation'] in ('inspect-latest', 'list-releases',
                                 'list-full-releases', 'view-assets'):
        filters.setdefault(record['operation'],
                           flag_argument(record['arguments'], '--jq'))
    self.assertEqual({
        'inspect-latest', 'list-releases', 'list-full-releases', 'view-assets'
    }, set(filters))

    fixtures = {
        'inspect-latest': ({}, {
            'errors': [{
                'message': 'failure'
            }],
            'data': {
                'repository': {
                    'latestRelease': None
                }
            }
        }, {
            'data': {
                'repository': {}
            }
        }, {
            'data': {
                'repository': {
                    'latestRelease': {
                        'tagName': 7
                    }
                }
            }
        }, {
            'data': {
                'repository': {
                    'latestRelease': {
                        'tagName': TAG_NAME + '|injected'
                    }
                }
            }
        }),
        'list-releases': ({}, [{
            'tag_name': TAG_NAME,
            'id': '1'
        }], [{
            'tag_name': TAG_NAME,
            'id': 0
        }], [{
            'tag_name': TAG_NAME,
            'id': 1.5
        }], [{
            'id': 1
        }]),
        'list-full-releases': ({}, [{
            'tag_name': TAG_NAME,
            'draft': 'false',
            'prerelease': False
        }], [{
            'tag_name': TAG_NAME,
            'draft': False,
            'prerelease': 0
        }], [{
            'tag_name': TAG_NAME + '|injected',
            'draft': False,
            'prerelease': False
        }], [{
            'draft': False,
            'prerelease': False
        }]),
        'view-assets': ({}, {
            'assets': {}
        }, {
            'assets': [{
                'name': ARCHIVE_NAMES[0],
                'size': '1',
                'state': 'uploaded',
                'digest': 'sha256:' + '0' * 64
            }]
        }, {
            'assets': [{
                'name': ARCHIVE_NAMES[0] + '|injected',
                'size': 1,
                'state': 'uploaded',
                'digest': 'sha256:' + '0' * 64
            }]
        }, {
            'assets': [{
                'name': ARCHIVE_NAMES[0],
                'size': -1,
                'state': 'uploaded',
                'digest': 'sha256:' + '0' * 64
            }]
        })
    }
    jq_path = shutil.which('jq')
    for operation, malformed_payloads in fixtures.items():
      for payload in malformed_payloads:
        with self.subTest(operation=operation, payload=payload):
          jq_result = subprocess.run(
              [jq_path, '-r', filters[operation]],
              check=False,
              capture_output=True,
              text=True,
              input=json.dumps(payload))
          self.assertEqual(0, jq_result.returncode, jq_result.stderr)
          self.assertEqual('invalid', jq_result.stdout.rstrip('\n'))

    valid_payloads = {
        'inspect-latest': ({
            'data': {
                'repository': {
                    'latestRelease': {
                        'tagName': TAG_NAME
                    }
                }
            }
        }, 'tag|' + TAG_NAME),
        'list-releases': ([{
            'tag_name': TAG_NAME,
            'id': 7
        }], '7'),
        'list-full-releases': ([{
            'tag_name': TAG_NAME,
            'draft': False,
            'prerelease': False
        }, {
            'tag_name': 'draft-release',
            'draft': True,
            'prerelease': False
        }, {
            'tag_name': 'prerelease',
            'draft': False,
            'prerelease': True
        }], 'tag|' + TAG_NAME),
        'view-assets': ({
            'assets': [{
                'name': ARCHIVE_NAMES[0],
                'size': 1,
                'state': 'uploaded',
                'digest': 'sha256:' + '0' * 64
            }]
        }, '{}|1|uploaded|sha256:{}'.format(ARCHIVE_NAMES[0], '0' * 64))
    }
    for operation, (payload, expected_output) in valid_payloads.items():
      with self.subTest(operation=operation, valid=True):
        jq_result = subprocess.run(
            [jq_path, '-r', filters[operation]],
            check=False,
            capture_output=True,
            text=True,
            input=json.dumps(payload))
        self.assertEqual(0, jq_result.returncode, jq_result.stderr)
        self.assertEqual(expected_output, jq_result.stdout.rstrip('\n'))

    latest_null_result = subprocess.run(
        [jq_path, '-r', filters['inspect-latest']],
        check=False,
        capture_output=True,
        text=True,
        input=json.dumps({'data': {
            'repository': {
                'latestRelease': None
            }
        }}))
    self.assertEqual(0, latest_null_result.returncode,
                     latest_null_result.stderr)
    self.assertEqual('null', latest_null_result.stdout.rstrip('\n'))

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

  def test_binary_jar_upload_failure_leaves_archives_only_in_draft(self):
    result = self.run_publisher(environment=self.environment(FAKE_GH_FAIL_UPLOAD=BINARY_JAR_NAME))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Primary asset upload failed', result.stderr)
    state = self.read_state()
    self.assertTrue(state['release']['draft'])
    self.assertEqual(set(ARCHIVE_NAMES), set(state['release']['assets']))
    self.assertFalse(any(name.endswith('.sha256') for name in state['release']['assets']))
    self.assertNotIn('publish-release', self.operations())

  def test_sources_jar_upload_failure_leaves_prior_primary_assets_in_draft(self):
    result = self.run_publisher(environment=self.environment(FAKE_GH_FAIL_UPLOAD=SOURCES_JAR_NAME))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Primary asset upload failed', result.stderr)
    state = self.read_state()
    self.assertTrue(state['release']['draft'])
    self.assertEqual(set(ARCHIVE_NAMES + (BINARY_JAR_NAME,)), set(state['release']['assets']))
    self.assertFalse(any(name.endswith('.sha256') for name in state['release']['assets']))
    self.assertNotIn('publish-release', self.operations())

  def test_replaced_draft_id_cannot_receive_first_asset_upload(self):
    state = self.read_state()
    state['replace_id_before_first_upload'] = True
    self.write_state(state)

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Primary asset upload failed', result.stderr)
    replacement = self.read_state()['release']
    self.assertEqual(999, replacement['id'])
    self.assertEqual({}, replacement['assets'])
    upload_records = [
        record for record in self.read_log()
        if record['operation'] == 'upload-release'
    ]
    self.assertEqual(1, len(upload_records))
    self.assertIn(
        'https://uploads.github.com/repos/{}/releases/1/assets?name={}'.format(
            REPOSITORY, ARCHIVE_NAMES[0]), upload_records[0]['arguments'])

  def test_create_response_failure_after_server_write_recovers_on_rerun(self):
    state = self.read_state()
    state['create_get_404_calls'] = 1
    state['create_list_delay_calls'] = 2
    self.write_state(state)
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_AFTER_WRITE_OPERATION='create-release'))

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(1, self.read_state()['release']['id'])
    self.assertEqual(['1', '2', '4'], self.sleep_delays())

    self.log_path.unlink()
    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(1, self.read_state()['release']['id'])
    self.assert_no_modifying_calls()

  def test_visible_created_id_with_transient_metadata_404_converges(self):
    self.set_release(True)
    state = self.read_state()
    state['create_get_404_remaining'] = 1
    self.write_state(state)

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(2, self.read_state()['release']['id'])
    self.assertEqual(['1'], self.sleep_delays())
    self.assertIn('view-release-status', self.operations())
    self.assertIn('delete-release', self.operations())

  def test_delete_response_failure_after_server_write_recovers_on_rerun(self):
    self.set_release(True, ARCHIVE_NAMES[:2])
    state = self.read_state()
    state['delete_get_stale_calls'] = 1
    state['delete_list_delay_calls'] = 2
    self.write_state(state)
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_AFTER_WRITE_OPERATION='delete-release'))

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(2, self.read_state()['release']['id'])
    self.assertEqual(['1', '2', '4'], self.sleep_delays())

    self.log_path.unlink()
    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(2, self.read_state()['release']['id'])
    self.assert_no_modifying_calls()

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

  def test_publish_response_failure_after_server_write_is_idempotent_on_rerun(
      self):
    self.set_release(True, ASSET_NAMES)
    state = self.read_state()
    state['publish_get_stale_calls'] = 1
    state['publish_immutable_delay_calls'] = 1
    state['publish_list_delay_calls'] = 1
    self.write_state(state)
    result = self.run_publisher(environment=self.environment(
        FAKE_GH_FAIL_AFTER_WRITE_OPERATION='publish-release'))

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(['1', '2', '4'], self.sleep_delays())

    self.log_path.unlink()
    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assert_no_modifying_calls()

  def test_current_published_id_converges_from_stale_draft_metadata(self):
    self.set_release(False, ASSET_NAMES)
    state = self.read_state()
    prepublish_release = dict(state['release'])
    prepublish_release['draft'] = True
    prepublish_release['immutable'] = False
    state['prepublish_release'] = prepublish_release
    state['publish_get_stale_remaining'] = 1
    state['publish_immutable_delay_remaining'] = 1
    self.write_state(state)

    result = self.run_publisher()

    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assert_no_modifying_calls()

  def test_final_preflight_rejects_repository_or_draft_changes_during_upload(
      self):
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
        result = self.run_publisher(environment=self.environment(
            FAKE_MUTATE_AFTER_UPLOAD=mutation))
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

  def test_post_publish_prior_latest_state_is_detected(self):
    state = self.read_state()
    state['latest_status'] = 'tag|other'
    state['ignore_make_latest_after_publish'] = True
    state['full_release_status'] = 'tag|{}\ntag|other'.format(TAG_NAME)
    self.write_state(state)
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn(
        'Unable to confirm {} as the latest published full release'.format(
            TAG_NAME), result.stderr)
    self.assertFalse(self.read_state()['release']['draft'])
    self.assertTrue(self.read_state()['release']['immutable'])
    self.assertLess(self.operations().index('publish-release'),
                    self.operations().index('inspect-latest'))

  def test_published_release_becomes_latest_with_other_full_releases(self):
    state = self.read_state()
    state['latest_status'] = 'tag|other'
    state['full_release_status'] = 'tag|{}\ntag|other'.format(TAG_NAME)
    self.write_state(state)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertLess(self.operations().index('publish-release'),
                    self.operations().index('list-full-releases'))

  def test_published_release_list_visibility_is_retried(self):
    state = self.read_state()
    state['full_release_visibility_delay_remaining'] = 2
    self.write_state(state)
    result = self.run_publisher()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assert_exact_published_release()
    self.assertEqual(['1', '2'], self.sleep_delays())
    self.assertEqual(3, self.operations().count('list-full-releases'))

  def test_mutating_api_interruption_statuses_are_terminal(self):
    operations = ('create-ref', 'create-release', 'upload-release',
                  'delete-release', 'publish-release')
    for operation in operations:
      for status in (129, 130, 143):
        with self.subTest(operation=operation, status=status):
          if operation == 'create-ref':
            self.write_state({'next_id': 1, 'tag_sha': None, 'release': None})
          elif operation in ('create-release', 'upload-release'):
            self.write_state({
                'next_id': 1,
                'tag_sha': COMMIT_SHA,
                'release': None
            })
          elif operation == 'delete-release':
            self.set_release(True, ARCHIVE_NAMES[:2])
          else:
            self.set_release(True, ASSET_NAMES)
          for log_path in (self.log_path, self.sleep_log):
            if log_path.exists():
              log_path.unlink()

          result = self.run_publisher(environment=self.environment(
              FAKE_GH_FAIL_OPERATION=operation,
              FAKE_GH_FAIL_STATUS=str(status)))

          self.assertEqual(status, result.returncode, result.stderr)
          recorded_operations = self.operations()
          self.assertEqual(operation, recorded_operations[-1])
          self.assertEqual(1, recorded_operations.count(operation))

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

  def test_canonical_release_asset_symlink_is_rejected_before_gh(self):
    replacement = self.root / 'replacement'
    replacement.write_bytes(b'replacement')
    for asset_name in (ARCHIVE_NAMES[0], CHECKSUM_NAMES[0], BINARY_JAR_NAME, SOURCES_JAR_NAME):
      with self.subTest(asset_name=asset_name):
        asset_path = self.artifact_directory / asset_name
        asset_path.unlink()
        asset_path.symlink_to(replacement)
        result = self.run_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.read_log())
        asset_path.unlink()
        self.create_artifacts()

  def test_invalid_standalone_jcef_jar_fails_before_any_gh_call(self):
    binary_jar_path = self.artifact_directory / BINARY_JAR_NAME
    binary_jar_path.write_bytes(b'not the packaged JCEF JAR')

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('including standalone JCEF JAR match', result.stderr)
    self.assertEqual([], self.read_log())

  def test_invalid_sources_jar_fails_before_any_gh_call(self):
    sources_jar_path = self.artifact_directory / SOURCES_JAR_NAME
    sources_jar_path.write_bytes(b'not the canonical sources JAR')
    before = self.state_path.read_bytes()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Sources JAR verification failed', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assertEqual([], self.read_log())

    self.create_artifacts()
    mismatched_snapshot_root = self.root / 'mismatched-source-snapshot'
    mismatched_source = mismatched_snapshot_root / 'java' / 'org' / 'cef' / 'CefApp.java'
    mismatched_source.parent.mkdir(parents=True)
    mismatched_source.write_text('package org.cef;\n', encoding='utf-8')
    result = self.run_publisher(source_snapshot_root=mismatched_snapshot_root)
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Sources JAR verification failed', result.stderr)
    self.assertEqual([], self.read_log())

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

  def test_schema_or_commit_invalid_archive_fails_before_any_gh_call(self):
    target = TARGETS[2]
    archive_name = '{}.tar.gz'.format(target)
    archive_path = self.artifact_directory / archive_name
    archive_path.write_bytes(build_valid_archive(target, WRONG_SHA))
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (self.artifact_directory / '{}.sha256'.format(archive_name)
    ).write_bytes('{}  {}\n'.format(digest, archive_name).encode('ascii'))
    before = self.state_path.read_bytes()
    result = self.run_publisher()
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Distribution archive byte verification failed',
                  result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assertEqual([], self.read_log())

  def test_incomplete_distribution_tree_fails_before_any_gh_call(self):
    target = TARGETS[0]
    archive_name = '{}.tar.gz'.format(target)
    archive_path = self.artifact_directory / archive_name
    members = [
        member for member in canonical_members(target, COMMIT_SHA)
        if member['name'] != target + '/README.txt'
    ]
    archive_path.write_bytes(build_tar_gz(members))
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (self.artifact_directory / '{}.sha256'.format(archive_name)
    ).write_bytes('{}  {}\n'.format(digest, archive_name).encode('ascii'))
    before = self.state_path.read_bytes()

    result = self.run_publisher()

    self.assertNotEqual(0, result.returncode)
    self.assertIn('Distribution archive byte verification failed',
                  result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
    self.assertEqual([], self.read_log())

  def test_python_older_than_3_9_fails_before_any_gh_call(self):
    before = self.state_path.read_bytes()
    result = self.run_publisher(environment=self.environment(
        FAKE_PYTHON_TOO_OLD='1'))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Python 3.9 or newer is required', result.stderr)
    self.assertEqual(before, self.state_path.read_bytes())
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
    result = self.run_publisher(source_snapshot_root=self.root / 'missing-source-snapshot')
    self.assertNotEqual(0, result.returncode)
    self.assertIn('Source snapshot root does not exist', result.stderr)
    self.assertEqual([], self.read_log())
    no_gh_bin = self.root / 'no-gh-bin'
    no_gh_bin.mkdir()
    for command_name in ('sha256sum', 'shasum', 'cmp', 'sleep', 'wc', 'tr'):
      source = shutil.which(command_name)
      if source is not None:
        destination = no_gh_bin / Path(source).name
        if not destination.exists():
          destination.symlink_to(source)
    (no_gh_bin / 'python3').symlink_to(sys.executable)
    result = self.run_publisher(environment=self.environment(
        PATH=str(no_gh_bin)))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('gh is required', result.stderr)
    self.assertEqual([], self.read_log())

  def test_source_snapshot_root_argument_is_required_before_any_gh_call(self):
    result = subprocess.run([str(PUBLISHER), COMMIT_SHA, str(self.artifact_directory)], check=False, capture_output=True, text=True, env=self.environment())
    self.assertNotEqual(0, result.returncode)
    self.assertIn('<source-snapshot-root>', result.stderr)
    self.assertEqual([], self.read_log())


if __name__ == '__main__':
  unittest.main()
