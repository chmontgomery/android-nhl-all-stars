package com.personal.hockey.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.render.figure.GoalieBody
import com.personal.hockey.render.figure.SkaterBody
import com.personal.hockey.render.mesh.TriangleSink
import com.personal.hockey.render.mesh.TriangleSinks
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.Goalie
import com.personal.hockey.sim.GoalieSkill
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.Kit
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Skaters and goalies are shaded low-poly meshes: whole from every side, cheap enough, and visible in a dark kit. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkaterMeshTest {
    private val w = 1600
    private val h = 720
    private val p = FloatArray(3)
    private val camera = Camera().apply { setViewport(w.toFloat(), h.toFloat()); snapTo(0f) }

    /** A magenta jersey: no rink or other kit colour is a shade of it. */
    private val magenta = KitColors(Kit("m", "Magenta", 0xFFFF00FF, 0xFF000000, 0xFF202020, 0xFF202020, 0xFFFFFFFF))

    /** Hands every triangle on to a real sink, keeping the corners it saw since the last begin. */
    private class Recording(private val inner: TriangleSink) : TriangleSink {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        override val count get() = inner.count
        override fun begin() { xs.clear(); ys.clear(); inner.begin() }
        override fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
            xs += x0; xs += x1; xs += x2; ys += y0; ys += y1; ys += y2
            inner.add(x0, y0, c0, x1, y1, c1, x2, y2, c2)
        }
        override fun flush(scope: DrawScope) = inner.flush(scope)
    }

    private fun draw(each: DrawScope.() -> Unit): PixelMap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) { each() }
        return bmp.toPixelMap()
    }

    private fun skater(act: Act, facingDeg: Float): Skater = Skater(0, Side.HOME, Position.C).apply {
        x = 0f; y = -3f; facing = (facingDeg * PI / 180.0).toFloat()
        this.act = act; windup = 0.5f
    }

    /** A shade of magenta: red and blue equal and clearly lit, no green. */
    private fun magentaShade(c: Color) = c.red > 0.3f && abs(c.red - c.blue) < 0.06f && c.green < 0.12f

    private fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test fun a_skater_has_at_most_500_triangles() {
        val total = SkaterBody.parts.sumOf { it.mesh.triangleCount }
        assertTrue("$total triangles", total in 1..500)
        val figures = FigurePainter()
        draw { figures.drawSkater(this, camera, skater(Act.NONE, 30f), magenta, 9, anim = AnimState()) }
        assertEquals(total, figures.lastTriangles)
    }

    @Test fun a_full_rink_scene_stays_within_8000_triangles() {
        val r = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY))
        r.camera.setViewport(w.toFloat(), h.toFloat()); r.camera.snapTo(0f)
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        draw { r.draw(this, sim, 0f) }
        val perSkater = SkaterBody.parts.sumOf { it.mesh.triangleCount }
        val perGoalie = GoalieBody.parts.sumOf { it.mesh.triangleCount }
        assertTrue("${r.figureTriangles} triangles in the frame",
            r.figureTriangles >= 10 * perSkater + 2 * perGoalie && r.figureTriangles <= 8000)
    }

    private fun goalie(side: Side, saveFlash: Float): Goalie = Goalie(side, GoalieSkill(0.1f, 3f, 0.5f)).apply {
        x = 0f; y = -3f; this.saveFlash = saveFlash
    }

    @Test fun a_goalie_has_at_most_800_triangles() {
        val total = GoalieBody.parts.sumOf { it.mesh.triangleCount }
        assertTrue("$total triangles", total in 1..800)
        // Each part's triangles are insertion-sorted, so no one part may grow large.
        for (part in GoalieBody.parts) assertTrue("a part of ${part.mesh.triangleCount} triangles", part.mesh.triangleCount <= 120)
        val figures = FigurePainter()
        draw { figures.drawGoalie(this, camera, goalie(Side.HOME, 99f), magenta, 30, AnimState()) }
        assertEquals(total, figures.lastTriangles)
    }

    @Test fun a_goalie_draws_in_both_poses_facing_both_ways() {
        val sink = Recording(TriangleSinks.default())
        val figures = FigurePainter(sink)
        for (side in Side.entries) for (flash in floatArrayOf(99f, 0.1f)) {
            val g = goalie(side, flash)
            val px = draw { figures.drawGoalie(this, camera, g, magenta, 30, AnimState()) }
            val label = "$side, ${if (flash < 1f) "save" else "ready"}"
            assertTrue("$label: nothing emitted", sink.count > 0 && sink.xs.isNotEmpty())
            for (i in sink.xs.indices) assertTrue("$label: corner $i not finite", sink.xs[i].isFinite() && sink.ys[i].isFinite())
            camera.project(g.x, g.y, 0f, p)
            val cx = p[0]; val bottom = p[1].toInt(); val reach = 1f * p[2]
            camera.project(g.x, g.y, 1.7f, p)
            var jersey = 0
            for (x in max(0, (cx - reach).toInt())..minOf(w - 1, (cx + reach).toInt()))
                for (y in max(0, p[1].toInt())..minOf(h - 1, bottom)) if (magentaShade(px[x, y])) jersey++
            assertTrue("$label: only $jersey jersey pixels", jersey >= 200)
        }
    }

    @Test fun every_facing_draws_a_whole_skater() {
        val sink = Recording(TriangleSinks.default())
        val figures = FigurePainter(sink)
        for (act in arrayOf(Act.NONE, Act.WINDUP, Act.DOWN)) for (k in 0 until 16) {
            val deg = k * 22.5f
            val s = skater(act, deg)
            val px = draw { figures.drawSkater(this, camera, s, magenta, 9, anim = AnimState().apply { sweepPhase = 0.3f; sweepW = 1f }) }
            val label = "$act facing $deg"
            assertTrue("$label: nothing emitted", sink.count > 0 && sink.xs.isNotEmpty())
            for (i in sink.xs.indices) assertTrue("$label: corner $i not finite", sink.xs[i].isFinite() && sink.ys[i].isFinite())
            // The screen box: 1.3 m round the skater (a fallen one lies about a metre ahead), up to 2.2 m.
            camera.project(s.x, s.y, 0f, p)
            val cx = p[0]; val bottom = p[1].toInt() + 4; val reach = 1.3f * p[2]
            camera.project(s.x, s.y, 2.2f, p)
            var found = false
            for (x in max(0, (cx - reach).toInt())..minOf(w - 1, (cx + reach).toInt())) {
                for (y in max(0, p[1].toInt())..minOf(h - 1, bottom)) if (magentaShade(px[x, y])) { found = true; break }
                if (found) break
            }
            assertTrue("$label: no jersey pixel in the skater's box", found)
        }
    }

    /** Blue jersey, red trim, green pants: each part's colour is easy to tell apart. */
    private val primaries = KitColors(Kit("p", "Primaries", 0xFF0000FF, 0xFFFF0000, 0xFF00FF00, 0xFF202020, 0xFFFFFFFF))

    private fun redShade(c: Color) = c.red > 0.3f && c.red > c.green + 0.25f && c.red > c.blue + 0.25f

    /** Trim-red pixels round the hips of a skater seen from behind (facing +y), with no number. */
    private fun hemPixelsFromBehind(act: Act, speed: Float): Int {
        val s = skater(act, 90f).apply { vy = speed; windup = 0f }
        val figures = FigurePainter()
        val px = draw { figures.drawSkater(this, camera, s, primaries, -1, anim = AnimState()) }
        camera.project(s.x, s.y, 0.6f, p)
        val cx = p[0]; val bottom = p[1].toInt(); val half = 0.5f * p[2]
        camera.project(s.x, s.y, 1.3f, p)
        var n = 0
        for (x in (cx - half).toInt()..(cx + half).toInt()) for (y in p[1].toInt()..bottom) {
            if (x in 0 until w && y in 0 until h && redShade(px[x, y])) n++
        }
        return n
    }

    @Test fun a_leaning_skater_seen_from_behind_keeps_his_jersey_hem_over_his_pants() {
        val standing = hemPixelsFromBehind(Act.NONE, 0f)
        assertTrue("only $standing hem pixels standing", standing >= 40)
        for ((act, speed) in listOf(Act.NONE to Tuning.MAX_SPEED, Act.BODY to Tuning.MAX_SPEED)) {
            val n = hemPixelsFromBehind(act, speed)
            assertTrue("$act at $speed m/s: $n hem pixels, $standing standing", n >= standing / 2)
        }
    }

    @Test fun a_dark_kit_at_the_far_boards_stands_out() {
        // No numbers: only the kit's shading should set him apart.
        val min = Teams.byId("MIN")!!.copy(skaters = emptyList())
        val r = CanvasRenderer(MatchSetup.of(min, Teams.byId("COL")!!, Level.EASY))
        r.camera.setViewport(w.toFloat(), h.toFloat()); r.camera.snapTo(0f, Rink.HALF_W)
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) { s.x = 40f + s.id; s.y = 0f; s.vx = 0f; s.vy = 0f }   // all off the ice
        sim.puck.x = 0f; sim.puck.y = -30f
        sim.controlledId = -1
        val s = sim.skaters[1]   // HOME, MIN's dark kit
        s.x = 0f; s.y = Rink.HALF_W - 1f; s.facing = 0f
        val with = draw { r.draw(this, sim, 0f) }
        s.x = 40f
        val without = draw { r.draw(this, sim, 0f) }

        // From just above his ground shadow to the top of his helmet.
        r.camera.project(0f, Rink.HALF_W - 1f, 0.3f, p)
        val cx = p[0]; val bottom = p[1].toInt(); val half = 0.45f * p[2]
        r.camera.project(0f, Rink.HALF_W - 1f, 1.95f, p)
        val top = p[1].toInt()
        var sumWith = 0.0; var sumWithout = 0.0; var differ = 0; var n = 0
        for (x in (cx - half).toInt()..(cx + half).toInt()) for (y in top..bottom) {
            if (x !in 0 until w || y !in 0 until h) continue
            val a = lum(with[x, y]); val b = lum(without[x, y])
            sumWith += a; sumWithout += b; n++
            if (abs(a - b) >= 0.2f) differ++
        }
        val meanDiff = abs(sumWith - sumWithout) / n
        val share = differ.toFloat() / n
        assertTrue("mean luminance differs by ${"%.3f".format(meanDiff)}, ${"%.0f".format(100 * share)}% of $n pixels by 0.2",
            meanDiff >= 0.08 || share >= 0.15f)
    }
}
