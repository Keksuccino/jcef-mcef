// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefNative;
import org.cef.callback.CefURLRequestClient;
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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class CefURLRequestClientTest {
    private static final String REQUEST_DESCRIPTOR = "Lorg/cef/network/CefURLRequest;";
    private static final String LEGACY_INTERFACE_SOURCE = "package org.cef.callback;\n"
            + "import org.cef.network.CefURLRequest;\n"
            + "public interface CefURLRequestClient extends CefNative {\n"
            + "    void onRequestComplete(CefURLRequest request);\n"
            + "    void onUploadProgress(CefURLRequest request, int current, int total);\n"
            + "    void onDownloadProgress(CefURLRequest request, int current, int total);\n"
            + "    void onDownloadData(CefURLRequest request, byte[] data, int dataLength);\n"
            + "    boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback);\n"
            + "}\n";
    private static final String LEGACY_CLIENT_SOURCE = "package legacy;\n"
            + "import org.cef.callback.CefAuthCallback;\n"
            + "import org.cef.callback.CefURLRequestClient;\n"
            + "import org.cef.network.CefURLRequest;\n"
            + "public final class LegacyClient implements CefURLRequestClient {\n"
            + "    private int current;\n"
            + "    private int total;\n"
            + "    private long nativeRef;\n"
            + "    public void onRequestComplete(CefURLRequest request) {}\n"
            + "    public void onUploadProgress(CefURLRequest request, int current, int total) {}\n"
            + "    public void onDownloadProgress(CefURLRequest request, int current, int total) { this.current = current; this.total = total; }\n"
            + "    public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {}\n"
            + "    public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) { return false; }\n"
            + "    public void setNativeRef(String identifier, long nativeRef) { this.nativeRef = nativeRef; }\n"
            + "    public long getNativeRef(String identifier) { return nativeRef; }\n"
            + "    public int current() { return current; }\n"
            + "    public int total() { return total; }\n"
            + "}\n";

    @Test
    void legacyProgressCallbacksSaturateWithoutWrapping() {
        long[] values = {Long.MIN_VALUE, (long) Integer.MIN_VALUE - 1, Integer.MIN_VALUE, -1, 0,
                Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE};
        int[] expected = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, -1, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        LegacyClient client = new LegacyClient();

        for (int index = 0; index < values.length; index++) {
            client.onUploadProgress(null, values[index], values[values.length - index - 1]);
            assertEquals(expected[index], client.uploadCurrent_);
            assertEquals(expected[expected.length - index - 1], client.uploadTotal_);
            client.onDownloadProgress(null, values[index], values[values.length - index - 1]);
            assertEquals(expected[index], client.downloadCurrent_);
            assertEquals(expected[expected.length - index - 1], client.downloadTotal_);
        }
    }

    @Test
    void longProgressOverridesReceiveExactInt64Values() {
        LongClient client = new LongClient();
        client.onUploadProgress(null, Long.MAX_VALUE, Long.MIN_VALUE);
        client.onDownloadProgress(null, Long.MIN_VALUE, Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, client.uploadCurrentLong_);
        assertEquals(Long.MIN_VALUE, client.uploadTotalLong_);
        assertEquals(Long.MIN_VALUE, client.downloadCurrentLong_);
        assertEquals(Long.MAX_VALUE, client.downloadTotalLong_);
    }

    @Test
    void retainsLegacyDefaultDescriptorsAndAddsDefaultLongDescriptors() throws Exception {
        Method uploadInt = CefURLRequestClient.class.getMethod("onUploadProgress", CefURLRequest.class, int.class, int.class);
        Method uploadLong = CefURLRequestClient.class.getMethod("onUploadProgress", CefURLRequest.class, long.class, long.class);
        Method downloadInt = CefURLRequestClient.class.getMethod("onDownloadProgress", CefURLRequest.class, int.class, int.class);
        Method downloadLong = CefURLRequestClient.class.getMethod("onDownloadProgress", CefURLRequest.class, long.class, long.class);

        assertFalse(Modifier.isAbstract(uploadInt.getModifiers()));
        assertFalse(Modifier.isAbstract(downloadInt.getModifiers()));
        assertTrue(uploadInt.isDefault());
        assertTrue(downloadInt.isDefault());
        assertTrue(uploadInt.isAnnotationPresent(Deprecated.class));
        assertTrue(downloadInt.isAnnotationPresent(Deprecated.class));
        assertTrue(uploadLong.isDefault());
        assertTrue(downloadLong.isDefault());
        assertTrue(CefNative.class.isAssignableFrom(CefURLRequestClient.class));
        assertEquals("(" + REQUEST_DESCRIPTOR + "II)V", MethodType.methodType(void.class, CefURLRequest.class, int.class, int.class).toMethodDescriptorString());
        assertEquals("(" + REQUEST_DESCRIPTOR + "JJ)V", MethodType.methodType(void.class, CefURLRequest.class, long.class, long.class).toMethodDescriptorString());
    }

    @Test
    void previouslyCompiledLegacyClientUsesNewDefaultCallbacks(@TempDir Path temporaryDirectory) throws Exception {
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path interfaceSource = writeSource(sourceDirectory, "org/cef/callback/CefURLRequestClient.java", LEGACY_INTERFACE_SOURCE);
        Path clientSource = writeSource(sourceDirectory, "legacy/LegacyClient.java", LEGACY_CLIENT_SOURCE);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require the project-mandated JDK 17, not a JRE");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        String productionClasses = Path.of(CefURLRequestClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String compilerClassPath = productionClasses + File.pathSeparator + System.getProperty("java.class.path");
        int result = compiler.run(null, null, diagnostics, "--release", "17", "-classpath", compilerClassPath, "-d", classesDirectory.toString(), interfaceSource.toString(), clientSource.toString());
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));

        URL[] classPath = {classesDirectory.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classPath, CefURLRequestClient.class.getClassLoader())) {
            Class<?> clientClass = Class.forName("legacy.LegacyClient", true, loader);
            CefURLRequestClient client = (CefURLRequestClient) clientClass.getConstructor().newInstance();
            client.onDownloadProgress(null, (long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1);
            assertEquals(Integer.MAX_VALUE, clientClass.getMethod("current").invoke(client));
            assertEquals(Integer.MIN_VALUE, clientClass.getMethod("total").invoke(client));
        }
    }

    @Test
    void nativeBridgeUsesPerRequestWrappersLongDescriptorsAndTerminalCleanup() throws Exception {
        String implementation = readSource("native/url_request_client.cpp");
        String header = readSource("native/url_request_client.h");
        String helperImplementation = readSource("native/jni_scoped_helpers.cpp");
        String dispatcher = readSource("native/url_request.cpp");
        String nativeBindings = readSource("native/CefURLRequest_N.cpp");
        String cancelPending = section(implementation, "void CancelPendingRequest(", "}  // namespace");
        String create = section(implementation, "CefRefPtr<URLRequestClient> URLRequestClient::Create(", "void URLRequestClient::OnRequestComplete(");
        String complete = section(implementation, "void URLRequestClient::OnRequestComplete(", "void URLRequestClient::OnUploadProgress(");
        String snapshots = section(implementation, "bool URLRequestClient::CreateLocalRefsLocked(", "bool URLRequestClient::SnapshotJavaHandles(");
        String snapshot = section(implementation, "bool URLRequestClient::SnapshotJavaHandles(", "bool URLRequestClient::CompleteJavaHandles(");
        String terminal = section(implementation, "bool URLRequestClient::CompleteJavaHandles(", null);
        String downloadData = section(implementation, "void URLRequestClient::OnDownloadData(", "bool URLRequestClient::GetAuthCredentials(");
        String dispatch = section(dispatcher, "bool DispatchPosted(", "std::shared_ptr<URLRequestOperationState> state_");
        String execute = section(dispatcher, "void Execute() override", "static bool RunStateMachineForTesting()");
        String standaloneCreate = section(dispatcher, "bool CreateStandaloneURLRequest(", "jobject CreateFrameURLRequest(");
        String commonCreate = section(dispatcher, "bool CreateURLRequest(", "}  // namespace");
        String nativeCreate = section(nativeBindings, "Java_org_cef_network_CefURLRequest_1N_N_1Create(", "JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(");
        String globalDestructor = section(helperImplementation, "ScopedJNIObjectGlobal::~ScopedJNIObjectGlobal()", "void ScopedJNIObjectGlobal::Clear(");
        String globalClear = section(helperImplementation, "void ScopedJNIObjectGlobal::Clear(", "jobject ScopedJNIObjectGlobal::get(");

        assertTrue(create.contains("return new URLRequestClient(env, jURLRequestClient, jURLRequest);"));
        assertFalse(create.contains("GetCefFromJNIObject"));
        assertFalse(create.contains("SetCefForJNIObject"));
        assertTrue(complete.contains("CompleteJavaHandles(env, &jclient, &jrequest)"));
        assertTrue(complete.contains("ScopedJNIObjectLocal client(env, jclient);"));
        assertTrue(complete.contains("ScopedJNIObjectLocal request_snapshot(env, jrequest);"));
        assertTrue(complete.contains("JNI_CALL_VOID_METHOD(env, client, \"onRequestComplete\""));
        assertFalse(complete.contains("client_handle_"));
        assertFalse(complete.contains("request_handle_"));
        assertEquals(2, occurrences(snapshots, "env->NewLocalRef("));
        assertTrue(snapshots.indexOf("client_snapshot_exception = DescribeAndClearJNIException(env)") < snapshots.indexOf("if (!*jURLRequestClient || client_snapshot_exception)"));
        assertTrue(snapshots.indexOf("request_snapshot_exception = DescribeAndClearJNIException(env)") < snapshots.indexOf("if (!*jURLRequest || request_snapshot_exception)"));
        assertTrue(snapshot.contains("std::lock_guard<std::mutex> lock(java_handles_lock_);"));
        int completed = terminal.indexOf("completed_ = true;");
        int terminalSnapshot = terminal.indexOf("CreateLocalRefsLocked(env, jURLRequestClient, jURLRequest);");
        int requestClear = terminal.indexOf("request_handle_.Clear(env);");
        int clientClear = terminal.indexOf("client_handle_.Clear(env);");
        assertTrue(completed >= 0 && completed < terminalSnapshot);
        assertTrue(terminalSnapshot < requestClear && requestClear < clientClear);
        int mutexField = header.indexOf("std::mutex java_handles_lock_;");
        int completedField = header.indexOf("bool completed_ = false;");
        int clientField = header.indexOf("ScopedJNIObjectGlobal client_handle_;");
        int requestField = header.indexOf("ScopedJNIObjectGlobal request_handle_;");
        assertTrue(mutexField >= 0 && mutexField < completedField && completedField < clientField && clientField < requestField);
        assertEquals(2, occurrences(implementation, "(Lorg/cef/network/CefURLRequest;JJ)V"));
        assertEquals(0, occurrences(implementation, "(Lorg/cef/network/CefURLRequest;II)V"));
        assertEquals(2, occurrences(implementation, "static_cast<jlong>(current)"));
        assertEquals(2, occurrences(implementation, "static_cast<jlong>(total)"));
        assertEquals(2, occurrences(header, "int64_t current"));
        assertEquals(2, occurrences(header, "int64_t total"));
        assertTrue(downloadData.contains("std::numeric_limits<jsize>::max()"));
        assertTrue(downloadData.contains("data_length != 0 && data == nullptr"));
        assertTrue(downloadData.contains("env->NewByteArray(jdata_length)"));
        assertTrue(downloadData.contains("static_cast<jint>(jdata_length)"));
        assertEquals(6, occurrences(downloadData, "CancelPendingRequest(request);"));
        int pendingStatus = cancelPending.indexOf("request->GetRequestStatus() == UR_IO_PENDING");
        int pendingCancel = cancelPending.indexOf("request->Cancel();");
        assertTrue(pendingStatus >= 0 && pendingStatus < pendingCancel);
        assertTrue(dispatcher.contains("class URLRequestOperation : public CefTask"));
        assertTrue(dispatcher.contains("const Mode mode_;"));
        assertEquals(6, occurrences(dispatcher, "new URLRequestOperation(this,"));
        assertFalse(dispatcher.contains("mode_ = mode"));
        assertTrue(dispatcher.contains("PENDING"));
        assertTrue(dispatcher.contains("EXECUTING"));
        assertTrue(dispatcher.contains("COMPLETED"));
        assertTrue(dispatcher.contains("ABANDONED"));
        int deadline = dispatch.indexOf("std::chrono::steady_clock::now() + pending_timeout");
        int post = dispatch.indexOf("const bool accepted = state_->IsPhase(PENDING) && post_task(task);");
        int stateLock = dispatch.indexOf("std::unique_lock<std::mutex> lock(state_->lock_);");
        assertTrue(deadline >= 0 && deadline < post && post < stateLock, "Posting must not run under the operation state lock");
        assertTrue(dispatch.contains("while (state_->phase_ == PENDING)"));
        assertTrue(dispatch.contains("state_->completion_condition_.wait_until(lock, deadline)"));
        assertTrue(dispatch.contains("state_->phase_ = ABANDONED;"));
        assertTrue(dispatch.contains("abandoned_owner = state_->owner_;"));
        assertTrue(dispatch.contains("state_->owner_ = nullptr;"));
        int releaseOwner = dispatch.indexOf("abandoned_owner = nullptr;");
        assertTrue(releaseOwner > stateLock);
        assertTrue(dispatch.contains("if (state_->phase_ == EXECUTING)"));
        assertTrue(dispatch.contains("state_->completion_condition_.wait(lock, [this]() { return state_->phase_ == COMPLETED; });"));
        int executing = execute.indexOf("state_->BeginExecuting(&owner)");
        int effect = execute.indexOf("switch (mode_)");
        int clearOwner = execute.indexOf("state_->ClearExecutingOwner();");
        int completedState = execute.indexOf("state_->MarkCompleted();");
        assertTrue(executing >= 0 && executing < effect && effect < completedState);
        assertTrue(effect < clearOwner && clearOwner < completedState);
        assertTrue(execute.contains("owner->url_request_->GetRequestStatus() == UR_IO_PENDING"));
        assertTrue(execute.contains("owner->url_request_->Cancel();"));
        int admissionAcquire = nativeCreate.indexOf("URLRequestAdmission admission = AcquireURLRequestCreationAdmission();");
        int invalidGuard = nativeCreate.indexOf("if (!admission || !jrequest || !jrequest_client)");
        int commonCall = nativeCreate.indexOf("CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, nullptr, admission);");
        int requestBinding = standaloneCreate.indexOf("GetRequest(env, jrequest)");
        int clientBinding = commonCreate.indexOf("URLRequestClient::Create(env, jrequest_client, jurl_request)");
        assertTrue(admissionAcquire >= 0 && admissionAcquire < invalidGuard && invalidGuard < commonCall);
        assertTrue(requestBinding >= 0 && standaloneCreate.indexOf("CreateURLRequest(env, jurl_request, request, jrequest_client, request_context, nullptr, admission)") > requestBinding);
        assertTrue(clientBinding >= 0);
        assertTrue(globalDestructor.contains("Clear(env);"));
        assertTrue(globalClear.contains("env->DeleteGlobalRef(jhandle_);"));
        assertTrue(globalClear.contains("jhandle_ = nullptr;"));
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
        if (endMarker == null) return source.substring(start);
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

    private abstract static class BaseClient implements CefURLRequestClient {
        private long nativeRef_;

        @Override
        public void setNativeRef(String identifier, long nativeRef) {
            nativeRef_ = nativeRef;
        }

        @Override
        public long getNativeRef(String identifier) {
            return nativeRef_;
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {}

        @Override
        public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {}

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
            return false;
        }
    }

    private static class LegacyClient extends BaseClient {
        private int uploadCurrent_;
        private int uploadTotal_;
        private int downloadCurrent_;
        private int downloadTotal_;

        @Override
        public void onUploadProgress(CefURLRequest request, int current, int total) {
            uploadCurrent_ = current;
            uploadTotal_ = total;
        }

        @Override
        public void onDownloadProgress(CefURLRequest request, int current, int total) {
            downloadCurrent_ = current;
            downloadTotal_ = total;
        }
    }

    private static final class LongClient extends BaseClient {
        private long uploadCurrentLong_;
        private long uploadTotalLong_;
        private long downloadCurrentLong_;
        private long downloadTotalLong_;

        @Override
        public void onUploadProgress(CefURLRequest request, long current, long total) {
            uploadCurrentLong_ = current;
            uploadTotalLong_ = total;
        }

        @Override
        public void onDownloadProgress(CefURLRequest request, long current, long total) {
            downloadCurrentLong_ = current;
            downloadTotalLong_ = total;
        }
    }
}
