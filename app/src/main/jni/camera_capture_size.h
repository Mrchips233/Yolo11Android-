#ifndef CAMERA_CAPTURE_SIZE_H
#define CAMERA_CAPTURE_SIZE_H

// Keep capture near 720p/960p so a 960-input detector receives real detail,
// without unconditionally allocating and converting full-resolution frames.
// This helper only ranks sizes; callers must pass advertised YUV output sizes.
struct CameraCaptureSize
{
    int width;
    int height;
};

inline int camera_capture_size_priority(int width, int height)
{
    if (width <= 0 || height <= 0 || width % 2 || height % 2)
        return 0;

    const int longer = width > height ? width : height;
    const int shorter = width > height ? height : width;
    // Do not fall back to multi-megapixel captures if normal preview sizes
    // are absent: their conversion/render cost can overwhelm mobile devices.
    if (longer > 1920 || shorter > 1080)
        return 0;
    if (longer == 1280 && shorter == 720)
        return 5;
    if (longer == 1280 && shorter == 960)
        return 4;
    if (longer >= 960 && longer <= 1280 && shorter * 16 >= longer * 9 && shorter * 4 <= longer * 3)
        return 3;
    if (longer <= 1280 && shorter <= 960)
        return 2;
    return 1;
}

inline void consider_camera_capture_size(CameraCaptureSize& best, int width, int height)
{
    const int priority = camera_capture_size_priority(width, height);
    const int best_priority = camera_capture_size_priority(best.width, best.height);
    const int pixels = priority ? width * height : 0;
    const int best_pixels = best_priority ? best.width * best.height : 0;
    // Prefer detail inside the preview budget. If only larger (up to 1080p)
    // outputs exist, select the smallest one to limit latency and memory.
    if (priority > best_priority || (priority && priority == best_priority &&
        (priority == 1 ? pixels < best_pixels : pixels > best_pixels)))
    {
        best.width = width;
        best.height = height;
    }
}

#endif // CAMERA_CAPTURE_SIZE_H
