package com.personal.hockey.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/** A puck carrier sweeps the blade across his body, and the carried puck is drawn on the blade. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickhandlingTest {
    private val w = 1600
    private val h = 720
    private val p = FloatArray(3)
    private val xy = FloatArray(2)

    private fun draw(each: DrawScope.() -> Unit): PixelMap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) { each() }
        return bmp.toPixelMap()
    }

    /** Lateral offset (to the skater's left) of the blade's middle from his centre line. */
    private fun lateral(figures: FigurePainter, camera: Camera, s: Skater, phase: Float): Float {
        figures.poseSkater(camera, s, AnimState().apply { sweepPhase = phase; sweepW = 1f })
        assertTrue(figures.bladeOnIce(xy))
        return xy[1] - s.y   // facing 0: left is +y
    }

    @Test fun the_blade_sweeps_across_the_body() {
        val camera = Camera().apply { setViewport(w.toFloat(), h.toFloat()); snapTo(0f) }
        val figures = FigurePainter()
        for (speed in floatArrayOf(1f, 3f, 5f)) {
            val s = Skater(1, Side.HOME, Position.C).apply { x = 0f; y = -3f; facing = 0f; vx = speed }
            // Half a turn apart from an off-peak phase (0.7 rad, not a multiple of pi): opposite, nonzero offsets.
            val a = lateral(figures, camera, s, 0.7f)
            val b = lateral(figures, camera, s, 0.7f + PI.toFloat())
            assertTrue("at $speed m/s the blade moved only ${abs(a - b)} m", abs(a - b) > 0.3f)
        }
    }

    @Test fun the_sweep_is_continuous_across_speed_changes() {
        val camera = Camera().apply { setViewport(w.toFloat(), h.toFloat()); snapTo(0f) }
        val figures = FigurePainter()
        val dir = AnimDirector()
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val s = sim.skaters[1]
        s.x = 0f; s.y = -3f; s.facing = 0f; s.act = Act.NONE
        val ticks = 120   // 1 to 6 m/s and back in 2 s
        var prev = Float.NaN; var worst = 0f
        for (i in 0..ticks) {
            val k = if (i <= ticks / 2) i / (ticks / 2f) else (ticks - i) / (ticks / 2f)
            s.vx = 1f + 5f * k
            dir.onTick(sim)
            dir.advance(Tuning.DT)
            val l = lateral(figures, camera, s, dir.skaters[s.id].sweepPhase)
            if (!prev.isNaN()) worst = maxOf(worst, abs(l - prev))
            prev = l
        }
        // The steepest natural sweep (0.4 m at 3 Hz) moves 0.126 m per tick; a phase jump moved it up to ~0.7 m.
        assertTrue("blade moved $worst m in one tick", worst < 0.14f)
    }

    private fun carrierScene(): Pair<CanvasRenderer, HockeySim> {
        val r = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY))
        r.camera.setViewport(w.toFloat(), h.toFloat()); r.camera.snapTo(SCENE_X)
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) { s.x = 40f + s.id; s.y = 0f; s.vx = 0f; s.vy = 0f }   // all off the ice
        for (g in sim.goalies) g.y = 0f
        val me = sim.skaters[0]
        me.x = SCENE_X; me.y = -3f; me.vx = 2f; me.vy = 0f; me.facing = 0f; me.act = Act.NONE
        sim.giveTo(me)
        sim.controlledId = -1
        r.anim.skaters[0].sweepPhase = 0.9f
        r.anim.skaters[0].sweepW = 1f   // carrying long enough for the sweep to be fully in
        return r to sim
    }

    /** Centre of the dark pixels within 22 px of [cx], [cy]; null when there are none. */
    private fun darkCentre(px: PixelMap, cx: Float, cy: Float): FloatArray? {
        var n = 0; var sx = 0f; var sy = 0f
        for (x in (cx - 22).toInt()..(cx + 22).toInt()) for (y in (cy - 22).toInt()..(cy + 22).toInt()) {
            if (x !in 0 until w || y !in 0 until h) continue
            val c = px[x, y]
            if (0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue < 0.15f) { n++; sx += x; sy += y }
        }
        return if (n == 0) null else floatArrayOf(sx / n, sy / n)
    }

    @Test fun the_carried_puck_is_drawn_at_the_blade() {
        val (r, sim) = carrierScene()
        val me = sim.skaters[0]
        val px = draw { r.draw(this, sim, 0f) }
        r.figures.poseSkater(r.camera, me, r.anim.skaters[0])
        assertTrue(r.figures.bladeOnIce(xy))
        r.camera.project(xy[0], xy[1], 0f, p)
        val c = darkCentre(px, p[0], p[1])
        assertTrue("no puck pixels near the blade", c != null)
        assertTrue("puck centre ${c!![0]}, ${c[1]} vs blade ${p[0]}, ${p[1]}", abs(c[0] - p[0]) <= 6f && abs(c[1] - p[1]) <= 6f)
    }

    /**
     * Skater 0 at 2 m/s (a 3 Hz sweep: half a turn every 10 ticks), ticked through the renderer as the
     * frame loop does. Each event comes a multiple of 10 ticks after the first, which falls on a sweep
     * peak, so a hard switch at any of them would jump the full 0.4 m half-width.
     */
    private class CarryRun(val r: CanvasRenderer, val sim: HockeySim) {
        val me: Skater = sim.skaters[0]
        private val xy = FloatArray(2)
        var prevBlade = Float.NaN; var prevPuckA = 0f; var prevPuckB = 0f
        var worstBlade = 0f; var worstPuck = 0f

        fun tick(n: Int = 1) = repeat(n) {
            r.onTick(sim)
            sim.events.clear()
            r.advance(sim, Tuning.DT)
            r.figures.poseSkater(r.camera, me, r.anim.skaters[me.id])
            val p = r.figures.lastPose
            val blade = (p.b[com.personal.hockey.render.figure.Skeleton.BLADE_HEEL] + p.b[com.personal.hockey.render.figure.Skeleton.BLADE_TOE]) / 2f
            r.puckDrawXY(sim, xy)
            val oa = xy[0] - sim.puck.x; val ob = xy[1] - sim.puck.y
            if (!prevBlade.isNaN()) {
                worstBlade = maxOf(worstBlade, abs(blade - prevBlade))
                worstPuck = maxOf(worstPuck, kotlin.math.hypot(oa - prevPuckA, ob - prevPuckB))
            }
            prevBlade = blade; prevPuckA = oa; prevPuckB = ob
        }
    }

    private fun carryRun(): CarryRun {
        val (r, sim) = carrierScene()
        val run = CarryRun(r, sim)
        val me = run.me
        sim.puck.carrier = null   // loose at his feet to start
        sim.events.clear()
        // Phase at the first event (tick 10): pi/2, a peak; +pi every 10 ticks after.
        r.anim.skaters[me.id].sweepPhase = (PI / 2 - 10 * PI / 10).toFloat() + 2 * PI.toFloat()
        return run
    }

    @Test fun the_blade_and_the_drawn_puck_ease_in_at_a_pickup_and_out_at_a_pass() {
        val run = carryRun()
        val sim = run.sim
        run.tick(9)
        sim.giveTo(run.me)
        run.tick(30)
        // A pass: the puck leaves from the sim's carry point.
        sim.puck.carrier = null; sim.puck.vx = 15f
        repeat(15) { sim.puck.x += sim.puck.vx * Tuning.DT; run.tick() }
        // The natural sweep moves the blade up to 0.126 m a tick; a hard switch at a peak moved it 0.4 m.
        assertTrue("blade moved ${run.worstBlade} m in one tick", run.worstBlade < 0.16f)
        assertTrue("drawn puck moved ${run.worstPuck} m off the sim's in one tick", run.worstPuck < 0.16f)
    }

    @Test fun the_blade_and_the_drawn_puck_ease_out_at_a_windup_and_a_deke() {
        for (act in arrayOf(Act.WINDUP, Act.DEKE)) {
            val run = carryRun()
            run.sim.giveTo(run.me)
            run.tick(30)
            assertEquals(1f, run.r.anim.skaters[0].sweepW, 1e-4f)
            run.me.act = act
            repeat(15) { run.me.windup += Tuning.DT; run.tick() }
            assertTrue("$act: blade moved ${run.worstBlade} m in one tick", run.worstBlade < 0.16f)
            assertTrue("$act: drawn puck moved ${run.worstPuck} m off the sim's in one tick", run.worstPuck < 0.16f)
        }
    }

    @Test fun at_a_shot_the_puck_is_drawn_where_the_sim_releases_it() {
        val run = carryRun()
        run.sim.giveTo(run.me)
        run.tick(30)
        run.me.act = Act.WINDUP
        repeat(Math.round(Tuning.WINDUP_MIN / Tuning.DT) + 6) { run.me.windup += Tuning.DT; run.tick() }
        // The last carried frame before the release: the sweep has eased out, so the puck sits at the carry point.
        assertEquals(0f, run.r.anim.skaters[0].sweepW, 0f)
        run.r.puckDrawXY(run.sim, xy)
        assertEquals(run.sim.puck.x, xy[0], 1e-4f)
        assertEquals(run.sim.puck.y, xy[1], 1e-4f)
    }

    @Test fun while_winding_up_the_puck_stays_on_the_ice_at_the_carry_point() {
        val (r, sim) = carrierScene()
        val me = sim.skaters[0]
        me.act = Act.WINDUP; me.windup = 0.7f
        r.anim.skaters[0].sweepW = 0f   // winding up long enough for the sweep to have eased out
        val px = draw { r.draw(this, sim, 0f) }
        r.figures.poseSkater(r.camera, me, r.anim.skaters[0])
        assertFalse(r.figures.bladeOnIce(xy))
        assertEquals(me, sim.puck.carrier)
        r.camera.project(sim.puck.x, sim.puck.y, 0f, p)
        val c = darkCentre(px, p[0], p[1])
        assertTrue("no puck pixels at the carry point", c != null)
        assertTrue("puck centre ${c!![0]}, ${c[1]} vs carry point ${p[0]}, ${p[1]}", abs(c[0] - p[0]) <= 6f && abs(c[1] - p[1]) <= 6f)
    }
}

/** Open ice between the blue line and the end-zone dots: clear of the dark centre-ice logo, which the puck finder would see. */
private const val SCENE_X = 12f
