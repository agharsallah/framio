package dev.framio.meadow.remote

import dev.framio.meadow.core.Command
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

class SummariesTest {

    private fun ms(y: Int, m: Int, d: Int): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun `last 14 days, oldest first, gaps are zero`() {
        val days = JSONObject().put("2026-10-18", 1500).put("2026-10-10", 600).put("2026-09-01", 9999)
        val a = Summaries.lastDays(days, ms(2026, 10, 18))
        assertEquals(14, a.length())
        assertEquals("2026-10-05", a.getJSONObject(0).getString("day"))
        val last = a.getJSONObject(13)
        assertEquals("2026-10-18", last.getString("day"))
        assertEquals(25, last.getInt("minutes"))
        assertTrue(last.getBoolean("today"))
        assertEquals(0, last.getInt("weekday")) // 18 Oct 2026 is a Sunday
        assertEquals(10, a.getJSONObject(5).getInt("minutes"))
        assertEquals(0, a.getJSONObject(6).getInt("minutes"))
    }

    @Test fun `days across a month end`() {
        val a = Summaries.lastDays(null, ms(2026, 3, 2), n = 3)
        assertEquals(listOf("2026-02-28", "2026-03-01", "2026-03-02"), (0 until 3).map { a.getJSONObject(it).getString("day") })
    }

    @Test fun `totals count days played and the average of those`() {
        val a = Summaries.lastDays(JSONObject().put("2026-10-18", 1200).put("2026-10-17", 600), ms(2026, 10, 18))
        val t = Summaries.totals(a)
        assertEquals(30, t.getInt("minutes"))
        assertEquals(2, t.getInt("daysPlayed"))
        assertEquals(15, t.getInt("average"))
    }

    @Test fun `skills are grouped by prefix`() {
        val g = Summaries.skills(JSONObject().put("letter:a", 3).put("letter:b", 1).put("count:7", 2).put("count:10", 1).put("tidy", 4))
        assertEquals(3, g.getJSONObject("letter").getInt("a"))
        assertEquals(2, g.getJSONObject("count").getInt("7"))
        assertEquals(4, g.getJSONObject("other").getInt("tidy"))
        assertEquals(10, Summaries.highest(g.getJSONObject("count")))
        assertNull(Summaries.highest(g.getJSONObject("letter")))
        assertNull(Summaries.highest(null))
    }

    @Test fun `plays sorted by last played, with catalogue info`() {
        val plays = JSONObject()
            .put("count", JSONObject().put("opened", 3).put("finished", 2).put("seconds", 610).put("lastMs", 200).put("level", 2))
            .put("gone", JSONObject().put("opened", 1).put("lastMs", 300))
        val rows = Summaries.plays(plays) { id -> if (id == "count") JSONObject().put("icon", "ob_apple") else null }
        assertEquals("gone", rows.getJSONObject(0).getString("id"))
        val c = rows.getJSONObject(1)
        assertEquals(10, c.getInt("minutes"))
        assertEquals("ob_apple", c.getString("icon"))
        assertEquals(2, c.getInt("level"))
        assertEquals(-1, rows.getJSONObject(0).getInt("level"))
    }

    private fun cmd(j: JSONObject) = RemoteCommand.parse(j, { it == "count" }, { it == "numbers" })

    @Test fun `commands map to the command bus`() {
        assertEquals(Command.Home, cmd(JSONObject().put("type", "home")))
        assertEquals(Command.Lock(true), cmd(JSONObject().put("type", "lock")))
        assertEquals(Command.Lock(false), cmd(JSONObject().put("type", "unlock")))
        assertEquals(Command.Bonus(15), cmd(JSONObject().put("type", "bonus")))
        assertEquals(Command.Bonus(120), cmd(JSONObject().put("type", "bonus").put("minutes", 999)))
        assertEquals(Command.OpenPlay("count"), cmd(JSONObject().put("type", "play").put("id", "count")))
        assertEquals(Command.OpenWorld("numbers"), cmd(JSONObject().put("type", "world").put("id", "numbers")))
        assertEquals(Command.Say("call_dinner"), cmd(JSONObject().put("type", "say").put("id", "call_dinner")))
        assertEquals(Command.Wiggle, cmd(JSONObject().put("type", "wiggle")))
        assertEquals(Command.NightLight, cmd(JSONObject().put("type", "nightlight")))
        assertEquals(Command.Star, cmd(JSONObject().put("type", "star")))
        assertEquals(Command.Wake, cmd(JSONObject().put("type", "wake")))
        assertEquals(Command.Message("abc"), cmd(JSONObject().put("type", "message").put("id", "abc")))
    }

    @Test fun `bad commands are refused`() {
        fun status(j: JSONObject): Int = try {
            cmd(j)
            fail()
            0
        } catch (e: HttpException) {
            e.status
        }
        assertEquals(400, status(JSONObject().put("type", "reboot")))
        assertEquals(400, status(JSONObject()))
        assertEquals(404, status(JSONObject().put("type", "play").put("id", "nope")))
        assertEquals(404, status(JSONObject().put("type", "play")))
        assertEquals(404, status(JSONObject().put("type", "say").put("id", "../x")))
    }
}
