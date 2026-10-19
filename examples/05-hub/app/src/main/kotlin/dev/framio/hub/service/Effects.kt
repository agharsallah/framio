package dev.framio.hub.service

import android.content.Context
import android.content.Intent
import dev.framio.hub.HubActivity
import dev.framio.hub.core.engine.Effect
import dev.framio.hub.core.engine.EffectHandler
import dev.framio.hub.data.ScribbleFiles
import dev.framio.hub.data.SecretsStore
import dev.framio.hub.platform.Logs
import dev.framio.hub.platform.SoundEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the Hub is on screen and how it lies; set by the activity, read by the service and effects. */
class Presence {
    val onScreen = MutableStateFlow(false)

    /** Face down: everything but alerts is muted. */
    val asleep = MutableStateFlow(false)

    /** Long press on Exit: stop the service too, until the Hub is opened again. */
    val quit = MutableStateFlow(false)
}

/**
 * Carries out the engine's effects on Android. Called on the engine's coroutine, so everything here only
 * hands work off (the sound engine has its own thread; disk work is launched).
 */
class EffectRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sound: SoundEngine,
    private val secrets: SecretsStore,
    private val scribbles: ScribbleFiles,
    private val presence: Presence,
    private val onSecretChanged: () -> Unit,
) : EffectHandler {
    private val _changes = MutableStateFlow(0L)

    /** The latest rev, for listeners (LAN SSE, the relay). */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    override fun handle(effect: Effect) {
        when (effect) {
            is Effect.Ring -> {
                sound.ring(effect.melody)
                if (!presence.onScreen.value) bringToFront(effect.timerId)
            }
            Effect.Silence -> sound.silence()
            is Effect.Play -> if (!presence.asleep.value) sound.cue(effect.cue)
            is Effect.Changed -> _changes.value = effect.rev
            is Effect.PutSecret -> scope.launch {
                secrets.put(effect.key, effect.value)
                onSecretChanged()
            }
            is Effect.DeleteScribble -> scope.launch { scribbles.delete(effect.fileId) }
        }
    }

    /**
     * A timer finished while another app is in front: bring the Hub back. Android 6 lets a process with a
     * foreground service start an activity; the sound has already started.
     */
    private fun bringToFront(timerId: String) {
        Logs.event("alert", "bringing the Hub to the front for $timerId")
        context.startActivity(
            Intent(context, HubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(HubActivity.EXTRA_ALERT, timerId),
        )
    }
}
