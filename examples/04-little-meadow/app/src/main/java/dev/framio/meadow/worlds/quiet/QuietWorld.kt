package dev.framio.meadow.worlds.quiet

import dev.framio.meadow.NIGHT_LIGHT
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Luma's Quiet Tree: breathing, feelings, stories and the night light. Open at bedtime too. */
object QuietWorld {
    val breathe = Play(
        id = "breathe", title = Txt("Breathe with Luma", "Atmen mit Luma"), friend = Friend.Luma, icon = "qt_candlelit",
        about = Txt(
            "Calm breathing, 5 slow breaths (about 4 s in, 5 s out): flower and candle, balloon belly or starfish hand",
            "Ruhig atmen, 5 langsame Atemzüge (etwa 4 s ein, 5 s aus): Blume und Kerze, Ballonbauch oder Seesternhand",
        ),
        intro = "breathe_intro", backdrop = "bg_qt_dusk", music = Track.Calm,
        showFriend = false, showDots = false, quiet = true, countsAsScreenTime = false,
        content = { Breathe(it) },
    )

    val feelings = Play(
        id = "feelings", title = Txt("Feelings", "Gefühle"), friend = Friend.Luma, icon = "qt_tuka_happy",
        about = Txt(
            "Naming feelings (happy, sad, angry, scared, then surprised, sleepy, proud, calm) and what helps: hug, talk, breathe, rest",
            "Gefühle benennen (fröhlich, traurig, wütend, ängstlich, dann überrascht, müde, stolz, ruhig) und was hilft: Umarmen, Reden, Atmen, Ausruhen",
        ),
        intro = "feelings_intro", backdrop = "bg_qt_dusk", music = Track.Calm, quiet = true,
        content = { Feelings(it) },
    )

    val stories = Play(
        id = "stories", title = Txt("Stories", "Geschichten"), friend = Friend.Luma, icon = "ob_book",
        about = Txt(
            "Four narrated picture stories: a bedtime routine, sharing, patience, naming fear and calming down",
            "Vier vorgelesene Bildergeschichten: Abendroutine, Teilen, Geduld, Angst benennen und ruhig werden",
        ),
        intro = "stories_intro", backdrop = "bg_qt_dusk", music = Track.Night, showFriend = false, quiet = true,
        content = { Stories(it) },
    )

    val nightLight = Play(
        id = NIGHT_LIGHT, title = Txt("Night light", "Nachtlicht"), friend = Friend.Luma, icon = "ob_moon",
        about = Txt(
            "A dark starry sky with a goodnight ritual; fades to dark over the set minutes, then turns the screen off",
            "Ein dunkler Sternenhimmel mit Gute-Nacht-Ritual; wird in den eingestellten Minuten dunkel und schaltet dann den Bildschirm aus",
        ),
        backdrop = "bg_qt_sky", music = Track.Night,
        showFriend = false, showDots = false, quiet = true, countsAsScreenTime = false, captions = false,
        content = { NightLight(it) },
    )

    val world = World(
        id = "quiet", title = Txt("Quiet Tree", "Stiller Baum"), friend = Friend.Luma, color = Palette.lavender,
        backdrop = "bg_night", props = listOf("ob_moon", "ob_star", "ob_book"), intro = "world_quiet", quiet = true,
        plays = listOf(breathe, feelings, stories, nightLight),
    )
}
