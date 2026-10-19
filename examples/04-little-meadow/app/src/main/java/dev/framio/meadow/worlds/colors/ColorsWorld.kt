package dev.framio.meadow.worlds.colors

import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Tuka's Studio: colours, shapes and making things. */
object ColorsWorld {
    val colorHunt = Play(
        id = "colorhunt", title = Txt("Color hunt", "Farbensuche"), friend = Friend.Tuka, icon = "co_icon_hunt",
        about = Txt(
            "Naming colours: red, yellow, blue, green first, then orange, purple, pink, then brown, white, black; 2 to 4 choices, balloons then everyday things",
            "Farben benennen: erst Rot, Gelb, Blau, Grün, dann Orange, Lila, Rosa, dann Braun, Weiß, Schwarz; 2 bis 4 zur Wahl, erst Ballons, dann Alltagsdinge",
        ),
        intro = "colorhunt_intro",
        content = { ColorHunt(it) },
    )

    val sorter = Play(
        id = "sorter", title = Txt("Shape sorter", "Formen-Box"), friend = Friend.Tuka, icon = "co_icon_sorter",
        about = Txt(
            "Matching and naming shapes (circle, square, triangle, star, heart; later rectangle, oval, diamond, hexagon), 3 to 6 per round, drag or tap-then-tap",
            "Formen zuordnen und benennen (Kreis, Quadrat, Dreieck, Stern, Herz; später Rechteck, Oval, Raute, Sechseck), 3 bis 6 pro Runde, ziehen oder antippen",
        ),
        intro = "sorter_intro",
        content = { ShapeSorter(it) },
    )

    val mix = Play(
        id = "mix", title = Txt("Mixing colors", "Farben mischen"), friend = Friend.Tuka, icon = "co_icon_mix", minAge = 3,
        about = Txt(
            "Colour mixing: red, yellow, blue and white (black at the top level) make orange, green, purple, pink, grey and more; no wrong answers",
            "Farben mischen: Rot, Gelb, Blau und Weiß (Schwarz auf der höchsten Stufe) ergeben Orange, Grün, Lila, Rosa, Grau und mehr; nichts ist falsch",
        ),
        intro = "mix_intro",
        content = { MixColors(it) },
    )

    val paint = Play(
        id = "paint", title = Txt("Finger paint", "Fingermalen"), friend = Friend.Tuka, icon = "co_icon_paint",
        about = Txt(
            "Free painting with fingers: colours, rainbow brush, stamps; finished pictures appear in the dashboard gallery",
            "Freies Malen mit den Fingern: Farben, Regenbogenpinsel, Stempel; fertige Bilder erscheinen in der Galerie im Dashboard",
        ),
        music = Track.Studio, showFriend = false, showDots = false,
        content = { FingerPaint(it) },
    )

    val coloring = Play(
        id = "coloring", title = Txt("Coloring pages", "Ausmalbilder"), friend = Friend.Tuka, icon = "co_icon_coloring",
        about = Txt(
            "Colouring six pictures with one tap per part: choosing colours, fine motor control; finished pages go to the gallery",
            "Sechs Bilder ausmalen, ein Tipp pro Fläche: Farben wählen, Feinmotorik; fertige Bilder kommen in die Galerie",
        ),
        intro = "play_coloring", music = Track.Studio, showFriend = false, showDots = false,
        content = { ColoringPages(it) },
    )

    val world = World(
        id = "colors", title = Txt("Tuka's Studio", "Tukas Atelier"), friend = Friend.Tuka, color = Palette.terracotta,
        backdrop = "bg_studio", props = listOf("ob_balloon_blue", "co_shape_star_yellow", "co_pot_red"), intro = "world_colors",
        plays = listOf(colorHunt, sorter, mix, paint, coloring),
    )
}
