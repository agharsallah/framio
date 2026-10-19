package dev.framio.meadow.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Things a grown-up can do from afar (dashboard, adb). The UI collects them on the main thread. */
sealed interface Command {
    data object Home : Command
    data class OpenWorld(val id: String) : Command
    data class OpenPlay(val id: String) : Command
    /** "Pip is resting": the screen shows a calm rest scene until unlocked. */
    data class Lock(val on: Boolean) : Command
    /** Extra play minutes today on top of the daily limit. */
    data class Bonus(val minutes: Int) : Command
    /** Start the wiggle break now. */
    data object Wiggle : Command
    /** Start the night light now, whatever the time. */
    data object NightLight : Command
    /** A star from far away: a little celebration with a sticker. */
    data object Star : Command
    /** Play a family voice message (or show a photo) now. */
    data class Message(val id: String) : Command
    /** Say a narration clip, e.g. "call_dinner". */
    data class Say(val voiceId: String) : Command
    /** Wake the screen (backlight back on) and leave the night light / rest scene if allowed. */
    data object Wake : Command
}

/**
 * A queue with one consumer (MeadowRoot): commands sent before the UI is up (adb extras in onCreate,
 * the dashboard while the frame boots) wait instead of being dropped.
 */
class Commands {
    private val channel = Channel<Command>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<Command> = channel.receiveAsFlow()

    fun send(c: Command) {
        channel.trySend(c)
    }
}
