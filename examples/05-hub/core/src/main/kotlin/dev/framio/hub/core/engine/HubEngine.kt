package dev.framio.hub.core.engine

import dev.framio.hub.core.model.HubState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single writer of [HubState]. Inputs from the UI, phones, adb, sensors and the scheduler queue up in
 * one inbox and one coroutine applies them in order: no locks, and no races between a finger and a phone.
 * The state flow updates first (the UI never waits for the disk); [StateStore.offer] persists afterwards.
 */
class HubEngine(
    private val clock: HubClock,
    private val store: StateStore,
    private val effects: EffectHandler,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private class Pending(val input: Input, val reply: CompletableDeferred<Next>?)

    private val inbox = Channel<Pending>(capacity = 64)
    private val _state = MutableStateFlow(HubState())
    private val _loaded = MutableStateFlow(false)

    val state: StateFlow<HubState> = _state.asStateFlow()

    /** False until the stored state is read; the UI waits for it so it never flashes an empty board. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Queues [input]; false only if the inbox is full (64 waiting), which means something is flooding us. */
    fun dispatch(input: Input): Boolean = inbox.trySend(Pending(input, null)).isSuccess

    fun dispatch(command: Command): Boolean = dispatch(Input.Wire(command))

    /** Applies [input] and returns the result, for callers that answer someone (the LAN API, adb). */
    suspend fun submit(input: Input): Next {
        val reply = CompletableDeferred<Next>()
        inbox.send(Pending(input, reply))
        return reply.await()
    }

    init {
        scope.launch {
            _state.value = store.load()
            _loaded.value = true
            for (pending in inbox) {
                val result = try {
                    apply(pending.input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // a bug in reduce must not stop the Hub: log it, keep the old state
                    log("engine: ${pending.input::class.simpleName} failed: $e")
                    Next(_state.value, rejected = "internal error")
                }
                pending.reply?.complete(result)
            }
        }
    }

    private fun apply(input: Input): Next {
        val before = _state.value
        val next = reduce(before, input, clock.now())
        if (next.state != before) {
            _state.value = next.state
            store.offer(next.state)
        }
        next.rejected?.let { if (input is Input.Wire) log("command ${input.command.id} rejected: $it") }
        next.effects.forEach { effect ->
            try {
                effects.handle(effect)
            } catch (e: Exception) {
                log("effect $effect failed: $e")
            }
        }
        return next
    }
}
