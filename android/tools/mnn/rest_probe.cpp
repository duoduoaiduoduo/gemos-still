// One reviewed SHARP post-ViT stage. No phone settings, private drivers or APK.
// The read-only Execution inspection below is tied to the exact MNN revision.
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include "core/Session.hpp"
#include <CL/cl.h>
#if !defined(__APPLE__)
#define CL_HPP_TARGET_OPENCL_VERSION 110
#define CL_HPP_MINIMUM_OPENCL_VERSION 110
#include <CL/cl.hpp>
#endif
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>
#include <vector>

using Clock=std::chrono::steady_clock;
static constexpr const char* kMnnRevision="dbf9ba61b08106a3b43426e295d1a29cb41cc8fe";
static constexpr size_t kGaussians=1179648;
static constexpr size_t kWeightBytes=93184376;
struct InputSpec { const char* name; std::vector<int> shape; };
static const std::array<InputSpec,8> kInputs{{
    {"image",{1,3,1536,1536}}, {"disparity_factor",{1}},
    {"spn_latent0",{1,1024,96,96}}, {"spn_latent1",{1,1024,96,96}},
    {"spn_high",{1,1024,96,96}}, {"spn_middle",{1,1024,48,48}},
    {"spn_low",{1,1024,24,24}}, {"spn_image_global",{1,1024,24,24}}
}};
static std::string quote(const std::string& value) {
    std::ostringstream out;out << '"';
    for(unsigned char ch:value) {
        if(ch=='"' || ch=='\\') out << '\\' << ch;
        else if(ch=='\n') out << "\\n";
        else if(ch=='\r') out << "\\r";
        else if(ch=='\t') out << "\\t";
        else if(ch<32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(ch) << std::dec;
        else out << ch;
    }
    return out.str()+'"';
}
static double seconds(Clock::time_point start) { return std::chrono::duration<double>(Clock::now()-start).count(); }
static void phase(const char* name,Clock::time_point start) {
    std::cout << "{\"phase\":" << quote(name) << ",\"seconds\":" << seconds(start) << "}" << std::endl;
}
static long kb_field(const char* file,const char* name) {
    std::ifstream input(file);std::string line;
    while(std::getline(input,line)) if(line.rfind(name,0)==0) return std::strtol(line.c_str()+std::string(name).size(),nullptr,10);
    return -1;
}
static size_t peak_rss_bytes() {
    rusage usage{};if(getrusage(RUSAGE_SELF,&usage)!=0) return 0;
#ifdef __APPLE__
    return static_cast<size_t>(usage.ru_maxrss);
#else
    return static_cast<size_t>(usage.ru_maxrss)*1024;
#endif
}
#ifdef __ANDROID__
class DeviceGuard {
    std::mutex mutex;std::condition_variable condition;bool stopped=false;std::thread worker;
public:
    DeviceGuard():worker([this] {
        auto start=Clock::now();std::unique_lock<std::mutex> lock(mutex);
        while(!condition.wait_for(lock,std::chrono::milliseconds(250),[this]{return stopped;})) {
            long rss=kb_field("/proc/self/status","VmRSS:");
            long available=kb_field("/proc/meminfo","MemAvailable:");
            const char* reason=nullptr;
            if(seconds(start)>=60) reason="wall_time_60s";
            else if(rss<0 || available<0) reason="guard_memory_metrics_unavailable";
            else if(rss>1536L*1024) reason="process_rss_1536MiB";
            else if(available<1024L*1024) reason="available_below_1024MiB";
            if(reason) {
                // Best-effort diagnostics must not delay exit on a blocked USB
                // pipe or another thread's stdio lock.
                char message[256];
                int length=std::snprintf(message,sizeof(message),"{\"guardStopped\":\"%s\",\"rssKiB\":%ld,\"availableKiB\":%ld}\n",reason,rss,available);
                int flags=fcntl(STDERR_FILENO,F_GETFL);
                if(length>0 && flags>=0 && fcntl(STDERR_FILENO,F_SETFL,flags|O_NONBLOCK)==0)
                    static_cast<void>(::write(STDERR_FILENO,message,std::min(static_cast<size_t>(length),sizeof(message)-1)));
                std::_Exit(124);
            }
        }
    }) {}
    ~DeviceGuard() { {std::lock_guard<std::mutex> lock(mutex);stopped=true;}condition.notify_all();worker.join(); }
};
#endif

struct PublicOpenCL {
    void* handle=nullptr;
    std::string device,vendor,version,extensions;
    cl_ulong global_bytes=0,max_allocation_bytes=0;
    size_t max_image_width=0,max_image_height=0;
    bool fp16=false;
    ~PublicOpenCL() { if(handle) dlclose(handle); }
    void query() {
        // Only the public library name. Never use a vendor path or namespace API.
        handle=dlopen("libOpenCL.so",RTLD_NOW|RTLD_LOCAL);
        if(!handle) throw std::runtime_error("Public libOpenCL.so unavailable; no private-driver or CPU fallback");
        auto platforms=reinterpret_cast<decltype(&clGetPlatformIDs)>(dlsym(handle,"clGetPlatformIDs"));
        auto devices=reinterpret_cast<decltype(&clGetDeviceIDs)>(dlsym(handle,"clGetDeviceIDs"));
        auto info=reinterpret_cast<decltype(&clGetDeviceInfo)>(dlsym(handle,"clGetDeviceInfo"));
        if(!platforms || !devices || !info) throw std::runtime_error("Public OpenCL entry points unavailable");
        cl_uint count=0;
        if(platforms(0,nullptr,&count)!=CL_SUCCESS || !count || count>64) throw std::runtime_error("No public OpenCL platform");
        std::vector<cl_platform_id> ids(count);
        if(platforms(count,ids.data(),nullptr)!=CL_SUCCESS) throw std::runtime_error("OpenCL platform query failed");
        cl_device_id selected=nullptr;
        for(auto platform:ids) if(devices(platform,CL_DEVICE_TYPE_GPU,1,&selected,nullptr)==CL_SUCCESS && selected) break;
        if(!selected) throw std::runtime_error("No public OpenCL GPU; refusing a CPU OpenCL device");
        auto text=[&](cl_device_info key) {
            size_t bytes=0;
            if(info(selected,key,0,nullptr,&bytes)!=CL_SUCCESS || bytes>65536) throw std::runtime_error("OpenCL device info query failed");
            std::vector<char> buffer(bytes+1,0);
            if(info(selected,key,bytes,buffer.data(),nullptr)!=CL_SUCCESS) throw std::runtime_error("OpenCL device info read failed");
            return std::string(buffer.data());
        };
        device=text(CL_DEVICE_NAME);vendor=text(CL_DEVICE_VENDOR);version=text(CL_DEVICE_VERSION);extensions=text(CL_DEVICE_EXTENSIONS);
        if(info(selected,CL_DEVICE_GLOBAL_MEM_SIZE,sizeof(global_bytes),&global_bytes,nullptr)!=CL_SUCCESS ||
           info(selected,CL_DEVICE_MAX_MEM_ALLOC_SIZE,sizeof(max_allocation_bytes),&max_allocation_bytes,nullptr)!=CL_SUCCESS)
            throw std::runtime_error("OpenCL memory capability query failed");
        fp16=(" "+extensions+" ").find(" cl_khr_fp16 ")!=std::string::npos;
        info(selected,CL_DEVICE_IMAGE2D_MAX_WIDTH,sizeof(max_image_width),&max_image_width,nullptr);
        info(selected,CL_DEVICE_IMAGE2D_MAX_HEIGHT,sizeof(max_image_height),&max_image_height,nullptr);
    }
    cl_mem_object_type memory_type(const MNN::Tensor* tensor,size_t& bytes,std::string& actual_gpu) const {
#if !defined(__APPLE__)
        auto info=reinterpret_cast<decltype(&clGetMemObjectInfo)>(dlsym(handle,"clGetMemObjectInfo"));
        if(!info || !tensor->deviceId()) throw std::runtime_error("Cannot query the actual public OpenCL memory object");
        // In this pinned MNN backend, normal tensor deviceId is a cl::Buffer*
        // or cl::Image2D*. Both inherit this public Khronos cl::Memory wrapper.
        // Read only its documented cl_mem handle; no private driver structure.
        static_assert(sizeof(cl::Memory)==sizeof(cl_mem),"Unexpected Khronos wrapper layout");
        const auto* memory=reinterpret_cast<const cl::Memory*>(tensor->deviceId());
        cl_mem object=(*memory)();cl_mem_object_type type=0;
        if(info(object,CL_MEM_TYPE,sizeof(type),&type,nullptr)!=CL_SUCCESS ||
           info(object,CL_MEM_SIZE,sizeof(bytes),&bytes,nullptr)!=CL_SUCCESS)
            throw std::runtime_error("Public clGetMemObjectInfo failed");
        auto context_info=reinterpret_cast<decltype(&clGetContextInfo)>(dlsym(handle,"clGetContextInfo"));
        auto device_info=reinterpret_cast<decltype(&clGetDeviceInfo)>(dlsym(handle,"clGetDeviceInfo"));
        cl_context context=nullptr;size_t device_bytes=0;
        if(!context_info || !device_info || info(object,CL_MEM_CONTEXT,sizeof(context),&context,nullptr)!=CL_SUCCESS ||
           context_info(context,CL_CONTEXT_DEVICES,0,nullptr,&device_bytes)!=CL_SUCCESS || device_bytes!=sizeof(cl_device_id))
            throw std::runtime_error("Expected a single public GPU in the actual buffer context");
        cl_device_id selected=nullptr;cl_device_type selected_type=0;
        if(context_info(context,CL_CONTEXT_DEVICES,sizeof(selected),&selected,nullptr)!=CL_SUCCESS ||
           device_info(selected,CL_DEVICE_TYPE,sizeof(selected_type),&selected_type,nullptr)!=CL_SUCCESS || !(selected_type&CL_DEVICE_TYPE_GPU))
            throw std::runtime_error("Actual OpenCL buffer context is not a verified GPU context");
        size_t name_bytes=0;
        if(device_info(selected,CL_DEVICE_NAME,0,nullptr,&name_bytes)!=CL_SUCCESS || name_bytes>65536)
            throw std::runtime_error("Cannot read the actual GPU name");
        std::vector<char> name(name_bytes+1,0);
        if(device_info(selected,CL_DEVICE_NAME,name_bytes,name.data(),nullptr)!=CL_SUCCESS)
            throw std::runtime_error("Cannot read the actual GPU name");
        actual_gpu=name.data();
        if(actual_gpu!=device) throw std::runtime_error("Actual buffer GPU differs from the capability admission device");
        return type;
#else
        (void)tensor;(void)bytes;(void)actual_gpu;
        throw std::runtime_error("Actual OpenCL object query is for the Android diagnostic build");
#endif
    }
    std::string json() const {
        std::ostringstream out;
        out << "{\"loader\":\"libOpenCL.so_public_name_only\",\"device\":" << quote(device)
            << ",\"vendor\":" << quote(vendor) << ",\"version\":" << quote(version)
            << ",\"fp16Extension\":" << (fp16?"true":"false") << ",\"deviceReportedGlobalBytes\":" << global_bytes
            << ",\"maxAllocationBytes\":" << max_allocation_bytes << ",\"globalBytesAreNotAvailableBudget\":true}";
        // The memory mode is explicitly BUFFER; these limits are diagnostic.
        auto value=out.str();value.pop_back();
        return value+",\"maxImage2DWidth\":"+std::to_string(max_image_width)+",\"maxImage2DHeight\":"+std::to_string(max_image_height)+"}";
    }
};
static void validate_model_inputs(const std::string& file) {
    std::ifstream input(file,std::ios::binary|std::ios::ate);
    if(!input || input.tellg()<=0 || input.tellg()>32*1024*1024) throw std::runtime_error("Rest model must be a small metadata file, not the complete ViT model");
    std::vector<uint8_t> bytes(static_cast<size_t>(input.tellg()));input.seekg(0);
    input.read(reinterpret_cast<char*>(bytes.data()),bytes.size());
    flatbuffers::Verifier verifier(bytes.data(),bytes.size());
    if(!input || !MNN::VerifyNetBuffer(verifier)) throw std::runtime_error("Invalid rest FlatBuffer");
    auto net=MNN::GetNet(bytes.data());std::map<std::string,std::vector<int>> found;
    for(auto op:*net->oplists()) if(op->type()==MNN::OpType_Input) {
        auto parameter=op->main_as_Input();
        if(!parameter || parameter->dtype()!=MNN::DataType_DT_FLOAT || !parameter->dims() || !op->outputIndexes() || op->outputIndexes()->size()!=1)
            throw std::runtime_error("Invalid rest input metadata");
        auto index=op->outputIndexes()->Get(0);
        auto name=net->tensorName()->Get(index)->str();
        found[name]=std::vector<int>(parameter->dims()->begin(),parameter->dims()->end());
    }
    if(found.size()!=kInputs.size()) throw std::runtime_error("Model must expose exactly the reviewed eight rest inputs");
    for(const auto& expected:kInputs) if(found[expected.name]!=expected.shape) throw std::runtime_error(std::string("Input contract mismatch: ")+expected.name);
}
static bool primary_compute(const std::string& type) {
    return type=="Convolution" || type=="Deconvolution" || type=="ConvolutionDepthwise" || type=="DeconvolutionDepthwise" || type=="MatMul";
}
struct BackendTrace {
    struct Operation { std::string name,type;int backend; };
    std::vector<Operation> plan;
    std::map<const MNN::OperatorInfo*,Operation> callbacks;
    std::map<int,size_t> planned,completed;
    std::map<std::string,size_t> cpu_types;
    size_t primary_gpu=0,primary_cpu=0,primary_completed_gpu=0,unknown=0;
    void inspect(MNN::Session* session) {
        // Pinned, read-only diagnostics: actual Execution backend, not a capability flag.
        // This does not modify the scheduler, layers, device namespaces or parameters.
        auto& pipeline=session->getPipelineInfo(0);
        for(const auto& cached:pipeline.second) {
            if(cached.type==MNN::Schedule::CONSTANT) continue;
            for(const auto& command:cached.executeBuffer.command) {
                if(!command->execution || !command->execution->backend()) { ++unknown;continue; }
                int backend=command->execution->backend()->type();
                std::string name=command->info?command->info->name():(command->op->name()?command->op->name()->str():"unnamed");
                std::string type=MNN::EnumNameOpType(command->op->type());
                Operation operation{name,type,backend};plan.push_back(operation);++planned[backend];
                if(command->info) callbacks[command->info.get()]=operation;
                if(backend==MNN_FORWARD_CPU || backend==MNN_FORWARD_CPU_EXTENSION) {
                    ++cpu_types[type];if(primary_compute(type)) ++primary_cpu;
                }
                if(backend==MNN_FORWARD_OPENCL && primary_compute(type)) ++primary_gpu;
            }
        }
    }
    void completed_operation(const MNN::OperatorInfo* info) {
        auto found=callbacks.find(info);
        if(found==callbacks.end()) { ++completed[-1];return; }
        const auto& operation=found->second;++completed[operation.backend];
        if(operation.backend==MNN_FORWARD_OPENCL && primary_compute(operation.type)) ++primary_completed_gpu;
    }
    std::string json() const {
        std::ostringstream out;out << "{\"coverageSource\":\"actual_created_Execution_backend_then_completed_callbacks\",\"plannedBackendCounts\":{";
        bool first=true;
        for(const auto& pair:planned) {if(!first) out << ',';first=false;out << quote(std::to_string(pair.first)) << ':' << pair.second;}
        out << "},\"completedBackendCounts\":{";first=true;
        for(const auto& pair:completed) {if(!first) out << ',';first=false;out << quote(std::to_string(pair.first)) << ':' << pair.second;}
        out << "},\"cpuOperationTypes\":{";first=true;
        for(const auto& pair:cpu_types) {if(!first) out << ',';first=false;out << quote(pair.first) << ':' << pair.second;}
        out << "},\"plannedGpuPrimaryOps\":" << primary_gpu << ",\"plannedCpuPrimaryOps\":" << primary_cpu
            << ",\"completedGpuPrimaryOps\":" << primary_completed_gpu << ",\"unknownPlannedBackendOps\":" << unknown
            << ",\"cpuReason\":\"MNN selected the CPU Execution for these operators; its exact driver/kernel rejection reason is not exposed by the public API\",\"operations\":[";
        first=true;
        for(const auto& operation:plan) {if(!first) out << ',';first=false;out << "{\"name\":" << quote(operation.name) << ",\"type\":" << quote(operation.type) << ",\"backend\":" << operation.backend << '}';}
        return out.str()+"]}";
    }
};
static void save_report(const std::string& directory,const std::string& status,const std::string& backend,int main_backend,
                        const std::string& capabilities,const BackendTrace& trace,float memory,double elapsed,
                        const std::string& outputs="{}",const std::string& observed_memory="null") {
    std::ofstream report(directory+"/report.json");
    if(!report) throw std::runtime_error("Cannot write report.json");
    report << std::setprecision(12) << "{\"status\":" << quote(status) << ",\"backendRequested\":" << quote(backend)
           << ",\"sessionMainBackend\":" << main_backend << ",\"mnnRevision\":" << quote(kMnnRevision)
           << ",\"openclCapabilities\":" << capabilities << ",\"executionTrace\":" << trace.json()
           << ",\"sessionMemoryMb\":" << memory << ",\"elapsedSeconds\":" << elapsed
           << ",\"peakRssBytes\":" << peak_rss_bytes() << ",\"processRssKiB\":" << kb_field("/proc/self/status","VmRSS:")
           << ",\"systemAvailableKiB\":" << kb_field("/proc/meminfo","MemAvailable:")
           << ",\"gpuMemoryMayBeMissingFromRss\":true,\"runs\":1,\"cpuThreads\":2,\"gaussians\":" << kGaussians
           << ",\"computePrecisionRequested\":" << quote(backend=="opencl"?"OpenCL_Precision_Normal_fp16_storage_fp32_compute":"CPU_high_precision_fp32")
           << ",\"pureFp16ArithmeticRequested\":false"
           << ",\"openclModeBitsRequested\":" << (backend=="opencl"?(MNN_GPU_TUNING_NONE|MNN_GPU_MEMORY_BUFFER):0)
           << ",\"openclMemoryModeRequested\":" << quote(backend=="opencl"?"buffer":"not_applicable")
           << ",\"actualOpenclInputMemoryObjects\":" << observed_memory
           << ",\"finiteDoesNotEstablishNumericalCorrectness\":true,\"outputs\":" << outputs << "}\n";
    if(!report) throw std::runtime_error("Report write failed");
}
static int probe(int argc,char** argv) {
    std::map<std::string,std::string> options;bool capabilities_only=false;
    for(int index=1;index<argc;++index) {
        std::string key=argv[index];
        if(key=="--help") {std::cout << "gemos-rest-probe --backend cpu|opencl [--capabilities-only] [--model FILE --weights FILE --inputs DIR --output DIR]\n";return 0;}
        if(key=="--capabilities-only") {capabilities_only=true;continue;}
        if(key!="--backend" && key!="--model" && key!="--weights" && key!="--inputs" && key!="--output") throw std::runtime_error("Unknown option: "+key);
        if(++index>=argc || options.count(key)) throw std::runtime_error("Missing or duplicate option value: "+key);
        options[key]=argv[index];
    }
    const std::string backend=options["--backend"];
    if(backend!="cpu" && backend!="opencl") throw std::runtime_error("Explicit --backend cpu|opencl is required");
    auto start=Clock::now();PublicOpenCL opencl;std::string capabilities="null";
    if(backend=="opencl") {phase("public_opencl_capabilities",start);opencl.query();capabilities=opencl.json();std::cout << "{\"opencl\":" << capabilities << "}" << std::endl;}
    if(capabilities_only) return 0;
    if(backend=="opencl" && !opencl.fp16) throw std::runtime_error("Public GPU has no cl_khr_fp16; refusing an unbudgeted FP32 GPU run");
    for(const char* key:{"--model","--weights","--inputs","--output"}) if(options[key].empty()) throw std::runtime_error(std::string("Required option: ")+key);
    std::filesystem::create_directories(options["--output"]);
    validate_model_inputs(options["--model"]);
    if(std::filesystem::file_size(options["--weights"])!=kWeightBytes) throw std::runtime_error("Wrong weight package size for this reviewed rest model");
    phase("load_rest_model",start);
    std::shared_ptr<MNN::Interpreter> model(MNN::Interpreter::createFromFile(options["--model"].c_str()),MNN::Interpreter::destroy);
    if(!model) throw std::runtime_error("Rest model load failed");
    model->setExternalFile(options["--weights"].c_str());
    model->setSessionMode(MNN::Interpreter::Session_Debug);
    model->setSessionMode(MNN::Interpreter::Session_Backend_Fix);
    model->setSessionMode(MNN::Interpreter::Session_Memory_Collect);
    model->setSessionHint(MNN::Interpreter::WINOGRAD_MEMORY_LEVEL,0);
    MNN::BackendConfig config;config.memory=MNN::BackendConfig::Memory_Low;
    // Pinned OpenCLRuntime.cpp: precision 0 defines FLOAT=half and
    // COMPUTE_FLOAT=float. Precision_Low (2) would also compute in half.
    config.precision=backend=="opencl"?MNN::BackendConfig::Precision_Normal:MNN::BackendConfig::Precision_High;
    MNN::ScheduleConfig schedule;
    schedule.type=backend=="opencl"?MNN_FORWARD_OPENCL:MNN_FORWARD_CPU;
    schedule.backupType=MNN_FORWARD_CPU;
    schedule.numThread=backend=="opencl"?(MNN_GPU_TUNING_NONE|MNN_GPU_MEMORY_BUFFER):2;
    schedule.backendConfig=&config;
    MNN::BackendConfig cpu_config;cpu_config.precision=MNN::BackendConfig::Precision_High;cpu_config.memory=MNN::BackendConfig::Memory_Low;
    MNN::ScheduleConfig cpu_schedule;cpu_schedule.type=MNN_FORWARD_CPU;cpu_schedule.numThread=2;cpu_schedule.backendConfig=&cpu_config;
    auto runtime=backend=="opencl"?MNN::Interpreter::createRuntime({schedule,cpu_schedule}):MNN::Interpreter::createRuntime({schedule});
    if(!runtime.first.count(schedule.type)) throw std::runtime_error("Requested MNN runtime unavailable; refusing whole-model CPU fallback");
    phase("create_rest_session",start);
    auto session=model->createSession(schedule,runtime);
    if(!session) throw std::runtime_error("Rest session creation failed");
    float memory=0;int resize=-1,actual[2]={-1,-1};
    model->getSessionInfo(session,MNN::Interpreter::MEMORY,&memory);
    model->getSessionInfo(session,MNN::Interpreter::RESIZE_STATUS,&resize);
    model->getSessionInfo(session,MNN::Interpreter::BACKENDS,actual);
    BackendTrace trace;trace.inspect(session);
    std::cout << "{\"sessionMainBackend\":" << actual[0] << ",\"sessionMemoryMb\":" << memory << ",\"resizeStatus\":" << resize << ",\"executionTrace\":" << trace.json() << "}" << std::endl;
    const bool unavailable=backend=="opencl" && (actual[0]!=MNN_FORWARD_OPENCL || !trace.primary_gpu || trace.primary_cpu || trace.unknown);
    if(resize!=0 || unavailable) {
        save_report(options["--output"],unavailable?"rejected_whole_or_primary_cpu_fallback":"session_resize_failed",backend,actual[0],capabilities,trace,memory,seconds(start));
        return 20;
    }
    std::string observed_memory="null";
    if(backend=="opencl") {
        std::ostringstream observations;observations << '[';bool first=true;size_t buffers=0;
        for(const auto& expected:kInputs) {
            auto tensor=model->getSessionInput(session,expected.name);
            const auto* selected=model->getBackend(session,tensor);
            if(!selected || selected->type()!=MNN_FORWARD_OPENCL || !tensor->deviceId()) continue;
            size_t bytes=0;std::string actual_gpu;auto type=opencl.memory_type(tensor,bytes,actual_gpu);
            if(type!=CL_MEM_OBJECT_BUFFER) throw std::runtime_error("Actual GPU input is an image object; refusing wide feature maps");
            ++buffers;if(!first) observations << ',';first=false;
            observations << "{\"input\":" << quote(expected.name) << ",\"clMemType\":" << type << ",\"mode\":\"buffer\",\"pooledObjectBytes\":" << bytes
                         << ",\"contextSingleGpu\":true,\"actualContextGpu\":" << quote(actual_gpu) << '}';
        }
        if(!buffers) throw std::runtime_error("Could not confirm BUFFER memory on an actual OpenCL input");
        observations << ']';observed_memory=observations.str();
        std::cout << "{\"openclModeBits\":" << schedule.numThread << ",\"actualOpenclInputMemoryObjects\":" << observed_memory << "}" << std::endl;
    }
    phase("load_eight_inputs",start);
    if(model->getSessionInputAll(session).size()!=kInputs.size()) throw std::runtime_error("Unexpected rest input count");
    for(const auto& expected:kInputs) {
        auto input=model->getSessionInput(session,expected.name);
        if(!input || input->shape()!=expected.shape) throw std::runtime_error(std::string("Runtime input shape mismatch: ")+expected.name);
        MNN::Tensor host(input,MNN::Tensor::CAFFE);
        if(host.getType().code!=halide_type_float || host.getType().bits!=32) throw std::runtime_error("Input host contract must be FP32");
        std::ifstream raw(options["--inputs"]+"/"+expected.name+".f32",std::ios::binary|std::ios::ate);
        if(!raw || raw.tellg()!=host.size()) throw std::runtime_error(std::string("Input byte count mismatch: ")+expected.name);
        raw.seekg(0);raw.read(reinterpret_cast<char*>(host.host<float>()),host.size());
        if(!raw) throw std::runtime_error("Input read failed");
        for(int index=0;index<host.elementSize();++index) if(!std::isfinite(host.host<float>()[index])) throw std::runtime_error("Nonfinite input");
        if(!input->copyFromHostTensor(&host)) throw std::runtime_error("FP32 host-to-backend input copy failed");
    }
    model->releaseModel();phase("run_once",start);size_t started_ops=0;
    auto before=[&](const std::vector<MNN::Tensor*>&,const MNN::OperatorInfo* info) {
        if(started_ops%25==0) std::cout << "{\"operatorIndex\":" << started_ops << ",\"name\":" << quote(info->name()) << ",\"seconds\":" << seconds(start) << "}" << std::endl;
        ++started_ops;return true;
    };
    auto after=[&](const std::vector<MNN::Tensor*>&,const MNN::OperatorInfo* info) {trace.completed_operation(info);return true;};
    auto error=model->runSessionWithCallBackInfo(session,before,after,true);
    if(error!=MNN::NO_ERROR) {
        save_report(options["--output"],"inference_failed_"+std::to_string(error),backend,actual[0],capabilities,trace,memory,seconds(start),"{}",observed_memory);return 21;
    }
    phase("save_outputs",start);
    const std::map<std::string,int> expected_outputs{{"mean_vectors_ndc",3},{"singular_values_ndc",3},{"quaternions_ndc",4},{"colors",3},{"opacities",0}};
    if(model->getSessionOutputAll(session).size()!=expected_outputs.size()) throw std::runtime_error("Unexpected Gaussian output count");
    std::ostringstream outputs;outputs << std::setprecision(12) << '{';bool first=true;
    for(const auto& expected:expected_outputs) {
        auto tensor=model->getSessionOutput(session,expected.first.c_str());
        std::vector<int> shape=expected.second?std::vector<int>{1,static_cast<int>(kGaussians),expected.second}:std::vector<int>{1,static_cast<int>(kGaussians)};
        if(!tensor || tensor->shape()!=shape) throw std::runtime_error("Gaussian output shape mismatch: "+expected.first);
        MNN::Tensor host(tensor,MNN::Tensor::CAFFE);
        if(host.getType().code!=halide_type_float || host.getType().bits!=32 || !tensor->copyToHostTensor(&host)) throw std::runtime_error("Output must copy to FP32 host storage");
        double sum=0,square=0;float minimum=INFINITY,maximum=-INFINITY;
        for(int index=0;index<host.elementSize();++index) {
            float value=host.host<float>()[index];if(!std::isfinite(value)) throw std::runtime_error("Nonfinite Gaussian output: "+expected.first);
            sum+=value;square+=static_cast<double>(value)*value;minimum=std::min(minimum,value);maximum=std::max(maximum,value);
        }
        std::string filename=expected.first+".f32";
        std::ofstream raw(options["--output"]+"/"+filename,std::ios::binary|std::ios::trunc);
        raw.write(reinterpret_cast<const char*>(host.host<float>()),host.size());if(!raw) throw std::runtime_error("Gaussian output write failed");
        if(!first) outputs << ',';first=false;
        outputs << quote(expected.first) << ":{\"file\":" << quote(filename) << ",\"elements\":" << host.elementSize()
                << ",\"min\":" << minimum << ",\"max\":" << maximum << ",\"mean\":" << sum/host.elementSize() << ",\"rms\":" << std::sqrt(square/host.elementSize()) << '}';
    }
    outputs << '}';
    bool incomplete=backend=="opencl" && trace.primary_completed_gpu!=trace.primary_gpu;
    std::string status=incomplete?"incomplete_execution_backend_trace":(backend=="cpu"?"finite_cpu_outputs":(trace.cpu_types.empty()?"finite_opencl_outputs":"finite_hybrid_opencl_cpu_outputs"));
    save_report(options["--output"],status,backend,actual[0],capabilities,trace,memory,seconds(start),outputs.str(),observed_memory);
    phase("complete",start);return incomplete?22:0;
}
int main(int argc,char** argv) {
    std::cout << "{\"probePid\":" << static_cast<long>(getpid()) << "}" << std::endl;
#ifdef __ANDROID__
    DeviceGuard guard;
#endif
    try {return probe(argc,argv);}
    catch(const std::exception& error) {std::cerr << "{\"error\":" << quote(error.what()) << "}" << std::endl;return 1;}
}
