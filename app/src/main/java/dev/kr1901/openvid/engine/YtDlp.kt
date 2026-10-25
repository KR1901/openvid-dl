package dev.kr1901.openvid.engine

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import dev.kr1901.openvid.data.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * yt-dlp, bundled for Android, used purely as an *extractor*.
 *
 * It hands us format URLs (plus any required HTTP headers) and our own Rust
 * downloader fetches the bytes — so ffmpeg is deliberately not bundled, since we
 * never ask yt-dlp to download or merge anything.
 *
 * Every call is defensive: if yt-dlp is unavailable or fails, callers fall back
 * to the native extractor. Status is published to [Diagnostics] so the failure
 * is visible in Settings rather than silent.
 */
object YtDlp {
    private const val TAG = "openvid-dl"
    private val ready = AtomicBoolean(false)

    fun init(context: Context, allowUpdate: Boolean) {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            ready.set(true)
            Diagnostics.setYtDlp(true, version(context), "")
            Log.i(TAG, "yt-dlp ready: ${version(context)}")

            if (allowUpdate) {
                // The bundled extractor ages quickly; sites change constantly.
                update(context)
            }
        } catch (t: Throwable) {
            ready.set(false)
            Diagnostics.setYtDlp(false, "", t.message ?: t.toString())
            Log.w(TAG, "yt-dlp unavailable", t)
        }
    }

    fun isReady(): Boolean = ready.get()

    fun version(context: Context): String =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull().orEmpty()

    /** Fetch the newest yt-dlp release. Returns true if it completed. */
    fun update(context: Context): Boolean {
        if (!ready.get()) return false
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, UpdateChannel.STABLE)
            Diagnostics.setYtDlp(true, version(context), "")
            Log.i(TAG, "yt-dlp updated to ${version(context)}")
            true
        } catch (t: Throwable) {
            // A failed update is not fatal — the bundled version still works.
            Diagnostics.setYtDlp(true, version(context), "update failed: ${t.message}")
            Log.w(TAG, "yt-dlp update failed", t)
            false
        }
    }

    /** Formats for [url], or null when yt-dlp is unavailable or found nothing. */
    fun formats(url: String): MediaInfo? {
        if (!ready.get()) {
            Diagnostics.report("yt-dlp is not available")
            return null
        }
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
                Diagnostics.report("yt-dlp returned no formats")
                null
            } else {
                Diagnostics.report("yt-dlp: ${list.size} format(s)")
                MediaInfo(info.title ?: info.fulltitle ?: "video", list)
            }
        } catch (t: Throwable) {
            Diagnostics.report("yt-dlp error: ${t.message ?: t.toString()}")
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
