package com.debunk.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Proof sheet" theme: a fact-checker marking up a page. Cool paper, deep ink, and one pen-indigo accent.
 * Verdict colours (green / red / amber) are reserved for verdicts and are never the only signal.
 */
internal val PenIndigo = Color(0xFF3B3FE0)
internal val PenIndigoDark = Color(0xFF9EA2FF)
private val InkLight = Color(0xFF14172B)
private val PaperLight = Color(0xFFF4F5FA)
private val InkNight = Color(0xFFECEEFA)
private val PaperNight = Color(0xFF0E1020)

private val LightScheme = lightColorScheme(
    primary = PenIndigo, onPrimary = Color.White,
    background = PaperLight, onBackground = InkLight,
    surface = PaperLight, onSurface = InkLight,
    surfaceVariant = Color(0xFFE6E8F4), onSurfaceVariant = Color(0xFF454963),
    outline = Color(0xFF8A8FA8),
)
private val DarkScheme = darkColorScheme(
    primary = PenIndigoDark, onPrimary = Color(0xFF0E1020),
    background = PaperNight, onBackground = InkNight,
    surface = PaperNight, onSurface = InkNight,
    surfaceVariant = Color(0xFF1C2038), onSurfaceVariant = Color(0xFFB4B8D4),
    outline = Color(0xFF6D7292),
)

private val DebunkType = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun DebunkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = DebunkType,
        content = content,
    )
}
