package dev.framio.pomodoro.ui.theme

import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize

/*
 * Quiet's type, spacing and motion. The colours live in QuietColors.kt, away from the font loading here, so the
 * contrast test runs on the plain JVM.
 */

/**
 * Named families on purpose: below API 28 Compose reaches Roboto Light and Medium only through "sans-serif-light" and
 * "sans-serif-medium" (font weights don't work). System fonts: nothing is loaded.
 */
private val Light = FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
private val Regular = FontFamily(Typeface.create("sans-serif", Typeface.NORMAL))
private val Medium = FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))

/** Tabular digits: a countdown's digits don't shift as they change. */
private const val TNUM = "tnum"

/** Sized for distance: the timer reads from across the room, nothing is smaller than 20 px. */
@Immutable
data class QuietType(
    /** The Full face's countdown (160–320 px band of PLAN §5). */
    val timer: TextStyle = TextStyle(fontFamily = Light, fontSize = 144.sp, lineHeight = 152.sp, fontFeatureSettings = TNUM),
    /** The Digits face: large numbers only (PLAN §5, the top of the 160–320 px band). */
    val timerHuge: TextStyle = TextStyle(fontFamily = Light, fontSize = 260.sp, lineHeight = 270.sp, fontFeatureSettings = TNUM),
    /** Calm's "38 min" and secondary countdowns. */
    val timerSmall: TextStyle = TextStyle(fontFamily = Light, fontSize = 96.sp, lineHeight = 104.sp, fontFeatureSettings = TNUM),
    val display: TextStyle = TextStyle(fontFamily = Light, fontSize = 56.sp, lineHeight = 66.sp),
    val title: TextStyle = TextStyle(fontFamily = Regular, fontSize = 34.sp, lineHeight = 42.sp),
    val body: TextStyle = TextStyle(fontFamily = Regular, fontSize = 26.sp, lineHeight = 34.sp),
    val label: TextStyle = TextStyle(fontFamily = Medium, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.3.sp),
    val caption: TextStyle = TextStyle(fontFamily = Regular, fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = TNUM),
)

@Immutable
data class QuietSpacing(
    val xs: Dp = 8.dp,
    val s: Dp = 16.dp,
    val m: Dp = 24.dp,
    val l: Dp = 40.dp,
    val xl: Dp = 64.dp,
    /** The screen edge. */
    val gutter: Dp = 48.dp,
)

/** Durations in ms. With [reduce] (a setting) only short fades remain (PLAN §5.2). */
@Immutable
data class QuietMotion(val reduce: Boolean = false) {
    val quick: Int get() = if (reduce) 150 else 200
    val screen: Int get() = if (reduce) 200 else 360
    val accent: Int get() = if (reduce) 200 else 400
    val morph: Int get() = if (reduce) 200 else 900
    val breathe: Int get() = if (reduce) 200 else 3_000

    fun <T> screenSpec() = tween<T>(screen, easing = FastOutSlowInEasing)

    /** The outgoing screen fades faster, so the two overlap (and both draw) for less time. */
    fun <T> fadeOutSpec() = tween<T>(screen / 2, easing = FastOutSlowInEasing)
}

val LocalQuietColors = staticCompositionLocalOf { QuietPalettes.Ink }

/** Settings → Appearance → Timer size: how the Full display's face is scaled (Medium = the design size). */
val LocalTimerScale = staticCompositionLocalOf { 1f }

/** Settings → Appearance → Keyboard: type with the system IME instead of the built-in keyboard. */
val LocalSystemKeyboard = staticCompositionLocalOf { false }

fun TimerSize.scale(): Float = when (this) {
    TimerSize.Small -> 0.8f
    TimerSize.Medium -> 1f
    TimerSize.Large -> 1.15f
}
val LocalQuietType = staticCompositionLocalOf { QuietType() }
val LocalQuietSpacing = staticCompositionLocalOf { QuietSpacing() }
val LocalQuietMotion = staticCompositionLocalOf { QuietMotion() }

object QuietTheme {
    val colors: QuietColors
        @Composable @ReadOnlyComposable get() = LocalQuietColors.current
    val type: QuietType
        @Composable @ReadOnlyComposable get() = LocalQuietType.current
    val spacing: QuietSpacing
        @Composable @ReadOnlyComposable get() = LocalQuietSpacing.current
    val motion: QuietMotion
        @Composable @ReadOnlyComposable get() = LocalQuietMotion.current

    /** Minimum touch target: for people standing, reaching across a desk. */
    val Target: Dp = 72.dp

    /** Start, End, Ready and other primary actions. */
    val BigTarget: Dp = 160.dp

    /** The launcher's hot-corner dot sits at 16–72 px bottom-left: this square stays free of anything tappable. */
    val HotCorner: Dp = 88.dp
}

/**
 * The theme for everything inside. [amber] blends the theme into evening amber (0 = the theme, 1 = amber; the root
 * animates it once when the evening starts or ends); [evening] is the shorthand for a settled amber.
 */
@Composable
fun QuietTheme(
    theme: Theme = Theme.Ink,
    evening: Boolean = false,
    reduceMotion: Boolean = false,
    amber: Float = if (evening) 1f else 0f,
    timerSize: TimerSize = TimerSize.Medium,
    systemKeyboard: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(theme, amber) { QuietPalettes.blend(theme, amber) }
    val motion = remember(reduceMotion) { QuietMotion(reduceMotion) }
    CompositionLocalProvider(
        LocalQuietColors provides colors,
        LocalQuietMotion provides motion,
        LocalTimerScale provides timerSize.scale(),
        LocalSystemKeyboard provides systemKeyboard,
        content = content,
    )
}
