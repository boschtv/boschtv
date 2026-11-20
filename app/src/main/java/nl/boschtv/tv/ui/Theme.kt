package nl.boschtv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import nl.boschtv.tv.R

/** Night blue as the canvas, royal blue as the single accent, white for whatever has focus. */
object Ink {
    val Bg = Color(0xFF0A0A24)
    val Surface = Color(0xFF15143F)
    val SurfaceHi = Color(0xFF1F1E5A)
    val Line = Color(0xFF2A2960)
    val Accent = Color(0xFF2979FF)
    val AccentSoft = Color(0xFF7FB0FF)
    val Text = Color(0xFFF2F5FC)
    val Soft = Color(0xFFC3C8E0)
    val Muted = Color(0xFF9096B5)
}

private fun geist(w: Int) = Font(R.font.geist, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700), geist(800))

private fun style(size: Int, weight: Int, spacing: Double = 0.0, line: Int = 0) = TextStyle(
    fontFamily = Geist, fontSize = size.sp, fontWeight = FontWeight(weight), letterSpacing = spacing.sp,
    lineHeight = if (line > 0) line.sp else (size * 1.25).sp,
)

private val Type = Typography(
    displayLarge = style(44, 800, -1.2, 48),
    displayMedium = style(34, 800, -0.8, 38),
    headlineLarge = style(28, 700, -0.5),
    headlineMedium = style(22, 700, -0.3),
    titleLarge = style(17, 700),
    titleMedium = style(15, 600),
    bodyLarge = style(15, 400, 0.0, 22),
    bodyMedium = style(13, 400, 0.0, 18),
    labelLarge = style(14, 600),
    labelMedium = style(12, 600, 0.8),
    labelSmall = style(11, 700, 1.0),
)

@Composable
fun BoschTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Accent, onPrimary = Color.White, background = Ink.Bg, onBackground = Ink.Text,
            surface = Ink.Surface, onSurface = Ink.Text, surfaceVariant = Ink.SurfaceHi, border = Ink.Line,
        ),
        typography = Type,
        content = content,
    )
}
