package dev.framio.mawaqit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    @Test
    fun scriptsTypedTogetherGetASeparator() {
        // The flash message of Moschee Tarik ben Ziad, Frankfurt, as published
        val raw = "تقام صلاة العشاء في المسجد مباشرة بعد آذان صلاة العشاءDas Ishaa Gebet findet in der Moschee direkt nach dem Adahn statt"
        assertEquals(
            "تقام صلاة العشاء في المسجد مباشرة بعد آذان صلاة العشاء  ·  Das Ishaa Gebet findet in der Moschee direkt nach dem Adahn statt",
            separateScripts(raw),
        )
        assertEquals("Dhuhr  ·  الظهر", separateScripts("Dhuhrالظهر"))
        assertEquals("Iftar à 19:00 · الإفطار", separateScripts("Iftar à 19:00 · الإفطار")) // already apart: unchanged
    }

    @Test
    fun arabicLayoutOnlyWhenMostLettersAreArabic() {
        assertTrue(hasArabic("الدعاء لا يرد بين الأذان والإقامة"))
        assertFalse(hasArabic("Jabir (رَضِيَ اللهُ عَنْهُ) said: The Prophet (صَلَّى اللهُ عَلَيْهِ وَسَلَّمَ) said, every one will be raised in the condition in which he dies."))
        assertFalse(hasArabic("12:30 · 13:45"))
    }
}
