# Building OpenJDK 17 for JCEF debugging

JCEF's Java build, JNI headers, tools, and CI all target JDK 17 exactly. A local debug build of OpenJDK 17 is useful when a failure crosses from JCEF native code into AWT, the JVM, or another JDK module and vendor-provided symbols are insufficient.

The OpenJDK build prerequisites change as operating systems and toolchains evolve. Use the [OpenJDK 17 Updates build instructions](https://github.com/openjdk/jdk17u-dev/blob/master/doc/building.md) as the authoritative source for platform packages, boot JDK requirements, supported compilers, and Windows POSIX environment setup.

## Build a debug JDK

Clone the maintained JDK 17 Updates repository and follow any dependency guidance printed by `configure`:

```sh
git clone https://github.com/openjdk/jdk17u-dev.git
cd jdk17u-dev
bash configure --with-debug-level=fastdebug
make images
make run-test-tier1
./build/*/images/jdk/bin/java -version
```

Use `--with-debug-level=slowdebug` when fully disabled optimization is more important than runtime performance. Keep the OpenJDK checkout in a short local path without spaces, particularly on Windows.

## Build and run JCEF with the debug JDK

Point `JAVA_HOME` at the generated JDK image before configuring or compiling JCEF. Replace `<configuration>` with the directory created under `build`:

```sh
export JAVA_HOME=/path/to/jdk17u-dev/build/<configuration>/images/jdk
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

Both version commands must report 17. Configure and build JCEF normally in its required `jcef_build` directory. For mixed Java/native debugging, also create a Debug JCEF build and install matching CEF debug symbols in `jcef_build/native/Debug`.

Launch through the repository's `tools/run.sh` or `tools/run.bat` script so the native library paths and required JVM options remain consistent. Attach LLDB, GDB, or Visual Studio to the resulting Java process. Do not hard-code the debug JDK path into repository scripts; selecting it through `JAVA_HOME` keeps local debugging configuration out of source control.
