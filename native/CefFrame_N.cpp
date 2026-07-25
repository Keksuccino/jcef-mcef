// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefFrame_N.h"

#include "include/cef_frame.h"

#include "jni_scoped_helpers.h"
#include "jni_util.h"
#include "string_visitor.h"
#include "url_request.h"

namespace {

CefRefPtr<CefFrame> GetSelf(jlong self) {
  return reinterpret_cast<CefFrame*>(self);
}

void ClearSelf(JNIEnv* env, jobject obj) {
  SetCefForJNIObject<CefFrame>(env, obj, nullptr, "CefFrame");
}

}  // namespace

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Dispose(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong self) {
  ClearSelf(env, obj);
}

JNIEXPORT jstring JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetIdentifier(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return nullptr;
  return NewJNIString(env, frame->GetIdentifier());
}

JNIEXPORT jstring JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetURL(JNIEnv* env,
                                           jobject obj,
                                           jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return nullptr;
  return NewJNIString(env, frame->GetURL());
}

JNIEXPORT jstring JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetName(JNIEnv* env,
                                            jobject obj,
                                            jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return nullptr;
  return NewJNIString(env, frame->GetName());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefFrame_1N_N_1IsMain(JNIEnv* env,
                                           jobject obj,
                                           jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return JNI_FALSE;
  return frame->IsMain() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefFrame_1N_N_1IsValid(JNIEnv* env,
                                            jobject obj,
                                            jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return JNI_FALSE;
  return frame->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefFrame_1N_N_1IsFocused(JNIEnv* env,
                                              jobject obj,
                                              jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return JNI_FALSE;
  return frame->IsFocused() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetParent(JNIEnv* env,
                                              jobject obj,
                                              jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return nullptr;
  CefRefPtr<CefFrame> parent = frame->GetParent();
  if (!parent)
    return nullptr;
  ScopedJNIFrame jparent(env, parent);
  return jparent.Release();
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetBrowser(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return nullptr;
  CefRefPtr<CefBrowser> browser = frame->GetBrowser();
  if (!browser)
    return nullptr;
  ScopedJNIBrowser jbrowser(env, browser);
  return jbrowser.Release();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1ViewSource(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;
  frame->ViewSource();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetSource(JNIEnv* env,
                                              jobject obj,
                                              jlong self,
                                              jobject jvisitor) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame || !jvisitor)
    return;
  frame->GetSource(new StringVisitor(env, jvisitor));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1GetText(JNIEnv* env,
                                            jobject obj,
                                            jlong self,
                                            jobject jvisitor) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame || !jvisitor)
    return;
  frame->GetText(new StringVisitor(env, jvisitor));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1LoadRequest(JNIEnv* env,
                                                jobject obj,
                                                jlong self,
                                                jobject jrequest) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame || !jrequest)
    return;
  ScopedJNIRequest requestObj(env);
  requestObj.SetHandle(jrequest, false /* should_delete */);
  CefRefPtr<CefRequest> request = requestObj.GetCefObject();
  if (!request)
    return;
  frame->LoadRequest(request);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1LoadURL(JNIEnv* env,
                                            jobject obj,
                                            jlong self,
                                            jstring url) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame || !url)
    return;
  frame->LoadURL(GetJNIString(env, url));
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefFrame_1N_N_1CreateURLRequest(JNIEnv* env, jobject obj, jlong self, jobject jrequest, jobject jrequest_client) {
  URLRequestAdmission admission = AcquireURLRequestCreationAdmission();
  if (!admission)
    return nullptr;
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame || !jrequest || !jrequest_client)
    return nullptr;
  return CreateFrameURLRequest(env, jrequest, jrequest_client, frame, admission);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1ExecuteJavaScript(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring code,
                                                      jstring url,
                                                      jint line) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;
  frame->ExecuteJavaScript(GetJNIString(env, code), GetJNIString(env, url),
                           line);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Undo(JNIEnv* env,
                                                                jobject obj,
                                                                jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Undo();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Redo(JNIEnv* env,
                                                                jobject obj,
                                                                jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Redo();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Cut(JNIEnv* env,
                                                               jobject obj,
                                                               jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Cut();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Copy(JNIEnv* env,
                                                                jobject obj,
                                                                jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Copy();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Paste(JNIEnv* env,
                                                                 jobject obj,
                                                                 jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Paste();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1PasteAndMatchStyle(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->PasteAndMatchStyle();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefFrame_1N_N_1Delete(JNIEnv* env,
                                                                  jobject obj,
                                                                  jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->Delete();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefFrame_1N_N_1SelectAll(JNIEnv* env,
                                              jobject obj,
                                              jlong self) {
  CefRefPtr<CefFrame> frame = GetSelf(self);
  if (!frame)
    return;

  frame->SelectAll();
}
