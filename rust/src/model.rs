use serde::{Deserialize, Serialize};

/// One selectable stream. Serialized to JSON and handed to Kotlin.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Format {
    pub id: String,
    pub url: String,
    pub ext: String,
    /// `direct` (progressive file) or `hls` (m3u8 playlist).
    pub protocol: String,
    /// `video`, `audio` or `muxed`.
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bitrate: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filesize: Option<u64>,
    pub note: String,
}

/// The result of inspecting a URL.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaInfo {
    pub title: String,
    pub formats: Vec<Format>,
}
