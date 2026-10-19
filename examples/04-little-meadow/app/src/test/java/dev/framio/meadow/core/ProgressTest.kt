package dev.framio.meadow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ProgressTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun levelsAdaptPerActivityAndSurviveARestart() {
        val f = tmp.newFile("progress.json").also { it.delete() }
        val p = Progress(f)
        assertEquals(1, p.level("count", age = 3))
        repeat(3) { p.round("count", firstTry = true, age = 3) }
        assertEquals(2, p.level("count", age = 3))
        assertEquals(1, p.level("howmany", age = 3))
        p.save()
        assertEquals(2, Progress(f).level("count", age = 3))
    }

    @Test fun stickersAreNeverAwardedTwice() {
        val p = Progress(tmp.newFile("p.json").also { it.delete() })
        val pool = listOf("a", "b", "c")
        val got = List(3) { p.award(pool, Random(it)) }
        assertEquals(pool.toSet(), got.toSet())
        assertNull(p.award(pool))
    }

    @Test fun theEventLogIsBounded() {
        val p = Progress(tmp.newFile("q.json").also { it.delete() })
        repeat(200) { p.log("event $it") }
        val events = p.toJson().getJSONArray("events")
        assertEquals(80, events.length())
        assertEquals("event 199", events.getJSONObject(79).getString("text"))
    }

    @Test fun aCorruptFileStartsFresh() {
        val f = tmp.newFile("bad.json").apply { writeText("{not json") }
        assertTrue(Progress(f).stickers().isEmpty())
    }

    @Test fun changingTheAgeStartsLevelsAgain() {
        val p = Progress(tmp.newFile("r.json").also { it.delete() })
        repeat(6) { p.round("trace", firstTry = true, age = 4) }
        assertEquals(4, p.level("trace", age = 4))
        p.resetLevels()
        assertEquals(1, p.level("trace", age = 3))
    }
}
