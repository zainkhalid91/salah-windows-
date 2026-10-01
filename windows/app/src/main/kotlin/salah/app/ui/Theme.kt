package salah.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * Colors from the spec, with the macOS app's WCAG AA refinements:
 * a brighter dark-mode accent and a darker light-mode secondary.
 */
@Immutable
data class SalahColors(
    val isDark: Boolean,
    val background: Color,
    val chrome: Color,
    val display: Color,
    val timeline: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val secondary: Color,
    val highlight: Color,
    val onTimeline: Color,
    val onTimelineDim: Color,
    val line: Color,
    val dot: Color,
) {
    companion object {
        val Light = SalahColors(
            isDark = false,
            background = Color(0xFFF3F4F1),
            chrome = Color(0xFFECEDE9),
            display = Color(0xFFE4E7E6),
            timeline = Color(0xFFA10F24),
            accent = Color(0xFFC21D35),
            onAccent = Color(0xFFFFFFFF),
            text = Color(0xFF171717),
            secondary = Color(0xFF5E5E5C),
            highlight = Color(0xFFF0F0EC),
            onTimeline = Color(0xFFFBEEF0),
            onTimelineDim = Color(0xFFE7BDC3),
            line = Color.Black.copy(alpha = 0.12f),
            dot = Color.Black.copy(alpha = 0.05f),
        )
        val Dark = SalahColors(
            isDark = true,
            background = Color(0xFF121212),
            chrome = Color(0xFF1A1B1B),
            display = Color(0xFF1E2020),
            timeline = Color(0xFF7E0C1C),
            accent = Color(0xFFF06377),
            onAccent = Color(0xFF1A0508),
            text = Color(0xFFEDEDEA),
            secondary = Color(0xFF9A9A96),
            highlight = Color(0xFF2A2C2C),
            onTimeline = Color(0xFFFBEEF0),
            onTimelineDim = Color(0xFFD8AFB5),
            line = Color.White.copy(alpha = 0.10f),
            dot = Color.White.copy(alpha = 0.035f),
        )
    }
}

val LocalSalahColors = staticCompositionLocalOf { SalahColors.Light }

/** Shorthand for the current palette inside composables. */
val palette: SalahColors
    @Composable get() = LocalSalahColors.current

/**
 * The bundled Doto pixel font (SIL OFL 1.1), as a static Black instance cut from the variable
 * font: lighter weights shrink the dots until small times read as outlines on Windows' renderer.
 * Used only for times, the countdown and the prayer name on the display.
 */
object PixelFont {
    val family: FontFamily by lazy {
        runCatching { FontFamily(Font("fonts/Doto-Black.ttf", FontWeight.Black)) }.getOrDefault(FontFamily.Monospace)
    }

    @Suppress("UNUSED_PARAMETER")
    fun style(size: Float, weight: FontWeight = FontWeight.Black) = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Black,
        fontSize = size.sp,
        // Tabular, so the countdown doesn't jitter.
        fontFeatureSettings = "tnum",
    )
}

/** Segoe UI on Windows, the platform UI font elsewhere. */
val UiFont: FontFamily = FontFamily.Default

@Composable
fun SalahTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) SalahColors.Dark else SalahColors.Light
    val scheme: ColorScheme = remember(dark) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.display,
            onSurface = colors.text,
            surfaceVariant = colors.display,
            onSurfaceVariant = colors.secondary,
            surfaceContainer = colors.highlight,
            surfaceContainerHigh = colors.highlight,
            surfaceContainerHighest = colors.highlight,
            surfaceContainerLow = colors.background,
            surfaceContainerLowest = colors.background,
            outline = colors.line,
            outlineVariant = colors.line,
        )
    }
    CompositionLocalProvider(LocalSalahColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * The display's fine dot-matrix texture: one 4×4 tile drawn once, then repeated by a shader, so
 * the per-second countdown never redraws the dots.
 */
@Composable
fun DottedBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = palette
    val brush = remember(colors.isDark) {
        val tile = ImageBitmap(4, 4)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(tile), Size(4f, 4f)) {
            drawCircle(colors.dot, radius = 0.6f, center = Offset(2.1f, 2.1f))
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(modifier) { drawRect(brush) }
}
