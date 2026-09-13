# Android SHARP memory investigation — 2026-09-13

Developer host: ARM64 macOS, 48 GiB RAM, ONNX Runtime 1.24.2. These are process RSS samples, not Android PSS/nativeHeap measurements and not a guarantee for vivo V2509A. One model runs at a time. CPU uses two intra-op threads, one inter-op thread, sequential execution.

## Findings

The exported FP16 graph has 1,307,950,020 bytes of FP16 initializers. ORT CPU graph optimization on this host produces 2,634,373,580 bytes of FP32 initializers. This is consistent with, but does not prove the complete cause of, the phone's roughly 2.63 GB native heap at inference start. Disabling prepacking reduced developer initialization RSS from about 3,608 MiB to 2,814 MiB, but did not solve inference memory.

| Experiment | Peak sampled RSS MiB | Completed | Input |
|---|---:|---|---|
| Original graph, arena off | at least 18,303 | stopped at safety limit | constant 1536 |
| Original graph, arena on, no prepack, node profiling | 22,728 | yes | constant 1536 |
| Stream attention, arena on, no prepack | 15,007 | yes | constant 1536 |
| Full-size FP32 + dynamic MatMul INT8 | 22,978 | yes | constant 1536 |
| Internal 512 + INT8 | 3,982 | yes | constant 512 |
| Internal 256, no quantization | 3,462 | yes | station photo |
| Internal 256 + INT8 | 1,739 | yes | station photo |

Early runs sampled every 250 ms, final 256 runs every 25 ms. Node profiling adds instrumentation; do not interpret comparisons as a precise Android speed/memory benchmark. Outputs were finite. 256 INT8 generates 32,768 Gaussians, versus 1,179,648 for original input.

## Quality limitations

Internal ViT image size is 64 instead of 384; learned positional embeddings are resampled using timm set_input_size, the pyramid uses non-overlapping windows, internal full image is 256. This is an untrained lower-resolution adaptation, not an official Apple mobile model. Constant-weight MatMul is dynamically quantized per channel to INT8.

On the station photo, compared with the unquantized *256* graph: mean-vector MAE 0.2216 (reference absolute mean 2.8127), scale MAE 0.01244, color MAE 0.00621, opacity MAE 0.04820. These differences are material, not a claim of equivalent geometry/quality. No representative image-set or physical Android validation yet.

The streamed attention prototype passes independent numerical tests (batches 2,3,7; maximum difference 0 on this host), but by itself does not make the full model mobile-ready. It is not used by the 256 APK.

## Android experiment

APK 0.4 imports a separately supplied .gemosmodel ZIP containing exactly two versioned files. Size limits, SHA-256 checks and fixed entry names prevent partial or substituted model imports. No online weight publication is included in this change. The large derived model package remains local pending explicit publication approval. Android retains exit-reason and memory diagnostics. Build checks do not substitute for real-device inference.
