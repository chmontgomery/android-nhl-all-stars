package com.personal.hockey.render.mesh

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.personal.hockey.render.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Plain JVM: [Camera] and [MeshPainter] touch no Android classes, and the sinks here never flush. */
class MeshPainterTest {
    private val camera = Camera().apply { setViewport(2400f, 1080f) }
    private val painter = MeshPainter()
    private val grey = intArrayOf(0xFF808080.toInt())
    private val red = intArrayOf(0xFFFF0000.toInt())
    private val blue = intArrayOf(0xFF0000FF.toInt())
    private val unitBox = MeshBuilder.taperedBox(0f, 1f, 1f, 1f, 1f, 1f, 0)

    // Joint 0 → joint 1 is the bone; parts below use Part(0, 1, mesh).
    private val jx = FloatArray(2)
    private val jy = FloatArray(2)
    private val jz = FloatArray(2)

    private fun bone(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        jx[0] = x0; jy[0] = y0; jz[0] = z0; jx[1] = x1; jy[1] = y1; jz[1] = z1
    }

    /** Records every triangle it is given, in order. */
    private class RecordingSink : TriangleSink {
        val x = ArrayList<Float>(); val y = ArrayList<Float>(); val c = ArrayList<Int>()
        override val count get() = c.size / 3
        override fun begin() { x.clear(); y.clear(); c.clear() }
        override fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
            x += x0; x += x1; x += x2; y += y0; y += y1; y += y2; c += c0; c += c1; c += c2
        }
        override fun flush(scope: DrawScope) = Unit

        /** Colour of the last-drawn (front-most) triangle covering screen point (px, py). */
        fun colourAt(px: Float, py: Float): Int {
            for (i in count - 1 downTo 0) {
                val v = i * 3
                if (inside(px, py, x[v], y[v], x[v + 1], y[v + 1], x[v + 2], y[v + 2])) return c[v]
            }
            error("nothing drawn at ($px, $py)")
        }

        private fun inside(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
            val d0 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)
            val d1 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
            val d2 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)
            return (d0 <= 0f && d1 <= 0f && d2 <= 0f) || (d0 >= 0f && d1 >= 0f && d2 >= 0f)
        }
    }

    /** Counts triangles without storing them, so it allocates nothing. */
    private class CountingSink : TriangleSink {
        override var count = 0
        override fun begin() { count = 0 }
        override fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) { count++ }
        override fun flush(scope: DrawScope) = Unit
    }

    private fun draw(vararg parts: Part, palette: IntArray = grey, leftX: Float = 0f, leftY: Float = 1f): RecordingSink {
        val sink = RecordingSink()
        painter.begin(camera)
        for (p in parts) painter.add(p, jx, jy, jz, leftX, leftY, palette)
        painter.emit(sink)
        return sink
    }

    private fun screen(x: Float, y: Float, z: Float): FloatArray = FloatArray(3).also { camera.project(x, y, z, it) }

    /** Blue rather than red, whatever the rim light adds to both. */
    private fun isBlue(argb: Int) = (argb and 0xFF) > ((argb shr 16) and 0xFF)

    private fun luminance(argb: Int): Int = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    // Settles the culling sign: in the bone frame along = +x, side = +y, up = +z, so this triangle is
    // counter-clockwise seen from -y, i.e. from the camera.
    @Test fun a_triangle_facing_the_camera_is_kept_and_its_reverse_culled() {
        fun tri(vararg order: Short) = Mesh(
            t = floatArrayOf(0f, 1f, 1f), e = FloatArray(3), a = FloatArray(3), b = floatArrayOf(0f, 0f, 1f),
            nt = FloatArray(3), na = floatArrayOf(-1f, -1f, -1f), nb = FloatArray(3),
            slot = ByteArray(3), tri = shortArrayOf(*order),
        )
        bone(0f, 0f, 0f, 1f, 0f, 0f)
        assertEquals(1, draw(Part(0, 1, tri(0, 1, 2))).count)
        assertEquals(0, draw(Part(0, 1, tri(0, 2, 1))).count)
    }

    @Test fun a_box_shows_at_most_three_faces() {
        val placements = arrayOf(
            floatArrayOf(-0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f),   // along x, straight ahead
            floatArrayOf(-6f, 3f, 0.5f, -5f, 3f, 0.5f),      // off to the left and further away
            floatArrayOf(4f, -2f, 0f, 4f, -2f, 1f),          // standing up, to the right
            floatArrayOf(1f, 1f, 1f, 1.6f, 1.5f, 1.6f),      // tilted every way
        )
        for (p in placements) {
            bone(p[0], p[1], p[2], p[3], p[4], p[5])
            val sink = draw(Part(0, 1, unitBox), leftX = 0.6f, leftY = 0.8f)
            assertEquals(12, painter.added)
            assertTrue("${sink.count} triangles for ${p.toList()}", sink.count in 2..6)
        }
    }

    @Test fun far_triangles_are_emitted_first() {
        val sink = RecordingSink()
        painter.begin(camera)
        // Near box added first, so only the sort can put the far one first.
        bone(-0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f)
        painter.add(Part(0, 1, unitBox), jx, jy, jz, 0f, 1f, red)
        bone(-0.5f, 2f, 0.5f, 0.5f, 2f, 0.5f)
        painter.add(Part(0, 1, unitBox), jx, jy, jz, 0f, 1f, blue)
        val sent = painter.emit(sink)
        assertEquals(sink.count, sent)
        var lastFar = -1
        var firstNear = Int.MAX_VALUE
        for (i in 0 until sink.count) {
            val c = sink.c[i * 3]
            if (isBlue(c)) lastFar = i else firstNear = min(firstNear, i)
        }
        assertTrue("both boxes drawn", lastFar >= 0 && firstNear < Int.MAX_VALUE)
        assertTrue("far box ends at $lastFar, near box starts at $firstNear", lastFar < firstNear)
    }

    @Test fun a_part_is_drawn_whole_in_the_order_of_its_middle() {
        // A long blue rod running from y = -2 to 2 (its middle at y = 0) and a red box at y = -1. The
        // rod's near end is nearer than the box, but the box's middle is nearer than the rod's.
        val rod = MeshBuilder.taperedBox(0f, 1f, 0.3f, 0.3f, 0.3f, 0.3f, 0)
        val js = floatArrayOf(0f, 0f, -0.5f, 0.5f)
        val ys = floatArrayOf(-2f, 2f, -1f, -1f)
        val zs = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        painter.begin(camera)
        painter.add(Part(2, 3, unitBox), js, ys, zs, 0f, 1f, red)   // added first: order is not add order
        painter.add(Part(0, 1, rod), js, ys, zs, 1f, 0f, blue)
        val sink = RecordingSink().also { painter.emit(it) }
        var lastBlue = -1; var firstRed = Int.MAX_VALUE
        for (i in 0 until sink.count) {
            if (isBlue(sink.c[i * 3])) lastBlue = i else firstRed = min(firstRed, i)
        }
        assertTrue("both drawn", lastBlue >= 0 && firstRed < Int.MAX_VALUE)
        assertTrue("rod ends at $lastBlue, box starts at $firstRed", lastBlue < firstRed)
    }

    @Test fun a_part_brought_nearer_is_drawn_after_a_nearer_part() {
        // The rod's middle is a metre farther than the box's; 1.5 m nearer puts it in front.
        val rod = MeshBuilder.taperedBox(0f, 1f, 0.3f, 0.3f, 0.3f, 0.3f, 0)
        val js = floatArrayOf(0f, 0f, -0.5f, 0.5f)
        val ys = floatArrayOf(-2f, 2f, -1f, -1f)
        val zs = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        painter.begin(camera)
        painter.add(Part(0, 1, rod, nearer = 1.5f), js, ys, zs, 1f, 0f, blue)
        painter.add(Part(2, 3, unitBox), js, ys, zs, 0f, 1f, red)
        val sink = RecordingSink().also { painter.emit(it) }
        var lastRed = -1; var firstBlue = Int.MAX_VALUE
        for (i in 0 until sink.count) {
            if (isBlue(sink.c[i * 3])) firstBlue = min(firstBlue, i) else lastRed = i
        }
        assertTrue("both drawn", lastRed >= 0 && firstBlue < Int.MAX_VALUE)
        assertTrue("box ends at $lastRed, rod starts at $firstBlue", lastRed < firstBlue)
    }

    @Test fun light_from_above_makes_the_top_brighter_than_the_side() {
        // along = +x, side = +y, up = +z: the box spans y and z in [-0.5, 0.5] around the bone.
        bone(-0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f)
        val sink = draw(Part(0, 1, unitBox))
        val top = screen(0.1f, 0.1f, 1f)        // on the top face (z = 1)
        val side = screen(0.1f, -0.5f, 0.6f)    // on the near side face (y = -0.5)
        val topColour = sink.colourAt(top[0], top[1])
        val sideColour = sink.colourAt(side[0], side[1])
        assertTrue(
            "top ${Integer.toHexString(topColour)} vs side ${Integer.toHexString(sideColour)}",
            luminance(topColour) > luminance(sideColour),
        )
        // Mid-grey stays grey and opaque.
        assertEquals(0xFF, topColour ushr 24)
        assertEquals((topColour shr 16) and 0xFF, topColour and 0xFF)
    }

    @Test fun bone_frame_is_finite_when_the_bone_points_along_left() {
        bone(0f, 0f, 0.5f, 0f, 1f, 0.5f)   // along = +y = left
        val sink = draw(Part(0, 1, unitBox), leftX = 0f, leftY = 1f)
        assertTrue(sink.count > 0)
        for (i in 0 until sink.x.size) assertTrue("vertex $i", sink.x[i].isFinite() && sink.y[i].isFinite())
        // A zero-length bone with no left at all must not poison the frame either.
        bone(0f, 0f, 0.5f, 0f, 0f, 0.5f)
        val point = draw(Part(0, 1, MeshBuilder.ellipsoid(0.2f, 0.2f, 0.2f, 6, 4, 0)), leftX = 0f, leftY = 0f)
        assertTrue(point.count > 0)
        for (i in 0 until point.x.size) assertTrue("vertex $i", point.x[i].isFinite() && point.y[i].isFinite())
    }

    @Test fun emit_does_not_allocate() {
        val ball = MeshBuilder.ellipsoid(0.3f, 0.25f, 0.2f, 10, 6, 0)   // 100 triangles
        val figure = Array(5) { Part(0, it + 1, ball) }
        val fx = floatArrayOf(0f, 0.5f, -0.5f, 0f, 0f, 0.3f)
        val fy = floatArrayOf(0f, 0f, 0f, 0.5f, -0.5f, 0.3f)
        val fz = floatArrayOf(1f, 1f, 1f, 1f, 1f, 2f)
        val sink = CountingSink()
        val round: () -> Unit = {
            sink.begin()
            painter.begin(camera)
            for (p in figure) painter.add(p, fx, fy, fz, 0.6f, 0.8f, grey)
            painter.emit(sink)
        }
        repeat(200) { round() }
        assertEquals(500, painter.added)
        assertTrue(sink.count > 0)

        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") // threadId() is not in the android.jar compile classpath
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        repeat(2000) { round() }
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 2000 rounds", bytes < 16 * 1024L)
    }

    // --- MeshBuilder: Tasks 7 and 8 build against the winding convention, so check it holds. ---

    /**
     * Every triangle has area, its face normal (from the winding) agrees with its vertex normals, and
     * every vertex normal points away from the shape's axis segment [lo, hi] (bone length 1).
     */
    private fun assertWoundOutward(mesh: Mesh, lo: Float, hi: Float) {
        fun px(v: Int) = mesh.t[v] + mesh.e[v]
        for (k in 0 until mesh.triangleCount) {
            val i0 = mesh.tri[3 * k].toInt(); val i1 = mesh.tri[3 * k + 1].toInt(); val i2 = mesh.tri[3 * k + 2].toInt()
            val ux = px(i1) - px(i0); val uy = mesh.a[i1] - mesh.a[i0]; val uz = mesh.b[i1] - mesh.b[i0]
            val wx = px(i2) - px(i0); val wy = mesh.a[i2] - mesh.a[i0]; val wz = mesh.b[i2] - mesh.b[i0]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            assertTrue("triangle $k has no area", sqrt(cx * cx + cy * cy + cz * cz) > 1e-7f)
            val nx = mesh.nt[i0] + mesh.nt[i1] + mesh.nt[i2]
            val ny = mesh.na[i0] + mesh.na[i1] + mesh.na[i2]
            val nz = mesh.nb[i0] + mesh.nb[i1] + mesh.nb[i2]
            assertTrue("triangle $k wound inward", cx * nx + cy * ny + cz * nz > 0f)
        }
        for (v in 0 until mesh.vertexCount) {
            val axis = max(lo, min(hi, px(v)))
            val out = (px(v) - axis) * mesh.nt[v] + mesh.a[v] * mesh.na[v] + mesh.b[v] * mesh.nb[v]
            assertTrue("vertex $v normal points inward", out > 0f)
            val len = sqrt(mesh.nt[v] * mesh.nt[v] + mesh.na[v] * mesh.na[v] + mesh.nb[v] * mesh.nb[v])
            assertEquals("vertex $v normal length", 1f, len, 1e-4f)
        }
    }

    @Test fun primitives_wind_counter_clockwise_from_outside() {
        assertWoundOutward(MeshBuilder.taperedBox(0.1f, 0.9f, 0.4f, 0.3f, 0.6f, 0.2f, 0), 0.5f, 0.5f)
        assertWoundOutward(MeshBuilder.capsule(0.12f, 0.08f, 8, 3, 0), 0f, 1f)
        assertWoundOutward(MeshBuilder.capsule(0.1f, 0.1f, 6, 1, 0, floatArrayOf(0.2f, 0.4f, 0.7f, 1f), 1), 0f, 1f)
        assertWoundOutward(MeshBuilder.ellipsoid(0.2f, 0.12f, 0.15f, 8, 5, 0), 0f, 0f)
        assertEquals(12, unitBox.triangleCount)
    }

    @Test fun join_keeps_every_part() {
        val box = MeshBuilder.taperedBox(0f, 1f, 1f, 1f, 1f, 1f, 2)
        val ball = MeshBuilder.ellipsoid(0.2f, 0.2f, 0.2f, 6, 4, 3)
        val both = MeshBuilder.join(box, ball)
        assertEquals(box.vertexCount + ball.vertexCount, both.vertexCount)
        assertEquals(box.triangleCount + ball.triangleCount, both.triangleCount)
        for (k in box.triangleCount until both.triangleCount) {
            for (j in 0..2) assertEquals(3, both.slot[both.tri[3 * k + j].toInt()].toInt())
        }
    }

    @Test fun capsule_bands_are_crisp_and_take_the_band_slot() {
        val m = MeshBuilder.capsule(0.1f, 0.1f, 8, 2, 0, floatArrayOf(0.4f, 0.6f), 5)
        var banded = 0
        for (k in 0 until m.triangleCount) {
            val s0 = m.slot[m.tri[3 * k].toInt()]
            for (j in 1..2) assertEquals("triangle $k mixes slots", s0, m.slot[m.tri[3 * k + j].toInt()])
            if (s0.toInt() == 5) {
                banded++
                for (j in 0..2) {
                    val v = m.tri[3 * k + j].toInt()
                    assertTrue("band vertex at t=${m.t[v]}", m.t[v] in 0.4f..0.6f && abs(m.e[v]) < 1e-6f)
                }
            }
        }
        assertEquals(16, banded)   // one ring segment of 8 quads
    }

    @Test fun placed_scales_then_moves_and_keeps_normals_true() {
        // A sphere squashed by (ka, kb) is the ellipsoid with those radii, normals included.
        val r = 0.2f
        val squashed = MeshBuilder.placed(MeshBuilder.ellipsoid(r, r, r, 8, 5, 0), ka = 2f, kb = 0.5f)
        val ellipsoid = MeshBuilder.ellipsoid(r, 2f * r, 0.5f * r, 8, 5, 0)
        for (v in 0 until ellipsoid.vertexCount) {
            assertEquals(ellipsoid.a[v], squashed.a[v], 1e-6f); assertEquals(ellipsoid.b[v], squashed.b[v], 1e-6f)
            assertEquals(ellipsoid.nt[v], squashed.nt[v], 1e-5f)
            assertEquals(ellipsoid.na[v], squashed.na[v], 1e-5f)
            assertEquals(ellipsoid.nb[v], squashed.nb[v], 1e-5f)
        }
        assertTrue(ellipsoid.tri.contentEquals(squashed.tri))
        // Then moved: positions shift, normals and winding do not change.
        val box = MeshBuilder.taperedBox(0.1f, 0.9f, 0.4f, 0.3f, 0.6f, 0.2f, 4)
        val moved = MeshBuilder.placed(box, t = 1f, e = 0.05f, a = -0.1f, b = 0.2f)
        for (v in 0 until box.vertexCount) {
            assertEquals(box.t[v] + 1f, moved.t[v], 1e-6f); assertEquals(box.e[v] + 0.05f, moved.e[v], 1e-6f)
            assertEquals(box.a[v] - 0.1f, moved.a[v], 1e-6f); assertEquals(box.b[v] + 0.2f, moved.b[v], 1e-6f)
            assertEquals(box.nt[v], moved.nt[v], 1e-6f); assertEquals(box.na[v], moved.na[v], 1e-6f); assertEquals(box.nb[v], moved.nb[v], 1e-6f)
            assertEquals(4, moved.slot[v].toInt())
        }
        assertWoundOutward(MeshBuilder.placed(MeshBuilder.capsule(0.1f, 0.12f, 8, 2, 0), ka = 1.6f, kb = 1.2f), 0f, 1f)
    }
}
