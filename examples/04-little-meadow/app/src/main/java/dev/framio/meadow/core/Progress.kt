package dev.framio.meadow.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Adaptive difficulty, the same rule for every activity: three first-try rounds in a row step up,
 * two rounds in a row that needed help step down. Levels are 0..[MAX]; age sets where a child
 * starts and how far below that they can drop. Pure, unit-tested.
 */
object Difficulty {
    const val MAX = 4
    private const val UP_AFTER = 3
    private const val DOWN_AFTER = 2

    fun start(age: Int): Int = (age - 2).coerceIn(0, MAX)

    data class Track(val level: Int, val streak: Int = 0, val misses: Int = 0)

    fun after(t: Track, firstTry: Boolean, age: Int): Track {
        val floor = (start(age) - 1).coerceAtLeast(0)
        return if (firstTry) {
            val s = t.streak + 1
            if (s >= UP_AFTER && t.level < MAX) Track(t.level + 1) else Track(t.level, s.coerceAtMost(UP_AFTER), 0)
        } else {
            val m = t.misses + 1
            if (m >= DOWN_AFTER && t.level > floor) Track(t.level - 1) else Track(t.level, 0, m.coerceAtMost(DOWN_AFTER))
        }
    }
}

/**
 * What the child did, for adaptive difficulty, the sticker book and the parent dashboard.
 * Kept in memory, saved by [MeadowApplication] every few seconds when changed.
 */
class Progress(private val file: File) {

    data class PlayStat(
        var opened: Int = 0,
        var finished: Int = 0,
        var rounds: Int = 0,
        var firstTry: Int = 0,
        var level: Int = -1, // -1 = not started: begins at Difficulty.start(age)
        var streak: Int = 0,
        var misses: Int = 0,
        var lastMs: Long = 0,
        var seconds: Int = 0,
    )

    data class Event(val ms: Long, val text: String)

    private val plays = HashMap<String, PlayStat>()
    private val daySeconds = LinkedHashMap<String, Int>() // yyyy-MM-dd → seconds of play
    private val stickers = ArrayList<String>()
    private val skills = HashMap<String, Int>() // e.g. "letter:a" → times practised
    private val events = ArrayDeque<Event>()
    @Volatile var dirty = false
        private set

    init {
        JsonFile.read(file)?.let(::load)
    }

    @Synchronized fun level(playId: String, age: Int): Int =
        plays[playId]?.level?.takeIf { it >= 0 } ?: Difficulty.start(age)

    @Synchronized fun opened(playId: String) {
        stat(playId).apply { opened++; lastMs = System.currentTimeMillis() }
        changed()
    }

    /** One round (question, puzzle, song) done. Returns the new level. */
    @Synchronized fun round(playId: String, firstTry: Boolean, age: Int): Int {
        val s = stat(playId)
        s.rounds++
        if (firstTry) s.firstTry++
        val t = Difficulty.after(Difficulty.Track(level(playId, age), s.streak, s.misses), firstTry, age)
        s.level = t.level; s.streak = t.streak; s.misses = t.misses
        changed()
        return t.level
    }

    @Synchronized fun finished(playId: String) {
        stat(playId).finished++
        changed()
    }

    @Synchronized fun addSeconds(playId: String?, seconds: Int) {
        if (seconds <= 0) return
        if (Clock.plausible()) {
            val day = Clock.dayKey()
            daySeconds[day] = (daySeconds[day] ?: 0) + seconds
            while (daySeconds.size > 60) daySeconds.remove(daySeconds.keys.first())
        }
        playId?.let { stat(it).seconds += seconds }
        changed()
    }

    @Synchronized fun todaySeconds(): Int = if (Clock.plausible()) daySeconds[Clock.dayKey()] ?: 0 else 0

    @Synchronized fun practised(skill: String) {
        skills[skill] = (skills[skill] ?: 0) + 1
        changed()
    }

    @Synchronized fun stickers(): List<String> = stickers.toList()

    /** Adds a sticker the child does not have yet, or returns null if they have all of [pool]. */
    @Synchronized fun award(pool: List<String>, random: kotlin.random.Random = kotlin.random.Random): String? {
        val fresh = pool.filter { it !in stickers }
        val pick = if (fresh.isEmpty()) null else fresh[random.nextInt(fresh.size)]
        if (pick != null) {
            stickers += pick
            changed()
        }
        return pick
    }

    @Synchronized fun log(text: String) {
        events.addLast(Event(System.currentTimeMillis(), text))
        while (events.size > 80) events.removeFirst()
        changed()
    }

    /** After a grown-up changes the age: every activity starts again from the new age's level. */
    @Synchronized fun resetLevels() {
        for (s in plays.values) {
            s.level = -1
            s.streak = 0
            s.misses = 0
        }
        changed()
    }

    @Synchronized fun reset() {
        plays.clear(); daySeconds.clear(); stickers.clear(); skills.clear(); events.clear()
        changed()
    }

    private fun stat(id: String) = plays.getOrPut(id) { PlayStat() }

    private fun changed() {
        dirty = true
    }

    // ---- persistence + the dashboard's view

    @Synchronized fun toJson(): JSONObject {
        val p = JSONObject()
        for ((id, s) in plays) p.put(
            id, JSONObject().put("opened", s.opened).put("finished", s.finished).put("rounds", s.rounds)
                .put("firstTry", s.firstTry).put("level", s.level).put("streak", s.streak).put("misses", s.misses)
                .put("lastMs", s.lastMs).put("seconds", s.seconds),
        )
        val days = JSONObject().also { j -> daySeconds.forEach { (k, v) -> j.put(k, v) } }
        val sk = JSONObject().also { j -> skills.forEach { (k, v) -> j.put(k, v) } }
        val ev = JSONArray().also { a -> events.forEach { a.put(JSONObject().put("ms", it.ms).put("text", it.text)) } }
        return JSONObject().put("plays", p).put("days", days).put("stickers", JSONArray(stickers)).put("skills", sk).put("events", ev)
    }

    fun save() {
        if (!dirty) return
        val j = toJson()
        dirty = false
        JsonFile.write(file, j)
    }

    private fun load(j: JSONObject) {
        j.optJSONObject("plays")?.let { p ->
            for (id in p.keys()) {
                val s = p.getJSONObject(id)
                plays[id] = PlayStat(
                    s.optInt("opened"), s.optInt("finished"), s.optInt("rounds"), s.optInt("firstTry"),
                    s.optInt("level", -1), s.optInt("streak"), s.optInt("misses"), s.optLong("lastMs"), s.optInt("seconds"),
                )
            }
        }
        j.optJSONObject("days")?.let { d -> d.keys().asSequence().sorted().forEach { daySeconds[it] = d.optInt(it) } }
        j.optJSONArray("stickers")?.strings()?.let(stickers::addAll)
        j.optJSONObject("skills")?.let { s -> for (k in s.keys()) skills[k] = s.optInt(k) }
        j.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { events.addLast(Event(it.optLong("ms"), it.optString("text"))) }
        }
    }
}
