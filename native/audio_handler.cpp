// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "audio_handler.h"

#include <cstdint>
#include <vector>

#include "jni_util.h"

namespace {

uint8_t g_zero_frame_buffer = 0;

// Keep these synchronized with media::limits values used by Chromium's
// AudioParameters::IsValid implementation. Rechecking after the Java getters
// closes the small race created by retaining mutable public fields for source
// compatibility.
constexpr jint kMinSampleRate = 3000;
constexpr jint kMaxSampleRate = 768000;
constexpr jint kMaxFramesPerBuffer = 768000;

bool IsConfigurableChannelLayout(jint layout_id) {
  // CefAudioParameters has no separate channel-count or bitstream-format
  // field. Chromium maps these two layouts to zero channels without that
  // additional data, which makes AudioParameters::IsValid() fail.
  return layout_id > static_cast<jint>(CEF_CHANNEL_LAYOUT_UNSUPPORTED) &&
         layout_id < static_cast<jint>(CEF_CHANNEL_NUM_VALUES) &&
         layout_id != static_cast<jint>(CEF_CHANNEL_LAYOUT_DISCRETE) &&
         layout_id != static_cast<jint>(CEF_CHANNEL_LAYOUT_BITSTREAM);
}

bool DescribeAndClearJNIException(JNIEnv* env) {
  if (!env->ExceptionCheck())
    return false;

  env->ExceptionDescribe();
  env->ExceptionClear();
  return true;
}

jobject NewJNICefAudioParameters(JNIEnv* env,
                                 const CefAudioParameters& params) {
  ScopedJNIClass layout_class(env, "org/cef/misc/CefChannelLayout");
  if (!layout_class)
    return nullptr;

  jmethodID for_id = env->GetStaticMethodID(
      layout_class, "forId", "(I)Lorg/cef/misc/CefChannelLayout;");
  if (DescribeAndClearJNIException(env) || !for_id)
    return nullptr;

  ScopedJNIObjectLocal layout(
      env, env->CallStaticObjectMethod(
               layout_class, for_id, static_cast<jint>(params.channel_layout)));
  if (DescribeAndClearJNIException(env) || !layout)
    return nullptr;

  ScopedJNIClass params_class(env, "org/cef/misc/CefAudioParameters");
  if (!params_class)
    return nullptr;

  jmethodID constructor = env->GetMethodID(
      params_class, "<init>", "(Lorg/cef/misc/CefChannelLayout;IIZ)V");
  if (DescribeAndClearJNIException(env) || !constructor)
    return nullptr;

  jobject result =
      env->NewObject(params_class, constructor, layout.get(),
                     static_cast<jint>(params.sample_rate),
                     static_cast<jint>(params.frames_per_buffer), JNI_TRUE);
  if (DescribeAndClearJNIException(env))
    return nullptr;
  return result;
}

bool GetJNICefAudioParameters(JNIEnv* env,
                              jobject source,
                              CefAudioParameters& destination) {
  if (!source)
    return false;

  jboolean valid = JNI_FALSE;
  JNI_CALL_METHOD(env, source, "isValid", "()Z", Boolean, valid);
  if (valid == JNI_FALSE)
    return false;

  ScopedJNIObjectResult layout(env);
  JNI_CALL_METHOD(env, source, "getChannelLayout",
                  "()Lorg/cef/misc/CefChannelLayout;", Object, layout);
  if (!layout)
    return false;

  jint layout_id = -1;
  JNI_CALL_METHOD(env, layout, "getId", "()I", Int, layout_id);

  jint sample_rate = 0;
  JNI_CALL_METHOD(env, source, "getSampleRate", "()I", Int, sample_rate);

  jint frames_per_buffer = 0;
  JNI_CALL_METHOD(env, source, "getFramesPerBuffer", "()I", Int,
                  frames_per_buffer);

  if (!IsConfigurableChannelLayout(layout_id) ||
      sample_rate < kMinSampleRate || sample_rate > kMaxSampleRate ||
      frames_per_buffer <= 0 || frames_per_buffer > kMaxFramesPerBuffer) {
    return false;
  }

  destination.channel_layout = static_cast<cef_channel_layout_t>(layout_id);
  destination.sample_rate = sample_rate;
  destination.frames_per_buffer = frames_per_buffer;
  return true;
}

jobject NewJNICallbackDataPointer(JNIEnv* env,
                                  const float** data,
                                  int channels,
                                  int frames) {
  const jlong pointer_bytes =
      static_cast<jlong>(channels) * static_cast<jlong>(sizeof(float*));
  const jlong sample_bytes =
      static_cast<jlong>(frames) * static_cast<jlong>(sizeof(float));

  ScopedJNIObjectLocal pointer_buffer(
      env,
      env->NewDirectByteBuffer(
          const_cast<void*>(static_cast<const void*>(data)), pointer_bytes));
  if (DescribeAndClearJNIException(env) || !pointer_buffer)
    return nullptr;

  jlongArray channel_addresses = env->NewLongArray(channels);
  ScopedJNIObjectLocal channel_addresses_ref(env, channel_addresses);
  if (DescribeAndClearJNIException(env) || !channel_addresses_ref)
    return nullptr;

  ScopedJNIClass byte_buffer_class(env, "java/nio/ByteBuffer");
  if (!byte_buffer_class)
    return nullptr;

  jobjectArray channel_buffers =
      env->NewObjectArray(channels, byte_buffer_class, nullptr);
  ScopedJNIObjectLocal channel_buffers_ref(env, channel_buffers);
  if (DescribeAndClearJNIException(env) || !channel_buffers_ref)
    return nullptr;

  std::vector<jlong> addresses(channels);
  for (int channel = 0; channel < channels; ++channel) {
    if (!data[channel] && frames > 0)
      return nullptr;

    addresses[channel] =
        static_cast<jlong>(reinterpret_cast<intptr_t>(data[channel]));
    void* channel_data =
        data[channel] ? static_cast<void*>(const_cast<float*>(data[channel]))
                      : static_cast<void*>(&g_zero_frame_buffer);
    ScopedJNIObjectLocal channel_buffer(
        env, env->NewDirectByteBuffer(channel_data, sample_bytes));
    if (DescribeAndClearJNIException(env) || !channel_buffer)
      return nullptr;

    env->SetObjectArrayElement(channel_buffers, channel, channel_buffer.get());
    if (DescribeAndClearJNIException(env))
      return nullptr;
  }

  env->SetLongArrayRegion(channel_addresses, 0, channels, addresses.data());
  if (DescribeAndClearJNIException(env))
    return nullptr;

  ScopedJNIClass data_pointer_class(env, "org/cef/misc/DataPointer");
  if (!data_pointer_class)
    return nullptr;

  jmethodID constructor =
      env->GetMethodID(data_pointer_class, "<init>",
                       "(JLjava/nio/ByteBuffer;[J[Ljava/nio/ByteBuffer;II)V");
  if (DescribeAndClearJNIException(env) || !constructor)
    return nullptr;

  jobject result = env->NewObject(
      data_pointer_class, constructor,
      static_cast<jlong>(reinterpret_cast<intptr_t>(data)),
      pointer_buffer.get(), channel_addresses, channel_buffers,
      static_cast<jint>(frames), static_cast<jint>(sizeof(float*)));
  if (DescribeAndClearJNIException(env))
    return nullptr;
  return result;
}

}  // namespace

AudioHandler::AudioHandler(JNIEnv* env, jobject handler)
    : handle_(env, handler) {}

bool AudioHandler::GetAudioParameters(CefRefPtr<CefBrowser> browser,
                                      CefAudioParameters& params) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIObjectLocal jparams(env, NewJNICefAudioParameters(env, params));
  if (!jparams)
    return false;

  jboolean callback_result = JNI_FALSE;
  JNI_CALL_METHOD(
      env, handle_, "getAudioParameters",
      "(Lorg/cef/browser/CefBrowser;Lorg/cef/misc/CefAudioParameters;)Z",
      Boolean, callback_result, jbrowser.get(), jparams.get());
  if (callback_result == JNI_FALSE)
    return false;

  CefAudioParameters updated_params = params;
  if (!GetJNICefAudioParameters(env, jparams.get(), updated_params))
    return false;

  params = updated_params;
  return true;
}

void AudioHandler::OnAudioStreamStarted(CefRefPtr<CefBrowser> browser,
                                        const CefAudioParameters& params,
                                        int channels) {
  SetStreamChannels(browser, channels);

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIObjectLocal jparams(env, NewJNICefAudioParameters(env, params));
  if (!jparams)
    return;

  JNI_CALL_VOID_METHOD(
      env, handle_, "onAudioStreamStarted",
      "(Lorg/cef/browser/CefBrowser;Lorg/cef/misc/CefAudioParameters;I)V",
      jbrowser.get(), jparams.get(), static_cast<jint>(channels));
}

void AudioHandler::OnAudioStreamPacket(CefRefPtr<CefBrowser> browser,
                                       const float** data,
                                       int frames,
                                       int64_t pts) {
  if (!data || frames < 0)
    return;

  const int channels = GetStreamChannels(browser);
  if (channels <= 0)
    return;

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIObjectLocal data_ptr(
      env, NewJNICallbackDataPointer(env, data, channels, frames));
  if (!data_ptr)
    return;

  JNI_CALL_VOID_METHOD(
      env, handle_, "onAudioStreamPacket",
      "(Lorg/cef/browser/CefBrowser;Lorg/cef/misc/DataPointer;IJ)V",
      jbrowser.get(), data_ptr.get(), static_cast<jint>(frames),
      static_cast<jlong>(pts));

  // CEF owns both the float** and its channel buffers only for the callback.
  // Invalidate the shared Java lifetime token even when the callback threw;
  // JNI_CALL_VOID_METHOD reports and clears that exception before returning.
  JNI_CALL_VOID_METHOD(env, data_ptr, "invalidate", "()V");
}

void AudioHandler::OnAudioStreamStopped(CefRefPtr<CefBrowser> browser) {
  RemoveStreamChannels(browser);

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  JNI_CALL_VOID_METHOD(env, handle_, "onAudioStreamStopped",
                       "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());
}

void AudioHandler::OnAudioStreamError(CefRefPtr<CefBrowser> browser,
                                      const CefString& text) {
  RemoveStreamChannels(browser);

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIString jtext(env, text);
  JNI_CALL_VOID_METHOD(env, handle_, "onAudioStreamError",
                       "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;)V",
                       jbrowser.get(), jtext.get());
}

void AudioHandler::SetStreamChannels(CefRefPtr<CefBrowser> browser,
                                     int channels) {
  if (!browser)
    return;

  const int browser_id = browser->GetIdentifier();
  std::lock_guard<std::mutex> lock(stream_channels_lock_);
  if (channels > 0) {
    stream_channels_[browser_id] = channels;
  } else {
    stream_channels_.erase(browser_id);
  }
}

int AudioHandler::GetStreamChannels(CefRefPtr<CefBrowser> browser) {
  if (!browser)
    return 0;

  const int browser_id = browser->GetIdentifier();
  std::lock_guard<std::mutex> lock(stream_channels_lock_);
  const auto it = stream_channels_.find(browser_id);
  return it == stream_channels_.end() ? 0 : it->second;
}

void AudioHandler::RemoveStreamChannels(CefRefPtr<CefBrowser> browser) {
  if (!browser)
    return;

  const int browser_id = browser->GetIdentifier();
  std::lock_guard<std::mutex> lock(stream_channels_lock_);
  stream_channels_.erase(browser_id);
}
