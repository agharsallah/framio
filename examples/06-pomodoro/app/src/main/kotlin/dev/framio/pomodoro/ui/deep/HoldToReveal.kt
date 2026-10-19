package dev.framio.pomodoro.ui.deep

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlin.time.Duration

/**
 * Deep's touch lock (TouchPolicy.HoldToReveal): a touch shorter than [hold] is a tap ([onTap], which shows at most a
 * faint hint); holding for [hold] calls [onHold] (the panel) and swallows the rest of that touch. [onPress] says when
 * a finger is down, for the hold's progress line. Movement doesn't matter: a resting palm counts as a hold.
 */
@Composable
fun Modifier.holdToReveal(
    hold: Duration,
    description: String,
    onPress: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: () -> Unit,
): Modifier {
    val press by rememberUpdatedState(onPress)
    val tap by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHold)
    val holdMs = hold.inWholeMilliseconds
    return this
        .semantics {
            contentDescription = description
            onLongClick { held(); true }
        }
        .pointerInput(holdMs) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = true)
                press(true)
                var ended = false
                val up = withTimeoutOrNull(holdMs) {
                    waitForUpOrCancellation().also { ended = true }
                }
                press(false)
                if (ended) {
                    if (up != null) {
                        up.consume()
                        tap()
                    }
                } else {
                    held()
                    // The same finger lifting must not tap whatever the panel put under it
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
}
