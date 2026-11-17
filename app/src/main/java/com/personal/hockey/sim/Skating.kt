package com.personal.hockey.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TWO_PI = (2 * PI).toFloat()
private const val PI_F = PI.toFloat()

/** Skating control, integration, skater–skater separation, boards and nets, for one tick. */
internal fun HockeySim.stepSkaters() {
    val n = skaters.size
    val live = phase == Phase.PLAY
    for (i in 0 until n) skaters[i].stopping = false
    for (i in 0 until n) {
        val s = skaters[i]
        when {
            s.act == Act.DOWN -> slide(s)
            phase.afterGoal && celebrate(s) -> {}   // the scorer's teammates mob the scorer
            !live -> glide(s)   // outside live play inputs are ignored and everyone coasts
            s.act == Act.DEKE || s.act == Act.BODY -> {}   // velocity was set when the act began
            else -> control(s)
        }
    }
    for (i in 0 until n) {
        val s = skaters[i]
        s.x += s.vx * Tuning.DT
        s.y += s.vy * Tuning.DT
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
        s.stride += speed * Tuning.DT
        // A deke slides sideways with the puck held in front, so facing stays put, as when down.
        if (speed > Tuning.FACE_MIN_SPEED && s.act != Act.DOWN && s.act != Act.DEKE) s.facing = atan2(s.vy, s.vx)
    }
    separate()
    for (i in 0 until n) {
        val s = skaters[i]
        boards(s, clampOut)
        keepOutOfNets(s)
    }
}

/** Pushes [s] out of both nets' footprints. */
internal fun keepOutOfNets(s: Skater) {
    net(s, 1f)
    net(s, -1f)
}

/**
 * After a goal, steers a teammate of the scorer toward the scorer: accelerating at [Tuning.ACCEL] up to
 * [Tuning.CELEBRATE_SPEED], braking at [Tuning.COAST_DECEL] once within [Tuning.CELEBRATE_GAP].
 * Returns false (leaving [s] to coast) for the scorer, opponents, or when nobody is credited.
 */
private fun HockeySim.celebrate(s: Skater): Boolean {
    if (lastScorerId < 0) return false
    val scorer = skaters[lastScorerId]
    if (s === scorer || s.side != scorer.side) return false
    val dx = scorer.x - s.x; val dy = scorer.y - s.y
    val dist = sqrt(dx * dx + dy * dy)
    val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
    if (dist > Tuning.CELEBRATE_GAP) {
        val newSpeed = min(speed + Tuning.ACCEL * Tuning.DT, Tuning.CELEBRATE_SPEED)
        s.vx = dx / dist * newSpeed; s.vy = dy / dist * newSpeed
    } else if (speed > 0f) {
        val k = max(0f, speed - Tuning.COAST_DECEL * Tuning.DT) / speed
        s.vx *= k; s.vy *= k
    }
    return true
}

/** Stick centred: the skater coasts under [Tuning.GLIDE_DRAG] and stops dead when slow enough. */
private fun glide(s: Skater) {
    val k = max(0f, 1f - Tuning.GLIDE_DRAG * Tuning.DT)
    s.vx *= k; s.vy *= k
    if (s.vx * s.vx + s.vy * s.vy < Tuning.STOP_SPEED * Tuning.STOP_SPEED) { s.vx = 0f; s.vy = 0f }
}

/** Top-speed factor for carrying the puck. */
private fun HockeySim.carrySpeedFactor(s: Skater): Float =
    if (puck.carrier === s) Tuning.CARRY_SPEED else 1f

/** A downed skater ignores its stick and slides to a halt. */
private fun slide(s: Skater) {
    val k = max(0f, 1f - Tuning.DOWN_DRAG * Tuning.DT)
    s.vx *= k; s.vy *= k
}

/**
 * Steers the skater's velocity toward its stick. Direction turns at a limited rate (slower the
 * faster you go); speed eases toward a target that shrinks as the stick points away from the
 * current heading. A stick pushed past [Tuning.HOCKEY_STOP_STICK] and more than
 * [Tuning.HOCKEY_STOP_ANGLE] from the heading is a hockey stop: no turning, braking at full
 * [Tuning.ACCEL], then the skater sets off the new way from rest. Anything else carves: speed
 * above the target bleeds off gently ([Tuning.COAST_DECEL]) while the heading turns, so a sharp
 * cut-back or easing off the stick never slams on the brakes.
 */
private fun HockeySim.control(s: Skater) {
    val mx = s.input.moveX; val my = s.input.moveY
    val stick = sqrt(mx * mx + my * my)
    if (stick < Tuning.STICK_DEAD_ZONE) { glide(s); return }
    var top = Tuning.MAX_SPEED * s.speedScale * min(stick, 1f) * carrySpeedFactor(s)
    if (s.act == Act.WINDUP) top *= Tuning.WINDUP_SPEED
    if (s.act == Act.RECOVER) top *= Tuning.RECOVER_SPEED

    val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
    val want = atan2(my, mx)
    var heading = if (speed > 1e-3f) atan2(s.vy, s.vx) else want
    var diff = wrap(want - heading)
    val braking = abs(diff) > Tuning.HOCKEY_STOP_ANGLE && stick > Tuning.HOCKEY_STOP_STICK
    val maxTurn = Tuning.TURN_RATE * Tuning.DT * Tuning.MAX_SPEED / max(speed, Tuning.TURN_MIN_SPEED)
    s.stopping = braking
    if (!braking) {   // braking holds the heading; its target is 0 since cos(diff) < 0
        if (abs(diff) <= maxTurn) {
            heading = want; diff = 0f
        } else {
            val step = if (diff > 0f) maxTurn else -maxTurn
            heading += step; diff -= step
        }
    }
    val target = top * max(0f, cos(diff))
    val newSpeed = if (speed < target) min(speed + Tuning.ACCEL * Tuning.DT, target)
                   else max(speed - (if (braking) Tuning.ACCEL else Tuning.COAST_DECEL) * Tuning.DT, target)
    s.vx = cos(heading) * newSpeed
    s.vy = sin(heading) * newSpeed
}

/** Wraps an angle into (-PI, PI]. */
internal fun wrap(a: Float): Float {
    var r = a
    while (r > PI_F) r -= TWO_PI
    while (r <= -PI_F) r += TWO_PI
    return r
}

/** Pushes overlapping skaters apart, each moving half the overlap, neither into the boards. */
private fun HockeySim.separate() {
    val n = skaters.size
    val minD = 2f * Tuning.SKATER_R
    for (i in 0 until n) {
        val a = skaters[i]
        for (j in i + 1 until n) {
            val b = skaters[j]
            var dx = b.x - a.x; var dy = b.y - a.y
            val d2 = dx * dx + dy * dy
            if (d2 >= minD * minD) continue
            val d = sqrt(d2)
            if (d > 1e-6f) { dx /= d; dy /= d } else { dx = 1f; dy = 0f }
            val half = (minD - d) * 0.5f
            a.x -= dx * half; a.y -= dy * half
            b.x += dx * half; b.y += dy * half
            // A skater pinned at the boards can't give way: put it back and push the other the rest.
            val ax = a.x; val ay = a.y
            boards(a, clampOut)
            b.x += a.x - ax; b.y += a.y - ay
            val bx = b.x; val by = b.y
            boards(b, clampOut)
            a.x += b.x - bx; a.y += b.y - by
        }
    }
}

/** Keeps the skater inside the boards and removes its velocity into them. */
private fun boards(s: Skater, out: FloatArray) {
    if (!Rink.clamp(s.x, s.y, Tuning.SKATER_R, out)) return
    s.x = out[0]; s.y = out[1]
    val nx = out[2]; val ny = out[3]
    val into = s.vx * nx + s.vy * ny
    if (into < 0f) { s.vx -= into * nx; s.vy -= into * ny }
}

/**
 * Keeps the skater's circle out of the footprint of the net on side [gs] (+1 or -1): the box from
 * the goal line back NET_DEPTH, half-width GOAL_HALF_W, mouth included. Velocity into it is removed.
 */
private fun net(s: Skater, gs: Float) {
    val r = Tuning.SKATER_R
    val x0 = if (gs > 0f) Rink.GOAL_X else -Rink.GOAL_X - Rink.NET_DEPTH
    val x1 = x0 + Rink.NET_DEPTH
    val y1 = Rink.GOAL_HALF_W
    val cx = min(max(s.x, x0), x1); val cy = min(max(s.y, -y1), y1)
    var nx = s.x - cx; var ny = s.y - cy
    val d2 = nx * nx + ny * ny
    if (d2 >= r * r) return
    if (d2 > 1e-12f) {
        val d = sqrt(d2)
        nx /= d; ny /= d
        s.x = cx + nx * r; s.y = cy + ny * r
    } else {
        // Centre inside the box: leave through the nearest face.
        val toX0 = s.x - x0; val toX1 = x1 - s.x; val toY0 = s.y + y1; val toY1 = y1 - s.y
        val m = min(min(toX0, toX1), min(toY0, toY1))
        when (m) {
            toX0 -> { nx = -1f; ny = 0f; s.x = x0 - r }
            toX1 -> { nx = 1f; ny = 0f; s.x = x1 + r }
            toY0 -> { nx = 0f; ny = -1f; s.y = -y1 - r }
            else -> { nx = 0f; ny = 1f; s.y = y1 + r }
        }
    }
    val into = s.vx * nx + s.vy * ny
    if (into < 0f) { s.vx -= into * nx; s.vy -= into * ny }
}
