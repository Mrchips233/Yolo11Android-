// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
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

#include "ndkcamera.h"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include <android/log.h>

#include <opencv2/core/core.hpp>

#include "mat.h"

static void onDisconnected(void*, ACameraDevice* device)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onDisconnected %p", device);
}

static void onError(void*, ACameraDevice* device, int error)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onError %p %d", device, error);
}

// The final sample can end before the row padding of its plane. Validate the
// addressed samples rather than assuming rowStride * height bytes are present.
static bool valid_plane(int width, int height, int pixel_stride, int row_stride, int length)
{
    if (width <= 0 || height <= 0 || pixel_stride <= 0 || row_stride <= 0 || length <= 0)
        return false;

    const int64_t row_bytes = static_cast<int64_t>(width - 1) * pixel_stride + 1;
    const int64_t required = static_cast<int64_t>(height - 1) * row_stride + row_bytes;
    return row_bytes <= row_stride && required <= length;
}

void NdkCamera::onImageAvailable(void* context, AImageReader* reader)
{
    NdkCamera* camera = static_cast<NdkCamera*>(context);
    std::lock_guard<std::mutex> lock(camera->image_mutex);
    if (!camera->accepting_images)
        return;

    AImage* acquired_image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &acquired_image) != AMEDIA_OK || !acquired_image)
        return;
    std::unique_ptr<AImage, decltype(&AImage_delete)> image(acquired_image, AImage_delete);

    int32_t format = 0;
    int32_t width = 0;
    int32_t height = 0;
    if (AImage_getFormat(image.get(), &format) != AMEDIA_OK || format != AIMAGE_FORMAT_YUV_420_888 ||
        AImage_getWidth(image.get(), &width) != AMEDIA_OK ||
        AImage_getHeight(image.get(), &height) != AMEDIA_OK ||
        width <= 0 || height <= 0 || width % 2 || height % 2)
        return;

    // ncnn's YUV helpers use signed integer dimensions and offsets.
    const int64_t pixel_count = static_cast<int64_t>(width) * height;
    if (pixel_count > std::numeric_limits<int>::max() / 3 * 2)
        return;

    int32_t pixel_stride[3] = {};
    int32_t row_stride[3] = {};
    uint8_t* data[3] = {};
    int length[3] = {};
    for (int plane = 0; plane < 3; plane++)
    {
        const int plane_width = plane == 0 ? width : width / 2;
        const int plane_height = plane == 0 ? height : height / 2;
        if (AImage_getPlanePixelStride(image.get(), plane, &pixel_stride[plane]) != AMEDIA_OK ||
            AImage_getPlaneRowStride(image.get(), plane, &row_stride[plane]) != AMEDIA_OK ||
            AImage_getPlaneData(image.get(), plane, &data[plane], &length[plane]) != AMEDIA_OK ||
            !data[plane] || !valid_plane(plane_width, plane_height, pixel_stride[plane], row_stride[plane], length[plane]))
            return;
    }

    if (reinterpret_cast<uintptr_t>(data[1]) == reinterpret_cast<uintptr_t>(data[2]) + 1 &&
        reinterpret_cast<uintptr_t>(data[2]) == reinterpret_cast<uintptr_t>(data[0]) + pixel_count &&
        pixel_stride[0] == 1 && pixel_stride[1] == 2 && pixel_stride[2] == 2 &&
        row_stride[0] == width && row_stride[1] == width && row_stride[2] == width)
    {
        camera->on_image(data[0], width, height);
        return;
    }

    // Reuse the conversion storage across frames instead of allocating each time.
    std::vector<unsigned char>& nv21 = camera->nv21_buffer;
    nv21.resize(static_cast<size_t>(pixel_count + pixel_count / 2));
    unsigned char* yptr = nv21.data();
    for (int y = 0; y < height; y++)
    {
        const unsigned char* source = data[0] + row_stride[0] * y;
        for (int x = 0; x < width; x++)
            *yptr++ = source[x * pixel_stride[0]];
    }

    unsigned char* uvptr = nv21.data() + pixel_count;
    for (int y = 0; y < height / 2; y++)
    {
        const unsigned char* vptr = data[2] + row_stride[2] * y;
        const unsigned char* uptr = data[1] + row_stride[1] * y;
        for (int x = 0; x < width / 2; x++)
        {
            *uvptr++ = vptr[x * pixel_stride[2]];
            *uvptr++ = uptr[x * pixel_stride[1]];
        }
    }
    camera->on_image(nv21.data(), width, height);
}

static void onSessionActive(void*, ACameraCaptureSession *session)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onSessionActive %p", session);
}

static void onSessionReady(void*, ACameraCaptureSession *session)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onSessionReady %p", session);
}

static void onSessionClosed(void*, ACameraCaptureSession *session)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onSessionClosed %p", session);
}

static void onCaptureFailed(void*, ACameraCaptureSession* session, ACaptureRequest* request, ACameraCaptureFailure* failure)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onCaptureFailed %p %p %p", session, request, failure);
}

static void onCaptureSequenceCompleted(void*, ACameraCaptureSession* session, int sequenceId, int64_t frameNumber)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onCaptureSequenceCompleted %p %d %lld", session, sequenceId, static_cast<long long>(frameNumber));
}

static void onCaptureSequenceAborted(void*, ACameraCaptureSession* session, int sequenceId)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onCaptureSequenceAborted %p %d", session, sequenceId);
}

NdkCamera::NdkCamera()
{
    camera_facing = 0;
    camera_orientation = 0;
    accepting_images = false;

    camera_manager = nullptr;
    camera_device = nullptr;
    image_reader = nullptr;
    image_reader_surface = nullptr;
    image_reader_target = nullptr;
    capture_request = nullptr;
    capture_session_output_container = nullptr;
    capture_session_output = nullptr;
    capture_session = nullptr;
}

NdkCamera::~NdkCamera()
{
    close();
}

int NdkCamera::open(int _camera_facing)
{
    std::lock_guard<std::mutex> lock(camera_mutex);
    close_locked();
    if (_camera_facing < 0 || _camera_facing > 1)
        return -1;

    camera_facing = _camera_facing;
    camera_orientation = 0;
    camera_manager = ACameraManager_create();
    if (!camera_manager)
        return -1;

    // Every failure releases the partially constructed capture pipeline.
    const auto fail = [this]() { close_locked(); return -1; };
    ACameraIdList* camera_id_list = nullptr;
    if (ACameraManager_getCameraIdList(camera_manager, &camera_id_list) != ACAMERA_OK || !camera_id_list)
    {
        if (camera_id_list)
            ACameraManager_deleteCameraIdList(camera_id_list);
        return fail();
    }

    std::string camera_id;
    for (int i = 0; camera_id_list->cameraIds && i < camera_id_list->numCameras; i++)
    {
        const char* id = camera_id_list->cameraIds[i];
        ACameraMetadata* metadata = nullptr;
        if (!id || ACameraManager_getCameraCharacteristics(camera_manager, id, &metadata) != ACAMERA_OK || !metadata)
        {
            if (metadata)
                ACameraMetadata_free(metadata);
            continue;
        }

        ACameraMetadata_const_entry facing = {};
        ACameraMetadata_const_entry orientation = {};
        const bool valid = ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &facing) == ACAMERA_OK &&
            facing.count > 0 && facing.data.u8 &&
            ACameraMetadata_getConstEntry(metadata, ACAMERA_SENSOR_ORIENTATION, &orientation) == ACAMERA_OK &&
            orientation.count > 0 && orientation.data.i32;
        const int requested_facing = camera_facing == 0 ? ACAMERA_LENS_FACING_FRONT : ACAMERA_LENS_FACING_BACK;
        if (valid && facing.data.u8[0] == requested_facing &&
            (orientation.data.i32[0] == 0 || orientation.data.i32[0] == 90 ||
             orientation.data.i32[0] == 180 || orientation.data.i32[0] == 270))
        {
            camera_id = id;
            camera_orientation = orientation.data.i32[0];
        }
        ACameraMetadata_free(metadata);
        if (!camera_id.empty())
            break;
    }
    ACameraManager_deleteCameraIdList(camera_id_list);
    if (camera_id.empty())
        return fail();

    if (AImageReader_new(640, 480, AIMAGE_FORMAT_YUV_420_888, 2, &image_reader) != AMEDIA_OK || !image_reader ||
        AImageReader_getWindow(image_reader, &image_reader_surface) != AMEDIA_OK || !image_reader_surface)
        return fail();
    // The image reader owns its surface. No additional window reference is needed.
    AImageReader_ImageListener listener = {};
    listener.context = this;
    listener.onImageAvailable = onImageAvailable;
    if (AImageReader_setImageListener(image_reader, &listener) != AMEDIA_OK)
        return fail();

    ACameraDevice_StateCallbacks device_callbacks = {};
    device_callbacks.context = this;
    device_callbacks.onDisconnected = onDisconnected;
    device_callbacks.onError = onError;
    if (ACameraManager_openCamera(camera_manager, camera_id.c_str(), &device_callbacks, &camera_device) != ACAMERA_OK || !camera_device)
        return fail();

    if (ACameraDevice_createCaptureRequest(camera_device, TEMPLATE_PREVIEW, &capture_request) != ACAMERA_OK || !capture_request ||
        ACameraOutputTarget_create(image_reader_surface, &image_reader_target) != ACAMERA_OK || !image_reader_target ||
        ACaptureRequest_addTarget(capture_request, image_reader_target) != ACAMERA_OK ||
        ACaptureSessionOutputContainer_create(&capture_session_output_container) != ACAMERA_OK || !capture_session_output_container ||
        ACaptureSessionOutput_create(image_reader_surface, &capture_session_output) != ACAMERA_OK || !capture_session_output ||
        ACaptureSessionOutputContainer_add(capture_session_output_container, capture_session_output) != ACAMERA_OK)
        return fail();

    ACameraCaptureSession_stateCallbacks session_callbacks = {};
    session_callbacks.context = this;
    session_callbacks.onActive = onSessionActive;
    session_callbacks.onReady = onSessionReady;
    session_callbacks.onClosed = onSessionClosed;
    if (ACameraDevice_createCaptureSession(camera_device, capture_session_output_container, &session_callbacks, &capture_session) != ACAMERA_OK || !capture_session)
        return fail();

    ACameraCaptureSession_captureCallbacks capture_callbacks = {};
    capture_callbacks.context = this;
    capture_callbacks.onCaptureFailed = onCaptureFailed;
    capture_callbacks.onCaptureSequenceCompleted = onCaptureSequenceCompleted;
    capture_callbacks.onCaptureSequenceAborted = onCaptureSequenceAborted;
    {
        std::lock_guard<std::mutex> image_lock(image_mutex);
        accepting_images = true;
    }
    if (ACameraCaptureSession_setRepeatingRequest(capture_session, &capture_callbacks, 1, &capture_request, nullptr) != ACAMERA_OK)
        return fail();

    return 0;
}

void NdkCamera::close()
{
    std::lock_guard<std::mutex> lock(camera_mutex);
    close_locked();
}

void NdkCamera::close_locked()
{
    if (image_reader)
        AImageReader_setImageListener(image_reader, nullptr);
    {
        // Wait for a frame already being processed before releasing its buffers.
        std::lock_guard<std::mutex> lock(image_mutex);
        accepting_images = false;
    }

    if (capture_session)
    {
        ACameraCaptureSession_stopRepeating(capture_session);
        ACameraCaptureSession_close(capture_session);
        capture_session = nullptr;
    }
    if (camera_device)
    {
        ACameraDevice_close(camera_device);
        camera_device = nullptr;
    }
    if (capture_request)
    {
        ACaptureRequest_free(capture_request);
        capture_request = nullptr;
    }
    if (image_reader_target)
    {
        ACameraOutputTarget_free(image_reader_target);
        image_reader_target = nullptr;
    }
    if (capture_session_output_container)
    {
        ACaptureSessionOutputContainer_free(capture_session_output_container);
        capture_session_output_container = nullptr;
    }
    if (capture_session_output)
    {
        ACaptureSessionOutput_free(capture_session_output);
        capture_session_output = nullptr;
    }
    if (image_reader)
    {
        AImageReader_delete(image_reader);
        image_reader = nullptr;
        image_reader_surface = nullptr;
    }
    if (camera_manager)
    {
        ACameraManager_delete(camera_manager);
        camera_manager = nullptr;
    }
}

static const int NDKCAMERAWINDOW_ID = 233;

NdkCameraWindow::NdkCameraWindow() : NdkCamera()
{
    sensor_manager = 0;
    sensor_event_queue = 0;
    sensor_looper = 0;
    accelerometer_sensor = 0;
    win = 0;

    accelerometer_orientation = 0;

    // sensor
    sensor_manager = ASensorManager_getInstance();

    if (sensor_manager)
        accelerometer_sensor = ASensorManager_getDefaultSensor(sensor_manager, ASENSOR_TYPE_ACCELEROMETER);
}

NdkCameraWindow::~NdkCameraWindow()
{
    close();
    set_window(nullptr);
}

void NdkCameraWindow::close()
{
    // Stop and join image processing before touching its sensor queue.
    NdkCamera::close();
    std::lock_guard<std::mutex> lock(window_mutex);
    if (sensor_event_queue)
    {
        if (accelerometer_sensor)
            ASensorEventQueue_disableSensor(sensor_event_queue, accelerometer_sensor);
        ASensorManager_destroyEventQueue(sensor_manager, sensor_event_queue);
        sensor_event_queue = nullptr;
    }
    if (sensor_looper)
    {
        ALooper_release(sensor_looper);
        sensor_looper = nullptr;
    }
}

void NdkCameraWindow::set_window(ANativeWindow* _win)
{
    std::lock_guard<std::mutex> lock(window_mutex);
    if (_win)
        ANativeWindow_acquire(_win);
    if (win)
    {
        ANativeWindow_release(win);
    }

    win = _win;
}

void NdkCameraWindow::on_image(const unsigned char* nv21, int nv21_width, int nv21_height) const
{
    std::lock_guard<std::mutex> lock(window_mutex);
    if (!win || !nv21 || nv21_width <= 0 || nv21_height <= 0)
        return;

    // resolve orientation from camera_orientation and accelerometer_sensor
    if (sensor_manager && accelerometer_sensor)
    {
        ALooper* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
        if (sensor_event_queue && sensor_looper != looper)
        {
            ASensorEventQueue_disableSensor(sensor_event_queue, accelerometer_sensor);
            ASensorManager_destroyEventQueue(sensor_manager, sensor_event_queue);
            sensor_event_queue = nullptr;
            ALooper_release(sensor_looper);
            sensor_looper = nullptr;
        }
        if (!sensor_event_queue)
        {
            if (looper)
                sensor_event_queue = ASensorManager_createEventQueue(sensor_manager, looper, NDKCAMERAWINDOW_ID, 0, 0);

            if (sensor_event_queue)
            {
                sensor_looper = looper;
                ALooper_acquire(sensor_looper);
                if (ASensorEventQueue_enableSensor(sensor_event_queue, accelerometer_sensor) < 0)
                {
                    ASensorManager_destroyEventQueue(sensor_manager, sensor_event_queue);
                    sensor_event_queue = nullptr;
                    ALooper_release(sensor_looper);
                    sensor_looper = nullptr;
                }
                else
                {
                    ASensorEventQueue_setEventRate(sensor_event_queue, accelerometer_sensor, 100000);
                }
            }
        }

        int id = sensor_event_queue ? ALooper_pollOnce(0, 0, 0, 0) : -1;
        if (id == NDKCAMERAWINDOW_ID)
        {
            ASensorEvent e[8];
            ssize_t num_event = 0;
            while (ASensorEventQueue_hasEvents(sensor_event_queue) == 1)
            {
                const ssize_t count = ASensorEventQueue_getEvents(sensor_event_queue, e, 8);
                if (count <= 0)
                    break;
                num_event = count;
            }

            if (num_event > 0)
            {
                float acceleration_x = e[num_event - 1].acceleration.x;
                float acceleration_y = e[num_event - 1].acceleration.y;

                if (acceleration_y > 7)
                {
                    accelerometer_orientation = 0;
                }
                if (acceleration_x < -7)
                {
                    accelerometer_orientation = 90;
                }
                if (acceleration_y < -7)
                {
                    accelerometer_orientation = 180;
                }
                if (acceleration_x > 7)
                {
                    accelerometer_orientation = 270;
                }
            }
        }
    }

    // roi crop and rotate nv21
    int nv21_roi_x = 0;
    int nv21_roi_y = 0;
    int nv21_roi_w = 0;
    int nv21_roi_h = 0;
    int roi_x = 0;
    int roi_y = 0;
    int roi_w = 0;
    int roi_h = 0;
    int rotate_type = 0;
    int render_w = 0;
    int render_h = 0;
    int render_rotate_type = 0;
    {
        int win_w = ANativeWindow_getWidth(win);
        int win_h = ANativeWindow_getHeight(win);
        if (win_w <= 0 || win_h <= 0)
            return;

        if (accelerometer_orientation == 90 || accelerometer_orientation == 270)
        {
            std::swap(win_w, win_h);
        }

        const int final_orientation = (camera_orientation + accelerometer_orientation) % 360;

        if (final_orientation == 0 || final_orientation == 180)
        {
            if (static_cast<int64_t>(win_w) * nv21_height > static_cast<int64_t>(win_h) * nv21_width)
            {
                roi_w = nv21_width;
                roi_h = static_cast<int>(static_cast<int64_t>(nv21_width) * win_h / win_w) / 2 * 2;
                roi_x = 0;
                roi_y = ((nv21_height - roi_h) / 2) / 2 * 2;
            }
            else
            {
                roi_h = nv21_height;
                roi_w = static_cast<int>(static_cast<int64_t>(nv21_height) * win_w / win_h) / 2 * 2;
                roi_x = ((nv21_width - roi_w) / 2) / 2 * 2;
                roi_y = 0;
            }

            nv21_roi_x = roi_x;
            nv21_roi_y = roi_y;
            nv21_roi_w = roi_w;
            nv21_roi_h = roi_h;
        }
        if (final_orientation == 90 || final_orientation == 270)
        {
            if (static_cast<int64_t>(win_w) * nv21_width > static_cast<int64_t>(win_h) * nv21_height)
            {
                roi_w = nv21_height;
                roi_h = static_cast<int>(static_cast<int64_t>(nv21_height) * win_h / win_w) / 2 * 2;
                roi_x = 0;
                roi_y = ((nv21_width - roi_h) / 2) / 2 * 2;
            }
            else
            {
                roi_h = nv21_width;
                roi_w = static_cast<int>(static_cast<int64_t>(nv21_width) * win_w / win_h) / 2 * 2;
                roi_x = ((nv21_height - roi_w) / 2) / 2 * 2;
                roi_y = 0;
            }

            nv21_roi_x = roi_y;
            nv21_roi_y = roi_x;
            nv21_roi_w = roi_h;
            nv21_roi_h = roi_w;
        }

        if (camera_facing == 0)
        {
            if (camera_orientation == 0 && accelerometer_orientation == 0)
            {
                rotate_type = 2;
            }
            if (camera_orientation == 0 && accelerometer_orientation == 90)
            {
                rotate_type = 7;
            }
            if (camera_orientation == 0 && accelerometer_orientation == 180)
            {
                rotate_type = 4;
            }
            if (camera_orientation == 0 && accelerometer_orientation == 270)
            {
                rotate_type = 5;
            }
            if (camera_orientation == 90 && accelerometer_orientation == 0)
            {
                rotate_type = 5;
            }
            if (camera_orientation == 90 && accelerometer_orientation == 90)
            {
                rotate_type = 2;
            }
            if (camera_orientation == 90 && accelerometer_orientation == 180)
            {
                rotate_type = 7;
            }
            if (camera_orientation == 90 && accelerometer_orientation == 270)
            {
                rotate_type = 4;
            }
            if (camera_orientation == 180 && accelerometer_orientation == 0)
            {
                rotate_type = 4;
            }
            if (camera_orientation == 180 && accelerometer_orientation == 90)
            {
                rotate_type = 5;
            }
            if (camera_orientation == 180 && accelerometer_orientation == 180)
            {
                rotate_type = 2;
            }
            if (camera_orientation == 180 && accelerometer_orientation == 270)
            {
                rotate_type = 7;
            }
            if (camera_orientation == 270 && accelerometer_orientation == 0)
            {
                rotate_type = 7;
            }
            if (camera_orientation == 270 && accelerometer_orientation == 90)
            {
                rotate_type = 4;
            }
            if (camera_orientation == 270 && accelerometer_orientation == 180)
            {
                rotate_type = 5;
            }
            if (camera_orientation == 270 && accelerometer_orientation == 270)
            {
                rotate_type = 2;
            }
        }
        else
        {
            if (final_orientation == 0)
            {
                rotate_type = 1;
            }
            if (final_orientation == 90)
            {
                rotate_type = 6;
            }
            if (final_orientation == 180)
            {
                rotate_type = 3;
            }
            if (final_orientation == 270)
            {
                rotate_type = 8;
            }
        }

        if (accelerometer_orientation == 0)
        {
            render_w = roi_w;
            render_h = roi_h;
            render_rotate_type = 1;
        }
        if (accelerometer_orientation == 90)
        {
            render_w = roi_h;
            render_h = roi_w;
            render_rotate_type = 8;
        }
        if (accelerometer_orientation == 180)
        {
            render_w = roi_w;
            render_h = roi_h;
            render_rotate_type = 3;
        }
        if (accelerometer_orientation == 270)
        {
            render_w = roi_h;
            render_h = roi_w;
            render_rotate_type = 6;
        }
    }

    if (roi_w <= 0 || roi_h <= 0 || render_w <= 0 || render_h <= 0 ||
        !rotate_type || !render_rotate_type || nv21_roi_x < 0 || nv21_roi_y < 0 ||
        nv21_roi_x + nv21_roi_w > nv21_width || nv21_roi_y + nv21_roi_h > nv21_height)
        return;

    // crop and rotate nv21
    cv::Mat nv21_croprotated(roi_h + roi_h / 2, roi_w, CV_8UC1);
    {
        const unsigned char* srcY = nv21 + nv21_roi_y * nv21_width + nv21_roi_x;
        unsigned char* dstY = nv21_croprotated.data;
        ncnn::kanna_rotate_c1(srcY, nv21_roi_w, nv21_roi_h, nv21_width, dstY, roi_w, roi_h, roi_w, rotate_type);

        const unsigned char* srcUV = nv21 + nv21_width * nv21_height + nv21_roi_y * nv21_width / 2 + nv21_roi_x;
        unsigned char* dstUV = nv21_croprotated.data + roi_w * roi_h;
        ncnn::kanna_rotate_c2(srcUV, nv21_roi_w / 2, nv21_roi_h / 2, nv21_width, dstUV, roi_w / 2, roi_h / 2, roi_w, rotate_type);
    }

    // nv21_croprotated to rgb
    cv::Mat rgb(roi_h, roi_w, CV_8UC3);
    ncnn::yuv420sp2rgb(nv21_croprotated.data, roi_w, roi_h, rgb.data);

    on_image_render(rgb);

    // rotate to native window orientation
    cv::Mat rgb_render(render_h, render_w, CV_8UC3);
    ncnn::kanna_rotate_c3(rgb.data, roi_w, roi_h, rgb_render.data, render_w, render_h, render_rotate_type);

    if (ANativeWindow_setBuffersGeometry(win, render_w, render_h, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) != 0)
        return;

    ANativeWindow_Buffer buf = {};
    if (ANativeWindow_lock(win, &buf, nullptr) != 0)
        return;
    if (!buf.bits || buf.width < render_w || buf.height < render_h || buf.stride < render_w)
    {
        ANativeWindow_unlockAndPost(win);
        return;
    }

    // scale to target size
    if (buf.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || buf.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM)
    {
        for (int y = 0; y < render_h; y++)
        {
            const unsigned char* ptr = rgb_render.ptr<const unsigned char>(y);
            unsigned char* outptr = (unsigned char*)buf.bits + buf.stride * 4 * y;

            int x = 0;
#if __ARM_NEON
            for (; x + 7 < render_w; x += 8)
            {
                uint8x8x3_t _rgb = vld3_u8(ptr);
                uint8x8x4_t _rgba;
                _rgba.val[0] = _rgb.val[0];
                _rgba.val[1] = _rgb.val[1];
                _rgba.val[2] = _rgb.val[2];
                _rgba.val[3] = vdup_n_u8(255);
                vst4_u8(outptr, _rgba);

                ptr += 24;
                outptr += 32;
            }
#endif // __ARM_NEON
            for (; x < render_w; x++)
            {
                outptr[0] = ptr[0];
                outptr[1] = ptr[1];
                outptr[2] = ptr[2];
                outptr[3] = 255;

                ptr += 3;
                outptr += 4;
            }
        }
    }

    ANativeWindow_unlockAndPost(win);
}
