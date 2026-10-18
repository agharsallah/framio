package dev.framio.mawaqit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Every screen is laid out for the frame's 1280×800 (800×1280 standing). Any other window, like a navigation bar
 * shown by the firmware, a split screen or another device, gets that same layout scaled to fit and centred, so nothing
 * clips and nothing reflows into gaps. On the frame the scale is exactly 1. Font scale is pinned to 1 for the same
 * reason: a display read from across the room is sized by design, not by the system font setting.
 */
@Composable
fun FitToDesign(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val landscape = maxWidth >= maxHeight
        val w = if (landscape) 1280.dp else 800.dp
        val h = if (landscape) 800.dp else 1280.dp
        val scale = min(maxWidth / w, maxHeight / h)
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density * scale, fontScale = 1f)) {
            Box(Modifier.size(w, h)) { content() }
        }
    }
}
