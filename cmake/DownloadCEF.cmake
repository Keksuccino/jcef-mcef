# Copyright (c) 2016 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

function(_cef_provenance_content platform version channel archive_sha1 output)
  set(_archive_name "cef_binary_${version}_${platform}_${channel}.tar.bz2")
  set(_content "JCEF_CEF_PROVENANCE_V1\narchive=${_archive_name}\nplatform=${platform}\nversion=${version}\nchannel=${channel}\nsha1=${archive_sha1}\n")
  set(${output} "${_content}" PARENT_SCOPE)
endfunction()

# Verify the exact layout and archive-derived provenance needed before FindCEF
# is allowed to consume an extracted binary distribution.
function(_cef_validate_distribution root platform version channel archive_sha1 valid_output reason_output)
  set(_distribution "cef_binary_${version}_${platform}_${channel}")
  get_filename_component(_root_name "${root}" NAME)
  if(NOT _root_name STREQUAL _distribution)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "root name '${_root_name}' does not match ${_distribution}" PARENT_SCOPE)
    return()
  endif()
  if(IS_SYMLINK "${root}" OR NOT IS_DIRECTORY "${root}")
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "root is missing, is not a directory, or is a symbolic link" PARENT_SCOPE)
    return()
  endif()

  set(_required_paths
    README.txt
    CMakeLists.txt
    cmake/FindCEF.cmake
    cmake/cef_macros.cmake
    cmake/cef_variables.cmake
    include/cef_app.h
    include/cef_api_versions.h
    include/cef_version.h
    libcef_dll/CMakeLists.txt
    )

  if(platform STREQUAL "macosx64")
    set(_snapshot_name "v8_context_snapshot.x86_64.bin")
  elseif(platform STREQUAL "macosarm64")
    set(_snapshot_name "v8_context_snapshot.arm64.bin")
  endif()

  if(platform MATCHES "^macos")
    foreach(_configuration Debug Release)
      set(_framework "${_configuration}/Chromium Embedded Framework.framework")
      list(APPEND _required_paths
        "${_framework}/Chromium Embedded Framework"
        "${_framework}/Libraries/libEGL.dylib"
        "${_framework}/Libraries/libGLESv2.dylib"
        "${_framework}/Libraries/libvk_swiftshader.dylib"
        "${_framework}/Libraries/vk_swiftshader_icd.json"
        "${_framework}/Resources/chrome_100_percent.pak"
        "${_framework}/Resources/chrome_200_percent.pak"
        "${_framework}/Resources/resources.pak"
        "${_framework}/Resources/icudtl.dat"
        "${_framework}/Resources/${_snapshot_name}"
        "${_framework}/Resources/en.lproj/locale.pak"
        )
    endforeach()
  elseif(platform MATCHES "^linux")
    list(APPEND _required_paths
      Debug/libcef.so
      Release/chrome-sandbox
      Release/libcef.so
      Release/libEGL.so
      Release/libGLESv2.so
      Release/libvk_swiftshader.so
      Release/v8_context_snapshot.bin
      Resources/chrome_100_percent.pak
      Resources/chrome_200_percent.pak
      Resources/resources.pak
      Resources/icudtl.dat
      Resources/locales/en-US.pak
      )
  elseif(platform MATCHES "^windows")
    list(APPEND _required_paths
      Debug/libcef.dll
      Debug/libcef.lib
      Release/libcef.dll
      Release/libcef.lib
      Release/libEGL.dll
      Release/libGLESv2.dll
      Release/v8_context_snapshot.bin
      Resources/chrome_100_percent.pak
      Resources/chrome_200_percent.pak
      Resources/resources.pak
      Resources/icudtl.dat
      Resources/locales/en-US.pak
      )
    if(platform STREQUAL "windows64")
      list(APPEND _required_paths Release/dxcompiler.dll Release/dxil.dll)
    endif()
  else()
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "unsupported CEF platform '${platform}'" PARENT_SCOPE)
    return()
  endif()

  foreach(_required_path IN LISTS _required_paths)
    set(_absolute_path "${root}/${_required_path}")
    if(NOT EXISTS "${_absolute_path}" OR IS_DIRECTORY "${_absolute_path}")
      set(${valid_output} FALSE PARENT_SCOPE)
      set(${reason_output} "missing regular file ${_required_path}" PARENT_SCOPE)
      return()
    endif()
    file(SIZE "${_absolute_path}" _required_size)
    if(_required_size LESS 1)
      set(${valid_output} FALSE PARENT_SCOPE)
      set(${reason_output} "empty required file ${_required_path}" PARENT_SCOPE)
      return()
    endif()
  endforeach()

  file(READ "${root}/README.txt" _readme LIMIT 8192)
  string(FIND "${_readme}" "CEF Version:      ${version}" _readme_version_position)
  if(_readme_version_position EQUAL -1)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "README.txt does not identify CEF ${version}" PARENT_SCOPE)
    return()
  endif()

  file(READ "${root}/include/cef_version.h" _version_header LIMIT 8192)
  string(FIND "${_version_header}" "#define CEF_VERSION \"${version}\"" _header_version_position)
  if(_header_version_position EQUAL -1)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "include/cef_version.h does not identify CEF ${version}" PARENT_SCOPE)
    return()
  endif()

  set(_provenance_path "${root}/.jcef-cef-provenance")
  if(IS_SYMLINK "${_provenance_path}" OR NOT EXISTS "${_provenance_path}" OR IS_DIRECTORY "${_provenance_path}")
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "archive provenance marker is missing or unsafe" PARENT_SCOPE)
    return()
  endif()
  _cef_provenance_content("${platform}" "${version}" "${channel}" "${archive_sha1}" _expected_provenance)
  file(READ "${_provenance_path}" _actual_provenance LIMIT 2048)
  if(NOT _actual_provenance STREQUAL _expected_provenance)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "archive provenance marker does not match ${platform}/${version}/${channel}/${archive_sha1}" PARENT_SCOPE)
    return()
  endif()

  set(${valid_output} TRUE PARENT_SCOPE)
  set(${reason_output} "" PARENT_SCOPE)
endfunction()

# CMake versions before 4.3 do not reject traversal during archive extraction.
# Validate every path ourselves before extracting the already hash-pinned file.
function(_cef_validate_archive_paths archive_path distribution valid_output reason_output)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E tar tf "${archive_path}"
    RESULT_VARIABLE _list_result
    OUTPUT_VARIABLE _list_output
    ERROR_VARIABLE _list_error
    )
  if(NOT _list_result EQUAL 0)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "unable to list archive (${_list_result}): ${_list_error}" PARENT_SCOPE)
    return()
  endif()
  if(_list_output MATCHES ";")
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "archive path contains a semicolon, which is unsafe in CMake lists" PARENT_SCOPE)
    return()
  endif()

  string(REPLACE "\r\n" "\n" _list_output "${_list_output}")
  string(REPLACE "\r" "\n" _list_output "${_list_output}")
  string(REPLACE "\n" ";" _archive_entries "${_list_output}")
  set(_entry_count 0)
  foreach(_entry IN LISTS _archive_entries)
    if(_entry STREQUAL "")
      continue()
    endif()
    math(EXPR _entry_count "${_entry_count} + 1")
    if(_entry MATCHES "\\\\" OR _entry MATCHES "^/" OR _entry MATCHES "^[A-Za-z]:[/\\\\]" OR _entry MATCHES "(^|/)\\.\\.(/|$)" OR _entry MATCHES "(^|/)\\.(/|$)")
      set(${valid_output} FALSE PARENT_SCOPE)
      set(${reason_output} "unsafe archive path '${_entry}'" PARENT_SCOPE)
      return()
    endif()
    string(REGEX REPLACE "/+$" "" _normalized_entry "${_entry}")
    string(FIND "${_normalized_entry}" "${distribution}/" _distribution_prefix_position)
    if(NOT _normalized_entry STREQUAL distribution AND NOT _distribution_prefix_position EQUAL 0)
      set(${valid_output} FALSE PARENT_SCOPE)
      set(${reason_output} "archive entry '${_entry}' is outside the exact ${distribution} root" PARENT_SCOPE)
      return()
    endif()
  endforeach()
  if(_entry_count EQUAL 0)
    set(${valid_output} FALSE PARENT_SCOPE)
    set(${reason_output} "archive contains no entries" PARENT_SCOPE)
    return()
  endif()
  set(${valid_output} TRUE PARENT_SCOPE)
  set(${reason_output} "" PARENT_SCOPE)
endfunction()

function(_cef_reject_managed_symlink path description)
  if(IS_SYMLINK "${path}")
    message(FATAL_ERROR "Refusing unsafe symbolic link at managed ${description} path: ${path}")
  endif()
endfunction()

# Download the exact CEF binary distribution for |platform|, |version| and
# |channel| to |download_dir|. CEF_ROOT is updated in both caller and cache
# scope so an existing build directory cannot retain a prior distribution.
function(DownloadCEF platform version channel download_dir)
  if(NOT version STREQUAL "151.2.3+g89cd581+chromium-151.0.7922.34")
    message(FATAL_ERROR "No trusted CEF archive hashes are configured for version '${version}'.")
  endif()
  if(NOT channel STREQUAL "beta")
    message(FATAL_ERROR "CEF ${version} must use the beta channel, not '${channel}'.")
  endif()

  if(platform STREQUAL "linux64")
    set(_expected_sha1 "184100929d0c6a320736b4d56b55893ee8b599d1")
  elseif(platform STREQUAL "linuxarm64")
    set(_expected_sha1 "a560ff702e5e43045874365d57b3a755c059141f")
  elseif(platform STREQUAL "macosx64")
    set(_expected_sha1 "02c25d0d61c0d31b2b2beb7cf951ab907199efd1")
  elseif(platform STREQUAL "macosarm64")
    set(_expected_sha1 "ee13eb24e7d7fca2db6641370ccfb9c78c512f94")
  elseif(platform STREQUAL "windows64")
    set(_expected_sha1 "ca4346d76a5ddb168f317923f60b1e19517da145")
  elseif(platform STREQUAL "windowsarm64")
    set(_expected_sha1 "57b0e72a8f6d28b6d4d2f9ac37b1d941049cdb8f")
  else()
    message(FATAL_ERROR "Unsupported CEF platform '${platform}'.")
  endif()

  set(_cef_distribution "cef_binary_${version}_${platform}_${channel}")
  get_filename_component(_cef_download_dir "${download_dir}" ABSOLUTE)
  get_filename_component(_cef_download_parent "${_cef_download_dir}" DIRECTORY)
  if(_cef_download_parent STREQUAL _cef_download_dir)
    message(FATAL_ERROR "CEF download directory must not be a filesystem root: ${_cef_download_dir}")
  endif()
  if(EXISTS "${_cef_download_dir}" AND NOT IS_DIRECTORY "${_cef_download_dir}")
    message(FATAL_ERROR "CEF download path exists but is not a directory: ${_cef_download_dir}")
  endif()
  _cef_reject_managed_symlink("${_cef_download_dir}" "download directory")
  file(MAKE_DIRECTORY "${_cef_download_dir}")

  set(_cef_root "${_cef_download_dir}/${_cef_distribution}")
  set(_cef_download_filename "${_cef_distribution}.tar.bz2")
  set(_cef_download_path "${_cef_download_dir}/${_cef_download_filename}")
  set(_cef_download_part_path "${_cef_download_path}.part")
  set(_cef_lock_path "${_cef_download_path}.lock")
  set(_cef_extract_dir "${_cef_download_dir}/${_cef_distribution}.extracting")
  set(_cef_staged_root "${_cef_extract_dir}/${_cef_distribution}")
  set(_cef_backup_root "${_cef_download_dir}/${_cef_distribution}.install-backup")

  set(CEF_ROOT "${_cef_root}" CACHE INTERNAL "CEF_ROOT" FORCE)
  set(CEF_ROOT "${_cef_root}" PARENT_SCOPE)

  foreach(_managed_path IN ITEMS "${_cef_root}" "${_cef_download_path}" "${_cef_download_part_path}" "${_cef_lock_path}" "${_cef_extract_dir}" "${_cef_backup_root}")
    _cef_reject_managed_symlink("${_managed_path}" "CEF")
  endforeach()

  # Serialize all mutation for this exact archive and repeat symlink checks
  # after taking the lock to close the normal concurrent-configure race.
  file(LOCK "${_cef_lock_path}" GUARD FUNCTION TIMEOUT 1800 RESULT_VARIABLE _lock_result)
  if(NOT _lock_result STREQUAL "0")
    message(FATAL_ERROR "Failed to lock CEF archive ${_cef_download_path}: ${_lock_result}")
  endif()
  foreach(_managed_path IN ITEMS "${_cef_root}" "${_cef_download_path}" "${_cef_download_part_path}" "${_cef_extract_dir}" "${_cef_backup_root}")
    _cef_reject_managed_symlink("${_managed_path}" "CEF")
  endforeach()

  _cef_validate_distribution("${_cef_root}" "${platform}" "${version}" "${channel}" "${_expected_sha1}" _root_valid _root_invalid_reason)

  set(_archive_valid FALSE)
  if(EXISTS "${_cef_download_path}")
    if(IS_DIRECTORY "${_cef_download_path}")
      message(FATAL_ERROR "CEF archive path is a directory: ${_cef_download_path}")
    endif()
    file(SHA1 "${_cef_download_path}" _archive_sha1)
    string(TOLOWER "${_archive_sha1}" _archive_sha1)
    if(_archive_sha1 STREQUAL _expected_sha1)
      set(_archive_valid TRUE)
      message(STATUS "Verified cached CEF archive: ${_cef_download_path}")
    else()
      message(WARNING "Removing corrupt CEF archive ${_cef_download_path}: expected SHA1 ${_expected_sha1}, got ${_archive_sha1}.")
      file(REMOVE "${_cef_download_path}")
      set(_root_valid FALSE)
      set(_root_invalid_reason "cached archive checksum was corrupt; reinstalling the extracted root")
    endif()
  endif()

  if(NOT _archive_valid AND NOT _root_valid)
    set(_cef_download_url "https://cef-builds.spotifycdn.com/${_cef_download_filename}")
    string(REPLACE "+" "%2B" _cef_download_url_escaped "${_cef_download_url}")
    file(REMOVE "${_cef_download_part_path}")
    message(STATUS "Downloading ${_cef_download_url_escaped}")
    file(DOWNLOAD
      "${_cef_download_url_escaped}"
      "${_cef_download_part_path}"
      STATUS _download_status
      LOG _download_log
      SHOW_PROGRESS
      TLS_VERIFY ON
      TIMEOUT 1800
      INACTIVITY_TIMEOUT 120
      )
    list(GET _download_status 0 _download_status_code)
    list(GET _download_status 1 _download_status_message)
    if(NOT _download_status_code EQUAL 0)
      file(REMOVE "${_cef_download_part_path}")
      message(FATAL_ERROR "CEF download failed (${_download_status_code}: ${_download_status_message}).\n${_download_log}")
    endif()
    file(SHA1 "${_cef_download_part_path}" _download_sha1)
    string(TOLOWER "${_download_sha1}" _download_sha1)
    if(NOT _download_sha1 STREQUAL _expected_sha1)
      file(REMOVE "${_cef_download_part_path}")
      message(FATAL_ERROR "Downloaded CEF archive has SHA1 ${_download_sha1}; expected ${_expected_sha1}.\n${_download_log}")
    endif()
    file(RENAME "${_cef_download_part_path}" "${_cef_download_path}" RESULT _archive_rename_result)
    if(NOT _archive_rename_result STREQUAL "0")
      file(REMOVE "${_cef_download_part_path}")
      message(FATAL_ERROR "Failed to finalize CEF archive ${_cef_download_path}: ${_archive_rename_result}")
    endif()
    set(_archive_valid TRUE)
  endif()

  if(_root_valid)
    message(STATUS "Using validated CEF distribution with pinned archive provenance: ${_cef_root}")
    return()
  endif()
  if(NOT _archive_valid)
    message(FATAL_ERROR "CEF distribution is incomplete (${_root_invalid_reason}) and no verified archive is available at ${_cef_download_path}.")
  endif()

  _cef_validate_archive_paths("${_cef_download_path}" "${_cef_distribution}" _archive_paths_valid _archive_paths_reason)
  if(NOT _archive_paths_valid)
    message(FATAL_ERROR "CEF archive path validation failed: ${_archive_paths_reason}")
  endif()

  file(REMOVE_RECURSE "${_cef_extract_dir}")
  file(MAKE_DIRECTORY "${_cef_extract_dir}")
  message(STATUS "Extracting ${_cef_download_path}")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E tar xjf "${_cef_download_path}"
    WORKING_DIRECTORY "${_cef_extract_dir}"
    RESULT_VARIABLE _extract_result
    OUTPUT_VARIABLE _extract_output
    ERROR_VARIABLE _extract_error
    )
  if(NOT _extract_result EQUAL 0)
    file(REMOVE_RECURSE "${_cef_extract_dir}")
    message(FATAL_ERROR "CEF extraction failed with status ${_extract_result}.\n${_extract_output}\n${_extract_error}")
  endif()

  set(_staged_provenance "${_cef_staged_root}/.jcef-cef-provenance")
  if(IS_SYMLINK "${_cef_staged_root}" OR NOT IS_DIRECTORY "${_cef_staged_root}")
    file(REMOVE_RECURSE "${_cef_extract_dir}")
    message(FATAL_ERROR "CEF archive did not extract the exact ${_cef_distribution} root.")
  endif()
  if(EXISTS "${_staged_provenance}" OR IS_SYMLINK "${_staged_provenance}")
    file(REMOVE_RECURSE "${_cef_extract_dir}")
    message(FATAL_ERROR "CEF archive unexpectedly contains the reserved provenance marker path.")
  endif()
  _cef_provenance_content("${platform}" "${version}" "${channel}" "${_expected_sha1}" _provenance)
  file(WRITE "${_staged_provenance}" "${_provenance}")
  _cef_validate_distribution("${_cef_staged_root}" "${platform}" "${version}" "${channel}" "${_expected_sha1}" _staged_root_valid _staged_root_invalid_reason)
  if(NOT _staged_root_valid)
    file(REMOVE_RECURSE "${_cef_extract_dir}")
    message(FATAL_ERROR "Extracted CEF distribution failed validation: ${_staged_root_invalid_reason}")
  endif()

  file(REMOVE_RECURSE "${_cef_backup_root}")
  set(_had_previous_root FALSE)
  if(EXISTS "${_cef_root}")
    file(RENAME "${_cef_root}" "${_cef_backup_root}" RESULT _backup_rename_result)
    if(NOT _backup_rename_result STREQUAL "0")
      file(REMOVE_RECURSE "${_cef_extract_dir}")
      message(FATAL_ERROR "Failed to preserve the previous CEF root before installation: ${_backup_rename_result}")
    endif()
    set(_had_previous_root TRUE)
  endif()

  file(RENAME "${_cef_staged_root}" "${_cef_root}" RESULT _root_rename_result)
  if(NOT _root_rename_result STREQUAL "0")
    if(_had_previous_root)
      file(RENAME "${_cef_backup_root}" "${_cef_root}" RESULT _rollback_result)
    endif()
    file(REMOVE_RECURSE "${_cef_extract_dir}")
    message(FATAL_ERROR "Failed to atomically install CEF distribution at ${_cef_root}: ${_root_rename_result}")
  endif()
  file(REMOVE_RECURSE "${_cef_extract_dir}")

  _cef_validate_distribution("${_cef_root}" "${platform}" "${version}" "${channel}" "${_expected_sha1}" _installed_root_valid _installed_root_invalid_reason)
  if(NOT _installed_root_valid)
    file(REMOVE_RECURSE "${_cef_root}")
    if(_had_previous_root)
      file(RENAME "${_cef_backup_root}" "${_cef_root}" RESULT _rollback_result)
    endif()
    message(FATAL_ERROR "Installed CEF distribution failed final validation and was rolled back: ${_installed_root_invalid_reason}")
  endif()
  file(REMOVE_RECURSE "${_cef_backup_root}")
  message(STATUS "Using validated CEF distribution with pinned archive provenance: ${_cef_root}")
endfunction()
