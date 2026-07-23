# Repository Guidelines

## Project Structure & Module Organization
- This project is a modified version of JCEF, used in the MCEF Minecraft mod.
- Java sources are under `java/org/cef`, JNI/native sources are under `native`, and JUnit tests are under `java/tests/junittests`. `java/tests/simple` and `java/tests/detailed` contain sample applications and manual integration harnesses.
- Native builds use CMake and must keep the build directory named `jcef_build` because repository scripts depend on that path. The build produces the JNI shared library, CEF helper subprocesses, and platform resources; Java compilation and packaging use `build.xml` and the helper scripts under `tools`.
- `third_party` contains vendored or downloaded dependencies. `jcef_build`, `out`, `binary_distrib`, downloaded CEF directories, and `native/jcef_version.h` are generated artifacts and should not be edited or committed unless the task requires it.

## Environment
- You are operating on macOS 27 Beta.

## Coding Style & Naming Conventions
- Target Java 17 with 4-space indentation and UTF-8 encoding (WITHOUT BOM).
- Follow existing packages under `org.cef`, mirroring existing sub-packages to keep Java and native boundaries clear.
- Java, C, C++, and Objective-C++ use the repository's Chromium-based `.clang-format`; Python uses `.style.yapf`. Run `python3 tools/fix_style.py <touched files>` and avoid formatting unrelated code.
- Code should be made reusable/shareable whenever possible. Avoid copy-pasting nearly identical code to multiple places when you could make it a shared method/field/etc. instead.
- The whole project (code, classes, packages, etc.) should always be well-structured and organized, with great focus on easy maintainability. The project should be easy to understand and maintain for new devs later.
- Avoid god classes. Split large classes into organized and well-structured smaller classes.
- Avoid spanning method heads and method calls over multiple lines, no matter how long they are. One line per method head and method call.
- Always document fragile parts of the code that could break easily when handled wrong. Explain what they do and what is important for them.
- Always document code that could look a bit hacky, weird, or even useless at first look. Explain what the code does, why it is there, and what is important to note for it.
- Follow the existing Java and native logging conventions. Do not introduce a new logging framework or global logger without a project-wide need.

## Native & JNI Guidelines
- Treat Java native declarations, generated JNI headers, C++ implementations, hard-coded JNI descriptors, and their call sites as one API. Update them together.
- Do not hand-edit headers marked as machine-generated. The existing header scripts use `javah`, which Java 17 no longer provides, so JNI signature changes require a verified `javac -h` replacement workflow.
- Preserve CEF thread requirements, JVM attach/detach behavior, JNI local/global reference ownership, native reference counting, and Java `dispose()`/CEF shutdown order.
- Add new native sources to the correct common or platform-specific list in `native/CMakeLists.txt`, and keep OS-specific behavior isolated.
- Preserve the fork's MCEF-specific off-screen rendering, externally driven message pump, GLFW input, audio callbacks, and native-buffer paths. Trace changes through both the Java and native sides.

## Workflow Guidelines
- When the user gives you a log snippet, always search for the full log file containing that snippet, and scan the whole log, so you have a complete picture of what was happening.
- Do not simply implement things without a second thought. Trace the full execution chain STEP-BY-STEP, including Java/JNI/CEF crossings, callbacks, threads, ownership, lifecycle, shutdown, and error paths, to identify side effects, edge cases, incomplete implementations, and bugs.
- Always implement everything in the best way possible. Implement everything in the most optimized, performance-friendly, and professional way, following best practices for everything.
- Never rush tasks. It doesn't matter how long a task will take, you always take the best possible route instead of the fastest.
- Always clean up after yourself! When finishing a task, remove leftover code from testing, code from earlier unsuccessful implementation attempts, and dead code.
- ALWAYS move most of the actual work to subagents. You just orchestrate your subagents as main agent. You keep and eye on them in case they do something stupid, so you can steer them back on the right path, or correct their mistakes, if needed. Make sure to move as much work as possible to subagents.

## Autonomous Testing
- After making changes, always compile/build every affected Java and native target to identify and fix compile errors.
- Make sure to use Java 17 for compile/run tasks. On macOS, select it with: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"`
- Add focused JUnit 5 regression tests for every bug fix or behavior change that can be tested automatically. The tests should fail for the broken behavior and cover the main path plus relevant boundary, failure, and lifecycle cases.
- Place test classes under `java/tests/junittests`, use package `tests.junittests`, and name each class `<Subject>Test`. Treat each test class as a focused suite for one coherent subject; split unrelated behavior into separate classes.
- Keep tests deterministic and isolated. Prefer small reusable or package-private helpers/controllers for logic that cannot safely instantiate CEF or native runtime objects, without weakening or distorting the production design just for testing.
- Inject clocks, executors, suppliers, and other changing inputs when needed. Use temporary directories, loopback servers, and fakes instead of real user files, external services, arbitrary sleeps, or test-order dependencies.
- JUnit suites and samples that use CEF require matching Java/native artifacts and correctly configured native library paths. Native or JNI changes are not validated by Java compilation alone.
- CEF integration tests also require an active message pump. Keep their execution bounded so a broken pump or shutdown path cannot hang indefinitely.
- Run the focused suite first, using JUnit's `--select-class tests.junittests.SubjectTest`, before running the full `tests.junittests` package.
- Report the exact commands and platform/architecture, plus the number of discovered suites/tests and the passed, failed, errored, and skipped totals.
- You can add temporary testing code to the simple or detailed JCEF sample for feedback from a running CEF process. Make sure to remove that testing code after.
- You always TRIPLE-CHECK EVERYTHING! When you are finishing a task, you triple-check everything for completeness, possible bad implementations, rushed implementations, performance, optimization, structurization, and so on. For native/JNI work, also check signature consistency, ownership, threading, platform guards, and accidental generated files.

## Subagents
- Always spawn ALL your subagents with the gpt-5.6 model on "max" reasoning effort.
- Always spawn ALL your subagents with a CLEAN context (do not give them your context), so they have a clean context for doing their task in the best possible way.
