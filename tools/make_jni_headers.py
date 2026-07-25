#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT_DIR = Path(__file__).resolve().parent.parent
JAVA_SOURCE_DIR = ROOT_DIR / 'java' / 'org' / 'cef'
NATIVE_DIR = ROOT_DIR / 'native'

# javac derives verbose file names from the binary class name. JCEF's native
# sources intentionally use the simple class name, so this mapping is the
# checked source of truth for every tracked machine-generated header.
HEADER_CLASSES = (
    'org.cef.CefApp', 'org.cef.browser.CefBrowser_N',
    'org.cef.browser.CefFrame_N', 'org.cef.browser.CefMessageRouter_N',
    'org.cef.browser.CefRegistration_N', 'org.cef.browser.CefRequestContext_N',
    'org.cef.callback.CefAuthCallback_N',
    'org.cef.callback.CefBeforeDownloadCallback_N',
    'org.cef.callback.CefBinaryValue_N', 'org.cef.callback.CefCallback_N',
    'org.cef.callback.CefCommandLine_N',
    'org.cef.callback.CefContextMenuParams_N',
    'org.cef.callback.CefDictionaryValue_N',
    'org.cef.callback.CefDownloadItem_N',
    'org.cef.callback.CefDownloadItemCallback_N',
    'org.cef.callback.CefDragData_N',
    'org.cef.callback.CefFileDialogCallback_N',
    'org.cef.callback.CefJSDialogCallback_N', 'org.cef.callback.CefListValue_N',
    'org.cef.callback.CefMenuModel_N',
    'org.cef.callback.CefPrintDialogCallback_N',
    'org.cef.callback.CefPrintJobCallback_N',
    'org.cef.callback.CefQueryCallback_N',
    'org.cef.callback.CefResourceReadCallback_N',
    'org.cef.callback.CefResourceSkipCallback_N',
    'org.cef.callback.CefSchemeRegistrar_N', 'org.cef.callback.CefValue_N',
    'org.cef.handler.CefClientHandler', 'org.cef.misc.CefPrintSettings_N',
    'org.cef.network.CefCookieManager_N', 'org.cef.network.CefPostData_N',
    'org.cef.network.CefPostDataElement_N', 'org.cef.network.CefRequest_N',
    'org.cef.network.CefResponse_N', 'org.cef.network.CefURLRequest_N',)

# OpenJDK 17's JNIWriter emits MSVC's i64 suffix on Windows and LL on other
# platforms. Restrict normalization to its complete decimal constant macro
# form so declarations and all other generated content remain byte-exact.
JAVAC_WINDOWS_LONG_CONSTANT_PATTERN = re.compile(
    rb'^(#define [A-Za-z_][A-Za-z0-9_]* -?[0-9]+)i64$', re.MULTILINE)


def parse_args():
  parser = argparse.ArgumentParser(
      description='Generate or verify all JCEF JNI headers with JDK 17 javac -h.'
  )
  parser.add_argument(
      '--class-name',
      choices=HEADER_CLASSES,
      help='Update or verify only this tracked header.')
  parser.add_argument(
      '--verify',
      action='store_true',
      help='Fail if a tracked header differs from javac output.')
  return parser.parse_args()


def get_javac():
  java_home = os.environ.get('JAVA_HOME')
  if not java_home:
    raise RuntimeError('JAVA_HOME must point to a JDK 17 installation')

  javac = Path(java_home) / 'bin' / ('javac.exe'
                                     if os.name == 'nt' else 'javac')
  if not javac.is_file():
    raise RuntimeError(f'javac was not found at {javac}')

  result = subprocess.run(
      [str(javac), '-version'], check=True, capture_output=True, text=True)
  version_output = (result.stdout + result.stderr).strip()
  match = re.fullmatch(r'javac (\d+)(?:\..*)?', version_output)
  if not match or match.group(1) != '17':
    raise RuntimeError(f'JDK 17 is required; found {version_output}')
  return javac


def argfile_path(path):
  # Forward slashes and explicit quoting work in javac argument files on every
  # supported platform, including repository paths containing spaces.
  return '"' + path.resolve().as_posix().replace('"', '\\"') + '"'


def normalized_header(path):
  return path.read_bytes().replace(b'\r\n', b'\n')


def normalized_generated_header(path):
  content = normalized_header(path)
  return JAVAC_WINDOWS_LONG_CONSTANT_PATTERN.sub(rb'\g<1>LL', content)


def generate_headers(javac, temporary_dir):
  temporary_path = Path(temporary_dir)
  classes_dir = temporary_path / 'classes'
  headers_dir = temporary_path / 'headers'
  classes_dir.mkdir()
  headers_dir.mkdir()

  source_files = sorted(JAVA_SOURCE_DIR.rglob('*.java'))
  if not source_files:
    raise RuntimeError(f'No Java sources were found under {JAVA_SOURCE_DIR}')

  source_list = temporary_path / 'sources.args'
  source_list.write_text(
      '\n'.join(argfile_path(path) for path in source_files) + '\n',
      encoding='utf-8')

  compile_jars = [
      ROOT_DIR / 'third_party' / 'jogamp' / 'jar' / 'gluegen-rt.jar',
      ROOT_DIR / 'third_party' / 'jogamp' / 'jar' / 'jogl-all.jar',
  ]
  missing_jars = [path for path in compile_jars if not path.is_file()]
  if missing_jars:
    raise RuntimeError('Missing production dependency: ' + ', '.join(
        str(path) for path in missing_jars))
  class_path = os.pathsep.join(str(path) for path in compile_jars)
  command = [
      str(javac), '--release', '17', '-encoding', 'UTF-8', '-Xlint:none',
      '-classpath', class_path, '-h',
      str(headers_dir), '-d',
      str(classes_dir), '@' + str(source_list)
  ]
  subprocess.run(command, cwd=ROOT_DIR, check=True)

  expected_files = {
      class_name.replace('.', '_') + '.h'
      for class_name in HEADER_CLASSES
  }
  generated_files = {path.name for path in headers_dir.glob('*.h')}
  missing = sorted(expected_files - generated_files)
  unexpected = sorted(generated_files - expected_files)
  if missing or unexpected:
    details = []
    if missing:
      details.append('missing: ' + ', '.join(missing))
    if unexpected:
      details.append('unexpected: ' + ', '.join(unexpected))
    raise RuntimeError('JNI header class list is out of sync (' +
                       '; '.join(details) + ')')
  return headers_dir


def process_headers(headers_dir, class_name, verify):
  selected_classes = (class_name,) if class_name else HEADER_CLASSES
  stale_headers = []

  for selected_class in selected_classes:
    generated_path = headers_dir / (selected_class.replace('.', '_') + '.h')
    tracked_path = NATIVE_DIR / (selected_class.rsplit('.', 1)[-1] + '.h')
    generated_content = normalized_generated_header(generated_path)

    if verify:
      if not tracked_path.is_file() or normalized_header(
          tracked_path) != generated_content:
        stale_headers.append(tracked_path.relative_to(ROOT_DIR).as_posix())
    else:
      tracked_path.write_bytes(generated_content)

  if stale_headers:
    raise RuntimeError(
        'Stale JNI headers: ' + ', '.join(stale_headers) +
        '. Run tools/make_all_jni_headers.sh to regenerate them.')

  action = 'Verified' if verify else 'Updated'
  print(
      f'{action} {len(selected_classes)} JNI header(s) using javac --release 17 -h.'
  )


def main():
  args = parse_args()
  javac = get_javac()
  with tempfile.TemporaryDirectory(prefix='jcef-jni-headers-') as temporary_dir:
    headers_dir = generate_headers(javac, temporary_dir)
    process_headers(headers_dir, args.class_name, args.verify)
  return 0


if __name__ == '__main__':
  try:
    sys.exit(main())
  except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
    print(f'ERROR: {error}', file=sys.stderr)
    sys.exit(1)
