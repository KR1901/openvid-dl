package dev.kr1901.openvid.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kr1901.openvid.BuildConfig
import dev.kr1901.openvid.data.AppSettings
import dev.kr1901.openvid.data.ThemeMode

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val themeMode by AppSettings.themeMode.collectAsState()
    val connections by AppSettings.connections.collectAsState()

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
