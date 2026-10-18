package dev.framio.mawaqit.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.edit
import dev.framio.mawaqit.audio.AdhanPlayer
import dev.framio.mawaqit.core.AdhkarBook
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarHistory
import dev.framio.mawaqit.core.AdhkarLog
import dev.framio.mawaqit.core.AdhkarProgress
import dev.framio.mawaqit.core.AdhkarReminders
import dev.framio.mawaqit.core.AdhkarSchedule
import dev.framio.mawaqit.core.AdhkarSettings
import dev.framio.mawaqit.core.AdhkarStats
import dev.framio.mawaqit.core.AdhkarTime
import dev.framio.mawaqit.core.Behavior
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.TimesSource
import dev.framio.mawaqit.core.adhkarGoals
import dev.framio.mawaqit.core.stats
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.remote.Rpc
import dev.framio.mawaqit.ui.isFullScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the adhkar section shows; null = closed. */
sealed interface AdhkarView {
    data object Categories : AdhkarView
    data class Reader(val category: AdhkarCategory, val after: PrayerKey?, val index: Int) : AdhkarView
    data class Done(val category: AdhkarCategory, val after: PrayerKey?) : AdhkarView
    data object Tasbih : AdhkarView
    /** The log: today, streaks, the heatmap, rates (ui/AdhkarStats.kt). */
    data object Log : AdhkarView
    /** The reminder schedules, editable on the frame (ui/AdhkarSchedulesScreen.kt). */
    data object Schedules : AdhkarView
}

/** [pulse] grows by one on every count: the UI animates on its change. [focus] is the D-pad's card. */
data class AdhkarUi(val view: AdhkarView? = null, val focus: Int = 0, val pulse: Int = 0)

/** A collection due now and not done: shown on the main screen. [since] is when it first appeared (ms epoch). */
data class AdhkarReminder(val category: AdhkarCategory, val day: LocalDate, val done: Int, val total: Int, val until: ZonedDateTime, val since: Long)

/** A collection's state today, for the cards, the reminder and the panel. */
data class AdhkarSummary(val category: AdhkarCategory, val after: PrayerKey?, val day: LocalDate, val done: Int, val total: Int, val complete: Boolean, val streak: Int)

/**
 * The adhkar section: the book (assets/adhkar.json), today's counts (SharedPreferences, kept per day so they reset at
 * local midnight), the reader's state and the reminders. [tick] runs every second from the engine, on the main
 * thread like every action here (RPCs post to it).
 *
 * The reader never fights the prayer flow: while a full-screen phase, a message or a black screen is up the reader
 * is only suspended ([onTop] is false and Root shows the phase); it comes back if that lasted under [RESUME_MS], and
 * closes after [IDLE_MS] without a touch.
 */
class AdhkarController(
    private val context: Context,
    private val store: SettingsStore,
    private val player: AdhanPlayer,
    private val scope: CoroutineScope,
    private val displayVisible: () -> Boolean,
    private val showDisplay: () -> Unit,
    /** Lights a screen the night switched off (a schedule that asks to wake it). */
    private val wake: () -> Unit = {},
    /** The display's presence policy for a firing reminder (category, opens): true when it lights / brings the display. */
    private val presence: (AdhkarCategory, Boolean) -> Boolean = { _, _ -> false },
) {
    private val prefs = context.getSharedPreferences("adhkar", Context.MODE_PRIVATE)
    private val sound = AdhkarSound()
    /** The durable log of every day (data/AdhkarLogStore.kt); fed from the progress on each change. */
    val log = AdhkarLogStore(context, scope)
    private val scheduler = AdhkarScheduler(object : AdhkarScheduler.Host {
        override val progress get() = _progress.value
        override fun save(p: AdhkarProgress) = this@AdhkarController.save(p)
        override fun complete(c: AdhkarCategory, day: LocalDate) = isComplete(c, day)
        override fun progressOf(c: AdhkarCategory, day: LocalDate): Pair<Int, Int> {
            val after = if (c == AdhkarCategory.AFTER_PRAYER) after() else null
            val items = items(c, after)
            val session = AdhkarProgress.session(c, after)
            return items.count { _progress.value.count(day, session, it.id) >= it.count(after) } to items.size
        }
        override fun viewOpen() = _ui.value.view != null
        override fun displayVisible() = displayVisible.invoke()
        override fun chime() {
            if (player.playing.value != null) return
            sound.chime()
            Log.i(TAG, "reminder chime")
        }
        override fun hold() {
            holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
        }
        override fun openReader(c: AdhkarCategory) {
            open(c)
            lastTouch = SystemClock.elapsedRealtime()
            holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
            showDisplay()
        }
        override fun wake() = wake.invoke()
        override fun presence(c: AdhkarCategory, opens: Boolean) = presence.invoke(c, opens)
    }, AdhkarPush(scope))
    /** Where Back goes from the log and the schedules. */
    private var logFrom: AdhkarView? = null

    /** Parsed on first use (about 80 KB of JSON, a few ms), then kept. */
    val book: AdhkarBook by lazy {
        AdhkarBook.parse(context.assets.open("adhkar.json").use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private val _progress = MutableStateFlow(load())
    val progress: StateFlow<AdhkarProgress> = _progress.asStateFlow()
    private val _ui = MutableStateFlow(AdhkarUi())
    val ui: StateFlow<AdhkarUi> = _ui.asStateFlow()
    private val _reminder = MutableStateFlow<AdhkarReminder?>(null)
    val reminder: StateFlow<AdhkarReminder?> = _reminder.asStateFlow()
    /** The collection whose time it is (highlighted on its card). */
    private val _current = MutableStateFlow<AdhkarCategory?>(null)
    val current: StateFlow<AdhkarCategory?> = _current.asStateFlow()

    /** Columns of the categories grid as laid out, for the D-pad. */
    @Volatile var gridColumns = 3

    private var last: DisplayState? = null
    private var open: List<AdhkarSchedule.Window> = emptyList()
    private var windowsKey: List<Any?>? = null
    private var windows: List<AdhkarSchedule.Window> = emptyList()
    private var lastTouch = 0L
    private var suspendedSince: Long? = null
    private var holdUntil = 0L
    private var advance: Job? = null

    // ---------------------------------------------------------------- tick

    fun tick(st: DisplayState) {
        last = st
        if (st.synced) log.restore(_progress.value, ::items, st.now.toLocalDateTime())
        val times = st.source.times
        val s = st.settings.adhkar
        if (!st.synced || times == null) {
            _reminder.value = null
            return
        }
        open = windows(st.now, times, st.behavior, s).filter { st.now in it }
        _current.value = AdhkarSchedule.current(open)
        reminderTick(st, s)
        readerTick(st)
    }

    /** Yesterday's and today's windows, built once a day (and when the times, flow or settings change). */
    private fun windows(now: ZonedDateTime, times: TimesSource, b: Behavior, s: AdhkarSettings): List<AdhkarSchedule.Window> {
        val today = now.withZoneSameInstant(times.zone).toLocalDate()
        val key = listOf(today, System.identityHashCode(times), b, s)
        if (key != windowsKey) {
            windows = AdhkarSchedule.windows(today.minusDays(1), times, b, s) + AdhkarSchedule.windows(today, times, b, s)
            windowsKey = key
        }
        return windows
    }

    /** The schedules: what fires now, the card to show (data/AdhkarScheduler.kt). */
    private fun reminderTick(st: DisplayState, s: AdhkarSettings) {
        _reminder.value = scheduler.tick(st, s, windows, _reminder.value)
    }

    /** Whether [c]'s session of [day] is complete; after the prayer, the last prayer's. */
    private fun isComplete(c: AdhkarCategory, day: LocalDate): Boolean =
        _progress.value.isDone(day, AdhkarProgress.session(c, if (c == AdhkarCategory.AFTER_PRAYER) after() else null))

    private fun readerTick(st: DisplayState) {
        val ui = _ui.value
        if (ui.view == null) return
        val now = SystemClock.elapsedRealtime()
        if (covered(st, ui.view)) {
            if (suspendedSince == null) suspendedSince = now
            return
        }
        suspendedSince?.let { since ->
            suspendedSince = null
            if (now - since > RESUME_MS) return close("suspended for ${(now - since) / 1000} s")
            lastTouch = now
        }
        if (now - lastTouch > IDLE_MS) close("idle")
    }

    /** Whatever Root would show instead of the reader: black, a message, the clock sync, a full-screen phase. */
    private fun covered(st: DisplayState, view: AdhkarView?): Boolean {
        if (st.screen.black || !st.synced || st.overlay != null || st.source.times == null) return true
        if (!isFullScreen(st)) return false
        // Opened by hand at night (dim: black is above): the reader shows, the night dims it again when left alone
        if (st.phase.kind == PhaseKind.NIGHT) return false
        // The counted after-prayer adhkar may stand in for Mawaqit's azkar screen, which the user tapped
        val afterPrayer = (view as? AdhkarView.Reader)?.category == AdhkarCategory.AFTER_PRAYER ||
            (view as? AdhkarView.Done)?.category == AdhkarCategory.AFTER_PRAYER
        return !(st.phase.kind == PhaseKind.AZKAR && afterPrayer)
    }

    /** Root: whether the adhkar screen is the one to draw now. */
    fun onTop(st: DisplayState, ui: AdhkarUi): Boolean = ui.view != null && !covered(st, ui.view)

    /**
     * The presence policy keeps the display on screen while this is true: a moment when a reminder appears (it brings the
     * display forward once), and while the reader is open on screen (leaving it for the photos by hand still works).
     */
    fun holdsDisplay(): Boolean = (_ui.value.view != null && displayVisible()) || SystemClock.elapsedRealtime() < holdUntil

    // ---------------------------------------------------------------- summaries

    fun after(st: DisplayState? = last): PrayerKey? {
        st ?: return null
        if (st.phase.kind == PhaseKind.AZKAR) st.phase.prayer?.key?.let { return if (it == PrayerKey.JUMUA) PrayerKey.DHUHR else it }
        val times = st.source.times ?: return null
        return AdhkarSchedule.lastPrayer(st.now, times)
    }

    fun items(category: AdhkarCategory, after: PrayerKey?): List<Dhikr> = book.items(category, after)

    fun day(category: AdhkarCategory): LocalDate {
        val now = last?.now ?: ZonedDateTime.now()
        return AdhkarSchedule.progressDay(category, now, open)
    }

    fun summary(category: AdhkarCategory, p: AdhkarProgress = _progress.value): AdhkarSummary {
        val after = if (category == AdhkarCategory.AFTER_PRAYER) after() else null
        val session = AdhkarProgress.session(category, after)
        val day = day(category)
        val items = items(category, after)
        val done = items.count { p.count(day, session, it.id) >= it.count(after) }
        val streak = if (category == AdhkarCategory.MORNING || category == AdhkarCategory.EVENING) AdhkarLog.streak(log.history.value, category, day) else 0
        return AdhkarSummary(category, after, day, done, items.size, p.isDone(day, session), streak)
    }

    fun count(view: AdhkarView.Reader, d: Dhikr, p: AdhkarProgress = _progress.value): Int =
        p.count(day(view.category), AdhkarProgress.session(view.category, view.after), d.id)

    fun tasbihToday(p: AdhkarProgress = _progress.value): Int = p.tasbihToday(last?.now?.toLocalDate() ?: LocalDate.now())

    // ---------------------------------------------------------------- actions

    /** Opens [category] at its first dhikr not yet done, the categories when null, the tasbih for "tasbih". */
    fun open(category: AdhkarCategory?, tasbih: Boolean = false) {
        touch()
        advance?.cancel()
        val view = when {
            tasbih -> AdhkarView.Tasbih
            category == null -> AdhkarView.Categories
            else -> reader(category)
        }
        val focus = category?.let { CARDS.indexOf(it) } ?: CARDS.indexOf(_current.value).coerceAtLeast(0)
        _ui.value = _ui.value.copy(view = view, focus = if (tasbih) CARDS.size else focus)
        Log.i(TAG, "open ${category?.id ?: if (tasbih) "tasbih" else "categories"}")
    }

    private fun reader(category: AdhkarCategory): AdhkarView {
        val after = if (category == AdhkarCategory.AFTER_PRAYER) after() else null
        val items = items(category, after)
        val day = day(category)
        val session = AdhkarProgress.session(category, after)
        val p = _progress.value
        if (p.isDone(day, session)) return AdhkarView.Done(category, after)
        val first = items.indexOfFirst { p.count(day, session, it.id) < it.count(after) }.coerceAtLeast(0)
        return AdhkarView.Reader(category, after, first)
    }

    fun close(why: String = "closed") {
        advance?.cancel()
        if (_ui.value.view != null) Log.i(TAG, "reader closed: $why")
        _ui.value = AdhkarUi(focus = _ui.value.focus)
        suspendedSince = null
    }

    /** The log screen; Back returns to what was open (or closes when it was opened from outside). */
    fun openLog() {
        touch()
        advance?.cancel()
        val v = _ui.value.view
        if (v != AdhkarView.Log && v != AdhkarView.Schedules) logFrom = if (v is AdhkarView.Reader) AdhkarView.Categories else v
        _ui.value = _ui.value.copy(view = AdhkarView.Log)
        Log.i(TAG, "open log")
    }

    /** The reminder schedules; Back returns to the log when opened from there. */
    fun openSchedules() {
        touch()
        advance?.cancel()
        val v = _ui.value.view
        if (v != AdhkarView.Log && v != AdhkarView.Schedules) logFrom = if (v is AdhkarView.Reader) AdhkarView.Categories else v
        schedulesFromLog = v == AdhkarView.Log
        _ui.value = _ui.value.copy(view = AdhkarView.Schedules)
        Log.i(TAG, "open schedules")
    }

    private var schedulesFromLog = false

    /** Back: from a collection to the categories, from the categories out. */
    fun back() {
        touch()
        when (_ui.value.view) {
            null -> Unit
            AdhkarView.Categories -> close()
            AdhkarView.Schedules -> if (schedulesFromLog) {
                schedulesFromLog = false
                _ui.value = _ui.value.copy(view = AdhkarView.Log)
            } else leaveLog()
            AdhkarView.Log -> leaveLog()
            else -> {
                advance?.cancel()
                _ui.value = _ui.value.copy(view = AdhkarView.Categories)
            }
        }
    }

    private fun leaveLog() {
        val to = logFrom
        logFrom = null
        if (to == null) close() else _ui.value = _ui.value.copy(view = to)
    }

    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
    }

    /** One count of the current dhikr; at its number, a short pause and on to the next one (or the end). */
    fun count() {
        touch()
        when (val v = _ui.value.view) {
            is AdhkarView.Reader -> countReader(v)
            AdhkarView.Tasbih -> countTasbih()
            else -> Unit
        }
    }

    private fun countReader(v: AdhkarView.Reader) {
        val items = items(v.category, v.after)
        val d = items.getOrNull(v.index) ?: return
        val day = day(v.category)
        val session = AdhkarProgress.session(v.category, v.after)
        var p = _progress.value
        val target = d.count(v.after)
        val n = p.count(day, session, d.id)
        if (n >= target) return go(v, +1) // already said: a tap moves on
        p = p.withCount(day, session, d.id, n + 1)
        val complete = items.all { p.count(day, session, it.id) >= it.count(v.after) }
        if (complete) p = p.withDone(day, session)
        save(p)
        feedback()
        if (n + 1 < target) return
        advance?.cancel()
        advance = scope.launch {
            delay(ADVANCE_MS)
            val now = _ui.value.view as? AdhkarView.Reader ?: return@launch
            if (now != v) return@launch
            if (complete) {
                _ui.value = _ui.value.copy(view = AdhkarView.Done(v.category, v.after))
            } else {
                // the next one still to say, after this one (wrapping round to any skipped before it)
                val order = (v.index + 1 until items.size) + (0 until v.index)
                val next = order.firstOrNull { _progress.value.count(day, session, items[it].id) < items[it].count(v.after) } ?: v.index
                _ui.value = _ui.value.copy(view = v.copy(index = next))
            }
        }
    }

    private fun countTasbih() {
        val today = last?.now?.toLocalDate() ?: LocalDate.now()
        val p = _progress.value
        save(p.withTasbih(today, p.tasbihToday(today) + 1))
        feedback()
    }

    private fun feedback() {
        _ui.value = _ui.value.copy(pulse = _ui.value.pulse + 1)
        if (store.current.adhkar.tapSound && player.playing.value == null) sound.tick()
    }

    /** Previous / next dhikr; past the last one, the end screen when all are said. */
    fun go(delta: Int) {
        touch()
        val v = _ui.value.view as? AdhkarView.Reader ?: return
        go(v, delta)
    }

    private fun go(v: AdhkarView.Reader, delta: Int) {
        advance?.cancel()
        val items = items(v.category, v.after)
        val i = v.index + delta
        _ui.value = when {
            i < 0 -> return
            i >= items.size -> {
                val s = summary(v.category)
                if (!s.complete) return
                _ui.value.copy(view = AdhkarView.Done(v.category, v.after))
            }
            else -> _ui.value.copy(view = v.copy(index = i))
        }
    }

    fun goTo(index: Int) {
        touch()
        val v = _ui.value.view as? AdhkarView.Reader ?: return
        advance?.cancel()
        _ui.value = _ui.value.copy(view = v.copy(index = index.coerceIn(0, items(v.category, v.after).lastIndex)))
    }

    /** Starts [category] (or the one on screen) again from zero, today. */
    fun reset(category: AdhkarCategory? = null) {
        touch()
        advance?.cancel()
        val v = _ui.value.view
        val cat = category ?: (v as? AdhkarView.Reader)?.category ?: (v as? AdhkarView.Done)?.category
        if (cat == null) {
            if (v == AdhkarView.Tasbih) save(_progress.value.withTasbih(last?.now?.toLocalDate() ?: LocalDate.now(), 0))
            return
        }
        val after = if (cat == AdhkarCategory.AFTER_PRAYER) after() else null
        save(_progress.value.reset(day(cat), AdhkarProgress.session(cat, after)))
        if (v is AdhkarView.Reader || v is AdhkarView.Done) _ui.value = _ui.value.copy(view = AdhkarView.Reader(cat, after, 0))
    }

    fun setTasbihPhrase(id: String) {
        touch()
        val p = _progress.value
        save(p.copy(tasbihPhrase = id))
    }

    /** "Not now": no more reminder of this collection for its window's day. */
    fun dismissReminder() {
        val r = _reminder.value ?: return
        save(_progress.value.withDismissed(AdhkarProgress.reminderKey(r.day, r.category)))
        _reminder.value = null
        holdUntil = 0
        Log.i(TAG, "reminder ${r.category.id} dismissed for ${r.day}")
    }

    fun toggle(setting: String) {
        touch()
        val a = store.current.adhkar
        val value = when (setting) {
            "translation" -> !a.translation
            "transliteration" -> !a.transliteration
            "tapSound" -> !a.tapSound
            else -> return
        }
        store.patch(JSONObject().put("adhkar", JSONObject().put(setting, value)))
    }

    // ---------------------------------------------------------------- schedules

    /** Adds [t], or replaces the schedule with its id. */
    fun saveSchedule(t: AdhkarTime) {
        touch()
        val list = store.current.adhkar.schedules
        val next = if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else (list + t).take(AdhkarReminders.MAX)
        patchSchedules(next)
    }

    fun removeSchedule(id: String) {
        touch()
        patchSchedules(store.current.adhkar.schedules.filter { it.id != id })
    }

    fun toggleSchedule(id: String) {
        touch()
        patchSchedules(store.current.adhkar.schedules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    private fun patchSchedules(list: List<AdhkarTime>) {
        store.patch(JSONObject().put("adhkar", JSONObject().put("enabled", true).put("schedules", JSONArray(list.map { it.toJson() }))))
    }

    /** The schedules' occurrences of yesterday and today (for the next one on the log and the panel). */
    fun dues(): List<AdhkarReminders.Due> = scheduler.dues

    // ---------------------------------------------------------------- keys

    /**
     * IR remote / keyboard. Closed: digits 1–5 open a collection, 0 the tasbih, a D-pad arrow the categories.
     * Categories: arrows move, OK opens. Reader and tasbih: OK counts, left/right move (mirrored in Arabic).
     */
    fun onKey(keyCode: Int): Boolean {
        val s = store.current
        val view = _ui.value.view
        // Under an adhan, a message or a black screen the keys are not ours
        if (last?.let { covered(it, view) } != false) return false
        val rtl = s.display.language == "ar"
        val forward = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val backward = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        // 9, Info or Guide: the log, from the main screen, the collections or a completed one
        val logKey = keyCode == KeyEvent.KEYCODE_9 || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_GUIDE
        if (logKey && s.adhkar.enabled && (view == null || view == AdhkarView.Categories || view is AdhkarView.Done)) {
            openLog()
            if (view == null) showDisplay()
            return true
        }
        when (view) {
            AdhkarView.Log -> when {
                ok || logKey -> back()
                keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) -> touch()
                else -> return false
            }
            AdhkarView.Schedules -> {
                touch()
                return false
            }
            null -> {
                if (!s.adhkar.enabled) return false
                when (keyCode) {
                    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5 -> open(CARDS[keyCode - KeyEvent.KEYCODE_1])
                    KeyEvent.KEYCODE_0 -> open(null, tasbih = true)
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (_reminder.value != null) open(_reminder.value!!.category) else open(null)
                    else -> return false
                }
            }
            AdhkarView.Categories -> {
                touch()
                val cols = gridColumns.coerceAtLeast(1)
                val f = _ui.value.focus
                val moved = when (keyCode) {
                    forward -> f + 1
                    backward -> f - 1
                    KeyEvent.KEYCODE_DPAD_DOWN -> f + cols
                    KeyEvent.KEYCODE_DPAD_UP -> f - cols
                    else -> null
                }
                when {
                    moved != null -> _ui.value = _ui.value.copy(focus = moved.coerceIn(0, CARDS.size))
                    ok -> if (f >= CARDS.size) open(null, tasbih = true) else open(CARDS[f])
                    keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5 -> open(CARDS[keyCode - KeyEvent.KEYCODE_1])
                    keyCode == KeyEvent.KEYCODE_0 -> open(null, tasbih = true)
                    else -> return false
                }
            }
            is AdhkarView.Reader -> when {
                ok -> count()
                keyCode == forward -> go(+1)
                keyCode == backward -> go(-1)
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> touch()
                else -> return false
            }
            AdhkarView.Tasbih -> when {
                ok -> count()
                keyCode == forward || keyCode == backward -> {
                    val phrases = book.tasbih
                    val i = phrases.indexOfFirst { it.id == _progress.value.tasbihPhrase }.coerceAtLeast(0)
                    val j = (i + if (keyCode == forward) 1 else phrases.size - 1) % phrases.size
                    setTasbihPhrase(phrases[j].id)
                }
                else -> return false
            }
            is AdhkarView.Done -> when {
                ok -> back()
                else -> return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- remote

    /** adhkar.open / adhkar.close / adhkar.dismiss / adhkar.reset (REMOTE.md). Runs on the main thread. */
    fun rpc(cmd: String, args: JSONObject): JSONObject {
        val name = args.optString("category").trim()
        val category = AdhkarCategory.of(name)
        if (name.isNotEmpty() && name != "tasbih" && category == null) {
            throw Rpc.Failure("bad_request", "category is one of ${AdhkarCategory.entries.joinToString { it.id }}, tasbih")
        }
        when (cmd) {
            "adhkar.open" -> {
                when (args.optString("view")) {
                    "log" -> openLog()
                    "schedules" -> openSchedules()
                    "" -> open(category, tasbih = name == "tasbih")
                    else -> throw Rpc.Failure("bad_request", "view is log or schedules")
                }
                showDisplay()
            }
            "adhkar.history" -> return historyJson(args.optInt("days", 120).coerceIn(1, AdhkarHistory.KEEP_DAYS.toInt()), args.optBoolean("detail", true))
            "adhkar.schedule.list" -> return schedulesJson()
            "adhkar.schedule.add" -> {
                val j = args.optJSONObject("schedule") ?: throw Rpc.Failure("bad_request", "schedule must be an object")
                val list = store.current.adhkar.schedules
                if (!j.has("id")) j.put("id", AdhkarReminders.newId(list.map { it.id }))
                val t = AdhkarTime.fromJson(j) ?: throw Rpc.Failure("bad_request",
                    "category is one of ${AdhkarCategory.entries.joinToString { it.id }}; at is HH:MM or <prayer>+N / <prayer>-N")
                if (list.size >= AdhkarReminders.MAX && list.none { it.id == t.id }) throw Rpc.Failure("too_many", "${AdhkarReminders.MAX} schedules at most")
                saveSchedule(t)
                return schedulesJson()
            }
            "adhkar.schedule.remove" -> {
                val id = args.optString("id")
                if (store.current.adhkar.schedules.none { it.id == id }) throw Rpc.Failure("not_found", "no schedule $id")
                removeSchedule(id)
                return schedulesJson()
            }
            "adhkar.push.test" -> {
                val s = store.current
                if (s.adhkar.pushTopic.isEmpty()) throw Rpc.Failure("bad_request", "set adhkar.pushTopic first")
                if (!scheduler.pushTest(s.adhkar, Strings.of(s.display.language), LocalDate.now())) throw Rpc.Failure("too_many", "today's phone reminders are used up")
            }
            "adhkar.history.seed", "adhkar.history.unseed" -> {
                // debug builds only: months of made-up days to look at the log, removed again by unseed
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) throw Rpc.Failure("unknown_cmd", cmd)
                val n = if (cmd == "adhkar.history.seed") log.seed(args.optInt("days", 120), LocalDate.now(), ::items) else log.clearSeed()
                return JSONObject().put("days", n)
            }
            "adhkar.close" -> close("remote")
            "adhkar.dismiss" -> dismissReminder()
            "adhkar.reset" -> if (name == "tasbih") save(_progress.value.withTasbih(LocalDate.now(), 0)) else reset(category ?: throw Rpc.Failure("bad_request", "which category?"))
            else -> throw Rpc.Failure("unknown_cmd", cmd)
        }
        return JSONObject()
    }

    /** The `adhkar` object of the remote `state`. */
    fun stateJson(): JSONObject {
        val ui = _ui.value
        val p = _progress.value
        val today = JSONObject()
        for (c in AdhkarCategory.entries) {
            val s = summary(c, p)
            today.put(c.id, JSONObject().put("done", s.done).put("total", s.total).put("complete", s.complete)
                .put("streak", s.streak).put("after", s.after?.id ?: JSONObject.NULL))
        }
        val r = _reminder.value
        val view = ui.view
        return JSONObject()
            .put("enabled", store.current.adhkar.enabled)
            .put("open", view != null)
            .put("view", when (view) {
                null -> JSONObject.NULL
                AdhkarView.Categories -> "categories"
                is AdhkarView.Reader -> "reader"
                is AdhkarView.Done -> "done"
                AdhkarView.Tasbih -> "tasbih"
                AdhkarView.Log -> "log"
                AdhkarView.Schedules -> "schedules"
            })
            .put("category", (view as? AdhkarView.Reader)?.category?.id ?: (view as? AdhkarView.Done)?.category?.id ?: JSONObject.NULL)
            .put("index", (view as? AdhkarView.Reader)?.index ?: JSONObject.NULL)
            .put("current", _current.value?.id ?: JSONObject.NULL)
            .put("reminder", r?.let {
                JSONObject().put("category", it.category.id).put("done", it.done).put("total", it.total).put("until", it.until.toInstant().toEpochMilli())
            } ?: JSONObject.NULL)
            .put("today", today)
            .put("tasbih", tasbihToday(p))
            .put("stats", stats().toJson().apply { remove("collections") })
            .put("next", nextJson())
    }

    // ---------------------------------------------------------------- log

    private var statsKey: List<Any?>? = null
    private var statsValue: AdhkarStats? = null

    /** The log's figures for today, kept until the log, the day or the schedules change. */
    fun stats(h: AdhkarHistory = log.history.value, today: LocalDate = last?.now?.toLocalDate() ?: LocalDate.now()): AdhkarStats {
        val schedules = store.current.adhkar.schedules
        val key = listOf(System.identityHashCode(h), today, schedules)
        statsValue?.takeIf { key == statsKey }?.let { return it }
        return AdhkarLog.stats(h, today, adhkarGoals(schedules), size = { items(it, null).size }).also {
            statsValue = it
            statsKey = key
        }
    }

    /**
     * adhkar.history: the figures, and the last [days] days (newest first) that have an entry. Without [detail] only
     * each day's score and tasbih, small enough for one ntfy message.
     */
    private fun historyJson(days: Int, detail: Boolean): JSONObject {
        val h = log.history.value
        val today = last?.now?.toLocalDate() ?: LocalDate.now()
        val goals = adhkarGoals(store.current.adhkar.schedules)
        val list = JSONArray()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val day = h[d] ?: continue
            val c = JSONObject()
            for (cat in AdhkarCategory.entries) if (day.touched(cat)) c.put(cat.id, Math.round(day.share(cat) * 100) / 100.0)
            val e = JSONObject().put("date", d.toString()).put("score", AdhkarLog.score(day, goals(d))?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
                .put("tasbih", day.tasbih)
            if (detail) e.put("collections", c).put("sessions", day.toJson().getJSONObject("s"))
            list.put(e)
        }
        return JSONObject().put("today", today.toString()).put("first", h.first?.toString() ?: JSONObject.NULL)
            .put("stats", stats(h, today).toJson().apply { if (!detail) getJSONObject("collections").keys().forEach { getJSONObject("collections").getJSONObject(it).remove("week") } })
            .put("days", list)
    }

    private fun schedulesJson(): JSONObject {
        val dues = scheduler.dues
        return JSONObject().put("schedules", JSONArray(store.current.adhkar.schedules.map { t ->
            t.toJson().put("next", dues.filter { it.time.id == t.id && it.at.isAfter(last?.now ?: ZonedDateTime.now()) }
                .minByOrNull { it.at.toInstant() }?.at?.toInstant()?.toEpochMilli() ?: JSONObject.NULL)
        })).put("next", nextJson())
    }

    /** The next reminder still to come (yesterday's and today's occurrences; `at` is when it fires), or null. */
    fun nextReminder(): AdhkarReminders.Due? = last?.now?.let { AdhkarReminders.next(it, scheduler.dues) }

    private fun nextJson(): Any {
        val d = nextReminder() ?: return JSONObject.NULL
        return JSONObject().put("id", d.time.id).put("category", d.category.id).put("at", d.at.toInstant().toEpochMilli()).put("mode", d.time.mode)
    }

    // ---------------------------------------------------------------- storage

    private fun load(): AdhkarProgress = try {
        AdhkarProgress.fromJson(prefs.getString(KEY, null)?.let(::JSONObject))
    } catch (_: JSONException) {
        AdhkarProgress()
    }

    private fun save(p: AdhkarProgress) {
        _progress.value = p
        prefs.edit { putString(KEY, p.toJson().toString()) }
        // the log of the days the progress holds (today, yesterday): in memory, written to disk now and then
        log.record(p, p.counts.keys + listOfNotNull(p.tasbihDay), ::items, (last?.now ?: ZonedDateTime.now()).toLocalDateTime())
    }

    companion object {
        private const val TAG = "Adhkar"
        private const val KEY = "progress"
        /** Back to the main screen after this long without a touch. */
        const val IDLE_MS = 3 * 60_000L
        /** A reader hidden by a prayer phase for longer than this does not come back. */
        const val RESUME_MS = 5 * 60_000L
        /** A reminder keeps the display in front (the presence policy) this long, then its own timer runs. */
        private const val HOLD_MS = 15_000L
        private const val ADVANCE_MS = 650L

        /** The cards in order; the tasbih is the card after them. */
        val CARDS = AdhkarCategory.entries.toList() // the daily ones first, then remembrance, forgiveness…
    }
}
