package dev.framio.meadow.hw

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Detects knocks on the frame's case: a short spike in acceleration after gravity is removed.
 * A low-pass filter tracks gravity; anything above [threshold] m/s² counts, at most once per
 * [refractoryMs]. Same detector as example 03's Sensors lab. Pure, unit-tested.
 */
class KnockDetector(
    private val threshold: Float = 1.4f,
    private val refractoryMs: Long = 400,
    private val alpha: Float = 0.9f,
) {
    private val gravity = FloatArray(3)
    private var primed = 0
    private var lastKnockMs = Long.MIN_VALUE / 2

    /** Feed one sample; returns true when it is a new knock. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (primed < 10) { // seed gravity from the first samples
            gravity[0] = if (primed == 0) x else gravity[0] * alpha + x * (1 - alpha)
            gravity[1] = if (primed == 0) y else gravity[1] * alpha + y * (1 - alpha)
            gravity[2] = if (primed == 0) z else gravity[2] * alpha + z * (1 - alpha)
            primed++
            return false
        }
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        gravity[0] = gravity[0] * alpha + x * (1 - alpha)
        gravity[1] = gravity[1] * alpha + y * (1 - alpha)
        gravity[2] = gravity[2] * alpha + z * (1 - alpha)
        val linear = sqrt(lx * lx + ly * ly + lz * lz)
        if (linear > threshold && timeMs - lastKnockMs > refractoryMs) {
            lastKnockMs = timeMs
            return true
        }
        return false
    }
}

/**
 * Listens to the accelerometer (the frame's only working sensor) while started.
 * Two knocks within [doubleMs] call [onDoubleKnock]; a single one calls [onKnock].
 */
class KnockListener(context: Context, private val onKnock: () -> Unit, private val onDoubleKnock: () -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var detector = KnockDetector()
    private var pendingSingle: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastKnock = 0L

    val available get() = sensor != null

    fun start() {
        val s = sensor ?: return
        detector = KnockDetector()
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
        pendingSingle?.let(handler::removeCallbacks)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        if (!detector.add(now, e.values[0], e.values[1], e.values[2])) return
        if (now - lastKnock < DOUBLE_MS) {
            pendingSingle?.let(handler::removeCallbacks)
            pendingSingle = null
            lastKnock = 0
            onDoubleKnock()
        } else {
            lastKnock = now
            val r = Runnable { pendingSingle = null; onKnock() }
            pendingSingle = r
            handler.postDelayed(r, DOUBLE_MS)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val DOUBLE_MS = 600L
    }
}
