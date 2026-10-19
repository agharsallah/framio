package dev.framio.meadow.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.framio.meadow.LocalApp
import dev.framio.meadow.core.Lang
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.drawIcon
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The way in for grown-ups: a quiet gear that must be held for two seconds (a ring fills up), then
 * a question written in words that a pre-reader can't answer. Children who tap it get nothing.
 */
@Composable
fun ParentGateButton(onOpen: () -> Unit, modifier: Modifier = Modifier, light: Boolean = false) {
    val hold = remember { Animatable(0f) }
    val co = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var asking by remember { mutableStateOf(false) }
    val ink = if (light) Palette.moon else Palette.ink
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.08f))
            .drawBehind {
                drawIcon(Icon.Gear, ink.copy(alpha = 0.45f))
                if (hold.value > 0f) drawArc(Palette.honey, -90f, 360f * hold.value, false, style = Stroke(6.dp.toPx()))
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    job = co.launch {
                        hold.snapTo(0f)
                        hold.animateTo(1f, tween(2000, easing = LinearEasing))
                        asking = true
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    co.launch { hold.animateTo(0f, tween(200)) }
                })
            },
    )
    if (asking) GrownUpQuestion(onDismiss = { asking = false }, onPass = { asking = false; onOpen() })
}

private val WORDS_EN = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
private val WORDS_DE = listOf("eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")

@Composable
private fun GrownUpQuestion(onDismiss: () -> Unit, onPass: () -> Unit) {
    val app = LocalApp.current
    val lang = app.settings.value.lang
    val target = remember { List(3) { Random.nextInt(1, 10) } }
    var typed by remember { mutableStateOf(listOf<Int>()) }
    val words = if (lang == Lang.DE) WORDS_DE else WORDS_EN
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SoftCard(Palette.cream, Modifier.padding(24.dp), corner = 36.dp) {
            Column(Modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(if (lang == Lang.DE) "Für Erwachsene" else "For grown-ups", style = Type.title.copy(fontSize = 28.sp))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    (if (lang == Lang.DE) "Tippe: " else "Tap: ") + target.joinToString("  ·  ") { words[it - 1] },
                    style = Type.body.copy(fontSize = 24.sp),
                )
                Spacer(Modifier.height(20.dp))
                for (row in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                        for (col in 1..3) {
                            val n = row * 3 + col
                            SoftCard(
                                Palette.paperDeep,
                                Modifier
                                    .size(78.dp)
                                    .pressable {
                                        val next = typed + n
                                        when {
                                            next == target -> onPass()
                                            target.subList(0, next.size) == next -> typed = next
                                            else -> typed = emptyList()
                                        }
                                    },
                                corner = 24.dp, elevation = 2.dp,
                            ) { BasicText("$n", style = Type.clay(34.sp)) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                BasicText("●".repeat(typed.size) + "○".repeat(3 - typed.size), style = Type.body)
            }
        }
    }
}
