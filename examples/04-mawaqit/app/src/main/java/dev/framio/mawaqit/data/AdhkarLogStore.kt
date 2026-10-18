package dev.framio.mawaqit.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarHistory
import dev.framio.mawaqit.core.AdhkarLog
import dev.framio.mawaqit.core.AdhkarProgress
import dev.framio.mawaqit.core.DayLog
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * The adhkar log on disk: `files/adhkar-log.json` (a few KB a month, at most [AdhkarHistory.KEEP_DAYS] days),
 * replaced atomically. Counting only changes the in-memory log; the file is written [SAVE_DELAY_MS] after the last
 * change, at once when a collection is completed (its time is only known here), and when the day changes. Losing the
 * last unsaved seconds loses nothing: today's and yesterday's counts are in the progress, and [restore] rebuilds
 * those days from it on the next start.
 */
class AdhkarLogStore(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "adhkar-log.json"))
    private val prefs = context.getSharedPreferences("adhkar", Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(AdhkarHistory())
    val history: StateFlow<AdhkarHistory> = _history.asStateFlow()
    private var pending: Job? = null
    private var loaded = false

    /** Reads the file (or, the first time, migrates the progress's completed days) and catches up from [p]. */
    fun restore(p: AdhkarProgress, items: (AdhkarCategory, PrayerKey?) -> List<Dhikr>, now: LocalDateTime) {
        if (loaded) return
        loaded = true
        val stored = try {
            if (file.baseFile.exists()) AdhkarHistory.fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8))) else null
        } catch (e: Exception) {
            Log.w(TAG, "log unreadable, starting from the progress: ${e.message}")
            null
        }
        // the first time, or after a damaged file: the completed days the progress kept (the streaks)
        var h = if (stored == null) AdhkarHistory.migrate(p) else AdhkarHistory.migrate(p, stored)
        for (day in p.counts.keys + listOfNotNull(p.tasbihDay)) h = h.with(day, AdhkarLog.dayFrom(p, day, items, h[day], AdhkarLog.minuteOf(day, now)))
        _history.value = h
        if (stored == null) Log.i(TAG, "log started: ${h.days.size} days from the progress")
        if (h != stored) save(0)
    }

    /** The progress changed: rebuild [days] (the session's day, and today for the tasbih). */
    fun record(p: AdhkarProgress, days: Collection<LocalDate>, items: (AdhkarCategory, PrayerKey?) -> List<Dhikr>, now: LocalDateTime) {
        if (!loaded) return // before the clock is set: restore() rebuilds these days from the progress
        val before = _history.value
        var h = before
        var completed = false
        for (day in days.toSet()) {
            val old = h[day]
            val log = AdhkarLog.dayFrom(p, day, items, old, AdhkarLog.minuteOf(day, now))
            if (log.sessions.any { (k, v) -> v.complete && old?.sessions?.get(k)?.complete != true }) completed = true
            h = h.with(day, log)
        }
        if (h == before) return
        val newDay = h.days.keys.maxOrNull() != before.days.keys.maxOrNull()
        _history.value = h
        save(if (completed || newDay) 0 else SAVE_DELAY_MS)
    }

    /** Writes now (when the service stops, or the app is told to trim memory). */
    fun flush() {
        if (pending?.isActive == true) save(0)
    }

    private fun save(delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val text = _history.value.toJson().toString()
            withContext(Dispatchers.IO) { write(text) }
        }
    }

    @Synchronized
    private fun write(text: String) {
        val out = try { file.startWrite() } catch (e: Exception) {
            Log.w(TAG, "can't write the log: ${e.message}")
            return
        }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "can't write the log: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- debug builds only (adhkar.history.seed)

    /**
     * Fills the days before today that have no entry with made-up but plausible ones, to look at the log screen with
     * months of data. The days written are remembered, and [clearSeed] removes exactly those: the real log stays.
     */
    fun seed(days: Int, today: LocalDate, items: (AdhkarCategory, PrayerKey?) -> List<Dhikr>): Int {
        val rnd = Random(today.toEpochDay())
        var h = _history.value
        val seeded = seededDays().toMutableSet()
        var n = 0
        for (i in 1..days.coerceIn(1, 380)) {
            val day = today.minusDays(i.toLong())
            if (h[day] != null) continue
            val habit = if (i < 40) 0.85 else 0.6 // more regular lately
            val sessions = HashMap<String, SessionLog>()
            fun session(key: String, c: AdhkarCategory, after: PrayerKey?, p: Double, minute: Int) {
                if (rnd.nextDouble() > p) return
                val list = items(c, after)
                val reps = list.sumOf { it.count(after) }
                val complete = rnd.nextDouble() < 0.85
                val said = if (complete) reps else (reps * rnd.nextDouble(0.2, 0.9)).toInt()
                val done = if (complete) list.size else (list.size * said / reps.coerceAtLeast(1))
                sessions[key] = SessionLog(done, list.size, said, reps, complete, if (complete) minute + rnd.nextInt(-25, 40) else null)
            }
            session("morning", AdhkarCategory.MORNING, null, habit, 7 * 60 + 30)
            session("evening", AdhkarCategory.EVENING, null, habit - 0.1, 17 * 60 + 10)
            for ((k, m) in listOf(PrayerKey.FAJR to 400, PrayerKey.DHUHR to 800, PrayerKey.ASR to 1000, PrayerKey.MAGHRIB to 1130, PrayerKey.ISHA to 1240)) {
                session("after_prayer:${k.id}", AdhkarCategory.AFTER_PRAYER, k, habit - 0.25, m)
            }
            session("sleep", AdhkarCategory.SLEEP, null, habit - 0.35, 23 * 60)
            // a few of the other collections too, so the log shows more than the daily ones
            for ((id, m) in listOf("waking" to 6 * 60 + 20, "dhikr" to 12 * 60 + 30, "istighfar" to 21 * 60)) {
                AdhkarCategory.of(id)?.let { session(id, it, null, habit - 0.3, m) }
            }
            val tasbih = if (rnd.nextDouble() < 0.5) 33 * rnd.nextInt(1, 6) else 0
            val log = DayLog(sessions, tasbih)
            if (log.isEmpty) continue
            h = h.with(day, log)
            seeded += day
            n++
        }
        _history.value = h
        prefs.edit { putString(SEEDED, JSONArray(seeded.map { it.toString() }).toString()) }
        save(0)
        return n
    }

    fun clearSeed(): Int {
        val seeded = seededDays()
        var h = _history.value
        for (d in seeded) h = h.with(d, DayLog())
        _history.value = h
        prefs.edit { remove(SEEDED) }
        save(0)
        return seeded.size
    }

    private fun seededDays(): Set<LocalDate> = runCatching {
        val a = JSONArray(prefs.getString(SEEDED, "[]"))
        (0 until a.length()).mapNotNull { runCatching { LocalDate.parse(a.getString(it)) }.getOrNull() }.toSet()
    }.getOrDefault(emptySet())

    private companion object {
        const val TAG = "AdhkarLog"
        const val SAVE_DELAY_MS = 30_000L
        const val SEEDED = "logSeeded"
    }
}
