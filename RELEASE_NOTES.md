# LX04Cast 0.1.10

面向小米小爱触屏音箱 LX04 的极简 AirPlay / DLNA 音频接收器。

## 小爱同学支持

- “小爱同学，暂停播放” / “继续播放” / “退出播放”
- 暂停后保持可恢复的 MediaSession 状态
- MediaSession、媒体按钮和音频焦点三重兜底，尽量兼容 Mico 语音媒体路由
- DLNA / AirPlay 均支持暂停/继续；AirPlay 切歌取决于发送端 DACP
- 系统媒体键、媒体通知和播放页按钮同样可用

> 不同 Mico 固件对第三方音频应用的语音路由不同，实际响应以设备为准。

## 主要功能

- AirPlay 1 音频接收（UxPlay/FairPlay native core）
- DLNA UPnP AV MediaRenderer（AVTransport / RenderingControl / GENA）
- 两页横屏播放界面：全屏模糊背景、毛玻璃控制栏、完整跟随歌词
- 网易云 DLNA `netease:musicId` 歌词，lrclib / lyrics.ovh 回退
- 原生全屏设置页；低内存，干净后台服务约 13MB PSS
- 0.1.10 新增 MediaButtonReceiver，避免暂停后媒体键被 Mico 会话接管

## 安装

```bash
adb install -r LX04Cast-0.1.10.apk
```

APK 使用 debug 证书签名，仅供个人侧载。仅支持音频；DLNA 切歌队列和手机端音量由发送方控制。

## 来源与许可证

GPL-3.0-or-later。协议层来自 CastBay 1.9.0 与 UxPlay；AriaCast 仅作产品形态参考，AirReceiverLite 仅作协议分析。详见源码中的 `NOTICE.md` 与 `LICENSE`。
