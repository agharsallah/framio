package dev.framio.meadow.worlds.words

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Bo's Word Room: letters, tracing, first sounds, animals and their sounds. */
object WordsWorld {
    val abc = Play(
        id = "abc", title = Txt("ABC garden", "ABC-Garten"), friend = Friend.Bo, icon = "wo_blocks",
        about = Txt(
            "Letter names A–Z and a word for each (\"A is for apple\"); from level 3 also finding a named letter",
            "Buchstaben A–Z mit je einem Wort (\"A wie Apfel\"); ab Stufe 3 auch einen genannten Buchstaben finden",
        ),
        intro = "abc_intro", backdrop = "bg_meadow",
        content = { AbcGarden(it) },
    )

    val trace = Play(
        id = "trace", title = Txt("Trace", "Nachspuren"), friend = Friend.Bo, icon = "wo_crayon", minAge = 3,
        about = Txt(
            "Pre-writing: lines, circle, zig-zag and waves at 3; uppercase letters in writing order from 4, simple ones first",
            "Vorschreiben: Linien, Kreis, Zickzack und Wellen mit 3; Großbuchstaben in Schreibrichtung ab 4, einfache zuerst",
        ),
        intro = "trace_intro", music = null,
        content = { Trace(it) },
    )

    val firstSound = Play(
        id = "firstsound", title = Txt("First sounds", "Erste Laute"), friend = Friend.Bo, icon = "wo_bee_happy", minAge = 4,
        about = Txt(
            "Hearing the first sound of a word (phonemic awareness) and matching it to its letter",
            "Den Anfangslaut eines Wortes hören (phonologische Bewusstheit) und dem Buchstaben zuordnen",
        ),
        intro = "firstsound_intro",
        content = { FirstSounds(it) },
    )

    val animals = Play(
        id = "animals", title = Txt("Animal friends", "Tierfreunde"), friend = Friend.Bo, icon = "wo_cow_happy",
        about = Txt(
            "Farm animals, their names and the sounds they make; listening and matching",
            "Bauernhoftiere, ihre Namen und Laute; zuhören und zuordnen",
        ),
        intro = "animals_intro", backdrop = "bg_wo_farm",
        content = { AnimalFriends(it) },
    )

    val world = World(
        id = "words", title = Txt("Bo's Word Room", "Bos Wörterzimmer"), friend = Friend.Bo, color = Palette.sky,
        backdrop = "bg_room", props = listOf("ob_book", "ob_apple", "ob_duck"), intro = "world_words",
        plays = listOf(abc, trace, firstSound, animals),
    )
}
