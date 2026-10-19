package dev.framio.hub.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.framio.hub.core.sensors.KnockPattern
import dev.framio.hub.core.sensors.Stand
import dev.framio.hub.core.sensors.StandFilter
import java.io.File
import java.io.Writer

/**
 * The accelerometer, only while the Hub is on screen. Samples arrive on a background thread and feed two
 * pure detectors from `:core`; their results are posted to the main thread.
 *
 * Debug builds can record raw samples to CSV (`--es record accel`): real knocks, door slams and full-volume
 * melodies become test fixtures for KnockPattern (spike S4).
 */
class Accelerometer(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var knock = KnockPattern()
    private var stand = StandFilter()
    private var onKnock: () -> Unit = {}
    private var onStand: (Stand) -> Unit = {}
    private var boost: () -> Float = { 0f }
    private var recorder: Writer? = null
    private var recordUntilNs = 0L

    val present get() = sensor != null

    fun start(onKnock: () -> Unit, onStand: (Stand) -> Unit, boost: () -> Float) {
        if (sensor == null || thread != null) return
        this.onKnock = onKnock
        this.onStand = onStand
        this.boost = boost
        knock = KnockPattern()
        stand = StandFilter()
        val t = HandlerThread("hub-sensors").apply { start() }
        thread = t
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        stopRecording()
    }

    /** Debug: writes [seconds] of samples to files/accel-<time>.csv. */
    fun record(seconds: Int): File {
        val file = File(context.filesDir, "accel-${System.currentTimeMillis()}.csv")
        recorder = file.bufferedWriter().apply { write("t_ms,x,y,z,linear\n") }
        recordUntilNs = System.nanoTime() + seconds * 1_000_000_000L
        Logs.event("sensors", "recording ${seconds}s to ${file.name}")
        return file
    }

    override fun onSensorChanged(e: SensorEvent) {
        val ms = e.timestamp / 1_000_000
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        knock.boost = boost()
        if (knock.add(ms, x, y, z)) {
            Logs.event("knock", "double knock")
            main.post { onKnock() }
        }
        stand.add(ms, x, y, z)?.let { s ->
            Logs.event("stand", s.name)
            main.post { onStand(s) }
        }
        recorder?.let { w ->
            w.write("$ms,$x,$y,$z,${knock.linear}\n")
            if (System.nanoTime() > recordUntilNs) stopRecording()
        }
    }

    private fun stopRecording() {
        recorder?.let {
            it.close()
            Logs.event("sensors", "recording done")
        }
        recorder = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
