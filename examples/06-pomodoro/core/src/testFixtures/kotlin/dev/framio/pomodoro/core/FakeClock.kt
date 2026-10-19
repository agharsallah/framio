@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.core

import dev.framio.pomodoro.core.time.BootClock
import dev.framio.pomodoro.core.time.EngineClock
import dev.framio.pomodoro.core.time.Now
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Wall-clock milliseconds for a local date and time ("2026-10-18T08:00") in [zone]. */
public fun wallAt(text: String, zone: ZoneId = AMSTERDAM): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

/**
 * A controllable clock: elapsed time, boot id and wall clock move only when told. [wallMs] is null while the
 * clock is untrusted, like the frame right after boot.
 */
public class FakeClock(
    public var elapsedMs: Long = 1_000_000,
    override var bootId: String = "boot-1",
    public var wallMs: Long? = wallAt("2026-10-18T08:00"),
    public var zone: ZoneId = AMSTERDAM,
) : EngineClock, BootClock {
    /** The real wall time while [wallMs] is untrusted after a [reboot]. */
    private var hiddenWall: Long? = null

    override fun now(): Now = Now(bootId, elapsedMs, wallMs, zone)

    override fun elapsedMs(): Long = elapsedMs

    public fun advance(d: Duration) {
        val ms = d.inWholeMilliseconds
        elapsedMs += ms
        wallMs = wallMs?.plus(ms)
        hiddenWall = hiddenWall?.plus(ms)
    }

    /** A reboot after [down]: elapsed time restarts, a new boot id, and the clock is untrusted until [trust]. */
    public fun reboot(down: Duration = 1.minutes) {
        hiddenWall = (wallMs ?: hiddenWall ?: 0) + down.inWholeMilliseconds
        elapsedMs = 5_000
        bootId = "boot-" + (bootId.removePrefix("boot-").toInt() + 1)
        wallMs = null
    }

    /** The network time arrives. */
    public fun trust() {
        wallMs = hiddenWall ?: wallMs
        hiddenWall = null
    }
}

/**
 * A clock driven by coroutines-test virtual time: elapsed time is [baseElapsedMs] + the scheduler's current
 * time, so `delay` in the engine and the clock agree. [reboot] and [trusted] work like [FakeClock]'s.
 */
public class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    public var bootId: String = "boot-1",
    private val startWallMs: Long? = wallAt("2026-10-18T08:00"),
    public var zone: ZoneId = AMSTERDAM,
    private val baseElapsedMs: Long = 1_000_000,
) : EngineClock {
    /** Virtual time at the last reboot; elapsed restarts there. */
    private var bootAt = 0L
    private var elapsedAtBoot = baseElapsedMs
    public var trusted: Boolean = startWallMs != null

    override fun now(): Now {
        val virtual = scheduler.currentTime
        val elapsed = elapsedAtBoot + (virtual - bootAt)
        val wall = if (trusted) startWallMs?.plus(virtual) else null
        return Now(bootId, elapsed, wall, zone)
    }

    public fun reboot() {
        bootAt = scheduler.currentTime
        elapsedAtBoot = 5_000
        bootId = "boot-" + (bootId.removePrefix("boot-").toInt() + 1)
        trusted = false
    }
}
