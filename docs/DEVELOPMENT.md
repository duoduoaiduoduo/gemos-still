# Deployment & development / 部署与开发

[English overview](../README.md) · [中文概览](../README.zh-CN.md)

## Static hosting / 静态部署

Upload the repository's application files to a static host, preserving relative paths. No backend, API key or server GPU is required. Production must use HTTPS for WebGPU, workers and local storage APIs. `localhost` is suitable for development.

将应用文件按原目录结构部署到静态托管即可，不需要后端、API 密钥或服务器 GPU。线上需要 HTTPS，本地开发可以使用 localhost。

Serve `.js` as JavaScript, `.wasm` as `application/wasm`, and `.mp4` as `video/mp4`; avoid rewriting missing assets to your site's HTML. The encoder intentionally uses a `.js` extension because some servers serve `.mjs` with an invalid module MIME type. Keep HTML revalidated and change module version URLs when deploying changes. Do not permanently cache the HTML entry.

确保 JS、WASM 和 MP4 的响应类型正确；缺失资源不要返回首页 HTML。编码器使用 `.js` 避免部分服务器错误处理 `.mjs`。入口 HTML 应允许重新验证缓存，修改模块后更新版本 URL。

The existing live domain is maintained separately. Pushing to this open-source repository does not deploy to the author's production site.
独立开源仓库与作者线上站点分开维护，推送本仓库不会自动更新作者网站。

## Source map / 代码导航

| Files | Responsibility / 职责 |
| --- | --- |
| `main.js`, `studio.js` | Scene, camera, rendering / 场景、镜头、渲染 |
| `computer.js`, `housing.js` | Terminal geometry / 主机建模 |
| `baked-lighting.js`, `baked/` | Baked diffuse lighting and texture / 烘焙光照与纹理 |
| `tide.js`, `ceremony.js`, `desk-field.js` | Particles, card and floating objects / 粒子、卡片与悬浮物 |
| `gaussian.js`, `sort-worker.js` | Gaussian projection and sorting / 高斯投影与排序 |
| `browser-inference/` | Model download, GPU inference, local library / 模型下载、推理、本地记忆库 |
| `film*.js` | Timeline, audio, gallery, frame export / 时间线、配乐、九宫格与导出 |

## Check / 检查

With Node.js 20 or later:

```sh
npm test
```

This checks film presets and timeline only. Also verify in a real browser: idle particles, example loading, memory import/export, touch framing, film dialog, cancellation, and an actual MP4 export. Photo inference requires a supported GPU and a full model download; it is not covered by this small test.

自动测试仅验证影片参数和时间线。仍需在真实浏览器检查待机、示例、记忆导入导出、触控、影片设置、取消和实际导出。照片推理需要完整下载模型及支持的 GPU，不包含在这个小测试中。

## Bake / 烘焙

Prebuilt textures are included. Geometry changes may invalidate baked UVs. The `tools/` scripts preserve the Blender workflow: build housing, export bake geometry, run Cycles bake, and regenerate plastic texture if required. Use Blender's Python environment for Blender scripts. Run tools from the repository root and inspect their paths before regenerating assets. Do not reorder opaque mesh traversal without rebuilding the UV mapping.

仓库已包含烘焙资源。几何修改可能使烘焙 UV 失效，需重新生成机壳、导出几何并进行 Cycles 烘焙；Blender 脚本需在 Blender Python 环境中运行。不要在未重建 UV 映射时修改不透明网格的遍历顺序。

The bundled montage is a pre-rendered nine-scene demo, not nine concurrent inference tasks. Replacing it requires rendering your own scenes and retaining the atlas layout expected by `film-montage.js`.
九宫格是预渲染的演示素材，不会同时执行九个推理任务。替换时需要自行渲染，并保持 `film-montage.js` 使用的图集布局。
