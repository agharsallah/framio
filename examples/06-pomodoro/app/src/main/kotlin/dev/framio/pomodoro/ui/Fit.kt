package dev.framio.pomodoro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** True when the design is 1280×800 (landscape), false for 800×1280 (the frame standing). */
val LocalLandscape = staticCompositionLocalOf { true }

/**
 * Every screen is laid out for the frame's 1280×800 (800×1280 standing). Any other window (a firmware bar, split
 * screen, another device) gets that same layout scaled to fit and centred, so nothing clips or reflows. On the frame
 * the scale is exactly 1. Font scale is pinned to 1: a timer read from across the room is sized by design, not by
 * the system font setting. (From 04-mawaqit `ui/Fit.kt`.)
 */
@Composable
fun FitToDesign(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val landscape = maxWidth >= maxHeight
        val w = if (landscape) 1280.dp else 800.dp
        val h = if (landscape) 800.dp else 1280.dp
        val scale = min(maxWidth / w, maxHeight / h)
        val density = LocalDensity.current.density
        CompositionLocalProvider(
            LocalDensity provides Density(density * scale, fontScale = 1f),
            LocalLandscape provides landscape,
        ) {
            Box(Modifier.size(w, h)) { content() }
        }
    }
}
