// A single original-size patch, not a complete mobile SHARP implementation.
// Uses public ncnn APIs only. Run under an external time/memory supervisor.
#include <net.h>
#include <layer.h>
#if NCNN_VULKAN
#include <allocator.h>
#include <gpu.h>
#endif
#include <array>
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
#include <memory>
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
                char diagnostic[256];
                const int length = std::snprintf(diagnostic, sizeof(diagnostic), "{\"guardStopped\":\"%s\",\"rssKiB\":%ld,\"availableKiB\":%ld}\n", reason, rss, available);
                const int flags = fcntl(STDERR_FILENO, F_GETFL);
                if (flags >= 0) fcntl(STDERR_FILENO, F_SETFL, flags | O_NONBLOCK);
                if (length > 0 && static_cast<size_t>(length) < sizeof(diagnostic))
                    (void)!write(STDERR_FILENO, diagnostic, static_cast<size_t>(length));
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

static std::uint32_t input_crc32(const std::vector<float>& input) {
    std::uint32_t crc = 0xffffffffU;
    const auto* bytes = reinterpret_cast<const unsigned char*>(input.data());
    for (size_t i = 0; i < input.size() * sizeof(float); ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc ^ 0xffffffffU;
}

struct ConstantSnapshot {
    std::string name;
    int dims, w, h, d, c;
    size_t elements;
    std::uint32_t crc;
};

#if NCNN_VULKAN
static std::vector<ConstantSnapshot> snapshot_memory_constants(const ncnn::Net& net) {
    // Public MemoryData::forward clones its existing GPU constant. Do not cast
    // Layer to a private implementation or reload/upload any of its weights.
    ncnn::VkBlobAllocator blob(net.vulkan_device());
    ncnn::VkStagingAllocator staging(net.vulkan_device());
    ncnn::Option opt = net.opt;
    opt.blob_allocator = nullptr;
    opt.workspace_allocator = nullptr;
    opt.blob_vkallocator = &blob;
    opt.workspace_vkallocator = &blob;
    opt.staging_vkallocator = &staging;
    opt.use_local_pool_allocator = false;
    opt.use_packing_layout = false; // Canonical FP32 unpacked readback on this GPU.
    std::vector<ConstantSnapshot> snapshots;
    for (const ncnn::Layer* layer : net.layers()) {
        if (layer->type != "MemoryData") continue;
        if (!layer->support_vulkan || layer->featmask)
            throw std::runtime_error("Constant snapshots require unchanged Vulkan MemoryData layers");
        const std::vector<ncnn::VkMat> inputs;
        std::vector<ncnn::VkMat> outputs(1);
        ncnn::Mat host;
        ncnn::VkCompute command(net.vulkan_device());
        check(layer->forward(inputs, outputs, command, opt), "constant_forward");
        if (outputs[0].empty()) throw std::runtime_error("Empty GPU constant snapshot");
        command.record_download(outputs[0], host, opt);
        check(command.submit_and_wait(), "constant_download");
        if (host.empty() || host.elempack != 1 || host.elemsize != sizeof(float))
            throw std::runtime_error("Constant snapshot must download as unpacked FP32");
        std::uint32_t crc = 0xffffffffU;
        const size_t plane = static_cast<size_t>(host.w) * host.h * host.d;
        // Exclude channel alignment padding, exactly as for the tensor outputs.
        for (int c = 0; c < host.c; ++c) {
            const auto* bytes = reinterpret_cast<const unsigned char*>(static_cast<const float*>(host.channel(c)));
            for (size_t j = 0; j < plane * sizeof(float); ++j) {
                crc ^= bytes[j];
                for (int bit = 0; bit < 8; ++bit)
                    crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
            }
        }
        snapshots.push_back({layer->name, host.dims, host.w, host.h, host.d, host.c,
                             plane * host.c, crc ^ 0xffffffffU});
    }
    if (snapshots.empty()) throw std::runtime_error("No Vulkan MemoryData constants to snapshot");
    return snapshots;
}
#endif

static void write_constant_snapshots(std::ostream& report, const std::vector<ConstantSnapshot>& snapshots) {
    report << '[';
    for (size_t i = 0; i < snapshots.size(); ++i) {
        if (i) report << ',';
        const auto& s = snapshots[i];
        report << "{\"name\":\"" << s.name << "\",\"dims\":" << s.dims << ",\"w\":" << s.w
               << ",\"h\":" << s.h << ",\"d\":" << s.d << ",\"c\":" << s.c
               << ",\"elements\":" << s.elements << ",\"crc32\":" << s.crc << '}';
    }
    report << ']';
}

static bool write_constant_comparison(std::ostream& report, const std::vector<ConstantSnapshot>& before,
                                      const std::vector<ConstantSnapshot>& after) {
    if (before.size() != after.size()) throw std::runtime_error("Constant snapshot count changed");
    report << ",\"constantSnapshots\":{\"method\":\"public_forward_clone_download_fp32\",\"count\":" << before.size() << ",\"before\":";
    write_constant_snapshots(report, before);
    report << ",\"after\":";
    write_constant_snapshots(report, after);
    report << ",\"changedNames\":[";
    bool unchanged = true;
    for (size_t i = 0; i < before.size(); ++i) {
        const auto& a = before[i]; const auto& b = after[i];
        if (a.name != b.name) throw std::runtime_error("Constant snapshot order changed");
        if (a.dims == b.dims && a.w == b.w && a.h == b.h && a.d == b.d && a.c == b.c && a.crc == b.crc) continue;
        if (!unchanged) report << ',';
        report << '"' << a.name << '"';
        unchanged = false;
    }
    report << "],\"unchanged\":" << (unchanged ? "true" : "false") << '}';
    return unchanged;
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
    if (argc < 7) {
        std::fprintf(stderr, "Usage: gemos-patch-probe PARAM BIN INPUT.f32 OUTPUT_PREFIX cpu|vulkan fp32|fp16 [--image-only|--tap-tail] [--tail-layernorm-cpu|--layernorm-cpu] [--repeat=1|2|3] [--fresh-vulkan-allocators] [--no-cpu-pool] [--fresh-net] [--snapshot-constants|--memorydata-cpu]\n");
        return 2;
    }
    bool tapTail = false, tailLayernormCpu = false, layernormCpu = false, imageOnly = false;
    int repeats = 1;
    bool repeatSpecified = false;
    bool freshVulkanAllocators = false;
    bool noCpuPool = false, freshNet = false;
    bool snapshotConstants = false, memoryDataCpu = false;
    for (int i = 7; i < argc; ++i) {
        const std::string flag = argv[i];
        if (flag == "--tap-tail") tapTail = true;
        else if (flag == "--tail-layernorm-cpu") tailLayernormCpu = true;
        else if (flag == "--layernorm-cpu") layernormCpu = true;
        else if (flag == "--image-only") imageOnly = true;
        else if (flag == "--fresh-vulkan-allocators") {
            if (freshVulkanAllocators) throw std::runtime_error("Fresh Vulkan allocators must be specified only once");
            freshVulkanAllocators = true;
        }
        else if (flag == "--no-cpu-pool") {
            if (noCpuPool) throw std::runtime_error("CPU pool policy must be specified only once");
            noCpuPool = true;
        }
        else if (flag == "--fresh-net") {
            if (freshNet) throw std::runtime_error("Fresh Net policy must be specified only once");
            freshNet = true;
        }
        else if (flag == "--snapshot-constants") {
            if (snapshotConstants) throw std::runtime_error("Constant snapshot policy must be specified only once");
            snapshotConstants = true;
        }
        else if (flag == "--memorydata-cpu") {
            if (memoryDataCpu) throw std::runtime_error("MemoryData CPU policy must be specified only once");
            memoryDataCpu = true;
        }
        else if (flag.rfind("--repeat=", 0) == 0) {
            if (repeatSpecified || (flag != "--repeat=1" && flag != "--repeat=2" && flag != "--repeat=3"))
                throw std::runtime_error("Repeat must be specified once and be 1, 2, or 3");
            repeats = flag.back() - '0';
            repeatSpecified = true;
        }
        else throw std::runtime_error("Unknown diagnostic option");
    }
    if (tailLayernormCpu && layernormCpu) throw std::runtime_error("Choose one normalization policy");
    if (imageOnly && (tapTail || tailLayernormCpu)) throw std::runtime_error("Image encoder exposes only its original final grid");
    const std::string backend = argv[5], precision = argv[6];
    if (backend != "cpu" && backend != "vulkan") throw std::runtime_error("Unknown backend");
    if (precision != "fp32" && precision != "fp16") throw std::runtime_error("Unknown precision");
    if (freshVulkanAllocators && (backend != "vulkan" || repeats < 2))
        throw std::runtime_error("Fresh Vulkan allocators require the Vulkan backend and at least two repeats");
    if (freshNet && repeats < 2) throw std::runtime_error("Fresh Net requires at least two repeats");
    if (snapshotConstants && (backend != "vulkan" || repeats < 2 || memoryDataCpu))
        throw std::runtime_error("Constant snapshots require Vulkan, at least two repeats, and GPU MemoryData");
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
    const std::uint32_t originalInputCrc = input_crc32(input);
    std::unique_ptr<ncnn::Net> net;
    int netLoads = 0;
    size_t memoryDataCpuCount = 0;
    const auto initializeNet = [&]() {
        const auto initializationBegan = Clock::now();
        // The caller must destroy the previous complete Net first. Do not call
        // load_model again on an existing set of pipelines or weight buffers.
        if (net) throw std::runtime_error("Previous Net must be fully released before initialization");
        phase("create_backend", began);
        net.reset(new ncnn::Net);
        net->opt.num_threads = 2;
        net->opt.lightmode = true;
        net->opt.use_local_pool_allocator = !noCpuPool;
        net->opt.use_int8_inference = false;
        net->opt.use_bf16_storage = false;
        net->opt.use_fp16_packed = precision == "fp16";
        net->opt.use_fp16_storage = precision == "fp16";
        net->opt.use_fp16_arithmetic = false;
        net->opt.use_vulkan_compute = backend == "vulkan";
#if NCNN_VULKAN
        if (net->opt.use_vulkan_compute) {
            if (ncnn::get_gpu_count() < 1) throw std::runtime_error("No public Vulkan compute device; no silent CPU fallback");
            net->set_vulkan_device(0);
        }
#else
        if (net->opt.use_vulkan_compute) throw std::runtime_error("This build has no Vulkan backend");
#endif
        phase("load_param", began);
        check(net->load_param(argv[1]), "load_param");
        if (tailLayernormCpu || layernormCpu) {
            size_t selected = 0;
            for (ncnn::Layer* layer : net->mutable_layers()) {
                if (layer->type == "LayerNorm" && (layernormCpu || layer->name == "ln_169")) {
                    layer->support_vulkan = false;
                    ++selected;
                }
            }
            // Scoped to this reviewed patch graph; do not silently change another model.
            if (selected != (layernormCpu ? 49U : 1U)) throw std::runtime_error("Unexpected original patch LayerNorm count");
        }
        memoryDataCpuCount = 0;
        if (memoryDataCpu) {
            for (ncnn::Layer* layer : net->mutable_layers()) {
                if (layer->type != "MemoryData") continue;
                layer->support_vulkan = false;
                ++memoryDataCpuCount;
            }
            if (!memoryDataCpuCount) throw std::runtime_error("No MemoryData layers selected for CPU");
        }
        phase("load_weights", began);
        check(net->load_model(argv[2]), "load_model");
        ++netLoads;
        return seconds(initializationBegan);
    };
    const double firstNetInitializeSeconds = initializeNet();
    double totalNetInitializeSeconds = firstNetInitializeSeconds;
    std::map<std::string, int> cpuOnlyTypes;
    size_t vulkanCapable = 0;
    for (const ncnn::Layer* layer : net->layers()) {
        if (layer->support_vulkan) ++vulkanCapable;
        else ++cpuOnlyTypes[layer->type];
    }
    phase("inference", began);
    const double initialized = seconds(began);
    std::ofstream report(std::string(argv[4]) + ".json", std::ios::trunc);
    if (!report) throw std::runtime_error("Cannot open report");
    report << std::setprecision(10) << "{\"backendRequested\":\"" << backend
           << "\",\"gpuLayerCoverage\":\"capabilityFlagsOnly_notExecutionTrace\",\"vulkanCapableLayers\":" << vulkanCapable
           << ",\"layerCount\":" << net->layers().size() << ",\"cpuOnlyLayerTypes\":{";
    bool firstType = true;
    for (const auto& entry : cpuOnlyTypes) {
        if (!firstType) report << ',';
        report << '"' << entry.first << "\":" << entry.second;
        firstType = false;
    }
    report << "},\"tailLayernormCpu\":" << (tailLayernormCpu ? "true" : "false")
           << ",\"allLayernormCpu\":" << (layernormCpu ? "true" : "false")
           << ",\"imageEncoderOnly\":" << (imageOnly ? "true" : "false")
           << ",\"precisionStorageRequested\":\"" << precision
           << "\",\"fp16Arithmetic\":false,\"cpuThreads\":2,\"runs\":" << repeats
           << ",\"inputShape\":[1,3,384,384],\"inputCrc32Initial\":" << originalInputCrc
           << ",\"freshVulkanAllocators\":" << (freshVulkanAllocators ? "true" : "false")
           << ",\"freshNetEachRun\":" << (freshNet ? "true" : "false")
           << ",\"cpuAllocatorPolicy\":\"" << (noCpuPool ? "system_malloc" : "net_pool") << '"'
           << ",\"snapshotConstants\":" << (snapshotConstants ? "true" : "false")
           << ",\"memoryDataCpu\":" << (memoryDataCpu ? "true" : "false") << ",\"memoryDataCpuCount\":" << memoryDataCpuCount
           << ",\"vulkanAllocatorPolicy\":\"" << (backend == "cpu" ? "not_applicable" : freshVulkanAllocators ? "fresh_per_run" : "device_pool") << "\",";
    // Capture intermediate outputs before final to avoid re-running freed trunks.
    std::vector<const char*> names;
    if (!imageOnly) names = {"out1", "out2"};
    if (tapTail) names.insert(names.end(), {"681", "682", "683", "684"});
    names.push_back("out0");
    if (repeats > 1)
        report << "\"repeatInputPolicy\":\"single_immutable_host_buffer_new_extractor_each_run\",\"iterations\":[";
    bool inputMutated = false;
    int completedRuns = 0;
    double totalInferenceSeconds = 0;
    bool allConstantsUnchanged = true;
    for (int run = 0; run < repeats; ++run) {
        double netInitializeSeconds = run == 0 ? firstNetInitializeSeconds : 0;
        if (run > 0 && freshNet) {
            phase("release_previous_net", began);
            net.reset(); // Finish destruction before allocating any replacement.
            netInitializeSeconds = initializeNet();
            totalNetInitializeSeconds += netInitializeSeconds;
        }
        const auto runBegan = Clock::now();
        const std::uint32_t inputCrcBefore = input_crc32(input);
        std::vector<ConstantSnapshot> constantsBefore, constantsAfter;
        double constantSnapshotSeconds = 0;
#if NCNN_VULKAN
        if (snapshotConstants) {
            const auto snapshotBegan = Clock::now();
            phase("constant_snapshot_before", began);
            constantsBefore = snapshot_memory_constants(*net);
            constantSnapshotSeconds += seconds(snapshotBegan);
        }
#endif
        const std::string prefix = repeats == 1 ? std::string(argv[4]) : std::string(argv[4]) + ".r" + std::to_string(run);
        if (repeats > 1) {
            if (run) report << ',';
            report << "{\"run\":" << run << ",\"outputSuffix\":\".r" << run << "\",";
        }
        report << "\"outputs\":{";
        // Match the pyramid probe's lifetime exactly: a new Extractor per patch,
        // with every host result consumed before destruction. The input vector is
        // deliberately neither replaced nor written between diagnostic rounds.
        {
#if NCNN_VULKAN
            // Allocators are declared before the Extractor, so exception unwinding
            // and ordinary scope exit destroy every tensor before its allocator.
            std::unique_ptr<ncnn::VkBlobAllocator> freshBlob;
            std::unique_ptr<ncnn::VkStagingAllocator> freshStaging;
            if (freshVulkanAllocators) {
                freshBlob.reset(new ncnn::VkBlobAllocator(net->vulkan_device()));
                freshStaging.reset(new ncnn::VkStagingAllocator(net->vulkan_device()));
            }
#endif
            ncnn::Extractor extractor = net->create_extractor();
#if NCNN_VULKAN
            if (freshVulkanAllocators) {
                extractor.set_blob_vkallocator(freshBlob.get());
                extractor.set_workspace_vkallocator(freshBlob.get());
                extractor.set_staging_vkallocator(freshStaging.get());
            }
#endif
            check(extractor.input("in0", ncnn::Mat(384, 384, 3, input.data())), "input");
            for (size_t i = 0; i < names.size(); ++i) {
                ncnn::Mat output;
                check(extractor.extract(names[i], output), names[i]);
                if (i) report << ',';
                const std::string name = names[i];
                const bool grid = name == "out0" || name == "683" || name == "684";
                write_output(prefix, names[i], output, grid ? 1024 * 24 * 24 : 577 * 1024, report);
                const std::string label = repeats == 1 ? name : "r" + std::to_string(run) + "_" + name;
                phase(label.c_str(), began);
            }
        }
#if NCNN_VULKAN
        if (snapshotConstants) {
            const auto snapshotBegan = Clock::now();
            phase("constant_snapshot_after", began);
            constantsAfter = snapshot_memory_constants(*net);
            constantSnapshotSeconds += seconds(snapshotBegan);
        }
#endif
        const std::uint32_t inputCrcAfter = input_crc32(input);
        inputMutated = inputCrcBefore != originalInputCrc || inputCrcAfter != inputCrcBefore;
        ++completedRuns;
        const double runInferenceSeconds = seconds(runBegan);
        totalInferenceSeconds += runInferenceSeconds;
        report << "},\"inputCrc32Before\":" << inputCrcBefore << ",\"inputCrc32After\":" << inputCrcAfter
               << ",\"inputUnchanged\":" << (inputMutated ? "false" : "true")
               << ",\"netLoadIndex\":" << netLoads << ",\"netInitializeSeconds\":" << netInitializeSeconds;
        if (snapshotConstants) {
            const bool unchanged = write_constant_comparison(report, constantsBefore, constantsAfter);
            allConstantsUnchanged = allConstantsUnchanged && unchanged;
            report << ",\"constantSnapshotSeconds\":" << constantSnapshotSeconds;
        }
        if (repeats > 1)
            report << ",\"inferenceSeconds\":" << runInferenceSeconds << ",\"peakRssBytes\":" << peak_rss_bytes() << '}';
        if (inputMutated) break;
    }
    if (repeats > 1) report << ']';
    const double completed = seconds(began);
    report << ",\"initializeSeconds\":" << initialized << ",\"inferenceSeconds\":" << totalInferenceSeconds
           << ",\"netLoads\":" << netLoads << ",\"totalNetInitializeSeconds\":" << totalNetInitializeSeconds
           << ",\"elapsedSeconds\":" << completed << ",\"peakRssBytes\":" << peak_rss_bytes()
           << ",\"completedRuns\":" << completedRuns
           << ",\"allConstantSnapshotsUnchanged\":" << (!snapshotConstants ? "null" : allConstantsUnchanged ? "true" : "false")
           << ",\"status\":\"" << (inputMutated ? "input_buffer_mutated" : "finite_outputs") << "\"}\n";
    if (!report) throw std::runtime_error("Report write failed");
    report.close();
    if (inputMutated) throw std::runtime_error("Inference modified the immutable host input buffer; see recorded before/after CRC32");
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
