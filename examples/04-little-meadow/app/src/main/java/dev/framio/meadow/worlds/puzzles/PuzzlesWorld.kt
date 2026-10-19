package dev.framio.meadow.worlds.puzzles

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Pip's Puzzle Path: matching, memory, jigsaws, patterns and 3D shapes. */
object PuzzlesWorld {
    val memory = Play(
        id = "memory", title = Txt("Memory match", "Memory"), friend = Friend.Pip, icon = "pz_card_back",
        about = Txt(
            "Memory and attention: turn two cards, find the pairs (4 to 12 cards)",
            "Gedächtnis und Aufmerksamkeit: zwei Karten umdrehen, Paare finden (4 bis 12 Karten)",
        ),
        intro = "memory_intro",
        content = { MemoryMatch(it) },
    )

    val jigsaw = Play(
        id = "jigsaw", title = Txt("Jigsaw", "Puzzle"), friend = Friend.Pip, icon = "pz_icon_jigsaw",
        about = Txt(
            "Spatial thinking and fine motor skills: picture puzzles from 2 to 9 pieces",
            "Räumliches Denken und Feinmotorik: Bilderpuzzles mit 2 bis 9 Teilen",
        ),
        intro = "jigsaw_intro",
        content = { Jigsaw(it) },
    )

    val shadows = Play(
        id = "shadows", title = Txt("Shadow match", "Schattenspiel"), friend = Friend.Pip, icon = "pz_icon_shadow",
        about = Txt(
            "Visual discrimination: match things to their outlines, later look-alikes",
            "Visuelle Wahrnehmung: Dinge ihren Umrissen zuordnen, später ähnliche Formen",
        ),
        intro = "shadows_intro",
        content = { ShadowMatch(it) },
    )

    val patterns = Play(
        id = "patterns", title = Txt("What comes next?", "Was kommt als Nächstes?"), friend = Friend.Bo, icon = "pz_train_engine",
        minAge = 3,
        about = Txt(
            "Early maths: continue patterns (AB, AAB, ABC, ABB, AABB) on a little train",
            "Frühe Mathematik: Muster fortsetzen (AB, AAB, ABC, ABB, AABB) auf einem kleinen Zug",
        ),
        intro = "patterns_intro",
        content = { Patterns(it) },
    )

    val shapes3d = Play(
        id = "shapes3d", title = Txt("Spin the shapes", "Formen drehen"), friend = Friend.Pip, icon = "pz_icon_shapes",
        minAge = 3, stillFriend = true,
        about = Txt(
            "3D shapes (sphere, cube, cylinder, cone, pyramid, torus): spin them, then find them, with everyday examples",
            "3D-Formen (Kugel, Würfel, Zylinder, Kegel, Pyramide, Ring): drehen, dann wiederfinden, mit Beispielen aus dem Alltag",
        ),
        intro = "shapes3d_intro",
        content = { SpinShapes(it) },
    )

    val world = World(
        id = "puzzles", title = Txt("Puzzle Path", "Rätselweg"), friend = Friend.Pip, color = Palette.rose,
        backdrop = "bg_path", props = listOf("ob_block", "ob_gift", "ob_heart"), intro = "world_puzzles",
        plays = listOf(memory, jigsaw, shadows, patterns, shapes3d),
    )
}
