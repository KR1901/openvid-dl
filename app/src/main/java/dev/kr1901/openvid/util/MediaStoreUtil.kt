package dev.kr1901.openvid.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreUtil {
    /**
     * Copies a finished download from app-private storage into the public
     * `Downloads/openvid-dl` folder via MediaStore (no runtime permission
     * needed on API 29+). Deletes the source on success.
     */
    fun publish(context: Context, source: File, displayName: String, mime: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/openvid-dl",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return false

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            source.delete()
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
