package dev.framio.pomodoro.ui.deep

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TouchPolicy
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.notes.EndDraft
import dev.framio.pomodoro.ui.notes.EndFlow
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.DeepHardware
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.FaceTiming
import dev.framio.pomodoro.ui.stateForUi
import dev.framio.pomodoro.ui.timing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What Deep focus shows. The flags below [timing] are this screen's own; everything above comes from the engine. */
@Immutable
data class DeepUi(
    val modeName: String = "",
    val accent: Int = 0,
    val intention: String? = null,
    val blackout: Boolean = false,
    val paused: Boolean = false,
    val overCap: Boolean = false,
    val knock: Boolean = false,
    /** "Still on it?": a 3 s glow every so often (logical time), Calm only; null = off (the default). */
    val checkIn: Duration? = null,
    val hold: Duration = 1200.milliseconds,
    /** A minute on the face, in real time (a second in debug "minutes as seconds" runs). */
    val tick: Duration = 1.minutes,
    val reduceMotion: Boolean = false,
    val timing: FaceTiming = FaceTiming.None,
    val panel: Boolean = false,
    val hint: Boolean = false,
    val peeking: Boolean = false,
    val park: Boolean = false,
    /** The end sheet's answers while it is open; null when closed. */
    val end: EndDraft? = null,
    /** The hold panel's reason chips (offered tags, in your order); empty hides the row. */
    val reasons: List<ReasonTag> = emptyList(),
    /** Tags of the distraction being logged right now (the 2 s window after a chip tap). */
    val logging: Set<TagId> = emptySet(),
    /** "More" was tapped: every offered tag shows. */
    val moreReasons: Boolean = false,
    /** Distractions logged in this block so far. */
    val logged: Int = 0,
    /** The end sheet: reasons (the end-only ones first), suggestions, minutes so far / planned. */
    val endReasons: List<ReasonTag> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val focusedMinutes: Int? = null,
    val plannedMinutes: Int? = null,
) {
    /** The "Next step when you're back?" words while the end sheet is open; null when closed. */
    val note: String? get() = end?.text

    /** Something is shown at full strength: a peek, the panel, the pad or the end prompt (or the pause view). */
    val revealed: Boolean get() = peeking || panel || park || note != null || paused

    /** Taps and holds go to the face only when nothing sits on top of it. */
    val locked: Boolean get() = !paused && !park && note == null
}

/**
 * Deep focus (Calm or Blackout): the touch lock, the panel, the knock peek, the parking lot and the end prompt. The
 * engine knows nothing of these (Peek is UI-only, PLAN §6.1); this ViewModel asks [hw] to lift the dark display while
 * any of them shows, and listens for knocks while the route has the accelerometer on.
 */
class DeepFocusViewModel(
    private val port: EnginePort,
    private val hw: DeepHardware,
    private val history: HistoryPort = NoHistory,
) : ViewModel() {
    private data class Local(
        val panel: Boolean = false,
        val hint: Boolean = false,
        val peeking: Boolean = false,
        val park: Boolean = false,
        val window: Entry.Distraction? = null,
        val more: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val endFlow = EndFlow(history)
    private var hintJob: Job? = null
    private var peekJob: Job? = null
    private var panelJob: Job? = null

    val ui = combine(port.state, local, endFlow.draft, history.tags, history.live) { s, l, end, tags, live -> map(s, l, end, tags, live) }
        .stateForUi(viewModelScope, map(port.state.value, Local(), null, history.tags.value, history.live.value))

    init {
        viewModelScope.launch {
            combine(local, endFlow.draft) { l, end -> l.peeking || l.panel || l.park || end != null }.distinctUntilChanged().collect(hw::light)
        }
        viewModelScope.launch { hw.knocks.collect { onKnock() } }
    }

    fun now(): Now = port.now()

    /** Time until the next check-in glow, in real time; null when check-ins are off. */
    fun untilCheckIn(): Duration? {
        val every = ui.value.checkIn ?: return null
        val focused = ui.value.timing.focused(port.now()) ?: return null
        val periodMs = every.inWholeMilliseconds.coerceAtLeast(1)
        val next = (periodMs - focused.inWholeMilliseconds.mod(periodMs)).milliseconds
        return port.config.real(next)
    }

    /** A short touch: nothing happens but a faint "hold to open" for 2 s (and nothing at all in Blackout). */
    fun tap() {
        val s = local.value
        when {
            s.panel -> closePanel()
            ui.value.blackout || ui.value.paused -> Unit
            else -> {
                hintJob?.cancel()
                local.update { it.copy(hint = true) }
                hintJob = viewModelScope.launch {
                    delay(HINT_FOR)
                    local.update { it.copy(hint = false) }
                }
            }
        }
    }

    /** Held for 1.2 s: the panel (Park a thought · Pause · End). It closes by itself when left alone. */
    fun hold() {
        if (ui.value.paused) return
        hintJob?.cancel()
        local.update { it.copy(panel = true, hint = false) }
        panelJob?.cancel()
        panelJob = viewModelScope.launch {
            delay(PANEL_FOR)
            local.update { it.copy(panel = false) }
        }
    }

    fun closePanel() {
        panelJob?.cancel()
        local.update { it.copy(panel = false, window = null, more = false) }
    }

    /**
     * A reason chip in the panel (DESIGN-history §5.1): the first tap logs a distraction now, at this focus time;
     * more taps in the next [LOG_WINDOW] add or remove reasons on the same one. Then the panel goes by itself.
     */
    fun logReason(tag: TagId) {
        val session = port.state.value.session?.id ?: return
        val cur = local.value.window
        val entry = if (cur == null) {
            Entry.Distraction(history.newEntryId(), session, ui.value.timing.focused(port.now()), history.wallNow(), listOf(tag))
        } else {
            cur.copy(tags = if (tag in cur.tags) cur.tags - tag else cur.tags + tag)
        }
        if (entry.tags.isEmpty()) history.delete(entry) else history.put(entry)
        local.update { it.copy(window = entry.takeIf { e -> e.tags.isNotEmpty() }) }
        closePanelAfter(LOG_WINDOW)
    }

    /** "More": the rest of the reasons; the panel stays a while longer. */
    fun moreReasons() {
        local.update { it.copy(more = true) }
        closePanelAfter(PANEL_FOR)
    }

    private fun closePanelAfter(d: Duration) {
        panelJob?.cancel()
        panelJob = viewModelScope.launch {
            delay(d)
            local.update { it.copy(panel = false, window = null, more = false) }
        }
    }

    fun pause() {
        closePanel()
        port.send(Input.Pause)
    }

    fun resume() = port.send(Input.Resume)

    fun openPark() {
        panelJob?.cancel()
        local.update { it.copy(panel = false, park = true) }
    }

    /** Parks [ink] (InkCodec text; null or empty parks nothing) and goes back to the dark face. */
    fun park(ink: String?) {
        if (!ink.isNullOrEmpty()) port.send(Input.Park(ink))
        local.update { it.copy(park = false) }
    }

    fun cancelPark() = local.update { it.copy(park = false) }

    /** End: the end sheet first, why and "Next step when you're back?" (PLAN §3 principle 4), all optional. */
    fun askEnd() {
        panelJob?.cancel()
        local.update { it.copy(panel = false, window = null, more = false) }
        endFlow.open()
    }

    /** The end sheet's own controls (reasons, suggestions, pad, keyboard). */
    val end: EndFlow get() = endFlow

    fun editNote(text: String) = endFlow.editText(text)

    /** Ends early with the reasons and the note (if any); logged as ended early, never as failed. */
    fun confirmEnd() {
        val s = port.state.value
        port.send(endFlow.finish(s.session?.id, ui.value.timing.focused(port.now())))
    }

    fun cancelEnd() = endFlow.close()

    /** The route's resume effect: the accelerometer only while Deep focus is on screen with knock on. */
    fun listen(on: Boolean) = hw.listen(on)

    /** The screen went away (another phase): forget the overlays, lower the display, stop listening. */
    fun leave() {
        hintJob?.cancel()
        peekJob?.cancel()
        panelJob?.cancel()
        local.value = Local()
        endFlow.close()
        hw.listen(false)
        hw.light(false)
    }

    private fun onKnock() {
        val s = port.state.value
        val dark = s.mode.environment.display != Display.Full
        if (s.phase !is Phase.Focus || !dark || !s.mode.environment.knockToPeek) return
        peek()
    }

    /** The peek: the display lifts for [PEEK_FOR] and shows the time left, then goes dark again. */
    fun peek() {
        peekJob?.cancel()
        local.update { it.copy(peeking = true) }
        peekJob = viewModelScope.launch {
            delay(PEEK_FOR)
            local.update { it.copy(peeking = false) }
        }
    }

    override fun onCleared() {
        hw.listen(false)
        hw.light(false)
    }

    private fun map(s: FocusState, l: Local, end: EndDraft?, tags: Taxonomy, live: Map<SessionId, List<Entry>>): DeepUi {
        val mode = s.mode
        val env = mode.environment
        val paused = s.phase as? Phase.Paused
        val timing = port.timing(s)
        val endOnly = tags.offered(TagScope.EndOnly)
        val intention = s.run?.intent?.intention
        return DeepUi(
            modeName = mode.name,
            accent = s.settings.accent ?: mode.accent,
            intention = s.run?.intent?.intention,
            blackout = env.display == Display.Blackout,
            paused = paused != null,
            overCap = paused?.overCap == true,
            knock = env.knockToPeek,
            checkIn = env.checkIn,
            hold = (env.touch as? TouchPolicy.HoldToReveal)?.hold ?: 1200.milliseconds,
            tick = port.config.real(1.minutes),
            reduceMotion = s.settings.reduceMotion,
            timing = timing,
            panel = l.panel,
            hint = l.hint,
            peeking = l.peeking,
            park = l.park,
            end = end,
            reasons = tags.offered(TagScope.Any),
            logging = l.window?.tags?.toSet().orEmpty(),
            moreReasons = l.more,
            logged = s.session?.id?.let { live[it] }.orEmpty().count { it is Entry.Distraction },
            endReasons = endOnly.filter { it.scope == TagScope.EndOnly } + endOnly.filter { it.scope != TagScope.EndOnly },
            suggestions = suggestionsFor(intention),
            focusedMinutes = if (end != null) timing.focused(port.now())?.inWholeMinutes?.toInt() else null,
            plannedMinutes = s.session?.planned?.inWholeMinutes?.toInt(),
        )
    }

    companion object {
        val HINT_FOR: Duration = 2.seconds
        val PEEK_FOR: Duration = 5.seconds
        val PANEL_FOR: Duration = 10.seconds

        /** After a reason chip, the panel stays this long for another reason, then goes. */
        val LOG_WINDOW: Duration = 2.seconds
        const val MAX_NOTE = EndFlow.MAX_NOTE

        fun factory(port: EnginePort, hw: DeepHardware, history: HistoryPort = NoHistory): ViewModelProvider.Factory =
            viewModelFactory { initializer { DeepFocusViewModel(port, hw, history) } }
    }
}

/** The resume note's suggestion chips (DESIGN-history §5.3). Plain words, so they read back as a note. */
fun suggestionsFor(intention: String?): List<String> = listOfNotNull(
    intention?.let { "Continue $it" },
    "Re-read where I stopped",
)
