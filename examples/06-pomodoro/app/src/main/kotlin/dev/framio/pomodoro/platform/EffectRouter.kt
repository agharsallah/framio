package dev.framio.pomodoro.platform

import dev.framio.pomodoro.core.engine.Effect
import dev.framio.pomodoro.core.engine.EffectHandler
import dev.framio.pomodoro.core.engine.Snapshot
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import kotlinx.coroutines.flow.MutableStateFlow

/** Whether Pomodoro is on screen; set by the activity, read by BringToFront. */
class Presence {
    val onScreen = MutableStateFlow(false)
}

/** Where the effects go. Everything here only hands work off: [EffectRouter] runs on the engine's thread. */
interface EffectSinks {
    fun cue(effect: Effect.PlayCue)
    fun display(effect: Effect.SetDisplay)
    fun service(effect: Effect)
    fun appendLog(record: SessionRecord)
    fun persist(snapshot: Snapshot)
    fun saveSettings(settings: Settings)
    fun bringToFront()

    /** The notes journal's share of the engine: parked thoughts and their clearing (DESIGN-history P1). */
    fun notes(effect: Effect) {}

    /** Plays (or with null, fades out) the mode's soundscape: only during focus (core Derive.soundscape). */
    fun soundscape(soundscape: Soundscape?) {}
}

/** Carries out the engine's effects on Android, with an `EVENT|engine|…` line for each one worth seeing in logcat. */
class EffectRouter(private val sinks: EffectSinks, private val presence: Presence) : EffectHandler {
    override fun handle(effect: Effect) {
        when (effect) {
            is Effect.ScheduleWake -> Logs.event("engine", "wake at elapsed ${effect.at.elapsedMs}")
            Effect.CancelWake -> Logs.event("engine", "no wake")
            is Effect.PlayCue -> sinks.cue(effect)
            is Effect.SetDisplay -> sinks.display(effect)
            Effect.StartService, Effect.StopService -> sinks.service(effect)
            is Effect.SetSoundscape -> sinks.soundscape(effect.soundscape)
            is Effect.AppendLog -> {
                Logs.event("log", "${effect.record.outcome} ${effect.record.focused} ${effect.record.modeName}")
                sinks.appendLog(effect.record)
            }
            is Effect.Persist -> sinks.persist(effect.snapshot)
            is Effect.SaveSettings -> sinks.saveSettings(effect.settings)
            is Effect.ThoughtParked, is Effect.ThoughtsCleared -> sinks.notes(effect)
            Effect.BringToFront -> if (ServicePolicy.bringToFront(presence.onScreen.value)) {
                Logs.event("front", "bringing Pomodoro to the front")
                sinks.bringToFront()
            }
        }
    }
}
