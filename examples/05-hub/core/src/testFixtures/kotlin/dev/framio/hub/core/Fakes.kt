package dev.framio.hub.core

import dev.framio.hub.core.engine.Effect
import dev.framio.hub.core.engine.EffectHandler
import dev.framio.hub.core.engine.HubClock
import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.engine.StateStore
import dev.framio.hub.core.model.HubState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Wall-clock milliseconds for a local date and time in Amsterdam. */
fun wallAt(text: String, zone: ZoneId = AMSTERDAM): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

/**
 * A controllable [HubClock]: elapsed time, boot id and wall clock move only when told. [wallMs] is null while
 * the clock is "untrusted", like the frame right after boot.
 */
class FakeClock(
    var elapsedMs: Long = 1_000_000,
    var bootId: String = "boot-1",
    var wallMs: Long? = wallAt("2026-10-18T08:00"),
    var zone: ZoneId = AMSTERDAM,
) : HubClock {
    /** The real time while [wallMs] is untrusted after a [reboot]. */
    private var pendingWall: Long? = null

    override fun now() = Now(elapsedMs, bootId, wallMs, zone)

    fun advance(ms: Long) {
        elapsedMs += ms
        wallMs = wallMs?.plus(ms)
        pendingWall = pendingWall?.plus(ms)
    }

    /** A reboot: elapsed time restarts, a new boot id, and the clock is untrusted until [trust] is called. */
    fun reboot(downMs: Long = 60_000) {
        val trueWall = (wallMs ?: 0) + downMs
        elapsedMs = 5_000
        bootId = "boot-" + (bootId.removePrefix("boot-").toInt() + 1)
        wallMs = null
        pendingWall = trueWall
    }

    fun trust() {
        wallMs = pendingWall ?: wallMs
        pendingWall = null
    }

    val instant: Instant? get() = wallMs?.let(Instant::ofEpochMilli)
}

class InMemoryStateStore(var stored: HubState = HubState()) : StateStore {
    var writes = 0
        private set

    override suspend fun load() = stored

    override fun offer(state: HubState) {
        stored = state
        writes++
    }
}

class RecordingEffects : EffectHandler {
    val effects = mutableListOf<Effect>()
    override fun handle(effect: Effect) {
        effects += effect
    }
}
