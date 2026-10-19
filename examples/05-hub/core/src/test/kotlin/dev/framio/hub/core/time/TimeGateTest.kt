package dev.framio.hub.core.time

import dev.framio.hub.core.AMSTERDAM
import dev.framio.hub.core.model.TimeWindow
import dev.framio.hub.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TimeGateTest {
    private val gate = TimeGate()
    private val real = wallAt("2026-10-18T08:00")

    @Test fun `the frame's boot time is not trusted`() {
        val bootClock = wallAt("2016-01-01T01:00")
        assertFalse(gate.offer(TimeAnchor(bootClock, 5_000, TimeSource.System)))
        assertNull(gate.wallAt(10_000))
    }

    @Test fun `trusted time follows elapsed time, whatever the system clock does later`() {
        assertTrue(gate.offer(TimeAnchor(real, 10_000, TimeSource.Sntp)))
        assertEquals(real + 50_000, gate.wallAt(60_000))
    }

    @Test fun `a better source replaces a worse one, but not the other way`() {
        gate.offer(TimeAnchor(real, 0, TimeSource.Http))
        assertTrue(gate.offer(TimeAnchor(real + 400, 0, TimeSource.Sntp)))
        assertFalse(gate.offer(TimeAnchor(real + 9_000, 0, TimeSource.System)))
        assertEquals(real + 400, gate.wallAt(0))
        assertTrue("a fresh SNTP answer resyncs", gate.offer(TimeAnchor(real + 410, 0, TimeSource.Sntp)))
    }

    @Test fun `sntp timestamps round-trip and the offset follows RFC 4330`() {
        val buf = ByteArray(48)
        Sntp.writeTimestamp(buf, 40, real + 123)
        assertEquals(real + 123, Sntp.readTimestamp(buf, 40))
        // server 500 ms ahead, 40 ms each way
        val (offset, rtt) = Sntp.offset(t1 = 1_000, t2 = 1_540, t3 = 1_545, t4 = 1_085)
        assertEquals(500L, offset)
        assertEquals(80L, rtt)
    }

    @Test fun `time windows can cross midnight`() {
        val quiet = TimeWindow("21:30", "07:00")
        assertTrue(quiet.contains(LocalTime.of(23, 0)))
        assertTrue(quiet.contains(LocalTime.of(6, 59)))
        assertFalse(quiet.contains(LocalTime.of(7, 0)))
        assertFalse(quiet.contains(LocalTime.of(12, 0)))
        assertTrue(TimeWindow("09:00", "17:00").contains(LocalTime.of(9, 0)))
        assertFalse(TimeWindow("bad", "17:00").contains(LocalTime.of(9, 0)))
    }

    @Test fun `next occurrence handles the skipped hour of summer time`() {
        // 2026-03-29 02:00 → 03:00 in Amsterdam: 02:30 doesn't exist and becomes 03:30
        val next = LocalTimes.nextOccurrence(LocalTime.of(2, 30), wallAt("2026-03-29T01:00"), AMSTERDAM)
        assertEquals(wallAt("2026-03-29T03:30"), next)
    }

    @Test fun `the reset day changes at the reset time`() {
        assertEquals("2026-10-17", LocalTimes.resetDay("04:00", wallAt("2026-10-18T03:59"), AMSTERDAM))
        assertEquals("2026-10-18", LocalTimes.resetDay("04:00", wallAt("2026-10-18T04:00"), AMSTERDAM))
    }
}
