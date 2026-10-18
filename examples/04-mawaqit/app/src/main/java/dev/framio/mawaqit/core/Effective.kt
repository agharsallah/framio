package dev.framio.mawaqit.core

import java.time.LocalDate

/**
 * Our settings merged with the mosque's own screen settings. With [SourceSettings.followMosque] and a Mawaqit
 * mosque, what the mosque decided in its back office wins for everything that happens around a prayer (like on
 * its own screens); look and sound (language, theme, voice, volume) always stay ours.
 */
object Effective {

    fun behavior(s: Settings, mosque: Mosque?): Behavior {
        val f = s.flow
        val night = NightMode.of(s.night.mode)
        val c = mosque?.config?.takeIf { s.source.followMosque && s.source.kind == "mawaqit" }
            ?: return Behavior(
                adhanEnabled = s.adhan.enabled,
                adhanSeconds = customAdhanSeconds(s) ?: f.adhanSeconds,
                adhanScreen = f.adhanScreen,
                duaAfterAdhan = f.duaAfterAdhan,
                iqamaCountdown = f.iqamaCountdown,
                iqamaFullScreen = f.iqamaFullScreen,
                iqamaSeconds = f.iqamaSeconds,
                prayerMinutes = f.prayerMinutes,
                blackScreen = f.blackScreen,
                azkar = f.azkar,
                azkarMinutes = f.azkarMinutes,
                jumuaMinutes = f.jumuaMinutes,
                jumuaBlackScreen = f.jumuaBlackScreen,
                jumuaReminder = f.jumuaReminder,
                reminderMinutes = s.adhan.reminderMinutes,
                reminderPrayers = s.adhan.reminderPrayers,
                nightMode = night,
                nightAfterIshaMinutes = s.night.afterIshaMinutes,
                nightBeforeFajrMinutes = s.night.beforeFajrMinutes,
            )
        return Behavior(
            adhanEnabled = c.adhanEnabled,
            adhanSeconds = adhanSeconds(s, c),
            adhanScreen = true,
            duaAfterAdhan = c.duaAfterAdhan,
            iqamaCountdown = true,
            iqamaFullScreen = c.iqamaFullScreenCountdown,
            iqamaSeconds = c.iqamaDisplaySeconds.coerceIn(5, 600),
            prayerMinutes = c.prayerMinutes,
            blackScreen = c.blackScreenWhenPraying,
            azkar = c.duaAfterPrayer,
            azkarMinutes = f.azkarMinutes,
            jumuaMinutes = c.jumuaMinutes,
            jumuaBlackScreen = c.jumuaBlackScreen,
            jumuaReminder = c.jumuaReminder,
            reminderMinutes = s.adhan.reminderMinutes,
            reminderPrayers = s.adhan.reminderPrayers,
            nightMode = night,
            nightAfterIshaMinutes = s.night.afterIshaMinutes,
            nightBeforeFajrMinutes = s.night.beforeFajrMinutes,
        )
    }

    /** The mosque's recording lasts its own duration; otherwise its configured adhan duration. */
    private fun adhanSeconds(s: Settings, c: MosqueConfig): Int =
        customAdhanSeconds(s) ?: if (s.adhan.voice == "mosque" && c.customAthanUrl != null) c.customAthanDurationSeconds ?: c.adhanDurationSeconds
        else c.adhanDurationSeconds

    /**
     * The adhan screen lasts as long as the user's own adhan when [AdhanSettings.voice] is one, mosque followed or
     * not: the voice is always ours, and a duration set for another recording would cut it or leave it hanging.
     * One value for all prayers (Fajr's own file, if different, keeps this length).
     */
    fun customAdhanSeconds(s: Settings): Int? =
        AdhanFiles.find(s.adhan, s.adhan.voice)?.seconds?.takeIf { it > 0 }?.coerceIn(10, 600)

    /** What to play at a prayer's adhan. */
    sealed interface Sound {
        data class Cdn(val id: String) : Sound   // cdn.mawaqit.net/audio/<id>.mp3
        data class Url(val url: String) : Sound  // the mosque's own recording
        data class Custom(val id: String) : Sound // one the user added: files/audio/custom-<id>.<ext>, never downloaded
        data object Beep : Sound
    }

    fun adhanSound(s: Settings, mosque: Mosque?, prayer: PrayerKey): Sound {
        val fajr = prayer == PrayerKey.FAJR
        val chosen = if (fajr && s.adhan.fajrVoice != "same") s.adhan.fajrVoice else s.adhan.voice
        val sound = when (chosen) {
            "beep" -> Sound.Beep
            "mosque" -> {
                val c = mosque?.config
                when {
                    c?.customAthanUrl != null -> Sound.Url(c.customAthanUrl)
                    c?.adhanVoice != null -> Sound.Cdn(c.adhanVoice)
                    else -> Sound.Beep
                }
            }
            else -> if (chosen.startsWith("custom:")) {
                // Settings.fromJson already drops a reference to a deleted adhan; the default voice if one slips through
                AdhanFiles.find(s.adhan, chosen)?.let { Sound.Custom(it.id) } ?: Sound.Cdn(AdhanSettings().voice)
            } else Sound.Cdn(chosen)
        }
        // Every CDN adhan has a calmer Fajr version (with "as-salatu khayrun min an-nawm")
        return if (fajr && sound is Sound.Cdn && !sound.id.endsWith("-fajr") && sound.id in AdhanSettings.VOICES) Sound.Cdn("${sound.id}-fajr")
        else sound
    }

    fun clock24h(s: Settings, mosque: Mosque?): Boolean =
        mosque?.config?.takeIf { s.source.followMosque && s.source.kind == "mawaqit" }?.clock24h ?: s.display.clock24h

    /**
     * Our own flash ticker wins over the mosque's; both only between their dates. [DisplaySettings.showFlash] off
     * hides either: a moving band is distracting in a quiet room, and the mosque's message can't be cleared from here.
     */
    fun flash(s: Settings, mosque: Mosque?, today: LocalDate): Flash? {
        if (!s.display.showFlash) return null
        val own = s.flash
        if (own.text.isNotBlank() && (own.end.isEmpty() || runCatching { LocalDate.parse(own.end) }.getOrNull()?.let { !today.isAfter(it) } != false)) {
            return Flash(own.text, own.color, own.rtl)
        }
        val m = mosque?.flash ?: return null
        if (m.start != null && today.isBefore(m.start)) return null
        if (m.end != null && today.isAfter(m.end)) return null
        return m
    }

    /** The slides shown in turn over the clock area: our messages, then the mosque's announcements. */
    fun announcements(s: Settings, mosque: Mosque?, now: java.time.ZonedDateTime): List<Announcement> {
        if (!s.display.showAnnouncements) return emptyList()
        val today = now.toLocalDate()
        val own = s.messages.filter { m ->
            m.enabled && (m.title.isNotBlank() || m.text.isNotBlank()) &&
                (m.start.isEmpty() || runCatching { !today.isBefore(LocalDate.parse(m.start)) }.getOrDefault(true)) &&
                (m.end.isEmpty() || runCatching { !today.isAfter(LocalDate.parse(m.end)) }.getOrDefault(true))
        }.map { Announcement(it.id, it.title.ifBlank { null }, it.text.ifBlank { null }, seconds = it.seconds) }
        val theirs = mosque?.announcements.orEmpty().filter { a ->
            (a.start == null || !now.isBefore(a.start)) && (a.end == null || now.isBefore(a.end)) &&
                (a.title != null || a.content != null || a.imageUrl != null)
        }
        return own + theirs
    }
}
