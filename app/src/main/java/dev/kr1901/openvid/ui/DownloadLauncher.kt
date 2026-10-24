package dev.kr1901.openvid.ui

import android.content.Context
import dev.kr1901.openvid.data.DownloadService
import dev.kr1901.openvid.engine.MediaFormat
import dev.kr1901.openvid.engine.MediaInfoParser
import dev.kr1901.openvid.engine.NativeEngine

/** Queues a specific format through the foreground service. */
fun launchDownload(context: Context, format: MediaFormat, title: String) {
    val safe = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "video" }
    val fileName = "$safe.${format.ext}"
    val mime = if (format.isAudio) "audio/mpeg" else "video/mp4"
    DownloadService.start(context, format.url, title, fileName, mime, format.filesize ?: 0L)
}

/**
 * Resolves the best downloadable format for a URL. Blocking — call from a
 * background dispatcher.
 */
fun resolveBestFormat(url: String): MediaFormat? = runCatching {
    val info = MediaInfoParser.parse(NativeEngine.extractFormats(url))
    info.formats.firstOrNull { it.kind != "audio" } ?: info.formats.firstOrNull()
}.getOrNull()

/** All formats for a URL. Blocking — call from a background dispatcher. */
fun resolveFormats(url: String): List<MediaFormat> = runCatching {
    MediaInfoParser.parse(NativeEngine.extractFormats(url)).formats
}.getOrDefault(emptyList())
