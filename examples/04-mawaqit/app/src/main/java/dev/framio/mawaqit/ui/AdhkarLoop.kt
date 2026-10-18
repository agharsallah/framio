package dev.framio.mawaqit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.LoopPage
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.data.AdhkarLoopController
import dev.framio.mawaqit.data.FramePage
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.engine.Slide
import kotlinx.coroutines.delay
import java.time.Duration

// The adhkar display: the frame's second page, a swipe away from the prayer times, and the dhikr slides of the main
// screen. Root draws [MainPages] where it drew the main screen; everything above it (prayer phases, messages, black,
// the adhkar reader) still takes over, and the page comes back as it was.

/** Provided by the activity; null in previews and tests: then there is only the main screen. */
val LocalAdhkarLoop = staticCompositionLocalOf<AdhkarLoopController?> { null }

/** The prayer times and the adhkar display side by side, swiped between; two small dots tell which is up. */
@Composable
fun MainPages(st: DisplayState, onSettings: () -> Unit, main: @Composable () -> Unit) {
    val loop = LocalAdhkarLoop.current
    if (loop == null || !st.settings.loop.enabled) return main()
    val page by loop.page.collectAsState()
    // Root drops this composable under a prayer screen: the page lives in the controller, the pager starts from it
    val pager = rememberPagerState(initialPage = page.ordinal) { FramePage.entries.size }
    LaunchedEffect(page) {
        if (pager.currentPage != page.ordinal) pager.animateScrollToPage(page.ordinal)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { loop.show(FramePage.entries[it], "swipe") }
    }
    Box(Modifier.fillMaxSize()) {
        // The D-pad never focuses into the pages: Compose's focus search would scroll the pager on an arrow (seen on
        // the frame in Arabic: ▶ jumped back to the prayer times). The arrows stay the reader's and the loop's.
        HorizontalPager(pager, Modifier.fillMaxSize().focusGroup().focusProperties { onEnter = { cancelFocusChange() } }, beyondViewportPageCount = 0, key = { it }) { i ->
            if (i == FramePage.MAIN.ordinal) main() else AdhkarLoopScreen(st, loop, onSettings)
        }
        // Bottom centre, in the ticker's lower margin on the main page (its text runs through the middle)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = LocalPalette.current
            for (i in FramePage.entries.indices) {
                val on = pager.currentPage == i
                Box(Modifier.size(if (on) 9.dp else 7.dp).clip(RoundedCornerShape(50)).background(if (on) p.ink.copy(alpha = 0.7f) else p.inkFaint.copy(alpha = 0.45f)))
            }
        }
    }
}

/** The top bar's way to the adhkar display (next to the adhkar chip). */
@Composable
fun AdhkarLoopButton(st: DisplayState) {
    val loop = LocalAdhkarLoop.current ?: return
    if (!st.settings.loop.enabled) return
    val p = LocalPalette.current
    Box(
        Modifier.padding(start = 12.dp).size(48.dp).clip(RoundedCornerShape(50)).background(p.card)
            .border(1.dp, p.hairline, RoundedCornerShape(50))
            .adClick { loop.show(FramePage.ADHKAR, "top bar") },
        contentAlignment = Alignment.Center,
    ) { LoopGlyph(p.inkDim, Modifier.size(28.dp)) }
}

/** Two pages, the second lit: the adhkar display. */
@Composable
fun LoopGlyph(color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
        drawRoundRect(color.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(w * 0.08f, w * 0.2f),
            androidx.compose.ui.geometry.Size(w * 0.4f, w * 0.6f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.07f))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.52f, w * 0.2f), androidx.compose.ui.geometry.Size(w * 0.4f, w * 0.6f), r)
    }
}

// ---------------------------------------------------------------- the page

@Composable
private fun AdhkarLoopScreen(st: DisplayState, loop: AdhkarLoopController, onSettings: () -> Unit) {
    val a = LocalAdhkar.current
    val pos by loop.position.collectAsState()
    // the collection whose time it is decides the playlist (time-aware): read so a change rebuilds it
    val current = a?.current?.collectAsState()?.value
    val lang = st.settings.display.language
    val page = remember(pos, st.settings.loop, lang, current) { loop.current() }
    // A page stays its reading time while it is on screen, then the next one; nothing runs while it isn't
    LaunchedEffect(pos, page?.key) {
        val shown = page ?: return@LaunchedEffect
        delay(shown.millis)
        loop.next(pos)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(start = if (wide) 56.dp else 40.dp, end = if (wide) 56.dp else 40.dp, top = 30.dp, bottom = 30.dp)) {
            LoopStrip(st, onSettings, wide)
            Spacer(Modifier.height(if (wide) 18.dp else 24.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    page, Modifier.fillMaxSize(),
                    contentKey = { it?.key + "@" + pos.cycle },
                    transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(700)) },
                    label = "loop",
                ) { shown ->
                    if (shown == null) Empty(lang) else LoopDhikr(shown, lang, wide) { loop.openInReader(shown) }
                }
            }
        }
    }
}

@Composable
private fun Empty(lang: String) {
    val p = LocalPalette.current
    val s = remember(lang) { Strings.of(lang) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(s["loop_empty"], style = Base.copy(color = p.inkDim, fontSize = 26.sp).let { if (s.rtl) it.arabic() else it })
    }
}

/**
 * The mosque stays on screen: clock and date on one side, the next prayer (or the iqama during its countdown) and the
 * time left on the other, the adhkar reminder chip and the gear.
 */
@Composable
private fun LoopStrip(st: DisplayState, onSettings: () -> Unit, wide: Boolean) {
    val p = LocalPalette.current
    val s = st.strings
    val counting = st.phase.kind == PhaseKind.IQAMA_COUNTDOWN && st.phase.prayer?.iqama != null
    val target: PrayerTime? = if (counting) st.phase.prayer else st.next
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(Format.time(st.now, st.clock24h), Modifier.alignByBaseline(), style = digits(if (wide) 64.sp else 60.sp, p.ink))
                if (st.settings.display.showSeconds) {
                    BasicText(Format.seconds(st.now), Modifier.alignByBaseline().padding(start = 8.dp), style = digits(24.sp, p.accent))
                }
            }
            BasicText(Format.date(st.now, st.settings.display.language), style = Base.copy(color = p.inkDim, fontSize = 22.sp, fontFamily = Fonts.light), maxLines = 1)
        }
        if (target != null) {
            val due = if (counting) target.iqama!! else target.adhan
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val title = if (counting) s["iqama_in"] else s.inPrefix(target.key)
                    BasicText(title, Modifier.alignByBaseline(), style = Base.copy(color = p.inkDim, fontSize = 24.sp).let { if (s.rtl) it.arabic().copy(fontSize = 27.sp) else it }, maxLines = 1)
                    Spacer(Modifier.width(14.dp))
                    BasicText(Format.countdown(Duration.between(st.now, due)), Modifier.alignByBaseline(), style = digits(if (wide) 52.sp else 46.sp, if (counting || st.phase.kind == PhaseKind.REMINDER) p.accent else p.ink))
                }
                val iqama = Format.iqama(target, st.settings.display.iqamaAsOffset, st.clock24h)
                val line = buildString {
                    append(s["adhan"]).append(' ').append(Format.time(target.adhan, st.clock24h))
                    if (iqama != null) append("  ·  ").append(s["iqama"]).append(' ').append(iqama)
                }
                BasicText(line, style = Base.copy(color = p.inkFaint, fontSize = 20.sp), maxLines = 1)
            }
        }
        AdhkarTopChip(st)
        Spacer(Modifier.width(16.dp))
        GearGlyph(
            p.inkFaint,
            Modifier.size(44.dp).clip(RoundedCornerShape(50))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSettings)
                .padding(8.dp),
        )
    }
}

/**
 * One page of the loop: the collection (and the occasion), the Arabic as large as it fits, then its meaning when it
 * is short enough to sit under it; a meaning page shows the translation alone. Recomposes only when the page changes,
 * not with the clock.
 */
@Composable
private fun LoopDhikr(page: LoopPage, lang: String, wide: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    // the language, not the second's Strings: the display state is new every second, this page is not
    val s = remember(lang) { Strings.of(lang) }
    Column(
        Modifier.fillMaxSize().card(p).adClick(onTap)
            .padding(start = if (wide) 48.dp else 34.dp, end = if (wide) 48.dp else 34.dp, top = 22.dp, bottom = 20.dp),
    ) {
        LoopHeader(page, s, lang)
        Spacer(Modifier.height(10.dp))
        val arabic = page.arabic
        if (arabic != null) {
            val latin = page.translation != null || page.transliteration != null
            Box(Modifier.weight(if (latin) 1.6f else 1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    arabic, Modifier.fillMaxWidth(),
                    style = Base.arabic().copy(color = p.ink, textAlign = TextAlign.Center, lineHeight = 1.75.em),
                    autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = if (wide) 100.sp else 92.sp, stepSize = 4.sp),
                )
            }
            if (latin) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(120.dp).height(2.dp).clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = 0.5f)))
                }
                Column(Modifier.weight(1f).fillMaxWidth()) { Meaning(page, 36.sp) }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) { Meaning(page, 46.sp) }
        }
        LoopFooter(page, s, lang)
    }
}

@Composable
private fun ColumnScope.Meaning(page: LoopPage, max: TextUnit) {
    val p = LocalPalette.current
    val style = TextStyle(fontFamily = Fonts.light, textAlign = TextAlign.Center, lineHeight = 1.3.em, textDirection = TextDirection.Ltr)
    page.transliteration?.let {
        Box(Modifier.weight(if (page.translation != null) 0.8f else 1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicText(it, Modifier.fillMaxWidth(), style = style.copy(color = p.accent2, fontStyle = FontStyle.Italic),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = max, stepSize = 2.sp))
        }
    }
    if (page.translation != null && page.transliteration != null) Spacer(Modifier.height(10.dp))
    page.translation?.let {
        Box(Modifier.weight(1.2f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicText(it, Modifier.fillMaxWidth(), style = style.copy(color = p.ink.copy(alpha = 0.88f)),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = max, stepSize = 2.sp))
        }
    }
}

@Composable
private fun LoopHeader(page: LoopPage, s: Strings, lang: String) {
    val p = LocalPalette.current
    val d = page.dhikr
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        AdhkarGlyph(page.category, p.accent, Modifier.size(34.dp), second = p.accent2)
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Label(s[page.category.key], p.accent, size = 17.sp)
            if (!s.rtl) {
                Spacer(Modifier.width(14.dp))
                BasicText(Strings.of("ar")[page.category.key], style = Base.arabic().copy(color = p.accent2, fontSize = 24.sp), maxLines = 1)
            }
            d.title(lang)?.let { t ->
                Spacer(Modifier.width(16.dp))
                val style = Base.copy(color = p.inkDim, fontSize = 22.sp)
                BasicText("·  $t", style = if (hasArabic(t)) style.arabic().copy(fontSize = 25.sp) else style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (page.parts > 1 && !page.meaning) {
            BasicText("${page.part} / ${page.parts}", style = digits(24.sp, p.inkDim, light = false).copy(textDirection = TextDirection.Ltr))
            Spacer(Modifier.width(14.dp))
        }
        if (page.meaning) {
            Label(s["loop_meaning"], p.inkDim)
            Spacer(Modifier.width(14.dp))
        }
        if (d.count > 1) Pill("\u200E× ${d.count}", p.accent, p.onAccent)
    }
}

@Composable
private fun LoopFooter(page: LoopPage, s: Strings, lang: String) {
    val p = LocalPalette.current
    val d = page.dhikr
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(s["loop_tap"], Modifier.weight(1f), style = Base.copy(color = p.inkFaint, fontSize = 18.sp).let { if (s.rtl) it.arabic().copy(fontSize = 21.sp) else it }, maxLines = 1)
        val ref = (if (lang == "ar") d.sourceAr?.takeIf { page.category == AdhkarCategory.QURAN } else null) ?: d.reference
        ref?.let {
            BasicText(it, Modifier.clip(RoundedCornerShape(50)).border(1.dp, p.hairline, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 5.dp),
                style = Base.copy(color = p.inkFaint, fontSize = 18.sp).let { st -> if (hasArabic(it)) st.arabic() else st.copy(textDirection = TextDirection.Ltr) }, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- the main screen's dhikr slide

/** A short dhikr over the clock area, like a hadith slide: the collection, the Arabic, its meaning, the count. */
@Composable
fun DhikrSlide(st: DisplayState, slide: Slide.OfDhikr) {
    val p = LocalPalette.current
    val s = st.strings
    val d: Dhikr = slide.dhikr
    val lang = st.settings.display.language
    val tr = d.translation?.takeIf { lang != "ar" && st.settings.loop.translation }
    Column(Modifier.fillMaxSize().padding(vertical = 18.dp).card(p).padding(horizontal = 48.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdhkarGlyph(slide.category, p.accent, Modifier.size(30.dp), second = p.accent2)
            Spacer(Modifier.width(12.dp))
            Label(s[slide.category.key], p.accent, Modifier.weight(1f))
            if (d.count > 1) Pill("\u200E× ${d.count}", p.accent, p.onAccent)
        }
        Box(Modifier.weight(if (tr != null) 1.5f else 1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicText(d.arabic, Modifier.fillMaxWidth(), style = Base.arabic().copy(color = p.ink, textAlign = TextAlign.Center, lineHeight = 1.7.em),
                autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 76.sp, stepSize = 4.sp))
        }
        tr?.let {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(it, Modifier.fillMaxWidth(), style = TextStyle(color = p.inkDim, fontFamily = Fonts.light, textAlign = TextAlign.Center, lineHeight = 1.3.em, textDirection = TextDirection.Ltr),
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 32.sp, stepSize = 2.sp))
            }
        }
        d.reference?.let {
            BasicText(it, Modifier.align(Alignment.End), style = Base.copy(color = p.inkFaint, fontSize = 17.sp, textDirection = TextDirection.Ltr), maxLines = 1)
        }
    }
}
