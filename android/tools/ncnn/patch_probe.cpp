// A single original-size patch, not a complete mobile SHARP implementation.
// Uses public ncnn APIs only. Run under an external time/memory supervisor.
#include <net.h>
#include <layer.h>
#if NCNN_VULKAN
#include <gpu.h>
#endif
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>
#include <vector>

using Clock = std::chrono::steady_clock;

#ifdef __ANDROID__
// The diagnostic remains bounded even if the USB connection disappears.
// These conservative limits apply to this single-patch probe, not the app.
class DeviceGuard {
    std::mutex mutex;
    std::condition_variable condition;
    bool stopped = false;
    std::thread worker;
    static long kb_field(const char* file, const char* name) {
        std::ifstream input(file);
        std::string line;
        while (std::getline(input, line)) {
            if (line.rfind(name, 0) == 0) return std::strtol(line.c_str() + std::string(name).size(), nullptr, 10);
        }
        return -1;
    }
public:
    DeviceGuard() : worker([this] {
        const auto began = Clock::now();
        std::unique_lock<std::mutex> lock(mutex);
        while (!condition.wait_for(lock, std::chrono::milliseconds(250), [this] { return stopped; })) {
            const long rss = kb_field("/proc/self/status", "VmRSS:");
            const long available = kb_field("/proc/meminfo", "MemAvailable:");
            const auto elapsed = std::chrono::duration<double>(Clock::now() - began).count();
            const char* reason = nullptr;
            if (elapsed >= 60) reason = "wall_time_60s";
            else if (rss > 1536L * 1024) reason = "process_rss_1536MiB";
            else if (available >= 0 && available < 1024L * 1024) reason = "available_below_1024MiB";
            if (reason) {
                std::fprintf(stderr, "{\"guardStopped\":\"%s\",\"rssKiB\":%ld,\"availableKiB\":%ld}\n", reason, rss, available);
                std::fflush(nullptr);
                std::_Exit(124); // The OS releases only this diagnostic's resources.
            }
        }
    }) {}
    ~DeviceGuard() {
        { std::lock_guard<std::mutex> lock(mutex); stopped = true; }
        condition.notify_all();
        worker.join();
    }
};
#endif

static double seconds(Clock::time_point began) {
    return std::chrono::duration<double>(Clock::now() - began).count();
}

static void phase(const char* name, Clock::time_point began) {
    std::printf("{\"phase\":\"%s\",\"seconds\":%.6f}\n", name, seconds(began));
    std::fflush(stdout);
}

static void check(int code, const char* operation) {
    if (code != 0) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(code));
}

static size_t peak_rss_bytes() {
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) return 0;
#ifdef __APPLE__
    return static_cast<size_t>(usage.ru_maxrss);
#else
    return static_cast<size_t>(usage.ru_maxrss) * 1024;
#endif
}

static void write_output(const std::string& prefix, const char* name,
                         const ncnn::Mat& value, size_t expected, std::ostream& report) {
    if (value.empty() || value.elempack != 1 || value.elemsize != sizeof(float))
        throw std::runtime_error(std::string(name) + " must extract as unpacked FP32");
    const size_t elements = static_cast<size_t>(value.w) * value.h * value.d * value.c;
    if (elements != expected) throw std::runtime_error(std::string(name) + " has an unexpected shape");
    std::ofstream raw(prefix + "." + name + ".f32", std::ios::binary | std::ios::trunc);
    if (!raw) throw std::runtime_error("Cannot open output file");
    double sum = 0, square = 0;
    size_t finite = 0;
    // cstep can include alignment padding. Do not serialize it as image data.
    const size_t plane = static_cast<size_t>(value.w) * value.h * value.d;
    for (int c = 0; c < value.c; ++c) {
        const float* data = value.channel(c);
        for (size_t j = 0; j < plane; ++j) {
            if (!std::isfinite(data[j])) throw std::runtime_error(std::string(name) + " contains a nonfinite result");
            sum += data[j]; square += static_cast<double>(data[j]) * data[j]; ++finite;
        }
        raw.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(plane * sizeof(float)));
    }
    if (!raw) throw std::runtime_error("Failed writing output tensor");
    report << "\"" << name << "\":{\"dims\":" << value.dims << ",\"w\":" << value.w
           << ",\"h\":" << value.h << ",\"d\":" << value.d << ",\"c\":" << value.c
           << ",\"elements\":" << finite << ",\"mean\":" << sum / finite
           << ",\"rms\":" << std::sqrt(square / finite) << "}";
}

static int probe(int argc, char** argv) {
    if (argc < 7 || argc > 9) {
        std::fprintf(stderr, "Usage: gemos-patch-probe PARAM BIN INPUT.f32 OUTPUT_PREFIX cpu|vulkan fp32|fp16 [--tap-tail] [--tail-layernorm-cpu]\n");
        return 2;
    }
    bool tapTail = false, tailLayernormCpu = false;
    for (int i = 7; i < argc; ++i) {
        const std::string flag = argv[i];
        if (flag == "--tap-tail") tapTail = true;
        else if (flag == "--tail-layernorm-cpu") tailLayernormCpu = true;
        else throw std::runtime_error("Unknown diagnostic option");
    }
    const std::string backend = argv[5], precision = argv[6];
    if (backend != "cpu" && backend != "vulkan") throw std::runtime_error("Unknown backend");
    if (precision != "fp32" && precision != "fp16") throw std::runtime_error("Unknown precision");
    const auto began = Clock::now();
    constexpr size_t pixels = 3 * 384 * 384;
    std::vector<float> input(pixels);
    std::ifstream image(argv[3], std::ios::binary | std::ios::ate);
    if (!image || image.tellg() != static_cast<std::streamoff>(pixels * sizeof(float)))
        throw std::runtime_error("Input must be precisely one original normalized [1,3,384,384] FP32 patch");
    image.seekg(0);
    if (!image.read(reinterpret_cast<char*>(input.data()), pixels * sizeof(float)))
        throw std::runtime_error("Input read failed");
    for (float value : input) if (!std::isfinite(value)) throw std::runtime_error("Nonfinite input");
    phase("create_backend", began);
    ncnn::Net net;
    net.opt.num_threads = 2;
    net.opt.lightmode = true;
    net.opt.use_int8_inference = false;
    net.opt.use_bf16_storage = false;
    net.opt.use_fp16_packed = precision == "fp16";
    net.opt.use_fp16_storage = precision == "fp16";
    net.opt.use_fp16_arithmetic = false;
    net.opt.use_vulkan_compute = backend == "vulkan";
#if NCNN_VULKAN
    if (net.opt.use_vulkan_compute) {
        if (ncnn::get_gpu_count() < 1) throw std::runtime_error("No public Vulkan compute device; no silent CPU fallback");
        net.set_vulkan_device(0);
    }
#else
    if (net.opt.use_vulkan_compute) throw std::runtime_error("This build has no Vulkan backend");
#endif
    phase("load_param", began);
    check(net.load_param(argv[1]), "load_param");
    if (tailLayernormCpu) {
        size_t selected = 0;
        for (ncnn::Layer* layer : net.mutable_layers()) {
            if (layer->name == "ln_169" && layer->type == "LayerNorm") {
                layer->support_vulkan = false;
                ++selected;
            }
        }
        // Scoped to this reviewed patch graph; do not silently change another model.
        if (selected != 1) throw std::runtime_error("Expected exactly one original patch tail LayerNorm");
    }
    phase("load_weights", began);
    check(net.load_model(argv[2]), "load_model");
    std::map<std::string, int> cpuOnlyTypes;
    size_t vulkanCapable = 0;
    for (const ncnn::Layer* layer : net.layers()) {
        if (layer->support_vulkan) ++vulkanCapable;
        else ++cpuOnlyTypes[layer->type];
    }
    phase("inference", began);
    const double initialized = seconds(began);
    ncnn::Extractor extractor = net.create_extractor();
    check(extractor.input("in0", ncnn::Mat(384, 384, 3, input.data())), "input");
    std::ofstream report(std::string(argv[4]) + ".json", std::ios::trunc);
    if (!report) throw std::runtime_error("Cannot open report");
    report << std::setprecision(10) << "{\"backendRequested\":\"" << backend
           << "\",\"gpuLayerCoverage\":\"capabilityFlagsOnly_notExecutionTrace\",\"vulkanCapableLayers\":" << vulkanCapable
           << ",\"layerCount\":" << net.layers().size() << ",\"cpuOnlyLayerTypes\":{";
    bool firstType = true;
    for (const auto& entry : cpuOnlyTypes) {
        if (!firstType) report << ',';
        report << '"' << entry.first << "\":" << entry.second;
        firstType = false;
    }
    report << "},\"tailLayernormCpu\":" << (tailLayernormCpu ? "true" : "false")
           << ",\"precisionStorageRequested\":\"" << precision
           << "\",\"fp16Arithmetic\":false,\"cpuThreads\":2,\"runs\":1,\"inputShape\":[1,3,384,384],\"outputs\":{";
    // Capture intermediate outputs before final to avoid re-running freed trunks.
    std::vector<const char*> names{"out1", "out2"};
    if (tapTail) names.insert(names.end(), {"681", "682", "683", "684"});
    names.push_back("out0");
    for (size_t i = 0; i < names.size(); ++i) {
        ncnn::Mat output;
        check(extractor.extract(names[i], output), names[i]);
        if (i) report << ',';
        const std::string name = names[i];
        const bool grid = name == "out0" || name == "683" || name == "684";
        write_output(argv[4], names[i], output, grid ? 1024 * 24 * 24 : 577 * 1024, report);
        phase(names[i], began);
    }
    const double completed = seconds(began);
    report << "},\"initializeSeconds\":" << initialized << ",\"inferenceSeconds\":" << completed - initialized
           << ",\"elapsedSeconds\":" << completed << ",\"peakRssBytes\":" << peak_rss_bytes()
           << ",\"status\":\"finite_outputs\"}\n";
    if (!report) throw std::runtime_error("Report write failed");
    phase("complete", began);
    return 0;
}

int main(int argc, char** argv) {
    std::printf("{\"probePid\":%ld}\n", static_cast<long>(getpid()));
    std::fflush(stdout);
#ifdef __ANDROID__
    DeviceGuard guard;
#endif
    try { return probe(argc, argv); }
    catch (const std::exception& error) {
        std::fprintf(stderr, "Original patch probe failed: %s\n", error.what());
        return 1;
    }
}
