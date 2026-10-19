package dev.framio.hub.core.engine

import dev.framio.hub.core.board.Expiry
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.time.LocalTimes
import dev.framio.hub.core.timers.remainingMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When the engine next needs a [Input.Tick]. Pure. */
object Wakeups {
    /** Ms until the earliest timer end, auto-silence, chip cleanup, note expiry or routine reset; null if none. */
    fun nextDelayMs(s: HubState, now: Now): Long? {
        val candidates = mutableListOf<Long>()
        for (t in s.timers) {
            when (val run = t.run) {
                is Run.Running -> run.until.remainingMs(now)?.let { candidates += it }
                is Run.Ringing -> run.since.remainingMs(now)?.let { candidates += it + Rules.RING_MS }
                is Run.Done -> run.at.remainingMs(now)?.let { candidates += it + Rules.DONE_KEEP_MS }
                is Run.Paused, Run.Interrupted -> Unit
            }
        }
        val wall = now.wallMs
        if (wall != null) {
            s.notes.mapNotNullTo(candidates) { it.expiresWallMs?.minus(wall) }
            s.routines.mapTo(candidates) { r ->
                LocalTimes.nextOccurrence(LocalTimes.parse(r.resetAt) ?: Expiry.END_OF_DAY, wall, now.zone) - wall
            }
        }
        return candidates.minOrNull()?.coerceAtLeast(0)
    }
}

/**
 * Sends [Input.Tick] exactly when something is due, recomputed whenever the state changes or [poke] is
 * called (clock or time zone changed). No AlarmManager or WorkManager: this frame's CPU never sleeps, and
 * they can't restart a killed process on this firmware anyway (03 LOG).
 */
class Scheduler(private val engine: HubEngine, private val clock: HubClock, scope: CoroutineScope) {
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    fun poke() {
        pokes.trySend(Unit)
    }

    init {
        scope.launch {
            combine(engine.loaded, engine.state, pokes.consumeAsFlow().onStart { emit(Unit) }) { loaded, _, _ -> loaded }.collectLatest { loaded ->
                if (!loaded) return@collectLatest
                engine.submit(Input.Tick) // catch up first: something may have become due while we were away
                while (true) {
                    val wait = Wakeups.nextDelayMs(engine.state.value, clock.now()) ?: MAX_SLEEP_MS
                    delay(wait.coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS))
                    engine.submit(Input.Tick)
                }
            }
        }
    }

    companion object {
        /** Never wait longer than this, as a safety net; a Tick that changes nothing costs microseconds. */
        const val MAX_SLEEP_MS = 5 * 60_000L
        private const val MIN_SLEEP_MS = 10L
    }
}
