package dev.kr1901.openvid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory download list shared between the foreground service and the UI.
 * A production app would persist this; for the MVP it lives for the process.
 */
object DownloadRepository {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private var nextId = 1L

    @Synchronized
    fun add(title: String, url: String, fileName: String, total: Long): Long {
        val id = nextId++
        _items.value = _items.value + DownloadItem(
            id = id,
            title = title,
            url = url,
            fileName = fileName,
            totalBytes = total,
            downloadedBytes = 0,
            status = DownloadItem.Status.QUEUED,
        )
        return id
    }

    @Synchronized
    fun progress(id: Long, downloaded: Long, total: Long) {
        _items.value = _items.value.map {
            if (it.id == id) {
                it.copy(
                    downloadedBytes = downloaded,
                    totalBytes = if (total > 0) total else it.totalBytes,
                    status = DownloadItem.Status.RUNNING,
                )
            } else {
                it
            }
        }
    }

    @Synchronized
    fun setStatus(id: Long, status: DownloadItem.Status) {
        _items.value = _items.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter { it.isActive }
    }

    @Synchronized
    fun clearAll() {
        _items.value = emptyList()
    }
}
