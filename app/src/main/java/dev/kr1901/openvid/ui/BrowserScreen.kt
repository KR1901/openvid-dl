package dev.kr1901.openvid.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kr1901.openvid.engine.MediaFormat
import dev.kr1901.openvid.engine.MediaInfoParser
import dev.kr1901.openvid.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AUDIO_REGEX = Regex("""(?i)\.(mp3|m4a|aac|ogg|opus)(\?|#|$)""")

private fun fileNameOf(url: String): String {
    val name = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: url
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: url

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showSheet by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val formats = remember { mutableStateListOf<MediaFormat>() }
    var selectedFormat by remember { mutableStateOf<String?>(null) }

    fun fetchFormats(url: String) {
        selected = url
        error = null
        formats.clear()
        selectedFormat = null
        fetching = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaInfoParser.parse(NativeEngine.extractFormats(url)) }
            }
            fetching = false
            result.onSuccess { info ->
                formats.addAll(info.formats)
                selectedFormat = (info.formats.firstOrNull { it.kind != "audio" }
                    ?: info.formats.firstOrNull())?.id
                if (info.formats.isEmpty()) error = "No downloadable formats were found."
            }.onFailure { e ->
                error = e.message ?: "Could not read this link."
            }
        }
    }

    // Back should walk the page history before leaving the app.
    BackHandler(enabled = controller.canGoBack) { controller.back() }

    // Periodically ask the page which media elements it exposes.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            controller.probe()
        }
    }

    Column(modifier.fillMaxSize()) {
        AddressBar(
            address = controller.address,
            secure = controller.secure,
            loading = controller.loading,
            onAddressChange = { /* handled inside AddressBar */ },
            onGo = { controller.go(controller.address) },
            onReload = { controller.reload() },
        )
        AnimatedVisibility(visible = controller.loading) {
            LinearProgressIndicator(
                progress = { controller.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { controller.ensureWebView() },
                modifier = Modifier.fillMaxSize(),
            )

            AnimatedVisibility(
                visible = controller.detected.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { selected = null; showSheet = true },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text("Download (${controller.detected.size})") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                if (selected == null) {
                    Text(
                        "Media found",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${controller.detected.size} stream(s) detected on this page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(
                        onClick = { fetchFormats(controller.currentUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use this page's link")
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(controller.detected, key = { it }) { url ->
                            MediaRow(url = url, onClick = { fetchFormats(url) })
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            selected = null
                            formats.clear()
                            error = null
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                fileNameOf(selected!!),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                hostOf(selected!!),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    when {
                        fetching -> Box(
                            Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        error != null -> {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(10.dp))
                            FilledTonalButton(onClick = { fetchFormats(selected!!) }) { Text("Retry") }
                        }

                        else -> {
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(formats, key = { it.id }) { format ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedFormat = format.id }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = format.id == selectedFormat,
                                            onClick = { selectedFormat = format.id },
                                        )
                                        Text(
                                            format.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    formats.firstOrNull { it.id == selectedFormat }?.let { format ->
                                        launchDownload(context, format, fileNameOf(selected!!))
                                        showSheet = false
                                        onMessage("Download started")
                                    }
                                },
                                enabled = selectedFormat != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressBar(
    address: String,
    secure: Boolean,
    loading: Boolean,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
) {
    var text by remember(address) { mutableStateOf(address) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onAddressChange(it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("Search or enter address", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        if (secure) Icons.Filled.Lock else Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
            )
            FilledIconButton(onClick = { if (loading) onReload() else onGo() }) {
                Icon(
                    if (loading) Icons.Filled.Refresh else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = if (loading) "Reload" else "Go",
                )
            }
        }
    }
}

@Composable
private fun MediaRow(url: String, onClick: () -> Unit) {
    val isAudio = remember(url) { AUDIO_REGEX.containsMatchIn(url) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fileNameOf(url),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hostOf(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
