package dev.framio.lab.labs.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay

/** Listens to sensors for a moment to find out which ones really deliver data. */
object SensorProbe {

    data class Result(val sensor: Sensor, val events: Int, val last: FloatArray?) {
        val live get() = events > 0
        val hz get(): Double = events / (PROBE_MS / 1000.0)
    }

    const val PROBE_MS = 2000L

    /** Registers every sensor at once for [PROBE_MS] and counts events. */
    suspend fun probeAll(sm: SensorManager, sensors: List<Sensor> = sm.getSensorList(Sensor.TYPE_ALL)): List<Result> {
        val counts = IntArray(sensors.size)
        val lasts = arrayOfNulls<FloatArray>(sensors.size)
        val listeners = sensors.mapIndexed { i, s ->
            object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    counts[i]++
                    lasts[i] = e.values.copyOf()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }.also { sm.registerListener(it, s, SensorManager.SENSOR_DELAY_GAME) }
        }
        try {
            delay(PROBE_MS)
        } finally {
            listeners.forEach(sm::unregisterListener)
        }
        return sensors.mapIndexed { i, s -> Result(s, counts[i], lasts[i]) }
    }

    fun typeName(s: Sensor): String = s.stringType.removePrefix("android.sensor.")

    fun reportingMode(s: Sensor) = when (s.reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "trigger"
        else -> "?"
    }
}
