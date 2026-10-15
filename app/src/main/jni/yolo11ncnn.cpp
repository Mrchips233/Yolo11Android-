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

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolo11.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

static int draw_status(cv::Mat& rgb, const char* text)
{
    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = std::max(0, (rgb.rows - label_size.height) / 2);
    int x = std::max(0, (rgb.cols - label_size.width) / 2);

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        const double elapsed = t1 - t0;
        t0 = t1;
        if (elapsed <= 0.0)
            return 0;
        float fps = 1000.f / elapsed;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = std::max(0, rgb.cols - label_size.width);

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}

static YOLO11* g_yolo11 = nullptr;
static ncnn::Mutex lock;
static int g_modelid = -1;
static int g_backend = -1;
#if NCNN_VULKAN
static bool g_gpu_initialized = false;
#endif

struct ModelConfig
{
    const char* param;
    const char* weights;
    int target_size;
};

// Keep this table in the same order as spinnerModel in strings.xml.
static const ModelConfig model_configs[] = {
    {"yolo11s.param", "yolo11s.bin", 640},
    {"yolo11m.param", "yolo11m.bin", 640},
    {"yolo11s.param", "yolo11s.bin", 320},
    {"yolo11m.param", "yolo11m.bin", 320},
};

class MyNdkCamera : public NdkCameraWindow
{
public:
    ~MyNdkCamera() override { close(); }
    void on_image_render(cv::Mat& rgb) const override;
};

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // yolo11
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo11)
        {
            std::vector<Object> objects;
            if (g_yolo11->detect(rgb, objects) != 0)
                draw_status(rgb, "inference failed");
            else if (g_yolo11->draw(rgb, objects) != 0)
                draw_status(rgb, "CSV write failed");

        }
        else
        {
            draw_status(rgb, "model unavailable");
        }
    }

    draw_fps(rgb);
}

static MyNdkCamera* g_camera = 0;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnLoad");

    g_camera = new MyNdkCamera;

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM*, void*)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnUnload");

    // Stop image callbacks before releasing the model and its Vulkan device.
    delete g_camera;
    g_camera = nullptr;

    ncnn::MutexLockGuard g(lock);
    delete g_yolo11;
    g_yolo11 = nullptr;
#if NCNN_VULKAN
    if (g_gpu_initialized)
        ncnn::destroy_gpu_instance();
    g_gpu_initialized = false;
#endif
    g_modelid = -1;
    g_backend = -1;
}

// public native boolean loadModel(AssetManager mgr, int taskid, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_loadModel(JNIEnv* env, jobject, jobject assetManager, jint taskid, jint modelid, jint cpugpu)
{
    if (taskid != 0 || modelid < 0 || modelid >= 4 || cpugpu < 0 || cpugpu > 2 || !assetManager)
        return JNI_FALSE;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
        return JNI_FALSE;

    ncnn::MutexLockGuard g(lock);
    const ModelConfig& config = model_configs[modelid];
    if (g_yolo11 && g_backend == cpugpu && g_modelid >= 0 &&
        std::strcmp(model_configs[g_modelid].param, config.param) == 0)
    {
        // Resolution changes can reuse the same dynamic-shape network weights.
        g_yolo11->set_det_target_size(config.target_size);
        g_modelid = modelid;
        return JNI_TRUE;
    }

    // Net destruction must precede destruction of the Vulkan device it uses.
    delete g_yolo11;
    g_yolo11 = nullptr;
    g_modelid = -1;
    if (g_backend != cpugpu)
    {
#if NCNN_VULKAN
        if (g_gpu_initialized)
            ncnn::destroy_gpu_instance();
        g_gpu_initialized = false;
        if (cpugpu != 0)
        {
            const char* driver = cpugpu == 2 ? "libvulkan_freedreno.so" : nullptr;
            const int status = ncnn::create_gpu_instance(driver);
            if (status != 0 || ncnn::get_gpu_count() <= 0)
            {
                __android_log_print(ANDROID_LOG_ERROR, "ncnn", "GPU backend %d unavailable (%d)", cpugpu, status);
                ncnn::destroy_gpu_instance();
                g_backend = -1;
                return JNI_FALSE;
            }
            g_gpu_initialized = true;
        }
#else
        if (cpugpu != 0)
        {
            g_backend = -1;
            return JNI_FALSE;
        }
#endif
        g_backend = cpugpu;
    }

    std::unique_ptr<YOLO11> model(new YOLO11_det);
    const int status = model->load(mgr, config.param, config.weights, cpugpu != 0);
    if (status != 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Model load failed: %s, status=%d", config.param, status);
        return JNI_FALSE;
    }
    model->set_det_target_size(config.target_size);
    g_yolo11 = model.release();
    g_modelid = modelid;
    __android_log_print(ANDROID_LOG_INFO, "ncnn", "Loaded %s, target=%d, backend=%d", config.param, config.target_size, cpugpu);
    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_openCamera(JNIEnv*, jobject, jint facing)
{
    if (!g_camera || facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    return g_camera->open((int)facing) == 0 ? JNI_TRUE : JNI_FALSE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_closeCamera(JNIEnv*, jobject)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    if (!g_camera)
        return JNI_FALSE;
    g_camera->close();
    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_setOutputWindow(JNIEnv* env, jobject, jobject surface)
{
    if (!g_camera)
        return JNI_FALSE;
    ANativeWindow* win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (surface && !win)
        return JNI_FALSE;
    g_camera->set_window(win);
    // set_window retains its own reference; release the JNI conversion reference.
    if (win)
        ANativeWindow_release(win);
    return JNI_TRUE;
}

}
