package dev.framio.meadow.worlds.quiet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBookTest {

    @Test fun fourStoriesOfSixToNinePages() {
        assertEquals(4, StoryBook.all.size)
        assertEquals(4, StoryBook.all.map { it.id }.toSet().size)
        for (st in StoryBook.all) {
            assertTrue(st.id, st.pages.size in 6..9)
            for (level in 0..4) assertTrue(st.pagesFor(level).size in 6..9)
            assertEquals(st.pages.indices.toList(), st.pagesFor(4))
            assertTrue(st.pagesFor(0).size < st.pages.size) // the little ones get a shorter telling
        }
    }

    @Test fun everyonePlacedInsideThePicture() {
        for (st in StoryBook.all) for (p in st.pages) {
            assertTrue(p.bg.startsWith("bg_"))
            assertTrue(p.actors.isNotEmpty())
            for (a in p.actors) {
                assertTrue(a.x - a.h * 0.6f / 2 > -0.05f && a.x + a.h * 0.6f / 2 < 1.05f)
                assertTrue(a.y in 0.3f..1f && a.y - a.h > 0f)
            }
            for (pr in p.props) assertTrue(pr.y - pr.h > 0f && pr.y <= 1f)
        }
    }

    @Test fun narrationIdsAreStable() {
        val st = StoryBook.pipSleep
        assertEquals("stories_pip_sleep_1", st.narration(0))
        assertEquals("stories_title_pip_sleep", st.titleLine)
        // skipping a page keeps the later pages' ids
        val shown = st.pagesFor(0)
        assertEquals("stories_pip_sleep_9", st.narration(shown.last()))
    }
}
