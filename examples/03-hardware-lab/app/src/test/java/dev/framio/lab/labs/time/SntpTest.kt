package dev.framio.lab.labs.time

import org.junit.Assert.assertEquals
import org.junit.Test

class SntpTest {

    @Test
    fun `timestamps survive a write and read at millisecond precision`() {
        val buf = ByteArray(48)
        val t = 1_792_257_922_334L
        Sntp.writeTimestamp(buf, 40, t)
        assertEquals(t, Sntp.readTimestamp(buf, 40))
    }

    @Test
    fun `unix epoch is 2208988800 seconds into the ntp era`() {
        val buf = ByteArray(8)
        Sntp.writeTimestamp(buf, 0, 0)
        assertEquals(0x83AA7E80.toInt(), java.nio.ByteBuffer.wrap(buf).int)
    }

    @Test
    fun `request is a version 3 client packet carrying the send time`() {
        val req = Sntp.request(1_000_000L)
        assertEquals(48, req.size)
        assertEquals(0x1B.toByte(), req[0])
        assertEquals(1_000_000L, Sntp.readTimestamp(req, 40))
    }

    @Test
    fun `clock 500 ms behind with 40 ms symmetric delay gives offset 500 and round trip 40`() {
        // local sends at 1000 (true 1500), server receives at 1520, replies at 1530, local gets it at 1050 (true 1550)
        val (offset, rtt) = Sntp.offset(t1 = 1000, t2 = 1520, t3 = 1530, t4 = 1050)
        assertEquals(500L, offset)
        assertEquals(40L, rtt)
    }
}
