package com.personal.hockey.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.personal.hockey.teams.Crest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original crest marks built from a few path segments. Shapes are authored in a unit box
 * (u, v in -0.5..0.5, v down) and drawn into a size × size box centred on (cx, cy).
 * Reuses one Path, so it does not allocate; call from the render thread only.
 */
object Crests {
    private val path = Path()
    private var ox = 0f
    private var oy = 0f
    private var k = 1f

    private const val LEAF_POINTS = 60
    /** Five-lobed outline, one lobe pointing up, as unit-box (u, v) pairs. */
    private val leaf = FloatArray(LEAF_POINTS * 2).also { pts ->
        for (i in 0 until LEAF_POINTS) {
            val a = (i.toFloat() / LEAF_POINTS) * 2f * Math.PI.toFloat()
            val r = 0.24f + 0.2f * abs(cos(2.5f * a))
            pts[2 * i] = r * sin(a)
            pts[2 * i + 1] = -r * cos(a) - 0.04f
        }
    }

    fun draw(scope: DrawScope, crest: Crest, cx: Float, cy: Float, size: Float, primary: Color, secondary: Color) {
        ox = cx; oy = cy; k = size
        when (crest) {
            Crest.PINE -> {
                path.reset()
                tri(0f, -0.5f, -0.22f, -0.15f, 0.22f, -0.15f)
                tri(0f, -0.32f, -0.32f, 0.08f, 0.32f, 0.08f)
                tri(0f, -0.12f, -0.42f, 0.34f, 0.42f, 0.34f)
                scope.drawPath(path, primary)
                path.reset()
                m(-0.07f, 0.32f); l(0.07f, 0.32f); l(0.07f, 0.5f); l(-0.07f, 0.5f); path.close()
                scope.drawPath(path, secondary)
            }
            Crest.PEAK -> {
                path.reset()
                tri(-0.18f, -0.25f, -0.5f, 0.4f, 0.14f, 0.4f)
                scope.drawPath(path, secondary)
                path.reset()
                tri(0.18f, -0.45f, -0.2f, 0.4f, 0.5f, 0.4f)
                scope.drawPath(path, primary)
                path.reset()
                m(0.18f, -0.45f); l(0.26f, -0.24f); l(0.2f, -0.28f); l(0.15f, -0.22f); l(0.085f, -0.24f); path.close()
                scope.drawPath(path, Color.White)
            }
            Crest.DROP -> {
                path.reset()
                m(0f, -0.5f)
                c(0.1f, -0.25f, 0.33f, -0.05f, 0.33f, 0.15f)
                c(0.33f, 0.33f, 0.18f, 0.48f, 0f, 0.48f)
                c(-0.18f, 0.48f, -0.33f, 0.33f, -0.33f, 0.15f)
                c(-0.33f, -0.05f, -0.1f, -0.25f, 0f, -0.5f)
                path.close()
                scope.drawPath(path, primary)
                scope.drawCircle(secondary, 0.08f * size, Offset(cx - 0.13f * size, cy + 0.16f * size))
            }
            Crest.LEAF -> {
                path.reset()
                m(leaf[0], leaf[1])
                for (i in 1 until LEAF_POINTS) l(leaf[2 * i], leaf[2 * i + 1])
                path.close()
                m(-0.03f, 0.2f); l(0.03f, 0.2f); l(0.04f, 0.5f); l(-0.04f, 0.5f); path.close()
                scope.drawPath(path, primary)
            }
        }
    }

    private fun m(u: Float, v: Float) = path.moveTo(ox + u * k, oy + v * k)
    private fun l(u: Float, v: Float) = path.lineTo(ox + u * k, oy + v * k)
    private fun c(u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float) =
        path.cubicTo(ox + u1 * k, oy + v1 * k, ox + u2 * k, oy + v2 * k, ox + u3 * k, oy + v3 * k)

    private fun tri(u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float) {
        m(u1, v1); l(u2, v2); l(u3, v3); path.close()
    }
}
