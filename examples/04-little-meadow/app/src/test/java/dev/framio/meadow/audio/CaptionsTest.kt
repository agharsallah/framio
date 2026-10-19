package dev.framio.meadow.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsTest {

    private fun join(vararg pieces: CaptionPiece) = CaptionText.join(pieces.toList())
    private fun piece(text: String, ms: Int = 1000) = CaptionPiece(text, ms)

    // ---- manifest

    @Test fun manifestCarriesDurationAndText() {
        val m = parseManifest("""{"praise_1":{"h":"f9b6c42c39","ms":822,"t":"Well done!"},"abc_a":{"h":"1","ms":1275,"t":"„A“… „A“ wie Apfel!"}}""")
        assertEquals(Clip(822, "Well done!"), m["praise_1"])
        assertEquals("„A“… „A“ wie Apfel!", m["abc_a"]?.text)
    }

    @Test fun anOlderManifestWithoutTextStillLoads() {
        val m = parseManifest("""{"praise_1":{"h":"f9b6c42c39","ms":822}}""")
        assertEquals(Clip(822, ""), m["praise_1"])
    }

    // ---- joining one say()

    @Test fun clipsOfOneSayJoinIntoOneSentence() {
        val (text, parts) = join(piece("I'd like"), piece("three"), piece("berries, please!"))
        assertEquals("I'd like three berries, please!", text)
        assertEquals(listOf("I'd like", "three", "berries, please!"), parts.map { text.substring(it.start, it.end) })
    }

    @Test fun aNameJoinsTheGreetingBeforeIt() {
        val (text, parts) = join(piece("Good morning!"), CaptionPiece("Mia", 0, isName = true), piece("What shall we play today?"))
        assertEquals("Good morning, Mia! What shall we play today?", text)
        assertEquals("Good morning", text.substring(parts[0].start, parts[0].end))
        assertEquals(", Mia!", text.substring(parts[1].start, parts[1].end))
        assertEquals("What shall we play today?", text.substring(parts[2].start, parts[2].end))
    }

    @Test fun aNameWorksInGermanAndOnItsOwn() {
        assertEquals("Guten Morgen, Ben! Was wollen wir heute spielen?",
            join(piece("Guten Morgen!"), CaptionPiece("Ben", 0, isName = true), piece("Was wollen wir heute spielen?")).first)
        assertEquals("Ben!", join(CaptionPiece("Ben", 0, isName = true)).first)
    }

    @Test fun piecesAreTrimmed() {
        assertEquals("Can you find the letter “A”!", join(piece(" Can you find the letter "), piece("“A”!")).first)
    }

    // ---- word timing

    @Test fun wordsStartInOrderWithinTheClip() {
        val text = "Let's count! Tap each one."
        val w = CaptionText.words(text, CaptionPart(0, text.length, 2000))
        assertEquals(listOf("Let's", "count!", "Tap", "each", "one."), w.map { text.substring(it.start, it.end) })
        assertEquals(0, w.first().atMs)
        assertTrue("strictly increasing", w.zipWithNext().all { (a, b) -> b.atMs > a.atMs })
        assertTrue("the last word starts before the clip ends", w.last().atMs < 2000 - 160)
    }

    @Test fun longerWordsTakeLongerAndFullStopsPause() {
        val text = "a wonderful, big day"
        val w = CaptionText.words(text, CaptionPart(0, text.length, 3000))
        val a = w[1].atMs - w[0].atMs          // "a"
        val wonderful = w[2].atMs - w[1].atMs  // "wonderful," + a comma pause
        val big = w[3].atMs - w[2].atMs        // "big"
        assertTrue(wonderful > big && big > a)
    }

    @Test fun wordsOfALaterPartAreTimedFromItsOwnClip() {
        val (text, parts) = join(piece("That's the circle.", 1500), piece("Where's the star's home?", 1800))
        val w = CaptionText.words(text, parts[1])
        assertEquals("Where's", text.substring(w[0].start, w[0].end))
        assertEquals(0, w[0].atMs)
        assertEquals(parts[1].end, w.last().end)
    }

    @Test fun aSilentPieceLightsUpAtOnce() {
        val text = "A voice message from Grandma"
        assertTrue(CaptionText.words(text, CaptionPart(0, text.length, 0)).all { it.atMs == 0 })
        assertTrue(CaptionText.words("   ", CaptionPart(0, 3, 500)).isEmpty())
    }

    // ---- pages

    @Test fun aShortLineIsOnePage() {
        assertEquals(listOf(0 until 10), CaptionText.pages("Well done!", 90))
    }

    @Test fun longLinesBreakAfterWholeSentences() {
        val text = "It was night in the meadow. The moon was up, and the stars were twinkling. But Pip could not sleep."
        val pages = CaptionText.pages(text, 60).map { text.substring(it) }
        assertEquals(listOf("It was night in the meadow.", "The moon was up, and the stars were twinkling.", "But Pip could not sleep."), pages)
    }

    @Test fun sentencesShareAPageWhenTheyFit() {
        val text = "Let's count! Tap each one. Then tap the big number at the end of the row."
        val pages = CaptionText.pages(text, 30).map { text.substring(it) }
        assertEquals("Let's count! Tap each one.", pages[0])
        assertTrue(pages.all { it.length <= 30 })
    }

    @Test fun aLongSentenceBreaksAtACommaThenBetweenWords() {
        val text = "When you've done it, tap the picture and it gets a gold star."
        assertEquals("When you've done it,", text.substring(CaptionText.pages(text, 40)[0]))
        val words = "one two three four five six seven eight nine ten eleven twelve"
        val pages = CaptionText.pages(words, 20)
        assertTrue(pages.all { words.substring(it).length <= 20 })
        assertEquals(words, pages.joinToString(" ") { words.substring(it) })
    }

    @Test fun closingQuotesStayWithTheirSentence() {
        val text = "\"Here, Bo,\" said Tuka. \"Would you like some berries?\" Bo smiled a big smile. \"Thank you, Tuka!\""
        val pages = CaptionText.pages(text, 60).map { text.substring(it) }
        assertEquals("\"Here, Bo,\" said Tuka. \"Would you like some berries?\"", pages[0])
        assertEquals(text, pages.joinToString(" "))
    }

    @Test fun pageAtFollowsTheSpokenPosition() {
        val text = "It was night in the meadow. The moon was up, and the stars were twinkling."
        val pages = CaptionText.pages(text, 40)
        assertEquals(0, CaptionText.pageAt(pages, 0))
        assertEquals(1, CaptionText.pageAt(pages, text.indexOf("moon")))
        assertEquals(pages.lastIndex, CaptionText.pageAt(pages, text.length))
    }

    // ---- the rest

    @Test fun longerLinesStayUpLongerWithinLimits() {
        assertEquals(1500L, CaptionText.lingerMs("Yes!"))
        assertTrue(CaptionText.lingerMs("x".repeat(100)) > 1500L)
        assertEquals(3200L, CaptionText.lingerMs("x".repeat(400)))
    }

    @Test fun familyMessagesAreLabelledInTheFramesLanguage() {
        assertEquals("A voice message from Grandma", CaptionText.messageFrom("Grandma", german = false))
        assertEquals("Eine Sprachnachricht von Oma", CaptionText.messageFrom("Oma", german = true))
    }
}
