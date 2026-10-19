package dev.framio.meadow.worlds.puzzles

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh for the shapes view: positions and unit normals (xyz each) and 16-bit indices.
 * Pure Kotlin (unit-tested); ShapeGL uploads it.
 */
internal class Mesh(val positions: FloatArray, val normals: FloatArray, val indices: ShortArray) {
    val vertexCount get() = positions.size / 3

    /** pos.xyz, normal.xyz per vertex, for one VBO. */
    fun interleaved(): FloatArray {
        val out = FloatArray(vertexCount * 6)
        for (i in 0 until vertexCount) {
            for (k in 0 until 3) {
                out[i * 6 + k] = positions[i * 3 + k]
                out[i * 6 + 3 + k] = normals[i * 3 + k]
            }
        }
        return out
    }
}

private class MeshBuilder {
    private val p = ArrayList<Float>()
    private val n = ArrayList<Float>()
    private val idx = ArrayList<Short>()
    val count get() = p.size / 3

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-6f } ?: 1f
        p += x; p += y; p += z
        n += nx / l; n += ny / l; n += nz / l
        return count - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        idx += a.toShort(); idx += b.toShort(); idx += c.toShort()
    }

    /** A grid of (rows+1)×(cols+1) vertices starting at [first], row-major: two triangles per cell. */
    fun grid(first: Int, rows: Int, cols: Int) {
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = first + r * (cols + 1) + c
            val b = a + 1
            val d = a + cols + 1
            val e = d + 1
            tri(a, d, b)
            tri(b, d, e)
        }
    }

    fun build() = Mesh(p.toFloatArray(), n.toFloatArray(), idx.toShortArray())
}

/** A point of a lathe profile: radius, height and the profile's outward normal (in r, y). */
private data class ProfilePt(val r: Float, val y: Float, val nr: Float, val ny: Float)

internal object ShapeMeshes {

    fun sphere(radius: Float = 1f, lat: Int = 28, lon: Int = 40): Mesh {
        val b = MeshBuilder()
        for (i in 0..lat) {
            val th = PI * i / lat
            for (j in 0..lon) {
                val ph = 2 * PI * j / lon
                val nx = (sin(th) * cos(ph)).toFloat()
                val ny = cos(th).toFloat()
                val nz = (sin(th) * sin(ph)).toFloat()
                b.vertex(nx * radius, ny * radius, nz * radius, nx, ny, nz)
            }
        }
        b.grid(0, lat, lon)
        return b.build()
    }

    /** A cube with softly rounded edges: each face is a grid pushed onto a rounded box. */
    fun roundedCube(half: Float = 0.78f, round: Float = 0.16f, seg: Int = 10): Mesh {
        val b = MeshBuilder()
        val inner = half - round
        // each face: its normal axis and two in-plane axes (u × v = n, so triangles face outwards)
        val faces = listOf(
            Triple(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)),
            Triple(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f)),
            Triple(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
            Triple(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            Triple(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
            Triple(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
        )
        for ((n, u, v) in faces) {
            val first = b.count
            for (i in 0..seg) for (j in 0..seg) {
                val s = -1f + 2f * j / seg
                val t = -1f + 2f * i / seg
                val q = FloatArray(3) { k -> (n[k] + u[k] * s + v[k] * t) * half }
                val c = FloatArray(3) { k -> q[k].coerceIn(-inner, inner) }
                var dx = q[0] - c[0]
                var dy = q[1] - c[1]
                var dz = q[2] - c[2]
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                if (l < 1e-6f) { dx = n[0]; dy = n[1]; dz = n[2] } else { dx /= l; dy /= l; dz /= l }
                b.vertex(c[0] + dx * round, c[1] + dy * round, c[2] + dz * round, dx, dy, dz)
            }
            b.grid(first, seg, seg)
        }
        return b.build()
    }

    /** A cylinder standing on y, radius [r], height 2·[h], with a soft rounded rim. */
    fun cylinder(r: Float = 0.72f, h: Float = 0.8f, bevel: Float = 0.12f, radial: Int = 44): Mesh {
        val prof = ArrayList<ProfilePt>()
        prof += ProfilePt(0f, -h, 0f, -1f)
        arc(prof, r - bevel, -h + bevel, bevel, -90.0, 0.0, 5)
        arc(prof, r - bevel, h - bevel, bevel, 0.0, 90.0, 5)
        prof += ProfilePt(0f, h, 0f, 1f)
        return lathe(prof, radial)
    }

    /** A cone: base radius [r] at y = -[h]/2, tip at +[h]/2; the base edge is softened a little. */
    fun cone(r: Float = 0.88f, h: Float = 1.9f, bevel: Float = 0.07f, radial: Int = 48): Mesh {
        val side = atan2(r.toDouble(), h.toDouble()) * 180 / PI // the side's normal, above horizontal
        val prof = ArrayList<ProfilePt>()
        prof += ProfilePt(0f, -h / 2, 0f, -1f)
        arc(prof, r - bevel * 1.6f, -h / 2 + bevel, bevel, -90.0, side, 5)
        val sn = side * PI / 180
        prof += ProfilePt(0.002f, h / 2, cos(sn).toFloat(), sin(sn).toFloat())
        return lathe(prof, radial)
    }

    /** A square pyramid with flat faces: base half-width [a] at y = -[h]/2, tip at +[h]/2. */
    fun pyramid(a: Float = 0.92f, h: Float = 1.65f): Mesh {
        val b = MeshBuilder()
        val y0 = -h / 2
        val y1 = h / 2
        val base = listOf(floatArrayOf(-a, y0, a), floatArrayOf(a, y0, a), floatArrayOf(a, y0, -a), floatArrayOf(-a, y0, -a))
        for (i in 0 until 4) {
            val p = base[i]
            val q = base[(i + 1) % 4]
            // face normal = (q - p) × (tip - p)
            val ux = q[0] - p[0]; val uy = q[1] - p[1]; val uz = q[2] - p[2]
            val vx = -p[0]; val vy = y1 - p[1]; val vz = -p[2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val i0 = b.vertex(p[0], p[1], p[2], nx, ny, nz)
            val i1 = b.vertex(q[0], q[1], q[2], nx, ny, nz)
            val i2 = b.vertex(0f, y1, 0f, nx, ny, nz)
            b.tri(i0, i1, i2)
        }
        val f = IntArray(4) { b.vertex(base[it][0], y0, base[it][2], 0f, -1f, 0f) }
        b.tri(f[0], f[2], f[1])
        b.tri(f[0], f[3], f[2])
        return b.build()
    }

    /** A ring (doughnut) in the xz plane: ring radius [big], tube radius [small]. */
    fun torus(big: Float = 0.68f, small: Float = 0.34f, segU: Int = 48, segV: Int = 24): Mesh {
        val b = MeshBuilder()
        for (i in 0..segU) {
            val u = 2 * PI * i / segU
            for (j in 0..segV) {
                val v = 2 * PI * j / segV
                val nx = (cos(v) * cos(u)).toFloat()
                val ny = sin(v).toFloat()
                val nz = (cos(v) * sin(u)).toFloat()
                val cx = (big * cos(u)).toFloat()
                val cz = (big * sin(u)).toFloat()
                b.vertex(cx + small * nx, small * ny, cz + small * nz, nx, ny, nz)
            }
        }
        b.grid(0, segU, segV)
        return b.build()
    }

    fun of(s: Solid): Mesh = when (s) {
        Solid.Sphere -> sphere()
        Solid.Cube -> roundedCube()
        Solid.Cylinder -> cylinder()
        Solid.Cone -> cone()
        Solid.Pyramid -> pyramid()
        Solid.Torus -> torus()
    }

    /** Where each solid's lowest point is (it stands on the ground there). */
    fun bottom(s: Solid): Float = when (s) {
        Solid.Sphere -> -1f
        Solid.Cube -> -0.78f
        Solid.Cylinder -> -0.8f
        Solid.Cone -> -0.95f
        Solid.Pyramid -> -0.825f
        Solid.Torus -> -0.34f
    }

    private fun arc(out: MutableList<ProfilePt>, cr: Float, cy: Float, rad: Float, fromDeg: Double, toDeg: Double, steps: Int) {
        for (i in 0..steps) {
            val a = (fromDeg + (toDeg - fromDeg) * i / steps) * PI / 180
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            out += ProfilePt(cr + rad * c, cy + rad * s, c, s)
        }
    }

    /** Spins a profile around y: one ring of [radial]+1 vertices per profile point. */
    private fun lathe(prof: List<ProfilePt>, radial: Int): Mesh {
        val b = MeshBuilder()
        for (pt in prof) {
            for (j in 0..radial) {
                val a = 2 * PI * j / radial
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                b.vertex(pt.r * c, pt.y, pt.r * s, pt.nr * c, pt.ny, pt.nr * s)
            }
        }
        b.grid(0, prof.size - 1, radial)
        return b.build()
    }
}
