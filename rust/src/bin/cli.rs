//! Desktop CLI over the same engine the Android app uses.
//! Build with: `cargo run --features cli --bin openvid-dl -- <URL>`

use anyhow::{anyhow, Result};
use clap::Parser;
use std::path::PathBuf;

use openvid_core::{downloader, extractor, util};

#[derive(Parser, Debug)]
#[command(
    name = "openvid-dl",
    version,
    about = "Universal video downloader — any site, any quality, with audio."
)]
struct Args {
    /// The video page URL to download.
    url: String,

    /// Output base name (without extension). Defaults to the page title.
    #[arg(short, long)]
    output: Option<String>,

    /// `best` or a specific format id from --list-formats.
    #[arg(short, long, default_value = "best")]
    quality: String,

    /// List the formats found for the URL and exit.
    #[arg(long)]
    list_formats: bool,

    /// Parallel connections per file.
    #[arg(short = 'n', long, default_value_t = 8)]
    connections: usize,
}

fn main() -> Result<()> {
    let args = Args::parse();
    let client = extractor::client()?;
    let info = extractor::extract(&client, &args.url)?;

    if args.list_formats {
        println!("{}", info.title);
        println!("{:<8} {:<6} {:<6} {:<8} {}", "ID", "EXT", "KIND", "PROTO", "NOTE");
        for f in &info.formats {
            println!(
                "{:<8} {:<6} {:<6} {:<8} {}",
                f.id, f.ext, f.kind, f.protocol, f.note
            );
        }
        return Ok(());
    }

    let chosen = if args.quality == "best" {
        info.formats
            .iter()
            .find(|f| f.kind == "video" || f.kind == "muxed")
            .or_else(|| info.formats.first())
    } else {
        info.formats.iter().find(|f| f.id == args.quality)
    };
    let format = chosen.ok_or_else(|| anyhow!("format `{}` not found", args.quality))?;

    let base = args
        .output
        .clone()
        .unwrap_or_else(|| util::sanitize(&info.title));
    let dest = PathBuf::from(format!("{base}.{}", format.ext));

    let req = downloader::DownloadRequest {
        url: format.url.clone(),
        dest: dest.clone(),
        connections: args.connections,
        protocol: format.protocol.clone(),
        total_hint: format.filesize,
        headers: Vec::new(),
    };

    let bar = indicatif::ProgressBar::new(format.filesize.unwrap_or(0));
    let bar2 = bar.clone();
    downloader::download(&client, &req, &move |downloaded, total| {
        if total > 0 && bar2.length() != Some(total) {
            bar2.set_length(total);
        }
        bar2.set_position(downloaded);
    })?;
    bar.finish_with_message("done");

    println!("Saved {}", dest.display());
    Ok(())
}
