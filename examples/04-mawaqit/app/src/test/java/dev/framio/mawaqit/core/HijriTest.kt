package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Expected values generated with Mawaqit's Python client algorithm (kuwaiti / from_gregorian).
class HijriTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun kuwaitiMatchesPythonClient() {
        val cases = mapOf(
            "2026-10-18" to HijriDate(1448, 5, 7),
            "2000-01-01" to HijriDate(1420, 9, 25),
            "1970-01-01" to HijriDate(1389, 10, 23),
            "2023-07-19" to HijriDate(1445, 1, 2),
            "2030-12-31" to HijriDate(1452, 9, 7),
            // Month and year boundaries.
            "2024-03-10" to HijriDate(1445, 9, 1),
            "2024-03-11" to HijriDate(1445, 9, 2),
            "2025-02-28" to HijriDate(1446, 9, 1),
            "2025-03-01" to HijriDate(1446, 9, 2),
            "2026-02-17" to HijriDate(1447, 9, 1),
            "2026-02-18" to HijriDate(1447, 9, 2),
            "2026-06-15" to HijriDate(1447, 12, 30),
            "2026-06-16" to HijriDate(1448, 1, 1),
        )
        for ((date, expected) in cases) {
            assertEquals(date, expected, Hijri.kuwaiti(d(date)))
            assertEquals(date, expected, Hijri.of(d(date)))
        }
    }

    @Test
    fun adjustmentShiftsTheGregorianDay() {
        assertEquals(HijriDate(1448, 5, 8), Hijri.of(d("2026-10-18"), adjustment = 1))
        assertEquals(HijriDate(1448, 5, 5), Hijri.of(d("2026-10-18"), adjustment = -2))
    }

    @Test
    fun force30AsInPythonClient() {
        assertEquals(HijriDate(1448, 5, 30), Hijri.of(d("2026-10-18"), force30 = true))
        // Day 1 becomes the 30th of the previous month, across the year boundary too.
        assertEquals(HijriDate(1447, 7, 30), Hijri.of(d("2026-01-19"), force30 = true))
        assertEquals(HijriDate(1447, 8, 30), Hijri.of(d("2026-02-17"), force30 = true))
        assertEquals(HijriDate(1447, 9, 30), Hijri.of(d("2026-03-19"), force30 = true))
        assertEquals(HijriDate(1447, 12, 30), Hijri.of(d("2026-06-16"), force30 = true))
    }

    @Test
    fun ramadan() {
        assertTrue(Hijri.isRamadan(d("2026-02-17")))
        assertFalse(Hijri.isRamadan(d("2026-02-16")))
        assertTrue(Hijri.isRamadan(d("2026-02-16"), adjustment = 1))
    }

    @Test
    fun monthNames() {
        assertEquals("Rabi' al-Thani", Hijri.monthName(4, "en"))
        assertEquals("ربيع الآخر", Hijri.monthName(4, "ar"))
        assertEquals("Ramazan", Hijri.monthName(9, "tr"))
        assertEquals("Chaabane", Hijri.monthName(8, "fr"))
        assertEquals("Muharram", Hijri.monthName(1, "xx"))
        for (lang in listOf("en", "ar", "fr", "de", "nl", "tr")) for (m in 1..12) assertTrue(Hijri.monthName(m, lang).isNotBlank())
    }

    @Test
    fun format() {
        assertEquals("26 Rabi' al-Thani 1448", Hijri.format(HijriDate(1448, 4, 26), "en"))
        assertEquals("٢٦ ربيع الآخر ١٤٤٨ هـ", Hijri.format(HijriDate(1448, 4, 26), "ar"))
        assertEquals("7 Joumada al Oula 1448", Hijri.format(Hijri.of(d("2026-10-18")), "fr"))
    }
}
