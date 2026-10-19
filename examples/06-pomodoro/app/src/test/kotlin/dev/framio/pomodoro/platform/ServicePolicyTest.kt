package dev.framio.pomodoro.platform

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.engine.Effect
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Snapshot
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.platform.ServicePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class ServicePolicyTest {
    @Test
    fun `start and stop effects turn into one start and one stop`() {
        assertEquals(Action.Start, ServicePolicy.onEffect(Effect.StartService, running = false))
        assertEquals(Action.None, ServicePolicy.onEffect(Effect.StartService, running = true))
        assertEquals(Action.Stop, ServicePolicy.onEffect(Effect.StopService, running = true))
        assertEquals(Action.None, ServicePolicy.onEffect(Effect.StopService, running = false))
        assertEquals(Action.None, ServicePolicy.onEffect(Effect.BringToFront, running = false))
    }

    @Test
    fun `a whole Classic cycle runs the service from Begin until Home, once`() {
        val d = Driver(settings = Settings(selectedMode = Presets.CLASSIC_ID))
        var running = false
        val actions = mutableListOf<Action>()
        fun follow(from: Int) = d.effects.drop(from).forEach { e ->
            val a = ServicePolicy.onEffect(e, running)
            if (a != Action.None) actions += a
            if (a == Action.Start) running = true
            if (a == Action.Stop) running = false
        }
        follow(0) // the restore's sync: StopService while not running → nothing
        var seen = d.effects.size
        d.send(Input.Begin())
        follow(seen); seen = d.effects.size
        d.pass(25.minutes) // focus ends, break starts by itself
        follow(seen); seen = d.effects.size
        d.pass(5.minutes) // break ends: Ready
        follow(seen); seen = d.effects.size
        assertEquals(listOf(Action.Start), actions)
        d.send(Input.Home)
        follow(seen)
        assertEquals(listOf(Action.Start, Action.Stop), actions)
    }

    @Test
    fun `bring to front only when another app is in front`() {
        assertEquals(true, ServicePolicy.bringToFront(onScreen = false))
        assertEquals(false, ServicePolicy.bringToFront(onScreen = true))
    }

    @Test
    fun `the router sends BringToFront only while off screen, and every effect to its sink`() {
        val sinks = RecordingSinks()
        val presence = Presence()
        val router = EffectRouter(sinks, presence)
        presence.onScreen.value = true
        router.handle(Effect.BringToFront)
        presence.onScreen.value = false
        router.handle(Effect.BringToFront)
        router.handle(Effect.StartService)
        router.handle(Effect.Persist(Snapshot()))
        assertEquals(listOf("front", "service StartService", "persist"), sinks.calls)
    }

    private class RecordingSinks : EffectSinks {
        val calls = mutableListOf<String>()
        override fun cue(effect: Effect.PlayCue) { calls += "cue" }
        override fun display(effect: Effect.SetDisplay) { calls += "display ${effect.level}" }
        override fun service(effect: Effect) { calls += "service $effect" }
        override fun appendLog(record: SessionRecord) { calls += "log" }
        override fun persist(snapshot: Snapshot) { calls += "persist" }
        override fun saveSettings(settings: Settings) { calls += "settings" }
        override fun bringToFront() { calls += "front" }
    }
}
