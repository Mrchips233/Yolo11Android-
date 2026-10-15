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

// The dynamically shaped output has one row per stride-8/16/32 grid cell.
// Each row contains four 16-bin box distributions and the ten trained classes.

#include "yolo11.h"
#include "detection_output.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <algorithm>
#include <cfloat>
#include <cmath>
#include <memory>
#include <string>
#include <android/log.h>
#include <jni.h>

#define LOG_TAG "YOLO11"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static inline float intersection_area(const Object& a, const Object& b)
{
    cv::Rect_<float> inter = a.rect & b.rect;
    return inter.area();
}

static const char* const class_names[] = {
    "W", "P", "S", "I", "A", "WD", "PD", "SD", "ID", "AD"
};
static const int class_count = sizeof(class_names) / sizeof(class_names[0]);

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold)
{
    picked.clear();

    const int n = objects.size();

    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
    {
        areas[i] = objects[i].rect.area();
    }

    for (int i = 0; i < n; i++)
    {
        const Object& a = objects[i];

        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++)
        {
            const Object& b = objects[picked[j]];

            if (a.label != b.label)
                continue;

            // intersection over union
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            // float IoU = inter_area / union_area
            if (union_area > 0.f && inter_area / union_area > nms_threshold)
            {
                keep = 0;
                break;
            }
        }

        if (keep)
            picked.push_back(i);
    }
}

static inline float sigmoid(float x)
{
    return 1.0f / (1.0f + expf(-x));
}

static int generate_proposals(const ncnn::Mat& pred, int stride, const ncnn::Mat& in_pad,
                              float prob_threshold, std::vector<Object>& objects,
                              ncnn::Layer& softmax, const ncnn::Option& opt)
{
    const int w = in_pad.w;
    const int h = in_pad.h;

    const int num_grid_x = w / stride;
    const int num_grid_y = h / stride;

    const int reg_max_1 = 16;
    const int num_class = class_count;

    for (int y = 0; y < num_grid_y; y++)
    {
        for (int x = 0; x < num_grid_x; x++)
        {
            const ncnn::Mat pred_grid = pred.row_range(y * num_grid_x + x, 1);

            // find label with max score
            int label = -1;
            float score = -FLT_MAX;
            {
                const ncnn::Mat pred_score = pred_grid.range(reg_max_1 * 4, num_class);

                for (int k = 0; k < num_class; k++)
                {
                    float s = pred_score[k];
                    if (!std::isfinite(s))
                        return -1;
                    if (s > score)
                    {
                        label = k;
                        score = s;
                    }
                }

                score = sigmoid(score);
            }

            if (score >= prob_threshold)
            {
                ncnn::Mat pred_bbox = pred_grid.range(0, reg_max_1 * 4).reshape(reg_max_1, 4);

                if (softmax.forward_inplace(pred_bbox, opt) != 0)
                    return -1;

                float pred_ltrb[4];
                for (int k = 0; k < 4; k++)
                {
                    float dis = 0.f;
                    const float* dis_after_sm = pred_bbox.row(k);
                    for (int l = 0; l < reg_max_1; l++)
                    {
                        dis += l * dis_after_sm[l];
                    }

                    if (!std::isfinite(dis))
                        return -1;
                    pred_ltrb[k] = dis * stride;
                }

                float pb_cx = (x + 0.5f) * stride;
                float pb_cy = (y + 0.5f) * stride;

                float x0 = pb_cx - pred_ltrb[0];
                float y0 = pb_cy - pred_ltrb[1];
                float x1 = pb_cx + pred_ltrb[2];
                float y1 = pb_cy + pred_ltrb[3];

                Object obj;
                obj.rect.x = x0;
                obj.rect.y = y0;
                obj.rect.width = x1 - x0;
                obj.rect.height = y1 - y0;
                obj.label = label;
                obj.prob = score;

                objects.push_back(obj);
            }
        }
    }
    return 0;
}


int YOLO11_det::detect(const cv::Mat& rgb, std::vector<Object>& objects)
{
    objects.clear();
    if (rgb.empty() || rgb.type() != CV_8UC3 || !rgb.isContinuous() || det_target_size <= 0)
        return -1;
    const int target_size = det_target_size;
    const float prob_threshold = 0.25f;
    const float nms_threshold = 0.45f;

    int img_w = rgb.cols;
    int img_h = rgb.rows;

    static const int strides[] = {8, 16, 32};
    const int max_stride = 32;

    // letterbox pad to multiple of max_stride
    int w = img_w;
    int h = img_h;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w;
        w = target_size;
        h = h * scale;
    }
    else
    {
        scale = (float)target_size / h;
        h = target_size;
        w = w * scale;
    }

    w = std::max(w, 1);
    h = std::max(h, 1);
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgb.data, ncnn::Mat::PIXEL_RGB, img_w, img_h, w, h);

    if (in.empty())
        return -1;

    // letterbox pad to target_size rectangle
    int wpad = (w + max_stride - 1) / max_stride * max_stride - w;
    int hpad = (h + max_stride - 1) / max_stride * max_stride - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);

    if (in_pad.empty())
        return -1;

    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in_pad.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = yolo11.create_extractor();

    if (ex.input("in0", in_pad) != 0)
        return -1;

    ncnn::Mat out;
    if (ex.extract("out0", out) != 0)
        return -1;
    int expected_rows = 0;
    for (int stride : strides)
        expected_rows += (in_pad.w / stride) * (in_pad.h / stride);
    if (out.empty() || out.dims != 2 || out.w != 64 + class_count ||
        out.h != expected_rows || out.elempack != 1 || out.elemsize != sizeof(float))
    {
        LOGD("Invalid output tensor: dims=%d w=%d h=%d, expected w=%d h=%d",
             out.dims, out.w, out.h, 64 + class_count, expected_rows);
        return -1;
    }

    // Reuse one Softmax pipeline per frame, rather than allocating one per box.
    std::unique_ptr<ncnn::Layer> softmax(ncnn::create_layer("Softmax"));
    if (!softmax)
        return -1;
    ncnn::ParamDict pd;
    pd.set(0, 1);
    pd.set(1, 1);
    ncnn::Option opt;
    opt.num_threads = 1;
    opt.use_packing_layout = false;
    if (softmax->load_param(pd) != 0)
        return -1;
    if (softmax->create_pipeline(opt) != 0)
    {
        softmax->destroy_pipeline(opt);
        return -1;
    }

    std::vector<Object> proposals;
    int row_offset = 0;
    int proposal_status = 0;
    for (int stride : strides)
    {
        const int rows = (in_pad.w / stride) * (in_pad.h / stride);
        proposal_status = generate_proposals(out.row_range(row_offset, rows), stride,
                                             in_pad, prob_threshold, proposals, *softmax, opt);
        if (proposal_status != 0)
            break;
        row_offset += rows;
    }
    softmax->destroy_pipeline(opt);
    if (proposal_status != 0)
        return proposal_status;

    std::sort(proposals.begin(), proposals.end(), [](const Object& a, const Object& b) {
        return a.prob > b.prob;
    });

    // apply nms with nms_threshold
    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, nms_threshold);

    int count = picked.size();

    objects.resize(count);
    for (int i = 0; i < count; i++)
    {
        objects[i] = proposals[picked[i]];

        // adjust offset to original unpadded
        float x0 = (objects[i].rect.x - (wpad / 2)) / scale;
        float y0 = (objects[i].rect.y - (hpad / 2)) / scale;
        float x1 = (objects[i].rect.x + objects[i].rect.width - (wpad / 2)) / scale;
        float y1 = (objects[i].rect.y + objects[i].rect.height - (hpad / 2)) / scale;

        // clip
        x0 = std::max(std::min(x0, (float)(img_w - 1)), 0.f);
        y0 = std::max(std::min(y0, (float)(img_h - 1)), 0.f);
        x1 = std::max(std::min(x1, (float)(img_w - 1)), 0.f);
        y1 = std::max(std::min(y1, (float)(img_h - 1)), 0.f);

        objects[i].rect.x = x0;
        objects[i].rect.y = y0;
        objects[i].rect.width = x1 - x0;
        objects[i].rect.height = y1 - y0;
    }
    

    // sort objects by area
    struct
    {
        bool operator()(const Object& a, const Object& b) const
        {
            return a.rect.area() > b.rect.area();
        }
    } objects_area_greater;
    std::sort(objects.begin(), objects.end(), objects_area_greater);

    return 0;
}

namespace {
DetectionOutput detection_output;

bool valid_object(const Object& object)
{
    return object.label >= 0 && object.label < class_count &&
           std::isfinite(object.prob) && object.prob >= 0.f && object.prob <= 1.f &&
           std::isfinite(object.rect.x) && std::isfinite(object.rect.y) &&
           std::isfinite(object.rect.width) && std::isfinite(object.rect.height) &&
           object.rect.width >= 0.f && object.rect.height >= 0.f;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_Chips_yolo11ncnn_YOLO_setOutputPath(JNIEnv* env, jobject, jstring path_)
{
    std::string path;
    if (path_)
    {
        const char* chars = env->GetStringUTFChars(path_, nullptr);
        if (!chars)
            return;
        path = chars;
        env->ReleaseStringUTFChars(path_, chars);
    }
    detection_output.setPath(path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_Chips_yolo11ncnn_YOLO_setOutputMetadata(JNIEnv* env, jobject, jstring time_,
                                               jdouble latitude, jdouble longitude)
{
    std::string time;
    if (time_)
    {
        const char* chars = env->GetStringUTFChars(time_, nullptr);
        if (!chars)
            return;
        time = chars;
        env->ReleaseStringUTFChars(time_, chars);
    }
    detection_output.setMetadata(time, latitude, longitude);
}

int YOLO11_det::draw(cv::Mat& rgb, const std::vector<Object>& objects) {
    if (rgb.empty())
        return -1;
    for (const Object& object : objects)
    {
        if (!valid_object(object))
            return -1;
    }

    static cv::Scalar colors[] = {
            cv::Scalar(67, 54, 244),
            cv::Scalar(30, 99, 233),
            cv::Scalar(39, 176, 156),
            cv::Scalar(58, 183, 103),
            cv::Scalar(81, 181, 63),
            cv::Scalar(150, 243, 33),
            cv::Scalar(169, 244, 3),
            cv::Scalar(188, 212, 0),
            cv::Scalar(150, 136, 0),
            cv::Scalar(175, 80, 76),
            cv::Scalar(195, 74, 139),
            cv::Scalar(220, 57, 205),
            cv::Scalar(235, 59, 255),
            cv::Scalar(193, 7, 255),
            cv::Scalar(152, 0, 255),
            cv::Scalar(87, 34, 255),
            cv::Scalar(85, 72, 121),
            cv::Scalar(158, 158, 158),
            cv::Scalar(125, 139, 96)
    };

    for (size_t i = 0; i < objects.size(); i++) {
        const Object &obj = objects[i];

        const cv::Scalar &color = colors[i % 19];

        cv::rectangle(rgb, obj.rect, color);

        char text[256];
        sprintf(text, "%s %.1f%%", class_names[obj.label], obj.prob * 100);

        int baseLine = 0;
        cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

        int x = obj.rect.x;
        int y = obj.rect.y - label_size.height - baseLine;
        if (y < 0)
            y = 0;
        if (x + label_size.width > rgb.cols)
            x = std::max(0, rgb.cols - label_size.width);

        cv::rectangle(rgb, cv::Rect(cv::Point(x, y),
                                    cv::Size(label_size.width, label_size.height + baseLine)),
                      cv::Scalar(255, 255, 255), -1);

        cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                    cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));
    }

    const int result = detection_output.write(objects, class_names, class_count);
    if (result != 0)
        LOGD("CSV output failed");
    return result;
}
