package dev.kr1901.openvid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dev.kr1901.openvid.data.AppSettings
import dev.kr1901.openvid.engine.NativeEngine
import dev.kr1901.openvid.engine.YtDlp

class OpenVidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        warmUp()
    }

    /**
     * Both of these are slow enough to matter, so they happen off the main
     * thread at startup. Until each finishes, the app simply behaves as if that
     * feature were absent — extraction falls back to the native extractor, and
     * nothing is blocked.
     */
    private fun warmUp() {
        Thread {
            YtDlp.init(this)

            runCatching {
                val rules = assets.open(FILTERS_ASSET).bufferedReader().use { it.readText() }
                val ok = NativeEngine.initBlocker(rules)
                Log.i(TAG, "adblock engine ready: $ok")
            }.onFailure { Log.w(TAG, "could not load filters", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val TAG = "openvid-dl"
        private const val FILTERS_ASSET = "filters.txt"
    }
}
