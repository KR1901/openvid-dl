package dev.kr1901.openvid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kr1901.openvid.engine.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val url: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val title: String? = null,
    val formats: List<MediaFormat> = emptyList(),
    val selectedId: String? = null,
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val urlRegex = Regex("""https?://\S+""")

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(url = value, error = null)
    }

    /** Accepts shared text (which may embed a URL) and immediately fetches. */
    fun setUrl(value: String) {
        val url = urlRegex.find(value)?.value ?: value.trim()
        _state.value = _state.value.copy(url = url, error = null)
        if (url.isNotBlank()) fetch()
    }

    fun select(id: String) {
        _state.value = _state.value.copy(selectedId = id)
    }

    fun selectedFormat(): MediaFormat? =
        _state.value.formats.firstOrNull { it.id == _state.value.selectedId }

    fun fetch() {
        val url = _state.value.url.trim()
        if (url.isBlank()) return

        _state.value = _state.value.copy(
            loading = true,
            error = null,
            title = null,
            formats = emptyList(),
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { resolveMedia(url) }
            }
            result.onSuccess { info ->
                _state.value = _state.value.copy(
                    loading = false,
                    title = info.title,
                    formats = info.formats,
                    selectedId = (info.formats.firstOrNull { it.kind != "audio" }
                        ?: info.formats.firstOrNull())?.id,
                    error = if (info.formats.isEmpty()) "No downloadable formats found." else null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Extraction failed.",
                )
            }
        }
    }
}
