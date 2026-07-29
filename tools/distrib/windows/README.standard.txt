CONTENTS
--------

README.txt                  This file.
DISTRIBUTION-MANIFEST.json  Machine-readable target and runtime inventory.
CEF-LICENSE.txt             Exact CEF redistribution terms.
CREDITS.html                Chromium and bundled third-party notices.
*.dll, jcef_helper.exe      Native JCEF/CEF runtime at the jcef.path root.
*.pak, locales/             Complete CEF resource set.
*.jar                       JCEF, sample and supported optional JogAmp archives.
docs/                       JCEF API documentation.
tests/                      Sample and test source code.
compile.bat                 Recompiles the detailed and simple samples.
run.bat                     Runs the generic detailed windowed sample.
java17_check.bat            Enforces the exact Java 17 runtime contract.

USAGE
-----

1. Set JAVA_HOME to a Java 17 installation of the same architecture.
2. Execute run.bat to run the detailed windowed sample.
3. Optionally execute compile.bat from any working directory to rebuild
   jcef-tests.jar.

The launcher explicitly selects JCEF's generic internally scheduled message
pump. Rinku integrations should set jcef.path themselves and continue driving
their configured external message pump.

Additional Rinku JCEF information is available at:
https://github.com/Keksuccino/jcef-rinku
