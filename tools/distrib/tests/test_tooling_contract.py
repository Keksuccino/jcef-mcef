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
      return subprocess.run(command, check=False, capture_output=True, text=True, env=environment)

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

  def test_every_workflow_architecture_runs_isolated_windowless_and_windowed_suites(self):
    workflow = (
        REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(
            encoding='utf-8')
    self.assertEqual(3, workflow.count('name: Run windowless/native JUnit suite'))
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
    self.assertEqual(1, windows_job_text.count(WINDOWLESS_RENDERING_CONFIG_ARGUMENT))
    self.assertIn(quoted_argument, windows_job_text)

  def test_every_workflow_architecture_builds_and_runs_native_unit_tests(self):
    workflow = (REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(encoding='utf-8')
    build_command = 'cmake --build jcef_build --config Release --target mouse_wheel_platform_util_test --parallel 4'
    test_command = 'ctest --test-dir jcef_build --build-config Release --output-on-failure'
    covered_targets = 0
    for job_name in ('linux', 'windows', 'macos'):
      job = re.search(r'^  {}:\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)'.format(job_name), workflow, re.DOTALL | re.MULTILINE)
      self.assertIsNotNone(job)
      covered_targets += len(re.findall(r'^\s+target:\s+(?:linux|windows|macos)_', job.group(1), re.MULTILINE))
      self.assertEqual(1, job.group(1).count('name: Build and run native unit tests'))
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

  def test_windows_arm64_places_chromium_fence_on_original_process_command_line(self):
    runner = (TOOLS_ROOT / 'run_tests.bat').read_text(encoding='utf-8')
    arm64_block = re.search(r'if /I "%PLATFORM%" == "windows_arm64" \((.*?)\n\)', runner, re.DOTALL)
    self.assertIsNotNone(arm64_block)
    self.assertIn('set "JUNIT_LAUNCHER_OPTION=-cp"', arm64_block.group(1))
    self.assertIn('set "JUNIT_LAUNCHER_PATH=%JUNIT_JAR%;%CLASS_PATH%"', arm64_block.group(1))
    self.assertIn('set "JUNIT_LAUNCHER_CLASS=tests.junittests.WindowsJUnitLauncher"', arm64_block.group(1))
    self.assertIn('set "CHROMIUM_PROCESS_ARGUMENT=--disable-best-effort-tasks"', arm64_block.group(1))
    self.assertEqual(1, runner.count('--disable-best-effort-tasks'))
    self.assertIn('set "JUNIT_LAUNCHER_OPTION=-jar"', runner)
    self.assertIn('set "CHROMIUM_PROCESS_ARGUMENT="', runner)
    self.assertIn('%JUNIT_LAUNCHER_CLASS% %CHROMIUM_PROCESS_ARGUMENT% execute', runner)

  def test_windows_arm64_browser_process_mitigations_remain_test_only(self):
    helper = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' / 'WindowsArm64TestCommandLine.java').read_text(encoding='utf-8')
    setup = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' /
             'TestSetupExtension.java').read_text(encoding='utf-8')
    retry_process = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' / 'CefPreInitializationRetryProcess.java').read_text(encoding='utf-8')
    retry_test = (REPOSITORY_ROOT / 'java' / 'tests' / 'junittests' / 'CefPreInitializationRetryTest.java').read_text(encoding='utf-8')
    self.assertIn('if (!processType.isEmpty() || !usesMitigations(windows, architecture)) return;', helper)
    self.assertIn('DISABLE_BEST_EFFORT_TASKS_SWITCH = "--disable-best-effort-tasks"', helper)
    self.assertIn('WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE = "WebAuthenticationUseInsecureSoftwareUnexportableKeys"', helper)
    self.assertIn('WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE = "ReportKeyCredentialManagerSupportWin"', helper)
    self.assertIn('appendCommaSeparatedSwitchValue(commandLine, ENABLE_FEATURES_SWITCH, WINDOWS_SOFTWARE_UNEXPORTABLE_KEYS_FEATURE);', helper)
    self.assertIn('appendCommaSeparatedSwitchValue(commandLine, DISABLE_FEATURES_SWITCH, WINDOWS_KEY_CREDENTIAL_TELEMETRY_FEATURE);', helper)
    callback = 'WindowsArm64TestCommandLine.configureBrowserProcess(processType, commandLine);'
    self.assertIn(callback, setup)
    self.assertIn(callback, retry_process)
    early_switch = retry_test.index('WindowsArm64TestCommandLine.appendEarlyProcessSwitch(command);')
    self.assertIn('setStaticField("appHandler_", null);', retry_process)
    main_class = retry_test.index('command.add(CefPreInitializationRetryProcess.class.getName());')
    child_arguments = retry_test.index('Path rootCache =')
    reset = retry_process.index('resetJavaConstructorState(abandoned);')
    handler = retry_process.index('CefApp.addAppHandler(retryHandler);')
    assertion = retry_process.index('assertRetryHandlerInstalled(retryHandler);')
    retry = retry_process.index('CefApp retried = CefApp.getInstance(settings);')
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
    workflow = (REPOSITORY_ROOT / '.github' / 'workflows' / 'build-jcef.yml').read_text(encoding='utf-8')
    setup_python_revision = 'a309ff8b426b58ec0e2a45f0f869d46889d02405'
    self.assertEqual(3, workflow.count('uses: actions/setup-python@{}'.format(setup_python_revision)))
    self.assertEqual(3, workflow.count("python-version: '3.12.10'"))
    self.assertEqual(3, workflow.count('architecture: ${{ matrix.python_architecture }}'))
    self.assertEqual(3, workflow.count('id: setup-python'))
    self.assertEqual(3, workflow.count('PYTHON_EXECUTABLE: ${{ steps.setup-python.outputs.python-path }}'))
    self.assertEqual(3, len(re.findall(r'python_architecture:\s+x64\b', workflow)))
    self.assertEqual(3, len(re.findall(r'python_architecture:\s+arm64\b', workflow)))


if __name__ == '__main__':
  unittest.main()
