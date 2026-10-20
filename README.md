# PixelCast

[![Latest release](https://img.shields.io/github/v/release/N2K02/PixelCast?label=download)](https://github.com/N2K02/PixelCast/releases/latest) ![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Cast videos and songs from a **Pixel** (Google Cast) to a **Samsung TV** (DLNA) — and control playback from the phone's **notification**.

*DLNA/UPnP casting app for Android: cast local videos and music from a Google Pixel to a Samsung Smart TV, with notification controls.*

**[⬇ Download the latest APK](https://github.com/N2K02/PixelCast/releases/latest)** · Android 10 or newer · Free and open source

Pixels speak Google Cast; Samsung TVs don't. Both speak **DLNA/UPnP**, so PixelCast:

1. finds the TV on your Wi-Fi (SSDP discovery),
2. serves the file from the phone over a tiny built-in HTTP server (with seeking support),
3. tells the TV to play it (UPnP AVTransport), and
4. shows a media notification with **play/pause, a seek bar, back/forward 10 s and stop**,
5. lets the phone's **volume buttons** (and a slider in the app) change the **TV's volume** and mute, and
6. sends an optional **.srt subtitle** file along with a video.

The TV decodes the file itself, so quality is full and the phone barely works.

## Install & use

1. Download `PixelCast-<version>.apk` (e.g. `PixelCast-1.0.apk`) from the latest **Release** of this repo and install it (allow "install unknown apps").
2. Turn the TV on. Phone and TV must be on the **same Wi-Fi**.
3. Open PixelCast → **Choose file** (or *Share* a video from Files/Photos to PixelCast).
4. It searches for TVs automatically → pick your Samsung → **Cast**.
5. Pull down the notification shade (or lock screen) for the controls. The phone's volume buttons now change the TV's volume.
6. Optional: under the video, tap **Add** next to *No subtitles* and pick an `.srt` file before casting.

## Notes & troubleshooting

| Problem | Fix |
|---|---|
| TV not found | TV on (not standby), same Wi-Fi, router "AP/client isolation" off. On the TV: *Settings → General → External Device Manager → Device Connection Manager* → allow access / set to "Always allow". |
| TV shows a "allow device?" prompt | Accept it once on the TV remote. |
| TV says file not supported | Use MP4 (H.264 video + AAC audio) or MP3. MKV/HEVC/DTS depend on the TV model. |
| Stops when screen turns off | Settings → Apps → PixelCast → Battery → **Unrestricted**. |
| No volume slider in the app | The TV doesn't offer volume control over DLNA. Use the TV remote. |
| Subtitles don't show | Turn subtitles on in the TV's player menu. Samsung TVs read them; other brands vary. Use a UTF-8 `.srt` file. |

Not supported (yet): live screen mirroring, playlists, subtitle formats other than `.srt`.

## Updates

The app updates itself from this repo's Releases:

- When you open it (at most every 12 hours) it checks for a newer release. If there is one, a card shows **what's new** with **Update now**, **Later** or **Skip this version**.
- **Check for updates** is at the bottom of the main screen, next to the version number.
- The APK is downloaded inside the app, checked (size, SHA-256 checksum from GitHub, same app, newer version, same signing key) and installed with Android's own installer.
- The first time, Android asks you to allow installs from this app ("Install unknown apps"); after that you just confirm the update.

## Signing

Releases are signed with a private key that is kept encrypted in `signing/keys.tar.enc` and unlocked during the build with the `SIGNING_PASSPHRASE` repository secret (see [signing/README.md](signing/README.md)). Earlier builds used a public test key; Android's key rotation moves installed apps to the private key on the next update, without reinstalling.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release named "PixelCast <version>" (1.0, 1.1, 1.2 …, one decimal place) with release notes listing the changes since the previous release. To jump to a new number (e.g. 2.0), run the workflow by hand from the Actions tab and type the version. Locally: `./gradlew assembleRelease` (shrunk APK) (needs JDK 17 + Android SDK). UI: Jetpack Compose with Material 3 Expressive (dynamic color, follows system light/dark).

| File | What it does |
|---|---|
| `SsdpDiscovery.kt` | Finds DLNA renderers and their AVTransport and RenderingControl URLs |
| `AvTransport.kt` | SOAP commands: SetAVTransportURI (with subtitles), Play, Pause, Seek, Stop, position/state |
| `RenderingControl.kt` | TV volume and mute |
| `MediaHttpServer.kt` | Streams the chosen file (and .srt subtitles) to the TV with HTTP Range + DLNA headers |
| `CastService.kt` | Foreground service, MediaSession, notification, polling the TV, volume buttons → TV volume |
| `MainActivity.kt` | Choose file, find TV, cast |
| `CastScreen.kt`, `Theme.kt` | Material 3 Expressive UI and theme |

## License

MIT. See [LICENSE](LICENSE).


## Official download and project links

Android DLNA/UPnP media casting app for sending local videos and music from a phone to compatible smart TVs.

- **Latest Android APK:** [Download the latest release](https://github.com/N2K02/PixelCast/releases/latest)
- **All releases and version history:** [GitHub Releases](https://github.com/N2K02/PixelCast/releases)
- **Source code and issue reporting:** [PixelCast repository](https://github.com/N2K02/PixelCast)

The APK is distributed through GitHub Releases. If no release is available, check the repository's Releases page for future published builds. Google indexing is not guaranteed; this section provides clear crawlable links for visitors and search engines.
