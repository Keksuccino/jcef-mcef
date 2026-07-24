# Copyright (c) 2016 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

# OVERVIEW
#
# CMake is a cross-platform open-source build system that can generate project
# files in many different formats. It can be downloaded from
# http://www.cmake.org or installed via a platform package manager.
#
# CMake-generated project formats that have been tested with JCEF include:
#
# Linux:      Ninja, GCC 10+, Unix Makefiles
# MacOS:      Ninja, Xcode 16+
# Windows:    Ninja, Visual Studio 2022
#
# Ninja is a cross-platform open-source tool for running fast builds using
# pre-installed platform toolchains (GNU, clang, Xcode or MSVC). It can be
# downloaded from http://martine.github.io/ninja/ or installed via a platform
# package manager.
#
# BUILD REQUIREMENTS
#
# The below requirements must be met to build JCEF.
#
# - CMake version 3.21 or newer.
#
# - A C++20-capable compiler.
#
# - Linux requirements:
#   A 64-bit x86_64 or ARM64 distribution with GCC 10 or newer.
#   Required packages include:
#     build-essential
#     libgtk3.0-dev     (required by the cefclient target only)
#
# - MacOS requirements:
#   Xcode 16 or newer building on MacOS 14.5 or newer. The Xcode command-line
#   tools must also be installed.
#
# - Windows requirements:
#   Visual Studio 2022 building on Windows 10 or newer. Windows 10/11 64-bit is
#   recommended. Newer versions will likely also work but may not have been
#   tested.
#
# BUILD EXAMPLES
#
# The below commands will generate project files and create a Debug build of all
# JCEF native targets using CMake and the platform toolchain.
#
# Start by creating and entering the CMake build output directory. The
# `jcef_build` directory name is required by other JCEF tooling (specifically the
# tools/make_distrib.[bat|sh] and tools/run.[bat|sh] scripts) and should not be
# changed.
# > cd path/to/java-cef/src
# > mkdir jcef_build && cd jcef_build
#
# To perform a Linux build using the CEF binary distribution matching the
# 64-bit target architecture:
#   Using Unix Makefiles:
#     > cmake -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Debug ..
#     > make -j4
#
#   Using Ninja:
#     > cmake -G "Ninja" -DCMAKE_BUILD_TYPE=Debug ..
#     > ninja
#
# To perform a MacOS build using a 64-bit CEF binary distribution:
#   Using the Xcode IDE:
#     > cmake -G "Xcode" -DPROJECT_ARCH="x86_64" ..
#     Open jcef.xcodeproj in Xcode and select Product > Build.
#
#   Using Ninja:
#     > cmake -G "Ninja" -DPROJECT_ARCH="x86_64" -DCMAKE_BUILD_TYPE=Debug ..
#     > ninja
#
# To perform a MacOS build using an ARM64 CEF binary distribution:
#   Using the Xcode IDE:
#     > cmake -G "Xcode" -DPROJECT_ARCH="arm64" ..
#     Open jcef.xcodeproj in Xcode and select Product > Build.
#
#   Using Ninja:
#     > cmake -G "Ninja" -DPROJECT_ARCH="arm64" -DCMAKE_BUILD_TYPE=Debug ..
#     > ninja
#
# To perform a Windows build using a 64-bit x86 CEF binary distribution:
#   Using the Visual Studio 2022 IDE:
#     > cmake -G "Visual Studio 17" -A x64 ..
#     Open jcef.sln in Visual Studio and select Build > Build Solution.
#
#   Using Ninja with Visual Studio 2022 command-line tools:
#     (this path may be different depending on your Visual Studio installation)
#     > "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
#     > cmake -G "Ninja" -DCMAKE_BUILD_TYPE=Debug ..
#     > ninja
#
# To perform a Windows ARM64 build using the Visual Studio 2022 IDE:
#   > cmake -G "Visual Studio 17" -A ARM64 ..
#   Open jcef.sln in Visual Studio and select Build > Build Solution.

#
# Shared configuration.
#

# For VS2022 and Xcode 16+ support.
cmake_minimum_required(VERSION 3.21)

function(_jcef_normalize_architecture input output)
  string(TOLOWER "${input}" _architecture)
  if(_architecture MATCHES "^(x86_64|amd64|x64)$")
    set(_normalized_architecture "x86_64")
  elseif(_architecture MATCHES "^(arm64|aarch64)$")
    set(_normalized_architecture "arm64")
  elseif(_architecture MATCHES "^(x86|i[3-6]86|win32|arm|armv[5-8].*)$")
    set(_normalized_architecture "32-bit")
  else()
    set(_normalized_architecture "")
  endif()
  set(${output} "${_normalized_architecture}" PARENT_SCOPE)
endfunction()

# PROJECT_ARCH is a public selector and therefore accepts only the canonical
# architecture names used by the six publication targets. On macOS this must
# happen before project() so compiler identification and every try-compile use
# the intended SDK architecture instead of the host architecture.
set(_requested_project_arch "")
if(DEFINED PROJECT_ARCH AND NOT "${PROJECT_ARCH}" STREQUAL "")
  string(TOLOWER "${PROJECT_ARCH}" _requested_project_arch)
  if(NOT _requested_project_arch MATCHES "^(x86_64|arm64)$")
    message(FATAL_ERROR "Unsupported PROJECT_ARCH '${PROJECT_ARCH}'. Expected canonical architecture x86_64 or arm64.")
  endif()
endif()

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
  set(_configured_osx_arch "")
  if(CMAKE_OSX_ARCHITECTURES)
    list(LENGTH CMAKE_OSX_ARCHITECTURES _osx_architecture_count)
    if(NOT _osx_architecture_count EQUAL 1)
      message(FATAL_ERROR "CEF requires one macOS target architecture; got CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}'.")
    endif()
    list(GET CMAKE_OSX_ARCHITECTURES 0 _configured_osx_arch_input)
    _jcef_normalize_architecture("${_configured_osx_arch_input}" _configured_osx_arch)
    if(NOT _configured_osx_arch OR _configured_osx_arch STREQUAL "32-bit")
      message(FATAL_ERROR "Unsupported CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}'. Expected x86_64 or arm64.")
    endif()
  endif()
  if(_requested_project_arch AND _configured_osx_arch AND NOT _requested_project_arch STREQUAL _configured_osx_arch)
    message(FATAL_ERROR "PROJECT_ARCH='${PROJECT_ARCH}' conflicts with CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}'.")
  endif()
  if(_requested_project_arch)
    set(CMAKE_OSX_ARCHITECTURES "${_requested_project_arch}" CACHE STRING "macOS target architecture" FORCE)
  elseif(_configured_osx_arch)
    set(CMAKE_OSX_ARCHITECTURES "${_configured_osx_arch}" CACHE STRING "macOS target architecture" FORCE)
  endif()
endif()

# Only generate Debug and Release configuration types.
set(CMAKE_CONFIGURATION_TYPES Debug Release)

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

# Project name.
project(jcef)

# Use folders in the resulting project files.
set_property(GLOBAL PROPERTY OS_FOLDERS ON)


#
# CEF configuration.
#

# This repository intentionally builds against one complete, identical CEF
# release on every supported target. Force these cache entries so reconfiguring
# a CEF 146 build directory cannot retain incompatible headers, ABI selection,
# or download paths.
set(CEF_VERSION "151.2.3+g89cd581+chromium-151.0.7922.34" CACHE STRING "CEF binary distribution version" FORCE)
set(CEF_CHANNEL "beta" CACHE STRING "CEF binary distribution channel" FORCE)
set(api_version "15100" CACHE STRING "Stable CEF API version" FORCE)

# Determine the compiler's real target using predefined macros from an actual
# compile. Host processor strings are unreliable for macOS cross-architecture
# builds, Visual Studio generators, and externally supplied toolchain files.
include(CheckCXXSourceCompiles)
unset(_jcef_compiler_is_x86_64 CACHE)
unset(_jcef_compiler_is_arm64 CACHE)
set(_saved_try_compile_target_type "${CMAKE_TRY_COMPILE_TARGET_TYPE}")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_REQUIRED_QUIET TRUE)
check_cxx_source_compiles([[
#if defined(_M_ARM64EC) || !(defined(__x86_64__) || defined(_M_X64))
#error Not a supported native x86_64 target.
#endif
int main() { return 0; }
]] _jcef_compiler_is_x86_64)
check_cxx_source_compiles([[
#if defined(_M_ARM64EC) || !(defined(__aarch64__) || defined(_M_ARM64))
#error Not a supported native ARM64 target.
#endif
int main() { return 0; }
]] _jcef_compiler_is_arm64)
unset(CMAKE_REQUIRED_QUIET)
if(_saved_try_compile_target_type)
  set(CMAKE_TRY_COMPILE_TARGET_TYPE "${_saved_try_compile_target_type}")
else()
  unset(CMAKE_TRY_COMPILE_TARGET_TYPE)
endif()

if(_jcef_compiler_is_x86_64 AND NOT _jcef_compiler_is_arm64)
  set(_target_arch "x86_64")
elseif(_jcef_compiler_is_arm64 AND NOT _jcef_compiler_is_x86_64)
  set(_target_arch "arm64")
else()
  message(FATAL_ERROR "The configured C++ compiler does not target exactly one supported 64-bit architecture (x86_64 or arm64). ARM64EC, universal, 32-bit, and unknown targets are unsupported.")
endif()

if(NOT CMAKE_SYSTEM_NAME MATCHES "^(Darwin|Linux|Windows)$")
  message(FATAL_ERROR "Unsupported target platform '${CMAKE_SYSTEM_NAME}'. Supported platforms are Windows, Linux, and macOS.")
endif()
if(NOT "${CMAKE_SIZEOF_VOID_P}" STREQUAL "8")
  message(FATAL_ERROR "Unsupported 32-bit toolchain (${CMAKE_SIZEOF_VOID_P}-byte pointers) for ${CMAKE_SYSTEM_NAME} ${_target_arch}.")
endif()
if(_requested_project_arch AND NOT _requested_project_arch STREQUAL _target_arch)
  message(FATAL_ERROR "PROJECT_ARCH '${PROJECT_ARCH}' requests ${_requested_project_arch}, but the configured C++ compiler targets ${_target_arch}.")
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
  if(NOT CMAKE_OSX_ARCHITECTURES)
    set(CMAKE_OSX_ARCHITECTURES "${_target_arch}" CACHE STRING "macOS target architecture" FORCE)
  endif()
  list(LENGTH CMAKE_OSX_ARCHITECTURES _osx_architecture_count)
  if(NOT _osx_architecture_count EQUAL 1)
    message(FATAL_ERROR "CEF requires one macOS compiler target; got CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}'.")
  endif()
  list(GET CMAKE_OSX_ARCHITECTURES 0 _compiled_osx_arch_input)
  _jcef_normalize_architecture("${_compiled_osx_arch_input}" _compiled_osx_arch)
  if(NOT _compiled_osx_arch STREQUAL _target_arch)
    message(FATAL_ERROR "CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}' disagrees with the compiler target ${_target_arch}.")
  endif()
endif()

set(PROJECT_ARCH "${_target_arch}" CACHE STRING "Canonical JCEF target architecture" FORCE)
if("${CMAKE_SYSTEM_NAME}" STREQUAL "Darwin")
  set(CMAKE_OSX_ARCHITECTURES "${PROJECT_ARCH}" CACHE STRING "macOS target architecture" FORCE)
  if(PROJECT_ARCH STREQUAL "arm64")
    set(CEF_PLATFORM "macosarm64")
  else()
    set(CEF_PLATFORM "macosx64")
  endif()
elseif("${CMAKE_SYSTEM_NAME}" STREQUAL "Linux")
  if(PROJECT_ARCH STREQUAL "arm64")
    set(CEF_PLATFORM "linuxarm64")
  else()
    set(CEF_PLATFORM "linux64")
  endif()
elseif("${CMAKE_SYSTEM_NAME}" STREQUAL "Windows")
  if(PROJECT_ARCH STREQUAL "arm64")
    set(CEF_PLATFORM "windowsarm64")
  else()
    set(CEF_PLATFORM "windows64")
  endif()
endif()

message(STATUS "Target architecture: ${PROJECT_ARCH} (${CEF_PLATFORM})")

# Add this project's cmake/ directory to the module path.
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

# Download and extract the CEF binary distribution (executes DownloadCEF.cmake).
include(DownloadCEF)
DownloadCEF("${CEF_PLATFORM}" "${CEF_VERSION}" "${CEF_CHANNEL}" "${CMAKE_SOURCE_DIR}/third_party/cef")

# Add the CEF binary distribution's cmake/ directory to the module path.
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CEF_ROOT}/cmake")

# Load the CEF configuration (executes FindCEF.cmake).
find_package(CEF REQUIRED)


#
# Python configuration.
#

# Support specification of the Python executable path via the command-line.
if(DEFINED ENV{PYTHON_EXECUTABLE})
  file(TO_CMAKE_PATH "$ENV{PYTHON_EXECUTABLE}" PYTHON_EXECUTABLE)
endif()

if(NOT PYTHON_EXECUTABLE)
  unset(PYTHON_EXECUTABLE)

  # Find the python interpreter.
  find_package(PythonInterp)

  if(NOT ${PYTHONINTERP_FOUND})
    message(FATAL_ERROR "A Python installation is required. Set the "
                        "PYTHON_EXECUTABLE environment variable to explicitly "
                        "specify the Python executable path.")
  endif()
endif()

message(STATUS "Using Python: ${PYTHON_EXECUTABLE}")


#
# Java configuration.
#

if(NOT DEFINED ENV{JAVA_HOME} OR "$ENV{JAVA_HOME}" STREQUAL "")
  message(FATAL_ERROR "JDK 17 is required. Set JAVA_HOME to a JDK 17 installation directory.")
endif()

# Find the Java compiler first so that JNI discovery uses the same JDK. JDK 17
# is intentionally exact: Java compilation uses --release 17 and native builds
# must not silently select headers or libraries from a different installation.
file(TO_CMAKE_PATH "$ENV{JAVA_HOME}" JAVA_HOME)
find_package(Java 17 REQUIRED COMPONENTS Development)
if(NOT "${Java_VERSION_MAJOR}" STREQUAL "17")
  message(FATAL_ERROR "JDK 17 is required, but javac ${Java_VERSION_STRING} was found at ${Java_JAVAC_EXECUTABLE}.")
endif()

get_filename_component(JAVA_BIN_DIR "${Java_JAVAC_EXECUTABLE}" DIRECTORY)
get_filename_component(JAVA_DIR "${JAVA_BIN_DIR}" DIRECTORY)
file(REAL_PATH "${JAVA_HOME}" JAVA_HOME_REAL)
file(REAL_PATH "${JAVA_DIR}" JAVA_DIR_REAL)
if(NOT "${JAVA_HOME_REAL}" STREQUAL "${JAVA_DIR_REAL}")
  message(FATAL_ERROR "JAVA_HOME (${JAVA_HOME_REAL}) does not match javac (${JAVA_DIR_REAL}). Clear the CMake cache and configure with JDK 17.")
endif()

find_package(JNI REQUIRED)
message(STATUS "Using Java ${Java_VERSION_STRING}: ${JAVA_DIR_REAL}")


#
# Post-configuration actions.
#

# Generate the JCEF version header.
message(STATUS "Generating native/jcef_version.h file...")
execute_process(
  COMMAND "${PYTHON_EXECUTABLE}"
          "tools/make_version_header.py"
          "--header"
          "native/jcef_version.h"
          "--cef-path"
          "${CEF_ROOT}"
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  RESULT_VARIABLE EXECUTE_RV
  )
if(NOT EXECUTE_RV STREQUAL "0")
  message(FATAL_ERROR "Execution failed with unexpected result: ${EXECUTE_RV}")
endif()

# Copy the CEF README.txt file to the cmake build directory for use by the
# make_readme.py script.
file(COPY "${CEF_ROOT}/README.txt" DESTINATION "${CMAKE_BINARY_DIR}")

# Download clang-format from Google Storage.
if(OS_WINDOWS)
  set(GS_PLATFORM "win32")
  set(GS_HASHPATH "win/clang-format.exe.sha1")
  set(GS_OUTPATH "win/clang-format.exe")
elseif(OS_MACOSX)
  set(GS_PLATFORM "darwin")
  if("${CMAKE_HOST_SYSTEM_PROCESSOR}" STREQUAL "arm64")
    set(GS_HASHPATH "mac/clang-format.arm64.sha1")
  else()
    set(GS_HASHPATH "mac/clang-format.x64.sha1")
  endif()
  set(GS_OUTPATH "mac/clang-format")
elseif(OS_LINUX)
  set(GS_PLATFORM "linux*")
  set(GS_HASHPATH "linux64/clang-format.sha1")
  set(GS_OUTPATH "linux64/clang-format")
endif()

message(STATUS "Downloading clang-format from Google Storage...")
execute_process(
  COMMAND "${PYTHON_EXECUTABLE}"
          "tools/buildtools/download_from_google_storage.py"
          "--no_resume"
          "--platform=${GS_PLATFORM}"
          "--no_auth"
          "--bucket" "chromium-clang-format"
          "-s" "tools/buildtools/${GS_HASHPATH}"
          "-o" "tools/buildtools/${GS_OUTPATH}"
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  RESULT_VARIABLE EXECUTE_RV
  )
if(NOT EXECUTE_RV STREQUAL "0")
  message(FATAL_ERROR "Execution failed with unexpected result: ${EXECUTE_RV}")
endif()


#
# Include target subdirectories.
#

include(CTest)
add_subdirectory(${CEF_LIBCEF_DLL_WRAPPER_PATH} libcef_dll_wrapper)
add_subdirectory(native)


#
# Display configuration settings.
#

PRINT_CEF_CONFIG()

message(STATUS "*** JCEF CONFIGURATION SETTINGS ***")
message(STATUS "Python executable:            ${PYTHON_EXECUTABLE}")
message(STATUS "Java directory:               ${JAVA_DIR}")
message(STATUS "JNI libraries:                ${JNI_LIBRARIES}")
message(STATUS "JNI include directories:      ${JNI_INCLUDE_DIRS}")
