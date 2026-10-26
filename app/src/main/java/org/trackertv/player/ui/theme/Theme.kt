package org.trackertv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Palette tuned for a dark room: neutral graphite, one teal accent, white focus. Posters bring the color. */
object Palette {
    val background = Color(0xFF16171A)
    val surface = Color(0xFF212226)
    val surfaceHigh = Color(0xFF2B2D32)
    val surfaceHigher = Color(0xFF383A40)
    val focus = Color(0xFFF2F2F2)
    val onFocus = Color(0xFF141518)
    val accent = Color(0xFF5EE0D2)
    val accentDim = Color(0x335EE0D2)
    val text = Color(0xFFF2F2F2)
    val muted = Color(0xFFA7A9AF)
    val faint = Color(0xFF72757C)
    val good = Color(0xFF4FD68E)
    val fair = Color(0xFFF2C14E)
    val bad = Color(0xFFFF6B6B)
    val heart = Color(0xFFFF5C7A)

    val canvas = Brush.verticalGradient(listOf(Color(0xFF202125), background, background))
    val hero = Brush.linearGradient(listOf(Color(0xFF2E3036), Color(0xFF232428), Color(0xFF1B1C1F)))
    val scrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE616171A)))

    /** Poster placeholders: a pleasant gradient picked by title so the same video keeps its color. */
    private val posters = listOf(
        listOf(Color(0xFF3B5B85), Color(0xFF1A2442)),
        listOf(Color(0xFF6A4C8C), Color(0xFF241D42)),
        listOf(Color(0xFF2E7470), Color(0xFF14313F)),
        listOf(Color(0xFF8A5A3C), Color(0xFF34223A)),
        listOf(Color(0xFF8C3F5A), Color(0xFF2B1A33)),
        listOf(Color(0xFF3F6E3F), Color(0xFF17302A)),
        listOf(Color(0xFF4D5D99), Color(0xFF1B1F3D)),
        listOf(Color(0xFF8C7A3C), Color(0xFF2E2A1A)),
    )

    fun poster(seed: String): Brush = Brush.linearGradient(posters[(seed.hashCode() and Int.MAX_VALUE) % posters.size])

    /** Glow for a card whose poster has not loaded: the brighter color of its placeholder. */
    fun posterTint(seed: String): Color = posters[(seed.hashCode() and Int.MAX_VALUE) % posters.size][0]
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onFocus,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            inverseSurface = Palette.focus,
            inverseOnSurface = Palette.onFocus,
            error = Palette.bad,
        ),
        typography = typography,
        content = content,
    )
}
