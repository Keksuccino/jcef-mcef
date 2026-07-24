// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "life_span_handler.h"

#include "include/base/cef_callback.h"
#include "include/wrapper/cef_closure_task.h"

#include "client_handler.h"
#include "jni_util.h"
#include "util.h"

namespace {

void ContinueCloseAfterDoClose(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  ScopedJNIEnv env;
  if (!env) {
    util::DestroyCefBrowser(browser);
    return;
  }

  ScopedJNIBrowser jbrowser(env, browser);
  if (!jbrowser) {
    util::DestroyCefBrowser(browser);
    return;
  }

  JNI_CALL_VOID_METHOD(env, jbrowser, "continueCloseAfterDoClose", "()V");
}

}  // namespace

LifeSpanHandler::LifeSpanHandler(JNIEnv* env, jobject handler)
    : handle_(env, handler) {}

// TODO(JCEF): Expose all parameters.
bool LifeSpanHandler::OnBeforePopup(CefRefPtr<CefBrowser> browser,
                                    CefRefPtr<CefFrame> frame,
                                    int popup_id,
                                    const CefString& target_url,
                                    const CefString& target_frame_name,
                                    WindowOpenDisposition target_disposition,
                                    bool user_gesture,
                                    const CefPopupFeatures& popupFeatures,
                                    CefWindowInfo& windowInfo,
                                    CefRefPtr<CefClient>& client,
                                    CefBrowserSettings& settings,
                                    CefRefPtr<CefDictionaryValue>& extra_info,
                                    bool* no_javascript_access) {
  if (browser->GetHost()->IsWindowRenderingDisabled()) {
    // Cancel popups in off-screen rendering mode.
    return true;
  }

  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIFrame jframe(env, frame);
  jframe.SetTemporary();
  ScopedJNIString jtargetUrl(env, target_url);
  ScopedJNIString jtargetFrameName(env, target_frame_name);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "onBeforePopup",
                  "(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/"
                  "CefFrame;Ljava/lang/String;Ljava/lang/String;)Z",
                  Boolean, jreturn, jbrowser.get(), jframe.get(),
                  jtargetUrl.get(), jtargetFrameName.get());

  return (jreturn != JNI_FALSE);
}

void LifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  ScopedJNIEnv env;
  if (!env || jbrowsers_.empty())
    return;

  util::AddCefBrowser(browser);

  jobject jbrowser = jbrowsers_.front();
  jbrowsers_.pop_front();

  CefRefPtr<ClientHandler> client =
      (ClientHandler*)browser->GetHost()->GetClient().get();
  client->OnAfterCreated();

  // Add a reference to |browser| that will be released in
  // LifeSpanHandler::OnBeforeClose.
  if (SetCefForJNIObject(env, jbrowser, browser.get(), "CefBrowser")) {
    // Creation is not complete when CefBrowserHost merely accepts the request.
    // Publish success only after this Java wrapper owns the native reference so
    // callers cannot issue JNI browser commands into the binding gap.
    JNI_CALL_VOID_METHOD(env, jbrowser, "notifyBrowserCreated", "()V");
    JNI_CALL_VOID_METHOD(env, handle_, "onAfterCreated",
                         "(Lorg/cef/browser/CefBrowser;)V", jbrowser);
  } else {
    JNI_CALL_VOID_METHOD(env, jbrowser, "notifyBrowserCreationFailed", "()V");
  }

  // Release the global ref added in CefBrowser_N::create.
  env->DeleteGlobalRef(jbrowser);
}

bool LifeSpanHandler::DoClose(CefRefPtr<CefBrowser> browser) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "doClose", "(Lorg/cef/browser/CefBrowser;)Z",
                  Boolean, jreturn, jbrowser.get());

  const bool cancel_close = (jreturn != JNI_FALSE);
  jboolean post_continuation = JNI_FALSE;
  JNI_CALL_METHOD(env, jbrowser, "prepareCloseContinuation", "(Z)Z", Boolean, post_continuation, cancel_close ? JNI_TRUE : JNI_FALSE);

  // Swing and CEF use different UI threads on Windows and Linux. Posting to
  // TID_UI ensures the Java continuation cannot force a second close until
  // this DoClose callback has completely unwound.
  if (post_continuation != JNI_FALSE && !CefPostTask(TID_UI, base::BindOnce(&ContinueCloseAfterDoClose, browser))) {
    JNI_CALL_VOID_METHOD(env, jbrowser, "abortCloseContinuation", "()V");
    return false;
  }

  return cancel_close;
}

void LifeSpanHandler::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);

  JNI_CALL_VOID_METHOD(env, handle_, "onBeforeClose",
                       "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());

  // Clear the browser pointer member of the Java object. This will
  // release the browser reference that was added in
  // LifeSpanHandler::OnAfterCreated.
  SetCefForJNIObject<CefBrowser>(env, jbrowser, nullptr, "CefBrowser");

  CefRefPtr<ClientHandler> client =
      (ClientHandler*)browser->GetHost()->GetClient().get();
  client->OnBeforeClose(browser);
}

void LifeSpanHandler::OnAfterParentChanged(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);

  JNI_CALL_VOID_METHOD(env, handle_, "onAfterParentChanged",
                       "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());
}

void LifeSpanHandler::registerJBrowser(jobject browser) {
  jbrowsers_.push_back(browser);
}

void LifeSpanHandler::unregisterJBrowser(jobject browser) {
  jbrowsers_.remove(browser);
}
