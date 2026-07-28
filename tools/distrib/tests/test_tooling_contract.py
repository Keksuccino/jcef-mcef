#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
TOOLS_ROOT = REPOSITORY_ROOT / 'tools'
CANONICAL_TARGETS = ('linux_amd64', 'linux_arm64', 'macos_amd64', 'macos_arm64',
                     'windows_amd64', 'windows_arm64')
WINDOWLESS_RENDERING_CONFIG_ARGUMENT = '--config=jcef.windowless_rendering_enabled=false'


class Java17CheckTest(unittest.TestCase):

  def run_check(self, version, tools, available_tools=None):
    with tempfile.TemporaryDirectory() as temporary_directory:
      java_home = Path(temporary_directory) / 'jdk'
      bin_directory = java_home / 'bin'
      bin_directory.mkdir(parents=True)
      (java_home / 'release').write_text(
          'JAVA_VERSION="{}"\n'.format(version), encoding='ascii')
      if available_tools is None:
        available_tools = tools
      for tool in available_tools:
        suffix = '.exe' if os.name == 'nt' else ''
        tool_path = bin_directory / '{}{}'.format(tool, suffix)
        if os.name == 'nt':
          tool_path.touch()
        else:
          tool_path.write_text('#!/bin/sh\nexit 0\n', encoding='ascii')
          tool_path.chmod(0o755)
      environment = os.environ.copy()
      environment['JAVA_HOME'] = str(java_home)
      if os.name == 'nt':
        helper = TOOLS_ROOT / 'distrib' / 'java17_check.bat'
        command = [
            environment.get('COMSPEC', 'cmd.exe'), '/D', '/C', 'call',
            str(helper), *tools
        ]
      else:
        helper = TOOLS_ROOT / 'distrib' / 'java17_check.sh'
        command = [
            '/bin/bash', '-c', 'source "$1"; shift; require_java17 "$@"',
            'java17-test',
            str(helper), *tools
        ]
      return subprocess.run(
          command, check=False, capture_output=True, text=True, env=environment)

  def test_exact_java_17_release_is_accepted(self):
    self.assertEqual(0, self.run_check('17.0.15', ('java', 'javac')).returncode)

  def test_non_17_release_is_rejected(self):
    result = self.run_check('21.0.7', ('java',))
    self.assertNotEqual(0, result.returncode)
    self.assertIn('JDK 17 is required', result.stderr)

  def test_missing_required_jdk_tool_is_rejected(self):
    result = self.run_check('17.0.15', ('java', 'jar'))
    self.assertEqual(0, result.returncode)
    missing_result = self.run_check('17.0.15', ('java',), available_tools=())
    self.assertNotEqual(0, missing_result.returncode)
    self.assertIn('java was not found', missing_result.stderr)


class PlatformToolingContractTest(unittest.TestCase):

  def test_public_tools_and_build_docs_use_only_canonical_target_names(self):
    public_files = ('.github/workflows/build-jcef.yml', 'appveyor.yml',
                    'README.md', 'docs/branches_and_building.md',
                    'tools/compile.sh', 'tools/compile.bat', 'tools/run.sh',
                    'tools/run.bat', 'tools/run_tests.sh',
                    'tools/run_tests.bat', 'tools/make_jar.sh',
                    'tools/make_jar.bat', 'tools/make_distrib.sh',
                    'tools/make_distrib.bat', 'tools/make_readme.sh',
                    'tools/make_readme.bat')
    legacy_name = re.compile(
        r'\b(?:linux32|linux64|linuxarm64|macosx64|macosarm64|win32|win64|'
        r'windows32|windows64|windowsarm64)\b', re.IGNORECASE)
    for relative_path in public_files:
      contents = (REPOSITORY_ROOT / relative_path).read_text(encoding='utf-8')
      self.assertIsNone(
          legacy_name.search(contents),
          '{} exposes a legacy target name'.format(relative_path))

  def test_workflow_builds_and_packages_all_six_targets(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    for target in CANONICAL_TARGETS:
      self.assertEqual(
          1, len(re.findall(r'target:\s+{}\b'.format(target), workflow)))
      self.assertIn("tools/make_distrib", workflow)
      self.assertIn('binary_distrib/${{ matrix.target }}.tar.gz', workflow)
      self.assertIn('binary_distrib/${{ matrix.target }}.tar.gz.sha256',
                    workflow)

  def test_documentation_scripts_fail_on_javadoc_warnings(self):
    for script_name in ('make_docs.sh', 'make_docs.bat'):
      script = (TOOLS_ROOT / script_name).read_text(encoding='utf-8')
      self.assertEqual(1, script.count('-Werror'), script_name)

  def test_every_workflow_architecture_runs_isolated_windowless_and_windowed_suites(
      self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    self.assertEqual(3,
                     workflow.count('name: Run windowless/native JUnit suite'))
    self.assertEqual(3, workflow.count('name: Run windowed JUnit suite'))
    self.assertEqual(3, workflow.count('--include-tag native-cef'))
    self.assertEqual(3, workflow.count('--exclude-tag windowed-cef'))
    self.assertEqual(3, workflow.count('--include-tag windowed-cef'))
    self.assertEqual(3, workflow.count(WINDOWLESS_RENDERING_CONFIG_ARGUMENT))
    self.assertNotIn("if: matrix.platform == 'amd64'", workflow)

  def test_windows_windowed_junit_config_is_one_quoted_batch_argument(self):
    workflow_path = REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml'
    workflow = workflow_path.read_text(encoding='utf-8')
    job_pattern = r'^  windows:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'
    windows_job = re.search(job_pattern, workflow, re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(windows_job)
    windows_job_text = windows_job.group(1)
    quoted_argument = '"{}"'.format(WINDOWLESS_RENDERING_CONFIG_ARGUMENT)
    self.assertEqual(
        1, windows_job_text.count(WINDOWLESS_RENDERING_CONFIG_ARGUMENT))
    self.assertIn(quoted_argument, windows_job_text)

  def test_windows_arm64_uses_java17_distribution_without_vm_exit_failure(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    job_pattern = r'^  windows:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'
    windows_job = re.search(job_pattern, workflow, re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(windows_job)
    windows_job_text = windows_job.group(1)
    amd64_entry = re.search(r'- runner: windows-2022\n(.*?)(?=\n\s+- runner:)',
                            windows_job_text, re.DOTALL)
    arm64_entry = re.search(r'- runner: windows-11-arm\n(.*?)(?=\n\s+runs-on:)',
                            windows_job_text, re.DOTALL)
    self.assertIsNotNone(amd64_entry)
    self.assertIsNotNone(arm64_entry)
    self.assertIn('java_distribution: microsoft', amd64_entry.group(1))
    self.assertIn("java_version: '17'", amd64_entry.group(1))
    self.assertIn('java_distribution: zulu', arm64_entry.group(1))
    self.assertIn("java_version: '17.0.20+8'", arm64_entry.group(1))
    self.assertIn('distribution: ${{ matrix.java_distribution }}',
                  windows_job_text)
    self.assertIn('java-version: ${{ matrix.java_version }}', windows_job_text)

  def test_windows_ant_download_retries_verified_mirrors(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    windows_job = re.search(r'^  windows:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)',
                            workflow, re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(windows_job)
    ant_step = re.search(
        r'^      - name: Set up Apache Ant 1\.10\.17\n(.*?)(?=^      - (?:name:|uses:)|\Z)',
        windows_job.group(1), re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(ant_step)
    step_text = ant_step.group(1)
    self.assertIn('https://downloads.apache.org/ant/binaries/', step_text)
    self.assertIn('https://archive.apache.org/dist/ant/binaries/', step_text)
    self.assertIn(
        'for ($attempt = 1; $attempt -le 3 -and -not $downloaded; $attempt++)',
        step_text)
    self.assertIn(
        'Invoke-WebRequest -Uri $downloadUri -OutFile $antArchive -TimeoutSec 120',
        step_text)
    self.assertIn('Get-FileHash -Algorithm SHA512 -Path $antArchive', step_text)
    self.assertIn('if ($actualHash -ne $expectedHash)', step_text)
    self.assertIn(
        'Remove-Item -Path $antArchive -Force -ErrorAction SilentlyContinue',
        step_text)
    self.assertIn('Start-Sleep -Seconds (5 * $attempt)', step_text)
    self.assertLess(
        step_text.index('Get-FileHash -Algorithm SHA512'),
        step_text.index('$downloaded = $true'))

  def test_every_workflow_architecture_builds_and_runs_native_unit_tests(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    build_command = 'cmake --build jcef_build --config Release --target mouse_wheel_platform_util_test permission_util_test --parallel 4'
    test_command = 'ctest --test-dir jcef_build --build-config Release --output-on-failure'
    covered_targets = 0
    for job_name in ('linux', 'windows', 'macos'):
      job = re.search(
          r'^  {}:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'.format(job_name),
          workflow, re.DOTALL | re.MULTILINE)
      self.assertIsNotNone(job)
      covered_targets += len(
          re.findall(r'^\s+target:\s+(?:linux|windows|macos)_',
                     job.group(1), re.MULTILINE))
      self.assertEqual(
          1, job.group(1).count('name: Build and run native unit tests'))
      self.assertEqual(1, job.group(1).count(build_command))
      self.assertEqual(1, job.group(1).count(test_command))
    self.assertEqual(6, covered_targets)

  def test_macos_headless_tests_do_not_use_first_thread_mode(self):
    runner = (TOOLS_ROOT / 'run_tests.sh').read_text(encoding='utf-8')
    self.assertIn('if [ "$HEADLESS" = false ]', runner)
    self.assertIn('JAVA_OPTIONS=(-XstartOnFirstThread', runner)
    self.assertIn('tests.junittests.MacJUnitLauncher', runner)
    self.assertIn('-cp "${JUNIT_JAR}:${CLASS_PATH}"', runner)
    launcher = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
                'MacJUnitLauncher.java').read_text(encoding='utf-8')
    self.assertIn('ConsoleLauncher.run(', launcher)
    self.assertIn('runLoop.invoke(null, mediator, true, false)', launcher)
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    self.assertIn(
        'Run native-independent JUnit suite without AppKit first-thread mode',
        workflow)
    self.assertIn('Release --headless --select-package', workflow)

  def test_macos_app_bundle_uses_complete_internal_awt_option_order(self):
    build = (REPOSITORY_ROOT / 'build.xml').read_text(encoding='utf-8')
    options = re.findall(r'<option value="(--[^"]+)"/>', build)
    expected_options = [
        '--add-opens=java.desktop/sun.awt=ALL-UNNAMED',
        '--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED',
        '--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED',
        '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
        '--enable-native-access=ALL-UNNAMED',
    ]
    self.assertEqual(expected_options, options)

  def test_windows_java_check_uses_release_metadata_and_exact_prefix(self):
    helper = (TOOLS_ROOT / 'distrib' / 'java17_check.bat').read_text(
        encoding='utf-8')
    self.assertIn('%JAVA_HOME%\\release', helper)
    self.assertIn('if "%JAVA_VERSION%" == "17"', helper)
    self.assertIn('if "%JAVA_VERSION:~0,3%" == "17."', helper)
    self.assertNotIn('java.exe" -version', helper)

  def test_windows_jni_header_verification_returns_from_ant_before_python(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    windows_job = re.search(r'^  windows:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)',
                            workflow, re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(windows_job)
    verification_step = re.search(
        r'^      - name: Verify toolchain and generated JNI headers\n(.*?)(?=^      - (?:name:|uses:)|\Z)',
        windows_job.group(1), re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(verification_step)
    step_text = verification_step.group(1)
    ant_call = 'call ant -version'
    header_verification = 'python tools/make_jni_headers.py --verify'
    self.assertIn('shell: cmd', step_text)
    self.assertEqual(1, step_text.count(ant_call))
    self.assertEqual(1, step_text.count(header_verification))
    self.assertLess(
        step_text.index(ant_call), step_text.index(header_verification))

  def test_windows_arm64_places_chromium_fence_on_original_process_command_line(
      self):
    runner = (TOOLS_ROOT / 'run_tests.bat').read_text(encoding='utf-8')
    arm64_block = re.search(
        r'if /I "%PLATFORM%" == "windows_arm64" \((.*?)\n\)', runner, re.DOTALL)
    self.assertIsNotNone(arm64_block)
    self.assertIn('set "JUNIT_LAUNCHER_OPTION=-cp"', arm64_block.group(1))
    self.assertIn('set "JUNIT_LAUNCHER_PATH=%JUNIT_JAR%;%CLASS_PATH%"',
                  arm64_block.group(1))
    self.assertIn(
        'set "JUNIT_LAUNCHER_CLASS=tests.junittests.WindowsJUnitLauncher"',
        arm64_block.group(1))
    self.assertIn('set "CHROMIUM_PROCESS_ARGUMENT=--disable-best-effort-tasks"',
                  arm64_block.group(1))
    self.assertEqual(1, runner.count('--disable-best-effort-tasks'))
    self.assertIn('set "JUNIT_LAUNCHER_OPTION=-jar"', runner)
    self.assertIn('set "CHROMIUM_PROCESS_ARGUMENT="', runner)
    self.assertIn('%JUNIT_LAUNCHER_CLASS% %CHROMIUM_PROCESS_ARGUMENT% execute',
                  runner)

  def test_windows_test_runner_opens_internal_awt_shutdown_api(self):
    runner = (TOOLS_ROOT / 'run_tests.bat').read_text(encoding='utf-8')
    java_invocation = '"%JAVA_HOME%\\bin\\java.exe"'
    module_open = '--add-opens=java.desktop/sun.awt=ALL-UNNAMED'
    self.assertEqual(1, runner.count(module_open))
    invocation_index = runner.index(java_invocation)
    self.assertLess(invocation_index, runner.index(module_open))
    self.assertLess(
        runner.index(module_open),
        runner.index('%JUNIT_LAUNCHER_OPTION%', invocation_index))

  def test_windows_test_runner_detects_its_own_jvm_crash_report(self):
    runner = (TOOLS_ROOT / 'run_tests.bat').read_text(encoding='utf-8')
    report_id = 'set "JVM_CRASH_REPORT_ID=%RANDOM%_%RANDOM%_%RANDOM%_%RANDOM%"'
    report_path = 'set "JVM_CRASH_REPORT=%OUT_PATH%\\hs_err_pid%%p_%JVM_CRASH_REPORT_ID%.log"'
    report_glob = '%OUT_PATH%\\hs_err_pid*_%JVM_CRASH_REPORT_ID%.log'
    pid_log_path = 'set "JVM_PID_LOG=%OUT_PATH%\\jvm_pid_%%p_%JVM_CRASH_REPORT_ID%.log"'
    pid_log_glob = '%OUT_PATH%\\jvm_pid_*_%JVM_CRASH_REPORT_ID%.log'
    java_invocation = '"-XX:ErrorFile=%JVM_CRASH_REPORT%"'
    pid_log_invocation = '"-Xlog:os=info:file=%JVM_PID_LOG%:none:filecount=0"'
    capture_exit = 'set "TEST_EXIT_CODE=%ERRORLEVEL%"'
    detect_report = 'for %%F in ("{}") do if exist "%%~fF" ('.format(
        report_glob)
    capture_pid = 'for %%F in ("{}") do if exist "%%~fF" call :capture_jvm_process_id'.format(
        pid_log_glob)
    cwd_fallback = 'call :record_jvm_crash_report "%JVM_LAUNCH_DIRECTORY%\\hs_err_pid%JVM_PROCESS_ID%.log"'
    temp_fallback = 'call :record_jvm_crash_report "%JVM_TEMP_PATH%\\hs_err_pid%JVM_PROCESS_ID%.log"'
    fail_success = 'if defined JVM_CRASH_REPORT_CREATED if "%TEST_EXIT_CODE%" == "0" set "TEST_EXIT_CODE=1"'
    self.assertIn(report_id, runner)
    self.assertIn('if exist "{}" goto prepare_crash_report'.format(report_glob),
                  runner)
    self.assertIn(
        'if exist "{}" goto prepare_crash_report'.format(pid_log_glob), runner)
    self.assertIn(report_path, runner)
    self.assertIn(pid_log_path, runner)
    self.assertIn(java_invocation, runner)
    self.assertIn(pid_log_invocation, runner)
    self.assertIn(detect_report, runner)
    self.assertIn(capture_pid, runner)
    self.assertIn(cwd_fallback, runner)
    self.assertIn(temp_fallback, runner)
    self.assertIn('set "JVM_TEMP_PATH=%TMP%"', runner)
    self.assertIn('if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%TEMP%"',
                  runner)
    self.assertIn(
        'if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%USERPROFILE%"',
        runner)
    self.assertIn(
        'if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%SystemRoot%"', runner)
    self.assertIn('if not defined JVM_PROCESS_ID (', runner)
    self.assertIn('copy /Y "%~1" "%OUT_PATH%\\%~nx1"', runner)
    self.assertIn('JVM fatal error report was created:', runner)
    self.assertIn(fail_success, runner)
    self.assertLess(runner.index(report_id), runner.index(java_invocation))
    self.assertLess(
        runner.index(pid_log_path), runner.index(pid_log_invocation))
    self.assertLess(runner.index(java_invocation), runner.index(capture_exit))
    self.assertLess(runner.index(capture_exit), runner.index(detect_report))
    self.assertLess(runner.index(detect_report), runner.index(capture_pid))
    self.assertLess(runner.index(capture_pid), runner.index(cwd_fallback))
    self.assertLess(runner.index(detect_report), runner.index(fail_success))

  def test_windows_arm64_browser_process_mitigations_remain_test_only(self):
    helper = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
              'WindowsArm64TestCommandLine.java').read_text(encoding='utf-8')
    setup = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
             'TestSetupExtension.java').read_text(encoding='utf-8')
    retry_process = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
                     'CefPreInitializationRetryProcess.java').read_text(
                         encoding='utf-8')
    retry_test = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
                  'CefPreInitializationRetryTest.java').read_text(
                      encoding='utf-8')
    self.assertIn(
        'if (!processType.isEmpty() || !usesMitigations(windows, architecture)) return;',
        helper)
    self.assertIn(
        'DISABLE_BEST_EFFORT_TASKS_SWITCH = "--disable-best-effort-tasks"',
        helper)
    self.assertIn(
        'WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE = "WebAuthenticationUseInsecureSoftwareUnexportableKeys"',
        helper)
    self.assertIn(
        'WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE = "ReportKeyCredentialManagerSupportWin"',
        helper)
    self.assertIn(
        'appendCommaSeparatedSwitchValue(commandLine, ENABLE_FEATURES_SWITCH, WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE);',
        helper)
    self.assertIn(
        'appendCommaSeparatedSwitchValue(commandLine, DISABLE_FEATURES_SWITCH, WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);',
        helper)
    callback = 'WindowsArm64TestCommandLine.configureBrowserProcess(processType, commandLine);'
    self.assertIn(callback, setup)
    self.assertIn(callback, retry_process)
    early_switch = retry_test.index(
        'WindowsArm64TestCommandLine.appendEarlyProcessSwitch(command);')
    self.assertIn('setStaticField("appHandler_", null);', retry_process)
    main_class = retry_test.index(
        'command.add(CefPreInitializationRetryProcess.class.getName());')
    child_arguments = retry_test.index('Path rootCache =')
    reset = retry_process.index('resetJavaConstructorState(abandoned);')
    handler = retry_process.index('CefApp.addAppHandler(retryHandler);')
    assertion = retry_process.index(
        'assertRetryHandlerInstalled(retryHandler);')
    retry = retry_process.index(
        'CefApp retried = CefApp.getInstance(settings);')
    self.assertLess(main_class, early_switch)
    self.assertLess(early_switch, child_arguments)
    self.assertLess(reset, handler)
    self.assertLess(handler, assertion)
    self.assertLess(assertion, retry)

  def test_github_actions_are_pinned_to_immutable_commits(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    action_uses = re.findall(r'uses:\s+[^@\s]+@([^\s#]+)', workflow)
    self.assertGreater(len(action_uses), 0)
    for revision in action_uses:
      self.assertRegex(revision, r'^[0-9a-f]{40}$')

  def test_workflow_pins_compatible_python_for_every_architecture(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    setup_python_revision = 'a309ff8b426b58ec0e2a45f0f869d46889d02405'
    platform_jobs = ''
    for job_name in ('linux', 'windows', 'macos'):
      job = re.search(r'^  {}:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'.format(job_name), workflow, re.DOTALL | re.MULTILINE)
      self.assertIsNotNone(job)
      platform_jobs += job.group(1)
    self.assertEqual(3, platform_jobs.count('uses: actions/setup-python@{}'.format(setup_python_revision)))
    self.assertEqual(3, platform_jobs.count("python-version: '3.12.10'"))
    self.assertEqual(3, platform_jobs.count('architecture: ${{ matrix.python_architecture }}'))
    self.assertEqual(3, platform_jobs.count('id: setup-python'))
    self.assertEqual(3, platform_jobs.count('PYTHON_EXECUTABLE: ${{ steps.setup-python.outputs.python-path }}'))
    x64_platform_count = len(re.findall(r'python_architecture:\s+x64\b', platform_jobs))
    arm64_platform_count = len(re.findall(r'python_architecture:\s+arm64\b', platform_jobs))
    self.assertEqual(3, x64_platform_count)
    self.assertEqual(3, arm64_platform_count)
    self.assertEqual(4, workflow.count('uses: actions/setup-python@{}'.format(setup_python_revision)))
    self.assertEqual(4, workflow.count("python-version: '3.12.10'"))


class PublicationWorkflowContractTest(unittest.TestCase):

  def setUp(self):
    self.workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')

  def job(self, name):
    match = re.search(
        r'^  {}:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'.format(name),
        self.workflow, re.DOTALL | re.MULTILINE)
    self.assertIsNotNone(match, 'missing {} workflow job'.format(name))
    return match.group(1)

  def test_workflow_only_builds_and_never_claims_publication_authority(self):
    self.assertIsNotNone(
        re.search(r'^  workflow_dispatch:\n', self.workflow, re.MULTILINE))
    self.assertNotIn('inputs:', self.workflow)
    self.assertNotIn('publish:', self.workflow)
    global_permissions = re.search(r'^permissions:\n((?:  [^\n]+\n)+)',
                                   self.workflow, re.MULTILINE)
    self.assertIsNotNone(global_permissions)
    self.assertEqual(1, self.workflow.count('permissions:'))
    self.assertEqual(['contents: read'], [
        line.strip() for line in global_permissions.group(1).splitlines()
    ])
    self.assertNotIn('contents: write', self.workflow)
    self.assertNotIn('GITHUB_TOKEN', self.workflow)
    self.assertNotIn('github.token', self.workflow)
    self.assertNotIn('secrets.', self.workflow)
    self.assertNotIn('publish_distributions.sh', self.workflow)
    self.assertNotIn('actions/download-artifact', self.workflow)
    self.assertNotIn('needs:', self.workflow)
    jobs = self.workflow.split('\njobs:\n', 1)[1]
    self.assertEqual(['sources', 'linux', 'windows', 'macos'], re.findall(r'^  ([a-z][a-z0-9_-]*):\n', jobs, re.MULTILINE))
    for build_job_name in ('sources', 'linux', 'windows', 'macos'):
      build_job = self.job(build_job_name)
      self.assertNotIn('s3cmd', build_job)
      self.assertNotIn('S3_CFG', build_job)
      self.assertNotIn('GITHUB_TOKEN', build_job)
      self.assertNotIn('publish_distributions.sh', build_job)
    self.assertNotIn('s3cmd', self.workflow)
    self.assertNotIn('S3_CFG', self.workflow)

  def test_sources_job_has_exact_independent_build_shape(self):
    checkout_revision = 'de0fac2e4500dabe0009e67214ff5f5447ce83dd'
    setup_python_revision = 'a309ff8b426b58ec0e2a45f0f869d46889d02405'
    upload_revision = '043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'
    build_command = 'python3 tools/distrib/sources_jar.py build --repository-root . --output binary_distrib/jcef-mcef-sources.jar'
    expected_job = f"""    name: Java sources
    runs-on: ubuntu-24.04
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@{checkout_revision} # v6.0.2
      - name: Set up Python 3.12.10
        uses: actions/setup-python@{setup_python_revision} # v6.2.0
        with:
          python-version: '3.12.10'
          architecture: x64
      - name: Build Java sources JAR
        run: {build_command}
      - name: Publish Java sources workflow artifact
        uses: actions/upload-artifact@{upload_revision} # v7.0.1
        with:
          path: binary_distrib/jcef-mcef-sources.jar
          archive: false
          if-no-files-found: error"""

    sources_job = self.job('sources')
    self.assertEqual(expected_job, sources_job.rstrip())
    self.assertNotIn('needs:', sources_job)
    self.assertEqual(1, self.workflow.count(build_command))

  def test_all_direct_artifacts_are_uploaded_as_canonical_raw_files(self):
    upload_revision = '043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'
    for build_job_name in ('linux', 'windows', 'macos'):
      build_job = self.job(build_job_name)
      self.assertNotIn('sources_jar.py', build_job)
      self.assertNotIn('jcef-mcef-sources.jar', build_job)
      expected_raw_upload_count = 3 if build_job_name == 'linux' else 2
      expected_action_count = 3 if build_job_name in ('linux', 'windows') else 2
      self.assertEqual(expected_action_count, build_job.count('uses: actions/upload-artifact@{}'.format(upload_revision)))
      self.assertEqual(1, build_job.count('path: binary_distrib/${{ matrix.target }}.tar.gz\n'))
      self.assertEqual(1, build_job.count('path: binary_distrib/${{ matrix.target }}.tar.gz.sha256\n'))
      self.assertEqual(expected_raw_upload_count, build_job.count('archive: false'))
      self.assertEqual(expected_raw_upload_count, build_job.count('if-no-files-found: error'))
    sources_job = self.job('sources')
    self.assertEqual(1, sources_job.count('uses: actions/upload-artifact@{}'.format(upload_revision)))
    self.assertEqual(1, sources_job.count('path: binary_distrib/jcef-mcef-sources.jar\n'))
    self.assertEqual(1, sources_job.count('archive: false'))
    self.assertEqual(1, sources_job.count('if-no-files-found: error'))
    self.assertNotIn('.sha256', sources_job)
    platform_target_count = self.workflow.count('target: ')
    self.assertEqual(6, platform_target_count)
    self.assertEqual(8, self.workflow.count('archive: false'))
    self.assertEqual(14, platform_target_count * 2 + 2)

  def test_linux_amd64_exports_the_verified_standalone_binary_jar_once(self):
    linux_job = self.job('linux')
    self.assertEqual(2, linux_job.count("if: matrix.target == 'linux_amd64'"))
    self.assertEqual(1, linux_job.count('cp -- binary_distrib/linux_amd64/jcef.jar binary_distrib/jcef-mcef.jar'))
    self.assertEqual(1, linux_job.count('--standalone-jcef-jar binary_distrib/jcef-mcef.jar'))
    self.assertEqual(1, linux_job.count('path: binary_distrib/jcef-mcef.jar\n'))
    for job_name in ('sources', 'windows', 'macos'):
      self.assertNotIn('jcef-mcef.jar', self.job(job_name))


if __name__ == '__main__':
  unittest.main()
