package dev.framio.meadow.worlds.colors

/*
 * The six original colouring pages and the little things painted in Mixing colors, as vector
 * parts in a 1000×700 space. Big, simple parts: one tap fills one, even for a two-year-old.
 */

private fun bg() = Part("sky", rrect(0f, 0f, PAGE_W, PAGE_H, 0f), required = false)

private fun hills(y: Float) = blob(
    -60f, y + 40f, 180f, y - 10f, 420f, y + 20f, 650f, y - 15f, 880f, y + 15f, 1060f, y - 5f, 1060f, 760f, -60f, 760f,
    tension = 0.7f,
)

private fun eyePair(lx: Float, rx: Float, y: Float, r: Float) = listOf(
    Ink(ellipse(lx, y, r, r * 1.15f), glint = true),
    Ink(ellipse(rx, y, r, r * 1.15f), glint = true),
)

private fun smileLine(cx: Float, y: Float, w: Float) = Line(curve(cx - w, y, cx, y + w * 0.6f, cx + w, y))

private fun cloud(cx: Float, cy: Float, s: Float, id: String) = Part(
    id,
    circle(cx, cy, 40f * s) + circle(cx - 50f * s, cy + 15f * s, 30f * s) + circle(cx + 50f * s, cy + 12f * s, 32f * s) +
        rrect(cx - 75f * s, cy + 5f * s, cx + 80f * s, cy + 42f * s, 20f * s),
    required = false,
)

/** Pip the honey bird standing on a little hill. */
private val pip = Page(
    "pip",
    listOf(
        bg(),
        cloud(170f, 120f, 1.2f, "cloud"),
        Part("hill", hills(600f)),
        Part("tuft", ellipse(470f, 150f, 16f, 42f, -25f) + ellipse(500f, 140f, 17f, 50f) + ellipse(530f, 150f, 16f, 42f, 25f), required = false),
        Part("wing_l", ellipse(305f, 410f, 70f, 120f, 25f)),
        Part("wing_r", ellipse(695f, 410f, 70f, 120f, -25f)),
        Part("foot_l", blob(430f, 600f, 400f, 645f, 445f, 632f, 460f, 650f, 470f, 610f), required = false),
        Part("foot_r", blob(570f, 600f, 600f, 645f, 555f, 632f, 540f, 650f, 530f, 610f), required = false),
        Part(
            "body", ellipse(500f, 390f, 215f, 215f),
            details = eyePair(430f, 570f, 340f, 17f) + listOf(Blush(ellipse(392f, 398f, 32f, 20f)), Blush(ellipse(608f, 398f, 32f, 20f))),
        ),
        Part("belly", ellipse(500f, 485f, 130f, 105f)),
        Part("beak", blob(462f, 372f, 500f, 350f, 538f, 372f, 500f, 428f, tension = 0.6f)),
    ),
)

/** A flower in a pot on a table, a sun peeking in. */
private val flowerPot = Page(
    "flowerpot",
    listOf(
        bg(),
        Part("table", rrect(-20f, 610f, 1020f, 760f, 0f)),
        Part("stem", rrect(478f, 230f, 522f, 470f, 20f)),
        Part("leaf_l", ellipse(420f, 380f, 75f, 32f, -30f)),
        Part("leaf_r", ellipse(580f, 330f, 75f, 32f, 30f)),
        Part("pot", poly(380f, 480f, 620f, 480f, 585f, 650f, 415f, 650f)),
        Part("rim", rrect(355f, 445f, 645f, 505f, 18f)),
    ) + (0 until 6).map { i ->
        val a = Math.toRadians(i * 60.0 - 90.0)
        Part("petal_$i", circle(500f + 95f * Math.cos(a).toFloat(), 205f + 95f * Math.sin(a).toFloat(), 62f))
    } + listOf(
        Part("middle", circle(500f, 205f, 62f), details = eyePair(478f, 522f, 195f, 9f) + smileLine(500f, 225f, 16f)),
        Part("sun", circle(880f, 110f, 70f), required = false, details = eyePair(858f, 902f, 100f, 7f) + smileLine(880f, 128f, 16f)),
        Part("bee_wing", ellipse(165f, 152f, 22f, 32f, -20f) + ellipse(195f, 150f, 20f, 30f, 20f), required = false),
        Part(
            "bee", ellipse(180f, 195f, 48f, 32f), required = false,
            details = listOf(Line(curve(170f, 166f, 165f, 195f, 170f, 224f), 0.9f), Line(curve(150f, 170f, 145f, 195f, 150f, 220f), 0.9f), Ink(circle(205f, 188f, 6f))),
        ),
    ),
)

/** A little house, a path, the sun and a cloud. */
private val house = Page(
    "house",
    listOf(
        bg(),
        Part(
            "sun", circle(150f, 135f, 68f) + Shape((0 until 10).flatMap { i -> ellipse(150f + 105f * cosD(i * 36f), 135f + 105f * sinD(i * 36f), 22f, 12f, i * 36f).segs }),
            details = eyePair(128f, 172f, 125f, 7f) + smileLine(150f, 152f, 18f),
        ),
        cloud(820f, 110f, 1.3f, "cloud"),
        Part("grass", hills(590f)),
        Part("chimney", rrect(600f, 165f, 650f, 280f, 8f)),
        Part("wall", rrect(325f, 320f, 675f, 610f, 10f)),
        Part("roof", blob(285f, 340f, 500f, 160f, 715f, 340f, tension = 0.15f)),
        Part("door", rrect(455f, 465f, 545f, 610f, 40f), details = listOf(Ink(circle(527f, 545f, 7f)))),
        Part("window_l", rrect(360f, 380f, 430f, 450f, 12f), details = listOf(Line(poly(395f, 380f, 395f, 450f, close = false), 0.6f))),
        Part("window_r", rrect(570f, 380f, 640f, 450f, 12f), details = listOf(Line(poly(605f, 380f, 605f, 450f, close = false), 0.6f))),
        Part("path", blob(465f, 612f, 535f, 612f, 600f, 705f, 400f, 705f, tension = 0.3f)),
        Part("bush", circle(250f, 590f, 55f) + circle(195f, 610f, 45f), required = false),
    ),
)

/** A happy fish swimming over the waves, bubbles going up. */
private val fish = Page(
    "fish",
    listOf(
        bg(),
        Part("tail", blob(640f, 330f, 790f, 220f, 760f, 330f, 790f, 440f, tension = 0.5f)),
        Part("fin_top", blob(400f, 230f, 470f, 160f, 560f, 215f, tension = 0.6f)),
        Part("body", ellipse(470f, 330f, 205f, 130f), details = eyePair(360f, 360f, 305f, 18f).take(1) + smileLine(320f, 360f, 22f)),
        Part("stripe", blob(520f, 210f, 575f, 220f, 590f, 330f, 575f, 440f, 520f, 450f, 540f, 330f, tension = 0.7f)),
        Part("fin_side", ellipse(470f, 380f, 50f, 28f, 30f)),
        Part("bubble_1", circle(200f, 230f, 32f), required = false),
        Part("bubble_2", circle(160f, 150f, 22f), required = false),
        Part("bubble_3", circle(215f, 85f, 16f), required = false),
        Part("wave_back", blob(-60f, 540f, 120f, 500f, 300f, 540f, 480f, 500f, 660f, 540f, 840f, 500f, 1060f, 540f, 1060f, 760f, -60f, 760f, tension = 0.8f)),
        Part("wave_front", blob(-60f, 620f, 160f, 585f, 380f, 620f, 600f, 585f, 820f, 620f, 1060f, 585f, 1060f, 760f, -60f, 760f, tension = 0.8f)),
        Part("starfish", starShape(860f, 640f, 50f, 24f), required = false),
    ),
)

/** Moss the turtle under a rainbow. */
private val mossRainbow = Page(
    "moss",
    listOf(bg()) + listOf(430f, 385f, 340f, 295f).mapIndexed { i, r ->
        Part("band_$i", ringBand(500f, 600f, r - 45f, r, 180f, 360f))
    } + listOf(
        cloud(150f, 560f, 1.3f, "cloud_l").copy(required = true),
        cloud(850f, 560f, 1.3f, "cloud_r").copy(required = true),
        Part("grass", hills(615f)),
        Part("leg_l", ellipse(330f, 628f, 55f, 38f)),
        Part("leg_r", ellipse(670f, 628f, 55f, 38f)),
        Part(
            "shell", blob(265f, 600f, 300f, 455f, 500f, 380f, 700f, 455f, 735f, 600f, tension = 0.8f),
            details = listOf(Line(curve(330f, 470f, 360f, 530f, 340f, 590f), 0.6f), Line(curve(670f, 470f, 640f, 530f, 660f, 590f), 0.6f)),
        ),
        Part("rim", rrect(250f, 578f, 750f, 628f, 25f)),
        Part("leaf_l", ellipse(468f, 352f, 34f, 16f, -25f), required = false),
        Part("leaf_r", ellipse(532f, 352f, 34f, 16f, 25f), required = false),
        Part(
            "head", ellipse(500f, 500f, 92f, 82f),
            details = listOf(Line(curve(500f, 419f, 500f, 362f), 0.8f)) + eyePair(468f, 532f, 490f, 11f) + smileLine(500f, 530f, 18f) +
                listOf(Blush(ellipse(447f, 522f, 18f, 11f)), Blush(ellipse(553f, 522f, 18f, 11f))),
        ),
    ),
)

/** Bo the elephant holding a balloon with the trunk. */
private val boBalloon = Page(
    "bo",
    listOf(
        bg(),
        cloud(160f, 120f, 1.1f, "cloud"),
        Part("balloon", ellipse(740f, 175f, 85f, 105f), details = listOf(Line(curve(740f, 295f, 720f, 350f, 640f, 400f, 585f, 432f), 0.6f))),
        Part("knot", poly(726f, 296f, 754f, 296f, 740f, 276f), required = false),
        Part("grass", hills(615f)),
        Part("body", ellipse(430f, 560f, 135f, 105f)),
        Part("foot_l", rrect(345f, 600f, 410f, 665f, 25f)),
        Part("foot_r", rrect(450f, 600f, 515f, 665f, 25f)),
        Part("ear_l", ellipse(290f, 360f, 95f, 115f, -10f)),
        Part("ear_r", ellipse(570f, 360f, 95f, 115f, 10f)),
        Part(
            "head", ellipse(430f, 380f, 145f, 135f),
            details = eyePair(385f, 475f, 355f, 12f) + listOf(Blush(ellipse(355f, 405f, 24f, 14f)), Blush(ellipse(505f, 405f, 24f, 14f))),
        ),
        Part(
            "trunk",
            blob(405f, 405f, 405f, 480f, 440f, 535f, 505f, 530f, 555f, 490f, 588f, 448f, 568f, 428f, 525f, 470f, 478f, 488f, 455f, 465f, 455f, 405f, tension = 0.7f),
        ),
    ),
)

val COLORING_PAGES: List<Page> = listOf(pip, flowerPot, house, fish, mossRainbow, boBalloon)

// ---------------------------------------------------------------- things to paint in Mixing colors (one part each)

/** A thing to paint with a new colour: one part, drawn in a square 1000×700 space like the pages. */
data class MixThing(val id: String, val page: Page)

private fun thing(id: String, shape: Shape, vararg details: Detail) = MixThing(id, Page(id, listOf(Part(id, shape, details = details.toList()))))

val MIX_THINGS: List<MixThing> = listOf(
    thing(
        "fish", ellipse(450f, 350f, 250f, 160f) + blob(650f, 350f, 860f, 200f, 820f, 350f, 860f, 500f, tension = 0.5f),
        Ink(ellipse(330f, 320f, 22f, 26f), glint = true), Line(curve(270f, 400f, 300f, 425f, 335f, 410f)),
    ),
    thing(
        "flower",
        Shape((0 until 6).flatMap { i -> circle(500f + 140f * cosD(i * 60f - 90f), 330f + 140f * sinD(i * 60f - 90f), 95f).segs }) + circle(500f, 330f, 100f),
        Line(circle(500f, 330f, 100f), 0.8f),
        Ink(ellipse(465f, 315f, 13f, 15f), glint = true), Ink(ellipse(535f, 315f, 13f, 15f), glint = true), Line(curve(470f, 360f, 500f, 380f, 530f, 360f)),
    ),
    thing(
        "butterfly",
        ellipse(370f, 250f, 140f, 120f, -20f) + ellipse(630f, 250f, 140f, 120f, 20f) + ellipse(390f, 480f, 100f, 90f, 20f) + ellipse(610f, 480f, 100f, 90f, -20f) +
            ellipse(500f, 370f, 40f, 170f),
        Line(curve(485f, 205f, 450f, 120f, 420f, 100f)), Line(curve(515f, 205f, 550f, 120f, 580f, 100f)),
    ),
    thing("star", starShape(500f, 375f, 290f, 140f), Ink(ellipse(450f, 360f, 16f, 19f), glint = true), Ink(ellipse(550f, 360f, 16f, 19f), glint = true), Line(curve(470f, 410f, 500f, 430f, 530f, 410f))),
    thing("heart", heart(500f, 370f, 560f)),
    thing(
        "snail", circle(430f, 350f, 190f) + blob(180f, 560f, 820f, 560f, 820f, 480f, 760f, 420f, 640f, 470f, 200f, 500f, tension = 0.5f),
        Line(curve(430f, 350f, 470f, 330f, 480f, 380f, 420f, 410f, 360f, 360f, 400f, 270f, 500f, 260f, 570f, 330f), 0.7f),
        Ink(circle(765f, 455f, 13f), glint = true),
    ),
)

internal fun cosD(deg: Float) = Math.cos(Math.toRadians(deg.toDouble())).toFloat()
internal fun sinD(deg: Float) = Math.sin(Math.toRadians(deg.toDouble())).toFloat()
