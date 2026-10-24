package dev.kr1901.openvid.data

data class DownloadItem(
    val id: Long,
    val title: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Status,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }

    val progress: Float
        get() = when {
            status == Status.DONE -> 1f
            totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}
