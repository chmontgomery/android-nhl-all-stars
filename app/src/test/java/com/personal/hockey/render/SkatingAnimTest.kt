package com.personal.hockey.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** The gliding stride, crossovers on fast turns and the hockey stop, posed procedurally every frame. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkatingAnimTest {
    private val camera = Camera().apply { setViewport(1600f, 720f); snapTo(0f) }
    private val figures = FigurePainter()

    private fun skater(speed: Float, stride: Float = 0f) = Skater(1, Side.HOME, Position.C).apply {
        x = 0f; y = -3f; facing = 0f; vx = speed; this.stride = stride
    }

    private fun pose(s: Skater, anim: AnimState = AnimState()): Pose {
        figures.poseSkater(camera, s, anim)
        return figures.lastPose
    }

    /** z of a heel and a toe slot whose blade rests on the ice: the steel hangs about 5 cm under the skate's bone. */
    private val heelIceZ = 0.06f
    private val toeIceZ = 0.04f

    @Test fun stride_lift_is_low() {
        var maxLift = 0f
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            val p = pose(skater(6f, d))
            maxLift = maxOf(maxLift, p.z[HIP_L + 4] - toeIceZ, p.z[HIP_R + 4] - toeIceZ)
            d += 0.02f
        }
        assertTrue("max toe lift $maxLift", maxLift < 0.07f)
    }

    @Test fun push_goes_out_to_the_side() {
        var maxB = 0f
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            val p = pose(skater(6f, d))
            maxB = maxOf(maxB, Math.abs(p.b[HIP_L + 2]), Math.abs(p.b[HIP_R + 2]))
            d += 0.02f
        }
        assertTrue("max lateral $maxB", maxB > 0.33f)
    }

    @Test fun gliding_keeps_both_skates_on_the_ice() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.vx = 6f; s.vy = 0f; s.facing = 0f; s.act = Act.NONE; s.stopping = false
        s.input.moveX = 0f; s.input.moveY = 0f
        repeat(30) { director.onTick(sim) }
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            s.stride = d
            val p = pose(s, director.skaters[2])
            for (hip in intArrayOf(HIP_L, HIP_R)) {
                assertTrue("stride $d hip $hip heel z ${p.z[hip + 3]}", p.z[hip + 3] - heelIceZ < 0.03f)
                assertTrue("stride $d hip $hip toe z ${p.z[hip + 4]}", p.z[hip + 4] - toeIceZ < 0.03f)
                assertTrue("stride $d hip $hip heel b ${p.b[hip + 3]}", Math.abs(p.b[hip + 3]) < 0.25f)
                assertTrue("stride $d hip $hip toe b ${p.b[hip + 4]}", Math.abs(p.b[hip + 4]) < 0.25f)
            }
            d += 0.1f
        }
    }

    @Test fun glide_eases() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.vx = 6f; s.vy = 0f; s.act = Act.NONE; s.stopping = false
        val st = director.skaters[2]
        var last = st.glideW
        for (i in 0 until 40) {
            s.input.moveX = if (i % 2 == 0) 0f else 1f; s.input.moveY = 0f
            director.onTick(sim)
            assertTrue("tick $i: $last -> ${st.glideW}", Math.abs(st.glideW - last) < 0.2f)
            last = st.glideW
        }
        repeat(30) { s.input.moveX = 0f; director.onTick(sim) }
        assertEquals(1f, st.glideW, 1e-6f)
        repeat(30) { s.input.moveX = 1f; director.onTick(sim) }
        assertEquals(0f, st.glideW, 1e-6f)
    }

    @Test fun no_glide_while_stopping() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.vx = 6f; s.vy = 0f; s.act = Act.NONE; s.stopping = true
        s.input.moveX = 0f; s.input.moveY = 0f
        repeat(30) { director.onTick(sim) }
        assertEquals(0f, director.skaters[2].glideW, 0f)
    }

    @Test fun a_hockey_stop_turns_the_skates_sideways() {
        val s = skater(6f).apply { stopping = true }
        val p = pose(s, AnimState().apply { stopW = 1f })
        for (hip in intArrayOf(HIP_L, HIP_R)) {
            val da = Math.abs(p.a[hip + 4] - p.a[hip + 3]); val db = Math.abs(p.b[hip + 4] - p.b[hip + 3])
            assertTrue("hip $hip: da $da db $db", db > da)
        }
    }

    @Test fun a_hockey_stop_drops_the_hips_and_sits_back() {
        val upright = pose(skater(6f)).z[HIP_C]
        val s = skater(6f).apply { stopping = true }
        val p = pose(s, AnimState().apply { stopW = 1f })
        assertEquals(upright - 0.15f, p.z[HIP_C], 0.03f)
    }

    @Test fun a_hockey_stop_throws_snow_forward() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val effects = Effects()
        val director = AnimDirector(effects)
        val s = sim.skaters[2]
        s.x = 0f; s.y = -3f; s.vx = 6f; s.vy = 0f; s.facing = 1.5f; s.act = Act.NONE; s.stopping = true   // facing is not the travel direction
        director.onTick(sim)
        assertEquals(3, effects.alive)
        var along = 0f
        for (i in effects.life.indices) if (effects.life[i] > 0f) along += effects.vx[i]   // travel is +x
        assertTrue("mean along travel ${along / 3f}", along / 3f > 1f)
    }

    @Test fun no_snow_when_not_stopping() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val effects = Effects()
        val director = AnimDirector(effects)
        val s = sim.skaters[2]
        s.vx = 6f; s.vy = 0f; s.stopping = false
        director.onTick(sim)
        assertEquals(0, effects.alive)
    }

    /** Whether the outside foot's b passes the inside foot's b at some point in one stride. */
    private fun crossesOver(turnRate: Float, speed: Float): Boolean {
        val anim = AnimState().apply { this.turnRate = turnRate }
        var sawOutsideOutside = false; var sawCrossed = false
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            val p = pose(skater(speed, d), anim)
            val left = p.b[HIP_L + 2]; val right = p.b[HIP_R + 2]
            val outside = if (turnRate > 0f) right else left   // turning left: the right foot is outside
            val inside = if (turnRate > 0f) left else right
            if (turnRate > 0f) { if (outside > inside) sawCrossed = true else sawOutsideOutside = true }
            else { if (outside < inside) sawCrossed = true else sawOutsideOutside = true }
            d += 0.05f
        }
        return sawCrossed && sawOutsideOutside
    }

    @Test fun a_fast_turn_crosses_the_outside_foot_over() {
        assertTrue(crossesOver(2.5f, 6f))
        assertTrue(crossesOver(-2.5f, 6f))
    }

    @Test fun straight_skating_never_crosses_over() {
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            val p = pose(skater(6f, d))
            assertTrue("stride $d", p.b[HIP_L + 2] > p.b[HIP_R + 2])
            d += 0.05f
        }
    }

    @Test fun a_slow_or_gentle_turn_does_not_cross() {
        var d = 0f
        while (d < FigurePainter.STRIDE_M) {
            assertTrue(pose(skater(3f, d), AnimState().apply { turnRate = 3f }).let { it.b[HIP_L + 2] > it.b[HIP_R + 2] })
            assertTrue(pose(skater(6f, d), AnimState().apply { turnRate = 1f }).let { it.b[HIP_L + 2] > it.b[HIP_R + 2] })
            d += 0.05f
        }
    }

    @Test fun a_fast_turn_leans_into_it() {
        val sh = com.personal.hockey.render.figure.Skeleton.SH_C
        val straight = pose(skater(6f)).b[sh]   // the painter reuses its pose: read before the next
        val left = pose(skater(6f), AnimState().apply { turnRate = 2.5f }).b[sh]
        assertEquals(0.25f, left - straight, 0.01f)
    }

    @Test fun a_teleported_facing_does_not_count_as_a_turn() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        director.onTick(sim)
        sim.skaters[2].facing += 3f
        director.onTick(sim)
        assertEquals(0f, director.skaters[2].turnRate, 1e-6f)
    }

    @Test fun a_real_hard_turn_in_the_crossover_band_is_kept() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        director.onTick(sim)
        sim.skaters[2].facing += 15f * Tuning.DT   // the sim can turn this fast at 4 m/s
        director.onTick(sim)
        assertEquals(15f, director.skaters[2].turnRate, 0.05f)
    }

    @Test fun the_stop_pose_eases_in() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.vx = 6f; s.vy = 0f; s.act = Act.NONE; s.stopping = false
        director.onTick(sim)
        val upright = pose(s, director.skaters[2]).z[HIP_C]
        s.stopping = true
        director.onTick(sim)
        val drop = upright - pose(s, director.skaters[2]).z[HIP_C]
        assertTrue("drop $drop", drop > 0f && drop < 0.15f * 0.5f)
        repeat(10) { director.onTick(sim) }
        assertEquals(upright - 0.15f, pose(s, director.skaters[2]).z[HIP_C], 0.03f)
    }

    @Test fun no_stop_pose_during_another_action() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.vx = 6f; s.act = Act.WINDUP; s.stopping = true
        repeat(10) { director.onTick(sim) }
        assertEquals(0f, director.skaters[2].stopW, 0f)
    }
}
