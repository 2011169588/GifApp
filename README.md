# GifApp — 合成并切分 GIF

长图分段、动图拆帧、视频转 GIF，每段独立保存。

![Android](https://img.shields.io/badge/Android-34-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.1-orange) ![Compose](https://img.shields.io/badge/Compose-BOM_2024.12-blue)

## 功能

| 功能 | 说明 |
|------|------|
| **多图合成 GIF** | 逐帧导入图片，合成为动画 GIF |
| **GIF 动图分割** | 导入 GIF 动图，自动拆帧后分段切割 |
| **视频转 GIF** | 提取视频帧，分段导出动效 GIF |
| **分段切割** | 自定义段数、间距，每段独立保存分享 |
| **并行合成** | 1\~6 段同时处理，自由平衡速度与内存 |
| **无损优化** | 帧差算法只存变化部分，画质不变文件更小 |
| **通知栏进度** | 前台服务保活 + WakeLock，锁屏导出不中断 |

## 截图

| 首页 | 编辑 | 导出设置 |
|------|------|---------|
| ![home](screenshots/home.png) | ![editor](screenshots/editor.png) | ![settings](screenshots/settings.png) |

## 构建

```bash
# 克隆
git clone https://github.com/2011169588/GifApp.git
cd GifApp

# 构建 debug APK
./gradlew assembleDebug

# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

需要 Android Studio Hedgehog (2024.1.1+) 或等效环境。

## 技术栈

- **语言:** Kotlin 2.1
- **UI:** Jetpack Compose + Material 3
- **架构:** MVVM (ViewModel + StateFlow)
- **GIF 编码:** FFmpegKit (palettegen + paletteuse)
- **前台服务:** 通知栏进度 + WakeLock 保活

## 更新公告

App 启动时从远程 Gist 拉取公告，无需发版即可推送更新信息。  
公告内容维护在 [GitHub Gist](https://gist.github.com/2011169588/9e80ff69c80c3e1aa407563468ae14b5)。

## 开源许可

| 库 | 许可 |
|----|------|
| [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) (v6.0 full) | GPLv3 |
| Jetpack Compose / Material3 / AndroidX | Apache 2.0 |

FFmpegKit 是 GPLv3 许可的完整 FFmpeg 发行版，包含 GPL 协议的编解码器。  
如果您对 GPL 合规有特定需求，可替换为 `ffmpeg-kit-min-gpl` 或 `ffmpeg-kit-min` 变体。

## License

MIT
