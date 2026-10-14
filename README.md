# PixelCast

[![Latest release](https://img.shields.io/github/v/release/N2K02/PixelCast?label=download)](https://github.com/N2K02/PixelCast/releases/latest) ![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Cast videos and songs from a **Pixel** (Google Cast) to a **Samsung TV** (DLNA) — and control playback from the phone's **notification**.

*DLNA/UPnP casting app for Android: cast local videos and music from a Google Pixel to a Samsung Smart TV, with notification controls.*

**[⬇ Download the latest APK](https://github.com/N2K02/PixelCast/releases/latest)** · Android 10 or newer · Free and open source

Pixels speak Google Cast; Samsung TVs don't. Both speak **DLNA/UPnP**, so PixelCast:

1. finds the TV on your Wi-Fi (SSDP discovery),
2. serves the file from the phone over a tiny built-in HTTP server (with seeking support),
3. tells the TV to play it (UPnP AVTransport), and
4. shows a media notification with **play/pause, a seek bar, back/forward 10 s and stop**.

The TV decodes the file itself, so quality is full and the phone barely works.

## Install & use

1. Download `PixelCast-<version>.apk` (e.g. `PixelCast-1.0.apk`) from the latest **Release** of this repo and install it (allow "install unknown apps").
2. Turn the TV on. Phone and TV must be on the **same Wi-Fi**.
3. Open PixelCast → **Choose file** (or *Share* a video from Files/Photos to PixelCast).
4. It searches for TVs automatically → pick your Samsung → **Cast**.
5. Pull down the notification shade (or lock screen) for the controls.

## Notes & troubleshooting

| Problem | Fix |
|---|---|
| TV not found | TV on (not standby), same Wi-Fi, router "AP/client isolation" off. On the TV: *Settings → General → External Device Manager → Device Connection Manager* → allow access / set to "Always allow". |
| TV shows a "allow device?" prompt | Accept it once on the TV remote. |
| TV says file not supported | Use MP4 (H.264 video + AAC audio) or MP3. MKV/HEVC/DTS depend on the TV model. |
| Stops when screen turns off | Settings → Apps → PixelCast → Battery → **Unrestricted**. |

Not supported (yet): live screen mirroring, subtitles, playlists, TV volume.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release named "PixelCast <version>" (1.0, 1.1, 1.2 …, one decimal place) with release notes listing the changes since the previous release. To jump to a new number (e.g. 2.0), run the workflow by hand from the Actions tab and type the version. Locally: `./gradlew assembleRelease` (shrunk APK) (needs JDK 17 + Android SDK). UI: Jetpack Compose with Material 3 Expressive (dynamic color, follows system light/dark).

| File | What it does |
|---|---|
| `SsdpDiscovery.kt` | Finds DLNA renderers and their AVTransport control URL |
| `AvTransport.kt` | SOAP commands: SetAVTransportURI, Play, Pause, Seek, Stop, position/state |
| `MediaHttpServer.kt` | Streams the chosen file to the TV with HTTP Range + DLNA headers |
| `CastService.kt` | Foreground service, MediaSession, notification, polling the TV |
| `MainActivity.kt` | Choose file, find TV, cast |
| `CastScreen.kt`, `Theme.kt` | Material 3 Expressive UI and theme |

## License

MIT. See [LICENSE](LICENSE).
