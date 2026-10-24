use clap::Parser;

/// Universal video downloader — grab any video from any site in any quality,
/// always with its audio.
#[derive(Parser, Debug)]
#[command(
    name = "openvid-dl",
    version,
    about = "Universal video downloader — any site, any quality, with audio.",
    long_about = None
)]
pub struct Args {
    /// The video page URL to download.
    pub url: String,

    /// Output file path or base name (without extension).
    /// Defaults to the video title.
    #[arg(short, long)]
    pub output: Option<String>,

    /// Quality selector: `best`, `audio`, or a specific format id from
    /// `--list-formats`.
    #[arg(short, long, default_value = "best")]
    pub quality: String,

    /// List every available format for the URL and exit.
    #[arg(long)]
    pub list_formats: bool,

    /// Number of parallel connections used per file.
    #[arg(short = 'n', long, default_value_t = 8)]
    pub connections: usize,

    /// Path to the `yt-dlp` binary used for format extraction.
    #[arg(long, default_value = "yt-dlp")]
    pub ytdlp: String,

    /// Path to the `ffmpeg` binary used to mux separate video and audio.
    #[arg(long, default_value = "ffmpeg")]
    pub ffmpeg: String,
}
