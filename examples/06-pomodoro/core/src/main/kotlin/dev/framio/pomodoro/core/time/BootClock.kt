package dev.framio.pomodoro.core.time

import java.time.ZoneId

/** This boot's monotonic clock: `SystemClock.elapsedRealtime()` and an id that changes at every boot. */
public interface BootClock {
    public val bootId: String
    public fun elapsedMs(): Long
}

/** What the reducer reads time from. */
public fun interface EngineClock {
    public fun now(): Now
}

/** The production clock: boot time from [boot], wall time only once [gate] trusts it. */
public class GatedClock(
    private val boot: BootClock,
    private val gate: TimeGate,
    private val zone: () -> ZoneId,
) : EngineClock {
    override fun now(): Now {
        val elapsed = boot.elapsedMs()
        return Now(boot.bootId, elapsed, gate.wallAt(elapsed), zone())
    }
}
