package dev.framio.hub.core.day

import dev.framio.hub.core.AMSTERDAM
import dev.framio.hub.core.model.NightMode
import dev.framio.hub.core.model.NightSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ModeMachineTest {
    private var s = ModeState()
    private var now = 0L
    private fun send(e: ModeEvent) {
        s = ModeMachine.step(s, e, now)
    }

    @Test fun `a tap opens Today, which closes itself after 30 s idle`() {
        send(ModeEvent.Tap)
        assertEquals(Mode.Open(Panel.Today), s.mode)
        now += 29_999
        send(ModeEvent.Tick)
        assertEquals(Mode.Open(Panel.Today), s.mode)
        now += 1
        send(ModeEvent.Tick)
        assertEquals(Mode.Ambient, s.mode)
    }

    @Test fun `touching a panel keeps it open`() {
        send(ModeEvent.Open(Panel.Timers))
        now += 80_000
        send(ModeEvent.Interaction)
        now += 80_000
        send(ModeEvent.Tick)
        assertEquals(Mode.Open(Panel.Timers), s.mode)
        assertEquals(now + 10_000, ModeMachine.nextTimeoutMs(s))
    }

    @Test fun `night waits for an open panel to time out`() {
        send(ModeEvent.Open(Panel.Board))
        send(ModeEvent.NightChanged(true))
        assertEquals(Mode.Open(Panel.Board), s.mode)
        now += ModeMachine.PANEL_IDLE_MS
        send(ModeEvent.Tick)
        assertEquals(Mode.Night, s.mode)
        assertTrue(s.dark)
    }

    @Test fun `at night a tap or knock shows the night clock for 10 s`() {
        send(ModeEvent.NightChanged(true))
        assertEquals(Mode.Night, s.mode)
        send(ModeEvent.DoubleKnock)
        assertEquals(Mode.NightPeek, s.mode)
        now += 10_000
        send(ModeEvent.Tick)
        assertEquals(Mode.Night, s.mode)
        send(ModeEvent.NightChanged(false))
        assertEquals(Mode.Ambient, s.mode)
    }

    @Test fun `face down sleeps, standing up wakes to the right mode`() {
        send(ModeEvent.FaceDown(true))
        assertEquals(Mode.Asleep, s.mode)
        send(ModeEvent.Tap)
        assertEquals(Mode.Asleep, s.mode)
        send(ModeEvent.NightChanged(true))
        send(ModeEvent.FaceDown(false))
        assertEquals(Mode.Night, s.mode)
    }

    @Test fun `ambient has no timeout`() {
        assertNull(ModeMachine.nextTimeoutMs(s))
    }
}

class SkyAndNightTest {
    @Test fun `every sky colour keeps ink text at 7 to 1 or better`() {
        for (phase in SkyPalette.Phase.entries) {
            val (top, bottom) = SkyPalette.colors(phase)
            for (c in listOf(top, bottom)) {
                assertTrue("$phase luminance", SkyPalette.luminance(c) <= 0.07)
                assertTrue("$phase contrast ${SkyPalette.contrast(SkyPalette.INK, c)}", SkyPalette.contrast(SkyPalette.INK, c) >= 7.0)
            }
        }
    }

    @Test fun `phases follow the sun and the clouds`() {
        assertEquals(SkyPalette.Phase.Night, SkyPalette.phase(-20.0, 0))
        assertEquals(SkyPalette.Phase.BlueHour, SkyPalette.phase(-8.0, 0))
        assertEquals(SkyPalette.Phase.GoldenHour, SkyPalette.phase(2.0, 100))
        assertEquals(SkyPalette.Phase.Day, SkyPalette.phase(30.0, 20))
        assertEquals(SkyPalette.Phase.Overcast, SkyPalette.phase(30.0, 90))
    }

    private fun local(t: String) = LocalDateTime.parse(t).atZone(AMSTERDAM)
    private val night = NightSettings(start = "22:30", end = "06:30", rampMin = 15)

    @Test fun `night by schedule, with a morning ramp`() {
        assertEquals(NightPhase.Day, NightPlan.phase(night, null, local("2026-10-18T22:29")))
        assertEquals(NightPhase.Night, NightPlan.phase(night, null, local("2026-10-18T22:30")))
        assertEquals(NightPhase.Night, NightPlan.phase(night, null, local("2026-10-19T06:14")))
        assertEquals(NightPhase.Ramp(0f), NightPlan.phase(night, null, local("2026-10-19T06:15")))
        val late = NightPlan.phase(night, null, local("2026-10-19T06:29")) as NightPhase.Ramp
        assertEquals(14f / 15f, late.level, 0.001f)
        assertEquals(NightPhase.Day, NightPlan.phase(night, null, local("2026-10-19T06:30")))
        assertEquals(NightPhase.Day, NightPlan.phase(night.copy(enabled = false), null, local("2026-10-19T01:00")))
    }

    @Test fun `night by sunset starts at sunset plus the offset`() {
        val sunset = Instant.parse("2026-10-18T16:41:00Z") // 18:41 local
        val bySunset = night.copy(mode = NightMode.Sunset, sunsetOffsetMin = 180)
        assertEquals("21:41", NightPlan.window(bySunset, sunset, local("2026-10-18T12:00")).start)
        assertEquals(NightPhase.Night, NightPlan.phase(bySunset, sunset, local("2026-10-18T21:45")))
        assertEquals("no sunset known: the schedule", "22:30", NightPlan.window(bySunset, null, local("2026-10-18T12:00")).start)
    }
}
