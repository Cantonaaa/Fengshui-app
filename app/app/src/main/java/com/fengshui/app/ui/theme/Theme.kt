package com.fengshui.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 风水主题：沉稳绿 + 暖金（协调五行、天地自然感）
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBD9),
    onPrimaryContainer = Color(0xFF14341B),
    secondary = Color(0xFF7A6A4F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6D6),
    onSecondaryContainer = Color(0xFF2B2415),
    tertiary = Color(0xFF8C6D1F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E5BC),
    onTertiaryContainer = Color(0xFF2B2206),
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF1C1B18),
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF1C1B18),
    surfaceVariant = Color(0xFFEBE6DA),
    onSurfaceVariant = Color(0xFF4C483E),
    error = Color(0xFFC0392B),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF5A0E08),
    outline = Color(0xFF7B7669)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCFA5),
    onPrimary = Color(0xFF0B2E14),
    primaryContainer = Color(0xFF204E2C),
    onPrimaryContainer = Color(0xFFBCE6C4),
    secondary = Color(0xFFCFC1A6),
    onSecondary = Color(0xFF322A18),
    secondaryContainer = Color(0xFF4A412B),
    onSecondaryContainer = Color(0xFFECDFC3),
    tertiary = Color(0xFFDFC070),
    onTertiary = Color(0xFF3A2E08),
    tertiaryContainer = Color(0xFF55471A),
    onTertiaryContainer = Color(0xFFF8E6AE),
    background = Color(0xFF14140F),
    onBackground = Color(0xFFE8E6DE),
    surface = Color(0xFF1C1C16),
    onSurface = Color(0xFFE8E6DE),
    surfaceVariant = Color(0xFF46433A),
    onSurfaceVariant = Color(0xFFC8C3B4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF939083)
)

@Composable
fun FengShuiAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
