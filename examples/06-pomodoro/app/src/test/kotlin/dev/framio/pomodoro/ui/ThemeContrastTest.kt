package dev.framio.pomodoro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.ui.theme.QuietPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN §5: 8 accents at ≥ 4.5:1 on every theme, and text that reads on every surface. */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every accent reaches 4_5 to 1 on every theme's background and surface`() {
        val failures = QuietPalettes.all.flatMapIndexed { t, theme ->
            (0 until Mode.ACCENTS).flatMap { i ->
                listOf(theme.background, theme.surface).mapNotNull { bg ->
                    val c = contrast(theme.accent(i), bg)
                    if (c < 4.5) "theme $t accent $i: ${"%.2f".format(c)}" else null
                }
            }
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    @Test
    fun `there are eight accents per theme`() {
        QuietPalettes.all.forEach { assertEquals(Mode.ACCENTS, it.accentCount) }
    }

    @Test
    fun `ink reads at 7 to 1 and dim ink at 4_5 to 1 on background and surfaces`() {
        QuietPalettes.all.forEach { theme ->
            listOf(theme.background, theme.surface, theme.surfaceHigh).forEach { bg ->
                assertTrue(contrast(theme.ink, bg) >= 7.0)
                assertTrue("dim ${contrast(theme.inkDim, bg)}", contrast(theme.inkDim, bg) >= 4.5)
            }
        }
    }

    @Test
    fun `a filled button's label (background colour on the accent) reads too`() {
        QuietPalettes.all.forEach { theme ->
            (0 until Mode.ACCENTS).forEach { i -> assertTrue(contrast(theme.background, theme.accent(i)) >= 4.5) }
        }
    }
}
