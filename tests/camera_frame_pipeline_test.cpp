#include "camera_frame_pipeline.h"

// Three fixed backing allocations model copy/pending/processing storage. Pointer
// swaps exercise the same ownership transitions as production vector::swap.
static unsigned char buffers[3][24];
static int next_buffer = 0;
struct TestFrame
{
    TestFrame() : pixels(buffers[next_buffer++]), width(0), height(0) {}
    void swap(TestFrame& other)
    {
        unsigned char* p = pixels; pixels = other.pixels; other.pixels = p;
        int n = width; width = other.width; other.width = n;
        n = height; height = other.height; other.height = n;
    }
    unsigned char* pixels;
    int width;
    int height;
};

int main()
{
    CameraLatestFrameSlot<TestFrame> slot;
    TestFrame copying;
    TestFrame processing;
    if (slot.accepting() || slot.has_pending() || slot.take(processing) ||
        slot.publish(copying) != CameraLatestFrameSlot<TestFrame>::rejected)
        return 1;

    slot.start();
    copying.pixels[0] = 17; copying.width = 1280; copying.height = 720;
    unsigned char* first_storage = copying.pixels;
    if (slot.publish(copying) != CameraLatestFrameSlot<TestFrame>::queued ||
        copying.pixels == first_storage || !slot.has_pending())
        return 2;
    // The callback can immediately reuse its old storage without corrupting
    // either the pending image or the image taken by the worker.
    copying.pixels[0] = 99;
    if (!slot.take(processing) || processing.pixels != first_storage ||
        processing.pixels[0] != 17 || processing.width != 1280 || processing.height != 720 ||
        slot.has_pending() || slot.take(processing))
        return 3;

    for (int i = 0; i < 10000; ++i)
    {
        copying.pixels[0] = static_cast<unsigned char>(i);
        copying.width = i;
        const auto expected = i == 0 ? CameraLatestFrameSlot<TestFrame>::queued : CameraLatestFrameSlot<TestFrame>::replaced;
        if (slot.publish(copying) != expected || !slot.has_pending() ||
            processing.pixels[0] != 17 || processing.width != 1280)
            return 4;
    }
    // After 10,000 camera frames there is still exactly one waiting frame, the
    // newest, while the in-flight frame retains its own pixels and dimensions.
    if (!slot.take(processing) || processing.width != 9999 ||
        processing.pixels[0] != static_cast<unsigned char>(9999) || slot.take(processing))
        return 5;

    copying.width = 20000;
    slot.publish(copying);
    if (!slot.stop() || slot.accepting() || slot.has_pending() || slot.take(processing))
        return 6;
    unsigned char* rejected_storage = copying.pixels;
    if (slot.publish(copying) != CameraLatestFrameSlot<TestFrame>::rejected || copying.pixels != rejected_storage)
        return 7;
    slot.start();
    if (slot.has_pending() || slot.take(processing))
        return 8;
    for (int generation = 0; generation < 100; ++generation)
    {
        copying.width = generation;
        if (slot.publish(copying) != CameraLatestFrameSlot<TestFrame>::queued ||
            !slot.take(processing) || processing.width != generation || slot.stop())
            return 9;
        slot.start();
    }

    // YUV validation accepts a final row without padding, but rejects missing
    // samples, overlapping rows, invalid dimensions and overflow-sized strides.
    if (!valid_camera_yuv_plane(4, 4, 1, 6, 22) ||
        !valid_camera_yuv_plane(2, 2, 2, 5, 8) ||
        valid_camera_yuv_plane(2, 2, 2, 5, 7) ||
        valid_camera_yuv_plane(4, 4, 2, 6, 24) ||
        valid_camera_yuv_plane(0, 4, 1, 4, 16) ||
        valid_camera_yuv_plane(2147483647, 2147483647, 2147483647, 2147483647, 2147483647))
        return 10;

    unsigned char y[22] = {1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 0, 9, 10, 11, 12, 0, 0, 13, 14, 15, 16};
    unsigned char u[8] = {31, 0, 32, 0, 0, 33, 0, 34};
    unsigned char v[8] = {41, 0, 42, 0, 0, 43, 0, 44};
    const unsigned char* padded_planes[3] = {y, u, v};
    const int padded_pixels[3] = {1, 2, 2};
    const int padded_rows[3] = {6, 5, 5};
    unsigned char output[24];
    copy_camera_yuv420_to_nv21(output, 4, 4, padded_planes, padded_pixels, padded_rows);
    for (int i = 0; i < 16; ++i)
        if (output[i] != i + 1)
            return 11;
    const unsigned char expected_uv[8] = {41, 31, 42, 32, 43, 33, 44, 34};
    for (int i = 0; i < 8; ++i)
        if (output[16 + i] != expected_uv[i])
            return 12;

    // A contiguous NV21 AImage must also be copied before releasing it.
    const unsigned char* contiguous_planes[3] = {output, output + 17, output + 16};
    const int contiguous_pixels[3] = {1, 2, 2};
    const int contiguous_rows[3] = {4, 4, 4};
    copy_camera_yuv420_to_nv21(processing.pixels, 4, 4, contiguous_planes, contiguous_pixels, contiguous_rows);
    for (int i = 0; i < 24; ++i)
    {
        if (processing.pixels[i] != output[i])
            return 13;
        output[i] = 0; // Emulate the camera immediately reusing its allocation.
    }
    if (processing.pixels[0] != 1 || processing.pixels[23] != 34)
        return 14;
    return 0;
}
