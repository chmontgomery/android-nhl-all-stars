package com.personal.hockey.render.anim

import com.personal.hockey.render.Effects
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import com.personal.hockey.sim.wrap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TWO_PI = (2.0 * Math.PI).toFloat()
/** Seconds the hockey-stop pose takes to ease fully in or out. */
const val STOP_BLEND_S = 0.08f
/** Seconds the glide pose takes to ease fully in or out. */
const val GLIDE_BLEND_S = 0.15f
/** Stick length below which a skater that is not stopping glides on both skates instead of striding. */
const val GLIDE_STICK = 0.3f
/** Seconds a clip takes to ease fully in or out. */
const val BLEND_S = 0.1f
/** Seconds the downswing takes from the wound-up stick to a follow-through's strike key. */
const val STRIKE_S = 0.08f
/** Shot power from which a shot is a slapshot (wrist below). */
const val SLAP_POWER = 0.5f
/** The wind-up, in seconds, that gives [SLAP_POWER]: where the wrist draw-back becomes the slap lift. */
const val SLAP_WINDUP = 0.475f
/** A shot within this many seconds of a pickup that ended a pass to the shooter is a one-timer. */
const val ONE_TIMER_S = 0.6f

/** Stickhandling sweep rate and half-width at [speed] m/s: 3 Hz, 0.4 m at 2 m/s or less, easing to 2 Hz, 0.3 m at 4 m/s or more. */
fun sweepHz(speed: Float) = SWEEP_SLOW_HZ + (SWEEP_FAST_HZ - SWEEP_SLOW_HZ) * sweepBlend(speed)
fun sweepHalfWidth(speed: Float) = SWEEP_SLOW_M + (SWEEP_FAST_M - SWEEP_SLOW_M) * sweepBlend(speed)
private fun sweepBlend(speed: Float) = ((speed - SWEEP_SLOW_SPEED) / (SWEEP_FAST_SPEED - SWEEP_SLOW_SPEED)).coerceIn(0f, 1f)
const val SWEEP_SLOW_SPEED = 2f; const val SWEEP_FAST_SPEED = 4f
const val SWEEP_SLOW_HZ = 3f; const val SWEEP_FAST_HZ = 2f
const val SWEEP_SLOW_M = 0.4f; const val SWEEP_FAST_M = 0.3f
/**
 * Lateral offset in metres (to the skater's left) of the stickhandling blade and the puck on it, for
 * [st] at [speed] m/s: the sweep, eased in and out by [AnimState.sweepW].
 */
fun sweepOffset(st: AnimState, speed: Float) = sweepHalfWidth(speed) * sin(st.sweepPhase) * st.sweepW
/** Sweep phase step per skater id, radians, so a team does not wave its sticks in unison. */
const val SWEEP_PHASE_PER_ID = 1.3f
/** Snow particles a shot throws off the blade, and their speed in m/s. */
const val SHOT_SPRAY_COUNT = 14
const val SHOT_SPRAY_SPEED = 4f
/** Snow a hockey stop throws per tick off the skates, and its speed in m/s. */
const val STOP_SPRAY_COUNT = 3
const val STOP_SPRAY_SPEED = 5f
/** A facing change faster than this (rad/s) is the sim placing a skater, not turning: no turn rate. */
const val TURN_RATE_JUMP = 25f
/** Seconds the scorer glides on after the goal before celebrating. */
const val CHEER_GLIDE_S = 0.6f
/** A hugger this close to the scorer, metres, joins the hug. */
const val HUG_RANGE = 2f
/** Snow the knee slide throws per tick, its speed in m/s, and the stretch of the clip (s) it sprays in. */
const val SLIDE_SPRAY_COUNT = 2
const val SLIDE_SPRAY_SPEED = 3f
const val SLIDE_SPRAY_FROM = 0.25f
const val SLIDE_SPRAY_TO = 1.6f
/**
 * Seconds a goalie stays down in the butterfly after the shot resolves (a save, a goal or a pickup).
 * A dump or clear ([longShot]) does not drop him.
 */
const val BUTTERFLY_HOLD_S = 0.5f
/** Seconds after the shot that dropped him that a goalie gets up from the butterfly whatever happened. */
const val BUTTERFLY_MAX_S = 2.0f

/**
 * One figure's render-side animation state. Mutated only by [AnimDirector], except [strikeFrom] and
 * [woundUp], which the painter records as it poses a skater winding up.
 */
class AnimState {
    /** The clip playing ([Clip.NONE]: none) and seconds since it started. */
    var clip = Clip.NONE
    var clipT = 0f
    /**
     * Seconds before the clip's first key: a shot's downswing, in which the clip's slots move from
     * [strikeFrom] to its first key (the strike). 0 for every other clip.
     */
    var lead = 0f
    /** How much of the clip shows over the procedural pose, 0..1; eases over [BLEND_S]. */
    var weight = 0f
    /** Sim seconds since this skater last picked up the puck. */
    var sincePickup = NEVER
    /** Whether that pickup ended a pass meant for this skater. */
    var pickupFromPass = false
    /** Whether the last shot was a slapshot (power ≥ [SLAP_POWER]). */
    var slap = false
    /** Whether the last shot was a one-timer. */
    var oneTimer = false
    /** This figure's part in the goal celebration; set on a goal, cleared once the goal pause ends. */
    var role = Role.NONE
    /** Sim seconds since [role] was given. */
    var roleT = 0f
    /** The scorer's celebration clip, waiting out the [CHEER_GLIDE_S] glide; [Clip.NONE] once started, and for everyone else. */
    var cheer = Clip.NONE
    /** Radians per second of facing change over the last tick, positive to the left. */
    var turnRate = 0f
    /** How far into the hockey-stop pose, 0..1; eased each tick in [STOP_BLEND_S]. */
    var stopW = 0f
    /**
     * How far into the glide, 0..1 (0 striding, 1 gliding on both skates); eased each tick in
     * [GLIDE_BLEND_S] toward 1 while the stick is under [GLIDE_STICK] and the skater is not stopping.
     */
    var glideW = 0f
    /** Radians of the stickhandling sweep; advanced each tick at a rate that follows speed, so it never jumps. */
    var sweepPhase = 0f
    /** How far the stickhandling sweep shows, 0..1: eased each tick in [BLEND_S] toward 1 while carrying with no act, else 0. */
    var sweepW = 0f
    /** How far into the deke's blade offset, 0..1; eased each tick in [BLEND_S]. */
    var dekeW = 0f

    /** The pose last drawn while winding up, in the figure's frame; the downswing starts from it. */
    val strikeFrom = Pose()
    /** Whether the figure was last posed winding up, so [strikeFrom] is the stick as it is now. */
    var woundUp = false

    /** Easing the clip out (it ended, or [Clip.NONE] was played over it). */
    internal var fading = false
    internal var lastFacing = 0f

    private companion object {
        const val NEVER = 99f
    }
}

/**
 * Picks and runs each figure's clips, flies shots ([flight]), and throws the snow that goes with them
 * into [effects] (none when null). [onTick] runs after every sim tick, while that tick's events are
 * still in sim.events; [advance] runs once per frame with the sim time it covered. Neither allocates.
 * Render thread only.
 */
class AnimDirector(private val effects: Effects? = null) {
    /** Index = skater id. */
    val skaters = Array(Side.entries.size * Position.entries.size) { i -> AnimState().apply { sweepPhase = i * SWEEP_PHASE_PER_ID } }
    /** Index = side.index. */
    val goalies = Array(Side.entries.size) { AnimState() }
    /** How high a shot is drawn above the ice; fed here, read by the renderer. */
    val flight = PuckFlight()

    /** Ticks heard by [onTick]; for tests. */
    internal var ticks = 0
        private set

    /** Index = side.index: sim seconds since that goalie dropped into the butterfly. */
    private val butterflyT = FloatArray(Side.entries.size)
    /**
     * Index = side.index: sim seconds left before that goalie gets up from the butterfly, counted
     * down once the shot has resolved; [SHOT_LIVE] until then.
     */
    private val butterflyHold = FloatArray(Side.entries.size)

    /** Target of the last pass still travelling (no pickup, shot or save since); -1 for none. */
    private var passTarget = NO_ID
    /** False until the first tick has recorded every facing, so the first turn rate is not a jump. */
    private var primed = false

    /** Reads this tick's sim state and events; [Tuning.DT] seconds have passed. */
    fun onTick(sim: HockeySim) {
        ticks++
        val sk = sim.skaters
        for (i in sk.indices) {
            val s = sk[i]
            val st = skaters[s.id]
            val turn = if (primed) wrap(s.facing - st.lastFacing) / Tuning.DT else 0f
            st.turnRate = if (abs(turn) > TURN_RATE_JUMP) 0f else turn   // a faceoff reset is not a turn
            st.lastFacing = s.facing
            st.sweepPhase += Tuning.DT * TWO_PI * sweepHz(sqrt(s.vx * s.vx + s.vy * s.vy))
            if (st.sweepPhase > TWO_PI) st.sweepPhase -= TWO_PI
            st.sincePickup += Tuning.DT
            val stopped = s.stopping && s.act == Act.NONE
            val dw = Tuning.DT / STOP_BLEND_S
            st.stopW = if (stopped) min(1f, st.stopW + dw) else max(0f, st.stopW - dw)
            if (stopped) stopSpray(s)
            val gw = Tuning.DT / GLIDE_BLEND_S
            val gliding = hypot(s.input.moveX, s.input.moveY) < GLIDE_STICK && !s.stopping
            st.glideW = if (gliding) min(1f, st.glideW + gw) else max(0f, st.glideW - gw)
            val bw = Tuning.DT / BLEND_S
            st.sweepW = if (sim.puck.carrier === s && s.act == Act.NONE) min(1f, st.sweepW + bw) else max(0f, st.sweepW - bw)
            st.dekeW = if (s.act == Act.DEKE) min(1f, st.dekeW + bw) else max(0f, st.dekeW - bw)
            if (s.act == Act.DOWN) play(st, Clip.NONE)   // no follow-through on someone lying on the ice
        }
        primed = true
        celebrate(sim)
        val events = sim.events
        for (k in events.indices) {
            when (val e = events[k]) {
                is SimEvent.Pass -> { passTarget = e.targetId; flight.land() }
                is SimEvent.Pickup -> {
                    flight.land()
                    shotResolved()
                    val st = skaters[e.skaterId]
                    st.sincePickup = 0f
                    st.pickupFromPass = passTarget == e.skaterId
                    passTarget = NO_ID
                }
                is SimEvent.Shot -> {
                    passTarget = NO_ID
                    val st = skaters[e.shooterId]
                    shot(st, e.power)
                    flight.onShot(sim, e.shooterId, e.side, st.slap || st.oneTimer)
                    if (!longShot(sim, e.side, sim.skaters[e.shooterId].x)) dropIntoButterfly(defendingGoalie(sim, e.side).side.index)
                    spray(sim, e.shooterId)
                }
                is SimEvent.Poke -> if (e.hit) passTarget = NO_ID
                is SimEvent.Goal -> {
                    passTarget = NO_ID
                    flight.land()
                    shotResolved()
                    goal(sim, e)
                }
                is SimEvent.Save -> { passTarget = NO_ID; flight.land(); shotResolved() }
                SimEvent.Cover -> { passTarget = NO_ID; flight.land() }
                SimEvent.FaceoffDrop -> {
                    passTarget = NO_ID
                    flight.clear()
                    for (i in goalies.indices) if (goalies[i].clip == Clip.GOALIE_BUTTERFLY) clear(goalies[i])
                }
                else -> {}
            }
        }
        if (sim.phase != Phase.PLAY && !sim.phase.afterGoal) flight.clear()
        flight.onTick(sim)
        butterflies(sim)
    }

    /** Drops goalie [i] (by side index) into the butterfly for a shot at his net, or keeps him there for another. */
    private fun dropIntoButterfly(i: Int) {
        keep(goalies[i], Clip.GOALIE_BUTTERFLY)
        butterflyT[i] = 0f
        butterflyHold[i] = SHOT_LIVE
    }

    /** The shot is over (saved, scored or picked up): every goalie down for it gets up [BUTTERFLY_HOLD_S] from now. */
    private fun shotResolved() {
        for (i in goalies.indices) if (butterflyHold[i] == SHOT_LIVE) butterflyHold[i] = BUTTERFLY_HOLD_S
    }

    /**
     * Gets goalies up from the butterfly: [BUTTERFLY_HOLD_S] after the shot resolves, or
     * [BUTTERFLY_MAX_S] after it was taken; the clip eases out. (A catch always stops play, so no
     * goalie holds the puck in live play.) Out of
     * live play he is up at once, except through the whistle for his own catch, where he stays down
     * on the puck until the faceoff, and in the goal pause, where the beaten goalie's slump takes over
     * from the butterfly as it eases out.
     */
    private fun butterflies(sim: HockeySim) {
        for (i in goalies.indices) {
            val st = goalies[i]
            if (st.clip != Clip.GOALIE_BUTTERFLY || st.fading) continue
            if (sim.phase == Phase.WHISTLE && sim.goalies[i].holding) continue   // down on the puck till the faceoff
            if (sim.phase != Phase.PLAY) {
                if (sim.phase.afterGoal) play(st, Clip.NONE) else clear(st)
                continue
            }
            butterflyT[i] += Tuning.DT
            if (butterflyHold[i] != SHOT_LIVE) butterflyHold[i] -= Tuning.DT
            if (butterflyT[i] >= BUTTERFLY_MAX_S - SLACK || (butterflyHold[i] != SHOT_LIVE && butterflyHold[i] <= SLACK)) play(st, Clip.NONE)
        }
    }

    /**
     * Gives everyone their part in the goal [e]: the scorer cheers (the clip cycling with the scoring
     * team's goal count), teammates mob him, the other side's skaters are dejected and the goalie
     * scored on is beaten.
     */
    private fun goal(sim: HockeySim, e: SimEvent.Goal) {
        val sk = sim.skaters
        for (i in sk.indices) {
            val s = sk[i]
            val st = skaters[s.id]
            st.roleT = 0f
            st.role = when {
                s.id == e.scorerId -> Role.SCORER
                s.side == e.scorer -> Role.HUGGER
                else -> Role.DEJECTED
            }
            st.cheer = if (st.role == Role.SCORER) CHEERS[Math.floorMod(sim.score[e.scorer.index] - 1, CHEERS.size)] else Clip.NONE
        }
        for (i in goalies.indices) {
            val st = goalies[i]
            st.roleT = 0f
            st.role = if (i == e.scorer.other.index) Role.BEATEN else Role.NONE
        }
    }

    /**
     * Runs the celebration roles through the goal pause (GOAL then CELEBRATE): the scorer's clip once
     * the glide is over, the hug once a teammate is within [HUG_RANGE], the dejected and beaten clips
     * throughout. Nobody lying on the ice starts a clip until he is up. Once the pause is over (a
     * faceoff or a break) every role is cleared and its clip eased out.
     */
    private fun celebrate(sim: HockeySim) {
        if (!sim.phase.afterGoal) { clearRoles(); return }
        val sk = sim.skaters
        val sid = sim.lastScorerId
        val scorer = if (sid >= 0 && sid < sk.size) sk[sid] else null
        for (i in sk.indices) {
            val s = sk[i]
            val st = skaters[s.id]
            if (st.role == Role.NONE) continue
            st.roleT += Tuning.DT
            if (s.act == Act.DOWN) continue
            when (st.role) {
                Role.SCORER -> if (st.cheer != Clip.NONE && st.roleT >= CHEER_GLIDE_S) { play(st, st.cheer); st.cheer = Clip.NONE }
                Role.HUGGER -> if (scorer != null && within(s, scorer, HUG_RANGE)) keep(st, Clip.HUG)
                Role.DEJECTED -> keep(st, Clip.DEJECTED)
                else -> {}
            }
            if (st.clip == Clip.CELEBRATE_SLIDE) slideSpray(s, st)
        }
        for (i in goalies.indices) {
            val st = goalies[i]
            if (st.role == Role.NONE) continue
            st.roleT += Tuning.DT
            if (st.role == Role.BEATEN) keep(st, Clip.GOALIE_SLUMP)
        }
    }

    private fun within(s: Skater, o: Skater, r: Float): Boolean {
        val dx = o.x - s.x; val dy = o.y - s.y
        return dx * dx + dy * dy <= r * r
    }

    /** Starts [clip] unless it is already playing; one that is easing out is taken up again where it was. */
    private fun keep(st: AnimState, clip: Clip) {
        if (st.clip != clip || st.fading) play(st, clip)
    }

    private fun clearRoles() {
        for (i in skaters.indices) clearRole(skaters[i])
        for (i in goalies.indices) clearRole(goalies[i])
    }

    private fun clearRole(st: AnimState) {
        if (st.role == Role.NONE) return
        st.role = Role.NONE; st.roleT = 0f; st.cheer = Clip.NONE
        if (isCelebration(st.clip)) play(st, Clip.NONE)
    }

    private fun isCelebration(c: Clip) = when (c) {
        Clip.CELEBRATE_FIST, Clip.CELEBRATE_SLIDE, Clip.CELEBRATE_STICK, Clip.HUG, Clip.DEJECTED, Clip.GOALIE_SLUMP -> true
        else -> false
    }

    /** Snow off the knee slide while the knee is down and sliding, thrown on the way the scorer is going. */
    private fun slideSpray(s: Skater, st: AnimState) {
        val pool = effects ?: return
        if (st.fading || st.weight <= 0f || st.clipT < SLIDE_SPRAY_FROM || st.clipT > SLIDE_SPRAY_TO) return
        val moving = s.vx * s.vx + s.vy * s.vy > 0.25f
        pool.spray(s.x, s.y, if (moving) s.vx else cos(s.facing), if (moving) s.vy else sin(s.facing), SLIDE_SPRAY_COUNT, SLIDE_SPRAY_SPEED)
    }

    /** Snow off a hockey stop's skates, thrown on along the way the skater was travelling. */
    private fun stopSpray(s: Skater) {
        val pool = effects ?: return
        if (s.vx * s.vx + s.vy * s.vy < 1f) return
        pool.spray(s.x, s.y, s.vx, s.vy, STOP_SPRAY_COUNT, STOP_SPRAY_SPEED)
    }

    private fun shot(st: AnimState, power: Float) {
        st.slap = power >= SLAP_POWER
        st.oneTimer = st.pickupFromPass && st.sincePickup <= ONE_TIMER_S
        st.pickupFromPass = false
        play(st, if (st.slap || st.oneTimer) Clip.SLAP_FOLLOW else Clip.WRIST_FOLLOW)
        if (st.woundUp && st.clip != Clip.NONE) {
            // The stick comes down from where it was drawn, fully in from the first frame: no snap.
            st.lead = STRIKE_S
            st.weight = 1f
        }
    }

    /**
     * Snow off the shooter's blade, which strikes at the carry point, thrown along the shot: the
     * puck's direction, or the shooter's facing if the puck is barely moving.
     */
    private fun spray(sim: HockeySim, shooterId: Int) {
        val pool = effects ?: return
        val s = sim.skaters[shooterId]
        val c = cos(s.facing); val sn = sin(s.facing)
        val puck = sim.puck
        val moving = puck.vx * puck.vx + puck.vy * puck.vy > 1f
        pool.spray(s.x + Tuning.CARRY_OFFSET * c, s.y + Tuning.CARRY_OFFSET * sn,
            if (moving) puck.vx else c, if (moving) puck.vy else sn, SHOT_SPRAY_COUNT, SHOT_SPRAY_SPEED)
    }

    /** Moves every clip and its blend weight on by [dt] seconds. */
    fun advance(dt: Float) {
        for (i in skaters.indices) step(skaters[i], dt)
        for (i in goalies.indices) step(goalies[i], dt)
        flight.advance(dt)
    }

    private fun step(st: AnimState, dt: Float) {
        if (st.clip == Clip.NONE) return
        val d = ClipLibrary[st.clip]
        if (d == null) { clear(st); return }
        st.clipT += dt
        if (!d.loop && st.clipT >= st.lead + d.duration) st.fading = true
        if (st.fading) {
            st.weight -= dt / BLEND_S
            if (st.weight <= 0f) clear(st)
        } else {
            st.weight = min(1f, st.weight + dt / BLEND_S)
        }
    }

    private fun clear(st: AnimState) {
        st.clip = Clip.NONE; st.clipT = 0f; st.lead = 0f; st.weight = 0f; st.fading = false
    }

    /**
     * Starts [clip] on [state] from its first key, easing in from the current weight. [Clip.NONE], or
     * a clip with no [ClipLibrary] entry, eases out whatever is playing instead.
     */
    fun play(state: AnimState, clip: Clip) {
        if (clip == Clip.NONE || ClipLibrary[clip] == null) {
            if (state.clip != Clip.NONE) state.fading = true
            return
        }
        state.clip = clip; state.clipT = 0f; state.lead = 0f; state.fading = false
    }

    private companion object {
        const val NO_ID = -1
        /** [butterflyHold] while the shot that dropped the goalie is still live. */
        const val SHOT_LIVE = -1f
        /** Half a tick of slack absorbs float drift in the butterfly countdowns. */
        const val SLACK = Tuning.DT * 0.5f
        /** The scorer's clips, by the scoring team's goal count: goal n plays CHEERS[(n − 1) % 3]. */
        val CHEERS = arrayOf(Clip.CELEBRATE_FIST, Clip.CELEBRATE_SLIDE, Clip.CELEBRATE_STICK)
    }
}

/**
 * Lays [state]'s clip over [base] into [out] (which may be [base]): each keyed slot moves toward the
 * clip's pose at the current time by the blend weight; the other slots are copied. Through a shot's
 * [AnimState.lead] the clip's pose is the downswing: from [AnimState.strikeFrom] to the first key.
 * Does not allocate.
 */
fun blendPose(state: AnimState, base: Pose, out: Pose) {
    val d = if (state.clip == Clip.NONE) null else ClipLibrary[state.clip]
    val w = state.weight
    if (d == null || w <= 0f) {
        if (out !== base) copy(base, out)
        return
    }
    val times = d.times
    val n = times.size
    var t = state.clipT - state.lead
    val p0: Pose; val p1: Pose; val f: Float
    if (t < 0f) {
        p0 = state.strikeFrom; p1 = d.keys[0]; f = state.clipT / state.lead
    } else {
        if (d.loop && d.duration > 0f) t %= d.duration
        t = t.coerceIn(times[0], d.duration)
        var k = 0
        while (k + 1 < n && times[k + 1] <= t) k++
        val k1 = if (k + 1 < n) k + 1 else k
        f = if (k1 == k) 0f else (t - times[k]) / (times[k1] - times[k])
        p0 = d.keys[k]; p1 = d.keys[k1]
    }
    val keyed = d.keyed
    for (i in 0 until Skeleton.SLOTS) {
        if (keyed[i]) {
            val ca = p0.a[i] + (p1.a[i] - p0.a[i]) * f
            val cb = p0.b[i] + (p1.b[i] - p0.b[i]) * f
            val cz = p0.z[i] + (p1.z[i] - p0.z[i]) * f
            out.a[i] = base.a[i] + (ca - base.a[i]) * w
            out.b[i] = base.b[i] + (cb - base.b[i]) * w
            out.z[i] = base.z[i] + (cz - base.z[i]) * w
        } else if (out !== base) {
            out.a[i] = base.a[i]; out.b[i] = base.b[i]; out.z[i] = base.z[i]
        }
    }
}

private fun copy(from: Pose, to: Pose) {
    from.a.copyInto(to.a); from.b.copyInto(to.b); from.z.copyInto(to.z)
}
