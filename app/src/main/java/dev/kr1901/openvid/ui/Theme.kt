package dev.kr1901.openvid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF4F8CFF)
private val BlueDeep = Color(0xFF1B4FD8)

private val DarkColors = darkColorScheme(
    primary = Blue,
    secondary = BlueDeep,
    background = Color(0xFF0B1220),
    surface = Color(0xFF131C2E),
)

private val LightColors = lightColorScheme(
    primary = BlueDeep,
    secondary = Blue,
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun OpenVidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
