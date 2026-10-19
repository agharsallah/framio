package dev.framio.meadow.worlds.habits

import dev.framio.meadow.core.Txt

/**
 * The picture steps a grown-up can put in the morning and evening routines (Settings.morningRoutine
 * / eveningRoutine hold their ids, the dashboard edits them). Sprite: hb_step_<id>; narration:
 * routine_<id> ("Time to brush your teeth!") and routine_<id>_done.
 */
data class RoutineStep(val id: String, val title: Txt, val morning: Boolean, val evening: Boolean) {
    val sprite get() = "hb_step_$id"
}

object RoutineSteps {
    val all = listOf(
        RoutineStep("wake", Txt("Wake up and stretch", "Aufwachen und strecken"), morning = true, evening = false),
        RoutineStep("toilet", Txt("Use the toilet", "Aufs Klo gehen"), morning = true, evening = true),
        RoutineStep("wash", Txt("Wash hands and face", "Hände und Gesicht waschen"), morning = true, evening = true),
        RoutineStep("dress", Txt("Get dressed", "Anziehen"), morning = true, evening = false),
        RoutineStep("breakfast", Txt("Eat breakfast", "Frühstücken"), morning = true, evening = false),
        RoutineStep("brush", Txt("Brush teeth", "Zähne putzen"), morning = true, evening = true),
        RoutineStep("hair", Txt("Brush hair", "Haare kämmen"), morning = true, evening = false),
        RoutineStep("shoes", Txt("Shoes and jacket on", "Schuhe und Jacke an"), morning = true, evening = false),
        RoutineStep("water", Txt("Drink some water", "Wasser trinken"), morning = true, evening = true),
        RoutineStep("tidy", Txt("Tidy up toys", "Spielzeug aufräumen"), morning = false, evening = true),
        RoutineStep("dinner", Txt("Eat dinner", "Abendessen"), morning = false, evening = true),
        RoutineStep("bath", Txt("Bath time", "Baden"), morning = false, evening = true),
        RoutineStep("pyjamas", Txt("Put on pyjamas", "Schlafanzug anziehen"), morning = false, evening = true),
        RoutineStep("story", Txt("Read a story", "Geschichte lesen"), morning = false, evening = true),
        RoutineStep("hug", Txt("Goodnight hug", "Gute-Nacht-Kuscheln"), morning = false, evening = true),
        RoutineStep("sleep", Txt("Lights off, sleep tight", "Licht aus, schlaf gut"), morning = false, evening = true),
    )

    fun byId(id: String): RoutineStep? = all.firstOrNull { it.id == id }
}

/** Where finger paint saves the child's pictures (PNG); the dashboard's gallery reads them. */
const val DRAWINGS_DIR = "drawings"
