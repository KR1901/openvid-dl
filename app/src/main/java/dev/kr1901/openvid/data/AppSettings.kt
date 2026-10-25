package dev.kr1901.openvid.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Lightweight persisted settings, backed by SharedPreferences. */
object AppSettings {
    private const val PREFS = "openvid_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_CONNECTIONS = "connections"
    private const val KEY_YTDLP_UPDATED_AT = "ytdlp_updated_at"

    private var prefs: SharedPreferences? = null

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _connections = MutableStateFlow(8)
    val connections: StateFlow<Int> = _connections.asStateFlow()

    /** When we last tried to refresh yt-dlp, so we can throttle to daily. */
    private val _ytDlpUpdatedAt = MutableStateFlow(0L)
    val ytDlpUpdatedAt: StateFlow<Long> = _ytDlpUpdatedAt.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _themeMode.value = runCatching {
            ThemeMode.valueOf(p.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        _connections.value = p.getInt(KEY_CONNECTIONS, 8).coerceIn(1, 32)
        _ytDlpUpdatedAt.value = p.getLong(KEY_YTDLP_UPDATED_AT, 0L)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    fun setConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        _connections.value = clamped
        prefs?.edit()?.putInt(KEY_CONNECTIONS, clamped)?.apply()
    }

    fun markYtDlpUpdated() {
        val now = System.currentTimeMillis()
        _ytDlpUpdatedAt.value = now
        prefs?.edit()?.putLong(KEY_YTDLP_UPDATED_AT, now)?.apply()
    }

    /** True when we haven't tried refreshing yt-dlp recently. */
    fun ytDlpUpdateStale(maxAgeMs: Long = 24L * 60L * 60L * 1000L): Boolean =
        System.currentTimeMillis() - _ytDlpUpdatedAt.value > maxAgeMs
}
