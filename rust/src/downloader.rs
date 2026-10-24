//! Blocking, threaded downloader.
//!
//! When a server honours byte ranges we split the file into `connections`
//! chunks and pull them in parallel with OS threads, each writing straight to
//! its offset in the output file. Progress is reported from the calling thread
//! only, which keeps the JNI callback on the JVM thread that started the call.

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use reqwest::header::{ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, RANGE};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub struct DownloadRequest {
    pub url: String,
    pub dest: PathBuf,
    pub connections: usize,
    pub protocol: String,
    pub total_hint: Option<u64>,
}

pub fn download(client: &Client, req: &DownloadRequest, progress: &dyn Fn(u64, u64)) -> Result<()> {
    if let Some(parent) = req.dest.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    match req.protocol.as_str() {
        "hls" => crate::hls::download(client, &req.url, &req.dest, req.connections, progress),
        _ => download_http(client, req, progress),
    }
}

struct Probe {
    total: Option<u64>,
    ranges: bool,
}

fn probe(client: &Client, url: &str) -> Result<Probe> {
    if let Ok(resp) = client.head(url).send() {
        if resp.status().is_success() {
            let h = resp.headers();
            let total = h
                .get(CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok());
            let ranges = h
                .get(ACCEPT_RANGES)
                .and_then(|v| v.to_str().ok())
                .map(|v| v.to_ascii_lowercase().contains("bytes"))
                .unwrap_or(false);
            if total.is_some() {
                return Ok(Probe { total, ranges });
            }
        }
    }

    let resp = client
        .get(url)
        .header(RANGE, "bytes=0-0")
        .send()
        .context("failed to reach the media URL")?;
    let ranges = resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;
    let total = resp
        .headers()
        .get(CONTENT_RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(parse_total)
        .or_else(|| {
            resp.headers()
                .get(CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
        });
    Ok(Probe { total, ranges })
}

fn parse_total(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse::<u64>().ok()
}

fn download_http(client: &Client, req: &DownloadRequest, progress: &dyn Fn(u64, u64)) -> Result<()> {
    let probe = probe(client, &req.url)?;
    let known_total = probe.total.or(req.total_hint);

    if probe.ranges && req.connections > 1 && probe.total.is_some() {
        let total = probe.total.unwrap();
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&req.dest)
            .with_context(|| format!("cannot create {}", req.dest.display()))?;
        file.set_len(total).context("cannot preallocate the output file")?;
        drop(file);

        let n = req.connections.max(1) as u64;
        let chunk = total / n + 1;
        let mut ranges = Vec::new();
        let mut start = 0u64;
        while start < total {
            let end = (start + chunk - 1).min(total - 1);
            ranges.push((start, end));
            start = end + 1;
        }

        let done = Arc::new(AtomicU64::new(0));
        let jobs: Vec<_> = ranges
            .into_iter()
            .map(|(start, end)| {
                let client = client.clone();
                let url = req.url.clone();
                let dest = req.dest.clone();
                let done = done.clone();
                move || fetch_range(&client, &url, &dest, start, end, &done)
            })
            .collect();

        run_parallel(jobs, done, total, progress)
    } else {
        single_stream(client, req, known_total.unwrap_or(0), progress)
    }
}

fn fetch_range(
    client: &Client,
    url: &str,
    dest: &Path,
    start: u64,
    end: u64,
    done: &AtomicU64,
) -> Result<()> {
    let mut resp = client
        .get(url)
        .header(RANGE, format!("bytes={start}-{end}"))
        .send()
        .with_context(|| format!("range {start}-{end} request failed"))?;
    if !resp.status().is_success() {
        bail!("range {start}-{end} returned HTTP {}", resp.status());
    }

    let mut file = OpenOptions::new().write(true).open(dest)?;
    file.seek(SeekFrom::Start(start))?;

    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = resp.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        done.fetch_add(n as u64, Ordering::Relaxed);
    }
    Ok(())
}

fn single_stream(
    client: &Client,
    req: &DownloadRequest,
    total: u64,
    progress: &dyn Fn(u64, u64),
) -> Result<()> {
    let client = client.clone();
    let url = req.url.clone();
    let dest = req.dest.clone();
    let done = Arc::new(AtomicU64::new(0));
    let counter = done.clone();

    let job = move || -> Result<()> {
        let mut resp = client.get(&url).send().context("request failed")?;
        if !resp.status().is_success() {
            bail!("server returned HTTP {}", resp.status());
        }
        let mut file = File::create(&dest).with_context(|| format!("cannot create {}", dest.display()))?;
        let mut buf = [0u8; 64 * 1024];
        loop {
            let n = resp.read(&mut buf)?;
            if n == 0 {
                break;
            }
            file.write_all(&buf[..n])?;
            counter.fetch_add(n as u64, Ordering::Relaxed);
        }
        Ok(())
    };

    run_parallel(vec![job], done, total, progress)
}

/// Run `jobs` on scoped threads while polling a shared byte counter and
/// reporting progress from the calling thread.
pub(crate) fn run_parallel<F>(
    jobs: Vec<F>,
    done: Arc<AtomicU64>,
    total: u64,
    progress: &dyn Fn(u64, u64),
) -> Result<()>
where
    F: FnOnce() -> Result<()> + Send,
{
    let mut results = Vec::new();
    thread::scope(|scope| {
        let mut handles = Vec::new();
        for job in jobs {
            handles.push(scope.spawn(job));
        }

        let mut last = u64::MAX;
        while !handles.iter().all(|h| h.is_finished()) {
            let cur = done.load(Ordering::Relaxed);
            if cur != last {
                progress(cur, total);
                last = cur;
            }
            thread::sleep(Duration::from_millis(150));
        }

        let finished = done.load(Ordering::Relaxed);
        let final_total = if total > 0 { total } else { finished };
        progress(final_total, final_total);

        for h in handles {
            results.push(h.join());
        }
    });

    for r in results {
        r.map_err(|_| anyhow!("a download worker panicked"))??;
    }
    Ok(())
}
