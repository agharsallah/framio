package dev.framio.mawaqit.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * A knock on the frame's case wakes the screen at night: the accelerometer (the frame's only real sensor) sees a
 * short spike once gravity is filtered out. Tuned in example 03's Sensors lab.
 */
class KnockSensor(context: Context, private val onKnock: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = KnockDetector()
    private var listening = false
    private var since = 0L

    /** Listens only while asked: 60 Hz sampling isn't free on this CPU. */
    fun listen(on: Boolean) {
        if (on == listening || accelerometer == null) return
        listening = on
        if (on) {
            since = SystemClock.uptimeMillis()
            detector.reset()
            sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        } else sensors.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = SystemClock.uptimeMillis()
        // the first samples after registering can be stale (seen on the frame: a "knock" 1 s after the screen went off)
        if (detector.add(t, e.values[0], e.values[1], e.values[2]) && t - since > SETTLE_MS) onKnock()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}

/** Gravity tracked with a low-pass filter; anything above [threshold] m/s² counts, at most once per [refractoryMs]. */
class KnockDetector(
    private val threshold: Float = 1.6f, // a bit above the lab's 1.2: a door closing shouldn't light up the room
    private val refractoryMs: Long = 600,
    private val alpha: Float = 0.9f,
) {
    private val gravity = FloatArray(3)
    private var primed = 0
    private var last = Long.MIN_VALUE / 2

    /** Gravity is learnt again (listening starts afresh). */
    fun reset() {
        primed = 0
    }

    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        val v = floatArrayOf(x, y, z)
        if (primed < 10) {
            for (i in 0..2) gravity[i] = if (primed == 0) v[i] else gravity[i] * alpha + v[i] * (1 - alpha)
            primed++
            return false
        }
        var sum = 0f
        for (i in 0..2) {
            val linear = v[i] - gravity[i]
            sum += linear * linear
            gravity[i] = gravity[i] * alpha + v[i] * (1 - alpha)
        }
        if (sqrt(sum) > threshold && timeMs - last > refractoryMs) {
            last = timeMs
            return true
        }
        return false
    }
}
