//! Format discovery.
//!
//! Universal extraction is a genuinely hard problem — it needs to track
//! thousands of sites, players and signatures. Rather than reinvent that, we
//! delegate discovery to `yt-dlp` (the de-facto standard extractor) and do the
//! part where Rust actually buys us speed: the download itself.

use anyhow::{bail, Context, Result};
use serde::Deserialize;
use std::cmp::Ordering;
use std::process::Command;

/// A single downloadable stream as reported by the extractor.
#[derive(Debug, Clone, Deserialize)]
pub struct Format {
    pub format_id: String,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub ext: Option<String>,
    #[serde(default)]
    pub vcodec: Option<String>,
    #[serde(default)]
    pub acodec: Option<String>,
    #[serde(default)]
    pub height: Option<u32>,
    #[serde(default)]
    pub width: Option<u32>,
    #[serde(default)]
    pub fps: Option<f64>,
    #[serde(default)]
    pub abr: Option<f64>,
    #[serde(default)]
    pub tbr: Option<f64>,
    #[serde(default)]
    pub filesize: Option<u64>,
    #[serde(default)]
    pub filesize_approx: Option<u64>,
    #[serde(default)]
    pub format_note: Option<String>,
}

impl Format {
    pub fn has_video(&self) -> bool {
        self.vcodec.as_deref().map_or(false, |c| c != "none")
    }

    pub fn has_audio(&self) -> bool {
        self.acodec.as_deref().map_or(false, |c| c != "none")
    }

    pub fn size(&self) -> Option<u64> {
        self.filesize.or(self.filesize_approx)
    }
}

#[derive(Debug, Deserialize)]
pub struct MediaInfo {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub ext: Option<String>,
    #[serde(default)]
    pub formats: Vec<Format>,
}

impl MediaInfo {
    /// Highest-resolution video stream (ties broken by bitrate).
    pub fn best_video(&self) -> Option<&Format> {
        self.formats
            .iter()
            .filter(|f| f.has_video())
            .max_by_key(|f| (f.height.unwrap_or(0), (f.tbr.unwrap_or(0.0) * 1000.0) as u64))
    }

    /// Highest-bitrate audio-only stream, falling back to any stream with audio.
    pub fn best_audio(&self) -> Option<&Format> {
        let cmp = |a: &&Format, b: &&Format| {
            a.abr
                .unwrap_or(0.0)
                .partial_cmp(&b.abr.unwrap_or(0.0))
                .unwrap_or(Ordering::Equal)
        };
        self.formats
            .iter()
            .filter(|f| f.has_audio() && !f.has_video())
            .max_by(cmp)
            .or_else(|| self.formats.iter().filter(|f| f.has_audio()).max_by(cmp))
    }

    pub fn find(&self, id: &str) -> Option<&Format> {
        self.formats.iter().find(|f| f.format_id == id)
    }
}

/// Ask the extractor for the full metadata JSON of a URL.
pub fn extract(ytdlp: &str, url: &str) -> Result<MediaInfo> {
    let output = Command::new(ytdlp)
        .args(["-J", "--no-warnings", "--no-playlist", url])
        .output()
        .with_context(|| {
            format!("failed to run `{ytdlp}` — is yt-dlp installed and on your PATH?")
        })?;

    if !output.status.success() {
        bail!(
            "extractor failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }

    let info: MediaInfo =
        serde_json::from_slice(&output.stdout).context("could not parse extractor JSON output")?;
    Ok(info)
}
