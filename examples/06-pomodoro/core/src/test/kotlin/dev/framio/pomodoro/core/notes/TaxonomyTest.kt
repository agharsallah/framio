package dev.framio.pomodoro.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxonomyTest {
    private val t = Taxonomy()

    @Test
    fun `defaults are the eight pulls on their own palette slots, then Other, then two end-only reasons`() {
        val any = t.offered(TagScope.Any)
        assertEquals(
            listOf("Phone", "Person", "Noise", "Hunger/thirst", "Tired", "Wandering mind", "Urgent task", "Tool/tech", "Other"),
            any.map { it.label },
        )
        assertEquals((0..7).toList(), any.take(8).map { it.hue })
        assertEquals(ReasonTag.NEUTRAL, any.last().hue)
        assertEquals(any.map { it.id } + listOf(Taxonomy.DONE, Taxonomy.LEAVE), t.offered(TagScope.EndOnly).map { it.id })
        assertTrue(t.tags.all { it.builtIn })
        assertEquals(t.tags.size, t.tags.map { it.icon }.toSet().size) // every default has its own glyph
    }

    @Test
    fun `rename trims and caps, and ignores a blank name`() {
        assertEquals("Slack", t.rename(Taxonomy.TECH, "  Slack  ").label(Taxonomy.TECH))
        assertEquals(Taxonomy.MAX_LABEL, t.rename(Taxonomy.TECH, "x".repeat(99)).label(Taxonomy.TECH).length)
        assertEquals(t, t.rename(Taxonomy.TECH, "   "))
    }

    @Test
    fun `hidden tags are no longer offered but keep their label for history`() {
        val h = t.setHidden(Taxonomy.NOISE, true)
        assertFalse(h.offered(TagScope.Any).any { it.id == Taxonomy.NOISE })
        assertEquals("Noise", h.label(Taxonomy.NOISE))
    }

    @Test
    fun `a new tag goes before Other, gets a fresh id, and a duplicate label is refused`() {
        val (t2, id) = t.add("Email", TagIcon.Book, 3)!!
        assertEquals(TagId("u-email"), id)
        val labels = t2.offered(TagScope.Any).map { it.label }
        assertEquals(labels.indexOf("Other") - 1, labels.indexOf("Email"))
        assertNull(t2.add("EMAIL"))
        assertNull(t2.add("  "))
        val (t3, id2) = t2.rename(id, "Mail").add("email")!!
        assertEquals(TagId("u-email-2"), id2) // ids are never reused for another meaning
        assertEquals(t2.tags.size + 1, t3.tags.size)
    }

    @Test
    fun `adding a hidden label shows it again instead of duplicating it`() {
        val (t2, id) = t.setHidden(Taxonomy.TIRED, true).add("tired")!!
        assertEquals(Taxonomy.TIRED, id)
        assertEquals(t, t2)
    }

    @Test
    fun `the taxonomy is capped`() {
        var x = t
        repeat(Taxonomy.MAX_TAGS - t.tags.size) { i -> x = x.add("Tag $i")!!.first }
        assertNull(x.add("One too many"))
    }

    @Test
    fun `move reorders and clamps`() {
        assertEquals(Taxonomy.OTHER, t.move(Taxonomy.OTHER, 0).tags.first().id)
        assertEquals(Taxonomy.PHONE, t.move(Taxonomy.PHONE, 99).tags.last().id)
        assertEquals(t, t.move(TagId("nope"), 0))
    }

    @Test
    fun `restyle keeps hues in the palette`() {
        assertEquals(TagIcon.Leaf to 6, t.restyle(Taxonomy.NOISE, TagIcon.Leaf, 6).tag(Taxonomy.NOISE)?.let { it.icon to it.hue })
        assertEquals(ReasonTag.NEUTRAL, t.restyle(Taxonomy.NOISE, hue = 12).tag(Taxonomy.NOISE)?.hue)
    }

    @Test
    fun `normalized repairs duplicates, bad hues, blank labels and missing built-ins`() {
        val broken = Taxonomy(
            1,
            listOf(
                ReasonTag(Taxonomy.PHONE, "Mobile", hue = 0),
                ReasonTag(Taxonomy.PHONE, "dup", hue = 1),
                ReasonTag(TagId("u-x"), "  ", hue = 42),
                ReasonTag(Taxonomy.DONE, "Done!", scope = TagScope.Any),
            ),
        ).normalized()
        assertEquals("Mobile", broken.label(Taxonomy.PHONE)) // renames survive
        assertEquals(1, broken.tags.count { it.id == Taxonomy.PHONE })
        assertEquals("u-x", broken.tag(TagId("u-x"))?.label)
        assertEquals(ReasonTag.NEUTRAL, broken.tag(TagId("u-x"))?.hue)
        assertEquals(TagScope.EndOnly, broken.tag(Taxonomy.DONE)?.scope) // a built-in's scope can't change
        assertTrue(Taxonomy.Defaults.all { d -> broken.tag(d.id) != null })
        assertFalse(broken.tag(TagId("u-x"))?.builtIn ?: true)
    }

    @Test
    fun `serializes, and reads icons and scopes from a newer version as defaults`() {
        val json = JournalCodec.json
        assertEquals(t, json.decodeFromString(Taxonomy.serializer(), json.encodeToString(Taxonomy.serializer(), t)))
        val future = """{"v":2,"tags":[{"id":"u-a","label":"A","icon":"rocket","scope":"sometimes","hue":3,"glow":true}]}"""
        val read = json.decodeFromString(Taxonomy.serializer(), future)
        assertEquals(TagIcon.Dots, read.tags.single().icon)
        assertEquals(TagScope.Any, read.tags.single().scope)
    }

    @Test
    fun `an unknown tag id still gets a readable label`() {
        assertEquals("standing desk", t.label(TagId("u-standing-desk")))
    }
}
