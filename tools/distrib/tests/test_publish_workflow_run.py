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

SOURCE_ROOT = Path(__file__).resolve().parents[3]
TEST_ROOT = Path(__file__).resolve().parent
if str(SOURCE_ROOT / 'tools' / 'distrib') not in sys.path:
  sys.path.insert(0, str(SOURCE_ROOT / 'tools' / 'distrib'))
if str(TEST_ROOT) not in sys.path:
  sys.path.insert(0, str(TEST_ROOT))

from distribution_archive_test_util import build_valid_archive  # noqa: E402

SOURCE_WRAPPER = SOURCE_ROOT / 'tools' / 'distrib' / 'publish_workflow_run.sh'
RUN_ID = '30170658280'
RUN_SHA = '0123456789abcdef0123456789abcdef01234567'
REPOSITORY_ID = '1077297601'
WORKFLOW_ID = '319104439'
GH_TOKEN = 'preferred-gh-token'
GITHUB_TOKEN = 'fallback-github-token'
JOB_NAMES = ('Linux x86_64', 'Linux arm64', 'macOS x86_64', 'macOS arm64',
             'Windows x86_64', 'Windows arm64')
TARGETS = ('linux_amd64', 'linux_arm64', 'macos_amd64', 'macos_arm64',
           'windows_amd64', 'windows_arm64')
FAKE_VERIFIER = 'trusted-verifier-from-head\n'

FAKE_GIT = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
SENSITIVE_ENVIRONMENT = ('GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT')
sensitive_environment = {name: os.environ.get(name) for name in SENSITIVE_ENVIRONMENT}
with Path(os.environ['FAKE_GIT_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps({'arguments': arguments, 'gh_token': os.environ.get('GH_TOKEN'), 'github_token': os.environ.get('GITHUB_TOKEN'), 'gh_host': os.environ.get('GH_HOST'), 'sensitive_environment': sensitive_environment}, sort_keys=True) + '\n')
with Path(os.environ['FAKE_SUBPROCESS_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps({'tool': 'git', 'gh_token': os.environ.get('GH_TOKEN'), 'github_token': os.environ.get('GITHUB_TOKEN'), 'gh_host': os.environ.get('GH_HOST'), 'sensitive_environment': sensitive_environment}, sort_keys=True) + '\n')
if arguments[:1] == ['-C']:
  if Path(arguments[1]).resolve() != Path(os.environ['FAKE_ROOT']).resolve():
    raise SystemExit(89)
  arguments = arguments[2:]
if arguments == ['rev-parse', '--show-toplevel']:
  print(Path(os.environ['FAKE_ROOT']).resolve())
elif arguments == ['remote', 'get-url', 'origin']:
  print(os.environ.get('FAKE_ORIGIN_URL', 'https://github.com/Keksuccino/jcef-mcef.git'))
elif arguments[:2] == ['ls-files', '--error-unmatch']:
  raise SystemExit(0)
elif arguments[:3] == ['diff', '--quiet', 'HEAD']:
  dirty_path = os.environ.get('FAKE_DIRTY_PATH', '')
  raise SystemExit(1 if dirty_path and arguments[-1] == dirty_path else 0)
elif arguments[0:1] == ['show'] and ':' in arguments[1]:
  revision, relative_path = arguments[1].split(':', 1)
  if revision not in ('HEAD', os.environ['FAKE_RUN_SHA']):
    raise SystemExit(88)
  contents = (Path(os.environ['FAKE_HEAD_ROOT']) / relative_path).read_bytes()
  show_count_path = Path(os.environ['FAKE_GIT_SHOW_COUNT'])
  show_count = int(show_count_path.read_text(encoding='ascii')) + 1 if show_count_path.exists() else 1
  show_count_path.write_text(str(show_count), encoding='ascii')
  if os.environ.get('FAKE_HEAD_BLOB_MISMATCH') == relative_path:
    contents += b'changed'
  if os.environ.get('FAKE_HEAD_SHOW_MISMATCH_CALL') == str(show_count):
    contents += b'changed-once'
  sys.stdout.buffer.write(contents)
elif arguments[0:1] == ['fetch']:
  if os.environ.get('FAKE_FETCH_FAILURE') == '1':
    raise SystemExit(1)
elif arguments == ['rev-parse', '--verify', 'HEAD^{commit}']:
  print(os.environ.get('FAKE_HEAD_SHA', os.environ['FAKE_RUN_SHA']))
elif arguments == ['rev-parse', '--verify', 'refs/remotes/origin/master^{commit}']:
  print(os.environ.get('FAKE_ORIGIN_SHA', os.environ['FAKE_RUN_SHA']))
else:
  print('unsupported fake git arguments: {!r}'.format(arguments), file=sys.stderr)
  raise SystemExit(90)
'''

FAKE_GH = r'''#!/usr/bin/env python3
import base64
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
unsafe_shell_environment = [name for name in os.environ if name in ('BASH_ENV', 'ENV', 'SHELLOPTS', 'BASHOPTS', 'CDPATH', 'GLOBIGNORE', 'BASH_XTRACEFD', 'PS4') or name.startswith('BASH_FUNC_')]
if unsafe_shell_environment:
  print('unsafe shell environment reached fake gh: {!r}'.format(unsafe_shell_environment), file=sys.stderr)
  raise SystemExit(94)
unexpected_credentials = [name for name in ('GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT') if name in os.environ]
if unexpected_credentials:
  print('unexpected credentials reached fake gh: {!r}'.format(unexpected_credentials), file=sys.stderr)
  raise SystemExit(95)
if os.environ.get('FAKE_DRAIN_PUBLISHER_FD') == '1':
  try:
    os.lseek(9, 0, os.SEEK_END)
  except OSError:
    pass
  else:
    Path(os.environ['FAKE_PUBLISHER_FD_LOG']).write_text('fake gh inherited publisher fd 9\n', encoding='ascii')
if arguments[:1] != ['api'] or '--hostname' not in arguments or '--method' not in arguments:
  raise SystemExit(91)
if arguments[arguments.index('--hostname') + 1] != 'github.com' or arguments[arguments.index('--method') + 1] != 'GET':
  raise SystemExit(92)
endpoint = arguments[arguments.index('--method') + 2]
record = {'arguments': arguments, 'endpoint': endpoint, 'github_token': os.environ.get('GITHUB_TOKEN'), 'gh_token': os.environ.get('GH_TOKEN'), 'gh_host': os.environ.get('GH_HOST')}
with Path(os.environ['FAKE_GH_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps(record, sort_keys=True) + '\n')
state = json.loads(Path(os.environ['FAKE_STATE']).read_text(encoding='utf-8'))
if endpoint == 'repos/Keksuccino/jcef-mcef':
  print(state.get('repository_output', state['repository_id']))
elif endpoint == 'repos/Keksuccino/jcef-mcef/actions/workflows/build-jcef.yml':
  print(state.get('workflow_output', state['workflow_id']))
elif endpoint == 'repos/Keksuccino/jcef-mcef/actions/runs/' + state['run_id']:
  outputs = state.get('run_outputs', [state['run_sha'] + '|' + state['run_attempt']])
  index = min(state.get('run_call_count', 0), len(outputs) - 1)
  print(outputs[index])
  state['run_call_count'] = state.get('run_call_count', 0) + 1
  if state['run_call_count'] == state.get('replace_publisher_on_run_call'):
    publisher = Path(os.environ['FAKE_ROOT']) / 'tools' / 'distrib' / 'publish_distributions.sh'
    publisher.write_bytes(base64.b64decode(state['replacement_publisher'].encode('ascii')))
    publisher.chmod(0o755)
  if state['run_call_count'] == state.get('replace_verifier_on_run_call'):
    verifier = Path(os.environ['FAKE_ROOT']) / 'tools' / 'distrib' / 'verify_distribution_archive.py'
    verifier.write_bytes(base64.b64decode(state['replacement_verifier'].encode('ascii')))
    verifier.chmod(0o644)
  Path(os.environ['FAKE_STATE']).write_text(json.dumps(state), encoding='utf-8')
elif endpoint == 'repos/Keksuccino/jcef-mcef/immutable-releases':
  print(state.get('immutable_output', 'boolean|true'))
elif endpoint == 'user':
  print(state.get('login_output', 'Keksuccino'))
elif endpoint == 'repos/Keksuccino/jcef-mcef/actions/runs/' + state['run_id'] + '/attempts/' + state['run_attempt'] + '/jobs?per_page=100':
  print('meta|' + str(state.get('jobs_total', len(state['jobs']))))
  for job in state['jobs']:
    print('|'.join(('job', str(job['id']), job['name'], job['status'], job['conclusion'], str(job['attempt']), str(job['run_id']), job['head_sha'], job['head_branch'], job['workflow_name'])))
elif endpoint == 'repos/Keksuccino/jcef-mcef/actions/runs/' + state['run_id'] + '/jobs?filter=all&per_page=100':
  all_jobs = state.get('prior_jobs', []) + state['jobs']
  print('meta|' + str(len(all_jobs)))
  for job in all_jobs:
    print('|'.join(('job', str(job['id']), job['name'], job['status'], job['conclusion'], str(job['attempt']), str(job['run_id']), job['head_sha'], job['head_branch'], job['workflow_name'])))
elif endpoint == 'repos/Keksuccino/jcef-mcef/actions/runs/' + state['run_id'] + '/artifacts?per_page=100':
  print('meta|' + str(state.get('artifacts_total', len(state['artifacts']))))
  for artifact in state['artifacts']:
    print('|'.join(('artifact', str(artifact['id']), artifact['name'], str(artifact['size']), artifact['digest'], str(artifact['expired']).lower(), str(artifact['run_id']), str(artifact['repository_id']), str(artifact['head_repository_id']), artifact['head_branch'], artifact['head_sha'])))
elif endpoint.startswith('repos/Keksuccino/jcef-mcef/actions/artifacts/') and endpoint.endswith('/zip'):
  artifact_id = endpoint.split('/')[-2]
  contents = state['contents'][artifact_id]
  if state.get('corrupt_download_id') == artifact_id:
    contents = base64.b64encode(b'corrupt').decode('ascii')
  sys.stdout.buffer.write(base64.b64decode(contents.encode('ascii')))
else:
  print('unsupported fake gh endpoint: ' + endpoint, file=sys.stderr)
  raise SystemExit(93)
'''

FAKE_PUBLISHER = r'''#!/bin/bash
set -euo pipefail
set +x

captured_gh_token="${GH_TOKEN-}"
captured_github_token="${GITHUB_TOKEN-}"
captured_gh_host="${GH_HOST-}"
captured_gh_enterprise_token="${GH_ENTERPRISE_TOKEN-}"
captured_github_enterprise_token="${GITHUB_ENTERPRISE_TOKEN-}"
captured_env_token_source="${ENV_TOKEN_SOURCE-}"
captured_env_token_content="${ENV_TOKEN_CONTENT-}"
unset GH_TOKEN GITHUB_TOKEN GH_HOST GH_ENTERPRISE_TOKEN GITHUB_ENTERPRISE_TOKEN ENV_TOKEN_SOURCE ENV_TOKEN_CONTENT

if [ "${FAKE_INSPECT_PUBLISHER_FDS-}" = 1 ]; then
  inspect-publisher-fds "$0"
fi

if /usr/bin/env | /usr/bin/grep -E '^(BASH_ENV|ENV|SHELLOPTS|BASHOPTS|CDPATH|GLOBIGNORE|BASH_XTRACEFD|PS4|BASH_FUNC_[^=]*)=' >/dev/null; then
  exit 95
fi

mode_of() {
  if ! stat -f '%Lp' "$1" 2>/dev/null; then
    stat -c '%a' "$1"
  fi
}

artifact_directory="$2"
private_root="${artifact_directory%/*}"
directory_mode="$(mode_of "$artifact_directory")"
private_root_mode="$(mode_of "$private_root")"
script_mode="$(mode_of "$0")"
verifier_path="${0%/*}/verify_distribution_archive.py"
if [ ! -f "$verifier_path" ] || [ -L "$verifier_path" ] || [ ! -r "$verifier_path" ]; then
  exit 93
fi
verifier_mode="$(mode_of "$verifier_path")"
verifier_contents="$(< "$verifier_path")"
shopt -s dotglob nullglob
artifact_paths=("${artifact_directory}"/*)
{
  printf 'implementation=HEAD\n'
  printf 'sha=%s\n' "$1"
  printf 'script_path=%s\n' "$0"
  printf 'directory=%s\n' "$artifact_directory"
  printf 'mode=%s\n' "$directory_mode"
  printf 'private_root_mode=%s\n' "$private_root_mode"
  printf 'script_mode=%s\n' "$script_mode"
  printf 'verifier_path=%s\n' "$verifier_path"
  printf 'verifier_mode=%s\n' "$verifier_mode"
  printf 'verifier_contents=%s\n' "$verifier_contents"
  printf 'gh_token=%s\n' "$captured_gh_token"
  printf 'github_token=%s\n' "$captured_github_token"
  printf 'gh_host=%s\n' "$captured_gh_host"
  printf 'gh_enterprise_token=%s\n' "$captured_gh_enterprise_token"
  printf 'github_enterprise_token=%s\n' "$captured_github_enterprise_token"
  printf 'env_token_source=%s\n' "$captured_env_token_source"
  printf 'env_token_content=%s\n' "$captured_env_token_content"
  for artifact_path in "${artifact_paths[@]}"; do
    printf 'file=%s\n' "${artifact_path##*/}"
  done
} > "$FAKE_PUBLISHER_LOG"
if [ "$1" != "$FAKE_RUN_SHA" ] || [ "${#artifact_paths[@]}" -ne 12 ] || [ "$verifier_contents" != 'trusted-verifier-from-head' ]; then
  exit 94
fi
'''

MALICIOUS_PUBLISHER = r'''#!/bin/bash
printf 'worktree replacement executed\n' > "$FAKE_MALICIOUS_PUBLISHER_LOG"
exit 95
'''

FAKE_FD_INSPECTOR = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys

publisher_stat = os.stat(sys.argv[1])
inherited = []
for descriptor in range(3, 256):
  try:
    descriptor_stat = os.fstat(descriptor)
  except OSError:
    continue
  if (descriptor_stat.st_dev, descriptor_stat.st_ino) != (publisher_stat.st_dev, publisher_stat.st_ino):
    continue
  inherited.append(descriptor)
  try:
    os.lseek(descriptor, 0, os.SEEK_END)
  except OSError:
    pass
if inherited:
  Path(os.environ['FAKE_PUBLISHER_FD_LOG']).write_text('publisher descriptor inherited by child: {}\n'.format(inherited), encoding='ascii')
'''

FAKE_FORWARDER = r'''#!PYTHON_EXECUTABLE
import json
import os
from pathlib import Path
import sys

tool = Path(sys.argv[0]).name
SENSITIVE_ENVIRONMENT = ('GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'ENV_TOKEN_SOURCE', 'ENV_TOKEN_CONTENT')
record = {'tool': tool, 'gh_token': os.environ.get('GH_TOKEN'), 'github_token': os.environ.get('GITHUB_TOKEN'), 'gh_host': os.environ.get('GH_HOST'), 'sensitive_environment': {name: os.environ.get(name) for name in SENSITIVE_ENVIRONMENT}}
with Path(os.environ['FAKE_SUBPROCESS_LOG']).open('a', encoding='utf-8') as stream:
  stream.write(json.dumps(record, sort_keys=True) + '\n')
real_path = os.environ['FAKE_REAL_' + tool.upper().replace('-', '_')]
os.execv(real_path, [real_path] + sys.argv[1:])
'''


@unittest.skipUnless(os.name == 'posix' and Path('/bin/bash').is_file(),
                     'workflow publisher tests require POSIX /bin/bash')
class PublishWorkflowRunTest(unittest.TestCase):

  def setUp(self):
    self.temporary_directory = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary_directory.name)
    self.distrib = self.root / 'tools' / 'distrib'
    self.head_distrib = self.root / '.head' / 'tools' / 'distrib'
    self.fake_bin = self.root / 'bin'
    self.temp_root = self.root / 'tmp'
    self.distrib.mkdir(parents=True)
    self.head_distrib.mkdir(parents=True)
    self.fake_bin.mkdir()
    self.temp_root.mkdir()
    self.wrapper = self.distrib / 'publish_workflow_run.sh'
    shutil.copy2(SOURCE_WRAPPER, self.wrapper)
    self.wrapper.chmod(0o755)
    shutil.copy2(SOURCE_WRAPPER, self.head_distrib / 'publish_workflow_run.sh')
    self.publisher = self.distrib / 'publish_distributions.sh'
    self.publisher.write_text(FAKE_PUBLISHER, encoding='utf-8')
    self.publisher.chmod(0o755)
    self.head_publisher = self.head_distrib / 'publish_distributions.sh'
    self.head_publisher.write_text(FAKE_PUBLISHER, encoding='utf-8')
    self.head_publisher.chmod(0o755)
    self.verifier = self.distrib / 'verify_distribution_archive.py'
    self.verifier.write_text(FAKE_VERIFIER, encoding='utf-8')
    self.verifier.chmod(0o644)
    self.head_verifier = self.head_distrib / 'verify_distribution_archive.py'
    self.head_verifier.write_text(FAKE_VERIFIER, encoding='utf-8')
    self.head_verifier.chmod(0o644)
    self.git_log = self.root / 'git.log'
    self.git_show_count = self.root / 'git-show-count'
    self.gh_log = self.root / 'gh.log'
    self.subprocess_log = self.root / 'subprocess.log'
    self.publisher_log = self.root / 'publisher.log'
    self.publisher_fd_log = self.root / 'publisher-fd.log'
    self.malicious_publisher_log = self.root / 'malicious-publisher.log'
    self.shell_injection_log = self.root / 'shell-injection.log'
    self.state_path = self.root / 'state.json'
    self.write_executable('git', FAKE_GIT)
    self.write_executable('gh', FAKE_GH)
    self.write_executable('inspect-publisher-fds', FAKE_FD_INSPECTOR)
    self.real_tools = {}
    for tool in ('chmod', 'cmp', 'dirname', 'mkdir', 'mktemp', 'mv', 'rm',
                 'stat', 'tr', 'wc'):
      self.write_forwarder(tool)
    if shutil.which('sha256sum'):
      self.write_forwarder('sha256sum')
    else:
      self.write_forwarder('shasum')
    self.state = self.canonical_state()
    self.write_state()

  def tearDown(self):
    self.temporary_directory.cleanup()

  def write_executable(self, name, contents):
    path = self.fake_bin / name
    contents = contents.replace('#!/usr/bin/env python3',
                                '#!{}'.format(sys.executable), 1)
    path.write_text(contents, encoding='utf-8')
    path.chmod(0o755)

  def write_forwarder(self, name):
    real_path = shutil.which(name)
    self.assertIsNotNone(real_path,
                         'missing required test tool: {}'.format(name))
    self.real_tools[name] = real_path
    self.write_executable(name,
                          FAKE_FORWARDER.replace('PYTHON_EXECUTABLE',
                                                 sys.executable, 1))

  def canonical_state(self):
    jobs = []
    for index, name in enumerate(JOB_NAMES):
      jobs.append({
          'id': 1000 + index,
          'name': name,
          'status': 'completed',
          'conclusion': 'success',
          'attempt': 1,
          'run_id': int(RUN_ID),
          'head_sha': RUN_SHA,
          'head_branch': 'master',
          'workflow_name': 'Build JCEF'
      })
    artifacts = []
    contents = {}
    artifact_id = 2000
    for target in TARGETS:
      archive_name = target + '.tar.gz'
      archive = build_valid_archive(target, RUN_SHA)
      archive_digest = hashlib.sha256(archive).hexdigest()
      checksum_name = archive_name + '.sha256'
      checksum = '{}  {}\n'.format(archive_digest, archive_name).encode('ascii')
      for name, data in ((archive_name, archive), (checksum_name, checksum)):
        artifacts.append({
            'id': artifact_id,
            'name': name,
            'size': len(data),
            'digest': 'sha256:' + hashlib.sha256(data).hexdigest(),
            'expired': False,
            'run_id': int(RUN_ID),
            'repository_id': int(REPOSITORY_ID),
            'head_repository_id': int(REPOSITORY_ID),
            'head_branch': 'master',
            'head_sha': RUN_SHA
        })
        contents[str(artifact_id)] = base64.b64encode(data).decode('ascii')
        artifact_id += 1
    return {
        'repository_id': REPOSITORY_ID,
        'workflow_id': WORKFLOW_ID,
        'run_id': RUN_ID,
        'run_sha': RUN_SHA,
        'run_attempt': '1',
        'jobs': jobs,
        'artifacts': artifacts,
        'contents': contents
    }

  def write_state(self):
    self.state_path.write_text(
        json.dumps(self.state, sort_keys=True), encoding='utf-8')

  def environment(self, **updates):
    environment = os.environ.copy()
    for name in tuple(environment):
      if name in ('BASH_ENV', 'ENV', 'GITHUB_TOKEN', 'GH_TOKEN',
                  'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'GH_HOST',
                  'ENV_TOKEN_SOURCE',
                  'ENV_TOKEN_CONTENT') or name.startswith('BASH_FUNC_'):
        environment.pop(name, None)
    environment.update({
        'FAKE_ROOT':
            str(self.root),
        'FAKE_HEAD_ROOT':
            str(self.root / '.head'),
        'FAKE_RUN_SHA':
            RUN_SHA,
        'FAKE_GIT_LOG':
            str(self.git_log),
        'FAKE_GIT_SHOW_COUNT':
            str(self.git_show_count),
        'FAKE_GH_LOG':
            str(self.gh_log),
        'FAKE_SUBPROCESS_LOG':
            str(self.subprocess_log),
        'FAKE_STATE':
            str(self.state_path),
        'FAKE_PUBLISHER_LOG':
            str(self.publisher_log),
        'FAKE_PUBLISHER_FD_LOG':
            str(self.publisher_fd_log),
        'FAKE_MALICIOUS_PUBLISHER_LOG':
            str(self.malicious_publisher_log),
        'FAKE_SHELL_INJECTION_LOG':
            str(self.shell_injection_log),
        'TMPDIR':
            str(self.temp_root),
        'PATH':
            '{}{}{}'.format(self.fake_bin, os.pathsep,
                            environment.get('PATH', ''))
    })
    for tool, real_path in self.real_tools.items():
      environment['FAKE_REAL_' + tool.upper().replace('-', '_')] = real_path
    environment.update(updates)
    return environment

  def run_wrapper(self, run_id=RUN_ID, environment=None):
    return subprocess.run(
        [str(self.wrapper), run_id],
        check=False,
        capture_output=True,
        text=True,
        env=environment or self.environment(),
        cwd=self.root)

  def gh_records(self):
    if not self.gh_log.exists():
      return []
    return [
        json.loads(line)
        for line in self.gh_log.read_text(encoding='utf-8').splitlines()
    ]

  def subprocess_records(self):
    if not self.subprocess_log.exists():
      return []
    return [
        json.loads(line)
        for line in self.subprocess_log.read_text(encoding='utf-8')
        .splitlines()
    ]

  def publisher_record(self):
    values = {}
    files = []
    for line in self.publisher_log.read_text(encoding='utf-8').splitlines():
      key, value = line.split('=', 1)
      if key == 'file':
        files.append(value)
      else:
        values[key] = value
    values['files'] = sorted(files)
    return values

  def assert_no_credentials_in_non_gh_subprocesses(self):
    records = self.subprocess_records()
    self.assertGreater(len(records), 0)
    observed_tools = {record['tool'] for record in records}
    self.assertTrue({'git', *self.real_tools}.issubset(observed_tools),
                    'missing subprocess audit coverage: {}'.format(
                        sorted({'git', *self.real_tools} - observed_tools)))
    for record in records:
      self.assertIsNone(record['gh_token'], record)
      self.assertIsNone(record['github_token'], record)
      self.assertIsNone(record['gh_host'], record)
      self.assertTrue(all(value is None for value in record['sensitive_environment'].values()), record)

  def assert_rejected_before_publisher(self, expected_error=None):
    self.write_state()
    result = self.run_wrapper()
    self.assertNotEqual(0, result.returncode)
    if expected_error:
      self.assertIn(expected_error, result.stderr)
    self.assertFalse(self.publisher_log.exists())

  def test_exact_keyring_authenticated_run_downloads_by_id_and_publishes(self):
    result = self.run_wrapper()
    self.assertEqual(0, result.returncode, result.stderr)
    record = self.publisher_record()
    self.assertEqual('HEAD', record['implementation'])
    self.assertEqual(RUN_SHA, record['sha'])
    self.assertEqual('700', record['mode'])
    self.assertEqual('700', record['private_root_mode'])
    self.assertEqual('400', record['script_mode'])
    self.assertEqual('400', record['verifier_mode'])
    self.assertEqual('trusted-verifier-from-head', record['verifier_contents'])
    self.assertEqual(Path(record['directory']).parent, Path(record['script_path']).parent)
    self.assertEqual(Path(record['script_path']).parent, Path(record['verifier_path']).parent)
    self.assertNotEqual(Path(record['directory']), Path(record['script_path']).parent)
    self.assertEqual('publish_distributions.sh', Path(record['script_path']).name)
    self.assertFalse(Path(record['script_path']).exists())
    self.assertEqual('verify_distribution_archive.py', Path(record['verifier_path']).name)
    self.assertFalse(Path(record['verifier_path']).exists())
    self.assertEqual(
        sorted(artifact['name']
               for artifact in self.state['artifacts']), record['files'])
    self.assertFalse(Path(record['directory']).exists())
    self.assertEqual('', record['gh_token'])
    self.assertEqual('', record['github_token'])
    self.assertEqual('github.com', record['gh_host'])
    self.assertEqual('', record['gh_enterprise_token'])
    self.assertEqual('', record['github_enterprise_token'])
    self.assertEqual('', record['env_token_source'])
    self.assertEqual('', record['env_token_content'])
    gh_records = self.gh_records()
    self.assertTrue(
        all(record['github_token'] is None and record['gh_token'] is None and
            record['gh_host'] == 'github.com' for record in gh_records))
    self.assertEqual(['repos/Keksuccino/jcef-mcef/actions/workflows/build-jcef.yml'], [record['endpoint'] for record in gh_records if '/actions/workflows/' in record['endpoint']])
    self.assert_no_credentials_in_non_gh_subprocesses()
    download_endpoints = [
        record['endpoint'] for record in gh_records
        if '/actions/artifacts/' in record['endpoint']
    ]
    self.assertEqual([
        'repos/Keksuccino/jcef-mcef/actions/artifacts/{}/zip'.format(
            artifact['id']) for artifact in self.state['artifacts']
    ], download_endpoints)
    git_calls = [
        json.loads(line)['arguments']
        for line in self.git_log.read_text(encoding='utf-8').splitlines()
    ]
    self.assertEqual(2,
                     sum(1 for arguments in git_calls if 'fetch' in arguments))

  def assert_explicit_credential_flow(self, environment, expected_token):
    result = self.run_wrapper(environment=environment)
    self.assertEqual(0, result.returncode, result.stderr)
    for record in self.gh_records():
      self.assertEqual(expected_token, record['gh_token'], record)
      self.assertIsNone(record['github_token'], record)
      self.assertEqual('github.com', record['gh_host'], record)
    publisher = self.publisher_record()
    self.assertEqual(expected_token, publisher['gh_token'])
    self.assertEqual('', publisher['github_token'])
    self.assertEqual('github.com', publisher['gh_host'])
    self.assertEqual('', publisher['gh_enterprise_token'])
    self.assertEqual('', publisher['github_enterprise_token'])
    self.assertEqual('', publisher['env_token_source'])
    self.assertEqual('', publisher['env_token_content'])
    self.assert_no_credentials_in_non_gh_subprocesses()

  def test_gh_token_precedes_github_token_without_non_gh_leakage(self):
    self.assert_explicit_credential_flow(self.environment(GH_TOKEN=GH_TOKEN, GITHUB_TOKEN=GITHUB_TOKEN, GH_ENTERPRISE_TOKEN='must-not-leak', GITHUB_ENTERPRISE_TOKEN='must-not-leak', ENV_TOKEN_SOURCE='must-not-remain-exported', ENV_TOKEN_CONTENT='must-not-remain-exported', GH_HOST='untrusted.example'), GH_TOKEN)

  def test_github_token_is_normalized_without_non_gh_leakage(self):
    self.assert_explicit_credential_flow(
        self.environment(
            GITHUB_TOKEN=GITHUB_TOKEN, GH_HOST='untrusted.example'),
        GITHUB_TOKEN)

  def test_empty_preferred_token_does_not_fall_back_or_spawn_children(self):
    result = self.run_wrapper(environment=self.environment(
        GH_TOKEN=' \n\t', GITHUB_TOKEN=GITHUB_TOKEN))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('GH_TOKEN must contain a non-whitespace token', result.stderr)
    self.assertFalse(self.git_log.exists())
    self.assertFalse(self.gh_log.exists())
    self.assertFalse(self.subprocess_log.exists())
    self.assertFalse(self.publisher_log.exists())

  def test_privileged_startup_blocks_bash_env_and_exported_gh_function(self):
    bash_environment = self.root / 'malicious-bash-env'
    bash_environment.write_text("printf 'BASH_ENV executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"\ngh() { printf 'BASH_ENV gh function executed\\n' >> \"$FAKE_SHELL_INJECTION_LOG\"; return 97; }\nexport -f gh\n", encoding='utf-8')
    environment = self.environment(BASH_ENV=str(bash_environment))
    environment[
        'BASH_FUNC_gh%%'] = '() { printf \'exported gh function executed\\n\' >> "$FAKE_SHELL_INJECTION_LOG"; return 98; }'
    result = self.run_wrapper(environment=environment)
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertFalse(self.shell_injection_log.exists())
    self.assertTrue(self.publisher_log.exists())

  def test_non_privileged_bash_invocation_is_rejected_before_children(self):
    result = subprocess.run(['/bin/bash', str(self.wrapper), RUN_ID], check=False, capture_output=True, text=True, env=self.environment(), cwd=self.root)
    self.assertNotEqual(0, result.returncode)
    self.assertIn('execute publish_workflow_run.sh directly', result.stderr)
    self.assertFalse(self.git_log.exists())
    self.assertFalse(self.gh_log.exists())
    self.assertFalse(self.subprocess_log.exists())
    self.assertFalse(self.publisher_log.exists())

  def test_successful_second_attempt_ignores_prior_job_executions(self):
    prior_jobs = [dict(job) for job in self.state['jobs']]
    self.state['run_attempt'] = '2'
    self.state['run_outputs'] = [RUN_SHA + '|2']
    for index, job in enumerate(self.state['jobs']):
      job['id'] += 100
      job['attempt'] = 2
      prior_jobs[index]['attempt'] = 1
    self.state['prior_jobs'] = prior_jobs
    self.write_state()
    result = self.run_wrapper()
    self.assertEqual(0, result.returncode, result.stderr)
    job_endpoints = [
        record['endpoint'] for record in self.gh_records()
        if record['endpoint'].endswith('/jobs?per_page=100') or
        '/jobs?filter=' in record['endpoint']
    ]
    self.assertEqual([
        'repos/Keksuccino/jcef-mcef/actions/runs/{}/attempts/2/jobs?per_page=100'
        .format(RUN_ID)
    ], job_endpoints)
    self.assert_no_credentials_in_non_gh_subprocesses()

  def test_prior_attempt_artifacts_fail_closed_when_api_returns_them(self):
    self.state['run_attempt'] = '2'
    self.state['run_outputs'] = [RUN_SHA + '|2']
    for job in self.state['jobs']:
      job['attempt'] = 2
    prior_artifacts = []
    for artifact in self.state['artifacts']:
      prior_artifact = dict(artifact)
      prior_artifact['id'] += 10000
      prior_artifacts.append(prior_artifact)
    self.state['artifacts'] = prior_artifacts + self.state['artifacts']
    self.assert_rejected_before_publisher()

  def test_gh_children_cannot_access_trusted_publisher_descriptor(self):
    result = self.run_wrapper(environment=self.environment(FAKE_DRAIN_PUBLISHER_FD='1'))
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertFalse(self.publisher_fd_log.exists())
    self.assertEqual('HEAD', self.publisher_record()['implementation'])

  def test_publisher_children_cannot_inherit_publisher_script_descriptor(self):
    result = self.run_wrapper(environment=self.environment(FAKE_INSPECT_PUBLISHER_FDS='1'))
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertFalse(self.publisher_fd_log.exists())
    self.assertEqual('HEAD', self.publisher_record()['implementation'])

  def test_worktree_replacement_after_final_check_cannot_change_publisher(self):
    self.state['replace_publisher_on_run_call'] = 2
    self.state['replacement_publisher'] = base64.b64encode(
        MALICIOUS_PUBLISHER.encode('utf-8')).decode('ascii')
    self.write_state()
    result = self.run_wrapper()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual('HEAD', self.publisher_record()['implementation'])
    self.assertFalse(self.malicious_publisher_log.exists())
    self.assertEqual(
        MALICIOUS_PUBLISHER, self.publisher.read_text(encoding='utf-8'))

  def test_worktree_verifier_replacement_after_final_check_cannot_change_head_copy(self):
    replacement = b'worktree-verifier-replacement\n'
    self.state['replace_verifier_on_run_call'] = 2
    self.state['replacement_verifier'] = base64.b64encode(replacement).decode('ascii')
    self.write_state()
    result = self.run_wrapper()
    self.assertEqual(0, result.returncode, result.stderr)
    record = self.publisher_record()
    self.assertEqual('trusted-verifier-from-head', record['verifier_contents'])
    self.assertEqual(replacement, self.verifier.read_bytes())

  def test_invalid_run_id_fails_before_git_or_github(self):
    for run_id in ('', '0', '-1', '1.0', 'abc', ' 1', '1\n2'):
      with self.subTest(run_id=repr(run_id)):
        for path in (self.git_log, self.gh_log, self.subprocess_log,
                     self.publisher_log):
          if path.exists():
            path.unlink()
        result = self.run_wrapper(run_id=run_id)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.git_log.exists())
        self.assertFalse(self.gh_log.exists())
        self.assertFalse(self.subprocess_log.exists())
        self.assertFalse(self.publisher_log.exists())

  def test_checkout_and_run_identity_fail_closed(self):
    cases = (({
        'FAKE_ORIGIN_URL': 'https://github.com/attacker/repo.git'
    }, None), ({
        'FAKE_HEAD_SHA': '8' * 40
    }, None), ({
        'FAKE_ORIGIN_SHA': '8' * 40
    }, None), ({
        'FAKE_FETCH_FAILURE': '1'
    }, None), ({
        'FAKE_DIRTY_PATH': 'tools/distrib/publish_distributions.sh'
    }, None), ({
        'FAKE_DIRTY_PATH': 'tools/distrib/verify_distribution_archive.py'
    }, None), ({
        'FAKE_HEAD_BLOB_MISMATCH': 'tools/distrib/publish_workflow_run.sh'
    }, None), ({
        'FAKE_HEAD_BLOB_MISMATCH':
            'tools/distrib/verify_distribution_archive.py'
    }, None), ({
        'FAKE_HEAD_SHOW_MISMATCH_CALL': '4'
    }, None), ({}, 'invalid'), ({}, RUN_SHA + '|0'), ({}, '8' * 40 + '|1'),)
    for environment_updates, run_output in cases:
      with self.subTest(
          environment_updates=environment_updates, run_output=run_output):
        self.state = self.canonical_state()
        if run_output is not None:
          self.state['run_outputs'] = [run_output]
        self.write_state()
        for path in (self.git_log, self.git_show_count, self.gh_log,
                     self.subprocess_log, self.publisher_log):
          if path.exists():
            path.unlink()
        result = self.run_wrapper(environment=self.environment(
            **environment_updates))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.publisher_log.exists())

  def test_job_set_rejects_missing_extra_duplicate_failed_and_mismatched_provenance(
      self):
    mutations = (
        lambda state: state['jobs'].pop(),
        lambda state: state['jobs'].append(dict(state['jobs'][0], id=9999, name='Unexpected')),
        lambda state: state['jobs'][1].update(name=state['jobs'][0]['name']),
        lambda state: state['jobs'][1].update(id=state['jobs'][0]['id']),
        lambda state: state['jobs'][1].update(id=0),
        lambda state: state['jobs'][1].update(conclusion='failure'),
        lambda state: state['jobs'][1].update(status='in_progress'),
        lambda state: state['jobs'][1].update(attempt=2),
        lambda state: state['jobs'][1].update(run_id=1),
        lambda state: state['jobs'][1].update(head_sha='8' * 40),
        lambda state: state['jobs'][1].update(head_branch='other'),
        lambda state: state['jobs'][1].update(workflow_name='Other'),
    )
    for mutation in mutations:
      with self.subTest(mutation=mutation):
        self.state = self.canonical_state()
        mutation(self.state)
        self.assert_rejected_before_publisher()

  def test_artifact_set_rejects_malicious_duplicate_expired_and_mismatched_metadata(
      self):
    mutations = (
        lambda state: state['artifacts'].pop(),
        lambda state: state['artifacts'].append(dict(state['artifacts'][0], id=9999, name='unexpected.txt')),
        lambda state: state['artifacts'][1].update(name=state['artifacts'][0]['name']),
        lambda state: state['artifacts'][1].update(id=state['artifacts'][0]['id']),
        lambda state: state['artifacts'][1].update(id=0),
        lambda state: state['artifacts'][1].update(name='../escape'),
        lambda state: state['artifacts'][1].update(expired=True),
        lambda state: state['artifacts'][1].update(size=0),
        lambda state: state['artifacts'][1].update(digest='sha256:' + 'A' * 64),
        lambda state: state['artifacts'][1].update(run_id=1),
        lambda state: state['artifacts'][1].update(repository_id=1),
        lambda state: state['artifacts'][1].update(head_repository_id=1),
        lambda state: state['artifacts'][1].update(head_branch='other'),
        lambda state: state['artifacts'][1].update(head_sha='8' * 40),
    )
    for mutation in mutations:
      with self.subTest(mutation=mutation):
        self.state = self.canonical_state()
        mutation(self.state)
        self.assert_rejected_before_publisher()

  def test_access_download_and_post_download_run_changes_fail_before_publisher(
      self):
    cases = (('immutable_output', 'boolean|false'), ('login_output', ''),
             ('login_output', 'bad|login'), ('corrupt_download_id', '2000'),
             ('run_outputs', [RUN_SHA + '|1', RUN_SHA + '|2']),)
    for key, value in cases:
      with self.subTest(key=key):
        self.state = self.canonical_state()
        self.state[key] = value
        self.assert_rejected_before_publisher()


if __name__ == '__main__':
  unittest.main()
