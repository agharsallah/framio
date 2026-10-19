package dev.framio.meadow.hw

import org.junit.Assert.assertEquals
import org.junit.Test

class KnockDetectorTest {

    /** The frame on its stand: gravity mostly on y, leaning back (see docs/device-log.md). */
    private fun still(d: KnockDetector, from: Long, n: Int): Int =
        (0 until n).count { d.add(from + it * 10L, 0.1f, 9.5f, 3.4f) }

    @Test fun standingStillIsNeverAKnock() {
        assertEquals(0, still(KnockDetector(), 0, 500))
    }

    @Test fun aSpikeIsOneKnockAndTheRefractoryTimeSwallowsItsRinging() {
        val d = KnockDetector()
        still(d, 0, 50)
        var knocks = 0
        for (i in 0 until 10) if (d.add(500L + i * 10, 0.1f + (if (i % 2 == 0) 3f else -3f), 9.5f, 3.4f)) knocks++
        assertEquals(1, knocks)
        still(d, 700, 30)
        if (d.add(1100, 3.5f, 9.5f, 3.4f)) knocks++ // a second knock after the quiet time
        assertEquals(2, knocks)
    }
}
