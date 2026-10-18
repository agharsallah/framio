package dev.framio.mawaqit.data

import android.util.Log
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarProgress
import dev.framio.mawaqit.core.AdhkarReminders
import dev.framio.mawaqit.core.AdhkarSchedule
import dev.framio.mawaqit.core.AdhkarSettings
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.log
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.ui.Format
import dev.framio.mawaqit.ui.isFullScreen
import java.time.LocalDate

/**
 * The frame's side of the reminder schedules ([AdhkarReminders]): every second, which occurrence fires and what it
 * does, and which reminder card the main screen shows. Runs on the main thread from [AdhkarController.tick].
 *
 * Firing an occurrence, once ("announced" in the progress):
 * - "remind": the soft chime when the display shows (or the presence policy brings it forward), the card;
 * - "open": the reader opens on the collection's first dhikr not yet said, the display comes forward;
 * - at night (dim) neither chime nor opening: the night screen's quiet line. With the backlight off nothing lights,
 *   unless the schedule asks to wake the screen;
 * - and the phone notification, when on.
 * Never while an adhan … azkar screen, a message or the reader itself is up: it waits, at most [AdhkarReminders.GRACE_MIN].
 */
internal class AdhkarScheduler(private val host: Host, private val push: AdhkarPush) {

    interface Host {
        val progress: AdhkarProgress
        fun save(p: AdhkarProgress)
        /** Whether [c]'s session of [day] is complete (after the prayer: the last prayer's). */
        fun complete(c: AdhkarCategory, day: LocalDate): Boolean
        /** Adhkar said in full and in all, for the card. */
        fun progressOf(c: AdhkarCategory, day: LocalDate): Pair<Int, Int>
        /** The reader, the tasbih or the log is open. */
        fun viewOpen(): Boolean
        fun displayVisible(): Boolean
        fun chime()
        fun hold()
        fun openReader(c: AdhkarCategory)
        fun wake()
        /**
         * Asks the display's presence policy (engine/PresenceController) to light the screen and bring the display
         * forward for this reminder; true when it does (it wakes synchronously).
         */
        fun presence(c: AdhkarCategory, opens: Boolean): Boolean
    }

    private var key: List<Any?>? = null
    var dues: List<AdhkarReminders.Due> = emptyList()
        private set

    /** The card to show (null: none), after firing what is due. [windows]: yesterday's and today's own times. */
    fun tick(st: DisplayState, s: AdhkarSettings, windows: List<AdhkarSchedule.Window>, previous: AdhkarReminder?): AdhkarReminder? {
        val times = st.source.times ?: return null
        if (!s.enabled) return null
        val today = st.now.withZoneSameInstant(times.zone).toLocalDate()
        val k = listOf(today, System.identityHashCode(times), st.behavior, s.schedules)
        if (k != key) {
            dues = AdhkarReminders.occurrences(today.minusDays(1), s.schedules, times, st.behavior, windows) +
                AdhkarReminders.occurrences(today, s.schedules, times, st.behavior, windows)
            key = k
        }
        val p = host.progress
        fun dismissed(d: AdhkarReminders.Due) = AdhkarProgress.reminderKey(d.day, d.category) in p.dismissed
        val blocked = !st.synced || st.overlay != null || host.viewOpen() || (isFullScreen(st) && st.phase.kind != PhaseKind.NIGHT)
        for (d in dues) {
            val settled = d.key in p.announced || dismissed(d) || host.complete(d.category, d.day)
            when (AdhkarReminders.step(d, st.now, blocked, settled)) {
                AdhkarReminders.Step.FIRE -> fire(st, s, d)
                // one whose card time is over too can't come back: nothing to remember (yesterday's, mostly)
                AdhkarReminders.Step.SKIP -> if (st.now.isBefore(d.until)) {
                    host.save(host.progress.withAnnounced(d.key))
                    Log.i(TAG, "skipped ${d.key} (due ${d.at.toLocalTime()}, nothing fired)")
                }
                else -> Unit
            }
        }
        val shown = AdhkarReminders.showing(st.now, dues) { !dismissed(it) && !host.complete(it.category, it.day) } ?: return null
        val (done, total) = host.progressOf(shown.category, shown.day)
        val since = if (previous?.category == shown.category && previous.day == shown.day) previous.since else System.currentTimeMillis()
        return AdhkarReminder(shown.category, shown.day, done, total, shown.until, since)
    }

    private fun fire(st: DisplayState, s: AdhkarSettings, d: AdhkarReminders.Due) {
        val t = d.time
        val night = st.phase.kind == PhaseKind.NIGHT && !t.wake
        val lit = !night && host.presence(d.category, t.opens)
        var dark = st.screen.black && !lit
        if (dark && t.wake && st.screen.mode == "auto") {
            host.wake()
            dark = false
        }
        val what = when {
            dark -> "dark: card only"
            night -> "night: quiet line"
            t.opens -> {
                host.openReader(d.category)
                "opened"
            }
            else -> {
                if (s.chime && (host.displayVisible() || lit)) host.chime()
                "reminded"
            }
        }
        if (!dark && !night) host.hold()
        host.save(host.progress.withAnnounced(d.key))
        val pushed = t.push && pushReminder(st, s, d)
        Log.i(TAG, "fired ${d.key} at ${st.now.toLocalTime().withNano(0)} (due ${d.at.toLocalTime()}): $what${if (pushed) ", phone" else ""}")
    }

    private fun pushReminder(st: DisplayState, s: AdhkarSettings, d: AdhkarReminders.Due): Boolean {
        val str = st.strings
        val (done, total) = host.progressOf(d.category, d.day)
        val text = str.log("push_text").replace("{c}", str[d.category.key]).replace("{d}", done.toString())
            .replace("{n}", total.toString()).replace("{u}", Format.time(d.until, st.clock24h))
        return push.send(s, st.now.toLocalDate(), str[d.category.key], text)
    }

    /** adhkar.push.test: one message now, whether or not the phone reminders are on. */
    fun pushTest(s: AdhkarSettings, str: Strings, today: LocalDate): Boolean =
        push.send(s, today, str["adhkar"], str.log("push_test"), test = true)

    private companion object {
        const val TAG = "AdhkarReminders"
    }
}
