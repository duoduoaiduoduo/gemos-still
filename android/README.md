# Gemos Still for Android — native preview

A native Android memory terminal with on-device photo-to-3D generation and an OpenGL ES 3 Gaussian viewer. Android 9+, ARM64 and GLES 3 are required. This preview uses package `art.gemosdodo.still.mobile`, so it can coexist with the earlier inference experiment without replacing its files.

Choose a photo, then generate. The first generation offers a roughly 809 MB model download from the project website, with measured download progress, resume and SHA-256 verification. The model remains cached for offline use. Importing the separate `.gemosmodel` package is also available from the more menu. Photos and inference stay on the device.

The adapted SHARP model runs internally at 256 × 256 with INT8 constant weights and sequential CPU execution. It produces up to 32,768 Gaussians, with less detail than the desktop model. It is an experimental derivative, not an official Apple mobile model. See [measurements and limitations](MEMORY-STUDY.md) and the [model license](MODEL-LICENSE.txt).

The original terminal housing, keyboard, stickers and baked textures are reused. The native viewer projects anisotropic Gaussian covariances, sorts transparency by camera depth, applies housing occlusion and clips the splats to the chamber. Drag to orbit, pinch to zoom, or use Front and Reset. Rendering pauses while inference runs and when the app is in the background. Completed memories can be saved as `.still` or shared through the Android share sheet. Existing `.still` files can be opened; the mobile viewer caps imported memories at 250,000 displayed particles.

The previous inference-only build completed one real-photo CPU run on a vivo V2602A / 12 GB on 2026-10-15: sampled peak PSS about 1.46 GiB, peak battery temperature 31.6 °C, with no crash in that run. This does not establish stability on every photo or phone. The native viewer is verified separately using the bounded instrumentation check in this branch; it never starts inference automatically.

## Original-model research path (0.6)

The branch also contains an opt-in original SHARP pipeline. It preserves the original 1536 input, 35 overlapping 384-pixel transformer patches, learned weights and 1,179,648 raw Gaussian outputs. It is selected only when all eight locally provisioned, fixed-hash model files and the three installed native programs are present. These weights are not included in the APK or automatically published. If the original path fails, it reports the failure and does not substitute the 256 model.

The patch encoder, global image encoder, depth decoder and Gaussian decoder run sequentially in separate processes. Each process exits before the next loads its weights. Vulkan handles the transformer; its original LayerNorm operators use CPU to avoid observed FP16 squared-workspace overflow. The two decoder stages use public OpenCL buffer storage, FP32 computation and FP16 storage. The Android manifest declares optional public `libOpenCL.so`; no private driver path or system modification is used. Executables are packaged in the APK and extracted to the installed read-only native library directory, never downloaded or executed from writable model storage.

Admission requires 2304 MiB available memory. Runtime guards stop the current stage if available memory falls below 1024 MiB, RSS reaches 1536 MiB, battery temperature reaches 39 °C, thermal status reaches moderate, or its time limit is exceeded. Cancellation affects only this app's current child process. These conservative checks are experimental safeguards, not a stability guarantee.

The display preparation follows the desktop ray-depth-contrast-v2 transform and covariance Jacobian. Its maximum 250,000 display records are separate from the original 1,179,648-point inference output. The mobile viewer uses the same 250,000-record budget without a second reduction to 100,000.

**Validation status:** a single original transformer patch passed numerical comparison on the connected 12 GB vivo, with final-output normalized RMSE about 0.42% and sampled peak RSS about 0.76 GiB. The 35-patch phone experiment completed in 53.6 seconds with peak RSS about 0.91 GiB, but outputs after the first patch failed numerical validation. Full original-model generation is therefore not yet verified or ready for general use. Desktop split-decoder checks do not establish Android correctness. No representative phone/image-set claim is made.

Build with JDK 17, Android SDK 35 and Gradle 8.9. GitHub Actions builds both pinned native runtime bundles and the app/instrumentation APKs, runs JVM tests and Android lint, and verifies the APK signature, installed-code entries, license notices and 16 KiB ELF alignment. A local Gradle-only build without native bundles retains the lightweight route. The experimental keystore is cached between CI runs; cache retention is not production signing. Do not uninstall older experiments merely to test this separate package. The preview does not request storage-wide, camera, contact, root or system-setting access.

## 中文

这是原生 Android 预览版：在手机本机把照片生成 3D 记忆，并直接在原版复古电脑盒子中查看。单指旋转、双指缩放，支持正面、复位、示例、打开、保存和系统分享。

首次生成会提示下载约 809 MB 的轻量模型，提供下载进度、断点续传与完整性校验；缓存后可离线使用，也可以从更多菜单导入离线模型包。照片不上传。内部使用 256 × 256 INT8 CPU 模型，最多约 3.3 万个高斯粒子，细节低于电脑版。

新版包名为 `art.gemosdodo.still.mobile`，与旧实验版并存，不覆盖旧版数据。当前只验证了有限的真机样本，仍属于预览版。生成时请保持前台；可取消生成，失败前已保存的记忆会保留。模型遵循上方所列研究许可证。

原版模型实验流程（0.6）保留原尺寸、原权重与 35 个重叠图块，分阶段释放内存。它需要开发者在本机预置并校验模型，尚无公开下载入口；失败后不会降回轻量模型。当前手机的单图块已通过数值核验，35 图块连续执行仍发现结果差异，完整原版生成尚未验证，请勿把构建成功视为真机全流程成功。
