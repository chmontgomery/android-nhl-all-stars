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
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.random.Random

/** Who has the puck, and where it is, must read at a glance on a phone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PuckViewTest {
    private val w = 1600
    private val h = 720
    private val p = FloatArray(3)
    private val setup = MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY)

    private fun renderer() = CanvasRenderer(setup).also {
        it.camera.setViewport(w.toFloat(), h.toFloat()); it.camera.snapTo(0f)
    }

    /** timeS = 0 keeps the carrier's blade still, so frames with and without the puck match elsewhere. */
    private fun render(r: CanvasRenderer, sim: HockeySim): PixelMap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            r.draw(this, sim, 0f)
        }
        return bmp.toPixelMap()
    }

    /** Live play, everyone parked along the far boards out of the way, nobody human-controlled. */
    private fun quietSim(): HockeySim {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) { s.x = -18f + 4f * s.id; s.y = 11f; s.vx = 0f; s.vy = 0f; s.facing = 0f }
        sim.controlledId = -1
        return sim
    }

    private fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Calls [each] for every pixel within [radiusM] of the point last passed to [project]. */
    private inline fun around(radiusM: Float, each: (Int, Int) -> Unit) {
        val cx = projX; val cy = projY; val rad = radiusM * projS
        for (ix in (cx - rad).toInt()..(cx + rad).toInt()) for (iy in (cy - rad).toInt()..(cy + rad).toInt()) {
            if (ix !in 0 until w || iy !in 0 until h) continue
            val dx = ix - cx; val dy = iy - cy
            if (dx * dx + dy * dy <= rad * rad) each(ix, iy)
        }
    }

    private var projX = 0f; private var projY = 0f; private var projS = 0f

    /** Projects (x, y, z) through [r]'s camera into [projX], [projY], [projS] for [around]. */
    private fun project(r: CanvasRenderer, x: Float, y: Float, z: Float) {
        r.camera.project(x, y, z, p); projX = p[0]; projY = p[1]; projS = p[2]
    }

    @Test fun a_puck_on_open_ice_has_a_dark_rim() {
        val sim = quietSim()
        val r = renderer()
        sim.puck.x = 0f; sim.puck.y = -2f
        val with = render(r, sim)
        sim.puck.x = 0f; sim.puck.y = -30f   // off the ice: same frame without the puck
        val without = render(r, sim)
        project(r, 0f, -2f, 0f)
        var changed = 0; var dark = 0
        around(0.5f) { x, y ->
            if (with[x, y] != without[x, y]) { changed++; if (lum(with[x, y]) < 0.15f) dark++ }
        }
        // The 0.15 m puck's rim (about 0.14 m effective radius) at 1.6x: the same 45% dark share as before.
        val area = (PI * (0.14f * projS) * (0.14f * projS) * 0.45f).toInt()
        assertTrue("$dark dark of $changed changed pixels; want at least $area dark", dark >= area)
    }

    @Test fun a_carried_puck_has_a_light_rim_that_shows_against_a_dark_kit() {
        // The HOME carrier, in MIN's dark green, skates away from the camera: his puck is drawn
        // over his own skates and shins.
        val sim = quietSim()
        val s = sim.skaters[2]
        s.x = 0f; s.y = -3f; s.facing = (PI / 2).toFloat()
        sim.giveTo(s)
        val r = renderer()
        val with = render(r, sim)
        val px = sim.puck.x; val py = sim.puck.y
        sim.puck.carrier = null; sim.puck.x = 0f; sim.puck.y = -30f
        val without = render(r, sim)
        project(r, px, py, 0.04f)
        var rim = 0
        around(0.45f) { x, y ->
            if (lum(without[x, y]) < 0.3f && lum(with[x, y]) > 0.8f) rim++
        }
        assertTrue("only $rim light rim pixels over the dark kit", rim >= 25)
    }
}
