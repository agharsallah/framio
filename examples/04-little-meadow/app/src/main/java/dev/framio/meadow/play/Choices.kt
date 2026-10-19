package dev.framio.meadow.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.wobble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A row of big cards, one (or more) of them right: the most common question in the meadow.
 * Handles the whole answer: a right tap glows, praises and after a moment calls [onRight];
 * a wrong tap tilts softly and says "not quite" (the second miss turns the hint on, which
 * pulses around the right card). Cards come in one after another, gently. A new [options]
 * list starts a new round.
 */
@Composable
fun <T> ChoiceRow(
    scope: PlayScope,
    options: List<T>,
    isRight: (T) -> Boolean,
    modifier: Modifier = Modifier,
    cardSize: Dp = 220.dp,
    spacing: Dp = 36.dp,
    cardColor: (T) -> Color = { Palette.cream },
    /** Said when a card is tapped, before judging (e.g. its colour name). Null = nothing. */
    onTapSay: ((T) -> String?)? = null,
    onRight: (option: T, firstTry: Boolean) -> Unit,
    content: @Composable BoxScope.(T) -> Unit,
) {
    androidx.compose.runtime.key(options) {
        var tries by remember { mutableIntStateOf(0) }
        var solved by remember { mutableStateOf<T?>(null) }
        val wobbles = remember { mutableStateMapOf<Int, Int>() }
        val co = rememberCoroutineScope()
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, opt ->
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(90L * i)
                    enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                }
                val right = isRight(opt)
                val dim by animateFloatAsState(if (solved != null && solved != opt) 0.45f else 1f, tween(400), label = "dim")
                val grow by animateFloatAsState(if (solved == opt) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "grow")
                SoftCard(
                    cardColor(opt),
                    Modifier
                        .size(cardSize)
                        .graphicsLayer {
                            alpha = enter.value * dim
                            val s = (0.85f + 0.15f * enter.value) * grow
                            scaleX = s
                            scaleY = s
                            translationY = (1f - enter.value) * 40f
                        }
                        .wobble(wobbles[i] ?: 0)
                        .halo(solved == opt, Palette.honey)
                        .hintPulse(scope.hint && right && solved == null)
                        .pressable(enabled = solved == null) {
                            onTapSay?.invoke(opt)?.let { scope.say(it) }
                            if (right) {
                                solved = opt
                                val first = tries == 0
                                co.launch {
                                    if (onTapSay != null) delay(700)
                                    scope.praiseAndWait()
                                    delay(250)
                                    onRight(opt, first)
                                }
                            } else {
                                tries++
                                wobbles[i] = (wobbles[i] ?: 0) + 1
                                co.launch {
                                    if (onTapSay != null) delay(800)
                                    scope.notQuite()
                                }
                            }
                        },
                ) { content(opt) }
            }
        }
    }
}
