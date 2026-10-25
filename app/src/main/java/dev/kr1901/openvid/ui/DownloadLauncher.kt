package dev.kr1901.openvid.ui

import android.content.Context
import dev.kr1901.openvid.data.DownloadService
import dev.kr1901.openvid.engine.MediaFormat
import dev.kr1901.openvid.engine.MediaInfo
import dev.kr1901.openvid.engine.MediaInfoParser
import dev.kr1901.openvid.engine.NativeEngine
import dev.kr1901.openvid.engine.YtDlp
import org.json.JSONObject

/**
 * Metadata for a URL, preferring yt-dlp (far broader site coverage) and falling
 * back to the native Rust extractor. Blocking — call from a background thread.
 */
fun resolveMedia(url: String): MediaInfo {
    YtDlp.formats(url)?.let { info ->
        if (info.formats.isNotEmpty()) return info
    }
    return MediaInfoParser.parse(NativeEngine.extractFormats(url))
}

/** All formats for a URL. Blocking — call from a background dispatcher. */
fun resolveFormats(url: String): List<MediaFormat> =
    runCatching { resolveMedia(url).formats }.getOrDefault(emptyList())

/** The best downloadable format for a URL. Blocking. */
fun resolveBestFormat(url: String): MediaFormat? {
    val list = resolveFormats(url)
    return list.firstOrNull { it.kind != "audio" } ?: list.firstOrNull()
}

/** Queues a specific format through the foreground service. */
fun launchDownload(context: Context, format: MediaFormat, title: String) {
    val safe = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "video" }
    val fileName = "$safe.${format.ext}"
    val mime = if (format.isAudio) "audio/mpeg" else "video/mp4"
    DownloadService.start(
        context,
        format.url,
        title,
        fileName,
        mime,
        format.filesize ?: 0L,
        headersToJson(format.headers),
    )
}

private fun headersToJson(headers: Map<String, String>?): String {
    if (headers.isNullOrEmpty()) return "{}"
    return runCatching {
        val obj = JSONObject()
        headers.forEach { (key, value) -> obj.put(key, value) }
        obj.toString()
    }.getOrDefault("{}")
}
