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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.lerp
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
        /** Neutrals from the spec; the timeline and accent come from the chosen [AccentColor]. */
        fun of(accent: AccentColor, dark: Boolean): SalahColors {
            val timeline = Color(if (dark) accent.darkTimeline else accent.lightTimeline)
            val acc = Color(if (dark) accent.darkAccent else accent.lightAccent)
            return if (dark) SalahColors(
                isDark = true,
                background = Color(0xFF121212),
                chrome = Color(0xFF1A1B1B),
                display = Color(0xFF1E2020),
                timeline = timeline,
                accent = acc,
                onAccent = lerp(Color.Black, acc, 0.12f),
                text = Color(0xFFEDEDEA),
                secondary = Color(0xFF9A9A96),
                highlight = Color(0xFF2A2C2C),
                onTimeline = lerp(Color.White, acc, 0.06f),
                onTimelineDim = lerp(Color.White, acc, 0.32f),
                line = Color.White.copy(alpha = 0.10f),
                dot = Color.White.copy(alpha = 0.035f),
            ) else SalahColors(
                isDark = false,
                background = Color(0xFFF3F4F1),
                chrome = Color(0xFFECEDE9),
                display = Color(0xFFE4E7E6),
                timeline = timeline,
                accent = acc,
                onAccent = Color.White,
                text = Color(0xFF171717),
                secondary = Color(0xFF5E5E5C),
                highlight = Color(0xFFF0F0EC),
                onTimeline = lerp(Color.White, timeline, 0.05f),
                onTimelineDim = lerp(Color.White, timeline, 0.28f),
                line = Color.Black.copy(alpha = 0.12f),
                dot = Color.Black.copy(alpha = 0.05f),
            )
        }

        val Light = of(AccentColor.CRIMSON, dark = false)
        val Dark = of(AccentColor.CRIMSON, dark = true)
    }
}

/**
 * Colour themes, the same values as the macOS and Android apps. Crimson is the original Salah red;
 * each keeps the contrast with a deep timeline colour and a brighter accent per mode.
 */
enum class AccentColor(
    val key: String,
    val title: String,
    val lightTimeline: Long,
    val lightAccent: Long,
    val darkTimeline: Long,
    val darkAccent: Long,
) {
    CRIMSON("crimson", "Crimson", 0xFFA10F24, 0xFFC21D35, 0xFF7E0C1C, 0xFFF06377),
    EMERALD("emerald", "Emerald", 0xFF0E6B49, 0xFF128A5E, 0xFF0B4A33, 0xFF4FD6A0),
    TEAL("teal", "Teal", 0xFF0B6E73, 0xFF0E8A90, 0xFF084B4F, 0xFF4FD8DE),
    SAPPHIRE("sapphire", "Sapphire", 0xFF1846A3, 0xFF2459C9, 0xFF12336F, 0xFF7AA7FF),
    INDIGO("indigo", "Indigo", 0xFF2E2F8F, 0xFF3E40B8, 0xFF22236A, 0xFF9A9CFF),
    AMETHYST("amethyst", "Amethyst", 0xFF5B2A9E, 0xFF7239C4, 0xFF3F1D70, 0xFFB48CFF),
    ROSE("rose", "Rose", 0xFFA3175E, 0xFFC41F72, 0xFF6E0F3F, 0xFFFF7AB8),
    AMBER("amber", "Amber", 0xFF9A4F08, 0xFFB8620C, 0xFF6B3906, 0xFFFFB45C),
    OLIVE("olive", "Olive", 0xFF5C5A12, 0xFF77741A, 0xFF403E0C, 0xFFD8CF5C),
    GRAPHITE("graphite", "Graphite", 0xFF2B2F36, 0xFF3D434C, 0xFF24272C, 0xFFB8C2CC),
    // Earth tones from macOS 1.5.
    FOREST("forest", "Forest", 0xFF2F4A3A, 0xFF2F5A41, 0xFF1C2E24, 0xFF8FC1A2),
    EARTH("earth", "Earth", 0xFF5A4A32, 0xFF5E6B3A, 0xFF3E3324, 0xFFB7C48E),
    CLAY("clay", "Clay", 0xFF8C4425, 0xFF9A4A26, 0xFF6E361E, 0xFFE79A76);

    companion object {
        private val prefs by lazy { runCatching { java.util.prefs.Preferences.userRoot().node("salah") }.getOrNull() }

        fun fromKey(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: CRIMSON

        /** The chosen theme, kept per Windows user (like macOS keeps it in user defaults). */
        var current by mutableStateOf(fromKey(runCatching { prefs?.get("accent", null) }.getOrNull()))
            private set

        fun select(a: AccentColor) {
            current = a
            runCatching { prefs?.put("accent", a.key) }
        }
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
    val accent = AccentColor.current
    val colors = remember(accent, dark) { SalahColors.of(accent, dark) }
    val scheme: ColorScheme = remember(colors) {
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
    val brush = remember(colors.dot) {
        val tile = ImageBitmap(4, 4)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(tile), Size(4f, 4f)) {
            drawCircle(colors.dot, radius = 0.6f, center = Offset(2.1f, 2.1f))
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(modifier) { drawRect(brush) }
}
