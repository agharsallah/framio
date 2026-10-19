package dev.framio.pomodoro.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxonomyCodecTest {
    @Test
    fun `the tags file round-trips, normalizes, and garbage reads as null`() {
        val t = Taxonomy().rename(Taxonomy.PHONE, "Mobile").move(Taxonomy.OTHER, 0)
        assertEquals(t, Taxonomy.decode(Taxonomy.encode(t)))
        assertEquals(Taxonomy().tags.size, Taxonomy.decode("""{"v":1,"tags":[]}""")?.tags?.size)
        assertNull(Taxonomy.decode("{"))
        assertNull(Taxonomy.decode(""))
    }
}
