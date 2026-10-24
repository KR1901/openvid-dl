//! Muxing of separately-downloaded video and audio streams.
//!
//! Streaming sites almost always serve high-quality video and audio as two
//! separate streams. Once both are on disk we hand them to `ffmpeg` to remux
//! into a single file — `-c copy` means no re-encoding, so it is lossless and
//! near-instant.

use anyhow::{bail, Context, Result};
use std::path::Path;

pub async fn mux(ffmpeg: &str, video: &Path, audio: &Path, out: &Path) -> Result<()> {
    let status = tokio::process::Command::new(ffmpeg)
        .arg("-y")
        .arg("-loglevel")
        .arg("error")
        .arg("-i")
        .arg(video)
        .arg("-i")
        .arg(audio)
        .arg("-c")
        .arg("copy")
        .arg(out)
        .status()
        .await
        .with_context(|| format!("failed to run `{ffmpeg}` — is ffmpeg installed and on your PATH?"))?;

    if !status.success() {
        bail!("ffmpeg failed to mux the streams (exit status {status})");
    }
    Ok(())
}
