// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCommandLine;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.callback.CefNativeAdapter;
import org.cef.callback.CefPermissionPromptCallback;
import org.cef.handler.CefClientHandler;
import org.cef.handler.CefMediaAccessPermissionTypes;
import org.cef.handler.CefPermissionHandler;
import org.cef.handler.CefPermissionHandlerAdapter;
import org.cef.handler.CefPermissionRequestResult;
import org.cef.handler.CefPermissionRequestTypes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class CefPermissionApiTest {
    @Test
    void mediaPermissionConstantsMatchCef151ExactlyAndPreserveUnknownBits() throws IllegalAccessException {
        Map<String, Integer> expected = Map.ofEntries(Map.entry("CEF_MEDIA_PERMISSION_NONE", 0), Map.entry("CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE", 1 << 0), Map.entry("CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE", 1 << 1), Map.entry("CEF_MEDIA_PERMISSION_DESKTOP_AUDIO_CAPTURE", 1 << 2), Map.entry("CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE", 1 << 3), Map.entry("CEF_MEDIA_PERMISSION_KNOWN_MASK", (1 << 4) - 1));

        assertPublicIntConstants(CefMediaAccessPermissionTypes.class, expected);
        assertEquals(0, CefMediaAccessPermissionTypes.getUnknownBits(0xF));
        assertEquals(0x80000010, CefMediaAccessPermissionTypes.getUnknownBits(0x8000001F));
    }

    @Test
    @SuppressWarnings("deprecation")
    void promptPermissionConstantsMatchCef151ExactlyAndMarkBit25Deprecated() throws ReflectiveOperationException {
        Map<String, Integer> expected = new TreeMap<String, Integer>();
        expected.put("CEF_PERMISSION_TYPE_NONE", 0);
        expected.put("CEF_PERMISSION_TYPE_AR_SESSION", 1 << 0);
        expected.put("CEF_PERMISSION_TYPE_CAMERA_PAN_TILT_ZOOM", 1 << 1);
        expected.put("CEF_PERMISSION_TYPE_CAMERA_STREAM", 1 << 2);
        expected.put("CEF_PERMISSION_TYPE_CAPTURED_SURFACE_CONTROL", 1 << 3);
        expected.put("CEF_PERMISSION_TYPE_CLIPBOARD", 1 << 4);
        expected.put("CEF_PERMISSION_TYPE_TOP_LEVEL_STORAGE_ACCESS", 1 << 5);
        expected.put("CEF_PERMISSION_TYPE_DISK_QUOTA", 1 << 6);
        expected.put("CEF_PERMISSION_TYPE_LOCAL_FONTS", 1 << 7);
        expected.put("CEF_PERMISSION_TYPE_GEOLOCATION", 1 << 8);
        expected.put("CEF_PERMISSION_TYPE_HAND_TRACKING", 1 << 9);
        expected.put("CEF_PERMISSION_TYPE_IDENTITY_PROVIDER", 1 << 10);
        expected.put("CEF_PERMISSION_TYPE_IDLE_DETECTION", 1 << 11);
        expected.put("CEF_PERMISSION_TYPE_MIC_STREAM", 1 << 12);
        expected.put("CEF_PERMISSION_TYPE_MIDI_SYSEX", 1 << 13);
        expected.put("CEF_PERMISSION_TYPE_MULTIPLE_DOWNLOADS", 1 << 14);
        expected.put("CEF_PERMISSION_TYPE_NOTIFICATIONS", 1 << 15);
        expected.put("CEF_PERMISSION_TYPE_KEYBOARD_LOCK", 1 << 16);
        expected.put("CEF_PERMISSION_TYPE_POINTER_LOCK", 1 << 17);
        expected.put("CEF_PERMISSION_TYPE_PROTECTED_MEDIA_IDENTIFIER", 1 << 18);
        expected.put("CEF_PERMISSION_TYPE_REGISTER_PROTOCOL_HANDLER", 1 << 19);
        expected.put("CEF_PERMISSION_TYPE_STORAGE_ACCESS", 1 << 20);
        expected.put("CEF_PERMISSION_TYPE_VR_SESSION", 1 << 21);
        expected.put("CEF_PERMISSION_TYPE_WEB_APP_INSTALLATION", 1 << 22);
        expected.put("CEF_PERMISSION_TYPE_WINDOW_MANAGEMENT", 1 << 23);
        expected.put("CEF_PERMISSION_TYPE_FILE_SYSTEM_ACCESS", 1 << 24);
        expected.put("CEF_PERMISSION_TYPE_LOCAL_NETWORK_ACCESS_DEPRECATED", 1 << 25);
        expected.put("CEF_PERMISSION_TYPE_LOCAL_NETWORK", 1 << 26);
        expected.put("CEF_PERMISSION_TYPE_LOOPBACK_NETWORK", 1 << 27);
        expected.put("CEF_PERMISSION_TYPE_SENSORS", 1 << 28);
        expected.put("CEF_PERMISSION_TYPE_KNOWN_MASK", (1 << 29) - 1);

        assertPublicIntConstants(CefPermissionRequestTypes.class, expected);
        Field deprecatedBit = CefPermissionRequestTypes.class.getField("CEF_PERMISSION_TYPE_LOCAL_NETWORK_ACCESS_DEPRECATED");
        assertNotNull(deprecatedBit.getAnnotation(Deprecated.class));
        assertEquals(0, CefPermissionRequestTypes.getUnknownBits((1 << 29) - 1));
        assertEquals(0xE0000000, CefPermissionRequestTypes.getUnknownBits(0xFFFFFFFF));
    }

    @Test
    void permissionResultsMatchCef151AndRejectSentinelOrUnknownValues() throws IllegalAccessException {
        Map<String, Integer> expected = Map.of("CEF_PERMISSION_RESULT_ACCEPT", 0, "CEF_PERMISSION_RESULT_DENY", 1, "CEF_PERMISSION_RESULT_DISMISS", 2, "CEF_PERMISSION_RESULT_IGNORE", 3, "CEF_PERMISSION_RESULT_NUM_VALUES", 4);

        assertPublicIntConstants(CefPermissionRequestResult.class, expected);
        for (int result = 0; result < 4; ++result)
            assertTrue(CefPermissionRequestResult.isValid(result));
        assertFalse(CefPermissionRequestResult.isValid(-1));
        assertFalse(CefPermissionRequestResult.isValid(4));
        assertFalse(CefPermissionRequestResult.isValid(Integer.MAX_VALUE));
    }

    @Test
    void handlerAndAdapterDefaultsLeaveRequestsToCef() {
        CefPermissionHandler handler = new CefPermissionHandler() {};
        CefPermissionHandler adapter = new CefPermissionHandlerAdapter() {};

        assertFalse(handler.onRequestMediaAccessPermission(null, null, "https://permission.test", Integer.MIN_VALUE, null));
        assertFalse(handler.onShowPermissionPrompt(null, Long.MIN_VALUE, "https://permission.test", Integer.MIN_VALUE, null));
        handler.onDismissPermissionPrompt(null, Long.MIN_VALUE, CefPermissionRequestResult.CEF_PERMISSION_RESULT_IGNORE);
        assertFalse(adapter.onRequestMediaAccessPermission(null, null, "https://permission.test", Integer.MIN_VALUE, null));
        assertFalse(adapter.onShowPermissionPrompt(null, Long.MIN_VALUE, "https://permission.test", Integer.MIN_VALUE, null));
        adapter.onDismissPermissionPrompt(null, Long.MIN_VALUE, CefPermissionRequestResult.CEF_PERMISSION_RESULT_IGNORE);
    }

    @Test
    void handlerSurfaceAndCompatibilityGetterRemainConcrete() throws NoSuchMethodException {
        Method media = CefPermissionHandler.class.getMethod("onRequestMediaAccessPermission", CefBrowser.class, CefFrame.class, String.class, int.class, CefMediaAccessCallback.class);
        Method show = CefPermissionHandler.class.getMethod("onShowPermissionPrompt", CefBrowser.class, long.class, String.class, int.class, CefPermissionPromptCallback.class);
        Method dismiss = CefPermissionHandler.class.getMethod("onDismissPermissionPrompt", CefBrowser.class, long.class, int.class);
        Method getter = CefClientHandler.class.getDeclaredMethod("getPermissionHandler");
        Method remover = CefClientHandler.class.getDeclaredMethod("removePermissionHandler", CefPermissionHandler.class);

        assertEquals(boolean.class, media.getReturnType());
        assertTrue(media.isDefault());
        assertEquals(boolean.class, show.getReturnType());
        assertTrue(show.isDefault());
        assertEquals(void.class, dismiss.getReturnType());
        assertTrue(dismiss.isDefault());
        assertTrue(Modifier.isProtected(getter.getModifiers()));
        assertFalse(Modifier.isAbstract(getter.getModifiers()));
        assertTrue(Modifier.isProtected(remover.getModifiers()));
        assertTrue(Modifier.isSynchronized(remover.getModifiers()));
    }

    @Test
    void promptNativeWrapperValidatesBeforeConsumingItsOneShotState() throws ReflectiveOperationException {
        CefPermissionPromptCallback callback = newNativeCallback("org.cef.callback.CefPermissionPromptCallback_N", CefPermissionPromptCallback.class);
        Field pending = callback.getClass().getSuperclass().getDeclaredField("pending_");
        pending.setAccessible(true);

        assertTrue(pending.getBoolean(callback));
        assertThrows(IllegalArgumentException.class, () -> callback.Continue(-1));
        assertThrows(IllegalArgumentException.class, () -> callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_NUM_VALUES));
        assertTrue(pending.getBoolean(callback));
        callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_IGNORE);
        assertFalse(pending.getBoolean(callback));
        callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_ACCEPT);
        assertThrows(IllegalArgumentException.class, () -> callback.Continue(Integer.MAX_VALUE));
    }

    @Test
    void mediaNativeWrapperConsumesOnlyItsFirstCompletion() throws ReflectiveOperationException {
        CefMediaAccessCallback callback = newNativeCallback("org.cef.callback.CefMediaAccessCallback_N", CefMediaAccessCallback.class);
        Field pending = callback.getClass().getSuperclass().getDeclaredField("pending_");
        pending.setAccessible(true);

        assertTrue(pending.getBoolean(callback));
        callback.Continue(0x80000005);
        assertFalse(pending.getBoolean(callback));
        callback.Cancel();
        callback.Continue(CefMediaAccessPermissionTypes.CEF_MEDIA_PERMISSION_NONE);
        assertFalse(pending.getBoolean(callback));
    }

    @Test
    void callbackNativeReferenceTakeIsAtomicAcrossRacingCleanupPaths() throws Exception {
        CefPermissionPromptCallback callback = newNativeCallback("org.cef.callback.CefPermissionPromptCallback_N", CefPermissionPromptCallback.class);
        CefNativeAdapter nativeAdapter = (CefNativeAdapter) callback;
        Method takeNativeRef = callback.getClass().getSuperclass().getDeclaredMethod("takeNativeRef");
        takeNativeRef.setAccessible(true);
        assertTrue(Modifier.isFinal(takeNativeRef.getModifiers()));
        assertTrue(Modifier.isSynchronized(takeNativeRef.getModifiers()));
        assertSynchronizedCallbackMethod("org.cef.callback.CefPermissionPromptCallback_N", "Continue", int.class);
        assertSynchronizedCallbackMethod("org.cef.callback.CefMediaAccessCallback_N", "Continue", int.class);
        assertSynchronizedCallbackMethod("org.cef.callback.CefMediaAccessCallback_N", "Cancel");

        long sentinel = 0x12345678ABCDEFL;
        nativeAdapter.setNativeRef("CefPermissionPromptCallback", sentinel);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicLong winningValue = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Runnable contender = () -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Timed out waiting for native-ref race start");
                long value = ((Long) takeNativeRef.invoke(callback)).longValue();
                if (value != 0) {
                    winners.incrementAndGet();
                    winningValue.set(value);
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        };
        Thread first = new Thread(contender, "permission-native-ref-take-1");
        Thread second = new Thread(contender, "permission-native-ref-take-2");
        first.start();
        second.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        if (failure.get() != null)
            throw new AssertionError("Atomic native-ref take failed", failure.get());
        assertEquals(1, winners.get());
        assertEquals(sentinel, winningValue.get());
        assertEquals(0L, nativeAdapter.getNativeRef("CefPermissionPromptCallback"));
    }

    @Test
    void nativeBridgeUsesExactPermissionDescriptorsAndCallbackEntryPoints() throws Exception {
        String handlerSource = readSource("native/permission_handler.cpp");
        assertTrue(handlerSource.contains("\"onRequestMediaAccessPermission\", \"(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/CefFrame;Ljava/lang/String;ILorg/cef/callback/CefMediaAccessCallback;)Z\""));
        assertTrue(handlerSource.contains("\"onShowPermissionPrompt\", \"(Lorg/cef/browser/CefBrowser;JLjava/lang/String;ILorg/cef/callback/CefPermissionPromptCallback;)Z\""));
        assertTrue(handlerSource.contains("\"onDismissPermissionPrompt\", \"(Lorg/cef/browser/CefBrowser;JI)V\""));
        assertTrue(handlerSource.contains("ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>"));
        assertTrue(handlerSource.contains("ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>"));
        assertFalse(handlerSource.contains("jcallback.SetTemporary()"));

        String callbackHelperSource = readSource("native/permission_callback_jni.cpp");
        assertTrue(callbackHelperSource.indexOf("env->ExceptionClear()") < callbackHelperSource.indexOf("\"takeNativeRef\", \"()J\""));
        assertTrue(callbackHelperSource.contains("\"takeNativeRef\", \"()J\""));
        assertTrue(readSource("native/CefMediaAccessCallback_N.cpp").contains("ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>"));
        assertTrue(readSource("native/CefPermissionPromptCallback_N.cpp").contains("ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>"));

        assertNativeMethod("org.cef.callback.CefMediaAccessCallback_N", "N_Continue", long.class, int.class);
        assertNativeMethod("org.cef.callback.CefMediaAccessCallback_N", "N_Cancel", long.class);
        assertNativeMethod("org.cef.callback.CefPermissionPromptCallback_N", "N_Continue", long.class, int.class);
    }

    @Test
    void baseClientDisposalInvalidatesPermissionBridgesBeforeReleasingTheNativeClient() throws Exception {
        String javaSource = readSource("java/org/cef/handler/CefClientHandler.java");
        int javaDisposeStart = javaSource.indexOf("protected synchronized void dispose()");
        int javaDisposeEnd = javaSource.indexOf("abstract protected CefBrowser getBrowser", javaDisposeStart);
        assertTrue(javaDisposeStart >= 0 && javaDisposeEnd > javaDisposeStart);
        String javaDispose = javaSource.substring(javaDisposeStart, javaDisposeEnd);
        assertTrue(javaDispose.contains("if (nativeClientDisposed_ || nativeClientDisposalInProgress_) return"));
        assertTrue(javaDispose.indexOf("nativeClientDisposalInProgress_ = true") < javaDispose.indexOf("removePermissionHandler(null)"));
        assertTrue(javaDispose.indexOf("removePermissionHandler(null)") < javaDispose.indexOf("N_CefClientHandler_DTOR()"));
        assertTrue(javaDispose.indexOf("nativeClientDisposed_ = true") < javaDispose.indexOf("nativeClientDisposalInProgress_ = false"));

        String bindingSource = readSource("native/CefClientHandler.cpp");
        int nativeDtorStart = bindingSource.indexOf("Java_org_cef_handler_CefClientHandler_N_1CefClientHandler_1DTOR");
        assertTrue(nativeDtorStart >= 0);
        String nativeDtor = bindingSource.substring(nativeDtorStart);
        int retainedClient = nativeDtor.indexOf("CefRefPtr<ClientHandler> client");
        int removeHandler = nativeDtor.indexOf("client->RemovePermissionHandler()");
        int clearBinding = nativeDtor.indexOf("SetCefForJNIObject<ClientHandler>(env, clientHandler, nullptr");
        assertTrue(retainedClient >= 0 && retainedClient < removeHandler && removeHandler < clearBinding);

        String clientSource = readSource("native/client_handler.cpp");
        int nativeDestructor = clientSource.indexOf("ClientHandler::~ClientHandler()");
        assertTrue(nativeDestructor >= 0);
        assertTrue(clientSource.substring(nativeDestructor, clientSource.indexOf("template <class T>", nativeDestructor)).contains("RemovePermissionHandler()"));
        assertTrue(clientSource.contains("if (permission_handler_removed_)"));
        assertTrue(clientSource.contains("permission_handlers.swap(permission_handlers_)"));
        assertTrue(clientSource.contains("candidate->IsValid()"));
    }

    @Test
    void deterministicMediaFixtureUsesHardwareOnlyAndCannotBypassPermissions() {
        Map<String, String> switches = new TreeMap<String, String>();
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("hasSwitch")) return switches.containsKey(arguments[0]);
            if (method.getName().equals("appendSwitch")) {
                switches.put((String) arguments[0], "");
                return null;
            }
            if (method.getName().equals("removeSwitch")) {
                switches.remove(arguments[0]);
                return null;
            }
            return defaultValue(method.getReturnType());
        };
        CefCommandLine commandLine = (CefCommandLine) Proxy.newProxyInstance(CefCommandLine.class.getClassLoader(), new Class<?>[] {CefCommandLine.class}, handler);

        switches.put(PermissionTestCommandLine.BYPASS_MEDIA_PERMISSION_UI_SWITCH, "");
        switches.put(PermissionTestCommandLine.GRANT_ALL_MEDIA_PERMISSIONS_SWITCH, "");
        PermissionTestCommandLine.configureBrowserProcess("renderer", commandLine);
        assertEquals(2, switches.size());
        PermissionTestCommandLine.configureBrowserProcess("", commandLine);
        PermissionTestCommandLine.configureBrowserProcess("", commandLine);

        assertEquals(Map.of(PermissionTestCommandLine.FAKE_MEDIA_DEVICE_SWITCH, ""), switches);
        assertFalse(switches.containsKey(PermissionTestCommandLine.BYPASS_MEDIA_PERMISSION_UI_SWITCH));
        assertFalse(switches.containsKey(PermissionTestCommandLine.GRANT_ALL_MEDIA_PERMISSIONS_SWITCH));
    }

    private static void assertPublicIntConstants(Class<?> type, Map<String, Integer> expected) throws IllegalAccessException {
        Map<String, Integer> actual = new TreeMap<String, Integer>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == int.class && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers))
                actual.put(field.getName(), Integer.valueOf(field.getInt(null)));
        }
        assertEquals(new TreeMap<String, Integer>(expected), actual);
    }

    private static <T> T newNativeCallback(String className, Class<T> callbackType) throws ReflectiveOperationException {
        Class<?> callbackClass = Class.forName(className);
        Constructor<?> constructor = callbackClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return callbackType.cast(constructor.newInstance());
    }

    private static void assertNativeMethod(String className, String methodName, Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = Class.forName(className).getDeclaredMethod(methodName, parameterTypes);
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isNative(method.getModifiers()));
    }

    private static void assertSynchronizedCallbackMethod(String className, String methodName, Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = Class.forName(className).getDeclaredMethod(methodName, parameterTypes);
        assertTrue(Modifier.isSynchronized(method.getModifiers()));
    }

    private static String readSource(String relativePath) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), relativePath);
        assertTrue(Files.isRegularFile(path), "Run source contract tests from the repository root");
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return Character.valueOf('\0');
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0.0F);
        return Double.valueOf(0.0D);
    }
}
