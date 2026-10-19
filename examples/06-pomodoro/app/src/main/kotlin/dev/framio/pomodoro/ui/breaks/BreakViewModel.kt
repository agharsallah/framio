package dev.framio.pomodoro.ui.breaks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.CopyTone
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.rhythm.FlowRhythm
import dev.framio.pomodoro.core.time.DayPart
import kotlin.time.Duration.Companion.minutes
import dev.framio.pomodoro.core.rhythm.BreakKind
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.FaceTiming
import dev.framio.pomodoro.ui.stateForUi
import dev.framio.pomodoro.ui.timing
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.wrapup.countsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@Immutable
data class BreakUi(
    val long: Boolean = false,
    val prompt: BreakPrompt = BreakPrompt.Move,
    /** Auto-break modes: the block's wrap-up is a card on the break screen until answered. */
    val pendingWrapUp: Boolean = false,
    val accent: Int = 0,
    val done: Int = 0,
    val target: Int = 6,
    val timing: FaceTiming = FaceTiming.None,
    /** The block's parked thoughts (ink), back at the break (PLAN §3 principle 3). */
    val parked: List<String> = emptyList(),
    /** Flow: how long the block was and the break it earned ("worked 37 min → 8 min break"); null otherwise. */
    val tier: FlowTier? = null,
    /** The landscape's light. */
    val dayPart: DayPart = DayPart.Day,
    /** Reduce motion: the landscape is a still picture. */
    val reduceMotion: Boolean = false,
    /** Gentle start's warmer words. */
    val warm: Boolean = false,
    /** +5 min is offered while the break may still grow (at most 90 min). */
    val canExtend: Boolean = true,
    /** The wrap-up card's "Pulled away?" chips, and the block's distractions per tag. */
    val reasons: List<ReasonTag> = emptyList(),
    val counts: Map<TagId, Int> = emptyMap(),
    val moreReasons: Boolean = false,
)

/** Flow's tier, for the break: [workedMinutes] of focus earned [breakMinutes] of rest (Smits tiers). */
@Immutable
data class FlowTier(val workedMinutes: Int, val breakMinutes: Int)

/**
 * The break: the landscape, one prompt that points away from the screen, the time left, +5 min and Skip, Flow's tier,
 * and the wrap-up card for auto-break modes.
 */
class BreakViewModel(private val port: EnginePort, private val history: HistoryPort = NoHistory) : ViewModel() {
    private data class Local(val session: SessionId? = null, val recalled: Map<TagId, Entry.Distraction> = emptyMap(), val more: Boolean = false)

    private val local = MutableStateFlow(Local())

    val ui = combine(port.state, local, history.tags, history.live) { s, l, tags, live -> map(s, l, tags, live) }
        .stateForUi(viewModelScope, map(port.state.value, Local(), history.tags.value, history.live.value))

    /**
     * A reason chip on the wrap-up card (DESIGN-history §5.5): a distraction remembered now; the same chip again takes
     * back the one added here.
     */
    fun toggleReason(tag: TagId) {
        val session = port.state.value.session?.id ?: return
        val l = local.value.takeIf { it.session == session } ?: Local(session)
        val mine = l.recalled[tag]
        local.value = if (mine != null) {
            history.delete(mine)
            l.copy(recalled = l.recalled - tag)
        } else {
            val e = Entry.Distraction(history.newEntryId(), session, null, history.wallNow(), listOf(tag))
            history.put(e)
            l.copy(recalled = l.recalled + (tag to e))
        }
    }

    fun moreReasons() {
        local.value = local.value.copy(more = true)
    }

    fun now(): Now = port.now()

    /** A null [outcome] dismisses the card; the block is then logged unrated. */
    fun wrapUp(outcome: Outcome?) = port.send(Input.WrapUp(outcome = outcome))

    fun skip() = port.send(Input.SkipBreak)

    /** +5 min. */
    fun extend() = port.send(Input.ExtendBreak())

    fun home() = port.send(Input.Home)

    private fun map(s: FocusState, l: Local, tags: Taxonomy, live: Map<SessionId, List<Entry>>): BreakUi {
        val p = s.phase as? Phase.Break
        return BreakUi(
            long = p?.kind == BreakKind.Long,
            prompt = p?.prompt ?: BreakPrompt.Move,
            pendingWrapUp = p?.pendingWrapUp == true && s.session?.logged == false,
            accent = s.settings.accent ?: s.mode.accent,
            done = s.completedToday,
            target = s.settings.dailyTarget,
            timing = port.timing(s),
            parked = s.session?.parked.orEmpty().map { it.ink },
            tier = tierOf(s),
            dayPart = DayPart.of(port.now(), s.settings.eveningStart),
            reduceMotion = s.settings.reduceMotion,
            warm = s.mode.environment.tone == CopyTone.Warm,
            canExtend = p == null || p.length + 5.minutes <= MAX_BREAK,
            reasons = tags.offered(TagScope.Any),
            counts = countsOf(s.session?.id?.let { live[it] }.orEmpty()),
            moreReasons = l.more && l.session == s.session?.id,
        )
    }

    private fun tierOf(s: FocusState): FlowTier? {
        val rhythm = s.mode.rhythm as? Rhythm.Flow ?: return null
        val focused = s.session?.focused ?: return null
        return FlowTier(focused.inWholeMinutes.toInt(), FlowRhythm.breakFor(rhythm, focused).inWholeMinutes.toInt())
    }

    companion object {
        /** The engine refuses a break longer than this (Reduce.MAX_BREAK). */
        val MAX_BREAK = 90.minutes

        fun factory(port: EnginePort, history: HistoryPort = NoHistory): ViewModelProvider.Factory =
            viewModelFactory { initializer { BreakViewModel(port, history) } }
    }
}
