package com.familytv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Dark 10-foot palette with a red accent (matches the red play-button logo). */
object Ftv {
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF1C1C1C)
    val SurfaceHigh = Color(0xFF282828)
    val SurfaceSelected = Color(0xFF3A2222)
    val Primary = Color(0xFFFF2B2B)
    val OnPrimary = Color.White
    val Accent = Color(0xFF6EC6FF)
    val TextPrimary = Color(0xFFF2F3F5)
    val TextSecondary = Color(0xFFB3B7C0)
    val TextMuted = Color(0xFF7D828C)
    val Focus = Color.White
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC857)
    val Success = Color(0xFF66D19E)
    val Scrim = Color(0xCC000000)
}

@Composable
fun FamilyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ftv.Primary,
            onPrimary = Ftv.OnPrimary,
            background = Ftv.Background,
            onBackground = Ftv.TextPrimary,
            surface = Ftv.Surface,
            onSurface = Ftv.TextPrimary,
            surfaceVariant = Ftv.SurfaceHigh,
            onSurfaceVariant = Ftv.TextSecondary,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            error = Ftv.Error,
            border = Ftv.TextMuted,
        ),
        content = content,
    )
}
