# LX04Cast

**小米小爱触屏音箱 LX04 的极简 AirPlay / DLNA 音频接收器**

![platform](https://img.shields.io/badge/platform-Android%208.1%20%7C%20armeabi--v7a-blue)
![version](https://img.shields.io/badge/version-0.1.10-blueviolet)
![license](https://img.shields.io/badge/license-GPL--3.0--or--later-green)

- GitHub：https://github.com/zoujiayu/LX04Cast
- 下载：[Releases](https://github.com/zoujiayu/LX04Cast/releases)
- 包名：`com.plx.lx04cast`
- 目标设备：小米 LX04，Android 8.1，1GB RAM，800×480，armeabi-v7a

## 截图

<p align="center">
  <img src="docs/screenshots/playing.png" width="49%" alt="播放页" />
  <img src="docs/screenshots/control-bar.png" width="49%" alt="控制栏" />
</p>
<p align="center">
  <img src="docs/screenshots/lyrics.png" width="49%" alt="歌词页" />
  <img src="docs/screenshots/settings.png" width="49%" alt="设置页" />
</p>

## 小爱同学支持

- 语音控制：**“小爱同学，暂停播放 / 继续播放 / 退出播放”**
- 播放中保持 MediaSession；暂停后保留可恢复状态
- 通过 MediaSession、媒体按钮和音频焦点兜底，尽量兼容 Mico 不向第三方应用发送媒体命令的固件
- DLNA 与 AirPlay 均支持暂停/继续；AirPlay 上一首/下一首取决于发送端 DACP
- 系统的媒体键、媒体通知和播放页按钮同样可用

> 不同 Mico 固件的语音媒体路由不同；实际响应以设备为准。

## 功能

- **AirPlay 音频接收**：AirPlay 1，UxPlay/FairPlay 原生解码。
- **DLNA 音频接收**：UPnP AV MediaRenderer（SSDP、AVTransport、RenderingControl、GENA）。
- **两页播放界面**：176dp 封面、全屏模糊背景、毛玻璃控制栏、完整滚动歌词。
- **系统媒体控制**：MediaSession、MediaStyle 通知、媒体按钮；支持 MediaSession 和音频焦点兜底。
- **歌词**：优先网易云 DLNA `netease:musicId`，回退 lrclib / lyrics.ovh。
- **原生设置页**：设备名称、AirPlay、DLNA、开机自启。
- **低内存**：干净后台服务约 13MB PSS；UI 停止后自动回收 HWUI/GL。

## 安装

```bash
adb install -r LX04Cast-0.1.10.apk
```

或在设备上安装，打开「小爱触屏音箱」即可开始接收投送。

## 构建

要求 JDK 17+、Android SDK 35、Gradle 8.11.1（仓库含 wrapper）：

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

预编译的 `libcastbay_protocol.so` / `libc++_shared.so` 已包含在 `app/src/main/jniLibs/armeabi-v7a/`。

## 限制

- 仅音频：不支持 AirPlay 视频/屏幕镜像、DLNA 视频/图片、字幕。
- DLNA 的手机端音量和切歌队列由发送端控制；接收端不能远程操作。
- 小爱语音在不同 Mico 固件上的媒体路由不一致；已做 MediaSession、媒体按钮和音频焦点兜底，但不保证所有固件。
- 发布 APK 使用 debug 证书签名，仅供个人侧载。

## 参考项目与来源

本项目基于以下项目；LX04 应用层和播放页为本项目自研：

- **CastBay 1.9.0**（GPL-3.0-or-later）：DLNA/DACP/AirPlay 音频调度及预编译 native 库 — https://github.com/weenas/castbay
- **UxPlay**（GPL-3.0）：AirPlay native core — https://github.com/FDH2/UxPlay
- **AriaCast**：产品形态参考，未复制代码 — https://github.com/AriaCast/AriaCast-app
- **AirReceiverLite 5.1.8 XAPK**：仅作 DACP/mDNS 协议分析，未复制代码或 native 库。

完整第三方许可与归属见 [NOTICE.md](NOTICE.md)。

## License

GPL-3.0-or-later。详见 [LICENSE](LICENSE)。
