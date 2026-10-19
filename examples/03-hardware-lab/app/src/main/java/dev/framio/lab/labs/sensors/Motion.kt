package dev.framio.lab.labs.sensors

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Accelerometer math. Pure Kotlin, unit-tested.
 * Axes follow Android: relative to the display's natural orientation, which on this frame is landscape
 * (the panel is portrait but rotated 90° by SurfaceFlinger). Standing upright on its stand gives y ≈ +9.5.
 */
object Motion {

    enum class Stand(val label: String) {
        Landscape("Landscape, upright"),
        LandscapeUpsideDown("Landscape, upside down"),
        PortraitLeftDown("Portrait, left edge down"),
        PortraitRightDown("Portrait, right edge down"),
        FaceUp("Flat, screen up"),
        FaceDown("Flat, screen down"),
    }

    /** Which way gravity points, i.e. how the frame is standing. */
    fun stand(x: Float, y: Float, z: Float): Stand {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            ay >= ax && ay >= az -> if (y > 0) Stand.Landscape else Stand.LandscapeUpsideDown
            ax >= ay && ax >= az -> if (x > 0) Stand.PortraitLeftDown else Stand.PortraitRightDown
            else -> if (z > 0) Stand.FaceUp else Stand.FaceDown
        }
    }

    /** How far the screen leans back from vertical, in degrees (0 = upright, 90 = flat). */
    fun leanBackDegrees(x: Float, y: Float, z: Float): Float {
        val upright = sqrt(x * x + y * y)
        return Math.toDegrees(atan2(z.toDouble(), upright.toDouble())).toFloat()
    }

    fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
}

/**
 * Detects knocks/taps on the frame: a short spike in acceleration after gravity is removed.
 * A low-pass filter tracks gravity; anything above [threshold] m/s² counts, at most once per [refractoryMs].
 */
class KnockDetector(
    private val threshold: Float = 1.2f,
    private val refractoryMs: Long = 350,
    private val alpha: Float = 0.9f,
) {
    private val gravity = FloatArray(3)
    private var primed = 0
    private var lastKnockMs = Long.MIN_VALUE / 2

    var count = 0
        private set

    /** Last linear (gravity-free) magnitude, for display. */
    var linear = 0f
        private set

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
        linear = Motion.magnitude(lx, ly, lz)
        if (linear > threshold && timeMs - lastKnockMs > refractoryMs) {
            lastKnockMs = timeMs
            count++
            return true
        }
        return false
    }
}
