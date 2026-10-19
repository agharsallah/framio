package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * The shape of a screen that is still loading (History, Insights): still blocks in the surface colour where the
 * content will be, so the screen is there at once and the content doesn't jump in from nothing. Static: no shimmer
 * (on this GPU a moving gradient redraws the whole screen every frame). [label] is what a screen reader hears.
 */
@Composable
fun SkeletonCards(label: String, landscape: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SkeletonBar(56.dp, Modifier.width(420.dp))
        repeat(2) {
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    SkeletonBar(240.dp, Modifier.weight(1f), radius = 24.dp)
                    SkeletonBar(240.dp, Modifier.weight(1f), radius = 24.dp)
                }
            } else {
                SkeletonBar(240.dp, Modifier.fillMaxWidth(), radius = 24.dp)
            }
        }
    }
}

/** A list still loading: a few rows' worth of still bars. */
@Composable
fun SkeletonRows(label: String, modifier: Modifier = Modifier, rows: Int = 6) {
    Column(modifier.semantics { contentDescription = label }.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeletonBar(28.dp, Modifier.width(180.dp))
        repeat(rows) { SkeletonBar(72.dp, Modifier.fillMaxWidth(), radius = 16.dp) }
    }
}

@Composable
private fun SkeletonBar(height: Dp, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    val color = QuietTheme.colors.surface
    Spacer(
        modifier.height(height).drawBehind {
            val r = radius.toPx()
            drawRoundRect(color, cornerRadius = CornerRadius(r, r))
        },
    )
}
