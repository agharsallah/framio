package dev.framio.pomodoro.ui.m5

import dev.framio.pomodoro.ui.keyboard.KeyLayer
import dev.framio.pomodoro.ui.keyboard.KeyboardModel
import dev.framio.pomodoro.ui.keyboard.Shift
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The built-in keyboard's rules, typed on the JVM: case, layers, accents, Backspace's repeat, the length limit. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardModelTest {
    private var now = 0L

    private fun TestScope.keyboard(initial: String = "", max: Int = 80): Pair<KeyboardModel, MutableList<String>> {
        val sent = mutableListOf<String>()
        val k = KeyboardModel(initial, backgroundScope, max, clock = { now }) { sent += it }
        return k to sent
    }

    private fun KeyboardModel.type(text: String) = text.forEach { c -> if (c == ' ') space() else tap(c.toString()) }

    @Test
    fun `sentence case - a capital to start and after a full stop, then lower case`() = runTest {
        val (k, sent) = keyboard()
        assertEquals(Shift.Once, k.shift)
        k.type("hello. world")
        assertEquals("Hello. World", k.text)
        assertEquals(Shift.Off, k.shift)
        assertEquals("Hello. World", sent.last()) // every edit is reported at once
    }

    @Test
    fun `shift is one capital, a double tap locks caps, a third tap unlocks`() = runTest {
        val (k, _) = keyboard("x")
        assertEquals(Shift.Off, k.shift)
        k.shiftTap()
        k.type("ab")
        assertEquals("xAb", k.text)
        now = 1_000
        k.shiftTap()
        now = 1_200
        k.shiftTap() // within 400 ms: caps lock
        assertEquals(Shift.Locked, k.shift)
        k.type("cd. e")
        assertEquals("xAbCD. E", k.text)
        k.shiftTap()
        assertEquals(Shift.Off, k.shift)
        k.type("f")
        assertEquals("xAbCD. Ef", k.text)
        // Two slow taps are on, then off
        now = 5_000
        k.shiftTap()
        now = 6_000
        k.shiftTap()
        assertEquals(Shift.Off, k.shift)
    }

    @Test
    fun `a long press offers French and German accents in the right case`() = runTest {
        val (k, _) = keyboard()
        k.longPress("e")
        assertEquals(listOf("É", "È", "Ê", "Ë", "Ē"), k.accents?.options) // sentence start: capitals
        k.pick("É")
        assertNull(k.accents)
        k.longPress("t") // no variants: types itself
        k.longPress("e")
        k.pick("é")
        k.longPress("a")
        k.pick("à")
        assertEquals("Étéà", k.text)
        k.space()
        k.longPress("u")
        k.pick("ü")
        k.longPress("s")
        assertEquals("ß", k.accents?.options?.first())
        k.pick("ß")
        assertEquals("Étéà üß", k.text)
        k.longPress("o")
        k.dismissAccents()
        assertNull(k.accents)
        assertEquals("Étéà üß", k.text)
    }

    @Test
    fun `under Shift the sharp s stays a sharp s`() = runTest {
        val (k, _) = keyboard()
        k.longPress("s")
        assertEquals("ß", k.accents?.options?.first())
    }

    @Test
    fun `the symbols layer types digits and marks, and space goes back to letters`() = runTest {
        val (k, _) = keyboard("Call at")
        k.switchLayer()
        assertEquals(KeyLayer.Symbols, k.layer)
        k.space()
        assertEquals(KeyLayer.Letters, k.layer)
        k.switchLayer()
        k.type("10:30")
        k.longPress("\"")
        k.pick("„")
        k.longPress("-")
        k.pick("–")
        assertEquals("Call at 10:30„–", k.text)
        assertEquals(KeyLayer.Symbols, k.layer)
    }

    @Test
    fun `backspace deletes at once, repeats while held, then whole words, and stops on release`() = runTest {
        val (k, _) = keyboard("one two three four five six seven eight nine ten eleven twelve thirteen")
        val start = k.text.length
        k.backspaceDown()
        assertEquals(start - 1, k.text.length)
        advanceTimeBy(KeyboardModel.REPEAT_START.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(start - 1, k.text.length) // nothing more before the repeat starts
        advanceTimeBy(1)
        runCurrent()
        assertEquals(start - 2, k.text.length)
        advanceTimeBy(KeyboardModel.REPEAT_EVERY.inWholeMilliseconds * 3)
        runCurrent()
        assertEquals(start - 5, k.text.length)
        k.backspaceUp()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(start - 5, k.text.length) // released: stays
        // Held long, it clears word by word down to empty, then stops by itself
        k.backspaceDown()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("", k.text)
        assertEquals(Shift.Once, k.shift) // empty again: a sentence start
        k.backspaceUp()
    }

    @Test
    fun `nothing types past the limit, and an outside change is taken over`() = runTest {
        val (k, sent) = keyboard("abc", max = 5)
        k.type("def")
        assertEquals("abcde", k.text)
        k.sync("")
        assertEquals("", k.text)
        assertEquals(Shift.Once, k.shift)
        val before = sent.size
        k.sync("") // the same value: nothing to do, nothing reported
        assertEquals(before, sent.size)
        k.tap("x")
        k.deleteOne()
        k.deleteOne() // on empty: harmless
        assertEquals("", k.text)
    }

    @Test
    fun `an emoji or other surrogate pair is deleted whole`() = runTest {
        val (k, _) = keyboard("ok 😀")
        k.deleteOne()
        assertEquals("ok ", k.text)
    }
}
