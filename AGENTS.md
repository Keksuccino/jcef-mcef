# Repository Guidelines

## Project Structure & Module Organization
- This project is a modified version of JCEF, for using it in combination with the MCEF Minecraft mod.

## Environment
- You are operating on macOS 27 Beta.

## Coding Style & Naming Conventions
- Target Java 17 with 4-space indentation and UTF-8 encoding (WITHOUT BOM), matching the Gradle toolchain configuration.
- Follow existing packages under `de.keksuccino.fancymenu`, mirroring existing sub-packages to keep cross-loader boundaries clear.
- Name resources with the `fancymenu` prefix (e.g., `fancymenu.mixins.json`, `fancymenu.accesswidener`) so Gradle and the loaders resolve them consistently.
- Prefer explicit nullability annotations from `jsr305`.
- Code should be made reusable/shareable whenever possible. Avoid copy-pasting nearly identical code to multiple places when you could make it a shared method/field/etc. instead.
- The whole project (code, classes, packages, etc.) should always be well-structured and organized, with great focus on easy maintainability. The project should be easy to understand and maintain for new devs later.
- Avoid god classes. Split large classes into organized and well-structured smaller classes.
- Avoid spanning method heads and method calls over multiple lines, no matter how long they are. One line per method head and method call.
- Always document fragile parts of the code that could break easily when handled wrong. Explain what they do and what is important for them.
- Always document code that could look a bit hacky, weird, or even useless at first look. Explain what the code does, why it is there, and what is important to note for it.
- Prefer giving every class that needs a logger its own static final LOGGER object, instead of using a global shared logger.

## Workflow Guidelines
- When the user gives you a log snippet, always search for the full log file containing that snippet, and scan the whole log, so you have a complete picture of what was happening.
- Do not simply implement things without a second thought. Simulate in your reasoning STEP-BY-STEP what each step of the execution chain of the code you implemented does, where it does something, and what could be side effects of it. Chase the whole code execution chain step-by-step, to notice edge cases, incomplete implementations, bugs, etc.
- Always implement everything in the best way possible. Implement everything in the most optimized, performance-friendly, and professional way, following best practices for everything.
- Never rush tasks. It doesn't matter how long a task will take, you always take the best possible route instead of the fastest.
- Always clean up after yourself! When finishing a task, remove leftover code from testing, code from earlier unsuccessful implementation attempts, and dead code.
- ALWAYS move most of the actual work to subagents. You just orchestrate your subagents as main agent. You keep and eye on them in case they do something stupid, so you can steer them, or correct their mistakes, if needed. Make sure to move as much work as possible to subagents.

## Autonomous Testing
- After making changes, always compile/build the project to identify and fix compile errors.
- Make sure to use Java 17 for compile/run stuff, like this for example: `JAVA_HOME=$(/usr/libexec/java_home -v 25) sh gradlew :fabric:compileJava :neoforge:compileJava --stacktrace`
- Add focused JUnit 5 regression tests for every bug fix or behavior change that can be tested automatically. The tests should fail for the broken behavior and cover the main path plus relevant boundary, failure, and lifecycle cases.
- Place shared and Fabric test classes under `fabric/src/test/java`, mirroring the production package and naming each class `<Subject>Test`. Treat each test class as a focused suite for one coherent subject; split unrelated behavior into separate classes.
- Keep tests deterministic and isolated. Prefer small reusable or package-private helpers/controllers for logic that cannot safely instantiate Minecraft runtime objects, without weakening or distorting the production design just for testing.
- Inject clocks, executors, suppliers, and other changing inputs when needed. Use temporary directories, loopback servers, and fakes instead of real user files, external services, arbitrary sleeps, or test-order dependencies.
- Run the focused suite first, for example: `JAVA_HOME=$(/usr/libexec/java_home -v 25) sh gradlew :fabric:test --tests 'fully.qualified.SubjectTest' --stacktrace`
- Report the number of discovered suites/tests and the passed, failed, errored, and skipped totals.
- Never try to control the client, for example with "Computer Use".
- You can add temporary testing code to the mod that executes on client launch or when it hits the Title screen or something, for getting feedback from the game process directly, for things like shader testing and other stuff you need the actual Minecraft process for. Make sure to remove that testing code after.
- There are tools available on the system to validate GLSL shaders. Use these when working with shaders.
- You always TRIPLE-CHECK EVERYTHING! When you are finishing a task, you triple-check everything for completeness, possible bad implementations, rushed implementations, performance, optimization, structurization, and so on.

## Subagents
- Always spawn ALL your subagents with the gpt-5.6 model on "max" reasoning effort.
- Always spawn ALL your subagents with a CLEAN context (do not give them your context), so they have a clean context for doing their task in the best possible way.


