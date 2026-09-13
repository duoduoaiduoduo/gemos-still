# Gemos Still — native Android low-memory experiment

Android 9+, ARM64. Version 0.4 uses internal 256x256 SHARP adaptation with INT8 constant-weight MatMul and CPU inference. It generates 32,768 Gaussians and intentionally has less detail than the desktop model. This is an experimental derivative, not an official Apple mobile model. See [memory measurements and limitations](MEMORY-STUDY.md).

Install the APK, import `Gemos-Still-Lite-256.gemosmodel`, choose a photo and generate while keeping the app foreground. The model package is separate and remains local pending publication approval. Photos are processed locally. Successful output exports as .still for the existing web viewer. The full 3D viewer is not included.

Build with JDK17, SDK35, Gradle8.9 and the explicit debug keystore configured in the workflow. Android build/lint/signing checks run in GitHub Actions. Debug builds are not store releases. Previous versions used a lost ephemeral certificate; upgrading may require export/backup followed by uninstall, which removes previous cache. Future experimental keys are explicitly stored/cached; cache retention is not production signing.

## 中文

这是内部 256×256、约 3.3 万粒子的原生低内存实验版，细节低于桌面版。开发机真实照片测试峰值约 1.70 GiB，尚未通过 vivo 真机验证。先安装 APK，再导入单独提供的 .gemosmodel 模型包，选择照片并保持应用前台。成功后保存 .still，在网页中查看。闪退后重新打开并导出诊断日志。

旧版签名无法恢复，升级可能需要卸载旧版；请先导出记忆和日志。模型遵循 [Apple SHARP 研究许可证](MODEL-LICENSE.txt)。修改后模型权重尚未公开发布，等待单独授权。
