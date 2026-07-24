#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.
"""Create a validated JCEF/MCEF binary distribution and tar.gz archive."""

from __future__ import absolute_import
from __future__ import print_function

import argparse
import gzip
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from distribution import CEF_API_VERSION, CEF_VERSION, DistributionError
from distribution import JCEF_RUNTIME_FILES, cef_runtime_manifest
from distribution import jogamp_jars, mac_runtime_requirements, resolve_target
from distribution import sha256_file
from distribution import validate_archive, validate_build_configuration
from distribution import validate_host, validate_jar_class_version
from distribution import validate_matching_jar_classes, validate_runtime

JAVA_CHECK_NAMES = {
    'linux': 'java17_check.sh',
    'macos': 'java17_check.sh',
    'windows': 'java17_check.bat',
}

LAUNCHER_NAMES = {
    'linux': ('compile.sh', 'run.sh'),
    'macos': ('compile.sh',),
    'windows': ('compile.bat', 'run.bat'),
}


def _run(command, cwd):
  print('+ {}'.format(' '.join(str(argument) for argument in command)))
  result = subprocess.run([str(argument) for argument in command], cwd=str(cwd))
  if result.returncode != 0:
    raise DistributionError('Command failed with exit code {}: {}'.format(
        result.returncode, command[0]))


def _run_build_tool(repository_root, script_name, target_name=None):
  script = repository_root / 'tools' / script_name
  arguments = [] if target_name is None else [target_name]
  if os.name == 'nt':
    _run(['cmd.exe', '/d', '/c', script] + arguments, repository_root)
  else:
    _run([script] + arguments, repository_root)


def _copy_entry(source, destination):
  if source.is_symlink():
    destination.symlink_to(
        os.readlink(str(source)), target_is_directory=source.is_dir())
  elif source.is_dir():
    shutil.copytree(str(source), str(destination), symlinks=True)
  else:
    shutil.copy2(str(source), str(destination))


def _sign_flat_mac_app(app_path):
  framework = (app_path / 'Contents' / 'Frameworks' /
               'Chromium Embedded Framework.framework')
  _run([
      '/usr/bin/codesign', '--force', '--sign', '-', '--timestamp=none',
      framework
  ], app_path.parent)
  _run([
      '/usr/bin/codesign', '--force', '--sign', '-', '--timestamp=none',
      app_path
  ], app_path.parent)


def _require_linux_strip():
  strip_program = shutil.which('strip')
  if strip_program is None:
    raise DistributionError(
        'Linux distribution packaging requires strip with --strip-debug '
        'support, but strip was not found on PATH.')
  return strip_program


def _strip_linux_runtime_debug_sections(runtime_root, strip_program):
  """Strip debug sections from regular ELF files in a copied runtime tree."""

  def _walk_error(error):
    raise DistributionError(
        'Unable to scan staged Linux runtime files: {}'.format(error))

  elf_paths = []
  for directory, directory_names, file_names in os.walk(
      str(runtime_root), topdown=True, onerror=_walk_error, followlinks=False):
    directory_path = Path(directory)
    # Never descend through a copied link. A link is rejected by archive
    # validation later, and following one here could modify a build input.
    directory_names[:] = sorted(
        name for name in directory_names
        if not (directory_path / name).is_symlink())
    for file_name in sorted(file_names):
      path = directory_path / file_name
      if path.is_symlink() or not path.is_file():
        continue
      try:
        with path.open('rb') as stream:
          is_elf = stream.read(4) == b'\x7fELF'
      except OSError as exc:
        raise DistributionError(
            'Unable to inspect staged Linux runtime file {}: {}'.format(
                path, exc))
      if is_elf:
        elf_paths.append(path)

  for path in elf_paths:
    original_mode = stat.S_IMODE(path.stat().st_mode)
    command = [strip_program, '--strip-debug', str(path)]
    print('+ {}'.format(' '.join(command)))
    try:
      result = subprocess.run(
          command,
          check=False,
          stdout=subprocess.PIPE,
          stderr=subprocess.PIPE,
          text=True)
    except OSError as exc:
      raise DistributionError(
          'Unable to strip staged Linux ELF file {}: {}'.format(path, exc))
    if result.returncode != 0:
      details = result.stderr.strip() or result.stdout.strip() or 'no output'
      raise DistributionError(
          'strip --strip-debug failed for staged Linux ELF file {} with exit '
          'code {}: {}'.format(path, result.returncode, details))
    if path.is_symlink() or not path.is_file():
      raise DistributionError(
          'strip did not leave a regular staged Linux ELF file: {}'.format(
              path))
    path.chmod(original_mode)
    with path.open('rb') as stream:
      if stream.read(4) != b'\x7fELF':
        raise DistributionError(
            'strip produced an invalid staged Linux ELF file: {}'.format(path))


def _copy_runtime(native_output, destination, cef_root, target):
  if target.family == 'macos':
    app_path = destination / 'jcef_app.app'
    _copy_entry(native_output / 'jcef_app.app', app_path)
    framework = (app_path / 'Contents' / 'Frameworks' /
                 'Chromium Embedded Framework.framework')
    # MCEF's hardened archive extractor intentionally rejects links. Replace the
    # developer build's versioned framework with CEF 151's canonical flat
    # framework, then re-sign the changed nested bundle and enclosing app.
    shutil.rmtree(str(framework))
    shutil.copytree(
        str(cef_root / 'Release' / 'Chromium Embedded Framework.framework'),
        str(framework),
        symlinks=True)
    return mac_runtime_requirements(target, 'flat')
  strip_program = _require_linux_strip() if target.family == 'linux' else None
  binaries, resources = cef_runtime_manifest(cef_root, target)
  entries = list(binaries + resources + JCEF_RUNTIME_FILES[target.family])
  if target.family == 'linux' and (native_output / 'libminigbm.so').is_file():
    entries.append('libminigbm.so')
  for relative_path in entries:
    _copy_entry(native_output / relative_path, destination / relative_path)
  if strip_program is not None:
    # Strip only after copying. The Release build and downloaded CEF artifacts
    # are reused by tests and must remain byte-for-byte untouched.
    _strip_linux_runtime_debug_sections(destination, strip_program)
  return tuple(entries)


def _copy_templates(repository_root, destination, target):
  template_root = repository_root / 'tools' / 'distrib' / target.family
  if not template_root.is_dir():
    raise DistributionError(
        'Distribution template directory is missing: {}'.format(template_root))
  for source in template_root.iterdir():
    if source.name.startswith('README.'):
      continue
    _copy_entry(source, destination / source.name)
  java_check = (
      repository_root / 'tools' / 'distrib' / JAVA_CHECK_NAMES[target.family])
  _copy_entry(java_check, destination / java_check.name)


def _copy_java_artifacts(repository_root, destination, target):
  out_path = repository_root / 'out' / target.name
  java_jars = ('jcef.jar', 'jcef-tests.jar')
  for jar_name in java_jars:
    source = out_path / jar_name
    validate_jar_class_version(source)
    shutil.copy2(str(source), str(destination / jar_name))

  jogamp_source = repository_root / 'third_party' / 'jogamp' / 'jar'
  selected_jogamp_jars = jogamp_jars(target)
  for jar_name in selected_jogamp_jars:
    source = jogamp_source / jar_name
    if not source.is_file():
      raise DistributionError(
          'Required matching JogAmp artifact is missing for {}: {}'.format(
              target.name, source))
    shutil.copy2(str(source), str(destination / jar_name))

  if target.family == 'macos':
    app_java = destination / 'jcef_app.app' / 'Contents' / 'Java'
    # Refresh Java archives before signing so a reused native build cannot
    # publish stale classes and the final resource seal covers exact outputs.
    for jar_name in java_jars + selected_jogamp_jars:
      shutil.copy2(str(destination / jar_name), str(app_java / jar_name))
    validate_matching_jar_classes(destination / 'jcef.jar',
                                  app_java / 'jcef.jar')
    validate_matching_jar_classes(destination / 'jcef-tests.jar',
                                  app_java / 'jcef-tests.jar')
    for jar_name in selected_jogamp_jars:
      if not (app_java / jar_name).is_file():
        raise DistributionError(
            'Signed macOS app bundle is missing {}.'.format(jar_name))
    _sign_flat_mac_app(destination / 'jcef_app.app')
  return java_jars, selected_jogamp_jars


def _copy_documentation_and_licenses(repository_root, destination, cef_root,
                                     target):
  shutil.copytree(
      str(repository_root / 'out' / 'docs'), str(destination / 'docs'))
  shutil.copytree(
      str(repository_root / 'java' / 'tests'), str(destination / 'tests'))
  shutil.copy2(str(repository_root / 'LICENSE.txt'), str(destination))
  shutil.copy2(
      str(cef_root / 'LICENSE.txt'), str(destination / 'CEF-LICENSE.txt'))
  shutil.copy2(str(cef_root / 'CREDITS.html'), str(destination))
  if target.supports_jogl_swing_osr:
    for source in (
        repository_root / 'third_party' / 'jogamp').glob('*.LICENSE.txt'):
      shutil.copy2(str(source), str(destination))


def _create_readme(repository_root, destination, target):
  _run([
      sys.executable, repository_root / 'tools' / 'make_readme.py',
      '--output-dir', destination, '--platform', target.name
  ], repository_root)


def _write_distribution_manifest(destination, target, runtime_entries,
                                 java_jars, selected_jogamp_jars):
  data = {
      'archive_root': target.name,
      'cef_api_version': CEF_API_VERSION,
      'cef_version': CEF_VERSION,
      'java_release': 17,
      'jogl_swing_osr_supported': target.supports_jogl_swing_osr,
      'jogamp_jars': list(selected_jogamp_jars),
      'jcef_jars': list(java_jars),
      'runtime_entries': list(runtime_entries),
      'target': target.name,
  }
  with (destination / 'DISTRIBUTION-MANIFEST.json').open(
      'w', encoding='utf-8', newline='\n') as stream:
    json.dump(data, stream, indent=2, sort_keys=True)
    stream.write('\n')


def _archive_filter(member):
  # Stable metadata avoids leaking CI/user account details and makes identical
  # distribution trees byte-for-byte reproducible across hosts.
  member.uid = 0
  member.gid = 0
  member.uname = 'root'
  member.gname = 'root'
  member.mtime = 946684800
  member.mode = 0o755 if member.isdir() or member.mode & 0o111 else 0o644
  member.pax_headers = {}
  return member


def _create_archive(distribution_path, archive_path, target):
  with archive_path.open('wb') as compressed_stream:
    with gzip.GzipFile(
        filename='',
        mode='wb',
        compresslevel=9,
        fileobj=compressed_stream,
        mtime=946684800) as gzip_stream:
      with tarfile.open(
          fileobj=gzip_stream,
          mode='w',
          format=tarfile.PAX_FORMAT,
          dereference=False) as archive:
        archive.add(
            str(distribution_path),
            arcname=target.name,
            recursive=True,
            filter=_archive_filter)


def _write_checksum(archive_path, checksum_path):
  digest = sha256_file(archive_path)
  checksum_path.write_text(
      '{}  {}\n'.format(digest, archive_path.name), encoding='ascii')


def create_distribution(repository_root, target):
  validate_host(target)
  cef_root = validate_build_configuration(repository_root, target)
  native_output = repository_root / 'jcef_build' / 'native' / 'Release'
  validate_runtime(native_output, cef_root, target)

  binary_root = repository_root / 'binary_distrib'
  distribution_path = binary_root / target.name
  archive_path = binary_root / '{}.tar.gz'.format(target.name)
  checksum_path = binary_root / '{}.tar.gz.sha256'.format(target.name)
  existing_outputs = [
      path for path in (distribution_path, archive_path, checksum_path)
      if path.exists()
  ]
  if existing_outputs:
    raise DistributionError(
        'Refusing to overwrite existing distribution output(s): {}. Remove '
        'only those generated paths and retry.'.format(
            ', '.join(str(path) for path in existing_outputs)))

  _run_build_tool(repository_root, 'make_jar.bat'
                  if os.name == 'nt' else 'make_jar.sh', target.name)
  _run_build_tool(repository_root, 'make_docs.bat'
                  if os.name == 'nt' else 'make_docs.sh')

  binary_root.mkdir(parents=True, exist_ok=True)
  staging_root = Path(
      tempfile.mkdtemp(prefix='.{}-'.format(target.name), dir=str(binary_root)))
  staging_distribution = staging_root / target.name
  staging_archive = staging_root / archive_path.name
  staging_checksum = staging_root / checksum_path.name
  try:
    staging_distribution.mkdir()
    runtime_entries = _copy_runtime(native_output, staging_distribution,
                                    cef_root, target)
    java_jars, selected_jogamp_jars = _copy_java_artifacts(
        repository_root, staging_distribution, target)
    _copy_documentation_and_licenses(repository_root, staging_distribution,
                                     cef_root, target)
    _copy_templates(repository_root, staging_distribution, target)
    _create_readme(repository_root, staging_distribution, target)
    _write_distribution_manifest(staging_distribution, target, runtime_entries,
                                 java_jars, selected_jogamp_jars)

    runtime_requirements = validate_runtime(
        staging_distribution,
        cef_root,
        target,
        mac_framework_layout='flat'
        if target.family == 'macos' else 'versioned')
    validate_jar_class_version(staging_distribution / 'jcef.jar')
    validate_jar_class_version(staging_distribution / 'jcef-tests.jar')
    _create_archive(staging_distribution, staging_archive, target)
    required_directory_paths = ['docs', 'tests']
    if target.family != 'macos':
      required_directory_paths.append('locales')
    required_archive_paths = tuple(runtime_requirements) + (
        'CEF-LICENSE.txt', 'CREDITS.html', 'DISTRIBUTION-MANIFEST.json',
        'LICENSE.txt', 'README.txt', 'docs', 'jcef.jar', 'jcef-tests.jar',
        'tests',
        JAVA_CHECK_NAMES[target.family]) + LAUNCHER_NAMES[target.family]
    validate_archive(staging_archive, target, required_archive_paths,
                     tuple(required_directory_paths))
    _write_checksum(staging_archive, staging_checksum)

    staging_distribution.rename(distribution_path)
    staging_archive.rename(archive_path)
    staging_checksum.rename(checksum_path)
    staging_root.rmdir()
  except Exception:
    shutil.rmtree(str(staging_root), ignore_errors=True)
    raise

  print('Created {}'.format(distribution_path))
  print('Created {}'.format(archive_path))
  print('Created {}'.format(checksum_path))
  return distribution_path, archive_path, checksum_path


def main(argv=None):
  parser = argparse.ArgumentParser(
      description='Create an exact CEF 151 JCEF binary distribution.')
  parser.add_argument('target', help='Canonical platform target')
  options = parser.parse_args(argv)
  try:
    target = resolve_target(options.target)
    repository_root = Path(__file__).resolve().parents[2]
    create_distribution(repository_root, target)
  except (DistributionError, OSError, subprocess.SubprocessError,
          tarfile.TarError, zipfile.BadZipFile) as exc:
    print('ERROR: {}'.format(exc), file=sys.stderr)
    return 1
  return 0


if __name__ == '__main__':
  sys.exit(main())
