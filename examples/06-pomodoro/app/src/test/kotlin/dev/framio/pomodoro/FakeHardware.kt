package dev.framio.pomodoro

import dev.framio.pomodoro.di.DeepHardware
import kotlinx.coroutines.flow.MutableSharedFlow

/** Deep's device side for tests: knocks on demand, and a record of what the screen asked for. */
class FakeHardware : DeepHardware {
    val knockFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val knocks = knockFlow
    val listening = mutableListOf<Boolean>()
    val lights = mutableListOf<Boolean>()

    override fun listen(on: Boolean) {
        listening += on
    }

    override fun light(on: Boolean) {
        lights += on
    }

    val lit: Boolean get() = lights.lastOrNull() == true
    val isListening: Boolean get() = listening.lastOrNull() == true

    fun knock() {
        knockFlow.tryEmit(Unit)
    }
}
