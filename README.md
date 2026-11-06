# Allervue for Android

**Allervue** is an Android client for Jellyfin, built as part of the **Alleria Labs** ecosystem. It combines a media-library interface with a custom landscape video player, device-aware automatic playback, and direct access to essential playback controls.

> Allervue is an independent client project. It connects to a Jellyfin server; it does not provide or host media.

## Highlights

- **Jellyfin library access** — browse and play movies and TV series from your server.
- **Home discovery** — featured content, Continue Watching, popular movie and series rows, and personalized recommendations.
- **Library navigation** — Movies, TV Shows, My List, Downloads, and search.
- **Custom video player** — a focused landscape interface with transport controls, episode navigation, and playback gestures.
- **Automatic playback engine** — ExoPlayer is used first. If the device cannot decode a stream, Allervue can fall back to the bundled LibVLC player.
- **Device-aware playback** — inspect phone capabilities, display details, supported codecs, network, battery, and the active playback path.
- **Picture-in-picture** — continue watching in a floating player on supported Android devices.
- **Subtitle and audio selection** — choose available subtitle and audio tracks.
- **Playback preferences** — choose video quality, audio output, playback speed, and aspect ratio.
- **Jellyfin progress sync** — resume playback and report viewing progress to the server.

## Player controls

The player keeps its main actions on the control bar:

| Control | Purpose |
| --- | --- |
| Lock | Prevent accidental touches and gestures |
| Quality | Choose Auto, Original, or an available quality limit |
| Subs | Select or disable subtitles |
| Audio | Select an available audio track |
| Output | Set the maximum audio channel output |
| Aspect | Cycle the video display mode |
| Speed | Set playback speed |

The top of the player displays the title, phone model and playback mode, plus available video resolution/HDR information. Tap the phone/playback information to open the detailed device and playback report.

### Playback gestures

- **Single tap** — show or hide the player controls.
- **Double tap left / right** — seek backward / forward by 10 seconds.
- **Double tap center** — pause or resume.
- **Swipe horizontally** — seek through the video.
- **Swipe vertically on the left half** — adjust screen brightness.
- **Swipe vertically on the right half** — adjust media volume.
- **Pinch** — switch between fit and crop behavior.
- **Long press** — temporarily play at 2× speed.
- **Picture-in-picture** — use the system PiP action on supported devices.

## Automatic playback

Playback engine selection is automatic; there is no manual engine selector in the player.

1. Allervue attempts direct playback with ExoPlayer.
2. If the device reports a decoder failure, Allervue can retry through Jellyfin transcoding.
3. If the stream still cannot be handled by the primary player, the bundled LibVLC player is available as the fallback path.

The **This Phone & Playback** report shows device capabilities and the current playback plan. Automatic playback may adapt to the device's supported codecs, network conditions, battery state, and thermal state.

## Jellyfin server setup

- The app currently starts with the Alleria media server address configured as its default.
- You can change the server URL from the Settings option on the login screen or in the app.
- Sign in with an account on your Jellyfin server.
- Your server must be reachable from the Android device. For remote access, configure your Jellyfin server and network accordingly.

Allervue is a client: media availability, user permissions, library access, transcoding, and playback compatibility depend on the connected Jellyfin server and the Android device.

## Requirements

- Android **7.0 (API 24)** or later.
- A reachable Jellyfin server and a valid user account.
- Internet or local-network access to the server.
- For development: Android Studio with a Java 17 JDK and Android SDK platform 34.

## Install a build

### GitHub Actions artifact

1. Open the repository's **Actions** tab.
2. Select a successful **Build APK** workflow run.
3. Download the `Allervue-apk` artifact.
4. Extract the downloaded ZIP archive.
5. Install the `Allervue.apk` file on your Android device.

Android may ask you to allow the browser or file manager to install unknown apps. Only install APKs you trust and that come from this repository's build workflow.

The workflow currently produces a **debug APK**. It is suitable for testing; it is not a Play Store release build.

## Build from source

1. Clone this repository:
   ```bash
   git clone https://github.com/AlleriaLabs/Allervue-Android.git
   cd Allervue-Android
   ```
2. Open the project in Android Studio and allow Gradle sync to complete, or build from a terminal with Gradle 8.9 and Java 17:
   ```bash
   gradle assembleDebug
   ```
3. Find the APK at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

The repository's **Build APK** GitHub Actions workflow runs on pushes and can also be started manually from the Actions tab. A successful workflow uploads an artifact named `Allervue-apk`.

To publish a GitHub Release from the workflow, run it manually and provide a release tag. Leaving the tag empty builds and uploads the APK without creating a Release.

## Technical overview

| Component | Details |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose and Android Views for the custom player |
| Minimum Android version | Android 7.0 (API 24) |
| Compile / target SDK | 34 |
| Java target | 17 |
| Primary playback | AndroidX Media3 / ExoPlayer |
| Fallback playback | VideoLAN LibVLC |
| Networking | OkHttp |
| Serialization | kotlinx.serialization |
| Media backend | Jellyfin |

Application ID: `com.allerix.app`

## Project layout

```
.
├── app/
│   └── src/main/
│       ├── java/com/allerix/app/
│       │   ├── MainActivity.kt       # App navigation and library UI
│       │   ├── PlayerActivity.kt     # Custom player and playback controls
│       │   ├── VlcPlayerActivity.kt # LibVLC fallback player
│       │   ├── Device.kt             # Device and playback capability detection
│       │   └── Api.kt                # Jellyfin API and stream integration
│       └── res/                      # Icons, themes, and app resources
└── .github/workflows/
    └── build.yml                     # APK build and optional release workflow
```

## Notes

- Some features depend on metadata or capabilities provided by Jellyfin and the playback device.
- Direct play, transcoding, HDR, audio formats, and subtitle behavior can vary by server configuration, media file, and Android hardware.
- This project is under active development. Report reproducible issues with the Android version, device model, server version, and relevant diagnostic details.

## License

No license has been specified in this repository yet. Unless a license is added, the source remains subject to the default copyright rules; do not assume it is licensed for redistribution or reuse.
