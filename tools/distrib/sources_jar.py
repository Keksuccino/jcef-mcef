#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

import argparse
from io import BytesIO
import os
from pathlib import Path
from pathlib import PurePosixPath
import stat
import sys
import tempfile
import zipfile

ARCHIVE_NAME = 'jcef-mcef-sources.jar'
SOURCE_DIRECTORY = Path('java') / 'org' / 'cef'
ARCHIVE_DIRECTORY = PurePosixPath('org') / 'cef'
FIXED_TIMESTAMP = (2000, 1, 1, 0, 0, 0)
MAX_SOURCE_COUNT = 4096
MAX_SOURCE_SIZE = 4 * 1024 * 1024
MAX_ARCHIVE_SIZE = 72 * 1024 * 1024
MAX_ARCHIVE_PATH_SIZE = 1024
# A stored ZIP uses one 30-byte local header and one 46-byte central-directory
# header per entry, with the UTF-8 path repeated in each, plus a 22-byte end
# record. Reserving the worst case makes every accepted source set writable.
MAX_ARCHIVE_OVERHEAD = MAX_SOURCE_COUNT * (76 + 2 * MAX_ARCHIVE_PATH_SIZE) + 22
MAX_TOTAL_SOURCE_SIZE = MAX_ARCHIVE_SIZE - MAX_ARCHIVE_OVERHEAD
MAX_SOURCE_TREE_DEPTH = 32
MAX_SOURCE_TREE_ENTRY_COUNT = 8192
REGULAR_SOURCE_MODE = stat.S_IFREG | 0o644
DESCRIPTOR_TRAVERSAL_SUPPORTED = hasattr(os, 'O_DIRECTORY') and hasattr(os, 'O_NOFOLLOW') and os.scandir in getattr(os, 'supports_fd', ()) and os.open in getattr(os, 'supports_dir_fd', ()) and os.stat in getattr(os, 'supports_dir_fd', ()) and os.stat in getattr(os, 'supports_follow_symlinks', ())


class SourcesJarError(Exception):
  pass


def _file_identity(metadata):
  return (metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns)


def _file_storage_identity(metadata):
  return (metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_size)


def _file_object_identity(metadata):
  return (metadata.st_dev, metadata.st_ino, metadata.st_mode)


def _regular_file_open_flags():
  flags = os.O_RDONLY
  if hasattr(os, 'O_BINARY'):
    flags |= os.O_BINARY
  if hasattr(os, 'O_CLOEXEC'):
    flags |= os.O_CLOEXEC
  if hasattr(os, 'O_NOFOLLOW'):
    flags |= os.O_NOFOLLOW
  if hasattr(os, 'O_NONBLOCK'):
    flags |= os.O_NONBLOCK
  return flags


def _directory_open_flags():
  flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
  if hasattr(os, 'O_CLOEXEC'):
    flags |= os.O_CLOEXEC
  if hasattr(os, 'O_NONBLOCK'):
    flags |= os.O_NONBLOCK
  return flags


def _read_stable_regular_file(path, maximum_size, description):
  path = Path(path)
  try:
    path_metadata = os.lstat(path)
    if not stat.S_ISREG(path_metadata.st_mode):
      raise SourcesJarError('{} must be a regular file: {}'.format(description, path))
    if path_metadata.st_size > maximum_size:
      raise SourcesJarError('{} exceeds its size limit: {}'.format(description, path))
    file_descriptor = os.open(path, _regular_file_open_flags())
    try:
      with os.fdopen(file_descriptor, 'rb') as stream:
        file_descriptor = -1
        opened_metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(opened_metadata.st_mode) or _file_identity(opened_metadata) != _file_identity(path_metadata):
          raise SourcesJarError('{} changed before it could be read: {}'.format(description, path))
        contents = stream.read(maximum_size + 1)
        final_metadata = os.fstat(stream.fileno())
    finally:
      if file_descriptor >= 0:
        os.close(file_descriptor)
    final_path_metadata = os.lstat(path)
  except SourcesJarError:
    raise
  except OSError as error:
    raise SourcesJarError('Unable to read {} {}: {}'.format(description, path, error)) from error
  if len(contents) != opened_metadata.st_size or len(contents) > maximum_size or _file_identity(final_metadata) != _file_identity(opened_metadata) or _file_identity(final_path_metadata) != _file_identity(opened_metadata):
    raise SourcesJarError('{} changed while it was being read: {}'.format(description, path))
  return contents


def _read_stable_regular_file_at(directory_descriptor, file_name, path_metadata, display_path, maximum_size, description):
  try:
    if not stat.S_ISREG(path_metadata.st_mode):
      raise SourcesJarError('{} must be a regular file: {}'.format(description, display_path))
    if path_metadata.st_size > maximum_size:
      raise SourcesJarError('{} exceeds its size limit: {}'.format(description, display_path))
    file_descriptor = os.open(file_name, _regular_file_open_flags(), dir_fd=directory_descriptor)
    try:
      with os.fdopen(file_descriptor, 'rb') as stream:
        file_descriptor = -1
        opened_metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(opened_metadata.st_mode) or _file_identity(opened_metadata) != _file_identity(path_metadata):
          raise SourcesJarError('{} changed before it could be read: {}'.format(description, display_path))
        contents = stream.read(maximum_size + 1)
        final_metadata = os.fstat(stream.fileno())
    finally:
      if file_descriptor >= 0:
        os.close(file_descriptor)
    final_path_metadata = os.stat(file_name, dir_fd=directory_descriptor, follow_symlinks=False)
  except SourcesJarError:
    raise
  except OSError as error:
    raise SourcesJarError('Unable to read {} {}: {}'.format(description, display_path, error)) from error
  if len(contents) != opened_metadata.st_size or len(contents) > maximum_size or _file_identity(final_metadata) != _file_identity(opened_metadata) or _file_identity(final_path_metadata) != _file_identity(opened_metadata):
    raise SourcesJarError('{} changed while it was being read: {}'.format(description, display_path))
  return contents


def _archive_path(relative_parts, source_path):
  archive_path = ARCHIVE_DIRECTORY.joinpath(*relative_parts).as_posix()
  try:
    encoded_path = archive_path.encode('utf-8')
  except UnicodeEncodeError as error:
    raise SourcesJarError('Java source has a noncanonical archive path: {}'.format(source_path)) from error
  if '\\' in archive_path or len(encoded_path) > MAX_ARCHIVE_PATH_SIZE:
    raise SourcesJarError('Java source has a noncanonical archive path: {}'.format(source_path))
  return archive_path


def _append_source(sources, total_size, relative_parts, contents, source_path):
  if len(sources) >= MAX_SOURCE_COUNT:
    raise SourcesJarError('Java sources exceed the file-count limit')
  total_size += len(contents)
  if total_size > MAX_TOTAL_SOURCE_SIZE:
    raise SourcesJarError('Java sources exceed the total size limit')
  sources.append((_archive_path(relative_parts, source_path), contents))
  return total_size


def _open_root_directory(path, description):
  directory_descriptor = -1
  try:
    path_metadata = os.lstat(path)
    if not stat.S_ISDIR(path_metadata.st_mode):
      raise SourcesJarError('{} must be a regular directory: {}'.format(description, path))
    directory_descriptor = os.open(path, _directory_open_flags())
    opened_metadata = os.fstat(directory_descriptor)
    final_path_metadata = os.lstat(path)
    if not stat.S_ISDIR(opened_metadata.st_mode) or _file_object_identity(path_metadata) != _file_object_identity(opened_metadata) or _file_object_identity(final_path_metadata) != _file_object_identity(opened_metadata):
      raise SourcesJarError('{} changed before it could be traversed: {}'.format(description, path))
    return directory_descriptor, opened_metadata
  except SourcesJarError:
    if directory_descriptor >= 0:
      os.close(directory_descriptor)
    raise
  except OSError as error:
    if directory_descriptor >= 0:
      os.close(directory_descriptor)
    raise SourcesJarError('Unable to open {} {}: {}'.format(description, path, error)) from error


def _open_child_directory(parent_descriptor, directory_name, display_path, description):
  directory_descriptor = -1
  try:
    path_metadata = os.stat(directory_name, dir_fd=parent_descriptor, follow_symlinks=False)
    if not stat.S_ISDIR(path_metadata.st_mode):
      raise SourcesJarError('{} must be a regular directory: {}'.format(description, display_path))
    directory_descriptor = os.open(directory_name, _directory_open_flags(), dir_fd=parent_descriptor)
    opened_metadata = os.fstat(directory_descriptor)
    final_path_metadata = os.stat(directory_name, dir_fd=parent_descriptor, follow_symlinks=False)
    if not stat.S_ISDIR(opened_metadata.st_mode) or _file_object_identity(path_metadata) != _file_object_identity(opened_metadata) or _file_object_identity(final_path_metadata) != _file_object_identity(opened_metadata):
      raise SourcesJarError('{} changed before it could be traversed: {}'.format(description, display_path))
    return directory_descriptor, opened_metadata
  except SourcesJarError:
    if directory_descriptor >= 0:
      os.close(directory_descriptor)
    raise
  except OSError as error:
    if directory_descriptor >= 0:
      os.close(directory_descriptor)
    raise SourcesJarError('Unable to open {} {}: {}'.format(description, display_path, error)) from error


def _collect_descriptor_sources(directory_descriptor, directory_metadata, display_path, relative_parts, sources, total_size, traversal_state):
  try:
    with os.scandir(directory_descriptor) as entries:
      entry_names = []
      for entry in entries:
        traversal_state[0] += 1
        if traversal_state[0] > MAX_SOURCE_TREE_ENTRY_COUNT:
          raise SourcesJarError('JCEF production source tree exceeds the entry-count limit')
        entry_names.append(entry.name)
      entry_names.sort()
  except OSError as error:
    raise SourcesJarError('Unable to traverse the JCEF production source tree: {}'.format(error)) from error

  for entry_name in entry_names:
    entry_path = display_path / entry_name
    try:
      entry_metadata = os.stat(entry_name, dir_fd=directory_descriptor, follow_symlinks=False)
    except OSError as error:
      raise SourcesJarError('Unable to inspect source tree entry {}: {}'.format(entry_path, error)) from error
    if stat.S_ISLNK(entry_metadata.st_mode):
      raise SourcesJarError('Source tree entries must not be symbolic links: {}'.format(entry_path))
    if stat.S_ISDIR(entry_metadata.st_mode):
      if len(relative_parts) >= MAX_SOURCE_TREE_DEPTH:
        raise SourcesJarError('JCEF production source tree exceeds the directory-depth limit')
      child_descriptor, child_metadata = _open_child_directory(directory_descriptor, entry_name, entry_path, 'Source directory')
      try:
        total_size = _collect_descriptor_sources(child_descriptor, child_metadata, entry_path, relative_parts + (entry_name,), sources, total_size, traversal_state)
        final_entry_metadata = os.stat(entry_name, dir_fd=directory_descriptor, follow_symlinks=False)
        if _file_object_identity(final_entry_metadata) != _file_object_identity(child_metadata):
          raise SourcesJarError('Source directory changed while it was being traversed: {}'.format(entry_path))
      except OSError as error:
        raise SourcesJarError('Unable to verify source directory {}: {}'.format(entry_path, error)) from error
      finally:
        os.close(child_descriptor)
    elif entry_name.endswith('.java'):
      contents = _read_stable_regular_file_at(directory_descriptor, entry_name, entry_metadata, entry_path, MAX_SOURCE_SIZE, 'Java source')
      total_size = _append_source(sources, total_size, relative_parts + (entry_name,), contents, entry_path)

  try:
    final_directory_metadata = os.fstat(directory_descriptor)
  except OSError as error:
    raise SourcesJarError('Unable to verify source directory {}: {}'.format(display_path, error)) from error
  if _file_identity(final_directory_metadata) != _file_identity(directory_metadata):
    raise SourcesJarError('Source directory changed while it was being traversed: {}'.format(display_path))
  return total_size


def _load_sources_with_descriptors(repository_root):
  descriptors = []
  links = []
  repository_descriptor, repository_metadata = _open_root_directory(repository_root, 'Repository root')
  descriptors.append((repository_descriptor, repository_metadata, repository_root))
  try:
    parent_descriptor = repository_descriptor
    display_path = repository_root
    descriptions = ('Java source root', 'Java package root', 'JCEF production source directory')
    for component, description in zip(SOURCE_DIRECTORY.parts, descriptions):
      display_path = display_path / component
      directory_descriptor, directory_metadata = _open_child_directory(parent_descriptor, component, display_path, description)
      descriptors.append((directory_descriptor, directory_metadata, display_path))
      links.append((parent_descriptor, component, directory_metadata, display_path))
      parent_descriptor = directory_descriptor

    sources = []
    _collect_descriptor_sources(parent_descriptor, descriptors[-1][1], display_path, (), sources, 0, [0])

    # Every directory remains open until all links are rechecked. This prevents a
    # renamed or symlink-substituted source root from silently changing the tree
    # represented by an otherwise valid set of file descriptors.
    for descriptor, metadata, path in descriptors[:-1]:
      if _file_object_identity(os.fstat(descriptor)) != _file_object_identity(metadata):
        raise SourcesJarError('Source path component changed while sources were loaded: {}'.format(path))
    for parent_descriptor, component, metadata, path in links:
      final_metadata = os.stat(component, dir_fd=parent_descriptor, follow_symlinks=False)
      if _file_object_identity(final_metadata) != _file_object_identity(metadata):
        raise SourcesJarError('Source path component changed while sources were loaded: {}'.format(path))
    if _file_object_identity(os.lstat(repository_root)) != _file_object_identity(repository_metadata):
      raise SourcesJarError('Repository root changed while sources were loaded: {}'.format(repository_root))
    return sources
  except SourcesJarError:
    raise
  except RecursionError as error:
    raise SourcesJarError('JCEF production source tree exceeds the safe traversal depth') from error
  except OSError as error:
    raise SourcesJarError('Unable to traverse the JCEF production source tree: {}'.format(error)) from error
  finally:
    for descriptor, _, _ in reversed(descriptors):
      os.close(descriptor)


def _load_sources(repository_root):
  repository_root = Path(repository_root)
  # Path-only traversal cannot bind ancestor directories against symlink or
  # Windows junction replacement. Release tooling therefore fails closed when
  # Python cannot provide descriptor-relative, no-follow directory traversal.
  if not DESCRIPTOR_TRAVERSAL_SUPPORTED:
    raise SourcesJarError('Secure descriptor-relative source traversal is unavailable on this platform')
  sources = _load_sources_with_descriptors(repository_root)

  if not sources:
    raise SourcesJarError('JCEF production source directory does not contain Java sources')
  sources.sort(key=lambda source: source[0])
  names = [source[0] for source in sources]
  if len(names) != len(set(names)):
    raise SourcesJarError('Java sources produce duplicate archive paths')
  casefolded_names = [name.casefold() for name in names]
  if len(casefolded_names) != len(set(casefolded_names)):
    raise SourcesJarError('Java sources produce archive paths that collide by case')
  return sources


def _write_archive(destination, sources):
  with zipfile.ZipFile(destination, mode='w', compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
    for archive_path, contents in sources:
      info = zipfile.ZipInfo(archive_path, date_time=FIXED_TIMESTAMP)
      info.compress_type = zipfile.ZIP_STORED
      info.create_system = 3
      info.external_attr = REGULAR_SOURCE_MODE << 16
      archive.writestr(info, contents, compress_type=zipfile.ZIP_STORED)


def _canonical_archive_bytes(sources):
  canonical_archive = BytesIO()
  _write_archive(canonical_archive, sources)
  archive_bytes = canonical_archive.getvalue()
  if not archive_bytes or len(archive_bytes) > MAX_ARCHIVE_SIZE:
    raise SourcesJarError('Canonical sources JAR size is outside the accepted limits')
  return archive_bytes


def _validate_entry_path(name):
  path = PurePosixPath(name)
  if not name or name.startswith('/') or '\\' in name or path.as_posix() != name:
    raise SourcesJarError('Sources JAR contains a noncanonical path: {}'.format(name))
  if any(part in ('', '.', '..') for part in path.parts):
    raise SourcesJarError('Sources JAR contains an unsafe path: {}'.format(name))


def _verify_archive_bytes(sources, archive_bytes):
  # Compare bounded bytes before invoking zipfile. Otherwise an attacker could
  # force eager parsing of a huge central directory before entry limits apply.
  if archive_bytes != _canonical_archive_bytes(sources):
    raise SourcesJarError('Sources JAR bytes do not match the deterministic archive format')
  expected = dict(sources)
  try:
    with zipfile.ZipFile(BytesIO(archive_bytes), mode='r', allowZip64=False) as archive:
      if archive.comment:
        raise SourcesJarError('Sources JAR must not contain an archive comment')
      entries = archive.infolist()
      if len(entries) > MAX_SOURCE_COUNT:
        raise SourcesJarError('Sources JAR exceeds the entry-count limit')
      names = [entry.filename for entry in entries]
      if len(names) != len(set(names)):
        raise SourcesJarError('Sources JAR contains duplicate entries')
      if names != sorted(expected):
        missing = sorted(set(expected) - set(names))
        extra = sorted(set(names) - set(expected))
        raise SourcesJarError('Sources JAR membership or ordering is invalid; missing={}, extra={}'.format(missing, extra))
      total_size = 0
      for entry in entries:
        _validate_entry_path(entry.filename)
        if entry.is_dir() or not entry.filename.endswith('.java'):
          raise SourcesJarError('Sources JAR entries must be Java source files: {}'.format(entry.filename))
        if entry.compress_type != zipfile.ZIP_STORED:
          raise SourcesJarError('Sources JAR entries must use deterministic stored encoding')
        if entry.flag_bits & ~0x800:
          raise SourcesJarError('Sources JAR entry uses unsupported ZIP flags: {}'.format(entry.filename))
        if entry.date_time != FIXED_TIMESTAMP:
          raise SourcesJarError('Sources JAR entry timestamp is not deterministic: {}'.format(entry.filename))
        if entry.create_system != 3 or entry.external_attr >> 16 != REGULAR_SOURCE_MODE:
          raise SourcesJarError('Sources JAR entry permissions are not canonical: {}'.format(entry.filename))
        if entry.extra or entry.comment:
          raise SourcesJarError('Sources JAR entry contains unsupported ZIP metadata: {}'.format(entry.filename))
        expected_contents = expected[entry.filename]
        if entry.file_size != len(expected_contents) or entry.compress_size != len(expected_contents) or entry.file_size > MAX_SOURCE_SIZE:
          raise SourcesJarError('Sources JAR entry size is invalid: {}'.format(entry.filename))
        contents = archive.read(entry)
        if contents != expected_contents:
          raise SourcesJarError('Sources JAR entry does not match the repository source: {}'.format(entry.filename))
        total_size += len(contents)
      if total_size > MAX_TOTAL_SOURCE_SIZE:
        raise SourcesJarError('Sources JAR expands beyond the total size limit')
  except (OSError, RuntimeError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
    raise SourcesJarError('Unable to read sources JAR: {}'.format(error)) from error
  return len(sources)


def verify_sources_jar(repository_root, archive_path):
  sources = _load_sources(repository_root)
  archive_bytes = _read_stable_regular_file(archive_path, MAX_ARCHIVE_SIZE, 'Sources JAR')
  if not archive_bytes:
    raise SourcesJarError('Sources JAR must not be empty')
  return _verify_archive_bytes(sources, archive_bytes)


def build_sources_jar(repository_root, output_path):
  output_path = Path(output_path)
  if output_path.name != ARCHIVE_NAME:
    raise SourcesJarError('Sources JAR output must be named {}'.format(ARCHIVE_NAME))
  sources = _load_sources(repository_root)
  archive_bytes = _canonical_archive_bytes(sources)
  verification_sources = _load_sources(repository_root)
  _verify_archive_bytes(verification_sources, archive_bytes)
  output_path.parent.mkdir(parents=True, exist_ok=True)
  file_descriptor, temporary_name = tempfile.mkstemp(prefix='.{}.'.format(ARCHIVE_NAME), suffix='.tmp', dir=str(output_path.parent))
  temporary_path = Path(temporary_name)
  try:
    with os.fdopen(file_descriptor, 'wb') as stream:
      file_descriptor = -1
      stream.write(archive_bytes)
      stream.flush()
      fchmod = getattr(os, 'fchmod', None)
      if fchmod is not None:
        fchmod(stream.fileno(), 0o644)
      os.fsync(stream.fileno())
      temporary_metadata = os.fstat(stream.fileno())
    if _file_identity(os.lstat(temporary_path)) != _file_identity(temporary_metadata):
      raise SourcesJarError('Temporary sources JAR changed before publication')
    os.replace(temporary_path, output_path)
    if _file_storage_identity(os.lstat(output_path)) != _file_storage_identity(temporary_metadata):
      raise SourcesJarError('Published sources JAR does not match the atomic temporary file')
  finally:
    if file_descriptor >= 0:
      os.close(file_descriptor)
    try:
      os.lstat(temporary_path)
    except FileNotFoundError:
      pass
    else:
      temporary_path.unlink()
  return len(verification_sources)


def _parse_arguments(arguments):
  parser = argparse.ArgumentParser(description='Build or verify the deterministic JCEF IDE sources JAR.')
  subparsers = parser.add_subparsers(dest='command', required=True)
  build_parser = subparsers.add_parser('build')
  build_parser.add_argument('--repository-root', required=True)
  build_parser.add_argument('--output', required=True)
  verify_parser = subparsers.add_parser('verify')
  verify_parser.add_argument('--repository-root', required=True)
  verify_parser.add_argument('--archive', required=True)
  return parser.parse_args(arguments)


def main(arguments=None):
  options = _parse_arguments(arguments)
  try:
    if options.command == 'build':
      source_count = build_sources_jar(options.repository_root, options.output)
      print('Built {} with {} Java sources'.format(options.output, source_count))
    else:
      archive_path = Path(options.archive)
      if archive_path.name != ARCHIVE_NAME:
        raise SourcesJarError('Sources JAR must be named {}'.format(ARCHIVE_NAME))
      source_count = verify_sources_jar(options.repository_root, archive_path)
      print('Verified {} with {} Java sources'.format(options.archive, source_count))
  except (OSError, SourcesJarError) as error:
    print('ERROR: {}'.format(error), file=sys.stderr)
    return 1
  return 0


if __name__ == '__main__':
  raise SystemExit(main())
