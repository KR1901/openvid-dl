use url::Url;

/// Best-effort file extension from a content type, falling back to the URL.
pub fn guess_ext(content_type: &str, url: &Url) -> String {
    let ct = content_type.to_ascii_lowercase();
    if ct.contains("mpegurl") {
        return "ts".into();
    }
    if ct.contains("mp4") {
        return "mp4".into();
    }
    if ct.contains("webm") {
        return "webm".into();
    }
    if ct.contains("matroska") {
        return "mkv".into();
    }
    if ct.contains("aac") {
        return "m4a".into();
    }
    if ct.contains("ogg") {
        return "ogg".into();
    }
    if ct.contains("audio") {
        return "m4a".into();
    }
    if ct.contains("video") {
        return "mp4".into();
    }
    let ext = url
        .path()
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    if !ext.is_empty() && ext.len() <= 5 && !ext.contains('/') {
        ext
    } else {
        "mp4".into()
    }
}

/// A human-ish title derived from the last path segment of a URL.
pub fn title_from_url(url: &Url) -> String {
    let seg = url
        .path_segments()
        .and_then(|s| s.filter(|x| !x.is_empty()).last())
        .unwrap_or("video");
    let name = seg.split('.').next().unwrap_or("video");
    if name.is_empty() {
        "video".into()
    } else {
        sanitize(name)
    }
}

/// Turn arbitrary text into something safe to use as a filename.
pub fn sanitize(name: &str) -> String {
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

pub fn human_size(bytes: u64) -> String {
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

/// Truncate a string to at most `max` bytes on a char boundary.
pub fn head(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut i = max;
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    &s[..i]
}
