// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefAppHandler;
import org.cef.handler.CefAppHandlerAdapter;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Exposes static methods for managing the global CEF context.
 */
public class CefApp extends CefAppHandlerAdapter {
    private static final long SHUTDOWN_WAIT_SECONDS = 30;
    private static final long SHUTDOWN_PUMP_DELAY_MILLIS = 10;

    public final class CefVersion {
        public final int JCEF_COMMIT_NUMBER;

        public final int CEF_VERSION_MAJOR;
        public final int CEF_VERSION_MINOR;
        public final int CEF_VERSION_PATCH;
        public final int CEF_COMMIT_NUMBER;

        public final int CHROME_VERSION_MAJOR;
        public final int CHROME_VERSION_MINOR;
        public final int CHROME_VERSION_BUILD;
        public final int CHROME_VERSION_PATCH;

        private CefVersion(int jcefCommitNo, int cefMajor, int cefMinor, int cefPatch,
                int cefCommitNo, int chrMajor, int chrMin, int chrBuild, int chrPatch) {
            JCEF_COMMIT_NUMBER = jcefCommitNo;

            CEF_VERSION_MAJOR = cefMajor;
            CEF_VERSION_MINOR = cefMinor;
            CEF_VERSION_PATCH = cefPatch;
            CEF_COMMIT_NUMBER = cefCommitNo;

            CHROME_VERSION_MAJOR = chrMajor;
            CHROME_VERSION_MINOR = chrMin;
            CHROME_VERSION_BUILD = chrBuild;
            CHROME_VERSION_PATCH = chrPatch;
        }

        public String getJcefVersion() {
            return CEF_VERSION_MAJOR + "." + CEF_VERSION_MINOR + "." + CEF_VERSION_PATCH + "."
                    + JCEF_COMMIT_NUMBER;
        }

        public String getCefVersion() {
            return CEF_VERSION_MAJOR + "." + CEF_VERSION_MINOR + "." + CEF_VERSION_PATCH;
        }

        public String getChromeVersion() {
            return CHROME_VERSION_MAJOR + "." + CHROME_VERSION_MINOR + "." + CHROME_VERSION_BUILD
                    + "." + CHROME_VERSION_PATCH;
        }

        @Override
        public String toString() {
            return "JCEF Version = " + getJcefVersion() + "\n"
                    + "CEF Version = " + getCefVersion() + "\n"
                    + "Chromium Version = " + getChromeVersion();
        }
    }

    /**
     * The CefAppState gives you a hint if the CefApp is already usable or not
     * usable any more. See values for details.
     */
    public enum CefAppState {
        /**
         * No CefApp instance was created yet. Call getInstance() to create a new
         * one.
         */
        NONE,

        /**
         * CefApp is new created but not initialized yet. No CefClient and no
         * CefBrowser was created until now.
         */
        NEW,

        /**
         * CefApp is in its initializing process. Please wait until initializing is
         * finished.
         */
        INITIALIZING,

        /**
         * CefApp is up and running. At least one CefClient was created and the
         * message loop is running. You can use all classes and methods of JCEF now.
         */
        INITIALIZED,

        /**
         * CEF initialization has failed (for example due to a second process using
         * the same root_cache_path).
         */
        INITIALIZATION_FAILED,

        /**
         * CefApp is in its shutdown process. All CefClients and CefBrowser
         * instances will be disposed. No new CefClient or CefBrowser is allowed to
         * be created. The message loop will be performed until all CefClients and
         * all CefBrowsers are disposed completely.
         */
        SHUTTING_DOWN,

        /**
         * CefApp is terminated and can't be used any more. You can shutdown the
         * application safely now.
         */
        TERMINATED
    }

    /**
     * According the singleton pattern, this attribute keeps
     * one single object of this class.
     */
    private static volatile CefApp self = null;
    private static CefAppHandler appHandler_ = null;
    private static final Object stateLock_ = new Object();
    private static CefAppState state_ = CefAppState.NONE;
    private static boolean startupSucceeded_ = false;
    private static boolean startupRetryRequired_ = false;
    private static String[] startupArgs_ = null;
    private static long stateNotificationSequence_ = 0;
    private final Path configuredJcefPath_;
    private final boolean configuredLibraryPath_;
    private final boolean externallyDrivenMessagePump_;
    private final boolean directLifecycleThread_;
    private final CefLifecycleExecutor lifecycleExecutor_;
    private final CountDownLatch terminationComplete_ = new CountDownLatch(1);
    private volatile Timer workTimer_ = null;
    private HashSet<CefClient> clients_ = new HashSet<CefClient>();
    private CefSettings settings_ = null;
    private boolean nativeContextActive_ = false;
    private boolean nativeInitialized_ = false;
    private volatile boolean nativeTerminationStarted_ = false;

    /**
     * To get an instance of this class, use the method
     * getInstance() instead of this CTOR.
     *
     * The CTOR is called by getInstance() as needed and
     * loads all required JCEF libraries.
     *
     * @throws UnsatisfiedLinkError
     */
    private CefApp(String[] args, CefSettings settings) throws UnsatisfiedLinkError {
        super(args);
        configuredJcefPath_ = getConfiguredJcefPath();
        configuredLibraryPath_ = configuredJcefPath_ != null;
        externallyDrivenMessagePump_ = usesExternallyDrivenMessagePump(configuredLibraryPath_);
        directLifecycleThread_ = usesDirectLifecycleThread(externallyDrivenMessagePump_, OS.isMacintosh());
        if (settings != null) settings_ = settings.clone();

        if (startupRetryRequired_) {
            startupSucceeded_ = startupImpl(startupArgs_ != null ? startupArgs_ : args);
            startupRetryRequired_ = !startupSucceeded_;
            if (!startupSucceeded_)
                throw new IllegalStateException("Failed to restart native CEF startup after pre-initialization abort");
        }

        if (!configuredLibraryPath_) {
            if (OS.isWindows()) {
                SystemBootstrap.loadLibrary("jawt");
                SystemBootstrap.loadLibrary("chrome_elf");
                SystemBootstrap.loadLibrary("libcef");
                SystemBootstrap.loadLibrary("jcef");
            } else if (OS.isLinux()) {
                SystemBootstrap.loadLibrary("cef");
            }
        }
        lifecycleExecutor_ = usesDedicatedLifecycleThread(externallyDrivenMessagePump_, OS.isMacintosh())
                ? new CefLifecycleExecutor("JCEF-Lifecycle")
                : null;

        try {
            if (!callOnLifecycleThread(this::N_PreInitialize))
                throw new IllegalStateException("Failed to pre-initialize native code");
            nativeContextActive_ = true;
        } catch (RuntimeException | Error error) {
            try {
                runOnLifecycleThread(this::N_AbortInitialization);
            } catch (RuntimeException | Error abortError) {
                error.addSuppressed(abortError);
            } finally {
                if (lifecycleExecutor_ != null) lifecycleExecutor_.close();
                if (OS.isMacintosh()) {
                    // Abort unloads the dynamically loaded CEF framework. A constructor retry must
                    // repeat N_Startup even though the original startup() call had succeeded.
                    startupSucceeded_ = false;
                    startupRetryRequired_ = true;
                }
            }
            throw error;
        }

        // Publish this instance as the default handler only after native pre-initialization has
        // succeeded. A failed constructor must not leave callbacks targeting a poisoned instance.
        if (appHandler_ == null) appHandler_ = this;
    }

    /**
     * Assign an AppHandler to CefApp. The AppHandler can be used to evaluate
     * application arguments, to register your own schemes and to hook into the
     * shutdown sequence. See CefAppHandler for more details.
     *
     * This method must be called before CefApp is initialized. CefApp will be
     * initialized automatically if you call createClient() the first time.
     * @param appHandler An instance of CefAppHandler.
     *
     * @throws IllegalStateException in case of CefApp is already initialized
     */
    public static void addAppHandler(CefAppHandler appHandler) throws IllegalStateException {
        synchronized (stateLock_) {
            if (state_.compareTo(CefAppState.NEW) > 0)
                throw new IllegalStateException("Must be called before CefApp is initialized");
            appHandler_ = appHandler;
        }
    }

    /**
     * Get an instance of this class.
     * @return an instance of this class
     * @throws UnsatisfiedLinkError
     */
    public static synchronized CefApp getInstance() throws UnsatisfiedLinkError {
        return getInstance(null, null);
    }

    public static synchronized CefApp getInstance(String[] args) throws UnsatisfiedLinkError {
        return getInstance(args, null);
    }

    public static synchronized CefApp getInstance(CefSettings settings)
            throws UnsatisfiedLinkError {
        return getInstance(null, settings);
    }

    public static synchronized CefApp getInstance(String[] args, CefSettings settings)
            throws UnsatisfiedLinkError {
        if (getState() == CefAppState.TERMINATED)
            throw new IllegalStateException("CefApp was terminated");
        if (settings != null) {
            if (getState() != CefAppState.NONE && getState() != CefAppState.NEW)
                throw new IllegalStateException("Settings can only be passed to CEF"
                        + " before createClient is called the first time.");
        }
        if (self == null) {
            self = new CefApp(args, settings);
            setState(CefAppState.NEW);
        }
        return self;
    }

    public final void setSettings(CefSettings settings) throws IllegalStateException {
        if (getState() != CefAppState.NONE && getState() != CefAppState.NEW)
            throw new IllegalStateException("Settings can only be passed to CEF"
                    + " before createClient is called the first time.");
        settings_ = settings.clone();
    }

    public final CefVersion getVersion() {
        try {
            return N_GetVersion();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    /**
     * Returns the current state of CefApp.
     * @return current state.
     */
    public final static CefAppState getState() {
        synchronized (stateLock_) {
            return state_;
        }
    }

    private static final void setState(final CefAppState state) {
        synchronized (stateLock_) {
            state_ = state;
            enqueueStateNotificationLocked(state, appHandler_);
        }
    }

    private static long enqueueStateNotificationLocked(final CefAppState state, CefAppHandler handler) {
        // invokeLater never executes inline. Holding stateLock_ through this enqueue fixes the
        // callback order while keeping arbitrary application code outside the lock.
        long sequence = ++stateNotificationSequence_;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (handler != null) handler.stateHasChanged(state);
            }
        });
        return sequence;
    }

    private static long enqueueStateNotificationForTesting(CefAppState state, CefAppHandler handler) {
        synchronized (stateLock_) {
            return enqueueStateNotificationLocked(state, handler);
        }
    }

    /**
     * To shutdown the system, it's important to call the dispose
     * method. Calling this method closes all client instances with
     * and all browser instances each client owns. After that the
     * message loop is terminated and CEF is shutdown.
     */
    public final void dispose() {
        HashSet<CefClient> clientsToDispose = null;
        boolean abortInitialization = false;
        boolean dispatchShutdown = false;
        boolean waitForTermination = false;
        synchronized (this) {
            switch (getState()) {
                case NEW:
                case INITIALIZATION_FAILED:
                    if (nativeTerminationStarted_) return;
                    nativeTerminationStarted_ = true;
                    abortInitialization = true;
                    setState(CefAppState.SHUTTING_DOWN);
                    break;

                case INITIALIZING:
                case INITIALIZED:
                    setState(CefAppState.SHUTTING_DOWN);
                    if (clients_.isEmpty()) {
                        nativeTerminationStarted_ = true;
                        dispatchShutdown = true;
                    } else {
                        // Client disposal may synchronously re-enter clientWasDisposed(). Never
                        // hold the CefApp monitor while invoking it or while waiting on the owner
                        // thread.
                        clientsToDispose = new HashSet<CefClient>(clients_);
                    }
                    break;

                case SHUTTING_DOWN:
                    break;

                case NONE:
                case TERMINATED:
                    return;
            }
            waitForTermination = lifecycleExecutor_ != null && !lifecycleExecutor_.isOwnerThread();
        }

        Throwable failure = null;
        if (abortInitialization) {
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::abortNativeInitialization);
        } else {
            if (clientsToDispose != null) {
                for (CefClient client : clientsToDispose)
                    failure = CefLifecycleExecutor.runAndCollectFailure(failure, client::dispose);
                failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::startShutdownMessagePump);
            }
            if (dispatchShutdown)
                failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::dispatchNativeShutdown);
        }

        if (waitForTermination)
            failure = CefLifecycleExecutor.runAndCollectFailure(failure, this::awaitTermination);
        CefLifecycleExecutor.rethrowFailure(failure);
    }

    /**
     * Creates a new client instance and returns it to the caller.
     * One client instance is responsible for one to many browser
     * instances
     * @return a new client instance
     */
    public synchronized CefClient createClient() {
        if (getState() == CefAppState.NEW) {
            setState(CefAppState.INITIALIZING);
            initialize();
        }

        CefAppState state = getState();
        if (state != CefAppState.INITIALIZED)
            throw new IllegalStateException("Can't create client in state " + state);

        CefClient client = new CefClient(this);
        clients_.add(client);
        return client;
    }

    /**
     * Register a scheme handler factory for the specified |scheme_name| and
     * optional |domain_name|. An empty |domain_name| value for a standard scheme
     * will cause the factory to match all domain names. The |domain_name| value
     * will be ignored for non-standard schemes. If |scheme_name| is a built-in
     * scheme and no handler is returned by |factory| then the built-in scheme
     * handler factory will be called. If |scheme_name| is a custom scheme then
     * also implement the CefApp::OnRegisterCustomSchemes() method in all
     * processes. This function may be called multiple times to change or remove
     * the factory that matches the specified |scheme_name| and optional
     * |domain_name|. Returns false if an error occurs. This function may be
     * called on any thread in the browser process.
     */
    public boolean registerSchemeHandlerFactory(
            String schemeName, String domainName, CefSchemeHandlerFactory factory) {
        try {
            return N_RegisterSchemeHandlerFactory(schemeName, domainName, factory);
        } catch (Exception err) {
            err.printStackTrace();
        }
        return false;
    }

    /**
     * Clear all registered scheme handler factories. Returns false on error. This
     * function may be called on any thread in the browser process.
     */
    public boolean clearSchemeHandlerFactories() {
        try {
            return N_ClearSchemeHandlerFactories();
        } catch (Exception err) {
            err.printStackTrace();
        }
        return false;
    }

    /**
     * This method is called by a CefClient if it was disposed. This causes
     * CefApp to clean up its list of available client instances. If all clients
     * are disposed, CefApp will be shutdown.
     * @param client the disposed client.
     */
    protected final void clientWasDisposed(CefClient client) {
        boolean dispatchShutdown = false;
        synchronized (this) {
            clients_.remove(client);
            if (clients_.isEmpty() && getState() == CefAppState.SHUTTING_DOWN
                    && !nativeTerminationStarted_) {
                nativeTerminationStarted_ = true;
                dispatchShutdown = true;
            }
        }
        // This callback can arrive inside CEF's OnBeforeClose stack. Queue shutdown so CefShutdown
        // cannot wait for the UI callback that would otherwise be waiting for shutdown itself.
        if (dispatchShutdown) dispatchNativeShutdown();
    }

    /**
     * Initialize the context.
     * @return true on success.
     */
    private final void initialize() {
        try {
            boolean initialized = callOnLifecycleThread(() -> {
                String jcefPath = configuredJcefPath_ == null ? getJcefLibPath()
                                                              : getJcefLibPath(configuredJcefPath_);
                System.out.println("initialize on " + Thread.currentThread() + " with library path "
                        + jcefPath);
                CefSettings settings = settings_ != null ? settings_ : new CefSettings();
                setDefaultSettingsPaths(settings, Paths.get(jcefPath));
                return N_Initialize(appHandler_, settings);
            });
            if (initialized) {
                nativeInitialized_ = true;
                setState(CefAppState.INITIALIZED);
            } else {
                abortNativeInitializationAfterFailure();
                setState(CefAppState.INITIALIZATION_FAILED);
            }
        } catch (RuntimeException | Error error) {
            abortNativeInitializationAfterFailure();
            setState(CefAppState.INITIALIZATION_FAILED);
            throw error;
        }
    }

    private void setDefaultSettingsPaths(CefSettings settings, Path jcefPath) {
        if (OS.isWindows()) {
            if (settings.browser_subprocess_path == null)
                settings.browser_subprocess_path =
                        jcefPath.resolve("jcef_helper.exe").normalize().toAbsolutePath().toString();
        } else if (OS.isMacintosh()) {
            if (configuredLibraryPath_) {
                Path bundlePath =
                        configuredJcefPath_.resolve("jcef_app.app").normalize().toAbsolutePath();
                Path frameworksPath = bundlePath.resolve("Contents/Frameworks");
                Path cefFrameworkPath =
                        frameworksPath.resolve("Chromium Embedded Framework.framework");
                if (settings.main_bundle_path == null)
                    settings.main_bundle_path = bundlePath.toString();
                if (settings.framework_dir_path == null)
                    settings.framework_dir_path = cefFrameworkPath.toString();
                if (settings.locales_dir_path == null)
                    settings.locales_dir_path = cefFrameworkPath.resolve("Resources").toString();
                if (settings.resources_dir_path == null)
                    settings.resources_dir_path = cefFrameworkPath.resolve("Resources").toString();
                if (settings.browser_subprocess_path == null)
                    settings.browser_subprocess_path =
                            frameworksPath.resolve("jcef Helper.app/Contents/MacOS/jcef Helper")
                                    .toString();
            } else if (settings.browser_subprocess_path == null) {
                settings.browser_subprocess_path =
                        jcefPath.resolve("../Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper")
                                .normalize()
                                .toAbsolutePath()
                                .toString();
            }
        } else if (OS.isLinux()) {
            if (settings.resources_dir_path == null)
                settings.resources_dir_path = jcefPath.normalize().toAbsolutePath().toString();
            if (settings.browser_subprocess_path == null)
                settings.browser_subprocess_path =
                        jcefPath.resolve("jcef_helper").normalize().toAbsolutePath().toString();
            if (settings.locales_dir_path == null)
                settings.locales_dir_path =
                        jcefPath.resolve("locales").normalize().toAbsolutePath().toString();
        }
    }

    /**
     * CEF takes full control of Cmd+Q and doesn't allow our application to see that it has been
     * pressed. This allows us to run our application's shutdown code, so we can have a graceful
     * "Cmd+Q" exit.
     */
    public Runnable macOSTerminationRequestRunnable = new Runnable() {
        @Override
        public void run() {}
    };

    /**
     * This method is invoked by the native code (currently on Mac only) in case
     * of a termination event (e.g. someone pressed CMD+Q).
     */
    protected final void handleBeforeTerminate() {
        if (externallyDrivenMessagePump_) {
            macOSTerminationRequestRunnable.run();
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                CefAppHandler handler = appHandler_ == null ? CefApp.this : appHandler_;
                if (!handler.onBeforeTerminate()) dispose();
            }
        });
    }

    /**
     * Shut down the context.
     */
    private void dispatchNativeShutdown() {
        Runnable shutdown = this::shutdownNativeContext;
        if (lifecycleExecutor_ != null) {
            lifecycleExecutor_.execute(shutdown);
        } else if (directLifecycleThread_) {
            // On macOS N_Shutdown synchronously marshals to AppKit main. Queueing first through the
            // EDT can deadlock when AppKit main is waiting for an AWT callback. A detached,
            // non-daemon worker also escapes the CEF OnBeforeClose callback stack while keeping
            // the JVM alive until the native shutdown path reaches finishTermination().
            CefLifecycleExecutor.executeDetached("JCEF-Mac-Shutdown", shutdown);
        } else {
            SwingUtilities.invokeLater(shutdown);
        }
    }

    /**
     * Perform a single message loop iteration. Used on all platforms except
     * Windows with windowed rendering.
     */
    public final void doMessageLoopWork(final long delay_ms) {
        if (externallyDrivenMessagePump_) return;
        scheduleAwtMessageLoopWork(delay_ms);
    }

    private void scheduleAwtMessageLoopWork(final long delay_ms) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!canDoMessageLoopWork()) {
                    stopWorkTimer();
                    return;
                }

                final long maxTimerDelay = 1000 / 30;
                if (workTimer_ != null) {
                    workTimer_.stop();
                    workTimer_ = null;
                }

                if (delay_ms <= 0) {
                    N_DoMessageLoopWork();
                    scheduleAwtMessageLoopWork(maxTimerDelay);
                    return;
                }

                long timerDelay = Math.min(delay_ms, maxTimerDelay);
                workTimer_ = new Timer((int) timerDelay, new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        Timer firedTimer = (Timer) event.getSource();
                        firedTimer.stop();
                        if (workTimer_ == firedTimer) workTimer_ = null;
                        if (!canDoMessageLoopWork()) return;
                        N_DoMessageLoopWork();
                        scheduleAwtMessageLoopWork(maxTimerDelay);
                    }
                });
                workTimer_.start();
            }
        });
    }

    private static boolean canDoMessageLoopWork() {
        CefAppState state = getState();
        CefApp app = self;
        return state == CefAppState.INITIALIZING || state == CefAppState.INITIALIZED
                || (state == CefAppState.SHUTTING_DOWN && app != null
                        && !app.nativeTerminationStarted_);
    }

    private void stopWorkTimer() {
        if (workTimer_ == null) return;
        workTimer_.stop();
        workTimer_ = null;
    }

    /**
     * This method must be called at the beginning of the main() method to perform platform-
     * specific startup initialization. On Linux this initializes Xlib multithreading and on
     * macOS this dynamically loads the CEF framework.
     * @param args Command-line arguments massed to main().
     * @return True on successful startup.
     */
    public static final synchronized boolean startup(String[] args) {
        // startup() is intentionally independent of CefAppState. Startup must happen before the
        // singleton exists, and using CefAppState as the sentinel allows two callers (or two test
        // classes initialized in a different order) to create the native Context twice.
        if (startupSucceeded_) return true;

        startupSucceeded_ = startupImpl(args);
        if (startupSucceeded_) startupArgs_ = args == null ? null : args.clone();
        return startupSucceeded_;
    }

    private static boolean startupImpl(String[] args) {
        Path configuredPath = getConfiguredJcefPath();
        if (configuredPath != null) {
            CefHelperExecutableSupport.prepare(configuredPath, OS.isMacintosh(), OS.isLinux());
            Path jcefPath = Paths.get(getJcefLibPath(configuredPath));
            if (OS.isWindows()) {
                SystemBootstrap.loadLibrary("jawt");
                System.load(jcefPath.resolve("d3dcompiler_47.dll").toString());
                System.load(jcefPath.resolve("libGLESv2.dll").toString());
                System.load(jcefPath.resolve("libEGL.dll").toString());
                System.load(jcefPath.resolve("chrome_elf.dll").toString());
                System.load(jcefPath.resolve("libcef.dll").toString());
                System.load(jcefPath.resolve("jcef.dll").toString());
                return true;
            } else if (OS.isMacintosh()) {
                System.load(jcefPath.resolve("libjcef.dylib").toString());
                return N_Startup(getCefFrameworkPath(args, jcefPath));
            } else if (OS.isLinux()) {
                System.load(jcefPath.resolve("libcef.so").toString());
                System.load(jcefPath.resolve("libjcef.so").toString());
                return N_Startup(null);
            }
            return false;
        }

        if (OS.isLinux() || OS.isMacintosh()) {
            SystemBootstrap.loadLibrary("jcef");
            return N_Startup(OS.isMacintosh() ? getCefFrameworkPath(args) : null);
        }
        return true;
    }

    /**
     * Get the path which contains the jcef library
     * @return The path to the jcef library
     */
    private static final String getJcefLibPath() {
        Path configuredPath = getConfiguredJcefPath();
        if (configuredPath != null) return getJcefLibPath(configuredPath);

        String libraryPath = System.getProperty("java.library.path", "");
        String separator = System.getProperty("path.separator", File.pathSeparator);
        Path firstPath = null;
        for (String entry : libraryPath.split(java.util.regex.Pattern.quote(separator))) {
            if (entry == null || entry.trim().isEmpty()) continue;
            Path path = Paths.get(entry).normalize().toAbsolutePath();
            if (firstPath == null) firstPath = path;
            if (Files.isRegularFile(path.resolve("libjcef.dylib"))
                    || Files.isRegularFile(path.resolve("libjcef.so"))
                    || Files.isRegularFile(path.resolve("jcef.dll")))
                return path.toString();
        }
        return (firstPath != null ? firstPath : Paths.get(".").toAbsolutePath().normalize())
                .toString();
    }

    private static String getJcefLibPath(Path configuredPath) {
        Path jcefPath = OS.isMacintosh() ? configuredPath.resolve("jcef_app.app/Contents/Java")
                                         : configuredPath;
        return jcefPath.normalize().toAbsolutePath().toString();
    }

    /**
     * A configured jcef.path is the historical MCEF signal for application-owned initialization
     * and message pumping. Generic embedders that use an absolute bundle can opt back into the
     * upstream AWT-owned behavior with -Djcef.external_message_pump=false.
     */
    private static boolean usesExternallyDrivenMessagePump(boolean configuredLibraryPath) {
        String override = System.getProperty("jcef.external_message_pump");
        return override == null ? configuredLibraryPath : Boolean.parseBoolean(override);
    }

    private static boolean usesDirectLifecycleThread(boolean externallyDrivenMessagePump, boolean macintosh) {
        // util_mac synchronously marshals CEF/AppKit operations to NSThread.mainThread. Routing the
        // JNI call through Swing first would deadlock when -XstartOnFirstThread's main thread is
        // waiting for the EDT, while the EDT is waiting for that same AppKit main thread.
        return macintosh;
    }

    private static boolean usesDedicatedLifecycleThread(boolean externallyDrivenMessagePump, boolean macintosh) {
        return externallyDrivenMessagePump && !macintosh;
    }

    private static Path getConfiguredJcefPath() {
        String configuredPath = System.getProperty("jcef.path");
        if (configuredPath == null || configuredPath.trim().isEmpty()) return null;
        return Paths.get(configuredPath).normalize().toAbsolutePath();
    }

    /**
     * Get the path that contains the CEF Framework on macOS.
     * @return The path to the CEF Framework.
     */
    private static final String getCefFrameworkPath(String[] args) {
        return getCefFrameworkPath(args, Paths.get(getJcefLibPath()));
    }

    private static String getCefFrameworkPath(String[] args, Path jcefLibraryPath) {
        // Check for the path on the command-line.
        String switchPrefix = "--framework-dir-path=";
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith(switchPrefix)) {
                    return new File(arg.substring(switchPrefix.length())).getAbsolutePath();
                }
            }
        }

        // Determine the path relative to the JCEF lib location in the app bundle.
        return jcefLibraryPath.resolve("../Frameworks/Chromium Embedded Framework.framework")
                .normalize()
                .toAbsolutePath()
                .toString();
    }

    private static void runOnEventDispatchThread(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the AWT event dispatch thread", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new IllegalStateException("AWT event dispatch operation failed", cause);
        }
    }

    private boolean callOnLifecycleThread(BooleanSupplier operation) {
        if (lifecycleExecutor_ != null) return lifecycleExecutor_.call(operation::getAsBoolean);
        if (directLifecycleThread_) return operation.getAsBoolean();
        final boolean[] result = new boolean[1];
        runOnEventDispatchThread(() -> result[0] = operation.getAsBoolean());
        return result[0];
    }

    private void runOnLifecycleThread(Runnable operation) {
        if (lifecycleExecutor_ != null) {
            lifecycleExecutor_.call(() -> {
                operation.run();
                return null;
            });
        } else if (directLifecycleThread_) {
            operation.run();
        } else {
            runOnEventDispatchThread(operation);
        }
    }

    private void abortNativeInitializationAfterFailure() {
        synchronized (this) {
            if (!nativeContextActive_) return;
            nativeContextActive_ = false;
        }
        try {
            runOnLifecycleThread(this::N_AbortInitialization);
        } finally {
            if (lifecycleExecutor_ != null) lifecycleExecutor_.close();
        }
    }

    private void abortNativeInitialization() {
        try {
            abortNativeInitializationAfterFailure();
        } finally {
            finishTermination();
        }
    }

    private void shutdownNativeContext() {
        try {
            if (directLifecycleThread_ && !SwingUtilities.isEventDispatchThread())
                SwingUtilities.invokeLater(this::stopWorkTimer);
            else
                stopWorkTimer();
            boolean shouldShutdown;
            synchronized (this) {
                shouldShutdown = nativeContextActive_ && nativeInitialized_;
                nativeContextActive_ = false;
                nativeInitialized_ = false;
            }
            if (shouldShutdown)
                N_Shutdown();
            else
                N_AbortInitialization();
        } finally {
            if (lifecycleExecutor_ != null) lifecycleExecutor_.close();
            finishTermination();
        }
    }

    private void finishTermination() {
        setState(CefAppState.TERMINATED);
        CefApp.self = null;
        appHandler_ = null;
        terminationComplete_.countDown();
    }

    private void startShutdownMessagePump() {
        if (!canDoMessageLoopWork()) return;
        if (lifecycleExecutor_ != null) {
            lifecycleExecutor_.execute(this::runShutdownMessagePump);
        } else {
            scheduleAwtMessageLoopWork(0);
        }
    }

    private void runShutdownMessagePump() {
        if (!canDoMessageLoopWork()) return;
        N_DoMessageLoopWorkNative();
        if (canDoMessageLoopWork())
            lifecycleExecutor_.schedule(this::runShutdownMessagePump, SHUTDOWN_PUMP_DELAY_MILLIS);
    }

    private void awaitTermination() {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_WAIT_SECONDS);
        try {
            while (terminationComplete_.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    System.err.println("Timed out waiting for CEF shutdown after "
                            + SHUTDOWN_WAIT_SECONDS + " seconds");
                    return;
                }
                try {
                    if (!terminationComplete_.await(remaining, TimeUnit.NANOSECONDS)) {
                        System.err.println("Timed out waiting for CEF shutdown after "
                                + SHUTDOWN_WAIT_SECONDS + " seconds");
                        return;
                    }
                } catch (InterruptedException exception) {
                    // Keep the only native shutdown route alive until the same bounded deadline.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Routes the MCEF-facing pump entry point to the native context's owning application thread.
     */
    public final void N_DoMessageLoopWork() {
        if (!canDoMessageLoopWork()) return;
        if (lifecycleExecutor_ != null) {
            if (lifecycleExecutor_.isOwnerThread())
                N_DoMessageLoopWorkNative();
            else
                lifecycleExecutor_.executeCoalescedPump(this::N_DoMessageLoopWorkNative);
        } else if (directLifecycleThread_ || SwingUtilities.isEventDispatchThread()) {
            N_DoMessageLoopWorkNative();
        } else {
            SwingUtilities.invokeLater(this::N_DoMessageLoopWorkNative);
        }
    }

    private final static native boolean N_Startup(String pathToCefFramework);
    private final native boolean N_PreInitialize();
    private final native boolean N_Initialize(CefAppHandler appHandler, CefSettings settings);
    private final native void N_AbortInitialization();
    private final native void N_Shutdown();
    private final native void N_DoMessageLoopWorkNative();
    private final native CefVersion N_GetVersion();
    private static native int N_GetLogSeverityForTesting(CefSettings settings);
    private final native boolean N_RegisterSchemeHandlerFactory(String schemeName, String domainName, CefSchemeHandlerFactory factory);
    private final native boolean N_ClearSchemeHandlerFactories();
}
