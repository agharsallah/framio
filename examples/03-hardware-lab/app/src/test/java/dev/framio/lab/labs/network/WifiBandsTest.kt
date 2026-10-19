package dev.framio.lab.labs.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiBandsTest {

    @Test
    fun `frame's own link at 2462 MHz is channel 11 on 2_4 GHz`() {
        assertEquals("2.4 GHz", WifiBands.band(2462))
        assertEquals(11, WifiBands.channel(2462))
    }

    @Test
    fun `band edges and special channels`() {
        assertEquals(1, WifiBands.channel(2412))
        assertEquals(13, WifiBands.channel(2472))
        assertEquals(14, WifiBands.channel(2484))
        assertEquals(36, WifiBands.channel(5180))
        assertEquals("5 GHz", WifiBands.band(5180))
        assertEquals("6 GHz", WifiBands.band(5955))
        assertEquals(1, WifiBands.channel(5955))
        assertEquals("?", WifiBands.band(900))
    }

    @Test
    fun `WifiInfo ip ints are little endian`() {
        assertEquals("192.168.0.1", WifiBands.ipv4(0x0100A8C0))
        assertEquals("10.0.0.255", WifiBands.ipv4(0xFF00000A.toInt()))
    }

    @Test
    fun `signal bars follow rssi thresholds`() {
        assertEquals(4, WifiBands.bars(-54))
        assertEquals(4, WifiBands.bars(-55))
        assertEquals(3, WifiBands.bars(-56))
        assertEquals(0, WifiBands.bars(-89))
    }
}
