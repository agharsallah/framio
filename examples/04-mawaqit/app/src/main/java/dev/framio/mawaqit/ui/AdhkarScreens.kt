package dev.framio.mawaqit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarProgress
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.data.AdhkarController
import dev.framio.mawaqit.data.AdhkarUi
import dev.framio.mawaqit.data.AdhkarView
import dev.framio.mawaqit.engine.DisplayState
import kotlin.math.abs
import kotlin.math.min

/**
 * The adhkar section: the collections, the reader (one dhikr at a time, tap anywhere to count), the end of a
 * collection and a free tasbih. Laid out for 1280×800 and 800×1280 like the rest; read from a metre or two away.
 */
@Composable
fun AdhkarScreen(st: DisplayState) {
    val a = LocalAdhkar.current ?: return
    val ui by a.ui.collectAsState()
    val progress by a.progress.collectAsState()
    BackHandler(enabled = ui.view != null) { a.back() }
    val view = ui.view ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        AnimatedContent(
            view,
            contentKey = { v ->
                when (v) {
                    AdhkarView.Categories -> "categories"
                    is AdhkarView.Reader -> "reader:" + v.category.id
                    is AdhkarView.Done -> "done:" + v.category.id
                    AdhkarView.Tasbih -> "tasbih"
                    AdhkarView.Log -> "log"
                    AdhkarView.Schedules -> "schedules"
                }
            },
            transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(450)) },
            label = "adhkar",
        ) { v ->
            when (v) {
                AdhkarView.Categories -> Categories(st, a, ui, progress, wide)
                is AdhkarView.Reader -> Reader(st, a, v, ui, progress, wide)
                is AdhkarView.Done -> Done(st, a, v, progress)
                AdhkarView.Tasbih -> Tasbih(st, a, ui, progress, wide)
                AdhkarView.Log -> AdhkarLogScreen(st, a, wide)
                AdhkarView.Schedules -> AdhkarSchedulesScreen(st, a, wide)
            }
        }
    }
}

// ---------------------------------------------------------------- categories

@Composable
private fun Categories(st: DisplayState, a: AdhkarController, ui: AdhkarUi, progress: AdhkarProgress, wide: Boolean) {
    val p = LocalPalette.current
    val s = st.strings
    val current by a.current.collectAsState()
    // 11 collections and the tasbih: 4 × 3 lying, 3 × 4 standing
    val cols = if (wide) 4 else 3
    SideEffect { a.gridColumns = cols }
    val cards: List<AdhkarCategory?> = AdhkarController.CARDS + null // the tasbih last
    Column(Modifier.fillMaxSize().padding(start = if (wide) 56.dp else 40.dp, end = if (wide) 56.dp else 40.dp, top = 30.dp, bottom = 24.dp)) {
        Header(st, s["adhkar"], Strings.of("ar")["adhkar"], onBack = { a.close() }, trailing = { LogPill(st) { a.openLog() } })
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.chunked(cols).forEachIndexed { row, chunk ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chunk.forEachIndexed { col, c ->
                        val i = row * cols + col
                        CategoryCard(st, a, c, progress, focused = ui.focus == i, now = c != null && c == current)
                    }
                }
            }
        }
        BasicText(
            s["adhkar_hint_remote"], Modifier.fillMaxWidth().padding(top = 18.dp),
            style = Base.copy(color = p.inkFaint, fontSize = 19.sp, textAlign = TextAlign.Center).let { if (s.rtl) it.arabic().copy(fontSize = 22.sp) else it },
        )
    }
}

@Composable
private fun Header(st: DisplayState, title: String, arabic: String, onBack: () -> Unit, close: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    val s = st.strings
    Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
        AdRoundButton(onBack, size = 58.dp) {
            if (close) AdCross(p.ink, Modifier.size(34.dp)) else AdChevron(p.ink, toEnd = false, rtl = s.rtl, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.width(22.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            BasicText(title, Modifier.alignByBaseline(), style = Base.copy(color = p.ink, fontSize = 44.sp, fontFamily = Fonts.light).let { if (s.rtl) it.arabic().copy(fontSize = 48.sp) else it }, maxLines = 1)
            if (!s.rtl) {
                Spacer(Modifier.width(18.dp))
                BasicText(arabic, Modifier.alignByBaseline(), style = Base.arabic().copy(color = p.accent2, fontSize = 36.sp), maxLines = 1)
            }
        }
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(22.dp))
        }
        BasicText(Format.time(st.now, st.clock24h), style = digits(38.sp, p.inkDim))
    }
}

@Composable
private fun RowScope.CategoryCard(st: DisplayState, a: AdhkarController, c: AdhkarCategory?, progress: AdhkarProgress, focused: Boolean, now: Boolean) {
    val p = LocalPalette.current
    val s = st.strings
    val shape = RoundedCornerShape(24.dp)
    val summary = c?.let { a.summary(it, progress) }
    Column(
        Modifier.weight(1f).fillMaxHeight().card(p, highlighted = now)
            .then(if (focused) Modifier.border(3.dp, p.ink.copy(alpha = 0.8f), shape) else Modifier)
            .adClick { if (c == null) a.open(null, tasbih = true) else a.open(c) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AdhkarGlyph(c, p.accent, Modifier.size(46.dp), second = p.accent2)
            Spacer(Modifier.weight(1f))
            // one status, the most telling: done, its time now, a streak, which prayer
            when {
                summary?.complete == true -> Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(p.accent), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(32.dp)) { adCheck(p.onAccent, 3.5.dp.toPx()) }
                }
                now -> Pill(s["now"], p.accent, p.onAccent)
                summary != null && summary.streak > 1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    AdFlame(p.accent, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicText(summary.streak.toString(), style = digits(22.sp, p.ink, light = false))
                }
                summary?.after != null -> BasicText(s["adhkar_after"].replace("{p}", s.prayer(summary.after)),
                    style = Base.copy(color = p.inkDim, fontSize = 17.sp).let { if (s.rtl) it.arabic().copy(fontSize = 20.sp) else it }, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        val name = if (c == null) s["tasbih"] else s[c.key]
        // Longer names (French, Dutch…) step down rather than being cut
        BasicText(name, Modifier.fillMaxWidth(), style = Base.copy(color = p.ink, fontWeight = FontWeight.Medium).let { if (s.rtl) it.arabic().copy(fontWeight = FontWeight.Bold) else it },
            maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = if (s.rtl) 28.sp else 25.sp, stepSize = 1.sp))
        if (!s.rtl) {
            BasicText(Strings.of("ar")[c?.key ?: "tasbih"], Modifier.fillMaxWidth(), style = Base.arabic().copy(color = p.accent2, lineHeight = 1.45.em),
                maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = 25.sp, stepSize = 1.sp))
        }
        Spacer(Modifier.height(10.dp))
        if (summary != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(50)).background(p.hairline)) {
                    val f = if (summary.total == 0) 0f else summary.done / summary.total.toFloat()
                    if (f > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(p.accent))
                }
                Spacer(Modifier.width(12.dp))
                BasicText("${summary.done}/${summary.total}", style = digits(21.sp, p.ink, light = false).copy(textDirection = TextDirection.Ltr))
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(a.tasbihToday(progress).toString(), Modifier.alignByBaseline(), style = digits(30.sp, p.ink))
                Spacer(Modifier.width(10.dp))
                BasicText(s["adhkar_today"], Modifier.alignByBaseline(), style = Base.copy(color = p.inkDim, fontSize = 18.sp).let { if (s.rtl) it.arabic() else it })
            }
        }
    }
}

// ---------------------------------------------------------------- reader

@Composable
private fun Reader(st: DisplayState, a: AdhkarController, v: AdhkarView.Reader, ui: AdhkarUi, progress: AdhkarProgress, wide: Boolean) {
    val items = a.items(v.category, v.after)
    if (items.isEmpty()) return
    val day = a.day(v.category)
    val session = AdhkarProgress.session(v.category, v.after)
    val rtl = st.strings.rtl
    val pulse = rememberPulse(ui.pulse)
    // Tap anywhere to count; a horizontal swipe moves (towards the end of the reading direction = next)
    val tapAndSwipe = Modifier
        .adClick { a.count() }
        .pointerInput(v.index, rtl) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                if (abs(dx) > 90.dp.toPx()) a.go(if ((dx < 0) != rtl) +1 else -1)
            }) { change, amount ->
                change.consume()
                dx += amount
            }
        }
    val segments = @Composable { m: Modifier ->
        AdSegments(items.size, { i -> min(1f, progress.count(day, session, items[i].id) / items[i].count(v.after).toFloat()) }, v.index, m)
    }
    val card = @Composable { m: Modifier ->
        AnimatedContent(
            v.index, m,
            transitionSpec = {
                val forward = targetState > initialState
                val dir = if (forward != rtl) 1 else -1
                (slideInHorizontally(tween(380)) { it / 8 * dir } + fadeIn(tween(380))) togetherWith
                    (slideOutHorizontally(tween(300)) { -it / 8 * dir } + fadeOut(tween(300)))
            },
            label = "dhikr",
        ) { i -> items.getOrNull(i)?.let { DhikrCard(st, it, Modifier.fillMaxSize()) } }
    }
    val d = items.getOrNull(v.index) ?: return
    val n = progress.count(day, session, d.id)
    val target = d.count(v.after)
    if (wide) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 26.dp, bottom = 26.dp)) {
            ReaderTop(st, a, v, items.size)
            Spacer(Modifier.height(16.dp))
            segments(Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(22.dp))
            Row(Modifier.weight(1f).then(tapAndSwipe)) {
                card(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleX = 1f - (1f - pulse) * 0.15f; scaleY = scaleX })
                Spacer(Modifier.width(30.dp))
                Column(Modifier.width(320.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    Counter(n, target, pulse, 280.dp)
                    Spacer(Modifier.height(16.dp))
                    BasicText(if (n >= target) "" else st.strings["adhkar_tap"], style = Base.copy(color = p().inkFaint, fontSize = 20.sp).let { if (rtl) it.arabic() else it })
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        AdRoundButton({ a.go(-1) }, size = 72.dp) { AdChevron(p().ink, toEnd = false, rtl = rtl, modifier = Modifier.size(44.dp)) }
                        AdRoundButton({ a.go(+1) }, size = 72.dp) { AdChevron(p().ink, toEnd = true, rtl = rtl, modifier = Modifier.size(44.dp)) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Toggles(st, a, d)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 34.dp, end = 34.dp, top = 26.dp, bottom = 28.dp)) {
            ReaderTop(st, a, v, items.size)
            Spacer(Modifier.height(14.dp))
            segments(Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f).then(tapAndSwipe)) {
                card(Modifier.weight(1f).fillMaxWidth().graphicsLayer { scaleX = 1f - (1f - pulse) * 0.15f; scaleY = scaleX })
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    AdRoundButton({ a.go(-1) }, size = 80.dp) { AdChevron(p().ink, toEnd = false, rtl = rtl, modifier = Modifier.size(48.dp)) }
                    Counter(n, target, pulse, 250.dp)
                    AdRoundButton({ a.go(+1) }, size = 80.dp) { AdChevron(p().ink, toEnd = true, rtl = rtl, modifier = Modifier.size(48.dp)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Toggles(st, a, d) }
        }
    }
}

@Composable
private fun p() = LocalPalette.current

/** 1 at rest; dips on each new count and springs back (only scale animates). */
@Composable
private fun rememberPulse(pulse: Int): Float {
    val anim = remember { Animatable(1f) }
    val seen = remember { mutableIntStateOf(pulse) }
    LaunchedEffect(pulse) {
        if (pulse == seen.intValue) return@LaunchedEffect
        seen.intValue = pulse
        anim.snapTo(0.9f)
        anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }
    return anim.value
}

@Composable
private fun ReaderTop(st: DisplayState, a: AdhkarController, v: AdhkarView.Reader, total: Int) {
    val p = LocalPalette.current
    val s = st.strings
    Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
        AdRoundButton({ a.back() }, size = 58.dp) { AdChevron(p.ink, toEnd = false, rtl = s.rtl, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.width(20.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            AdhkarGlyph(v.category, p.accent, Modifier.size(38.dp), second = p.accent2)
            Spacer(Modifier.width(14.dp))
            val style = Base.copy(color = p.ink, fontSize = 32.sp, fontWeight = FontWeight.Medium)
            BasicText(s[v.category.key], style = if (s.rtl) style.arabic().copy(fontSize = 36.sp) else style, maxLines = 1)
            v.after?.let {
                Spacer(Modifier.width(14.dp))
                BasicText("· " + s["adhkar_after"].replace("{p}", s.prayer(it)), style = Base.copy(color = p.inkDim, fontSize = 24.sp).let { st2 -> if (s.rtl) st2.arabic() else st2 }, maxLines = 1)
            }
        }
        AdRoundButton({ a.reset() }, size = 50.dp) { AdReset(p.inkDim, Modifier.size(30.dp)) }
        Spacer(Modifier.width(20.dp))
        BasicText("${v.index + 1} / $total", style = digits(32.sp, p.inkDim, light = false).copy(textDirection = TextDirection.Ltr))
        Spacer(Modifier.width(26.dp))
        BasicText(Format.time(st.now, st.clock24h), style = digits(32.sp, p.inkFaint))
        Spacer(Modifier.width(22.dp))
        AdRoundButton({ a.close() }, size = 58.dp) { AdCross(p.ink, Modifier.size(34.dp)) }
    }
}

/** The dhikr: Arabic as large as it fits, then (when on) the transliteration and translation, the virtue and source. */
@Composable
private fun DhikrCard(st: DisplayState, d: Dhikr, modifier: Modifier) {
    val p = LocalPalette.current
    val lang = st.settings.display.language
    val ad = st.settings.adhkar
    // Arabic speakers read the Arabic; the English lines would only be noise for them
    val tl = d.transliteration?.takeIf { ad.transliteration && lang != "ar" }
    val tr = d.translation?.takeIf { ad.translation && lang != "ar" }
    val virtue = d.virtue(lang)
    Column(modifier.card(p).padding(start = 44.dp, end = 44.dp, top = 30.dp, bottom = 22.dp)) {
        // the everyday and distress du'as say when: "Entering the mosque", "In distress"
        d.title(lang)?.let { t ->
            val ts = Base.copy(color = p.accent, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            BasicText(t, Modifier.fillMaxWidth().padding(bottom = 6.dp), style = if (hasArabic(t)) ts.arabic().copy(fontSize = 28.sp) else ts.copy(textDirection = TextDirection.Ltr), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(if (tl != null || tr != null) 1.65f else 1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val style = Base.arabic().copy(color = p.ink, textAlign = TextAlign.Center, lineHeight = 1.8.em)
            if (d.arabic.length > LONG_ARABIC) {
                // Al 'Imran 190–200 on waking: too long to fit at a readable size, it scrolls instead
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    BasicText(d.arabic, Modifier.fillMaxWidth(), style = style.copy(fontSize = 32.sp))
                }
            } else {
                BasicText(d.arabic, Modifier.fillMaxWidth(), style = style, autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 76.sp, stepSize = 2.sp))
            }
        }
        if (tl != null || tr != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(120.dp).height(2.dp).clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = 0.5f)))
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                val latin = TextStyle(fontFamily = Fonts.light, textAlign = TextAlign.Center, lineHeight = 1.32.em, textDirection = TextDirection.Ltr)
                tl?.let { FitText(it, latin.copy(color = p.accent2, fontStyle = FontStyle.Italic), if (tr != null) 0.8f else 1f, 30.sp) }
                if (tl != null && tr != null) Spacer(Modifier.height(10.dp))
                tr?.let { FitText(it, latin.copy(color = p.ink.copy(alpha = 0.86f)), 1.2f, 30.sp) }
            }
        }
        if (virtue != null || d.reference != null) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (virtue != null) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(p.accent))
                    Spacer(Modifier.width(12.dp))
                    val vs = Base.copy(color = p.inkDim, fontSize = 19.sp, lineHeight = 26.sp)
                    BasicText(virtue, Modifier.weight(1f), style = if (lang == "ar" && hasArabic(virtue)) vs.arabic().copy(fontSize = 21.sp, lineHeight = 32.sp) else vs.copy(textDirection = TextDirection.Ltr), maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else Spacer(Modifier.weight(1f))
                d.reference?.let {
                    Spacer(Modifier.width(18.dp))
                    BasicText(it, Modifier.clip(RoundedCornerShape(50)).border(1.dp, p.hairline, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                        style = Base.copy(color = p.inkFaint, fontSize = 18.sp, textDirection = TextDirection.Ltr), maxLines = 1)
                }
            }
        }
    }
}

private const val LONG_ARABIC = 1100

@Composable
private fun ColumnScope.FitText(text: String, style: TextStyle, weight: Float, max: androidx.compose.ui.unit.TextUnit) {
    Box(Modifier.weight(weight).fillMaxWidth(), contentAlignment = Alignment.Center) {
        BasicText(text, Modifier.fillMaxWidth(), style = style, autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = max, stepSize = 1.sp))
    }
}

/** The big round counter: what is left to say, the ring filling as it is said; a check when done. */
@Composable
private fun Counter(n: Int, target: Int, pulse: Float, size: Dp) {
    val p = LocalPalette.current
    val done = n >= target
    val fraction by animateFloatAsState(if (target == 0) 0f else n / target.toFloat(), tween(220), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // a soft halo grows out of the ring on each count
        Box(Modifier.fillMaxSize().graphicsLayer {
            val t = ((1f - pulse) / 0.1f).coerceIn(0f, 1f)
            scaleX = 1f + (1f - t) * 0.12f
            scaleY = scaleX
            alpha = t * 0.5f
        }.clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(50)).background(if (done) p.accent.copy(alpha = 0.12f) else p.card))
        AdRing(fraction, p.accent, p.hairline, 14.dp, Modifier.fillMaxSize().graphicsLayer { scaleX = pulse; scaleY = pulse })
        if (done) {
            Canvas(Modifier.size(size * 0.5f)) { adCheck(p.accent, 12.dp.toPx()) }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText((target - n).toString(), style = digits((size.value * 0.4f).sp, p.ink).copy(fontWeight = FontWeight.Normal))
                if (target > 1) BasicText("× $target", Modifier.padding(top = 6.dp), style = Base.copy(color = p.inkDim, fontSize = 22.sp, textDirection = TextDirection.Ltr))
            }
        }
    }
}

@Composable
private fun Toggles(st: DisplayState, a: AdhkarController, d: Dhikr) {
    val s = st.strings
    val ad = st.settings.adhkar
    val latin = st.settings.display.language != "ar"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (latin && d.translation != null) Toggle(s["adhkar_translation_short"], ad.translation) { a.toggle("translation") }
        if (latin && d.transliteration != null) Toggle(s["adhkar_translit_short"], ad.transliteration) { a.toggle("transliteration") }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.height(52.dp).clip(RoundedCornerShape(50))
            .background(if (on) p.accent.copy(alpha = 0.2f) else p.card)
            .border(1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(50))
            .adClick(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.inkFaint))
        Spacer(Modifier.width(10.dp))
        val style = Base.copy(color = if (on) p.ink else p.inkDim, fontSize = 20.sp)
        BasicText(text, style = if (hasArabic(text)) style.arabic() else style, maxLines = 1)
    }
}

// ---------------------------------------------------------------- done

@Composable
private fun Done(st: DisplayState, a: AdhkarController, v: AdhkarView.Done, progress: AdhkarProgress) {
    val p = LocalPalette.current
    val s = st.strings
    val summary = a.summary(v.category, progress)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Halo(p.accent)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = 0.14f)))
                AdRing(1f, p.accent, p.hairline, 12.dp, Modifier.fillMaxSize())
                Canvas(Modifier.size(104.dp)) { adCheck(p.accent, 11.dp.toPx()) }
            }
            Spacer(Modifier.height(34.dp))
            Label(s["adhkar_done"], p.accent, size = 20.sp)
            Spacer(Modifier.height(12.dp))
            BasicText(s[v.category.key], style = Base.copy(color = p.ink, fontSize = 58.sp, fontFamily = Fonts.light).let { if (s.rtl) it.arabic().copy(fontSize = 62.sp) else it })
            Spacer(Modifier.height(10.dp))
            BasicText(Strings.of("ar")["adhkar_accept"], style = Base.arabic().copy(color = p.accent2, fontSize = 44.sp, lineHeight = 66.sp))
            if (!s.rtl) BasicText(s["adhkar_accept"], style = Base.copy(color = p.inkDim, fontSize = 26.sp, fontFamily = Fonts.light))
            if (summary.streak > 0) {
                Spacer(Modifier.height(22.dp))
                Row(Modifier.clip(RoundedCornerShape(50)).background(p.card).padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AdFlame(p.accent, Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    BasicText(s["adhkar_streak"].replace("{n}", summary.streak.toString()), style = Base.copy(color = p.ink, fontSize = 24.sp).let { if (s.rtl) it.arabic() else it })
                }
            }
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdPill(s["adhkar_again"], { a.reset(v.category) }, leading = { AdReset(p.inkDim, Modifier.size(26.dp)) })
                AdPill(s["adhkar"], { a.back() })
                LogPill(st) { a.openLog() }
                AdPill(s["adhkar_close"], { a.close() }, primary = true)
            }
        }
    }
}

/** Two slow rings growing behind the end screen: only scale and alpha animate. */
@Composable
private fun Halo(color: Color) {
    val t = rememberInfiniteTransition(label = "halo")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "f")
    for (i in 0 until 2) {
        val k = (f + i / 2f) % 1f
        Canvas(Modifier.size(560.dp).graphicsLayer { scaleX = 0.45f + 0.55f * k; scaleY = scaleX; alpha = (1f - k) * 0.4f }) {
            drawCircle(color, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------- tasbih

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tasbih(st: DisplayState, a: AdhkarController, ui: AdhkarUi, progress: AdhkarProgress, wide: Boolean) {
    val p = LocalPalette.current
    val s = st.strings
    val phrases = a.book.tasbih
    val phrase = phrases.firstOrNull { it.id == progress.tasbihPhrase } ?: phrases.firstOrNull() ?: return
    val count = a.tasbihToday(progress)
    val pulse = rememberPulse(ui.pulse)
    val tap = Modifier.adClick { a.count() }
    val round = 33
    val chips = @Composable {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            phrases.forEach { ph ->
                val on = ph.id == phrase.id
                BasicText(
                    ph.arabic,
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.card).border(1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(50))
                        .adClick { a.setTasbihPhrase(ph.id) }
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                    style = Base.arabic().copy(color = if (on) p.onAccent else p.ink, fontSize = 28.sp, lineHeight = 44.sp), maxLines = 1,
                )
            }
        }
    }
    val words = @Composable { centered: Boolean ->
        // absolute sides: the Arabic and the English under it line up whatever their own directions
        val align = if (centered) TextAlign.Center else if (s.rtl) TextAlign.Right else TextAlign.Left
        BasicText(phrase.arabic, Modifier.fillMaxWidth(), style = Base.arabic().copy(color = p.ink, fontSize = 92.sp, lineHeight = 140.sp, textAlign = align, fontWeight = FontWeight.Bold))
        if (!s.rtl) phrase.translation?.let {
            CompositionLocalLtr {
                BasicText(it, Modifier.fillMaxWidth(), style = Base.copy(color = p.inkDim, fontSize = 30.sp, fontFamily = Fonts.light, textAlign = align))
            }
        }
        phrase.transliteration?.takeIf { !s.rtl }?.let {
            BasicText(it, Modifier.fillMaxWidth().padding(top = 4.dp), style = Base.copy(color = p.accent2, fontSize = 24.sp, fontStyle = FontStyle.Italic, fontFamily = Fonts.light, textAlign = align, textDirection = TextDirection.Ltr))
        }
    }
    val ring = @Composable { size: Dp ->
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val fraction by animateFloatAsState((count % round) / round.toFloat(), tween(200), label = "tasbih")
            Box(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(50)).background(p.card))
            AdRing(if (count > 0 && count % round == 0) 1f else fraction, p.accent, p.hairline, 16.dp, Modifier.fillMaxSize().graphicsLayer { scaleX = pulse; scaleY = pulse })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(count.toString(), style = digits((size.value * 0.34f).sp, p.ink))
                BasicText(if (count >= round) "${count / round} × $round" else "/ $round", style = Base.copy(color = p.inkDim, fontSize = 24.sp, textDirection = TextDirection.Ltr))
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = if (wide) 56.dp else 40.dp, end = if (wide) 56.dp else 40.dp, top = 30.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Header(st, s["tasbih"], Strings.of("ar")["tasbih"], onBack = { a.back() }, close = false) }
            Spacer(Modifier.width(18.dp))
            AdRoundButton({ a.reset() }, size = 58.dp) { AdReset(p.inkDim, Modifier.size(34.dp)) }
        }
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth().then(tap), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 40.dp)) {
                    words(false)
                    Spacer(Modifier.height(40.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { chips() }
                }
                ring(420.dp)
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().then(tap), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { words(true) }
                ring(440.dp)
                chips()
            }
        }
        BasicText(s["adhkar_tap"], Modifier.fillMaxWidth(), style = Base.copy(color = p.inkFaint, fontSize = 19.sp, textAlign = TextAlign.Center).let { if (s.rtl) it.arabic() else it })
    }
}

/** English inside an Arabic layout stays left to right. */
@Composable
private fun CompositionLocalLtr(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
