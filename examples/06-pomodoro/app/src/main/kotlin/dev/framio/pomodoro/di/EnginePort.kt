package dev.framio.pomodoro.di

import dev.framio.pomodoro.core.engine.EngineConfig
import dev.framio.pomodoro.core.engine.FocusEngine
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.time.EngineClock
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.platform.Logs
import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI sees of the engine: its state, the clock it reads, and an inbox. ViewModels depend on this, so their
 * tests run against a fake that applies the real reducer on virtual time.
 */
interface EnginePort {
    val state: StateFlow<FocusState>
    val config: EngineConfig

    fun now(): Now

    /** Queues [input]; the engine applies it on its own thread. */
    fun send(input: Input)
}

class EngineAdapter(private val engine: FocusEngine, private val clock: EngineClock) : EnginePort {
    override val state: StateFlow<FocusState> get() = engine.state
    override val config: EngineConfig get() = engine.config

    override fun now(): Now = clock.now()

    override fun send(input: Input) {
        if (!engine.dispatch(input)) Logs.warn("engine", "inbox full, dropped ${input::class.simpleName}")
    }
}
