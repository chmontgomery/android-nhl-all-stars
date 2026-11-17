package com.personal.hockey.render.anim

import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton
import com.personal.hockey.render.figure.Skeleton.ANKLE
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.ELBOW
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HEAD
import com.personal.hockey.render.figure.Skeleton.HEAD_FWD
import com.personal.hockey.render.figure.Skeleton.HEEL
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.render.figure.Skeleton.KNEE
import com.personal.hockey.render.figure.Skeleton.SH_C
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.STICK_MID
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.render.figure.Skeleton.TOE
import com.personal.hockey.sim.Tuning
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The keyframed clips a figure can play over its procedural pose. */
enum class Clip { NONE, WRIST_FOLLOW, SLAP_FOLLOW, CELEBRATE_FIST, CELEBRATE_SLIDE, CELEBRATE_STICK, HUG, DEJECTED, GOALIE_SLUMP, GOALIE_BUTTERFLY }

/** A figure's part in a goal celebration. */
enum class Role { NONE, SCORER, HUGGER, DEJECTED, BEATEN }

/**
 * A clip: poses [keys] at ascending [times] (seconds from the clip's start). Only the slots marked in
 * [keyed] move; the rest come from the procedural pose. A skater clip that moves the stick but keys
 * neither hand has its hands, elbows and stick middle put back on the stick after blending
 * ([handsFollowStick]); one that keys the hands (letting go of the stick) keys the elbows too. A
 * [loop] clip wraps at its last key time; any other holds its last key and then eases out.
 */
class ClipData(val times: FloatArray, val keys: Array<Pose>, val keyed: BooleanArray, val loop: Boolean) {
    init {
        require(times.isNotEmpty() && times.size == keys.size) { "one time per key" }
        require(keyed.size == Skeleton.SLOTS) { "one keyed flag per slot" }
        for (k in 1 until times.size) require(times[k] > times[k - 1]) { "times must ascend" }
    }

    /** Seconds from the start to the last key. */
    val duration: Float get() = times[times.size - 1]

    /** Moves the stick and leaves the hands to it: the posing puts them back on the shaft after blending. */
    val handsFollowStick: Boolean =
        (keyed[STICK_TOP] || keyed[BLADE_HEEL] || keyed[BLADE_TOE]) && !keyed[HAND_L] && !keyed[HAND_R]
}

/** Clip data by [Clip]; a clip with no entry does not play. */
object ClipLibrary {
    private val clips = arrayOfNulls<ClipData>(Clip.entries.size)

    init {
        register(Clip.WRIST_FOLLOW, ShotClips.wristFollow())
        register(Clip.SLAP_FOLLOW, ShotClips.slapFollow())
        register(Clip.CELEBRATE_FIST, CelebrationClips.fist())
        register(Clip.CELEBRATE_SLIDE, CelebrationClips.slide())
        register(Clip.CELEBRATE_STICK, CelebrationClips.stick())
        register(Clip.HUG, CelebrationClips.hug())
        register(Clip.DEJECTED, CelebrationClips.dejected())
        register(Clip.GOALIE_SLUMP, CelebrationClips.goalieSlump())
        register(Clip.GOALIE_BUTTERFLY, GoalieClips.butterfly())
    }

    operator fun get(c: Clip): ClipData? = clips[c.ordinal]

    /** Sets (or with null removes) [c]'s data. */
    internal fun register(c: Clip, d: ClipData?) { clips[c.ordinal] = d }
}

/**
 * The shot follow-throughs, in the skater's frame (a forward, b left, z up), starting at release with
 * the blade through the puck on the ice ahead. They key the stick, the shoulders and the head: the
 * weight goes forward and the left shoulder (the blade's side in the wind-up) comes through; the
 * hands follow the stick ([ClipData.handsFollowStick]).
 */
internal object ShotClips {
    private val STICK_SLOTS = intArrayOf(STICK_TOP, BLADE_HEEL, BLADE_TOE)

    /** 0.35 s: the blade sweeps up and finishes about chest height, ahead. */
    fun wristFollow() = follow(
        floatArrayOf(0f, 0.1f, 0.2f, 0.35f),
        floatArrayOf(0.30f, 0.42f, 0.45f, 0.40f),       // lean
        floatArrayOf(0f, 0.03f, 0.06f, 0.07f),          // shoulder turn
        arrayOf(
            floatArrayOf(0.30f, 0.00f, 1.12f, 0.62f, 0.12f, 0.02f, 0.95f, 0.10f, 0.02f),
            floatArrayOf(0.30f, 0.02f, 1.08f, 1.20f, 0.00f, 0.35f, 1.50f, -0.12f, 0.42f),
            floatArrayOf(0.25f, 0.04f, 1.03f, 1.35f, -0.08f, 0.85f, 1.55f, -0.32f, 0.95f),
            floatArrayOf(0.20f, 0.05f, 1.00f, 1.30f, -0.12f, 1.20f, 1.42f, -0.40f, 1.30f),
        ),
    )

    /** 0.45 s: a bigger swing through, finishing with the blade high above the head. */
    fun slapFollow() = follow(
        floatArrayOf(0f, 0.12f, 0.25f, 0.45f),
        floatArrayOf(0.35f, 0.50f, 0.50f, 0.40f),
        floatArrayOf(0f, 0.05f, 0.08f, 0.09f),
        arrayOf(
            floatArrayOf(0.30f, 0.00f, 1.10f, 0.60f, 0.15f, 0.02f, 0.93f, 0.12f, 0.02f),
            floatArrayOf(0.32f, 0.00f, 1.10f, 1.30f, 0.00f, 0.50f, 1.60f, -0.12f, 0.60f),
            floatArrayOf(0.25f, 0.04f, 1.10f, 1.25f, -0.10f, 1.35f, 1.40f, -0.38f, 1.50f),
            floatArrayOf(0.15f, 0.05f, 1.15f, 0.95f, -0.15f, 2.00f, 1.02f, -0.45f, 2.12f),
        ),
    )

    /**
     * A clip keying [STICK_SLOTS] from [stick] (per key: a, b, z of the stick top, blade heel and blade toe)
     * and the upper body leaning forward by [lean] m with the left shoulder [turn] m ahead of the right.
     */
    private fun follow(times: FloatArray, lean: FloatArray, turn: FloatArray, stick: Array<FloatArray>): ClipData {
        val keyed = BooleanArray(Skeleton.SLOTS)
        for (i in STICK_SLOTS) keyed[i] = true
        for (i in intArrayOf(SH_C, SH_L, SH_R, HEAD, HEAD_FWD)) keyed[i] = true
        val keys = Array(times.size) { k ->
            Pose().also { p ->
                for (j in STICK_SLOTS.indices) p.set(STICK_SLOTS[j], stick[k][3 * j], stick[k][3 * j + 1], stick[k][3 * j + 2])
                val sh = lean[k]
                p.set(SH_C, sh, 0f, SHOULDER_Z)
                p.set(SH_L, sh + turn[k], SHOULDER_HALF, SHOULDER_Z - 0.02f)
                p.set(SH_R, sh - turn[k], -SHOULDER_HALF, SHOULDER_Z - 0.02f)
                p.set(HEAD, sh + 0.07f + 0.15f * sh, 0f, SHOULDER_Z + 0.25f)
                p.set(HEAD_FWD, p.a[HEAD] + 1f, 0f, p.z[HEAD])
            }
        }
        return ClipData(times, keys, keyed, loop = false)
    }

    private fun Pose.set(i: Int, a: Float, b: Float, z: Float) { this.a[i] = a; this.b[i] = b; this.z[i] = z }

    /** A skater's shoulder height and half-width when upright on the skates (FigurePainter's skating pose). */
    private const val SHOULDER_Z = 1.44f
    private const val SHOULDER_HALF = 0.2f
}

/** The goalie's clips in play, in his frame (a forward, out of the net; b left; z up). */
internal object GoalieClips {
    private val UPPER = intArrayOf(SH_C, SH_L, SH_R, HEAD, HEAD_FWD)
    private val HANDS_AND_STICK = intArrayOf(HAND_L, HAND_R, STICK_TOP, BLADE_HEEL, BLADE_TOE)
    private val LEGS = IntArray(11) { if (it < 5) HIP_L + it else if (it < 10) HIP_R + it - 5 else HIP_C }

    /**
     * The butterfly, held while a shot is live: down on both knees with the pads flared flat along the
     * ice to either side, the torso upright over the hips, glove and blocker out to the sides about
     * waist high, and the paddle flat on the ice across the five-hole. The drop is the clip easing in
     * ([BLEND_S]); the two keys are the same pose (a drop key and a hold key), looped. The painter puts
     * the elbows and the stick's middle back between the keyed hands and stick ends.
     */
    fun butterfly(): ClipData {
        val times = floatArrayOf(0.12f, 0.3f)
        val keyed = BooleanArray(Skeleton.SLOTS)
        for (g in arrayOf(UPPER, HANDS_AND_STICK, LEGS)) for (i in g) keyed[i] = true
        val keys = Array(times.size) { Pose().also { butterflyPose(it) } }
        return ClipData(times, keys, keyed, loop = true)
    }

    private fun butterflyPose(p: Pose) {
        val hz = 0.45f
        p.set(HIP_C, 0f, 0f, hz)
        for (side in 0..1) {
            val s = if (side == 0) 1f else -1f
            val hip = if (side == 0) HIP_L else HIP_R
            p.set(hip, 0f, s * 0.16f, hz)
            // The knee on the ice just ahead of and outside the hip; the shin and its pad laid flat out to
            // the side and a little back toward the net, so the camera across the rink sees the V. The
            // shin is about as long as the rigid pad reaches past the knee, so the skate meets its end.
            p.set(hip + KNEE, 0.14f, s * 0.36f, 0.08f)
            p.set(hip + ANKLE, -0.06f, s * 0.7f, 0.06f)
            // The skate on its inside edge at the end of the pad, toe out.
            p.set(hip + HEEL, -0.14f, s * 0.72f, 0.06f)
            p.set(hip + TOE, -0.08f, s * 0.95f, 0.04f)
        }
        // Upright: the shoulders a torso's length (as standing) over the hips.
        val shZ = hz + 0.48f
        p.set(SH_C, 0.06f, 0f, shZ)
        p.set(SH_L, 0.06f, 0.25f, shZ - 0.02f)
        p.set(SH_R, 0.06f, -0.25f, shZ - 0.02f)
        p.set(HEAD, 0.1f, 0f, shZ + 0.28f)
        p.set(HEAD_FWD, 0.1f + cos(-0.1f), 0f, shZ + 0.28f + sin(-0.1f))
        // Glove (left) and blocker (right, holding the stick) out to the sides.
        p.set(HAND_L, 0.3f, 0.62f, 0.72f)
        p.set(HAND_R, 0.38f, -0.5f, 0.66f)
        p.set(BLADE_HEEL, 0.6f, -0.16f, 0.02f)
        p.set(BLADE_TOE, 0.6f, 0.3f, 0.02f)
        // The stick's top just past the blocker hand, on the line up from the heel through the hand.
        val da = p.a[HAND_R] - p.a[BLADE_HEEL]; val db = p.b[HAND_R] - p.b[BLADE_HEEL]; val dz = p.z[HAND_R] - p.z[BLADE_HEEL]
        val k = STICK_PAST_HAND / sqrt(da * da + db * db + dz * dz)
        p.set(STICK_TOP, p.a[HAND_R] + k * da, p.b[HAND_R] + k * db, p.z[HAND_R] + k * dz)
    }

    private fun Pose.set(i: Int, a: Float, b: Float, z: Float) { this.a[i] = a; this.b[i] = b; this.z[i] = z }

    /** How far the stick's top sticks out past the blocker hand, m. */
    private const val STICK_PAST_HAND = 0.1f
}

/**
 * The goal celebration clips, in the figure's frame (a forward, b left, z up). The scorer's three and
 * the hug let go of the stick, so they key both hands, both elbows and the whole stick; the dejected
 * clip keys only the stick and leaves the hands to it. The scorer's clips run [CHEER_S] (the rest of
 * the goal pause after the glide) and end held; the others loop until the pause ends.
 */
internal object CelebrationClips {
    /** The goal pause after the scorer's glide: GOAL then CELEBRATE, less [CHEER_GLIDE_S] (3.4 s). */
    private const val CHEER_S = Tuning.GOAL_TIME + Tuning.CELEBRATE_TIME - CHEER_GLIDE_S

    private val UPPER = intArrayOf(SH_C, SH_L, SH_R, HEAD, HEAD_FWD)
    private val ARMS = intArrayOf(SH_L + ELBOW, SH_R + ELBOW, HAND_L, HAND_R)
    private val STICK = intArrayOf(STICK_TOP, STICK_MID, BLADE_HEEL, BLADE_TOE)
    private val LEGS = IntArray(11) { if (it < 5) HIP_L + it else if (it < 10) HIP_R + it - 5 else HIP_C }

    /**
     * A fist pump: the stick held low in the left hand, blade on the ice, the right fist punched up
     * over the helmet and pumped three times, then held high.
     */
    fun fist(): ClipData {
        val times = floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1.05f, 1.3f, CHEER_S)
        val up = booleanArrayOf(false, true, false, true, false, true, true)
        return clip(times, UPPER, ARMS, STICK) { k, p ->
            torso(p, lean = 0.1f, z = SHOULDER_Z)
            head(p, 0.18f, SHOULDER_Z + 0.26f, pitch = 0.2f)
            stickLow(p, 0.2f, 0.32f, 1.0f)
            if (up[k]) arm(p, SH_R, 0.15f, -0.32f, 1.72f, 0.2f, -0.28f, 2.05f)
            else arm(p, SH_R, 0.1f, -0.38f, 1.22f, 0.28f, -0.3f, 1.5f)
        }
    }

    /**
     * A knee slide: the hips drop and the right knee goes down on the ice, the shin trailing behind,
     * the left foot planted ahead; the stick in the left hand, the right fist pumping, then held up.
     */
    fun slide(): ClipData {
        val times = floatArrayOf(0f, 0.45f, 0.7f, 0.95f, 1.2f, 1.45f, CHEER_S)
        return clip(times, UPPER, ARMS, STICK, LEGS) { k, p ->
            if (k == 0) {
                standingLegs(p)
                torso(p, lean = 0.12f, z = SHOULDER_Z)
                head(p, 0.2f, SHOULDER_Z + 0.26f, pitch = 0.1f)
                stickLow(p, 0.2f, 0.32f, 1.0f)
                arm(p, SH_R, 0.1f, -0.38f, 1.22f, 0.28f, -0.3f, 1.5f)
            } else {
                kneelingLegs(p)
                val shZ = KNEEL_HIP_Z + 0.52f
                torso(p, lean = -0.02f, z = shZ)
                head(p, 0.03f, shZ + 0.26f, pitch = 0.3f)
                arm(p, SH_L, 0.08f, 0.32f, 0.86f, 0.25f, 0.35f, 0.75f)
                stickFrom(p, 0.2f, 0.36f, 0.85f, 1.1f, 0.5f)
                if (k % 2 == 1 || k == times.size - 1) arm(p, SH_R, 0.0f, -0.34f, 1.32f, 0.05f, -0.3f, 1.62f)
                else arm(p, SH_R, -0.05f, -0.34f, 0.82f, 0.18f, -0.3f, 0.95f)
            }
        }
    }

    /** The stick thrust up overhead in the right hand, blade to the sky, the left arm up too; pumped twice, then held. */
    fun stick(): ClipData {
        val times = floatArrayOf(0f, 0.3f, 0.6f, 0.9f, 1.2f, 1.5f, CHEER_S)
        return clip(times, UPPER, ARMS, STICK) { k, p ->
            val up = k % 2 == 1 || k == times.size - 1
            torso(p, lean = 0.05f, z = SHOULDER_Z)
            head(p, 0.12f, SHOULDER_Z + 0.26f, pitch = 0.35f)
            // Drawn back down, the hands come in front of the face and the stick drops with them.
            val da = if (up) 0f else 0.13f
            val dz = if (up) 0f else -0.25f
            if (up) {
                arm(p, SH_R, 0.1f, -0.34f, 1.72f, 0.15f, -0.3f, 2.02f)
                arm(p, SH_L, 0.12f, 0.34f, 1.7f, 0.2f, 0.32f, 2.0f)
            } else {
                arm(p, SH_R, 0.18f, -0.36f, 1.5f, 0.28f, -0.3f, 1.77f)
                arm(p, SH_L, 0.2f, 0.36f, 1.48f, 0.32f, 0.32f, 1.75f)
            }
            p.set(STICK_TOP, 0.13f + da, -0.3f, 1.82f + dz)
            p.set(BLADE_HEEL, 0.45f + da, -0.36f, 3.05f + dz)
            p.set(BLADE_TOE, 0.76f + da, -0.36f, 2.97f + dz)
            mid(p)
        }
    }

    /**
     * The group hug: leaning in toward the scorer ahead, both arms reaching round him, the head in
     * close; the stick hangs from the right hand. A gentle sway side to side, looped.
     */
    fun hug(): ClipData {
        val times = floatArrayOf(0f, 0.6f, 1.2f)
        return clip(times, UPPER, ARMS, STICK, loop = true) { k, p ->
            val sway = if (k == 1) -0.05f else 0.05f
            val shZ = SHOULDER_Z - 0.06f
            p.set(SH_C, 0.3f, sway, shZ)
            p.set(SH_L, 0.3f, sway + SHOULDER_HALF, shZ - 0.02f)
            p.set(SH_R, 0.3f, sway - SHOULDER_HALF, shZ - 0.02f)
            head(p, 0.45f, shZ + 0.22f, pitch = -0.25f, b = sway * 1.2f)
            arm(p, SH_L, 0.5f, sway + 0.42f, 1.36f, 0.78f, sway + 0.25f, 1.42f)
            arm(p, SH_R, 0.5f, sway - 0.42f, 1.36f, 0.78f, sway - 0.25f, 1.42f)
            p.set(STICK_TOP, 0.78f, sway - 0.25f, 1.45f)
            p.set(BLADE_HEEL, 0.45f, -0.55f, 0.25f)
            p.set(BLADE_TOE, 0.6f, -0.85f, 0.2f)
            mid(p)
        }
    }

    /**
     * The conceding skaters: shoulders slumped, head hanging, the stick resting with its blade flat on
     * the ice ahead (the hands stay on it). A slow breath, looped.
     */
    fun dejected(): ClipData {
        val times = floatArrayOf(0f, 1.2f, 2.4f)
        return clip(times, UPPER, intArrayOf(STICK_TOP, BLADE_HEEL, BLADE_TOE), loop = true) { k, p ->
            val breath = if (k == 1) 0.02f else 0f
            torso(p, lean = 0.3f, z = SHOULDER_Z - 0.08f + breath)
            head(p, 0.44f, SHOULDER_Z + 0.08f + breath, pitch = -0.8f)
            p.set(STICK_TOP, 0.3f, -0.05f, 1.15f + breath)
            p.set(BLADE_HEEL, 0.9f, 0.05f, 0.02f)
            p.set(BLADE_TOE, 1.24f, 0.08f, 0.02f)
        }
    }

    /**
     * The beaten goalie: shoulders dropped over the crouch, head down, glove and blocker hanging, the
     * paddle laid down flat on the ice ahead. The painter puts the stick's middle and the elbows back
     * between the keyed ends. A slow breath, looped.
     */
    fun goalieSlump(): ClipData {
        val times = floatArrayOf(0f, 1.2f, 2.4f)
        return clip(times, UPPER, intArrayOf(HAND_L, HAND_R, STICK_TOP, BLADE_HEEL, BLADE_TOE), loop = true) { k, p ->
            val breath = if (k == 1) 0.02f else 0f
            p.set(SH_C, 0.32f, 0f, 0.98f + breath)
            p.set(SH_L, 0.3f, 0.25f, 0.94f + breath)
            p.set(SH_R, 0.3f, -0.25f, 0.94f + breath)
            head(p, 0.42f, 1.2f + breath, pitch = -0.7f)
            p.set(HAND_L, 0.4f, 0.4f, 0.55f + breath)
            p.set(HAND_R, 0.45f, -0.38f, 0.45f)
            p.set(STICK_TOP, 0.45f, -0.38f, 0.5f)
            p.set(BLADE_HEEL, 1.05f, -0.25f, 0.02f)
            p.set(BLADE_TOE, 1.05f, 0.23f, 0.02f)
        }
    }

    // ---- building ---------------------------------------------------------------------------------

    /** A clip keying every slot in [groups], with key k's pose filled by [fill]. */
    private fun clip(times: FloatArray, vararg groups: IntArray, loop: Boolean = false, fill: (Int, Pose) -> Unit): ClipData {
        val keyed = BooleanArray(Skeleton.SLOTS)
        for (g in groups) for (i in g) keyed[i] = true
        return ClipData(times, Array(times.size) { k -> Pose().also { fill(k, it) } }, keyed, loop)
    }

    /** Shoulders square, [lean] m ahead of the hips, the centre at height [z]. */
    private fun torso(p: Pose, lean: Float, z: Float) {
        p.set(SH_C, lean, 0f, z)
        p.set(SH_L, lean, SHOULDER_HALF, z - 0.02f)
        p.set(SH_R, lean, -SHOULDER_HALF, z - 0.02f)
    }

    /** The head at (a, b, z), facing ahead tilted up by [pitch] radians (down when negative). */
    private fun head(p: Pose, a: Float, z: Float, pitch: Float, b: Float = 0f) {
        p.set(HEAD, a, b, z)
        p.set(HEAD_FWD, a + cos(pitch), b, z + sin(pitch))
    }

    /** The arm from shoulder [sh]: elbow at (ea, eb, ez), hand at (ha, hb, hz). */
    private fun arm(p: Pose, sh: Int, ea: Float, eb: Float, ez: Float, ha: Float, hb: Float, hz: Float) {
        p.set(sh + ELBOW, ea, eb, ez)
        p.set(if (sh == SH_L) HAND_L else HAND_R, ha, hb, hz)
    }

    /** The stick in the left hand at (ha, hb, hz), hanging down to its blade on the ice ahead. */
    private fun stickLow(p: Pose, ha: Float, hb: Float, hz: Float) {
        arm(p, SH_L, ha - 0.08f, hb - 0.02f, hz + 0.2f, ha, hb, hz)
        stickFrom(p, ha - 0.03f, hb + 0.01f, hz + 0.1f, ha + 0.75f, hb + 0.13f)
    }

    /** The stick from its top at (ta, tb, tz) down to the blade's heel on the ice at (ha, hb), the toe on ahead. */
    private fun stickFrom(p: Pose, ta: Float, tb: Float, tz: Float, ha: Float, hb: Float) {
        p.set(STICK_TOP, ta, tb, tz)
        p.set(BLADE_HEEL, ha, hb, 0.02f)
        p.set(BLADE_TOE, ha + 0.33f, hb + 0.03f, 0.02f)
        mid(p)
    }

    /** The stick's middle where the painter's grip puts it: 0.6 of the way from the top to the heel. */
    private fun mid(p: Pose) = p.set(STICK_MID,
        p.a[STICK_TOP] + 0.6f * (p.a[BLADE_HEEL] - p.a[STICK_TOP]),
        p.b[STICK_TOP] + 0.6f * (p.b[BLADE_HEEL] - p.b[STICK_TOP]),
        p.z[STICK_TOP] + 0.6f * (p.z[BLADE_HEEL] - p.z[STICK_TOP]))

    /** Upright on both skates, legs nearly straight. */
    private fun standingLegs(p: Pose) {
        val hz = 0.9f
        p.set(HIP_C, 0f, 0f, hz)
        for (side in 0..1) {
            val s = if (side == 0) 1f else -1f
            val hip = if (side == 0) HIP_L else HIP_R
            p.set(hip, 0f, s * 0.11f, hz)
            p.set(hip + KNEE, 0.14f, s * 0.15f, 0.5f)
            p.set(hip + ANKLE, 0.02f, s * 0.17f, 0.13f)
            p.set(hip + HEEL, -0.11f, s * 0.17f, 0.06f)
            p.set(hip + TOE, 0.21f, s * 0.17f, 0.04f)
        }
    }

    /** Down on the right knee, the shin flat behind and the toe on the ice; the left foot planted ahead. */
    private fun kneelingLegs(p: Pose) {
        val hz = KNEEL_HIP_Z
        p.set(HIP_C, 0f, 0f, hz)
        p.set(HIP_L, 0f, 0.11f, hz)
        p.set(HIP_L + KNEE, 0.43f, 0.16f, 0.52f)
        p.set(HIP_L + ANKLE, 0.45f, 0.18f, 0.12f)
        p.set(HIP_L + HEEL, 0.32f, 0.18f, 0.06f)
        p.set(HIP_L + TOE, 0.64f, 0.18f, 0.04f)
        p.set(HIP_R, 0f, -0.11f, hz)
        p.set(HIP_R + KNEE, 0.05f, -0.16f, 0.1f)
        p.set(HIP_R + ANKLE, -0.36f, -0.17f, 0.1f)
        p.set(HIP_R + HEEL, -0.37f, -0.17f, 0.22f)   // sole up, as when lying down
        p.set(HIP_R + TOE, -0.49f, -0.17f, 0.03f)
    }

    private fun Pose.set(i: Int, a: Float, b: Float, z: Float) { this.a[i] = a; this.b[i] = b; this.z[i] = z }

    /** A skater's shoulder height and half-width when upright on the skates (FigurePainter's skating pose). */
    private const val SHOULDER_Z = 1.44f
    private const val SHOULDER_HALF = 0.2f
    /** Hip height kneeling. */
    private const val KNEEL_HIP_Z = 0.55f
}
