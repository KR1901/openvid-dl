package dev.kr1901.openvid.engine

/**
 * JNI bridge to the Rust engine (`libopenvid_core.so`).
 *
 * All methods are synchronous and must not be called on the main thread, except
 * [isBlocked] which is a fast in-memory lookup.
 */
object NativeEngine {
    init {
        System.loadLibrary("openvid_core")
    }

    /** Returns JSON for a [MediaInfo], or `{"error": "..."}` on failure. */
    external fun extractFormats(url: String): String

    /**
     * Downloads [url] into the file at [destPath].
     *
     * @param connections parallel connections to use per file.
     * @param totalHint   expected size in bytes, or 0 if unknown.
     * @param callback    receives (downloaded, total) byte counts.
     * @return true on success.
     */
    external fun download(
        url: String,
        destPath: String,
        connections: Int,
        totalHint: Long,
        callback: ProgressCallback,
    ): Boolean

    /**
     * Installs the ad/tracker filter rules (Adblock Plus syntax). Safe to call
     * again to replace the rule set.
     */
    external fun initBlocker(rules: String): Boolean

    /**
     * Whether a request for [url] made from [sourceUrl] should be blocked.
     * [kind] is one of: document, script, image, stylesheet, font, media,
     * xmlhttprequest.
     */
    external fun isBlocked(url: String, sourceUrl: String, kind: String): Boolean
}

/** Progress callback invoked from the native download loop. */
fun interface ProgressCallback {
    fun onProgress(downloaded: Long, total: Long)
}
