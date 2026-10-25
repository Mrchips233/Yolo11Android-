// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.Chips.yolo11ncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;


public final class YOLO11Ncnn {
    public native boolean loadModel(AssetManager mgr, int taskid, int modelid, int cpugpu);
    /**
     * Runs the currently loaded model without camera drawing or location/CSV writes.
     * Call on the shared serial native executor with an unrecycled software
     * {@link Bitmap.Config#ARGB_8888} bitmap. Each detection occupies six values:
     * [zero-based label, probability, x, y, width, height], in bitmap pixels.
     * No detections returns an empty array. Invalid input, unavailable models,
     * or inference failures throw an exception; callers must handle failures.
     */
    public native float[] detectBitmap(Bitmap bitmap);
    public native boolean openCamera(int facing);
    public native boolean closeCamera();
    public native boolean setOutputWindow(Surface surface);

    static {
        System.loadLibrary("yolo11ncnn");
    }
}

