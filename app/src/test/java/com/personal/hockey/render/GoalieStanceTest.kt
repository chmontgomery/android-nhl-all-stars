package com.personal.hockey.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.render.anim.Clip
import com.personal.hockey.render.anim.GOALIE_STEP_M
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton.ANKLE
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.ELBOW
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.render.figure.Skeleton.KNEE
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.SLOTS
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The goalie's standing ready stance and his drawn movement: a shuffle for slow lateral moves, a
 * T-push for fast ones. (That a shot still drops him into the butterfly is ButterflyTest's
 * shot_drops_the_defending_goalie, left unchanged.)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalieStanceTest {
    private val renderer = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD)).also {
        it.camera.setViewport(2400f, 1080f); it.camera.snapTo(0f, 0f)
    }
    private val anim get() = renderer.anim
    private val sim = HockeySim(Random(1)).also { it.startPlayForTest(); it.puck.carrier = null }
    private val g = sim.goalies[Side.AWAY.index]
    private val st: AnimState get() = anim.goalies[Side.AWAY.index]
    /** Straight out of the AWAY net, in world x. */
    private val out = -sign(g.x)

    /**
     * One tick with the AWAY goalie moved by ([vx], [vy]) m/s over it and the puck 10 m straight out in
     * front of him, so he faces straight out; heard by the renderer, then the frame's clocks moved.
     */
    private fun tick(vx: Float = 0f, vy: Float = 0f) {
        g.x += vx * Tuning.DT; g.y += vy * Tuning.DT
        sim.puck.x = g.x + out * 10f; sim.puck.y = g.y
        sim.events.clear()
        renderer.onTick(sim)
        renderer.advance(sim, Tuning.DT)
    }

    private fun ticks(seconds: Float): Int = Math.round(seconds / Tuning.DT)

    @Test fun ready_stance_is_standing() {
        repeat(10) { tick() }
        val p = pose()
        assertTrue("hips ${p.z[HIP_C]}", p.z[HIP_C] >= 0.74f)
        for (hip in intArrayOf(HIP_L, HIP_R)) assertTrue("knee $hip z ${p.z[hip + KNEE]}", p.z[hip + KNEE] > 0.35f)
        assertInProportion(p)
    }

    @Test fun ready_stance_holds_until_a_shot() {
        repeat(ticks(3f)) { k ->
            // The puck sweeps 10 m out across the front of the net; nobody shoots.
            val a = 1.1f * sin(k * 0.03f)
            sim.puck.x = g.x + out * 10f * cos(a); sim.puck.y = g.y + 10f * sin(a)
            sim.events.clear()
            renderer.onTick(sim)
            renderer.advance(sim, Tuning.DT)
            assertEquals("tick $k", Clip.NONE, st.clip)
            if (k % 20 == 0) assertTrue("hips at tick $k", pose().z[HIP_C] >= 0.74f)
        }
        assertTrue(pose().z[HIP_C] >= 0.74f)
    }

    @Test fun slow_lateral_move_shuffles() {
        tick()
        repeat(ticks(0.3f)) { tick(vy = 1.5f) }
        assertEquals(1f, st.shuffleW, 1e-4f)
        assertEquals(0f, st.pushW, 0f)
        // Over a step cycle the lead and trail feet step in turn: their lateral offsets swing against each other.
        val n = ticks(GOALIE_STEP_M / 1.5f) + 1
        val lead = FloatArray(n); val trail = FloatArray(n)
        val move = moveSide(0f, 1.5f)
        val leadHip = if (move > 0f) HIP_L else HIP_R
        val trailHip = if (move > 0f) HIP_R else HIP_L
        for (k in 0 until n) {
            tick(vy = 1.5f)
            val p = pose()
            lead[k] = p.b[leadHip + ANKLE] - p.b[HIP_C]
            trail[k] = p.b[trailHip + ANKLE] - p.b[HIP_C]
            for (i in 0 until SLOTS) assertTrue("slot $i through the ice", p.z[i] >= 0f)
        }
        val ml = lead.average().toFloat(); val mt = trail.average().toFloat()
        var cov = 0f
        for (k in 0 until n) cov += (lead[k] - ml) * (trail[k] - mt)
        assertTrue("lead foot steps ${lead.max() - lead.min()}", lead.max() - lead.min() > 0.08f)
        assertTrue("trail foot steps ${trail.max() - trail.min()}", trail.max() - trail.min() > 0.08f)
        assertTrue("feet alternate, covariance $cov", cov < 0f)
    }

    @Test fun fast_lateral_move_t_pushes() {
        tick()
        repeat(ticks(0.4f)) { tick(vy = 5f) }
        assertEquals(1f, st.pushW, 1e-4f)
        assertEquals(0f, st.shuffleW, 1e-4f)
        val p = pose()
        val move = moveSide(0f, 5f)
        val trailHip = if (move > 0f) HIP_R else HIP_L
        val reach = (p.b[trailHip + ANKLE] - p.b[HIP_C]) * move
        assertTrue("trail ankle ${-reach} m out on the side away from the move", reach <= -0.6f)
        assertInProportion(p)
    }

    @Test fun near_the_threshold_it_does_not_flicker() {
        tick()
        for (start in floatArrayOf(1.5f, 5f)) {
            repeat(ticks(0.5f)) { tick(vy = start) }
            var last = st.pushW
            var slope = 0f
            var flips = 0
            repeat(ticks(2f)) { k ->
                tick(vy = if (k % 2 == 0) 2.3f else 2.7f)
                val d = st.pushW - last
                if (d != 0f) {
                    if (slope != 0f && d.sign != slope) flips++
                    slope = d.sign
                }
                last = st.pushW
            }
            assertTrue("from $start m/s: pushW turned $flips times", flips <= 2)
        }
    }

    @Test fun a_reset_jump_is_not_a_t_push() {
        repeat(10) { tick() }
        g.y += 3f
        tick()
        assertEquals(0f, st.pushW, 0f)
        assertEquals(0f, st.shuffleW, 0f)
        tick()
        assertEquals(0f, st.pushW, 0f)
        assertEquals(0f, st.shuffleW, 0f)
    }

    @Test fun a_small_teleport_mid_t_push_stops_the_push() {
        repeat(40) { tick(vy = 6f) }   // well into a T-push
        assertTrue(st.pushW > 0.5f)
        g.y += 0.4f                    // a reset jump, but under the old 0.5 m threshold
        tick(vy = 6f)
        assertEquals(0f, st.pushW, 0f)
        assertEquals(0f, st.shuffleW, 0f)
        tick()
        assertEquals(0f, st.pushW, 0f)
        assertEquals(0f, st.shuffleW, 0f)
    }

    @Test fun stance_allocates_nothing() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun round(k: Int) {
            // Back and forth across the crease, from a standstill through a shuffle to a T-push.
            tick(vy = 5f * sin(k * 0.02f))
            for (side in Side.entries) renderer.figures.poseGoalie(renderer.camera, sim.goalies[side.index], anim.goalies[side.index])
        }
        repeat(200) { round(it) }
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 600) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 600 ticks", bytes < 16 * 1024L)
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** +1 if a move at ([vx], [vy]) m/s goes to the AWAY goalie's drawn left, -1 if to his right. */
    private fun moveSide(vx: Float, vy: Float) = sign(-vx * sin(st.facing) + vy * cos(st.facing))

    private fun pose(): Pose {
        renderer.figures.poseGoalie(renderer.camera, g, st)
        val p = renderer.figures.lastPose
        return Pose().also { p.a.copyInto(it.a); p.b.copyInto(it.b); p.z.copyInto(it.z) }
    }

    /** Limbs and stick in the goalie's proportions, as ButterflyTest holds the butterfly to, and nothing under the ice. */
    private fun assertInProportion(p: Pose) {
        val stick = dist(p, STICK_TOP, BLADE_HEEL)
        assertTrue("stick $stick", stick in 0.6f..0.95f)
        for (sh in intArrayOf(SH_L, SH_R)) {
            val hand = if (sh == SH_L) HAND_L else HAND_R
            assertTrue("upper arm ${dist(p, sh, sh + ELBOW)}", dist(p, sh, sh + ELBOW) < 0.42f)
            assertTrue("forearm ${dist(p, sh + ELBOW, hand)}", dist(p, sh + ELBOW, hand) < 0.42f)
        }
        for (hip in intArrayOf(HIP_L, HIP_R)) {
            assertTrue("thigh ${dist(p, hip, hip + KNEE)}", dist(p, hip, hip + KNEE) < 0.5f)
            assertTrue("shin ${dist(p, hip + KNEE, hip + ANKLE)}", dist(p, hip + KNEE, hip + ANKLE) < 0.5f)
        }
        for (i in 0 until SLOTS) assertTrue("slot $i z ${p.z[i]}", p.z[i] >= 0f)
    }

    private fun dist(p: Pose, i: Int, j: Int): Float {
        val da = p.a[i] - p.a[j]; val db = p.b[i] - p.b[j]; val dz = p.z[i] - p.z[j]
        return sqrt(da * da + db * db + dz * dz)
    }
}
