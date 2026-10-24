//! Format discovery.
//!
//! This is deliberately a *native, best-effort* extractor: it recognises direct
//! media links, the common HTML5 `<video>`/`<source>`/OpenGraph patterns, and
//! HLS (`.m3u8`) playlists. It does not attempt the per-site reverse
//! engineering that `yt-dlp` does — see the README for what that means for
//! site coverage.

use anyhow::{bail, Context, Result};
use regex::Regex;
use reqwest::blocking::Client;
use reqwest::header::{CONTENT_LENGTH, CONTENT_TYPE};
use std::collections::HashSet;
use std::time::Duration;
use url::Url;

use crate::model::{Format, MediaInfo};
use crate::util::{guess_ext, head, title_from_url};

const UA: &str = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

/// HTTP client used for both discovery and downloading. No total timeout, so a
/// large download is never cut off; only connection setup is bounded.
pub fn client() -> Result<Client> {
    Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
        .context("failed to build the HTTP client")
}

pub fn extract(client: &Client, input: &str) -> Result<MediaInfo> {
    let url = Url::parse(input).context("that does not look like a valid URL")?;
    if url.scheme() != "http" && url.scheme() != "https" {
        bail!("only http(s) URLs are supported");
    }

    // A direct link to a media file needs no scraping.
    if let Some(fmt) = direct_format(client, &url) {
        return Ok(MediaInfo {
            title: title_from_url(&url),
            formats: vec![fmt],
        });
    }

    let resp = client.get(url.clone()).send().context("failed to fetch the page")?;
    if !resp.status().is_success() {
        bail!("the site returned HTTP {}", resp.status());
    }

    let final_url = resp.url().clone();
    let ctype = resp
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    let clen = resp
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());

    if ctype.contains("mpegurl") {
        return Ok(MediaInfo {
            title: title_from_url(&final_url),
            formats: vec![hls_format(&final_url)],
        });
    }
    if ctype.starts_with("video/") || ctype.starts_with("audio/") {
        let ext = guess_ext(&ctype, &final_url);
        return Ok(MediaInfo {
            title: title_from_url(&final_url),
            formats: vec![media_format(&final_url, &ext, clen, "direct", "direct link")],
        });
    }

    let body = resp.text().context("failed to read the page")?;
    let html = head(&body, 3_000_000);
    let mut formats = scrape(html, &final_url);
    formats.truncate(60);
    if formats.is_empty() {
        bail!(
            "no downloadable media was found on this page. The native extractor \
             handles direct media links, common HTML5 video and HLS playlists — \
             it is not a full yt-dlp replacement."
        );
    }

    let title = html_title(html).unwrap_or_else(|| title_from_url(&final_url));
    Ok(MediaInfo { title, formats })
}

fn direct_format(client: &Client, url: &Url) -> Option<Format> {
    let path = url.path().to_ascii_lowercase();
    if path.ends_with(".m3u8") {
        return Some(hls_format(url));
    }
    const EXTS: [&str; 15] = [
        "mp4", "webm", "mkv", "m4v", "mov", "flv", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav",
        "mpd", "3gp",
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
                filesize: head_size(client, url),
                note: "direct link".into(),
            });
        }
    }
    None
}

fn head_size(client: &Client, url: &Url) -> Option<u64> {
    client
        .head(url.clone())
        .send()
        .ok()
        .and_then(|r| r.headers().get(CONTENT_LENGTH).and_then(|v| v.to_str().ok()).and_then(|v| v.parse().ok()))
}

fn scrape(html: &str, base: &Url) -> Vec<Format> {
    let patterns = [
        r#"(?i)<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']"#,
        r#"(?i)<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video(?::secure_url|:url)?["']"#,
        r#"(?i)<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["']([^"']+)["']"#,
        r#"(?i)<video[^>]+src=["']([^"']+)["']"#,
        r#"(?i)<source[^>]+src=["']([^"']+)["']"#,
        r#"(?i)["'](https?://[^"']+?\.m3u8[^"']*)["']"#,
        r#"(?i)["'](https?://[^"']+?\.(?:mp4|webm|mkv|m4v|mov|mp3|m4a|ts)(?:\?[^"']*)?)["']"#,
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
