package dev.framio.hub.core.day

import dev.framio.hub.core.AMSTERDAM
import dev.framio.hub.core.feeds.RainSlot
import dev.framio.hub.core.time.Sun
import dev.framio.hub.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayRingTest {
    private val now = wallAt("2026-10-18T21:00")
    private fun at(t: String) = wallAt(t)
    private fun build(events: List<RingEvent> = emptyList(), rain: List<RainSlot> = emptyList(), timers: List<RingTimer> = emptyList()) =
        DayRingModel.build(now, AMSTERDAM, sunFor(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19)), events, rain, timers)

    private fun sunFor(vararg dates: LocalDate) = dates.map { Sun.times(it, 52.37, 4.89, AMSTERDAM) }

    @Test fun `midnight is at the bottom, noon at the top, six on the left`() {
        assertEquals(90f, DayRingModel.angleOf(at("2026-10-18T00:00"), AMSTERDAM), 0.01f)
        assertEquals(270f, DayRingModel.angleOf(at("2026-10-18T12:00"), AMSTERDAM), 0.01f)
        assertEquals(180f, DayRingModel.angleOf(at("2026-10-18T06:00"), AMSTERDAM), 0.01f)
        assertEquals(0f, DayRingModel.angleOf(at("2026-10-18T18:00"), AMSTERDAM), 0.01f)
    }

    @Test fun `the now marker and the tomorrow boundary`() {
        val ring = build()
        assertEquals(DayRingModel.angleOf(now, AMSTERDAM), ring.nowAngle, 0.01f)
        assertEquals(90f, ring.midnightAngle, 0.01f)
    }

    @Test fun `at 21_00 tomorrow's dentist at 08_00 is on the ring, marked tomorrow`() {
        val ring = build(events = listOf(RingEvent("d", at("2026-10-19T08:00"), at("2026-10-19T09:00"), 1, "S")))
        val arc = ring.events.single()
        assertTrue(arc.tomorrow)
        assertEquals(DayRingModel.angleOf(at("2026-10-19T08:00"), AMSTERDAM), arc.start, 0.01f)
        assertEquals(15f, arc.sweep, 0.01f)
    }

    @Test fun `an event across midnight is split into today and tomorrow`() {
        val ring = build(events = listOf(RingEvent("p", at("2026-10-18T23:00"), at("2026-10-19T01:00"), 1, "P")))
        assertEquals(listOf(false, true), ring.events.map { it.tomorrow })
        assertEquals(30f, ring.events.sumOf { it.sweep.toDouble() }.toFloat(), 0.01f)
    }

    @Test fun `events past the 24-hour window are clipped and earlier ones dropped`() {
        val ring = build(
            events = listOf(
                RingEvent("past", at("2026-10-18T18:00"), at("2026-10-18T19:00"), 1, "A"),
                RingEvent("late", at("2026-10-19T20:00"), at("2026-10-19T22:00"), 1, "B"),
            ),
        )
        val late = ring.events.single()
        assertEquals("late", late.id)
        assertEquals("ends at the window's end, 21:00 tomorrow", 15f, late.sweep, 0.01f)
    }

    @Test fun `overlapping events take the next lane, a fourth becomes plus n`() {
        val e = (1..5).map { RingEvent("e$it", at("2026-10-19T10:00"), at("2026-10-19T11:00"), it, "X") }
        val ring = build(events = e)
        assertEquals(listOf(0, 1, 2), ring.events.map { it.lane })
        assertEquals(2, ring.overflow.single().count)
        val next = build(events = e.take(1) + RingEvent("after", at("2026-10-19T11:00"), at("2026-10-19T12:00"), 1, "Y"))
        assertEquals("back-to-back events share a lane", listOf(0, 0), next.events.map { it.lane })
    }

    @Test fun `a very short event still gets a visible arc`() {
        val ring = build(events = listOf(RingEvent("call", at("2026-10-19T10:00"), at("2026-10-19T10:00"), 1, "C")))
        assertEquals(5f, ring.events.single().sweep, 0.01f)
    }

    @Test fun `daylight lies over the top, between tomorrow's sunrise and sunset`() {
        val ring = build()
        val day = ring.light.filter { it.kind == DayRing.Light.Day }
        assertEquals(1, day.size) // today's daylight is already over at 21:00
        val sunrise = DayRingModel.angleOf(Sun.times(LocalDate.of(2026, 10, 19), 52.37, 4.89, AMSTERDAM).sunrise!!.toEpochMilli(), AMSTERDAM)
        assertEquals(sunrise, day.single().start, 0.01f)
        assertTrue(day.single().tomorrow)
        assertTrue("twilight on both sides", ring.light.count { it.kind == DayRing.Light.Twilight } == 2)
    }

    @Test fun `rain windows and timer ends are placed`() {
        val ring = build(
            rain = listOf(RainSlot(at("2026-10-18T22:00"), at("2026-10-18T23:00"), 3.0, heavy = true)),
            timers = listOf(RingTimer("pasta", at("2026-10-18T21:08"), 7), RingTimer("never", at("2026-10-20T21:00"), 7)),
        )
        assertEquals(15f, ring.rain.single().sweep, 0.01f)
        assertTrue(ring.rain.single().heavy)
        assertFalse(ring.rain.single().tomorrow)
        assertEquals(listOf("pasta"), ring.timers.map { it.id })
    }

    @Test fun `the summer-time night is drawn on the clock face, not as 25 hours`() {
        val dstNow = wallAt("2026-10-24T21:00")
        val event = RingEvent("night", wallAt("2026-10-25T01:00"), wallAt("2026-10-25T04:00"), 1, "N") // 4 real hours
        val ring = DayRingModel.build(dstNow, AMSTERDAM, emptyList(), listOf(event), emptyList(), emptyList())
        assertEquals("01:00 to 04:00 on the dial", 45f, ring.events.single().sweep, 0.01f)
    }
}
