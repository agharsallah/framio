package dev.framio.meadow.worlds.numbers

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/** Pip's Nest: counting, how many, more and fewer. */
object NumbersWorld {
    val count = Play(
        id = "count", title = Txt("Count with me", "Zähl mit mir"), friend = Friend.Pip, icon = "ob_apple",
        about = Txt("Counting 1–10, one tap per thing", "Zählen 1–10, ein Tipp pro Ding"),
        content = { CountWithMe(it) },
    )

    val howMany = Play(
        id = "howmany", title = Txt("How many?", "Wie viele?"), friend = Friend.Pip, icon = "ob_duck",
        about = Txt(
            "Seeing small amounts at a glance (subitising) and matching them to dot patterns and numerals; 1–3 at first, up to 10 with numerals only",
            "Kleine Mengen auf einen Blick erfassen und Punktebildern und Ziffern zuordnen; erst 1–3, später bis 10 nur mit Ziffern",
        ),
        intro = "howmany_intro",
        content = { HowMany(it) },
    )

    val feed = Play(
        id = "feed", title = Txt("Feed Pip", "Füttere Pip"), friend = Friend.Pip, icon = "nu_berry",
        about = Txt(
            "Counting out an exact amount (1–3, later up to 10) and putting extras back",
            "Eine genaue Anzahl abzählen (1–3, später bis 10) und zu viele zurücklegen",
        ),
        intro = "feed_intro", showFriend = false,
        content = { FeedPip(it) },
    )

    val more = Play(
        id = "more", title = Txt("More or fewer", "Mehr oder weniger"), friend = Friend.Pip, icon = "nu_basket_back", minAge = 4,
        about = Txt(
            "Comparing two groups: which has more, later which has fewer, with ever smaller differences",
            "Zwei Mengen vergleichen: wo ist mehr, später wo ist weniger, mit immer kleineren Unterschieden",
        ),
        intro = "more_intro",
        content = { MoreOrFewer(it) },
    )

    val world = World(
        id = "numbers", title = Txt("Pip's Nest", "Pips Nest"), friend = Friend.Pip, color = Palette.honey,
        backdrop = "bg_nest", props = listOf("ob_apple", "ob_strawberry", "ob_star"), intro = "world_numbers",
        plays = listOf(count, howMany, feed, more),
    )
}
