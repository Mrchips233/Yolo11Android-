#ifndef CAMERA_FRAME_PIPELINE_H
#define CAMERA_FRAME_PIPELINE_H

// Externally synchronized, single pending frame. Frame::swap transfers ownership
// of app-owned storage; a frame currently used by the worker is never replaced.
// stop() discards pending work, and start() begins an empty camera generation.
template <typename Frame>
class CameraLatestFrameSlot
{
public:
    enum PublishResult { rejected, queued, replaced };

    CameraLatestFrameSlot() : accepting_(false), pending_(false) {}
    void start() { pending_ = false; accepting_ = true; }
    bool stop()
    {
        const bool discarded = pending_;
        accepting_ = false;
        pending_ = false;
        return discarded;
    }
    bool accepting() const { return accepting_; }
    bool has_pending() const { return pending_; }

    PublishResult publish(Frame& frame)
    {
        if (!accepting_)
            return rejected;
        const PublishResult result = pending_ ? replaced : queued;
        frame_.swap(frame);
        pending_ = true;
        return result;
    }
    bool take(Frame& frame)
    {
        if (!accepting_ || !pending_)
            return false;
        frame_.swap(frame);
        pending_ = false;
        return true;
    }

private:
    Frame frame_;
    bool accepting_;
    bool pending_;
};

// The last sample may precede the final row's padding. Validate the addressed
// samples rather than assuming row_stride * height bytes are present.
inline bool valid_camera_yuv_plane(int width, int height, int pixel_stride, int row_stride, int length)
{
    if (width <= 0 || height <= 0 || pixel_stride <= 0 || row_stride <= 0 || length <= 0)
        return false;
    const long long row_bytes = static_cast<long long>(width - 1) * pixel_stride + 1;
    const long long required = static_cast<long long>(height - 1) * row_stride + row_bytes;
    return row_bytes <= row_stride && required <= length;
}

// Caller validates dimensions, all three planes and output capacity first.
// Always copy, including contiguous NV21: no AImage-owned pointer can escape.
inline void copy_camera_yuv420_to_nv21(unsigned char* output, int width, int height,
    const unsigned char* const data[3], const int pixel_stride[3], const int row_stride[3])
{
    for (int y = 0; y < height; ++y)
    {
        const unsigned char* source = data[0] + row_stride[0] * y;
        for (int x = 0; x < width; ++x)
            *output++ = source[x * pixel_stride[0]];
    }
    for (int y = 0; y < height / 2; ++y)
    {
        const unsigned char* vptr = data[2] + row_stride[2] * y;
        const unsigned char* uptr = data[1] + row_stride[1] * y;
        for (int x = 0; x < width / 2; ++x)
        {
            *output++ = vptr[x * pixel_stride[2]];
            *output++ = uptr[x * pixel_stride[1]];
        }
    }
}

#endif // CAMERA_FRAME_PIPELINE_H
