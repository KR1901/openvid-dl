//! openvid-dl — a fast, universal video downloader.
//!
//! Pipeline: `yt-dlp` discovers the available streams → this crate picks the
//! requested quality → streams are pulled in parallel over HTTP(S) → `ffmpeg`
//! remuxes video + audio into one file when they arrive separately.

mod cli;
mod downloader;
mod extractor;
mod muxer;

use anyhow::{Context, Result};
use clap::Parser;
use std::path::PathBuf;

use crate::extractor::Format;

#[tokio::main]
async fn main() -> Result<()> {
    let args = cli::Args::parse();

    let info = extractor::extract(&args.ytdlp, &args.url)?;

    if args.list_formats {
        print_formats(&info);
        return Ok(());
    }

    let (video, audio) = select(&info, &args.quality)?;

    let title = sanitize(
        &info
            .title
            .clone()
            .or_else(|| info.id.clone())
            .unwrap_or_else(|| "video".to_string()),
    );
    let base = args.output.clone().unwrap_or(title);

    let client = downloader::build_client()?;
    let tmp_dir = std::env::temp_dir().join(format!("openvid-dl-{}", std::process::id()));
    tokio::fs::create_dir_all(&tmp_dir).await.ok();

    if let Some(audio) = audio {
        // Separate video and audio streams → download both, then remux.
        let vext = video.ext.clone().unwrap_or_else(|| "mp4".to_string());
        let aext = audio.ext.clone().unwrap_or_else(|| "m4a".to_string());
        let vpath = tmp_dir.join(format!("video.{vext}"));
        let apath = tmp_dir.join(format!("audio.{aext}"));

        let vurl = video
            .url
            .clone()
            .context("the selected video format has no direct URL")?;
        let aurl = audio
            .url
            .clone()
            .context("the selected audio format has no direct URL")?;

        tokio::try_join!(
            downloader::download(&client, &vurl, &vpath, args.connections, "video"),
            downloader::download(&client, &aurl, &apath, args.connections, "audio"),
        )?;

        let out = PathBuf::from(format!("{base}.mp4"));
        muxer::mux(&args.ffmpeg, &vpath, &apath, &out).await?;
        tokio::fs::remove_dir_all(&tmp_dir).await.ok();
        println!("Saved {out}");
    } else {
        let url = video
            .url
            .clone()
            .context("the selected format has no direct URL")?;
        let ext = video.ext.clone().unwrap_or_else(|| "mp4".to_string());
        let out = PathBuf::from(format!("{base}.{ext}"));
        downloader::download(&client, &url, &out, args.connections, "video").await?;
        println!("Saved {out}");
    }

    Ok(())
}

/// Choose which stream(s) to fetch for a given quality selector.
fn select<'a>(
    info: &'a extractor::MediaInfo,
    quality: &str,
) -> Result<(&'a Format, Option<&'a Format>)> {
    match quality {
        "best" => {
            let video = info.best_video().context("no video format was found")?;
            if video.has_audio() {
                Ok((video, None))
            } else {
                let audio = info.best_audio().context("no audio format was found")?;
                Ok((video, Some(audio)))
            }
        }
        "audio" | "bestaudio" => {
            let audio = info.best_audio().context("no audio format was found")?;
            Ok((audio, None))
        }
        id => {
            let format = info
                .find(id)
                .with_context(|| format!("format `{id}` not found — try --list-formats"))?;
            if format.has_audio() || !format.has_video() {
                Ok((format, None))
            } else {
                let audio = info.best_audio().context("no audio format was found")?;
                Ok((format, Some(audio)))
            }
        }
    }
}

fn print_formats(info: &extractor::MediaInfo) {
    println!(
        "{}",
        info.title.clone().unwrap_or_else(|| "(untitled)".to_string())
    );
    println!(
        "{:<12} {:<6} {:<7} {:<9} {:<9} {}",
        "ID", "EXT", "RES", "VCODEC", "ACODEC", "SIZE"
    );
    for f in &info.formats {
        let res = f
            .height
            .map(|h| format!("{h}p"))
            .unwrap_or_else(|| "-".to_string());
        let size = f.size().map(human_size).unwrap_or_else(|| "-".to_string());
        println!(
            "{:<12} {:<6} {:<7} {:<9} {:<9} {}",
            f.format_id,
            f.ext.clone().unwrap_or_else(|| "-".to_string()),
            res,
            truncate(f.vcodec.as_deref().unwrap_or("-"), 8),
            truncate(f.acodec.as_deref().unwrap_or("-"), 8),
            size
        );
    }
}

fn human_size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        s.chars().take(max.saturating_sub(1)).collect::<String>() + "…"
    }
}

/// Turn a video title into a safe filename.
fn sanitize(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\n' | '\r' | '\t' => '_',
            c => c,
        })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.').to_string();
    if trimmed.is_empty() {
        "video".to_string()
    } else {
        trimmed
    }
}
