package com.personal.hockey.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.render.anim.Clip
import com.personal.hockey.render.anim.ClipLibrary
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** The wind-up pose (wrist draw-back morphing into the slap lift) and the follow-through clips. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotAnimTest {
    private val camera = Camera().apply { setViewport(1600f, 720f); snapTo(0f) }
    private val figures = FigurePainter()

    private fun bladeA(p: Pose) = (p.a[BLADE_HEEL] + p.a[BLADE_TOE]) / 2f
    private fun bladeZ(p: Pose) = (p.z[BLADE_HEEL] + p.z[BLADE_TOE]) / 2f

    private fun winder(windup: Float) = Skater(1, Side.HOME, Position.C).apply {
        x = 0f; y = -3f; facing = 0f; vx = 2f; act = Act.WINDUP; this.windup = windup
    }

    private fun windupPose(windup: Float, anim: AnimState = AnimState()): Pose {
        figures.poseSkater(camera, winder(windup), anim)
        return figures.lastPose
    }

    @Test fun a_short_windup_keeps_the_blade_low_and_back() {
        val p = windupPose(0.3f)
        assertTrue("blade z ${bladeZ(p)}", bladeZ(p) < 0.35f)
        assertTrue("blade a ${bladeA(p)}", bladeA(p) < 0f)
    }

    @Test fun a_long_windup_lifts_the_stick_above_the_shoulder() {
        val p = windupPose(0.75f)
        val shoulder = max(p.z[SH_L], p.z[SH_R])
        assertTrue("blade z ${bladeZ(p)} vs shoulder $shoulder", bladeZ(p) > shoulder)
    }

    @Test fun the_windup_morphs_without_a_jump() {
        var prevA = Float.NaN; var prevB = 0f; var prevZ = 0f; var worst = 0f
        var w = 0f
        while (w <= Tuning.WINDUP_AUTO_FIRE) {
            val p = windupPose(w)
            val a = bladeA(p); val b = (p.b[BLADE_HEEL] + p.b[BLADE_TOE]) / 2f; val z = bladeZ(p)
            if (!prevA.isNaN()) {
                val da = a - prevA; val db = b - prevB; val dz = z - prevZ
                worst = max(worst, sqrt(da * da + db * db + dz * dz))
            }
            prevA = a; prevB = b; prevZ = z
            w += Tuning.DT
        }
        // The steepest smooth stretch (the lift, ~2 m over 0.15 s) moves the blade ~0.35 m a tick.
        assertTrue("blade moved $worst m in one tick", worst < 0.4f)
    }

    @Test fun a_one_timer_winds_up_like_a_slapshot() {
        val anim = AnimState().apply { pickupFromPass = true; sincePickup = 0.1f }
        val p = windupPose(0.2f, anim)
        val shoulder = max(p.z[SH_L], p.z[SH_R])
        assertTrue("blade z ${bladeZ(p)} vs shoulder $shoulder", bladeZ(p) > shoulder)
    }

    /** A sim with [shooter] standing at (0, -3) facing +x, a [power] shot heard and [seconds] of clip played. */
    private fun afterShot(power: Float, seconds: Float): Pair<Skater, AnimState> {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.x = 0f; s.y = -3f; s.vx = 2f; s.vy = 0f; s.facing = 0f; s.act = Act.NONE
        sim.events.clear()
        sim.events.add(SimEvent.Shot(Side.HOME, power, s.id))
        director.onTick(sim)
        sim.events.clear()
        repeat(Math.round(seconds / Tuning.DT)) { director.advance(Tuning.DT) }
        return s to director.skaters[s.id]
    }

    @Test fun the_follow_through_clips_are_in_the_library() {
        assertEquals(0.35f, ClipLibrary[Clip.WRIST_FOLLOW]!!.duration, 1e-4f)
        assertEquals(0.45f, ClipLibrary[Clip.SLAP_FOLLOW]!!.duration, 1e-4f)
    }

    @Test fun the_follow_through_ends_high_and_ahead() {
        val (s, anim) = afterShot(0.3f, 0.2f)
        assertEquals(Clip.WRIST_FOLLOW, anim.clip)
        figures.poseSkater(camera, s, anim)
        val p = figures.lastPose
        assertTrue("blade a ${bladeA(p)}", bladeA(p) > 0.5f)
        assertTrue("blade z ${bladeZ(p)}", bladeZ(p) > 0.8f)
    }

    @Test fun the_slap_follow_through_ends_higher_than_the_wrist() {
        val (ws, wAnim) = afterShot(0.3f, 0.34f)
        figures.poseSkater(camera, ws, wAnim)
        val wrist = bladeZ(figures.lastPose)
        val (ss, sAnim) = afterShot(0.8f, 0.44f)
        assertEquals(Clip.SLAP_FOLLOW, sAnim.clip)
        figures.poseSkater(camera, ss, sAnim)
        val slap = bladeZ(figures.lastPose)
        assertTrue("wrist end $wrist, slap end $slap", wrist in 1.0f..1.5f && slap > wrist + 0.3f)
    }

    @Test fun the_hands_stay_on_the_stick_through_the_follow_through() {
        for (facing in floatArrayOf(0f, Math.PI.toFloat())) {   // either hand on top, by camera side
            for (t in floatArrayOf(0.05f, 0.15f, 0.3f, 0.43f)) {
                val (s, anim) = afterShot(0.8f, t)
                s.facing = facing
                figures.poseSkater(camera, s, anim)
                val p = figures.lastPose
                // One hand at the top of the shaft, the other on the shaft between the top and the heel.
                val dl = offShaft(p, HAND_L); val dr = offShaft(p, HAND_R)
                assertTrue("t $t facing $facing: hands ${dl}, ${dr} m off the shaft", max(dl, dr) < 0.08f)
            }
        }
    }

    /**
     * Skater 2 winds up for [windup] s and releases a [power] shot, ticked and drawn as the game does
     * (onTick, advance, pose). Returns the largest per-tick move of the blade's middle from the wind-up
     * through 0.35 s after the release, the lowest the blade's middle gets in the 0.12 s after it, and
     * the farthest a hand gets off the shaft after it.
     */
    private fun release(windup: Float, power: Float, clip: Clip): FloatArray {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector()
        val s = sim.skaters[2]
        s.x = 0f; s.y = -3f; s.vx = 2f; s.vy = 0f; s.facing = 0f; s.act = Act.WINDUP; s.windup = 0f
        val st = director.skaters[s.id]
        var pa = Float.NaN; var pb = 0f; var pz = 0f; var worst = 0f; var lowest = Float.MAX_VALUE
        var sinceRelease = -1f; var offHand = 0f
        fun frame() {
            director.advance(Tuning.DT)
            figures.poseSkater(camera, s, st)
            val p = figures.lastPose
            val a = bladeA(p); val b = (p.b[BLADE_HEEL] + p.b[BLADE_TOE]) / 2f; val z = bladeZ(p)
            if (!pa.isNaN()) worst = max(worst, sqrt((a - pa) * (a - pa) + (b - pb) * (b - pb) + (z - pz) * (z - pz)))
            if (sinceRelease in 0f..0.12f) lowest = minOf(lowest, z)
            if (sinceRelease >= 0f) offHand = max(offHand, max(offShaft(p, HAND_L), offShaft(p, HAND_R)))
            pa = a; pb = b; pz = z
        }
        while (s.windup < windup) { s.windup += Tuning.DT; director.onTick(sim); frame() }
        s.act = Act.NONE; s.windup = 0f
        sim.events.add(SimEvent.Shot(Side.HOME, power, s.id))
        director.onTick(sim)
        sim.events.clear()
        assertEquals(clip, st.clip)
        sinceRelease = 0f
        repeat(Math.round(0.35f / Tuning.DT)) { sinceRelease += Tuning.DT; frame(); director.onTick(sim) }
        return floatArrayOf(worst, lowest, offHand)
    }

    @Test fun the_stick_swings_down_through_the_puck_at_a_slapshot() {
        val (worst, lowest, offHand) = release(0.75f, 0.8f, Clip.SLAP_FOLLOW)
        // About 2.6 m from the lift to the puck: a one-frame snap; spread over the downswing, ~0.5 m a tick.
        assertTrue("blade moved $worst m in one tick", worst < 0.8f)
        assertTrue("the blade came down only to $lowest m", lowest < 0.1f)
        assertTrue("a hand came $offHand m off the shaft", offHand < 0.08f)
    }

    @Test fun the_blade_sweeps_through_at_a_wrist_shot() {
        val (worst, lowest, offHand) = release(0.3f, 0.3f, Clip.WRIST_FOLLOW)
        assertTrue("blade moved $worst m in one tick", worst < 0.8f)
        assertTrue("the blade came down only to $lowest m", lowest < 0.1f)
        assertTrue("a hand came $offHand m off the shaft", offHand < 0.08f)
    }

    /** Distance of [hand] from the segment STICK_TOP → BLADE_HEEL. */
    private fun offShaft(p: Pose, hand: Int): Float {
        val ax = p.a[STICK_TOP]; val bx = p.b[STICK_TOP]; val zx = p.z[STICK_TOP]
        val ua = p.a[BLADE_HEEL] - ax; val ub = p.b[BLADE_HEEL] - bx; val uz = p.z[BLADE_HEEL] - zx
        val ha = p.a[hand] - ax; val hb = p.b[hand] - bx; val hz = p.z[hand] - zx
        val k = ((ha * ua + hb * ub + hz * uz) / (ua * ua + ub * ub + uz * uz)).coerceIn(0f, 1f)
        val da = ha - k * ua; val db = hb - k * ub; val dz = hz - k * uz
        return sqrt(da * da + db * db + dz * dz)
    }
}
