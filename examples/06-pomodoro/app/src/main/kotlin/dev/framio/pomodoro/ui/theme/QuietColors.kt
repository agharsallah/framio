package dev.framio.pomodoro.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.Theme

/**
 * *Quiet*, Pomodoro's design system (PLAN §5), on compose.foundation without Material. The frame is 1280×800 at
 * 160 dpi, so 1 dp = 1 px: every size here is pixels on the frame.
 */
@Immutable
data class QuietColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val ink: Color,
    val inkDim: Color,
    /** Decoration only (ring tracks, hairlines): never text. */
    val inkFaint: Color,
    val light: Boolean,
    private val accents: List<Color>,
) {
    /** A mode's accent by palette index (0..7), in this theme's tone. */
    fun accent(index: Int): Color = accents[index.mod(accents.size)]

    val accentCount: Int get() = accents.size
}

/**
 * Eight accent hues. Each has a light tone for the dark themes and a deep tone for Paper: no single colour can reach
 * 4.5:1 against both near-black and off-white (it would need a luminance both ≤ 0.15 and ≥ 0.23), so "8 accents that
 * pass 4.5:1 on every theme" means 8 hues, each in the tone its theme needs. ThemeContrastTest checks every pair.
 * 0 is the tile's coral (#E5735C).
 */
private val DarkAccents = listOf(
    Color(0xFFE5735C), // 0 coral (Classic)
    Color(0xFF9AA8FF), // 1 periwinkle (Deep)
    Color(0xFF4FC1B0), // 2 teal (Flow)
    Color(0xFFE2B44E), // 3 gold (Gentle)
    Color(0xFFF28DB4), // 4 rose
    Color(0xFF6DB8F2), // 5 sky
    Color(0xFF86C98A), // 6 green
    Color(0xFFC79BE0), // 7 lilac
)

private val PaperAccents = listOf(
    Color(0xFFB23A22),
    Color(0xFF3F51B5),
    Color(0xFF00796B),
    Color(0xFF8A5A00),
    Color(0xFFAD1457),
    Color(0xFF1565C0),
    Color(0xFF2B6E2F),
    Color(0xFF7B1FA2),
)

/**
 * Evening amber's accents: each hue pulled halfway to amber and its blue taken out, so the modes stay told apart (and by
 * name, never colour alone) while nothing on screen is cold (PLAN §3.2 row 13).
 */
private val EveningAccents = DarkAccents.map { c ->
    val amber = Color(0xFFE8A65A)
    Color(
        red = c.red + (amber.red - c.red) * 0.5f,
        green = c.green + (amber.green - c.green) * 0.5f,
        blue = (c.blue + (amber.blue - c.blue) * 0.5f) * 0.7f,
    )
}

object QuietPalettes {
    val Ink = QuietColors(
        background = Color(0xFF0E1116), surface = Color(0xFF171B22), surfaceHigh = Color(0xFF212630), line = Color(0xFF2A303A),
        ink = Color(0xFFE8E6E1), inkDim = Color(0xFFA3A19C), inkFaint = Color(0xFF3A404B), light = false, accents = DarkAccents,
    )
    val Paper = QuietColors(
        background = Color(0xFFF3EFE6), surface = Color(0xFFFBF8F2), surfaceHigh = Color(0xFFE9E4D8), line = Color(0xFFD8D2C4),
        ink = Color(0xFF1D1B18), inkDim = Color(0xFF57534C), inkFaint = Color(0xFFCFC8BA), light = true, accents = PaperAccents,
    )
    val Forest = QuietColors(
        background = Color(0xFF0F2119), surface = Color(0xFF152B21), surfaceHigh = Color(0xFF1D382C), line = Color(0xFF284838),
        ink = Color(0xFFE6EEE7), inkDim = Color(0xFFA5B8AB), inkFaint = Color(0xFF2F5241), light = false, accents = DarkAccents,
    )
    val Tomato = QuietColors(
        background = Color(0xFF2B0F0C), surface = Color(0xFF381511), surfaceHigh = Color(0xFF461C17), line = Color(0xFF582820),
        ink = Color(0xFFF6E9E4), inkDim = Color(0xFFD0ABA2), inkFaint = Color(0xFF5E2F27), light = false, accents = DarkAccents,
    )

    /** Applied automatically after the evening start (M5), never chosen: warm, dim, no blue. */
    val Evening = QuietColors(
        background = Color(0xFF120C07), surface = Color(0xFF1B140C), surfaceHigh = Color(0xFF241A10), line = Color(0xFF33251A),
        ink = Color(0xFFF0D9B5), inkDim = Color(0xFFB89A74), inkFaint = Color(0xFF3D2E20), light = false, accents = EveningAccents,
    )

    fun of(theme: Theme, evening: Boolean = false): QuietColors = if (evening) {
        Evening
    } else {
        when (theme) {
            Theme.Ink -> Ink
            Theme.Paper -> Paper
            Theme.Forest -> Forest
            Theme.Tomato -> Tomato
        }
    }

    val all: List<QuietColors> get() = listOf(Ink, Paper, Forest, Tomato, Evening)

    /**
     * [theme] blended [amber] (0..1) of the way into [Evening]: the once-a-day transition. 0 and 1 return the palettes
     * themselves (no new objects while nothing moves).
     */
    fun blend(theme: Theme, amber: Float): QuietColors {
        val day = of(theme)
        return when {
            amber <= 0f -> day
            amber >= 1f -> Evening
            else -> day.mix(Evening, amber)
        }
    }
}

private fun Color.mix(to: Color, t: Float) = lerp(this, to, t)

private fun QuietColors.mix(to: QuietColors, t: Float) = QuietColors(
    background = background.mix(to.background, t),
    surface = surface.mix(to.surface, t),
    surfaceHigh = surfaceHigh.mix(to.surfaceHigh, t),
    line = line.mix(to.line, t),
    ink = ink.mix(to.ink, t),
    inkDim = inkDim.mix(to.inkDim, t),
    inkFaint = inkFaint.mix(to.inkFaint, t),
    light = if (t < 0.5f) light else to.light,
    accents = (0 until accentCount).map { accent(it).mix(to.accent(it), t) },
)

/** A mode's accent, honouring the settings' override. */
fun QuietColors.accentOf(mode: Mode, override: Int?): Color = accent(override ?: mode.accent)
