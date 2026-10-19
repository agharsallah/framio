package dev.framio.pomodoro.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.framio.pomodoro.core.gesture.Knock
import dev.framio.pomodoro.core.gesture.KnockDetector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Knock twice to peek (PLAN §6.1): the accelerometer feeds core's [KnockDetector], and each double knock is emitted
 * on [doubles]. The sensor is registered **only while listening** ([listen] true from Deep focus's resume effect, false
 * on pause/dispose), at SENSOR_DELAY_GAME, on its own thread so the main thread never sees 100 samples a second.
 *
 * M0: the HAL sensor is `getDefaultSensor(TYPE_ACCELEROMETER)` ("Gravity sensor", handle 0, ~100 Hz). While our
 * own speaker plays ([playing]) the threshold is raised, so a chime doesn't knock.
 */
class Motion(context: Context, private val playing: StateFlow<Boolean>) {
    // Looked up on first use (Deep focus), not while the first frame is composed
    private val manager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager? }
    private val sensor: Sensor? by lazy { manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private val detector = KnockDetector()
    private val _doubles = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Last real sample, for the debug injection (in the sensor thread only). */
    private var lastMs = 0L
    private val rest = floatArrayOf(0f, 9.5f, 3.8f)

    /** Synthetic samples run ahead of the real clock; real ones older than this are ignored (debug injection). */
    private var ignoreUntilMs = Long.MIN_VALUE

    val doubles: SharedFlow<Unit> = _doubles.asSharedFlow()

    @Volatile var listening = false
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ms = event.timestamp / 1_000_000
            val v = event.values
            rest[0] = v[0]
            rest[1] = v[1]
            rest[2] = v[2]
            lastMs = ms
            if (ms < ignoreUntilMs) return
            feed(ms, v[0], v[1], v[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun feed(ms: Long, x: Float, y: Float, z: Float) {
        detector.boost = if (playing.value) SPEAKER_BOOST else 0f
        if (detector.add(ms, x, y, z) == Knock.Double) {
            Logs.event("motion", "double knock")
            _doubles.tryEmit(Unit)
        }
    }

    /** Main thread. Registers or unregisters the accelerometer; idempotent. */
    fun listen(on: Boolean) {
        if (on == listening) return
        val m = manager
        val s = sensor
        if (m == null || s == null) {
            Logs.warn("motion", "no accelerometer")
            return
        }
        if (on) {
            val t = HandlerThread("knock").apply { start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            h.post { detector.reset() }
            listening = m.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME, h)
            Logs.event("motion", "accelerometer ${if (listening) "on" else "failed"}")
            if (!listening) { // no thread left behind for a sensor that never came
                t.quitSafely()
                thread = null
                handler = null
            }
        } else {
            m.unregisterListener(listener)
            thread?.quitSafely()
            thread = null
            handler = null
            listening = false
            Logs.event("motion", "accelerometer off")
        }
    }

    /**
     * Debug (adb `--es cmd knock`): feeds the detector a synthetic double knock, two 4 m/s² taps 300 ms apart in
     * 100 Hz samples, then quiet. It goes through the same detector and flow as a real knock, so it proves the
     * wiring; only while listening (the real sensor registered). False when not listening.
     */
    fun injectDoubleKnock(): Boolean {
        val h = handler ?: return false
        h.post {
            var t = lastMs + 10
            fun sample(dz: Float) {
                feed(t, rest[0], rest[1], rest[2] + dz)
                t += 10
            }
            repeat(5) { sample(0f) }
            sample(4f)
            repeat(29) { sample(0f) }
            sample(4f)
            repeat(80) { sample(0f) }
            ignoreUntilMs = t
            Logs.event("motion", "injected a double knock")
        }
        return true
    }

    private companion object {
        /** m/s² added to the knock threshold while our speaker plays. */
        const val SPEAKER_BOOST = 1.5f
    }
}
