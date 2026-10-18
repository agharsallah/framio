package dev.framio.mawaqit.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import dev.framio.mawaqit.R

// Laid out for the frame: 1280×800 at 160 dpi, so 1.dp == 1 px. Sizes are meant to read from across a room.

@Immutable
data class Palette(
    val id: String,
    val top: Color,
    val bottom: Color,
    /** Lines of the geometric pattern. */
    val pattern: Color,
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    /** Cards and hairlines over the background. */
    val card: Color,
    val hairline: Color,
    /** The next prayer, countdowns, the current phase. */
    val accent: Color,
    /** Arabic names and secondary highlights. */
    val accent2: Color,
    val onAccent: Color,
    val light: Boolean = false,
)

object Palettes {
    val emerald = Palette(
        "emerald", top = Color(0xFF0C3D32), bottom = Color(0xFF03150F), pattern = Color(0x14E3B25A),
        ink = Color(0xFFF5EFE4), inkDim = Color(0xFFAFC4BA), inkFaint = Color(0xFF5E7F73),
        card = Color(0x12FFFFFF), hairline = Color(0x22FFFFFF), accent = Color(0xFFE6B65C), accent2 = Color(0xFF8FD6BF),
        onAccent = Color(0xFF1A1405),
    )
    val night = Palette(
        "night", top = Color(0xFF141A2E), bottom = Color(0xFF05060B), pattern = Color(0x12AFC2FF),
        ink = Color(0xFFEDEFF7), inkDim = Color(0xFFA2A9C2), inkFaint = Color(0xFF555C78),
        card = Color(0x10FFFFFF), hairline = Color(0x1FFFFFFF), accent = Color(0xFFF0C674), accent2 = Color(0xFFAFC2FF),
        onAccent = Color(0xFF1B1608),
    )
    val sand = Palette(
        "sand", top = Color(0xFFF7F0E3), bottom = Color(0xFFE6D8BF), pattern = Color(0x1A8A6A2E),
        ink = Color(0xFF2A241B), inkDim = Color(0xFF6E6250), inkFaint = Color(0xFFA6977E),
        card = Color(0x8CFFFFFF), hairline = Color(0x22000000), accent = Color(0xFF16705A), accent2 = Color(0xFF9A6B1F),
        onAccent = Color(0xFFFFFFFF), light = true,
    )
    val ocean = Palette(
        "ocean", top = Color(0xFF0D3149), bottom = Color(0xFF031019), pattern = Color(0x145FD3E6),
        ink = Color(0xFFEFF6F8), inkDim = Color(0xFFA3C0CB), inkFaint = Color(0xFF507484),
        card = Color(0x12FFFFFF), hairline = Color(0x22FFFFFF), accent = Color(0xFFF2C46D), accent2 = Color(0xFF7FDCEB),
        onAccent = Color(0xFF1A1405),
    )

    fun of(id: String) = when (id) {
        "night" -> night
        "sand" -> sand
        "ocean" -> ocean
        else -> emerald // "mosque" uses emerald's colors over the mosque's picture
    }
}

val LocalPalette = staticCompositionLocalOf { Palettes.emerald }

object Fonts {
    /** Below API 28 Compose only reaches Roboto Light/Thin through these named families. */
    val light = FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL).let { androidx.compose.ui.text.font.Typeface(it) })
    val regular = FontFamily.SansSerif
    val condensed = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL).let { androidx.compose.ui.text.font.Typeface(it) })

    /** Amiri (SIL OFL): a Naskh made for long Arabic text; the system's Noto Naskh lacks the Quranic marks. */
    val arabic = FontFamily(Font(R.font.amiri_regular, FontWeight.Normal), Font(R.font.amiri_bold, FontWeight.Bold))
}

fun TextStyle.arabic() = copy(fontFamily = Fonts.arabic, textDirection = TextDirection.Rtl)
