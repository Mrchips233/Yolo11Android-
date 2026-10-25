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

#ifndef DETECTION_OUTPUT_H
#define DETECTION_OUTPUT_H

#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <limits>
#include <locale>
#include <mutex>
#include <string>
#include <vector>

// Owns export-session metadata independently of Android, OpenCV and the model.
class DetectionOutput
{
public:
    void setPath(const std::string& path)
    {
        std::lock_guard<std::mutex> lock(metadata_mutex_);
        metadata_.path = path;
        metadata_.time.clear();
        metadata_.latitude = std::numeric_limits<double>::quiet_NaN();
        metadata_.longitude = std::numeric_limits<double>::quiet_NaN();
        ++metadata_.generation;
    }

    void setMetadata(const std::string& time, double latitude, double longitude)
    {
        std::lock_guard<std::mutex> lock(metadata_mutex_);
        metadata_.time = time;
        metadata_.latitude = latitude;
        metadata_.longitude = longitude;
    }

    // A skipped export is successful. Invalid detections or failed I/O return -1.
    // Object only needs label and prob members; the model validates its geometry.
    template <typename Object>
    int write(const std::vector<Object>& objects, const char* const* class_names, int class_count)
    {
        if (objects.empty())
            return 0;
        if (!class_names || class_count <= 0)
            return -1;
        for (const Object& object : objects)
        {
            if (object.label < 0 || object.label >= class_count || !class_names[object.label] ||
                !std::isfinite(object.prob) || object.prob < 0 || object.prob > 1)
                return -1;
        }

        // Serialize file appends and deduplication without blocking metadata updates.
        std::lock_guard<std::mutex> write_lock(write_mutex_);
        Metadata metadata;
        {
            std::lock_guard<std::mutex> metadata_lock(metadata_mutex_);
            metadata = metadata_;
        }
        if (metadata.path.empty() || metadata.time.empty() ||
            !std::isfinite(metadata.latitude) || !std::isfinite(metadata.longitude) ||
            std::abs(metadata.latitude) > 90.0 || std::abs(metadata.longitude) > 180.0)
            return 0;

        const std::string second = secondKey(metadata.time);
        if (metadata.generation == last_generation_ && second == last_second_)
            return 0;

        std::ofstream outfile(metadata.path.c_str(), std::ios::app | std::ios::binary | std::ios::ate);
        if (!outfile.is_open())
            return -1;
        const std::streampos size = outfile.tellp();
        if (size == std::streampos(-1))
            return -1;
        const bool need_header = size == std::streampos(0);
        outfile.imbue(std::locale::classic());
        if (need_header)
        {
            outfile.write("\xEF\xBB\xBF", 3);
            outfile << "时间,纬度,经度,id,类别,置信度\n";
        }
        for (const Object& object : objects)
        {
            outfile << csvQuote(metadata.time) << ','
                    << std::fixed << std::setprecision(6) << metadata.latitude << ','
                    << std::fixed << std::setprecision(6) << metadata.longitude << ','
                    << object.label << ',' << csvQuote(class_names[object.label]) << ','
                    << std::fixed << std::setprecision(2) << object.prob << '\n';
        }
        outfile.flush();
        outfile.close();
        if (outfile.fail())
            return -1;

        // Only successful appends consume this second, so failures can be retried.
        last_generation_ = metadata.generation;
        last_second_ = second;
        return 0;
    }

private:
    struct Metadata
    {
        std::string path;
        std::string time;
        double latitude = std::numeric_limits<double>::quiet_NaN();
        double longitude = std::numeric_limits<double>::quiet_NaN();
        std::uint64_t generation = 0;
    };

    static std::string csvQuote(const std::string& value)
    {
        std::string result = "\"";
        for (char c : value)
        {
            if (c == '"')
                result += '"';
            result += c;
        }
        return result + '"';
    }

    static std::string secondKey(const std::string& time)
    {
        std::string result = time;
        const std::size_t dot = result.find('.');
        if (dot != std::string::npos)
        {
            std::size_t end = dot + 1;
            while (end < result.size() && result[end] >= '0' && result[end] <= '9')
                ++end;
            if (end > dot + 1)
                result.erase(dot, end - dot);
        }
        return result;
    }

    std::mutex metadata_mutex_;
    std::mutex write_mutex_;
    Metadata metadata_;
    std::uint64_t last_generation_ = std::numeric_limits<std::uint64_t>::max();
    std::string last_second_;
};

#endif // DETECTION_OUTPUT_H
