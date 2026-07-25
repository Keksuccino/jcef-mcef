// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "permission_util.h"
#include "permission_callback_state.h"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <future>
#include <iostream>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace {

int failure_count = 0;

class TestPermissionCallbackOwner final : public PermissionCallbackOwner {
 public:
  explicit TestPermissionCallbackOwner(PermissionCallbackRegistry* registry = nullptr)
      : registry_(registry) {}

  void OnPermissionCallbackTerminal(PermissionCallbackState* callback) override {
    if (registry_)
      registry_->Remove(callback);
    terminal_calls_.fetch_add(1, std::memory_order_relaxed);
  }

  int terminal_calls() const {
    return terminal_calls_.load(std::memory_order_relaxed);
  }

 private:
  PermissionCallbackRegistry* const registry_;
  std::atomic<int> terminal_calls_{0};

  IMPLEMENT_REFCOUNTING(TestPermissionCallbackOwner);
};

class MediaCallbackControl {
 public:
  void EnterAndWait() {
    std::unique_lock<std::mutex> lock_scope(lock_);
    entered_ = true;
    entered_condition_.notify_all();
    release_condition_.wait(lock_scope, [this]() { return !blocked_; });
    if (reentrant_state_)
      reentrant_state_->Invalidate();
  }

  void Block() {
    std::lock_guard<std::mutex> lock_scope(lock_);
    blocked_ = true;
  }

  bool WaitUntilEntered(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock_scope(lock_);
    return entered_condition_.wait_for(lock_scope, timeout, [this]() { return entered_; });
  }

  void ReleaseBlock() {
    std::lock_guard<std::mutex> lock_scope(lock_);
    blocked_ = false;
    release_condition_.notify_all();
  }

  void SetReentrantState(PermissionCallbackState* state) {
    std::lock_guard<std::mutex> lock_scope(lock_);
    reentrant_state_ = state;
  }

 private:
  std::mutex lock_;
  std::condition_variable entered_condition_;
  std::condition_variable release_condition_;
  bool entered_ = false;
  bool blocked_ = false;
  PermissionCallbackState* reentrant_state_ = nullptr;
};

class TestMediaAccessCallback final : public CefMediaAccessCallback {
 public:
  explicit TestMediaAccessCallback(std::atomic<int>* destructor_calls = nullptr, std::shared_ptr<MediaCallbackControl> control = std::make_shared<MediaCallbackControl>())
      : destructor_calls_(destructor_calls), control_(std::move(control)) {}

  void Continue(uint32_t allowed_permissions) override {
    allowed_permissions_.store(allowed_permissions, std::memory_order_relaxed);
    continue_calls_.fetch_add(1, std::memory_order_relaxed);
    control_->EnterAndWait();
  }

  void Cancel() override {
    cancel_calls_.fetch_add(1, std::memory_order_relaxed);
  }

  void SetReentrantState(PermissionCallbackState* state) {
    control_->SetReentrantState(state);
  }
  int continue_calls() const {
    return continue_calls_.load(std::memory_order_relaxed);
  }
  int cancel_calls() const {
    return cancel_calls_.load(std::memory_order_relaxed);
  }
  uint32_t allowed_permissions() const {
    return allowed_permissions_.load(std::memory_order_relaxed);
  }

 private:
  ~TestMediaAccessCallback() override {
    if (destructor_calls_)
      destructor_calls_->fetch_add(1, std::memory_order_relaxed);
  }

  std::atomic<int>* const destructor_calls_;
  const std::shared_ptr<MediaCallbackControl> control_;
  std::atomic<int> continue_calls_{0};
  std::atomic<int> cancel_calls_{0};
  std::atomic<uint32_t> allowed_permissions_{0};

  IMPLEMENT_REFCOUNTING(TestMediaAccessCallback);
};

class RaceStartGate {
 public:
  explicit RaceStartGate(int participant_count)
      : participant_count_(participant_count) {}

  void ArriveAndWait() {
    std::unique_lock<std::mutex> lock_scope(lock_);
    ++arrived_count_;
    condition_.notify_all();
    condition_.wait(lock_scope, [this]() { return started_; });
  }

  bool StartWhenAllArrive(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock_scope(lock_);
    const bool all_arrived = condition_.wait_for(lock_scope, timeout, [this]() { return arrived_count_ == participant_count_; });
    started_ = true;
    condition_.notify_all();
    return all_arrived;
  }

 private:
  const int participant_count_;
  std::mutex lock_;
  std::condition_variable condition_;
  int arrived_count_ = 0;
  bool started_ = false;
};

void Expect(bool condition, const char* description) {
  if (condition)
    return;
  std::cerr << "FAILED: " << description << std::endl;
  ++failure_count;
}

void TestExactMaskConversions() {
  constexpr std::array<uint32_t, 6> kMasks = {
      0U, 1U, 0x1FFFFFFFU, 0x80000000U, 0xDEADBEEFU, 0xFFFFFFFFU};
  for (uint32_t mask : kMasks)
    Expect(permission_util::PermissionMaskFromJNI(permission_util::PermissionMaskToJNI(mask)) == mask, "uint32 permission mask round-trip preserves every bit");
}

void TestExactPromptIdConversions() {
  constexpr std::array<uint64_t, 6> kPromptIds = {0ULL,
                                                  1ULL,
                                                  0x7FFFFFFFFFFFFFFFULL,
                                                  0x8000000000000000ULL,
                                                  0xDEADBEEF01234567ULL,
                                                  0xFFFFFFFFFFFFFFFFULL};
  for (uint64_t prompt_id : kPromptIds)
    Expect(permission_util::PromptIdFromJNI(permission_util::PromptIdToJNI(prompt_id)) == prompt_id, "uint64 prompt identifier round-trip preserves every bit");
}

void TestPermissionResultValidation() {
  Expect(permission_util::PermissionResultFromJNI(CEF_PERMISSION_RESULT_ACCEPT) == CEF_PERMISSION_RESULT_ACCEPT, "accept remains accept");
  Expect(permission_util::PermissionResultFromJNI(CEF_PERMISSION_RESULT_DENY) == CEF_PERMISSION_RESULT_DENY, "deny remains deny");
  Expect(permission_util::PermissionResultFromJNI(CEF_PERMISSION_RESULT_DISMISS) == CEF_PERMISSION_RESULT_DISMISS, "dismiss remains dismiss");
  Expect(permission_util::PermissionResultFromJNI(CEF_PERMISSION_RESULT_IGNORE) == CEF_PERMISSION_RESULT_IGNORE, "ignore remains ignore");
  Expect(permission_util::PermissionResultFromJNI(-1) == CEF_PERMISSION_RESULT_IGNORE, "negative result fails closed to ignore");
  Expect(permission_util::PermissionResultFromJNI(CEF_PERMISSION_RESULT_NUM_VALUES) == CEF_PERMISSION_RESULT_IGNORE, "NUM_VALUES sentinel fails closed to ignore");
  Expect(permission_util::PermissionResultFromJNI(99) == CEF_PERMISSION_RESULT_IGNORE, "future invalid result fails closed to ignore");
}

void TestOneShotGateRaces() {
  constexpr int kIterations = 250;
  constexpr int kThreadCount = 8;
  for (int iteration = 0; iteration < kIterations; ++iteration) {
    permission_util::OneShotGate gate;
    std::atomic<int> winners{0};
    std::array<std::thread, kThreadCount> threads;
    for (auto& thread : threads)
      thread = std::thread([&gate, &winners]() { if (gate.TryClaim()) winners.fetch_add(1, std::memory_order_relaxed); });
    for (auto& thread : threads)
      thread.join();
    Expect(winners.load(std::memory_order_relaxed) == 1, "exactly one racing callback claims completion");
    Expect(!gate.TryClaim(), "duplicate completion remains rejected after a race");
  }
}

void TestPendingCompletionActivationAndAbandonment() {
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> continued_callback =
      new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> continued_state =
      new MediaAccessCallbackState(1, continued_callback);
  Expect(continued_state->Continue(0x80000005U), "pending media continuation is accepted");
  Expect(continued_callback->continue_calls() == 0, "pending media continuation waits for handled result");
  continued_state->Activate(owner);
  Expect(continued_callback->continue_calls() == 1, "activation dispatches queued media continuation");
  Expect(continued_callback->allowed_permissions() == 0x80000005U, "queued media mask preserves unknown high bits");
  Expect(owner->terminal_calls() == 1, "completed media state unregisters from its owner");

  CefRefPtr<TestMediaAccessCallback> abandoned_callback =
      new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> abandoned_state =
      new MediaAccessCallbackState(1, abandoned_callback);
  Expect(abandoned_state->Continue(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE), "pre-return continuation is queued");
  abandoned_state->Abandon();
  abandoned_state->Activate(owner);
  Expect(abandoned_callback->continue_calls() == 0, "false handler result discards queued continuation");
}

void TestStateRejectsRacingDuplicateCompletions() {
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> callback = new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(2, callback);
  state->Activate(owner);

  constexpr int kThreadCount = 16;
  std::atomic<int> accepted{0};
  std::vector<std::thread> threads;
  threads.reserve(kThreadCount);
  for (int index = 0; index < kThreadCount; ++index)
    threads.emplace_back([state, &accepted, index]() { if (state->Continue(static_cast<uint32_t>(index + 1))) accepted.fetch_add(1, std::memory_order_relaxed); });
  for (auto& thread : threads)
    thread.join();

  Expect(accepted.load(std::memory_order_relaxed) == 1, "one state completion wins a native race");
  Expect(callback->continue_calls() == 1, "native callback dispatches once after racing completion");
  Expect(owner->terminal_calls() == 1, "racing completion unregisters exactly once");
}

void TestStateRejectsRacingContinueAndCancel() {
  constexpr int kIterations = 250;
  constexpr std::chrono::seconds kWatchdogTimeout(5);
  for (int iteration = 0; iteration < kIterations; ++iteration) {
    CefRefPtr<TestPermissionCallbackOwner> owner =
        new TestPermissionCallbackOwner();
    CefRefPtr<TestMediaAccessCallback> callback = new TestMediaAccessCallback();
    CefRefPtr<MediaAccessCallbackState> state =
        new MediaAccessCallbackState(iteration + 10, callback);
    state->Activate(owner);

    RaceStartGate start_gate(2);
    std::atomic<int> accepted{0};
    std::thread continuation([state, &start_gate, &accepted]() { start_gate.ArriveAndWait(); if (state->Continue(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE)) accepted.fetch_add(1, std::memory_order_relaxed); });
    std::thread cancellation([state, &start_gate, &accepted]() { start_gate.ArriveAndWait(); if (state->Cancel()) accepted.fetch_add(1, std::memory_order_relaxed); });
    const bool both_ready = start_gate.StartWhenAllArrive(kWatchdogTimeout);
    Expect(both_ready, "Continue-vs-Cancel contenders reach the race gate");
    continuation.join();
    cancellation.join();

    Expect(accepted.load(std::memory_order_relaxed) == 1, "exactly one Continue-vs-Cancel action is accepted");
    Expect(callback->continue_calls() + callback->cancel_calls() == 1, "exactly one Continue-vs-Cancel CEF action is dispatched");
    Expect(owner->terminal_calls() == 1, "Continue-vs-Cancel race unregisters exactly once");
  }
}

void TestInvalidationIsNonBlockingAndLifecycleDrainsDispatch() {
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "permission lifecycle opens");
  Expect(lifecycle.Open(), "permission lifecycle open is idempotent");
  std::atomic<int> destructor_calls{0};
  std::shared_ptr<MediaCallbackControl> callback_control =
      std::make_shared<MediaCallbackControl>();
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> callback =
      new TestMediaAccessCallback(&destructor_calls, callback_control);
  callback_control->Block();
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(3, callback, &lifecycle);
  state->Activate(owner);
  callback = nullptr;

  std::thread completion([state]() { state->Continue(CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE); });
  const bool dispatch_entered =
      callback_control->WaitUntilEntered(std::chrono::seconds(5));
  Expect(dispatch_entered, "media continuation enters the controlled CEF callback");
  Expect(lifecycle.WaitForActiveLeasesForTesting(1, std::chrono::seconds(5)), "retained CEF callback holds one lifecycle lease");
  std::future<void> invalidation_finished =
      std::async(std::launch::async, [state]() { state->Invalidate(); });
  Expect(invalidation_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "CEF UI-side invalidation does not wait for an in-flight callback");
  Expect(destructor_calls.load(std::memory_order_relaxed) == 0, "non-blocking invalidation leaves the local dispatch reference safely admitted");

  std::future<bool> close_finished = std::async(std::launch::async, [&lifecycle]() { return lifecycle.Close(); });
  Expect(lifecycle.WaitForPhaseForTesting(PermissionDispatchLifecycle::Phase::kClosing, std::chrono::seconds(5)), "global lifecycle enters closing while dispatch is active");
  PermissionCallbackLease rejected_lease = lifecycle.AcquireLease();
  Expect(!rejected_lease, "closing lifecycle rejects a new callback lease");
  Expect(!lifecycle.Open(), "overlapping Open is rejected while Close owns the controller operation");
  Expect(close_finished.wait_for(std::chrono::milliseconds(0)) == std::future_status::timeout, "global shutdown drain waits for the admitted CEF callback");

  callback_control->ReleaseBlock();
  completion.join();
  Expect(close_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "global shutdown completes after callback dispatch drains");
  Expect(close_finished.get(), "serialized lifecycle Close succeeds after callback dispatch drains");
  Expect(destructor_calls.load(std::memory_order_relaxed) == 1, "global close publishes completion only after the local CEF callback is destroyed");
  Expect(lifecycle.phase_for_testing() == PermissionDispatchLifecycle::Phase::kClosed, "drained lifecycle is closed");
  Expect(!state->Cancel(), "browser-invalidated callback remains inert after lifecycle drain");

  CefRefPtr<TestPermissionCallbackOwner> rejected_owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> rejected_callback =
      new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> rejected_state =
      new MediaAccessCallbackState(4, rejected_callback, &lifecycle);
  Expect(!rejected_state->IsValid(), "closed lifecycle prevents a state from retaining a CEF callback");
  rejected_state->Invalidate();

  Expect(lifecycle.Open(), "local permission lifecycle can reopen after a completed Close");
  CefRefPtr<TestPermissionCallbackOwner> reopened_owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> reopened_callback =
      new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> reopened_state =
      new MediaAccessCallbackState(5, reopened_callback, &lifecycle);
  reopened_state->Activate(reopened_owner);
  Expect(reopened_state->Continue(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE), "reopened lifecycle admits a new callback");
  Expect(reopened_callback->continue_calls() == 1, "reopened lifecycle dispatches into CEF again");
  Expect(lifecycle.Close(), "reopened lifecycle closes after its callback terminates");
  Expect(lifecycle.Close(), "permission lifecycle Close is idempotent");
  state = nullptr;
  owner = nullptr;
  Expect(destructor_calls.load(std::memory_order_relaxed) == 1, "the CEF callback is destroyed exactly once");
}

void TestClosingDeniedDispatchTerminalizesUnderLease() {
  constexpr int kBrowserIdentifier = 72;
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "closing-denial lifecycle opens");
  std::atomic<int> destructor_calls{0};
  PermissionCallbackRegistry registry;
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner(&registry);
  CefRefPtr<TestMediaAccessCallback> callback =
      new TestMediaAccessCallback(&destructor_calls);
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(kBrowserIdentifier, callback, &lifecycle);
  Expect(state->IsValid(), "state acquires a lifecycle lease before retaining its callback");
  Expect(registry.Add(state), "leased callback registers before shutdown");
  state->Activate(owner);
  callback = nullptr;

  std::future<bool> close_finished = std::async(std::launch::async, [&lifecycle]() { return lifecycle.Close(); });
  Expect(lifecycle.WaitForPhaseForTesting(PermissionDispatchLifecycle::Phase::kClosing, std::chrono::seconds(5)), "Close enters closing while an unresolved callback lease exists");
  Expect(close_finished.wait_for(std::chrono::milliseconds(0)) == std::future_status::timeout, "an unresolved callback lease blocks Close");

  Expect(state->Continue(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE), "closing-denied completion consumes its one-shot callback");
  Expect(close_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "denied dispatch cleanup releases its lease without manual invalidation");
  Expect(close_finished.get(), "Close succeeds after denied dispatch cleanup");
  Expect(destructor_calls.load(std::memory_order_relaxed) == 1, "denied dispatch destroys the CEF callback before Close returns");
  Expect(owner->terminal_calls() == 1, "denied dispatch notifies its owner exactly once");
  Expect(!state->Cancel(), "terminalized denied callback remains inert");
}

void TestLeaseMovesAcrossWorkerBeforeCreatorThreadClose() {
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "movable-lease lifecycle opens");
  std::shared_ptr<MediaCallbackControl> callback_control =
      std::make_shared<MediaCallbackControl>();
  callback_control->Block();
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> callback =
      new TestMediaAccessCallback(nullptr, callback_control);
  // The main thread creates the state and acquires its lease. Completion moves
  // that lease through a worker dispatch; Close on the original thread must
  // wait, not confuse provenance with active dispatch ownership and reject the
  // valid shutdown.
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(75, callback, &lifecycle);
  state->Activate(owner);
  std::thread completion([state]() { state->Continue(CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE); });
  Expect(callback_control->WaitUntilEntered(std::chrono::seconds(5)), "worker dispatch receives a lease acquired on the creator thread");

  std::atomic<bool> observed_closing{false};
  std::thread releaser([&lifecycle, callback_control, &observed_closing]() { observed_closing.store(lifecycle.WaitForPhaseForTesting(PermissionDispatchLifecycle::Phase::kClosing, std::chrono::seconds(5)), std::memory_order_relaxed); callback_control->ReleaseBlock(); });
  Expect(lifecycle.Close(), "creator-thread Close waits for a worker holding the moved lease");
  releaser.join();
  completion.join();
  Expect(observed_closing.load(std::memory_order_relaxed), "creator-thread Close enters closing before the moved lease is released");
  Expect(owner->terminal_calls() == 1, "moved-lease worker completion terminalizes exactly once");
}

void TestReentrantCloseIsRejectedWithoutDeadlock() {
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "reentrant-close lifecycle opens");
  PermissionCallbackLease lease = lifecycle.AcquireLease();
  Expect(static_cast<bool>(lease), "reentrant-close test acquires a callback lease");
  PermissionDispatchPermit dispatch_permit = lifecycle.TryBeginDispatch(lease);
  Expect(static_cast<bool>(dispatch_permit), "reentrant-close test begins dispatch");
  Expect(!lifecycle.Close(), "Close invoked by a leased dispatch thread is rejected without waiting");
  dispatch_permit.Release();
  lease.Release();
  Expect(lifecycle.Close(), "Close succeeds after the reentrant dispatch releases its lease");
}

void TestCloseAlsoDrainsDispatchPermitAfterEarlyLeaseRelease() {
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "permit-drain lifecycle opens");
  PermissionCallbackLease lease = lifecycle.AcquireLease();
  PermissionDispatchPermit dispatch_permit = lifecycle.TryBeginDispatch(lease);
  Expect(static_cast<bool>(dispatch_permit), "permit-drain test begins dispatch");

  std::future<bool> close_finished = std::async(std::launch::async, [&lifecycle]() { return lifecycle.Close(); });
  Expect(lifecycle.WaitForPhaseForTesting(PermissionDispatchLifecycle::Phase::kClosing, std::chrono::seconds(5)), "permit-drain Close enters closing");
  lease.Release();
  Expect(close_finished.wait_for(std::chrono::milliseconds(0)) == std::future_status::timeout, "Close still waits when a defensive path releases its lease before its dispatch permit");
  dispatch_permit.Release();
  Expect(close_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "Close completes after the final dispatch permit releases");
  Expect(close_finished.get(), "permit-drain lifecycle Close succeeds");
}

void TestRegistryRetainsInFlightDispatchAcrossLaterRegistration() {
  constexpr int kBrowserIdentifier = 73;
  PermissionDispatchLifecycle lifecycle;
  Expect(lifecycle.Open(), "registry-race lifecycle opens");
  std::atomic<int> destructor_calls{0};
  PermissionCallbackRegistry registry;
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner(&registry);
  std::shared_ptr<MediaCallbackControl> callback_control =
      std::make_shared<MediaCallbackControl>();
  CefRefPtr<TestMediaAccessCallback> first_callback =
      new TestMediaAccessCallback(&destructor_calls, callback_control);
  callback_control->Block();
  CefRefPtr<MediaAccessCallbackState> first_state =
      new MediaAccessCallbackState(kBrowserIdentifier, first_callback, &lifecycle);
  Expect(registry.Add(first_state), "first callback registers before shutdown");
  first_state->Activate(owner);
  first_callback = nullptr;

  std::thread completion([first_state]() { first_state->Continue(CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE); });
  Expect(callback_control->WaitUntilEntered(std::chrono::seconds(5)), "first registered callback enters CEF dispatch");

  CefRefPtr<TestMediaAccessCallback> second_callback =
      new TestMediaAccessCallback(&destructor_calls);
  CefRefPtr<MediaAccessCallbackState> second_state =
      new MediaAccessCallbackState(kBrowserIdentifier, second_callback, &lifecycle);
  Expect(registry.Add(second_state), "later callback registers while the first dispatch is in flight");
  second_state->Activate(owner);
  second_callback = nullptr;

  std::future<void> invalidation_finished = std::async(std::launch::async, [&registry]() { registry.InvalidateBrowserCallbacks(kBrowserIdentifier); });
  Expect(invalidation_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "browser invalidation remains non-blocking after later registration");
  Expect(destructor_calls.load(std::memory_order_relaxed) == 1, "browser invalidation immediately releases the later non-dispatching callback");
  std::future<bool> close_finished = std::async(std::launch::async, [&lifecycle]() { return lifecycle.Close(); });
  Expect(lifecycle.WaitForPhaseForTesting(PermissionDispatchLifecycle::Phase::kClosing, std::chrono::seconds(5)), "global close observes the earlier callback admission");
  Expect(close_finished.wait_for(std::chrono::milliseconds(0)) == std::future_status::timeout, "later registration cannot make shutdown miss the earlier in-flight dispatch");
  callback_control->ReleaseBlock();
  completion.join();
  Expect(close_finished.wait_for(std::chrono::seconds(5)) == std::future_status::ready, "global lifecycle drains the in-flight callback retained across registration");
  Expect(close_finished.get(), "registry-race lifecycle Close succeeds after its leased dispatch drains");
  Expect(destructor_calls.load(std::memory_order_relaxed) == 2, "global drain releases the earlier local callback after browser invalidation returned");
  Expect(!second_state->Cancel(), "a callback invalidated by browser close is permanently inert");
}

void TestRegistryRejectsRegistrationAfterShutdown() {
  PermissionCallbackRegistry registry;
  registry.InvalidateAllCallbacks();
  CefRefPtr<TestMediaAccessCallback> callback = new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(74, callback);
  Expect(!registry.Add(state), "registry rejects callback registration after runtime shutdown");
  state->Abandon();
  Expect(!state->Cancel(), "a callback rejected during shutdown remains inert");
}

void TestSameThreadDismissInvalidationDoesNotDeadlock() {
  CefRefPtr<TestPermissionCallbackOwner> owner =
      new TestPermissionCallbackOwner();
  CefRefPtr<TestMediaAccessCallback> callback = new TestMediaAccessCallback();
  CefRefPtr<MediaAccessCallbackState> state =
      new MediaAccessCallbackState(4, callback);
  callback->SetReentrantState(state.get());
  state->Activate(owner);
  Expect(state->Continue(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE), "same-thread continuation completes");
  Expect(callback->continue_calls() == 1, "same-thread reentrant invalidation preserves winning continuation");
  Expect(owner->terminal_calls() == 0, "same-thread invalidation owns cleanup instead of issuing duplicate terminal removal");
}

}  // namespace

int main() {
  // Aborting before successful initialization closes an already-closed
  // lifecycle but must not permanently prevent the later successful
  // initialization attempt from opening it.
  ClosePermissionDispatchLifecycle();
  OpenPermissionDispatchLifecycle();
  TestExactMaskConversions();
  TestExactPromptIdConversions();
  TestPermissionResultValidation();
  TestOneShotGateRaces();
  TestPendingCompletionActivationAndAbandonment();
  TestStateRejectsRacingDuplicateCompletions();
  TestStateRejectsRacingContinueAndCancel();
  TestInvalidationIsNonBlockingAndLifecycleDrainsDispatch();
  TestClosingDeniedDispatchTerminalizesUnderLease();
  TestLeaseMovesAcrossWorkerBeforeCreatorThreadClose();
  TestReentrantCloseIsRejectedWithoutDeadlock();
  TestCloseAlsoDrainsDispatchPermitAfterEarlyLeaseRelease();
  TestRegistryRetainsInFlightDispatchAcrossLaterRegistration();
  TestRegistryRejectsRegistrationAfterShutdown();
  TestSameThreadDismissInvalidationDoesNotDeadlock();
  ClosePermissionDispatchLifecycle();
  if (failure_count != 0) {
    std::cerr << failure_count << " permission utility assertions failed"
              << std::endl;
    return 1;
  }
  std::cout << "All permission utility assertions passed" << std::endl;
  return 0;
}
