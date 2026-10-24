package dev.kr1901.openvid.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kr1901.openvid.engine.MediaFormat

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Download a video", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paste a link to any video page, then choose the quality you want.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.url,
            onValueChange = vm::onUrlChange,
            label = { Text("Video URL") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryAction(
                text = if (state.loading) "Fetching…" else "Get formats",
                icon = Icons.Filled.Search,
                onClick = vm::fetch,
                enabled = state.url.isNotBlank() && !state.loading,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.onUrlChange(pasteFromClipboard(context)) }) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
        }

        state.error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (state.formats.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SurfaceCard {
                Text(
                    state.title ?: "Video",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.formats.size} format(s) available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                state.formats.forEach { format ->
                    FormatRow(
                        format = format,
                        selected = format.id == state.selectedId,
                        onSelect = { vm.select(format.id) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryAction(
                text = "Download",
                icon = Icons.Filled.Download,
                onClick = {
                    vm.selectedFormat()?.let { format ->
                        launchDownload(context, format, state.title ?: "video")
                        onMessage("Queued download")
                    }
                },
                enabled = state.selectedId != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FormatRow(format: MediaFormat, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Text(
            format.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val size = formatBytes(format.filesize)
        if (size.isNotEmpty()) {
            Text(
                size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pasteFromClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
