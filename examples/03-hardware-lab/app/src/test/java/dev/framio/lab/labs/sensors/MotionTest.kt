package dev.framio.lab.labs.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {

    @Test
    fun `reading measured on the frame's stand is landscape upright leaning back about 20 degrees`() {
        assertEquals(Motion.Stand.Landscape, Motion.stand(0.1f, 9.5f, 3.4f))
        assertEquals(19.7f, Motion.leanBackDegrees(0.1f, 9.5f, 3.4f), 0.2f)
    }

    @Test
    fun `gravity along each axis maps to the matching stand`() {
        assertEquals(Motion.Stand.LandscapeUpsideDown, Motion.stand(0f, -9.8f, 0f))
        assertEquals(Motion.Stand.PortraitLeftDown, Motion.stand(9.8f, 0f, 0f))
        assertEquals(Motion.Stand.PortraitRightDown, Motion.stand(-9.8f, 0f, 0f))
        assertEquals(Motion.Stand.FaceUp, Motion.stand(0f, 0f, 9.8f))
        assertEquals(Motion.Stand.FaceDown, Motion.stand(0f, 0f, -9.8f))
    }

    @Test
    fun `lean back is 0 upright and 90 flat`() {
        assertEquals(0f, Motion.leanBackDegrees(0f, 9.8f, 0f), 1e-4f)
        assertEquals(90f, Motion.leanBackDegrees(0f, 0f, 9.8f), 1e-4f)
    }
}
