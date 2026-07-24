CONTENTS
--------

README.txt                  This file.
DISTRIBUTION-MANIFEST.json  Machine-readable target and runtime inventory.
CEF-LICENSE.txt             Exact CEF redistribution terms.
CREDITS.html                Chromium and bundled third-party notices.
jcef_app.app/               Signed JCEF sample app and complete CEF runtime.
*.jar                       JCEF, sample and matching JogAmp archives.
docs/                       JCEF API documentation.
tests/                      Sample and test source code.
compile.sh                  Recompiles samples inside the signed app.
java17_check.sh             Enforces the exact Java 17 runtime contract.

The distributed app intentionally uses CEF 151's flat framework layout. This
preserves a link-free archive that MCEF's hardened extractor accepts. The CEF
framework and enclosing app are ad-hoc signed after this deterministic
packaging transform and are verified with codesign --deep --strict.

USAGE
-----

1. Install a Java 17 runtime matching this distribution's architecture.
2. Launch jcef_app.app to run the detailed windowed sample.
3. Optionally set JAVA_HOME to a matching JDK 17 and execute ./compile.sh to
   rebuild the samples. The script re-signs the modified app bundle.

Additional JCEF information is available at:
https://github.com/chromiumembedded/java-cef
