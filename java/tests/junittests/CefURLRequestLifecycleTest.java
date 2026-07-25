// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String dispatchTimeouts = section(implementation, "constexpr auto kPendingDispatchTimeout", "enum class URLRequestOperationPhase");
        String operationState = section(implementation, "class URLRequestOperationState", "class URLRequestLifecycle");
        String beginExecuting = section(operationState, "bool BeginExecuting(", "void ClearExecutingOwner()");
        String notifier = section(operationState, "void NotifyForTesting()", "void EnableWaitObservationForTesting()");
        String waitObservation = section(operationState, "bool WaitForWaitCountForTesting(", "bool WaitForExecutingCompletionWaitForTesting(");
        String tokenHelpers = section(implementation, "bool ReportAndClearJNIException(", "void RollBackURLRequestToken(");
        String scenarioReporter = section(implementation, "bool ReportSyntheticTestScenario(", "}  // namespace");
        String productionDispatch = section(implementation, "bool Dispatch(CefThreadId thread_id)", "bool created() const");
        String syntheticDispatch = section(implementation, "bool DispatchPostedForTesting(", "bool DispatchPosted(");
        String dispatch = section(implementation, "bool DispatchPosted(", "std::shared_ptr<URLRequestOperationState> state_");
        String pendingWait = section(dispatch, "while (state_->phase_ == PENDING)", "if (state_->phase_ == EXECUTING)");
        String stateHook = section(implementation, "bool URLRequestOperation::RunStateMachineForTesting()", "namespace {");
        String pendingScenario = section(stateHook, "struct PendingControl", "struct ExecutionGate");
        String executingScenario = section(stateHook, "struct ExecutionGate", "const bool pending_close_valid");
        String lifecycleHook = section(implementation, "bool RunURLRequestLifecycleForTesting(", null);

        assertTrue(tokenHelpers.contains("GetFieldID(url_request_class, \"N_CefHandle\", \"J\")"));
        assertTrue(tokenHelpers.contains("std::fprintf(stderr"));
        assertFalse(tokenHelpers.contains("LOG(ERROR)"));
        assertFalse(tokenHelpers.contains("ScopedJNIString"));
        assertTrue(dispatchTimeouts.contains("kPendingDispatchTimeout = std::chrono::seconds(5)"));
        assertTrue(dispatchTimeouts.contains("kSyntheticTestWatchdogTimeout = std::chrono::seconds(10)"));
        assertTrue(dispatchTimeouts.contains("kNonExpiringPendingDispatchTimeoutForTesting = std::chrono::hours(1)"));
        assertOccurrenceCount(implementation, "kPendingDispatchTimeout, true, false", 1);
        assertOccurrenceCount(implementation, "DispatchPosted(post_task, pending_timeout, false, true)", 1);
        assertTrue(productionDispatch.contains("kPendingDispatchTimeout, true, false"));
        assertTrue(syntheticDispatch.contains("DispatchPosted(post_task, pending_timeout, false, true)"));
        assertOrdered(dispatch, "auto deadline = std::chrono::steady_clock::now() + pending_timeout", "const bool accepted = state_->IsPhase(PENDING) && post_task(task)", "if (restart_deadline_after_post_for_testing)", "std::unique_lock<std::mutex> lock(state_->lock_)", "while (state_->phase_ == PENDING)", "wait_until(lock, deadline)");
        assertOccurrenceCount(dispatch, "std::chrono::steady_clock::now() + pending_timeout", 2);
        assertOccurrenceCount(pendingWait, "wait_until(lock, deadline)", 1);
        assertFalse(pendingWait.contains("deadline ="));
        assertFalse(pendingWait.contains("wait_for("));
        assertOrdered(beginExecuting, "phase_ = URLRequestOperationPhase::EXECUTING", "*owner = owner_", "completion_condition_.notify_all()");
        assertTrue(beginExecuting.contains("    }\n    completion_condition_.notify_all();"));
        assertOrdered(notifier, "std::lock_guard<std::mutex> lock(lock_)", "completion_condition_.notify_all()");
        assertOrdered(waitObservation, "wait_observer_ready_for_testing_ = true", "test_condition_.notify_all()", "WaitForWaitObserverForTesting", "return test_condition_.wait_for(lock, timeout, [this]() { return wait_observer_ready_for_testing_; })");
        assertOrdered(pendingScenario, "WaitForWaitObserverForTesting(kSyntheticTestWatchdogTimeout)", "observer_watchdog_forced_abandonment = !observer_ready", "WaitForWaitCountForTesting(wait_count, kSyntheticTestWatchdogTimeout)", "NotifyForTesting()");
        assertOccurrenceCount(stateHook, "const bool scenario_valid = !watchdog_forced_cleanup", 5);
        assertOccurrenceCount(stateHook, "scenario_valid, watchdog_forced_cleanup)", 5);
        assertOrdered(scenarioReporter, "if (!valid)", "std::fprintf(stderr", "synthetic test scenario failed: %s", "watchdog-forced cleanup: %s");
        assertTrue(stateHook.contains("ReportSyntheticTestScenario(\"rejected post\", scenario_valid, watchdog_forced_cleanup)"));
        assertTrue(stateHook.contains("ReportSyntheticTestScenario(\"accepted pending timeout\", scenario_valid, watchdog_forced_cleanup)"));
        assertTrue(stateHook.contains("ReportSyntheticTestScenario(\"executing operation wins pending race\", scenario_valid, watchdog_forced_cleanup)"));
        assertTrue(stateHook.contains("ReportSyntheticTestScenario(\"pending operation lifecycle close\", scenario_valid, watchdog_forced_cleanup)"));
        assertTrue(stateHook.contains("ReportSyntheticTestScenario(\"executing operation lifecycle close\", scenario_valid, watchdog_forced_cleanup)"));
        assertTrue(pendingScenario.contains("deadline_not_shortened = control->timeout_wait_started && timeout_wait_elapsed >= kPendingDispatchTimeoutForTesting"));
        assertFalse(pendingScenario.contains("<= kPendingDispatchTimeoutForTesting"));
        assertFalse(pendingScenario.contains("std::chrono::milliseconds(500)"));
        assertFalse(stateHook.contains("watchdog_timeout"));
        assertFalse(stateHook.contains("std::chrono::seconds(1)"));
        assertFalse(stateHook.contains("std::chrono::hours(1)"));
        assertTrue(executingScenario.contains("DispatchPostedForTesting(poster, kNonExpiringPendingDispatchTimeoutForTesting)"));
        assertTrue(executingScenario.contains("WaitForExecutingCompletionWaitForTesting(kSyntheticTestWatchdogTimeout)"));
        assertTrue(stateHook.contains("late_task->Execute()"));
        assertTrue(stateHook.contains("dispatcher.detach()"));
        assertTrue(stateHook.contains("captures only heap control"));
        assertTrue(stateHook.contains("RunPendingCloseForTesting()"));
        assertTrue(stateHook.contains("RunExecutingCloseForTesting()"));
        assertTrue(stateHook.contains("retained_admission = URLRequestAdmission()"));
        assertTrue(stateHook.contains("close_blocked"));
        assertTrue(lifecycleHook.contains("WaitForLifecycleWaitersForTesting(1, 1, kSyntheticTestWatchdogTimeout)"));
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

    private static void assertOccurrenceCount(String source, String marker, int expected) {
        int count = 0;
        for (int index = source.indexOf(marker); index >= 0; index = source.indexOf(marker, index + marker.length())) count++;
        assertEquals(expected, count, "Unexpected occurrence count for source marker: " + marker);
    }
}
