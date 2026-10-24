//! Blocking, threaded downloader.
//!
//! When a server honours byte ranges we split the file into `connections`
//! chunks and pull them in parallel with OS threads, each writing straight to
//! its offset in the output file. Progress is reported from the calling thread
//! only, which keeps the JNI callback on the JVM thread that started the call.

use anyhow::{anyhow, bail, Context, Result};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;
use ureq::Agent;

use crate::extractor::UA;

pub struct DownloadRequest {
    pub url: String,
    pub dest: PathBuf,
    pub connections: usize,
    pub protocol: String,
    pub total_hint: Option<u64>,
}

pub fn download(agent: &Agent, req: &DownloadRequest, progress: &dyn Fn(u64, u64)) -> Result<()> {
    if let Some(parent) = req.dest.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    match req.protocol.as_str() {
        "hls" => crate::hls::download(agent, &req.url, &req.dest, req.connections, progress),
        _ => download_http(agent, req, progress),
    }
}

struct Probe {
    total: Option<u64>,
    ranges: bool,
}

fn probe(agent: &Agent, url: &str) -> Result<Probe> {
    if let Ok(resp) = agent.head(url).header("User-Agent", UA).call() {
        if resp.status().is_success() {
            let headers = resp.headers();
            let total = headers
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok());
            let ranges = headers
                .get("accept-ranges")
                .and_then(|v| v.to_str().ok())
                .map(|v| v.to_ascii_lowercase().contains("bytes"))
                .unwrap_or(false);
            if total.is_some() {
                return Ok(Probe { total, ranges });
            }
        }
    }

    let resp = agent
        .get(url)
        .header("User-Agent", UA)
        .header("Range", "bytes=0-0")
        .call()
        .context("failed to reach the media URL")?;
    let ranges = resp.status().as_u16() == 206;
    let total = resp
        .headers()
        .get("content-range")
        .and_then(|v| v.to_str().ok())
        .and_then(parse_total)
        .or_else(|| {
            resp.headers()
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
        });
    Ok(Probe { total, ranges })
}

fn parse_total(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse::<u64>().ok()
}

fn download_http(agent: &Agent, req: &DownloadRequest, progress: &dyn Fn(u64, u64)) -> Result<()> {
    let probe = probe(agent, &req.url)?;
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
                let agent = agent.clone();
                let url = req.url.clone();
                let dest = req.dest.clone();
                let done = done.clone();
                move || fetch_range(&agent, &url, &dest, start, end, &done)
            })
            .collect();

        run_parallel(jobs, done, total, progress)
    } else {
        single_stream(agent, req, known_total.unwrap_or(0), progress)
    }
}

fn fetch_range(
    agent: &Agent,
    url: &str,
    dest: &Path,
    start: u64,
    end: u64,
    done: &AtomicU64,
) -> Result<()> {
    let resp = agent
        .get(url)
        .header("User-Agent", UA)
        .header("Range", format!("bytes={start}-{end}"))
        .call()
        .with_context(|| format!("range {start}-{end} request failed"))?;
    if !resp.status().is_success() {
        bail!("range {start}-{end} returned HTTP {}", resp.status().as_u16());
    }

    let mut file = OpenOptions::new().write(true).open(dest)?;
    file.seek(SeekFrom::Start(start))?;

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

fn single_stream(
    agent: &Agent,
    req: &DownloadRequest,
    total: u64,
    progress: &dyn Fn(u64, u64),
) -> Result<()> {
    let agent = agent.clone();
    let url = req.url.clone();
    let dest = req.dest.clone();
    let done = Arc::new(AtomicU64::new(0));
    let counter = done.clone();

    let job = move || -> Result<()> {
        let resp = agent
            .get(&url)
            .header("User-Agent", UA)
            .call()
            .context("request failed")?;
        if !resp.status().is_success() {
            bail!("server returned HTTP {}", resp.status().as_u16());
        }
        let mut file =
            File::create(&dest).with_context(|| format!("cannot create {}", dest.display()))?;
        let mut reader = resp.into_body().into_reader();
        let mut buf = [0u8; 64 * 1024];
        loop {
            let n = reader.read(&mut buf)?;
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
