package dev.framio.lab.labs.touch

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.Surface0
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.Events
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.Coral
import dev.framio.lab.core.ui.Ink
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Lime
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Rose
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.Surface1
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.Violet
import dev.framio.lab.core.ui.Actions

/** Multitouch canvas and an edge-to-edge coverage grid for the Silead gslX680 digitizer. */
object TouchLab {

    val lab = Lab(
        id = "touch",
        title = "Touch",
        summary = "Silead gslX680 multitouch: finger trails, report rate, coverage grid",
        accent = Lime,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private val fingerColors = listOf(Teal, Amber, Coral, Sky, Violet, Lime, Rose, Ink, Teal, Amber)

    private const val COLS = 16
    private const val ROWS = 10

    /** Touch state kept outside Compose state; the caller bumps a counter to redraw. */
    private class Touches {
        val trails = HashMap<Int, Path>()
        val active = HashMap<Int, Offset>()
        var maxPointers = 0
        var lastInfo = ""
        var moveSamples = 0
        var firstMoveMs = 0L
        var lastMoveMs = 0L

        /** Move reports per second while a finger slides (historical samples included). */
        val reportHz: Double
            get() = if (lastMoveMs > firstMoveMs && moveSamples > 10) moveSamples * 1000.0 / (lastMoveMs - firstMoveMs) else 0.0

        fun clear() {
            trails.clear(); active.clear()
            maxPointers = 0; moveSamples = 0; firstMoveMs = 0; lastMoveMs = 0
        }

        fun onEvent(e: MotionEvent) {
            val idx = e.actionIndex
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    trails[e.getPointerId(idx)] = Path().apply { moveTo(e.getX(idx), e.getY(idx)) }
                MotionEvent.ACTION_MOVE -> {
                    if (firstMoveMs == 0L) firstMoveMs = e.eventTime
                    moveSamples += 1 + e.historySize
                    lastMoveMs = e.eventTime
                    for (i in 0 until e.pointerCount) {
                        val path = trails[e.getPointerId(i)] ?: continue
                        for (h in 0 until e.historySize) path.lineTo(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        path.lineTo(e.getX(i), e.getY(i))
                    }
                }
            }
            active.clear()
            if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                for (i in 0 until e.pointerCount) {
                    if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == idx) continue
                    active[e.getPointerId(i)] = Offset(e.getX(i), e.getY(i))
                }
            }
            if (active.size > maxPointers) Events.log(lab.id, "${active.size} fingers down")
            maxPointers = maxOf(maxPointers, active.size)
            val i = idx.coerceAtMost(e.pointerCount - 1)
            lastInfo = "id ${e.getPointerId(i)} x ${e.getX(i).toInt()} y ${e.getY(i).toInt()} · pressure ${Format.f2(e.getPressure(i))} · " +
                "size ${Format.f2(e.getSize(i))} · major ${Format.f1(e.getTouchMajor(i))} · ${toolName(e.getToolType(i))} · ${e.device?.name}"
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Content() {
        val host = LocalHost.current
        var version by remember { mutableIntStateOf(0) }
        val t = remember { Touches() }

        LabPage("Touch", "Raw MotionEvents: pointer ids, pressure, contact size. Try 5+ fingers.", scroll = false) {
            Actions {
                ActionButton("Clear", { t.clear(); version++ }, primary = false)
                ActionButton("Full-screen coverage grid", { host.overlay = { CoverageOverlay { host.overlay = null } } })
            }
            version.let { // read so the text recomposes on every event
                Text(
                    "now ${t.active.size} · max ${t.maxPointers} fingers · move reports ${Format.f1(t.reportHz)} Hz\n${t.lastInfo.ifEmpty { "Touch the area below." }}",
                    style = Mono,
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface1)
                    .pointerInteropFilter { e -> t.onEvent(e); version++; true },
            ) {
                version // redraw trigger
                t.trails.forEach { (id, path) -> drawPath(path, fingerColors[id % fingerColors.size], style = Stroke(4.dp.toPx())) }
                t.active.forEach { (id, p) ->
                    val c = fingerColors[id % fingerColors.size]
                    drawCircle(c.copy(alpha = 0.25f), 48.dp.toPx(), p)
                    drawCircle(c, 48.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                }
            }
        }
    }

    /** 16×10 cells over the whole screen; each lights up when touched. Tap the centre text to close. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun CoverageOverlay(onClose: () -> Unit) {
        val hits = remember { HashSet<Int>() }
        var version by remember { mutableIntStateOf(0) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.fillMaxSize().background(Surface0).onSizeChanged { size = it }) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { e ->
                        if (size.width > 0) for (i in 0 until e.pointerCount) {
                            val c = (e.getX(i) / size.width * COLS).toInt().coerceIn(0, COLS - 1)
                            val r = (e.getY(i) / size.height * ROWS).toInt().coerceIn(0, ROWS - 1)
                            if (hits.add(r * COLS + c)) {
                                version++
                                if (hits.size == COLS * ROWS) Events.log(lab.id, "coverage grid complete: all ${COLS * ROWS} cells")
                            }
                        }
                        true
                    },
            ) {
                version
                val cw = this.size.width / COLS
                val ch = this.size.height / ROWS
                for (r in 0 until ROWS) for (c in 0 until COLS) {
                    drawRect(
                        if ((r * COLS + c) in hits) Teal.copy(alpha = 0.6f) else Color(0x18FFFFFF),
                        Offset(c * cw + 1, r * ch + 1),
                        Size(cw - 2, ch - 2),
                    )
                }
            }
            version.let {
                Text(
                    if (hits.size == COLS * ROWS) "All ${COLS * ROWS} cells reached · tap here to close" else "${hits.size}/${COLS * ROWS} · tap here to close",
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Surface1).clickable(onClick = onClose).padding(16.dp),
                    style = Mono,
                )
            }
        }
    }

    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "unknown"
    }

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        guard("Touchscreen") {
            val touch = InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull()
                .filter { it.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) }
            if (touch.isEmpty()) fail("Touchscreen", "no input device with SOURCE_TOUCHSCREEN")
            touch.forEach { d ->
                val x = d.getMotionRange(MotionEvent.AXIS_X)
                val y = d.getMotionRange(MotionEvent.AXIS_Y)
                pass("Touchscreen", "${d.name}: x 0–${x?.max?.toInt()}, y 0–${y?.max?.toInt()}, pressure axis ${d.getMotionRange(MotionEvent.AXIS_PRESSURE) != null}")
            }
        }
        guard("Multitouch") {
            val pm = context.packageManager
            info("Multitouch", "declared: distinct=${pm.hasSystemFeature("android.hardware.touchscreen.multitouch.distinct")}, jazzhand (5+)=${pm.hasSystemFeature("android.hardware.touchscreen.multitouch.jazzhand")}")
        }
        manual("Fingers and edges", "Touch → Draw with 5+ fingers; Coverage grid: swipe every edge until all 160 cells light up")
        items
    }
}
