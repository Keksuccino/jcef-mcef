CONTENTS
--------

README.txt                  This file.
DISTRIBUTION-MANIFEST.json  Machine-readable target and runtime inventory.
CEF-LICENSE.txt             Exact CEF redistribution terms.
CREDITS.html                Chromium and bundled third-party notices.
*.so, jcef_helper, *.pak    Native JCEF/CEF runtime at the jcef.path root.
locales/                    Complete CEF locale resources.
*.jar                       JCEF, sample and matching optional JogAmp archives.
docs/                       JCEF API documentation.
tests/                      Sample and test source code.
compile.sh                  Recompiles the detailed and simple samples.
run.sh                      Runs a detailed or simple generic sample.
java17_check.sh             Enforces the exact Java 17 runtime contract.

USAGE
-----

1. Set JAVA_HOME to a Java 17 JDK of the same architecture.
2. Execute ./run.sh to run the detailed windowed sample, or execute
   ./run.sh simple to run the minimal windowed sample.
3. Optionally execute ./compile.sh to rebuild jcef-tests.jar.

The launcher explicitly selects JCEF's generic internally scheduled message
pump. MCEF integrations should set jcef.path themselves and continue driving
their configured external message pump.

Additional JCEF information is available at:
https://github.com/chromiumembedded/java-cef
