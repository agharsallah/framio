package dev.framio.mawaqit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.Texts
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.engine.Overlay
import java.time.Duration

/** The full-screen phases. NORMAL, REMINDER and a non-full-screen countdown stay on [MainScreen]. */
fun isFullScreen(st: DisplayState): Boolean = when (st.phase.kind) {
    PhaseKind.NORMAL, PhaseKind.REMINDER -> false
    PhaseKind.IQAMA_COUNTDOWN -> st.behavior.iqamaFullScreen
    else -> true
}

@Composable
fun PhaseScreen(st: DisplayState) {
    when (st.phase.kind) {
        PhaseKind.ADHAN -> Adhan(st)
        PhaseKind.DUA_AFTER_ADHAN -> TextScreen(st, st.strings["dua_title"], Texts.duaAfterAdhan)
        PhaseKind.IQAMA_COUNTDOWN -> Countdown(st)
        PhaseKind.IQAMA -> Iqama(st)
        PhaseKind.PRAYER -> if (st.behavior.blackScreen) Black() else Praying(st)
        PhaseKind.AZKAR -> Azkar(st)
        PhaseKind.JUMUA -> when {
            st.behavior.jumuaBlackScreen -> Black()
            st.behavior.jumuaReminder -> TextScreen(st, st.strings["jumua_title"], Texts.jumua)
            else -> Praying(st)
        }
        PhaseKind.NIGHT -> if (st.screen.black) Black() else Night(st)
        else -> Unit
    }
}

@Composable
fun Black() = Box(Modifier.fillMaxSize().background(Color.Black))

/** Slowly expanding rings behind the word: only alpha and scale animate, which the GPU does for free. */
@Composable
private fun Rings(color: Color) {
    val t = rememberInfiniteTransition(label = "rings")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.fillMaxSize()) {
        val max = size.minDimension * 0.62f
        for (i in 0 until 3) {
            val f = (phase + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - f) * 0.35f), radius = max * (0.35f + 0.65f * f), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun PrayerTitle(st: DisplayState, size: Int = 56) {
    val p = LocalPalette.current
    val key = st.phase.prayer?.key ?: return
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(st.strings.prayer(key), Modifier.alignByBaseline(), style = Base.copy(color = p.ink, fontSize = size.sp, fontFamily = Fonts.light))
        if (!st.strings.rtl) {
            Spacer(Modifier.width(20.dp))
            BasicText(Strings.arabic(key), Modifier.alignByBaseline(), style = Base.arabic().copy(color = p.accent2, fontSize = (size * 0.85f).sp))
        }
    }
}

@Composable
private fun Adhan(st: DisplayState) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Rings(p.accent)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("الأذان", style = Base.arabic().copy(color = p.accent, fontSize = 150.sp, fontWeight = FontWeight.Bold, lineHeight = 190.sp))
            if (!st.strings.rtl) Label(st.strings["adhan"], p.inkDim, size = 22.sp)
            Spacer(Modifier.height(28.dp))
            PrayerTitle(st)
            st.phase.prayer?.let {
                BasicText(Format.time(it.adhan, st.clock24h), Modifier.padding(top = 8.dp), style = digits(64.sp, p.ink))
            }
        }
    }
}

/** A title, the Arabic text large, its translation under it. */
@Composable
private fun TextScreen(st: DisplayState, title: String, block: Texts.Block) {
    val p = LocalPalette.current
    val lang = st.settings.display.language
    Column(
        Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label(title, p.accent, size = 22.sp)
        Spacer(Modifier.height(36.dp))
        BasicText(
            block.arabic,
            Modifier.widthIn(max = 1100.dp),
            style = Base.arabic().copy(color = p.ink, fontSize = 50.sp, lineHeight = 92.sp, textAlign = TextAlign.Center),
        )
        block.other(lang)?.let {
            Spacer(Modifier.height(30.dp))
            BasicText(it, Modifier.widthIn(max = 1000.dp), style = Base.copy(color = p.inkDim, fontSize = 28.sp, lineHeight = 40.sp, fontFamily = Fonts.light, textAlign = TextAlign.Center))
        }
        block.source?.let { BasicText(it, Modifier.padding(top = 18.dp), style = Base.copy(color = p.inkFaint, fontSize = 20.sp)) }
        if (st.phase.kind == PhaseKind.DUA_AFTER_ADHAN || st.phase.kind == PhaseKind.JUMUA) SmallClock(st, Modifier.padding(top = 36.dp))
    }
}

@Composable
private fun Countdown(st: DisplayState) {
    val p = LocalPalette.current
    val s = st.strings
    val prayer = st.phase.prayer
    val left = prayer?.iqama?.let { Duration.between(st.now, it) } ?: Duration.ZERO
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PrayerTitle(st, 52)
        Spacer(Modifier.height(22.dp))
        Label(s["iqama_in"], p.inkDim, size = 24.sp)
        BasicText(Format.countdown(left), style = digits(250.sp, p.accent))
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneOffGlyph(p.ink, Modifier.size(58.dp))
            Spacer(Modifier.width(18.dp))
            BasicText(s["phones"], style = Base.copy(color = p.ink, fontSize = 34.sp))
        }
        val between = Texts.betweenAdhanIqama.other(st.settings.display.language)
        if (between != null) {
            BasicText(between, Modifier.padding(top = 40.dp).widthIn(max = 1000.dp), style = Base.copy(color = p.inkFaint, fontSize = 22.sp, lineHeight = 31.sp, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun Iqama(st: DisplayState) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Rings(p.accent2)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("الإقامة", style = Base.arabic().copy(color = p.accent2, fontSize = 150.sp, fontWeight = FontWeight.Bold, lineHeight = 190.sp))
            if (!st.strings.rtl) Label(st.strings["iqama"], p.inkDim, size = 22.sp)
            Spacer(Modifier.height(24.dp))
            PrayerTitle(st)
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneOffGlyph(p.ink, Modifier.size(52.dp))
                Spacer(Modifier.width(16.dp))
                BasicText(st.strings["phones"], style = Base.copy(color = p.ink, fontSize = 30.sp))
            }
        }
    }
}

/** During the prayer: almost nothing on screen, nothing moving. */
@Composable
private fun Praying(st: DisplayState) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BasicText(Format.time(st.now, st.clock24h), style = digits(120.sp, p.inkDim))
        Label(st.strings["prayer_now"], p.inkFaint, Modifier.padding(top = 16.dp), size = 22.sp)
    }
}

/** The remembrances after the prayer, one block at a time over the phase's length. */
@Composable
private fun Azkar(st: DisplayState) {
    val p = LocalPalette.current
    val start = st.phase.start
    val end = st.phase.end
    val index = if (start != null && end != null) {
        val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
        val per = total / Texts.azkar.size
        (Duration.between(start, st.now).toMillis() / per.coerceAtLeast(1)).toInt().coerceIn(0, Texts.azkar.lastIndex)
    } else 0
    AnimatedContent(index, transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) }, label = "azkar") { i ->
        val block = Texts.azkar[i]
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Label(st.strings["azkar"], p.accent, size = 22.sp)
            Spacer(Modifier.height(30.dp))
            val long = block.arabic.length > 400
            BasicText(
                block.arabic,
                Modifier.widthIn(max = 1140.dp),
                style = Base.arabic().copy(color = p.ink, fontSize = if (long) 38.sp else 48.sp, lineHeight = if (long) 74.sp else 90.sp, textAlign = TextAlign.Center),
            )
            block.other("en")?.let {
                BasicText(it, Modifier.padding(top = 24.dp).widthIn(max = 1040.dp), style = Base.copy(color = p.inkDim, fontSize = 24.sp, lineHeight = 34.sp, fontFamily = Fonts.light, textAlign = TextAlign.Center))
            }
            block.source?.let { BasicText(it, Modifier.padding(top = 12.dp), style = Base.copy(color = p.inkFaint, fontSize = 18.sp)) }
        }
    }
}

/** Dim: the time and Fajr, drifting a little every minute so the same pixels aren't lit all night. */
@Composable
private fun Night(st: DisplayState) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val m = st.now.hour * 60 + st.now.minute
        val dx = ((m * 37) % 200 - 100).dp
        val dy = ((m * 23) % 120 - 60).dp
        Column(Modifier.align(Alignment.Center).offset(dx, dy), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(Format.time(st.now, st.clock24h), style = digits(150.sp, p.inkDim))
            st.next?.let { n ->
                BasicText(
                    st.strings.prayer(n.key) + "  " + Format.time(n.adhan, st.clock24h),
                    Modifier.padding(top = 8.dp),
                    style = Base.copy(color = p.inkFaint, fontSize = 30.sp),
                )
            }
        }
    }
}

@Composable
private fun SmallClock(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    BasicText(Format.time(st.now, st.clock24h), modifier, style = digits(44.sp, p.inkDim))
}

/** A message sent from the remote panel. */
@Composable
fun MessageScreen(st: DisplayState, overlay: Overlay) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Label(overlay.title ?: st.strings["message"], p.accent, size = 24.sp)
        Spacer(Modifier.height(32.dp))
        // Any length from the panel: steps down until it fits between the title and the hint
        val arabic = hasArabic(overlay.text)
        val style = Base.copy(color = p.ink, lineHeight = if (arabic) 1.6.em else 1.3.em, fontFamily = Fonts.light, textAlign = TextAlign.Center, textDirection = TextDirection.Ltr)
        BasicText(
            overlay.text,
            Modifier.widthIn(max = 1100.dp).weight(1f, fill = false),
            style = if (arabic) style.arabic().copy(lineHeight = 1.6.em) else style,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 64.sp, stepSize = 2.sp),
        )
        BasicText(st.strings["tap_close"], Modifier.padding(top = 48.dp), style = Base.copy(color = p.inkFaint, fontSize = 20.sp))
    }
}

/** No RTC: until NTP sets the clock the frame believes it's 1970. Showing prayer times then would be a lie. */
@Composable
fun SyncScreen(st: DisplayState) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "sync")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize().padding(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BasicText(st.strings["sync"], Modifier.graphicsLayer { alpha = a }, style = Base.copy(color = p.ink, fontSize = 48.sp, fontFamily = Fonts.light))
        BasicText(st.strings["sync_hint"], Modifier.padding(top = 20.dp), style = Base.copy(color = p.inkDim, fontSize = 26.sp, textAlign = TextAlign.Center))
    }
}

/** First start, or no prayer times: how to reach the panel. */
@Composable
fun SetupScreen(st: DisplayState) {
    val p = LocalPalette.current
    val s = st.strings
    Row(Modifier.fillMaxSize().padding(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText("مواقيت", style = Base.arabic().copy(color = p.accent, fontSize = 110.sp, fontWeight = FontWeight.Bold, lineHeight = 150.sp))
            BasicText(if (st.source.loading) s["loading"] else s["no_times"], style = Base.copy(color = p.ink, fontSize = 46.sp, fontFamily = Fonts.light))
            BasicText(s["setup_hint"], Modifier.padding(top = 18.dp, end = 40.dp), style = Base.copy(color = p.inkDim, fontSize = 28.sp, lineHeight = 38.sp))
            st.source.error?.takeIf { it != "no_mosque" }?.let {
                Pill(it, p.card, p.accent, Modifier.padding(top = 24.dp))
            }
        }
        PanelAccess(st, Modifier.width(380.dp))
    }
}

/** The QR code of the LAN panel, its address and the PIN. */
@Composable
fun PanelAccess(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    val url = st.panelUrl ?: return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(url, Modifier.size(300.dp))
        BasicText(st.strings["scan"], Modifier.padding(top = 18.dp), style = Base.copy(color = p.inkDim, fontSize = 22.sp, textAlign = TextAlign.Center))
        BasicText(url.removePrefix("http://").trimEnd('/'), Modifier.padding(top = 6.dp), style = digits(28.sp, p.ink, light = false))
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(st.strings["pin"], p.inkDim)
            Spacer(Modifier.width(14.dp))
            BasicText(st.settings.remote.pin.chunked(3).joinToString(" "), style = digits(40.sp, p.accent, light = false))
        }
    }
}
