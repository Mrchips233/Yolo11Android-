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
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <limits>
#include <new>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolo11.h"

#include "ndkcamera.h"
#include "runtime_metrics.h"

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

static int draw_fps(cv::Mat& rgb, bool measured, double fps)
{
    char text[32];
    // Same completed-callback count / elapsed window as YoloPerf. It includes
    // frame gaps and failed callbacks; it is not camera refresh rate or a mean
    // of instantaneous reciprocal frame times. Wait for the first full window.
    if (measured)
        std::snprintf(text, sizeof(text), "FPS=%.2f", fps);
    else
        std::snprintf(text, sizeof(text), "FPS=...");

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
static RuntimeMetrics g_metrics;
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
    {"yolo11n_tt100k45.param", "yolo11n_tt100k45.bin", 960},
    {"yolo11n_tt100k45.param", "yolo11n_tt100k45.bin", 640},
};

static const char* backend_name(int backend)
{
    switch (backend)
    {
    case 0: return "CPU";
    case 1: return "Vulkan";
    case 2: return "Turnip";
    default: return "unavailable";
    }
}

static int current_target_size()
{
    const int model_count = sizeof(model_configs) / sizeof(model_configs[0]);
    return g_modelid >= 0 && g_modelid < model_count ? model_configs[g_modelid].target_size : 0;
}

static void log_model_config(const char* status, int backend, int target, bool reused, double started_ms)
{
    __android_log_print(std::strcmp(status, "ok") == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        "ModelConfig", "status=%s backend=%s input=%d reused=%d total_ms=%.2f",
                        status, backend_name(backend), target, reused ? 1 : 0,
                        ncnn::get_current_time() - started_ms);
}

// Java calls this API on its shared serial native executor. Keep the native
// model mutex as well, so a camera callback cannot overlap model inference.
class ScopedBitmapPixels
{
public:
    ScopedBitmapPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap)
    {
        status_ = AndroidBitmap_lockPixels(env_, bitmap_, &pixels_);
    }

    ~ScopedBitmapPixels()
    {
        if (status_ == ANDROID_BITMAP_RESULT_SUCCESS)
            AndroidBitmap_unlockPixels(env_, bitmap_);
    }

    ScopedBitmapPixels(const ScopedBitmapPixels&) = delete;
    ScopedBitmapPixels& operator=(const ScopedBitmapPixels&) = delete;
    bool valid() const { return status_ == ANDROID_BITMAP_RESULT_SUCCESS && pixels_ != nullptr; }
    void* pixels() const { return pixels_; }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
    int status_ = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
};

static jfloatArray bitmap_error(JNIEnv* env, const char* exception_class, const char* message)
{
    // Preserve exceptions already raised by the Android bitmap/JNI APIs.
    if (!env->ExceptionCheck())
    {
        jclass exception = env->FindClass(exception_class);
        if (exception)
        {
            env->ThrowNew(exception, message);
            env->DeleteLocalRef(exception);
        }
    }
    return nullptr;
}

class MyNdkCamera : public NdkCameraWindow
{
public:
    ~MyNdkCamera() override { close(); }
    void on_image_render(cv::Mat& rgb) const override;
};

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    bool measured = false;
    double fps = 0.0;
    {
        ncnn::MutexLockGuard g(lock);
        bool available = g_yolo11 != nullptr;
        int detect_status = 0;
        int draw_status_code = 0;
        double detect_ms = 0.0;
        double draw_ms = 0.0;
        unsigned int object_count = 0;
        if (g_yolo11)
        {
            std::vector<Object> objects;
            const double detect_started_ms = ncnn::get_current_time();
            detect_status = g_yolo11->detect(rgb, objects);
            detect_ms = ncnn::get_current_time() - detect_started_ms;
            if (detect_status != 0)
            {
                draw_status(rgb, "inference failed");
                if (g_metrics.should_log_failure(RuntimeMetrics::DetectFailed, ncnn::get_current_time()))
                    __android_log_print(ANDROID_LOG_ERROR, "YoloPerf", "detect failed backend=%s input=%d status=%d",
                                        backend_name(g_backend), current_target_size(), detect_status);
            }
            else
            {
                object_count = static_cast<unsigned int>(objects.size());
                const double draw_started_ms = ncnn::get_current_time();
                draw_status_code = g_yolo11->draw(rgb, objects);
                draw_ms = ncnn::get_current_time() - draw_started_ms;
                if (draw_status_code != 0)
                {
                    draw_status(rgb, "draw / CSV failed");
                    if (g_metrics.should_log_failure(RuntimeMetrics::DrawFailed, ncnn::get_current_time()))
                        __android_log_print(ANDROID_LOG_ERROR, "YoloPerf", "draw/CSV failed backend=%s input=%d status=%d",
                                            backend_name(g_backend), current_target_size(), draw_status_code);
                }
            }
        }
        else
        {
            draw_status(rgb, "model unavailable");
            if (g_metrics.should_log_failure(RuntimeMetrics::ModelUnavailable, ncnn::get_current_time()))
                __android_log_print(ANDROID_LOG_ERROR, "YoloPerf", "model unavailable backend=%s input=%d",
                                    backend_name(g_backend), current_target_size());
        }
        g_metrics.record(available, detect_status, draw_status_code, detect_ms, draw_ms, object_count);
        RuntimeMetricsWindow window;
        if (g_metrics.take_window(ncnn::get_current_time(), window))
            __android_log_print(ANDROID_LOG_INFO, "YoloPerf",
                                "backend=%s input=%d frames=%u window_ms=%.1f fps=%.2f "
                                "detect_ms_mean=%.2f detect_ms_max=%.2f draw_ms_mean=%.2f draw_ms_max=%.2f "
                                "objects=%u objects_mean=%.2f detect_fail=%u draw_fail=%u unavailable=%u scope=callback",
                                backend_name(g_backend), current_target_size(), window.frames, window.elapsed_ms, window.fps,
                                window.mean_detect_ms(), window.detect_ms_max, window.mean_draw_ms(), window.draw_ms_max,
                                window.objects, window.mean_objects(), window.detect_failures, window.draw_failures,
                                window.unavailable_frames);
        measured = g_metrics.has_display_fps();
        fps = g_metrics.display_fps();
    }
    draw_fps(rgb, measured, fps);
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
    const double started_ms = ncnn::get_current_time();
    const int model_count = sizeof(model_configs) / sizeof(model_configs[0]);
    if (taskid != 0 || modelid < 0 || modelid >= model_count || cpugpu < 0 || cpugpu > 2 || !assetManager)
    {
        log_model_config("invalid_request", cpugpu, 0, false, started_ms);
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
    {
        log_model_config("invalid_assets", cpugpu, model_configs[modelid].target_size, false, started_ms);
        return JNI_FALSE;
    }

    ncnn::MutexLockGuard g(lock);
    g_metrics.reset(ncnn::get_current_time());
    const ModelConfig& config = model_configs[modelid];
    if (g_yolo11 && g_backend == cpugpu && g_modelid >= 0 &&
        std::strcmp(model_configs[g_modelid].param, config.param) == 0)
    {
        // Resolution changes can reuse the same dynamic-shape network weights.
        g_yolo11->set_det_target_size(config.target_size);
        g_modelid = modelid;
        log_model_config("ok", cpugpu, config.target_size, true, started_ms);
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
                log_model_config("gpu_unavailable", cpugpu, config.target_size, false, started_ms);
                return JNI_FALSE;
            }
            g_gpu_initialized = true;
        }
#else
        if (cpugpu != 0)
        {
            g_backend = -1;
            log_model_config("gpu_not_built", cpugpu, config.target_size, false, started_ms);
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
        log_model_config("load_failed", cpugpu, config.target_size, false, started_ms);
        return JNI_FALSE;
    }
    model->set_det_target_size(config.target_size);
    g_yolo11 = model.release();
    g_modelid = modelid;
    __android_log_print(ANDROID_LOG_INFO, "ncnn", "Loaded %s, target=%d, backend=%d", config.param, config.target_size, cpugpu);
    log_model_config("ok", cpugpu, config.target_size, false, started_ms);
    return JNI_TRUE;
}

// public native float[] detectBitmap(Bitmap bitmap);
JNIEXPORT jfloatArray JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_detectBitmap(JNIEnv* env, jobject, jobject bitmap)
{
    if (!bitmap)
        return bitmap_error(env, "java/lang/IllegalArgumentException", "Bitmap must not be null");

    AndroidBitmapInfo info = {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return bitmap_error(env, "java/lang/IllegalArgumentException", "Bitmap information is unavailable; it may have been recycled");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0)
        return bitmap_error(env, "java/lang/IllegalArgumentException", "Bitmap must be a software ARGB_8888 bitmap");
    if (info.width == 0 || info.height == 0 ||
        info.width > static_cast<unsigned int>(std::numeric_limits<int>::max()) ||
        info.height > static_cast<unsigned int>(std::numeric_limits<int>::max()) ||
        static_cast<unsigned long long>(info.stride) < static_cast<unsigned long long>(info.width) * 4)
        return bitmap_error(env, "java/lang/IllegalArgumentException", "Bitmap dimensions or row stride are invalid");

    const unsigned long long rgb_byte_count = static_cast<unsigned long long>(info.width) * info.height * 3;
    if (rgb_byte_count > std::numeric_limits<size_t>::max())
        return bitmap_error(env, "java/lang/IllegalArgumentException", "Bitmap is too large for this process");
    std::unique_ptr<unsigned char[]> rgb_pixels(new (std::nothrow) unsigned char[static_cast<size_t>(rgb_byte_count)]);
    if (!rgb_pixels)
        return bitmap_error(env, "java/lang/OutOfMemoryError", "Not enough memory to convert bitmap pixels");
    {
        ScopedBitmapPixels pixels(env, bitmap);
        if (!pixels.valid())
            return bitmap_error(env, "java/lang/IllegalArgumentException", "Cannot lock bitmap pixels; use a non-recycled software bitmap");
        // Android ARGB_8888 software pixels are exposed by the NDK in RGBA
        // byte order. Honor source row padding and copy to contiguous RGB.
        // This simple copy also works with the project's no-exceptions OpenCV.
        const auto* rgba = static_cast<const unsigned char*>(pixels.pixels());
        for (unsigned int y = 0; y < info.height; ++y)
        {
            const unsigned char* source = rgba + static_cast<size_t>(y) * info.stride;
            unsigned char* destination = rgb_pixels.get() + static_cast<size_t>(y) * info.width * 3;
            for (unsigned int x = 0; x < info.width; ++x)
            {
                destination[0] = source[0];
                destination[1] = source[1];
                destination[2] = source[2];
                source += 4;
                destination += 3;
            }
        }
    } // Release bitmap pixels before potentially long CPU/GPU inference.

    cv::Mat rgb(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC3, rgb_pixels.get());
    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);
        if (!g_yolo11)
            return bitmap_error(env, "java/lang/IllegalStateException", "Load a model before bitmap inference");
        if (g_yolo11->detect(rgb, objects) != 0)
            return bitmap_error(env, "java/lang/IllegalStateException", "Bitmap inference failed");
    }
    // Do not call draw(): video evaluation must not write location/CSV
    // records or change the live camera's FPS and failure-stat windows.
    if (objects.size() > static_cast<size_t>(std::numeric_limits<jsize>::max() / 6))
        return bitmap_error(env, "java/lang/IllegalStateException", "Too many bitmap detections");
    const jsize length = static_cast<jsize>(objects.size() * 6);
    jfloatArray result = env->NewFloatArray(length);
    if (!result || length == 0)
        return result;
    for (size_t i = 0; i < objects.size(); ++i)
    {
        const Object& object = objects[i];
        const jfloat row[6] = {static_cast<jfloat>(object.label), object.prob,
                              object.rect.x, object.rect.y, object.rect.width, object.rect.height};
        env->SetFloatArrayRegion(result, static_cast<jsize>(i * 6), 6, row);
        if (env->ExceptionCheck())
            return nullptr;
    }
    return result;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_Chips_yolo11ncnn_YOLO11Ncnn_openCamera(JNIEnv*, jobject, jint facing)
{
    if (!g_camera || facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);
    {
        ncnn::MutexLockGuard g(lock);
        g_metrics.reset(ncnn::get_current_time());
    }
    // Do not hold the model mutex across open/close: those may join the camera
    // worker, whose final callback needs the same mutex.
    const int status = g_camera->open((int)facing);
    __android_log_print(status == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        "ModelConfig", "camera_open facing=%d status=%d", facing, status);
    return status == 0 ? JNI_TRUE : JNI_FALSE;
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
