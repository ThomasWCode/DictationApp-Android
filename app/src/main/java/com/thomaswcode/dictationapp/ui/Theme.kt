package com.thomaswcode.dictationapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thomaswcode.dictationapp.AppGraph

/** The Windows app's palette (App.xaml): accent blue, recording red, cool greys. */
object Palette {
    val Accent = Color(0xFF1F6FEB)
    val AccentDark = Color(0xFF1858B8)
    val Recording = Color(0xFFE5484D)
    val Success = Color(0xFF1F9D55)
    val Warning = Color(0xFFB7791F)
    val Muted = Color(0xFF6B7280)
    val Bar = Color(0xFF161A20)
}

private val Light = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FD),
    onPrimaryContainer = Color(0xFF0B2A5E),
    secondary = Color(0xFF4B5563),
    secondaryContainer = Color(0xFFE8ECF2),
    onSecondaryContainer = Color(0xFF1B1F24),
    tertiary = Palette.Recording,
    error = Palette.Recording,
    background = Color.White,
    onBackground = Color(0xFF1B1F24),
    surface = Color.White,
    onSurface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFFF5F7FA),
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF2F5F9),
    surfaceContainerHigh = Color(0xFFECF0F5),
    surfaceContainerHighest = Color(0xFFE6EBF1),
    outline = Color(0xFFD9DEE5),
    outlineVariant = Color(0xFFE6EAF0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA2F7),
    onPrimary = Color(0xFF0B2A5E),
    primaryContainer = Color(0xFF173E7F),
    onPrimaryContainer = Color(0xFFDCE8FD),
    secondary = Color(0xFFAEB6C2),
    secondaryContainer = Color(0xFF262C35),
    onSecondaryContainer = Color(0xFFE6EAF0),
    tertiary = Color(0xFFF07B7F),
    error = Color(0xFFF07B7F),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1B2027),
    onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF161A20),
    surfaceContainer = Color(0xFF1A1F26),
    surfaceContainerHigh = Color(0xFF20262E),
    surfaceContainerHighest = Color(0xFF272E37),
    outline = Color(0xFF39414C),
    outlineVariant = Color(0xFF2C333C),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)

@Composable
fun DictationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}

/** The app-wide object graph, available to every screen. */
val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
