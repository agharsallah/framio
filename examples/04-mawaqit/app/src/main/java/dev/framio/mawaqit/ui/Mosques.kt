package dev.framio.mawaqit.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.PrayerDay
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.Secondary
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.engine.DisplayState
import kotlinx.coroutines.delay

// The secondary mosques on the display: a "Nearby mosques" slide over the clock area, and on demand (tap the
// mosque's name) a full-screen comparison of every mosque, main first, for today or tomorrow.

/** Whether the comparison is open. Closes by itself [TIMEOUT_MS] after the last touch, like a sheet left open. */
@Stable
class MosquesView {
    var open by mutableStateOf(false)
        private set
    var tomorrow by mutableStateOf(false)
        private set
    internal var touchedAt by mutableLongStateOf(0L)
        private set

    fun show() {
        tomorrow = false
        open = true
        touch()
    }

    fun showTomorrow(value: Boolean) {
        tomorrow = value
        touch()
    }

    fun close() {
        open = false
    }

    private fun touch() {
        touchedAt = SystemClock.uptimeMillis()
    }
}

/** Provided by the root: the top bar's mosque name opens the comparison through it. */
val LocalMosquesView = staticCompositionLocalOf<MosquesView?> { null }

private const val TIMEOUT_MS = 60_000L

@Composable
fun rememberMosquesView(): MosquesView {
    val view = remember { MosquesView() }
    LaunchedEffect(view.open, view.touchedAt) {
        if (view.open) {
            delay(TIMEOUT_MS)
            view.close()
        }
    }
    return view
}

/** The main mosque's name in the top bar; with secondary mosques, tappable and followed by a "+2 mosques" chip. */
@Composable
fun MosqueTitle(st: DisplayState, name: String, style: TextStyle, modifier: Modifier) {
    val view = LocalMosquesView.current
    val count = st.secondary.size
    if (view == null || count == 0) {
        BasicText(name, modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val p = LocalPalette.current
    val s = st.strings
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = view::show)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(name, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(16.dp))
            Pill(
                if (count == 1) s["plus_mosque"] else s["plus_mosques"].replace("{n}", count.toString()),
                p.card, p.inkDim,
                leading = { DomeGlyph(p.accent, Modifier.size(20.dp)) },
            )
        }
    }
}

/** The slide: one row per secondary mosque, Fajr … Isha with adhan and iqama, and Jumu'a; each one's next prayer lit. */
@Composable
fun NearbySlide(st: DisplayState) {
    val p = LocalPalette.current
    val rows = rows(st, tomorrow = false, withMain = false)
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 18.dp).card(p)) {
        val wide = maxWidth > 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 28.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DomeGlyph(p.accent, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Label(st.strings["nearby"], p.accent)
            }
            if (wide) {
                Header(st, SLIDE, Modifier.padding(top = 6.dp))
                rows.forEach { row ->
                    Hairline()
                    WideRow(st, row, SLIDE, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Column(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    rows.forEach { StackedRow(st, it, SLIDE_TALL) }
                }
            }
        }
    }
}

/** Full screen, main mosque first: tap anywhere, Back or a minute untouched closes it. */
@Composable
fun MosquesScreen(st: DisplayState, view: MosquesView) {
    BackHandler(onBack = view::close)
    val p = LocalPalette.current
    val s = st.strings
    val tomorrow = view.tomorrow
    val rows = rows(st, tomorrow, withMain = true)
    BoxWithConstraints(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = view::close)) {
        val wide = maxWidth >= maxHeight
        Column(Modifier.fillMaxSize().padding(start = if (wide) 48.dp else 36.dp, end = if (wide) 48.dp else 36.dp, top = 34.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(s["mosques"], style = Base.copy(color = p.ink, fontSize = 40.sp, fontFamily = Fonts.light), maxLines = 1)
                    BasicText(
                        Format.date(if (tomorrow) st.now.plusDays(1) else st.now, st.settings.display.language),
                        Modifier.padding(top = 2.dp),
                        style = Base.copy(color = p.inkDim, fontSize = 24.sp, fontFamily = Fonts.light),
                        maxLines = 1,
                    )
                }
                Toggle(s["today"], !tomorrow) { view.showTomorrow(false) }
                Spacer(Modifier.width(10.dp))
                Toggle(s["tomorrow"], tomorrow) { view.showTomorrow(true) }
                Spacer(Modifier.width(18.dp))
                BasicText(
                    "✕",
                    Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(p.card).clickable(onClick = view::close).padding(top = 11.dp),
                    style = Base.copy(color = p.inkDim, fontSize = 24.sp, textAlign = TextAlign.Center),
                )
            }
            if (wide) {
                Header(st, FULL, Modifier.padding(top = 22.dp, start = 24.dp, end = 24.dp), arabic = true)
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { row -> WideRow(st, row, FULL, Modifier.fillMaxWidth().weight(1f, fill = false).card(p, radius = 22.dp).padding(horizontal = 24.dp)) }
                }
            } else {
                Column(Modifier.weight(1f).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    rows.forEach { row -> StackedRow(st, row, FULL_TALL, Modifier.card(p, radius = 22.dp).padding(horizontal = 24.dp, vertical = 20.dp)) }
                }
            }
            BasicText(s["tap_close"], Modifier.fillMaxWidth().padding(top = 12.dp), style = Base.copy(color = p.inkFaint, fontSize = 18.sp, textAlign = TextAlign.Center))
        }
    }
}

/** A mosque's line for one day. [highlight]: index of the next prayer's cell (0–4, 5 = Jumu'a), -1 for none. */
private class MosqueRow(
    val name: String,
    val main: Boolean,
    val day: PrayerDay?,
    val jumua: List<PrayerTime>,
    val highlight: Int,
    val status: String?,
)

private fun rows(st: DisplayState, tomorrow: Boolean, withMain: Boolean): List<MosqueRow> {
    val s = st.strings
    val out = ArrayList<MosqueRow>()
    if (withMain) {
        val day = if (tomorrow) st.tomorrow else st.today
        val jumua = Secondary.jumua(st.source.times, st.now.toLocalDate().plusDays(if (tomorrow) 1 else 0))
        val name = st.source.mosque?.name ?: st.settings.source.placeName
        out += MosqueRow(name, true, day, jumua, Secondary.highlight(day, jumua, st.next.takeIf { !tomorrow }), s["main_mosque"])
    }
    for (e in st.secondary) {
        val day = if (tomorrow) e.tomorrow else e.today
        val status = when {
            e.today == null && e.loading -> s["loading"]
            e.today == null && e.error == "not_found" -> s["not_found"]
            e.today == null -> s["no_times"]
            e.error != null -> s["offline"]
            else -> null
        }
        out += MosqueRow(e.name, false, day, e.jumua, Secondary.highlight(day, e.jumua, e.next.takeIf { !tomorrow }), status)
    }
    return out
}

/** Type sizes of a table: the slide is small (it shares the screen), the full-screen view reads from across a room. */
private class Sizes(val adhan: TextUnit, val iqama: TextUnit, val name: TextUnit, val label: TextUnit, val col: Dp, val jumuaCol: Dp, val cellPad: Dp)

private val SLIDE = Sizes(32.sp, 18.sp, 22.sp, 17.sp, 120.dp, 136.dp, 3.dp)
private val FULL = Sizes(50.sp, 27.sp, 28.sp, 22.sp, 146.dp, 150.dp, 9.dp)
private val SLIDE_TALL = Sizes(40.sp, 22.sp, 26.sp, 17.sp, 0.dp, 0.dp, 4.dp)
private val FULL_TALL = Sizes(46.sp, 24.sp, 30.sp, 18.sp, 0.dp, 0.dp, 6.dp)

@Composable
private fun Header(st: DisplayState, z: Sizes, modifier: Modifier, arabic: Boolean = false) {
    val p = LocalPalette.current
    val s = st.strings
    val showArabic = arabic && st.settings.display.showArabicNames && !s.rtl
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.weight(1f))
        (PrayerKey.FIVE + PrayerKey.JUMUA).forEach { key ->
            Column(Modifier.width(if (key == PrayerKey.JUMUA) z.jumuaCol else z.col), horizontalAlignment = Alignment.CenterHorizontally) {
                // Line height pinned: Arabic names would otherwise take a taller line from the fallback font
                BasicText(s.prayer(key), style = Base.copy(color = p.inkDim, fontSize = z.label, lineHeight = z.label * 1.3f, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showArabic) BasicText(Strings.arabic(key), style = Base.arabic().copy(color = p.accent2, fontSize = z.label, lineHeight = z.label * 1.3f))
            }
        }
    }
}

/** Landscape: the name, then the five prayers and Jumu'a in columns under [Header]. */
@Composable
private fun WideRow(st: DisplayState, row: MosqueRow, z: Sizes, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        NameCell(st, row, z, Modifier.weight(1f).padding(end = 16.dp))
        for (i in 0 until 5) TimeCell(st, row, i, z, Modifier.width(z.col))
        JumuaCell(st, row, z, Modifier.width(z.jumuaCol))
    }
}

/** Portrait: the name (and Jumu'a) on a line, the five prayers under it, each with its name. */
@Composable
private fun StackedRow(st: DisplayState, row: MosqueRow, z: Sizes, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val s = st.strings
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NameCell(st, row, z, Modifier.weight(1f))
            if (row.jumua.isNotEmpty()) {
                val lit = row.highlight == 5
                Row(
                    Modifier.padding(start = 12.dp).then(if (lit) Modifier.card(p, highlighted = true, radius = 50.dp) else Modifier).padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(s.prayer(PrayerKey.JUMUA), style = Base.copy(color = p.inkDim, fontSize = z.label))
                    Spacer(Modifier.width(10.dp))
                    BasicText(row.jumua.joinToString(" · ") { Format.time(it.adhan, st.clock24h) }, style = digits(z.iqama, if (lit) p.accent else p.ink, light = false))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            for (i in 0 until 5) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(s.prayer(PrayerKey.FIVE[i]), style = Base.copy(color = p.inkFaint, fontSize = z.label, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TimeCell(st, row, i, z, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun NameCell(st: DisplayState, row: MosqueRow, z: Sizes, modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (row.main) {
            Box(Modifier.width(5.dp).height(z.name.value.dp * 1.4f).clip(RoundedCornerShape(50)).background(p.accent))
            Spacer(Modifier.width(14.dp))
        }
        Column {
            BasicText(row.name, style = Base.copy(color = p.ink, fontSize = z.name, fontWeight = FontWeight.Medium, lineHeight = z.name * 1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.status?.let {
                BasicText(it, Modifier.padding(top = 2.dp), style = Base.copy(color = if (row.main) p.accent else p.inkFaint, fontSize = z.label * 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Adhan over iqama; the next prayer lit like the prayer cards, the ones gone by faded. */
@Composable
private fun TimeCell(st: DisplayState, row: MosqueRow, index: Int, z: Sizes, modifier: Modifier) {
    val p = LocalPalette.current
    val prayer = row.day?.five?.getOrNull(index)
    val lit = row.highlight == index
    val past = !lit && row.day?.date == st.now.toLocalDate() && prayer != null && prayer.adhan.isBefore(st.now)
    val iqama = prayer?.let { Format.iqama(it, st.settings.display.iqamaAsOffset, st.clock24h) }
    Column(
        modifier.then(if (lit) Modifier.card(p, highlighted = true, radius = 16.dp) else Modifier).alpha(if (past) 0.5f else 1f)
            .padding(start = 8.dp, end = 8.dp, top = z.cellPad, bottom = z.cellPad + 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(prayer?.let { Format.time(it.adhan, st.clock24h) } ?: "--:--", style = digits(z.adhan, if (lit) p.accent else p.ink), softWrap = false, maxLines = 1)
        BasicText(iqama ?: " ", Modifier.padding(top = 4.dp), style = digits(z.iqama, if (lit) p.accent else p.inkDim, light = false), maxLines = 1)
    }
}

/** The first Jumu'a large, the others under it. */
@Composable
private fun JumuaCell(st: DisplayState, row: MosqueRow, z: Sizes, modifier: Modifier) {
    val p = LocalPalette.current
    val lit = row.highlight == 5
    val first = row.jumua.firstOrNull()
    Column(
        modifier.then(if (lit) Modifier.card(p, highlighted = true, radius = 16.dp) else Modifier).padding(start = 8.dp, end = 8.dp, top = z.cellPad, bottom = z.cellPad + 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(first?.let { Format.time(it.adhan, st.clock24h) } ?: "–", style = digits(z.adhan, if (lit) p.accent else p.ink), softWrap = false, maxLines = 1)
        BasicText(row.jumua.drop(1).joinToString(" ") { Format.time(it.adhan, st.clock24h) }.ifEmpty { " " }, Modifier.padding(top = 4.dp), style = digits(z.iqama, if (lit) p.accent else p.inkDim, light = false), maxLines = 1)
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.hairline))
}

@Composable
private fun Toggle(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    BasicText(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.card).clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        style = Base.copy(color = if (selected) p.onAccent else p.ink, fontSize = 22.sp),
        maxLines = 1,
    )
}

/** A dome with its crescent: marks the other mosques. */
@Composable
private fun DomeGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawArc(color, 180f, 180f, useCenter = true, topLeft = Offset(w * 0.18f, w * 0.34f), size = Size(w * 0.64f, w * 0.64f))
        drawRect(color, Offset(w * 0.18f, w * 0.64f), Size(w * 0.64f, w * 0.3f))
        drawRect(color, Offset(w * 0.47f, w * 0.12f), Size(w * 0.06f, w * 0.22f))
        drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.1f))
    }
}
