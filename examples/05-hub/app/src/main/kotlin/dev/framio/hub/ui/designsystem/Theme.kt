package dev.framio.hub.ui.designsystem

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.framio.hub.core.model.Palette

/**
 * The Hub's design system, on compose.foundation without Material: a 10" screen read from across a room
 * doesn't suit phone-scale components, and leaving Material out keeps the APK and memory small.
 * 1280×800 at 160 dpi, so 1 dp = 1 px: sizes here are pixels on the frame.
 * Colour names match the launcher and the Lab, so the examples look like one family.
 */
@Immutable
data class HubColors(
    val surface0: Color = Color(0xFF0E0F12),
    val surface1: Color = Color(0xFF16181D),
    val surface2: Color = Color(0xFF1E2127),
    val hairline: Color = Color(0xFF262A31),
    val ink: Color = Color(0xFFF1ECE4),
    val inkDim: Color = Color(0xFF9A968E),
    val inkFaint: Color = Color(0xFF55575D),
    /** Reserved for the sun, daylight and the night clock. */
    val amber: Color = Color(0xFFE9A23B),
    val sky: Color = Color(0xFF6AA8E8),
    val good: Color = Color(0xFF6CC48A),
    val bad: Color = Color(0xFFE5675C),
) {
    /** A person's or timer's colour by palette index. */
    fun person(index: Int) = Color(Palette.argb(index))
}

/** Sizes chosen for distance: the ring clock reads from ~5 m, the sentence from ~3 m, nothing below 20 px. */
@Immutable
data class HubType(
    val clock: TextStyle,
    val display: TextStyle,
    val big: TextStyle,
    val sentence: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

/**
 * Named families on purpose: below API 28 Compose only reaches Roboto Light through "sans-serif-light"
 * (found by the launcher). System fonts, so no font file is loaded.
 */
private val Light = FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
private val Regular = FontFamily(Typeface.create("sans-serif", Typeface.NORMAL))
private val Medium = FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))

/** Tabular digits: a clock's digits don't shift as they change. */
private const val TNUM = "tnum"

private fun hubType(ink: Color) = HubType(
    clock = TextStyle(fontFamily = Light, fontSize = 132.sp, lineHeight = 140.sp, color = ink, fontFeatureSettings = TNUM),
    display = TextStyle(fontFamily = Light, fontSize = 96.sp, lineHeight = 104.sp, color = ink, fontFeatureSettings = TNUM),
    big = TextStyle(fontFamily = Light, fontSize = 64.sp, lineHeight = 72.sp, color = ink, fontFeatureSettings = TNUM),
    sentence = TextStyle(fontFamily = Light, fontSize = 44.sp, lineHeight = 54.sp, color = ink),
    title = TextStyle(fontFamily = Regular, fontSize = 32.sp, lineHeight = 40.sp, color = ink),
    body = TextStyle(fontFamily = Regular, fontSize = 26.sp, lineHeight = 34.sp, color = ink),
    label = TextStyle(fontFamily = Medium, fontSize = 22.sp, lineHeight = 28.sp, color = ink, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    caption = TextStyle(fontFamily = Regular, fontSize = 20.sp, lineHeight = 26.sp, color = ink),
)

/** Motion settings; with [reduce] only fades remain. */
@Immutable
data class HubMotion(val reduce: Boolean = false) {
    val panelMs get() = if (reduce) 150 else 220
    val slidePx get() = if (reduce) 0 else 24
}

val LocalHubColors = staticCompositionLocalOf { HubColors() }
val LocalHubType = staticCompositionLocalOf { hubType(HubColors().ink) }
val LocalHubMotion = staticCompositionLocalOf { HubMotion() }

object Hub {
    val colors: HubColors @Composable get() = LocalHubColors.current
    val type: HubType @Composable get() = LocalHubType.current
    val motion: HubMotion @Composable get() = LocalHubMotion.current

    /** Minimum touch target: bigger than Material's 48 dp, for people standing, reaching, with wet hands. */
    const val TARGET = 72

    /** Main actions. */
    const val BIG_TARGET = 160

    /** The launcher's hot-corner dot sits at 16–72 px in the bottom-left: keep this square free of controls. */
    const val HOT_CORNER = 88
}

@Composable
fun HubTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val colors = HubColors()
    CompositionLocalProvider(
        LocalHubColors provides colors,
        LocalHubType provides hubType(colors.ink),
        LocalHubMotion provides HubMotion(reduceMotion),
        content = content,
    )
}
