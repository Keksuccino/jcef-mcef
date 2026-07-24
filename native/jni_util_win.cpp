// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "jni_util.h"

#include <assert.h>
#include <jawt.h>
#include <jawt_md.h>

HWND GetHwndOfCanvas(jobject canvas, JNIEnv* env) {
  JAWT awt;
  JAWT_DrawingSurface* ds;
  JAWT_DrawingSurfaceInfo* dsi;
  JAWT_Win32DrawingSurfaceInfo* dsi_win;
  jboolean bGetAwt;
  jint lock;

  awt.version = JAWT_VERSION_1_4;
  bGetAwt = JAWT_GetAWT(env, &awt);
  assert(bGetAwt != JNI_FALSE);

  ds = awt.GetDrawingSurface(env, canvas);
  assert(ds != nullptr);

  lock = ds->Lock(ds);
  if (lock & JAWT_LOCK_ERROR) {
    return 0;
  }

  dsi = ds->GetDrawingSurfaceInfo(ds);
  if (dsi == nullptr) {
    ds->Unlock(ds);
    return 0;
  }

  dsi_win = (JAWT_Win32DrawingSurfaceInfo*)dsi->platformInfo;
  HWND result = dsi_win->hwnd;

  ds->FreeDrawingSurfaceInfo(dsi);
  ds->Unlock(ds);
  awt.FreeDrawingSurface(ds);

  return result;
}
