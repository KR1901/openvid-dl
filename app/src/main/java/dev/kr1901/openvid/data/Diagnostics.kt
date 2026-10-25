package dev.kr1901.openvid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable status of the extraction and filtering backends.
 *
 * This exists because these failures are otherwise invisible: when yt-dlp is
 * unavailable, extraction silently falls back to the native extractor and the
 * user sees the same message either way. Settings surfaces this instead.
 */
object Diagnostics {
    private val _ytDlpReady = MutableStateFlow(false)
    val ytDlpReady: StateFlow<Boolean> = _ytDlpReady.asStateFlow()

    private val _ytDlpVersion = MutableStateFlow("")
    val ytDlpVersion: StateFlow<String> = _ytDlpVersion.asStateFlow()

    private val _ytDlpError = MutableStateFlow("")
    val ytDlpError: StateFlow<String> = _ytDlpError.asStateFlow()

    private val _adblockReady = MutableStateFlow(false)
    val adblockReady: StateFlow<Boolean> = _adblockReady.asStateFlow()

    private val _lastReport = MutableStateFlow("")
    val lastReport: StateFlow<String> = _lastReport.asStateFlow()

    fun setYtDlp(ready: Boolean, version: String, error: String) {
        _ytDlpReady.value = ready
        _ytDlpVersion.value = version
        _ytDlpError.value = error
    }

    fun setAdblock(ready: Boolean) {
        _adblockReady.value = ready
    }

    fun report(message: String) {
        _lastReport.value = message
    }
}
