# Third-party notices — LX04Cast 0.1.10

LX04Cast is licensed **GPL-3.0-or-later** (see `LICENSE`). The app contains or
links the components below. The project is an independent LX04 audio receiver;
the protocol core and native library are reused from the listed upstream
projects, while the LX04 application layer and player UI were written for this
project.

| Component | Use | License | Source |
| --- | --- | --- | --- |
| **CastBay 1.9.0** | DLNA/UPnP renderer, SSDP, GENA, DACP remote control, AirPlay audio scheduling; prebuilt `libcastbay_protocol.so` | GPL-3.0-or-later | https://github.com/weenas/castbay |
| **UxPlay** (inside the prebuilt protocol library) | AirPlay 1 / RAOP / RTSP / FairPlay receiver core | GPL-3.0 | https://github.com/FDH2/UxPlay |
| **OpenSSL** (inside the prebuilt protocol library) | AirPlay cryptography | Apache-2.0 | https://www.openssl.org |
| **libplist** (inside the prebuilt protocol library) | Apple binary/XML plist handling | LGPL-2.1 | https://github.com/libimobiledevice/libplist |
| **Apple ALAC reference decoder** (inside the prebuilt protocol library) | AirPlay audio decoding | Apache-2.0 | https://github.com/macosforge/alac |
| **lrclib.net API** | Lyrics fetched at runtime; not bundled | Service terms | https://lrclib.net |
| **lyrics.ovh API** | Plain-lyrics fallback fetched at runtime; not bundled | Service terms | https://lyrics.ovh |
| **NetEase Cloud Music lyric API** | Synced lyrics for NetEase DLNA tracks, fetched at runtime; not bundled | Service terms | https://music.163.com |

## Reference-only projects

These projects informed the design but **no code or proprietary native library
from them is included**:

- **AriaCast** — minimal receiver/product-shape reference: https://github.com/AriaCast/AriaCast-app
- **AirReceiverLite 5.1.8 XAPK** — proprietary app used only for static
  analysis of its DACP/mDNS architecture. Its APK, code, and native libraries
  are not part of this repository or release.
- **Apple Music iOS 17 StandBy / Xiaomi AI player** — visual references for the
  two-page player and control bar. No proprietary assets are bundled.

## Native source

The prebuilt `libcastbay_protocol.so` and `libc++_shared.so` under
`app/src/main/jniLibs/armeabi-v7a/` are taken from the official CastBay v1.9.0
Android release. The complete corresponding source for that native library is
the CastBay v1.9.0 source tree at the upstream URL above. LX04Cast's own source
is provided in the GitHub repository and in the Release source archive
(`LX04Cast-0.1.10-source.zip`), licensed GPL-3.0-or-later.

No analytics, crash reporting, update checks, or telemetry are included. The
only network destinations are the media URLs supplied by the sender, the
AirPlay protocol traffic, the configured lyric APIs, and NetEase album-art CDN
URLs supplied by a DLNA sender.
