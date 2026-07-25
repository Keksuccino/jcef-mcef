#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.
"""Create a validated JCEF/MCEF binary distribution and tar.gz archive."""

from __future__ import absolute_import
from __future__ import print_function

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from distribution import CEF_API_VERSION, CEF_VERSION, DistributionError
from distribution import JCEF_RUNTIME_FILES, cef_runtime_manifest
from distribution import jogamp_jars, resolve_target
from distribution import sha256_file
from distribution import validate_archive, validate_build_configuration
from distribution import validate_host, validate_jar_class_version
from distribution import validate_matching_jar_classes, validate_runtime
from verify_distribution_archive import JOGAMP_LICENSE_FILES
from verify_distribution_archive import MANIFEST_NAME
from verify_distribution_archive import VerificationError
from verify_distribution_archive import verify_distribution_archive

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

MANIFEST_SCHEMA = 2
_JAVA_CEF_COMMIT_PATTERN = re.compile(r'^[0-9a-f]{40}$')


def _normalize_java_cef_commit(value):
  if not isinstance(value, str):
    raise DistributionError(
        'Java CEF source commit must be a 40-character hexadecimal string.')
  normalized = value.strip().lower()
  if _JAVA_CEF_COMMIT_PATTERN.fullmatch(normalized) is None:
    raise DistributionError(
        'Java CEF source commit must be exactly 40 hexadecimal characters, '
        'but found {!r}.'.format(value))
  return normalized


def _git_output(repository_root, arguments):
  # Repository-selection variables can redirect Git away from the checkout
  # passed by the caller. Publication provenance must come from that checkout,
  # so resolve with a clean Git context while preserving the normal PATH.
  environment = {
      key: value
      for key, value in os.environ.items() if not key.upper().startswith('GIT_')
  }
  command = ['git', '-C', str(repository_root)] + list(arguments)
  try:
    result = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=environment)
  except OSError as exc:
    raise DistributionError('Unable to inspect Java CEF source checkout {}: {}'.
                            format(repository_root, exc))
  if result.returncode != 0:
    details = result.stderr.strip() or result.stdout.strip() or 'no output'
    raise DistributionError(
        'Unable to inspect Java CEF source checkout {} with git {}: {}'.format(
            repository_root, ' '.join(arguments), details))
  return result.stdout.strip()


def _resolve_java_cef_commit(repository_root):
  try:
    repository_root = Path(repository_root).resolve(strict=True)
  except OSError as exc:
    raise DistributionError(
        'Java CEF source checkout does not exist: {}'.format(exc))
  if not repository_root.is_dir():
    raise DistributionError('Java CEF source checkout is not a directory: {}'.
                            format(repository_root))

  top_level_output = _git_output(repository_root, ('rev-parse',
                                                   '--show-toplevel'))
  if not top_level_output or '\n' in top_level_output or '\r' in top_level_output:
    raise DistributionError(
        'Git returned an invalid Java CEF checkout root: {!r}.'.format(
            top_level_output))
  try:
    top_level = Path(top_level_output).resolve(strict=True)
  except OSError as exc:
    raise DistributionError(
        'Git returned an unusable Java CEF checkout root {!r}: {}'.format(
            top_level_output, exc))
  try:
    is_same_checkout = os.path.samefile(str(repository_root), str(top_level))
  except OSError as exc:
    raise DistributionError(
        'Unable to compare Java CEF checkout roots {} and {}: {}'.format(
            repository_root, top_level, exc))
  if not is_same_checkout:
    raise DistributionError(
        'Java CEF source root {} belongs to a different checkout rooted at {}.'
        .format(repository_root, top_level))

  commit_output = _git_output(repository_root, ('rev-parse', '--verify',
                                                'HEAD^{commit}'))
  return _normalize_java_cef_commit(commit_output)


def _require_java_cef_commit(repository_root, expected_commit):
  expected_commit = _normalize_java_cef_commit(expected_commit)
  actual_commit = _resolve_java_cef_commit(repository_root)
  if actual_commit != expected_commit:
    raise DistributionError(
        'Java CEF source checkout changed during distribution creation: '
        'expected {}, found {}.'.format(expected_commit, actual_commit))


def _require_clean_source_checkout(repository_root):
  status = _git_output(repository_root, ('status', '--porcelain=v1',
                                         '--untracked-files=normal'))
  if status:
    raise DistributionError(
        'Java CEF source checkout is dirty; commit identity would not describe '
        'the packaged source tree.')


def _read_provenance_file(path, description):
  try:
    status = path.lstat()
  except OSError as exc:
    raise DistributionError(
        '{} is missing at {}: {}'.format(description, path, exc))
  if _is_link_like(path, status) or not stat.S_ISREG(status.st_mode):
    raise DistributionError(
        '{} must be a regular file: {}'.format(description, path))
  try:
    return path.read_text(encoding='utf-8')
  except (OSError, UnicodeError) as exc:
    raise DistributionError(
        'Unable to read {} at {}: {}'.format(description, path, exc))


def _validate_native_source_commit(repository_root, expected_commit):
  expected_commit = _normalize_java_cef_commit(expected_commit)
  header_path = Path(repository_root) / 'native' / 'jcef_version.h'
  contents = _read_provenance_file(header_path,
                                   'Generated native JCEF version header')
  declarations = re.findall(
      r'^[ \t]*#[ \t]*define[ \t]+JCEF_COMMIT_HASH\b[^\r\n]*$',
      contents,
      flags=re.MULTILINE)
  if len(declarations) != 1:
    raise DistributionError(
        '{} must contain exactly one JCEF_COMMIT_HASH definition.'.format(
            header_path))
  match = re.fullmatch(
      r'[ \t]*#[ \t]*define[ \t]+JCEF_COMMIT_HASH[ \t]+"([0-9A-Fa-f]{40})"[ \t]*',
      declarations[0])
  if match is None:
    raise DistributionError(
        '{} contains a malformed JCEF_COMMIT_HASH definition.'.format(
            header_path))
  native_commit = _normalize_java_cef_commit(match.group(1))
  if native_commit != expected_commit:
    raise DistributionError(
        'Native JCEF build commit mismatch: expected {}, found {} in {}.'.
        format(expected_commit, native_commit, header_path))


def _validate_readme_source_commit(readme_path, expected_commit):
  expected_commit = _normalize_java_cef_commit(expected_commit)
  readme_path = Path(readme_path)
  contents = _read_provenance_file(readme_path, 'Distribution README')
  revisions = re.findall(r'^[ \t]*@([^\r\n]*)$', contents, flags=re.MULTILINE)
  if len(revisions) != 1:
    raise DistributionError(
        '{} must contain exactly one full JCEF source revision.'.format(
            readme_path))
  try:
    readme_commit = _normalize_java_cef_commit(revisions[0])
  except DistributionError:
    raise DistributionError(
        '{} contains a malformed JCEF source revision.'.format(readme_path))
  if readme_commit != expected_commit:
    raise DistributionError(
        'Distribution README commit mismatch: expected {}, found {} in {}.'.
        format(expected_commit, readme_commit, readme_path))


def _normalize_runtime_path(value):
  if not isinstance(value, (str, os.PathLike)):
    raise DistributionError(
        'Runtime path must be a relative filesystem path, but found {!r}.'
        .format(value))
  value = os.fspath(value)
  if not isinstance(value, str):
    raise DistributionError(
        'Runtime path must be text, but found {!r}.'.format(value))
  contains_control_character = any(
      ord(character) < 32 or ord(character) == 127 for character in value)
  if (not value or '\\' in value or ':' in value or contains_control_character):
    raise DistributionError('Unsafe runtime path: {!r}.'.format(value))
  components = value.split('/')
  if any(component in ('', '.', '..') for component in components):
    raise DistributionError('Unsafe runtime path: {!r}.'.format(value))
  path = PurePosixPath(value)
  if path.is_absolute():
    raise DistributionError('Unsafe runtime path: {!r}.'.format(value))
  return path.as_posix()


def _normalize_runtime_entries(runtime_entries):
  if isinstance(runtime_entries, (str, os.PathLike)):
    raise DistributionError('Runtime entries must be a collection of paths.')
  normalized_entries = []
  seen_entries = set()
  for entry in runtime_entries:
    normalized_entry = _normalize_runtime_path(entry)
    if normalized_entry in seen_entries:
      raise DistributionError(
          'Duplicate runtime entry: {}'.format(normalized_entry))
    seen_entries.add(normalized_entry)
    normalized_entries.append(normalized_entry)
  if not normalized_entries:
    raise DistributionError('Runtime entry list must not be empty.')
  return tuple(sorted(normalized_entries))


def _is_link_like(path, status):
  if stat.S_ISLNK(status.st_mode):
    return True
  reparse_point = getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)
  if reparse_point and getattr(status, 'st_file_attributes', 0) & reparse_point:
    return True
  is_junction = getattr(path, 'is_junction', None)
  return is_junction is not None and is_junction()


def _runtime_path(destination, relative_path):
  relative_path = _normalize_runtime_path(relative_path)
  current = destination
  components = PurePosixPath(relative_path).parts
  status = None
  for index, component in enumerate(components):
    current = current / component
    try:
      status = current.lstat()
    except OSError as exc:
      raise DistributionError(
          'Unable to inspect runtime path {}: {}'.format(relative_path, exc))
    if _is_link_like(current, status):
      raise DistributionError(
          'Runtime paths must not contain symbolic links: {}'.format(
              relative_path))
    if index + 1 < len(components) and not stat.S_ISDIR(status.st_mode):
      raise DistributionError(
          'Runtime path has a non-directory parent: {}'.format(relative_path))
  return current, status


def _capture_runtime_file_paths(destination, runtime_entries):
  destination = Path(destination)
  try:
    destination_status = destination.lstat()
  except OSError as exc:
    raise DistributionError(
        'Unable to inspect runtime root {}: {}'.format(destination, exc))
  if _is_link_like(
      destination,
      destination_status) or not stat.S_ISDIR(destination_status.st_mode):
    raise DistributionError(
        'Runtime root must be a real directory: {}'.format(destination))

  normalized_entries = _normalize_runtime_entries(runtime_entries)

  captured_paths = set()

  def capture(path, relative_path):
    relative_path = _normalize_runtime_path(relative_path)
    try:
      status = path.lstat()
    except OSError as exc:
      raise DistributionError(
          'Unable to inspect runtime path {}: {}'.format(relative_path, exc))
    if _is_link_like(path, status):
      raise DistributionError(
          'Runtime entries must not contain symbolic links: {}'.format(
              relative_path))
    if stat.S_ISREG(status.st_mode):
      if status.st_size <= 0:
        raise DistributionError(
            'Runtime files must be non-empty: {}'.format(relative_path))
      if relative_path in captured_paths:
        raise DistributionError(
            'Runtime entries overlap at file: {}'.format(relative_path))
      captured_paths.add(relative_path)
      return 1
    if not stat.S_ISDIR(status.st_mode):
      raise DistributionError(
          'Runtime entries must contain only directories and regular files: '
          '{}'.format(relative_path))
    try:
      with os.scandir(str(path)) as iterator:
        children = sorted(iterator, key=lambda child: child.name)
    except OSError as exc:
      raise DistributionError(
          'Unable to scan runtime directory {}: {}'.format(relative_path, exc))
    count = 0
    for child in children:
      child_relative_path = _normalize_runtime_path(
          '{}/{}'.format(relative_path, child.name))
      count += capture(Path(child.path), child_relative_path)
    return count

  for entry in normalized_entries:
    path, _ = _runtime_path(destination, entry)
    if capture(path, entry) == 0:
      raise DistributionError(
          'Runtime entry contains no regular files: {}'.format(entry))
  return tuple(sorted(captured_paths))


def _file_metadata(destination, relative_path, inventory_name, require_nonempty):
  relative_path = _normalize_runtime_path(relative_path)
  path, initial_status = _runtime_path(destination, relative_path)
  if not stat.S_ISREG(initial_status.st_mode):
    raise DistributionError('{} inventory path is not a regular file: {}'.format(inventory_name, relative_path))
  digest = hashlib.sha256()
  byte_count = 0
  try:
    with path.open('rb') as stream:
      opened_status = os.fstat(stream.fileno())
      if not stat.S_ISREG(opened_status.st_mode):
        raise DistributionError('{} inventory path is not a regular file: {}'.format(inventory_name, relative_path))
      if (initial_status.st_dev,
          initial_status.st_ino) != (opened_status.st_dev,
                                     opened_status.st_ino):
        raise DistributionError('{} file changed while opening it: {}'.format(inventory_name, relative_path))
      while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
          break
        byte_count += len(chunk)
        digest.update(chunk)
      final_status = os.fstat(stream.fileno())
  except DistributionError:
    raise
  except OSError as exc:
    raise DistributionError('Unable to hash {} file {}: {}'.format(inventory_name.lower(), relative_path, exc))
  if require_nonempty and byte_count <= 0:
    raise DistributionError('{} files must be non-empty: {}'.format(inventory_name, relative_path))
  stable_fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
  if any(
      getattr(opened_status, field) != getattr(final_status, field)
      for field in stable_fields) or byte_count != final_status.st_size:
    raise DistributionError('{} file changed while hashing it: {}'.format(inventory_name, relative_path))
  return {
      'path': relative_path,
      'sha256': digest.hexdigest(),
      'size': byte_count,
  }


def _runtime_file_metadata(destination, relative_path):
  return _file_metadata(destination, relative_path, 'Runtime', True)


def _build_runtime_file_inventory(destination, runtime_entries, captured_runtime_paths, distribution_files=None):
  captured = []
  seen = set()
  for relative_path in captured_runtime_paths:
    normalized = _normalize_runtime_path(relative_path)
    if normalized in seen:
      raise DistributionError(
          'Duplicate captured runtime path: {}'.format(normalized))
    seen.add(normalized)
    captured.append(normalized)
  captured = tuple(sorted(captured))
  current = _capture_runtime_file_paths(destination, runtime_entries)
  if captured != current:
    missing = sorted(set(captured) - set(current))
    unexpected = sorted(set(current) - set(captured))
    raise DistributionError(
        'Runtime file set changed after staging; missing={}, unexpected={}'.
        format(missing, unexpected))
  if distribution_files is None:
    return [
        _runtime_file_metadata(Path(destination), relative_path)
        for relative_path in current
    ]
  distribution_by_path = {item['path']: item for item in distribution_files}
  runtime_files = []
  for relative_path in current:
    item = distribution_by_path.get(relative_path)
    if item is None or item['size'] <= 0:
      raise DistributionError('Runtime file is absent or empty in the distribution inventory: {}'.format(relative_path))
    runtime_files.append(dict(item))
  return runtime_files


def _capture_distribution_tree_paths(destination):
  destination = Path(destination)
  try:
    root_status = destination.lstat()
  except OSError as exc:
    raise DistributionError('Unable to inspect distribution root {}: {}'.format(destination, exc))
  if _is_link_like(destination, root_status) or not stat.S_ISDIR(root_status.st_mode):
    raise DistributionError('Distribution root must be a real directory: {}'.format(destination))

  directories = []
  files = []
  # Reserve the self-excluded manifest path so a differently-cased source
  # entry cannot collide with the manifest that is written after inventory.
  folded_paths = {MANIFEST_NAME.casefold(): MANIFEST_NAME}

  def capture(path, relative_path):
    relative_path = _normalize_runtime_path(relative_path)
    folded_path = relative_path.casefold()
    if folded_path in folded_paths:
      raise DistributionError('Distribution tree contains a case-colliding path: {} and {}'.format(folded_paths[folded_path], relative_path))
    folded_paths[folded_path] = relative_path
    try:
      status = path.lstat()
    except OSError as exc:
      raise DistributionError('Unable to inspect distribution path {}: {}'.format(relative_path, exc))
    if _is_link_like(path, status):
      raise DistributionError('Distribution tree must not contain symbolic links: {}'.format(relative_path))
    if stat.S_ISREG(status.st_mode):
      files.append(relative_path)
      return
    if not stat.S_ISDIR(status.st_mode):
      raise DistributionError('Distribution tree must contain only directories and regular files: {}'.format(relative_path))
    directories.append(relative_path)
    try:
      with os.scandir(str(path)) as iterator:
        children = sorted(iterator, key=lambda child: child.name)
    except OSError as exc:
      raise DistributionError('Unable to scan distribution directory {}: {}'.format(relative_path, exc))
    for child in children:
      child_relative_path = _normalize_runtime_path('{}/{}'.format(relative_path, child.name))
      capture(Path(child.path), child_relative_path)

  try:
    with os.scandir(str(destination)) as iterator:
      children = sorted(iterator, key=lambda child: child.name)
  except OSError as exc:
    raise DistributionError('Unable to scan distribution root {}: {}'.format(destination, exc))
  for child in children:
    if child.name == MANIFEST_NAME:
      continue
    capture(Path(child.path), child.name)
  return tuple(sorted(directories)), tuple(sorted(files))


def _build_distribution_tree_inventory(destination):
  directories, files = _capture_distribution_tree_paths(destination)
  return list(directories), [
      _file_metadata(Path(destination), relative_path, 'Distribution', False)
      for relative_path in files
  ]


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
    # Schema 2 declares the copied app as the single runtime root. Expanding it
    # recursively is both complete and unambiguous, unlike the validation-only
    # list of selected required leaves returned by mac_runtime_requirements().
    return ('jcef_app.app',)
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
    for license_name in JOGAMP_LICENSE_FILES:
      source = repository_root / 'third_party' / 'jogamp' / license_name
      if not source.is_file():
        raise DistributionError('Required JogAmp license is missing: {}'.format(source))
      shutil.copy2(str(source), str(destination))


def _create_readme(repository_root, destination, target):
  _run([
      sys.executable, repository_root / 'tools' / 'make_readme.py',
      '--output-dir', destination, '--platform', target.name
  ], repository_root)


def _write_distribution_manifest(destination, target, runtime_entries,
                                 captured_runtime_paths, java_cef_commit,
                                 java_jars, selected_jogamp_jars):
  java_cef_commit = _normalize_java_cef_commit(java_cef_commit)
  runtime_entries = _normalize_runtime_entries(runtime_entries)
  distribution_directories, distribution_files = _build_distribution_tree_inventory(destination)
  runtime_files = _build_runtime_file_inventory(destination, runtime_entries, captured_runtime_paths, distribution_files)
  data = {
      'archive_root': target.name,
      'cef_api_version': CEF_API_VERSION,
      'cef_version': CEF_VERSION,
      'distribution_directories': distribution_directories,
      'distribution_files': distribution_files,
      'java_cef_commit': java_cef_commit,
      'java_release': 17,
      'jogl_swing_osr_supported': target.supports_jogl_swing_osr,
      'jogamp_jars': list(selected_jogamp_jars),
      'jcef_jars': list(java_jars),
      'manifest_schema': MANIFEST_SCHEMA,
      'runtime_entries': list(runtime_entries),
      'runtime_files': runtime_files,
      'target': target.name,
  }
  manifest_path = destination / MANIFEST_NAME
  try:
    with manifest_path.open('x', encoding='utf-8', newline='\n') as stream:
      json.dump(data, stream, indent=2, sort_keys=True)
      stream.write('\n')
  except FileExistsError:
    raise DistributionError(
        'Refusing to replace existing distribution manifest: {}'.format(
            manifest_path))


def _validate_archive_source_tree(distribution_path):
  distribution_path = Path(distribution_path)

  def validate(path, relative_path):
    relative_path = _normalize_runtime_path(relative_path)
    try:
      status = path.lstat()
    except OSError as exc:
      raise DistributionError(
          'Unable to inspect archive source {}: {}'.format(relative_path, exc))
    if _is_link_like(path, status):
      raise DistributionError(
          'Archive source must not contain symbolic links: {}'.format(
              relative_path))
    if stat.S_ISREG(status.st_mode):
      return
    if not stat.S_ISDIR(status.st_mode):
      raise DistributionError(
          'Archive source must contain only directories and regular files: '
          '{}'.format(relative_path))
    try:
      with os.scandir(str(path)) as iterator:
        children = sorted(iterator, key=lambda child: child.name)
    except OSError as exc:
      raise DistributionError('Unable to scan archive source directory {}: {}'.
                              format(relative_path, exc))
    for child in children:
      child_relative_path = _normalize_runtime_path(
          '{}/{}'.format(relative_path, child.name))
      validate(Path(child.path), child_relative_path)

  validate(distribution_path, distribution_path.name)


def _archive_filter(member):
  _normalize_runtime_path(member.name)
  if member.issym() or member.islnk():
    raise DistributionError('Archive creation refuses links: {} -> {}'.format(
        member.name, member.linkname))
  if not member.isdir() and not member.isfile():
    raise DistributionError(
        'Archive creation supports only directories and regular files: {}'.
        format(member.name))
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
  _validate_archive_source_tree(distribution_path)
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


def _verify_created_archive(archive_path, target, java_cef_commit):
  try:
    verify_distribution_archive(archive_path, target.name, java_cef_commit)
  except VerificationError as exc:
    raise DistributionError('Generated distribution archive failed schema-2 byte verification: {}'.format(exc))


def create_distribution(repository_root, target):
  validate_host(target)
  java_cef_commit = _resolve_java_cef_commit(repository_root)
  _require_clean_source_checkout(repository_root)
  cef_root = validate_build_configuration(repository_root, target)
  _validate_native_source_commit(repository_root, java_cef_commit)
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
    runtime_entries = _normalize_runtime_entries(
        _copy_runtime(native_output, staging_distribution, cef_root, target))
    java_jars, selected_jogamp_jars = _copy_java_artifacts(
        repository_root, staging_distribution, target)
    # macOS Java refreshes and code signing intentionally mutate the app and
    # may create missing signature or embedded-JAR paths. Capture only after
    # those runtime mutations, then require all later packaging steps to leave
    # the exact runtime path set intact.
    captured_runtime_paths = _capture_runtime_file_paths(
        staging_distribution, runtime_entries)
    _copy_documentation_and_licenses(repository_root, staging_distribution,
                                     cef_root, target)
    _copy_templates(repository_root, staging_distribution, target)
    _create_readme(repository_root, staging_distribution, target)
    _validate_readme_source_commit(staging_distribution / 'README.txt',
                                   java_cef_commit)
    _require_java_cef_commit(repository_root, java_cef_commit)
    _write_distribution_manifest(staging_distribution, target, runtime_entries,
                                 captured_runtime_paths, java_cef_commit,
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
        'CEF-LICENSE.txt', 'CREDITS.html', MANIFEST_NAME,
        'LICENSE.txt', 'README.txt', 'docs', 'jcef.jar', 'jcef-tests.jar',
        'tests',
        JAVA_CHECK_NAMES[target.family]) + LAUNCHER_NAMES[target.family]
    validate_archive(staging_archive, target, required_archive_paths,
                     tuple(required_directory_paths))
    _verify_created_archive(staging_archive, target, java_cef_commit)
    _write_checksum(staging_archive, staging_checksum)
    _require_java_cef_commit(repository_root, java_cef_commit)
    _require_clean_source_checkout(repository_root)

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
