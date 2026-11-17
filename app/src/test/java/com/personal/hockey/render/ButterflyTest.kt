package com.personal.hockey.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.BUTTERFLY_HOLD_S
import com.personal.hockey.render.anim.BUTTERFLY_MAX_S
import com.personal.hockey.render.anim.Clip
import com.personal.hockey.render.anim.ClipLibrary
import com.personal.hockey.render.anim.PuckFlight
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton.ANKLE
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.ELBOW
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HEEL
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.render.figure.Skeleton.KNEE
import com.personal.hockey.render.figure.Skeleton.SH_C
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** The goalie's butterfly on a shot: who drops, the pose, and that he always gets back up. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButterflyTest {
    private val renderer = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD)).also {
        it.camera.setViewport(2400f, 1080f); it.camera.snapTo(0f, 0f)
    }
    private val anim get() = renderer.anim
    /** The HOME shooter (2) in the AWAY end and the AWAY shooter (7) in the HOME end, both 12 m out: shots, not dumps. */
    private val sim = HockeySim(Random(1)).also {
        it.startPlayForTest()
        it.skaters[2].x = it.goalies[Side.AWAY.index].x - 12f
        it.skaters[7].x = it.goalies[Side.HOME.index].x + 12f
    }

    /** A HOME shot attacks the AWAY net. */
    private val shot = SimEvent.Shot(Side.HOME, power = 0.7f, shooterId = 2)
    private val away get() = anim.goalies[Side.AWAY.index]
    private val home get() = anim.goalies[Side.HOME.index]

    /** One tick carrying [events], heard by the renderer, then the frame's clocks moved; the sim itself is left still. */
    private fun tick(vararg events: SimEvent) {
        sim.events.clear()
        for (e in events) sim.events.add(e)
        renderer.onTick(sim)
        renderer.advance(sim, Tuning.DT)
        sim.events.clear()
    }

    private fun run(seconds: Float) = repeat(Math.round(seconds / Tuning.DT)) { tick() }

    @Test fun shot_drops_the_defending_goalie() {
        tick(shot)
        assertEquals(Clip.GOALIE_BUTTERFLY, away.clip)
        assertNotEquals(Clip.GOALIE_BUTTERFLY, home.clip)
        // And the other way: an AWAY shot drops the HOME goalie.
        tick(SimEvent.Shot(Side.AWAY, power = 0.3f, shooterId = 7))
        assertEquals(Clip.GOALIE_BUTTERFLY, home.clip)
    }

    @Test fun long_dump_does_not_drop_the_goalie() {
        val goalLineX = Rink.GOAL_X * kotlin.math.sign(sim.goalies[Side.AWAY.index].x)
        sim.skaters[2].x = goalLineX - PuckFlight.FLAT_SHOT_M - 1f
        tick(shot)
        assertEquals("a dump from ${sim.skaters[2].x}", Clip.NONE, away.clip)
        assertEquals(Clip.NONE, home.clip)
        run(0.3f)
        assertEquals(0f, away.weight, 0f)
        // Just inside the distance, it is a shot.
        sim.skaters[2].x = goalLineX - PuckFlight.FLAT_SHOT_M + 1f
        tick(shot)
        assertEquals(Clip.GOALIE_BUTTERFLY, away.clip)
    }

    @Test fun butterfly_pose_has_pads_flat() {
        tick(shot)
        run(0.2f)
        assertEquals(1f, away.weight, 0f)
        val p = pose(Side.AWAY)
        for (hip in intArrayOf(HIP_L, HIP_R)) {
            assertTrue("knee $hip z ${p.z[hip + KNEE]}", p.z[hip + KNEE] < 0.15f)
            for (pad in intArrayOf(hip + ANKLE, hip + HEEL)) {
                assertTrue("pad $pad lateral ${p.b[pad]}", abs(p.b[pad]) > 0.6f)
                assertTrue("pad $pad z ${p.z[pad]}", p.z[pad] < 0.12f)
            }
        }
        assertTrue("left pad to the left", p.b[HIP_L + ANKLE] > 0f && p.b[HIP_R + ANKLE] < 0f)
        assertTrue("hips ${p.z[HIP_C]}", p.z[HIP_C] in 0.35f..0.55f)
        assertTrue("torso upright", abs(p.a[SH_C] - p.a[HIP_C]) < 0.2f && p.z[SH_C] > p.z[HIP_C] + 0.4f)
        assertTrue("glove out ${p.b[HAND_L]} at ${p.z[HAND_L]}", p.b[HAND_L] > 0.5f && p.z[HAND_L] in 0.55f..0.85f)
        assertTrue("blocker out ${p.b[HAND_R]} at ${p.z[HAND_R]}", p.b[HAND_R] < -0.4f && p.z[HAND_R] in 0.55f..0.85f)
        assertTrue("blade flat", p.z[BLADE_HEEL] < 0.05f && p.z[BLADE_TOE] < 0.05f)
        assertTrue("blade across the five-hole", p.b[BLADE_HEEL] < 0f && p.b[BLADE_TOE] > 0f)
    }

    /** Every key keeps the goalie's limbs and stick in the proportions of his standing pose. */
    @Test fun butterfly_keys_keep_the_goalie_in_proportion() {
        val d = ClipLibrary[Clip.GOALIE_BUTTERFLY]!!
        assertTrue(d.loop)
        assertEquals(2, d.times.size)
        tick(shot)
        run(0.2f)
        val p = pose(Side.AWAY)
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
    }

    @Test fun goalie_gets_up_after_a_save() {
        tick(shot)
        run(0.3f)
        tick(SimEvent.Save(Side.AWAY))
        run(BUTTERFLY_HOLD_S - 0.1f)
        assertEquals("still down just before the hold is up", Clip.GOALIE_BUTTERFLY, away.clip)
        assertEquals(1f, away.weight, 0f)
        run(0.4f)
        assertEquals(0f, away.weight, 0f)
        assertEquals(Clip.NONE, away.clip)
    }

    @Test fun goalie_gets_up_after_a_goal_or_a_pickup() {
        tick(shot)
        tick(SimEvent.Pickup(Side.AWAY, skaterId = 6))
        run(BUTTERFLY_HOLD_S + 0.3f)
        assertEquals(0f, away.weight, 0f)
    }

    /** Review Focus 2. */
    @Test fun goalie_never_stuck_down() {
        tick(shot)
        run(0.3f)
        tick(SimEvent.FaceoffDrop)
        assertEquals("the faceoff clears it at once", Clip.NONE, away.clip)
        assertEquals(0f, away.weight, 0f)

        tick(shot)
        run(BUTTERFLY_MAX_S - 0.2f)
        assertEquals("a live shot holds it", Clip.GOALIE_BUTTERFLY, away.clip)
        run(2.3f - BUTTERFLY_MAX_S + 0.2f - Tuning.DT)
        assertEquals("the cap gets him up", 0f, away.weight, 0f)
    }

    @Test fun a_stoppage_gets_him_up() {
        for (phase in arrayOf(Phase.FACEOFF, Phase.BREAK, Phase.FINAL)) {
            sim.startPlayForTest()
            tick(shot)
            run(0.2f)
            sim.phase = phase
            tick()
            assertEquals("$phase", Clip.NONE, away.clip)
        }
    }

    @Test fun a_goal_slumps_the_goalie_out_of_the_butterfly() {
        tick(shot)
        run(0.2f)
        sim.lastScorerId = 2
        sim.phase = Phase.GOAL
        tick(SimEvent.Goal(Side.HOME, scorerId = 2))
        run(0.3f)
        assertEquals(Clip.GOALIE_SLUMP, away.clip)
        assertEquals(1f, away.weight, 0f)
    }

    @Test fun the_whistle_holds_the_butterfly_until_the_faceoff() {
        val g = sim.goalies[Side.AWAY.index]
        tick(shot)
        run(0.3f)
        g.holding = true
        sim.phase = Phase.WHISTLE
        tick(SimEvent.Save(Side.AWAY), SimEvent.Cover, SimEvent.Whistle(Side.AWAY))
        run(Tuning.WHISTLE_TIME)
        assertEquals("holding through the whistle", Clip.GOALIE_BUTTERFLY, away.clip)
        assertEquals(1f, away.weight, 0f)
        g.holding = false
        sim.phase = Phase.FACEOFF
        tick()
        assertEquals("up for the faceoff", Clip.NONE, away.clip)
    }

    @Test fun a_whistle_without_the_puck_gets_him_up() {
        tick(SimEvent.Shot(Side.AWAY, power = 0.3f, shooterId = 7))   // the HOME goalie drops
        run(0.2f)
        sim.goalies[Side.AWAY.index].holding = true   // the other goalie has it
        sim.phase = Phase.WHISTLE
        tick()
        assertEquals(Clip.NONE, home.clip)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun pose(side: Side): Pose {
        renderer.figures.poseGoalie(renderer.camera, sim.goalies[side.index], anim.goalies[side.index])
        val p = renderer.figures.lastPose
        return Pose().also { p.a.copyInto(it.a); p.b.copyInto(it.b); p.z.copyInto(it.z) }
    }

    private fun dist(p: Pose, i: Int, j: Int): Float {
        val da = p.a[i] - p.a[j]; val db = p.b[i] - p.b[j]; val dz = p.z[i] - p.z[j]
        return sqrt(da * da + db * db + dz * dz)
    }
}
