// Original 1536px SHARP patch pyramid only: no decoder or photo generation.
#include "pyramid_stitch.h"
#include <net.h>
#include <layer.h>
#if NCNN_VULKAN
#include <gpu.h>
#endif
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <fcntl.h>
#include <iomanip>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>

using Clock = std::chrono::steady_clock;

#ifdef __ANDROID__
class PyramidDeviceGuard {
    std::mutex mutex_;
    std::condition_variable condition_;
    bool stopped_ = false;
    std::thread worker_;
    static long kb_field(const char* file, const char* name) {
        std::ifstream input(file);
        std::string line;
        while (std::getline(input, line))
            if (line.rfind(name, 0) == 0) return std::strtol(line.c_str() + std::string(name).size(), nullptr, 10);
        return -1;
    }
public:
    PyramidDeviceGuard() : worker_([this] {
        const auto began = Clock::now();
        std::unique_lock<std::mutex> lock(mutex_);
        while (!condition_.wait_for(lock, std::chrono::milliseconds(250), [this] { return stopped_; })) {
            const long rss = kb_field("/proc/self/status", "VmRSS:");
            const long available = kb_field("/proc/meminfo", "MemAvailable:");
            const double elapsed = std::chrono::duration<double>(Clock::now() - began).count();
            const char* reason = nullptr;
            if (elapsed >= 90) reason = "wall_time_90s";
            else if (rss < 0 || available < 0) reason = "memory_monitor_unavailable";
            else if (rss >= 1536L * 1024) reason = "process_rss_1536MiB";
            else if (available < 1024L * 1024) reason = "available_below_1024MiB";
            if (reason) {
                char diagnostic[256];
                const int length = std::snprintf(diagnostic, sizeof(diagnostic), "{\"guardStopped\":\"%s\",\"rssKiB\":%ld,\"availableKiB\":%ld}\n", reason, rss, available);
                const int flags = fcntl(STDERR_FILENO, F_GETFL);
                if (flags >= 0) fcntl(STDERR_FILENO, F_SETFL, flags | O_NONBLOCK);
                if (length > 0 && static_cast<size_t>(length) < sizeof(diagnostic))
                    (void)!write(STDERR_FILENO, diagnostic, static_cast<size_t>(length));
                std::_Exit(124);
            }
        }
    }) {}
    ~PyramidDeviceGuard() {
        { std::lock_guard<std::mutex> lock(mutex_); stopped_ = true; }
        condition_.notify_all(); worker_.join();
    }
};
#endif

static double seconds(Clock::time_point began) { return std::chrono::duration<double>(Clock::now() - began).count(); }
static std::size_t peak_rss_bytes() {
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) return 0;
#ifdef __APPLE__
    return static_cast<std::size_t>(usage.ru_maxrss);
#else
    return static_cast<std::size_t>(usage.ru_maxrss) * 1024;
#endif
}
static void check(int result, const char* operation) {
    if (result != 0) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(result));
}
static void phase(const char* name, Clock::time_point began) {
    std::printf("{\"phase\":\"%s\",\"seconds\":%.6f}\n", name, seconds(began)); std::fflush(stdout);
}

static std::vector<float> read_level(const char* path, int size) {
    const std::size_t count = static_cast<std::size_t>(3) * size * size;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input || input.tellg() != static_cast<std::streamoff>(count * sizeof(float)))
        throw std::runtime_error("Original normalized pyramid file has the wrong shape/size");
    input.seekg(0);
    std::vector<float> values(count);
    if (!input.read(reinterpret_cast<char*>(values.data()), count * sizeof(float))) throw std::runtime_error("Pyramid input read failed");
    for (float value : values)
        if (!std::isfinite(value) || value < -1.0001f || value > 1.0001f)
            throw std::runtime_error("Pyramid must contain finite original normalized RGB in [-1,1]");
    return values;
}

static void write_features(const std::string& prefix, const gemos::Accumulator& accumulator, std::ostream& report) {
    const auto& features = accumulator.finish();
    report << "\"features\":{";
    for (int i = 0; i < 5; ++i) {
        const char* name = gemos::feature_name(i);
        const auto& values = features[i];
        double sum = 0, squares = 0;
        float minimum = INFINITY, maximum = -INFINITY;
        for (float value : values) {
            if (!std::isfinite(value)) throw std::runtime_error("Nonfinite stitched feature");
            sum += value; squares += static_cast<double>(value) * value;
            minimum = std::min(minimum, value); maximum = std::max(maximum, value);
        }
        std::ofstream raw(prefix + "." + name + ".f32", std::ios::binary | std::ios::trunc);
        if (!raw || !raw.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(float)))
            throw std::runtime_error("Feature output write failed");
        if (i) report << ',';
        const int size = gemos::feature_size(i);
        const std::size_t channels = values.size() / size / size;
        report << '"' << name << "\":{\"shape\":[1," << channels << ',' << size << ',' << size
               << "],\"elements\":" << values.size() << ",\"finite\":true,\"min\":" << minimum
               << ",\"max\":" << maximum << ",\"mean\":" << sum / values.size()
               << ",\"rms\":" << std::sqrt(squares / values.size()) << '}';
    }
    report << '}';
}

static gemos::GridView output_view(const ncnn::Mat& tensor, bool tokens) {
    if (tensor.empty() || tensor.elempack != 1 || tensor.elemsize != sizeof(float))
        throw std::runtime_error("Encoder output must be unpacked FP32");
    if (tokens ? !(tensor.dims == 2 && tensor.w == 1024 && tensor.h == 577)
               : !(tensor.dims == 3 && tensor.w == 24 && tensor.h == 24 && tensor.c == 1024))
        throw std::runtime_error("Original encoder output shape changed");
    const std::size_t plane = static_cast<std::size_t>(tensor.w) * tensor.h * tensor.d;
    for (int c = 0; c < tensor.c; ++c) {
        const float* channel = tensor.channel(c);
        for (std::size_t i = 0; i < plane; ++i)
            if (!std::isfinite(channel[i])) throw std::runtime_error("Encoder returned a nonfinite feature");
    }
    return {static_cast<const float*>(tensor), 1024, tokens, tensor.cstep};
}

// Fast, portable host contract check, independent of any learned model.
static int fake_contract(const std::string& prefix) {
    constexpr int channels = 8;
    gemos::Accumulator accumulator(channels);
    std::vector<float> final(channels * 576), first(577 * channels), second(577 * channels);
    for (int index = 0; index < 35; ++index) {
        const auto p = gemos::patch_at(index);
        for (int c = 0; c < channels; ++c) {
            first[c] = -1000000.f + index;
            second[c] = -2000000.f + index;
            for (int spatial = 0; spatial < 576; ++spatial) {
                const float value = index * 10000.f + c * 600.f + spatial;
                final[c * 576 + spatial] = value;
                first[(spatial + 1) * channels + c] = value + 1000000.f;
                second[(spatial + 1) * channels + c] = value + 2000000.f;
            }
        }
        if (p.level == 0) {
            accumulator.add(p, gemos::latent0, {first.data(), channels, true, 0});
            accumulator.add(p, gemos::latent1, {second.data(), channels, true, 0});
        }
        accumulator.add(p, static_cast<gemos::Feature>(gemos::high + p.level), {final.data(), channels, false, 576});
    }
    std::ofstream report(prefix + ".json", std::ios::trunc);
    if (!report) throw std::runtime_error("Cannot open fake-contract report");
    report << "{\"status\":\"fake_contract\",\"patches\":35,\"channels\":8,";
    write_features(prefix, accumulator, report); report << "}\n";
    return report ? 0 : 1;
}

static uint32_t crc32(const float* values, std::size_t count) {
    uint32_t crc = 0xffffffffU;
    const auto* bytes = reinterpret_cast<const unsigned char*>(values);
    for (std::size_t i = 0; i < count * sizeof(float); ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc ^ 0xffffffffU;
}

static int inspect_pyramid(char** argv) {
    const std::array<std::vector<float>, 3> levels{{read_level(argv[2], 1536), read_level(argv[3], 768), read_level(argv[4], 384)}};
    std::ofstream report(std::string(argv[5]) + ".json", std::ios::trunc);
    if (!report) throw std::runtime_error("Cannot open pixel-contract report");
    std::vector<float> pixels;
    report << "{\"status\":\"pixel_contract\",\"patches\":[";
    for (int index = 0; index < 35; ++index) {
        const auto p = gemos::patch_at(index); gemos::copy_patch(levels[p.level], p, pixels);
        if (index) report << ',';
        report << "{\"index\":" << index << ",\"level\":" << p.level << ",\"row\":" << p.row
               << ",\"column\":" << p.column << ",\"inputCrc32\":" << crc32(pixels.data(), pixels.size()) << '}';
    }
    report << "]}\n"; return report ? 0 : 1;
}

static int probe(int argc, char** argv) {
    if (argc == 3 && std::string(argv[1]) == "--fake-contract") return fake_contract(argv[2]);
    if (argc == 6 && std::string(argv[1]) == "--inspect-pyramid") return inspect_pyramid(argv);
    if (argc != 9) {
        std::fprintf(stderr, "Usage: gemos-pyramid-probe PE_PARAM PE_BIN P1536.f32 P768.f32 P384.f32 OUTPUT_PREFIX cpu|vulkan fp32|fp16\n");
        return 2;
    }
    const std::string backend = argv[7], precision = argv[8];
    if ((backend != "cpu" && backend != "vulkan") || (precision != "fp32" && precision != "fp16"))
        throw std::runtime_error("Unknown backend/precision; there is no silent fallback");
    const auto began = Clock::now();
    phase("read_original_pyramid", began);
    const std::array<std::vector<float>, 3> levels{{read_level(argv[3], 1536), read_level(argv[4], 768), read_level(argv[5], 384)}};
    ncnn::Net net;
    net.opt.num_threads = 2; net.opt.lightmode = true;
    net.opt.use_int8_inference = false; net.opt.use_bf16_storage = false;
    net.opt.use_fp16_packed = precision == "fp16"; net.opt.use_fp16_storage = precision == "fp16";
    net.opt.use_fp16_arithmetic = false; net.opt.use_vulkan_compute = backend == "vulkan";
#if NCNN_VULKAN
    if (net.opt.use_vulkan_compute) {
        if (ncnn::get_gpu_count() < 1) throw std::runtime_error("No public Vulkan compute device");
        net.set_vulkan_device(0);
    }
#else
    if (net.opt.use_vulkan_compute) throw std::runtime_error("This build has no Vulkan backend");
#endif
    phase("load_param", began); check(net.load_param(argv[1]), "load_param");
    int layernorm_count = 0;
    for (ncnn::Layer* layer : net.mutable_layers())
        if (layer->type == "LayerNorm") { layer->support_vulkan = false; ++layernorm_count; }
    if (layernorm_count != 49) throw std::runtime_error("Expected exactly 49 original LayerNorm layers");
    phase("load_weights_once", began); check(net.load_model(argv[2]), "load_model");
    std::map<std::string, int> cpu_only;
    std::size_t vulkan_capable = 0;
    for (const ncnn::Layer* layer : net.layers())
        if (layer->support_vulkan) ++vulkan_capable; else ++cpu_only[layer->type];
    gemos::Accumulator accumulator;
    std::vector<float> pixels;
    std::ofstream report(std::string(argv[6]) + ".json", std::ios::trunc);
    if (!report) throw std::runtime_error("Cannot open pyramid report");
    report << std::setprecision(10) << "{\"backendRequested\":\"" << backend << "\",\"precisionStorageRequested\":\"" << precision
           << "\",\"fp16Arithmetic\":false,\"cpuThreads\":2,\"layernormCpuCount\":" << layernorm_count
           << ",\"netLoads\":1,\"patchCount\":35,\"batchSize\":1,\"originalInternalSize\":[1536,1536],"
           << "\"gpuLayerCoverage\":\"capabilityFlagsOnly_notExecutionTrace\",\"vulkanCapableLayers\":" << vulkan_capable
           << ",\"layerCount\":" << net.layers().size() << ",\"cpuOnlyLayerTypes\":{";
    bool first = true;
    for (const auto& entry : cpu_only) { if (!first) report << ','; first = false; report << '"' << entry.first << "\":" << entry.second; }
    const double initialized = seconds(began);
    report << "},\"initializeSeconds\":" << initialized << ",\"retainedFeatureBytes\":" << accumulator.retained_bytes() << ",\"patches\":[";
    for (int index = 0; index < 35; ++index) {
        const auto patch_began = Clock::now();
        const auto p = gemos::patch_at(index);
        gemos::copy_patch(levels[p.level], p, pixels);
        {
            ncnn::Extractor extractor = net.create_extractor();
            check(extractor.input("in0", ncnn::Mat(384, 384, 3, pixels.data())), "input");
            if (p.level == 0) {
                for (int latent = 0; latent < 2; ++latent) {
                    ncnn::Mat value;
                    check(extractor.extract(latent == 0 ? "out1" : "out2", value), "latent_extract");
                    accumulator.add(p, static_cast<gemos::Feature>(latent), output_view(value, true));
                }
            }
            ncnn::Mat value;
            check(extractor.extract("out0", value), "final_extract");
            accumulator.add(p, static_cast<gemos::Feature>(gemos::high + p.level), output_view(value, false));
        } // Release all patch tensors before the next new extractor.
        const double elapsed = seconds(patch_began);
        if (index) report << ',';
        report << "{\"index\":" << index << ",\"level\":" << p.level << ",\"row\":" << p.row << ",\"column\":" << p.column
               << ",\"seconds\":" << elapsed << ",\"finite\":true}"; report.flush();
        std::printf("{\"phase\":\"original_patch\",\"done\":%d,\"total\":35,\"level\":%d,\"seconds\":%.6f,\"totalSeconds\":%.6f}\n",
                    index + 1, p.level, elapsed, seconds(began)); std::fflush(stdout);
    }
    const double inferred = seconds(began);
    report << "],"; write_features(argv[6], accumulator, report);
    report << ",\"inferenceSeconds\":" << inferred - initialized << ",\"elapsedSeconds\":" << seconds(began)
           << ",\"peakRssBytes\":" << peak_rss_bytes() << ",\"status\":\"finite_original_35_patch_pyramid\","
           << "\"scope\":\"Unprojected original five-grid patch encoder stage; decoder/image encoder not included\"}\n";
    if (!report) throw std::runtime_error("Pyramid report write failed");
    phase("complete", began); return 0;
}

int main(int argc, char** argv) {
    std::printf("{\"probePid\":%ld}\n", static_cast<long>(getpid())); std::fflush(stdout);
#ifdef __ANDROID__
    PyramidDeviceGuard guard;
#endif
    try { return probe(argc, argv); }
    catch (const std::exception& error) { std::fprintf(stderr, "Original pyramid probe failed: %s\n", error.what()); return 1; }
}
