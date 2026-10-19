package dev.framio.hub.core.engine

import dev.framio.hub.core.model.HubState
import java.time.ZoneId

/**
 * The time as the engine sees it. [elapsedMs] is `SystemClock.elapsedRealtime()` (never jumps, resets at boot,
 * identified by [bootId]). [wallMs] is null until the clock is trusted (see TimeGate): the frame has no RTC
 * and wakes up at 01-01 01:00.
 */
data class Now(val elapsedMs: Long, val bootId: String, val wallMs: Long?, val zone: ZoneId)

/** What reduce() sees: wire commands plus events only the frame itself produces. */
sealed interface Input {
    data class Wire(val command: Command) : Input

    /** Time passed: timers may end, notes expire, routines reset. Sent by the scheduler. */
    data object Tick : Input

    /** Two knocks on the frame. Stops a ringing timer; everything else about knocks is UI. */
    data object DoubleKnock : Input

    /** The wall clock just became trusted: fill in wall times that were unknown until now. */
    data object TimeTrusted : Input
}

sealed interface Effect {
    /** Start (or switch to) the alarm for the timer now on top of the ringing stack, and show the Alert. */
    data class Ring(val timerId: String, val melody: Int) : Effect

    /** Nothing rings any more. */
    data object Silence : Effect

    /** A short sound for something that happened. Already filtered by quiet hours. */
    data class Play(val cue: Cue) : Effect

    /** The state changed: tell phones listening for updates. */
    data class Changed(val rev: Long) : Effect

    /** Store (or, with null, delete) a secret, such as a calendar URL. */
    data class PutSecret(val key: String, val value: String?) : Effect

    /** A scribble's strokes file is no longer used. */
    data class DeleteScribble(val fileId: String) : Effect
}

enum class Cue { NoteArrived, RoutineDone, StepDone }

/** The result of one input. [rejected] says why a command did nothing (for the API's response and the log). */
data class Next(val state: HubState, val effects: List<Effect> = emptyList(), val rejected: String? = null)

/** Persists [HubState]. [offer] must not block: the engine calls it on every change. */
interface StateStore {
    suspend fun load(): HubState
    fun offer(state: HubState)
}

fun interface HubClock {
    fun now(): Now
}

fun interface EffectHandler {
    fun handle(effect: Effect)
}
