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

#ifndef NDKCAMERA_H
#define NDKCAMERA_H

#include <android/looper.h>
#include <android/native_window.h>
#include <android/sensor.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>

#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

#include "camera_frame_pipeline.h"

#include <opencv2/core/core.hpp>

struct CameraStatistics;

class NdkCamera
{
public:
    NdkCamera();
    virtual ~NdkCamera();

    // facing 0=front 1=back. open/close are blocking lifecycle operations:
    // call from the serialized control thread, never from an image callback or
    // frame worker, and never while holding model/render locks used by on_image.
    // close stops admission, drops the one pending frame, then joins any render.
    int open(int camera_facing = 0);
    virtual void close();

    virtual void on_image(const unsigned char* nv21, int nv21_width, int nv21_height) const = 0;

protected:
    int camera_facing;
    int camera_orientation;

private:
    static void onImageAvailable(void* context, AImageReader* reader);
    void process_images();
    void close_locked();

    struct OwnedFrame
    {
        OwnedFrame() : width(0), height(0) {}
        void swap(OwnedFrame& other)
        {
            pixels.swap(other.pixels);
            std::swap(width, other.width);
            std::swap(height, other.height);
        }
        std::vector<unsigned char> pixels;
        int width;
        int height;
    };

    std::mutex camera_mutex;
    std::mutex image_mutex;
    std::condition_variable frame_ready;
    std::thread frame_worker;
    CameraLatestFrameSlot<OwnedFrame> latest_frame;
    OwnedFrame copy_frame;
    std::shared_ptr<CameraStatistics> statistics;
    ACameraManager* camera_manager;
    ACameraDevice* camera_device;
    AImageReader* image_reader;
    ANativeWindow* image_reader_surface;
    ACameraOutputTarget* image_reader_target;
    ACaptureRequest* capture_request;
    ACaptureSessionOutputContainer* capture_session_output_container;
    ACaptureSessionOutput* capture_session_output;
    ACameraCaptureSession* capture_session;
};

class NdkCameraWindow : public NdkCamera
{
public:
    NdkCameraWindow();
    ~NdkCameraWindow() override;

    void close() override;
    void set_window(ANativeWindow* win);

    virtual void on_image_render(cv::Mat& rgb) const = 0;

    void on_image(const unsigned char* nv21, int nv21_width, int nv21_height) const override;

private:
    mutable int accelerometer_orientation;
    mutable std::mutex window_mutex;
    ASensorManager* sensor_manager;
    mutable ASensorEventQueue* sensor_event_queue;
    mutable ALooper* sensor_looper;
    const ASensor* accelerometer_sensor;
    ANativeWindow* win;
};

#endif // NDKCAMERA_H
