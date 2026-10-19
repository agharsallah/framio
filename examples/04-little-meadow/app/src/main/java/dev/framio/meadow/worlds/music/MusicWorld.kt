package dev.framio.meadow.worlds.music

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Bo's Music Corner: play, listen, keep the beat. Every activity here makes its own sound, so no music track. */
object MusicWorld {
    val xylo = Play(
        id = "xylo", title = Txt("Xylophone", "Xylofon"), friend = Friend.Bo, icon = "mu_xylo",
        about = Txt(
            "Free play on an eight-note xylophone (marimba, bells or music box) and playing five simple songs note by note, then hearing them in rhythm",
            "Freies Spiel auf einem Xylofon mit acht Tönen (Marimba, Glocken oder Spieluhr) und fünf einfache Lieder Ton für Ton nachspielen und dann im Rhythmus hören",
        ),
        intro = "xylo_intro", music = null,
        content = { Xylophone(it) },
    )

    val bubbles = Play(
        id = "bubbles", title = Txt("Bubble notes", "Blasenmusik"), friend = Friend.Bo, icon = "mu_bubbles",
        about = Txt(
            "Cause and effect and hand–eye coordination: popping slow bubbles plays gentle notes; some carry things that are named aloud",
            "Ursache und Wirkung, Auge-Hand-Koordination: langsame Blasen platzen mit sanften Tönen, manche tragen Dinge, die benannt werden",
        ),
        intro = "bubbles_intro", music = null, backdrop = "bg_meadow",
        content = { BubbleNotes(it) },
    )

    val echo = Play(
        id = "echo", title = Txt("Echo drums", "Echo-Trommeln"), friend = Friend.Bo, icon = "mu_drum_big", minAge = 3,
        about = Txt(
            "Listening and auditory memory: repeating Bo's patterns of 2 to 5 sounds on two or three drums",
            "Zuhören und Hörgedächtnis: Bos Muster aus 2 bis 5 Klängen auf zwei oder drei Trommeln nachspielen",
        ),
        intro = "echo_intro", music = null,
        content = { EchoDrums(it) },
    )

    val world = World(
        id = "music", title = Txt("Music Corner", "Musikecke"), friend = Friend.Bo, color = Palette.mint,
        backdrop = "bg_music", props = listOf("mu_xylo", "mu_drum_big", "mu_bell"), intro = "world_music",
        plays = listOf(xylo, bubbles, echo),
    )
}
