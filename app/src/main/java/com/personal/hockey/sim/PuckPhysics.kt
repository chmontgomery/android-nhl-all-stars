package com.personal.hockey.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Carried puck follows its stick; loose puck slides, bounces, gets picked up or deflected. */
internal fun HockeySim.stepPuck() {
    if (puckHeld()) { puck.stillT = 0f; return }   // covered: it sits hidden at the goalie
    val carrier = puck.carrier
    if (carrier != null) {
        placeOnStick(carrier)
        puck.lastSide = carrier.side
        return
    }

    val k = max(0f, 1f - Tuning.PUCK_DRAG * Tuning.DT)
    puck.vx *= k; puck.vy *= k
    // Two half-steps keep each move short; the net walls are swept, so they hold at any speed.
    // After a goal the mesh swallows the puck instead of bouncing it, so it stays in the net
    // for the whole banner.
    val netBounce = if (phase.afterGoal) 0f else Tuning.BOARD_BOUNCE
    val h = Tuning.DT * 0.5f
    repeat(2) {
        val px = puck.x; val py = puck.y
        puck.x += puck.vx * h; puck.y += puck.vy * h
        boards()
        netWalls(puck, px, py, netBounce)
    }
    if (phase != Phase.PLAY) return   // outside live play the puck only slides
    pickup()
    stuckGuard()
}

/**
 * Puts the puck [Tuning.CARRY_OFFSET] ahead of [s] along its facing, inside the boards, and moving
 * with [s]. The reach from [s]'s centre is swept against the net walls, so the puck is never on
 * the other side of a net wall from its carrier.
 */
internal fun HockeySim.placeOnStick(s: Skater) {
    puck.x = s.x + Tuning.CARRY_OFFSET * cos(s.facing)
    puck.y = s.y + Tuning.CARRY_OFFSET * sin(s.facing)
    if (Rink.clamp(puck.x, puck.y, Tuning.PUCK_R, clampOut)) {
        puck.x = clampOut[0]; puck.y = clampOut[1]
    }
    netWalls(puck, s.x, s.y, Tuning.BOARD_BOUNCE)
    puck.vx = s.vx; puck.vy = s.vy
}

private fun HockeySim.boards() {
    if (!Rink.clamp(puck.x, puck.y, Tuning.PUCK_R, clampOut)) return
    puck.x = clampOut[0]; puck.y = clampOut[1]
    val nx = clampOut[2]; val ny = clampOut[3]
    val into = puck.vx * nx + puck.vy * ny
    if (into >= 0f) return
    puck.vx -= (1f + Tuning.BOARD_BOUNCE) * into * nx
    puck.vy -= (1f + Tuning.BOARD_BOUNCE) * into * ny
    if (-into > Tuning.BOARDS_EVENT_SPEED) events.add(SimEvent.Boards)
}

/**
 * Each net is a back wall at |x| = GOAL_X + NET_DEPTH and two side walls at |y| = GOAL_HALF_W,
 * solid from both faces; the mouth on the goal line is open. The walls are tested against the
 * whole move from (px, py), so no speed can carry the puck through one.
 */
private fun netWalls(p: Puck, px: Float, py: Float, bounce: Float) {
    val back = Rink.GOAL_X + Rink.NET_DEPTH
    val w = Rink.GOAL_HALF_W
    for (gs in NET_SIDES) {
        wallX(p, px, py, gs * back, -w, w, bounce)
        val x0 = if (gs > 0f) Rink.GOAL_X else -back
        val x1 = if (gs > 0f) back else -Rink.GOAL_X
        wallY(p, px, py, w, x0, x1, bounce)
        wallY(p, px, py, -w, x0, x1, bounce)
    }
}

private val NET_SIDES = floatArrayOf(-1f, 1f)

/** Thin wall along x = wx for y in [y0, y1]: stops the puck on whichever face it started, keeping [bounce] of its speed into the wall. */
private fun wallX(p: Puck, px: Float, py: Float, wx: Float, y0: Float, y1: Float, bounce: Float) {
    val r = Tuning.PUCK_R
    val move = p.x - px
    val side = if (px > wx) 1f else if (px < wx) -1f else -sign(move)
    if (side == 0f || move * side >= 0f) return   // not moving toward the wall
    val a = (px - wx) * side; val b = (p.x - wx) * side
    if (b >= r) return
    val t = if (a <= r) 0f else (a - r) / (a - b)
    val yc = py + (p.y - py) * t
    if (yc < y0 - r || yc > y1 + r) return
    p.x = wx + side * r
    if (p.vx * side < 0f) p.vx = -p.vx * bounce
}

/** Thin wall along y = wy for x in [x0, x1]; mirror of [wallX]. */
private fun wallY(p: Puck, px: Float, py: Float, wy: Float, x0: Float, x1: Float, bounce: Float) {
    val r = Tuning.PUCK_R
    val move = p.y - py
    val side = if (py > wy) 1f else if (py < wy) -1f else -sign(move)
    if (side == 0f || move * side >= 0f) return
    val a = (py - wy) * side; val b = (p.y - wy) * side
    if (b >= r) return
    val t = if (a <= r) 0f else (a - r) / (a - b)
    val xc = px + (p.x - px) * t
    if (xc < x0 - r || xc > x1 + r) return
    p.y = wy + side * r
    if (p.vy * side < 0f) p.vy = -p.vy * bounce
}

private fun sign(v: Float): Float = if (v > 0f) 1f else if (v < 0f) -1f else 0f

private fun eligible(s: Skater): Boolean =
    s.act != Act.DOWN && s.act != Act.RECOVER && s.pickupLock <= 0f && s.tiedUpT <= 0f

private fun HockeySim.inReach(s: Skater): Boolean {
    val dx = s.x - puck.x; val dy = s.y - puck.y
    return dx * dx + dy * dy <= Tuning.STICK_REACH * Tuning.STICK_REACH
}

/** The nearest eligible skater within [reach] of the puck, or null. */
private fun HockeySim.nearestEligible(reach: Float): Skater? {
    var best: Skater? = null
    var bestD2 = reach * reach
    for (i in skaters.indices) {
        val s = skaters[i]
        if (!eligible(s)) continue
        val dx = s.x - puck.x; val dy = s.y - puck.y
        val d2 = dx * dx + dy * dy
        if (d2 <= bestD2) { best = s; bestD2 = d2 }
    }
    return best
}

/** The nearest eligible skater in reach catches a slow enough puck; a faster one glances off. */
private fun HockeySim.pickup() {
    val s = nearestEligible(Tuning.STICK_REACH) ?: return
    val speed = sqrt(puck.vx * puck.vx + puck.vy * puck.vy)
    if (speed < Tuning.CATCH_SPEED) {
        giveTo(s)   // nearest wins, so a skater in the lane intercepts a slow pass
        return
    }
    val target = puck.passTarget
    if (target != null && speed < Tuning.PASS_CATCH_SPEED && eligible(target) && inReach(target)) {
        giveTo(target)
        return
    }
    val turn = (random.nextFloat() * 2f - 1f) * Tuning.DEFLECT_ANGLE
    val c = cos(turn) * Tuning.DEFLECT_KEEP; val sn = sin(turn) * Tuning.DEFLECT_KEEP
    val vx = puck.vx
    puck.vx = vx * c - puck.vy * sn
    puck.vy = vx * sn + puck.vy * c
    puck.passTarget = null
    s.pickupLock = Tuning.DEFLECT_LOCK
    lastTouch[s.side.index] = s.id
}

/**
 * A loose puck sitting still with nobody near for [Tuning.STUCK_TIME] gets nudged toward centre
 * ice. Behind a net the straight line is blocked by the back wall, so it goes round the side first;
 * inside a net it goes straight out through the mouth.
 */
private fun HockeySim.stuckGuard() {
    if (puck.carrier != null) return
    val speed2 = puck.vx * puck.vx + puck.vy * puck.vy
    if (speed2 >= Tuning.STUCK_SPEED * Tuning.STUCK_SPEED) { puck.stillT = 0f; return }
    puck.stillT += Tuning.DT
    if (puck.stillT < Tuning.STUCK_TIME || nearestEligible(Tuning.STUCK_CLEAR_R) != null) return
    var tx = 0f; var ty = 0f
    if (abs(puck.x) > Rink.GOAL_X && abs(puck.x) < Rink.GOAL_X + Rink.NET_DEPTH && abs(puck.y) < Rink.GOAL_HALF_W) {
        // Inside a net: out through the mouth, the only open face.
        tx = if (puck.x > 0f) Rink.GOAL_X - 2f else 2f - Rink.GOAL_X
        ty = puck.y
    } else if (abs(puck.x) > Rink.GOAL_X && abs(puck.y) < Rink.GOAL_HALF_W + BEHIND_NET_MARGIN) {
        tx = if (puck.x > 0f) Rink.GOAL_X else -Rink.GOAL_X
        ty = (if (puck.y < 0f) -1f else 1f) * (Rink.GOAL_HALF_W + 2f)
    }
    val dx = tx - puck.x; val dy = ty - puck.y
    val d = sqrt(dx * dx + dy * dy)
    if (d > 1e-3f) {
        puck.vx = dx / d * Tuning.STUCK_PUSH
        puck.vy = dy / d * Tuning.STUCK_PUSH
    } else {
        puck.vx = Tuning.STUCK_PUSH; puck.vy = 0f
    }
    puck.stillT = 0f
}

/** Beyond GOAL_HALF_W + this, a straight push to centre ice clears the back of the net. */
private const val BEHIND_NET_MARGIN = 0.5f
