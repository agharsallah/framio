package dev.framio.lab.labs.display

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.Events
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Choice
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.LabeledSlider
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.ToggleRow

/** Panel, brightness (per window and system-wide), rotation, frame pacing and test patterns. */
object DisplayLab {

    val lab = Lab(
        id = "display",
        title = "Display",
        summary = "1280×800 MIPI panel: brightness, rotation, refresh rate, test patterns",
        accent = Sky,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        val host = LocalHost.current
        LabPage("Display", "MIPI-DSI panel, natively 800×1280 portrait, rotated 90° by SurfaceFlinger. 160 dpi, so 1 dp = 1 px.") {
            InfoSection(host.context)
            BrightnessSection(host)
            OrientationSection(host)
            FrameSection()
            Section("Test patterns", note = "Full-screen: tap for the next pattern, long-press to close.") {
                Actions { ActionButton("Show patterns", { host.overlay = { Patterns.Overlay { host.overlay = null } } }) }
            }
            SelfTestSection(lab.id)
        }
    }

    @Composable
    private fun InfoSection(context: Context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val real = DisplayMetrics().also { display.getRealMetrics(it) }
        val mode = display.mode
        Section("Panel") {
            StatGrid(
                listOf(
                    "Resolution" to "${real.widthPixels} × ${real.heightPixels}",
                    "Refresh" to "${Format.f2(display.refreshRate)} Hz",
                    "Density" to "${real.densityDpi} dpi (x ${Format.f1(real.xdpi)}, y ${Format.f1(real.ydpi)})",
                    "Rotation" to "${display.rotation * 90}°",
                    "Mode" to "#${mode.modeId} ${mode.physicalWidth}×${mode.physicalHeight}",
                    "Modes" to display.supportedModes.size.toString(),
                    "Displays" to dm.displays.joinToString { it.name },
                    "Presentation displays" to dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).size.toString(),
                ),
            )
        }
    }

    @Composable
    private fun BrightnessSection(host: LabHost) {
        val context = host.context
        val resolver = context.contentResolver
        var windowLevel by remember { mutableFloatStateOf(-1f) }
        var keepOn by remember { mutableStateOf(true) }
        var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
        var system by remember { mutableIntStateOf(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 0)) }
        val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        // Leaving the lab puts the window back to normal: system brightness, screen kept on
        DisposableEffect(Unit) {
            onDispose {
                host.window.attributes = host.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        Section("Brightness", note = "Backlight rk28_bl, 0–255. No light sensor, so automatic brightness has nothing to follow.") {
            LabeledSlider(
                "This window",
                if (windowLevel < 0) "system" else "${(windowLevel * 100).toInt()} %",
                if (windowLevel < 0) system / 255f else windowLevel,
                { v ->
                    windowLevel = v.coerceAtLeast(0.02f) // 0 would blank the screen with no way to see the slider
                    host.window.attributes = host.window.attributes.apply { screenBrightness = windowLevel }
                },
            )
            Note("WindowManager.LayoutParams.screenBrightness: no permission, only while this window is in front.")
            Actions {
                ActionButton("Use system level", {
                    windowLevel = -1f
                    host.window.attributes = host.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }, primary = false)
            }
            LabeledSlider(
                "System",
                "$system / 255",
                system / 255f,
                { v ->
                    system = (v * 255).toInt().coerceIn(10, 255)
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, system)
                },
                enabled = canWrite,
            )
            Note(
                if (canWrite) "Settings.System.SCREEN_BRIGHTNESS: persists for every app. Auto mode: $auto."
                else "Needs WRITE_SETTINGS: adb shell appops set ${context.packageName} WRITE_SETTINGS allow",
            )
            if (!canWrite) Actions {
                ActionButton("Open permission screen", {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }, primary = false)
                ActionButton("Re-check", { canWrite = Settings.System.canWrite(context) }, primary = false)
            }
            ToggleRow(
                "Keep screen on",
                keepOn,
                {
                    keepOn = it
                    if (it) host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                },
                note = "FLAG_KEEP_SCREEN_ON. The frame's own timeout is ${screenTimeout(context)}, so off rarely matters.",
            )
        }
    }

    private fun screenTimeout(context: Context): String {
        val ms = Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        return if (ms >= Int.MAX_VALUE) "never" else Format.duration(ms)
    }

    private enum class Orientation(val label: String, val value: Int) {
        Landscape("Landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        Portrait("Portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        Reverse("Upside down", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        Sensor("Follow accelerometer", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR),
    }

    @Composable
    private fun OrientationSection(host: LabHost) {
        var o by remember { mutableStateOf(Orientation.Landscape) }
        DisposableEffect(Unit) { onDispose { host.orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) } }
        Section("Rotation", note = "Activity.setRequestedOrientation. 'Follow accelerometer' rotates when the frame is stood on its side.") {
            Choice(Orientation.entries, o, { it.label }) {
                o = it
                host.orientation(it.value)
            }
        }
    }

    /** Animates a ball and measures real frame intervals with withFrameNanos (Choreographer underneath). */
    @Composable
    private fun FrameSection() {
        var running by remember { mutableStateOf(false) }
        val stats = remember { FrameStats() }
        var summary by remember { mutableStateOf("") }
        LaunchedEffect(running) {
            if (!running) return@LaunchedEffect
            stats.clear()
            var last = 0L
            var n = 0
            while (true) {
                withFrameNanos { t ->
                    if (last != 0L) stats.add(t - last)
                    last = t
                }
                if (++n % 30 == 0) summary = "${Format.f1(stats.fps)} fps · worst ${Format.ms(stats.worstMs)} · ${stats.janks(57.17)} janks in last ${stats.count} frames"
                if (n % 240 == 0) Events.log(lab.id, "frame pacing: $summary")
            }
        }
        Section(
            "Frame pacing",
            note = "Panel refreshes at 57.17 Hz. A smooth animation should hold ~57 fps.",
            trailing = { ActionButton(if (running) "Stop" else "Animate", { running = !running }) },
        ) {
            if (running) {
                val t = rememberInfiniteTransition(label = "ball")
                val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "x")
                Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                    drawCircle(Teal, 24.dp.toPx(), Offset(24.dp.toPx() + x * (size.width - 48.dp.toPx()), size.height / 2))
                }
            }
            Text(summary.ifEmpty { "Press Animate." }, style = Mono)
        }
    }

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        guard("Panel") {
            val real = DisplayMetrics().also { d.getRealMetrics(it) }
            val text = "${real.widthPixels}×${real.heightPixels} @ ${Format.f2(d.refreshRate)} Hz, ${real.densityDpi} dpi"
            if (real.widthPixels == 1280 && real.heightPixels == 800) pass("Panel", text) else warn("Panel", "$text (expected 1280×800)")
        }
        guard("External display") {
            if (dm.displays.size > 1) pass("External display", dm.displays.joinToString { it.name })
            else absent("External display", "only the built-in panel (HDMI block in the SoC is not wired)")
        }
        guard("System brightness") {
            val b = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            if (Settings.System.canWrite(context)) pass("System brightness", "level $b/255, writable")
            else manual("System brightness", "level $b/255; to change it: adb shell appops set ${context.packageName} WRITE_SETTINGS allow")
        }
        manual("Frame pacing", "Display → Animate: measured on the frame at 53 fps of 57 (logged as EVENT|display)")
        manual("Test patterns", "open Display → Show patterns and look for dead pixels and banding")
        items
    }
}
