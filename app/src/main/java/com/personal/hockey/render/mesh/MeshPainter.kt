package com.personal.hockey.render.mesh

import com.personal.hockey.render.Camera
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Poses [Part]s on their bones, lights and projects them, and sends the visible triangles far to near
 * to a [TriangleSink]. One frame: [begin], [add] each part, [emit]. Every buffer is allocated here, so
 * none of that allocates.
 *
 * Bone frames and winding are as [Mesh] describes. Back faces are culled as parts are added, so
 * [maxTriangles] bounds the visible triangles kept per frame; further ones are dropped.
 *
 * **Order.** Parts go far to near by the mean depth of their vertices less [Part.nearer], and each
 * part's triangles far to near by their own mean depth. A closed convex part with its back faces culled never overlaps
 * itself, so this keeps overlapping parts whole (a hem over the pants, a thigh over the knee) where a
 * single sort of every triangle lets long triangles of one part slip through another. Joined,
 * non-convex parts still come out right inside themselves.
 */
class MeshPainter(private val maxTriangles: Int = MAX_SINK_TRIANGLES) {
    /** Triangles added since [begin] before culling, including any dropped past [maxTriangles]. */
    var added = 0
        private set

    private lateinit var camera: Camera
    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0f
    /** Metres of depth to this painter's depth units (1 / scale). */
    private var perMetre = 0f

    // The current part's vertices: screen position, depth (1/scale) and lit colour.
    private val maxVertices = maxTriangles * 3
    private val vx = FloatArray(maxVertices)
    private val vy = FloatArray(maxVertices)
    private val vd = FloatArray(maxVertices)
    private val vc = IntArray(maxVertices)

    // Kept triangles, three corners each, and their mean depth.
    private var count = 0
    private val tx = FloatArray(maxTriangles * 3)
    private val ty = FloatArray(maxTriangles * 3)
    private val tc = IntArray(maxTriangles * 3)
    private val td = FloatArray(maxTriangles)

    // Parts that kept a triangle: their first kept triangle and their depth. Past MAX_PARTS, triangles
    // join the last part.
    private var parts = 0
    private val partStart = IntArray(MAX_PARTS + 1)
    private val partDepth = FloatArray(MAX_PARTS)
    private val partOrder = IntArray(MAX_PARTS)
    private val order = IntArray(maxTriangles)

    private val projected = FloatArray(3)

    // The bone frame: along (ax, ay, az), side (sx, sy, sz), up (ux, uy, uz), and the bone's length.
    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var sx = 0f; private var sy = 0f; private var sz = 0f
    private var ux = 0f; private var uy = 0f; private var uz = 0f
    private var boneLength = 0f

    fun begin(camera: Camera) {
        this.camera = camera
        eyeX = camera.eyeX; eyeY = camera.eyeY; eyeZ = camera.eyeZ
        perMetre = 1f / camera.focalLength
        count = 0
        added = 0
        parts = 0
    }

    /** jx/jy/jz: joints in world metres; (leftX, leftY): the figure's left on the ice. */
    fun add(part: Part, jx: FloatArray, jy: FloatArray, jz: FloatArray, leftX: Float, leftY: Float, palette: IntArray) {
        val mesh = part.mesh
        val triangles = mesh.triangleCount
        added += triangles
        val n = mesh.vertexCount
        if (n > maxVertices || count >= maxTriangles) return
        val ox = jx[part.from]; val oy = jy[part.from]; val oz = jz[part.from]
        frame(jx[part.to] - ox, jy[part.to] - oy, jz[part.to] - oz, leftX, leftY)

        var depthSum = 0f
        for (v in 0 until n) {
            val along = mesh.t[v] * boneLength + mesh.e[v]
            val a = mesh.a[v]; val b = mesh.b[v]
            val px = ox + along * ax + a * sx + b * ux
            val py = oy + along * ay + a * sy + b * uy
            val pz = oz + along * az + a * sz + b * uz
            camera.project(px, py, pz, projected)
            vx[v] = projected[0]; vy[v] = projected[1]; vd[v] = 1f / projected[2]
            depthSum += vd[v]

            val nt = mesh.nt[v]; val na = mesh.na[v]; val nb = mesh.nb[v]
            val nx = nt * ax + na * sx + nb * ux
            val ny = nt * ay + na * sy + nb * uy
            val nz = nt * az + na * sz + nb * uz
            var wx = eyeX - px; var wy = eyeY - py; var wz = eyeZ - pz
            val wl = sqrt(wx * wx + wy * wy + wz * wz)
            if (wl > 0f) { wx /= wl; wy /= wl; wz /= wl }
            val lambert = nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z
            val rim = 1f - abs(nx * wx + ny * wy + nz * wz)
            val light = AMBIENT + DIFFUSE * (if (lambert > 0f) lambert else 0f)
            vc[v] = shade(palette[mesh.slot[v].toInt()], light, RIM * rim * rim * 255f)
        }

        val first = count
        val tri = mesh.tri
        for (k in 0 until triangles) {
            if (count >= maxTriangles) break
            val i0 = tri[3 * k].toInt(); val i1 = tri[3 * k + 1].toInt(); val i2 = tri[3 * k + 2].toInt()
            val area = (vx[i1] - vx[i0]) * (vy[i2] - vy[i0]) - (vx[i2] - vx[i0]) * (vy[i1] - vy[i0])
            if (area >= 0f) continue   // back face or edge-on; see Mesh
            val c = count * 3
            tx[c] = vx[i0]; ty[c] = vy[i0]; tc[c] = vc[i0]
            tx[c + 1] = vx[i1]; ty[c + 1] = vy[i1]; tc[c + 1] = vc[i1]
            tx[c + 2] = vx[i2]; ty[c + 2] = vy[i2]; tc[c + 2] = vc[i2]
            td[count] = (vd[i0] + vd[i1] + vd[i2]) / 3f
            count++
        }
        if (count > first && parts < MAX_PARTS) {
            partStart[parts] = first
            partDepth[parts] = depthSum / n - part.nearer * perMetre
            parts++
        }
    }

    /**
     * Sends the kept (front-facing) triangles to [sink] far to near, as the class describes; ties keep
     * the order they were added in. The caller begins and flushes the sink. Returns triangles sent.
     */
    fun emit(sink: TriangleSink): Int {
        if (count == 0) return 0
        partStart[parts] = count
        for (i in 0 until parts) {
            var j = i
            while (j > 0 && partDepth[partOrder[j - 1]] < partDepth[i]) { partOrder[j] = partOrder[j - 1]; j-- }
            partOrder[j] = i
        }
        for (k in 0 until parts) {
            val p = partOrder[k]
            val start = partStart[p]; val end = partStart[p + 1]
            for (i in start until end) {
                var j = i
                while (j > start && td[order[j - 1]] < td[i]) { order[j] = order[j - 1]; j-- }
                order[j] = i
            }
            for (j in start until end) {
                val c = order[j] * 3
                sink.add(tx[c], ty[c], tc[c], tx[c + 1], ty[c + 1], tc[c + 1], tx[c + 2], ty[c + 2], tc[c + 2])
            }
        }
        return count
    }

    /** Sets the bone frame from the bone vector (dx, dy, dz) and the figure's left; see [Mesh]. */
    private fun frame(dx: Float, dy: Float, dz: Float, leftX: Float, leftY: Float) {
        boneLength = sqrt(dx * dx + dy * dy + dz * dz)
        if (boneLength > EPS) {
            ax = dx / boneLength; ay = dy / boneLength; az = dz / boneLength
        } else {
            ax = 0f; ay = 0f; az = 1f; boneLength = 0f
        }
        val dot = leftX * ax + leftY * ay
        sx = leftX - dot * ax; sy = leftY - dot * ay; sz = -dot * az
        var sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < EPS) {
            // The bone points along the left: use up × along.
            sx = -ay; sy = ax; sz = 0f
            sl = sqrt(sx * sx + sy * sy)
            if (sl < EPS) {
                // ...and is vertical, with no usable left: any horizontal side will do.
                sx = 0f; sy = 1f; sl = 1f
            }
        }
        sx /= sl; sy /= sl; sz /= sl
        ux = ay * sz - az * sy
        uy = az * sx - ax * sz
        uz = ax * sy - ay * sx
    }

    /**
     * [argb]'s colour channels times [light], plus [glow] (0..255) toward white, each clamped to 255;
     * alpha kept. The rim is added rather than multiplied so it lifts the edges of dark kits too.
     */
    private fun shade(argb: Int, light: Float, glow: Float): Int {
        val r = min(255, (((argb ushr 16) and 0xFF) * light + glow).toInt())
        val g = min(255, (((argb ushr 8) and 0xFF) * light + glow).toInt())
        val b = min(255, ((argb and 0xFF) * light + glow).toInt())
        return (argb and ALPHA) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val MAX_PARTS = 64
        const val EPS = 1e-5f
        const val ALPHA = 0xFF000000.toInt()
        const val AMBIENT = 0.5f
        const val DIFFUSE = 0.7f
        /** Added toward white at a silhouette, as a fraction of full brightness. */
        const val RIM = 0.22f
        // normalise(0.25, -0.55, 0.8): above, and in front of the camera (which looks along +y).
        val LIGHT_LEN = sqrt(0.25f * 0.25f + 0.55f * 0.55f + 0.8f * 0.8f)
        val LIGHT_X = 0.25f / LIGHT_LEN
        val LIGHT_Y = -0.55f / LIGHT_LEN
        val LIGHT_Z = 0.8f / LIGHT_LEN
    }
}
