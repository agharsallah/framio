package dev.framio.mawaqit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.engine.Slide
import java.time.DayOfWeek
import java.time.Duration
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay

/** The everyday screen: clock and dates, the next prayer with its countdown, the five prayers, the extras. */
@Composable
fun MainScreen(st: DisplayState, onHideFlash: () -> Unit = {}, onSettings: () -> Unit) {
    // A long-press on the ticker hides it at once (display.showFlash); a short notice says where it comes back
    var flashHidden by remember { mutableStateOf(false) }
    LaunchedEffect(flashHidden) {
        if (flashHidden) { delay(3_000); flashHidden = false }
    }
    val hideFlash = { flashHidden = true; onHideFlash() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= maxHeight) Landscape(st, onSettings, hideFlash) else Portrait(st, onSettings, hideFlash)
        // Bottom left, level with the extras (always on the right) and clear of the launcher's hot-corner dot
        AnimatedVisibility(flashHidden, Modifier.align(AbsoluteAlignment.BottomLeft).padding(start = 104.dp, bottom = 22.dp), enter = fadeIn(), exit = fadeOut()) {
            val p = LocalPalette.current
            Pill(st.strings["flash_hidden"], p.accent, p.onAccent)
        }
    }
}

@Composable
private fun Landscape(st: DisplayState, onSettings: () -> Unit, onHideFlash: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(start = 56.dp, end = 56.dp, top = 32.dp)) {
            TopBar(st, onSettings)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Slides(st) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Clock(st, Modifier.weight(1f), big = 184)
                        NextCard(st, Modifier.width(470.dp))
                    }
                }
            }
            PrayerRow(st, Modifier.fillMaxWidth().height(226.dp))
            Extras(st, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp))
        }
        Ticker(st, onHideFlash)
    }
}

@Composable
private fun Portrait(st: DisplayState, onSettings: () -> Unit, onHideFlash: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(start = 40.dp, end = 40.dp, top = 32.dp)) {
            TopBar(st, onSettings)
            Box(Modifier.height(650.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Slides(st) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                        Clock(st, Modifier, big = 150, centered = true)
                        NextCard(st, Modifier.fillMaxWidth())
                    }
                }
            }
            PrayerList(st, Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp))
            Extras(st, Modifier.fillMaxWidth().padding(vertical = 18.dp))
        }
        Ticker(st, onHideFlash)
    }
}

@Composable
private fun TopBar(st: DisplayState, onSettings: () -> Unit) {
    val p = LocalPalette.current
    val s = st.strings
    val name = st.source.mosque?.name
        ?: st.settings.source.placeName.takeIf { st.settings.source.kind == "calc" }?.let { "$it · ${s["computed"]}" }
        ?: ""
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        // "Dhuhr in 20 min" before the adhan; else the name, tappable with secondary mosques (opens the comparison)
        if (preAdhanNotice(st)) PreAdhanNotice(st, Modifier.weight(1f))
        else MosqueTitle(st, name, Base.copy(color = p.ink, fontSize = 28.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium), Modifier.weight(1f))
        if (st.source.error != null && st.source.times != null) {
            Pill(s["offline"], p.card, p.inkDim, Modifier.padding(end = 16.dp))
        }
        st.weather?.let { WeatherBadge(it, p) }
        AdhkarTopChip(st)
        AdhkarLoopButton(st)
        Spacer(Modifier.width(20.dp))
        GearGlyph(
            p.inkFaint,
            Modifier.size(44.dp).clip(RoundedCornerShape(50))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSettings)
                .padding(8.dp),
        )
    }
}

@Composable
private fun Clock(st: DisplayState, modifier: Modifier, big: Int, centered: Boolean = false) {
    val p = LocalPalette.current
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier, horizontalAlignment = align) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(Format.time(st.now, st.clock24h), Modifier.alignByBaseline(), style = digits(big.sp, p.ink))
            if (st.settings.display.showSeconds || !st.clock24h) {
                Column(Modifier.alignByBaseline().padding(start = 14.dp)) {
                    if (!st.clock24h) BasicText(Format.amPm(st.now), style = Base.copy(color = p.inkDim, fontSize = 30.sp))
                    if (st.settings.display.showSeconds) BasicText(Format.seconds(st.now), style = digits((big * 0.3f).sp, p.accent))
                }
            }
        }
        BasicText(Format.date(st.now, st.settings.display.language), style = Base.copy(color = p.inkDim, fontSize = 32.sp, fontFamily = Fonts.light))
        st.hijri?.let {
            val style = Base.copy(color = p.accent2, fontSize = 32.sp, fontFamily = Fonts.light)
            BasicText(it, Modifier.padding(top = 4.dp), style = if (st.strings.rtl) style.arabic().copy(fontSize = 36.sp) else style)
        }
        if (st.ramadan) Pill(st.strings["ramadan"], p.accent.copy(alpha = 0.16f), p.accent, Modifier.padding(top = 14.dp))
    }
}

/** The next prayer and the time left; during the iqama countdown (when not full screen) the time to the iqama. */
@Composable
private fun NextCard(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    val s = st.strings
    val counting = st.phase.kind == PhaseKind.IQAMA_COUNTDOWN && st.phase.prayer?.iqama != null
    val target: PrayerTime = (if (counting) st.phase.prayer else st.next) ?: return
    val due = if (counting) target.iqama!! else target.adhan
    val reminder = st.phase.kind == PhaseKind.REMINDER
    Column(modifier.card(p, highlighted = reminder || counting).padding(horizontal = 36.dp, vertical = 30.dp)) {
        Label(if (counting) s["iqama_in"] else s["next"], p.inkDim)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(s.prayer(target.key), Modifier.alignByBaseline(), style = Base.copy(color = p.ink, fontSize = 52.sp, fontFamily = Fonts.light))
            if (st.settings.display.showArabicNames && !s.rtl) {
                Spacer(Modifier.width(18.dp))
                BasicText(Strings.arabic(target.key), Modifier.alignByBaseline(), style = Base.arabic().copy(color = p.accent2, fontSize = 42.sp))
            }
        }
        BasicText(Format.countdown(Duration.between(st.now, due)), Modifier.padding(top = 6.dp), style = digits(92.sp, p.accent))
        val iqama = Format.iqama(target, st.settings.display.iqamaAsOffset, st.clock24h)
        val line = buildString {
            append(s["adhan"]).append(' ').append(Format.time(target.adhan, st.clock24h))
            if (iqama != null) append("   ·   ").append(s["iqama"]).append(' ').append(iqama)
        }
        BasicText(line, Modifier.padding(top = 10.dp), style = Base.copy(color = p.inkDim, fontSize = 24.sp))
        Progress(st, target, Modifier.padding(top = 22.dp).fillMaxWidth().height(6.dp))
    }
}

/** How far we are from the previous prayer to the next one. */
@Composable
private fun Progress(st: DisplayState, next: PrayerTime, modifier: Modifier) {
    val p = LocalPalette.current
    val previous = st.today?.five?.filterNotNull()?.lastOrNull { !it.adhan.isAfter(st.now) }?.adhan
        ?: st.now.toLocalDate().atStartOfDay(st.now.zone)
    val total = Duration.between(previous, next.adhan).toMillis().coerceAtLeast(1)
    val done = (Duration.between(previous, st.now).toMillis().toFloat() / total).coerceIn(0f, 1f)
    Box(modifier.clip(RoundedCornerShape(50)).background(p.hairline)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(done).clip(RoundedCornerShape(50)).background(p.accent))
    }
}

/** The prayer the screen is about: the one whose flow is on, else the next. */
private fun focus(st: DisplayState): PrayerKey? {
    val k = (st.phase.prayer ?: st.next)?.key ?: return null
    return if (k == PrayerKey.JUMUA) PrayerKey.DHUHR else k
}

@Composable
private fun PrayerRow(st: DisplayState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        PrayerKey.FIVE.forEach { key -> PrayerCard(st, key) }
    }
}

@Composable
private fun RowScope.PrayerCard(st: DisplayState, key: PrayerKey) {
    val p = LocalPalette.current
    val s = st.strings
    val day = st.today
    val friday = key == PrayerKey.DHUHR && day?.jumua?.isNotEmpty() == true
    val prayer = if (friday) day!!.jumua.first() else day?.five?.getOrNull(key.five)
    val focused = focus(st) == key
    val past = prayer != null && !focused && prayer.adhan.isBefore(st.now)
    Column(
        Modifier.weight(1f).fillMaxHeight().card(p, highlighted = focused).alpha(if (past) 0.62f else 1f).padding(top = 16.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(s.prayer(if (friday) PrayerKey.JUMUA else key), style = Base.copy(color = if (focused) p.accent else p.ink, fontSize = 27.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            if (st.settings.display.showArabicNames && !s.rtl) {
                BasicText(Strings.arabic(if (friday) PrayerKey.JUMUA else key), style = Base.arabic().copy(color = p.accent2, fontSize = 26.sp, lineHeight = 30.sp))
            }
        }
        BasicText(prayer?.let { Format.time(it.adhan, st.clock24h) } ?: "--:--", style = digits(60.sp, p.ink))
        val iqama = when {
            friday -> day!!.jumua.drop(1).joinToString("  ") { Format.time(it.adhan, st.clock24h) }.ifEmpty { null }
            else -> prayer?.let { Format.iqama(it, st.settings.display.iqamaAsOffset, st.clock24h) }
        }
        val delay = if (friday) null else prayer?.let { Format.iqamaDelay(it, st.settings.display.iqamaAsOffset) }
        Row {
            BasicText(iqama ?: " ", Modifier.alignByBaseline(), style = digits(26.sp, if (focused) p.accent else p.inkDim, light = false))
            delay?.let { IqamaDelay(it, if (focused) p.accent else p.inkDim.copy(alpha = 0.7f)) }
        }
    }
}

/** The iqama's "+10" beside its time, small. */
@Composable
private fun RowScope.IqamaDelay(text: String, color: Color) {
    Spacer(Modifier.width(7.dp))
    BasicText(text, Modifier.alignByBaseline(), style = digits(17.sp, color, light = false).copy(textDirection = TextDirection.Ltr), maxLines = 1)
}

/** Portrait: one row per prayer. */
@Composable
private fun PrayerList(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    val s = st.strings
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrayerKey.FIVE.forEach { key ->
            val day = st.today
            val friday = key == PrayerKey.DHUHR && day?.jumua?.isNotEmpty() == true
            val prayer = if (friday) day!!.jumua.first() else day?.five?.getOrNull(key.five)
            val focused = focus(st) == key
            Row(
                Modifier.weight(1f).fillMaxWidth().card(p, highlighted = focused, radius = 20.dp).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = if (friday) PrayerKey.JUMUA else key
                // One line: the rows are 70 dp, a second line for the Arabic would crowd them
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(s.prayer(shown), style = Base.copy(color = if (focused) p.accent else p.ink, fontSize = 30.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    if (st.settings.display.showArabicNames && !s.rtl) {
                        Spacer(Modifier.width(14.dp))
                        BasicText(Strings.arabic(shown), style = Base.arabic().copy(color = p.accent2, fontSize = 28.sp), maxLines = 1)
                    }
                }
                BasicText(prayer?.let { Format.time(it.adhan, st.clock24h) } ?: "--:--", style = digits(54.sp, p.ink))
                val iqama = if (friday) null else prayer?.let { Format.iqama(it, st.settings.display.iqamaAsOffset, st.clock24h) }
                val delay = if (friday) null else prayer?.let { Format.iqamaDelay(it, st.settings.display.iqamaAsOffset) }
                Row(Modifier.width(170.dp), horizontalArrangement = Arrangement.End) {
                    BasicText(iqama ?: "", Modifier.alignByBaseline(), style = digits(26.sp, p.inkDim, light = false))
                    delay?.let { IqamaDelay(it, p.inkDim.copy(alpha = 0.7f)) }
                }
            }
        }
    }
}

/** Shuruq, Imsak (always in Ramadan), the Jumu'a times on other days, Eid. */
@Composable
private fun Extras(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    val s = st.strings
    val d = st.settings.display
    val day = st.today ?: return
    val items = buildList {
        if (d.showShuruq) day.shuruq?.let { add(s.prayer(PrayerKey.SHURUQ) to Format.time(it.adhan, st.clock24h)) }
        if (d.showImsak || st.ramadan) day.imsak?.let { add(s.prayer(PrayerKey.IMSAK) to Format.time(it.adhan, st.clock24h)) }
        if (st.ramadan) day.maghrib?.let { add(s["imsak_iftar"] to Format.time(it.adhan, st.clock24h)) }
        if (day.jumua.isEmpty()) {
            val friday = st.now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
            st.source.times?.day(friday)?.jumua?.takeIf { it.isNotEmpty() }?.let { j ->
                add(s.prayer(PrayerKey.JUMUA) to j.joinToString(" · ") { Format.time(it.adhan, st.clock24h) })
            }
        }
        val eid = day.eid.ifEmpty { st.tomorrow?.eid.orEmpty() }
        if (eid.isNotEmpty()) {
            val prefix = if (day.eid.isEmpty()) s["tomorrow"] + " · " else ""
            add(s.prayer(PrayerKey.EID) to prefix + eid.joinToString(" · ") { Format.time(it.adhan, st.clock24h) })
        }
    }
    if (items.isEmpty()) return
    // Against the right edge whatever the language: the launcher's hot-corner dot sits bottom-left
    Row(modifier, horizontalArrangement = Arrangement.Absolute.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (name, time) ->
            Row(Modifier.card(p, radius = 50.dp).padding(horizontal = 22.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(name, style = Base.copy(color = p.inkDim, fontSize = 22.sp))
                Spacer(Modifier.width(12.dp))
                BasicText(time, style = digits(24.sp, p.ink, light = false))
            }
        }
    }
}

/** The flash message, scrolling at the bottom like on the mosque screens. A long-press calls [onLongPress]. */
@Composable
private fun Ticker(st: DisplayState, onLongPress: () -> Unit = {}) {
    val p = LocalPalette.current
    val flash = st.flash ?: return
    // The screen recomposes every second with a new lambda: keyed on Unit, the gesture survives the ticks
    val longPress by rememberUpdatedState(onLongPress)
    val chosen = runCatching { Color(flash.color.toColorInt()) }.getOrNull()
    // A vivid colour is the mosque's statement: it fills the band. White, grey or black would punch a flat hole in
    // the theme (Moschee Tarik ben Ziad uses #FFFFFF): the band then takes the theme's surface, the accent a stripe.
    val vivid = chosen != null && saturation(chosen) > 0.25f && chosen.luminance() in 0.04f..0.8f
    val bg = if (vivid) chosen!! else if (p.light) Color(0xFFFBF6EC) else p.bottom
    val ink = when {
        !vivid -> p.ink
        bg.luminance() > 0.45f -> Color(0xFF1C1A16)
        else -> Color.White
    }
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(bg).drawBehind {
            drawLine(p.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        }.pointerInput(Unit) { detectTapGestures(onLongPress = { longPress() }) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(88.dp)) // clear of the launcher's hot-corner dot
        if (!vivid) Box(Modifier.width(6.dp).height(30.dp).clip(RoundedCornerShape(50)).background(p.accent))
        val style = Base.copy(color = ink, fontSize = 28.sp)
        val text = separateScripts(flash.text)
        // The mosque's own direction setting; the marquee then runs the way the text reads
        CompositionLocalProvider(LocalLayoutDirection provides if (flash.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            BasicText(
                text,
                Modifier.weight(1f)
                    .padding(start = 20.dp, end = 40.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 70.dp, repeatDelayMillis = 0, spacing = MarqueeSpacing(160.dp)),
                style = if (hasArabic(text)) style.arabic() else style,
                maxLines = 1,
            )
        }
    }
}

private fun saturation(c: Color): Float {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    return if (max == 0f) 0f else (max - min) / max
}

/**
 * Bilingual messages are often typed as one run ("…صلاة العشاءDas Ishaa Gebet…"): a separator where the script
 * switches from Arabic to Latin (or back) with no space keeps the two halves apart.
 */
internal fun separateScripts(text: String): String =
    text.replace(Regex("(?<=[\\u0600-\\u06FF])(?=[A-Za-z\\u00C0-\\u024F])|(?<=[A-Za-z\\u00C0-\\u024F])(?=[\\u0600-\\u06FF])"), "  ·  ")

/** A hadith or an announcement crossfades over the clock area for a while (the prayers stay visible). */
@Composable
private fun Slides(st: DisplayState, content: @Composable () -> Unit) {
    AdhkarReminderArea(st) { // a due adhkar reminder takes the slide area for a while
        AnimatedContent(st.slide, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "slide") { slide ->
            if (slide == null) content() else SlideCard(st, slide)
        }
    }
}

@Composable
private fun SlideCard(st: DisplayState, slide: Slide) {
    val p = LocalPalette.current
    val s = st.strings
    val (title, text, image) = when (slide) {
        is Slide.OfHadith -> Triple(s["hadith"], slide.hadith.text, null)
        is Slide.OfAnnouncement -> Triple(slide.announcement.title ?: s["message"], slide.announcement.content.orEmpty().trim(), slide.announcement.imageUrl)
        Slide.OfMosques -> return NearbySlide(st)
        is Slide.OfDhikr -> return DhikrSlide(st, slide)
    }
    val frame = Modifier.fillMaxSize().padding(vertical = 18.dp).card(p)
    when {
        // A poster: mosques post flyers with a title only. Shown whole and as large as the card allows.
        image != null && text.isBlank() -> Column(frame.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RemoteImage(image, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)), ContentScale.Fit)
            Label(title, p.accent, Modifier.padding(top = 14.dp))
        }
        image != null -> Row(frame.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(image, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)), ContentScale.Fit)
            Spacer(Modifier.width(32.dp))
            SlideText(title, text, Modifier.weight(1f))
        }
        else -> Box(frame.padding(horizontal = 48.dp, vertical = 32.dp), contentAlignment = Alignment.CenterStart) {
            SlideText(title, text, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SlideText(title: String, text: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Label(title, p.accent)
        Spacer(Modifier.height(18.dp))
        // Announcements are any length: the text steps down until it fits the card (line height in em scales along)
        val arabic = hasArabic(text)
        // An English hadith on an Arabic screen still reads left to right (else its full stop jumps to the start)
        val style = Base.copy(color = p.ink, lineHeight = if (arabic) 1.6.em else 1.3.em, fontFamily = Fonts.light, textDirection = TextDirection.Ltr)
        BasicText(
            text,
            Modifier.weight(1f, fill = false),
            style = if (arabic) style.arabic().copy(lineHeight = 1.6.em) else style,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = if (arabic) 50.sp else 44.sp, stepSize = 2.sp),
        )
    }
}

/**
 * Whether to lay a text out as Arabic (right to left, Amiri). By majority of letters: English hadiths carry Arabic
 * honorifics like (صَلَّى اللهُ عَلَيْهِ وَسَلَّمَ) and must stay left to right.
 */
internal fun hasArabic(text: String): Boolean {
    var arabic = 0
    var letters = 0
    for (c in text) if (c.isLetter()) {
        letters++
        if (c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFEFF') arabic++
    }
    return letters > 0 && arabic * 2 > letters
}
