package dev.framio.pomodoro.core.time

import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DeadlineTest {
    private val clock = FakeClock()

    @Test
    fun `same boot counts elapsed time, even when the wall clock jumps`() {
        val deadline = clock.now() + 25.minutes
        clock.advance(10.minutes)
        clock.wallMs = clock.wallMs?.plus(3.hours.inWholeMilliseconds) // NTP correction or a user change
        assertEquals(15.minutes, deadline.remaining(clock.now()))
        clock.advance(20.minutes)
        assertEquals(-(5.minutes), deadline.remaining(clock.now()))
    }

    @Test
    fun `after a process kill the stored deadline still resolves`() {
        val stored = clock.now() + 25.minutes
        // the process dies; a new one reads the same numbers back
        val restored = Moment(stored.bootId, stored.elapsedMs, stored.wallMs)
        clock.advance(5.minutes)
        assertEquals(20.minutes, restored.remaining(clock.now()))
    }

    @Test
    fun `after a reboot a trusted deadline resolves by wall clock once the clock is trusted again`() {
        val deadline = clock.now() + 25.minutes
        clock.advance(5.minutes)
        clock.reboot(down = 2.minutes)
        assertNull(deadline.remaining(clock.now()))
        assertTrue(deadline.isLost(clock.now()))
        clock.trust()
        assertEquals(18.minutes, deadline.remaining(clock.now()))
        val rebased = deadline.rebased(clock.now())
        assertEquals(clock.bootId, rebased.bootId)
        assertEquals(18.minutes, rebased.remaining(clock.now()))
    }

    @Test
    fun `a deadline set before the clock was trusted is lost after a reboot`() {
        clock.wallMs = null
        val deadline = clock.now() + 25.minutes
        assertNull(deadline.wallMs)
        clock.reboot()
        clock.trust()
        assertNull(deadline.remaining(clock.now()))
    }

    @Test
    fun `rebasing fills in the wall time once trusted, so it survives the next reboot`() {
        clock.wallMs = null
        val deadline = clock.now() + 25.minutes
        clock.wallMs = wallAt("2026-10-18T09:00")
        val rebased = deadline.rebased(clock.now())
        assertEquals(wallAt("2026-10-18T09:25"), rebased.wallMs)
        clock.reboot(down = 1.minutes)
        clock.trust()
        assertEquals(24.minutes, rebased.remaining(clock.now()))
    }

    @Test
    fun `durations between moments`() {
        val a = clock.now().moment
        clock.advance(90.seconds)
        val b = clock.now().moment
        assertEquals(90.seconds, a.durationUntil(b))
        clock.reboot()
        assertNull(a.durationUntil(clock.now().moment))
    }

    @Test
    fun `today needs a trusted clock`() {
        assertEquals("2026-10-18", clock.now().date.toString())
        clock.wallMs = null
        assertNull(clock.now().date)
    }
}

class TimeGateTest {
    @Test
    fun `refuses the boot clock, trusts a plausible one, anchors to elapsed time`() {
        val gate = TimeGate()
        assertFalse(gate.offer(wallAt("1970-01-01T01:00"), 5_000))
        assertFalse(gate.isTrusted)
        assertNull(gate.wallAt(10_000))
        assertTrue(gate.offer(wallAt("2026-10-18T08:00"), 10_000))
        assertEquals(wallAt("2026-10-18T08:01"), gate.wallAt(70_000))
    }

    @Test
    fun `refuses Android's 2021 placeholder and anything before the firmware build time`() {
        val gate = TimeGate(notBefore = wallAt("2026-03-07T00:00"))
        assertFalse(gate.offer(wallAt("2021-05-15T02:00"), 20_000))
        assertFalse(gate.offer(wallAt("2026-02-01T10:00"), 20_000))
        assertFalse(gate.offer(wallAt("2101-01-01T00:00"), 20_000))
        assertTrue(gate.offer(wallAt("2026-10-18T08:00"), 36_000))
    }

    @Test
    fun `the gated clock reports wall time only once trusted`() {
        val boot = FakeClock(elapsedMs = 10_000, wallMs = null)
        val gate = TimeGate()
        val clock = GatedClock(boot, gate) { dev.framio.pomodoro.core.AMSTERDAM }
        assertNull(clock.now().wallMs)
        gate.offer(wallAt("2026-10-18T08:00"), 10_000)
        boot.elapsedMs = 70_000
        assertEquals(wallAt("2026-10-18T08:01"), clock.now().wallMs)
        assertEquals("boot-1", clock.now().bootId)
    }
}
