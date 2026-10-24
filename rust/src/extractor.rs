//! Format discovery.
//!
//! Best-effort and native: it recognises direct media links, the common HTML5
//! `<video>`/`<source>`/OpenGraph patterns, and HLS (`.m3u8`) playlists. It does
//! not attempt the per-site reverse engineering that `yt-dlp` does.

use anyhow::{bail, Context, Result};
use regex::Regex;
use std::collections::HashSet;
use std::io::Read;
use std::time::Duration;
use url::Url;
use ureq::Agent;

use crate::model::{Format, MediaInfo};
use crate::util::{guess_ext, title_from_url};

pub const UA: &str = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

/// HTTP agent used for both discovery and downloading.
///
/// No global timeout, so a large download is never cut off; only connection
/// setup is bounded. `http_status_as_error` is off so we can inspect 4xx/5xx
/// (and 206 partial content) ourselves.
pub fn client() -> Result<Agent> {
    let agent: Agent = Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(20)))
        .http_status_as_error(false)
        .build()
        .into();
    Ok(agent)
}

pub fn extract(agent: &Agent, input: &str) -> Result<MediaInfo> {
    let url = Url::parse(input).context("that does not look like a valid URL")?;
    if url.scheme() != "http" && url.scheme() != "https" {
        bail!("only http(s) URLs are supported");
    }

    // A direct link to a media file needs no scraping.
    if let Some(fmt) = direct_format(agent, &url) {
        return Ok(MediaInfo {
            title: title_from_url(&url),
            formats: vec![fmt],
        });
    }

    let mut resp = agent
        .get(url.as_str())
        .header("User-Agent", UA)
        .call()
        .context("failed to fetch the page")?;

    let status = resp.status();
    if !status.is_success() {
        bail!("the site returned HTTP {}", status.as_u16());
    }

    let ctype = resp
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    let clen = resp
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());

    if ctype.contains("mpegurl") {
        return Ok(MediaInfo {
            title: title_from_url(&url),
            formats: vec![hls_format(&url)],
        });
    }
    if ctype.starts_with("video/") || ctype.starts_with("audio/") {
        let ext = guess_ext(&ctype, &url);
        return Ok(MediaInfo {
            title: title_from_url(&url),
            formats: vec![media_format(&url, &ext, clen, "direct", "direct link")],
        });
    }

    // Read at most 3 MB of HTML — enough for any head, and it bounds memory.
    let mut reader = resp.body_mut().as_reader();
    let mut buf = Vec::new();
    reader
        .by_ref()
        .take(3_000_000)
        .read_to_end(&mut buf)
        .context("failed to read the page")?;
    let body = String::from_utf8_lossy(&buf);

    let mut formats = scrape(&body, &url);
    formats.truncate(80);
    if formats.is_empty() {
        bail!(
            "no downloadable media was found on this page. The native extractor \
             handles direct media links, common HTML5 video and HLS playlists — \
             it is not a full yt-dlp replacement."
        );
    }

    let title = html_title(&body).unwrap_or_else(|| title_from_url(&url));
    Ok(MediaInfo { title, formats })
}

fn direct_format(agent: &Agent, url: &Url) -> Option<Format> {
    let path = url.path().to_ascii_lowercase();
    if path.ends_with(".m3u8") {
        return Some(hls_format(url));
    }
    const EXTS: [&str; 16] = [
        "mp4", "webm", "mkv", "m4v", "mov", "flv", "ts", "m4s", "mpd", "3gp", "mp3", "m4a", "aac",
        "ogg", "opus", "wav",
    ];
    for ext in EXTS {
        if path.ends_with(&format!(".{ext}")) {
            let kind = if is_audio_ext(ext) { "audio" } else { "video" };
            return Some(Format {
                id: "direct".into(),
                url: url.to_string(),
                ext: ext.into(),
                protocol: "direct".into(),
                kind: kind.into(),
                height: None,
                width: None,
                bitrate: None,
                filesize: head_size(agent, url),
                note: "direct link".into(),
            });
        }
    }
    None
}

fn head_size(agent: &Agent, url: &Url) -> Option<u64> {
    agent
        .head(url.as_str())
        .header("User-Agent", UA)
        .call()
        .ok()
        .and_then(|r| {
            r.headers()
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse().ok())
        })
}

fn scrape(html: &str, base: &Url) -> Vec<Format> {
    let patterns = [
        r#"(?i)<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']"#,
        r#"(?i)<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video(?::secure_url|:url)?["']"#,
        r#"(?i)<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["']([^"']+)["']"#,
        r#"(?i)<video[^>]+src=["']([^"']+)["']"#,
        r#"(?i)<source[^>]+src=["']([^"']+)["']"#,
        r#"(?i)<video[^>]+data-src=["']([^"']+)["']"#,
        r#"(?i)["'](https?://[^"']+?\.m3u8[^"']*)["']"#,
        r#"(?i)["'](https?://[^"']+?\.(?:mp4|webm|mkv|m4v|mov|mp3|m4a|ts|m4s)(?:\?[^"']*)?)["']"#,
        r#"(?i)["']([^"']+?\.m3u8[^"']*)["']"#,
    ];

    let mut seen = HashSet::new();
    let mut raw: Vec<String> = Vec::new();
    for p in patterns {
        if let Ok(re) = Regex::new(p) {
            for cap in re.captures_iter(html) {
                if let Some(m) = cap.get(1) {
                    let s = m.as_str().to_string();
                    if seen.insert(s.clone()) {
                        raw.push(s);
                    }
                }
            }
        }
    }

    let mut formats = Vec::new();
    for r in raw {
        if r.starts_with("data:") {
            continue;
        }
        let resolved = match base.join(&r) {
            Ok(u) => u,
            Err(_) => continue,
        };
        let u = resolved.to_string();
        let id = formats.len().to_string();
        if u.contains(".m3u8") {
            let mut f = hls_format(&resolved);
            f.id = id;
            formats.push(f);
        } else {
            let ext = resolved
                .path()
                .rsplit('.')
                .next()
                .unwrap_or("")
                .to_ascii_lowercase();
            if ext.is_empty() || ext.len() > 5 {
                continue;
            }
            let kind = if is_audio_ext(&ext) { "audio" } else { "video" };
            formats.push(Format {
                id,
                url: u,
                ext,
                protocol: "direct".into(),
                kind: kind.into(),
                height: None,
                width: None,
                bitrate: None,
                filesize: None,
                note: "from page".into(),
            });
        }
    }
    formats
}

fn html_title(html: &str) -> Option<String> {
    let re = Regex::new(r"(?is)<title[^>]*>(.*?)</title>").ok()?;
    let cap = re.captures(html)?;
    let t = cap.get(1)?.as_str().trim().to_string();
    if t.is_empty() {
        None
    } else {
        Some(t)
    }
}

fn hls_format(url: &Url) -> Format {
    Format {
        id: "hls".into(),
        url: url.to_string(),
        ext: "ts".into(),
        protocol: "hls".into(),
        kind: "video".into(),
        height: None,
        width: None,
        bitrate: None,
        filesize: None,
        note: "HLS stream".into(),
    }
}

fn media_format(url: &Url, ext: &str, size: Option<u64>, protocol: &str, note: &str) -> Format {
    let kind = if is_audio_ext(ext) { "audio" } else { "video" };
    Format {
        id: "direct".into(),
        url: url.to_string(),
        ext: ext.into(),
        protocol: protocol.into(),
        kind: kind.into(),
        height: None,
        width: None,
        bitrate: None,
        filesize: size,
        note: note.into(),
    }
}

fn is_audio_ext(ext: &str) -> bool {
    matches!(ext, "mp3" | "m4a" | "aac" | "ogg" | "opus" | "wav")
}
