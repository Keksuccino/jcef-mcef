#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.
"""Small deterministic schema-2 archives shared by distribution tool tests."""

from __future__ import absolute_import

import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile

from verify_distribution_archive import CEF_API_VERSION, CEF_VERSION
from verify_distribution_archive import MANIFEST_NAME, MANIFEST_SCHEMA
from verify_distribution_archive import TARGET_JOGAMP_JARS
from verify_distribution_archive import TARGET_REQUIRED_RUNTIME_FILES
from verify_distribution_archive import TARGET_RUNTIME_ENTRIES
from verify_distribution_archive import TARGET_TOP_LEVEL_FILES

TEST_COMMIT = '0123456789abcdef0123456789abcdef01234567'


def canonical_runtime_files(target):
  """Return the minimum non-empty runtime byte mapping for one target."""
  if target.startswith('macos_'):
    paths = TARGET_REQUIRED_RUNTIME_FILES[target]
  else:
    paths = []
    for entry in TARGET_RUNTIME_ENTRIES[target]:
      paths.append('{}/en-US.pak'.format(entry) if entry == 'locales' else entry)
    paths.extend(TARGET_REQUIRED_RUNTIME_FILES[target])
  return {
      path: 'runtime:{}:{}'.format(target, path).encode('utf-8')
      for path in paths
  }


def canonical_jar_files(target):
  names = ('jcef.jar', 'jcef-tests.jar') + TARGET_JOGAMP_JARS[target]
  return {
      name: 'jar:{}:{}'.format(target, name).encode('utf-8')
      for name in names
  }


def canonical_static_files(target):
  runtime_entries = set(TARGET_RUNTIME_ENTRIES[target])
  jar_names = {'jcef.jar', 'jcef-tests.jar'} | set(TARGET_JOGAMP_JARS[target])
  files = {
      name: 'required:{}:{}'.format(target, name).encode('utf-8')
      for name in TARGET_TOP_LEVEL_FILES[target]
      if name != MANIFEST_NAME and name not in runtime_entries and name not in jar_names
  }
  files['docs/index.html'] = b'generated documentation'
  files['tests/README.txt'] = b'test harness'
  return files


def canonical_distribution_files(target, runtime_files=None, jar_files=None, static_files=None):
  files = dict(canonical_runtime_files(target) if runtime_files is None else runtime_files)
  files.update(canonical_jar_files(target) if jar_files is None else jar_files)
  files.update(canonical_static_files(target) if static_files is None else static_files)
  return files


def canonical_distribution_directories(files):
  directories = set()
  for path in files:
    components = path.split('/')
    directories.update(('/'.join(components[:index]) for index in range(1, len(components))))
  return sorted(directories)


def _file_inventory(files):
  return [{
      'path': path,
      'sha256': hashlib.sha256(contents).hexdigest(),
      'size': len(contents),
  } for path, contents in sorted(files.items())]


def canonical_manifest(target, commit=TEST_COMMIT, runtime_files=None, jar_files=None, static_files=None):
  runtime_files = canonical_runtime_files(target) if runtime_files is None else runtime_files
  distribution_files = canonical_distribution_files(target, runtime_files, jar_files, static_files)
  return {
      'archive_root':
          target,
      'cef_api_version':
          CEF_API_VERSION,
      'cef_version':
          CEF_VERSION,
      'distribution_directories':
          canonical_distribution_directories(distribution_files),
      'distribution_files':
          _file_inventory(distribution_files),
      'java_cef_commit':
          commit,
      'java_release':
          17,
      'jogl_swing_osr_supported':
          bool(TARGET_JOGAMP_JARS[target]),
      'jogamp_jars':
          list(TARGET_JOGAMP_JARS[target]),
      'jcef_jars': ['jcef.jar', 'jcef-tests.jar'],
      'manifest_schema':
          MANIFEST_SCHEMA,
      'runtime_entries':
          list(TARGET_RUNTIME_ENTRIES[target]),
      'runtime_files':
          _file_inventory(runtime_files),
      'target':
          target,
  }


def directory_member(name):
  return {'name': name, 'type': tarfile.DIRTYPE}


def file_member(name, contents, pax_headers=None):
  return {
      'contents': contents,
      'name': name,
      'pax_headers': dict(pax_headers or {}),
      'type': tarfile.REGTYPE,
  }


def special_member(name, member_type, linkname='', pax_headers=None):
  return {
      'linkname': linkname,
      'name': name,
      'pax_headers': dict(pax_headers or {}),
      'type': member_type,
  }


def canonical_members(target, commit=TEST_COMMIT, manifest=None, manifest_bytes=None, runtime_files=None, jar_files=None, static_files=None):
  runtime_files = canonical_runtime_files(target) if runtime_files is None else runtime_files
  jar_files = canonical_jar_files(target) if jar_files is None else jar_files
  static_files = canonical_static_files(target) if static_files is None else static_files
  manifest = canonical_manifest(target, commit, runtime_files, jar_files, static_files) if manifest is None else manifest
  if manifest_bytes is None:
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')
  files = canonical_distribution_files(target, runtime_files, jar_files, static_files)
  files[MANIFEST_NAME] = manifest_bytes
  archive_files = {
      '{}/{}'.format(target, path): contents
      for path, contents in files.items()
  }
  directories = {target}
  for path in archive_files:
    components = path.split('/')
    directories.update(('/'.join(components[:index]) for index in range(1, len(components))))
  members = [
      directory_member(path)
      for path in sorted(directories, key=lambda value: (value.count('/'), value))
  ]
  members.extend((file_member(path, contents) for path, contents in sorted(archive_files.items())))
  return members


def build_tar_gz(members, tar_format=tarfile.PAX_FORMAT):
  output = io.BytesIO()
  with gzip.GzipFile(filename='', mode='wb', compresslevel=9, fileobj=output, mtime=946684800) as compressed_stream:
    with tarfile.open(fileobj=compressed_stream, mode='w', format=tar_format) as archive:
      for specification in members:
        member = tarfile.TarInfo(specification['name'])
        member.type = specification.get('type', tarfile.REGTYPE)
        member.linkname = specification.get('linkname', '')
        member.pax_headers = dict(specification.get('pax_headers', {}))
        contents = specification.get('contents', b'')
        member.size = specification.get('size', len(contents))
        member.mode = specification.get('mode', 0o755 if member.type == tarfile.DIRTYPE else 0o644)
        member.mtime = specification.get('mtime', 946684800)
        member.uid = specification.get('uid', 0)
        member.gid = specification.get('gid', 0)
        member.uname = specification.get('uname', 'root')
        member.gname = specification.get('gname', 'root')
        stream = io.BytesIO(contents) if member.type in (
            tarfile.REGTYPE, tarfile.AREGTYPE) else None
        archive.addfile(member, stream)
  return output.getvalue()


def build_valid_archive(target, commit=TEST_COMMIT):
  return build_tar_gz(canonical_members(target, commit))


def write_valid_archive(path, target, commit=TEST_COMMIT):
  path = Path(path)
  path.write_bytes(build_valid_archive(target, commit))
  return path
