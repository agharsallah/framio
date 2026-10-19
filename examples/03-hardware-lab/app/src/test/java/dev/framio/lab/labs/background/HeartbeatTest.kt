package dev.framio.lab.labs.background

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatTest {

    @Test
    fun `steady beats have no late beats and nothing missed`() {
        val h = Heartbeat(1000)
        listOf(0L, 1000, 2001, 2999, 4000).forEach { h.beat(it) }
        assertEquals(5, h.count)
        assertEquals(0, h.lateBeats)
        assertEquals(0L, h.missed)
        assertEquals(1001L, h.maxGapMs)
        assertEquals(4000L, h.runningMs)
    }

    @Test
    fun `a 30 s freeze is one late beat with 29 missed`() {
        val h = Heartbeat(1000)
        h.beat(0)
        h.beat(1000)
        assertEquals(30_000L, h.beat(31_000))
        assertEquals(1, h.lateBeats)
        assertEquals(29L, h.missed)
        assertEquals(30_000L, h.maxGapMs)
    }

    @Test
    fun `a gap of exactly two periods is still on time`() {
        val h = Heartbeat(1000)
        h.beat(0)
        h.beat(2000)
        assertEquals(0, h.lateBeats)
    }

    @Test
    fun `the first beat has no gap`() {
        val h = Heartbeat(1000)
        assertEquals(0L, h.beat(12_345))
        assertEquals(0L, h.runningMs)
    }
}
