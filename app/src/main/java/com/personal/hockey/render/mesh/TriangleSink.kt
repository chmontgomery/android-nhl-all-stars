package com.personal.hockey.render.mesh

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas

const val MAX_SINK_TRIANGLES = 1024

/** Collects screen-space triangles with per-vertex ARGB colours and draws them in one go. */
interface TriangleSink {
    val count: Int
    fun begin()
    /** Screen pixels, ARGB colours per vertex. Ignored once full. */
    fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int)
    fun flush(scope: DrawScope)
}

/** Gouraud-shaded triangles through one `drawVertices` call. */
class VerticesSink(private val capacity: Int = MAX_SINK_TRIANGLES) : TriangleSink {
    private val verts = FloatArray(capacity * 6)
    // Twice the vertex count: some Android versions bounds-check colours against the float count.
    private val colors = IntArray(2 * capacity * 3)
    private val paint = Paint()
    override var count = 0
        private set

    override fun begin() { count = 0 }

    override fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
        if (count >= capacity) return
        val v = count * 6
        verts[v] = x0; verts[v + 1] = y0; verts[v + 2] = x1; verts[v + 3] = y1; verts[v + 4] = x2; verts[v + 5] = y2
        val c = count * 3
        colors[c] = c0; colors[c + 1] = c1; colors[c + 2] = c2
        count++
    }

    override fun flush(scope: DrawScope) {
        if (count == 0) return
        scope.drawContext.canvas.nativeCanvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, count * 6, verts, 0, null, 0, colors, 0, null, 0, 0, paint,
        )
    }
}

/** Flat-shaded fallback: each triangle is a path filled with the average of its corner colours. */
class PathSink(private val capacity: Int = MAX_SINK_TRIANGLES) : TriangleSink {
    private val xs = FloatArray(capacity * 3)
    private val ys = FloatArray(capacity * 3)
    private val avg = IntArray(capacity)
    private val path = Path()
    private val paint = Paint().apply { isAntiAlias = true }
    override var count = 0
        private set

    override fun begin() { count = 0 }

    override fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
        if (count >= capacity) return
        val v = count * 3
        xs[v] = x0; xs[v + 1] = x1; xs[v + 2] = x2
        ys[v] = y0; ys[v + 1] = y1; ys[v + 2] = y2
        avg[count] = (channel(c0, c1, c2, 24) shl 24) or (channel(c0, c1, c2, 16) shl 16) or
            (channel(c0, c1, c2, 8) shl 8) or channel(c0, c1, c2, 0)
        count++
    }

    private fun channel(a: Int, b: Int, c: Int, shift: Int): Int =
        (((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) + ((c ushr shift) and 0xFF)) / 3

    override fun flush(scope: DrawScope) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        for (i in 0 until count) {
            val v = i * 3
            path.rewind()
            path.moveTo(xs[v], ys[v]); path.lineTo(xs[v + 1], ys[v + 1]); path.lineTo(xs[v + 2], ys[v + 2]); path.close()
            paint.color = avg[i]
            canvas.drawPath(path.asAndroidPath(), paint)
        }
    }
}

object TriangleSinks {
    fun default(): TriangleSink = VerticesSink()
}
