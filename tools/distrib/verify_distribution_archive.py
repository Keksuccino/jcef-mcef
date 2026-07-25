#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.
"""Verify one schema-2 JCEF distribution archive without extracting it."""

from __future__ import absolute_import
from __future__ import print_function

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tarfile
import unicodedata

CEF_VERSION = '151.2.3+g89cd581+chromium-151.0.7922.34'
CEF_API_VERSION = '15100'
MANIFEST_NAME = 'DISTRIBUTION-MANIFEST.json'
MANIFEST_SCHEMA = 2
CANONICAL_TAR_MTIME = 946684800
MAX_ARCHIVE_BYTES = 750 * 1024 * 1024
MAX_EXTRACTED_BYTES = 2_000 * 1024 * 1024
MAX_TAR_BYTES = MAX_EXTRACTED_BYTES + 512 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 100_000
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_TAR_CONTROL_BYTES = 1024 * 1024
MAX_TOTAL_TAR_CONTROL_BYTES = 32 * 1024 * 1024
MAX_TAR_CONTROL_MEMBERS = MAX_ARCHIVE_MEMBERS
MAX_CONSECUTIVE_TAR_CONTROL_MEMBERS = 8
MAX_TAR_ZERO_BLOCKS = 1_024
MAX_RUNTIME_FILES = 50_000
MAX_RUNTIME_ENTRIES = 1_024
MAX_DISTRIBUTION_FILES = MAX_ARCHIVE_MEMBERS
MAX_DISTRIBUTION_DIRECTORIES = MAX_ARCHIVE_MEMBERS
MAX_PATH_BYTES = 4_096
MAX_PATH_DEPTH = 64
MAX_TOTAL_PATH_BYTES = 16 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024

_COMMIT_PATTERN = re.compile(r'^[0-9a-f]{40}$')
_DIGEST_PATTERN = re.compile(r'^[0-9a-f]{64}$')
_MANIFEST_KEYS = frozenset(('archive_root', 'cef_api_version', 'cef_version', 'distribution_directories', 'distribution_files', 'java_cef_commit', 'java_release', 'jogl_swing_osr_supported', 'jogamp_jars', 'jcef_jars', 'manifest_schema', 'runtime_entries', 'runtime_files', 'target'))

_COMMON_RUNTIME_ENTRIES = ('chrome_100_percent.pak', 'chrome_200_percent.pak',
                           'icudtl.dat', 'locales', 'resources.pak',
                           'v8_context_snapshot.bin',)
_LINUX_RUNTIME_ENTRIES = _COMMON_RUNTIME_ENTRIES + (
    'chrome-sandbox', 'jcef_helper', 'libEGL.so', 'libGLESv2.so', 'libcef.so',
    'libjcef.so', 'libvk_swiftshader.so', 'libvulkan.so.1',
    'vk_swiftshader_icd.json',)
_WINDOWS_RUNTIME_ENTRIES = _COMMON_RUNTIME_ENTRIES + (
    'chrome_elf.dll', 'd3dcompiler_47.dll', 'jcef.dll', 'jcef_helper.exe',
    'libEGL.dll', 'libGLESv2.dll', 'libcef.dll', 'vk_swiftshader.dll',
    'vk_swiftshader_icd.json', 'vulkan-1.dll',)
TARGET_RUNTIME_ENTRIES = {
    'linux_amd64':
        tuple(sorted(_LINUX_RUNTIME_ENTRIES)),
    'linux_arm64':
        tuple(sorted(_LINUX_RUNTIME_ENTRIES)),
    'macos_amd64': ('jcef_app.app',),
    'macos_arm64': ('jcef_app.app',),
    'windows_amd64':
        tuple(sorted(_WINDOWS_RUNTIME_ENTRIES + ('dxcompiler.dll', 'dxil.dll'))),
    'windows_arm64':
        tuple(sorted(_WINDOWS_RUNTIME_ENTRIES)),
}
OPTIONAL_RUNTIME_ENTRIES = {
    'linux_amd64': ('libminigbm.so',),
    'linux_arm64': ('libminigbm.so',),
    'macos_amd64': (),
    'macos_arm64': (),
    'windows_amd64': (),
    'windows_arm64': (),
}

TARGET_JOGAMP_JARS = {
    'linux_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                    'gluegen-rt-natives-linux-amd64.jar',
                    'jogl-all-natives-linux-amd64.jar'),
    'linux_arm64': ('gluegen-rt.jar', 'jogl-all.jar',
                    'gluegen-rt-natives-linux-aarch64.jar',
                    'jogl-all-natives-linux-aarch64.jar'),
    'macos_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                    'gluegen-rt-natives-macosx-universal.jar',
                    'jogl-all-natives-macosx-universal.jar'),
    'macos_arm64': ('gluegen-rt.jar', 'jogl-all.jar',
                    'gluegen-rt-natives-macosx-universal.jar',
                    'jogl-all-natives-macosx-universal.jar'),
    'windows_amd64': ('gluegen-rt.jar', 'jogl-all.jar',
                      'gluegen-rt-natives-windows-amd64.jar',
                      'jogl-all-natives-windows-amd64.jar'),
    'windows_arm64': (),
}

JOGAMP_LICENSE_FILES = ('gluegen.LICENSE.txt', 'jogl.LICENSE.txt')
TARGET_RUNTIME_DIRECTORY_ENTRIES = {
    'linux_amd64': ('locales',),
    'linux_arm64': ('locales',),
    'macos_amd64': ('jcef_app.app',),
    'macos_arm64': ('jcef_app.app',),
    'windows_amd64': ('locales',),
    'windows_arm64': ('locales',),
}
TARGET_JAVA_CHECK = {
    'linux_amd64': 'java17_check.sh',
    'linux_arm64': 'java17_check.sh',
    'macos_amd64': 'java17_check.sh',
    'macos_arm64': 'java17_check.sh',
    'windows_amd64': 'java17_check.bat',
    'windows_arm64': 'java17_check.bat',
}
TARGET_LAUNCHERS = {
    'linux_amd64': ('compile.sh', 'run.sh'),
    'linux_arm64': ('compile.sh', 'run.sh'),
    'macos_amd64': ('compile.sh',),
    'macos_arm64': ('compile.sh',),
    'windows_amd64': ('compile.bat', 'run.bat'),
    'windows_arm64': ('compile.bat', 'run.bat'),
}


def _target_top_level_contract(target):
  directories = set(TARGET_RUNTIME_DIRECTORY_ENTRIES[target])
  directories.update(('docs', 'tests'))
  files = set(TARGET_RUNTIME_ENTRIES[target]) - directories
  files.update(('CEF-LICENSE.txt', 'CREDITS.html', MANIFEST_NAME, 'LICENSE.txt', 'README.txt', 'jcef.jar', 'jcef-tests.jar', TARGET_JAVA_CHECK[target]))
  files.update(TARGET_JOGAMP_JARS[target])
  files.update(TARGET_LAUNCHERS[target])
  if TARGET_JOGAMP_JARS[target]:
    files.update(JOGAMP_LICENSE_FILES)
  return tuple(sorted(files)), tuple(sorted(directories))


TARGET_TOP_LEVEL_FILES = {}
TARGET_TOP_LEVEL_DIRECTORIES = {}
for _target_name in TARGET_RUNTIME_ENTRIES:
  TARGET_TOP_LEVEL_FILES[_target_name], TARGET_TOP_LEVEL_DIRECTORIES[_target_name] = _target_top_level_contract(_target_name)


def _mac_required_runtime_files(target, architecture):
  app = 'jcef_app.app/Contents'
  frameworks = app + '/Frameworks'
  framework = frameworks + '/Chromium Embedded Framework.framework'
  libraries = framework + '/Libraries'
  resources = framework + '/Resources'
  required = [
      app + '/Info.plist',
      app + '/Java/libjcef.dylib',
      app + '/Java/jcef-tests.jar',
      app + '/Java/jcef.jar',
      app + '/MacOS/JavaAppLauncher',
      app + '/_CodeSignature/CodeResources',
      framework + '/Chromium Embedded Framework',
      framework + '/_CodeSignature/CodeResources',
      libraries + '/libEGL.dylib',
      libraries + '/libGLESv2.dylib',
      libraries + '/libvk_swiftshader.dylib',
      libraries + '/vk_swiftshader_icd.json',
      resources + '/Info.plist',
      resources + '/chrome_100_percent.pak',
      resources + '/chrome_200_percent.pak',
      resources + '/en.lproj/locale.pak',
      resources + '/icudtl.dat',
      resources + '/resources.pak',
      resources + '/v8_context_snapshot.{}.bin'.format(architecture),
  ]
  for suffix in ('', ' (Alerts)', ' (GPU)', ' (Plugin)', ' (Renderer)'):
    helper = 'jcef Helper{}'.format(suffix)
    helper_contents = '{}/{}.app/Contents'.format(frameworks, helper)
    required.extend((helper_contents + '/Info.plist', helper_contents + '/MacOS/' + helper, helper_contents + '/_CodeSignature/CodeResources'))
  required.extend((app + '/Java/' + jar_name for jar_name in TARGET_JOGAMP_JARS[target]))
  return tuple(sorted(required))


TARGET_REQUIRED_RUNTIME_FILES = {
    'linux_amd64': ('locales/en-US.pak',),
    'linux_arm64': ('locales/en-US.pak',),
    'macos_amd64': _mac_required_runtime_files('macos_amd64', 'x86_64'),
    'macos_arm64': _mac_required_runtime_files('macos_arm64', 'arm64'),
    'windows_amd64': ('locales/en-US.pak',),
    'windows_arm64': ('locales/en-US.pak',),
}


class VerificationError(RuntimeError):
  """Raised when a distribution archive violates the publication contract."""


class VerificationLimits(object):
  """Resource limits used while parsing an untrusted distribution archive."""

  def __init__(self, max_archive_bytes=MAX_ARCHIVE_BYTES, max_extracted_bytes=MAX_EXTRACTED_BYTES, max_tar_bytes=MAX_TAR_BYTES, max_members=MAX_ARCHIVE_MEMBERS, max_manifest_bytes=MAX_MANIFEST_BYTES, max_tar_control_bytes=MAX_TAR_CONTROL_BYTES, max_total_tar_control_bytes=MAX_TOTAL_TAR_CONTROL_BYTES, max_tar_control_members=MAX_TAR_CONTROL_MEMBERS, max_consecutive_tar_control_members=MAX_CONSECUTIVE_TAR_CONTROL_MEMBERS, max_tar_zero_blocks=MAX_TAR_ZERO_BLOCKS, max_runtime_files=MAX_RUNTIME_FILES, max_runtime_entries=MAX_RUNTIME_ENTRIES, max_distribution_files=MAX_DISTRIBUTION_FILES, max_distribution_directories=MAX_DISTRIBUTION_DIRECTORIES, max_path_bytes=MAX_PATH_BYTES, max_path_depth=MAX_PATH_DEPTH, max_total_path_bytes=MAX_TOTAL_PATH_BYTES):
    self.max_archive_bytes = max_archive_bytes
    self.max_extracted_bytes = max_extracted_bytes
    self.max_tar_bytes = max_tar_bytes
    self.max_members = max_members
    self.max_manifest_bytes = max_manifest_bytes
    self.max_tar_control_bytes = max_tar_control_bytes
    self.max_total_tar_control_bytes = max_total_tar_control_bytes
    self.max_tar_control_members = max_tar_control_members
    self.max_consecutive_tar_control_members = max_consecutive_tar_control_members
    self.max_tar_zero_blocks = max_tar_zero_blocks
    self.max_runtime_files = max_runtime_files
    self.max_runtime_entries = max_runtime_entries
    self.max_distribution_files = max_distribution_files
    self.max_distribution_directories = max_distribution_directories
    self.max_path_bytes = max_path_bytes
    self.max_path_depth = max_path_depth
    self.max_total_path_bytes = max_total_path_bytes


class ArchiveRecord(object):
  """Immutable metadata calculated from one streamed archive member."""

  def __init__(self, kind, size, digest=None):
    self.kind = kind
    self.size = size
    self.digest = digest


class _DecompressedLimitReader(object):
  """Cap every decoded tar byte, including headers, padding and metadata."""

  def __init__(self, stream, limit):
    self._stream = stream
    self._limit = limit
    self.byte_count = 0

  def read(self, size=-1):
    if size is None or size < 0:
      raise VerificationError('Unbounded reads from the decoded tar stream are not allowed')
    remaining = self._limit - self.byte_count
    # Read one byte beyond the limit when possible. Returning a short block to
    # tarfile could otherwise turn a resource-limit violation into an ambiguous
    # truncated-archive error.
    requested = min(size, remaining + 1)
    chunk = self._stream.read(requested)
    self.byte_count += len(chunk)
    if self.byte_count > self._limit:
      raise VerificationError('Decoded tar stream exceeds limit {}'.format(self._limit))
    return chunk


def _bounded_tar_info(limits):
  """Create a per-archive TarInfo class that bounds hidden control records."""
  control_types = frozenset((tarfile.GNUTYPE_LONGNAME, tarfile.GNUTYPE_LONGLINK, tarfile.SOLARIS_XHDTYPE, tarfile.XGLTYPE, tarfile.XHDTYPE))

  class BoundedTarInfo(tarfile.TarInfo):

    def _reject_sparse(self):
      raise VerificationError('Archive contains sparse tar metadata')

    def _proc_sparse(self, archive):
      self._reject_sparse()

    def _proc_gnusparse_00(self, next_member, headers):
      self._reject_sparse()

    def _proc_gnusparse_01(self, next_member, headers):
      self._reject_sparse()

    def _proc_gnusparse_10(self, next_member, headers, archive):
      self._reject_sparse()

    @classmethod
    def _read_bounded_header(cls, archive, dircheck):
      buffer = archive.fileobj.read(tarfile.BLOCKSIZE)
      if buffer == b'\0' * tarfile.BLOCKSIZE:
        block_offset = archive.fileobj.tell() - tarfile.BLOCKSIZE
        if getattr(archive, '_verification_first_zero_offset', None) is None:
          archive._verification_first_zero_offset = block_offset
        zero_count = getattr(archive, '_verification_zero_count', 0) + 1
        archive._verification_zero_count = zero_count
        if zero_count > limits.max_tar_zero_blocks:
          raise VerificationError('Archive exceeds the tar zero-block limit')
      elif buffer and getattr(archive, '_verification_first_zero_offset', None) is not None:
        raise VerificationError('Archive contains data after its first tar zero block')
      if hasattr(cls, '_frombuf'):
        member = cls._frombuf(buffer, archive.encoding, archive.errors, dircheck=dircheck)
      else:
        member = cls.frombuf(buffer, archive.encoding, archive.errors)
      member.offset = archive.fileobj.tell() - tarfile.BLOCKSIZE
      header_count = getattr(archive, '_verification_header_count', 0) + 1
      archive._verification_header_count = header_count
      if header_count > limits.max_members + limits.max_tar_control_members:
        raise VerificationError('Archive exceeds the physical tar-header limit')
      if member.type == tarfile.GNUTYPE_SPARSE:
        raise VerificationError('Archive contains a GNU sparse control member')
      if member.type not in control_types:
        return member._proc_member(archive)
      control_count = getattr(archive, '_verification_control_count', 0) + 1
      archive._verification_control_count = control_count
      if control_count > limits.max_tar_control_members:
        raise VerificationError('Archive exceeds the hidden tar-control-member limit')
      if member.size < 0 or member.size > limits.max_tar_control_bytes:
        raise VerificationError('Tar control payload size {} exceeds limit {}'.format(member.size, limits.max_tar_control_bytes))
      total_control_bytes = getattr(archive, '_verification_control_bytes', 0) + member.size
      archive._verification_control_bytes = total_control_bytes
      if total_control_bytes > limits.max_total_tar_control_bytes:
        raise VerificationError('Archive tar control payloads exceed aggregate limit {}'.format(limits.max_total_tar_control_bytes))
      # Global PAX values persist on TarFile and are copied into every later
      # TarInfo. Accepting them would make retained parser metadata grow
      # quadratically even though visible paths and payload bytes are bounded.
      if member.type == tarfile.XGLTYPE:
        raise VerificationError('Archive contains unsupported global PAX metadata')
      if member.type != tarfile.XHDTYPE:
        raise VerificationError('Archive contains a non-canonical tar control member')
      control_depth = getattr(archive, '_verification_control_depth', 0) + 1
      archive._verification_control_depth = control_depth
      if control_depth > limits.max_consecutive_tar_control_members:
        raise VerificationError('Archive exceeds the consecutive tar-control-member limit')
      try:
        return member._proc_member(archive)
      finally:
        archive._verification_control_depth -= 1

    @classmethod
    def fromtarfile(cls, archive):
      return cls._read_bounded_header(archive, True)

    @classmethod
    def _fromtarfile(cls, archive, *, dircheck=True):
      return cls._read_bounded_header(archive, dircheck)

  return BoundedTarInfo


def _is_link_like(path, status):
  if stat.S_ISLNK(status.st_mode):
    return True
  reparse_point = getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)
  if reparse_point and getattr(status, 'st_file_attributes', 0) & reparse_point:
    return True
  is_junction = getattr(path, 'is_junction', None)
  return is_junction is not None and is_junction()


def _validate_relative_path(value, description, limits):
  if not isinstance(value, str) or not value:
    raise VerificationError('{} must be a non-empty string'.format(description))
  try:
    encoded_value = value.encode('utf-8')
  except UnicodeError:
    raise VerificationError('{} is not valid Unicode text: {!r}'.format(description, value))
  if unicodedata.normalize('NFC', value) != value:
    raise VerificationError('{} is not Unicode-normalized: {!r}'.format(description, value))
  if '\\' in value or ':' in value or any((ord(character) < 32 or ord(character) == 127 for character in value)):
    raise VerificationError('{} contains an unsafe character: {!r}'.format(description, value))
  components = value.split('/')
  if any(component in ('', '.', '..') for component in components):
    raise VerificationError('{} is not a normalized relative path: {!r}'.format(description, value))
  if len(components) > limits.max_path_depth:
    raise VerificationError('{} exceeds the path-depth limit: {!r}'.format(description, value))
  if len(encoded_value) > limits.max_path_bytes:
    raise VerificationError('{} exceeds the path-length limit: {!r}'.format(description, value))
  return value


def _validate_archive_member_path(value, target, limits):
  value = _validate_relative_path(value, 'Archive member path', limits)
  components = value.split('/')
  if components[0] != target:
    raise VerificationError('Archive member is outside the canonical {} root: {}'.format(target, value))
  return value


def _reject_json_constant(value):
  raise VerificationError('Manifest contains unsupported JSON value: {}'.format(value))


def _parse_json_integer(value):
  if len(value) > 20:
    raise VerificationError('Manifest integer is too large')
  return int(value)


def _unique_json_object(pairs):
  result = {}
  for key, value in pairs:
    if key in result:
      raise VerificationError('Manifest contains duplicate object key: {}'.format(key))
    result[key] = value
  return result


def _parse_manifest(contents):
  try:
    text = contents.decode('utf-8')
  except UnicodeError as exc:
    raise VerificationError('Manifest is not valid UTF-8: {}'.format(exc))
  try:
    return json.loads(text, object_pairs_hook=_unique_json_object, parse_int=_parse_json_integer, parse_float=_reject_json_constant, parse_constant=_reject_json_constant)
  except VerificationError:
    raise
  except (ValueError, RecursionError) as exc:
    raise VerificationError('Manifest is not valid duplicate-safe JSON: {}'.format(exc))


def _validate_archive_file(archive_path, target, limits):
  try:
    status = archive_path.lstat()
  except OSError as exc:
    raise VerificationError('Archive is missing at {}: {}'.format(archive_path, exc))
  if _is_link_like(archive_path, status) or not stat.S_ISREG(status.st_mode):
    raise VerificationError('Archive must be a regular non-link file: {}'.format(archive_path))
  if status.st_size <= 0:
    raise VerificationError('Archive is empty: {}'.format(archive_path))
  if status.st_size > limits.max_archive_bytes:
    raise VerificationError('Archive compressed size {} exceeds limit {}'.format(status.st_size, limits.max_archive_bytes))
  if archive_path.name != '{}.tar.gz'.format(target):
    raise VerificationError('Archive filename must be {}.tar.gz'.format(target))
  return status


def _is_sparse_member(member):
  if member.type == tarfile.GNUTYPE_SPARSE or getattr(member, 'sparse', None):
    return True
  for key, value in member.pax_headers.items():
    if key.startswith('GNU.sparse.') or (key == 'SCHILY.filetype' and
                                         value == 'sparse'):
      return True
  return False


def _validate_tar_metadata(member, member_path):
  if member.uid != 0 or member.gid != 0 or member.uname != 'root' or member.gname != 'root':
    raise VerificationError('Archive member ownership metadata is not canonical: {}'.format(member_path))
  if member.linkname:
    raise VerificationError('Archive member link-name metadata is not canonical: {}'.format(member_path))
  if member.devmajor != 0 or member.devminor != 0:
    raise VerificationError('Archive member device metadata is not canonical: {}'.format(member_path))
  if member.mtime != CANONICAL_TAR_MTIME:
    raise VerificationError('Archive member modification time is not canonical: {}'.format(member_path))
  allowed_pax_keys = {'path'}
  unexpected_pax_keys = set(member.pax_headers) - allowed_pax_keys
  if unexpected_pax_keys:
    raise VerificationError('Archive member contains unsupported local PAX metadata {}: {}'.format(sorted(unexpected_pax_keys)[0], member_path))
  expected_pax_path = member_path + '/' if member.type == tarfile.DIRTYPE else member_path
  if 'path' in member.pax_headers and member.pax_headers['path'] != expected_pax_path:
    raise VerificationError('Archive member PAX path does not match its normalized path: {}'.format(member_path))
  if member.type == tarfile.DIRTYPE:
    if member.mode != 0o755:
      raise VerificationError('Archive directory mode is not canonical: {}'.format(member_path))
  elif member.mode not in (0o644, 0o755):
    raise VerificationError('Archive regular-file mode is not canonical: {}'.format(member_path))


def _read_regular_member(archive, member, capture_contents):
  stream = archive.extractfile(member)
  if stream is None:
    raise VerificationError('Unable to read regular archive member: {}'.format(member.name))
  digest = hashlib.sha256()
  byte_count = 0
  captured = bytearray() if capture_contents else None
  with stream:
    while True:
      chunk = stream.read(READ_CHUNK_BYTES)
      if not chunk:
        break
      byte_count += len(chunk)
      digest.update(chunk)
      if captured is not None:
        captured.extend(chunk)
  if byte_count != member.size:
    raise VerificationError('Archive member size changed while reading {}: expected {}, found {}'.format(member.name, member.size, byte_count))
  return ArchiveRecord('file', byte_count, digest.hexdigest()), bytes(captured) if captured is not None else None


def _validate_canonical_end_record(archive):
  first_zero_offset = getattr(archive, '_verification_first_zero_offset', None)
  if first_zero_offset is None:
    raise VerificationError('Archive is missing the canonical tar end record')
  zero_count = getattr(archive, '_verification_zero_count', 0)
  end_after_terminators = first_zero_offset + tarfile.BLOCKSIZE * 2
  canonical_end_offset = ((end_after_terminators + tarfile.RECORDSIZE - 1) // tarfile.RECORDSIZE) * tarfile.RECORDSIZE
  expected_zero_count = (canonical_end_offset - first_zero_offset) // tarfile.BLOCKSIZE
  if zero_count != expected_zero_count:
    raise VerificationError('Archive tar end padding is not canonical: expected {} zero blocks, found {}'.format(expected_zero_count, zero_count))


def _stream_archive(archive_path, target, limits, initial_status):
  records = {}
  casefold_paths = {}
  manifest_contents = None
  extracted_size = 0
  member_count = 0
  total_path_bytes = 0
  manifest_path = '{}/{}'.format(target, MANIFEST_NAME)
  try:
    with archive_path.open('rb') as compressed_stream:
      opened_status = os.fstat(compressed_stream.fileno())
      stable_fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns',
                       'st_ctime_ns')
      if not stat.S_ISREG(opened_status.st_mode) or any((getattr(initial_status, field) != getattr(opened_status, field) for field in stable_fields)):
        raise VerificationError('Archive changed while it was being opened: {}'.format(archive_path))
      with gzip.GzipFile(fileobj=compressed_stream, mode='rb') as decoded_stream:
        bounded_stream = _DecompressedLimitReader(decoded_stream, limits.max_tar_bytes)
        with tarfile.open(name=str(archive_path), fileobj=bounded_stream, mode='r|', tarinfo=_bounded_tar_info(limits), ignore_zeros=True) as archive:
          for member in archive:
            # Streaming mode still caches every TarInfo in archive.members.
            # Links are forbidden and records below retain all needed metadata,
            # so release the parser objects before processing untrusted bytes.
            archive.members[:] = []
            member_count += 1
            if member_count > limits.max_members:
              raise VerificationError('Archive exceeds the {}-member limit'.format(limits.max_members))
            member_path = _validate_archive_member_path(member.name, target, limits)
            total_path_bytes += len(member_path.encode('utf-8'))
            if total_path_bytes > limits.max_total_path_bytes:
              raise VerificationError('Archive member paths exceed aggregate byte limit {}'.format(limits.max_total_path_bytes))
            if member_path in records:
              raise VerificationError('Archive contains duplicate normalized path: {}'.format(member_path))
            folded_path = member_path.casefold()
            if folded_path in casefold_paths:
              raise VerificationError('Archive contains case-colliding paths: {} and {}'.format(casefold_paths[folded_path], member_path))
            casefold_paths[folded_path] = member_path
            if _is_sparse_member(member):
              raise VerificationError('Archive contains a sparse member: {}'.format(member_path))
            if member.type != tarfile.DIRTYPE and member.type != tarfile.REGTYPE:
              raise VerificationError('Archive contains a link or unsupported member type: {}'.format(member_path))
            _validate_tar_metadata(member, member_path)
            if member.type == tarfile.DIRTYPE:
              if member.size != 0:
                raise VerificationError('Archive directory has non-zero size: {}'.format(member_path))
              records[member_path] = ArchiveRecord('directory', 0)
              continue
            if member.size < 0:
              raise VerificationError('Archive member has a negative size: {}'.format(member_path))
            extracted_size += member.size
            if extracted_size > limits.max_extracted_bytes:
              raise VerificationError('Archive extracted size exceeds limit {}'.format(limits.max_extracted_bytes))
            capture_manifest = member_path == manifest_path
            if capture_manifest and member.size > limits.max_manifest_bytes:
              raise VerificationError('Manifest size exceeds limit {}'.format(limits.max_manifest_bytes))
            record, captured = _read_regular_member(archive, member, capture_manifest)
            records[member_path] = record
            if capture_manifest:
              manifest_contents = captured
          _validate_canonical_end_record(archive)
        # Drain any decoder bytes not consumed by tarfile so the cap also
        # covers partial trailing data and concatenated gzip members.
        while bounded_stream.read(READ_CHUNK_BYTES):
          pass
      final_status = os.fstat(compressed_stream.fileno())
      if any((getattr(opened_status, field) != getattr(final_status, field) for field in stable_fields)):
        raise VerificationError('Archive changed while it was being verified: {}'.format(archive_path))
  except VerificationError:
    raise
  except (OSError, EOFError, RecursionError, tarfile.TarError) as exc:
    raise VerificationError('Unable to stream archive {}: {}'.format(archive_path, exc))
  if member_count == 0:
    raise VerificationError('Archive contains no members')
  root = records.get(target)
  if root is None or root.kind != 'directory':
    raise VerificationError('Archive root must be an explicit directory: {}'.format(target))
  for member_path, record in records.items():
    if member_path == target:
      continue
    components = member_path.split('/')
    for index in range(1, len(components) - 1):
      parent_path = '/'.join(components[:index + 1])
      parent = records.get(parent_path)
      if parent is None or parent.kind != 'directory':
        raise VerificationError('Archive path has a missing or non-directory parent: {} below {}'.format(member_path, parent_path))
  if manifest_contents is None:
    raise VerificationError('Archive is missing regular manifest {}'.format(manifest_path))
  if not manifest_contents:
    raise VerificationError('Archive manifest is empty')
  return records, manifest_contents


def _validate_string_list(value, field, expected):
  if type(value) is not list or any(type(item) is not str for item in value):
    raise VerificationError('Manifest {} must be an array of strings'.format(field))
  if tuple(value) != tuple(expected):
    raise VerificationError('Manifest {} does not match the target contract'.format(field))


def _validate_distribution_directories(manifest, limits):
  directories = manifest['distribution_directories']
  if type(directories) is not list or len(directories) > limits.max_distribution_directories:
    raise VerificationError('Manifest distribution_directories must be a bounded array')
  normalized = [
      _validate_relative_path(path, 'Distribution directory path', limits)
      for path in directories
  ]
  if normalized != sorted(normalized) or len(set(normalized)) != len(normalized):
    raise VerificationError('Manifest distribution_directories must be sorted and unique')
  if MANIFEST_NAME in normalized:
    raise VerificationError('Distribution manifest path cannot be declared as a directory')
  return normalized


def _validate_distribution_files(manifest, limits):
  files = manifest['distribution_files']
  if type(files) is not list or len(files) > limits.max_distribution_files:
    raise VerificationError('Manifest distribution_files must be a bounded array')
  inventory = {}
  ordered_paths = []
  for item in files:
    if type(item) is not dict or set(item) != {'path', 'sha256', 'size'}:
      raise VerificationError('Each distribution_files item must contain exactly path, sha256 and size')
    path = _validate_relative_path(item['path'], 'Distribution file path', limits)
    if path == MANIFEST_NAME:
      raise VerificationError('Distribution manifest must be excluded from its own file inventory')
    if type(item['size']) is not int or item['size'] < 0:
      raise VerificationError('Distribution file size must be a nonnegative integer: {}'.format(path))
    if type(item['sha256']) is not str or _DIGEST_PATTERN.fullmatch(item['sha256']) is None:
      raise VerificationError('Distribution file SHA-256 must be lowercase hexadecimal: {}'.format(path))
    if path in inventory:
      raise VerificationError('Manifest distribution_files contains duplicate path: {}'.format(path))
    inventory[path] = item
    ordered_paths.append(path)
  if ordered_paths != sorted(ordered_paths):
    raise VerificationError('Manifest distribution_files must be sorted by path')
  return inventory


def _validate_distribution_tree(manifest, target, records, limits):
  directories = _validate_distribution_directories(manifest, limits)
  files = _validate_distribution_files(manifest, limits)
  folded_paths = {}
  for path in directories:
    folded = path.casefold()
    if folded in folded_paths:
      raise VerificationError('Manifest distribution inventory contains a case collision: {} and {}'.format(folded_paths[folded], path))
    folded_paths[folded] = path
  for path in files:
    folded = path.casefold()
    if folded in folded_paths:
      raise VerificationError('Manifest distribution inventory contains a file/directory or case collision: {} and {}'.format(folded_paths[folded], path))
    folded_paths[folded] = path

  expected_kinds = {
      target: 'directory',
      '{}/{}'.format(target, MANIFEST_NAME): 'file',
  }
  for path in directories:
    expected_kinds['{}/{}'.format(target, path)] = 'directory'
  for path in files:
    expected_kinds['{}/{}'.format(target, path)] = 'file'
  missing = set(expected_kinds) - set(records)
  unexpected = set(records) - set(expected_kinds)
  if missing or unexpected:
    if missing:
      raise VerificationError('Archive is missing distribution inventory member: {}'.format(min(missing)))
    raise VerificationError('Archive contains member absent from distribution inventory: {}'.format(min(unexpected)))
  for path, expected_kind in expected_kinds.items():
    if records[path].kind != expected_kind:
      raise VerificationError('Archive distribution inventory member has wrong kind: {}'.format(path))
  for path, item in files.items():
    record = records['{}/{}'.format(target, path)]
    if item['size'] != record.size or item['sha256'] != record.digest:
      raise VerificationError('Distribution file byte metadata mismatch: {}'.format(path))
  return files


def _validate_top_level_contract(target, records):
  prefix = target + '/'
  actual = {}
  for path, record in records.items():
    if not path.startswith(prefix):
      continue
    relative_path = path[len(prefix):]
    if '/' not in relative_path:
      actual[relative_path] = record
  expected_files = set(TARGET_TOP_LEVEL_FILES[target])
  expected_directories = set(TARGET_TOP_LEVEL_DIRECTORIES[target])
  expected_files.update((path for path in OPTIONAL_RUNTIME_ENTRIES[target] if path in actual))
  expected = expected_files | expected_directories
  missing = expected - set(actual)
  unexpected = set(actual) - expected
  if missing or unexpected:
    if missing:
      raise VerificationError('Archive is missing canonical top-level entry for {}: {}'.format(target, min(missing)))
    raise VerificationError('Archive contains unexpected top-level entry for {}: {}'.format(target, min(unexpected)))
  for path in expected_files:
    record = actual[path]
    if record.kind != 'file' or record.size <= 0:
      raise VerificationError('Canonical top-level file is missing, empty or has wrong kind: {}'.format(path))
  for path in expected_directories:
    record = actual[path]
    if record.kind != 'directory':
      raise VerificationError('Canonical top-level directory has wrong kind: {}'.format(path))
    directory_prefix = '{}/{}/'.format(target, path)
    if not any((member_path.startswith(directory_prefix) and child.kind == 'file' for member_path, child in records.items())):
      raise VerificationError('Canonical top-level directory contains no regular files: {}'.format(path))


def _validate_runtime_entries(manifest, target, records, limits):
  entries = manifest['runtime_entries']
  if type(entries) is not list or not entries or len(entries) > limits.max_runtime_entries:
    raise VerificationError('Manifest runtime_entries must be a bounded non-empty array')
  normalized_entries = [
      _validate_relative_path(entry, 'Runtime entry', limits)
      for entry in entries
  ]
  if normalized_entries != sorted(normalized_entries) or len(set(normalized_entries)) != len(normalized_entries):
    raise VerificationError('Manifest runtime_entries must be sorted and unique')
  folded_entries = set()
  entry_set = set(normalized_entries)
  for entry in normalized_entries:
    folded = entry.casefold()
    if folded in folded_entries:
      raise VerificationError('Manifest runtime_entries contain a case collision')
    folded_entries.add(folded)
    components = entry.split('/')
    for index in range(1, len(components)):
      if '/'.join(components[:index]) in entry_set:
        raise VerificationError('Manifest runtime_entries overlap at {}'.format(entry))
  required = set(TARGET_RUNTIME_ENTRIES[target])
  optional = set(OPTIONAL_RUNTIME_ENTRIES[target])
  actual = set(normalized_entries)
  if not required.issubset(actual) or actual - required - optional:
    raise VerificationError('Manifest runtime_entries do not match the {} runtime contract'.format(target))
  directory_entries = {'jcef_app.app'} if target.startswith('macos_') else {
      'locales'
  }
  for entry in normalized_entries:
    record = records.get('{}/{}'.format(target, entry))
    expected_kind = 'directory' if entry in directory_entries else 'file'
    if record is None or record.kind != expected_kind or (
        expected_kind == 'file' and record.size <= 0):
      raise VerificationError('Runtime entry has the wrong archived type or is empty: {}'.format(entry))
  for optional_entry in optional:
    full_path = '{}/{}'.format(target, optional_entry)
    if (optional_entry in actual) != (full_path in records):
      raise VerificationError('Optional runtime entry presence mismatch: {}'.format(optional_entry))
  return normalized_entries


def _validate_runtime_files(manifest, target, entries, records, distribution_files, limits):
  runtime_files = manifest['runtime_files']
  if type(runtime_files) is not list or not runtime_files or len(runtime_files) > limits.max_runtime_files:
    raise VerificationError('Manifest runtime_files must be a bounded non-empty array')
  inventory = {}
  ordered_paths = []
  folded_paths = set()
  for item in runtime_files:
    if type(item) is not dict or set(item) != {'path', 'sha256', 'size'}:
      raise VerificationError('Each runtime_files item must contain exactly path, sha256 and size')
    path = _validate_relative_path(item['path'], 'Runtime file path', limits)
    if type(item['size']) is not int or item['size'] <= 0:
      raise VerificationError('Runtime file size must be a positive integer: {}'.format(path))
    if type(item['sha256']) is not str or _DIGEST_PATTERN.fullmatch(item['sha256']) is None:
      raise VerificationError('Runtime file SHA-256 must be lowercase hexadecimal: {}'.format(path))
    if path in inventory:
      raise VerificationError('Manifest runtime_files contains duplicate path: {}'.format(path))
    folded = path.casefold()
    if folded in folded_paths:
      raise VerificationError('Manifest runtime_files contains a case collision: {}'.format(path))
    folded_paths.add(folded)
    inventory[path] = item
    ordered_paths.append(path)
  if ordered_paths != sorted(ordered_paths):
    raise VerificationError('Manifest runtime_files must be sorted by path')

  expanded_paths = set()
  for entry in entries:
    full_entry = '{}/{}'.format(target, entry)
    record = records.get(full_entry)
    if record is None:
      raise VerificationError('Archive is missing declared runtime entry: {}'.format(entry))
    if record.kind == 'file':
      entry_paths = {entry}
    else:
      prefix = full_entry + '/'
      entry_paths = {
          path[len(target) + 1:]
          for path, child in records.items()
          if path.startswith(prefix) and child.kind == 'file'
      }
    if not entry_paths:
      raise VerificationError('Declared runtime entry expands to no files: {}'.format(entry))
    overlap = expanded_paths.intersection(entry_paths)
    if overlap:
      raise VerificationError('Declared runtime entries overlap at {}'.format(sorted(overlap)[0]))
    expanded_paths.update(entry_paths)
  if expanded_paths != set(inventory):
    missing = sorted(expanded_paths - set(inventory))
    unexpected = sorted(set(inventory) - expanded_paths)
    raise VerificationError('Runtime inventory does not exactly expand declared entries; missing={}, unexpected={}'.format(missing, unexpected))
  for path, item in inventory.items():
    record = records.get('{}/{}'.format(target, path))
    if record is None or record.kind != 'file':
      raise VerificationError('Runtime inventory path is not an archived regular file: {}'.format(path))
    if item['size'] != record.size or item['sha256'] != record.digest:
      raise VerificationError('Runtime inventory byte metadata mismatch: {}'.format(path))
    distribution_item = distribution_files.get(path)
    if distribution_item is None or distribution_item['size'] != item['size'] or distribution_item['sha256'] != item['sha256']:
      raise VerificationError('Runtime inventory does not match distribution inventory: {}'.format(path))
  for required_path in TARGET_REQUIRED_RUNTIME_FILES[target]:
    record = records.get('{}/{}'.format(target, required_path))
    if record is None or record.kind != 'file' or record.size <= 0:
      raise VerificationError('Archive is missing required non-empty runtime file: {}'.format(required_path))


def _validate_archived_jars(manifest, target, records):
  for field in ('jcef_jars', 'jogamp_jars'):
    for jar_name in manifest[field]:
      record = records.get('{}/{}'.format(target, jar_name))
      if record is None or record.kind != 'file' or record.size <= 0:
        raise VerificationError('Manifest {} archive is missing or empty: {}'.format(field, jar_name))


def _validate_manifest(manifest, target, expected_commit, records, limits):
  if type(manifest) is not dict or set(manifest) != _MANIFEST_KEYS:
    raise VerificationError('Manifest must contain exactly the schema-2 fields')
  if type(manifest['manifest_schema']) is not int or manifest['manifest_schema'] != MANIFEST_SCHEMA:
    raise VerificationError('Manifest schema must be integer 2')
  if type(manifest['java_release']) is not int or manifest['java_release'] != 17:
    raise VerificationError('Manifest java_release must be integer 17')
  if manifest['target'] != target or manifest['archive_root'] != target:
    raise VerificationError('Manifest target and archive_root must match {}'.format(target))
  if manifest['cef_version'] != CEF_VERSION or manifest['cef_api_version'] != CEF_API_VERSION:
    raise VerificationError('Manifest CEF version does not match the pinned distribution contract')
  if type(manifest['java_cef_commit']) is not str or manifest['java_cef_commit'] != expected_commit:
    raise VerificationError('Manifest java_cef_commit does not match the publication commit')
  _validate_string_list(manifest['jcef_jars'], 'jcef_jars', ('jcef.jar', 'jcef-tests.jar'))
  _validate_string_list(manifest['jogamp_jars'], 'jogamp_jars', TARGET_JOGAMP_JARS[target])
  expected_jogl = bool(TARGET_JOGAMP_JARS[target])
  if type(manifest['jogl_swing_osr_supported']) is not bool or manifest['jogl_swing_osr_supported'] != expected_jogl:
    raise VerificationError('Manifest jogl_swing_osr_supported does not match the target contract')
  distribution_files = _validate_distribution_tree(manifest, target, records, limits)
  _validate_top_level_contract(target, records)
  _validate_archived_jars(manifest, target, records)
  entries = _validate_runtime_entries(manifest, target, records, limits)
  _validate_runtime_files(manifest, target, entries, records, distribution_files, limits)


def verify_distribution_archive(archive_path, target, expected_commit, limits=None):
  """Stream and verify one archive against the exact schema-2 contract."""
  if target not in TARGET_RUNTIME_ENTRIES:
    raise VerificationError('Unsupported canonical target: {}'.format(target))
  if not isinstance(expected_commit, str) or _COMMIT_PATTERN.fullmatch(expected_commit) is None:
    raise VerificationError('Expected Java CEF commit must be 40 lowercase hexadecimal characters')
  limits = limits or VerificationLimits()
  archive_path = Path(archive_path)
  initial_status = _validate_archive_file(archive_path, target, limits)
  records, manifest_contents = _stream_archive(archive_path, target, limits, initial_status)
  manifest = _parse_manifest(manifest_contents)
  _validate_manifest(manifest, target, expected_commit, records, limits)


def main(argv=None):
  if sys.version_info < (3, 9):
    print('ERROR: distribution archive verification requires Python 3.9 or newer', file=sys.stderr)
    return 2
  parser = argparse.ArgumentParser(description='Verify one schema-2 JCEF distribution archive without extraction.')
  parser.add_argument('--target', required=True, choices=tuple(TARGET_RUNTIME_ENTRIES), help='Canonical distribution target')
  parser.add_argument('--archive', required=True, help='Path to the canonical target.tar.gz file')
  parser.add_argument('--java-cef-commit', required=True, help='Expected 40-lowercase-hex JCEF source commit')
  options = parser.parse_args(argv)
  try:
    verify_distribution_archive(options.archive, options.target, options.java_cef_commit)
  except VerificationError as exc:
    print('ERROR: {}'.format(exc), file=sys.stderr)
    return 1
  print('Verified {}'.format(options.archive))
  return 0


if __name__ == '__main__':
  sys.exit(main())
