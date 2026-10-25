//! HLS (`.m3u8`) support: pick the highest-bandwidth variant of a master
//! playlist, download the media segments in parallel, and concatenate them.
//!
//! Output is MPEG-TS. Turning that into an MP4 would require a muxer, which is
//! deliberately out of scope for the native engine (see the roadmap).

use anyhow::{bail, Context, Result};
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use url::Url;
use ureq::Agent;

use crate::extractor::UA;

pub fn download(
    agent: &Agent,
    url: &str,
    dest: &Path,
    connections: usize,
    headers: &[(String, String)],
    progress: &dyn Fn(u64, u64),
) -> Result<()> {
    let base = Url::parse(url).context("invalid HLS URL")?;

    let mut resp = request(agent, url, headers)
        .call()
        .context("failed to fetch the HLS playlist")?;
    let text = resp
        .body_mut()
        .read_to_string()
        .context("failed to read the playlist")?;

    let (media_url, media_text) = if text.contains("#EXT-X-STREAM-INF") {
        let variant = pick_variant(&text, &base)?;
        let mut r = request(agent, variant.as_str(), headers)
            .call()
            .context("failed to fetch the HLS variant playlist")?;
        let t = r.body_mut().read_to_string()?;
        (variant, t)
    } else {
        (base.clone(), text)
    };

    let segments = parse_segments(&media_text, &media_url);
    if segments.is_empty() {
        bail!("the HLS playlist contains no segments");
    }

    let parts = dest.with_extension("hls-parts");
    std::fs::create_dir_all(&parts).ok();

    let done = Arc::new(AtomicU64::new(0));
    let jobs: Vec<_> = segments
        .iter()
        .enumerate()
        .map(|(i, seg)| {
            let agent = agent.clone();
            let seg = seg.clone();
            let headers = headers.to_vec();
            let path = parts.join(format!("{i:06}.ts"));
            let done = done.clone();
            move || -> Result<()> {
                let resp = request(&agent, &seg, &headers)
                    .call()
                    .with_context(|| format!("segment {i} failed"))?;
                if !resp.status().is_success() {
                    bail!("segment {i} returned HTTP {}", resp.status().as_u16());
                }
                let mut file = File::create(&path)?;
                let mut reader = resp.into_body().into_reader();
                let mut buf = [0u8; 64 * 1024];
                loop {
                    let n = reader.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    file.write_all(&buf[..n])?;
                    done.fetch_add(n as u64, Ordering::Relaxed);
                }
                Ok(())
            }
        })
        .collect();

    let _ = connections;
    crate::downloader::run_parallel(jobs, done, 0, progress)?;

    let mut out = File::create(dest).with_context(|| format!("cannot create {}", dest.display()))?;
    for i in 0..segments.len() {
        let p = parts.join(format!("{i:06}.ts"));
        let mut f = File::open(&p).with_context(|| format!("missing segment file {}", p.display()))?;
        std::io::copy(&mut f, &mut out)?;
    }
    std::fs::remove_dir_all(&parts).ok();
    Ok(())
}

fn request(
    agent: &Agent,
    url: &str,
    headers: &[(String, String)],
) -> ureq::RequestBuilder<ureq::typestate::WithoutBody> {
    let mut builder = agent.get(url).header("User-Agent", UA);
    for (key, value) in headers {
        if !key.eq_ignore_ascii_case("user-agent") {
            builder = builder.header(key, value);
        }
    }
    builder
}

fn pick_variant(text: &str, base: &Url) -> Result<Url> {
    let lines: Vec<&str> = text.lines().collect();
    let mut best: Option<(u64, String)> = None;

    for (i, line) in lines.iter().enumerate() {
        if let Some(rest) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            let bw = rest
                .split(',')
                .find_map(|p| {
                    p.trim()
                        .strip_prefix("BANDWIDTH=")
                        .and_then(|v| v.trim().parse::<u64>().ok())
                })
                .unwrap_or(0);
            if let Some(next) = lines.get(i + 1) {
                let next = next.trim();
                if !next.is_empty() && !next.starts_with('#') {
                    if let Ok(u) = base.join(next) {
                        if best.as_ref().map_or(true, |(b, _)| bw > *b) {
                            best = Some((bw, u.to_string()));
                        }
                    }
                }
            }
        }
    }

    let (_, u) = best.context("no playable variant was found in the master playlist")?;
    Url::parse(&u).context("invalid variant URL")
}

fn parse_segments(text: &str, base: &Url) -> Vec<String> {
    text.lines()
        .filter(|l| {
            let t = l.trim();
            !t.is_empty() && !t.starts_with('#')
        })
        .filter_map(|l| base.join(l.trim()).ok())
        .filter(|u| u.scheme() == "http" || u.scheme() == "https")
        .map(|u| u.to_string())
        .collect()
}
