# Branches and building

This MCEF-focused JCEF fork builds one pinned browser runtime on six 64-bit
targets:

| Canonical target | Operating system | Architecture |
| --- | --- | --- |
| `linux_amd64` | Linux | x86_64 |
| `linux_arm64` | Linux | ARM64/AArch64 |
| `macos_amd64` | macOS | x86_64 |
| `macos_arm64` | macOS | ARM64/Apple Silicon |
| `windows_amd64` | Windows | x86_64 |
| `windows_arm64` | Windows | ARM64 |

The exact runtime is CEF
`151.2.3+g89cd581+chromium-151.0.7922.34`, stable API version `15100`, on the
`beta` channel. The build downloads only the matching archive for the selected
target and verifies its pinned digest and extracted layout before use.

## Requirements

Every target requires:

- A JDK whose major version is exactly 17, selected with `JAVA_HOME`.
- Apache Ant 1.10 or newer.
- CMake 3.21 or newer and Ninja, or the supported platform IDE generator.
- Python 3 and Git.
- A C++20-capable native toolchain.

Platform toolchains are:

- Linux: a native 64-bit x86_64 or ARM64 installation with GCC 10 or newer.
  Debian and Ubuntu packages include `build-essential`, `libgtk-3-dev`,
  `ninja-build`, `xauth`, and `xvfb` for automated GUI-backed tests.
- macOS: macOS 14.5 or newer with Xcode 16 or newer and its command-line tools.
- Windows: Windows 10 or newer with Visual Studio 2022. Use an x64 developer
  prompt for `windows_amd64` or an ARM64 developer prompt for
  `windows_arm64`.

Confirm Java before configuring:

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

On macOS, the system selector can locate the installed JDK 17:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

On Windows `cmd.exe`:

```bat
set "JAVA_HOME=C:\absolute\path\to\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
javac -version
```

The repository scripts reject a different JDK major version even if its tools
are otherwise available on `PATH`.

## Configure and build

Clone the repository normally, then create the CMake output directory with the
exact name `jcef_build`. Repository launch, test, JNI, and distribution tools
depend on that name. A build directory represents one operating-system and
architecture pair; do not reuse it after changing target architecture.

### Linux

Run the matching commands on the native target machine.

```sh
mkdir jcef_build

# Choose exactly one target/architecture pair:
cmake -S . -B jcef_build -G Ninja -DPROJECT_ARCH=x86_64 -DCMAKE_BUILD_TYPE=Release
tools/compile.sh linux_amd64

# Or, on ARM64:
# cmake -S . -B jcef_build -G Ninja -DPROJECT_ARCH=arm64 -DCMAKE_BUILD_TYPE=Release
# tools/compile.sh linux_arm64

cmake --build jcef_build --parallel 4
```

### macOS

`PROJECT_ARCH` is required when selecting the CEF architecture. CMake applies
it before compiler detection, so the generated target and downloaded CEF
runtime cannot disagree.

```sh
mkdir jcef_build

# Intel:
cmake -S . -B jcef_build -G Ninja -DPROJECT_ARCH=x86_64 -DCMAKE_BUILD_TYPE=Release
tools/compile.sh macos_amd64

# Or, on Apple Silicon:
# cmake -S . -B jcef_build -G Ninja -DPROJECT_ARCH=arm64 -DCMAKE_BUILD_TYPE=Release
# tools/compile.sh macos_arm64

cmake --build jcef_build --parallel 4
```

The Xcode generator is also supported. Replace `-G Ninja` with `-G Xcode`,
omit `CMAKE_BUILD_TYPE`, and build the Release configuration explicitly:

```sh
cmake --build jcef_build --config Release --parallel 4
```

### Windows

From the matching Visual Studio 2022 developer prompt:

```bat
mkdir jcef_build

rem Choose exactly one target/architecture pair:
cmake -S . -B jcef_build -G "Visual Studio 17 2022" -A x64 -DPROJECT_ARCH=x86_64
tools\compile.bat windows_amd64

rem Or, from an ARM64 developer prompt:
rem cmake -S . -B jcef_build -G "Visual Studio 17 2022" -A ARM64 -DPROJECT_ARCH=arm64
rem tools\compile.bat windows_arm64

cmake --build jcef_build --config Release --parallel 4
```

Ninja is supported from a developer prompt whose compiler already targets the
selected architecture. Add `-DCMAKE_BUILD_TYPE=Release` for that
single-configuration generator.

## Verify JNI headers and run tests

JNI headers are generated directly from production sources and are independent
of the publication target:

```sh
python3 tools/make_jni_headers.py --verify
# Equivalent POSIX wrapper:
tools/make_all_jni_headers.sh --verify
```

Use `python` instead of `python3` and the `.bat` wrapper on Windows.

Run the native-independent suite in headless mode, then the native-backed CEF
suite with the matching Release runtime. Replace `<target>` with one canonical
target from the table.

```sh
tools/run_tests.sh <target> Release --headless --select-package tests.junittests --exclude-tag native-cef
tools/run_tests.sh <target> Release --select-package tests.junittests --include-tag native-cef
```

Linux CI runs both commands under `xvfb-run`. On macOS, `--headless` also keeps
non-GUI tests out of `-XstartOnFirstThread`; native GUI tests retain the AppKit
first-thread requirement. On Windows use `tools\run_tests.bat` with the same
arguments.

The interactive Linux sample can be launched with:

```sh
tools/run.sh linux_amd64 Release detailed
```

Use `linux_arm64` on ARM64. Windows uses `tools\run.bat` with the matching
Windows target. On macOS, launch the generated app bundle:

```sh
open jcef_build/native/Release/jcef_app.app
```

## Package a distribution

Packaging requires a matching Release build in `jcef_build`. It validates the
CEF version, CEF API version, target architecture, native binary architecture,
runtime inventory, Java 17 class-file version, archive safety limits, and the
MCEF-compatible link-free layout.

```sh
tools/make_distrib.sh linux_amd64
```

On Windows:

```bat
tools\make_distrib.bat windows_amd64
```

Replace the target with the exact platform being built. Successful packaging
creates:

- `binary_distrib/<target>/`, the validated unpacked distribution.
- `binary_distrib/<target>.tar.gz`, the deterministic publication archive.
- `binary_distrib/<target>.tar.gz.sha256`, its SHA-256 checksum.

The archive has exactly one canonical target root and is directly usable as
MCEF's `jcef.path`. The generic sample launchers select JCEF's internal message
pump; MCEF continues to select and drive its external message pump itself.

## Continuous integration

`.github/workflows/build-jcef.yml` builds, tests, packages, checksums, and
publishes workflow artifacts for all six targets on native GitHub-hosted
runners. AppVeyor independently covers `windows_amd64`. Master builds also
publish the same tar.gz and checksum files to the configured MCEF object
storage location.
