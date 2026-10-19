package dev.framio.hub.core.engine

import dev.framio.hub.core.wallAt
import dev.framio.hub.core.model.CalendarFeed
import dev.framio.hub.core.model.Item
import dev.framio.hub.core.model.Keep
import dev.framio.hub.core.model.NoteBody
import dev.framio.hub.core.model.Person
import dev.framio.hub.core.model.Routine
import dev.framio.hub.core.model.Step
import dev.framio.hub.core.model.TimeWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReduceTest {
    private val h = Harness()

    // Commands in general

    @Test fun `a repeated command id applies once`() {
        val cmd = NoteAdd("same", "relay:pixel", NoteBody.Text("Milk"))
        h.send(cmd)
        h.send(cmd)
        assertEquals(1, h.state.notes.size)
        assertEquals("duplicate", h.lastRejected)
    }

    @Test fun `rev goes up on every change and only then`() {
        h.send(NoteAdd(h.id(), "frame", NoteBody.Text("Hi")))
        assertEquals(1L, h.state.rev)
        assertTrue(h.effects.contains(Effect.Changed(1)))
        h.tick() // nothing due
        h.send(NoteRemove(h.id(), "frame", "nope"))
        assertEquals(1L, h.state.rev)
    }

    @Test fun `seen ids are capped`() {
        repeat(300) { h.send(BoardSeen("id$it", "frame")) }
        assertEquals(256, h.state.seen.size)
        assertEquals("id299", h.state.seen.last())
    }

    @Test fun `commands round-trip through JSON with the type discriminator`() {
        val json = """{"type":"note.add","id":"n1","from":"adb","body":{"type":"text","text":"Hi"}}"""
        val cmd = Commands.decode(json).getOrThrow()
        assertEquals(NoteAdd("n1", "adb", NoteBody.Text("Hi")), cmd)
        assertEquals(cmd, Commands.decode(Commands.encode(cmd)).getOrThrow())
        assertTrue(Commands.decode("""{"type":"no.such"}""").isFailure)
        assertTrue(Commands.decode("not json").isFailure)
    }

    // Board

    @Test fun `a note from a phone chimes, unless it's quiet hours`() {
        h.send(NoteAdd(h.id(), "lan:pixel", NoteBody.Text("Home at 6")))
        assertTrue(h.effects.contains(Effect.Play(Cue.NoteArrived)))
        h.effects.clear()
        h.clock.wallMs = wallAt("2026-10-18T22:00")
        h.send(NoteAdd(h.id(), "lan:pixel", NoteBody.Text("Night")))
        assertTrue(h.effects.none { it is Effect.Play })
        h.send(NoteAdd(h.id(), FROM_FRAME, NoteBody.Text("From the frame")))
        assertTrue(h.effects.none { it is Effect.Play })
    }

    @Test fun `notes expire by their keep setting`() {
        h.clock.wallMs = wallAt("2026-10-18T23:00")
        h.send(NoteAdd("today", "frame", NoteBody.Text("a"), keep = Keep.Today))
        h.send(NoteAdd("three", "frame", NoteBody.Text("b"), keep = Keep.ThreeDays))
        h.send(NoteAdd("pinned", "frame", NoteBody.Text("c"), keep = Keep.Pinned))
        h.clock.wallMs = wallAt("2026-10-19T03:59")
        h.tick()
        assertEquals(3, h.state.notes.size)
        h.clock.wallMs = wallAt("2026-10-19T04:00")
        h.tick()
        assertEquals(listOf("three", "pinned"), h.state.notes.map { it.id })
        h.clock.wallMs = wallAt("2026-10-21T23:00")
        h.tick()
        assertEquals(listOf("pinned"), h.state.notes.map { it.id })
    }

    @Test fun `a note written before the clock was trusted gets its times once it is`() {
        h.clock.wallMs = null
        h.send(NoteAdd("n", "frame", NoteBody.Text("x"), keep = Keep.ThreeDays))
        assertNull(h.state.notes.single().createdWallMs)
        h.clock.wallMs = wallAt("2026-10-18T09:00")
        h.apply(Input.TimeTrusted)
        val note = h.state.notes.single()
        assertEquals(wallAt("2026-10-18T09:00"), note.createdWallMs)
        assertEquals(wallAt("2026-10-21T09:00"), note.expiresWallMs)
    }

    @Test fun `checklist items can be added, ticked and removed`() {
        h.send(NoteAdd("list", "lan:pixel", NoteBody.Checklist("Shopping", listOf(Item("", "Milk"), Item("", "Eggs")))))
        val items = (h.state.notes.single().body as NoteBody.Checklist).items
        assertEquals(listOf("list.0", "list.1"), items.map { it.id })
        h.send(ItemCheck(h.id(), "frame", "list", "list.1", true))
        h.send(ItemAdd("bread", "relay:pixel", "list", "Bread"))
        h.send(ItemRemove(h.id(), "frame", "list", "list.0"))
        assertEquals(listOf(Item("list.1", "Eggs", true), Item("bread", "Bread")), (h.state.notes.single().body as NoteBody.Checklist).items)
    }

    @Test fun `removing a scribble deletes its file`() {
        h.send(NoteAdd("s", "frame", NoteBody.Scribble("s", 3)))
        h.send(NoteRemove(h.id(), "frame", "s"))
        assertTrue(h.effects.contains(Effect.DeleteScribble("s")))
    }

    @Test fun `empty notes are rejected`() {
        h.send(NoteAdd(h.id(), "frame", NoteBody.Text("  ")))
        assertEquals("empty note", h.lastRejected)
        assertTrue(h.state.notes.isEmpty())
    }

    // Routines

    private val bedtime = Routine(
        id = "bed",
        title = "Bedtime",
        steps = listOf(Step("teeth", "Teeth", "😁"), Step("pyjamas", "Pyjamas", "👕")),
        window = TimeWindow("19:00", "20:00"),
    )

    @Test fun `ticking every step of a routine plays the finish cue once`() {
        h.send(RoutinePut(h.id(), "lan", bedtime))
        h.send(RoutineStep(h.id(), "frame", "bed", "teeth", true))
        assertEquals(Effect.Play(Cue.StepDone), h.effects.last { it is Effect.Play })
        h.send(RoutineStep(h.id(), "frame", "bed", "pyjamas", true))
        assertEquals(Effect.Play(Cue.RoutineDone), h.effects.last { it is Effect.Play })
    }

    @Test fun `routines reset at their reset time, every day`() {
        h.clock.wallMs = wallAt("2026-10-18T19:00")
        h.send(RoutinePut(h.id(), "lan", bedtime))
        h.send(RoutineStep(h.id(), "frame", "bed", "teeth", true))
        h.clock.wallMs = wallAt("2026-10-19T03:59")
        h.tick()
        assertTrue(h.state.routines.single().steps.first().done)
        h.clock.wallMs = wallAt("2026-10-19T04:00")
        h.tick()
        assertFalse(h.state.routines.single().steps.first().done)
        assertEquals("2026-10-19", h.state.routines.single().lastResetDay)
    }

    @Test fun `routines reset correctly across the end of summer time`() {
        // 2026-10-25: Amsterdam goes from 03:00 CEST back to 02:00 CET
        h.clock.wallMs = wallAt("2026-10-24T20:00")
        h.send(RoutinePut(h.id(), "lan", bedtime.copy(resetAt = "02:30")))
        h.send(RoutineStep(h.id(), "frame", "bed", "teeth", true))
        h.clock.wallMs = wallAt("2026-10-25T02:29")
        h.tick()
        assertTrue(h.state.routines.single().steps.first().done)
        h.clock.wallMs = wallAt("2026-10-25T02:30")
        h.tick()
        assertFalse(h.state.routines.single().steps.first().done)
    }

    @Test fun `updating a routine keeps the ticks of steps it keeps`() {
        h.send(RoutinePut(h.id(), "lan", bedtime))
        h.send(RoutineStep(h.id(), "frame", "bed", "teeth", true))
        h.send(RoutinePut(h.id(), "lan", bedtime.copy(steps = bedtime.steps + Step("story", "Story", "📖"))))
        assertEquals(listOf(true, false, false), h.state.routines.single().steps.map { it.done })
    }

    @Test fun `routines need valid steps and times`() {
        h.send(RoutinePut(h.id(), "lan", bedtime.copy(steps = emptyList())))
        assertEquals("a routine has 1 to 12 steps", h.lastRejected)
        h.send(RoutinePut(h.id(), "lan", bedtime.copy(resetAt = "25:00")))
        assertEquals("times must be HH:mm", h.lastRejected)
    }

    // People, calendars, settings

    @Test fun `a calendar's link goes to the secrets store, never into the state`() {
        h.send(CalendarPut(h.id(), "lan", CalendarFeed("fam", "Family"), url = "webcal://example.com/secret.ics"))
        assertEquals(listOf(CalendarFeed("fam", "Family")), h.state.calendars)
        assertTrue(h.effects.contains(Effect.PutSecret("calendar.fam", "https://example.com/secret.ics")))
        assertFalse(StateCodec.encode(h.state).contains("secret.ics"))
        h.send(CalendarRemove(h.id(), "lan", "fam"))
        assertTrue(h.effects.contains(Effect.PutSecret("calendar.fam", null)))
    }

    @Test fun `calendars need an https link`() {
        h.send(CalendarPut(h.id(), "lan", CalendarFeed("x", "X"), url = "http://example.com/a.ics"))
        assertEquals("calendar links must be https:// or webcal://", h.lastRejected)
        h.send(CalendarPut(h.id(), "lan", CalendarFeed("x", "X")))
        assertEquals("a new calendar needs its link", h.lastRejected)
    }

    @Test fun `removing a person unlinks their calendars and routines`() {
        h.send(PersonPut(h.id(), "lan", Person("lena", "Lena", 2)))
        h.send(CalendarPut(h.id(), "lan", CalendarFeed("swim", "Swimming", "lena"), url = "https://x/y.ics"))
        h.send(RoutinePut(h.id(), "lan", bedtime.copy(personId = "lena")))
        h.send(PersonRemove(h.id(), "lan", "lena"))
        assertTrue(h.state.people.isEmpty())
        assertNull(h.state.calendars.single().personId)
        assertNull(h.state.routines.single().personId)
    }

    @Test fun `settings patches merge nested keys and are validated`() {
        h.send(SettingsPatch(h.id(), "adb", buildJsonObject { putJsonObject("night") { put("start", "23:15") } }))
        assertEquals("23:15", h.state.settings.night.start)
        assertEquals("06:30", h.state.settings.night.end)
        h.send(
            SettingsPatch(
                h.id(),
                "adb",
                buildJsonObject {
                    putJsonObject("location") {
                        put("latitude", 52.37)
                        put("longitude", 4.89)
                        put("name", "Amsterdam")
                    }
                },
            ),
        )
        assertEquals(52.37, h.state.settings.location!!.latitude, 0.0)
        h.send(SettingsPatch(h.id(), "adb", buildJsonObject { putJsonObject("night") { put("start", "7pm") } }))
        assertEquals("night times must be HH:mm", h.lastRejected)
        h.send(SettingsPatch(h.id(), "adb", JsonObject(mapOf("keepRunning" to JsonPrimitive("maybe")))))
        assertTrue(h.lastRejected!!.startsWith("bad settings"))
        assertEquals("23:15", h.state.settings.night.start)
    }
}
