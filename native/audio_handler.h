// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_AUDIO_HANDLER_H_
#define JCEF_NATIVE_AUDIO_HANDLER_H_
#pragma once

#include <jni.h>

#include <mutex>
#include <unordered_map>

#include "include/cef_audio_handler.h"

#include "jni_scoped_helpers.h"

// https://github.com/chromiumembedded/cef/blob/master/include/cef_audio_handler.h
// AudioHandler implementation.
class AudioHandler : public CefAudioHandler {
 public:
  AudioHandler(JNIEnv* env, jobject handler);

  // CefAudioHandler methods:
  bool GetAudioParameters(CefRefPtr<CefBrowser> browser,
                          CefAudioParameters& params) override;
  void OnAudioStreamStarted(CefRefPtr<CefBrowser> browser,
                            const CefAudioParameters& params,
                            int channels) override;
  void OnAudioStreamPacket(CefRefPtr<CefBrowser> browser,
                           const float** data,
                           int frames,
                           int64_t pts) override;
  void OnAudioStreamStopped(CefRefPtr<CefBrowser> browser) override;
  void OnAudioStreamError(CefRefPtr<CefBrowser> browser,
                          const CefString& text) override;

 private:
  void SetStreamChannels(CefRefPtr<CefBrowser> browser, int channels);
  int GetStreamChannels(CefRefPtr<CefBrowser> browser);
  void RemoveStreamChannels(CefRefPtr<CefBrowser> browser);

  // CEF does not repeat the channel count for packet callbacks. Keep it per
  // globally unique browser ID and synchronize access because start/packet/stop
  // use different CEF threads and a CefClient can own multiple browsers. CEF
  // guarantees a stop callback after every start; handler destruction also
  // releases the complete map.
  std::mutex stream_channels_lock_;
  std::unordered_map<int, int> stream_channels_;

 protected:
  ScopedJNIObjectGlobal handle_;

  // Include the default reference counting implementation.
  IMPLEMENT_REFCOUNTING(AudioHandler);
};

#endif  // JCEF_NATIVE_AUDIO_HANDLER_H_
