# Test-only DownloadCEF replacement. The configure probe supplies this module
# ahead of the production cmake/ directory and intentionally stops after the
# compiler target has been selected, before any source/generated artifact can
# be touched.
function(DownloadCEF platform version channel download_dir)
  if(NOT DEFINED EXPECTED_CEF_PLATFORM OR NOT platform STREQUAL EXPECTED_CEF_PLATFORM)
    message(FATAL_ERROR "Architecture probe expected '${EXPECTED_CEF_PLATFORM}', got '${platform}'.")
  endif()
  message(FATAL_ERROR "JCEF_ARCHITECTURE_PROBE_OK platform=${platform} project_arch=${PROJECT_ARCH} osx_arch=${CMAKE_OSX_ARCHITECTURES}")
endfunction()
