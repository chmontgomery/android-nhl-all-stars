package com.personal.hockey.render.anim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.CanvasRenderer
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.ELBOW
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HEAD
import com.personal.hockey.render.figure.Skeleton.HEAD_FWD
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** The goal celebration: roles, clips, the hug, the beaten goalie and the camera push-in. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CelebrationAnimTest {
    private fun newRenderer() = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD)).also {
        it.camera.setViewport(2400f, 1080f); it.camera.snapTo(0f, 0f)
    }
    private var renderer = newRenderer()
    private val anim get() = renderer.anim

    /** One sim tick heard by the renderer, then the frame's clocks moved, as GameViewModel.onFrame does. */
    private fun HockeySim.run(seconds: Float, each: () -> Unit = {}) = repeat(Math.round(seconds / Tuning.DT)) {
        step(); renderer.onTick(this); renderer.advance(this, Tuning.DT); each()
    }

    /**
     * HOME skater 0 (in the slot at x 12) is credited with a puck put into the AWAY net, after HOME's
     * [goalsBefore] goals, with [clockS] left; returns the sim in GOAL on the goal's tick, heard.
     */
    private fun scored(goalsBefore: Int = 0, clockS: Float = Tuning.PERIOD_SECONDS): HockeySim {
        val sim = HockeySim(Random(1))
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        val spots = arrayOf(12f to 0f, 3f to -8f, 2f to 6f, 0f to -3f, 5f to 9f)
        for ((i, p) in spots.withIndex()) sim.skaters[i].also { it.x = p.first; it.y = p.second }
        sim.skaters[0].vx = 2f
        sim.score[Side.HOME.index] = goalsBefore
        sim.clockS = clockS
        sim.giveTo(sim.skaters[0])
        sim.puck.carrier = null
        sim.puck.x = Rink.GOAL_X - 0.05f; sim.puck.y = 0.5f; sim.puck.vx = 12f; sim.puck.vy = 0f
        sim.run(Tuning.DT)
        assertEquals(Phase.GOAL, sim.phase)
        assertEquals(0, sim.lastScorerId)
        return sim
    }

    @Test fun a_goal_gives_everyone_a_role() {
        val sim = scored()
        for (s in sim.skaters) {
            val expected = when {
                s.id == 0 -> Role.SCORER
                s.side == Side.HOME -> Role.HUGGER
                else -> Role.DEJECTED
            }
            assertEquals("skater ${s.id}", expected, anim.skaters[s.id].role)
        }
        assertEquals(Role.BEATEN, anim.goalies[Side.AWAY.index].role)
        assertEquals(Role.NONE, anim.goalies[Side.HOME.index].role)
        sim.run(Tuning.DT)
        for (s in sim.skaters) if (s.side == Side.AWAY) assertEquals("skater ${s.id}", Clip.DEJECTED, anim.skaters[s.id].clip)
        assertEquals(Clip.GOALIE_SLUMP, anim.goalies[Side.AWAY.index].clip)
        assertEquals(Clip.NONE, anim.goalies[Side.HOME.index].clip)
        assertEquals("the scorer glides first", Clip.NONE, anim.skaters[0].clip)
    }

    @Test fun celebrations_cycle_with_the_teams_goal_count() {
        val expected = arrayOf(Clip.CELEBRATE_FIST, Clip.CELEBRATE_SLIDE, Clip.CELEBRATE_STICK, Clip.CELEBRATE_FIST)
        for (goal in 1..4) {
            renderer = newRenderer()
            val sim = scored(goalsBefore = goal - 1)
            assertEquals(goal, sim.score[Side.HOME.index])
            sim.run(CHEER_GLIDE_S - 0.05f)
            assertEquals("goal $goal still gliding", Clip.NONE, anim.skaters[0].clip)
            sim.run(0.1f)
            assertEquals("goal $goal", expected[goal - 1], anim.skaters[0].clip)
        }
    }

    @Test fun huggers_hug_once_close() {
        val sim = scored()
        val scorer = sim.skaters[0]
        val mates = sim.skaters.filter { it.side == Side.HOME && it.id != 0 }
        for (m in mates) assertTrue("skater ${m.id} starts far", hypot(m.x - scorer.x, m.y - scorer.y) > HUG_RANGE)
        val startedAt = FloatArray(sim.skaters.size) { -1f }
        sim.run(3f) {
            for (m in mates) if (startedAt[m.id] < 0f && anim.skaters[m.id].clip == Clip.HUG) startedAt[m.id] = hypot(m.x - scorer.x, m.y - scorer.y)
        }
        for (m in mates) {
            assertEquals("skater ${m.id}", Clip.HUG, anim.skaters[m.id].clip)
            assertTrue("skater ${m.id} began hugging ${startedAt[m.id]} m away", startedAt[m.id] in 0f..HUG_RANGE)
        }
    }

    /** Review Focus 4: a goal with no time left runs GOAL, CELEBRATE, then BREAK, not FACEOFF. */
    @Test fun roles_clear_and_the_camera_zooms_out_when_the_goal_pause_ends_in_a_break() {
        val sim = scored(clockS = 0f)
        sim.run(1.5f)
        assertEquals(1.3f, renderer.camera.zoomGoal, 0f)
        var seen = 0
        var ticks = 0
        while (sim.phase != Phase.BREAK && ticks < 600) {
            if (sim.phase == Phase.CELEBRATE) {
                seen++
                assertEquals(1.3f, renderer.camera.zoomGoal, 0f)
            }
            sim.run(Tuning.DT); ticks++
        }
        assertEquals(Phase.BREAK, sim.phase)
        assertTrue("went through CELEBRATE", seen > 0)
        assertAllCleared(sim)
    }

    @Test fun roles_clear_at_the_faceoff() {
        val sim = scored()
        var ticks = 0
        while (sim.phase != Phase.FACEOFF && ticks < 600) { sim.run(Tuning.DT); ticks++ }
        assertEquals(Phase.FACEOFF, sim.phase)
        assertAllCleared(sim)
    }

    private fun assertAllCleared(sim: HockeySim) {
        for (st in anim.skaters) assertEquals(Role.NONE, st.role)
        for (st in anim.goalies) assertEquals(Role.NONE, st.role)
        assertEquals(1f, renderer.camera.zoomGoal, 0f)
        sim.run(0.2f)
        for ((i, st) in anim.skaters.withIndex()) assertEquals("skater $i weight", 0f, st.weight, 0f)
        for ((i, st) in anim.goalies.withIndex()) assertEquals("goalie $i weight", 0f, st.weight, 0f)
    }

    @Test fun the_camera_pushes_in_on_the_scorer() {
        val sim = scored()
        sim.run(1.5f)
        val cam = renderer.camera
        assertTrue("zoom ${cam.zoom}", cam.zoom > 1.2f)
        val p = FloatArray(3)
        val s = sim.skaters[0]
        cam.project(s.x, s.y, 0f, p)
        assertTrue("scorer at ${p[0]} of ${cam.width}", abs(p[0] - cam.width / 2f) < 0.15f * cam.width)
    }

    @Test fun the_knee_slide_sprays_snow() {
        val slide = scored(goalsBefore = 1)
        slide.run(CHEER_GLIDE_S + 0.5f)
        assertEquals(Clip.CELEBRATE_SLIDE, anim.skaters[0].clip)
        assertTrue("snow ${renderer.effects.alive}", renderer.effects.alive > 0)
    }

    @Test fun the_fist_pump_throws_no_snow() {
        val fist = scored(goalsBefore = 0)
        fist.run(CHEER_GLIDE_S + 0.5f)
        assertEquals(Clip.CELEBRATE_FIST, anim.skaters[0].clip)
        assertEquals(0, renderer.effects.alive)
    }

    // ---- the poses ------------------------------------------------------------------------------

    private val figures get() = renderer.figures

    /** Skater 2 of a fresh sim posed with [clip] fully in, [t] seconds along. */
    private fun skaterPose(clip: Clip, t: Float): Pose {
        val sim = HockeySim(Random(1))
        val s = sim.skaters[2]
        s.x = 0f; s.y = 0f; s.vx = 1f; s.vy = 0f; s.facing = 0f
        val st = AnimState().apply { this.clip = clip; clipT = t; weight = 1f }
        figures.poseSkater(renderer.camera, s, st)
        return copyOf(figures.lastPose)
    }

    private fun goaliePose(clip: Clip, t: Float): Pose {
        val sim = HockeySim(Random(1))
        val st = AnimState().apply { this.clip = clip; clipT = t; weight = if (clip == Clip.NONE) 0f else 1f }
        figures.poseGoalie(renderer.camera, sim.goalies[0], st)
        return copyOf(figures.lastPose)
    }

    private fun copyOf(p: Pose) = Pose().also { p.a.copyInto(it.a); p.b.copyInto(it.b); p.z.copyInto(it.z) }

    private fun bladeZ(p: Pose) = (p.z[BLADE_HEEL] + p.z[BLADE_TOE]) / 2f

    @Test fun the_fist_pump_raises_a_fist_above_the_head() {
        val p = skaterPose(Clip.CELEBRATE_FIST, 2f)
        assertTrue("hands ${p.z[HAND_L]}, ${p.z[HAND_R]} head ${p.z[HEAD]}", max(p.z[HAND_L], p.z[HAND_R]) > p.z[HEAD] + 0.2f)
    }

    @Test fun the_knee_slide_drops_to_a_knee() {
        val p = skaterPose(Clip.CELEBRATE_SLIDE, 2f)
        val knee = min(p.z[HIP_L + KNEE], p.z[HIP_R + KNEE])
        assertTrue("knee $knee", knee < 0.15f)
        assertTrue("hips ${p.z[HIP_C]}", p.z[HIP_C] < 0.7f)
    }

    @Test fun the_stick_goes_up_overhead() {
        val p = skaterPose(Clip.CELEBRATE_STICK, 2f)
        assertTrue("blade ${bladeZ(p)} head ${p.z[HEAD]}", bladeZ(p) > p.z[HEAD] + 0.8f)
    }

    @Test fun the_hug_reaches_both_arms_ahead() {
        for (t in floatArrayOf(0f, 0.3f, 0.9f)) {
            val p = skaterPose(Clip.HUG, t)
            for (h in intArrayOf(HAND_L, HAND_R)) {
                assertTrue("t $t hand $h a ${p.a[h]}", p.a[h] > p.a[SH_C] + 0.3f)
                assertTrue("t $t hand $h z ${p.z[h]}", p.z[h] in 1.1f..1.7f)
            }
            assertTrue("t $t leans in", p.a[HEAD] > 0.35f)
        }
    }

    @Test fun the_dejected_hang_their_heads_with_blades_flat() {
        for (t in floatArrayOf(0f, 0.7f, 1.9f)) {
            val p = skaterPose(Clip.DEJECTED, t)
            assertTrue("t $t head looks down: ${p.z[HEAD_FWD]} vs ${p.z[HEAD]}", p.z[HEAD_FWD] < p.z[HEAD] - 0.4f)
            assertTrue("t $t blade heel ${p.z[BLADE_HEEL]} toe ${p.z[BLADE_TOE]}", max(p.z[BLADE_HEEL], p.z[BLADE_TOE]) < 0.05f)
            assertTrue("t $t hands on the stick", max(offShaft(p, HAND_L), offShaft(p, HAND_R)) < 0.08f)
        }
    }

    @Test fun the_beaten_goalie_slumps() {
        val ready = goaliePose(Clip.NONE, 0f)
        val slump = goaliePose(Clip.GOALIE_SLUMP, 1f)
        assertTrue("head ${slump.z[HEAD]} vs ${ready.z[HEAD]}", slump.z[HEAD] < ready.z[HEAD] - 0.1f)
        assertTrue("shoulders ${slump.z[SH_C]} vs ${ready.z[SH_C]}", slump.z[SH_C] < ready.z[SH_C] - 0.08f)
        assertTrue("head looks down", slump.z[HEAD_FWD] < slump.z[HEAD] - 0.4f)
        assertTrue("blade ${bladeZ(slump)}", max(slump.z[BLADE_HEEL], slump.z[BLADE_TOE]) < 0.05f)
        // The stick's middle and the elbows follow the keyed hands and stick ends.
        assertTrue("stick mid on the shaft", offSegment(slump, Skeleton.STICK_MID, STICK_TOP, BLADE_HEEL) < 1e-3f)
        for (sh in intArrayOf(SH_L, SH_R)) {
            val hand = if (sh == SH_L) HAND_L else HAND_R
            assertTrue("elbow $sh between shoulder and hand", dist(slump, sh, sh + ELBOW) < dist(slump, sh, hand))
        }
    }

    /**
     * Every celebration clip, at every key, keeps the stick a stick's length and the arms an arm's:
     * no limb stretched out of shape by a mistyped key.
     */
    @Test fun every_celebration_key_keeps_limbs_in_proportion() {
        for (clip in arrayOf(Clip.CELEBRATE_FIST, Clip.CELEBRATE_SLIDE, Clip.CELEBRATE_STICK, Clip.HUG, Clip.DEJECTED, Clip.GOALIE_SLUMP)) {
            val d = ClipLibrary[clip]!!
            assertTrue("$clip loops or lasts the pause after the glide", d.loop || d.duration >= Tuning.GOAL_TIME + Tuning.CELEBRATE_TIME - CHEER_GLIDE_S - 1e-3f)
            for (t in d.times) {
                val p = if (clip == Clip.GOALIE_SLUMP) goaliePose(clip, t) else skaterPose(clip, t)
                val stick = dist(p, STICK_TOP, BLADE_HEEL)
                val range = if (clip == Clip.GOALIE_SLUMP) 0.6f..0.95f else 1.1f..1.45f
                assertTrue("$clip t $t stick $stick", stick in range)
                for (sh in intArrayOf(SH_L, SH_R)) {
                    val hand = if (sh == SH_L) HAND_L else HAND_R
                    assertTrue("$clip t $t upper arm ${dist(p, sh, sh + ELBOW)}", dist(p, sh, sh + ELBOW) < 0.42f)
                    assertTrue("$clip t $t forearm ${dist(p, sh + ELBOW, hand)}", dist(p, sh + ELBOW, hand) < 0.42f)
                }
                for (hip in intArrayOf(HIP_L, HIP_R)) {
                    assertTrue("$clip t $t thigh ${dist(p, hip, hip + KNEE)}", dist(p, hip, hip + KNEE) < 0.55f)
                    assertTrue("$clip t $t shin ${dist(p, hip + KNEE, hip + Skeleton.ANKLE)}", dist(p, hip + KNEE, hip + Skeleton.ANKLE) < 0.55f)
                }
            }
        }
    }

    @Test fun the_scorers_clips_let_go_of_the_stick_with_both_hands_keyed() {
        for (clip in arrayOf(Clip.CELEBRATE_FIST, Clip.CELEBRATE_SLIDE, Clip.CELEBRATE_STICK, Clip.HUG)) {
            val d = ClipLibrary[clip]!!
            for (i in intArrayOf(HAND_L, HAND_R, SH_L + ELBOW, SH_R + ELBOW, Skeleton.STICK_MID)) assertTrue("$clip keys $i", d.keyed[i])
        }
        assertTrue(ClipLibrary[Clip.DEJECTED]!!.handsFollowStick)
        assertNotEquals(null, ClipLibrary[Clip.GOALIE_SLUMP])
    }

    private fun dist(p: Pose, i: Int, j: Int): Float {
        val da = p.a[i] - p.a[j]; val db = p.b[i] - p.b[j]; val dz = p.z[i] - p.z[j]
        return sqrt(da * da + db * db + dz * dz)
    }

    private fun offShaft(p: Pose, hand: Int) = offSegment(p, hand, STICK_TOP, BLADE_HEEL)

    /** Distance of slot [i] from the segment [from] → [to]. */
    private fun offSegment(p: Pose, i: Int, from: Int, to: Int): Float {
        val ax = p.a[from]; val bx = p.b[from]; val zx = p.z[from]
        val ua = p.a[to] - ax; val ub = p.b[to] - bx; val uz = p.z[to] - zx
        val ha = p.a[i] - ax; val hb = p.b[i] - bx; val hz = p.z[i] - zx
        val k = ((ha * ua + hb * ub + hz * uz) / (ua * ua + ub * ub + uz * uz)).coerceIn(0f, 1f)
        val da = ha - k * ua; val db = hb - k * ub; val dz = hz - k * uz
        return sqrt(da * da + db * db + dz * dz)
    }
}
