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
 * Runs one download in a foreground service so it survives the UI and shows
 * progress in the notification shade.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "video"
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "video.mp4"
        val mime = intent?.getStringExtra(EXTRA_MIME) ?: "video/mp4"
        val totalHint = intent?.getLongExtra(EXTRA_TOTAL, 0L) ?: 0L

        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(title, 0, 0, indeterminate = true))
        scope.launch { runDownload(url, title, fileName, mime, totalHint) }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(
        url: String,
        title: String,
        fileName: String,
        mime: String,
        totalHint: Long,
    ) {
        val id = DownloadRepository.add(title, url, fileName, totalHint)
        DownloadRepository.setStatus(id, DownloadItem.Status.RUNNING)

        val tmp = File(cacheDir, fileName)
        val ok = withContext(Dispatchers.IO) {
            NativeEngine.download(
                url,
                tmp.absolutePath,
                8,
                totalHint,
                ProgressCallback { downloaded, total ->
                    DownloadRepository.progress(id, downloaded, total)
                    notifyProgress(title, downloaded, total)
                },
            )
        }

        val published = ok && tmp.exists() && withContext(Dispatchers.IO) {
            MediaStoreUtil.publish(this@DownloadService, tmp, fileName, mime)
        }
        DownloadRepository.setStatus(
            id,
            if (published) DownloadItem.Status.DONE else DownloadItem.Status.FAILED,
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        title: String,
        downloaded: Long,
        total: Long,
        indeterminate: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        return NotificationCompat.Builder(this, OpenVidApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Downloading…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent, indeterminate || total <= 0)
            .build()
    }

    private fun notifyProgress(title: String, downloaded: Long, total: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, downloaded, total, indeterminate = false))
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

        fun start(
            context: Context,
            url: String,
            title: String,
            fileName: String,
            mime: String,
            total: Long,
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME, mime)
                putExtra(EXTRA_TOTAL, total)
            }
            context.startForegroundService(intent)
        }
    }
}
