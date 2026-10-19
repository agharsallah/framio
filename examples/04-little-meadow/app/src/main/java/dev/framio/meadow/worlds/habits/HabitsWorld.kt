package dev.framio.meadow.worlds.habits

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Palette

/**
 * Moss's Garden: good habits for every day, with Moss the slow, kind turtle. The activities speak
 * their own introduction once the title has been said (see awaitQuiet), so [Play.intro] stays null.
 */
object HabitsWorld {
    val brush = Play(
        id = "brush", title = Txt("Brush teeth with Moss", "Zähneputzen mit Moos"), friend = Friend.Moss, icon = "ob_toothbrush",
        about = Txt(
            "A real two-minute guided brush (one minute at age 2): top, bottom, front and tongue. Tip: brush along together with a pea-sized bit of fluoride toothpaste.",
            "Echtes, angeleitetes Zähneputzen, zwei Minuten (mit 2 Jahren eine): oben, unten, vorne und die Zunge. Tipp: Putzen Sie mit, mit einer erbsengroßen Menge Fluorid-Zahnpasta.",
        ),
        music = null, showDots = false, countsAsScreenTime = false,
        content = { BrushTeeth(it) },
    )

    val wash = Play(
        id = "wash", title = Txt("Wash hands", "Hände waschen"), friend = Friend.Moss, icon = "ob_soap",
        about = Txt(
            "Hand washing step by step with a 20-second scrub: palms, backs, between fingers, thumbs, nails. Tip: wash along at the sink and count out loud together.",
            "Händewaschen Schritt für Schritt mit 20 Sekunden Einseifen: Handflächen, Handrücken, Fingerzwischenräume, Daumen, Nägel. Tipp: Am Waschbecken mitmachen und laut mitzählen.",
        ),
        music = null, countsAsScreenTime = false,
        content = { WashHands(it) },
    )

    val routine = Play(
        id = "routine", title = Txt("My day", "Mein Tag"), friend = Friend.Moss, icon = "hb_step_wake",
        about = Txt(
            "The morning or evening routine you chose, as a picture checklist with a gold star per step. Tip: set the steps on the dashboard and let your child tick each one off after doing it.",
            "Die Morgen- oder Abendroutine, die Sie gewählt haben, als Bilder-Checkliste mit einem goldenen Stern pro Schritt. Tipp: Schritte im Dashboard festlegen und Ihr Kind jeden nach dem Erledigen abhaken lassen.",
        ),
        music = null, showDots = false, countsAsScreenTime = false,
        content = { MyDay(it) },
    )

    val plate = Play(
        id = "plate", title = Txt("Healthy plate", "Gesunder Teller"), friend = Friend.Moss, icon = "hb_plate",
        about = Txt(
            "Everyday foods and sometimes foods (treats are for special days, nothing is bad), then a rainbow plate of colours. Tip: count the colours on your real plate at dinner.",
            "Alltagsessen und Ab-und-zu-Essen (Süßes ist für besondere Tage, nichts ist verboten), dann ein Regenbogenteller. Tipp: Beim Abendessen die Farben auf dem echten Teller zählen.",
        ),
        content = { HealthyPlate(it) },
    )

    val tidy = Play(
        id = "tidy", title = Txt("Tidy up time", "Aufräumzeit"), friend = Friend.Moss, icon = "hb_toybox",
        backdrop = "bg_hb_room",
        about = Txt(
            "Every toy has a home: toy box, bookshelf, bath basket. Sorting by place, more toys and homes as it gets easier. Tip: give real toys a fixed home and tidy up together with a song.",
            "Jedes Spielzeug hat ein Zuhause: Spielkiste, Bücherregal, Badekorb. Sortieren nach Ort, mit mehr Spielsachen und Plätzen. Tipp: Echten Spielsachen einen festen Platz geben und gemeinsam mit einem Lied aufräumen.",
        ),
        content = { TidyUp(it) },
    )

    val weather = Play(
        id = "weather", title = Txt("Dress for the weather", "Anziehen fürs Wetter"), friend = Friend.Moss, icon = "hb_wear_umbrella",
        minAge = 3,
        about = Txt(
            "Look out of the window and dress Tuka for sun, rain, snow or wind, with a kind reason for each wrong pick. Tip: look outside together in the morning and choose clothes the same way.",
            "Aus dem Fenster schauen und Tuka für Sonne, Regen, Schnee oder Wind anziehen, mit einer freundlichen Erklärung bei falscher Wahl. Tipp: Morgens gemeinsam rausschauen und genauso die Kleidung wählen.",
        ),
        content = { DressForWeather(it) },
    )

    val world = World(
        id = "habits", title = Txt("Moss's Garden", "Moos' Garten"), friend = Friend.Moss, color = Palette.sage,
        backdrop = "bg_garden", props = listOf("ob_toothbrush", "ob_soap", "ob_carrot"), intro = "world_habits",
        plays = listOf(brush, wash, routine, plate, tidy, weather),
    )
}
