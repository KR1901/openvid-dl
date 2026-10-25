package dev.kr1901.openvid.engine

import org.json.JSONArray
import org.json.JSONObject

/** One selectable stream, mirroring `openvid_core::model::Format`. */
data class MediaFormat(
    val id: String,
    val url: String,
    val ext: String,
    val protocol: String,
    val kind: String,
    val height: Int?,
    val width: Int?,
    val bitrate: Long?,
    val filesize: Long?,
    val note: String,
    /**
     * Extra request headers the extractor says this URL needs (yt-dlp reports
     * `http_headers`). Many CDNs 403 without them.
     */
    val headers: Map<String, String>? = null,
) {
    val isAudio: Boolean get() = kind == "audio"

    val label: String
        get() = buildString {
            append(if (isAudio) "Audio" else height?.let { "${it}p" } ?: "Video")
            if (ext.isNotEmpty()) append(" · ").append(ext.uppercase())
            if (protocol == "hls") append(" · HLS")
            if (note.isNotEmpty()) append(" · ").append(note)
        }
}

data class MediaInfo(val title: String, val formats: List<MediaFormat>)

class ExtractException(message: String) : Exception(message)

object MediaInfoParser {
    fun parse(json: String): MediaInfo {
        val root = JSONObject(json)
        if (root.has("error")) throw ExtractException(root.getString("error"))

        val arr = root.optJSONArray("formats") ?: JSONArray()
        val formats = ArrayList<MediaFormat>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            formats += MediaFormat(
                id = f.optString("id"),
                url = f.optString("url"),
                ext = f.optString("ext"),
                protocol = f.optString("protocol", "direct"),
                kind = f.optString("kind", "video"),
                height = if (f.isNull("height")) null else f.optInt("height"),
                width = if (f.isNull("width")) null else f.optInt("width"),
                bitrate = if (f.isNull("bitrate")) null else f.optLong("bitrate"),
                filesize = if (f.isNull("filesize")) null else f.optLong("filesize"),
                note = f.optString("note"),
            )
        }
        return MediaInfo(root.optString("title", "video"), formats)
    }
}
