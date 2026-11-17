package com.personal.hockey.render.mesh

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small mesh in bone-local space, built once at start-up and posed every frame by [MeshPainter].
 *
 * Vertex v sits at along = t[v] * boneLength + e[v] metres from the bone's first joint, a[v] m to the
 * side, b[v] m up (bone frame). Its unit normal is nt[v] along + na[v] side + nb[v] up. So t stretches
 * with the bone and e does not. slot[v] indexes a palette. [tri] holds three vertex indices per triangle.
 *
 * **Bone frame.** along points from the bone's first joint to its second. side is the figure's left with
 * its along component removed (or up × along when the bone points along the left). up = along × side.
 * (along, side, up) is right-handed, like world (x, y, z). For a bone along +x on a figure whose left is
 * +y, it is exactly world (x, y, z).
 *
 * **Winding.** Triangles wind counter-clockwise seen from outside: (v1 − v0) × (v2 − v0), taken in the
 * right-handed (along, a, b) frame, points out of the mesh. [MeshPainter] keeps a triangle only when its
 * projection has negative signed area (x1 − x0)(y2 − y0) − (x2 − x0)(y1 − y0). Screen y grows downward,
 * so a triangle the viewer sees wound counter-clockwise has negative area there. A face is visible only
 * from the side it winds toward, so parts must be closed.
 */
class Mesh(
    val t: FloatArray, val e: FloatArray, val a: FloatArray, val b: FloatArray,
    val nt: FloatArray, val na: FloatArray, val nb: FloatArray,
    val slot: ByteArray, val tri: ShortArray,
) {
    val vertexCount: Int get() = t.size
    val triangleCount: Int get() = tri.size / 3

    init {
        val n = t.size
        require(e.size == n && a.size == n && b.size == n && nt.size == n && na.size == n && nb.size == n && slot.size == n) {
            "vertex arrays differ in length"
        }
        require(tri.size % 3 == 0) { "tri holds ${tri.size} indices, not a multiple of 3" }
        for (i in tri) require(i.toInt() in 0 until n) { "index $i out of range" }
    }
}

/**
 * A mesh hung on the bone from joint slot [from] to joint slot [to]. [nearer] metres are taken off
 * the part's depth when parts are ordered (negative pushes it back), so a part that should cover a
 * neighbour (a jersey over its pants) still does when a pose moves their middles past each other.
 */
class Part(val from: Int, val to: Int, val mesh: Mesh, val nearer: Float = 0f)

/**
 * Primitives in bone-local space, wound as [Mesh] describes. Fractions of the bone (t) place things
 * that should stretch with it; radii and widths are metres.
 */
object MeshBuilder {
    private val EMPTY = FloatArray(0)

    /**
     * A box from t = [len0] to t = [len1] along the bone, [w0] m wide (side) by [d0] m deep (up) at the
     * len0 end, tapering to [w1] by [d1] at the len1 end, centred on the bone. Flat-shaded: 24 vertices,
     * 12 triangles.
     */
    fun taperedBox(len0: Float, len1: Float, w0: Float, d0: Float, w1: Float, d1: Float, slot: Int): Mesh {
        val m = Builder()
        // Corner (end, sa, sb): end 0 or 1, sa and sb ±1 for the side and up halves.
        fun corner(end: Int, sa: Int, sb: Int, nt: Float, na: Float, nb: Float): Int =
            if (end == 0) m.vertex(len0, 0f, sa * w0 / 2, sb * d0 / 2, nt, na, nb, slot)
            else m.vertex(len1, 0f, sa * w1 / 2, sb * d1 / 2, nt, na, nb, slot)
        // Each face's corners counter-clockwise seen from outside.
        fun face(nt: Float, na: Float, nb: Float, vararg c: Int) {
            val i0 = corner(c[0], c[1], c[2], nt, na, nb)
            val i1 = corner(c[3], c[4], c[5], nt, na, nb)
            val i2 = corner(c[6], c[7], c[8], nt, na, nb)
            val i3 = corner(c[9], c[10], c[11], nt, na, nb)
            m.quad(i0, i1, i2, i3)
        }
        face(0f, 0f, 1f, 0, -1, 1, 1, -1, 1, 1, 1, 1, 0, 1, 1)          // up
        face(0f, 0f, -1f, 0, -1, -1, 0, 1, -1, 1, 1, -1, 1, -1, -1)     // down
        face(0f, 1f, 0f, 0, 1, -1, 0, 1, 1, 1, 1, 1, 1, 1, -1)          // side
        face(0f, -1f, 0f, 0, -1, -1, 1, -1, -1, 1, -1, 1, 0, -1, 1)     // other side
        face(1f, 0f, 0f, 1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1)        // len1 end
        face(-1f, 0f, 0f, 0, -1, -1, 0, -1, 1, 0, 1, 1, 0, 1, -1)       // len0 end
        return m.build()
    }

    /**
     * A tube from the bone's first joint (radius [r0]) to its second ([r1]), closed by hemispheres that
     * reach r0 behind the first joint and r1 past the second. [sides] vertices round, [rings] latitude
     * steps per hemisphere (1 makes a cone). Smooth-shaded.
     *
     * [bands] holds (t0, t1) pairs: the tube between those fractions takes [bandSlot], with its own edge
     * rings so the band stays crisp. A band reaching t = 0 or 1 colours that hemisphere too.
     */
    fun capsule(r0: Float, r1: Float, sides: Int, rings: Int, slot: Int, bands: FloatArray = EMPTY, bandSlot: Int = slot): Mesh {
        require(sides >= 3 && rings >= 1) { "need sides >= 3 and rings >= 1" }
        require(bands.size % 2 == 0) { "bands come in (t0, t1) pairs" }
        val cuts = ArrayList<Float>().apply {
            add(0f); add(1f)
            for (x in bands) add(x.coerceIn(0f, 1f))
        }.distinct().sorted()
        val segSlot = IntArray(cuts.size - 1) { i ->
            val mid = (cuts[i] + cuts[i + 1]) / 2
            var inBand = false
            for (k in bands.indices step 2) if (mid > bands[k] && mid < bands[k + 1]) inBand = true
            if (inBand) bandSlot else slot
        }
        val first = segSlot.first()
        val last = segSlot.last()
        val step = (PI / 2 / rings).toFloat()
        val p = ArrayList<Ring>()
        for (k in rings downTo 1) {          // first hemisphere, pole to equator
            val s = sin(k * step); val c = cos(k * step)
            p += Ring(0f, -r0 * s, if (k == rings) 0f else r0 * c, -s, c, first, joinPrev = k != rings)
        }
        p += Ring(0f, 0f, r0, 0f, 1f, first, joinPrev = true)
        for (i in 1 until cuts.size) {
            val r = r0 + (r1 - r0) * cuts[i]
            p += Ring(cuts[i], 0f, r, 0f, 1f, segSlot[i - 1], joinPrev = true)
            if (i < segSlot.size && segSlot[i] != segSlot[i - 1]) p += Ring(cuts[i], 0f, r, 0f, 1f, segSlot[i], joinPrev = false)
        }
        for (k in 1..rings) {                // second hemisphere, equator to pole
            val s = sin(k * step); val c = cos(k * step)
            p += Ring(1f, r1 * s, if (k == rings) 0f else r1 * c, s, c, last, joinPrev = true)
        }
        return lathe(p, sides, 1f, 1f)
    }

    /**
     * An ellipsoid centred on the bone's first joint: [rx] m along the bone, [ry] to the side, [rz] up.
     * It does not stretch with the bone. [sides] vertices round, [rings] latitude steps pole to pole
     * (at least 2). Smooth-shaded.
     */
    fun ellipsoid(rx: Float, ry: Float, rz: Float, sides: Int, rings: Int, slot: Int): Mesh {
        require(sides >= 3 && rings >= 2) { "need sides >= 3 and rings >= 2" }
        val p = ArrayList<Ring>()
        for (k in 0..rings) {
            val phi = (-PI / 2 + k * PI / rings).toFloat()
            val s = sin(phi); val c = cos(phi)
            val pole = k == 0 || k == rings
            p += Ring(0f, rx * s, if (pole) 0f else c, s / rx, c, slot, joinPrev = k > 0)
        }
        return lathe(p, sides, ry, rz)
    }

    /**
     * [mesh] with its side and up coordinates scaled by [ka] and [kb] (both > 0), then moved by [t]
     * (bone fractions), [e], [a] and [b] (metres). Normals follow the scaling, so a sphere squashed
     * this way shades like the matching ellipsoid; winding is unchanged.
     */
    fun placed(mesh: Mesh, t: Float = 0f, e: Float = 0f, a: Float = 0f, b: Float = 0f, ka: Float = 1f, kb: Float = 1f): Mesh {
        require(ka > 0f && kb > 0f) { "scales must be positive" }
        val m = Builder()
        for (v in 0 until mesh.vertexCount) {
            m.vertex(mesh.t[v] + t, mesh.e[v] + e, mesh.a[v] * ka + a, mesh.b[v] * kb + b,
                mesh.nt[v], mesh.na[v] / ka, mesh.nb[v] / kb, mesh.slot[v].toInt())
        }
        for (i in mesh.tri) m.index(i.toInt())
        return m.build()
    }

    /** All of [parts] as one mesh. */
    fun join(vararg parts: Mesh): Mesh {
        val m = Builder()
        for (part in parts) {
            val base = m.count
            for (v in 0 until part.vertexCount) {
                m.vertex(part.t[v], part.e[v], part.a[v], part.b[v], part.nt[v], part.na[v], part.nb[v], part.slot[v].toInt())
            }
            for (i in part.tri) m.index(base + i)
        }
        return m.build()
    }

    /**
     * One ring of a surface of revolution about the bone: at (t, e) along it, radius [r] (0 makes a
     * pole), with normal [nAlong] along the bone plus [nRad] outward. [joinPrev] stitches it to the ring
     * before; false starts a new strip (a crisp colour edge at the same place).
     */
    private class Ring(val t: Float, val e: Float, val r: Float, val nAlong: Float, val nRad: Float, val slot: Int, val joinPrev: Boolean)

    /**
     * Revolves [rings], listed in increasing along, through [sides] angles. Ring points sit at
     * a = r·ka·cosθ, b = r·kb·sinθ; normals are (nAlong, nRad·cosθ/ka, nRad·sinθ/kb) normalised,
     * which is exact for an ellipsoid scaled by (·, ka, kb).
     */
    private fun lathe(rings: List<Ring>, sides: Int, ka: Float, kb: Float): Mesh {
        val m = Builder()
        val start = IntArray(rings.size)
        for ((i, ring) in rings.withIndex()) {
            start[i] = m.count
            if (ring.r == 0f) {
                m.vertex(ring.t, ring.e, 0f, 0f, if (ring.nAlong < 0f) -1f else 1f, 0f, 0f, ring.slot)
            } else {
                for (k in 0 until sides) {
                    val th = (2 * PI * k / sides).toFloat()
                    val c = cos(th); val s = sin(th)
                    m.vertex(ring.t, ring.e, ring.r * ka * c, ring.r * kb * s, ring.nAlong, ring.nRad * c / ka, ring.nRad * s / kb, ring.slot)
                }
            }
        }
        for (i in 1 until rings.size) {
            if (!rings[i].joinPrev) continue
            val p = start[i - 1]; val q = start[i]
            val pPole = rings[i - 1].r == 0f; val qPole = rings[i].r == 0f
            for (k in 0 until sides) {
                val k1 = (k + 1) % sides
                when {
                    pPole && qPole -> {}
                    pPole -> m.triangle(p, q + k1, q + k)
                    qPole -> m.triangle(p + k, p + k1, q)
                    else -> { m.triangle(p + k, p + k1, q + k); m.triangle(p + k1, q + k1, q + k) }
                }
            }
        }
        return m.build()
    }

    /** Collects vertices and triangles at start-up; normals are normalised on the way in. */
    private class Builder {
        private val v = ArrayList<Float>()
        private val slots = ArrayList<Int>()
        private val idx = ArrayList<Int>()
        val count: Int get() = slots.size

        fun vertex(t: Float, e: Float, a: Float, b: Float, nt: Float, na: Float, nb: Float, slot: Int): Int {
            val len = sqrt(nt * nt + na * na + nb * nb)
            v += t; v += e; v += a; v += b; v += nt / len; v += na / len; v += nb / len
            slots += slot
            return count - 1
        }

        fun index(i: Int) { idx += i }

        fun triangle(i0: Int, i1: Int, i2: Int) { idx += i0; idx += i1; idx += i2 }

        /** Corners counter-clockwise seen from outside. */
        fun quad(i0: Int, i1: Int, i2: Int, i3: Int) { triangle(i0, i1, i2); triangle(i0, i2, i3) }

        fun build(): Mesh {
            val n = count
            require(n <= Short.MAX_VALUE) { "$n vertices do not fit short indices" }
            return Mesh(
                t = FloatArray(n) { v[it * 7] }, e = FloatArray(n) { v[it * 7 + 1] },
                a = FloatArray(n) { v[it * 7 + 2] }, b = FloatArray(n) { v[it * 7 + 3] },
                nt = FloatArray(n) { v[it * 7 + 4] }, na = FloatArray(n) { v[it * 7 + 5] }, nb = FloatArray(n) { v[it * 7 + 6] },
                slot = ByteArray(n) { slots[it].toByte() }, tri = ShortArray(idx.size) { idx[it].toShort() },
            )
        }
    }
}
