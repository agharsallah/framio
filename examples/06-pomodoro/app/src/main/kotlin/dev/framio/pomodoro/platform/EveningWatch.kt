package dev.framio.pomodoro.platform

import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.time.EngineClock
import dev.framio.pomodoro.core.time.Evening
import dev.framio.pomodoro.core.time.Now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Whether evening amber is on ([on]): core [Evening] on the engine's clock and the settings' window. It sleeps until
 * the next start or end (at most [RECHECK], which also covers an untrusted clock that later becomes trusted without a
 * broadcast), and looks again at once on [recheck] (a time change) or a settings change. No per-minute polling.
 *
 * The hooks build can [fake] the local time it judges by (adb `--es evening 21:30`), so the amber can be checked on the
 * frame without touching the system clock.
 */
class EveningWatch(private val clock: EngineClock, state: StateFlow<FocusState>, scope: CoroutineScope) {
    private val poke = MutableStateFlow(0)
    private val offsetMs = MutableStateFlow(0L)
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    init {
        scope.launch {
            val window = state.map { s -> Triple(s.loaded, s.settings.amberFrom, s.settings.eveningEnd) }.distinctUntilChanged()
            combine(window, poke, offsetMs) { w, _, offset -> w to offset }.collectLatest { (w, offset) ->
                val (loaded, from, end) = w
                if (!loaded) return@collectLatest
                while (true) {
                    val now = shifted(clock.now(), offset)
                    val amber = Evening.at(now, from, end)
                    if (amber != _on.value) Logs.event("evening", if (amber) "amber on" else "amber off")
                    _on.value = amber
                    val next = Evening.nextChange(now, from, end)?.coerceAtMost(RECHECK) ?: RECHECK
                    delay(next + 1.seconds)
                }
            }
        }
    }

    /** The clock or zone changed: judge again now. */
    fun recheck() {
        poke.value++
    }

    /** Hooks builds: judge as if the local time now were [at] (null: the real time). */
    fun fake(at: LocalTime?) {
        val now = clock.now()
        val wall = now.wallMs
        if (at == null || wall == null) {
            offsetMs.value = 0
            Logs.event("evening", if (at == null) "fake time off" else "fake time needs a trusted clock")
            return
        }
        val local = Instant.ofEpochMilli(wall).atZone(now.zone).toLocalTime()
        offsetMs.value = local.until(at, ChronoUnit.MILLIS).mod(DAY_MS)
        Logs.event("evening", "judging as if it were $at")
    }

    private fun shifted(now: Now, offset: Long): Now = if (offset == 0L || now.wallMs == null) now else now.copy(wallMs = now.wallMs?.plus(offset))

    private companion object {
        val RECHECK = 15.minutes
        const val DAY_MS = 86_400_000L
    }
}
