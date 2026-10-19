package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.time.Deadline
import dev.framio.pomodoro.core.time.EngineClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Carries out effects. Called on the engine's single thread, in order: must not block. */
public fun interface EffectHandler {
    public fun handle(effect: Effect)
}

/**
 * The single writer of [FocusState]. Taps, adb commands and the scheduler queue up in one inbox and one
 * coroutine applies them in order through [reduce], on [dispatcher] limited to one thread at a time: no locks,
 * no races. The state flow updates before effects run, so the UI never waits for the disk.
 *
 * A single scheduler coroutine sleeps until the next wake ([Derive.wake]) and then sends
 * [Input.DeadlineReached]. There is no per-second ticker: the UI derives the remaining time itself.
 * Nothing happens until the app dispatches [Input.Restore].
 */
public class FocusEngine(
    private val clock: EngineClock,
    private val effects: EffectHandler,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    public val config: EngineConfig = EngineConfig.Default,
    private val log: (String) -> Unit = {},
) {
    private class Pending(val input: Input, val reply: CompletableDeferred<Next>?)

    private val inbox = Channel<Pending>(capacity = 64)
    private val _state = MutableStateFlow(FocusState())
    private val wake = MutableStateFlow<Deadline?>(null)

    public val state: StateFlow<FocusState> = _state.asStateFlow()

    /** Queues [input]; false only if 64 inputs are already waiting, which means something is flooding us. */
    public fun dispatch(input: Input): Boolean = inbox.trySend(Pending(input, null)).isSuccess

    /** Applies [input] and returns the result, for callers that answer someone (adb, tests). */
    public suspend fun submit(input: Input): Next {
        val reply = CompletableDeferred<Next>()
        inbox.send(Pending(input, reply))
        return reply.await()
    }

    init {
        val confined = dispatcher.limitedParallelism(1)
        scope.launch(confined) {
            for (pending in inbox) {
                val result = try {
                    apply(pending.input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // a bug in reduce must not stop the timer: log it, keep the old state
                    log("engine: ${pending.input::class.simpleName} failed: $e")
                    Next(_state.value, rejected = "internal error")
                }
                pending.reply?.complete(result)
            }
        }
        scope.launch(confined) {
            wake.collectLatest { at ->
                if (at == null) return@collectLatest
                while (true) {
                    // Unknown (lost across a reboot) counts as due: reduce then decides what that means
                    val left = clock.now().until(at) ?: Duration.ZERO
                    delay(left.coerceIn(MIN_SLEEP, MAX_SLEEP))
                    if (left <= MAX_SLEEP) submit(Input.DeadlineReached)
                    // Still the same wake (it wasn't due yet, or the clock moved): look again
                }
            }
        }
    }

    private fun apply(input: Input): Next {
        val next = reduce(_state.value, input, clock.now(), config)
        if (next.rejected != null) {
            log("input ${input::class.simpleName} rejected: ${next.rejected}")
            return next
        }
        _state.value = next.state
        wake.value = Derive.wake(next.state, config)
        for (effect in next.effects) {
            try {
                effects.handle(effect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("effect $effect failed: $e")
            }
        }
        return next
    }

    public companion object {
        /** Never sleep longer than this, as a safety net; a DeadlineReached that changes nothing costs microseconds. */
        public val MAX_SLEEP: Duration = 5.minutes
        private val MIN_SLEEP: Duration = 10.milliseconds
    }
}
