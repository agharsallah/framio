package dev.framio.pomodoro.ui

import androidx.compose.runtime.Immutable
import dev.framio.pomodoro.core.engine.EngineConfig
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.engine.Timing
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.EnginePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration

/** Keeps a screen's state while its UI is away for up to 5 s (rotation, a quick trip to another app). */
fun <T> Flow<T>.stateForUi(scope: CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

/**
 * What a countdown needs, frozen at the last state change: the face asks it for the time left and the progress at
 * any [Now] (PLAN §6.1: the engine has no ticker, the UI derives the time itself).
 */
@Immutable
data class FaceTiming(private val state: FocusState, private val config: EngineConfig) {
    fun left(now: Now): Duration? = Timing.left(state, now, config)

    fun focused(now: Now): Duration? = Timing.focused(state, now, config)

    fun progress(now: Now): Float = Timing.progress(state, now, config)

    /**
     * Progress in whole minutes (Calm): it moves only when the minutes shown change, so re-reading the clock between
     * ticks never nudges the arc (each nudge re-rasterizes its path on this GPU, LOG M3).
     */
    fun minuteProgress(now: Now): Float {
        val planned = state.session?.planned ?: return 0f
        val left = left(now) ?: return 0f
        if (!planned.isPositive()) return 1f
        val shown = kotlin.math.ceil(left.inWholeMilliseconds / 60_000.0) * 60_000.0
        return (1.0 - shown / planned.inWholeMilliseconds).toFloat().coerceIn(0f, 1f)
    }

    /** The deadline's elapsed time, so tickers turn over on its second boundaries; 0 when there is none. */
    val anchorMs: Long
        get() = when (val p = state.phase) {
            is Phase.Focus -> p.deadline?.elapsedMs ?: p.startedAt.elapsedMs
            is Phase.Break -> p.deadline.elapsedMs
            else -> 0L
        }

    companion object {
        val None = FaceTiming(FocusState(), EngineConfig.Default)
    }
}

fun EnginePort.timing(s: FocusState): FaceTiming = FaceTiming(s, config)
