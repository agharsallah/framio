package dev.framio.pomodoro.di

import dev.framio.pomodoro.platform.DisplayController
import dev.framio.pomodoro.platform.Logs
import dev.framio.pomodoro.platform.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * What Deep focus needs from the device, so its ViewModel can be tested with a fake: double knocks, the accelerometer
 * switched on only while Deep focus is on screen, and a temporary lift of the dark display (peek, panel, pad, prompt).
 */
interface DeepHardware {
    /** A double knock (or adb `--es cmd peek`). */
    val knocks: Flow<Unit>

    /** Registers (true) or unregisters the accelerometer. Main thread. */
    fun listen(on: Boolean)

    /** Lifts a Calm or Blackout display for a moment (true), or lowers it again. */
    fun light(on: Boolean)

    /** For previews and screens that never knock. */
    object None : DeepHardware {
        override val knocks: Flow<Unit> = emptyFlow()
        override fun listen(on: Boolean) = Unit
        override fun light(on: Boolean) = Unit
    }
}

class AndroidDeepHardware(
    private val motion: Motion,
    private val display: DisplayController,
    private val scope: CoroutineScope,
) : DeepHardware {
    private val peeks = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    override val knocks: Flow<Unit> = merge(motion.doubles, peeks)

    override fun listen(on: Boolean) = motion.listen(on)

    override fun light(on: Boolean) = display.light(on)

    /** adb `--es cmd peek`: a peek without knocking (skips the detector). */
    fun peekNow() {
        Logs.event("adb", "peek")
        peeks.tryEmit(Unit)
    }

    /**
     * Debug adb `--es cmd knock`: a synthetic double knock through the real detector; needs the sensor registered.
     * The adb intent itself pauses and resumes the activity, which unregisters and registers the sensor again, so
     * this waits up to 3 s for it to be back.
     */
    fun knock() {
        scope.launch(Dispatchers.Main) {
            repeat(60) {
                if (motion.listening && motion.injectDoubleKnock()) {
                    Logs.event("adb", "knock: injected")
                    return@launch
                }
                delay(50)
            }
            Logs.event("adb", "knock: not listening (no Deep focus on screen)")
        }
    }
}
