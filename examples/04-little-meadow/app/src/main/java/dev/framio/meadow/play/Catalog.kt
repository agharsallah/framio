package dev.framio.meadow.play

import dev.framio.meadow.worlds.colors.ColorsWorld
import dev.framio.meadow.worlds.habits.HabitsWorld
import dev.framio.meadow.worlds.music.MusicWorld
import dev.framio.meadow.worlds.numbers.NumbersWorld
import dev.framio.meadow.worlds.puzzles.PuzzlesWorld
import dev.framio.meadow.worlds.quiet.QuietWorld
import dev.framio.meadow.worlds.words.WordsWorld

/** Every world, in the order they stand along the meadow on the home screen. */
object Catalog {
    val worlds: List<World> by lazy {
        listOf(NumbersWorld.world, ColorsWorld.world, WordsWorld.world, MusicWorld.world, HabitsWorld.world, PuzzlesWorld.world, QuietWorld.world)
    }

    fun world(id: String): World? = worlds.firstOrNull { it.id == id }
    fun play(id: String): Play? = worlds.firstNotNullOfOrNull { w -> w.plays.firstOrNull { it.id == id } }
    fun worldOf(playId: String): World? = worlds.firstOrNull { w -> w.plays.any { it.id == playId } }
    val allPlays: List<Play> get() = worlds.flatMap { it.plays }
}

/** Stickers the sticker book can hold: every finished activity adds one the child doesn't have. */
object Stickers {
    val all = listOf(
        "ob_apple", "ob_pear", "ob_banana", "ob_orange", "ob_lemon", "ob_strawberry", "ob_cherry", "ob_grapes", "ob_carrot",
        "ob_broccoli", "ob_tomato", "ob_cupcake", "ob_cookie", "ob_icecream", "ob_watermelon", "ob_cheese",
        "ob_flower", "ob_mushroom", "ob_sun", "ob_moon", "ob_cloud", "ob_rainbow", "ob_star", "ob_heart", "ob_tree",
        "ob_snail", "ob_butterfly", "ob_ball", "ob_block", "ob_teddy", "ob_car", "ob_boat", "ob_kite", "ob_drum",
        "ob_rocket", "ob_duck", "ob_fish", "ob_bell", "ob_gift", "ob_crown", "ob_umbrella", "ob_hat",
        "ob_balloon_red", "ob_balloon_blue", "ob_balloon_yellow", "ob_balloon_green", "ob_balloon_purple", "ob_balloon_pink",
    )
}
