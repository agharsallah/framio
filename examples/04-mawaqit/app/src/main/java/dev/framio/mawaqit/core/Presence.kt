package dev.framio.mawaqit.core

import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.random.Random

/**
 * When the prayer display is on screen. With [enabled], it goes away [idleMinutes] after the last touch or key:
 * [away] "off" switches the backlight off over a black screen (as the night's black mode; a touch or a knock wakes
 * it), "photos" hands over to Frameo's slideshow (what photo-frame mode did), "home" to the home launcher.
 *
 * It comes back by itself (see [Presence.holder]) for every screen around a prayer (the "adhan soon" reminder of
 * [AdhanSettings.reminderMinutes], the adhan … azkar, Jumu'a), [leadMinutes] before each adhan, for a live
 * message ([messages]), an adhkar reminder ([reminders]), and, with [periodic], every [everyMinutes] ("every") or
 * at random between [minMinutes] and [maxMinutes] ("random") for [showSeconds] of adhkar, between [from] and [to]
 * and never at night. Once that is over it goes away again after [returnAfterMinutes] (at once after the adhkar),
 * unless it was touched meanwhile: then [idleMinutes] counts again.
 *
 * Replaces the photo-frame mode's `frame` settings, read once into these ([fromFrame]).
 */
data class PresenceSettings(
    val enabled: Boolean = false,
    /** "off", "photos" or "home". */
    val away: String = "off",
    /** After the last touch or key; 0 = only what came back by itself goes away again. */
    val idleMinutes: Int = 10,
    /** After what brought the display back is over (the azkar, a message, a reminder card's first seconds). */
    val returnAfterMinutes: Int = 2,
    /** Also come back this many minutes before every adhan, silently (the "adhan soon" reminder has its own time). */
    val leadMinutes: Int = 0,
    val messages: Boolean = true,
    val reminders: Boolean = true,
    /** "off", "every" or "random": adhkar now and then while the display is away. */
    val periodic: String = "off",
    val everyMinutes: Int = 60,
    val minMinutes: Int = 30,
    val maxMinutes: Int = 90,
    /** How long the adhkar stay, 10 s – 30 min. */
    val showSeconds: Int = 60,
    /** The periodic adhkar's hours, "HH:MM"; [to] before [from] runs over midnight, equal = all day (night excepted). */
    val from: String = "08:00",
    val to: String = "21:00",
) {
    val awayMode: Away get() = Away.of(away)

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("away", away).put("idleMinutes", idleMinutes).put("returnAfterMinutes", returnAfterMinutes)
        .put("leadMinutes", leadMinutes).put("messages", messages).put("reminders", reminders).put("periodic", periodic)
        .put("everyMinutes", everyMinutes).put("minMinutes", minMinutes).put("maxMinutes", maxMinutes)
        .put("showSeconds", showSeconds).put("from", from).put("to", to)

    enum class Away(val id: String) {
        OFF("off"), PHOTOS("photos"), HOME("home");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    companion object {
        private val D = PresenceSettings()
        val PERIODIC = listOf("off", "every", "random")

        fun fromJson(j: JSONObject?): PresenceSettings {
            if (j == null) return D
            val min = j.optInt("minMinutes", D.minMinutes).coerceIn(5, 720)
            return PresenceSettings(
                enabled = j.optBoolean("enabled", D.enabled),
                away = Away.of(j.optString("away", D.away)).id,
                idleMinutes = j.optInt("idleMinutes", D.idleMinutes).coerceIn(0, 240),
                returnAfterMinutes = j.optInt("returnAfterMinutes", D.returnAfterMinutes).coerceIn(0, 60),
                leadMinutes = j.optInt("leadMinutes", D.leadMinutes).coerceIn(0, 60),
                messages = j.optBoolean("messages", D.messages),
                reminders = j.optBoolean("reminders", D.reminders),
                periodic = j.optString("periodic", D.periodic).takeIf { it in PERIODIC } ?: D.periodic,
                everyMinutes = j.optInt("everyMinutes", D.everyMinutes).coerceIn(5, 720),
                minMinutes = min,
                maxMinutes = j.optInt("maxMinutes", D.maxMinutes).coerceIn(min, 720),
                showSeconds = j.optInt("showSeconds", D.showSeconds).coerceIn(10, 1800),
                from = hhmm(j.optString("from"), D.from),
                to = hhmm(j.optString("to"), D.to),
            )
        }

        /**
         * Settings saved before this policy had photo-frame mode, `frame` {enabled, leadMinutes, returnAfterMinutes,
         * idleMinutes}: when it was on, the same behaviour (away to the photos, same minutes); off, the defaults.
         */
        fun fromFrame(frame: JSONObject?): PresenceSettings {
            if (frame == null || !frame.optBoolean("enabled", false)) return D
            return D.copy(
                enabled = true,
                away = Away.PHOTOS.id,
                leadMinutes = frame.optInt("leadMinutes", 5).coerceIn(0, 60),
                returnAfterMinutes = frame.optInt("returnAfterMinutes", 2).coerceIn(0, 60),
                idleMinutes = frame.optInt("idleMinutes", 15).coerceIn(0, 240),
            )
        }

        /**
         * A settings.patch from an older panel or script may still say `frame`: it is turned into the same
         * `presence` keys (enabled → away "photos"), so it isn't silently dropped.
         */
        fun upgradePatch(patch: JSONObject): JSONObject {
            val frame = patch.optJSONObject("frame") ?: return patch
            val out = JSONObject(patch.toString())
            out.remove("frame")
            val presence = out.optJSONObject("presence") ?: JSONObject().also { out.put("presence", it) }
            if (frame.has("enabled") && !presence.has("enabled")) {
                val on = frame.optBoolean("enabled")
                presence.put("enabled", on)
                if (on && !presence.has("away")) presence.put("away", Away.PHOTOS.id)
            }
            for (k in listOf("leadMinutes", "returnAfterMinutes", "idleMinutes")) {
                if (frame.has(k) && !presence.has(k)) presence.put(k, frame.get(k))
            }
            return out
        }

        private fun hhmm(s: String?, default: String): String =
            runCatching { LocalTime.parse(s?.trim()).let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) } }.getOrNull() ?: default
    }
}

/**
 * The pure part of the presence policy ([PresenceSettings]): what holds the display, when it goes away, when the
 * next periodic adhkar are due. Times are in milliseconds on one clock (the engine passes elapsedRealtime).
 */
object Presence {

    /** Why the display is on screen. */
    enum class Reason(val id: String) {
        TOUCH("touch"),
        /** The adhan, the dua, the iqama countdown, the iqama, the prayer, the azkar, Jumu'a. */
        PRAYER("adhan"),
        /** The "adhan soon" reminder phase, or [PresenceSettings.leadMinutes] before the adhan. */
        BEFORE_ADHAN("before_adhan"),
        /** The night clock (away "photos" / "home" only: as photo-frame mode, the night shows the dim clock). */
        NIGHT("night"),
        MESSAGE("message"),
        /** An adhkar reminder fired, or the reader is open. */
        REMINDER("reminder"),
        /** The periodic adhkar. */
        ADHKAR("adhkar"),
        /** A preview, a dhikr slide, adhkar.open… from the panel. */
        REMOTE("remote"),
    }

    /**
     * What keeps the display on screen right now (null: nothing, so the idle rules apply), most important first.
     * [untilAdhanMs]: to the next adhan; [message]: a live message is up; [adhkarHolds]: a reminder just fired or the
     * reader is open; [periodic]: the periodic adhkar are on.
     */
    fun holder(p: PresenceSettings, phase: PhaseKind, untilAdhanMs: Long?, message: Boolean, adhkarHolds: Boolean, periodic: Boolean): Reason? = when {
        phase == PhaseKind.REMINDER -> Reason.BEFORE_ADHAN
        phase == PhaseKind.NIGHT -> if (p.awayMode == PresenceSettings.Away.OFF) null else Reason.NIGHT
        phase != PhaseKind.NORMAL -> Reason.PRAYER
        untilAdhanMs != null && untilAdhanMs >= 0 && untilAdhanMs < p.leadMinutes * 60_000L -> Reason.BEFORE_ADHAN
        message -> Reason.MESSAGE
        adhkarHolds -> Reason.REMINDER
        periodic -> Reason.ADHKAR
        else -> null
    }

    /**
     * Whether [r] brings an away display back, or only keeps one that is on screen ([PresenceSettings.messages] and
     * [PresenceSettings.reminders] off: what is shown while someone looks stays, but nothing wakes for it).
     */
    fun brings(p: PresenceSettings, r: Reason): Boolean = when (r) {
        Reason.MESSAGE -> p.messages
        Reason.REMINDER -> p.reminders
        else -> true
    }

    /**
     * Whether the display goes away now. [auto]: it came back by itself and nobody touched it since: away
     * [PresenceSettings.returnAfterMinutes] after [lastHeldMs] (at once after the periodic adhkar, [lastReason]
     * ADHKAR). Otherwise [PresenceSettings.idleMinutes] after the later of the last touch and the last holder.
     */
    fun goesAway(p: PresenceSettings, nowMs: Long, lastTouchMs: Long, lastHeldMs: Long, auto: Boolean, lastReason: Reason?): Boolean {
        if (auto) {
            val grace = if (lastReason == Reason.ADHKAR) 0L else p.returnAfterMinutes * 60_000L
            return nowMs - lastHeldMs >= grace
        }
        if (p.idleMinutes == 0) return false
        return nowMs - maxOf(lastTouchMs, lastHeldMs) >= p.idleMinutes * 60_000L
    }

    /**
     * When the next periodic adhkar are due, counted from [after] (the moment the display went away, or the last
     * ones ended): [PresenceSettings.everyMinutes] later, or a random [minMinutes, maxMinutes] drawn from
     * [random]; moved to the next [PresenceSettings.from] when that falls outside the hours. Null when off.
     */
    fun nextPeriodic(p: PresenceSettings, after: ZonedDateTime, random: Random): ZonedDateTime? {
        val minutes = when (p.periodic) {
            "every" -> p.everyMinutes
            "random" -> random.nextInt(p.minMinutes, p.maxMinutes + 1)
            else -> return null
        }
        return intoHours(p, after.plusMinutes(minutes.toLong()))
    }

    /** [t] when it is within the periodic hours, else the next start of them. */
    fun intoHours(p: PresenceSettings, t: ZonedDateTime): ZonedDateTime {
        if (inHours(p, t.toLocalTime())) return t
        val from = LocalTime.parse(p.from)
        val today = t.with(from).withSecond(0).withNano(0)
        return if (today.isAfter(t)) today else today.plusDays(1).with(from)
    }

    /** Within [PresenceSettings.from, to); across midnight when to < from; equal = all day. */
    fun inHours(p: PresenceSettings, t: LocalTime): Boolean {
        val from = LocalTime.parse(p.from)
        val to = LocalTime.parse(p.to)
        return when {
            from == to -> true
            from.isBefore(to) -> !t.isBefore(from) && t.isBefore(to)
            else -> !t.isBefore(from) || t.isBefore(to)
        }
    }

    /**
     * Whether periodic adhkar may come now: within the hours and not at night (the night's own screen, dim or black,
     * stays undisturbed).
     */
    fun periodicAllowed(p: PresenceSettings, now: ZonedDateTime, phase: PhaseKind): Boolean =
        p.periodic != "off" && phase != PhaseKind.NIGHT && inHours(p, now.toLocalTime())

    /**
     * How many minutes before [prayer]'s adhan the display comes back: the "adhan soon" reminder when that prayer
     * has one, or [PresenceSettings.leadMinutes], whichever is earlier. Jumu'a counts as Dhuhr.
     */
    fun beforeAdhanMinutes(p: PresenceSettings, b: Behavior, prayer: PrayerKey): Int {
        val i = if (prayer == PrayerKey.JUMUA) PrayerKey.DHUHR.five else prayer.five
        val reminder = if (b.reminderMinutes > 0 && b.reminderPrayers.getOrElse(i) { true }) b.reminderMinutes else 0
        return maxOf(reminder, p.leadMinutes)
    }
}
