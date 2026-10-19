package dev.framio.pomodoro.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backlight rules of M0: restore only what we changed, only if nobody changed it since, and after a kill. */
class DisplayPowerTest {
    private class FakeBacklight(var level: Int = 187) : Backlight {
        val writes = mutableListOf<Int>()
        override fun read(): Int = level
        override fun write(level: Int): Boolean {
            writes += level
            this.level = level
            return true
        }
    }

    private class MemoryMarker : DarkMarker {
        var value: Pair<Int, Int>? = null
        override fun save(wrote: Int, day: Int) { value = wrote to day }
        override fun load() = value
        override fun clear() { value = null }
    }

    private fun power(b: Backlight, m: DarkMarker) = DisplayPower(b, m, Dispatchers.Unconfined) { 120 }

    @Test
    fun `off then restore brings back the level read before going dark`() = runBlocking {
        val b = FakeBacklight(187)
        val p = power(b, MemoryMarker())
        assertTrue(p.off())
        assertEquals(0, b.level)
        assertTrue(p.restore("test"))
        assertEquals(187, b.level)
        assertNull(p.written)
    }

    @Test
    fun `restore leaves the backlight alone when something else changed it meanwhile`() = runBlocking {
        val b = FakeBacklight(187)
        val m = MemoryMarker()
        val p = power(b, m)
        p.off()
        b.level = 40 // Frameo or the system wrote it
        assertFalse(p.restore("test"))
        assertEquals(40, b.level)
        assertNull(m.value)
    }

    @Test
    fun `restore does nothing when we never touched the backlight`() = runBlocking {
        val b = FakeBacklight(0) // Frameo's standby turned it off
        assertFalse(power(b, MemoryMarker()).restore("test"))
        assertEquals(emptyList<Int>(), b.writes)
    }

    @Test
    fun `after a kill while dark the next start restores it, from the marker`() = runBlocking {
        val b = FakeBacklight(150)
        val m = MemoryMarker()
        power(b, m).off() // then kill -9: no handler ran
        power(b, m).recoverAtStart()
        assertEquals(150, b.level)
        assertNull(m.value)
    }

    @Test
    fun `a dark screen without our marker stays dark at start`() = runBlocking {
        val b = FakeBacklight(0)
        power(b, MemoryMarker()).recoverAtStart()
        assertEquals(emptyList<Int>(), b.writes)
    }

    @Test
    fun `the crash path follows the same rule`() = runBlocking {
        val b = FakeBacklight(187)
        val p = power(b, MemoryMarker())
        p.off()
        p.restoreBlocking()
        assertEquals(187, b.level)
    }
}
