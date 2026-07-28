# Changelog

## 2026-07-28: IDE binary and sources release artifacts

- The release pipeline now adds a matching `jcef-mcef.jar` and `jcef-mcef-sources.jar` pair to every
  new JCEF release. The binary is the exact platform-neutral Java JAR from the verified
  `linux_amd64` distribution, and the deterministic sources JAR contains the production Java source
  tree at `org/cef/...` for direct IDE attachment. Neither standalone JAR contains native runtime
  files.

## 2026-07-27: Handler relay and compatibility fixes

- Completed `CefDownloadHandler.canDownload` forwarding through the stable `CefClient` relay.
  Registered handlers now receive the exact browser, URL, and request method and can allow or deny
  a user-initiated download; no handler or a removed handler retains CEF's default-allow behavior.
- Restored direct source and binary compatibility for both legacy six-argument and current
  eight-argument `CefDialogHandler.onFileDialog` implementations. CEF still dispatches the full
  ordered MIME filter, expansion, and description vectors through the eight-argument path, which
  falls back one-way to a legacy override when necessary.

## 2026-07-24 to 2026-07-27: JCEF 151 and Java 17 modernization

This entry records the complete product, API, compatibility, build, test, and publication work in
the `54837c0` through `0224f39` implementation series. The previous MCEF fork was based on the
JCEF/CEF 116 generation and used CEF
`116.0.27+gd8c85ac+chromium-116.0.5845.190`. This series synchronized the fork with the functional
upstream JCEF line through upstream commit `d3de827` (CEF 146), then advanced beyond upstream to
CEF `151.2.3+g89cd581+chromium-151.0.7922.34`, Chromium `151.0.7922.34`, and stable CEF API
version `15100`. The released implementation therefore identifies itself as JCEF
`151.2.3.532+g95e664e`, replacing the old fork's `116.0.27.427+geaeb3d4` build identity.

Adding this changelog does not change the runtime, create a tag, or publish another release.

### Runtime and toolchain upgrade

- Ported the Java and native runtime from the CEF 116-era fork to the then-current upstream JCEF API
  shape, including all functional upstream changes through the CEF 146 update, while preserving
  the fork's MCEF integrations.
- Pinned the runtime to the exact CEF 151.2.3 beta distribution and Chromium 151.0.7922.34 on every
  supported platform. Archive digests, extracted layout, architecture, CEF API version, and runtime
  inventory are verified before a build or distribution is accepted.
- Replaced the old mirror path and disabled checksum behavior with official CEF archive downloads,
  pinned per-target digests, serialized extraction, and atomic install/rollback.
- Moved the complete Java build to Java 17. Production, sample, test, and JNI-header compilation
  use `javac --release 17`; documentation uses `javadoc --release 17`; packaging and distribution
  validate Java 17 bytecode and JDK metadata. Public scripts reject a `JAVA_HOME` whose release
  metadata does not identify JDK 17.
- Updated native targets to the C++20 language level required by CEF 151.
- Standardized the supported native toolchains on CMake 3.21+, GCC 10+, Xcode 16+/macOS 14.5+,
  and Visual Studio 2022/Windows 10+.
- Updated AppBundler, upgraded JogAmp to 2.4.0, moved the JUnit console runtime to 6.1.2, added
  Linux ARM64 JogAmp natives, and removed obsolete 32-bit Linux and Windows artifacts.
- Kept Windows ARM64's component-free MCEF OSR path supported without bundling JogAmp, because no
  matching Windows ARM64 JogAmp native artifacts are published.
- Replaced the old `javah`-style JNI process with one reproducible Java 17 `javac -h` generator and
  byte-for-byte verification for all 37 tracked JNI headers.
- Kept Java 17 macOS app bundles working by restoring the required internal-AWT module access and
  by running native JUnit work beside a live AppKit run loop instead of blocking the first thread.

### New and expanded Java API coverage

The synchronization includes upstream JCEF additions such as `CefDevToolsClient`, rendered-frame
listeners, `CefBrowserSettings.windowless_frame_rate`,
`CefDisplayHandler.onFullscreenModeChange`, `CefFrame.selectAll`, request-context preferences, the
modern resource-handler API, `CefDragData.getFilePaths`, folder-selection file dialogs,
`CefSettings.root_cache_path`, `CefSettings.chrome_policy_id`,
`CefAppHandler.onAlreadyRunningAppRelaunch`, and the new PDF options. The fork additionally bridges
the following CEF 151 and Chromium capabilities that were not reachable through the previous JCEF
API.

#### Browser creation and settings

- Added the three-, four-, and five-argument
  `CefClient.createBrowser(url, isOffscreenRendered, isTransparent[, context[, settings]])`
  overloads, the six-argument `CefBrowserFactory.create` overload, and the settings-aware
  `CefBrowserOsr` constructor. Settings are cloned and validated before they cross browser creation
  and JNI; the legacy `(url, isTransparent[, context])` path remains component-free for MCEF.
- Made `CefBrowser.getUIComponent()` available across browser implementations. Component-free
  `CefBrowserOsr` intentionally returns `null` instead of manufacturing an AWT component.
- Completed `CefBrowserSettings` coverage for every active stable CEF 151 per-browser field: windowless
  frame rate; standard, fixed, serif, sans-serif, cursive, and fantasy font families; default,
  fixed, minimum, and minimum-logical font sizes; default encoding; remote fonts; JavaScript,
  JavaScript window closing, clipboard access, and DOM paste; image loading and standalone-image
  shrinking; text-area resizing; tab-to-links; local storage; WebGL; background color; and Chrome
  status- and zoom-bubble controls.
- Added immutable `CefColor` ARGB values and the typed `CefState` tri-state used by browser
  settings. Validation preserves MCEF transparency behavior and rejects invalid alpha/state/frame
  rate combinations before native browser creation.
- Added `CefRequestContextSettings` for cache path, persisted session cookies, accepted languages,
  and cookieable-scheme configuration.
- Added CEF 120+ single-instance cache safety through `CefSettings.root_cache_path` and
  `CefAppHandler.onAlreadyRunningAppRelaunch`, and fixed command-line reconstruction so switch
  values containing additional `=` characters remain intact.

#### Browser-host state and commands

- Added per-browser audio controls: `CefBrowser.setAudioMuted(boolean)` and the asynchronous,
  lifecycle-safe `CefBrowser.isAudioMuted()` query.
- Added lifecycle-safe browser identity and mode APIs: `CefBrowser.isValid()`,
  `CefBrowser.isSame(CefBrowser)`, and `CefBrowser.isWindowRenderingDisabled()`.
- Added `CefBrowser.getRequestContext()` so browser-specific cookie, preference, cache, content
  setting, and URL-request work can use the exact effective context.
- Added zoom capability and command coverage with `CefZoomCommand`, `canZoom`, `zoom`,
  `getDefaultZoomLevel`, and `getZoomLevelAsync`, while retaining the legacy synchronous zoom
  methods.
- Added browser-fullscreen inspection and control through `isFullscreen()` and
  `exitFullscreen(boolean)`, complementing the display handler's fullscreen-change callback. This
  reports JavaScript/browser fullscreen, not the containing host window's fullscreen state.
- Added `hasDevTools()` for associated DevTools frontend detection without confusing it with a
  Chrome DevTools Protocol client or remote-debugging connection.
- Added `isRenderProcessUnresponsive()` for CEF's snapshot of a renderer that has not processed
  input for at least 15 seconds.
- Added `CefFrame.getBrowser`, `viewSource`, `getSource`, `getText`, `loadRequest`, `loadURL`,
  `createURLRequest`, `pasteAndMatchStyle`, and `delete`, with source-compatible default methods
  where necessary.
- Made asynchronous browser-host queries exact-once and close-safe. Admission is serialized with
  Java browser teardown, the JNI handle is promoted while the lifecycle lock is held, the OSR host
  is revalidated on the CEF UI thread, failed task posting completes deterministically, and late
  native callbacks cannot publish a second result.
- New host-query futures complete exceptionally when a query cannot run, except
  `getWindowlessFrameRate()`, whose compatibility contract converts query failure to `0`. Prefer
  `getZoomLevelAsync()` over legacy `getZoomLevel()`, which waits at most one second and returns
  `0.0` after failure or timeout.

#### DevTools protocol

- Added `CefBrowser.getDevToolsClient()` and a full `CefDevToolsClient` bridge for executing Chrome
  DevTools Protocol methods with JSON parameters and `CompletableFuture<String>` results.
- Added event listeners, method-result correlation, agent attach/detach handling, explicit client
  closure, registration ownership, early-result buffering, and deterministic failure of pending
  commands when the client closes or the agent detaches.
- Fixed the upstream queued-command retention issue and bounded unmatched early results.

#### Off-screen rendering, painting, and presentation

- Raised the JCEF windowless-rendering default from CEF's 30 FPS fallback to 60 FPS and removed the
  Java AWT message-pump behavior that could hold OSR painting near 30 FPS by raising that fallback
  scheduler to 120 FPS.
- Removed the historical 60 FPS API cap. The runtime setter accepts any value of 1 or greater,
  including 120 FPS and 240 FPS. Creation settings reject negative values, while `0` explicitly
  selects CEF's 30 FPS fallback.
- Routed `setWindowVisibility` for OSR browsers through `CefBrowserHost.WasHidden`, so paints stop
  while hidden and resume correctly. Added `notifyScreenInfoChanged()` and typed
  `invalidate(CefPaintElementType)` for view/popup invalidation.
- Added `sendCaptureLostEvent()` so a windowless host can release Chromium mouse capture.
- Added safe paint-listener ownership. Every listener receives detached dirty rectangles and its
  own read-only view of callback-scoped CEF pixels; `CefPaintEvent.copyRenderedFrame()` produces an
  owned copy for retention. Listener failures no longer prevent later listeners from running.
  Register per browser with `CefBrowserOsr.addOnPaintListener`, `setOnPaintListener`, and
  `removeOnPaintListener`; the same methods on `CefClient` are intentionally no-op relay
  methods, not client-wide registration.
- Preserved screenshot capture through `createScreenshot(boolean)` in the upstream-compatible
  Swing/JOGL OSR implementation without making MCEF depend on AWT or JOGL. Component-free
  `CefBrowserOsr.createScreenshot()` throws `UnsupportedOperationException`; a headless subclass
  that needs capture must implement it from its own rendered-frame storage.
- Added protected headless/Swing extension hooks for installing AWT input once per surface,
  notifying paint listeners and parent changes, updating view geometry and screen information, and
  reading the device scale factor: `installAwtInputListeners`, `notifyPaintListeners`,
  `notifyAfterParentChanged`, `updateViewGeometry`, `updateScreenInfo`, and
  `getDeviceScaleFactor`.
- Added immutable custom-cursor snapshots through `CefCursorInfo` (hotspot, scale, size, and owned
  BGRA pixels), stable raw `CefCursorType` decoding, and valid Swing cursor mapping while retaining
  MCEF's raw CEF cursor-ID contract.

#### Off-screen input, IME, touch, and selection

- Split Swing/AWT input into dedicated JNI entry points while keeping the legacy MCEF GLFW DTO and
  source contract intact. The native bridge now translates key, character, repeat, modifier,
  keypad, location, mouse, precise wheel, page scroll, drag, and Unicode behavior per platform.
- Added protected `CefBrowser_N.sendAwtKeyEvent`, `sendAwtFocusEvent`, `sendAwtMouseEvent`, and
  `sendAwtMouseWheelEvent` hooks so component-backed subclasses can reuse the platform-correct
  translation without coupling headless MCEF paths to AWT listeners.
- Added the legacy-MCEF-facing `CefKeyEvent.KEY_REPEAT` action and current `EventFlags` values for
  AltGr, repeated keys, precision scrolling, and page scrolling.
- Fixed Chromium 151 physical-key delivery using Linux XKB hardware codes and Windows OEM scan
  codes. Corrected Windows wheel scaling so AWT precise, axis, sign, and page semantics survive the
  system wheel configuration.
- Added the complete OSR IME host API: `imeSetComposition`, `imeCommitText`,
  `imeFinishComposingText`, and `imeCancelComposition`, backed by immutable `CefRange`,
  `CefCompositionUnderline`, and `CefCompositionUnderlineStyle` values.
- Added `CefRenderHandler.onImeCompositionRangeChanged` with Java-owned range and character-bound
  snapshots.
- Added `CefRenderHandler.onTextSelectionChanged` with exact UTF-16 text and directional unsigned
  CEF ranges.
- Added immutable `CefTouchEvent`, `CefTouchEventType`, and `CefPointerType` plus
  `CefBrowser.sendTouchEvent`. The bridge validates the full CEF domain and supports every CEF
  pointer type, including touch, mouse, pen, eraser, and unknown. It preserves geometry, pressure,
  modifiers, and lifecycle rules, and compensates for the pinned CEF 151 rotation-unit mismatch at
  the native boundary. Hosts without detected touch hardware can opt into Chromium touch routing
  with `--touch-events=enabled`.
- Sequenced native OSR tests and synthetic input through paint, focus, renderer, and event
  acknowledgements so asynchronous input backpressure cannot silently drop events or satisfy a
  test with stale state.

#### Browser events and handlers

- Added `CefFindHandler`, `CefFindHandlerAdapter`, `CefClient.addFindHandler`, and exact find-result
  delivery with identifier, match count, selection rectangle, active match ordinal, and final
  update state.
- Added `CefDisplayHandler.onLoadingProgressChange` for overall page progress from 0.0 through 1.0.
- Added `CefDisplayHandler.onFaviconURLChange` with ordered, detached icon-URL snapshots.
- Added source-compatible custom-cursor overloads to both display and render handlers.
- Updated file-dialog delivery with folder selection plus CEF-provided MIME expansion and
  description lists through the complete eight-argument `CefDialogHandler.onFileDialog` callback.
- Added a complete `CefPermissionHandler` surface for media-access requests and general permission
  prompts, including `CefMediaAccessPermissionTypes`, `CefPermissionRequestTypes`,
  `CefPermissionRequestResult`, one-shot `CefMediaAccessCallback` and
  `CefPermissionPromptCallback` continuations, exact raw masks and 64-bit prompt IDs, prompt
  dismissal, browser-close invalidation, and runtime-shutdown invalidation.
- Completed the audio handler bridge with mutable/validated `CefAudioParameters`, current
  `CefChannelLayout` values, stream start/packet/stop/error callbacks, and bounded callback-scoped
  planar PCM access through `DataPointer`.
- Modernized download handling with explicit `onBeforeDownloadWithDecision`, pause/interruption
  state, complete interrupt reasons, raw reason fallback, and original URL reporting while
  preserving the legacy before-download callback.
- Added the CEF 151 `CefDownloadHandler.canDownload` interface and native callback; the 2026-07-27
  follow-up above completes forwarding through normal `CefClient.addDownloadHandler` registration.
- Updated `CefLoadHandler.ErrorCode` to the CEF 151 domain and added raw integer error delivery for
  forward compatibility. Navigation transitions now use immutable `CefRequest.Transition` values
  so source, qualifier, redirect, and future bits are preserved without mutating shared enum
  constants.

#### Requests, responses, resources, and contexts

- Added standalone URL-request creation with an explicit `CefRequestContext`, plus
  `CefFrame.createURLRequest` for a request associated with a browser/frame and its normal request
  handlers.
- Added `CefURLRequest.responseWasCached()` and exact raw request error codes.
- Upgraded URL-request progress to 64-bit counters with saturating legacy `int` callbacks, gave
  every request an independent native client, and made concurrent, reentrant, authentication,
  cancellation, completion, JNI-exception, and shutdown paths ownership-safe.
- URL-request creation synchronously marshals to CEF's UI thread, and a fast callback can arrive
  before the Java factory returns. Authentication callbacks run on CEF's IO thread; the other
  URL-request callbacks run on its UI thread.
- Added duplicate-preserving `CefHeader` lists to requests and responses, exact response error
  integers, response charset and resolved URL, current request flags, and forward-compatible raw
  transition values.
- Added `CefPostData.hasExcludedElements()` so callers can detect upload elements that Chromium
  intentionally omits from the exposed post-data collection.
- Added the modern parallel `CefResourceHandler` `open`, 64-bit `getResponseHeaders`, `read`, and
  `skip` API with `LongRef`, `CefResourceReadCallback`, `CefResourceSkipCallback`, and legacy
  fallbacks.
- Direct `CefResourceHandler` implementations still provide deprecated `processRequest` and
  `readResponse`, or extend `CefResourceHandlerAdapter`. The new methods use documented sentinel
  outputs to select the legacy fallbacks. An asynchronous `read` must fill
  `CefResourceReadCallback.getBuffer()` before invoking its one-shot `Continue` callback.
- Expanded `CefRequestContext` with configured and shared-context factories; identity and storage
  sharing checks; cache path and cookie manager access; context-specific scheme handlers;
  certificate, HTTP cache, and HTTP-auth clearing; connection closure; DNS/host resolution;
  website and content settings; setting observers; Chrome color scheme control; and the complete
  preference get/set API.
- Added `CefResolveCallback`, `CefResolveResult`, `CefContentSettingType`,
  `CefContentSettingValue`, `CefColorVariant`, `CefSettingObserver`, and closeable
  `CefRegistration` ownership.

#### Native value, command-line, menu, and metadata bridges

- Added native-backed `CefValue`, `CefBinaryValue`, `CefDictionaryValue`, and `CefListValue`
  containers plus `CefValueType`, with validity, ownership, equality/identity, copy, typed get/set,
  nested values, read-only state, explicit disposal, and Java/native conversion for
  request-context settings and preferences.
- Callers explicitly close or dispose command-line, request-context, and native-value wrappers, the
  original `CefRequest` passed to a `CefURLRequest`, and every `CefResponse` wrapper before
  `CefApp` shutdown. Dictionary/list complex setters use CEF transfer semantics: an unowned source
  is invalidated on success and an owned source is copied. `CefValue.setBinary`, `setDictionary`,
  and `setList` retain a reference without transferring ownership.
- Completed `CefCommandLine`: writable creation, global read-only access, copy/dispose,
  platform-specific initialization, argv/string conversion, reset, program access, switch maps,
  switch removal, arguments, and debugger-style wrapper prepending.
- Updated context-menu media/edit flags and metadata for CEF 151, including canvas, picture in
  picture, looping, rich editing, title text, and custom renderer menus.
- Expanded `CefMenuModel` with current command IDs, submenu detection, typed foreground/background
  colors, accelerator colors, and per-command/default font lists.
- Added current download metadata and interrupt reasons, current drag file paths, PDF
  accessibility-tagging and document-outline settings alongside the existing header/footer
  controls, and the Chrome policy identifier plus root-cache path/relaunch integration.

### Source and binary migration notes

The upgrade deliberately targets Java 17 and current CEF contracts. Consumers must compile and run
with JDK 17; Java 8 runtimes and the removed 32-bit Linux/Windows targets are no longer supported.
Recompile downstream code rather than reusing classes compiled against the CEF 116-era API,
especially where Java may have inlined changed numeric constants.

- Frame identifiers changed from `long` to `String`. `CefFrame.getIdentifier()` now returns a
  string; `CefBrowser.getFrameByIdentifier(String)` and `getFrameByName(String)` replace the two
  overloaded `getFrame` methods; and `getFrameIdentifiers()` now returns `Vector<String>`.
- The visual DevTools frontend API changed from `getDevTools([Point])`, which returned a
  `CefBrowser`, to `openDevTools([Point])`, `closeDevTools()`, and asynchronous `hasDevTools()`.
  `getDevToolsClient()` is a separate Chrome DevTools Protocol connection and is not a frontend
  browser.
- Direct `CefRequestHandler` implementations must accept the new raw error code and string in
  `onRenderProcessTerminated`; the termination-status enum also includes launch and integrity
  failures. Direct `CefAppHandler` implementations must add `onAlreadyRunningAppRelaunch`, and
  direct `CefRenderHandler` implementations must add the paint-listener methods. Their adapters
  provide defaults. Custom `CefClientHandler` subclasses must implement `getFindHandler()`.
- The initial CEF 151 synchronization changed `CefDialogHandler.onFileDialog` from six to eight
  arguments. The compatibility defaults above accept direct implementations of either
  signature and preserve all three parallel metadata vectors. The interface is no longer a
  functional interface because Java cannot represent both lambda arities; lambda users must use an
  explicit handler implementation.
- Removed obsolete `CefSettings.pack_loading_disabled`; CEF 151 no longer exposes that setting.
- Request-flag values changed: stored credentials moved from `2` to `8`, upload progress from `8`
  to `16`, no-download-data from `64` to `32`, and no-retry-on-5xx from `128` to `64`.
  Raw-header reporting (`32`) is now a deprecated zero-valued no-op. Only-from-cache (`2`),
  disable-cache (`4`), and stop-on-redirect (`128`) are new. Recompile all consumers and rebuild
  persisted or manually assembled masks because the old bits have been reassigned.
- `CefRequest.TransitionType.addQualifier`, `addQualifiers`, and `removeQualifier` now throw instead
  of mutating shared enum instances. Construct and inspect immutable `CefRequest.Transition`
  values instead.
- Context-menu constants shifted when paste-and-match-style was added: `MENU_ID_PASTE_MATCH_STYLE`
  is `115`, `MENU_ID_DELETE` moved from `115` to `116`, and `MENU_ID_SELECT_ALL` moved from `116`
  to `117`. Recompile consumers because Java inlines these constants.
- `CefDownloadItem.isCanceled()` now reports cancellation only. Test `isInterrupted()` separately
  when handling interrupted downloads.
- `DataPointer` and every channel view returned by `getData` during
  `CefAudioHandler.onAudioStreamPacket` are confined to the callback thread and become invalid as
  soon as the callback returns, including exceptional returns. Copy every sample that must outlive
  the callback while still inside it.
- Two deprecated context-menu media flags now preserve source compatibility only: bit `64`
  `CM_MEDIAFLAG_HAS_VIDEO` aliases `CM_MEDIAFLAG_CAN_TOGGLE_CONTROLS`, and bit `128`
  `CM_MEDIAFLAG_CONTROL_ROOT_ELEMENT` aliases `CM_MEDIAFLAG_CONTROLS`. The old names no longer
  represent their former concepts.

### MCEF compatibility retained and improved

- Preserved component-free/headless `CefBrowserOsr`, MCEF native-buffer rendering, GLFW input DTOs,
  raw cursor IDs, audio callbacks, transparency, and the externally driven CEF message pump through
  `CefApp.N_DoMessageLoopWork()`.
- Preserved `jcef.path` as MCEF's historical signal for automatically enabling the external message
  pump. Other embedders can override that inference with `-Djcef.external_message_pump=false`.
- Kept Swing/AWT-specific behavior in the Swing OSR implementation and dedicated AWT bridge so
  MCEF does not acquire a JOGL, Canvas, or AWT-event dependency.
- Made headless browser close complete without an AWT `Window` owner and serialized browser,
  client, AWT, CEF UI-thread, and application shutdown paths. Browser closure no longer depends on
  the removed `CefBrowser_N` finalizer fallback.
- Preserved hidden-browser geometry and deterministic resize/paint resumption for MCEF's direct OSR
  lifecycle.
- Kept distribution layouts directly usable as MCEF's `jcef.path`, without runtime symlink setup.
- Safely restored missing owner-execute permission on Linux/macOS CEF helper binaries extracted
  below `jcef.path`, while resolving and rejecting symbolic-link escapes before changing modes.
- Stripped debug-only ELF sections from staged Linux runtime copies so CEF 151 ARM64 remains within
  MCEF's extracted-size limit without modifying build inputs or runtime symbols.
- Updated the MCEF `26.2.0` `common/java-cef` submodule to the tested JCEF code commit
  `95e664ef615dd05d8eb2e8c0b131c1aae9e37b34`; every produced common, Fabric, and NeoForge JAR
  records that exact commit in its manifest.

### How to use 60/120+ FPS OSR

Windowless browsers now default to 60 FPS, including MCEF-style creation paths that pass no browser
settings. To request 120 FPS when creating an OSR browser:

```java
CefBrowserSettings browserSettings = new CefBrowserSettings();
browserSettings.windowless_frame_rate = 120;
CefBrowser browser = client.createBrowser(url, true, false, null, browserSettings);
```

To change an existing windowless browser at runtime:

```java
browser.setWindowlessFrameRate(120);
browser.getWindowlessFrameRate().thenAccept(frameRate -> System.out.println("Requested FPS: " + frameRate));
```

The runtime setter accepts values of 1 or greater; it is not capped at 60. A creation-time setting
of `0` is the explicit opt-in to CEF's 30 FPS fallback. The requested value is a maximum callback
rate, not a guarantee: actual painting still depends on Chromium producing frames, host load, and
how often the message loop is serviced. MCEF drives the external message pump itself and must keep
doing so frequently enough for the requested rate. Apply runtime changes after
`CefLifeSpanHandler.onAfterCreated`; an MCEF `CefBrowserOsr` subclass can instead pass the same
settings object to its five-argument constructor before `createImmediately()`. For any unattached,
component-free browser returned by `createBrowser`, register its handlers and then call
`createImmediately()`. The detailed sample also accepts `--windowless-frame-rate-120` when OSR is
enabled.

### How to mute a browser

Mute and unmute only the selected CEF browser/tab:

```java
browser.setAudioMuted(true);
browser.isAudioMuted().thenAccept(muted -> System.out.println("Muted: " + muted));
browser.setAudioMuted(false);
```

`isAudioMuted()` and the other asynchronous browser-host queries complete through the CEF UI
thread. Code that owns MCEF's external message pump must not block that pump with `join()` or
`get()` while waiting for the result; attach a continuation instead. Set the initial mute state
after `CefLifeSpanHandler.onAfterCreated`, when the native browser host exists.

### Lifecycle, correctness, and platform fixes

- Added the terminal `CefApp.CefAppState.INITIALIZATION_FAILED` state so initialization failure is
  observable and separate from normal termination.
- Reworked CEF application initialization, retry, state notification, client disposal, browser
  creation, and shutdown ownership. Failed pre-initialization can be retried safely; terminal
  cleanup is exact-once; callbacks unwind before references are released; and teardown cannot
  strand `OnBeforeClose` behind AWT destruction.
- Preserved full Java UTF-16/CEF UTF-8 content, including supplementary characters and embedded NUL
  code units, instead of passing through lossy JNI modified UTF-8.
- Added lifecycle gates and retained native ownership to browser-host queries, URL requests,
  permission callbacks, DevTools registrations, paint buffers, custom cursors, frames, and native
  values.
- Corrected download-handler removal typing, request authentication realm/scheme forwarding,
  request/response raw enum preservation, drag-data ownership, and frame-operation validity.
- Fixed hidden OSR resize resumption, renderer focus propagation, physical keyboard delivery, and
  platform mouse/wheel behavior in production.
- Stabilized deterministic native integration-test sequencing for custom cursors, selection focus
  transfer, touch dispatch, paint, renderer readiness, and asynchronous input acknowledgements.
- Hardened Windows ARM64 native tests against runner credential/telemetry failures without changing
  production WebAuthn behavior; preserved JUnit argument quoting and JNI verification on Windows.
- Added bounded Windows AWT shutdown coordination, crash-report detection, and native callback
  thread-role probes. Linux, macOS, Windows, amd64, and ARM64 paths all retain platform-specific
  ownership and message-loop requirements.
- Made source-contract tests insensitive to CRLF/LF checkout differences, fixed JNI long-constant
  portability, promoted native warnings to actionable failures, and made Java 17 Javadoc warnings
  fail the documentation build.

### Build, distribution, CI, and publication

- Added deterministic distributions for `linux_amd64`, `linux_arm64`, `macos_amd64`,
  `macos_arm64`, `windows_amd64`, and `windows_arm64`, each containing exact Java 17 bytecode,
  architecture-correct native files and supported JogAmp artifacts, a provenance manifest, a
  reproducible archive, and a SHA-256 sidecar.
- Added strict archive validation for the exact CEF/runtime inventory, target architecture, native
  binary architecture, Java class-file version, safe paths, duplicate members, member count/size
  limits, extracted size, executable modes, and source commit identity.
- Rebuilt GitHub Actions around six native target jobs that compile Java, verify JNI headers, build
  native CEF/JCEF, run native-independent and native-backed tests, package, checksum, and upload raw
  artifacts. Actions and external tools are pinned and platform/toolchain contracts are tested.
- Pinned architecture-matched Python 3.12.10 for CMake/storage tooling, made Windows Apache Ant
  downloads retry verified mirrors with a pinned SHA-512, and retained AppVeyor as an independent
  Windows x86_64 build.
- Kept the build workflow read-only and moved release creation to an authenticated maintainer tool
  that accepts only a fresh `origin/master` checkout and an exact successful six-platform workflow
  run.
- Added fail-closed, recoverable immutable-release publication: exact workflow/job/artifact
  identity, twelve canonical assets, GitHub size and digest verification, archive/sidecar matching,
  release-author ownership, full-SHA tag targeting, credential/startup isolation, REST-ID recovery,
  visibility convergence, interruption handling, and byte-exact idempotency.
- Managed releases are explicitly created and published with `make_latest=true`, and publication
  verifies that the target becomes GitHub Latest even when it is the repository's sole release.
- The tested native code was published as the immutable Latest release
  `java-cef-95e664ef615dd05d8eb2e8c0b131c1aae9e37b34` with all six archives and six checksum
  sidecars. The following `0224f39` commit only corrected publication documentation.
