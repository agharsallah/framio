package dev.framio.mawaqit.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.framio.mawaqit.MawaqitActivity
import dev.framio.mawaqit.audio.AdhanPlayer
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarLoop
import dev.framio.mawaqit.core.Announcement
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.Behavior
import dev.framio.mawaqit.core.Effective
import dev.framio.mawaqit.core.Flash
import dev.framio.mawaqit.core.Hijri
import dev.framio.mawaqit.core.NightMode
import dev.framio.mawaqit.core.Phase
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.Presence
import dev.framio.mawaqit.core.PrayerDay
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.SecondaryEntry
import dev.framio.mawaqit.core.Settings
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.Timeline
import dev.framio.mawaqit.data.AdhkarLoopController
import dev.framio.mawaqit.data.FramePage
import dev.framio.mawaqit.data.Hadith
import dev.framio.mawaqit.data.Repository
import dev.framio.mawaqit.data.SecondaryMosques
import dev.framio.mawaqit.data.SettingsStore
import dev.framio.mawaqit.data.SourceState
import dev.framio.mawaqit.data.Weather
import dev.framio.mawaqit.system.Device
import dev.framio.mawaqit.system.KnockSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A hadith or an announcement shown over the clock area for a while. */
sealed interface Slide {
    data class OfHadith(val hadith: Hadith) : Slide
    data class OfAnnouncement(val announcement: Announcement) : Slide
    /** The secondary mosques' times, side by side. */
    data object OfMosques : Slide
    /** Now and then a short dhikr from the adhkar display's collections (see [AdhkarLoopController.slideDhikr]). */
    data class OfDhikr(val dhikr: Dhikr, val category: AdhkarCategory) : Slide
}

/** A message pushed from the remote panel, full screen until [until]. */
data class Overlay(val title: String?, val text: String, val until: Long)

/** How the screen is lit. [brightness] is the window brightness (0–1), [black] means draw nothing. */
data class ScreenState(val mode: String = "auto", val state: String = "on", val brightness: Float = 1f, val black: Boolean = false)

/** Everything the display draws, republished every second. */
data class DisplayState(
    val now: ZonedDateTime,
    val synced: Boolean,
    val settings: Settings,
    val strings: Strings,
    val source: SourceState,
    val behavior: Behavior,
    val today: PrayerDay?,
    val tomorrow: PrayerDay?,
    val next: PrayerTime?,
    val phase: Phase,
    val hijri: String?,
    val ramadan: Boolean,
    val clock24h: Boolean,
    val flash: Flash?,
    val slide: Slide?,
    val overlay: Overlay?,
    val weather: Weather?,
    val screen: ScreenState,
    val audioPlaying: Boolean,
    /** What is playing, its live volume and mute (the adhan controls). */
    val audio: AdhanPlayer.Playing? = null,
    val panelUrl: String?,
    /** The secondary mosques, display only (see [SecondaryMosques]). */
    val secondary: List<SecondaryEntry> = emptyList(),
)

/**
 * The frame's brain. Runs in the foreground service (so it keeps time behind Frameo and with the screen off) and
 * publishes [state]; the activity only draws it.
 */
class Engine(
    private val context: Context,
    private val store: SettingsStore,
    private val repo: Repository,
    val player: AdhanPlayer,
    private val scope: CoroutineScope,
    val secondary: SecondaryMosques,
) {
    @Volatile var displayVisible = false
        private set
    private var wakeUntil = 0L
    private var screenMode = "auto"
    private var preview: Pair<PhaseKind, Long>? = null
    private var overlay: Overlay? = null
    private var lastPhase: Phase? = null
    private var firedAdhan: Instant? = null
    /** The adhan whose screen was skipped: its ADHAN phase gives way to what follows it. */
    private var skippedAdhan: Instant? = null
    private var backlightIsOff = false
    private val backlight = Mutex()

    // Slides: every slideEveryMinutes, the next of hadith / announcements for its seconds
    private var slideIndex = 0
    private var slideStart = 0L
    private var slideShown: Slide? = null
    /** The dhikr-or-not draw of the slot starting at [slideStart] (made once per slot). */
    private var dhikrDrawnFor = -1L
    private var dhikrDrawn = false
    private val slideRandom = kotlin.random.Random(System.nanoTime())

    private val knock = KnockSensor(context) {
        presence.touch()
        wake("knock")
    }

    /** When the display is on screen: away after a while untouched, back by itself (replaces photo-frame mode). */
    val presence: PresenceController = PresenceController(context, object : PresenceController.Host {
        override fun displayVisible() = displayVisible
        override fun bringDisplay() = showDisplay()
        override fun showAdhkar() = periodicAdhkar()
        override fun restPage() = loop.show(loopHome(), "presence away")
        override fun changed() = post()
    })

    /** The adhkar section: its reader, progress and reminders, ticked with the display. */
    val adhkar: dev.framio.mawaqit.data.AdhkarController = dev.framio.mawaqit.data.AdhkarController(context, store, player, scope, { displayVisible }, { remoteShow() },
        { presence.trigger(Presence.Reason.REMINDER, force = true); wake("adhkar") }, { _, _ -> presence.reminder() })

    /** The adhkar display (the second page) and the dhikr slides. */
    val loop: AdhkarLoopController = AdhkarLoopController(store, adhkar)

    // Last: compute() reads the fields above
    private val _state = MutableStateFlow(compute(System.currentTimeMillis()))
    val state: StateFlow<DisplayState> = _state.asStateFlow()

    fun start() {
        // a backlight left off by a previous run (killed, reinstalled while dark) comes back on
        scope.launch { backlight.withLock { if (!backlightIsOff) Device.backlightOn() } }
        scope.launch {
            while (true) {
                tick()
                // Wake right after each second boundary: the clock's seconds then change in step
                delay(1000L - System.currentTimeMillis() % 1000L + 5L)
            }
        }
        // Prefetch the adhans this configuration will play, whenever it changes
        scope.launch {
            store.settings.collect { prefetch(it) }
        }
        scope.launch { repo.state.drop(1).collect { prefetch(store.current) } }
    }

    /** Touch, key or knock: restarts the presence idle time, lights a switched-off or black night screen. */
    fun userActivity() {
        presence.touch()
        loop.touch()
        wake("touch")
    }

    fun setDisplayVisible(visible: Boolean) {
        val shown = visible && !displayVisible
        displayVisible = visible
        if (shown) {
            loop.onShown() // back from the photos or another app: the resting page
            presence.onShown()
        }
        post()
    }

    fun preview(kind: PhaseKind, seconds: Int) {
        preview = kind to System.currentTimeMillis() + seconds * 1000L
        remoteShow()
        post()
    }

    /** A dhikr slide on the main screen right now (the panel's preview), for the usual slide seconds. Main thread. */
    fun showDhikrSlide(): Boolean {
        val d = loop.slideDhikr() ?: return false
        loop.show(dev.framio.mawaqit.data.FramePage.MAIN, "slide preview")
        slideShown = Slide.OfDhikr(d.first, d.second)
        slideStart = System.currentTimeMillis()
        remoteShow()
        post()
        return true
    }

    fun showMessage(title: String?, text: String, seconds: Int) {
        overlay = Overlay(title, text, System.currentTimeMillis() + seconds * 1000L)
        wake("message")
        // with the presence policy on, presence.messages decides whether it comes back for it
        if (!presence.trigger(Presence.Reason.MESSAGE) && !store.current.presence.enabled) showDisplay()
        post()
    }

    fun dismissOverlay() {
        overlay = null
        preview = null
        post()
    }

    fun setScreenMode(mode: String) {
        screenMode = mode
        post()
    }

    fun testAdhan(prayer: PrayerKey) {
        val s = store.current
        player.play(Effective.adhanSound(s, repo.state.value.mosque, prayer), s.adhan.volume, "test:${prayer.id}")
    }

    /**
     * "Skip" on the adhan: the audio stops and the adhan screen gives way to what follows it (dua, iqama countdown…).
     * A previewed adhan screen just closes. True when there was something to skip.
     */
    fun skipAdhan(): Boolean {
        val st = _state.value
        val playing = player.playing.value?.what?.let { it.startsWith("adhan:") || it.startsWith("test:") } == true
        if (playing) player.stop()
        val onScreen = st.phase.kind == PhaseKind.ADHAN
        if (onScreen) {
            if (preview?.first == PhaseKind.ADHAN) preview = null else skippedAdhan = st.phase.prayer?.adhan?.toInstant()
        }
        Log.i(TAG, "adhan skipped (audio $playing, screen $onScreen)")
        post()
        return playing || onScreen
    }

    /**
     * The adhan's volume from its controls: applied now and kept as the adhan volume for the next ones. Null keeps
     * it; [muted] silences this adhan only. False when nothing plays.
     */
    fun adhanVolume(percent: Int?, muted: Boolean? = null): Boolean {
        val ok = when {
            muted != null && percent == null -> player.setMuted(muted)
            percent != null -> player.setVolumeNow(percent).also { if (it) store.patch(JSONObject().put("adhan", JSONObject().put("volume", percent.coerceIn(0, 100)))) }
            else -> player.playing.value != null
        }
        post()
        return ok
    }

    fun openPhotos() {
        Device.openPhotos(context)
    }

    /**
     * Shown from the panel (a preview, a dhikr slide, adhkar.open) or an "open" reminder: lit and in front; with
     * the presence policy on it goes away again once that is over.
     */
    private fun remoteShow() {
        if (!presence.trigger(Presence.Reason.REMOTE)) showDisplay()
    }

    /** "open display" from the panel: as if opened by hand. */
    fun bringDisplay() {
        presence.touch()
        showDisplay()
    }

    private fun loopHome() = store.current.loop.let { if (it.enabled && it.home == "adhkar") FramePage.ADHKAR else FramePage.MAIN }

    /** The presence policy's periodic adhkar: the adhkar display, or a dhikr slide when that page is off. */
    private fun periodicAdhkar() {
        if (store.current.loop.enabled) {
            adhkar.close("presence")
            loop.touch() // or the loop's own return time would send it back to the resting page at once
            loop.show(FramePage.ADHKAR, "presence adhkar")
        } else if (loop.slideDhikr() != null) {
            showDhikrSlide()
        }
    }

    private fun showDisplay() {
        if (displayVisible) return
        context.startActivity(
            Intent(context, MawaqitActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun wake(why: String) {
        wakeUntil = SystemClock.elapsedRealtime() + WAKE_MS
        Log.d(TAG, "wake: $why")
        post()
    }

    private fun prefetch(s: Settings) {
        val mosque = repo.state.value.mosque
        player.prefetch(PrayerKey.FIVE.map { Effective.adhanSound(s, mosque, it) }.toSet())
    }

    /** Every state change goes through the main thread: RPCs arrive on the HTTP and ntfy threads. */
    private fun post() {
        scope.launch { tick() }
    }

    private fun tick() {
        val nowMs = System.currentTimeMillis()
        val st = compute(nowMs)
        _state.value = st
        adhkar.tick(st)
        loop.tick(st)
        if (st.synced) sideEffects(st)
        applyScreen(st)
    }

    private fun compute(nowMs: Long): DisplayState {
        val s = store.current
        val src = repo.state.value
        val times = src.times
        val zone = times?.zone ?: ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val behavior = Effective.behavior(s, src.mosque)
        val today = times?.day(now.toLocalDate())
        val tomorrow = times?.day(now.toLocalDate().plusDays(1))
        val next = times?.let { Timeline.nextPrayer(now, it) }
        var phase = times?.let { Timeline.phaseAt(now, it, behavior) } ?: Phase(PhaseKind.NORMAL, null, null, null)

        // skipped: what follows the adhan screen, from now (the times are exact, only its start moves)
        if (phase.kind == PhaseKind.ADHAN && phase.prayer?.adhan?.toInstant() == skippedAdhan && times != null) {
            phase.end?.let { end -> phase = Timeline.phaseAt(end, times, behavior).copy(start = now) }
        }
        preview?.let { (kind, until) ->
            if (nowMs < until) phase = Phase(kind, next ?: phase.prayer, now, Instant.ofEpochMilli(until).atZone(zone))
            else preview = null
        }
        overlay = overlay?.takeIf { nowMs < it.until }

        val adjust = (src.mosque?.hijriAdjustment ?: 0) + s.display.hijriAdjust
        val hijriDate = Hijri.of(now.toLocalDate(), adjust, src.mosque?.hijriForce30 == true)
        val lang = s.display.language
        return DisplayState(
            now = now,
            synced = Device.clockLooksSet(nowMs),
            settings = s,
            strings = Strings.of(lang),
            source = src,
            behavior = behavior,
            today = today,
            tomorrow = tomorrow,
            next = next,
            phase = phase,
            hijri = if (s.display.showHijri) Hijri.format(hijriDate, lang) else null,
            ramadan = hijriDate.month == 9,
            clock24h = Effective.clock24h(s, src.mosque),
            flash = Effective.flash(s, src.mosque, now.toLocalDate()),
            slide = slide(s, src, now, nowMs, phase, next),
            overlay = overlay,
            weather = repo.weather.value.takeIf { s.display.showWeather },
            screen = screen(s, behavior, phase),
            audioPlaying = player.playing.value != null,
            audio = player.playing.value,
            panelUrl = Device.ipAddress()?.let { "http://$it:${s.remote.port}/" },
            secondary = secondary.entries.value,
        )
    }

    private fun slide(s: Settings, src: SourceState, now: ZonedDateTime, nowMs: Long, phase: Phase, next: PrayerTime?): Slide? {
        if (phase.kind != PhaseKind.NORMAL && phase.kind != PhaseKind.REMINDER) return null
        val shown = slideShown
        val seconds = when (shown) {
            is Slide.OfAnnouncement -> shown.announcement.seconds
            else -> s.display.slideSeconds
        }
        if (shown != null && nowMs - slideStart < seconds * 1000L) return shown
        if (shown != null) {
            slideShown = null
            slideStart = nowMs
        }
        if (nowMs - slideStart < s.display.slideEveryMinutes * 60_000L) return null
        val hadith = repo.hadith.value?.takeIf { s.display.showHadith }
        val mosques = Slide.OfMosques.takeIf { s.display.showMosquesSlide && secondary.entries.value.any { it.today != null } }
        val slides = listOfNotNull(hadith?.let(Slide::OfHadith), mosques) +
            Effective.announcements(s, src.mosque, now).map(Slide::OfAnnouncement)
        // A dhikr instead, adhkarSlidePercent of the slots (every slot when there is nothing else to show)
        if (dhikrDrawnFor != slideStart) {
            dhikrDrawnFor = slideStart
            dhikrDrawn = slideRandom.nextInt(100) < s.display.adhkarSlidePercent
        }
        val dhikr = if (s.display.showAdhkarSlides && (dhikrDrawn || slides.isEmpty()) &&
            AdhkarLoop.slideAllowed(now, phase.kind, next?.adhan)) loop.slideDhikr()?.let { Slide.OfDhikr(it.first, it.second) } else null
        if (slides.isEmpty() && dhikr == null) return null
        val shownNow = dhikr ?: slides[slideIndex++ % slides.size]
        slideShown = shownNow
        slideStart = nowMs
        return shownNow
    }

    private fun screen(s: Settings, b: Behavior, phase: Phase): ScreenState {
        val day = s.display.brightness / 100f
        val awake = SystemClock.elapsedRealtime() < wakeUntil
        // "off" keeps the window brightness as it was: any change makes Android rewrite the backlight we just
        // switched off through root (seen on the frame: 0 became 30)
        return when {
            screenMode == "off" -> ScreenState("off", "off", day, black = true)
            screenMode == "on" -> ScreenState("on", "on", day)
            // the presence policy's "off": as the night's black mode (a touch or a knock wakes it)
            presence.asleep -> ScreenState("auto", "off", day, black = true)
            phase.kind == PhaseKind.NIGHT && !awake -> when (b.nightMode) {
                NightMode.BLACK -> ScreenState("auto", "off", day, black = true)
                else -> ScreenState("auto", "dim", s.night.brightness / 100f)
            }
            phase.kind == PhaseKind.PRAYER && b.blackScreen -> ScreenState("auto", "dim", 0.02f, black = true)
            phase.kind == PhaseKind.JUMUA && b.jumuaBlackScreen -> ScreenState("auto", "dim", 0.02f, black = true)
            else -> ScreenState("auto", "on", day)
        }
    }

    private fun applyScreen(st: DisplayState) {
        val wantOff = st.screen.state == "off" && displayVisible
        if (wantOff != backlightIsOff) {
            backlightIsOff = wantOff
            // one at a time and in order: an "on" right after an "off" (a knock, a touch) mustn't land before it
            scope.launch { backlight.withLock { if (wantOff) Device.backlightOff() else Device.backlightOn() } }
        }
        val night = st.phase.kind == PhaseKind.NIGHT || st.screen.state == "off"
        knock.listen(night && st.settings.night.wakeOnKnock && displayVisible)
    }

    private fun sideEffects(st: DisplayState) {
        val s = st.settings
        val phase = st.phase
        val previous = lastPhase
        lastPhase = phase
        val entered = previous != null && (previous.kind != phase.kind || previous.prayer?.adhan != phase.prayer?.adhan)
        val previewing = preview != null

        // Adhan: on the minute, whether or not the adhan screen is shown, never late by more than a minute
        st.source.times?.let { times ->
            val due = Timeline.nextPrayer(st.now.minusMinutes(1), times)
            if (due != null && !due.adhan.isAfter(st.now) && due.adhan.toInstant() != firedAdhan) {
                firedAdhan = due.adhan.toInstant()
                val i = due.key.five
                if (i >= 0 && st.behavior.adhanEnabled.getOrElse(i) { true } && Duration.between(due.adhan, st.now).seconds < 60) {
                    Log.i(TAG, "adhan ${due.key.id} at ${due.adhan}")
                    player.play(Effective.adhanSound(s, st.source.mosque, due.key), s.adhan.volume, "adhan:${due.key.id}")
                }
            }
        }
        if (entered && !previewing) {
            when (phase.kind) {
                PhaseKind.IQAMA -> if (s.adhan.iqamaBeep) player.beep(s.adhan.volume)
                PhaseKind.REMINDER -> if (s.adhan.reminderChime) player.beep(s.adhan.volume)
                else -> Unit
            }
            Log.i(TAG, "phase ${phase.kind.id} ${phase.prayer?.key?.id ?: ""} until ${phase.end}")
        }
        presence.tick(st, adhkar.holdsDisplay())
    }

    private companion object {
        const val TAG = "Engine"
        const val WAKE_MS = 60_000L
    }
}
