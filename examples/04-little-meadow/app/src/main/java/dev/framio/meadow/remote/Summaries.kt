package dev.framio.meadow.remote

import dev.framio.meadow.core.Clock
import dev.framio.meadow.core.Command
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** The dashboard's digest of [dev.framio.meadow.core.Progress.toJson]. Pure, unit-tested. */
object Summaries {

    /** Minutes played on each of the last [n] days, oldest first, today last (days without play are 0). */
    fun lastDays(days: JSONObject?, todayMs: Long, n: Int = 14): JSONArray {
        val out = JSONArray()
        val c = Calendar.getInstance().apply { timeInMillis = todayMs }
        c.add(Calendar.DAY_OF_YEAR, -(n - 1))
        repeat(n) { i ->
            val key = Clock.dayKey(c.timeInMillis)
            val sec = days?.optInt(key, 0) ?: 0
            out.put(
                JSONObject().put("day", key).put("minutes", (sec + 30) / 60)
                    .put("weekday", c.get(Calendar.DAY_OF_WEEK) - 1).put("today", i == n - 1),
            )
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /** "letter:a" → { letter: { a: 3 } }. Skills without a prefix go to "other". */
    fun skills(skills: JSONObject?): JSONObject {
        val out = JSONObject()
        if (skills == null) return out
        for (k in skills.keys().asSequence().sorted()) {
            val i = k.indexOf(':')
            val group = if (i > 0) k.substring(0, i) else "other"
            val name = if (i > 0) k.substring(i + 1) else k
            val g = out.optJSONObject(group) ?: JSONObject().also { out.put(group, it) }
            g.put(name, g.optInt(name, 0) + skills.optInt(k, 0))
        }
        return out
    }

    /** The largest number among a group's keys ("count:7" → 7), or null. */
    fun highest(group: JSONObject?): Int? =
        group?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull()

    /**
     * One row per activity that was ever opened, most recently played first, with [info] adding what the
     * catalogue knows (title, icon, world); activities no longer in the catalogue are kept with their id.
     */
    fun plays(plays: JSONObject?, info: (String) -> JSONObject?): JSONArray {
        val rows = ArrayList<JSONObject>()
        if (plays != null) for (id in plays.keys()) {
            val s = plays.optJSONObject(id) ?: continue
            val row = JSONObject().put("id", id)
                .put("opened", s.optInt("opened")).put("finished", s.optInt("finished"))
                .put("rounds", s.optInt("rounds")).put("firstTry", s.optInt("firstTry"))
                .put("level", s.optInt("level", -1)).put("minutes", (s.optInt("seconds") + 30) / 60)
                .put("lastMs", s.optLong("lastMs"))
            info(id)?.let { extra -> for (k in extra.keys()) row.put(k, extra.get(k)) }
            rows += row
        }
        rows.sortByDescending { it.optLong("lastMs") }
        return JSONArray(rows)
    }

    fun totals(lastDays: JSONArray): JSONObject {
        var total = 0
        var played = 0
        for (i in 0 until lastDays.length()) {
            val m = lastDays.getJSONObject(i).optInt("minutes")
            total += m
            if (m > 0) played++
        }
        return JSONObject().put("minutes", total).put("daysPlayed", played).put("average", if (played == 0) 0 else (total + played / 2) / played)
    }
}

/** A dashboard command ({type, id?, minutes?}) → a [Command]. Pure, unit-tested. */
object RemoteCommand {
    private val VOICE_ID = Regex("^[a-z0-9_]{1,64}$")

    fun parse(j: JSONObject, playExists: (String) -> Boolean, worldExists: (String) -> Boolean): Command {
        val id = j.optString("id", "")
        fun needId(ok: (String) -> Boolean, what: String): String {
            if (id.isEmpty() || !ok(id)) throw HttpException(404, "No such $what")
            return id
        }
        return when (j.optString("type")) {
            "home" -> Command.Home
            "world" -> Command.OpenWorld(needId(worldExists, "world"))
            "play" -> Command.OpenPlay(needId(playExists, "activity"))
            "lock" -> Command.Lock(true)
            "unlock" -> Command.Lock(false)
            "bonus" -> Command.Bonus(j.optInt("minutes", 15).coerceIn(1, 120))
            "wiggle" -> Command.Wiggle
            "nightlight" -> Command.NightLight
            "star" -> Command.Star
            "wake" -> Command.Wake
            "say" -> Command.Say(needId({ VOICE_ID.matches(it) }, "voice line"))
            "message" -> Command.Message(needId({ it.isNotBlank() }, "message"))
            else -> throw HttpException(400, "Unknown command")
        }
    }
}
