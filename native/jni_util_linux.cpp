// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "jni_util.h"

#include <assert.h>
#include <jawt.h>
#include <jawt_md.h>

unsigned long GetDrawableOfCanvas(jobject canvas, JNIEnv* env) {
  JAWT awt;
  JAWT_DrawingSurface* ds;
  JAWT_DrawingSurfaceInfo* dsi;
  JAWT_X11DrawingSurfaceInfo* dsi_x11;
  jint lock;

  awt.version = JAWT_VERSION_1_4;
  JAWT_GetAWT(env, &awt);

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

  dsi_x11 = (JAWT_X11DrawingSurfaceInfo*)dsi->platformInfo;
  Drawable result = dsi_x11->drawable;

  ds->FreeDrawingSurfaceInfo(dsi);
  ds->Unlock(ds);
  awt.FreeDrawingSurface(ds);

  return result;
}
