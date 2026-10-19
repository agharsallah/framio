package dev.framio.pomodoro.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colours for data (DESIGN-history §3, §8), from the `dataviz` reference palette and validated against Quiet's own
 * surfaces with its `validate_palette.js` (LOG "History + notes · Phase A"): the 8 categorical slots identify reason
 * tags (dot beside the label, never the text), one hue carries every single-series chart, and a one-hue ordinal
 * ramp shows how sessions ended. Dark themes take the dark steps; Paper the light ones.
 */
@Immutable
data class ChartColors(
    /** Categorical slots 0..7 (reason tags), in the palette's validated order. */
    private val slots: List<Color>,
    /** The one hue of single-series charts (bars, columns, sparklines, heatmap). */
    val data: Color,
    /** Done → Partly → Not yet → Unrated, most to least. */
    val outcomes: List<Color>,
    /** Ended early and interrupted: neutral, never a status red (no shaming). */
    val neutral: Color,
    val neutralDeep: Color,
    /** Hairline grid and baseline. */
    val grid: Color,
) {
    /** A tag's dot colour; [hue] outside 0..7 is the neutral gray. */
    fun tag(hue: Int): Color = slots.getOrElse(hue) { neutral }

    /** A step of the data hue for magnitude (heatmap): 0 = faint, 1 = full. */
    fun sequential(t: Float, surface: Color): Color = lerp(surface, data, 0.18f + 0.82f * t.coerceIn(0f, 1f))

    companion object {
        val Dark = ChartColors(
            slots = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map(::Color),
            data = Color(0xFF3987E5),
            outcomes = listOf(0xFFCDE2FB, 0xFF9EC5F4, 0xFF6DA7EC, 0xFF3987E5).map(::Color),
            neutral = Color(0xFF8A8F98),
            neutralDeep = Color(0xFF5C626C),
            grid = Color(0xFF2C2C2A),
        )
        val Light = ChartColors(
            slots = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map(::Color),
            data = Color(0xFF2A78D6),
            outcomes = listOf(0xFF184F95, 0xFF256ABF, 0xFF3987E5, 0xFF6DA7EC).map(::Color),
            neutral = Color(0xFF898781),
            neutralDeep = Color(0xFFB9B6AE),
            grid = Color(0xFFE1E0D9),
        )

        fun of(colors: QuietColors): ChartColors = if (colors.light) Light else Dark

        private fun lerp(a: Color, b: Color, t: Float) = Color(
            a.red + (b.red - a.red) * t,
            a.green + (b.green - a.green) * t,
            a.blue + (b.blue - a.blue) * t,
            1f,
        )
    }
}
