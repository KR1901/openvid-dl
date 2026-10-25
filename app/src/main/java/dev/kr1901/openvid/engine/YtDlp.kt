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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * yt-dlp, bundled for Android, used purely as an *extractor*.
 *
 * It hands us format URLs (plus any required HTTP headers) and our own Rust
 * downloader fetches the bytes — so ffmpeg is deliberately not bundled, since we
 * never ask yt-dlp to download or merge anything.
 *
 * Three things make this work in practice:
 *
 * 1. **URL canonicalisation.** yt-dlp's VK extractor only accepts a single host
 *    prefix (`m.`, `new.` or `vksport.`), so a real-world URL like
 *    `vksport.m.vkvideo.ru/video-1_2` matches nothing and silently drops to the
 *    generic extractor. We try the canonical public forms first.
 * 2. **Session passthrough**, via a Netscape cookie file. yt-dlp deprecates
 *    passing cookies as a header and scopes it poorly; a file is the supported
 *    route and applies to every host the extractor touches.
 * 3. **Every attempt is reported.** Reporting only the last failure hid the
 *    interesting one, which is exactly how a canonical-URL failure stayed
 *    invisible.
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

        val notes = ArrayList<String>()
        for (candidate in candidates(url)) {
            when (val attempt = tryExtract(candidate)) {
                is Attempt.Ok -> {
                    val via = if (candidate != url) " (via $candidate)" else ""
                    Diagnostics.report("yt-dlp: ${attempt.info.formats.size} format(s)$via")
                    return attempt.info
                }

                is Attempt.Fail -> notes += "${hostLabel(candidate)} → ${attempt.message.take(300)}"
            }
        }

        Diagnostics.report(
            "yt-dlp could not extract this link (${notes.size} attempt(s)):\n\n" +
                notes.joinToString("\n\n"),
        )
        return null
    }

    private fun hostLabel(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url

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

            cookieFile(context, url)?.let { request.addOption("--cookies", it.absolutePath) }
        }

        return request
    }

    /**
     * Write the browser's cookies for [url] as a Netscape cookie file. yt-dlp
     * deprecates a `Cookie:` header and scopes it to the download URL only; a
     * file is the supported approach and covers every host the extractor uses.
     */
    private fun cookieFile(context: Context, url: String): File? {
        val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (raw.isNullOrBlank()) return null
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
        val domain = "." + host.removePrefix("www.")

        return runCatching {
            val file = File(context.cacheDir, "openvid-cookies.txt")
            val text = buildString {
                append("# Netscape HTTP Cookie File\n")
                raw.split(';').forEach { part ->
                    val entry = part.trim()
                    val eq = entry.indexOf('=')
                    if (eq <= 0) return@forEach
                    val name = entry.substring(0, eq).trim()
                    val value = entry.substring(eq + 1).trim()
                    if (name.isEmpty()) return@forEach
                    append(domain).append('\t')
                        .append("TRUE").append('\t')
                        .append('/').append('\t')
                        .append("TRUE").append('\t')
                        .append("0").append('\t')
                        .append(name).append('\t')
                        .append(value).append('\n')
                }
            }
            file.writeText(text)
            file
        }.getOrNull()
    }

    /**
     * Candidate URLs, most likely to work first. Anything whose host shape
     * yt-dlp cannot match gets canonical public forms prepended.
     */
    private fun candidates(url: String): List<String> {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()

        val out = LinkedHashSet<String>()
        if (uri != null && host.isNotEmpty() && !VK_HOST_OK.matches(host)) {
            vkCandidates(uri, host)?.let { out.addAll(it) }
        }
        out += url
        return out.toList()
    }

    /**
     * yt-dlp's VK extractor accepts only a single host prefix, so
     * `vksport.m.vkvideo.ru/video-1_2` matches nothing and falls through to the
     * generic extractor. Offer the plain public forms instead.
     */
    private fun vkCandidates(uri: Uri, host: String): List<String>? {
        val isVk = host.endsWith(".vk.com") || host.endsWith(".vk.ru") ||
            host.endsWith(".vkvideo.ru")
        if (!isVk) return null
        // VK Play / live have their own extractors and must not be rewritten.
        if (host.startsWith("live.") || host.contains("vkplay")) return null

        val path = uri.path ?: return null
        val match = VK_VIDEO_PATH.find(path) ?: return null
        val kind = match.groupValues[1]
        val id = match.groupValues[2]
        return listOf("https://vk.com/$kind$id", "https://vkvideo.ru/$kind$id")
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
