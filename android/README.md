# Gemos Still — native Android experiment

This is a native Android/ONNX Runtime test, not a WebView wrapper. Requires ARM64 Android 9+. Download about 1.31 GB of research-licensed SHARP weights. Photos stay local. CPU and NNAPI are experimental: model operator support, memory and speed must be verified on a physical device. Successful compilation is not proof that SHARP runs on every phone.

Build: JDK 17, Android SDK 35, Gradle 8.9; `gradle -p android assembleDebug`. The GitHub Actions workflow produces an installable debug-signed APK. This is not a Play Store release. Keep the app foreground during inference. The UI reports the last interrupted stage after process death. Export the diagnostic log if generation fails. A successful run exports `.still` for the web viewer; this first version does not reproduce the 3D terminal UI.

这是原生推理验证版，非网页套壳。Android 9+、ARM64。首次下载约 1.31 GB 模型；照片本地处理。CPU/系统加速能否支持完整 SHARP，需要真机验证。请保持前台；中断后会显示最后步骤。成功可导出 `.still`，使用网页查看器打开。未在此首版中移植完整 3D 展示界面。模型仅限其许可证允许的研究用途，见 MODEL-LICENSE.txt。
