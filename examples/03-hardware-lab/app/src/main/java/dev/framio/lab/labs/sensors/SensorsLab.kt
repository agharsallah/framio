package dev.framio.lab.labs.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.Events
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Ring
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.Choice
import dev.framio.lab.core.ui.Coral
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.Sparkline
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.StatusChip
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.Violet
import kotlinx.coroutines.launch

/** The one real sensor (MiraMEMS accelerometer) live, and a probe of everything the HAL claims. */
object SensorsLab {

    val lab = Lab(
        id = "sensors",
        title = "Sensors",
        summary = "Accelerometer live: tilt, stand orientation, knock detection; which sensors are real",
        accent = Violet,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private enum class Rate(val label: String, val delay: Int) {
        Normal("Normal", SensorManager.SENSOR_DELAY_NORMAL),
        Ui("UI", SensorManager.SENSOR_DELAY_UI),
        Game("Game", SensorManager.SENSOR_DELAY_GAME),
        Fastest("Fastest", SensorManager.SENSOR_DELAY_FASTEST),
    }

    private class Accel {
        val x = Ring(200)
        val y = Ring(200)
        val z = Ring(200)
        val knocks = KnockDetector()
        var last = FloatArray(3)
        var events = 0
        var windowStart = 0L
        var hz = 0.0
    }

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        val sm = remember { context.getSystemService(SensorManager::class.java) }
        val accel = remember { Accel() }
        var rate by remember { mutableStateOf(Rate.Game) }
        var version by remember { mutableIntStateOf(0) }
        var probe by remember { mutableStateOf<List<SensorProbe.Result>?>(null) }
        var probing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        DisposableEffect(rate) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val (x, y, z) = e.values
                    accel.x.add(x); accel.y.add(y); accel.z.add(z)
                    accel.last = e.values.copyOf()
                    if (accel.knocks.add(System.currentTimeMillis(), x, y, z)) {
                        Events.log(lab.id, "knock #${accel.knocks.count} (${Format.f2(accel.knocks.linear)} m/s²)")
                    }
                    val now = System.nanoTime()
                    if (accel.windowStart == 0L) accel.windowStart = now
                    accel.events++
                    if (now - accel.windowStart > 1_000_000_000L) {
                        accel.hz = accel.events * 1e9 / (now - accel.windowStart)
                        accel.events = 0
                        accel.windowStart = now
                    }
                    if (accel.events % 3 == 0) version++ // redraw at most ~1/3 of the sample rate
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (sensor != null) sm.registerListener(listener, sensor, rate.delay)
            onDispose { sm.unregisterListener(listener) }
        }

        LabPage("Sensors", "Only the MiraMEMS gs_mir3da accelerometer is real. Knock on the frame or tilt it.") {
            Section(
                "Accelerometer",
                note = "SensorManager TYPE_ACCELEROMETER, m/s². In WebView the same data arrives as the devicemotion event.",
                trailing = { Choice(Rate.entries, rate, { it.label }) { rate = it } },
            ) {
                version.let {
                    val (x, y, z) = accel.last
                    StatGrid(
                        listOf(
                            "x / y / z" to "${Format.f2(x)} / ${Format.f2(y)} / ${Format.f2(z)}",
                            "|a|" to "${Format.f2(Motion.magnitude(x, y, z))} m/s²",
                            "Rate" to "${Format.f1(accel.hz)} Hz",
                            "Stand" to Motion.stand(x, y, z).label,
                            "Leans back" to "${Format.f1(Motion.leanBackDegrees(x, y, z))}°",
                            "Knocks" to accel.knocks.count.toString(),
                            "Motion (no gravity)" to "${Format.f2(accel.knocks.linear)} m/s²",
                        ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(Triple("x", accel.x, Coral), Triple("y", accel.y, Teal), Triple("z", accel.z, Sky)).forEach { (n, ring, c) ->
                            Column(Modifier.weight(1f)) {
                                Text(n, style = MaterialTheme.typography.labelMedium)
                                Sparkline(ring.toList(), c, min = -12f, max = 12f, capacity = 200, height = 80.dp)
                            }
                        }
                    }
                }
                Note("Ideas: wake the screen on a knock, switch to portrait layout when the frame is turned, pause when laid flat.")
            }

            Section(
                "Every declared sensor",
                note = "The HAL lists 8 hardware sensors plus Android's fused ones. Probe listens to all of them for 2 s.",
                trailing = {
                    ActionButton(if (probing) "Listening…" else "Probe", {
                        probing = true
                        scope.launch { probe = SensorProbe.probeAll(sm); probing = false }
                    }, enabled = !probing)
                },
            ) {
                val results = probe
                sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
                    val r = results?.firstOrNull { it.sensor == s }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(
                            when {
                                r == null -> Status.INFO
                                r.live -> Status.PASS
                                else -> Status.ABSENT
                            },
                            Modifier.width(76.dp),
                        )
                        Text(s.name, Modifier.width(280.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${SensorProbe.typeName(s)} · ${s.vendor} · ${SensorProbe.reportingMode(s)} · range ${Format.f1(s.maximumRange)}" +
                                (r?.let { if (it.live) " · ${Format.f1(it.hz)} Hz · ${it.last?.take(3)?.joinToString { v -> Format.f2(v) }}" else " · no events" } ?: ""),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(color = InkDim),
                        )
                    }
                }
            }

            Section("Not here at all") {
                Text("No light sensor (no auto-brightness), no proximity, gyroscope, compass, barometer or thermometer — the HAL entries have no chip behind them on the I²C bus.", style = Mono)
            }

            SelfTestSection(lab.id)
        }
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val sm = context.getSystemService(SensorManager::class.java)
        val results = SensorProbe.probeAll(sm)
        val accel = results.firstOrNull { it.sensor.type == Sensor.TYPE_ACCELEROMETER && it.sensor.vendor != "AOSP" }
        if (accel?.live == true) {
            val (x, y, z) = accel.last!!
            pass("Accelerometer", "${accel.sensor.name}: ${Format.f1(accel.hz)} Hz at GAME rate, now (${Format.f1(x)}, ${Format.f1(y)}, ${Format.f1(z)}) → ${Motion.stand(x, y, z).label}, leaning ${Format.f1(Motion.leanBackDegrees(x, y, z))}°")
        } else {
            fail("Accelerometer", "no events")
        }
        val silent = results.filter { !it.live }
        val live = results.filter { it.live && it != accel }
        if (live.isNotEmpty()) info("Other live sensors", live.joinToString { "${it.sensor.name} (${Format.f1(it.hz)} Hz)" })
        if (silent.isNotEmpty()) absent("Silent sensors", "${silent.size} declared but no events in 2 s: ${silent.joinToString { it.sensor.name }}")
        guard("Light sensor") {
            if (results.any { it.sensor.type == Sensor.TYPE_LIGHT && it.live }) pass("Light sensor", "live")
            else absent("Light sensor", "no data: automatic brightness can't work")
        }
        manual("Knock", "Sensors → knock on the frame's edge, the Knocks counter should go up")
        items
    }
}
