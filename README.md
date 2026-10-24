# openvid-dl

**A fast, universal video downloader — any site, any quality, with audio.**

`openvid-dl` grabs video from the sites you already use, lets you pick exactly
the quality you want, and always keeps the audio. It is a single native binary
written in Rust, designed to make the download step as fast as your connection
allows.

> **Status:** early MVP (v0.1). Works end to end; the CLI surface may still
> change.

## Why another downloader?

Most downloaders read a single HTTP stream start to finish. `openvid-dl` splits
the transfer into parallel byte-range requests and reassembles the file, which
typically saturates a fast link far better — and it does the same for the
separate video and audio streams that modern streaming sites serve, remuxing
them losslessly at the end.

- **Any site** — discovery is delegated to [`yt-dlp`](https://github.com/yt-dlp/yt-dlp),
  which tracks thousands of sites, so you get that coverage for free.
- **Any quality** — `--list-formats` shows every stream; pick one by id, or let
  `best` choose the highest resolution.
- **Always with audio** — when the top quality is served as separate video and
  audio streams, both are downloaded and muxed back together with `ffmpeg`.
- **Fast** — concurrent, segmented downloads with live progress.
- **Small** — a native Rust binary, no runtime to install.

## Requirements

`openvid-dl` shells out to two well-known tools for the jobs they do best:

| Tool | Why it is needed | Install |
| --- | --- | --- |
| [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) | discovers formats for a URL | `pipx install yt-dlp` |
| [`ffmpeg`](https://ffmpeg.org) | muxes separate video + audio streams | `apt install ffmpeg` / `brew install ffmpeg` |

Both must be on your `PATH`, or point at them with `--ytdlp` / `--ffmpeg`.

## Install

From source (requires a Rust toolchain, 1.74+):

```bash
git clone https://github.com/KR1901/openvid-dl
cd openvid-dl
cargo install --path .
```

Or build a release binary:

```bash
cargo build --release
# target/release/openvid-dl
```

## Usage

```bash
# Best available quality, with audio, saved as the video title
openvid-dl "https://example.com/watch?v=..."

# See every available format and its size
openvid-dl --list-formats "https://example.com/watch?v=..."

# A specific format id (audio is added automatically if the format has none)
openvid-dl -q 137 "https://example.com/watch?v=..."

# Audio only
openvid-dl -q audio "https://example.com/watch?v=..."

# Choose the output name and crank up the parallel connections
openvid-dl -o my-clip -n 16 "https://example.com/watch?v=..."
```

### Options

| Flag | Default | Description |
| --- | --- | --- |
| `-o, --output <PATH>` | video title | Output file path or base name |
| `-q, --quality <SEL>` | `best` | `best`, `audio`, or a format id |
| `--list-formats` | — | List all formats and exit |
| `-n, --connections <N>` | `8` | Parallel connections per file |
| `--ytdlp <PATH>` | `yt-dlp` | Path to the extractor binary |
| `--ffmpeg <PATH>` | `ffmpeg` | Path to the ffmpeg binary |

## How it works

```
URL ──▶ yt-dlp -J ──▶ MediaInfo { formats: [...] }
                              │
                 select quality (best / audio / id)
                              │
        ┌─────────────────────┴─────────────────────┐
        ▼                                            ▼
  video stream                                 audio stream
  (parallel byte-range download)               (parallel byte-range download)
        └─────────────────────┬─────────────────────┘
                              ▼
                       ffmpeg -c copy ──▶ single file
```

1. **Extract** — `yt-dlp -J` returns the page metadata as JSON; we parse the
   `formats` array.
2. **Select** — the requested quality is resolved to a concrete video and/or
   audio stream.
3. **Download** — each stream is probed for size and range support, then pulled
   concurrently and written directly to its offsets in the output file. Servers
   without range support fall back to a single stream.
4. **Mux** — if video and audio arrived separately, `ffmpeg -c copy` remuxes
   them without re-encoding.

## Roadmap

- Native extractors for a handful of popular sites (drop the `yt-dlp` runtime
  dependency for the common cases).
- Cookie / header passthrough for gated content.
- Resume of interrupted downloads.
- Subtitles and thumbnail embedding.

## Legal

`openvid-dl` is a general-purpose tool. Downloading a video does not grant you
any rights to it — respect each site's terms of service and the copyright of
the content you download. You are responsible for how you use this software.

## Contributing

Issues and pull requests are welcome. Please run `cargo fmt` and `cargo clippy`
before opening a PR.

## License

Licensed under the [GNU General Public License v3.0 or later](LICENSE).
