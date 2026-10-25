#include "camera_capture_size.h"

// No host libraries are required, so the NDK's Windows clang can run these
// selection checks without a separately installed C++ development environment.
int main()
{
    CameraCaptureSize selected = {0, 0};
    consider_camera_capture_size(selected, 640, 480);
    consider_camera_capture_size(selected, 1280, 960);
    consider_camera_capture_size(selected, 1920, 1080);
    consider_camera_capture_size(selected, 1280, 720);
    if (selected.width != 1280 || selected.height != 720)
        return 1;

    selected = {0, 0};
    consider_camera_capture_size(selected, 640, 480);
    consider_camera_capture_size(selected, 1024, 768);
    consider_camera_capture_size(selected, 1280, 960);
    if (selected.width != 1280 || selected.height != 960)
        return 2;

    selected = {0, 0};
    consider_camera_capture_size(selected, 800, 600);
    consider_camera_capture_size(selected, 1024, 768);
    consider_camera_capture_size(selected, 960, 720);
    if (selected.width != 1024 || selected.height != 768)
        return 3;

    selected = {0, 0};
    consider_camera_capture_size(selected, 320, 240);
    consider_camera_capture_size(selected, 640, 480);
    consider_camera_capture_size(selected, 1920, 1080);
    if (selected.width != 640 || selected.height != 480)
        return 4;

    selected = {0, 0};
    consider_camera_capture_size(selected, 1920, 1080);
    consider_camera_capture_size(selected, 1600, 900);
    if (selected.width != 1600 || selected.height != 900)
        return 5;

    selected = {0, 0};
    consider_camera_capture_size(selected, 0, 480);
    consider_camera_capture_size(selected, -640, 480);
    consider_camera_capture_size(selected, 1279, 720);
    consider_camera_capture_size(selected, 4000, 3000);
    if (selected.width != 0 || selected.height != 0)
        return 6;

    consider_camera_capture_size(selected, 720, 1280);
    if (selected.width != 720 || selected.height != 1280)
        return 7;
    // Enumeration order must not make a lower-priority size replace 720p.
    consider_camera_capture_size(selected, 1280, 960);
    consider_camera_capture_size(selected, 960, 720);
    if (selected.width != 720 || selected.height != 1280)
        return 8;
    return 0;
}
