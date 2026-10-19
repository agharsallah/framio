package dev.framio.meadow.worlds.habits

import dev.framio.meadow.core.PartOfDay
import kotlin.random.Random

// Pure rules for Moss's Garden: no Android, unit-tested (HabitsLogicTest).

// ---------------------------------------------------------------- brushing

/** The six places a toothbrush visits. [x], [y]: centre on the mouth sprite, as fractions. */
enum class MouthArea(val x: Float, val y: Float, val line: String) {
    TopA(0.31f, 0.27f, "brush_top_a"),
    TopB(0.69f, 0.27f, "brush_top_b"),
    BottomA(0.33f, 0.74f, "brush_bottom_a"),
    BottomB(0.67f, 0.74f, "brush_bottom_b"),
    Front(0.5f, 0.3f, "brush_front"),
    Tongue(0.5f, 0.56f, "brush_tongue"),
}

data class BrushStep(val area: MouthArea, val seconds: Int)

/**
 * Two minutes (dentists' advice; one minute for two-year-olds), shared out so the four sides get
 * the most time, then the front teeth and a little for the tongue. The seconds always add up.
 */
fun brushPlan(age: Int): List<BrushStep> {
    val total = if (age <= 2) 60 else 120
    val weights = listOf(MouthArea.TopA to 6, MouthArea.TopB to 6, MouthArea.BottomA to 6, MouthArea.BottomB to 6, MouthArea.Front to 4, MouthArea.Tongue to 2)
    val sum = weights.sumOf { it.second }
    val steps = weights.map { (a, w) -> BrushStep(a, total * w / sum) }.toMutableList()
    val rest = total - steps.sumOf { it.seconds }
    steps[0] = steps[0].copy(seconds = steps[0].seconds + rest)
    return steps
}

/**
 * Where the sugar bugs sit for an area (fractions of the mouth): the sides' bugs lean outwards and
 * the front ones sit on the middle teeth, top and bottom, so no two bugs overlap.
 */
fun bugSpots(area: MouthArea, count: Int): List<Pair<Float, Float>> {
    val spots = when (area) {
        MouthArea.TopA -> listOf(0.21f to 0.3f, 0.34f to 0.26f)
        MouthArea.TopB -> listOf(0.79f to 0.3f, 0.66f to 0.26f)
        MouthArea.BottomA -> listOf(0.24f to 0.72f, 0.37f to 0.77f)
        MouthArea.BottomB -> listOf(0.76f to 0.72f, 0.63f to 0.77f)
        MouthArea.Front -> listOf(0.5f to 0.24f, 0.5f to 0.8f)
        MouthArea.Tongue -> listOf(0.5f to 0.55f, 0.42f to 0.57f)
    }
    return spots.take(count.coerceIn(1, spots.size))
}

/** A bug fades once the brushing of its area passes its share: the first goes early, the last near the end. */
fun bugGone(index: Int, count: Int, progress: Float): Boolean = progress >= (index + 1f) / (count + 1f)

/** Petals of the progress flower that are open after [elapsed] of [total] seconds. */
fun petalsOpen(elapsed: Int, total: Int, petals: Int): Int =
    if (total <= 0) petals else (elapsed * petals / total).coerceIn(0, petals)

// ---------------------------------------------------------------- washing hands

enum class WashStep(val line: String, val scrub: Boolean) {
    Wet("wash_wet", false),
    Soap("wash_soap", false),
    Palms("wash_palms", true),
    Backs("wash_backs", true),
    Between("wash_between", true),
    Thumbs("wash_thumbs", true),
    Nails("wash_nails", true),
    Rinse("wash_rinse", false),
    Dry("wash_dry", false),
}

/** Twenty seconds of scrubbing, counted out loud: which numbers each scrubbing step counts. */
fun scrubCounts(): Map<WashStep, IntRange> {
    val scrubs = WashStep.entries.filter { it.scrub }
    val per = 20 / scrubs.size
    return scrubs.mapIndexed { i, s -> s to (i * per + 1..(i + 1) * per) }.toMap()
}

// ---------------------------------------------------------------- routine

/** Morning steps before noon (or when the clock isn't set yet), evening steps after. */
fun routineIsMorning(part: PartOfDay?, plausible: Boolean): Boolean =
    !plausible || part == PartOfDay.Morning || part == null

fun routineSteps(ids: List<String>): List<RoutineStep> = ids.distinct().mapNotNull { RoutineSteps.byId(it) }

/** The card sizes along the path so every step fits on one row of [width] dp. */
fun routineCardSizes(count: Int, width: Float = 1180f, big: Float = 270f, gap: Float = 18f): Pair<Float, Float> {
    if (count <= 1) return big to big
    val small = ((width - big - gap * (count - 1)) / (count - 1)).coerceIn(84f, 150f)
    // ten steps: the big card gives up a little room rather than running off the screen
    return minOf(big, width - (small + gap) * (count - 1)) to small
}

// ---------------------------------------------------------------- healthy plate

enum class FoodKind { Everyday, Sometimes }

data class Food(val id: String, val sprite: String, val kind: FoodKind, val color: String? = null)

object Foods {
    val all = listOf(
        Food("apple", "ob_apple", FoodKind.Everyday, "red"),
        Food("tomato", "ob_tomato", FoodKind.Everyday, "red"),
        Food("strawberry", "ob_strawberry", FoodKind.Everyday, "red"),
        Food("cherry", "ob_cherry", FoodKind.Everyday, "red"),
        Food("watermelon", "ob_watermelon", FoodKind.Everyday, "red"),
        Food("carrot", "ob_carrot", FoodKind.Everyday, "orange"),
        Food("orange", "ob_orange", FoodKind.Everyday, "orange"),
        Food("banana", "ob_banana", FoodKind.Everyday, "yellow"),
        Food("corn", "hb_corn", FoodKind.Everyday, "yellow"),
        Food("lemon", "ob_lemon", FoodKind.Everyday, "yellow"),
        Food("broccoli", "ob_broccoli", FoodKind.Everyday, "green"),
        Food("pear", "ob_pear", FoodKind.Everyday, "green"),
        Food("peas", "hb_peas", FoodKind.Everyday, "green"),
        Food("grapes", "ob_grapes", FoodKind.Everyday, "purple"),
        Food("eggplant", "hb_eggplant", FoodKind.Everyday, "purple"),
        Food("blueberry", "ob_blueberry", FoodKind.Everyday),
        Food("cheese", "ob_cheese", FoodKind.Everyday),
        Food("egg", "ob_egg", FoodKind.Everyday),
        Food("bread", "ob_bread", FoodKind.Everyday),
        Food("milk", "ob_milk", FoodKind.Everyday),
        Food("cupcake", "ob_cupcake", FoodKind.Sometimes),
        Food("cookie", "ob_cookie", FoodKind.Sometimes),
        Food("icecream", "ob_icecream", FoodKind.Sometimes),
        Food("candy", "hb_candy", FoodKind.Sometimes),
        Food("chips", "hb_chips", FoodKind.Sometimes),
        Food("lollipop", "hb_lollipop", FoodKind.Sometimes),
    )
    val rainbow = listOf("red", "orange", "yellow", "green", "purple")
}

/** A sorting round: how many foods, always some of each kind, never the same food twice. */
fun sortRound(level: Int, r: Random): List<Food> {
    val n = when (level) { 0, 1 -> 4; 2 -> 5; else -> 6 }
    val some = if (n >= 6) 3 else 2
    val every = Foods.all.filter { it.kind == FoodKind.Everyday && it.id != "lemon" }.shuffled(r).take(n - some)
    val treats = Foods.all.filter { it.kind == FoodKind.Sometimes }.shuffled(r).take(some)
    return (every + treats).shuffled(r)
}

/** The colours Moss asks for on the rainbow plate: three for the youngest, all five later. */
fun rainbowColors(level: Int): List<String> = if (level <= 1) listOf("red", "yellow", "green") else Foods.rainbow

/** The tray for one colour: one food of [color] and distractors of colours not on the plate yet (or others). */
fun rainbowChoices(color: String, level: Int, r: Random): List<Food> {
    val right = Foods.all.filter { it.color == color }.random(r)
    val others = Foods.all.filter { it.color != null && it.color != color }.shuffled(r).distinctBy { it.color }
    val n = if (level <= 1) 2 else 3
    return (listOf(right) + others.take(n)).shuffled(r)
}

// ---------------------------------------------------------------- tidy up

enum class Home(val sprite: String) {
    ToyBox("hb_toybox"),
    Shelf("hb_shelf"),
    Bath("hb_bathbasket"),
}

data class Toy(val id: String, val sprite: String, val home: Home)

object Toys {
    val all = listOf(
        Toy("teddy", "ob_teddy", Home.ToyBox),
        Toy("ball", "ob_ball", Home.ToyBox),
        Toy("block", "ob_block", Home.ToyBox),
        Toy("car", "ob_car", Home.ToyBox),
        Toy("drum", "ob_drum", Home.ToyBox),
        Toy("book", "ob_book", Home.Shelf),
        Toy("book_red", "hb_book_red", Home.Shelf),
        Toy("book_blue", "hb_book_blue", Home.Shelf),
        Toy("duck", "ob_duck", Home.Bath),
        Toy("boat", "ob_boat", Home.Bath),
        Toy("fish", "ob_fish", Home.Bath),
    )
}

fun tidyHomes(level: Int): List<Home> = when (level) {
    0 -> listOf(Home.ToyBox)
    1 -> listOf(Home.ToyBox, Home.Shelf)
    else -> listOf(Home.ToyBox, Home.Shelf, Home.Bath)
}

/** Toys for a round: more at higher levels, at least one for every home, none twice. */
fun tidyRound(level: Int, r: Random): List<Toy> {
    val homes = tidyHomes(level)
    val n = when (level) { 0 -> 4; 1 -> 5; 2 -> 6; 3 -> 7; else -> 8 }
    val pool = Toys.all.filter { it.home in homes }.shuffled(r)
    val firsts = homes.map { h -> pool.first { it.home == h } }
    val rest = pool.filter { it !in firsts }
    return (firsts + rest).take(n).shuffled(r)
}

/**
 * Where toys lie on the floor, as fractions of the play area: a loose grid over the rug, nudged by
 * hand, away from the homes along the back and the friend's corner (bottom left).
 */
fun tidySpots(n: Int, seed: Int): List<Pair<Float, Float>> {
    val r = Random(seed)
    val cols = if (n <= 4) n else (n + 1) / 2
    val rows = if (n <= 4) 1 else 2
    val out = ArrayList<Pair<Float, Float>>(n)
    for (row in 0 until rows) {
        val inRow = if (row == 0) cols else n - cols
        for (i in 0 until inRow) {
            val left = if (row == 1) 0.3f else 0.24f
            val right = 0.74f
            val x = if (inRow == 1) (left + right) / 2 else left + (right - left) * i / (inRow - 1)
            val y = (if (rows == 1) 0.75f else if (row == 0) 0.69f else 0.885f) + (r.nextFloat() - 0.5f) * 0.02f
            out += (x + (r.nextFloat() - 0.5f) * 0.03f) to y
        }
    }
    return out
}

// ---------------------------------------------------------------- dress for the weather

enum class Weather { Sunny, Rainy, Snowy, Windy }

enum class Wear(val layer: Int) {
    // drawn in this order on Tuka: body first, then feet and hands, scarf, faces and hats, the umbrella last
    Tshirt(0), Raincoat(0), Jacket(0), Boots(1), Mittens(1), Scarf(2), Sunglasses(3), Sunhat(4), Woollyhat(4), Umbrella(5);

    val id get() = name.lowercase()
    val sprite get() = "hb_dress_$id"
    val icon get() = "hb_wear_$id"
}

/** What Tuka needs in each weather, and the kind "not this one" picks it may be offered. */
val WEAR_NEEDED = mapOf(
    Weather.Sunny to listOf(Wear.Sunhat, Wear.Sunglasses, Wear.Tshirt),
    Weather.Rainy to listOf(Wear.Raincoat, Wear.Boots, Wear.Umbrella),
    Weather.Snowy to listOf(Wear.Woollyhat, Wear.Scarf, Wear.Mittens),
    Weather.Windy to listOf(Wear.Jacket, Wear.Scarf),
)
val WEAR_WRONG = mapOf(
    Weather.Sunny to listOf(Wear.Woollyhat, Wear.Mittens, Wear.Raincoat, Wear.Scarf),
    Weather.Rainy to listOf(Wear.Sunglasses, Wear.Sunhat, Wear.Tshirt),
    Weather.Snowy to listOf(Wear.Sunhat, Wear.Tshirt, Wear.Umbrella),
    Weather.Windy to listOf(Wear.Umbrella, Wear.Sunhat, Wear.Tshirt),
)

/** Voice id for the kind explanation of a wrong pick, e.g. weather_no_snowy_sunhat. */
fun wrongLine(w: Weather, item: Wear) = "weather_no_${w.name.lowercase()}_${item.id}"

data class WeatherRound(val weather: Weather, val needed: List<Wear>, val cards: List<Wear>)

/** One item to find with two cards at first; later everything Tuka needs among up to five cards. */
fun weatherRound(w: Weather, level: Int, r: Random): WeatherRound {
    val all = WEAR_NEEDED.getValue(w)
    val needed = when (level) {
        0 -> all.shuffled(r).take(1)
        1 -> all.shuffled(r).take(2.coerceAtMost(all.size))
        else -> all
    }
    val wrongCount = when (level) { 0 -> 1; 1 -> 1; 2 -> 2; else -> 2 }
    val wrong = WEAR_WRONG.getValue(w).shuffled(r).take(wrongCount)
    return WeatherRound(w, needed, (needed + wrong).shuffled(r))
}

/** Four rounds, four different weathers. */
fun weatherOrder(r: Random): List<Weather> = Weather.entries.shuffled(r)
