package dev.framio.meadow.worlds.habits

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.rememberSlowClock
import dev.framio.meadow.ui.wobble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val ROUNDS = 4
private const val TUKA = 560f

/**
 * Dress for the weather: the window shows sun, rain, snow or wind; Tuka needs the right things to
 * go outside. Tapping a card puts it on her; a pick that doesn't suit the weather gets a kind
 * reason ("Brr, it's snowy! A sun hat won't keep you warm."). Higher levels: everything she needs
 * among more cards.
 */
@Composable
fun DressForWeather(s: PlayScope) {
    val order = remember { weatherOrder(Random(System.nanoTime())) }
    var round by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        awaitQuiet(s)
        s.sayAndWait("weather_intro")
        ready = true
    }
    if (!ready) return
    key(round) {
        WeatherRoundView(s, round, order[round % order.size]) { first ->
            s.roundDone(first)
            if (round + 1 >= ROUNDS) {
                s.rounds(ROUNDS, ROUNDS)
                s.finish()
            } else round++
        }
    }
}

@Composable
private fun WeatherRoundView(s: PlayScope, round: Int, w: Weather, onDone: (Boolean) -> Unit) {
    val spec = remember { weatherRound(w, s.level, Random(System.nanoTime() + round)) }
    val worn = remember { mutableStateListOf<Wear>() }
    val wobbles = remember { mutableStateMapOf<Wear, Int>() }
    var misses by remember { mutableIntStateOf(0) }
    var dressed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val co = rememberCoroutineScope()
    val wid = w.name.lowercase()

    LaunchedEffect(Unit) {
        s.rounds(round, ROUNDS)
        delay(300)
        s.prompt("weather_$wid")
    }

    fun pick(item: Wear) {
        if (busy || dressed || item in worn) return
        if (item in spec.needed) {
            worn += item
            s.sfx(Sound.Place)
            s.practised("weather:$wid")
            if (spec.needed.all { it in worn }) {
                dressed = true
                busy = true
                co.launch {
                    s.sayAndWait("weather_on_${item.id}")
                    s.friendMood = Mood.Happy
                    s.sfx(Sound.Good)
                    s.sayAndWait("weather_done_$wid")
                    delay(700)
                    onDone(misses == 0)
                }
            } else {
                s.say("weather_on_${item.id}")
            }
        } else {
            misses++
            wobbles[item] = (wobbles[item] ?: 0) + 1
            s.sfx(Sound.Hmm)
            s.say(wrongLine(w, item))
            if (misses >= 2) s.showHint(true)
        }
    }

    // the window
    SoftCard(Palette.cream, Modifier.offset(250.dp, 24.dp).size(470.dp, 340.dp), corner = 34.dp) {
        Box(Modifier.size(430.dp, 290.dp)) {
            Sprite("hb_sky_$wid", Modifier.size(430.dp, 290.dp))
            WeatherParticles(w, Modifier.size(430.dp, 290.dp))
        }
        // window bars
        Canvas(Modifier.size(430.dp, 290.dp)) {
            val c = Palette.cream
            drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 10f * density)
            drawLine(c, Offset(0f, size.height * 0.45f), Offset(size.width, size.height * 0.45f), 10f * density)
        }
    }

    // Tuka, dressed layer by layer
    Box(Modifier.offset(700.dp, (-90).dp).size(TUKA.dp)) {
        TukaBody(happy = dressed)
        worn.sortedBy { it.layer }.forEach { item -> key(item) { WornItem(item) } }
    }

    // the cards
    val cell = 190f
    val startX = 250f + (1000f - spec.cards.size * cell) / 2
    spec.cards.forEachIndexed { i, item ->
        val isWorn = item in worn
        val dim by animateFloatAsState(if (isWorn) 0.35f else 1f, tween(400), label = "dim")
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(90L * i + 200)
            enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
        SoftCard(
            if (isWorn) Palette.sage.lighten(0.55f) else Palette.cream,
            Modifier
                .offset((startX + i * cell).dp, 460.dp)
                .size(170.dp)
                .graphicsLayer {
                    alpha = enter.value * dim
                    translationY = (1f - enter.value) * 40f * density
                }
                .wobble(wobbles[item] ?: 0)
                .hintPulse(s.hint && item in spec.needed && !isWorn && !dressed)
                .pressable(enabled = !isWorn && !dressed, sound = null) { pick(item) },
            corner = 40.dp,
        ) {
            Sprite(item.icon, Modifier.size(140.dp))
        }
    }
}

/** Tuka blinks now and then on her own, and smiles when she's ready to go out. */
@Composable
private fun TukaBody(happy: Boolean) {
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val r = Random(System.nanoTime())
        while (true) {
            delay(2400L + r.nextLong(2600))
            blink = true
            delay(140)
            blink = false
        }
    }
    val pose = when {
        happy -> "happy"
        blink -> "blink"
        else -> "idle"
    }
    // all three share one crop with the clothes, so they line up exactly
    Sprite("hb_dress_tuka_idle", Modifier.size(TUKA.dp), alpha = if (pose == "idle") 1f else 0f)
    Sprite("hb_dress_tuka_blink", Modifier.size(TUKA.dp), alpha = if (pose == "blink") 1f else 0f)
    Sprite("hb_dress_tuka_happy", Modifier.size(TUKA.dp), alpha = if (pose == "happy") 1f else 0f)
}

@Composable
private fun WornItem(item: Wear) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Sprite(
        item.sprite,
        Modifier
            .size(TUKA.dp)
            .graphicsLayer {
                alpha = a.value
                translationY = (1f - a.value) * -24f * density
            },
    )
}

/** Gentle weather in the window: drifting rain, snowflakes, wind-blown leaves or a warm glow. */
@Composable
private fun WeatherParticles(w: Weather, modifier: Modifier) {
    val clock = rememberSlowClock(60)
    val seeds = remember(w) {
        val r = Random(w.ordinal * 17 + 3)
        List(if (w == Weather.Windy) 7 else 18) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    Canvas(modifier) {
        val t = clock.value / 1000f
        clipRect { particles(w, t, seeds) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.particles(w: Weather, t: Float, seeds: List<FloatArray>) {
    when (w) {
        Weather.Rainy -> for (p in seeds) {
            val y = ((p[1] + t * (0.55f + p[2] * 0.2f)) % 1f) * size.height
            val x = p[0] * size.width - y * 0.12f
            drawLine(Color(0xAA7FA6C9), Offset(x, y), Offset(x - 4f * density, y + 16f * density), 3f * density, StrokeCap.Round)
        }
        Weather.Snowy -> for (p in seeds) {
            val y = ((p[1] + t * (0.08f + p[2] * 0.06f)) % 1f) * size.height
            val x = p[0] * size.width + sin(t * 1.2f + p[2] * 6f) * 10f * density
            drawCircle(Color.White.copy(alpha = 0.95f), (3f + p[2] * 4f) * density, Offset(x, y))
        }
        Weather.Windy -> for (p in seeds) {
            val x = ((p[0] + t * (0.12f + p[2] * 0.08f)) % 1.2f - 0.1f) * size.width
            val y = p[1] * size.height * 0.8f + sin(t * 2f + p[2] * 6f) * 14f * density
            rotate(t * 90f * (0.5f + p[2]), Offset(x, y)) {
                drawOval(if (p[2] > 0.5f) Color(0xFF9CC88A) else Color(0xFFE6B567), Offset(x - 9f * density, y - 5f * density), Size(18f * density, 10f * density))
            }
            drawLine(Color.White.copy(alpha = 0.55f), Offset(x - 60f * density, y + 12f * density), Offset(x - 22f * density, y + 12f * density), 3f * density, StrokeCap.Round)
        }
        Weather.Sunny -> {
            // the sun in the picture glows softly
            val g = 0.5f + 0.5f * sin((t * 2 * PI / 3.2).toFloat())
            val c = Offset(size.width * 0.725f, size.height * 0.26f)
            drawCircle(Color(0xFFFFF1C2).copy(alpha = 0.18f + 0.14f * g), (52f + 8f * g) * density, c)
        }
    }
}
