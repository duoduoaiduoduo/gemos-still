# Gemos Still for Android — native preview

A native Android memory terminal with on-device photo-to-3D generation and an OpenGL ES 3 Gaussian viewer. Android 9+, ARM64 and GLES 3 are required. This preview uses package `art.gemosdodo.still.mobile`, so it can coexist with the earlier inference experiment without replacing its files.

Choose a photo, then generate. The first generation offers a roughly 809 MB model download from the project website, with measured download progress, resume and SHA-256 verification. The model remains cached for offline use. Importing the separate `.gemosmodel` package is also available from the more menu. Photos and inference stay on the device.

The adapted SHARP model runs internally at 256 × 256 with INT8 constant weights and sequential CPU execution. It produces up to 32,768 Gaussians, with less detail than the desktop model. It is an experimental derivative, not an official Apple mobile model. See [measurements and limitations](MEMORY-STUDY.md) and the [model license](MODEL-LICENSE.txt).

The original terminal housing, keyboard, stickers and baked textures are reused. The native viewer projects anisotropic Gaussian covariances, sorts transparency by camera depth, applies housing occlusion and clips the splats to the chamber. Drag to orbit, pinch to zoom, or use Front and Reset. Rendering pauses while inference runs and when the app is in the background. Completed memories can be saved as `.still` or shared through the Android share sheet. Existing `.still` files can be opened; the mobile viewer caps imported memories at 100,000 displayed particles.

The previous inference-only build completed one real-photo CPU run on a vivo V2602A / 12 GB on 2026-10-15: sampled peak PSS about 1.46 GiB, peak battery temperature 31.6 °C, with no crash in that run. This does not establish stability on every photo or phone. The native viewer is verified separately using the bounded instrumentation check in this branch; it never starts inference automatically.

Build with JDK 17, Android SDK 35 and Gradle 8.9. GitHub Actions builds both the app and its instrumentation APK, runs Android lint and verifies the APK signature. The experimental keystore is cached between CI runs; cache retention is not production signing. Do not uninstall older experiments merely to test this separate package. The preview does not request storage-wide, camera, contact, root or system-setting access.

## 中文

这是原生 Android 预览版：在手机本机把照片生成 3D 记忆，并直接在原版复古电脑盒子中查看。单指旋转、双指缩放，支持正面、复位、示例、打开、保存和系统分享。

首次生成会提示下载约 809 MB 的轻量模型，提供下载进度、断点续传与完整性校验；缓存后可离线使用，也可以从更多菜单导入离线模型包。照片不上传。内部使用 256 × 256 INT8 CPU 模型，最多约 3.3 万个高斯粒子，细节低于电脑版。

新版包名为 `art.gemosdodo.still.mobile`，与旧实验版并存，不覆盖旧版数据。当前只验证了有限的真机样本，仍属于预览版。生成时请保持前台；可取消生成，失败前已保存的记忆会保留。模型遵循上方所列研究许可证。
