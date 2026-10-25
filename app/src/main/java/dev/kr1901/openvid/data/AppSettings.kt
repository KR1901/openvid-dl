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
    private const val KEY_YTDLP_UPDATED = "ytdlp_updated"

    private var prefs: SharedPreferences? = null

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _connections = MutableStateFlow(8)
    val connections: StateFlow<Int> = _connections.asStateFlow()

    /** Whether we have already pulled a fresh yt-dlp once on this install. */
    private val _ytDlpUpdated = MutableStateFlow(false)
    val ytDlpUpdated: StateFlow<Boolean> = _ytDlpUpdated.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _themeMode.value = runCatching {
            ThemeMode.valueOf(p.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        _connections.value = p.getInt(KEY_CONNECTIONS, 8).coerceIn(1, 32)
        _ytDlpUpdated.value = p.getBoolean(KEY_YTDLP_UPDATED, false)
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
        _ytDlpUpdated.value = true
        prefs?.edit()?.putBoolean(KEY_YTDLP_UPDATED, true)?.apply()
    }
}
