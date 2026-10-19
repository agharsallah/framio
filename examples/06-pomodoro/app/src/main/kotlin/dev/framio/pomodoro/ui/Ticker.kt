package dev.framio.pomodoro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.framio.pomodoro.core.time.Now
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The time for a countdown on screen: one ticker per screen, running only while the activity is RESUMED (PLAN §6.1).
 * It wakes exactly when the displayed value changes: every [period], aligned to [anchorMs] (the deadline's elapsed
 * time), so "24:59" turns over on the real second boundary of the deadline, and nothing runs in between.
 */
@Composable
fun rememberNow(now: () -> Now, period: Duration, anchorMs: Long): State<Now> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = now(), lifecycle, period, anchorMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val p = period.inWholeMilliseconds.coerceAtLeast(MIN_PERIOD_MS)
            while (true) {
                val n = now()
                value = n
                val untilBoundary = (anchorMs - n.elapsedMs).mod(p)
                delay((if (untilBoundary < MIN_PERIOD_MS) untilBoundary + p else untilBoundary) + LATE_MS)
            }
        }
    }
}

private const val MIN_PERIOD_MS = 50L

/** Wake just after the boundary, so the value has certainly changed. */
private const val LATE_MS = 3L
