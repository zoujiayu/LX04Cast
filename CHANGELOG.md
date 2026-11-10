# Changelog

All notable changes to LX04Cast are documented here. The project targets the
Xiaomi LX04 (Android 8.1, armeabi-v7a) and remains audio-only.

## 0.1.10 — 2026-11-10

### Added
- `MediaButtonReceiver` and `MediaSession.setMediaButtonReceiver(...)` so the
  receiver remains the system media-button session while paused. This addresses
  Mico's `MICO.player.video` taking over pause/continue media keys on DLNA.
- Handling for `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` in addition to transient
  audio-focus loss.
- GitHub-ready documentation, screenshots, Gradle wrapper, and CI workflow.

### Fixed
- DLNA media-button pause/continue routing when another Mico session had taken
  the global media-button slot.
- Release build remains installable on LX04 with the bundled native libraries.

## 0.1.9 — 2026-11-10

- XiaoAi resume fallback based on audio focus: if voice takes focus while a
  cast is paused, the receiver resumes when focus returns; if it takes focus
  while playing, the receiver pauses and stays paused after focus returns.
- Kept MediaSession in PAUSED after stop to make resume available to assistants.
- Added MediaStyle notification, play/pause action, `PLAY_FROM_SEARCH` and
  `PLAY_FROM_MEDIA_ID` actions, and `setPlaybackToLocal`.
- Removed the control-bar white border; 28dp borderless frosted capsule.
- Fixed the control bar being dismissed by progress updates on tracks without
  cover art.

## 0.1.8 — 2026-11-10

- AirPlay sender-resume detection (PCM gap while locally paused).
- DLNA `AVTransport.Play` resume verified.
- Full-screen native settings, native rename dialog, NetEase `musicId` lyrics,
  and full-image artwork fit from the 0.1.7 line.

## 0.1.7 — 2026-11-10

- NetEase DLNA `netease:musicId` parsing and synced lyric API fallback.
- Full artwork display and 320px NetEase thumbnail loading.
- Full-screen native settings; native rename dialog.
- Lyrics auto-follow with manual-scroll grace period.
- XiaoAi/MediaSession resume improvements.

## 0.1.6 — 2026-11-09

- Raw mDNS DACP fallback; close player UI 30 seconds after sender disconnect.
- Local AirPlay pause fallback and frosted control bar.
