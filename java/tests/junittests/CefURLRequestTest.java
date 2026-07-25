// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefRequestContext;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefRequest;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class CefURLRequestTest {
    private static final String LEGACY_URL_REQUEST_SOURCE = "package org.cef.network;\n"
            + "import org.cef.callback.CefURLRequestClient;\n"
            + "import org.cef.handler.CefLoadHandler.ErrorCode;\n"
            + "public abstract class CefURLRequest {\n"
            + "    public enum Status { UR_UNKNOWN }\n"
            + "    CefURLRequest() {}\n"
            + "    public abstract void dispose();\n"
            + "    public abstract CefRequest getRequest();\n"
            + "    public abstract CefURLRequestClient getClient();\n"
            + "    public abstract Status getRequestStatus();\n"
            + "    public ErrorCode getRequestError() { return ErrorCode.ERR_FAILED; }\n"
            + "    public abstract int getRequestErrorCode();\n"
            + "    public abstract CefResponse getResponse();\n"
            + "    public abstract void cancel();\n"
            + "}\n";
    private static final String LEGACY_IMPLEMENTATION_SOURCE = "package org.cef.network;\n"
            + "import org.cef.callback.CefURLRequestClient;\n"
            + "public final class LegacyURLRequest extends CefURLRequest {\n"
            + "    public LegacyURLRequest() {}\n"
            + "    public void dispose() {}\n"
            + "    public CefRequest getRequest() { return null; }\n"
            + "    public CefURLRequestClient getClient() { return null; }\n"
            + "    public Status getRequestStatus() { return Status.UR_UNKNOWN; }\n"
            + "    public int getRequestErrorCode() { return 0; }\n"
            + "    public CefResponse getResponse() { return null; }\n"
            + "    public void cancel() {}\n"
            + "}\n";

    @Test
    void preservesLegacyCreateAndAddsDistinctContextAndCacheApis() throws Exception {
        Method legacyCreate = CefURLRequest.class.getMethod("create", CefRequest.class, CefURLRequestClient.class);
        Method contextCreate = CefURLRequest.class.getMethod("create", CefRequest.class, CefURLRequestClient.class, CefRequestContext.class);
        Method responseWasCached = CefURLRequest.class.getMethod("responseWasCached");
        Class<?> nativeClass = Class.forName("org.cef.network.CefURLRequest_N");
        Method nativeLegacyCreate = nativeClass.getDeclaredMethod("N_Create", CefRequest.class, CefURLRequestClient.class);
        Method nativeContextCreate = nativeClass.getDeclaredMethod("N_CreateWithContext", CefRequest.class, CefURLRequestClient.class, CefRequestContext.class);
        Method nativeResponseWasCached = nativeClass.getDeclaredMethod("N_ResponseWasCached", long.class);
        Method nativeDisposedRace = nativeClass.getDeclaredMethod("N_RunDisposedCreationRaceForTesting", nativeClass);
        Method nativeRegistryConcurrency = nativeClass.getDeclaredMethod("N_RunTokenRegistryConcurrencyForTesting");
        Method nativePendingAbandonment = nativeClass.getDeclaredMethod("N_RunPendingDispatchAbandonmentForTesting");
        Method nativeLifecycle = nativeClass.getDeclaredMethod("N_RunURLRequestLifecycleForTesting", nativeClass);

        assertEquals("(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)Lorg/cef/network/CefURLRequest;", MethodType.methodType(legacyCreate.getReturnType(), legacyCreate.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;Lorg/cef/browser/CefRequestContext;)Lorg/cef/network/CefURLRequest;", MethodType.methodType(contextCreate.getReturnType(), contextCreate.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)V", MethodType.methodType(nativeLegacyCreate.getReturnType(), nativeLegacyCreate.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;Lorg/cef/browser/CefRequestContext;)V", MethodType.methodType(nativeContextCreate.getReturnType(), nativeContextCreate.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(J)Z", MethodType.methodType(nativeResponseWasCached.getReturnType(), nativeResponseWasCached.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(Lorg/cef/network/CefURLRequest_N;)Z", MethodType.methodType(nativeDisposedRace.getReturnType(), nativeDisposedRace.getParameterTypes()).toMethodDescriptorString());
        assertEquals("()Z", MethodType.methodType(nativeRegistryConcurrency.getReturnType(), nativeRegistryConcurrency.getParameterTypes()).toMethodDescriptorString());
        assertEquals("()Z", MethodType.methodType(nativePendingAbandonment.getReturnType(), nativePendingAbandonment.getParameterTypes()).toMethodDescriptorString());
        assertEquals("(Lorg/cef/network/CefURLRequest_N;)Z", MethodType.methodType(nativeLifecycle.getReturnType(), nativeLifecycle.getParameterTypes()).toMethodDescriptorString());
        assertTrue(Modifier.isPublic(legacyCreate.getModifiers()));
        assertTrue(Modifier.isStatic(legacyCreate.getModifiers()));
        assertTrue(Modifier.isFinal(legacyCreate.getModifiers()));
        assertFalse(Modifier.isAbstract(responseWasCached.getModifiers()));
        assertTrue(Modifier.isNative(nativeLegacyCreate.getModifiers()));
        assertTrue(Modifier.isNative(nativeContextCreate.getModifiers()));
        assertTrue(Modifier.isNative(nativeResponseWasCached.getModifiers()));
        assertTrue(Modifier.isStatic(nativeDisposedRace.getModifiers()));
        assertTrue(Modifier.isNative(nativeDisposedRace.getModifiers()));
        assertEquals(0, nativeDisposedRace.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
        assertTrue(Modifier.isStatic(nativeRegistryConcurrency.getModifiers()));
        assertTrue(Modifier.isNative(nativeRegistryConcurrency.getModifiers()));
        assertEquals(0, nativeRegistryConcurrency.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
        assertTrue(Modifier.isStatic(nativePendingAbandonment.getModifiers()));
        assertTrue(Modifier.isNative(nativePendingAbandonment.getModifiers()));
        assertEquals(0, nativePendingAbandonment.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
        assertTrue(Modifier.isStatic(nativeLifecycle.getModifiers()));
        assertTrue(Modifier.isNative(nativeLifecycle.getModifiers()));
        assertEquals(0, nativeLifecycle.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
        assertEquals(1, Arrays.stream(nativeClass.getDeclaredMethods()).filter(method -> method.getName().equals("N_Create")).count());
        assertEquals(1, Arrays.stream(nativeClass.getDeclaredMethods()).filter(method -> method.getName().equals("N_CreateWithContext")).count());
    }

    @Test
    void previouslyCompiledImplementationUsesConservativeCacheFallback(@TempDir Path temporaryDirectory) throws Exception {
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path legacyApi = writeSource(sourceDirectory, "org/cef/network/CefURLRequest.java", LEGACY_URL_REQUEST_SOURCE);
        Path legacyImplementation = writeSource(sourceDirectory, "org/cef/network/LegacyURLRequest.java", LEGACY_IMPLEMENTATION_SOURCE);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require the project-mandated JDK 17, not a JRE");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        String productionClasses = Path.of(CefURLRequest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String compilerClassPath = productionClasses + File.pathSeparator + System.getProperty("java.class.path");
        int result = compiler.run(null, null, diagnostics, "--release", "17", "-classpath", compilerClassPath, "-d", classesDirectory.toString(), legacyApi.toString(), legacyImplementation.toString());
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));

        // Define only the legacy implementation through a private lookup on the production class.
        // It therefore shares CefURLRequest's loader/runtime package, can invoke the
        // package-private constructor normally, and still carries bytecode compiled against the
        // pre-method API.
        Path implementationClassFile = classesDirectory.resolve("org/cef/network/LegacyURLRequest.class");
        MethodHandles.Lookup productionLookup = MethodHandles.privateLookupIn(CefURLRequest.class, MethodHandles.lookup());
        Class<?> implementationClass = productionLookup.defineClass(Files.readAllBytes(implementationClassFile));
        CefURLRequest request = (CefURLRequest) implementationClass.getConstructor().newInstance();
        assertFalse(request.responseWasCached());
    }

    @Test
    void sourceContractPreservesLegacyAbiContextLifetimeAndPerCallDispatch() throws Exception {
        String javaApi = readSource("java/org/cef/network/CefURLRequest.java");
        String javaNative = readSource("java/org/cef/network/CefURLRequest_N.java");
        String nativeHeader = readSource("native/CefURLRequest_N.h");
        String nativeImplementation = readSource("native/CefURLRequest_N.cpp");
        String nativeDispatcher = readSource("native/url_request.cpp");
        String nativeDispatcherHeader = readSource("native/url_request.h");
        String clientImplementation = readSource("native/url_request_client.cpp");
        String contextCreate = section(javaNative, "public static final CefURLRequest createNative(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext)", "@Override\n    public void dispose()");
        String nativeLegacyCreate = section(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1Create(", "JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(");
        String nativeContextCreate = section(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(", "Java_org_cef_network_CefURLRequest_1N_N_1Dispose(");
        String nativeDispose = section(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1Dispose(", "Java_org_cef_network_CefURLRequest_1N_N_1GetRequestStatus(");
        String standaloneCreate = section(nativeDispatcher, "bool CreateStandaloneURLRequest(", "jobject CreateFrameURLRequest(");
        String commonCreate = section(nativeDispatcher, "bool CreateURLRequest(", "}  // namespace");
        String lifecycle = section(nativeDispatcher, "class URLRequestLifecycle", "namespace {");
        String lifecycleLifetime = section(nativeDispatcher, "URLRequestLifecycle& GetURLRequestLifecycle()", "bool ReportAndClearJNIException(");
        String operation = section(nativeDispatcher, "class URLRequestOperation : public CefTask", "namespace {");
        String modes = section(operation, "enum Mode {", "};");
        String states = section(nativeDispatcher, "enum class URLRequestOperationPhase {", "};");

        assertTrue(javaApi.contains("public static final CefURLRequest create(CefRequest request, CefURLRequestClient client)"));
        assertTrue(javaApi.contains("public static final CefURLRequest create(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext)"));
        assertTrue(javaApi.contains("public boolean responseWasCached()"));
        assertTrue(javaNative.contains("private final native void N_Create(CefRequest request, CefURLRequestClient client);"));
        assertTrue(javaNative.contains("private final native void N_CreateWithContext(CefRequest request, CefURLRequestClient client, CefRequestContext requestContext);"));
        assertTrue(javaNative.contains("private final native boolean N_ResponseWasCached(long self);"));
        assertTrue(javaNative.contains("private static CefURLRequest completeCreation(CefURLRequest_N result)"));
        assertEquals(2, occurrences(javaNative, "return completeCreation(result);"));
        assertTrue(javaNative.contains("older native libraries"));
        assertTrue(contextCreate.contains("if (requestContext == null) return createNative(request, client);"));
        assertTrue(contextCreate.indexOf("synchronized (requestContext)") < contextCreate.indexOf("result.N_CreateWithContext(request, client, requestContext);"));
        assertEquals(0, occurrences(contextCreate, "result.N_Create(request, client)"));

        assertTrue(nativeHeader.contains("Method:    N_Create\n * Signature: (Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)V"));
        assertTrue(nativeHeader.contains("Java_org_cef_network_CefURLRequest_1N_N_1Create\n"));
        assertTrue(nativeHeader.contains("Method:    N_CreateWithContext\n * Signature: (Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;Lorg/cef/browser/CefRequestContext;)V"));
        assertTrue(nativeHeader.contains("Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext\n"));
        assertTrue(nativeHeader.contains("Method:    N_ResponseWasCached\n * Signature: (J)Z"));
        assertTrue(nativeHeader.contains("Java_org_cef_network_CefURLRequest_1N_N_1ResponseWasCached\n"));
        assertTrue(nativeHeader.contains("Method:    N_RunDisposedCreationRaceForTesting\n * Signature: (Lorg/cef/network/CefURLRequest_N;)Z"));
        assertTrue(nativeHeader.contains("Method:    N_RunTokenRegistryConcurrencyForTesting\n * Signature: ()Z"));
        assertTrue(nativeHeader.contains("Method:    N_RunPendingDispatchAbandonmentForTesting\n * Signature: ()Z"));
        assertTrue(nativeHeader.contains("Method:    N_RunURLRequestLifecycleForTesting\n * Signature: (Lorg/cef/network/CefURLRequest_N;)Z"));
        assertEquals(1, occurrences(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1Create("));
        assertEquals(1, occurrences(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext("));
        assertEquals(1, occurrences(nativeImplementation, "Java_org_cef_network_CefURLRequest_1N_N_1ResponseWasCached("));
        assertTrue(nativeLegacyCreate.contains("CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, nullptr, admission);"));
        assertTrue(nativeDispose.contains("DisposeURLRequest(env, obj, self);"));
        int admissionAcquire = nativeContextCreate.indexOf("URLRequestAdmission admission = AcquireURLRequestCreationAdmission();");
        int argumentGuard = nativeContextCreate.indexOf("if (!admission || !jrequest || !jrequest_client || !jrequest_context)");
        int contextAcquire = nativeContextCreate.indexOf("CefRefPtr<CefRequestContext> request_context = GetCefFromJNIObject<CefRequestContext>");
        int commonCall = nativeContextCreate.indexOf("CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, request_context, admission);");
        assertTrue(admissionAcquire >= 0 && admissionAcquire < argumentGuard && argumentGuard < contextAcquire && contextAcquire < commonCall);
        int requestAcquire = standaloneCreate.indexOf("CefRefPtr<CefRequest> request = GetRequest(env, jrequest);");
        int commonDispatch = standaloneCreate.indexOf("CreateURLRequest(env, jurl_request, request, jrequest_client, request_context, nullptr, admission)");
        int clientBinding = commonCreate.indexOf("URLRequestClient::Create(env, jrequest_client, jurl_request)");
        assertTrue(requestAcquire >= 0 && requestAcquire < commonDispatch);
        assertTrue(clientBinding >= 0);
        assertTrue(nativeDispatcherHeader.contains("CefRefPtr<CefRequestContext> request_context_;"));
        assertTrue(nativeDispatcher.contains("CefURLRequest::Create(owner->request_, owner->client_.get(), owner->request_context_)"));
        assertTrue(lifecycle.contains("std::mutex lock_;"));
        assertTrue(lifecycle.contains("std::condition_variable quiescence_condition_;"));
        assertTrue(lifecycle.contains("std::unordered_map<jlong, CefRefPtr<URLRequest>> requests_;"));
        assertTrue(lifecycle.contains("jlong next_token_ = 1;"));
        assertTrue(lifecycle.contains("next_token_ = token == std::numeric_limits<jlong>::max() ? 0 : token + 1;"));
        assertTrue(lifecycle.contains("owner = request->second;"));
        assertTrue(lifecycle.contains("requests_.erase(request);"));
        assertTrue(lifecycleLifetime.contains("static URLRequestLifecycle* lifecycle = new URLRequestLifecycle();"));
        assertFalse(nativeDispatcher.contains("reinterpret_cast<URLRequest*>"));
        assertFalse(nativeDispatcher.contains("SetCefForJNIObject"));
        assertFalse(nativeDispatcher.contains("->AddRef("));
        assertTrue(nativeDispatcher.contains("return GetURLRequestLifecycle().AcquireAccess(token);"));
        assertTrue(nativeDispatcher.contains("URLRequestAccess removed = GetURLRequestLifecycle().TakeAccess(token);"));
        int registerToken = commonCreate.indexOf("GetURLRequestLifecycle().Register(admission, url_request)");
        int publishToken = commonCreate.indexOf("PublishURLRequestToken(env, jurl_request, token)");
        int createRequest = commonCreate.indexOf("url_request->Create()");
        int publishSuccess = commonCreate.indexOf("SetJNIFieldBoolean(env, url_request_class, jurl_request, \"N_CreationSucceeded\", true)");
        assertTrue(registerToken >= 0 && registerToken < publishToken && publishToken < createRequest && createRequest < publishSuccess);
        assertTrue(commonCreate.contains("RollBackURLRequestToken(env, jurl_request, token);"));

        assertTrue(operation.contains("REQ_CREATE"));
        assertTrue(operation.contains("REQ_STATUS"));
        assertTrue(operation.contains("REQ_ERROR"));
        assertTrue(operation.contains("REQ_RESPONSE"));
        assertTrue(operation.contains("REQ_WAS_CACHED"));
        assertTrue(operation.contains("REQ_CANCEL"));
        assertEquals(6, occurrences(modes, "REQ_"));
        assertTrue(states.contains("PENDING"));
        assertTrue(states.contains("EXECUTING"));
        assertTrue(states.contains("COMPLETED"));
        assertTrue(states.contains("ABANDONED"));
        assertTrue(operation.contains("bool response_was_cached_ = false;"));
        assertEquals(6, occurrences(nativeDispatcher, "new URLRequestOperation(this,"));
        assertTrue(nativeDispatcher.contains("owner->url_request_->ResponseWasCached()"));
        assertTrue(nativeDispatcher.contains("URLRequestOperation::REQ_WAS_CACHED"));
        assertTrue(clientImplementation.contains("JNI_CALL_VOID_METHOD(env, client, \"onRequestComplete\", \"(Lorg/cef/network/CefURLRequest;)V\""));
        assertTrue(javaNative.contains("request_ = request;"));
        assertTrue(javaNative.contains("client_ = client;"));
        assertTrue(javaNative.contains("return request_;"));
        assertTrue(javaNative.contains("return client_;"));
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
}
