REDISTRIBUTION
--------------

On Linux and Windows, the native runtime is copied from the CMake Release output
using CEF 151's exact CEF_BINARY_FILES and CEF_RESOURCE_FILES manifests. On
macOS, the JCEF Release app is combined with the exact matching CEF Release
framework in the link-free layout described above. Packaging fails if any
required entry is missing. The runtime entries are:

$RUNTIME_COMPONENTS$

jcef.jar contains the Java 17 JCEF API. jcef-tests.jar and tests/ contain the
sample programs. They are not required by applications that provide their own
Java entry point.

$JOGAMP_COMPONENTS$

Keep the runtime directory intact. In particular, icudtl.dat, the CEF resource
packs, all locale resources, the JNI library and every helper executable are
part of the supported runtime. The pre-CEF-151 files d3dcompiler_43.dll,
icudt.dll, natives_blob.bin and snapshot_blob.bin are intentionally absent.
