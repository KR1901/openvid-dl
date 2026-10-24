package dev.kr1901.openvid.ui

import android.content.Context
import dev.kr1901.openvid.data.DownloadService
import dev.kr1901.openvid.engine.MediaFormat

/** Starts a download through the foreground service. Shared by Home and Browser. */
fun launchDownload(context: Context, format: MediaFormat, title: String) {
    val safe = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "video" }
    val fileName = "$safe.${format.ext}"
    val mime = if (format.isAudio) "audio/mpeg" else "video/mp4"
    DownloadService.start(context, format.url, title, fileName, mime, format.filesize ?: 0L)
}
