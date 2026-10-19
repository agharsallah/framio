package dev.framio.meadow.worlds.quiet

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Where each friend curls up for a moment during the goodnight ritual (screen dp, size). */
private val SLEEPERS = listOf(
    Triple(Friend.Pip, 330.dp to 600.dp, 160.dp),
    Triple(Friend.Tuka, 560.dp to 560.dp, 200.dp),
    Triple(Friend.Bo, 800.dp to 560.dp, 210.dp),
    Triple(Friend.Moss, 1040.dp to 590.dp, 180.dp),
)

/**
 * Night light: a very dark, calm sky. Luma says goodnight to each friend (each one appears asleep
 * for a moment), then everything goes quiet: stars twinkle so slowly you hardly notice, and over
 * the grown-up's `nightLightMin` minutes the screen fades almost black and the lullaby softens, then
 * the backlight goes off (any touch or knock brings it back). Tapping the sky makes one star twinkle
 * and Luma whispers "Shh… sleep tight." The back button stays, very dim.
 */
@Composable
fun NightLight(s: PlayScope) {
    val app = s.app
    val stars = remember { skyStars(30, seed = 7) }
    val boosts = remember { FloatArray(stars.size) { -100f } }
    val clock = rememberSlowClock() // ~12 Hz: the stars move far too slowly to need more
    var asleepAt by remember { mutableFloatStateOf(0f) }
    var dark by remember { mutableFloatStateOf(0f) }
    var lumaMood by remember { mutableStateOf(Mood.Sleep) }
    var sleeper by remember { mutableStateOf<Friend?>(null) }
    var fadeEpoch by remember { mutableIntStateOf(0) }
    var fadeFrom by remember { mutableFloatStateOf(0f) }
    var fadeMs by remember { mutableStateOf(0L) }
    var slept by remember { mutableStateOf(false) }
    var lastShh by remember { mutableStateOf(0L) }
    val co = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            app.music.master = 1f
            app.music.duck(app.voice.speaking.value)
        }
    }
    // the goodnight ritual, then the slow fade
    LaunchedEffect(Unit) {
        delay(1200)
        lumaMood = Mood.Idle
        s.sayAndWait("nightlight_hello")
        delay(500)
        for ((f, _, _) in SLEEPERS) {
            sleeper = f
            delay(500)
            s.sayAndWait("nightlight_gn_${f.id}")
            delay(1600)
            sleeper = null
            delay(1200)
        }
        s.sayAndWait("nightlight_gn_you")
        delay(600)
        lumaMood = Mood.Sleep
        fadeMs = app.settings.value.nightLightMin * 60_000L
        fadeEpoch++
    }
    LaunchedEffect(fadeEpoch) {
        if (fadeEpoch == 0) return@LaunchedEffect
        val start = SystemClock.uptimeMillis()
        var n = 0
        while (true) {
            delay(1000)
            dark = nightAlpha(SystemClock.uptimeMillis() - start, fadeMs, fadeFrom)
            app.music.master = nightMusic(dark)
            if (n++ % 5 == 0) app.music.duck(app.voice.speaking.value) // re-applies the level
            if (dark >= NIGHT_DARK - 0.001f) {
                asleepAt = clock.value / 1000f
                slept = true
                app.sleepScreen()
                break
            }
        }
    }

    fun tapSky(fx: Float, fy: Float) {
        val i = nearestStar(stars, fx, fy, within = 2f)
        if (i >= 0) boosts[i] = clock.value / 1000f
        if (slept) { // woken by a touch: a little light again, then a short fade
            slept = false
            fadeFrom = 0.82f
            dark = fadeFrom
            fadeMs = 2 * 60_000L
            fadeEpoch++
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastShh > 7000 && lumaMood == Mood.Sleep) {
            lastShh = now
            co.launch {
                lumaMood = Mood.Idle
                s.sayAndWait(if ((now / 7000) % 3 == 0L) "nightlight_shh_2" else "nightlight_shh")
                delay(300)
                lumaMood = Mood.Sleep
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // everything below reaches up behind the top bar: it is one sky
        Box(Modifier.fillMaxSize().underTopBar()) {
            Canvas(Modifier.fillMaxSize()) {
                // while the backlight is off nothing reads the clock, so nothing redraws
                val t = if (slept) asleepAt else clock.value / 1000f
                stars.forEachIndexed { i, st ->
                    val x = ((st.x + st.drift * t / 150f) % 1f + 1f) % 1f * size.width
                    val y = st.y * size.height
                    val tw = 0.55f + 0.3f * sin(2 * PI.toFloat() * t / st.periodS + st.phase)
                    val since = t - boosts[i]
                    val bump = if (since in 0f..3f) sin(PI.toFloat() * since / 3f) else 0f
                    val a = (tw + 0.45f * bump).coerceIn(0f, 1f)
                    val r = st.r.dp.toPx() * (1f + 0.5f * bump)
                    val c = Offset(x, y)
                    drawCircle(Brush.radialGradient(listOf(STAR.copy(alpha = 0.28f * a), STAR.copy(alpha = 0f)), c, r * 4.5f), r * 4.5f, c)
                    drawCircle(STAR.copy(alpha = a), r, c)
                }
            }
            Sprite("qt_branch", Modifier.offset((-20).dp, 150.dp).size(600.dp, 300.dp), alpha = 0.82f)
            FriendView(Friend.Luma, Modifier.offset(150.dp, 134.dp).size(170.dp), mood = lumaMood)
            SLEEPERS.forEach { (f, pos, size) ->
                AnimatedVisibility(
                    sleeper == f,
                    Modifier.offset(pos.first - size / 2, pos.second - size),
                    enter = fadeIn(tween(1000)), exit = fadeOut(tween(1400)),
                ) { FriendView(f, Modifier.size(size), mood = Mood.Sleep) }
            }
            // the host's buttons stay usable but nearly invisible
            Canvas(Modifier.fillMaxSize()) {
                for (cx in floatArrayOf(64.dp.toPx(), size.width - 64.dp.toPx())) {
                    drawCircle(Palette.nightDeep.copy(alpha = 0.9f), 48.dp.toPx(), Offset(cx, 56.dp.toPx()))
                }
                if (dark > 0f) drawRect(Color.Black.copy(alpha = dark))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p -> tapSky(p.x / size.width, (p.y + TOP_BAR.toPx()) / (size.height + TOP_BAR.toPx())) }
                },
        )
    }
}

private val STAR = Color(0xFFF7EFD6)
