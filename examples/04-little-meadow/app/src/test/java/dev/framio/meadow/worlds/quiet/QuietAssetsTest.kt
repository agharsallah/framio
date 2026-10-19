package dev.framio.meadow.worlds.quiet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every line and sprite the quiet world asks for exists, in English and German. */
class QuietAssetsTest {

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "voice").isDirectory }

    private fun ids(lang: String): Set<String> = File(root(), "voice/$lang").listFiles { f -> f.name.endsWith(".tsv") }!!
        .flatMap { it.readLines() }
        .filter { it.isNotBlank() && !it.startsWith("#") && "\t" in it }
        .map { it.substringBefore("\t").trim() }
        .toSet()

    private fun neededLines(): List<String> {
        val out = mutableListOf("play_breathe", "play_feelings", "play_stories", "play_nightlight")
        out += listOf("breathe_intro", "breathe_pick", "breathe_end", "breathe_in", "breathe_out")
        for (p in BreathPic.entries) out += listOf("breathe_name_${p.id}", "breathe_${p.id}_start", "breathe_${p.id}_in", "breathe_${p.id}_out")
        out += feelingsVoiceIds()
        out += listOf("stories_intro", "stories_pick", "stories_end")
        for (st in StoryBook.all) {
            out += st.titleLine
            out += st.pages.indices.map { st.narration(it) }
        }
        out += listOf("nightlight_hello", "nightlight_gn_you", "nightlight_shh", "nightlight_shh_2")
        out += listOf("pip", "tuka", "bo", "moss").map { "nightlight_gn_$it" }
        return out
    }

    @Test fun everyLineInBothLanguages() {
        val en = ids("en")
        val de = ids("de")
        for (id in neededLines()) {
            assertTrue("en: $id", id in en)
            assertTrue("de: $id", id in de)
        }
    }

    @Test fun everySpriteExists() {
        val dir = File(root(), "app/src/main/assets/sprites")
        val names = mutableSetOf("qt_candle", "qt_candlelit", "qt_balloon", "qt_hand", "qt_branch", "bg_qt_sky", "bg_qt_dusk", "ob_flower")
        for (f in FEELING_FRIENDS) for (fe in Feeling.entries) names += feelingSprite(f, fe)
        for (st in StoryBook.all) {
            names += st.coverBg
            for (p in st.pages) {
                names += p.bg
                names += p.props.map { it.sprite }
            }
        }
        for (s in SCENARIOS) names += s.props.map { it.sprite }
        names += Helper.entries.map { it.sprite }
        for (n in names) assertTrue("missing sprite $n", File(dir, "$n.webp").exists())
    }
}
