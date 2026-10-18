package dev.framio.mawaqit.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.framio.mawaqit.core.Presence
import dev.framio.mawaqit.core.Presence.Reason
import dev.framio.mawaqit.core.PresenceSettings
import dev.framio.mawaqit.system.Device
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * The presence policy on the frame ([PresenceSettings]; the rules are [Presence]'s): every second from the engine,
 * whether the display goes away (backlight off, Frameo's photos or the home screen) or comes back, and why.
 * It replaces photo-frame mode, which was the "photos" case of it. Every call is on the main thread.
 *
 * Away means: [asleep] (the "off" case: the display stays in front, black, backlight off), or another app in front,
 * whoever put it there. Coming back: backlight on, the display brought to the front. Timers run on elapsedRealtime,
 * so an NTP correction can't fire them; the periodic adhkar's hours are wall-clock.
 */
class PresenceController(private val context: Context, private val host: Host) {

    interface Host {
        fun displayVisible(): Boolean
        /** Starts the activity to the front (it isn't visible yet). */
        fun bringDisplay()
        /** The periodic adhkar: the adhkar display, or a dhikr slide when that page is off. */
        fun showAdhkar()
        /** On going away: the resting page, so a wake doesn't land on whatever was left up. */
        fun restPage()
        /** Republish the state (the screen changed). */
        fun changed()
    }

    /** "off": drawn black with the backlight off, until a touch, a knock or a reason to come back. */
    @Volatile var asleep = false
        private set
    @Volatile var settings = PresenceSettings()
        private set

    private var reason: Reason = Reason.TOUCH
    /** Came back by itself and untouched since: [PresenceSettings.returnAfterMinutes] applies, not the idle time. */
    private var auto = false
    private var lastTouch = clock()
    private var lastHeld = 0L
    private var lastHeldReason: Reason? = null
    private var periodicUntil = 0L
    @Volatile private var nextPeriodic: ZonedDateTime? = null
    private var periodicKey: List<Any>? = null
    private var wasAway = false
    /** When we last asked for the display (so its onResume isn't taken for a hand) / sent it away (no double send). */
    private var bringing = Long.MIN_VALUE / 2
    private var leaving = Long.MIN_VALUE / 2
    private var frameo: Boolean? = null
    private var frameoAt = 0L
    private val random = Random(System.nanoTime())

    private fun clock() = SystemClock.elapsedRealtime()

    /** Touch, key or knock: here by hand. Lights a sleeping screen. */
    fun touch() {
        lastTouch = clock()
        auto = false
        reason = Reason.TOUCH
        if (asleep) {
            asleep = false
            Log.i(TAG, "awake: touch")
            host.changed()
        }
    }

    /** The activity came to the front: by hand, unless we asked for it a moment ago. */
    fun onShown() {
        if (clock() - bringing > SETTLE_MS) touch()
        else if (reason == Reason.ADHKAR) host.showAdhkar() // the resting page was just put back by the loop
    }

    /**
     * A live message, an adhkar reminder, the panel: back now when the policy is on and lets [r] wake it.
     * Returns whether the display is (being) shown for it; false with the policy off (the caller does as before).
     * [force]: whatever [PresenceSettings.messages] / [PresenceSettings.reminders] say (a schedule's own "wake").
     */
    fun trigger(r: Reason, force: Boolean = false): Boolean {
        val p = settings
        if (!p.enabled) return false
        if (!force && !Presence.brings(p, r)) return !away()
        come(r)
        held(r)
        return true
    }

    /** The adhkar reminder hook (AdhkarController.presence): whether the policy lit the display for it. */
    fun reminder(): Boolean = trigger(Reason.REMINDER)

    /** presence.away from the panel: away now, as if the idle time were over. */
    fun goAwayNow() {
        if (settings.enabled && !away()) goAway(settings, "remote")
    }

    fun tick(st: DisplayState, adhkarHolds: Boolean) {
        val p = st.settings.presence
        if (p.enabled && !settings.enabled) touch() // just turned on: the idle time starts now, not at the last touch
        settings = p
        if (!p.enabled || !st.synced) {
            if (asleep) {
                asleep = false
                host.changed()
            }
            wasAway = false
            nextPeriodic = null
            return
        }
        val now = clock()
        val untilAdhan = st.next?.let { Duration.between(st.now, it.adhan).toMillis() }
        val holder = Presence.holder(p, st.phase.kind, untilAdhan, st.overlay != null, adhkarHolds, now < periodicUntil)
        val away = away()
        if (holder != null && (!away || Presence.brings(p, holder))) {
            if (away) come(holder) else reason = holder
            held(holder)
            return
        }
        if (away) {
            val key = listOf(p.periodic, p.everyMinutes, p.minMinutes, p.maxMinutes, p.from, p.to)
            if (!wasAway || key != periodicKey) {
                wasAway = true
                periodicKey = key
                nextPeriodic = Presence.nextPeriodic(p, st.now, random)
            }
            val due = nextPeriodic ?: return
            if (st.now.isBefore(due)) return
            if (Presence.periodicAllowed(p, st.now, st.phase.kind)) {
                periodicUntil = now + p.showSeconds * 1000L
                come(Reason.ADHKAR)
                held(Reason.ADHKAR)
                host.showAdhkar()
            } else {
                nextPeriodic = Presence.nextPeriodic(p, st.now, random)
                Log.i(TAG, "periodic adhkar at night or out of hours: next ${nextPeriodic?.toLocalTime()}")
            }
            return
        }
        wasAway = false
        if (now - leaving > SETTLE_MS && Presence.goesAway(p, now, lastTouch, lastHeld, auto, lastHeldReason)) {
            goAway(p, if (auto) "after ${lastHeldReason?.id}" else "idle")
        }
    }

    private fun away() = asleep || !host.displayVisible()

    private fun held(r: Reason) {
        lastHeld = clock()
        lastHeldReason = r
    }

    /**
     * Lit and in front. Called every second while a holder lasts and the display is away: only a change is acted on,
     * and the activity is asked for at most once a minute (another app opened over a prayer screen isn't fought).
     */
    private fun come(r: Reason) {
        val woke = asleep
        reason = r
        auto = true
        asleep = false
        val bring = !host.displayVisible() && clock() - bringing > BRING_EVERY_MS
        if (bring) {
            bringing = clock()
            host.bringDisplay()
        }
        if (woke || bring) {
            Log.i(TAG, "back: ${r.id}")
            host.changed()
        }
    }

    private fun goAway(p: PresenceSettings, why: String) {
        leaving = clock()
        auto = false
        periodicUntil = 0
        host.restPage()
        val mode = when (p.awayMode) {
            PresenceSettings.Away.PHOTOS -> if (frameoInstalled() && Device.openPhotos(context)) "photos" else null
            PresenceSettings.Away.HOME -> if (Device.openHome(context)) "home" else null
            PresenceSettings.Away.OFF -> null
        }
        if (mode == null) asleep = true // also when Frameo or the home screen can't be opened
        Log.i(TAG, "away ($why): ${mode ?: "screen off"}")
        host.changed()
    }

    /** The package manager isn't asked every second. */
    private fun frameoInstalled(): Boolean {
        val now = clock()
        val known = frameo
        if (known != null && now - frameoAt < 10 * 60_000L) return known
        frameoAt = now
        return Device.frameoInstalled(context).also { frameo = it }
    }

    /**
     * For the state: awake or not and why, the away mode, when it goes away if nothing happens ([awayAt]) and what
     * brings it back next ([nextWake]: before an adhan, an adhkar reminder, the periodic adhkar).
     */
    fun stateJson(st: DisplayState, nextReminder: ZonedDateTime?): JSONObject {
        val p = st.settings.presence
        val wall = System.currentTimeMillis()
        val elapsed = clock()
        val away = p.enabled && away()
        val o = JSONObject().put("enabled", p.enabled).put("awake", !away).put("awayMode", p.away)
            .put("reason", if (!p.enabled) JSONObject.NULL else if (away) "away" else reason.id)
            .put("asleep", asleep)
        val awayAt: Long? = when {
            !p.enabled || away -> null
            elapsed - lastHeld < 2_000 -> null // held right now: not before that is over
            auto -> lastHeld + if (lastHeldReason == Reason.ADHKAR) 0 else p.returnAfterMinutes * 60_000L
            p.idleMinutes > 0 -> maxOf(lastTouch, lastHeld) + p.idleMinutes * 60_000L
            else -> null
        }
        o.put("awayAt", awayAt?.let { wall + (it - elapsed).coerceAtLeast(0) } ?: JSONObject.NULL)
        if (!p.enabled) return o.put("nextWake", JSONObject.NULL)
        val wakes = ArrayList<Pair<ZonedDateTime, String>>()
        st.next?.let { n ->
            val m = Presence.beforeAdhanMinutes(p, st.behavior, n.key)
            val at = n.adhan.minusMinutes(m.toLong())
            if (at.isAfter(st.now)) wakes += at to if (m > 0) Reason.BEFORE_ADHAN.id else Reason.PRAYER.id
        }
        if (p.reminders) nextReminder?.takeIf { it.isAfter(st.now) }?.let { wakes += it to Reason.REMINDER.id }
        if (away) nextPeriodic?.let { wakes += it to Reason.ADHKAR.id }
        val first = wakes.minByOrNull { it.first.toInstant() }
        return o.put("nextWake", first?.let { JSONObject().put("at", it.first.toInstant().toEpochMilli()).put("reason", it.second) } ?: JSONObject.NULL)
    }

    private companion object {
        const val TAG = "Presence"
        const val SETTLE_MS = 5_000L
        const val BRING_EVERY_MS = 60_000L
    }
}
