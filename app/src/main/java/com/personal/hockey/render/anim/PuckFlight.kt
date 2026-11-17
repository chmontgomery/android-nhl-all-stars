package com.personal.hockey.render.anim

import com.personal.hockey.sim.Goalie
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** The goalie in the net that a shot by [side] attacks: the other side's. */
internal fun defendingGoalie(sim: HockeySim, side: Side): Goalie = sim.goalies[side.other.index]

/** +1 when the net a shot by [side] attacks is at +x, else −1: the end its [defendingGoalie] stands in. */
internal fun attackDir(sim: HockeySim, side: Side): Float = if (defendingGoalie(sim, side).x >= 0f) 1f else -1f

/**
 * Whether a shot by [side] from [x] is a dump or a clear rather than a shot on net: farther than
 * [PuckFlight.FLAT_SHOT_M] (x distance) from the goal line it attacks. It stays on the ice and the
 * goalie stays up.
 */
internal fun longShot(sim: HockeySim, side: Side, x: Float): Boolean =
    abs(attackDir(sim, side) * Rink.GOAL_X - x) > PuckFlight.FLAT_SHOT_M

/**
 * How high a shot is drawn above the ice. Drawing only: the sim stays 2D. A shot pops off the blade
 * at [LAUNCH_Z] and rises to its peak ([SLAP_PEAK] or [WRIST_PEAK]) over the first [RISE] of the way
 * from the shooter to the goal line it attacks, then holds there until something puts it down: a
 * save, a pickup, a rebound away from the net, a deflection (a sudden loss of speed or turn), the
 * puck slowing or going well past the goal line. It then comes down over [LAND_S]. A shot from
 * farther than [FLAT_SHOT_M] out is a dump or a clear and stays on the ice. Fed by [AnimDirector];
 * never allocates.
 */
class PuckFlight {
    /** Metres above the ice the puck is drawn at; ≥ 0. */
    var height = 0f
        internal set

    private var state = GROUNDED
    private var peak = 0f
    private var startX = 0f
    private var goalX = 0f
    /** +1 when the target goal line is at +x, else −1. */
    private var dir = 1f
    private var landFrom = 0f
    private var landT = 0f
    /** The puck's velocity at the last tick heard, for spotting a deflection. */
    private var lastVx = 0f
    private var lastVy = 0f

    /**
     * Hears a shot by [shooterId] of [side] (a [slap] flies higher): the shooter's x and the goal line
     * of the net his side attacks, the one [side]'s opposing goalie stands in front of. Farther than
     * [FLAT_SHOT_M] from that goal line, it does not lift.
     */
    fun onShot(sim: HockeySim, shooterId: Int, side: Side, slap: Boolean) {
        val x = sim.skaters[shooterId].x
        if (longShot(sim, side, x)) return
        val d = attackDir(sim, side)
        startX = x
        dir = d
        goalX = d * Rink.GOAL_X
        peak = if (slap) SLAP_PEAK else WRIST_PEAK
        lastVx = sim.puck.vx; lastVy = sim.puck.vy
        state = FLYING
        if (height < LAUNCH_Z) height = LAUNCH_Z
    }

    /** Starts the descent to the ice over [LAND_S] from wherever the puck is now; nothing when it is not flying. */
    fun land() {
        if (state != FLYING) return
        state = LANDING
        landFrom = height
        landT = 0f
        if (height <= 0f) { state = GROUNDED; height = 0f }
    }

    /** While flying: the height for how far toward the goal line the puck has come, or a landing. */
    fun onTick(sim: HockeySim) {
        if (state != FLYING) return
        val puck = sim.puck
        val vx = puck.vx; val vy = puck.vy
        val speed2 = vx * vx + vy * vy
        val deflected = deflected(vx, vy, speed2)
        lastVx = vx; lastVy = vy
        if (deflected || sim.puckHeld() || vx * dir < 0f || speed2 < LAND_SPEED * LAND_SPEED ||
            (puck.x - goalX) * dir > PAST_GOAL) {
            land()
            return
        }
        val span = (goalX - startX) * dir
        val progress = if (span > MIN_SPAN) ((puck.x - startX) * dir / span).coerceIn(0f, 1f) else 1f
        height = LAUNCH_Z + (peak - LAUNCH_Z) * smoothstep(progress / RISE)
    }

    /**
     * Whether the puck's velocity (vx, vy), [speed2] its squared speed, changed since the last tick
     * like a deflection: its speed fell by more than [DEFLECT_LOSS] or it turned more than [DEFLECT_TURN].
     */
    private fun deflected(vx: Float, vy: Float, speed2: Float): Boolean {
        val last2 = lastVx * lastVx + lastVy * lastVy
        if (last2 <= 0f) return false
        val keep = 1f - DEFLECT_LOSS
        if (speed2 < keep * keep * last2) return true
        // cos of the turn = dot / (|v| |last|); compared squared to stay off sqrt.
        val dot = vx * lastVx + vy * lastVy
        return dot < 0f || dot * dot < COS_TURN * COS_TURN * speed2 * last2
    }

    /** Moves a descent on by [dt] seconds. */
    fun advance(dt: Float) {
        if (state != LANDING) return
        landT += dt
        if (landT >= LAND_S) {
            height = 0f; state = GROUNDED
        } else {
            height = max(0f, landFrom * (1f - smoothstep(landT / LAND_S)))
        }
    }

    /** Back on the ice at once. */
    fun clear() {
        height = 0f; state = GROUNDED; landT = 0f
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    companion object {
        /** Peak height, m, of a slapshot (or one-timer) and of a wrist shot. */
        const val SLAP_PEAK = 1.1f
        const val WRIST_PEAK = 0.5f
        /** Height the puck leaves the blade at, m. */
        const val LAUNCH_Z = 0.05f
        /** Seconds a landing takes. */
        const val LAND_S = 0.15f
        /** Slower than this, m/s, a shot puts down. */
        const val LAND_SPEED = 8f
        /** Farther than this past the goal line, m, a shot puts down. */
        const val PAST_GOAL = 1.5f
        /** The fraction of the way to the goal line over which a shot climbs to its peak. */
        const val RISE = 0.35f
        /** A shooter closer than this to the goal line, m, is treated as already there. */
        const val MIN_SPAN = 1e-3f
        /** A shot from farther than this (x distance, m) from the goal line it attacks stays on the ice. */
        const val FLAT_SHOT_M = 20f
        /** In one tick, a loss of more than this fraction of the speed, or a turn of more than this many radians, is a deflection. */
        const val DEFLECT_LOSS = 0.25f
        const val DEFLECT_TURN = 0.35f
        private val COS_TURN = cos(DEFLECT_TURN)

        private const val GROUNDED = 0
        private const val FLYING = 1
        private const val LANDING = 2
    }
}
