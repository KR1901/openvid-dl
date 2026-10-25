package dev.kr1901.openvid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kr1901.openvid.BuildConfig
import dev.kr1901.openvid.data.AppSettings
import dev.kr1901.openvid.data.Diagnostics
import dev.kr1901.openvid.data.ThemeMode
import dev.kr1901.openvid.engine.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val themeMode by AppSettings.themeMode.collectAsState()
    val connections by AppSettings.connections.collectAsState()
    val ytReady by Diagnostics.ytDlpReady.collectAsState()
    val ytVersion by Diagnostics.ytDlpVersion.collectAsState()
    val ytError by Diagnostics.ytDlpError.collectAsState()
    val adblockReady by Diagnostics.adblockReady.collectAsState()
    val report by Diagnostics.lastReport.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SectionHeader("Appearance", "How openvid-dl looks.")
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == themeMode, onClick = { AppSettings.setThemeMode(mode) })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == themeMode, onClick = { AppSettings.setThemeMode(mode) })
                    Spacer(Modifier.width(6.dp))
                    Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionHeader("Downloads", "Parallel connections used per file.")
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            Text("Connections: $connections", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Slider(
                value = connections.toFloat(),
                onValueChange = { AppSettings.setConnections(it.toInt()) },
                valueRange = 1f..16f,
                steps = 14,
            )
        }

        Spacer(Modifier.height(26.dp))
        SectionHeader("Storage", "Finished videos are saved to the public Downloads folder.")
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            Text("Downloads/openvid-dl", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Visible in your file manager and gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(26.dp))
        SectionHeader("Diagnostics", "What the extractors are actually doing.")
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            Text(
                "yt-dlp: " + if (ytReady) {
                    "ready" + if (ytVersion.isNotBlank()) " ($ytVersion)" else ""
                } else {
                    "not available"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (ytError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ytError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Ad filter engine: " + if (adblockReady) "ready" else "not available",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "If a site fails to extract, open it in the Browser tab and sign in " +
                    "first — extraction reuses the browser's cookies. VK in particular " +
                    "needs a session for most videos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = { Text("Test a URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            PrimaryAction(
                text = if (busy) "Testing…" else "Test extraction",
                icon = Icons.Filled.Search,
                onClick = {
                    val target = testUrl.trim()
                    if (target.isEmpty()) return@PrimaryAction
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { resolveMedia(target) } }
                        busy = false
                        Diagnostics.report(
                            result.fold(
                                onSuccess = { info ->
                                    "OK: ${info.formats.size} format(s) found — \"${info.title}\""
                                },
                                onFailure = { t -> t.message ?: "extraction failed" },
                            ),
                        )
                    }
                },
                enabled = testUrl.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { YtDlp.update(context) }
                            busy = false
                            Diagnostics.report(
                                if (ok) "yt-dlp updated to ${Diagnostics.ytDlpVersion.value}"
                                else "yt-dlp update failed or yt-dlp is unavailable",
                            )
                        }
                    },
                ) { Text("Update yt-dlp") }
            }

            if (report.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionHeader("About")
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            Text("openvid-dl", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A fast, universal video downloader. Downloading a video does not " +
                    "grant you rights to it — respect each site's terms and copyright.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
