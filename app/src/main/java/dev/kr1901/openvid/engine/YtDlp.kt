package dev.kr1901.openvid.engine

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * yt-dlp, bundled for Android, used purely as an *extractor*.
 *
 * It hands us format URLs (plus any required HTTP headers) and our own Rust
 * downloader fetches the bytes — so ffmpeg is deliberately not bundled, since we
 * never ask yt-dlp to download or merge anything. That keeps roughly 40 MB out
 * of the APK compared to the usual "bundle everything" approach.
 *
 * Every call is defensive: if yt-dlp is unavailable or fails, callers fall back
 * to the native extractor rather than surfacing an error.
 */
object YtDlp {
    private const val TAG = "openvid-dl"
    private val ready = AtomicBoolean(false)

    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            ready.set(true)
            Log.i(TAG, "yt-dlp ready")
        } catch (t: Throwable) {
            Log.w(TAG, "yt-dlp unavailable; using the native extractor", t)
        }
    }

    fun isReady(): Boolean = ready.get()

    /** Formats for [url], or null when yt-dlp is unavailable or found nothing. */
    fun formats(url: String): MediaInfo? {
        if (!ready.get()) return null
        return try {
            val info = YoutubeDL.getInstance().getInfo(url)
            val list = info.formats.orEmpty().mapNotNull { f ->
                val link = f.url ?: return@mapNotNull null
                if (link.startsWith("data:") || link.startsWith("blob:")) return@mapNotNull null
                MediaFormat(
                    id = f.formatId ?: link.hashCode().toString(),
                    url = link,
                    ext = f.ext ?: "mp4",
                    protocol = if (link.contains(".m3u8")) "hls" else "direct",
                    kind = kindOf(f.vcodec, f.acodec),
                    height = f.height.takeIf { it > 0 },
                    width = f.width.takeIf { it > 0 },
                    bitrate = null,
                    filesize = f.fileSize.takeIf { it > 0 }
                        ?: f.fileSizeApproximate.takeIf { it > 0 },
                    note = f.formatNote ?: "yt-dlp",
                    headers = f.httpHeaders,
                )
            }
            if (list.isEmpty()) {
                null
            } else {
                MediaInfo(info.title ?: info.fulltitle ?: "video", list)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "yt-dlp could not extract $url", t)
            null
        }
    }

    private fun kindOf(vcodec: String?, acodec: String?): String {
        val hasVideo = !vcodec.isNullOrBlank() && vcodec != "none"
        val hasAudio = !acodec.isNullOrBlank() && acodec != "none"
        return when {
            hasVideo && hasAudio -> "muxed"
            hasVideo -> "video"
            else -> "audio"
        }
    }
}
