# Browser-local SHARP experiment

This path runs SHARP on the visitor's GPU, with no inference API or photo upload. The website hosts the application and ONNX Runtime; fixed-revision community FP16 model files are fetched from Hugging Face. First load is about 1.31 GB, cached with OPFS when available. A WebGPU adapter with shader-f16 is required. There is deliberately no server or WASM inference fallback.

The Worker owns model download, the GPU session, input preprocessing, covariance transforms, subject framing and output packing. It is terminated on completion/cancel/error to release model memory. The 1536-square input uses a 30 mm equivalent focal estimate when no camera data is available. Compared with native SHARP, browser canvas resampling, FP16 precision and the focal estimate may change results. Current framing uses near/contrast-weighted sampled bounds and bounded ray-depth compression.

Photos, settings and generated model buffers are saved in IndexedDB on the current origin. A .still file packages photo, settings and model for browser-local export/import. Browser data can be evicted: exported files are the durable backup. The .still format is data, not executable content.

Model provenance and restrictions are in licenses/NOTICE.txt and licenses/APPLE-SHARP.txt. This is a non-commercial research experiment, not an unrestricted commercial model service.

Tests: node neon-cube/tests/test_browser_inference.mjs (from parent project). Test the real model through New Memory → example photo in a supported desktop browser before deploying. Demo rendering alone does not validate inference.

The deployed entry loads browser-inference/ui.js. The Python localhost application's existing memory-ui.js is retained separately and still uses the native Python engine.
