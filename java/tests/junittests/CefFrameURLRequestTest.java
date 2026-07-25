// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefFrame;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefRequest;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class CefFrameURLRequestTest {
    private static final String LEGACY_FRAME_SOURCE = "package org.cef.browser;\n"
            + "public interface CefFrame {\n"
            + "    void dispose();\n"
            + "    String getIdentifier();\n"
            + "    String getURL();\n"
            + "    String getName();\n"
            + "    boolean isMain();\n"
            + "    boolean isValid();\n"
            + "    boolean isFocused();\n"
            + "    CefFrame getParent();\n"
            + "    void executeJavaScript(String code, String url, int line);\n"
            + "    void undo();\n"
            + "    void redo();\n"
            + "    void cut();\n"
            + "    void copy();\n"
            + "    void paste();\n"
            + "    void selectAll();\n"
            + "}\n";
    private static final String LEGACY_IMPLEMENTATION_SOURCE = "package compatibility;\n"
            + "import org.cef.browser.CefFrame;\n"
            + "public final class LegacyFrame implements CefFrame {\n"
            + "    public void dispose() {}\n"
            + "    public String getIdentifier() { return \"\"; }\n"
            + "    public String getURL() { return \"\"; }\n"
            + "    public String getName() { return \"\"; }\n"
            + "    public boolean isMain() { return false; }\n"
            + "    public boolean isValid() { return false; }\n"
            + "    public boolean isFocused() { return false; }\n"
            + "    public CefFrame getParent() { return null; }\n"
            + "    public void executeJavaScript(String code, String url, int line) {}\n"
            + "    public void undo() {}\n"
            + "    public void redo() {}\n"
            + "    public void cut() {}\n"
            + "    public void copy() {}\n"
            + "    public void paste() {}\n"
            + "    public void selectAll() {}\n"
            + "}\n";

    @Test
    void frameFactoryIsAnAdditiveDefaultWithADistinctNativeDescriptor() throws Exception {
        Method apiMethod = CefFrame.class.getMethod("createURLRequest", CefRequest.class, CefURLRequestClient.class);
        Class<?> nativeFrameClass = Class.forName("org.cef.browser.CefFrame_N");
        Method nativeMethod = nativeFrameClass.getDeclaredMethod("N_CreateURLRequest", long.class, CefRequest.class, CefURLRequestClient.class);

        assertEquals(CefURLRequest.class, apiMethod.getReturnType());
        assertTrue(Modifier.isPublic(apiMethod.getModifiers()));
        assertFalse(Modifier.isAbstract(apiMethod.getModifiers()));
        assertEquals("(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)Lorg/cef/network/CefURLRequest;", MethodType.methodType(apiMethod.getReturnType(), apiMethod.getParameterTypes()).toMethodDescriptorString());
        assertTrue(Modifier.isPrivate(nativeMethod.getModifiers()));
        assertTrue(Modifier.isNative(nativeMethod.getModifiers()));
        assertEquals("(JLorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)Lorg/cef/network/CefURLRequest;", MethodType.methodType(nativeMethod.getReturnType(), nativeMethod.getParameterTypes()).toMethodDescriptorString());
        assertEquals(1, Arrays.stream(nativeFrameClass.getDeclaredMethods()).filter(method -> method.getName().equals("N_CreateURLRequest")).count());
    }

    @Test
    void implementationCompiledAgainstTheLegacyInterfaceUsesTheNewDefault(@TempDir Path temporaryDirectory) throws Exception {
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path legacyInterface = writeSource(sourceDirectory, "org/cef/browser/CefFrame.java", LEGACY_FRAME_SOURCE);
        Path legacyImplementation = writeSource(sourceDirectory, "compatibility/LegacyFrame.java", LEGACY_IMPLEMENTATION_SOURCE);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require the project-mandated JDK 17, not a JRE");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        String productionClasses = Path.of(CefFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String compilerClassPath = productionClasses + File.pathSeparator + System.getProperty("java.class.path");
        int result = compiler.run(null, null, diagnostics, "--release", "17", "-classpath", compilerClassPath, "-d", classesDirectory.toString(), legacyInterface.toString(), legacyImplementation.toString());
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));

        try (URLClassLoader loader = new URLClassLoader(new URL[] {classesDirectory.toUri().toURL()}, CefFrame.class.getClassLoader())) {
            CefFrame frame = (CefFrame) Class.forName("compatibility.LegacyFrame", true, loader).getConstructor().newInstance();
            assertThrows(NullPointerException.class, () -> frame.createURLRequest(null, null));
        }
    }

    @Test
    void sourceContractUsesTheExactFramePathAndSharedPublishedLifecycle() throws Exception {
        String javaApi = readSource("java/org/cef/browser/CefFrame.java");
        String javaNative = readSource("java/org/cef/browser/CefFrame_N.java");
        String javaUrlRequest = readSource("java/org/cef/network/CefURLRequest_N.java");
        String nativeHeader = readSource("native/CefFrame_N.h");
        String frameNative = readSource("native/CefFrame_N.cpp");
        String sharedNative = readSource("native/url_request.cpp");
        String standaloneNative = readSource("native/CefURLRequest_N.cpp");
        String frameMethod = section(frameNative, "Java_org_cef_browser_CefFrame_1N_N_1CreateURLRequest(", "Java_org_cef_browser_CefFrame_1N_N_1ExecuteJavaScript(");
        String createOperation = section(sharedNative, "case REQ_CREATE:", "case REQ_STATUS:");
        String commonCreate = section(sharedNative, "bool CreateURLRequest(", "}  // namespace");

        assertTrue(javaApi.contains("default CefURLRequest createURLRequest(CefRequest request, CefURLRequestClient client)"));
        assertTrue(javaApi.contains("Objects.requireNonNull(request, \"request\");"));
        assertTrue(javaApi.contains("Objects.requireNonNull(client, \"client\");"));
        assertTrue(javaNative.contains("return N_CreateURLRequest(getNativeRef(null), request, client);"));
        assertTrue(javaNative.contains("private final native CefURLRequest N_CreateURLRequest(long self, CefRequest request, CefURLRequestClient client);"));
        assertTrue(javaUrlRequest.contains("private volatile long N_CefHandle = 0;"));
        assertTrue(javaUrlRequest.contains("private volatile boolean N_CreationSucceeded = false;"));
        assertEquals(1, occurrences(javaUrlRequest, "if (!result.N_CreationSucceeded && result.N_CefHandle == 0) return null;"));
        assertEquals(2, occurrences(javaUrlRequest, "return completeCreation(result);"));

        assertTrue(nativeHeader.contains("Method:    N_CreateURLRequest\n * Signature: (JLorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)Lorg/cef/network/CefURLRequest;"));
        assertTrue(nativeHeader.contains("Java_org_cef_browser_CefFrame_1N_N_1CreateURLRequest\n"));
        assertEquals(1, occurrences(frameNative, "Java_org_cef_browser_CefFrame_1N_N_1CreateURLRequest("));
        assertTrue(frameMethod.contains("CreateFrameURLRequest(env, jrequest, jrequest_client, frame, admission)"));
        assertOrdered(frameMethod, "AcquireURLRequestCreationAdmission()", "GetSelf(self)", "CreateFrameURLRequest(");
        assertFalse(frameMethod.contains("CefURLRequest::Create"));
        assertFalse(frameMethod.contains("frame->CreateURLRequest"));

        assertTrue(sharedNative.contains("NewJNIObject(env, \"org/cef/network/CefURLRequest_N\", \"(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)V\", jrequest, jrequest_client)"));
        assertTrue(createOperation.contains("owner->frame_->CreateURLRequest(owner->request_, owner->client_.get())"));
        assertTrue(createOperation.contains("CefURLRequest::Create(owner->request_, owner->client_.get(), owner->request_context_)"));
        assertTrue(createOperation.contains("owner->frame_ = nullptr;"));
        assertEquals(1, occurrences(commonCreate, "new URLRequest(TID_UI,"));
        int register = commonCreate.indexOf("GetURLRequestLifecycle().Register(admission, url_request)");
        int publish = commonCreate.indexOf("PublishURLRequestToken(env, jurl_request, token)");
        int start = commonCreate.indexOf("url_request->Create()");
        int clearOnFailure = commonCreate.indexOf("RollBackURLRequestToken(env, jurl_request, token)");
        int publishSuccess = commonCreate.indexOf("SetJNIFieldBoolean(env, url_request_class, jurl_request, \"N_CreationSucceeded\", true)");
        assertTrue(register >= 0 && register < publish && publish < clearOnFailure && clearOnFailure < start && start < publishSuccess);
        assertFalse(commonCreate.contains("!SetJNIFieldBoolean"), "The optional success field must not break ordinary older-wrapper compatibility");
        assertFalse(sharedNative.contains("reinterpret_cast<URLRequest*>"));
        assertFalse(sharedNative.contains("SetCefForJNIObject"));

        assertTrue(standaloneNative.contains("CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, nullptr, admission);"));
        assertTrue(standaloneNative.contains("CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, request_context, admission);"));
        assertEquals(1, occurrences(standaloneNative, "Java_org_cef_network_CefURLRequest_1N_N_1Create("));
        assertEquals(1, occurrences(standaloneNative, "Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext("));
    }

    @Test
    void nativeTestKeepsResourceCallbacksInlineAndCoordinatesFastCompletion() throws Exception {
        String integrationTest = readSource("java/tests/junittests/CefFrameURLRequestNativeTest.java");
        String firstTest = section(integrationTest, "void frameCreationPreservesIdentityAndAssociationWithoutChangingStandaloneRouting()", "@Test\n    void pendingFrameRequestCanCancelAndDisposeReentrantlyExactlyOnce()");
        String secondTest = section(integrationTest, "void pendingFrameRequestCanCancelAndDisposeReentrantlyExactlyOnce()", "private static void verifyInvalidInputs(");
        String creator = section(firstTest, "private void createRequests(", "private void requestCompleted()");
        String completion = section(firstTest, "private void requestCompleted()", "});\n\n            testFrame.awaitCompletion()");
        String cleanupState = section(integrationTest, "private static final class CleanupState", "private abstract static class BaseClient");
        String factory = section(integrationTest, "private static final class AssociatedSchemeFactory", "private static final class ImmediateResourceHandler");
        String handler = section(integrationTest, "private static final class ImmediateResourceHandler", "private static final class RecordingClient");
        String handlerProcess = section(handler, "public boolean processRequest(", "public void getResponseHeaders(");
        String recordingClient = section(integrationTest, "private static final class RecordingClient", "private static final class CancelClient");
        String returnedPublication = section(recordingClient, "void publishReturnedURLRequest(", "public void onUploadProgress(");
        String callbackIdentity = section(recordingClient, "private void verifyIdentity(", "private CefURLRequest adoptURLRequest(");
        String identityAdoption = section(integrationTest, "private CefURLRequest adoptURLRequest(", "private static final class CancelClient");

        assertOrdered(firstTest, "awaitCreatorThread(creationThread, creationThreadFinished, \"the off-CEF Java creator thread\", false)", "terminateAndAwait(testFrame)", "awaitCreatorThread(creationThread, creationThreadFinished, \"the off-CEF Java creator thread\", true)", "closeServer(server)", "disposeRequestContext(browserContext)", "cleanup.finish()");
        assertOrdered(secondTest, "awaitCreatorThread(creationThread, creatorFinished, \"the cancellation creator thread\", false)", "terminateAndAwait(testFrame)", "awaitCreatorThread(creationThread, creatorFinished, \"the cancellation creator thread\", true)", "closeServer(server)", "cleanup.finish()");
        assertFalse(cleanupState.contains("synchronized"));
        assertTrue(cleanupState.contains("thread.join(milliseconds, nanoseconds)"));
        assertTrue(cleanupState.contains("recordInterruption("));
        assertTrue(cleanupState.contains("captureAndClearInterruptFlag()"));
        assertEquals(1, occurrences(cleanupState, "Thread.currentThread().interrupt()"));
        assertOrdered(cleanupState, "closeServer(LoopbackHttpServer server)", "disposeRequestContext(CefRequestContext context)", "Throwable finish()");

        assertFalse(firstTest.contains("creationReturned"));
        assertFalse(factory.contains("CountDownLatch"));
        assertTrue(factory.contains("return new ImmediateResourceHandler(body_);"));
        assertOrdered(handlerProcess, "callback.Continue();", "return true;");
        assertFalse(handlerProcess.contains("synchronized"));
        assertEquals(1, occurrences(handler, "callback.Continue();"));
        assertFalse(handler.contains("callback.cancel()"));
        assertFalse(handler.contains("new Thread("));
        assertFalse(handler.contains("AtomicReference<CefCallback>"));
        assertFalse(handler.contains("CefCallback callback_"));
        assertFalse(integrationTest.contains("frame-urlrequest-resource"));
        assertFalse(integrationTest.contains("HandlerClosure"));
        assertFalse(integrationTest.contains("CallbackAdmission"));

        assertOrdered(creator, "frame.createURLRequest(associatedRequest, associatedClient)", "assertNotNull(associatedUrlRequest)", "frameUrlRequest.set(associatedUrlRequest)", "associatedClient.publishReturnedURLRequest(associatedUrlRequest)", "CefURLRequest.create(unassociatedRequest, unassociatedClient)", "assertNotNull(unassociatedUrlRequest)", "standaloneUrlRequest.set(unassociatedUrlRequest)", "unassociatedClient.publishReturnedURLRequest(unassociatedUrlRequest)", "creationThreadFinished.countDown()", "if (creationFailed)", "terminateTest();", "else", "terminateIfComplete();");
        assertEquals(1, occurrences(creator, "terminateTest();"));
        assertEquals(2, occurrences(firstTest, "terminateIfComplete();"));
        assertOrdered(completion, "remainingCompletions.decrementAndGet()", "terminateIfComplete()", "private void terminateIfComplete()", "remainingCompletions.get() != 0 || creationThreadFinished.getCount() != 0", "completionTerminationQueued_.compareAndSet(false, true)", "terminateTest()");

        assertOrdered(returnedPublication, "assertNotNull(request)", "assertSame(request, adoptURLRequest(request))");
        assertOrdered(callbackIdentity, "assertNotNull(request)", "assertSame(request, adoptURLRequest(request))");
        assertEquals(2, occurrences(recordingClient, "adoptURLRequest(request)"));
        assertOrdered(identityAdoption, "expectedURLRequest_.compareAndExchange(null, request)", "return adopted == null ? request : adopted");
        assertFalse(recordingClient.contains("callback ran before the Java factory published"));
    }

    private static Path writeSource(Path root, String relativePath, String source) throws Exception {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source);
    }

    private static String readSource(String relativePath) throws Exception {
        Path path = Path.of(System.getProperty("user.dir"), relativePath);
        assertTrue(Files.isRegularFile(path), "Run source contract tests from the repository root");
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "Missing source marker: " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "Missing source marker after section: " + endMarker);
        return source.substring(start, end);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker, previous + 1);
            assertTrue(current > previous, "Missing or out-of-order source marker: " + marker);
            previous = current;
        }
    }
}
