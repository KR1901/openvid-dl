//! Concurrent, segmented HTTP(S) downloader.
//!
//! This is where Rust earns its keep: when a server supports byte ranges we
//! split the file into `connections` chunks and pull them in parallel, which
//! typically saturates a fast link far better than a single stream. When the
//! server does not cooperate we transparently fall back to one stream.

use anyhow::{bail, Context, Result};
use futures::StreamExt;
use indicatif::{ProgressBar, ProgressStyle};
use reqwest::header::{ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, RANGE};
use std::io::SeekFrom;
use std::path::Path;
use std::time::Duration;
use tokio::fs::OpenOptions;
use tokio::io::{AsyncSeekExt, AsyncWriteExt};

/// A browser-like user agent avoids the most common trivial blocks.
const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

pub fn build_client() -> Result<reqwest::Client> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(30))
        .pool_max_idle_per_host(16)
        .build()
        .context("failed to build the HTTP client")
}

struct Probe {
    total: Option<u64>,
    ranges: bool,
}

/// Work out the file size and whether byte-range requests are honoured.
async fn probe(client: &reqwest::Client, url: &str) -> Result<Probe> {
    if let Ok(resp) = client.head(url).send().await {
        if resp.status().is_success() {
            let headers = resp.headers();
            let total = headers
                .get(CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok());
            let ranges = headers
                .get(ACCEPT_RANGES)
                .and_then(|v| v.to_str().ok())
                .map(|v| v.to_ascii_lowercase().contains("bytes"))
                .unwrap_or(false);
            if total.is_some() {
                return Ok(Probe { total, ranges });
            }
        }
    }

    // HEAD was refused or gave no length — try a single-byte ranged GET.
    let resp = client
        .get(url)
        .header(RANGE, "bytes=0-0")
        .send()
        .await
        .context("failed to reach the media URL")?;
    let headers = resp.headers();
    let ranges = resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;
    let total = headers
        .get(CONTENT_RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(parse_total_from_content_range)
        .or_else(|| {
            headers
                .get(CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
        });
    Ok(Probe { total, ranges })
}

/// Parse the total size out of a `Content-Range: bytes 0-0/12345` header.
fn parse_total_from_content_range(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse::<u64>().ok()
}

/// Download `url` into `dest`, using up to `connections` parallel streams.
pub async fn download(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    connections: usize,
    label: &str,
) -> Result<()> {
    let probe = probe(client, url).await?;

    let pb = ProgressBar::new(probe.total.unwrap_or(0));
    pb.set_style(
        ProgressStyle::with_template(
            "{msg:>7} [{bar:40.cyan/blue}] {bytes}/{total_bytes} ({bytes_per_sec}, {eta})",
        )
        .unwrap()
        .progress_chars("=>-"),
    );
    pb.set_message(label.to_string());

    let result = if probe.ranges && connections > 1 {
        match probe.total {
            Some(total) => segmented(client, url, dest, total, connections, &pb).await,
            None => single_stream(client, url, dest, &pb).await,
        }
    } else {
        single_stream(client, url, dest, &pb).await
    };

    match result {
        Ok(()) => {
            pb.finish_with_message(format!("{label} done"));
            Ok(())
        }
        Err(e) => {
            pb.abandon_with_message(format!("{label} failed"));
            Err(e)
        }
    }
}

async fn segmented(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    total: u64,
    connections: usize,
    pb: &ProgressBar,
) -> Result<()> {
    // Preallocate the file so every task can write straight to its offset.
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(dest)
        .await
        .with_context(|| format!("cannot create {}", dest.display()))?;
    file.set_len(total)
        .await
        .context("cannot preallocate the output file")?;
    drop(file);

    let chunk = total / connections as u64 + 1;
    let mut ranges = Vec::new();
    let mut start = 0u64;
    while start < total {
        let end = (start + chunk - 1).min(total - 1);
        ranges.push((start, end));
        start = end + 1;
    }

    let tasks = ranges.into_iter().map(|(start, end)| {
        let client = client.clone();
        let url = url.to_string();
        let dest = dest.to_path_buf();
        let pb = pb.clone();
        async move {
            let resp = client
                .get(&url)
                .header(RANGE, format!("bytes={start}-{end}"))
                .send()
                .await
                .with_context(|| format!("range {start}-{end} request failed"))?;
            if !resp.status().is_success() {
                bail!("range {start}-{end} returned HTTP {}", resp.status());
            }

            let mut file = OpenOptions::new().write(true).open(&dest).await?;
            file.seek(SeekFrom::Start(start)).await?;

            let mut stream = resp.bytes_stream();
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.context("stream error while downloading a range")?;
                file.write_all(&chunk).await?;
                pb.inc(chunk.len() as u64);
            }
            file.flush().await?;
            Ok::<(), anyhow::Error>(())
        }
    });

    futures::stream::iter(tasks)
        .buffer_unordered(connections)
        .collect::<Vec<_>>()
        .await
        .into_iter()
        .collect::<Result<Vec<()>>>()?;

    Ok(())
}

async fn single_stream(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    pb: &ProgressBar,
) -> Result<()> {
    let resp = client.get(url).send().await.context("request failed")?;
    if !resp.status().is_success() {
        bail!("server returned HTTP {}", resp.status());
    }

    let mut file = tokio::fs::File::create(dest)
        .await
        .with_context(|| format!("cannot create {}", dest.display()))?;
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.context("stream error while downloading")?;
        file.write_all(&chunk).await?;
        pb.inc(chunk.len() as u64);
    }
    file.flush().await?;
    Ok(())
}
