package dev.framio.meadow.worlds.habits

import dev.framio.meadow.core.PartOfDay
import dev.framio.meadow.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class HabitsLogicTest {

    @Test fun brushingLastsTwoMinutesOrOneForTwoYearOlds() {
        for (age in 2..5) {
            val plan = brushPlan(age)
            assertEquals(if (age == 2) 60 else 120, plan.sumOf { it.seconds })
            assertEquals(MouthArea.entries.toList(), plan.map { it.area })
            assertTrue(plan.all { it.seconds > 0 })
        }
    }

    @Test fun sidesGetMoreTimeThanTheTongue() {
        val plan = brushPlan(4).associate { it.area to it.seconds }
        assertTrue(plan.getValue(MouthArea.TopA) > plan.getValue(MouthArea.Tongue))
        assertTrue(plan.getValue(MouthArea.BottomB) >= plan.getValue(MouthArea.Front))
    }

    @Test fun bugsLeaveOneAfterAnotherAndAllAreGoneAtTheEnd() {
        assertFalse(bugGone(0, 2, 0f))
        assertTrue(bugGone(0, 2, 0.34f))
        assertFalse(bugGone(1, 2, 0.5f))
        assertTrue((0 until 3).all { bugGone(it, 3, 1f) })
        assertEquals(2, bugSpots(MouthArea.TopA, 2).size)
        bugSpots(MouthArea.Tongue, 1).forEach { (x, y) -> assertTrue(x in 0f..1f && y in 0f..1f) }
        // on the 720×450 dp mouth, bugs (74 dp) never sit on top of each other
        val all = MouthArea.entries.flatMap { a -> bugSpots(a, if (a == MouthArea.Tongue) 1 else 2) }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val dx = (all[i].first - all[j].first) * 720f
            val dy = (all[i].second - all[j].second) * 450f
            assertTrue("bugs $i and $j", dx * dx + dy * dy >= 74f * 74f)
        }
    }

    @Test fun flowerOpensWithTime() {
        assertEquals(0, petalsOpen(0, 120, 12))
        assertEquals(6, petalsOpen(60, 120, 12))
        assertEquals(12, petalsOpen(120, 120, 12))
        assertEquals(12, petalsOpen(500, 120, 12))
    }

    @Test fun scrubbingCountsToTwentyOnce() {
        val counts = scrubCounts()
        assertEquals(WashStep.entries.filter { it.scrub }, counts.keys.toList())
        assertEquals((1..20).toList(), counts.values.flatMap { it.toList() })
    }

    @Test fun routineFollowsTheClock() {
        assertTrue(routineIsMorning(PartOfDay.Morning, true))
        assertFalse(routineIsMorning(PartOfDay.Evening, true))
        assertFalse(routineIsMorning(PartOfDay.Afternoon, true))
        assertTrue(routineIsMorning(PartOfDay.Evening, false)) // unsynced clock: start with the morning
    }

    @Test fun routineStepsSkipUnknownAndRepeatedIds() {
        assertEquals(listOf("wake", "brush"), routineSteps(listOf("wake", "nope", "brush", "wake")).map { it.id })
        val d = Settings()
        assertEquals(d.morningRoutine.size, routineSteps(d.morningRoutine).size)
        assertEquals(d.eveningRoutine.size, routineSteps(d.eveningRoutine).size)
    }

    @Test fun routineCardsFitOnOneRow() {
        for (n in 1..10) {
            val (big, small) = routineCardSizes(n)
            assertTrue("n=$n", big + small * (n - 1) + 18f * (n - 1) <= 1180f + 0.5f)
            assertTrue(small >= 84f)
        }
    }

    @Test fun sortingRoundsAlwaysHaveBothKinds() {
        for (level in 0..4) repeat(30) { seed ->
            val foods = sortRound(level, Random(seed))
            assertEquals(foods.size, foods.distinct().size)
            assertTrue(foods.any { it.kind == FoodKind.Everyday })
            assertTrue(foods.any { it.kind == FoodKind.Sometimes })
            assertTrue(foods.size in 4..6)
        }
    }

    @Test fun rainbowTrayHasExactlyOneRightColour() {
        for (level in 0..4) for (color in rainbowColors(level)) repeat(20) { seed ->
            val tray = rainbowChoices(color, level, Random(seed))
            assertEquals(1, tray.count { it.color == color })
            assertEquals(tray.size, tray.map { it.color }.distinct().size)
        }
        assertEquals(Foods.rainbow, rainbowColors(3))
        assertEquals(3, rainbowColors(0).size)
    }

    @Test fun everyRainbowColourHasAFood() {
        Foods.rainbow.forEach { c -> assertTrue(c, Foods.all.any { it.color == c }) }
    }

    @Test fun tidyRoundsGrowAndCoverEveryHome() {
        for (level in 0..4) repeat(20) { seed ->
            val homes = tidyHomes(level)
            val toys = tidyRound(level, Random(seed))
            assertTrue(toys.all { it.home in homes })
            assertTrue(homes.all { h -> toys.any { it.home == h } })
            assertEquals(toys.size, toys.distinct().size)
        }
        val sizes = (0..4).map { tidyRound(it, Random(1)).size }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(sizes.last() > sizes.first())
    }

    @Test fun toysLieOnTheRugAwayFromTheFriend() {
        for (n in 4..8) {
            val spots = tidySpots(n, n)
            assertEquals(n, spots.size)
            spots.forEach { (x, y) ->
                assertTrue(x in 0.2f..0.8f && y in 0.5f..0.9f)
                assertFalse("friend corner", x < 0.2f && y > 0.68f)
            }
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val dx = (spots[i].first - spots[j].first) * 1280f
                val dy = (spots[i].second - spots[j].second) * 688f
                assertTrue("toys $i and $j overlap (n=$n)", dx * dx + dy * dy > 120f * 120f)
            }
        }
    }

    @Test fun weatherRoundsOfferWhatIsNeededAndKindWrongOnes() {
        for (w in Weather.entries) for (level in 0..4) repeat(20) { seed ->
            val r = weatherRound(w, level, Random(seed))
            assertTrue(r.needed.isNotEmpty())
            assertTrue(r.cards.containsAll(r.needed))
            assertEquals(r.cards.size, r.cards.distinct().size)
            assertTrue(r.cards.size <= 5)
            r.cards.filter { it !in r.needed }.forEach { assertTrue(it in WEAR_WRONG.getValue(w)) }
        }
        // nothing is both right and wrong for the same weather
        Weather.entries.forEach { w -> assertTrue(WEAR_NEEDED.getValue(w).none { it in WEAR_WRONG.getValue(w) }) }
        assertEquals("weather_no_snowy_sunhat", wrongLine(Weather.Snowy, Wear.Sunhat))
        assertEquals(4, weatherOrder(Random(1)).distinct().size)
    }

    @Test fun everyLineTheWorldSaysIsRecordedInBothLanguages() {
        fun ids(lang: String): Set<String> {
            val f = listOf(File("../voice/$lang/habits.tsv"), File("voice/$lang/habits.tsv")).first { it.exists() }
            val common = File(f.parentFile, "common.tsv")
            return (f.readLines() + common.readLines()).filter { '\t' in it && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        }
        val needed = buildList {
            MouthArea.entries.forEach { add(it.line) }
            WashStep.entries.forEach { add(it.line) }
            RoutineSteps.all.forEach { add("routine_${it.id}"); add("routine_${it.id}_done") }
            Foods.all.forEach { add("name_${it.id}") }
            Foods.rainbow.forEach { add("plate_find_$it") }
            Toys.all.forEach { add("tidy_${it.id}") }
            (1..3).forEach { add("tidy_done_$it") }
            Weather.entries.forEach { w ->
                val wid = w.name.lowercase()
                add("weather_$wid"); add("weather_done_$wid")
                WEAR_WRONG.getValue(w).forEach { add(wrongLine(w, it)) }
            }
            Wear.entries.forEach { add("weather_on_${it.id}") }
            listOf("brush", "wash", "routine", "plate", "tidy", "weather").forEach { add("play_$it") }
            (1..20).forEach { add("num_$it") }
        }
        for (lang in listOf("en", "de")) {
            val have = ids(lang)
            assertEquals(lang, emptyList<String>(), needed.filter { it !in have })
        }
    }
}
