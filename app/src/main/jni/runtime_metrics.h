#ifndef YOLO_RUNTIME_METRICS_H
#define YOLO_RUNTIME_METRICS_H

// No platform dependencies: the caller supplies monotonic milliseconds. All
// access is protected by the model mutex in yolo11ncnn.cpp.
struct RuntimeMetricsWindow
{
    unsigned int frames = 0;
    unsigned int detect_calls = 0;
    unsigned int draw_calls = 0;
    unsigned int detect_failures = 0;
    unsigned int draw_failures = 0;
    unsigned int unavailable_frames = 0;
    unsigned int objects = 0;
    double elapsed_ms = 0.0;
    double fps = 0.0;
    double detect_ms_sum = 0.0;
    double detect_ms_max = 0.0;
    double draw_ms_sum = 0.0;
    double draw_ms_max = 0.0;

    double mean_detect_ms() const { return detect_calls ? detect_ms_sum / detect_calls : 0.0; }
    double mean_draw_ms() const { return draw_calls ? draw_ms_sum / draw_calls : 0.0; }
    double mean_objects() const
    {
        const unsigned int successful_detects = detect_calls - detect_failures;
        return successful_detects ? static_cast<double>(objects) / successful_detects : 0.0;
    }
};

class RuntimeMetrics
{
public:
    enum Failure { ModelUnavailable = 0, DetectFailed = 1, DrawFailed = 2 };

    void reset(double now_ms)
    {
        window_ = RuntimeMetricsWindow();
        window_started_ms_ = now_ms;
        display_fps_ = 0.0;
        has_display_fps_ = false;
        for (int i = 0; i < 3; ++i)
        {
            has_failure_log_[i] = false;
            failure_logged_ms_[i] = 0.0;
        }
    }

    void record(bool model_available, int detect_status, int draw_status,
                double detect_ms, double draw_ms, unsigned int objects)
    {
        ++window_.frames;
        if (!model_available)
        {
            ++window_.unavailable_frames;
            return;
        }
        ++window_.detect_calls;
        window_.detect_ms_sum += detect_ms;
        if (detect_ms > window_.detect_ms_max)
            window_.detect_ms_max = detect_ms;
        if (detect_status != 0)
        {
            ++window_.detect_failures;
            return;
        }
        window_.objects += objects;
        ++window_.draw_calls;
        window_.draw_ms_sum += draw_ms;
        if (draw_ms > window_.draw_ms_max)
            window_.draw_ms_max = draw_ms;
        if (draw_status != 0)
            ++window_.draw_failures;
    }

    bool take_window(double now_ms, RuntimeMetricsWindow& result)
    {
        const double elapsed_ms = now_ms - window_started_ms_;
        if (elapsed_ms < 2000.0 || window_.frames == 0)
            return false;
        result = window_;
        result.elapsed_ms = elapsed_ms;
        result.fps = 1000.0 * result.frames / elapsed_ms;
        display_fps_ = result.fps;
        has_display_fps_ = true;
        window_ = RuntimeMetricsWindow();
        window_started_ms_ = now_ms;
        return true;
    }

    bool should_log_failure(Failure failure, double now_ms)
    {
        const int index = static_cast<int>(failure);
        if (has_failure_log_[index] && now_ms - failure_logged_ms_[index] < 2000.0)
            return false;
        has_failure_log_[index] = true;
        failure_logged_ms_[index] = now_ms;
        return true;
    }

    bool has_display_fps() const { return has_display_fps_; }
    double display_fps() const { return display_fps_; }

private:
    RuntimeMetricsWindow window_;
    double window_started_ms_ = 0.0;
    double display_fps_ = 0.0;
    bool has_display_fps_ = false;
    bool has_failure_log_[3] = {false, false, false};
    double failure_logged_ms_[3] = {0.0, 0.0, 0.0};
};

#endif
