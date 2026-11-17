package com.personal.hockey.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Player
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/** Jersey numbers are printed on the back: readable on a phone when the back faces the camera, hidden otherwise. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberViewTest {
    private val w = 1600
    private val h = 720
    private val p = FloatArray(3)

    private fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun render(r: CanvasRenderer, sim: HockeySim): PixelMap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            r.draw(this, sim, 0f)
        }
        return bmp.toPixelMap()
    }

    /** HOME is MIN in [kit] with its centre wearing 88, or no number at all; HOME goalie [goalie]. */
    private fun renderer(white: Boolean, number: Boolean, goalie: Int): CanvasRenderer {
        val min = Teams.byId("MIN")!!
        val roster = Teams.standardSkaters().mapNotNull {
            if (it.position != Position.C) it else if (number) Player(88, Position.C) else null
        }
        val home = min.copy(skaters = roster, goalieNumber = goalie)
        val col = Teams.byId("COL")!!
        val setup = MatchSetup(home, col, if (white) home.whiteKit else home.darkKit, if (white) col.darkKit else col.whiteKit, Level.EASY)
        return CanvasRenderer(setup).also { it.camera.setViewport(w.toFloat(), h.toFloat()) }
    }

    private fun quietSim(): HockeySim {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) { s.x = -18f + 4f * s.id; s.y = 11.5f; s.vx = 0f; s.vy = 0f; s.facing = 0f }
        sim.puck.x = 0f; sim.puck.y = -30f
        sim.controlledId = -1
        return sim
    }

    /**
     * Pixels around the figure at (x, y) that the number changes by a clear step in brightness, and
     * the height in metres of the box they span.
     */
    private fun measure(with: PixelMap, without: PixelMap, r: CanvasRenderer, x: Float, y: Float): Pair<Int, Float> {
        r.camera.project(x, y, 2.1f, p)
        val top = p[1].toInt(); val s = p[2]
        r.camera.project(x, y, 0.5f, p)
        val bottom = p[1].toInt(); val cx = p[0]
        var n = 0; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (ix in (cx - 0.8f * s).toInt()..(cx + 0.8f * s).toInt()) for (iy in top..bottom) {
            if (ix !in 0 until w || iy !in 0 until h) continue
            if (abs(lum(with[ix, iy]) - lum(without[ix, iy])) < 0.35f) continue
            n++; minY = minOf(minY, iy); maxY = maxOf(maxY, iy)
        }
        return n to (if (n == 0) 0f else (maxY - minY + 1) / s)
    }

    /**
     * The number is printed on the back. With the back to the camera (facing 45..135 deg, away up the
     * screen) it is large and contrasting; facing the camera, or side-on, it is not drawn at all.
     */
    @Test fun a_skater_number_shows_on_his_back_and_not_from_the_front() {
        for (white in listOf(false, true)) for (k in 0 until 16) {
            val deg = k * 22.5f   // the 16 facings SkaterMeshTest draws
            val sim = quietSim()
            val c = sim.skaters[0]
            c.x = 0f; c.y = -4f; c.facing = (deg * PI / 180.0).toFloat()
            val withR = renderer(white, number = true, goalie = 30); withR.camera.snapTo(0f)
            val withoutR = renderer(white, number = false, goalie = 30); withoutR.camera.snapTo(0f)
            val (n, height) = measure(render(withR, sim), render(withoutR, sim), withR, c.x, c.y)
            val kit = if (white) "white" else "dark"
            val msg = "$kit kit facing $deg: $n number pixels, ${"%.2f".format(height)} m tall"
            when {
                deg in 45f..135f -> assertTrue(msg, n >= 120 && height >= 0.18f)
                deg == 0f || deg >= 180f -> assertTrue(msg, n == 0)
            }
        }
    }

    /** A goalie faces out of his net, side-on to the camera, so his back and its number do not show. */
    @Test fun a_goalie_number_does_not_show_side_on() {
        for (white in listOf(false, true)) {
            val sim = quietSim()
            val g = sim.goalies[0]
            val withR = renderer(white, number = true, goalie = 32); withR.camera.snapTo(-100f)
            val withoutR = renderer(white, number = true, goalie = -1); withoutR.camera.snapTo(-100f)
            val (n, height) = measure(render(withR, sim), render(withoutR, sim), withR, g.x, g.y)
            assertTrue("white $white: $n number pixels, ${"%.2f".format(height)} m tall", n == 0)
        }
    }
}
