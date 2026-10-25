package dev.kr1901.openvid.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.kr1901.openvid.data.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * yt-dlp, bundled for Android, used purely as an *extractor*.
 *
 * It hands us format URLs (plus any required HTTP headers) and our own Rust
 * downloader fetches the bytes — so ffmpeg is deliberately not bundled, since we
 * never ask yt-dlp to download or merge anything.
 *
 * Two things make this work in practice:
 *
 * 1. **URL canonicalisation.** yt-dlp's VK extractor only accepts a single host
 *    prefix (`m.`, `new.` or `vksport.`), so a real-world URL like
 *    `vksport.m.vkvideo.ru/video-1_2` matches nothing and silently drops to the
 *    generic extractor. We rewrite those to a canonical form first.
 * 2. **Session passthrough.** The WebView's cookies and user agent are handed to
 *    yt-dlp, so pages that need a logged-in session work.
 *
 * Every call is defensive: if yt-dlp is unavailable or fails, callers fall back
 * to the native extractor. Status is published to [Diagnostics] so the failure
 * is visible in Settings rather than silent.
 */
object YtDlp {
    private const val TAG = "openvid-dl"

    /** Host shapes yt-dlp's VK extractor already understands — leave alone. */
    private val VK_HOST_OK = Regex("""^(?:(?:m|new|vksport)\.)?(?:vk\.com|vk\.ru|vkvideo\.ru)$""")
    private val VK_VIDEO_PATH = Regex("""/(video|clip)(-?\d+_\d+)""")

    private val ready = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context, allowUpdate: Boolean) {
        appContext = context.applicationContext
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

        var lastError = ""
        for (candidate in candidates(url)) {
            when (val attempt = tryExtract(candidate)) {
                is Attempt.Ok -> {
                    val suffix = if (candidate != url) " (via $candidate)" else ""
                    Diagnostics.report("yt-dlp: ${attempt.info.formats.size} format(s)$suffix")
                    return attempt.info
                }

                is Attempt.Fail -> lastError = attempt.message
            }
        }

        Diagnostics.report("yt-dlp error: $lastError")
        return null
    }

    private sealed interface Attempt {
        data class Ok(val info: MediaInfo) : Attempt
        data class Fail(val message: String) : Attempt
    }

    private fun tryExtract(url: String): Attempt = try {
        val info = YoutubeDL.getInstance().getInfo(buildRequest(url))
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
                filesize = f.fileSize.takeIf { it > 0 } ?: f.fileSizeApproximate.takeIf { it > 0 },
                note = f.formatNote ?: "yt-dlp",
                headers = f.httpHeaders,
            )
        }
        if (list.isEmpty()) {
            Attempt.Fail("no formats returned")
        } else {
            Attempt.Ok(MediaInfo(info.title ?: info.fulltitle ?: "video", list))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "yt-dlp could not extract $url", t)
        Attempt.Fail(t.message ?: t.toString())
    }

    private fun buildRequest(url: String): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        // The bundled build has no ffmpeg; that warning is just noise.
        request.addOption("--no-warnings")

        appContext?.let { context ->
            runCatching { WebSettings.getDefaultUserAgent(context) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { request.addOption("--user-agent", it) }
        }

        // Reuse the browser's session so login-gated pages work.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addOption("--add-header", "Cookie: $it") }

        return request
    }

    /** Try a canonical URL first when the given one is a shape yt-dlp can't match. */
    private fun candidates(url: String): List<String> {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()

        val canonical = if (uri != null && host.isNotEmpty() && !VK_HOST_OK.matches(host)) {
            canonicalVk(uri, host)
        } else {
            null
        }

        val out = LinkedHashSet<String>()
        if (canonical != null) out += canonical
        out += url
        return out.toList()
    }

    /**
     * VK serves the same video from many host shapes, but yt-dlp matches only
     * some. Rewrite anything else to the plain public form.
     */
    private fun canonicalVk(uri: Uri, host: String): String? {
        val isVk = host.endsWith(".vk.com") || host.endsWith(".vk.ru") ||
            host.endsWith(".vkvideo.ru")
        if (!isVk) return null
        // VK Play / live have their own extractors and must not be rewritten.
        if (host.startsWith("live.") || host.contains("vkplay")) return null

        val path = uri.path ?: return null
        val match = VK_VIDEO_PATH.find(path) ?: return null
        return "https://vkvideo.ru/${match.groupValues[1]}${match.groupValues[2]}"
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
