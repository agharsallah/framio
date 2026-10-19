package dev.framio.lab.labs.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuTimesTest {

    // user nice system idle iowait irq softirq steal guest guest_nice
    private val line = "cpu  100 0 50 800 50 0 0 0 0 0"

    @Test
    fun `idle and iowait count as not busy`() {
        assertEquals(CpuTimes(busy = 150, total = 1000), CpuTimes.parse(line))
    }

    @Test
    fun `usage is the busy share of the time between two samples`() {
        val a = CpuTimes(busy = 150, total = 1000)
        val b = CpuTimes(busy = 450, total = 1400)
        assertEquals(0.75f, b.usageSince(a), 1e-6f)
    }

    @Test
    fun `usage is zero when no time passed`() {
        val a = CpuTimes(busy = 150, total = 1000)
        assertEquals(0f, a.usageSince(a), 0f)
    }

    @Test
    fun `per-core lines and garbage are rejected`() {
        assertNull(CpuTimes.parse("cpu0 1 2 3 4 5 6 7"))
        assertNull(CpuTimes.parse("intr 12345"))
    }
}
