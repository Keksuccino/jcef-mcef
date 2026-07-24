// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefCommandLine_N.h"

#include <string>
#include <vector>

#include "include/cef_command_line.h"
#include "jni_scoped_helpers.h"
#include "jni_util.h"

namespace {

const char kCefClassName[] = "CefCommandLine";

CefRefPtr<CefCommandLine> GetSelf(jlong self) {
  return reinterpret_cast<CefCommandLine*>(self);
}

void ThrowJavaException(JNIEnv* env,
                        const char* class_name,
                        const char* message) {
  if (env->ExceptionCheck())
    return;
  ScopedJNIClass exception_class(env, class_name);
  if (exception_class)
    env->ThrowNew(exception_class, message);
}

jobject NewJNICommandLine(JNIEnv* env, CefRefPtr<CefCommandLine> command_line) {
  if (!command_line)
    return nullptr;
  ScopedJNIObject<CefCommandLine> jcommand_line(
      env, command_line, "org/cef/callback/CefCommandLine_N", kCefClassName);
  return jcommand_line.Release();
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1Create(JNIEnv* env, jclass cls) {
  return NewJNICommandLine(env, CefCommandLine::CreateCommandLine());
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetGlobalCommandLine(JNIEnv* env,
                                                                jclass cls) {
  return NewJNICommandLine(env, CefCommandLine::GetGlobalCommandLine());
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1Dispose(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  SetCefForJNIObject<CefCommandLine>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1IsValid(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  return command_line && command_line->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1IsReadOnly(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  return !command_line || command_line->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1Copy(JNIEnv* env,
                                                jobject obj,
                                                jlong self) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  return NewJNICommandLine(env, command_line ? command_line->Copy() : nullptr);
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1InitFromArgv(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self,
                                                        jobjectArray jargv) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  if (!command_line)
    return;
#if defined(OS_WIN)
  ThrowJavaException(env, "java/lang/UnsupportedOperationException",
                     "initFromArgv is not supported on Windows");
#else
  if (!jargv) {
    ThrowJavaException(env, "java/lang/NullPointerException",
                       "argv must not be null");
    return;
  }

  const jsize argc = env->GetArrayLength(jargv);
  if (argc <= 0) {
    ThrowJavaException(env, "java/lang/IllegalArgumentException",
                       "argv must contain the program name");
    return;
  }

  std::vector<std::string> values;
  values.reserve(argc);
  for (jsize i = 0; i < argc; ++i) {
    ScopedJNIStringResult value(
        env, static_cast<jstring>(env->GetObjectArrayElement(jargv, i)));
    if (!value) {
      ThrowJavaException(env, "java/lang/NullPointerException",
                         "argv elements must not be null");
      return;
    }
    values.push_back(value.GetCefString().ToString());
  }
  if (values.front().empty()) {
    ThrowJavaException(env, "java/lang/IllegalArgumentException",
                       "argv[0] must contain the program name");
    return;
  }

  std::vector<const char*> argv;
  argv.reserve(values.size());
  for (const std::string& value : values)
    argv.push_back(value.c_str());
  command_line->InitFromArgv(argc, argv.data());
#endif
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1InitFromString(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jstring jcommand_line) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  if (!command_line)
    return;
#if defined(OS_WIN)
  if (!jcommand_line) {
    ThrowJavaException(env, "java/lang/NullPointerException",
                       "commandLine must not be null");
    return;
  }
  command_line->InitFromString(GetJNIString(env, jcommand_line));
#else
  ThrowJavaException(env, "java/lang/UnsupportedOperationException",
                     "initFromString is supported on Windows only");
#endif
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1Reset(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return;
  commandLine->Reset();
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetArgv(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  CefCommandLine::ArgumentList argv;
  if (command_line)
    command_line->GetArgv(argv);
  return NewJNIStringVector(env, argv);
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetCommandLineString(JNIEnv* env,
                                                                jobject obj,
                                                                jlong self) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  if (!command_line)
    return env->NewStringUTF("");
  return NewJNIString(env, command_line->GetCommandLineString());
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetProgram(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return env->NewStringUTF("");
  return NewJNIString(env, commandLine->GetProgram());
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1SetProgram(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring program) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return;
  commandLine->SetProgram(GetJNIString(env, program));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1HasSwitches(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return JNI_FALSE;
  return commandLine->HasSwitches() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1HasSwitch(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self,
                                                     jstring name) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return JNI_FALSE;
  return commandLine->HasSwitch(GetJNIString(env, name)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetSwitchValue(JNIEnv* env,
                                                          jobject obj,
                                                          jlong self,
                                                          jstring name) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return env->NewStringUTF("");
  return NewJNIString(env,
                      commandLine->GetSwitchValue(GetJNIString(env, name)));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetSwitches(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  CefCommandLine::SwitchMap switches;
  if (commandLine)
    commandLine->GetSwitches(switches);
  return NewJNIStringMap(env, switches);
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1AppendSwitch(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self,
                                                        jstring name) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return;
  commandLine->AppendSwitch(GetJNIString(env, name));
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1AppendSwitchWithValue(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jstring name,
    jstring value) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return;
  commandLine->AppendSwitchWithValue(GetJNIString(env, name),
                                     GetJNIString(env, value));
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1RemoveSwitch(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self,
                                                        jstring name) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  if (!command_line)
    return;
  command_line->RemoveSwitch(GetJNIString(env, name));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1HasArguments(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return JNI_FALSE;
  return commandLine->HasArguments() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1GetArguments(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  CefCommandLine::ArgumentList arguments;
  if (commandLine)
    commandLine->GetArguments(arguments);
  return NewJNIStringVector(env, arguments);
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1AppendArgument(JNIEnv* env,
                                                          jobject obj,
                                                          jlong self,
                                                          jstring argument) {
  CefRefPtr<CefCommandLine> commandLine = GetSelf(self);
  if (!commandLine)
    return;
  commandLine->AppendArgument(GetJNIString(env, argument));
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefCommandLine_1N_N_1PrependWrapper(JNIEnv* env,
                                                          jobject obj,
                                                          jlong self,
                                                          jstring wrapper) {
  CefRefPtr<CefCommandLine> command_line = GetSelf(self);
  if (!command_line)
    return;
  command_line->PrependWrapper(GetJNIString(env, wrapper));
}
