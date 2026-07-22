# GifApp ✂️ — 合成并切分 GIF

> 长图分段、动图拆帧、视频转 GIF，每段独立保存分享。

![Android](https://img.shields.io/badge/Android-35-green?logo=android) ![Kotlin](https://img.shields.io/badge/Kotlin-2.1-orange?logo=kotlin) ![Compose](https://img.shields.io/badge/Compose-BOM_2024.12-blue?logo=jetpackcompose) ![License](https://img.shields.io/badge/License-MIT-yellow)

一款 Android 端 GIF 分段处理工具。支持多图合成、动图拆帧、视频转 GIF，按自定义段数垂直切分，每段独立保存。

---

## ✨ 功能一览

| 功能 | 说明 |
|------|------|
| 🖼️ **多图合成 GIF** | 逐帧导入图片，每张作为一帧，合成动画 GIF |
| 🎞️ **GIF 动图分割** | 导入 GIF 动图，FFmpeg 精确拆帧后分段切割 |
| 🎬 **视频转 GIF** | 提取视频帧，按帧率控制输出动效 GIF |
| ✂️ **分段切割** | 自定义段数（1\~20）、间距比例，垂直切分 |
| ⚡ **并行合成** | 1\~6 段同时处理，自由平衡速度与内存 |
| 🎨 **调色板控制** | 色彩数 32\~256、Floyd/Bayer 抖动、全局最优/帧差模式 |
| 📦 **无损优化** | 帧差算法（`+offsetting`）、透明色优化、帧去重 |
| 📡 **在线公告** | 启动时从 Gist 拉取公告，内容更新自动重弹 |
| 🔄 **自动更新** | 检测 GitHub Release，有新版本时图标红点提示 |
| 🔔 **通知栏进度** | 前台 Service + WakeLock 保活，锁屏导出不中断 |

## 📱 截图

| 首页 | 编辑 | 导出设置 |
|------|------|---------|
| ![home](screenshots/home.png) | ![editor](screenshots/editor.png) | ![settings](screenshots/settings.png) |

## 🚀 快速开始

```bash
git clone https://github.com/2011169588/GifApp.git
cd GifApp
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

需要 Android Studio Hedgehog (2024.1.1+) 或等效环境。

## 📦 下载

从 [Releases](https://github.com/2011169588/GifApp/releases) 页面下载最新 APK，或自行构建。

## 🧱 技术栈

| 层 | 技术 |
|----|------|
| **语言** | Kotlin 2.1 |
| **UI** | Jetpack Compose + Material 3 |
| **架构** | MVVM (ViewModel + StateFlow) |
| **动图编码** | FFmpegKit 6.0 (palettegen + paletteuse) |
| **后台服务** | Android Foreground Service + WakeLock |
| **更新检测** | GitHub Releases API |
| **公告推送** | Gist JSON 远程拉取 |

## 📡 公告系统

无需发版即可推送更新信息。公告内容维护在 [GitHub Gist](https://gist.github.com/2011169588/9e80ff69c80c3e1aa407563468ae14b5)，支持 Markdown、超链接、已读记忆、内容变更重弹。

## ⚖️ 开源许可

| 库 | 许可 |
|----|------|
| [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) (v6.0 full) | GPLv3 |
| Jetpack Compose / Material3 / AndroidX | Apache 2.0 |

FFmpegKit 是 GPLv3 许可的完整 FFmpeg 发行版。  
如需替换 GPL 编解码器，可使用 `ffmpeg-kit-min-gpl` 或 `ffmpeg-kit-min`。

本项目源码采用 **MIT** 许可。
