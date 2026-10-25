#include "runtime_metrics.h"

#if defined(_WIN32)
// Required by the NDK's MSVC-targeting clang for floating-point arithmetic;
// these host-only checks otherwise have no C/C++ runtime dependencies.
extern "C" { int _fltused = 0; }
#endif

static bool near(double a, double b)
{
    const double difference = a > b ? a - b : b - a;
    return difference < 0.00001;
}

int main()
{
    RuntimeMetrics metrics;
    RuntimeMetricsWindow result;
    metrics.reset(100.0);
    if (metrics.has_display_fps() || metrics.take_window(2100.0, result))
        return 1;

    // Uneven callback intervals must yield completed count / wall time, not
    // the inflated arithmetic mean of 1 / each individual callback interval.
    metrics.record(true, 0, 0, 40.0, 3.0, 2);
    metrics.record(true, 0, 0, 80.0, 5.0, 4);
    if (metrics.take_window(2099.0, result))
        return 2;
    if (!metrics.take_window(2100.0, result) || result.frames != 2 || !near(result.fps, 1.0) ||
        !near(result.mean_detect_ms(), 60.0) || !near(result.detect_ms_max, 80.0) ||
        !near(result.mean_draw_ms(), 4.0) || !near(result.draw_ms_max, 5.0) ||
        result.objects != 6 || !near(result.mean_objects(), 3.0))
        return 3;
    if (!metrics.has_display_fps() || !near(metrics.display_fps(), result.fps))
        return 4;

    // Failed detect must not manufacture a draw call or count unusable
    // detections; missing models must not enter detect-time denominators.
    metrics.record(true, -1, 0, 100.0, 0.0, 99);
    metrics.record(true, 0, -1, 20.0, 7.0, 1);
    metrics.record(false, 0, 0, 0.0, 0.0, 0);
    if (!metrics.take_window(4100.0, result) || result.frames != 3 || !near(result.fps, 1.5) ||
        result.detect_calls != 2 || result.draw_calls != 1 || result.detect_failures != 1 ||
        result.draw_failures != 1 || result.unavailable_frames != 1 || result.objects != 1 ||
        !near(result.mean_objects(), 1.0) || !near(result.mean_detect_ms(), 60.0) ||
        !near(result.mean_draw_ms(), 7.0))
        return 5;

    if (!metrics.should_log_failure(RuntimeMetrics::DetectFailed, 5000.0) ||
        metrics.should_log_failure(RuntimeMetrics::DetectFailed, 6999.0) ||
        !metrics.should_log_failure(RuntimeMetrics::DetectFailed, 7000.0) ||
        !metrics.should_log_failure(RuntimeMetrics::DrawFailed, 5000.0) ||
        !metrics.should_log_failure(RuntimeMetrics::ModelUnavailable, 5000.0))
        return 6;

    // A backend/resolution/camera restart clears counters, old displayed FPS,
    // and failure throttling, including the first-error immediate log.
    metrics.reset(10000.0);
    if (metrics.has_display_fps() || !near(metrics.display_fps(), 0.0) ||
        !metrics.should_log_failure(RuntimeMetrics::DetectFailed, 10000.0))
        return 7;
    metrics.record(true, 0, 0, 12.0, 2.0, 0);
    if (!metrics.take_window(12000.0, result) || result.frames != 1 || !near(result.fps, 0.5) ||
        result.detect_failures != 0 || result.draw_failures != 0 || result.unavailable_frames != 0 ||
        !near(result.mean_detect_ms(), 12.0) || result.objects != 0)
        return 8;

    // Even long idle time cannot cause a zero-frame report or a divide by zero.
    if (metrics.take_window(20000.0, result))
        return 9;
    return 0;
}
