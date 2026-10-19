@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.platform

import dev.framio.pomodoro.core.VirtualClock
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.wallAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Evening amber's watcher on virtual time: it wakes at the start and the end, follows settings, and can be faked. */
class EveningWatchTest {
    @Test
    fun `turns on at the evening start and off in the morning, without polling in between`() = runTest {
        val clock = VirtualClock(testScheduler, startWallMs = wallAt("2026-10-18T19:58"))
        val state = MutableStateFlow(FocusState(loaded = true, settings = Settings()))
        val watch = EveningWatch(clock, state, backgroundScope)
        runCurrent()
        assertFalse(watch.on.value)
        advanceTimeBy(2.minutes + 2.seconds)
        runCurrent()
        assertTrue(watch.on.value)
        advanceTimeBy(9.hours + 58.minutes) // 05:59 and a bit
        runCurrent()
        assertTrue(watch.on.value)
        advanceTimeBy(2.minutes)
        runCurrent()
        assertFalse(watch.on.value)
    }

    @Test
    fun `follows the settings at once - off, and a later start`() = runTest {
        val clock = VirtualClock(testScheduler, startWallMs = wallAt("2026-10-18T21:00"))
        val state = MutableStateFlow(FocusState(loaded = true, settings = Settings()))
        val watch = EveningWatch(clock, state, backgroundScope)
        runCurrent()
        assertTrue(watch.on.value)
        state.value = state.value.copy(settings = Settings(evening = false))
        runCurrent()
        assertFalse(watch.on.value)
        state.value = state.value.copy(settings = Settings(eveningStart = LocalTime.of(22, 0)))
        runCurrent()
        assertFalse(watch.on.value)
    }

    @Test
    fun `an untrusted clock never turns it on, and the fake time is only for a trusted one`() = runTest {
        val clock = VirtualClock(testScheduler, startWallMs = wallAt("2026-10-18T12:00"))
        clock.trusted = false
        val state = MutableStateFlow(FocusState(loaded = true, settings = Settings()))
        val watch = EveningWatch(clock, state, backgroundScope)
        runCurrent()
        watch.fake(LocalTime.of(21, 30))
        runCurrent()
        assertFalse(watch.on.value)
        clock.trusted = true
        watch.recheck()
        runCurrent()
        assertFalse(watch.on.value) // noon
        watch.fake(LocalTime.of(21, 30))
        runCurrent()
        assertTrue(watch.on.value)
        watch.fake(null)
        runCurrent()
        assertFalse(watch.on.value)
    }
}
