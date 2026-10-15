#include "detection_output.h"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <sys/stat.h>
#include <unistd.h>

namespace
{
struct Object
{
    int label;
    float prob;
};

const char* const names[] = {"W", "comma, and \"quote\""};
const std::vector<Object> objects = {{0, 0.9f}};

void require(bool value, const char* message)
{
    if (!value)
        throw std::runtime_error(message);
}

struct TemporaryFiles
{
    std::string root;
    std::vector<std::string> files;
    std::vector<std::string> directories;

    TemporaryFiles()
    {
        char pattern[] = "/tmp/yolo-csv-check-XXXXXX";
        char* directory = mkdtemp(pattern);
        require(directory != nullptr, "mkdtemp failed");
        root = directory;
    }

    ~TemporaryFiles()
    {
        for (const std::string& file : files)
            unlink(file.c_str());
        for (std::vector<std::string>::reverse_iterator i = directories.rbegin(); i != directories.rend(); ++i)
            rmdir(i->c_str());
        rmdir(root.c_str());
    }

    std::string file(const std::string& name)
    {
        const std::string path = root + '/' + name;
        files.push_back(path);
        return path;
    }

    void directory(const std::string& path)
    {
        require(mkdir(path.c_str(), 0700) == 0, "mkdir failed");
        directories.push_back(path);
    }
};

std::string read(const std::string& path)
{
    std::ifstream file(path.c_str(), std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
}

std::size_t rows(const std::string& path)
{
    const std::string content = read(path);
    if (content.empty())
        return 0;
    std::size_t count = 0;
    for (char c : content)
        if (c == '\n')
            ++count;
    return count - 1; // exclude the single CSV header
}

void check_seconds_and_sessions(TemporaryFiles& temporary)
{
    DetectionOutput output;
    const std::string path = temporary.file("seconds.csv");
    output.setPath(path);
    output.setMetadata("12:34:56.100", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0, "first append failed");
    output.setMetadata("12:34:56.999", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 1, "same second not deduplicated");
    output.setMetadata("12:34:57.001", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 2, "next second lost");
    output.setMetadata("2026-10-15T12:34:57.001Z", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0, "dated append failed");
    output.setMetadata("2026-10-15T12:34:57.900Z", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 3, "ISO same second not deduplicated");
    output.setMetadata("2026-10-16T12:34:57.001Z", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 4, "next date lost");
    output.setMetadata("2026-10-16T12:34:57.001+08:00", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 5, "timezone suffix discarded");
    output.setMetadata("2026-10-16T12:34:57.999+08:00", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 5, "offset same second not deduplicated");

    output.setPath(path);
    require(output.write(objects, names, 2) == 0 && rows(path) == 5, "new session reused stale metadata");
    output.setMetadata("2026-10-16T12:34:57.999+08:00", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(path) == 6, "same-path session not reset");
    const std::string content = read(path);
    require(content.compare(0, 3, "\xEF\xBB\xBF") == 0, "UTF-8 BOM missing");
    require(content.find("\xEF\xBB\xBF", 3) == std::string::npos, "BOM repeated on append");

    const std::string other = temporary.file("other.csv");
    output.setPath(other);
    output.setMetadata("2026-10-16T12:34:57.999+08:00", 30.25, 120.5);
    require(output.write(objects, names, 2) == 0 && rows(other) == 1, "new path suppressed by old second");
    output.setPath("");
    output.setMetadata("12:00:00.000", 1, 2);
    require(output.write(objects, names, 2) == 0 && rows(other) == 1, "empty path not disabled");
}

void check_invalid_data(TemporaryFiles& temporary)
{
    DetectionOutput output;
    const std::string path = temporary.file("invalid.csv");
    output.setPath(path);
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double invalid[][2] = {{nan, 1}, {1, nan}, {91, 1}, {-91, 1}, {1, 181}, {1, -181}};
    for (const auto& coordinates : invalid)
    {
        output.setMetadata("12:00:00.000", coordinates[0], coordinates[1]);
        require(output.write(objects, names, 2) == 0, "invalid coordinates should be skipped");
    }
    output.setMetadata("", 1, 2);
    require(output.write(objects, names, 2) == 0, "empty time should be skipped");
    require(access(path.c_str(), F_OK) != 0, "invalid metadata created a CSV");

    output.setMetadata("12:00:00.000", 1, 2);
    const Object invalid_objects[] = {{-1, .9f}, {2, .9f}, {0, -.1f}, {0, 1.1f},
                                     {0, std::numeric_limits<float>::quiet_NaN()},
                                     {0, std::numeric_limits<float>::infinity()}};
    for (const Object& object : invalid_objects)
        require(output.write(std::vector<Object>(1, object), names, 2) == -1, "invalid detection accepted");
    require(output.write(objects, nullptr, 2) == -1, "null class table accepted");
    require(access(path.c_str(), F_OK) != 0, "invalid detection created a CSV");
    require(output.write(objects, names, 2) == 0 && rows(path) == 1, "invalid data consumed deduplication second");
}

void check_retry_and_escaping(TemporaryFiles& temporary)
{
    DetectionOutput output;
    const std::string directory = temporary.root + "/missing";
    const std::string path = temporary.file("missing/retry.csv");
    output.setPath(path);
    output.setMetadata("12:00:00.000", 1, 2);
    require(output.write(objects, names, 2) == -1, "missing directory did not fail");
    temporary.directory(directory);
    require(output.write(objects, names, 2) == 0 && rows(path) == 1, "failed second could not be retried");

    const std::string full = temporary.file("disk-full.csv");
    require(symlink("/dev/full", full.c_str()) == 0, "disk-full fixture failed");
    output.setPath(full);
    output.setMetadata("12:00:00.000", 1, 2);
    require(output.write(objects, names, 2) == -1, "full disk write was reported as successful");
    require(unlink(full.c_str()) == 0, "disk-full fixture cleanup failed");
    require(output.write(objects, names, 2) == 0 && rows(full) == 1, "failed flush consumed deduplication second");

    const std::string escaped = temporary.file("escaped.csv");
    { std::ofstream empty(escaped.c_str(), std::ios::binary); }
    output.setPath(escaped);
    output.setMetadata("time,\"quoted\"", 1.5, 2.25);
    require(output.write(std::vector<Object>(1, Object{1, .75f}), names, 2) == 0, "escaped append failed");
    require(read(escaped).find("\"time,\"\"quoted\"\"\",1.500000,2.250000,1,\"comma, and \"\"quote\"\"\",0.75\n") != std::string::npos,
            "CSV quote escaping or decimal formatting is incorrect");
    require(read(escaped).compare(0, 3, "\xEF\xBB\xBF") == 0, "empty existing file did not receive its header");
}

void check_concurrent_snapshot(TemporaryFiles& temporary)
{
    DetectionOutput output;
    const std::string path = temporary.file("concurrent.csv");
    output.setPath(path);
    output.setMetadata("snapshotA", 11, 111);
    std::atomic<bool> start(false);
    std::atomic<bool> failed(false);
    std::thread setter([&]() {
        while (!start.load())
            std::this_thread::yield();
        for (int i = 0; i < 3000; ++i)
        {
            if (i % 2)
                output.setMetadata("snapshotA", 11, 111);
            else
                output.setMetadata("snapshotB", 22, 122);
            std::this_thread::yield();
        }
    });
    const auto writer = [&]() {
        while (!start.load())
            std::this_thread::yield();
        for (int i = 0; i < 500; ++i)
            if (output.write(objects, names, 2) != 0)
                failed.store(true);
    };
    std::thread first(writer);
    std::thread second(writer);
    start.store(true);
    setter.join();
    first.join();
    second.join();
    require(!failed.load() && rows(path) > 0, "concurrent export failed");
    std::istringstream stream(read(path));
    std::string line;
    std::getline(stream, line); // header
    while (std::getline(stream, line))
        require(line == "\"snapshotA\",11.000000,111.000000,0,\"W\",0.90" ||
                line == "\"snapshotB\",22.000000,122.000000,0,\"W\",0.90",
                "CSV row mixed metadata from different setter calls");
}
}

int main()
{
    try
    {
        TemporaryFiles temporary;
        check_seconds_and_sessions(temporary);
        check_invalid_data(temporary);
        check_retry_and_escaping(temporary);
        check_concurrent_snapshot(temporary);
        std::cout << "Native CSV production-class checks passed\n";
        return 0;
    }
    catch (const std::exception& error)
    {
        std::cerr << "Native CSV check failed: " << error.what() << '\n';
        return 1;
    }
}
