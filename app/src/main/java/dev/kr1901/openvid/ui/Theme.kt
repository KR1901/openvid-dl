package dev.kr1901.openvid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = TerracottaDeep,
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = PaperMuted,
    onSecondaryContainer = Ink,
    tertiary = TerracottaDeep,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperElevated,
    onSurface = Ink,
    surfaceVariant = PaperMuted,
    onSurfaceVariant = InkMuted,
    outline = OutlineWarm,
    outlineVariant = OutlineWarm,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerSoft,
    onErrorContainer = Danger,
    scrim = Color(0x661F1E1D),
)

private val DarkColors = darkColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFF26140E),
    primaryContainer = Color(0xFF4A2A1F),
    onPrimaryContainer = Color(0xFFF6E4DB),
    secondary = NightInk,
    onSecondary = NightPaper,
    secondaryContainer = NightMuted,
    onSecondaryContainer = NightInk,
    tertiary = Terracotta,
    onTertiary = Color(0xFF26140E),
    background = NightPaper,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightMuted,
    onSurfaceVariant = NightInkMuted,
    outline = NightOutline,
    outlineVariant = NightOutline,
    error = Color(0xFFE08A72),
    onError = Color(0xFF26140E),
    errorContainer = Color(0xFF4A2A1F),
    onErrorContainer = Color(0xFFF6E0DA),
    scrim = Color(0xCC000000),
)

private val OpenVidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun OpenVidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OpenVidTypography,
        shapes = OpenVidShapes,
        content = content,
    )
}
