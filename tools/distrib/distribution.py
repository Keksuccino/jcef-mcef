#!/usr/bin/env python3
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.
"""Shared definitions and validation for JCEF binary distributions."""

from __future__ import absolute_import
from __future__ import print_function

import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import struct
import subprocess
import sys
import tarfile
import zipfile

CEF_VERSION = '151.2.3+g89cd581+chromium-151.0.7922.34'
CEF_API_VERSION = '15100'
JAVA_CLASS_VERSION = 61
MCEF_DEFAULT_MAX_ARCHIVE_BYTES = 750 * 1024 * 1024
MCEF_DEFAULT_MAX_EXTRACTED_BYTES = 2_000 * 1024 * 1024

# MCEF does not currently expose an entry-count setting. Keep publication
# validation bounded well above the complete six-platform runtime inventory so
# malformed archives cannot exhaust filesystem metadata during extraction.
MAX_ARCHIVE_MEMBERS = 100_000


class DistributionError(RuntimeError):
  """Raised when a distribution input or output violates its contract."""


class Target(object):
  """Describes one official JCEF/MCEF publication target."""

  def __init__(self, name, family, architecture, cef_platform, platform_label,
               architecture_label, jogamp_suffix):
    self.name = name
    self.family = family
    self.architecture = architecture
    self.cef_platform = cef_platform
    self.platform_label = platform_label
    self.architecture_label = architecture_label
    self.jogamp_suffix = jogamp_suffix

  @property
  def supports_jogl_swing_osr(self):
    return self.jogamp_suffix is not None


TARGETS = {
    'linux_amd64':
        Target('linux_amd64', 'linux', 'x86_64', 'linux64', 'Linux',
               'x86_64 (AMD64)', 'linux-amd64'),
    'linux_arm64':
        Target('linux_arm64', 'linux', 'arm64', 'linuxarm64', 'Linux',
               'ARM64 (AArch64)', 'linux-aarch64'),
    'macos_amd64':
        Target('macos_amd64', 'macos', 'x86_64', 'macosx64', 'macOS',
               'x86_64 (AMD64)', 'macosx-universal'),
    'macos_arm64':
        Target('macos_arm64', 'macos', 'arm64', 'macosarm64', 'macOS',
               'ARM64 (Apple Silicon)', 'macosx-universal'),
    'windows_amd64':
        Target('windows_amd64', 'windows', 'x86_64', 'windows64', 'Windows',
               'x86_64 (AMD64)', 'windows-amd64'),
    'windows_arm64':
        Target('windows_arm64', 'windows', 'arm64', 'windowsarm64', 'Windows',
               'ARM64', None),
}

CEF_ARCHIVE_SHA1 = {
    'linux_amd64': '184100929d0c6a320736b4d56b55893ee8b599d1',
    'linux_arm64': 'a560ff702e5e43045874365d57b3a755c059141f',
    'macos_amd64': '02c25d0d61c0d31b2b2beb7cf951ab907199efd1',
    'macos_arm64': 'ee13eb24e7d7fca2db6641370ccfb9c78c512f94',
    'windows_amd64': 'ca4346d76a5ddb168f317923f60b1e19517da145',
    'windows_arm64': '57b0e72a8f6d28b6d4d2f9ac37b1d941049cdb8f',
}

JOGAMP_COMMON_JARS = ('gluegen-rt.jar', 'jogl-all.jar')
JOGAMP_NATIVE_PATTERNS = ('gluegen-rt-natives-{}.jar',
                          'jogl-all-natives-{}.jar')

JCEF_RUNTIME_FILES = {
    'linux': ('libjcef.so', 'jcef_helper'),
    'windows': ('jcef.dll', 'jcef_helper.exe'),
}

OBSOLETE_CEF_FILES = frozenset(('d3dcompiler_43.dll', 'icudt.dll',
                                'natives_blob.bin', 'snapshot_blob.bin',))

MAC_HELPER_SUFFIXES = ('', ' (Alerts)', ' (GPU)', ' (Plugin)', ' (Renderer)')


def canonical_target_names():
  return tuple(TARGETS.keys())


def resolve_target(value):
  requested = value.strip().lower()
  if requested not in TARGETS:
    raise DistributionError("Unsupported distribution target '{}'. Expected "
                            'one of: {}.'.format(
                                value, ', '.join(canonical_target_names())))
  return TARGETS[requested]


def host_family():
  if sys.platform.startswith('linux'):
    return 'linux'
  if sys.platform == 'darwin':
    return 'macos'
  if sys.platform == 'win32':
    return 'windows'
  return None


def validate_host(target):
  actual_family = host_family()
  if target.family != actual_family:
    raise DistributionError(
        '{} must be packaged on {}, but this host is {}.'.format(
            target.name, target.platform_label, sys.platform))


def jogamp_jars(target):
  if target.jogamp_suffix is None:
    return ()
  native_jars = tuple(
      pattern.format(target.jogamp_suffix)
      for pattern in JOGAMP_NATIVE_PATTERNS)
  return JOGAMP_COMMON_JARS + native_jars


def read_cmake_cache(cache_path):
  values = {}
  try:
    lines = cache_path.read_text(encoding='utf-8').splitlines()
  except (OSError, UnicodeError) as exc:
    raise DistributionError('Unable to read {}: {}'.format(cache_path, exc))
  for line in lines:
    if not line or line.startswith(('#', '//')) or '=' not in line:
      continue
    key_and_type, value = line.split('=', 1)
    key = key_and_type.split(':', 1)[0]
    values[key] = value
  return values


def cef_root_path(repository_root, target):
  directory_name = 'cef_binary_{}_{}_beta'.format(CEF_VERSION,
                                                  target.cef_platform)
  path = repository_root / 'third_party' / 'cef' / directory_name
  if not path.is_dir():
    raise DistributionError(
        'Exact CEF {} source directory is missing for {}: {}'.format(
            CEF_VERSION, target.name, path))
  readme_path = path / 'README.txt'
  readme = readme_path.read_text(encoding='utf-8')
  expected_version_line = 'CEF Version:      {}'.format(CEF_VERSION)
  if expected_version_line not in readme:
    raise DistributionError(
        '{} does not describe exact CEF {}.'.format(readme_path, CEF_VERSION))
  archive_name = '{}.tar.bz2'.format(directory_name)
  expected_provenance = ('JCEF_CEF_PROVENANCE_V1\n'
                         'archive={}\n'
                         'platform={}\n'
                         'version={}\n'
                         'channel=beta\n'
                         'sha1={}\n').format(archive_name, target.cef_platform,
                                             CEF_VERSION,
                                             CEF_ARCHIVE_SHA1[target.name])
  provenance_path = path / '.jcef-cef-provenance'
  try:
    provenance = provenance_path.read_text(encoding='ascii')
  except (OSError, UnicodeError) as exc:
    raise DistributionError('Exact CEF archive provenance is missing at {}: {}'.
                            format(provenance_path, exc))
  if provenance != expected_provenance:
    raise DistributionError('{} does not match the pinned {} archive checksum.'.
                            format(provenance_path, target.name))
  return path


def validate_build_configuration(repository_root, target):
  build_root = repository_root / 'jcef_build'
  cache = read_cmake_cache(build_root / 'CMakeCache.txt')
  expected_values = {
      'CEF_VERSION': CEF_VERSION,
      'api_version': CEF_API_VERSION,
      'PROJECT_ARCH': target.architecture,
  }
  for key, expected in expected_values.items():
    actual = cache.get(key)
    if actual != expected:
      raise DistributionError(
          '{} must be {}, but jcef_build/CMakeCache.txt contains {!r}. '
          'Configure a clean matching Release build first.'.format(
              key, expected, actual))
  build_type = cache.get('CMAKE_BUILD_TYPE', '')
  if build_type and build_type != 'Release':
    raise DistributionError(
        'CMAKE_BUILD_TYPE must be Release, but the configured value is {}.'.
        format(build_type))
  if target.family == 'macos':
    osx_architecture = cache.get('CMAKE_OSX_ARCHITECTURES')
    if osx_architecture != target.architecture:
      raise DistributionError(
          'CMAKE_OSX_ARCHITECTURES must be {} for {}, but found {!r}.'.format(
              target.architecture, target.name, osx_architecture))
  cef_root = cef_root_path(repository_root, target)
  build_readme = (build_root / 'README.txt').read_text(encoding='utf-8')
  if 'CEF Version:      {}'.format(CEF_VERSION) not in build_readme:
    raise DistributionError(
        'jcef_build/README.txt does not describe exact CEF {}.'.format(
            CEF_VERSION))
  return cef_root


def _configuration_section(contents, family):
  headings = {
      'linux': ('Linux configuration.', 'Mac OS X configuration.'),
      'windows': ('Windows configuration.', None),
  }
  start_heading, end_heading = headings[family]
  start = contents.find(start_heading)
  if start < 0:
    raise DistributionError(
        'CEF manifest is missing the {} section.'.format(start_heading))
  if end_heading is None:
    return contents[start:]
  end = contents.find(end_heading, start)
  if end < 0:
    raise DistributionError(
        'CEF manifest is missing the {} section.'.format(end_heading))
  return contents[start:end]


def _cmake_list(contents, command, variable):
  variable_expression = r'\s+'.join(
      re.escape(part) for part in variable.split())
  expression = r'\b{}\s*\(\s*{}\b(.*?)\n\s*\)'.format(
      re.escape(command), variable_expression)
  match = re.search(expression, contents, flags=re.DOTALL)
  if match is None:
    raise DistributionError(
        'CEF manifest does not define {} with {}().'.format(variable, command))
  lexer = shlex.shlex(match.group(1), posix=True)
  lexer.whitespace_split = True
  lexer.commenters = '#'
  values = tuple(lexer)
  if not values or any('$' in value for value in values):
    raise DistributionError(
        'CEF manifest contains an unsupported {} definition: {}'.format(
            variable, values))
  return values


def cef_runtime_manifest(cef_root, target):
  """Read the canonical CEF CMake runtime lists for Linux and Windows."""
  if target.family == 'macos':
    raise DistributionError(
        'macOS runtime files are represented by the canonical app bundle, not '
        'CEF_BINARY_FILES/CEF_RESOURCE_FILES.')
  manifest_path = cef_root / 'cmake' / 'cef_variables.cmake'
  contents = manifest_path.read_text(encoding='utf-8')
  section = _configuration_section(contents, target.family)
  binaries = list(_cmake_list(section, 'set', 'CEF_BINARY_FILES'))
  resources = _cmake_list(section, 'set', 'CEF_RESOURCE_FILES')
  if target.family == 'windows' and target.architecture == 'x86_64':
    architecture_block = re.search(
        r'if\s*\(\s*PROJECT_ARCH\s+STREQUAL\s+"x86_64"\s*\)(.*?)endif\s*\(\s*\)',
        section,
        flags=re.DOTALL)
    if architecture_block is None:
      raise DistributionError(
          'CEF Windows manifest is missing its x86_64 runtime block.')
    binaries.extend(
        _cmake_list(
            architecture_block.group(1), 'list', 'APPEND CEF_BINARY_FILES'))
  return tuple(binaries), resources


def _require_nonempty_file(path):
  if not path.is_file() or path.stat().st_size <= 0:
    raise DistributionError(
        'Required non-empty file is missing: {}'.format(path))


def _require_locales(path):
  if not path.is_dir():
    raise DistributionError(
        'Required locales directory is missing: {}'.format(path))
  if not any(child.is_file() and child.suffix == '.pak'
             for child in path.iterdir()):
    raise DistributionError(
        'Locales directory contains no locale .pak files: {}'.format(path))


def _validate_elf_architecture(path, architecture):
  with path.open('rb') as stream:
    header = stream.read(20)
  if len(header) < 20 or header[:4] != b'\x7fELF' or header[4] != 2:
    raise DistributionError('{} is not a 64-bit ELF binary.'.format(path))
  byte_order = '<' if header[5] == 1 else '>' if header[5] == 2 else None
  if byte_order is None:
    raise DistributionError('{} has an invalid ELF byte order.'.format(path))
  machine = struct.unpack(byte_order + 'H', header[18:20])[0]
  expected_machine = 62 if architecture == 'x86_64' else 183
  if machine != expected_machine:
    raise DistributionError('{} has ELF machine {}, expected {} for {}.'.format(
        path, machine, expected_machine, architecture))


def _validate_pe_architecture(path, architecture):
  with path.open('rb') as stream:
    dos_header = stream.read(64)
    if len(dos_header) < 64 or dos_header[:2] != b'MZ':
      raise DistributionError('{} is not a PE binary.'.format(path))
    pe_offset = struct.unpack('<I', dos_header[60:64])[0]
    stream.seek(pe_offset)
    pe_header = stream.read(6)
  if len(pe_header) < 6 or pe_header[:4] != b'PE\0\0':
    raise DistributionError('{} has an invalid PE header.'.format(path))
  machine = struct.unpack('<H', pe_header[4:6])[0]
  expected_machine = 0x8664 if architecture == 'x86_64' else 0xAA64
  if machine != expected_machine:
    raise DistributionError(
        '{} has PE machine 0x{:04x}, expected 0x{:04x} for {}.'.format(
            path, machine, expected_machine, architecture))


def _validate_macho_architecture(path, architecture, allow_additional=False):
  result = subprocess.run(
      ['/usr/bin/lipo', '-archs', str(path)],
      check=False,
      stdout=subprocess.PIPE,
      stderr=subprocess.PIPE,
      text=True)
  if result.returncode != 0:
    raise DistributionError('Unable to inspect Mach-O architecture for {}: {}'.
                            format(path, result.stderr.strip()))
  architectures = result.stdout.split()
  if allow_additional and architecture not in architectures:
    raise DistributionError('{} contains Mach-O architectures {}, expected '
                            'a {} slice.'.format(path, architectures,
                                                 architecture))
  if not allow_additional and architectures != [architecture]:
    raise DistributionError('{} contains Mach-O architectures {}, expected '
                            'only {}.'.format(path, architectures,
                                              architecture))


def _validate_native_architecture(path, target, allow_additional=False):
  if target.family == 'linux':
    _validate_elf_architecture(path, target.architecture)
  elif target.family == 'windows':
    _validate_pe_architecture(path, target.architecture)
  else:
    _validate_macho_architecture(path, target.architecture, allow_additional)


def _validate_linux_or_windows_runtime(runtime_root, cef_root, target,
                                       check_architecture):
  binaries, resources = cef_runtime_manifest(cef_root, target)
  for relative_path in binaries + resources + JCEF_RUNTIME_FILES[target.family]:
    path = runtime_root / relative_path
    if relative_path == 'locales':
      _require_locales(path)
    else:
      _require_nonempty_file(path)
  for obsolete_name in OBSOLETE_CEF_FILES:
    if (runtime_root / obsolete_name).exists():
      raise DistributionError(
          'Obsolete pre-CEF-151 runtime file must not be packaged: {}'.format(
              runtime_root / obsolete_name))
  if check_architecture:
    architecture_files = JCEF_RUNTIME_FILES[target.family]
    if target.family == 'linux':
      architecture_files += ('libcef.so',)
    else:
      architecture_files += ('libcef.dll',)
    for relative_path in architecture_files:
      _validate_native_architecture(runtime_root / relative_path, target)
  return binaries + resources + JCEF_RUNTIME_FILES[target.family]


def mac_runtime_requirements(target, framework_layout='versioned'):
  framework = ('jcef_app.app/Contents/Frameworks/'
               'Chromium Embedded Framework.framework')
  if framework_layout == 'versioned':
    framework_contents = framework + '/Versions/A'
  elif framework_layout == 'flat':
    framework_contents = framework
  else:
    raise DistributionError(
        'Unsupported macOS framework layout: {}'.format(framework_layout))
  resources = framework_contents + '/Resources'
  libraries = framework_contents + '/Libraries'
  requirements = [
      'jcef_app.app/Contents/Info.plist',
      'jcef_app.app/Contents/MacOS/JavaAppLauncher',
      'jcef_app.app/Contents/Java/libjcef.dylib',
      framework_contents + '/Chromium Embedded Framework',
      libraries + '/libEGL.dylib',
      libraries + '/libGLESv2.dylib',
      libraries + '/libvk_swiftshader.dylib',
      libraries + '/vk_swiftshader_icd.json',
      resources + '/Info.plist',
      resources + '/chrome_100_percent.pak',
      resources + '/chrome_200_percent.pak',
      resources + '/resources.pak',
      resources + '/icudtl.dat',
      resources + '/v8_context_snapshot.{}.bin'.format(target.architecture),
      resources + '/en.lproj/locale.pak',
  ]
  for suffix in MAC_HELPER_SUFFIXES:
    helper_name = 'jcef Helper{}'.format(suffix)
    requirements.append(
        'jcef_app.app/Contents/Frameworks/{0}.app/Contents/MacOS/{0}'.format(
            helper_name))
  return tuple(requirements)


def _validate_mac_symlinks(runtime_root):
  framework = (runtime_root / 'jcef_app.app' / 'Contents' / 'Frameworks' /
               'Chromium Embedded Framework.framework')
  expected_links = {
      'Chromium Embedded Framework': 'Versions/A/Chromium Embedded Framework',
      'Libraries': 'Versions/A/Libraries',
      'Resources': 'Versions/A/Resources',
      'Versions/Current': 'A',
  }
  for relative_path, expected_target in expected_links.items():
    path = framework / relative_path
    if not path.is_symlink():
      raise DistributionError(
          'CEF 151 macOS framework link is missing: {}'.format(path))
    actual_target = os.readlink(str(path))
    if actual_target != expected_target:
      raise DistributionError('{} points to {!r}, expected {!r}.'.format(
          path, actual_target, expected_target))


def _validate_macos_runtime(runtime_root, target, check_architecture,
                            verify_codesign, framework_layout):
  requirements = mac_runtime_requirements(target, framework_layout)
  for relative_path in requirements:
    _require_nonempty_file(runtime_root / relative_path)
  framework = (runtime_root / 'jcef_app.app' / 'Contents' / 'Frameworks' /
               'Chromium Embedded Framework.framework')
  if framework_layout == 'versioned':
    _validate_mac_symlinks(runtime_root)
    framework_contents = framework / 'Versions' / 'A'
  else:
    framework_contents = framework
    links = [path for path in runtime_root.rglob('*') if path.is_symlink()]
    if links:
      raise DistributionError(
          'MCEF-compatible macOS distribution must contain no links: {}'.format(
              ', '.join(str(path) for path in links)))
  resources = framework_contents / 'Resources'
  for obsolete_name in OBSOLETE_CEF_FILES:
    if (resources / obsolete_name).exists():
      raise DistributionError(
          'Obsolete pre-CEF-151 runtime file must not be packaged: {}'.format(
              resources / obsolete_name))
  if check_architecture:
    architecture_paths = [
        'jcef_app.app/Contents/Java/libjcef.dylib',
        str((framework_contents /
             'Chromium Embedded Framework').relative_to(runtime_root)),
    ]
    for suffix in MAC_HELPER_SUFFIXES:
      helper_name = 'jcef Helper{}'.format(suffix)
      architecture_paths.append(
          'jcef_app.app/Contents/Frameworks/{0}.app/Contents/MacOS/{0}'.format(
              helper_name))
    for relative_path in architecture_paths:
      _validate_native_architecture(runtime_root / relative_path, target)
    # AppBundler's bootstrap executable is intentionally universal in the
    # upstream JCEF app. It must contain the target slice, while CEF/JCEF and
    # every helper above remain single-architecture publication artifacts.
    launcher = runtime_root / 'jcef_app.app/Contents/MacOS/JavaAppLauncher'
    _validate_native_architecture(launcher, target, allow_additional=True)
  if verify_codesign:
    app_path = runtime_root / 'jcef_app.app'
    result = subprocess.run(
        [
            '/usr/bin/codesign', '--verify', '--deep', '--strict',
            '--verbose=2',
            str(app_path)
        ],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True)
    if result.returncode != 0:
      raise DistributionError('Code-signature validation failed for {}: {}'.
                              format(app_path, result.stderr.strip()))
  return requirements


def validate_runtime(runtime_root,
                     cef_root,
                     target,
                     check_architecture=True,
                     verify_codesign=True,
                     mac_framework_layout='versioned'):
  if not runtime_root.is_dir():
    raise DistributionError(
        'Native Release build output path does not exist: {}'.format(
            runtime_root))
  if target.family == 'macos':
    return _validate_macos_runtime(runtime_root, target, check_architecture,
                                   verify_codesign, mac_framework_layout)
  return _validate_linux_or_windows_runtime(runtime_root, cef_root, target,
                                            check_architecture)


def validate_jar_class_version(jar_path, expected_version=JAVA_CLASS_VERSION):
  _require_nonempty_file(jar_path)
  versions = set()
  with zipfile.ZipFile(str(jar_path), 'r') as archive:
    class_names = [
        name for name in archive.namelist() if name.endswith('.class')
    ]
    if not class_names:
      raise DistributionError('{} contains no Java classes.'.format(jar_path))
    for class_name in class_names:
      header = archive.read(class_name)[:8]
      if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe':
        raise DistributionError('{} contains an invalid class file: {}'.format(
            jar_path, class_name))
      versions.add(struct.unpack('>H', header[6:8])[0])
  if versions != {expected_version}:
    raise DistributionError('{} contains class-file versions {}, expected '
                            'Java 17 version {} only.'.format(
                                jar_path, sorted(versions), expected_version))


def validate_matching_jar_classes(first_path, second_path):
  """Ensure a signed app does not contain stale Java classes."""
  with zipfile.ZipFile(str(first_path), 'r') as first_archive:
    with zipfile.ZipFile(str(second_path), 'r') as second_archive:
      first_classes = {
          name: first_archive.read(name)
          for name in first_archive.namelist() if name.endswith('.class')
      }
      second_classes = {
          name: second_archive.read(name)
          for name in second_archive.namelist() if name.endswith('.class')
      }
  if first_classes != second_classes:
    raise DistributionError(
        '{} and {} contain different Java classes. Rebuild the native macOS '
        'Release target so its signed app bundle matches the Java output.'.
        format(first_path, second_path))


def validate_archive(archive_path,
                     target,
                     required_relative_paths,
                     required_directory_paths=(),
                     max_archive_bytes=MCEF_DEFAULT_MAX_ARCHIVE_BYTES,
                     max_extracted_bytes=MCEF_DEFAULT_MAX_EXTRACTED_BYTES,
                     max_members=MAX_ARCHIVE_MEMBERS):
  """Validate an archive against MCEF's bounded, link-free extraction model."""
  expected_root = target.name
  archive_size = archive_path.stat().st_size
  if archive_size <= 0:
    raise DistributionError('{} is empty.'.format(archive_path))
  if archive_size > max_archive_bytes:
    raise DistributionError(
        '{} compressed size {} exceeds MCEF limit {}.'.format(
            archive_path, archive_size, max_archive_bytes))

  members_by_name = {}
  extracted_size = 0
  member_count = 0
  with tarfile.open(str(archive_path), 'r:gz') as archive:
    for member in archive:
      member_count += 1
      if member_count > max_members:
        raise DistributionError(
            '{} contains more than {} members, exceeding the publication '
            'limit.'.format(archive_path, max_members))
      path = PurePosixPath(member.name.replace('\\', '/'))
      if path.is_absolute() or '..' in path.parts or not path.parts:
        raise DistributionError(
            'Unsafe archive member path: {}'.format(member.name))
      if path.parts[0] != expected_root:
        raise DistributionError('Archive member is outside the {} root: {}'.
                                format(expected_root, member.name))
      normalized_name = path.as_posix().rstrip('/')
      if normalized_name in members_by_name:
        raise DistributionError(
            'Archive contains duplicate normalized path: {}'.format(
                normalized_name))
      if member.issym() or member.islnk():
        raise DistributionError(
            'MCEF-compatible archives must not contain links: {} -> {}'.format(
                member.name, member.linkname))
      if not member.isdir() and not member.isfile():
        raise DistributionError(
            'MCEF-compatible archives contain only directories and regular '
            'files; unsupported member: {}'.format(member.name))
      if member.isfile():
        if member.size < 0:
          raise DistributionError(
              'Archive member has a negative size: {}'.format(member.name))
        extracted_size += member.size
        if extracted_size > max_extracted_bytes:
          raise DistributionError('{} extracted size exceeds MCEF limit {}.'.
                                  format(archive_path, max_extracted_bytes))
      members_by_name[normalized_name] = member

  if member_count == 0:
    raise DistributionError('{} is empty.'.format(archive_path))

  root_member = members_by_name.get(expected_root)
  if root_member is None or not root_member.isdir():
    raise DistributionError(
        'Archive root must be an explicit directory: {}'.format(expected_root))

  # A regular-file parent would make extraction order-dependent and may cause
  # one entry to overwrite the filesystem object required by another.
  for normalized_name in members_by_name:
    for parent in PurePosixPath(normalized_name).parents:
      parent_name = parent.as_posix()
      if parent_name == '.':
        break
      parent_member = members_by_name.get(parent_name)
      if parent_member is not None and not parent_member.isdir():
        raise DistributionError(
            'Archive path has a non-directory parent: {} below {}'.format(
                normalized_name, parent_name))

  required_directories = {
      PurePosixPath(path.replace('\\', '/')).as_posix().rstrip('/')
      for path in required_directory_paths
  }
  for relative_path in required_relative_paths:
    normalized_relative_path = PurePosixPath(
        relative_path.replace('\\', '/')).as_posix().rstrip('/')
    expected_path = PurePosixPath(expected_root,
                                  normalized_relative_path).as_posix()
    member = members_by_name.get(expected_path)
    if member is None:
      raise DistributionError(
          'Archive is missing required entry: {}'.format(expected_path))
    if normalized_relative_path in required_directories:
      if not member.isdir():
        raise DistributionError(
            'Required archive directory is not a directory: {}'.format(
                expected_path))
      descendant_prefix = expected_path + '/'
      if not any(
          name.startswith(descendant_prefix) and child.isfile() and
          child.size > 0 for name, child in members_by_name.items()):
        raise DistributionError(
            'Required archive directory contains no non-empty files: {}'.format(
                expected_path))
    elif not member.isfile() or member.size <= 0:
      raise DistributionError(
          'Required archive file is missing or empty: {}'.format(expected_path))


def sha256_file(path):
  digest = hashlib.sha256()
  with path.open('rb') as stream:
    while True:
      chunk = stream.read(1024 * 1024)
      if not chunk:
        break
      digest.update(chunk)
  return digest.hexdigest()
