# Browser-local SHARP experiment

This path runs SHARP on the visitor's GPU, with no inference API or photo upload. The website hosts the application and ONNX Runtime; fixed-revision community FP16 model files are fetched from Hugging Face. First load is about 1.31 GB, cached with OPFS when available. A WebGPU adapter with shader-f16 is required. Desktop keeps this original pipeline. There is no server inference.

The Worker owns model download, the GPU session, input preprocessing, covariance transforms, subject framing and output packing. It is terminated on completion/cancel/error to release model memory. The 1536-square input uses a 30 mm equivalent focal estimate when no camera data is available. Compared with native SHARP, browser canvas resampling, FP16 precision and the focal estimate may change results. Current framing uses near/contrast-weighted sampled bounds and bounded ray-depth compression.

Photos, settings and generated model buffers are saved in IndexedDB on the current origin. A .still file packages photo, settings and model for browser-local export/import. Browser data can be evicted: exported files are the durable backup. The .still format is data, not executable content.

Model provenance and restrictions are in licenses/NOTICE.txt and licenses/APPLE-SHARP.txt. This is a non-commercial research experiment, not an unrestricted commercial model service.

Tests: node neon-cube/tests/test_browser_inference.mjs (from parent project). Test the real model through New Memory → example photo in a supported desktop browser before deploying. Demo rendering alone does not validate inference.

The deployed entry loads browser-inference/ui.js. The Python localhost application's existing memory-ui.js is retained separately and still uses the native Python engine.

## Mobile Lite (experimental)

Android/iOS/iPad browsers are routed separately to `mobile-worker.js`: CPU WebAssembly, 256×256 FP32 input, INT8 constant-weight MatMul, up to 32,768 Gaussians. Desktop still uses the original 1536 FP16 WebGPU model. Mobile does not require WebGPU or shader-f16.

Selecting a photo automatically downloads the Lite model from the same origin at `/models/gemos-still-lite-v1/`. Streaming downloads show byte progress, retry transient failures and resume completed partial writes using HTTP Range. Sizes and SHA-256 are checked before a cache is marked ready. The server only distributes fixed model files: no visitor photos or results are uploaded. Manual import of the same `Gemos-Still-Lite-256.gemosmodel` published with Android v0.4 remains optional under Model & Cache. The ZIP_STORED package contains only `lite256int8.onnx` and `lite256int8.onnx.data`. Sizes and SHA-256 are pinned in `mobile-model.js`; incremental verification and OPFS writes avoid holding an extra 809 MB package buffer in JavaScript. Cache readiness is committed only after both files pass verification. Where OPFS is unavailable, the selected File remains available for this page session. Import errors never mark a partial cache as ready. No photos or generated scenes are uploaded.

The WASM runtime is ONNX Runtime Web 1.24.2 (MIT), single-threaded to work without cross-origin isolation. Model sessions disable weight prepacking and enable memory pattern/CPU arena. Rendering pauses during inference and the Worker is terminated after completion/cancellation/error. The lower-resolution derivative loses detail and is not an official Apple mobile model. Native APK success does not guarantee browser success; memory limits and browser kernels vary. Use the APK and import its `.still` output if the mobile browser cannot finish.

Validation: full model import, checksum, OPFS reuse after reload, CPU inference, Gaussian packing and UI reveal completed in local Chrome with a mobile viewport/user agent. Desktop opened its existing GPU path and made no CPU runtime requests. Actual Vivo browser stability still requires a device test. `tools/test-mobile-browser.mjs` reproduces the browser test with a locally installed Playwright and model package.

### Serving mobile model files

Serve `lite256int8.onnx` and `lite256int8.onnx.data` under `/models/gemos-still-lite-v1/` with Content-Length, HTTP Range, immutable caching, and a binary Content-Type. Pin files to the hashes/sizes in `mobile-model.js`. Keep these ~809 MB weights outside the application build and Git; see the authorized release above. On gemosdodo.art the files live in a dedicated static directory exposed by a narrowly scoped Nginx location. Deployments do not replace or recopy the model directory. OPFS is required for automatic download; manual import is a session-only alternative where OPFS is unavailable.
