package dev.framio.pomodoro.core

import dev.framio.pomodoro.core.engine.Effect
import dev.framio.pomodoro.core.engine.EffectHandler
import dev.framio.pomodoro.core.engine.EngineConfig
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Next
import dev.framio.pomodoro.core.engine.reduce
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.time.Moment
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Collects effects in order. */
public class RecordingEffects : EffectHandler {
    public val effects: MutableList<Effect> = mutableListOf()
    override fun handle(effect: Effect) {
        effects += effect
    }

    public inline fun <reified T : Effect> all(): List<T> = effects.filterIsInstance<T>()
}

/**
 * Drives [reduce] like the engine does, without coroutines: one state, a [FakeClock], and every effect
 * recorded. Throws on a rejected input unless told to expect it.
 */
public class Driver(
    public val clock: FakeClock = FakeClock(),
    settings: Settings = Settings(),
    public val config: EngineConfig = EngineConfig.Default,
) {
    public var state: FocusState = reduce(FocusState(), Input.Restore(null, settings), clock.now(), config).state
    public val effects: MutableList<Effect> = mutableListOf()
    public var last: Next? = null

    public fun send(input: Input): Next {
        val next = reduce(state, input, clock.now(), config)
        check(next.rejected == null) { "$input rejected: ${next.rejected}" }
        return apply(next)
    }

    /** Sends an input that must be rejected; returns the reason. */
    public fun reject(input: Input): String {
        val next = reduce(state, input, clock.now(), config)
        check(next.state == state && next.effects.isEmpty()) { "rejection changed something" }
        return next.rejected ?: error("$input was accepted")
    }

    /** Moves time on and lets the scheduler fire. */
    public fun pass(d: Duration): Next {
        clock.advance(d)
        return send(Input.DeadlineReached)
    }

    private fun apply(next: Next): Next {
        state = next.state
        effects += next.effects
        last = next
        return next
    }

    public inline fun <reified T : Effect> all(): List<T> = effects.filterIsInstance<T>()

    public val logged: List<SessionRecord> get() = all<Effect.AppendLog>().map { it.record }
}

/** A session-log record started at local [start] ("2026-10-18T09:00") in [zone]. */
public fun record(
    start: String,
    focused: Duration = 25.minutes,
    outcome: Outcome = Outcome.Done,
    rating: Int? = null,
    task: TaskId? = null,
    id: String = "s-$start",
    zone: ZoneId = AMSTERDAM,
): SessionRecord = SessionRecord(
    id = SessionId(id),
    mode = ModeId("classic"),
    task = task,
    planned = 25.minutes,
    focused = focused,
    outcome = outcome,
    rating = rating,
    start = Moment("boot-1", 1_000, wallAt(start, zone)),
)
