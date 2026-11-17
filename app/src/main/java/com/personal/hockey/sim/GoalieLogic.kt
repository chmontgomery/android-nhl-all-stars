package com.personal.hockey.sim

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI_F = PI.toFloat()

/** x of the goal line [g] defends. HOME defends -GOAL_X; "inward" (out of the net) is [Side.dir]. */
private fun goalLineX(g: Goalie): Float = -g.side.dir * Rink.GOAL_X

/** Straight out from his goal line at the challenging depth, empty-handed, remembering the puck as where it is now. */
internal fun HockeySim.resetGoalie(g: Goalie) {
    g.x = goalLineX(g) + g.side.dir * Tuning.GOALIE_DEPTH_OUT
    g.y = 0f
    g.holding = false; g.beaten = false; g.carryBeaten = false
    g.seenX.fill(puck.x); g.seenY.fill(puck.y)
}

/**
 * Goalies track a stale puck position and save; then skaters are pushed off them. Saves only happen
 * in live play. A goalie holding the puck keeps it until the faceoff his catch led to.
 */
internal fun HockeySim.stepGoalies() {
    for (i in goalies.indices) {
        val g = goalies[i]
        g.saveFlash += Tuning.DT
        track(g)
        // Per goalie: a catch by the first stops play for the second.
        val live = phase == Phase.PLAY
        if (g.holding) continue   // holds it through the whistle; the faceoff takes it from him
        if (live && puck.carrier == null) {
            g.carryBeaten = false
            trySave(g)
        } else {
            g.beaten = false
            if (live) tryStrip(g) else g.carryBeaten = false
        }
    }
    pushSkatersOffGoalies()
}

/**
 * Records the puck and moves toward [goalieTarget] for the puck as it was [GoalieSkill.reactionS]
 * ago, at most [GoalieSkill.speed] m/s along the straight line to it. Draws no random numbers.
 */
private fun HockeySim.track(g: Goalie) {
    val n = g.seenX.size
    g.seenX[g.seenHead] = puck.x; g.seenY[g.seenHead] = puck.y
    g.seenHead = (g.seenHead + 1) % n
    if (g.holding) return
    val sx = g.seenX[g.seenHead]; val sy = g.seenY[g.seenHead]   // the oldest entry
    goalieTarget(goalLineX(g), g.side.dir, sx, sy, g.target)
    val dx = g.target[0] - g.x; val dy = g.target[1] - g.y
    val d = sqrt(dx * dx + dy * dy)
    val maxStep = g.skill.speed * Tuning.DT
    if (d <= maxStep) {
        g.x = g.target[0]; g.y = g.target[1]
    } else {
        g.x += dx * maxStep / d; g.y += dy * maxStep / d
    }
}

/**
 * Where a goalie defending the goal line at [lineX], facing [dir] out of the net, wants to stand
 * with the puck at ([puckX], [puckY]); writes (x, y) into [out]. In front of the goal line it is on
 * the ray from the goal-line centre to the puck, at most [Tuning.GOALIE_MAX_ANGLE] from straight
 * out, [Tuning.GOALIE_DEPTH_IN] out with the puck within [Tuning.GOALIE_CLOSE_R], easing linearly
 * to [Tuning.GOALIE_DEPTH_OUT] at [Tuning.GOALIE_CHALLENGE_R]. With the puck on or behind the goal
 * line, he seals the post on the puck's side.
 */
internal fun goalieTarget(lineX: Float, dir: Float, puckX: Float, puckY: Float, out: FloatArray) {
    val ahead = (puckX - lineX) * dir
    if (ahead <= 0f) {
        out[0] = lineX + dir * Tuning.GOALIE_POST_OUT
        out[1] = (if (puckY >= 0f) 1f else -1f) * (Rink.GOAL_HALF_W - Tuning.GOALIE_POST_IN)
        return
    }
    val r = sqrt(ahead * ahead + puckY * puckY)
    val k = ((r - Tuning.GOALIE_CLOSE_R) / (Tuning.GOALIE_CHALLENGE_R - Tuning.GOALIE_CLOSE_R)).coerceIn(0f, 1f)
    val depth = Tuning.GOALIE_DEPTH_IN + (Tuning.GOALIE_DEPTH_OUT - Tuning.GOALIE_DEPTH_IN) * k
    val a = atan2(puckY, ahead).coerceIn(-Tuning.GOALIE_MAX_ANGLE, Tuning.GOALIE_MAX_ANGLE)
    out[0] = lineX + dir * depth * cos(a)
    out[1] = depth * sin(a)
}

/**
 * A loose puck in front of the goal line heading for it, whose move this tick passes within
 * [Tuning.GOALIE_REACH], gets one save roll per approach. A save slower than [Tuning.COVER_SPEED] is
 * caught; a faster one rolls its outcome ([saveOutcome]).
 */
private fun HockeySim.trySave(g: Goalie) {
    val lineX = goalLineX(g)
    if ((puck.x - lineX) * g.side.dir <= 0f || puck.vx * g.side.dir >= 0f) { g.beaten = false; return }
    val ex = puck.vx * Tuning.DT; val ey = puck.vy * Tuning.DT
    val len2 = ex * ex + ey * ey
    val t = if (len2 > 1e-9f) (((g.x - puck.x) * ex + (g.y - puck.y) * ey) / len2).coerceIn(0f, 1f) else 0f
    val cx = puck.x + ex * t; val cy = puck.y + ey * t
    val dx = cx - g.x; val dy = cy - g.y
    if (dx * dx + dy * dy > Tuning.GOALIE_REACH * Tuning.GOALIE_REACH) { g.beaten = false; return }
    if (g.beaten) return

    val speed = sqrt(puck.vx * puck.vx + puck.vy * puck.vy)
    var p = g.skill.saveBase + 0.25f * (1f - speed / Tuning.SHOT_SPEED_MAX)
    if (puck.fromShot) {
        val tx = puck.x - puck.shotX; val ty = puck.y - puck.shotY
        p += 0.02f * sqrt(tx * tx + ty * ty).coerceAtMost(10f)
    }
    p = p.coerceIn(0.05f, 0.97f)
    if (random.nextFloat() >= p) { g.beaten = true; return }   // it goes through

    events.add(SimEvent.Save(g.side))
    g.saveFlash = 0f
    puck.passTarget = null
    puck.fromShot = false
    puck.x = cx; puck.y = cy
    if (speed < Tuning.COVER_SPEED) catchPuck(g) else saveOutcome(g, speed)
}

/**
 * An opponent carrying the puck within [Tuning.GOALIE_REACH] gets one save roll per approach: the
 * shot formula with the carrier's speed as the shot speed and no distance term. A save knocks the
 * puck off his stick and rolls its outcome as if saved at [Tuning.STRIP_SPEED]; with the carrier on
 * every lane out, a kick is nearly always caught instead. A miss leaves him the puck until it leaves
 * reach.
 */
private fun HockeySim.tryStrip(g: Goalie) {
    val c = puck.carrier ?: return
    if (c.side == g.side) { g.carryBeaten = false; return }
    val dx = puck.x - g.x; val dy = puck.y - g.y
    if (dx * dx + dy * dy > Tuning.GOALIE_REACH * Tuning.GOALIE_REACH) { g.carryBeaten = false; return }
    if (g.carryBeaten) return

    val speed = sqrt(c.vx * c.vx + c.vy * c.vy)
    val p = (g.skill.saveBase + 0.25f * (1f - speed / Tuning.SHOT_SPEED_MAX)).coerceIn(0.05f, 0.97f)
    if (random.nextFloat() >= p) { g.carryBeaten = true; return }

    knockLoose(0f, 0f, Tuning.PICKUP_LOCK)
    puck.passTarget = null
    events.add(SimEvent.Save(g.side))
    g.saveFlash = 0f
    saveOutcome(g, Tuning.STRIP_SPEED)
}

/**
 * Rolls what a save the goalie [g] made at [speed] becomes, with one draw against his
 * [GoalieSkill.catch] / [GoalieSkill.kick] split: caught, kicked to an open teammate (caught if
 * nobody is open), or a loose rebound off him. The puck is at the point of the save.
 */
private fun HockeySim.saveOutcome(g: Goalie, speed: Float) {
    val u = random.nextFloat()
    val skill = g.skill
    when {
        u < skill.catch -> catchPuck(g)
        u < skill.catch + skill.kick -> if (!kick(g, speed)) catchPuck(g)
        else -> {
            // Drawn after the outcome, so a catch or a kick takes one draw and a rebound two.
            val out = if (g.side.dir > 0f) 0f else PI_F
            val a = out + (random.nextFloat() * 2f - 1f) * Tuning.REBOUND_ANGLE
            val v = speed * Tuning.REBOUND_KEEP
            puck.vx = cos(a) * v; puck.vy = sin(a) * v
            puck.stillT = 0f
            g.beaten = true
        }
    }
}

/**
 * The goalie [g] holds the puck at his position and the whistle goes: play stops for
 * [Tuning.WHISTLE_TIME], then faces off on his zone's dot on the side of the catch (the puck's y
 * now, at the save).
 */
private fun HockeySim.catchPuck(g: Goalie) {
    whistleDotX = -g.side.dir * Rink.FACEOFF_X   // the goalie's own end
    whistleDotY = if (puck.y >= 0f) Rink.FACEOFF_Y else -Rink.FACEOFF_Y
    g.holding = true
    puck.x = g.x; puck.y = g.y; puck.vx = 0f; puck.vy = 0f; puck.stillT = 0f
    events.add(SimEvent.Cover)
    events.add(SimEvent.Whistle(g.side))
    phase = Phase.WHISTLE; phaseT = Tuning.WHISTLE_TIME
}

/**
 * Sends the puck from the point of the save straight to [openTeammate] at the rebound's speed
 * ([speed] · [Tuning.REBOUND_KEEP], at most [Tuning.PASS_SPEED]), leading him by the time it takes
 * to get there, and names him as the pass target. Returns false, leaving the puck alone, when
 * nobody is open or leading him would send it back toward the goal line.
 */
private fun HockeySim.kick(g: Goalie, speed: Float): Boolean {
    val t = openTeammate(g) ?: return false
    val v = minOf(speed * Tuning.REBOUND_KEEP, Tuning.PASS_SPEED)
    var dx = t.x - puck.x; var dy = t.y - puck.y
    val lead = sqrt(dx * dx + dy * dy) / v.coerceAtLeast(1e-3f)
    dx += t.vx * lead; dy += t.vy * lead
    if (dx * g.side.dir < Tuning.KICK_MIN_OUT) return false
    val a = atan2(dy, dx)
    puck.vx = cos(a) * v; puck.vy = sin(a) * v
    puck.stillT = 0f
    puck.passTarget = t
    puck.lastSide = g.side
    g.beaten = true   // don't save its own kick on the way out
    events.add(SimEvent.Pass(g.side, -1, t.id))
    return true
}

/**
 * The teammate on his feet, at least [Tuning.KICK_MIN_OUT] up ice from the goalie, whose line
 * from the goalie is furthest from any opponent, if that is at least [Tuning.KICK_LANE_CLEAR];
 * otherwise null.
 */
private fun HockeySim.openTeammate(g: Goalie): Skater? {
    val mates = skatersOf(g.side)
    val opps = skatersOf(g.side.other)
    var best: Skater? = null
    var bestClear = Tuning.KICK_LANE_CLEAR * Tuning.KICK_LANE_CLEAR
    for (i in mates.indices) {
        val m = mates[i]
        if (m.act == Act.DOWN) continue
        if ((m.x - g.x) * g.side.dir < Tuning.KICK_MIN_OUT) continue
        var clear = Float.MAX_VALUE
        for (j in opps.indices) clear = minOf(clear, segmentDist2(g.x, g.y, m.x, m.y, opps[j].x, opps[j].y))
        if (clear >= bestClear) { best = m; bestClear = clear }
    }
    return best
}

/** Squared distance from (px, py) to the segment (ax, ay)–(bx, by). */
private fun segmentDist2(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
    val ex = bx - ax; val ey = by - ay
    val len2 = ex * ex + ey * ey
    val t = if (len2 > 1e-6f) (((px - ax) * ex + (py - ay) * ey) / len2).coerceIn(0f, 1f) else 0f
    val dx = ax + ex * t - px; val dy = ay + ey * t - py
    return dx * dx + dy * dy
}

/** Keeps every skater's centre [Tuning.GOALIE_BODY_R] from each goalie, then out of the nets again. */
private fun HockeySim.pushSkatersOffGoalies() {
    val r = Tuning.GOALIE_BODY_R
    for (gi in goalies.indices) {
        val g = goalies[gi]
        for (i in skaters.indices) {
            val s = skaters[i]
            var nx = s.x - g.x; var ny = s.y - g.y
            val d2 = nx * nx + ny * ny
            if (d2 >= r * r) continue
            val d = sqrt(d2)
            if (d > 1e-6f) { nx /= d; ny /= d } else { nx = g.side.dir; ny = 0f }
            s.x = g.x + nx * r; s.y = g.y + ny * r
            val into = s.vx * nx + s.vy * ny
            if (into < 0f) { s.vx -= into * nx; s.vy -= into * ny }
            keepOutOfNets(s)
        }
    }
}
