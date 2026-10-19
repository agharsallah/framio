package dev.framio.pomodoro

import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.engine.EngineConfig
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.reduce
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.EnginePort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * A fake engine for ViewModel tests: the real reducer, applied synchronously on a [FakeClock], with every input
 * recorded. No coroutines, no scheduler: [pass] moves time and fires the deadline like the engine's scheduler would.
 */
class FakePort(
    val clock: FakeClock = FakeClock(),
    settings: Settings = Settings(selectedMode = Presets.CLASSIC_ID),
    override val config: EngineConfig = EngineConfig.Default,
) : EnginePort {
    private val _state = MutableStateFlow(reduce(FocusState(), Input.Restore(null, settings), clock.now(), config).state)
    override val state: StateFlow<FocusState> = _state.asStateFlow()
    val sent = mutableListOf<Input>()
    val rejected = mutableListOf<String>()

    override fun now(): Now = clock.now()

    override fun send(input: Input) {
        sent += input
        val next = reduce(_state.value, input, clock.now(), config)
        if (next.rejected != null) rejected += next.rejected!! else _state.value = next.state
    }

    fun pass(d: Duration) {
        clock.advance(d)
        send(Input.DeadlineReached)
    }
}
