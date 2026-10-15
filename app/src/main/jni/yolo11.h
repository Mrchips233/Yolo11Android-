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

#ifndef YOLO11_H
#define YOLO11_H

#include <vector>

#include <opencv2/core/core.hpp>

#include <net.h>

struct Object
{
    cv::Rect_<float> rect;
    int label;
    float prob;
};

class YOLO11
{
public:
    YOLO11();
    virtual ~YOLO11() = default;

    int load(AAssetManager* mgr, const char* parampath, const char* modelpath, bool use_gpu = false);

    void set_det_target_size(int target_size);

    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects) = 0;
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects) = 0;

protected:
    ncnn::Net yolo11;
    int det_target_size;
};

class YOLO11_det : public YOLO11
{
public:
    int detect(const cv::Mat& rgb, std::vector<Object>& objects) override;
    int draw(cv::Mat& rgb, const std::vector<Object>& objects) override;
};

#endif // YOLO11_H
