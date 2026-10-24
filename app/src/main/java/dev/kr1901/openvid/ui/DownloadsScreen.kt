package dev.kr1901.openvid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kr1901.openvid.data.DownloadItem
import dev.kr1901.openvid.data.DownloadRepository

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val items by DownloadRepository.items.collectAsState()

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Filled.Download,
                title = "Nothing here yet",
                message = "Videos you download will show up here with their progress.",
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = items.count { it.isActive }
            val done = items.count { it.status == DownloadItem.Status.DONE }
            Text(
                "$active active · $done done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { DownloadRepository.clearFinished() }) {
                Text("Clear finished")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { item -> DownloadCard(item) }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem) {
    val failed = item.status == DownloadItem.Status.FAILED
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = item.progress,
                size = 44.dp,
                stroke = 4.dp,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (container, content) = when (item.status) {
                        DownloadItem.Status.DONE ->
                            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        DownloadItem.Status.FAILED ->
                            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                        else ->
                            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    StatusChip(text = statusLabel(item.status), container = container, content = content)
                    val size = formatBytes(if (item.totalBytes > 0) item.totalBytes else item.downloadedBytes)
                    if (size.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            size,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: DownloadItem.Status): String = when (status) {
    DownloadItem.Status.QUEUED -> "Queued"
    DownloadItem.Status.RUNNING -> "Downloading"
    DownloadItem.Status.DONE -> "Saved"
    DownloadItem.Status.FAILED -> "Failed"
}
