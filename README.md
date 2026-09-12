<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

![Gemos Still — a moment, kept.](docs/cover.png)

# Gemos Still

**Keep a moment inside a little world.**

A browser-based memory terminal by **Gemosdodo**. Turn a photograph into a Gaussian 3D scene, place it inside a glass retro computer, and share its construction as a cinematic film.

[Try the live experience](https://gemosdodo.art/vibecoding-projects/still-memory-box/index.html) · [Gemosdodo](https://gemosdodo.art) · [简体中文](README.zh-CN.md)

## Experience

- A volumetric, colorful particle tide while the terminal waits for a photograph.
- A personalized photo card slides into the drive; turbulent particles assemble the scene from bottom to top.
- A cubic glass enclosure, detailed keyboard, baked diffuse lighting and subtle ABS plastic grain.
- Touch rotation and pinch zoom, smooth camera transitions, adjustable framing and depth.
- Browser-local photo processing and a portable `.still` memory file.
- A 52-second film with construction shots, an orbiting close-up, an animated nine-scene gallery, original synthesized music and a closing brand card.
- Landscape and portrait MP4 exports: **1080p30, 1080p60, 4K30 and 4K60**, rendered frame by frame.

## Run locally

Clone this repository, then serve its root:

```sh
git clone https://github.com/duoduoaiduoduo/gemos-still.git
cd gemos-still
python3 -m http.server 8765 --bind 127.0.0.1
```

Open **http://127.0.0.1:8765**. No build step or Python inference server is required. Do not open `index.html` using `file://`.

Start with the built-in example, or open a previously exported memory. To create a memory, choose **新建记忆** and select a photograph. After creation, the adjustment panel provides saving, replay and **制作展示影片**. The interface is currently Chinese; these project documents are bilingual.

## Requirements and practical limits

| Operation | Requirement |
| --- | --- |
| View the terminal and an existing memory | WebGL 2 browser; touch controls supported |
| Generate from a photo | WebGPU with `shader-f16`, enough GPU memory, access to the external model host |
| Export an MP4 | WebCodecs with supported H.264 encoding; AAC for music |
| Publish the site | Static hosting over HTTPS |

First inference downloads approximately **1.31 GB** of community-converted SHARP weights from Hugging Face. OPFS caches them when available. A phone having a GPU does not guarantee that its browser exposes the required features or has enough memory. There is no server inference or WASM fallback. Viewing the example does not test inference compatibility.

Single-image reconstruction cannot recover unseen surfaces. Large rotations may reveal holes, stretched areas or missing geometry. Depth fitting and subject weighting are geometric heuristics, not semantic object recognition. Glass uses real-time approximations; this is not full-scene path tracing.

4K60 uses a real 3840 × 2160 render target (2160 × 3840 in portrait) and 3,120 frames for the full film. Rendering may take longer than playback and use substantial memory. Unsupported encoders report an error instead of silently lowering quality. The nine-scene gallery is a pre-rendered 2304-square atlas; a 4K export does not make each gallery tile native 4K.

## Privacy and storage

Photos and generated memories are processed on the visitor's device and stored in IndexedDB for the current origin. They are not uploaded to an inference server. App assets come from your static host; model downloads contact Hugging Face and expose normal connection metadata, but not the photograph. Browser storage may be cleared or evicted: export `.still` files for durable backups. Video generation also stays local.

## Deployment, development and licensing

- [Deployment and architecture](docs/DEVELOPMENT.md)
- [Third-party notices and asset scope](THIRD_PARTY_NOTICES.md)
- [Application source license](LICENSE)

The independently authored application source is MIT-licensed. **SHARP model use remains restricted by Apple's research model license**; the application's MIT license does not remove that restriction. Model weights are downloaded separately and are not included here. Branded illustrations, photographs and rendered demo media are not covered by the source-code MIT grant; see the notices before reusing them.

## Credits

Created by **Gemosdodo**. Built with Three.js, Apple SHARP (community ONNX conversion), ONNX Runtime Web, Mediabunny and Blender Cycles. This project is independent and is not endorsed by those projects or vendors.
