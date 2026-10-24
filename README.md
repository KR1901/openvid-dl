# openvid-dl

**A fast, universal video downloader for Android — any site, any quality, with audio.**

`openvid-dl` is a native Android app (Kotlin + Jetpack Compose) powered by a
Rust download engine bridged over JNI. Paste or share a link, or browse to a
video in the built-in browser, pick the quality you want, and download it — the
audio always comes along.

> **Status: early MVP (v0.1), pre-build.** The project is complete as source but
> has **not** been compiled in this environment (no JDK/Android SDK/NDK/Rust
> toolchain available where it was written). Treat the first Android Studio
> build as the real verification. See [Build](#build) and [Limitations](#limitations).

## What it does

- **Paste or share** — type a URL, paste from the clipboard, or share a link to
  openvid-dl from any other app (the share-sheet intent is registered).
- **Built-in browser** — a WebView that watches network traffic for media URLs
  (`.m3u8`, `.mp4`, `.webm`, `.m4a`, `.mp3`) and lets you tap a detected stream
  to fetch its formats.
- **Pick any quality** — every stream the engine finds is listed with its
  resolution/format; choose one and download.
- **Fast downloads** — the Rust engine probes each server for HTTP range support
  and, when available, pulls the file with several parallel connections.
- **Audio included** — audio-only streams are selectable; HLS playlists are
  handled by downloading and concatenating their segments.
- **Foreground service** — downloads continue while you use the app, with
  progress in the notification shade, and land in `Downloads/openvid-dl`.

## Architecture

```
┌────────────────────────────── Android app (Kotlin + Compose) ──────────────────────────────┐
│  MainActivity ──▶ AppRoot ──▶ HomeScreen / BrowserScreen / DownloadsScreen                  │
│        │                          │                                                          │
│        │                     MainViewModel ──▶ NativeEngine (JNI)                            │
│        │                          │                    │                                     │
│   share intent                DownloadService ◀────────┘                                     │
│  (ACTION_SEND)                (foreground, notification, MediaStore)                         │
└──────────────────────────────────────────┬──────────────────────────────────────────────────┘
                                            │  JNI
┌───────────────────────────────────────────▼──────────────────────────────────────────────────┐
│  Rust engine  (rust/, crate openvid-core → libopenvid_core.so)                                │
│    extractor.rs  best-effort discovery (direct links, HTML5 <video>, OpenGraph, HLS)          │
│    downloader.rs threaded, range-based parallel HTTP downloader                              │
│    hls.rs        master-playlist variant selection + segment download/concat                 │
│    android.rs    JNI exports (extractFormats, download)                                       │
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
│       │   ├── data/                  download list, foreground service
│       │   ├── ui/                    Compose screens + theme + ViewModel
│       │   └── util/                  MediaStore publishing
│       ├── res/                       strings, theme, launcher icon
│       └── AndroidManifest.xml
├── rust/                      Native engine (Rust)
│   └── src/{lib,extractor,downloader,hls,android,model,util}.rs  + bin/cli.rs
├── build.gradle.kts           root Gradle config (AGP 9.4.1, Kotlin 2.4.21)
├── settings.gradle.kts
└── gradle/                    Gradle wrapper
```

## Build

### Prerequisites

- **Android Studio** (Ladybug or newer) with the **Android SDK** and **NDK**.
- **Rust** (via [rustup](https://rustup.rs)) with the Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- **cargo-ndk**:
  ```bash
  cargo install cargo-ndk
  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/<version>"
  ```

### Build the APK

```bash
git clone https://github.com/KR1901/openvid-dl
cd openvid-dl
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

The `:app:preBuild` task automatically invokes
`cargo ndk ... build --release --features android` and copies the resulting
`.so` files into `app/src/main/jniLibs`, so a plain `./gradlew` call builds the
native engine too.

### Build without Android Studio (GitHub Actions)

You don't need Android Studio, a local SDK, or Rust installed. The `Build APK`
workflow (`.github/workflows/build-apk.yml`) builds both APKs in the cloud:

- automatically on every push to `main`, and on tags like `v0.1.0`;
- manually from **Actions → Build APK → Run workflow**.

Download the result from the run's **Artifacts** section. Pushing a tag (for
example `git tag v0.1.0 && git push origin v0.1.0`) also creates a GitHub
Release with both APKs attached, which is the easiest way to share them.

### Release signing

The release build is signed with your key when one is supplied, and falls back
to the debug key otherwise — so it always produces an installable APK:

- **locally** — copy `keystore.properties.example` to `keystore.properties` and
  fill it in (it is git-ignored);
- **in CI** — add repository secrets `KEYSTORE_BASE64` (base64 of your `.jks`),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Create a keystore with:

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
  APIs will not work. Broadening coverage means adding extractors (see roadmap).
- **HLS output is MPEG-TS**, not MP4 — remuxing would require a muxer, which is
  not bundled.
- **DRM-protected content is out of scope** and always will be.
- **Not compiled yet.** See the status note at the top.

## Roadmap

- Per-site extractors (start with the most requested sites).
- Bundle a muxer so HLS and separate video/audio streams become MP4.
- Download queue with pause/resume and persistence.
- Subtitle and thumbnail embedding.

## Legal

`openvid-dl` is a general-purpose tool. Downloading a video does not grant you
any rights to it — respect each site's terms of service and the copyright of the
content you download. You are responsible for how you use this software.

## License

Licensed under the [GNU General Public License v3.0 or later](LICENSE).
