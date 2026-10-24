# openvid-dl

**A fast, universal video downloader for Android — any site, any quality, with audio.**

`openvid-dl` is a native Android app (Kotlin + Jetpack Compose) powered by a
Rust download engine bridged over JNI. Paste or share a link, or browse to a
page in the built-in browser, and download any — or every — video on it.

> **Status: v0.3.0.** Built and packaged in CI on every push; see
> [Releases](https://github.com/KR1901/openvid-dl/releases) for installable APKs.

## What it does

- **Find every video on a page.** The built-in browser watches network traffic
  *and* probes the DOM for `<video>` / `<audio>` / `<source>` elements, so a page
  with ten videos lists all ten.
- **Download any, or each.** Tap the count pill to open the list: download a
  single video at its best quality, open the quality picker for one, or tick
  several and **Download selected**. With nothing ticked, one tap grabs them all.
- **Any quality.** Every stream the engine finds is listed with its resolution
  and format so you can choose.
- **Fast.** The Rust engine probes each server for HTTP range support and, when
  available, pulls the file over several parallel connections.
- **A real download queue.** Batch selections are queued and processed in order,
  with one summary notification and per-item progress in the Downloads tab.
- **Paste or share.** Type a URL, paste from the clipboard, or share a link into
  openvid-dl from any other app.
- **Warm, editorial UI.** Paper-and-ink palette with a terracotta accent, serif
  headings, light and dark themes (follow system, or force one in Settings).

## Architecture

```
┌────────────────────────────── Android app (Kotlin + Compose) ──────────────────────────────┐
│  MainActivity ─▶ AppRoot (theme, 4-tab shell)                                               │
│      HomeScreen   BrowserScreen            DownloadsScreen      SettingsScreen              │
│         │              │                          │                    │                    │
│    MainViewModel   BrowserController         DownloadRepository   AppSettings               │
│         │         (WebView + session)              ▲                                        │
│         └──────────────┴───────────────┐           │                                        │
│                                        ▼           │                                        │
│                                  NativeEngine (JNI)│                                        │
│                                        │      DownloadService (foreground, FIFO queue)     │
└────────────────────────────────────────┼────────────┴────────────────────────────────────────┘
                                         │ JNI
┌────────────────────────────────────────▼─────────────────────────────────────────────────────┐
│  Rust engine  (rust/, crate openvid-core → libopenvid_core.so)                                │
│    extractor.rs   best-effort discovery (direct links, HTML5 <video>, OpenGraph, HLS)         │
│    downloader.rs  threaded, range-based parallel HTTP downloader                              │
│    hls.rs         master-playlist variant selection + segment download/concat                 │
│    android.rs     JNI exports (extractFormats, download)                                      │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Layout

```
.
├── app/                       Android application module (Kotlin + Compose)
│   └── src/main/
│       ├── java/dev/kr1901/openvid/
│       │   ├── MainActivity.kt        share/view intents, notification permission
│       │   ├── engine/                JNI bridge + format models
│       │   ├── data/                  download queue, service, settings
│       │   ├── ui/                    theme, components, screens, ViewModel
│       │   └── util/                  MediaStore publishing
│       ├── res/                       strings, colours, theme, launcher icon
│       └── AndroidManifest.xml
├── rust/                      Native engine (Rust)
│   └── src/{lib,extractor,downloader,hls,android,model,util}.rs  + bin/cli.rs
├── build.gradle.kts           root Gradle config (AGP 9.4.1, Kotlin 2.4.21)
├── settings.gradle.kts
└── gradle/                    Gradle wrapper (9.8.2)
```

## Build

### Prerequisites

- **Android Studio** (or a plain JDK 17 + Android SDK/NDK).
- **Rust** with the Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- **cargo-ndk**: `cargo install cargo-ndk`, and `ANDROID_NDK_HOME` pointing at an NDK.

### Build the APK

```bash
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

The `:app:preBuild` task automatically invokes
`cargo ndk ... build --release --features android` and copies the resulting
`.so` files into `app/src/main/jniLibs`, so a plain `./gradlew` call builds the
native engine too.

### Build without Android Studio (GitHub Actions)

You don't need Android Studio, a local SDK, or Rust installed. The `Build APK`
workflow builds both APKs in the cloud:

- automatically on every push to `main`, and on tags like `v0.3.0`;
- manually from **Actions → Build APK → Run workflow**.

Download the result from the run's **Artifacts** section, or from the
[Releases](https://github.com/KR1901/openvid-dl/releases) page for tagged builds.

### Cutting a release

```bash
# bump versionCode/versionName in app/build.gradle.kts, then:
git tag v0.3.0 && git push origin v0.3.0
```

The workflow builds both APKs and publishes them on the Releases page as
`openvid-dl-v0.3.0-release.apk` and `openvid-dl-v0.3.0-debug.apk`, with
auto-generated release notes.

### Release signing

The release build is signed with your key when one is supplied, and falls back
to the debug key otherwise — so it always produces an installable APK:

- **locally** — copy `keystore.properties.example` to `keystore.properties` and
  fill it in (it is git-ignored);
- **in CI** — add repository secrets `KEYSTORE_BASE64` (base64 of your `.jks`),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias openvid
base64 -w0 release.jks   # paste the output into the KEYSTORE_BASE64 secret
```

Never commit the keystore: if you publish an app with it, losing it means you
can no longer ship updates under the same identity.

### Desktop CLI (same engine)

The Rust engine also builds as a small CLI, useful for testing extraction and
downloads without a device:

```bash
cd rust
cargo run --features cli --bin openvid-dl -- --list-formats "https://example.com/video"
cargo run --features cli --bin openvid-dl -- -q best "https://example.com/video"
```

## Limitations

Being honest about the MVP:

- **Native extraction is deliberately narrow.** Unlike `yt-dlp`, which tracks
  thousands of sites with per-site logic, the Rust engine only understands
  direct media links, common HTML5 `<video>`/`<source>`/OpenGraph tags, and HLS
  playlists. Sites that serve media through proprietary players, DRM, or signed
  APIs will not work.
- **`blob:` / MediaSource playback can't be downloaded.** Many modern players
  feed the video element from JavaScript; there is no downloadable URL to grab.
- **HLS output is MPEG-TS**, not MP4 — remuxing would require a muxer, which is
  not bundled.
- **Downloads run one at a time** (each file still uses parallel connections).
- **DRM-protected content is out of scope** and always will be.

## Roadmap

- Per-site extractors (start with the most requested sites).
- Bundle a muxer so HLS and separate video/audio streams become MP4.
- Pause/resume and persistence for the download queue.
- Subtitle and thumbnail embedding.

## Legal

`openvid-dl` is a general-purpose tool. Downloading a video does not grant you
any rights to it — respect each site's terms of service and the copyright of the
content you download. You are responsible for how you use this software.

## License

Licensed under the [GNU General Public License v3.0 or later](LICENSE).
