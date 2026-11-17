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
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** What a person sees at a net: a puck inside it, and the goal light. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalViewTest {
    private val w = 1600
    private val h = 720
    private val p = FloatArray(3)

    private fun renderer() = CanvasRenderer(MatchSetup.of(Teams.all[0], Teams.all[1], Level.EASY)).also {
        it.camera.setViewport(w.toFloat(), h.toFloat())
    }

    private fun render(r: CanvasRenderer, sim: HockeySim): PixelMap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            r.draw(this, sim, 1f)
        }
        return bmp.toPixelMap()
    }

    /** Live play with every skater parked along the near boards at centre ice, out of the picture. */
    private fun quietSim(): HockeySim {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) { s.x = -4f + s.id; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.controlledId = -1
        return sim
    }

    /** Puck-black, allowing for a faint mesh strand across it. */
    private fun blackish(c: Color) = c.red < 0.4f && c.green < 0.4f && c.blue < 0.4f

    /** Fraction of pixels within [radiusM] of the top of a puck lying at (x, y) that are puck-black. */
    private fun puckCoverage(r: CanvasRenderer, px: PixelMap, x: Float, y: Float, radiusM: Float): Float {
        r.camera.project(x, y, 0.03f, p)
        val cx = p[0]; val cy = p[1]; val rad = radiusM * p[2]
        var n = 0; var dark = 0
        for (ix in (cx - rad).toInt()..(cx + rad).toInt()) for (iy in (cy - rad).toInt()..(cy + rad).toInt()) {
            val dx = ix - cx; val dy = (iy - cy) * 2f   // the puck is an ellipse about twice as wide as tall
            if (dx * dx + dy * dy > rad * rad) continue
            n++
            if (blackish(px[ix, iy])) dark++
        }
        return dark.toFloat() / n
    }

    @Test fun a_puck_anywhere_inside_either_net_is_seen_through_the_mesh() {
        for (end in floatArrayOf(1f, -1f)) {
            for (y in floatArrayOf(-0.8f, -0.3f, 0.3f, 0.8f)) for (depth in floatArrayOf(0.45f, 0.7f, 0.95f)) {
                val sim = quietSim()
                sim.phase = Phase.GOAL
                val x = end * (Rink.GOAL_X + depth)
                sim.puck.x = x; sim.puck.y = y
                val r = renderer()
                r.camera.snapTo(end * 100f)
                val cover = puckCoverage(r, render(r, sim), x, y, 0.1f)
                // Near the mouth the near post may cross it, as it would really; deeper in, where a
                // scored puck comes to rest against the back, nothing does.
                val need = if (depth >= 0.9f) 0.6f else 0.3f
                assertTrue("puck at $x,$y: only ${(cover * 100).toInt()}% of it shows", cover >= need)
            }
        }
    }

    /** Pixels of the goal light's red: brighter and purer than the posts' red. */
    private fun lampRed(c: Color) = c.red > 0.93f && c.green < 0.35f && c.blue < 0.35f

    /** Lamp-red pixels within 3 m (projected) of the net on the goal line at x = [goalX]. */
    private fun redNear(r: CanvasRenderer, px: PixelMap, goalX: Float): Int {
        r.camera.project(goalX, 0f, 1f, p)
        val cx = p[0]; val cy = p[1]; val rad = 3f * p[2]
        var red = 0
        for (ix in (cx - rad).toInt().coerceAtLeast(0)..(cx + rad).toInt().coerceAtMost(w - 1))
            for (iy in (cy - rad).toInt().coerceAtLeast(0)..(cy + rad).toInt().coerceAtMost(h - 1))
                if (lampRed(px[ix, iy])) red++
        return red
    }

    @Test fun the_goal_light_shines_at_the_net_scored_on_during_the_banner() {
        for (scorer in Side.entries) {
            val goalX = scorer.dir * Rink.GOAL_X   // the net this side attacks
            val sim = quietSim()
            sim.puck.x = 0f; sim.puck.y = 9f
            val r = renderer()
            r.camera.snapTo(scorer.dir * 100f)
            val before = redNear(r, render(r, sim), goalX)
            sim.phase = Phase.GOAL; sim.lastGoalSide = scorer
            val lit = redNear(r, render(r, sim), goalX)
            sim.lastGoalSide = scorer.other   // a goal at the other end lights the other lamp
            val otherEnd = redNear(r, render(r, sim), goalX)
            assertTrue("$scorer: $lit lit red pixels vs $before in play", lit >= 400 && before < 20)
            assertTrue("$scorer: other end's goal lit $otherEnd here", otherEnd < 20)
        }
    }
}
