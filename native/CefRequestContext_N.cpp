// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefRequestContext_N.h"

#include <cstdint>
#include <string>

#include "include/cef_request_context.h"

#include "completion_callback.h"
#include "jni_util.h"
#include "request_context_handler.h"
#include "resolve_callback.h"
#include "scheme_handler_factory.h"
#include "setting_observer.h"

namespace {

constexpr char kCefClassName[] = "CefRequestContext";

// Java exposes these values directly, so fail compilation instead of silently
// crossing an ABI boundary when the selected CEF headers change. Interior
// assertions cover the version-gated slots that are most likely to shift, while
// the sentinels prove the complete CEF 151 ranges.
static_assert(CEF_API_VERSION == 15100,
              "CefRequestContext JNI requires CEF API 15100");
static_assert(CEF_CONTENT_SETTING_TYPE_COOKIES == 0);
static_assert(CEF_CONTENT_SETTING_TYPE_PERSISTENT_STORAGE == 18);
static_assert(CEF_CONTENT_SETTING_TYPE_INSECURE_PRIVATE_NETWORK_DEPRECATED ==
              60);
static_assert(CEF_CONTENT_SETTING_TYPE_PRIVATE_NETWORK_GUARD_DEPRECATED == 77);
static_assert(
    CEF_CONTENT_SETTING_TYPE_THIRD_PARTY_STORAGE_PARTITIONING_DEPRECATED == 85);
static_assert(CEF_CONTENT_SETTING_TYPE_TOP_LEVEL_TPCD_ORIGIN_TRIAL_DEPRECATED ==
              93);
static_assert(CEF_CONTENT_SETTING_TYPE_SUB_APP_INSTALLATION_PROMPTS == 102);
static_assert(CEF_CONTENT_SETTING_TYPE_TRACKING_PROTECTION_DEPRECATED == 108);
static_assert(CEF_CONTENT_SETTING_TYPE_LOCAL_NETWORK == 127);
static_assert(CEF_CONTENT_SETTING_TYPE_INLINE_CUE_MENU == 130);
static_assert(CEF_CONTENT_SETTING_TYPE_NUM_VALUES == 131);
static_assert(CEF_CONTENT_SETTING_VALUE_DEFAULT == 0);
static_assert(CEF_CONTENT_SETTING_VALUE_DETECT_IMPORTANT_CONTENT_DEPRECATED ==
              5);
static_assert(CEF_CONTENT_SETTING_VALUE_NUM_VALUES == 6);
static_assert(CEF_COLOR_VARIANT_SYSTEM == 0);
static_assert(CEF_COLOR_VARIANT_EXPRESSIVE == 6);
static_assert(CEF_COLOR_VARIANT_NUM_VALUES == 7);

void ThrowJavaException(JNIEnv* env, const char* class_name, const std::string& message) {
  if (env->ExceptionCheck())
    return;
  ScopedJNIClass exception_class(env, class_name);
  if (exception_class)
    env->ThrowNew(exception_class, message.c_str());
}

void ThrowIllegalArgumentException(JNIEnv* env, const std::string& message) {
  ThrowJavaException(env, "java/lang/IllegalArgumentException", message);
}

bool RequireUIThread(JNIEnv* env, const char* method_name) {
  if (CefCurrentlyOn(TID_UI))
    return true;
  ThrowJavaException(env, "java/lang/IllegalStateException", std::string(method_name) + " must be called on the CEF UI thread");
  return false;
}

bool IsValidContentType(jint content_type) {
  return content_type >= 0 &&
         content_type < CEF_CONTENT_SETTING_TYPE_NUM_VALUES;
}

bool IsValidContentValue(jint value) {
  return value >= 0 && value < CEF_CONTENT_SETTING_VALUE_NUM_VALUES;
}

bool IsValidColorVariant(jint variant) {
  return variant >= 0 && variant < CEF_COLOR_VARIANT_NUM_VALUES;
}

CefRefPtr<CefRequestContext> GetContext(JNIEnv* env, jobject context) {
  return GetCefFromJNIObject<CefRequestContext>(env, context, kCefClassName);
}

CefRefPtr<CefRequestContextHandler> GetRequestContextHandler(JNIEnv* env, jobject handler) {
  if (!handler)
    return nullptr;
  return new RequestContextHandler(env, handler);
}

CefRefPtr<CefCompletionCallback> GetCompletionCallback(JNIEnv* env, jobject callback) {
  if (!callback)
    return nullptr;
  return new CompletionCallback(env, callback);
}

CefRequestContextSettings GetJNIRequestContextSettings(JNIEnv* env, jobject object) {
  CefRequestContextSettings settings;
  ScopedJNIClass cls(env, "org/cef/CefRequestContextSettings");
  if (!object || !cls)
    return settings;

  CefString value;
  if (GetJNIFieldString(env, cls, object, "cache_path", &value) && !value.empty()) {
    CefString(&settings.cache_path) = value;
    value.clear();
  }
  GetJNIFieldBoolean(env, cls, object, "persist_session_cookies", &settings.persist_session_cookies);
  if (GetJNIFieldString(env, cls, object, "accept_language_list", &value) && !value.empty()) {
    CefString(&settings.accept_language_list) = value;
    value.clear();
  }
  if (GetJNIFieldString(env, cls, object, "cookieable_schemes_list", &value) && !value.empty()) {
    CefString(&settings.cookieable_schemes_list) = value;
  }
  GetJNIFieldBoolean(env, cls, object, "cookieable_schemes_exclude_defaults", &settings.cookieable_schemes_exclude_defaults);
  return settings;
}

jobject NewJNIRequestContext(JNIEnv* env, jclass cls, CefRefPtr<CefRequestContext> context) {
  if (!context)
    return nullptr;
  ScopedJNIObjectLocal java_context(env, NewJNIObject(env, cls));
  if (!java_context)
    return nullptr;
  SetCefForJNIObject(env, java_context, context.get(), kCefClassName);
  return java_context.Release();
}

class ScopedJNICookieManager : public ScopedJNIObject<CefCookieManager> {
 public:
  ScopedJNICookieManager(JNIEnv* env, CefRefPtr<CefCookieManager> manager) : ScopedJNIObject<CefCookieManager>(env, manager, "org/cef/network/CefCookieManager_N", "CefCookieManager") {}
};

class ScopedJNIRegistration : public ScopedJNIObject<CefRegistration> {
 public:
  ScopedJNIRegistration(JNIEnv* env, CefRefPtr<CefRegistration> registration) : ScopedJNIObject<CefRegistration>(env, registration, "org/cef/browser/CefRegistration_N", "CefRegistration") {}
};

// A null value passed directly to SetPreference means "restore the default".
// Nested nulls still flow through the general converter as VTYPE_NULL values.
bool IsPreferenceResetValue(jobject value) {
  return value == nullptr;
}

}  // namespace

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetGlobalContext(JNIEnv* env, jclass cls) {
  return NewJNIRequestContext(env, cls, CefRequestContext::GetGlobalContext());
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1CreateContext(JNIEnv* env, jclass cls, jobject settings, jobject handler) {
  if (!settings) {
    ThrowIllegalArgumentException(env, "settings must not be null");
    return nullptr;
  }
  CefRefPtr<CefRequestContext> context = CefRequestContext::CreateContext(GetJNIRequestContextSettings(env, settings), GetRequestContextHandler(env, handler));
  return NewJNIRequestContext(env, cls, context);
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1CreateContextShared(JNIEnv* env, jclass cls, jobject other, jobject handler) {
  CefRefPtr<CefRequestContext> other_context = GetContext(env, other);
  if (!other_context) {
    ThrowIllegalArgumentException(env, "other must reference a live CefRequestContext");
    return nullptr;
  }
  CefRefPtr<CefRequestContext> context = CefRequestContext::CreateContext(other_context, GetRequestContextHandler(env, handler));
  return NewJNIRequestContext(env, cls, context);
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1IsSame(JNIEnv* env, jobject object, jobject other) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  CefRefPtr<CefRequestContext> other_context = GetContext(env, other);
  return context && other_context && context->IsSame(other_context) ? JNI_TRUE
                                                                    : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1IsSharingWith(JNIEnv* env, jobject object, jobject other) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  CefRefPtr<CefRequestContext> other_context = GetContext(env, other);
  return context && other_context && context->IsSharingWith(other_context)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1IsGlobal(JNIEnv* env, jobject object) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context && context->IsGlobal() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetCachePath(JNIEnv* env, jobject object) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context ? NewJNIString(env, context->GetCachePath()) : nullptr;
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetCookieManager(JNIEnv* env, jobject object, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return nullptr;
  CefRefPtr<CefCookieManager> manager = context->GetCookieManager(GetCompletionCallback(env, callback));
  ScopedJNICookieManager java_manager(env, manager);
  return java_manager.Release();
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1RegisterSchemeHandlerFactory(JNIEnv* env, jobject object, jstring scheme_name, jstring domain_name, jobject factory) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return JNI_FALSE;
  CefRefPtr<CefSchemeHandlerFactory> cef_factory;
  if (factory)
    cef_factory = new SchemeHandlerFactory(env, factory);
  return context->RegisterSchemeHandlerFactory(GetJNIString(env, scheme_name), GetJNIString(env, domain_name), cef_factory)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1ClearSchemeHandlerFactories(JNIEnv* env, jobject object) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context && context->ClearSchemeHandlerFactories() ? JNI_TRUE
                                                           : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1ClearCertificateExceptions(JNIEnv* env, jobject object, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->ClearCertificateExceptions(GetCompletionCallback(env, callback));
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1ClearHttpCache(JNIEnv* env, jobject object, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->ClearHttpCache(GetCompletionCallback(env, callback));
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1ClearHttpAuthCredentials(JNIEnv* env, jobject object, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->ClearHttpAuthCredentials(GetCompletionCallback(env, callback));
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1CloseAllConnections(JNIEnv* env, jobject object, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->CloseAllConnections(GetCompletionCallback(env, callback));
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1ResolveHost(JNIEnv* env, jobject object, jstring origin, jobject callback) {
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context || !callback)
    return;
  context->ResolveHost(GetJNIString(env, origin), new ResolveCallback(env, callback));
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetWebsiteSetting(JNIEnv* env, jobject object, jstring requesting_url, jstring top_level_url, jint content_type) {
  if (!RequireUIThread(env, "getWebsiteSetting"))
    return nullptr;
  if (!IsValidContentType(content_type)) {
    ThrowIllegalArgumentException(env, "contentType is outside the CEF API 15100 range");
    return nullptr;
  }
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return nullptr;
  CefRefPtr<CefValue> value = context->GetWebsiteSetting(GetJNIString(env, requesting_url), GetJNIString(env, top_level_url), static_cast<cef_content_setting_types_t>(content_type));
  return value ? NewJNIObjectFromCefValue(env, value) : nullptr;
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1SetWebsiteSetting(JNIEnv* env, jobject object, jstring requesting_url, jstring top_level_url, jint content_type, jobject value) {
  if (!IsValidContentType(content_type)) {
    ThrowIllegalArgumentException(env, "contentType is outside the CEF API 15100 range");
    return;
  }
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return;
  CefRefPtr<CefValue> cef_value;
  if (value) {
    cef_value = GetCefValueFromJNIObject(env, value);
    if (env->ExceptionCheck() || !cef_value)
      return;
  }
  context->SetWebsiteSetting(GetJNIString(env, requesting_url), GetJNIString(env, top_level_url), static_cast<cef_content_setting_types_t>(content_type), cef_value);
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetContentSetting(JNIEnv* env, jobject object, jstring requesting_url, jstring top_level_url, jint content_type) {
  if (!RequireUIThread(env, "getContentSetting"))
    return CEF_CONTENT_SETTING_VALUE_DEFAULT;
  if (!IsValidContentType(content_type)) {
    ThrowIllegalArgumentException(env, "contentType is outside the CEF API 15100 range");
    return CEF_CONTENT_SETTING_VALUE_DEFAULT;
  }
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return CEF_CONTENT_SETTING_VALUE_DEFAULT;
  return static_cast<jint>(context->GetContentSetting(GetJNIString(env, requesting_url), GetJNIString(env, top_level_url), static_cast<cef_content_setting_types_t>(content_type)));
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1SetContentSetting(JNIEnv* env, jobject object, jstring requesting_url, jstring top_level_url, jint content_type, jint value) {
  if (!IsValidContentType(content_type) || !IsValidContentValue(value)) {
    ThrowIllegalArgumentException(env, "content type or value is outside the CEF API 15100 range");
    return;
  }
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->SetContentSetting(GetJNIString(env, requesting_url), GetJNIString(env, top_level_url), static_cast<cef_content_setting_types_t>(content_type), static_cast<cef_content_setting_values_t>(value));
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1AddSettingObserver(JNIEnv* env, jobject object, jobject observer) {
  if (!RequireUIThread(env, "addSettingObserver"))
    return nullptr;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context || !observer)
    return nullptr;
  CefRefPtr<SettingObserver> native_observer = new SettingObserver(env, observer);
  CefRefPtr<CefRegistration> native_registration = context->AddSettingObserver(native_observer);
  if (!native_registration) {
    native_observer->Dispose();
    return nullptr;
  }
  CefRefPtr<CefRegistration> registration = new SettingObserverRegistration(native_registration, native_observer);
  ScopedJNIRegistration java_registration(env, registration);
  return java_registration.Release();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1SetChromeColorScheme(JNIEnv* env, jobject object, jint variant, jint user_color) {
  if (!IsValidColorVariant(variant)) {
    ThrowIllegalArgumentException(env, "variant is outside the CEF API 15100 range");
    return;
  }
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (context)
    context->SetChromeColorScheme(static_cast<cef_color_variant_t>(variant), static_cast<cef_color_t>(static_cast<uint32_t>(user_color)));
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetChromeColorSchemeMode(JNIEnv* env, jobject object) {
  if (!RequireUIThread(env, "getChromeColorSchemeMode"))
    return CEF_COLOR_VARIANT_SYSTEM;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context ? static_cast<jint>(context->GetChromeColorSchemeMode())
                 : CEF_COLOR_VARIANT_SYSTEM;
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetChromeColorSchemeColor(JNIEnv* env, jobject object) {
  if (!RequireUIThread(env, "getChromeColorSchemeColor"))
    return 0;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context ? static_cast<jint>(context->GetChromeColorSchemeColor()) : 0;
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetChromeColorSchemeVariant(JNIEnv* env, jobject object) {
  if (!RequireUIThread(env, "getChromeColorSchemeVariant"))
    return CEF_COLOR_VARIANT_SYSTEM;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context ? static_cast<jint>(context->GetChromeColorSchemeVariant())
                 : CEF_COLOR_VARIANT_SYSTEM;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1HasPreference(JNIEnv* env, jobject object, jstring name) {
  if (!RequireUIThread(env, "hasPreference"))
    return JNI_FALSE;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context && context->HasPreference(GetJNIString(env, name)) ? JNI_TRUE
                                                                    : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetPreference(JNIEnv* env, jobject object, jstring name) {
  if (!RequireUIThread(env, "getPreference"))
    return nullptr;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return nullptr;
  CefRefPtr<CefValue> value = context->GetPreference(GetJNIString(env, name));
  return value ? NewJNIObjectFromCefValue(env, value) : nullptr;
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1GetAllPreferences(JNIEnv* env, jobject object, jboolean include_defaults) {
  if (!RequireUIThread(env, "getAllPreferences"))
    return nullptr;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return nullptr;
  CefRefPtr<CefDictionaryValue> value = context->GetAllPreferences(include_defaults == JNI_TRUE);
  if (!value)
    return nullptr;
  CefRefPtr<CefValue> dictionary_value = CefValue::Create();
  if (!dictionary_value->SetDictionary(value))
    return nullptr;
  return NewJNIObjectFromCefValue(env, dictionary_value);
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1CanSetPreference(JNIEnv* env, jobject object, jstring name) {
  if (!RequireUIThread(env, "canSetPreference"))
    return JNI_FALSE;
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  return context && context->CanSetPreference(GetJNIString(env, name))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1SetPreference(JNIEnv* env, jobject object, jstring name, jobject value) {
  if (!CefCurrentlyOn(TID_UI))
    return NewJNIString(env, "called on invalid thread");
  CefRefPtr<CefRequestContext> context = GetContext(env, object);
  if (!context)
    return NewJNIString(env, "no request context");

  CefRefPtr<CefValue> cef_value;
  if (!IsPreferenceResetValue(value)) {
    cef_value = GetCefValueFromJNIObject(env, value);
    if (env->ExceptionCheck())
      return nullptr;
    if (!cef_value)
      return NewJNIString(env, "no value to set");
  }

  CefString error;
  if (!context->SetPreference(GetJNIString(env, name), cef_value, error))
    return NewJNIString(env, error);
  return nullptr;
}

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1RoundTripPreferenceValueForTesting(JNIEnv* env, jclass, jobject value) {
  CefRefPtr<CefValue> cef_value = GetCefValueFromJNIObject(env, value);
  if (env->ExceptionCheck() || !cef_value)
    return nullptr;
  return NewJNIObjectFromCefValue(env, cef_value);
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1IsPreferenceResetForTesting(JNIEnv*, jclass, jobject value) {
  return IsPreferenceResetValue(value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefRequestContext_1N_N_1CefRequestContext_1DTOR(JNIEnv* env, jobject object) {
  SetCefForJNIObject<CefRequestContext>(env, object, nullptr, kCefClassName);
}
