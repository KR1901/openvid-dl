package dev.kr1901.openvid.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.kr1901.openvid.MainActivity
import dev.kr1901.openvid.OpenVidApp
import dev.kr1901.openvid.engine.NativeEngine
import dev.kr1901.openvid.engine.ProgressCallback
import dev.kr1901.openvid.util.MediaStoreUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs downloads in a foreground service with a simple FIFO queue, so selecting
 * several videos on a page queues them all. One summary notification reports
 * overall progress; per-item progress lives in the Downloads tab.
 */
class DownloadService : Service() {

    private data class Job(
        val url: String,
        val title: String,
        val fileName: String,
        val mime: String,
        val totalHint: Long,
        val headersJson: String,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ArrayDeque<Job>()
    private val lock = Any()
    private var draining = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = parseJob(intent)
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        synchronized(lock) { queue.addLast(job) }
        startForeground(NOTIF_ID, buildNotification())
        startDraining()
        return START_NOT_STICKY
    }

    private fun startDraining() {
        synchronized(lock) {
            if (draining) return
            draining = true
        }
        scope.launch {
            while (true) {
                val job = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                runJob(job)
                notifySummary()
            }
            synchronized(lock) { draining = false }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runJob(job: Job) {
        val id = DownloadRepository.add(job.title, job.url, job.fileName, job.totalHint)
        DownloadRepository.setStatus(id, DownloadItem.Status.RUNNING)

        val tmp = File(cacheDir, job.fileName)
        val ok = withContext(Dispatchers.IO) {
            NativeEngine.download(
                job.url,
                tmp.absolutePath,
                AppSettings.connections.value,
                job.totalHint,
                job.headersJson,
                ProgressCallback { downloaded, total ->
                    DownloadRepository.progress(id, downloaded, total)
                    notifySummary()
                },
            )
        }

        val published = ok && tmp.exists() && withContext(Dispatchers.IO) {
            MediaStoreUtil.publish(this@DownloadService, tmp, job.fileName, job.mime)
        }
        DownloadRepository.setStatus(
            id,
            if (published) DownloadItem.Status.DONE else DownloadItem.Status.FAILED,
        )
    }

    private fun parseJob(intent: Intent?): Job? {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return null
        if (url.isBlank()) return null
        return Job(
            url = url,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "video",
            fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "video.mp4",
            mime = intent.getStringExtra(EXTRA_MIME) ?: "video/mp4",
            totalHint = intent.getLongExtra(EXTRA_TOTAL, 0L),
            headersJson = intent.getStringExtra(EXTRA_HEADERS) ?: "{}",
        )
    }

    private fun buildNotification(): Notification {
        val items = DownloadRepository.items.value
        val active = items.filter { it.isActive }
        val running = items.firstOrNull { it.status == DownloadItem.Status.RUNNING }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            active.size > 1 -> "Downloading ${active.size} videos"
            active.size == 1 -> active.first().title
            else -> "Downloads"
        }

        val builder = NotificationCompat.Builder(this, OpenVidApp.CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(active.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setContentIntent(open)

        if (running != null && running.totalBytes > 0) {
            val percent = (running.progress * 100).toInt()
            builder.setContentText("$percent%")
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText(if (active.isEmpty()) "Finishing…" else "Preparing…")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notifySummary() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_HEADERS = "headers"

        fun start(
            context: Context,
            url: String,
            title: String,
            fileName: String,
            mime: String,
            total: Long,
            headersJson: String = "{}",
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME, mime)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_HEADERS, headersJson)
            }
            context.startForegroundService(intent)
        }
    }
}
