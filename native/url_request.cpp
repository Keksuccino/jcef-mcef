// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "url_request.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "jni_scoped_helpers.h"
#include "jni_util.h"

constexpr auto kPendingDispatchTimeout = std::chrono::seconds(5);
constexpr auto kPendingDispatchTimeoutForTesting = std::chrono::milliseconds(250);
constexpr auto kSyntheticTestWatchdogTimeout = std::chrono::seconds(10);
constexpr auto kNonExpiringPendingDispatchTimeoutForTesting = std::chrono::hours(1);

enum class URLRequestOperationPhase {
  PENDING,
  EXECUTING,
  COMPLETED,
  ABANDONED,
};

// Heap-owned operation state is tracked independently of the CefTask object.
// A posted task may remain retained by CEF after shutdown abandonment, but its
// shared state contains no CEF references once it reaches a terminal phase.
class URLRequestOperationState {
 public:
  explicit URLRequestOperationState(CefRefPtr<URLRequest> owner) : owner_(owner) {}

  bool BeginExecuting(CefRefPtr<URLRequest>* owner) {
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (phase_ != URLRequestOperationPhase::PENDING)
        return false;
      phase_ = URLRequestOperationPhase::EXECUTING;
      *owner = owner_;
    }
    completion_condition_.notify_all();
    return true;
  }

  void ClearExecutingOwner() {
    std::lock_guard<std::mutex> lock(lock_);
    if (phase_ != URLRequestOperationPhase::EXECUTING)
      return;
    owner_ = nullptr;
  }

  void MarkCompleted() {
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (phase_ != URLRequestOperationPhase::EXECUTING)
        return;
      phase_ = URLRequestOperationPhase::COMPLETED;
    }
    completion_condition_.notify_all();
  }

  bool AbandonPending() {
    CefRefPtr<URLRequest> owner;
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (phase_ != URLRequestOperationPhase::PENDING)
        return false;
      phase_ = URLRequestOperationPhase::ABANDONED;
      owner = owner_;
      owner_ = nullptr;
    }
    // Releasing the detached owner can destroy URLRequestClient JNI globals.
    // Keep that destructive release outside every operation/lifecycle lock.
    owner = nullptr;
    completion_condition_.notify_all();
    return true;
  }

  bool IsPhase(URLRequestOperationPhase phase) const {
    std::lock_guard<std::mutex> lock(lock_);
    return phase_ == phase;
  }

  void NotifyForTesting() {
    // DispatchPosted publishes its wait count while still holding lock_, then
    // atomically releases that lock when wait_until arms the wait. Acquiring the
    // same lock here is the handshake that prevents this test notification from
    // landing in between those actions, where a condition-variable wake would
    // be lost and the synthetic spurious-wake scenario would become racy.
    std::lock_guard<std::mutex> lock(lock_);
    completion_condition_.notify_all();
  }

  void EnableWaitObservationForTesting() { observe_waits_for_testing_.store(true, std::memory_order_release); }

  bool WaitForWaitCountForTesting(size_t count, std::chrono::steady_clock::duration timeout) {
    std::unique_lock<std::mutex> lock(test_lock_);
    wait_observer_ready_for_testing_ = true;
    test_condition_.notify_all();
    return test_condition_.wait_for(lock, timeout, [this, count]() { return wait_count_for_testing_ >= count; });
  }

  bool WaitForWaitObserverForTesting(std::chrono::steady_clock::duration timeout) {
    std::unique_lock<std::mutex> lock(test_lock_);
    return test_condition_.wait_for(lock, timeout, [this]() { return wait_observer_ready_for_testing_; });
  }

  bool WaitForExecutingCompletionWaitForTesting(std::chrono::steady_clock::duration timeout) {
    std::unique_lock<std::mutex> lock(test_lock_);
    return test_condition_.wait_for(lock, timeout, [this]() { return executing_completion_wait_for_testing_; });
  }

 private:
  friend class URLRequestOperation;

  mutable std::mutex lock_;
  std::condition_variable completion_condition_;
  URLRequestOperationPhase phase_ = URLRequestOperationPhase::PENDING;
  CefRefPtr<URLRequest> owner_;
  std::atomic<bool> observe_waits_for_testing_{false};
  std::mutex test_lock_;
  std::condition_variable test_condition_;
  size_t wait_count_for_testing_ = 0;
  bool wait_observer_ready_for_testing_ = false;
  bool executing_completion_wait_for_testing_ = false;
};

class URLRequestLifecycle {
 public:
  enum class Phase {
    CLOSED,
    OPEN,
    CLOSING,
  };

  URLRequestAdmission AcquireAdmission() {
    std::lock_guard<std::mutex> lock(lock_);
    if (phase_ != Phase::OPEN)
      return URLRequestAdmission();
    ++active_admissions_;
    return URLRequestAdmission(this);
  }

  URLRequestAccess AcquireAccess(jlong token) {
    if (token <= 0)
      return URLRequestAccess();

    CefRefPtr<URLRequest> owner;
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (phase_ != Phase::OPEN)
        return URLRequestAccess();
      const auto request = requests_.find(token);
      if (request == requests_.end())
        return URLRequestAccess();
      ++active_admissions_;
      owner = request->second;
    }
    return URLRequestAccess(URLRequestAdmission(this), owner);
  }

  URLRequestAccess TakeAccess(jlong token) {
    if (token <= 0)
      return URLRequestAccess();

    CefRefPtr<URLRequest> owner;
    {
      std::lock_guard<std::mutex> lock(lock_);
      if (phase_ != Phase::OPEN)
        return URLRequestAccess();
      const auto request = requests_.find(token);
      if (request == requests_.end())
        return URLRequestAccess();
      ++active_admissions_;
      owner = request->second;
      requests_.erase(request);
    }
    return URLRequestAccess(URLRequestAdmission(this), owner);
  }

  jlong Register(URLRequestAdmission& admission, CefRefPtr<URLRequest> request) {
    if (!admission || admission.lifecycle_ != this || !request)
      return 0;

    std::lock_guard<std::mutex> lock(lock_);
    if (phase_ != Phase::OPEN || next_token_ == 0)
      return 0;

    const jlong token = next_token_;
    next_token_ = token == std::numeric_limits<jlong>::max() ? 0 : token + 1;
    requests_.emplace(token, request);
    return token;
  }

  bool RegisterOperation(const std::shared_ptr<URLRequestOperationState>& operation) {
    std::lock_guard<std::mutex> lock(lock_);
    if (phase_ != Phase::OPEN)
      return false;
    const auto inserted = operations_.emplace(operation.get(), operation);
    if (!inserted.second)
      return false;
    ++active_admissions_;
    return true;
  }

  void CompleteOperation(const std::shared_ptr<URLRequestOperationState>& operation) {
    std::shared_ptr<URLRequestOperationState> removed;
    {
      std::lock_guard<std::mutex> lock(lock_);
      const auto registered = operations_.find(operation.get());
      if (registered == operations_.end())
        return;
      removed = std::move(registered->second);
      operations_.erase(registered);
      --active_admissions_;
      quiescence_condition_.notify_all();
    }
    removed.reset();
  }

  void ReleaseAdmission() {
    std::lock_guard<std::mutex> lock(lock_);
    if (active_admissions_ == 0)
      return;
    --active_admissions_;
    quiescence_condition_.notify_all();
  }

  void Open() {
    std::unique_lock<std::mutex> lock(lock_);
    if (phase_ == Phase::CLOSING) {
      ++opening_waiters_for_testing_;
      quiescence_condition_.notify_all();
      quiescence_condition_.wait(lock, [this]() { return phase_ != Phase::CLOSING; });
      --opening_waiters_for_testing_;
    }
    if (phase_ == Phase::OPEN)
      return;
    // next_token_ deliberately survives every close/open cycle. A stale Java
    // token from an older generation can therefore never name a new request.
    phase_ = Phase::OPEN;
    quiescence_condition_.notify_all();
  }

  void Close() {
    std::shared_ptr<CloseGeneration> close_generation;
    std::vector<std::shared_ptr<URLRequestOperationState>> operations;
    {
      std::unique_lock<std::mutex> lock(lock_);
      if (phase_ == Phase::CLOSED)
        return;
      if (phase_ == Phase::CLOSING) {
        // Wait for the exact generation observed by this caller. An Open()
        // waiter is allowed to publish a new OPEN generation immediately
        // after this close completes, so waiting on phase_ == CLOSED could
        // otherwise miss the transient CLOSED state and block forever.
        close_generation = close_generation_;
        ++closing_waiters_for_testing_;
        quiescence_condition_.notify_all();
        quiescence_condition_.wait(lock, [close_generation]() { return close_generation->completed; });
        --closing_waiters_for_testing_;
        return;
      }
      phase_ = Phase::CLOSING;
      close_generation = std::make_shared<CloseGeneration>();
      close_generation_ = close_generation;
      operations.reserve(operations_.size());
      for (const auto& operation : operations_)
        operations.push_back(operation.second);
      quiescence_condition_.notify_all();
    }

    // Strong snapshots keep pre-post tasks alive while shutdown races their
    // PENDING->EXECUTING transition. Never invoke state transitions or release
    // a snapshot while holding the lifecycle lock.
    for (const auto& operation : operations)
      operation->AbandonPending();
    operations.clear();

    std::unordered_map<jlong, CefRefPtr<URLRequest>> drained_requests;
    {
      std::unique_lock<std::mutex> lock(lock_);
      quiescence_condition_.wait(lock, [this]() { return active_admissions_ == 0; });
      drained_requests.swap(requests_);
    }

    // URLRequest destruction can release CEF objects and JNI globals. Drain
    // the JCEF-owned registry references while CEF/JVM are valid, after
    // unlocking, and before publishing CLOSED. CEF can still retain its own
    // request/client references until CefShutdown tears those requests down.
    drained_requests.clear();
    {
      std::lock_guard<std::mutex> lock(lock_);
      phase_ = Phase::CLOSED;
      close_generation->completed = true;
      close_generation_.reset();
      quiescence_condition_.notify_all();
    }
  }

  Phase phase_for_testing() const {
    std::lock_guard<std::mutex> lock(lock_);
    return phase_;
  }

  bool WaitForPhaseForTesting(Phase phase, std::chrono::steady_clock::duration timeout) {
    std::unique_lock<std::mutex> lock(lock_);
    return quiescence_condition_.wait_for(lock, timeout, [this, phase]() { return phase_ == phase; });
  }

  bool WaitForLifecycleWaitersForTesting(size_t closing_waiters, size_t opening_waiters, std::chrono::steady_clock::duration timeout) {
    std::unique_lock<std::mutex> lock(lock_);
    return quiescence_condition_.wait_for(lock, timeout, [this, closing_waiters, opening_waiters]() { return closing_waiters_for_testing_ >= closing_waiters && opening_waiters_for_testing_ >= opening_waiters; });
  }

 private:
  friend class URLRequestAdmission;

  struct CloseGeneration {
    bool completed = false;
  };

  mutable std::mutex lock_;
  std::condition_variable quiescence_condition_;
  Phase phase_ = Phase::CLOSED;
  size_t active_admissions_ = 0;
  std::unordered_map<jlong, CefRefPtr<URLRequest>> requests_;
  std::unordered_map<URLRequestOperationState*, std::shared_ptr<URLRequestOperationState>> operations_;
  std::shared_ptr<CloseGeneration> close_generation_;
  jlong next_token_ = 1;
  size_t closing_waiters_for_testing_ = 0;
  size_t opening_waiters_for_testing_ = 0;
};

namespace {

URLRequestLifecycle& GetURLRequestLifecycle() {
  // Admissions may be released by late Java finalizers. Keep the empty control
  // object alive for process lifetime, but explicitly drain JCEF-owned registry
  // and operation references before CEF shutdown and macOS framework unload.
  static URLRequestLifecycle* lifecycle = new URLRequestLifecycle();
  return *lifecycle;
}

bool ReportAndClearJNIException(JNIEnv* env, const char* operation) {
  if (!env->ExceptionCheck())
    return false;
  // CEF LOG() reaches runtime-loaded libcef symbols on macOS. Late finalizer
  // disposal must remain valid after cef_unload_library, including JNI failure
  // paths, so diagnostics here use only libc and the JVM.
  std::fprintf(stderr, "JCEF URLRequest JNI failure: %s\n", operation);
  env->ExceptionDescribe();
  env->ExceptionClear();
  return true;
}

bool ResolveJavaURLRequestTokenField(JNIEnv* env, jobject jurl_request, jfieldID* field) {
  if (!jurl_request || !field)
    return false;
  jclass url_request_class = env->GetObjectClass(jurl_request);
  if (ReportAndClearJNIException(env, "resolving the Java CefURLRequest class") || !url_request_class)
    return false;
  *field = env->GetFieldID(url_request_class, "N_CefHandle", "J");
  const bool failed = ReportAndClearJNIException(env, "resolving CefURLRequest.N_CefHandle") || !*field;
  env->DeleteLocalRef(url_request_class);
  return !failed;
}

bool ReadJavaURLRequestToken(JNIEnv* env, jobject jurl_request, jlong* token) {
  if (!jurl_request || !token)
    return false;
  jfieldID token_field = nullptr;
  if (!ResolveJavaURLRequestTokenField(env, jurl_request, &token_field))
    return false;
  *token = env->GetLongField(jurl_request, token_field);
  return !ReportAndClearJNIException(env, "reading CefURLRequest.N_CefHandle");
}

bool WriteJavaURLRequestToken(JNIEnv* env, jobject jurl_request, jlong token) {
  if (!jurl_request)
    return false;
  jfieldID token_field = nullptr;
  if (!ResolveJavaURLRequestTokenField(env, jurl_request, &token_field))
    return false;
  env->SetLongField(jurl_request, token_field, token);
  return !ReportAndClearJNIException(env, "writing CefURLRequest.N_CefHandle");
}

bool PublishURLRequestToken(JNIEnv* env, jobject jurl_request, jlong token) {
  if (!WriteJavaURLRequestToken(env, jurl_request, token))
    return false;
  jlong published_token = 0;
  return ReadJavaURLRequestToken(env, jurl_request, &published_token) && published_token == token;
}

void ClearJavaURLRequestTokenIfMatches(JNIEnv* env, jobject jurl_request, jlong token) {
  if (token <= 0)
    return;
  jlong current_token = 0;
  if (ReadJavaURLRequestToken(env, jurl_request, &current_token) && current_token == token)
    WriteJavaURLRequestToken(env, jurl_request, 0);
}

void RollBackURLRequestToken(JNIEnv* env, jobject jurl_request, jlong token) {
  URLRequestAccess removed = GetURLRequestLifecycle().TakeAccess(token);
  ClearJavaURLRequestTokenIfMatches(env, jurl_request, token);
}

CefRefPtr<CefRequest> GetRequest(JNIEnv* env, jobject jrequest) {
  ScopedJNIRequest request_obj(env);
  request_obj.SetHandle(jrequest, false /* should_delete */);
  return request_obj.GetCefObject();
}

bool InvokeJavaDisposeForTesting(JNIEnv* env, jobject jurl_request) {
  jclass url_request_class = env->GetObjectClass(jurl_request);
  if (ReportAndClearJNIException(env, "resolving the Java CefURLRequest class for the disposal race test") || !url_request_class)
    return false;
  jmethodID dispose = env->GetMethodID(url_request_class, "dispose", "()V");
  const bool failed = ReportAndClearJNIException(env, "resolving CefURLRequest.dispose for the disposal race test") || !dispose;
  env->DeleteLocalRef(url_request_class);
  if (failed)
    return false;
  env->CallVoidMethod(jurl_request, dispose);
  return !ReportAndClearJNIException(env, "calling CefURLRequest.dispose during the disposal race test");
}

bool ReportSyntheticTestScenario(const char* scenario, bool valid, bool watchdog_forced_cleanup) {
  if (!valid)
    std::fprintf(stderr, "JCEF URLRequest synthetic test scenario failed: %s (watchdog-forced cleanup: %s)\n", scenario, watchdog_forced_cleanup ? "yes" : "no");
  return valid;
}

}  // namespace

// A URLRequest can be called concurrently from Java and CEF operations may
// synchronously reenter Java. Each dispatch therefore owns independent mode,
// result and state, and never holds its state lock across CEF or Java calls.
class URLRequestOperation : public CefTask {
 public:
  enum Mode {
    REQ_CREATE,
    REQ_STATUS,
    REQ_ERROR,
    REQ_RESPONSE,
    REQ_WAS_CACHED,
    REQ_CANCEL,
  };

  using TaskPoster = std::function<bool(CefRefPtr<CefTask>)>;
  using ExecutionHook = std::function<void()>;

  static constexpr URLRequestOperationPhase PENDING = URLRequestOperationPhase::PENDING;
  static constexpr URLRequestOperationPhase EXECUTING = URLRequestOperationPhase::EXECUTING;
  static constexpr URLRequestOperationPhase COMPLETED = URLRequestOperationPhase::COMPLETED;
  static constexpr URLRequestOperationPhase ABANDONED = URLRequestOperationPhase::ABANDONED;

  URLRequestOperation(CefRefPtr<URLRequest> owner, Mode mode, URLRequestLifecycle* lifecycle, ExecutionHook execution_hook = ExecutionHook()) : state_(std::make_shared<URLRequestOperationState>(owner)), mode_(mode), lifecycle_(lifecycle), execution_hook_(std::move(execution_hook)) {}

  bool Dispatch(CefThreadId thread_id) {
    if (!lifecycle_->RegisterOperation(state_)) {
      state_->AbandonPending();
      return false;
    }
    registered_.store(true, std::memory_order_release);
    if (!state_->IsPhase(PENDING))
      return false;
    if (CefCurrentlyOn(thread_id)) {
      Execute();
      return state_->IsPhase(COMPLETED);
    }
    return DispatchPosted([thread_id](CefRefPtr<CefTask> task) { return CefPostTask(thread_id, task); }, kPendingDispatchTimeout, true, false);
  }

  bool created() const { return created_; }
  CefURLRequest::Status status() const { return status_; }
  CefURLRequest::ErrorCode error() const { return error_; }
  CefRefPtr<CefResponse> response() const { return response_; }
  bool response_was_cached() const { return response_was_cached_; }

  void Finish() {
    response_ = nullptr;
    if (registered_.exchange(false, std::memory_order_acq_rel))
      lifecycle_->CompleteOperation(state_);
  }

  void Execute() override {
    CefRefPtr<URLRequest> owner;
    if (!state_->BeginExecuting(&owner))
      return;

    // This transition happens before every CEF-visible effect. Once it wins
    // the timeout/shutdown race DispatchPosted and lifecycle close must await
    // COMPLETED instead of allowing a hidden create/cancel past quiescence.
    if (execution_hook_)
      execution_hook_();

    switch (mode_) {
      case REQ_CREATE:
        if (!owner->url_request_) {
          if (owner->frame_)
            owner->url_request_ = owner->frame_->CreateURLRequest(owner->request_, owner->client_.get());
          else
            owner->url_request_ = CefURLRequest::Create(owner->request_, owner->client_.get(), owner->request_context_);
          // A successfully created request owns its frame/context association.
          // Failed creation also no longer needs either transport reference.
          // Clear both so a completed Java wrapper cannot pin a browser or
          // request context.
          owner->request_context_ = nullptr;
          owner->frame_ = nullptr;
        }
        created_ = owner->url_request_.get() != nullptr;
        break;
      case REQ_STATUS:
        if (owner->url_request_)
          status_ = owner->url_request_->GetRequestStatus();
        break;
      case REQ_ERROR:
        if (owner->url_request_)
          error_ = owner->url_request_->GetRequestError();
        break;
      case REQ_RESPONSE:
        if (owner->url_request_)
          response_ = owner->url_request_->GetResponse();
        break;
      case REQ_WAS_CACHED:
        if (owner->url_request_)
          response_was_cached_ = owner->url_request_->ResponseWasCached();
        break;
      case REQ_CANCEL:
        // Completion may reenter cancel on TID_UI. Only a pending request can
        // be canceled, preventing a terminal reentrant cancel from recursing.
        if (owner->url_request_ && owner->url_request_->GetRequestStatus() == UR_IO_PENDING)
          owner->url_request_->Cancel();
        break;
    }

    // Remove every owner reference before publishing COMPLETED. A spurious
    // waiter wake can observe terminal state immediately, so notifying first
    // would allow shutdown to unload CEF while this stack still releases it.
    state_->ClearExecutingOwner();
    owner = nullptr;
    state_->MarkCompleted();
  }

  static bool RunStateMachineForTesting();

 private:
  static bool RunPendingCloseForTesting();
  static bool RunExecutingCloseForTesting();

  bool DispatchPostedForTesting(const TaskPoster& post_task, std::chrono::steady_clock::duration pending_timeout) {
    if (!lifecycle_->RegisterOperation(state_)) {
      state_->AbandonPending();
      return false;
    }
    registered_.store(true, std::memory_order_release);
    return DispatchPosted(post_task, pending_timeout, false, true);
  }

  bool DispatchPosted(const TaskPoster& post_task, std::chrono::steady_clock::duration pending_timeout, bool log_timeout, bool restart_deadline_after_post_for_testing) {
    CefRefPtr<URLRequest> abandoned_owner;
    bool abandoned = false;
    bool timed_out = false;
    bool completed = false;
    auto deadline = std::chrono::steady_clock::now() + pending_timeout;
    CefRefPtr<CefTask> task(this);
    const bool accepted = state_->IsPhase(PENDING) && post_task(task);
    // Synthetic posters may deliberately park before accepting a task so the
    // observer is deterministically ready. Restart only their deadline after
    // that choreography; production retains its original pre-CefPostTask clock.
    if (restart_deadline_after_post_for_testing)
      deadline = std::chrono::steady_clock::now() + pending_timeout;
    {
      std::unique_lock<std::mutex> lock(state_->lock_);
      if (!accepted && state_->phase_ == PENDING) {
        state_->phase_ = ABANDONED;
        abandoned_owner = state_->owner_;
        state_->owner_ = nullptr;
        abandoned = true;
      } else {
        while (state_->phase_ == PENDING) {
          if (state_->observe_waits_for_testing_.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> test_lock(state_->test_lock_);
            ++state_->wait_count_for_testing_;
            state_->test_condition_.notify_all();
          }
          if (state_->completion_condition_.wait_until(lock, deadline) == std::cv_status::timeout && state_->phase_ == PENDING) {
            state_->phase_ = ABANDONED;
            abandoned_owner = state_->owner_;
            state_->owner_ = nullptr;
            abandoned = true;
            timed_out = true;
          }
        }
        if (state_->phase_ == EXECUTING) {
          if (state_->observe_waits_for_testing_.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> test_lock(state_->test_lock_);
            state_->executing_completion_wait_for_testing_ = true;
            state_->test_condition_.notify_all();
          }
          state_->completion_condition_.wait(lock, [this]() { return state_->phase_ == COMPLETED; });
        }
      }
      completed = state_->phase_ == COMPLETED;
    }

    abandoned_owner = nullptr;
    if (abandoned)
      state_->completion_condition_.notify_all();
    if (timed_out && log_timeout)
      LOG(ERROR) << "Timed out after 5 seconds waiting for a pending CEF UI "
                    "URLRequest operation; the accepted task was safely abandoned";
    return completed;
  }

  std::shared_ptr<URLRequestOperationState> state_;
  const Mode mode_;
  URLRequestLifecycle* const lifecycle_;
  const ExecutionHook execution_hook_;
  std::atomic<bool> registered_{false};
  bool created_ = false;
  CefURLRequest::Status status_ = UR_UNKNOWN;
  CefURLRequest::ErrorCode error_ = ERR_FAILED;
  CefRefPtr<CefResponse> response_;
  bool response_was_cached_ = false;

  IMPLEMENT_REFCOUNTING(URLRequestOperation);
};

bool URLRequestOperation::RunStateMachineForTesting() {
  bool valid = true;

  {
    struct RejectedPostControl {
      std::mutex lock;
      std::condition_variable condition;
      std::shared_ptr<URLRequestLifecycle> lifecycle;
      CefRefPtr<URLRequestOperation> operation;
      bool dispatcher_done = false;
      bool dispatch_result = true;
    };
    std::shared_ptr<RejectedPostControl> control = std::make_shared<RejectedPostControl>();
    control->lifecycle = std::make_shared<URLRequestLifecycle>();
    control->lifecycle->Open();
    CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
    control->operation = new URLRequestOperation(owner, REQ_CANCEL, control->lifecycle.get());
    std::thread dispatcher([control]() {
      control->dispatch_result = control->operation->DispatchPostedForTesting([](CefRefPtr<CefTask>) { return false; }, kSyntheticTestWatchdogTimeout);
      control->operation->Finish();
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->dispatcher_done = true;
      }
      control->condition.notify_all();
    });
    bool dispatcher_done;
    {
      std::unique_lock<std::mutex> lock(control->lock);
      dispatcher_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done; });
    }
    bool watchdog_forced_cleanup = false;
    if (!dispatcher_done) {
      watchdog_forced_cleanup = true;
      control->operation->state_->AbandonPending();
      control->operation->state_->NotifyForTesting();
      std::unique_lock<std::mutex> lock(control->lock);
      dispatcher_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done; });
    }
    if (!dispatcher_done) {
      dispatcher.detach();
      ReportSyntheticTestScenario("rejected post", false, watchdog_forced_cleanup);
      return false;
    }
    dispatcher.join();
    const bool scenario_valid = !watchdog_forced_cleanup && !control->dispatch_result && control->operation->state_->IsPhase(ABANDONED) && owner->HasOneRef();
    valid = ReportSyntheticTestScenario("rejected post", scenario_valid, watchdog_forced_cleanup) && valid;
  }

  struct PendingControl {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    CefRefPtr<URLRequestOperation> operation;
    CefRefPtr<CefTask> captured_task;
    bool task_captured = false;
    bool observer_watchdog_forced_abandonment = false;
    bool dispatcher_done = false;
    bool dispatch_result = true;
    bool timeout_wait_started = false;
    std::chrono::steady_clock::time_point timeout_wait_start;
    std::chrono::steady_clock::time_point dispatch_finished;
  };

  {
    std::shared_ptr<PendingControl> control = std::make_shared<PendingControl>();
    control->lifecycle = std::make_shared<URLRequestLifecycle>();
    control->lifecycle->Open();
    CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
    control->operation = new URLRequestOperation(owner, REQ_CANCEL, control->lifecycle.get());
    control->operation->state_->EnableWaitObservationForTesting();
    std::thread dispatcher([control]() {
      TaskPoster poster = [control](CefRefPtr<CefTask> task) {
        {
          std::lock_guard<std::mutex> lock(control->lock);
          control->captured_task = task;
          control->task_captured = true;
        }
        control->condition.notify_all();
        // The first wait-count observation publishes readiness before blocking.
        // Do not accept the fake post until that observer is parked; the 250ms
        // dispatch deadline starts after this poster returns, so runner
        // descheduling cannot consume the behavior-under-test's time budget.
        const bool observer_ready = control->operation->state_->WaitForWaitObserverForTesting(kSyntheticTestWatchdogTimeout);
        {
          std::lock_guard<std::mutex> lock(control->lock);
          control->observer_watchdog_forced_abandonment = !observer_ready;
          if (observer_ready) {
            control->timeout_wait_start = std::chrono::steady_clock::now();
            control->timeout_wait_started = true;
          }
        }
        return observer_ready;
      };
      control->dispatch_result = control->operation->DispatchPostedForTesting(poster, kPendingDispatchTimeoutForTesting);
      control->dispatch_finished = std::chrono::steady_clock::now();
      control->operation->Finish();
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->dispatcher_done = true;
      }
      control->condition.notify_all();
    });

    bool captured;
    {
      std::unique_lock<std::mutex> lock(control->lock);
      captured = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->task_captured; });
    }
    bool observed_spurious_waits = captured;
    for (size_t wait_count = 1; observed_spurious_waits && wait_count <= 3; ++wait_count) {
      observed_spurious_waits = control->operation->state_->WaitForWaitCountForTesting(wait_count, kSyntheticTestWatchdogTimeout);
      if (observed_spurious_waits)
        control->operation->state_->NotifyForTesting();
    }

    bool dispatcher_done;
    bool watchdog_forced_cleanup;
    {
      std::unique_lock<std::mutex> lock(control->lock);
      dispatcher_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done; });
      watchdog_forced_cleanup = control->observer_watchdog_forced_abandonment;
    }
    if (!dispatcher_done) {
      watchdog_forced_cleanup = true;
      // The watchdog services both possible failure modes. Force terminal state
      // first, then execute an already-captured late task so the regression
      // cannot leave a joinable thread or stack capture behind.
      control->operation->state_->AbandonPending();
      CefRefPtr<CefTask> task;
      {
        std::lock_guard<std::mutex> lock(control->lock);
        task = control->captured_task;
      }
      if (task)
        task->Execute();
      control->operation->state_->NotifyForTesting();
      std::unique_lock<std::mutex> lock(control->lock);
      dispatcher_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done; });
    }
    if (!dispatcher_done) {
      // A broken terminal wait must fail within a bound instead of hanging the
      // test process. The worker captures only heap control that owns the
      // lifecycle, task and null-backed operation; it has no JNI or CEF state.
      dispatcher.detach();
      ReportSyntheticTestScenario("accepted pending timeout", false, watchdog_forced_cleanup);
      return false;
    }
    dispatcher.join();

    const auto timeout_wait_elapsed = control->dispatch_finished - control->timeout_wait_start;
    const bool deadline_not_shortened = control->timeout_wait_started && timeout_wait_elapsed >= kPendingDispatchTimeoutForTesting;
    CefRefPtr<CefTask> late_task;
    {
      std::lock_guard<std::mutex> lock(control->lock);
      late_task = control->captured_task;
    }
    if (late_task)
      late_task->Execute();
    const bool scenario_valid = !watchdog_forced_cleanup && captured && observed_spurious_waits && dispatcher_done && deadline_not_shortened && !control->dispatch_result && control->operation->state_->IsPhase(ABANDONED) && owner->HasOneRef();
    valid = ReportSyntheticTestScenario("accepted pending timeout", scenario_valid, watchdog_forced_cleanup) && valid;
  }

  struct ExecutionGate {
    std::mutex lock;
    std::condition_variable condition;
    bool executing = false;
    bool release_execution = false;
  };
  struct ExecutingControl {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    std::shared_ptr<ExecutionGate> gate;
    CefRefPtr<URLRequestOperation> operation;
    CefRefPtr<CefTask> captured_task;
    bool task_captured = false;
    bool dispatcher_done = false;
    bool executor_done = false;
    bool dispatch_result = false;
  };

  {
    std::shared_ptr<ExecutingControl> control = std::make_shared<ExecutingControl>();
    control->lifecycle = std::make_shared<URLRequestLifecycle>();
    control->lifecycle->Open();
    control->gate = std::make_shared<ExecutionGate>();
    CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
    ExecutionHook hook = [gate = control->gate]() {
      std::unique_lock<std::mutex> lock(gate->lock);
      gate->executing = true;
      gate->condition.notify_all();
      gate->condition.wait(lock, [gate]() { return gate->release_execution; });
    };
    control->operation = new URLRequestOperation(owner, REQ_CANCEL, control->lifecycle.get(), hook);
    control->operation->state_->EnableWaitObservationForTesting();
    std::thread dispatcher([control]() {
      TaskPoster poster = [control](CefRefPtr<CefTask> task) {
        std::lock_guard<std::mutex> lock(control->lock);
        control->captured_task = task;
        control->task_captured = true;
        control->condition.notify_all();
        return true;
      };
      control->dispatch_result = control->operation->DispatchPostedForTesting(poster, kNonExpiringPendingDispatchTimeoutForTesting);
      control->operation->Finish();
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->dispatcher_done = true;
      }
      control->condition.notify_all();
    });

    bool captured;
    {
      std::unique_lock<std::mutex> lock(control->lock);
      captured = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->task_captured; });
    }
    std::thread executor;
    if (captured) {
      executor = std::thread([control]() {
        control->captured_task->Execute();
        {
          std::lock_guard<std::mutex> lock(control->lock);
          control->executor_done = true;
        }
        control->condition.notify_all();
      });
    }

    bool executing = false;
    if (captured) {
      std::unique_lock<std::mutex> lock(control->gate->lock);
      executing = control->gate->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->gate->executing; });
    }
    const bool dispatcher_waited = captured && executing && control->operation->state_->WaitForExecutingCompletionWaitForTesting(kSyntheticTestWatchdogTimeout);
    {
      std::lock_guard<std::mutex> lock(control->gate->lock);
      control->gate->release_execution = true;
    }
    control->gate->condition.notify_all();

    const bool executor_expected = captured;
    bool both_done;
    {
      std::unique_lock<std::mutex> lock(control->lock);
      both_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control, executor_expected]() { return control->dispatcher_done && (!executor_expected || control->executor_done); });
    }
    bool watchdog_forced_cleanup = false;
    if (!both_done) {
      watchdog_forced_cleanup = true;
      // The gate is already released. Force PENDING to a terminal phase and
      // notify every waiter; an EXECUTING task owns the race and will complete
      // after leaving the released hook. This services all target regressions
      // before the unconditional joins below.
      control->operation->state_->AbandonPending();
      control->operation->state_->NotifyForTesting();
      std::unique_lock<std::mutex> lock(control->lock);
      both_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control, executor_expected]() { return control->dispatcher_done && (!executor_expected || control->executor_done); });
    }
    if (!both_done) {
      // Both closures retain the same heap control, including the released
      // execution gate and lifecycle. Detaching only on a failed watchdog
      // keeps a regression bounded without leaving stack/JNI/CEF references.
      dispatcher.detach();
      if (executor.joinable())
        executor.detach();
      ReportSyntheticTestScenario("executing operation wins pending race", false, watchdog_forced_cleanup);
      return false;
    }
    dispatcher.join();
    if (executor.joinable())
      executor.join();
    const bool scenario_valid = !watchdog_forced_cleanup && captured && executing && dispatcher_waited && both_done && control->dispatch_result && control->operation->state_->IsPhase(COMPLETED) && owner->HasOneRef();
    valid = ReportSyntheticTestScenario("executing operation wins pending race", scenario_valid, watchdog_forced_cleanup) && valid;
  }

  const bool pending_close_valid = RunPendingCloseForTesting();
  const bool executing_close_valid = RunExecutingCloseForTesting();
  return valid && pending_close_valid && executing_close_valid;
}

bool URLRequestOperation::RunPendingCloseForTesting() {
  struct Control {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    CefRefPtr<URLRequestOperation> operation;
    CefRefPtr<CefTask> captured_task;
    bool task_captured = false;
    bool dispatcher_done = false;
    bool closer_done = false;
    bool dispatch_result = true;
  };

  std::shared_ptr<Control> control = std::make_shared<Control>();
  control->lifecycle = std::make_shared<URLRequestLifecycle>();
  control->lifecycle->Open();
  CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  control->operation = new URLRequestOperation(owner, REQ_CANCEL, control->lifecycle.get());
  URLRequestAdmission retained_admission = control->lifecycle->AcquireAdmission();
  std::thread dispatcher([control]() {
    TaskPoster poster = [control](CefRefPtr<CefTask> task) {
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->captured_task = task;
        control->task_captured = true;
      }
      control->condition.notify_all();
      return true;
    };
    control->dispatch_result = control->operation->DispatchPostedForTesting(poster, kNonExpiringPendingDispatchTimeoutForTesting);
    control->operation->Finish();
    {
      std::lock_guard<std::mutex> lock(control->lock);
      control->dispatcher_done = true;
    }
    control->condition.notify_all();
  });

  bool captured;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    captured = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->task_captured; });
  }
  std::thread closer([control]() {
    control->lifecycle->Close();
    {
      std::lock_guard<std::mutex> lock(control->lock);
      control->closer_done = true;
    }
    control->condition.notify_all();
  });
  const bool closing = control->lifecycle->WaitForPhaseForTesting(URLRequestLifecycle::Phase::CLOSING, kSyntheticTestWatchdogTimeout);
  const bool admission_rejected = !control->lifecycle->AcquireAdmission();
  retained_admission = URLRequestAdmission();

  bool both_done;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    both_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done && control->closer_done; });
  }
  bool watchdog_forced_cleanup = false;
  if (!both_done) {
    watchdog_forced_cleanup = true;
    control->operation->state_->AbandonPending();
    CefRefPtr<CefTask> task;
    {
      std::lock_guard<std::mutex> lock(control->lock);
      task = control->captured_task;
    }
    if (task)
      task->Execute();
    control->operation->state_->NotifyForTesting();
    std::unique_lock<std::mutex> lock(control->lock);
    both_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->dispatcher_done && control->closer_done; });
  }
  if (!both_done) {
    dispatcher.detach();
    closer.detach();
    ReportSyntheticTestScenario("pending operation lifecycle close", false, watchdog_forced_cleanup);
    return false;
  }
  dispatcher.join();
  closer.join();

  CefRefPtr<CefTask> late_task;
  {
    std::lock_guard<std::mutex> lock(control->lock);
    late_task = control->captured_task;
  }
  if (late_task)
    late_task->Execute();
  const bool scenario_valid = !watchdog_forced_cleanup && captured && closing && admission_rejected && !control->dispatch_result && control->operation->state_->IsPhase(ABANDONED) && control->lifecycle->phase_for_testing() == URLRequestLifecycle::Phase::CLOSED && owner->HasOneRef();
  return ReportSyntheticTestScenario("pending operation lifecycle close", scenario_valid, watchdog_forced_cleanup);
}

bool URLRequestOperation::RunExecutingCloseForTesting() {
  struct ExecutionGate {
    std::mutex lock;
    std::condition_variable condition;
    bool executing = false;
    bool release_execution = false;
  };
  struct Control {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    std::shared_ptr<ExecutionGate> gate;
    CefRefPtr<URLRequestOperation> operation;
    CefRefPtr<CefTask> captured_task;
    bool task_captured = false;
    bool dispatcher_done = false;
    bool executor_done = false;
    bool closer_done = false;
    bool dispatch_result = false;
  };

  std::shared_ptr<Control> control = std::make_shared<Control>();
  control->lifecycle = std::make_shared<URLRequestLifecycle>();
  control->lifecycle->Open();
  control->gate = std::make_shared<ExecutionGate>();
  CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  ExecutionHook hook = [gate = control->gate]() {
    std::unique_lock<std::mutex> lock(gate->lock);
    gate->executing = true;
    gate->condition.notify_all();
    gate->condition.wait(lock, [gate]() { return gate->release_execution; });
  };
  control->operation = new URLRequestOperation(owner, REQ_CANCEL, control->lifecycle.get(), hook);
  std::thread dispatcher([control]() {
    TaskPoster poster = [control](CefRefPtr<CefTask> task) {
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->captured_task = task;
        control->task_captured = true;
      }
      control->condition.notify_all();
      return true;
    };
    control->dispatch_result = control->operation->DispatchPostedForTesting(poster, kNonExpiringPendingDispatchTimeoutForTesting);
    control->operation->Finish();
    {
      std::lock_guard<std::mutex> lock(control->lock);
      control->dispatcher_done = true;
    }
    control->condition.notify_all();
  });

  CefRefPtr<CefTask> captured_task;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->task_captured; });
    captured_task = control->captured_task;
  }
  std::thread executor;
  if (captured_task) {
    executor = std::thread([control, captured_task]() {
      captured_task->Execute();
      {
        std::lock_guard<std::mutex> lock(control->lock);
        control->executor_done = true;
      }
      control->condition.notify_all();
    });
  }
  bool executing = false;
  if (captured_task) {
    std::unique_lock<std::mutex> lock(control->gate->lock);
    executing = control->gate->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->gate->executing; });
  }

  std::thread closer([control]() {
    control->lifecycle->Close();
    {
      std::lock_guard<std::mutex> lock(control->lock);
      control->closer_done = true;
    }
    control->condition.notify_all();
  });
  const bool closing = control->lifecycle->WaitForPhaseForTesting(URLRequestLifecycle::Phase::CLOSING, kSyntheticTestWatchdogTimeout);
  bool close_blocked;
  {
    std::lock_guard<std::mutex> lock(control->lock);
    close_blocked = !control->closer_done;
  }
  {
    std::lock_guard<std::mutex> lock(control->gate->lock);
    control->gate->release_execution = true;
  }
  control->gate->condition.notify_all();

  const bool executor_expected = captured_task.get() != nullptr;
  bool all_done;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    all_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control, executor_expected]() { return control->dispatcher_done && control->closer_done && (!executor_expected || control->executor_done); });
  }
  bool watchdog_forced_cleanup = false;
  if (!all_done) {
    watchdog_forced_cleanup = true;
    control->operation->state_->AbandonPending();
    control->operation->state_->NotifyForTesting();
    if (captured_task)
      captured_task->Execute();
    std::unique_lock<std::mutex> lock(control->lock);
    all_done = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control, executor_expected]() { return control->dispatcher_done && control->closer_done && (!executor_expected || control->executor_done); });
  }
  if (!all_done) {
    dispatcher.detach();
    if (executor.joinable())
      executor.detach();
    closer.detach();
    ReportSyntheticTestScenario("executing operation lifecycle close", false, watchdog_forced_cleanup);
    return false;
  }
  dispatcher.join();
  if (executor.joinable())
    executor.join();
  closer.join();
  const bool scenario_valid = !watchdog_forced_cleanup && captured_task && executing && closing && close_blocked && control->dispatch_result && control->operation->state_->IsPhase(COMPLETED) && control->lifecycle->phase_for_testing() == URLRequestLifecycle::Phase::CLOSED && owner->HasOneRef();
  return ReportSyntheticTestScenario("executing operation lifecycle close", scenario_valid, watchdog_forced_cleanup);
}

namespace {

bool CreateURLRequest(JNIEnv* env, jobject jurl_request, CefRefPtr<CefRequest> request, jobject jrequest_client, CefRefPtr<CefRequestContext> request_context, CefRefPtr<CefFrame> frame, URLRequestAdmission& admission) {
  // Creating the client binds JNI globals. All native inputs and the Java
  // wrapper must already be valid so failed creation cannot retain the Java
  // request or client indefinitely.
  CefRefPtr<URLRequestClient> client = URLRequestClient::Create(env, jrequest_client, jurl_request);
  CefRefPtr<URLRequest> url_request = new URLRequest(TID_UI, request, client, request_context, frame);

  // Register before publishing. A callback can dispose the Java wrapper as
  // soon as CEF starts, while every concurrent JNI operation must either copy
  // a retained owner from the registry or observe an absent token.
  const jlong token = GetURLRequestLifecycle().Register(admission, url_request);
  if (token == 0)
    return false;
  if (!PublishURLRequestToken(env, jurl_request, token)) {
    RollBackURLRequestToken(env, jurl_request, token);
    return false;
  }
  if (!url_request->Create()) {
    RollBackURLRequestToken(env, jurl_request, token);
    return false;
  }

  // Do not infer creation success from the mutable token. A fast UI callback
  // may dispose the wrapper before an off-UI factory caller resumes. This
  // optional field lets matching new Java return that same disposed wrapper.
  // Older Java wrappers continue to use their ordinary live-token decision and
  // intentionally retain their legacy early-dispose return behavior.
  ScopedJNIClass url_request_class(env, env->GetObjectClass(jurl_request));
  if (url_request_class)
    SetJNIFieldBoolean(env, url_request_class, jurl_request, "N_CreationSucceeded", true);
  return true;
}

}  // namespace

URLRequestAdmission::URLRequestAdmission(URLRequestAdmission&& other) noexcept : lifecycle_(other.lifecycle_) {
  other.lifecycle_ = nullptr;
}

URLRequestAdmission& URLRequestAdmission::operator=(URLRequestAdmission&& other) noexcept {
  if (this != &other) {
    Release();
    lifecycle_ = other.lifecycle_;
    other.lifecycle_ = nullptr;
  }
  return *this;
}

URLRequestAdmission::~URLRequestAdmission() {
  Release();
}

void URLRequestAdmission::Release() {
  if (!lifecycle_)
    return;
  URLRequestLifecycle* lifecycle = lifecycle_;
  lifecycle_ = nullptr;
  lifecycle->ReleaseAdmission();
}

URLRequestAccess::URLRequestAccess(URLRequestAccess&& other) noexcept : admission_(std::move(other.admission_)), owner_(other.owner_) {
  other.owner_ = nullptr;
}

URLRequestAccess& URLRequestAccess::operator=(URLRequestAccess&& other) noexcept {
  if (this != &other) {
    Release();
    admission_ = std::move(other.admission_);
    owner_ = other.owner_;
    other.owner_ = nullptr;
  }
  return *this;
}

URLRequestAccess::~URLRequestAccess() {
  Release();
}

void URLRequestAccess::Release() {
  owner_ = nullptr;
  admission_.Release();
}

URLRequest::URLRequest(CefThreadId thread_id, CefRefPtr<CefRequest> request, CefRefPtr<URLRequestClient> client, CefRefPtr<CefRequestContext> request_context, CefRefPtr<CefFrame> frame) : thread_id_(thread_id), request_(request), client_(client), request_context_(request_context), frame_(frame) {}

bool URLRequest::Create() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_CREATE, &GetURLRequestLifecycle());
  const bool dispatched = operation->Dispatch(thread_id_);
  const bool created = dispatched && operation->created();
  operation->Finish();
  return created;
}

CefURLRequest::Status URLRequest::GetRequestStatus() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_STATUS, &GetURLRequestLifecycle());
  const bool dispatched = operation->Dispatch(thread_id_);
  const CefURLRequest::Status status = dispatched ? operation->status() : UR_UNKNOWN;
  operation->Finish();
  return status;
}

CefURLRequest::ErrorCode URLRequest::GetRequestError() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_ERROR, &GetURLRequestLifecycle());
  const bool dispatched = operation->Dispatch(thread_id_);
  const CefURLRequest::ErrorCode error = dispatched ? operation->error() : ERR_FAILED;
  operation->Finish();
  return error;
}

CefRefPtr<CefResponse> URLRequest::GetResponse() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_RESPONSE, &GetURLRequestLifecycle());
  const bool dispatched = operation->Dispatch(thread_id_);
  CefRefPtr<CefResponse> response = dispatched ? operation->response() : nullptr;
  operation->Finish();
  return response;
}

bool URLRequest::ResponseWasCached() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_WAS_CACHED, &GetURLRequestLifecycle());
  const bool dispatched = operation->Dispatch(thread_id_);
  const bool response_was_cached = dispatched && operation->response_was_cached();
  operation->Finish();
  return response_was_cached;
}

void URLRequest::Cancel() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_CANCEL, &GetURLRequestLifecycle());
  operation->Dispatch(thread_id_);
  operation->Finish();
}

URLRequestAdmission AcquireURLRequestCreationAdmission() {
  return GetURLRequestLifecycle().AcquireAdmission();
}

URLRequestAccess AcquireURLRequestAccess(jlong token) {
  return GetURLRequestLifecycle().AcquireAccess(token);
}

void OpenURLRequestLifecycle() {
  GetURLRequestLifecycle().Open();
}

void CloseURLRequestLifecycle() {
  GetURLRequestLifecycle().Close();
}

void DisposeURLRequest(JNIEnv* env, jobject jurl_request, jlong token) {
  URLRequestAccess removed = GetURLRequestLifecycle().TakeAccess(token);
  ClearJavaURLRequestTokenIfMatches(env, jurl_request, token);
}

bool CreateStandaloneURLRequest(JNIEnv* env, jobject jurl_request, jobject jrequest, jobject jrequest_client, CefRefPtr<CefRequestContext> request_context, URLRequestAdmission& admission) {
  if (!admission || !jurl_request || !jrequest || !jrequest_client)
    return false;
  CefRefPtr<CefRequest> request = GetRequest(env, jrequest);
  return request && CreateURLRequest(env, jurl_request, request, jrequest_client, request_context, nullptr, admission);
}

jobject CreateFrameURLRequest(JNIEnv* env, jobject jrequest, jobject jrequest_client, CefRefPtr<CefFrame> frame, URLRequestAdmission& admission) {
  if (!admission || !jrequest || !jrequest_client || !frame)
    return nullptr;
  CefRefPtr<CefRequest> request = GetRequest(env, jrequest);
  if (!request)
    return nullptr;

  // Construct the established Java wrapper before starting CEF so every
  // callback observes the exact request, client and CefURLRequest identities
  // supplied by this call.
  ScopedJNIObjectLocal jurl_request(env, NewJNIObject(env, "org/cef/network/CefURLRequest_N", "(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefURLRequestClient;)V", jrequest, jrequest_client));
  if (!jurl_request || !CreateURLRequest(env, jurl_request.get(), request, jrequest_client, nullptr, frame, admission))
    return nullptr;
  return jurl_request.Release();
}

bool RunDisposedCreationRaceForTesting(JNIEnv* env, jobject jurl_request) {
  if (!jurl_request)
    return false;
  URLRequestAdmission admission = AcquireURLRequestCreationAdmission();
  if (!admission)
    return false;
  CefRefPtr<URLRequest> owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  const jlong token = GetURLRequestLifecycle().Register(admission, owner);
  if (token == 0 || !PublishURLRequestToken(env, jurl_request, token)) {
    RollBackURLRequestToken(env, jurl_request, token);
    return false;
  }
  if (!InvokeJavaDisposeForTesting(env, jurl_request)) {
    RollBackURLRequestToken(env, jurl_request, token);
    return false;
  }

  jlong disposed_token = token;
  URLRequestAccess stale_access = AcquireURLRequestAccess(token);
  if (!ReadJavaURLRequestToken(env, jurl_request, &disposed_token) || disposed_token != 0 || stale_access || !owner->HasOneRef()) {
    RollBackURLRequestToken(env, jurl_request, token);
    return false;
  }

  // This is deliberately after Java disposal. The Java factory decision must
  // use this independent result and return the exact now-disposed wrapper.
  ScopedJNIClass url_request_class(env, env->GetObjectClass(jurl_request));
  return url_request_class && SetJNIFieldBoolean(env, url_request_class, jurl_request, "N_CreationSucceeded", true);
}

bool RunTokenRegistryConcurrencyForTesting() {
  struct Control {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    CefRefPtr<URLRequest> owner;
    jlong token = 0;
    int acquired_count = 0;
    int finished_count = 0;
    bool disposed = false;
    bool valid = true;
  };
  std::shared_ptr<Control> control = std::make_shared<Control>();
  control->lifecycle = std::make_shared<URLRequestLifecycle>();
  control->lifecycle->Open();
  control->owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  URLRequestAdmission admission = control->lifecycle->AcquireAdmission();
  control->token = control->lifecycle->Register(admission, control->owner);
  admission = URLRequestAdmission();
  const jlong token = control->token;
  if (token == 0)
    return false;

  auto retain_for_operation = [control]() {
    URLRequestAccess retained = control->lifecycle->AcquireAccess(control->token);
    {
      std::unique_lock<std::mutex> lock(control->lock);
      control->valid = control->valid && retained && retained.operator->() == control->owner.get();
      ++control->acquired_count;
      control->condition.notify_all();
      control->condition.wait(lock, [control]() { return control->disposed; });
      control->valid = control->valid && retained && retained->HasAtLeastOneRef();
      ++control->finished_count;
    }
    control->condition.notify_all();
  };

  // Query and cancel share this exact retained lookup before dispatching CEF.
  std::thread query(retain_for_operation);
  std::thread cancel(retain_for_operation);
  bool acquired_in_time;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    acquired_in_time = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->acquired_count == 2; });
  }

  URLRequestAccess removed = control->lifecycle->TakeAccess(token);
  {
    std::lock_guard<std::mutex> lock(control->lock);
    control->valid = control->valid && acquired_in_time && removed && removed.operator->() == control->owner.get();
    control->disposed = true;
  }
  control->condition.notify_all();
  bool finished_in_time;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    finished_in_time = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->finished_count == 2; });
  }
  if (!finished_in_time) {
    query.detach();
    cancel.detach();
    removed = URLRequestAccess();
    return false;
  }
  query.join();
  cancel.join();

  control->valid = control->valid && !control->lifecycle->AcquireAccess(token) && !control->lifecycle->TakeAccess(token);
  removed = URLRequestAccess();
  return control->valid && control->owner->HasOneRef();
}

bool RunPendingDispatchAbandonmentForTesting() {
  return URLRequestOperation::RunStateMachineForTesting();
}

bool RunURLRequestLifecycleForTesting(JNIEnv* env, jobject jurl_request) {
  if (!jurl_request)
    return false;

  bool valid = true;
  std::shared_ptr<URLRequestLifecycle> lifecycle = std::make_shared<URLRequestLifecycle>();
  lifecycle->Open();

  CefRefPtr<URLRequest> first_owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  URLRequestAdmission admission = lifecycle->AcquireAdmission();
  const jlong first_token = lifecycle->Register(admission, first_owner);
  if (first_token <= 0)
    return false;
  URLRequestAccess retained = lifecycle->AcquireAccess(first_token);
  admission = URLRequestAdmission();
  if (!retained)
    return false;

  struct LifecycleControl {
    std::mutex lock;
    std::condition_variable condition;
    std::shared_ptr<URLRequestLifecycle> lifecycle;
    size_t completed_calls = 0;
    bool final_close_completed = false;
  };
  std::shared_ptr<LifecycleControl> control = std::make_shared<LifecycleControl>();
  control->lifecycle = lifecycle;
  auto mark_completed = [control]() {
    {
      std::lock_guard<std::mutex> lock(control->lock);
      ++control->completed_calls;
    }
    control->condition.notify_all();
  };

  std::thread primary_closer([control, mark_completed]() {
    control->lifecycle->Close();
    mark_completed();
  });
  const bool closing = lifecycle->WaitForPhaseForTesting(URLRequestLifecycle::Phase::CLOSING, kSyntheticTestWatchdogTimeout);
  valid = valid && closing && !lifecycle->AcquireAdmission() && !lifecycle->AcquireAccess(first_token);

  std::thread idempotent_closer([control, mark_completed]() {
    control->lifecycle->Close();
    mark_completed();
  });
  std::thread concurrent_opener([control, mark_completed]() {
    control->lifecycle->Open();
    mark_completed();
  });
  const bool waiters_parked = lifecycle->WaitForLifecycleWaitersForTesting(1, 1, kSyntheticTestWatchdogTimeout);
  {
    std::lock_guard<std::mutex> lock(control->lock);
    valid = valid && waiters_parked && control->completed_calls == 0;
  }

  // Releasing the last retained access lets the primary close drain the
  // registry. The exact close-generation predicate lets both parked callers
  // complete even if Open publishes OPEN before the second Close resumes.
  retained = URLRequestAccess();
  bool all_calls_completed;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    all_calls_completed = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->completed_calls == 3; });
  }
  if (!all_calls_completed) {
    primary_closer.detach();
    idempotent_closer.detach();
    concurrent_opener.detach();
    return false;
  }
  primary_closer.join();
  idempotent_closer.join();
  concurrent_opener.join();

  valid = valid && lifecycle->phase_for_testing() == URLRequestLifecycle::Phase::OPEN && first_owner->HasOneRef() && !lifecycle->AcquireAccess(first_token);
  // The JNI argument keeps this wrapper strongly reachable. Publish the stale
  // local token only for the immediate late-clear check, after its registry
  // generation has drained, so no failure path can leak a token that Java
  // disposal might accidentally route into the independent global lifecycle.
  valid = valid && PublishURLRequestToken(env, jurl_request, first_token);
  ClearJavaURLRequestTokenIfMatches(env, jurl_request, first_token);
  jlong cleared_token = first_token;
  valid = valid && ReadJavaURLRequestToken(env, jurl_request, &cleared_token) && cleared_token == 0;

  CefRefPtr<URLRequest> second_owner = new URLRequest(TID_UI, nullptr, nullptr, nullptr, nullptr);
  admission = lifecycle->AcquireAdmission();
  const jlong second_token = lifecycle->Register(admission, second_owner);
  admission = URLRequestAdmission();
  valid = valid && second_token > first_token;
  std::thread final_closer([control]() {
    control->lifecycle->Close();
    control->lifecycle->Close();
    {
      std::lock_guard<std::mutex> lock(control->lock);
      control->final_close_completed = true;
    }
    control->condition.notify_all();
  });
  bool final_close_completed;
  {
    std::unique_lock<std::mutex> lock(control->lock);
    final_close_completed = control->condition.wait_for(lock, kSyntheticTestWatchdogTimeout, [control]() { return control->final_close_completed; });
  }
  if (!final_close_completed) {
    final_closer.detach();
    WriteJavaURLRequestToken(env, jurl_request, 0);
    return false;
  }
  final_closer.join();
  valid = valid && lifecycle->phase_for_testing() == URLRequestLifecycle::Phase::CLOSED && second_owner->HasOneRef() && !lifecycle->AcquireAccess(first_token) && !lifecycle->AcquireAccess(second_token);
  const bool token_reset = WriteJavaURLRequestToken(env, jurl_request, 0);
  return valid && token_reset;
}
