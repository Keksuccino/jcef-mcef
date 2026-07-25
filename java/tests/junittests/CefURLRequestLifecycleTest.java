// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class CefURLRequestLifecycleTest {
    @Test
    void shutdownEntrypointsQuiesceTheBridgeBeforeCefTeardown() throws Exception {
        String app = readSource("native/CefApp.cpp");
        String mac = readSource("native/util_mac.mm");
        String initialize = section(app, "Java_org_cef_CefApp_N_1Initialize(", "Java_org_cef_CefApp_N_1AbortInitialization(");
        String abort = section(app, "Java_org_cef_CefApp_N_1AbortInitialization(", "Java_org_cef_CefApp_N_1Shutdown(");
        String shutdown = section(app, "Java_org_cef_CefApp_N_1Shutdown(", "Java_org_cef_CefApp_N_1DoMessageLoopWorkNative(");
        String directMacShutdown = section(mac, "+ (void)shutdown {", "+ (void)doMessageLoopWork {");

        assertOrdered(initialize, "context->Initialize(env, c, appHandler, jsettings)", "OpenURLRequestLifecycle()");
        assertOrdered(abort, "CloseURLRequestLifecycle()", "ClearJNIReferences(env)", "Context::Destroy()");
        assertOrdered(shutdown, "CloseURLRequestLifecycle()", "context->Shutdown()", "ClearJNIReferences(env)", "Context::Destroy()");
        assertOrdered(directMacShutdown, "CloseURLRequestLifecycle()", "CefShutdown()", "g_client_app_ = nullptr");
        assertTrue(shutdown.contains("JCEF-owned registry references"));
        assertTrue(shutdown.contains("CEF may retain its own"));
    }

    @Test
    void lifecycleLinearizesAdmissionsCloseGenerationsAndOutOfLockRelease() throws Exception {
        String implementation = readSource("native/url_request.cpp");
        String header = readSource("native/url_request.h");
        String lifecycle = section(implementation, "class URLRequestLifecycle", "namespace {");
        String admission = section(lifecycle, "URLRequestAdmission AcquireAdmission()", "URLRequestAccess AcquireAccess(");
        String access = section(lifecycle, "URLRequestAccess AcquireAccess(", "URLRequestAccess TakeAccess(");
        String take = section(lifecycle, "URLRequestAccess TakeAccess(", "jlong Register(");
        String completeOperation = section(lifecycle, "void CompleteOperation(", "void ReleaseAdmission()");
        String open = section(lifecycle, "void Open()", "void Close()");
        String close = section(lifecycle, "void Close()", "Phase phase_for_testing()");
        String accessRelease = section(implementation, "void URLRequestAccess::Release()", "URLRequest::URLRequest(");

        assertOrdered(admission, "std::lock_guard<std::mutex> lock(lock_)", "phase_ != Phase::OPEN", "++active_admissions_");
        assertOrdered(access, "std::lock_guard<std::mutex> lock(lock_)", "phase_ != Phase::OPEN", "requests_.find(token)", "++active_admissions_", "owner = request->second");
        assertOrdered(take, "std::lock_guard<std::mutex> lock(lock_)", "phase_ != Phase::OPEN", "owner = request->second", "requests_.erase(request)");
        assertTrue(lifecycle.contains("std::condition_variable quiescence_condition_;"));
        assertTrue(lifecycle.contains("std::shared_ptr<CloseGeneration> close_generation_;"));
        assertTrue(lifecycle.contains("std::unordered_map<URLRequestOperationState*, std::shared_ptr<URLRequestOperationState>> operations_;"));
        assertOrdered(completeOperation, "removed = std::move(registered->second)", "--active_admissions_", "removed.reset()");
        assertTrue(open.contains("next_token_ deliberately survives every close/open cycle"));
        assertFalse(open.contains("next_token_ ="));
        assertTrue(close.contains("close_generation = close_generation_;"));
        assertTrue(close.contains("return close_generation->completed;"));
        assertOrdered(close, "phase_ = Phase::CLOSING", "operations.push_back(operation.second)", "operation->AbandonPending()", "active_admissions_ == 0", "drained_requests.swap(requests_)", "drained_requests.clear()", "phase_ = Phase::CLOSED", "close_generation->completed = true");
        assertOrdered(accessRelease, "owner_ = nullptr", "admission_.Release()");
        assertTrue(header.contains("URLRequestAdmission AcquireURLRequestCreationAdmission();"));
        assertTrue(header.contains("URLRequestAccess AcquireURLRequestAccess(jlong token);"));
        assertTrue(header.contains("void OpenURLRequestLifecycle();"));
        assertTrue(header.contains("void CloseURLRequestLifecycle();"));
    }

    @Test
    void everyJniEntryAcquiresTheBarrierBeforeRawCefConversion() throws Exception {
        String bindings = readSource("native/CefURLRequest_N.cpp");
        String frameBindings = readSource("native/CefFrame_N.cpp");
        String implementation = readSource("native/url_request.cpp");
        String legacyCreate = section(bindings, "Java_org_cef_network_CefURLRequest_1N_N_1Create(", "Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(");
        String contextCreate = section(bindings, "Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(", "Java_org_cef_network_CefURLRequest_1N_N_1Dispose(");
        String response = section(bindings, "Java_org_cef_network_CefURLRequest_1N_N_1GetResponse(", "Java_org_cef_network_CefURLRequest_1N_N_1ResponseWasCached(");
        String frameCreate = section(frameBindings, "Java_org_cef_browser_CefFrame_1N_N_1CreateURLRequest(", "Java_org_cef_browser_CefFrame_1N_N_1ExecuteJavaScript(");
        String standaloneCreate = section(implementation, "bool CreateStandaloneURLRequest(", "jobject CreateFrameURLRequest(");
        String commonCreate = section(implementation, "bool CreateURLRequest(", "}  // namespace");

        assertOrdered(legacyCreate, "AcquireURLRequestCreationAdmission()", "CreateStandaloneURLRequest(");
        assertOrdered(contextCreate, "AcquireURLRequestCreationAdmission()", "GetCefFromJNIObject<CefRequestContext>", "CreateStandaloneURLRequest(");
        assertOrdered(frameCreate, "AcquireURLRequestCreationAdmission()", "GetSelf(self)", "CreateFrameURLRequest(");
        assertOrdered(standaloneCreate, "if (!admission", "GetRequest(env, jrequest)", "CreateURLRequest(");
        assertOrdered(commonCreate, "URLRequestClient::Create", "new URLRequest", "Register(admission, url_request)", "PublishURLRequestToken", "url_request->Create()");
        assertOrdered(response, "AcquireURLRequestAccess(self)", "url_request->GetResponse()", "ScopedJNIResponse jresponse");
    }

    @Test
    void lateTokenHandlingAndRegressionHooksRemainCefIndependentAndBounded() throws Exception {
        String implementation = readSource("native/url_request.cpp");
        String javaApi = readSource("java/org/cef/network/CefURLRequest.java");
        String tokenHelpers = section(implementation, "bool ReportAndClearJNIException(", "void RollBackURLRequestToken(");
        String dispatch = section(implementation, "bool DispatchPosted(", "std::shared_ptr<URLRequestOperationState> state_");
        String stateHook = section(implementation, "bool URLRequestOperation::RunStateMachineForTesting()", "namespace {");
        String lifecycleHook = section(implementation, "bool RunURLRequestLifecycleForTesting(", null);

        assertTrue(tokenHelpers.contains("GetFieldID(url_request_class, \"N_CefHandle\", \"J\")"));
        assertTrue(tokenHelpers.contains("std::fprintf(stderr"));
        assertFalse(tokenHelpers.contains("LOG(ERROR)"));
        assertFalse(tokenHelpers.contains("ScopedJNIString"));
        assertTrue(dispatch.contains("const auto deadline = std::chrono::steady_clock::now() + pending_timeout;"));
        assertTrue(dispatch.contains("wait_until(lock, deadline)"));
        assertTrue(dispatch.indexOf("deadline =", dispatch.indexOf("deadline =") + 1) < 0);
        assertTrue(stateHook.contains("return false; }, watchdog_timeout"));
        assertTrue(stateHook.contains("WaitForWaitCountForTesting"));
        assertTrue(stateHook.contains("deadline_preserved"));
        assertTrue(stateHook.contains("late_task->Execute()"));
        assertTrue(stateHook.contains("WaitForExecutingCompletionWaitForTesting"));
        assertTrue(stateHook.contains("dispatcher.detach()"));
        assertTrue(stateHook.contains("captures only heap control"));
        assertTrue(stateHook.contains("RunPendingCloseForTesting()"));
        assertTrue(stateHook.contains("RunExecutingCloseForTesting()"));
        assertTrue(stateHook.contains("retained_admission = URLRequestAdmission()"));
        assertTrue(stateHook.contains("close_blocked"));
        assertTrue(lifecycleHook.contains("WaitForLifecycleWaitersForTesting(1, 1, watchdog_timeout)"));
        assertTrue(lifecycleHook.contains("second_token > first_token"));
        assertTrue(lifecycleHook.contains("final_close_completed = control->condition.wait_for"));
        assertTrue(lifecycleHook.contains("final_closer.detach()"));
        assertTrue(lifecycleHook.contains("PublishURLRequestToken(env, jurl_request, first_token)"));
        assertTrue(lifecycleHook.contains("ClearJavaURLRequestTokenIfMatches(env, jurl_request, first_token)"));
        assertTrue(javaApi.contains("late {@link #dispose()} or stale"));
        assertTrue(javaApi.contains("callers must dispose the {@link CefRequest}"));
        assertTrue(javaApi.contains("every {@link CefResponse}"));
        assertTrue(javaApi.contains("shutdown sequence begins"));
        assertTrue(javaApi.contains("before {@link org.cef.CefApp} shutdown begins"));
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

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker);
            assertTrue(current > previous, "Missing or out-of-order source marker: " + marker);
            previous = current;
        }
    }
}
