#!/usr/bin/env python3
# Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

from __future__ import absolute_import
from __future__ import print_function

from distrib.distribution import CEF_VERSION, JCEF_RUNTIME_FILES
from distrib.distribution import cef_root_path, cef_runtime_manifest
from distrib.distribution import jogamp_jars, mac_runtime_requirements
from distrib.distribution import resolve_target
from file_util import path_exists, read_file, write_file
from optparse import OptionParser
import os
from pathlib import Path
from readme_util import read_readme_file
import git_util as git
import sys


def get_readme_component(name):
  """Load a target-family or shared README component."""
  paths = [
      os.path.join(script_dir, 'distrib', target.name),
      os.path.join(script_dir, 'distrib', target.family),
      os.path.join(script_dir, 'distrib'),
  ]
  for path in paths:
    file_path = os.path.join(path, 'README.' + name + '.txt')
    if path_exists(file_path):
      return read_file(file_path)
  raise Exception('README component not found: ' + name)


def runtime_components():
  if target.family == 'macos':
    return mac_runtime_requirements(target, 'flat')
  cef_root = cef_root_path(jcef_dir_path, target)
  binaries, resources = cef_runtime_manifest(cef_root, target)
  return binaries + resources + JCEF_RUNTIME_FILES[target.family]


def format_component_list(components):
  return '\n'.join('    ' + component for component in components)


def jogamp_description():
  components = jogamp_jars(target)
  if components:
    return (
        'The optional JOGL-backed Swing off-screen renderer is supported by '
        'the matching JogAmp 2.4.0 artifacts included in this distribution:\n\n'
        + format_component_list(components))
  return (
      'The JCEF core API and windowed browser sample do not require JogAmp. '
      'JogAmp does not publish Windows ARM64 native artifacts, so this '
      'distribution intentionally omits all JogAmp jars. The JOGL-backed '
      'Swing off-screen renderer is therefore unavailable on this target; '
      'applications may provide a different CefRenderHandler-based renderer.')


def create_readme():
  """Create the distribution README.txt file."""
  header_data = get_readme_component('header')
  mode_data = get_readme_component('standard')
  redistrib_data = get_readme_component('redistrib')
  footer_data = get_readme_component('footer')
  data = header_data + '\n\n' + mode_data + '\n\n' + redistrib_data + '\n\n' + footer_data
  replacements = {
      '$JCEF_URL$':
          jcef_url,
      '$JCEF_REV$':
          jcef_commit_hash,
      '$JCEF_VER$':
          jcef_ver,
      '$CEF_URL$':
          cef_url,
      '$CEF_VER$':
          cef_ver,
      '$CHROMIUM_URL$':
          chromium_url,
      '$CHROMIUM_VER$':
          chromium_ver,
      '$PLATFORM$':
          '{} {}'.format(target.platform_label, target.architecture_label),
      '$TARGET$':
          target.name,
      '$RUNTIME_COMPONENTS$':
          format_component_list(runtime_components()),
      '$JOGAMP_COMPONENTS$':
          jogamp_description(),
  }
  for placeholder, value in replacements.items():
    data = data.replace(placeholder, value)
  write_file(os.path.join(output_dir, 'README.txt'), data)
  if not options.quiet:
    sys.stdout.write('Creating README.txt file.\n')


if __name__ != '__main__':
  sys.stderr.write('This file cannot be loaded as a module!\n')
  sys.exit(1)

description = 'This utility builds README.txt for a JCEF distribution.'
parser = OptionParser(description=description)
parser.add_option(
    '--output-dir',
    dest='outputdir',
    metavar='DIR',
    help='output directory [required]')
parser.add_option(
    '--platform', dest='platform', help='canonical target platform [required]')
parser.add_option(
    '-q',
    '--quiet',
    action='store_true',
    dest='quiet',
    default=False,
    help='do not output detailed status information')
(options, unused_args) = parser.parse_args()

if options.outputdir is None or options.platform is None:
  parser.print_help(sys.stderr)
  sys.exit(1)

try:
  target = resolve_target(options.platform)
except Exception as exc:
  print('ERROR: {}'.format(exc), file=sys.stderr)
  sys.exit(1)

output_dir = options.outputdir
script_dir = os.path.dirname(__file__)
jcef_dir = os.path.abspath(os.path.join(script_dir, os.pardir))
jcef_dir_path = Path(jcef_dir)

args = {}
read_readme_file(os.path.join(jcef_dir, 'jcef_build', 'README.txt'), args)
if args.get('CEF_VER') != CEF_VERSION:
  raise Exception('jcef_build/README.txt describes CEF {}, expected {}'.format(
      args.get('CEF_VER'), CEF_VERSION))

if not git.is_checkout(jcef_dir):
  raise Exception('Not a valid checkout: %s' % (jcef_dir))

jcef_commit_number = git.get_commit_number(jcef_dir)
jcef_commit_hash = git.get_hash(jcef_dir)
jcef_url = git.get_url(jcef_dir)
jcef_ver = '%s.%s.%s.%s+g%s' % (args['CEF_MAJOR'], args['CEF_MINOR'],
                                args['CEF_PATCH'], jcef_commit_number,
                                jcef_commit_hash[:7])

cef_ver = args['CEF_VER']
cef_url = args['CEF_URL']
chromium_ver = args['CHROMIUM_VER']
chromium_url = args['CHROMIUM_URL']

create_readme()
