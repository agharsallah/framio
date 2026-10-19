package dev.framio.meadow.worlds.habits

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.core.Clock
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/** Which steps are ticked off, so leaving to actually brush and coming back keeps the stars (for today). */
private object RoutineMemory {
    val done = HashMap<String, Set<String>>()
    fun key(morning: Boolean) = Clock.dayKey() + if (morning) ":m" else ":e"
}

private const val ROW_Y = 330f

/**
 * My day: the morning or evening routine the grown-ups chose, as a path of picture cards. The
 * current step is big; Moss says what to do, the child taps it when it's done and it gets a gold
 * star. Morning or evening follows the clock, with a sun/moon switch. All done: a celebration.
 */
@Composable
fun MyDay(s: PlayScope) {
    val settings = s.app.settings.value
    var morning by remember {
        mutableStateOf(routineIsMorning(Clock.partOfDay(Clock.minuteOfDay()), Clock.plausible()))
    }
    val steps = remember(morning) { routineSteps(if (morning) settings.morningRoutine else settings.eveningRoutine) }
    var done by remember(morning) { mutableStateOf(RoutineMemory.done[RoutineMemory.key(morning)] ?: emptySet()) }
    val current = steps.indexOfFirst { it.id !in done }
    var busy by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    val co = rememberCoroutineScope()

    LaunchedEffect(morning) {
        if (first) awaitQuiet(s)
        s.sayAndWait(if (morning) "routine_morning_intro" else "routine_evening_intro")
        val st = steps.getOrNull(steps.indexOfFirst { it.id !in done })
        if (st != null) {
            if (first) s.prompt("routine_${st.id}", "routine_tap") else s.prompt("routine_${st.id}")
        }
        first = false
    }
    LaunchedEffect(steps.size) { s.rounds(done.count { d -> steps.any { it.id == d } }, steps.size) }

    fun tick(st: RoutineStep) {
        if (busy) return
        busy = true
        done = done + st.id
        RoutineMemory.done[RoutineMemory.key(morning)] = done
        s.rounds(done.count { d -> steps.any { it.id == d } }, steps.size)
        s.practised("routine:${st.id}")
        co.launch {
            s.sfx(Sound.Good)
            s.friendMood = Mood.Happy
            s.sayAndWait("routine_${st.id}_done")
            val next = steps.firstOrNull { it.id !in done }
            if (next == null) {
                s.prompt()
                s.sfx(Sound.Sparkle, 0.6f)
                s.sayAndWait(if (morning) "routine_all_morning" else "routine_all_evening")
                s.roundDone(true)
                s.finish()
            } else {
                delay(300)
                s.prompt("routine_${next.id}")
            }
            busy = false
        }
    }

    // the sun / moon switch
    Box(Modifier.offset(540.dp, 6.dp)) {
        RoundButton(Icon.Sun, Modifier.halo(morning, Palette.honey, 45.dp), size = 90.dp, color = if (morning) Palette.honey.lighten(0.3f) else Palette.cream, iconColor = Palette.honey) {
            if (!morning && !busy) morning = true
        }
        RoundButton(Icon.Moon, Modifier.offset(110.dp, 0.dp).halo(!morning, Palette.lavender, 45.dp), size = 90.dp, color = if (!morning) Palette.lavender.lighten(0.3f) else Palette.cream, iconColor = Palette.lavender) {
            if (morning && !busy) morning = false
        }
    }

    if (steps.isEmpty()) return
    val (big, small) = routineCardSizes(steps.size)
    val gap = 18f
    val widths = steps.indices.map { if (it == current) big else small }
    val totalW = widths.sum() + gap * (steps.size - 1)
    var x = (1280f - totalW) / 2
    val centers = steps.indices.map { i -> (x + widths[i] / 2).also { x += widths[i] + gap } }
    val wave = { i: Int -> sin(i * 1.1f) * 26f }

    // a soft dotted path behind the cards
    Canvas(Modifier.size(1280.dp, 688.dp)) {
        for (i in 0 until steps.size - 1) {
            drawLine(
                Palette.cream.copy(alpha = 0.9f),
                Offset(centers[i] * density, (ROW_Y + wave(i)) * density), Offset(centers[i + 1] * density, (ROW_Y + wave(i + 1)) * density),
                strokeWidth = 14f * density, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 30f * density)),
            )
        }
    }
    steps.forEachIndexed { i, st ->
        StepCard(
            st, i, isCurrent = i == current, isDone = st.id in done, size = widths[i], cx = centers[i], cy = ROW_Y + wave(i),
            hint = s.hint && i == current, lang = s.lang,
        ) {
            when {
                i == current -> tick(st)
                else -> s.say("routine_${st.id}")
            }
        }
    }
}

@Composable
private fun StepCard(
    st: RoutineStep, index: Int, isCurrent: Boolean, isDone: Boolean, size: Float, cx: Float, cy: Float, hint: Boolean,
    lang: dev.framio.meadow.core.Lang, onTap: () -> Unit,
) {
    val sz by animateFloatAsState(size, tween(500, easing = FastOutSlowInEasing), label = "size")
    val px by animateFloatAsState(cx, tween(500, easing = FastOutSlowInEasing), label = "x")
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80L * index)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    val star = remember { Animatable(if (isDone) 1f else 0f) }
    LaunchedEffect(isDone) { if (isDone) star.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) else star.snapTo(0f) }
    Box(
        Modifier
            .zIndex(if (isCurrent) 2f else 1f)
            .graphicsLayer {
                translationX = (px - sz / 2) * density
                translationY = (cy - sz / 2 + (1f - enter.value) * 40f) * density
                alpha = enter.value
            },
    ) {
        SoftCard(
            if (isDone) Palette.honey.lighten(0.55f) else Palette.cream,
            Modifier
                .size(sz.dp)
                .hintPulse(hint)
                .pressable(sound = if (isCurrent) null else Sound.Tap, onTap = onTap),
            corner = (sz * 0.18f).dp,
        ) {
            Sprite(st.sprite, Modifier.size((sz * 0.84f).dp).graphicsLayer { alpha = if (isDone && !isCurrent) 0.75f else 1f })
        }
        if (star.value > 0f) {
            Sprite(
                "ob_star",
                Modifier
                    .offset((sz * 0.62f).dp, (-sz * 0.14f).dp)
                    .size((sz * 0.5f).coerceAtMost(110f).dp)
                    .graphicsLayer { scaleX = star.value; scaleY = star.value },
            )
        }
        if (isCurrent) {
            BasicText(
                st.title[lang], style = Type.label.copy(textAlign = TextAlign.Center, color = Palette.inkSoft),
                modifier = Modifier.offset(0.dp, (sz + 10f).dp).width(sz.dp).align(Alignment.TopCenter),
            )
        }
    }
}
