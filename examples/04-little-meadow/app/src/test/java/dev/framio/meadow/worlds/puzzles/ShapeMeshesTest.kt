package dev.framio.meadow.worlds.puzzles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ShapeMeshesTest {

    private fun Mesh.normalLengthsAreOne() = (0 until vertexCount).all { i ->
        val l = sqrt(normals[i * 3] * normals[i * 3] + normals[i * 3 + 1] * normals[i * 3 + 1] + normals[i * 3 + 2] * normals[i * 3 + 2])
        abs(l - 1f) < 1e-3f
    }

    private fun Mesh.indicesValid() = indices.all { it in 0 until vertexCount } && indices.size % 3 == 0

    /** For convex solids around the origin: every normal points away from the middle (or along the surface). */
    private fun Mesh.normalsPointOut(centerY: Float = 0f) = (0 until vertexCount).all { i ->
        val px = positions[i * 3]; val py = positions[i * 3 + 1] - centerY; val pz = positions[i * 3 + 2]
        px * normals[i * 3] + py * normals[i * 3 + 1] + pz * normals[i * 3 + 2] >= -1e-3f
    }

    @Test fun sphereCounts() {
        val m = ShapeMeshes.sphere(1f, 10, 20)
        assertEquals(11 * 21, m.vertexCount)
        assertEquals(10 * 20 * 6, m.indices.size)
        assertTrue(m.normalLengthsAreOne() && m.indicesValid() && m.normalsPointOut())
    }

    @Test fun roundedCubeCounts() {
        val m = ShapeMeshes.roundedCube(0.8f, 0.2f, 6)
        assertEquals(6 * 7 * 7, m.vertexCount)
        assertEquals(6 * 6 * 6 * 6, m.indices.size)
        assertTrue(m.normalLengthsAreOne() && m.indicesValid() && m.normalsPointOut())
        assertTrue((0 until m.vertexCount).all { i -> (0..2).all { k -> abs(m.positions[i * 3 + k]) <= 0.8f + 1e-4f } })
    }

    @Test fun latheShapes() {
        for (m in listOf(ShapeMeshes.cylinder(), ShapeMeshes.cone())) {
            assertTrue(m.normalLengthsAreOne() && m.indicesValid() && m.normalsPointOut())
        }
        val c = ShapeMeshes.cylinder(radial = 10)
        assertEquals(14 * 11, c.vertexCount) // centre, 6 + 6 rim points, centre: 14 rings of 11
    }

    @Test fun pyramidHasFlatFaces() {
        val m = ShapeMeshes.pyramid()
        assertEquals(16, m.vertexCount)
        assertEquals(18, m.indices.size)
        assertTrue(m.normalLengthsAreOne() && m.indicesValid() && m.normalsPointOut())
        // the three corners of each side share one normal
        for (f in 0 until 4) {
            val n0 = (0..2).map { m.normals[(f * 3) * 3 + it] }
            for (v in 1..2) assertEquals(n0, (0..2).map { m.normals[(f * 3 + v) * 3 + it] })
        }
    }

    @Test fun torusNormalsPointAwayFromTheRing() {
        val big = 0.7f
        val m = ShapeMeshes.torus(big, 0.3f, 16, 8)
        assertEquals(17 * 9, m.vertexCount)
        assertTrue(m.normalLengthsAreOne() && m.indicesValid())
        for (i in 0 until m.vertexCount) {
            val x = m.positions[i * 3]; val y = m.positions[i * 3 + 1]; val z = m.positions[i * 3 + 2]
            val r = sqrt(x * x + z * z)
            val cx = x / r * big; val cz = z / r * big
            val d = (x - cx) * m.normals[i * 3] + y * m.normals[i * 3 + 1] + (z - cz) * m.normals[i * 3 + 2]
            assertTrue(d > 0f)
        }
    }

    @Test fun everySolidFitsSixteenBitIndices() {
        for (s in Solid.entries) {
            val m = ShapeMeshes.of(s)
            assertTrue(m.vertexCount < 32767)
            assertEquals(m.vertexCount * 6, m.interleaved().size)
        }
    }
}
