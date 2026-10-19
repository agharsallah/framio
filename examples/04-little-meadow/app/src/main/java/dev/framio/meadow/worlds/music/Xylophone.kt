package dev.framio.meadow.worlds.music

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SLOT = 122.dp          // one bar's column, and its touch area
private val BAR_W = 156.dp         // the sprite (160×512) shown at this size; the bar body is ~70 % of its width
private val BAR_H = 500.dp
private val BARS_X = 262.dp
private val BARS_TOP = 172.dp
private const val BODY = 0.94f     // the longest bar's share of the sprite's height
private const val BEAT_MS = 520

private val TINTS = listOf(Palette.red, Palette.orange, Palette.yellow, Palette.green, Palette.mint, Palette.blue, Palette.purple, Palette.pink)

private class InstrumentChoice(val instrument: Instrument, val icon: String)

private val INSTRUMENTS = listOf(
    InstrumentChoice(Instrument.Marimba, "mu_mallet"),
    InstrumentChoice(Instrument.Bell, "ob_bell"),
    InstrumentChoice(Instrument.MusicBox, "mu_musicbox"),
)

/**
 * Xylophone: eight soft clay bars, C to C, longest and lowest on the left. Tap or slide along them
 * to play; three sounds to choose from (marimba, bells, music box). Pick a song card and the next
 * bar glows: the child plays at their own pace, and when the song is complete it plays itself
 * back once in rhythm. Three songs end the activity; free play never ends by itself.
 */
@Composable
fun Xylophone(s: PlayScope) {
    var instrument by remember { mutableStateOf(Instrument.Marimba) }
    var song by remember { mutableStateOf<Song?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }   // the song plays itself back: hands off for a moment
    var songsDone by remember { mutableIntStateOf(0) }
    val presses = remember { List(8) { Animatable(0f) } }
    val co = rememberCoroutineScope()

    fun sound(i: Int) {
        s.app.sfx.note(instrument, XYLO_MIDI[i])
        co.launch {
            presses[i].snapTo(1f)
            presses[i].animateTo(0f, tween(650, easing = FastOutSlowInEasing))
        }
    }

    fun songComplete(done: Song) {
        playing = true
        co.launch {
            delay(450)
            s.praiseAndWait()
            s.sayAndWait("xylo_listen")
            delay(350)
            playBack(done) { sound(it) }
            delay(700)
            songsDone++
            s.rounds(songsDone, XYLO_SONGS_TO_FINISH)
            s.practised("song:${done.id}")
            s.roundDone(firstTry = true)
            s.friendMood = Mood.Happy
            song = null
            playing = false
            if (songsDone >= XYLO_SONGS_TO_FINISH) s.finish() else s.prompt("xylo_another")
        }
    }

    fun hit(i: Int) {
        if (playing) return
        sound(i)
        val sg = song ?: return
        if (sg.notes[pos].bar == i) {
            pos++
            if (pos == sg.notes.size) songComplete(sg)
        }
    }

    LaunchedEffect(Unit) {
        s.rounds(0, XYLO_SONGS_TO_FINISH)
        s.awaitQuiet()
        s.prompt("xylo_free")
    }

    val next = song?.notes?.getOrNull(pos)?.bar.takeIf { !playing }
    Box(Modifier.fillMaxSize()) {
        // the sound pickers, above Bo
        INSTRUMENTS.forEachIndexed { k, choice ->
            val on = instrument == choice.instrument
            SoftCard(
                if (on) Palette.mint.lighten(0.35f) else Palette.cream,
                Modifier
                    .offset(30.dp, (16 + k * 140).dp)
                    .size(120.dp)
                    .halo(on, Palette.mint, 40.dp)
                    .pressable(sound = null) {
                        instrument = choice.instrument
                        co.launch { // a little C-E-G so the child hears the new sound
                            for (m in intArrayOf(72, 76, 79)) {
                                s.app.sfx.note(choice.instrument, m, 0.8f)
                                delay(130)
                            }
                        }
                    },
            ) { Sprite(choice.icon, Modifier.size(88.dp)) }
        }

        // the songs
        SONGS.forEachIndexed { k, sg ->
            val on = song === sg
            SoftCard(
                if (on) Palette.honey.lighten(0.45f) else Palette.cream,
                Modifier
                    .offset((352 + k * 164).dp, 8.dp)
                    .size(130.dp)
                    .halo(on, Palette.honey, 40.dp)
                    .pressable(enabled = !playing) {
                        if (on) {
                            song = null
                            s.prompt("xylo_free")
                        } else {
                            song = sg
                            pos = 0
                            s.prompt(sg.voice, "xylo_follow")
                        }
                    },
            ) { Sprite(sg.icon, Modifier.size(96.dp)) }
        }

        Rails(Modifier.offset(BARS_X, BARS_TOP).size(SLOT * 8, BAR_H))
        BarsArea(onHit = ::hit, modifier = Modifier.offset(BARS_X, BARS_TOP).size(SLOT * 8, BAR_H)) {
            for (i in 0 until 8) Bar(i, presses[i], glow = next == i, hint = next == i && s.hint, modifier = Modifier.offset(SLOT * i, 0.dp))
        }
    }
}

/** Plays [song] by itself in its rhythm, the bars bouncing along. */
private suspend fun playBack(song: Song, sound: (Int) -> Unit) {
    val start = SystemClock.uptimeMillis()
    noteTimes(song, BEAT_MS).forEachIndexed { k, t ->
        val wait = start + t - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        sound(song.notes[k].bar)
    }
    delay((song.notes.last().beats * BEAT_MS).toLong())
}

/**
 * One touch area for all bars, so a finger sliding along them plays each bar it reaches (a
 * glissando) and several fingers can play at once.
 */
@Composable
private fun BarsArea(onHit: (Int) -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val hit by rememberUpdatedState(onHit)
    Box(
        modifier.pointerInput(Unit) {
            val slot = SLOT.toPx()
            fun barAt(x: Float) = (x / slot).toInt().takeIf { x >= 0 && it in 0..7 }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val last = HashMap<PointerId, Int?>()
                barAt(down.position.x)?.let(hit)
                last[down.id] = barAt(down.position.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.none { it.pressed }) break
                    for (c in ev.changes) {
                        if (!c.pressed) {
                            last.remove(c.id)
                            continue
                        }
                        val b = barAt(c.position.x)
                        if (!last.containsKey(c.id) || last[c.id] != b) {
                            if (b != null) hit(b)
                            last[c.id] = b
                        }
                        c.consume()
                    }
                }
            }
        },
    ) { content() }
}

/** A bar: squishes a little and sends out a soft ripple when it sounds; glows when it's next in a song. */
@Composable
private fun Bar(i: Int, press: Animatable<Float, *>, glow: Boolean, hint: Boolean, modifier: Modifier) {
    val len = BAR_H * BODY * barLength(i)
    val tint = TINTS[i]
    Box(modifier.size(SLOT, BAR_H), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(SLOT - 14.dp, len)
                .drawBehind {
                    val p = press.value
                    if (p > 0.01f) {
                        val grow = (1f - p) * 26.dp.toPx()
                        drawRoundRect(
                            tint.copy(alpha = 0.45f * p), Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow),
                            CornerRadius(28.dp.toPx() + grow), style = Stroke(6.dp.toPx() * p + 1f),
                        )
                    }
                }
                .halo(glow, Palette.honey, 30.dp)
                .hintPulse(glow, if (hint) Palette.terracotta else Palette.honey, 30.dp), // the next bar breathes a soft ring
        )
        Sprite(
            "mu_bar_$i",
            Modifier
                .requiredSize(BAR_W, BAR_H)
                .graphicsLayer {
                    val p = press.value
                    scaleX = 1f - 0.035f * p
                    scaleY = 1f - 0.02f * p
                    translationY = 4.dp.toPx() * p
                },
        )
    }
}

/** The two wooden rails the bars rest on, meeting the pegs of each bar. */
@Composable
private fun Rails(modifier: Modifier) {
    Canvas(modifier) {
        val slot = SLOT.toPx()
        val h = size.height
        val wood = Color(0xFFC9976B)
        val woodDark = Color(0xFFA97A55)
        val stroke = 24.dp.toPx()
        for (sign in listOf(-1f, 1f)) {
            fun peg(i: Int) = Offset(slot * (i + 0.5f), h / 2 + sign * 0.36f * h * BODY * barLength(i))
            val a = peg(0) + (peg(0) - peg(7)) * 0.08f
            val b = peg(7) + (peg(7) - peg(0)) * 0.08f
            drawLine(woodDark, a + Offset(0f, 4.dp.toPx()), b + Offset(0f, 4.dp.toPx()), stroke, StrokeCap.Round)
            drawLine(Brush.verticalGradient(listOf(wood.lighten(0.2f), wood), startY = a.y - stroke, endY = a.y + stroke), a, b, stroke, StrokeCap.Round)
        }
    }
}
