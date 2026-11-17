package com.personal.hockey.ai

import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Drives one side's skaters through their [com.personal.hockey.sim.SkaterInput]s, like a player
 * would: it sees positions, never anyone's input. It reacts to the puck as it was
 * [Difficulty.reactionS] ago and makes fresh decisions only every [Difficulty.reactionS]; between
 * decisions each skater carries out the last one (steering to a point, holding a wind-up, a press).
 * A skater the human has just let go of decides at once instead of waiting.
 *
 * After a faceoff in its own zone, for [DZONE_TIME] or until it has the puck, the team defends
 * goal-side, man-to-man: each skater but the centre takes an opponent (a greedy nearest match at
 * the drop, within [MARK_REACH]) and stands between him and the net, [MARK_OFF] off him; one left
 * without a man holds the net front, and the centre covers the slot. The skater nearest the puck
 * still chases it, loose or carried, as in open play. If one of our defencemen gets the puck in
 * that window, he moves it at once ([breakout]): a pass up ice to the nearer open winger, else to
 * his partner, else a carry straight away from the nearest opponent.
 *
 * After any draw in its own zone or the neutral zone (centre ice included), if our centre is the
 * first to have the puck, he moves it straight back at once ([centreBreakout]): a pass to the
 * nearer defenceman with an open lane, else a carry straight away from the nearest opponent.
 * Neither breakout draws a random number.
 */
class TeamAi(val side: Side, val difficulty: Difficulty, private val random: Random) {
    // What the team saw, oldest first at [head]: the puck reactionS ago is what it reacts to.
    private val histLen = Math.round(difficulty.reactionS / Tuning.DT).coerceAtLeast(0) + 1
    private val histX = FloatArray(histLen); private val histY = FloatArray(histLen)
    private val histVx = FloatArray(histLen); private val histVy = FloatArray(histLen)
    private val histPoss = IntArray(histLen)      // Side.index of the carrier's team, +2 if its goalie holds it; -1 loose
    private var head = 0
    private var primed = false
    private var seenX = 0f; private var seenY = 0f; private var seenVx = 0f; private var seenVy = 0f
    private var seenPoss: Side? = null
    private var seenHeld = false

    private var thinkT = 0f
    private var lastSkipId = -1   // the skater the human drove last tick

    // Per skater, indexed by Position.ordinal: the decision being carried out.
    private val mode = IntArray(5)
    private val tx = FloatArray(5); private val ty = FloatArray(5)   // MOVE/CHASE point, or CARRY/DEKE direction
    private val shootSign = FloatArray(5)
    private val held = IntArray(5)                // buttons set last tick, so presses are fresh edges

    private val scratch = FloatArray(4)

    // The defensive-zone plan: seconds left, the faceoff phase last tick, and each skater's mark
    // (the opponent's Position.ordinal, -1 none).
    private var dzoneT = 0f
    private var breakoutT = 0f   // seconds left in which a defenceman taking the puck breaks out at once
    private var cBreakoutT = 0f  // seconds left in which our centre collecting the draw moves it straight back
    private var wasFaceoff = false
    private val markOf = IntArray(5) { -1 }
    private val oppTaken = BooleanArray(5)

    /** Writes input for every skater on [side] except skipId (the human's skater, or -1). */
    fun think(sim: HockeySim, skipId: Int) {
        see(sim)
        zonePlan(sim, skipId)
        thinkT -= Tuning.DT
        val mates = sim.skatersOf(side)
        if (thinkT <= Tuning.DT * 0.5f) {
            thinkT += difficulty.reactionS
            if (thinkT <= 0f) thinkT = difficulty.reactionS
            decide(sim, skipId)
        } else if (lastSkipId != skipId && lastSkipId >= 0 && sim.skaters[lastSkipId].side == side) {
            // The human just let go of this skater: its last decision is from before he took it.
            val i = sim.skaters[lastSkipId].pos.ordinal
            decideFor(sim, mates[i], i, chooseChaser(sim, skipId))
        }
        lastSkipId = skipId
        breakoutCheck(sim, skipId)
        for (i in mates.indices) {
            val s = mates[i]
            if (s.id == skipId) { held[i] = 0; continue }
            act(sim, s, i)
        }
    }

    private fun see(sim: HockeySim) {
        val p = sim.puck
        val c = p.carrier
        val poss = when {
            c != null -> c.side.index
            sim.goalies[0].holding -> 2
            sim.goalies[1].holding -> 3
            else -> -1
        }
        if (!primed) {
            histX.fill(p.x); histY.fill(p.y); histVx.fill(p.vx); histVy.fill(p.vy); histPoss.fill(poss)
            primed = true
        }
        histX[head] = p.x; histY[head] = p.y; histVx[head] = p.vx; histVy[head] = p.vy; histPoss[head] = poss
        head = (head + 1) % histLen
        seenX = histX[head]; seenY = histY[head]; seenVx = histVx[head]; seenVy = histVy[head]
        val sp = histPoss[head]
        seenPoss = if (sp < 0) null else Side.entries[sp % 2]
        seenHeld = sp >= 2
    }

    // --- Decisions, every reactionS ---

    private fun decide(sim: HockeySim, skipId: Int) {
        val mates = sim.skatersOf(side)
        val chaser = chooseChaser(sim, skipId)
        for (i in mates.indices) {
            val s = mates[i]
            if (s.id != skipId) decideFor(sim, s, i, chaser)
        }
    }

    /** One skater's decision; [chaser] is the index from [chooseChaser]. */
    private fun decideFor(sim: HockeySim, s: Skater, i: Int, chaser: Int) {
        when {
            sim.puck.carrier === s -> decideCarrier(sim, s, i)
            i == chaser -> {
                mode[i] = CHASE
                tx[i] = seenX + seenVx * LEAD_S; ty[i] = seenY + seenVy * LEAD_S
            }
            dzoneT > 0f -> defendZone(sim, s, i)
            else -> {
                mode[i] = MOVE
                Formation.spot(side, s.pos, seenX, seenY, seenPoss, scratch)
                keepOffPuck(scratch)
                holdOnside(s, scratch)
                tx[i] = scratch[0]; ty[i] = scratch[1]
            }
        }
    }

    /**
     * Index of the skater that goes for the puck: the nearest to it when it is loose or an opponent
     * carries it; nobody when we have it, a goalie holds it, or the human is within
     * [HUMAN_CLAIM] of it.
     */
    private fun chooseChaser(sim: HockeySim, skipId: Int): Int {
        if (seenPoss == side || seenHeld) return -1
        if (skipId >= 0) {
            val h = sim.skaters[skipId]
            if (dist(h.x, h.y, seenX, seenY) < HUMAN_CLAIM) return -1
        }
        val mates = sim.skatersOf(side)
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in mates.indices) {
            val s = mates[i]
            if (s.id == skipId || s.act == Act.DOWN) continue
            val d = dist(s.x, s.y, seenX, seenY)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // --- The defensive-zone plan ---

    /**
     * Starts the plan, marks assigned, on the tick a faceoff in our own zone is dropped; runs its
     * clock down and ends it once we have the puck or play stops.
     */
    private fun zonePlan(sim: HockeySim, skipId: Int) {
        val live = sim.phase == Phase.PLAY
        if (live && wasFaceoff && sim.puck.x * side.dir < Rink.BLUE_X) cBreakoutT = DZONE_TIME
        if (live && wasFaceoff && sim.puck.x * side.dir < -Rink.BLUE_X) {
            dzoneT = DZONE_TIME
            breakoutT = DZONE_TIME
            assignMarks(sim, skipId)
        }
        wasFaceoff = sim.phase == Phase.FACEOFF
        if (dzoneT <= 0f) return
        dzoneT -= Tuning.DT
        if (!live || sim.puck.carrier?.side == side) dzoneT = 0f
    }

    /**
     * In the breakout window after a draw in our own zone: the tick one of our defencemen has the
     * puck, he decides at once ([breakout]) and the window closes. It also closes when the time is
     * up, play stops, or the other side has the puck.
     */
    private fun breakoutCheck(sim: HockeySim, skipId: Int) {
        centreBreakoutCheck(sim, skipId)
        if (breakoutT <= 0f) return
        breakoutT -= Tuning.DT
        val c = sim.puck.carrier
        if (sim.phase != Phase.PLAY || (c != null && c.side != side)) { breakoutT = 0f; return }
        if (c == null || c.id == skipId || (c.pos != Position.LD && c.pos != Position.RD)) return
        breakoutT = 0f
        breakout(sim, c, c.pos.ordinal)
    }

    /**
     * After a draw in our own zone or the neutral zone: if our centre is the first to have the puck
     * (he won it to himself), he moves it straight back at once ([centreBreakout]). The window
     * closes on the first carrier of either side, when play stops, or when the time is up.
     */
    private fun centreBreakoutCheck(sim: HockeySim, skipId: Int) {
        if (cBreakoutT <= 0f) return
        cBreakoutT -= Tuning.DT
        val c = sim.puck.carrier
        if (sim.phase != Phase.PLAY) { cBreakoutT = 0f; return }
        if (c == null) return
        cBreakoutT = 0f
        if (c.side != side || c.id == skipId || c.pos != Position.C) return
        centreBreakout(sim, c, c.pos.ordinal)
    }

    /**
     * Centre [s] moves the puck back: a pass, stick pushed at him, to the nearer defenceman with an
     * open lane; with no lane, a carry straight away from the nearest opponent. No random draws.
     */
    private fun centreBreakout(sim: HockeySim, s: Skater, i: Int) {
        val mates = sim.skatersOf(side)
        var t: Skater? = null; var tD = Float.MAX_VALUE
        for (k in mates.indices) {
            val m = mates[k]
            if (m.pos != Position.LD && m.pos != Position.RD || m.act == Act.DOWN || !laneOpen(sim, s, m)) continue
            val d = dist(s.x, s.y, m.x, m.y)
            if (d < tD) { t = m; tD = d }
        }
        passOrEscape(sim, s, i, t)
    }

    /** Sets [s] to pass to [t] with the stick pushed at him, or, with no [t], to carry away from the nearest opponent. */
    private fun passOrEscape(sim: HockeySim, s: Skater, i: Int, t: Skater?) {
        if (t != null) {
            val dx = t.x - s.x; val dy = t.y - s.y
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            mode[i] = BREAKOUT_PASS; tx[i] = dx / d; ty[i] = dy / d
            return
        }
        val opps = sim.skatersOf(side.other)
        var opp: Skater? = null; var oppD = Float.MAX_VALUE
        for (j in opps.indices) {
            val o = opps[j]
            val d = dist(s.x, s.y, o.x, o.y)
            if (d < oppD) { opp = o; oppD = d }
        }
        mode[i] = CARRY
        if (opp == null || oppD < 1e-3f) { tx[i] = side.dir; ty[i] = 0f; return }
        tx[i] = (s.x - opp.x) / oppD; ty[i] = (s.y - opp.y) / oppD
    }

    /**
     * Defenceman [s] moves the puck out: a pass, stick pushed at him, to the nearer winger up ice
     * with an open lane, else to his partner if that lane is open; with no lane, a carry straight
     * away from the nearest opponent. No random draws.
     */
    private fun breakout(sim: HockeySim, s: Skater, i: Int) {
        val mates = sim.skatersOf(side)
        var t: Skater? = null; var tD = Float.MAX_VALUE
        for (k in mates.indices) {
            val m = mates[k]
            if (m === s || m.pos != Position.LW && m.pos != Position.RW || m.act == Act.DOWN) continue
            if ((m.x - s.x) * side.dir <= 0f || !laneOpen(sim, s, m)) continue
            val d = dist(s.x, s.y, m.x, m.y)
            if (d < tD) { t = m; tD = d }
        }
        if (t == null) {
            // Else his partner, if that lane is open.
            for (k in mates.indices) {
                val m = mates[k]
                if (m === s || m.pos != Position.LD && m.pos != Position.RD || m.act == Act.DOWN || !laneOpen(sim, s, m)) continue
                val d = dist(s.x, s.y, m.x, m.y)
                if (d < tD) { t = m; tD = d }
            }
        }
        passOrEscape(sim, s, i, t)
    }

    /**
     * Matches markers (everyone but the centre, the human's skater and anyone down) to opponents on
     * their feet, greedily: the nearest unmatched pair first, none further apart than [MARK_REACH].
     */
    private fun assignMarks(sim: HockeySim, skipId: Int) {
        val mates = sim.skatersOf(side)
        val opps = sim.skatersOf(side.other)
        markOf.fill(-1)
        for (j in opps.indices) oppTaken[j] = opps[j].act == Act.DOWN
        while (true) {
            var bi = -1; var bj = -1; var bestD = Float.MAX_VALUE
            for (i in mates.indices) {
                val s = mates[i]
                if (markOf[i] >= 0 || s.id == skipId || s.pos == Position.C || s.act == Act.DOWN) continue
                for (j in opps.indices) {
                    if (oppTaken[j]) continue
                    val d = dist(s.x, s.y, opps[j].x, opps[j].y)
                    if (d < bestD) { bi = i; bj = j; bestD = d }
                }
            }
            if (bi < 0 || bestD > MARK_REACH) return
            markOf[bi] = bj; oppTaken[bj] = true
        }
    }

    /** The centre to the slot; a marker [MARK_OFF] off his man toward our net; anyone unmatched to the net front. */
    private fun defendZone(sim: HockeySim, s: Skater, i: Int) {
        mode[i] = MOVE
        val netX = -side.dir * Rink.GOAL_X
        if (s.pos == Position.C) {
            tx[i] = netX + side.dir * SLOT_OUT; ty[i] = 0f
            return
        }
        val j = markOf[i]
        if (j < 0) {
            tx[i] = netX + side.dir * NET_FRONT_OUT; ty[i] = 0f
            return
        }
        val m = sim.skatersOf(side.other)[j]
        val dx = netX - m.x; val dy = -m.y
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val off = min(MARK_OFF, d)
        tx[i] = m.x + dx / d * off; ty[i] = m.y + dy / d * off
    }

    private fun decideCarrier(sim: HockeySim, s: Skater, i: Int) {
        if (s.act == Act.WINDUP) { mode[i] = SHOOT; return }
        val dir = side.dir
        val netX = dir * Rink.GOAL_X
        val px = sim.puck.x; val py = sim.puck.y
        val toNet = dist(px, py, netX, 0f)
        val inFront = (netX - s.x) * dir > SHOOT_MIN_AHEAD
        if (inFront && toNet <= difficulty.shootRange && (toNet < POINT_BLANK || laneClear(sim, s, px, py, netX))) {
            mode[i] = SHOOT
            tx[i] = netX; ty[i] = 0f
            val gy = sim.goalies[side.other.index].y
            val openSide = if (gy > 0f) -1f else 1f
            shootSign[i] = if (difficulty.aimsForCorners) openSide else -openSide
            return
        }
        // Nearest opponent, for dekes, passes and steering.
        val opps = sim.skatersOf(side.other)
        var opp: Skater? = null; var oppD = Float.MAX_VALUE
        for (j in opps.indices) {
            val o = opps[j]
            if (o.act == Act.DOWN) continue
            val d = dist(s.x, s.y, o.x, o.y)
            if (d < oppD) { opp = o; oppD = d }
        }
        if (opp != null && difficulty.dekes && s.dekeCd <= 0f && s.act == Act.NONE && oppD < DEKE_RANGE &&
            closing(s, opp)) {
            // Sideways across the checker's line, picking the side that keeps going up ice.
            val ax = (s.x - opp.x) / oppD; val ay = (s.y - opp.y) / oppD
            var nx = -ay; var ny = ax
            if (nx * dir < 0f) { nx = -nx; ny = -ny }
            mode[i] = DEKE
            tx[i] = nx; ty[i] = ny
            return
        }
        // Skate for the slot, bending away from a nearby opponent.
        var dx = netX - dir * CARRY_AIM_OUT - s.x; var dy = -s.y
        var d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        dx /= d; dy /= d
        if (opp != null && oppD < AVOID_RANGE) {
            val w = AVOID_WEIGHT * (AVOID_RANGE - oppD) / AVOID_RANGE / oppD.coerceAtLeast(0.1f)
            dx += (s.x - opp.x) * w; dy += (s.y - opp.y) * w
            d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            dx /= d; dy /= d
        }
        tx[i] = dx; ty[i] = dy
        // Under pressure, maybe pass, but only along a lane no opponent covers; else keep skating.
        mode[i] = if (opp != null && oppD < PRESSURE_RANGE && random.nextFloat() < difficulty.passUnderPressure &&
            openPassTarget(sim, s)) PASS else CARRY
    }

    /** The teammate a centred-stick pass would go to exists and no opponent covers the lane to him. */
    private fun openPassTarget(sim: HockeySim, s: Skater): Boolean {
        s.input.moveX = 0f; s.input.moveY = 0f   // the pass is pressed with the stick centred
        val t = sim.passTargetFor(s) ?: return false
        return laneOpen(sim, s, t)
    }

    /** No opponent within [Tuning.PASS_LANE_CLEAR] of the line from [s] to [t]. */
    private fun laneOpen(sim: HockeySim, s: Skater, t: Skater): Boolean {
        val ex = t.x - s.x; val ey = t.y - s.y
        val len2 = ex * ex + ey * ey
        val opps = sim.skatersOf(side.other)
        for (j in opps.indices) {
            val o = opps[j]
            val u = if (len2 > 1e-6f) (((o.x - s.x) * ex + (o.y - s.y) * ey) / len2).coerceIn(0f, 1f) else 0f
            if (dist(s.x + ex * u, s.y + ey * u, o.x, o.y) < Tuning.PASS_LANE_CLEAR) return false
        }
        return true
    }

    /** No skater but [s] within [LANE_CLEAR] of the line from the puck to the net centre. */
    private fun laneClear(sim: HockeySim, s: Skater, px: Float, py: Float, netX: Float): Boolean {
        val ex = netX - px; val ey = -py
        val len2 = ex * ex + ey * ey
        for (j in sim.skaters.indices) {
            val o = sim.skaters[j]
            if (o === s) continue
            val t = if (len2 > 1e-6f) (((o.x - px) * ex + (o.y - py) * ey) / len2).coerceIn(0f, 1f) else 0f
            if (dist(px + ex * t, py + ey * t, o.x, o.y) < LANE_CLEAR) return false
        }
        return true
    }

    /** [o] is moving toward [s]. */
    private fun closing(s: Skater, o: Skater): Boolean =
        (o.vx - s.vx) * (s.x - o.x) + (o.vy - s.vy) * (s.y - o.y) > 0f

    /**
     * Until the puck is in the attacking zone, keeps the spot [ONSIDE_MARGIN] short of the attacking
     * blue line, so nobody goes in ahead of the puck. There is no offside whistle; this is how real
     * players time their entry, and it leaves them at the line for a pass. While we carry the puck
     * up ice the forwards also keep pace [PACE_AHEAD] in front of where it is heading, so they reach
     * the line skating as it does, not standing far back.
     */
    private fun holdOnside(s: Skater, p: FloatArray) {
        val d = side.dir
        if (seenX * d >= Rink.BLUE_X) return
        var x = p[0] * d
        if (seenPoss == side && (s.pos == Position.C || s.pos == Position.LW || s.pos == Position.RW)) {
            val ahead = (seenX + seenVx * difficulty.reactionS * PACE_LEAD) * d + PACE_AHEAD
            if (ahead > x) x = ahead
        }
        val line = Rink.BLUE_X - ONSIDE_MARGIN
        if (x > line) x = line
        p[0] = x * d
    }

    /** Pushes a spot out to [KEEP_OFF] from the puck so non-chasers give it room, then inside the boards. */
    private fun keepOffPuck(p: FloatArray) {
        var dx = p[0] - seenX; var dy = p[1] - seenY
        val d = sqrt(dx * dx + dy * dy)
        if (d >= KEEP_OFF) return
        if (d > 1e-3f) { dx /= d; dy /= d } else { dx = -side.dir; dy = 0f }
        p[0] = seenX + dx * KEEP_OFF; p[1] = seenY + dy * KEEP_OFF
        Rink.clamp(p[0], p[1], 1f, p)   // writes the point, pulled inside the boards, to p[0..1]
    }

    // --- Carrying out the decision, every tick ---

    private fun act(sim: HockeySim, s: Skater, i: Int) {
        val inp = s.input
        inp.clear()
        val was = held[i]
        var now = 0
        val carrying = sim.puck.carrier === s
        when (mode[i]) {
            MOVE -> steer(s, tx[i], ty[i], arrive = true)
            CHASE -> {
                steer(s, tx[i], ty[i], arrive = false)
                now = tryChecks(sim, s, was)
            }
            CARRY -> { inp.moveX = tx[i]; inp.moveY = ty[i] }
            SHOOT -> {
                steer(s, tx[i], ty[i], arrive = false)
                if (!carrying) holdStill(s, i)
                else if (s.act == Act.WINDUP) {
                    if (s.windup >= difficulty.shotHold - Tuning.DT * 0.5f) {
                        inp.moveX = 0f; inp.moveY = shootSign[i]   // released: the stick picks the side
                        holdStill(s, i)
                    } else {
                        inp.shoot = true; now = now or SHOOT_BIT
                    }
                } else if (s.act == Act.NONE && was and SHOOT_BIT == 0) {
                    inp.shoot = true; now = now or SHOOT_BIT
                }
            }
            BREAKOUT_PASS -> {
                // The stick pushed at the receiver picks him (the sim's pass targeting).
                inp.moveX = tx[i]; inp.moveY = ty[i]
                if (carrying && s.act == Act.NONE && was and PASS_BIT == 0) { inp.pass = true; now = now or PASS_BIT }
                mode[i] = CARRY   // then skate on that way until the next decision
            }
            PASS -> {
                if (carrying && s.act == Act.NONE && was and PASS_BIT == 0) {
                    inp.pass = true; now = now or PASS_BIT   // stick centred: the open man furthest up ice
                } else {
                    inp.moveX = tx[i]; inp.moveY = ty[i]
                }
                mode[i] = CARRY   // whatever happened, keep skating the carry line until the next decision
            }
            DEKE -> {
                inp.moveX = tx[i]; inp.moveY = ty[i]
                if (carrying && s.act == Act.NONE && s.dekeCd <= 0f && was and DEKE_BIT == 0) {
                    inp.deke = true; now = now or DEKE_BIT
                }
                mode[i] = CARRY   // keep sliding that way until the next decision
            }
        }
        held[i] = now
    }

    /** After a shot or pass, or when the puck is gone mid-move: stay put until the next decision. */
    private fun holdStill(s: Skater, i: Int) {
        mode[i] = MOVE; tx[i] = s.x; ty[i] = s.y
    }

    /** Stick toward (x, y); with [arrive], easing off near it and centred once there. */
    private fun steer(s: Skater, x: Float, y: Float, arrive: Boolean) {
        val dx = x - s.x; val dy = y - s.y
        val d = sqrt(dx * dx + dy * dy)
        if (d < ARRIVED) return
        val m = if (arrive) min(1f, d / ARRIVE_SLOW) else 1f
        s.input.moveX = dx / d * m; s.input.moveY = dy / d * m
    }

    /**
     * Poke and body checks on an opposing carrier in range, at the difficulty's rates. With chance
     * 1 - timingError a check is only thrown when it would land. Returns the buttons pressed.
     */
    private fun tryChecks(sim: HockeySim, s: Skater, was: Int): Int {
        val c = sim.puck.carrier ?: return 0
        if (c.side == side || s.act != Act.NONE) return 0
        val d = dist(s.x, s.y, c.x, c.y)
        if (d > CHECK_RANGE) return 0
        val dodging = c.act == Act.DEKE
        if (s.pokeCd <= 0f && was and POKE_BIT == 0 && random.nextFloat() < difficulty.pokePerSecond * Tuning.DT) {
            val px = sim.puck.x - s.x; val py = sim.puck.y - s.y
            val lands = !dodging && px * px + py * py <= Tuning.POKE_REACH * Tuning.POKE_REACH &&
                abs(wrap(atan2(py, px) - s.facing)) <= Tuning.POKE_ARC
            if (lands || random.nextFloat() < difficulty.timingError) {
                s.input.poke = true
                return POKE_BIT
            }
        }
        if (was and BODY_BIT == 0 && random.nextFloat() < difficulty.hitPerSecond * Tuning.DT) {
            val lands = !dodging && c.act != Act.DOWN && d < BODY_LANDS
            if (lands || random.nextFloat() < difficulty.timingError) {
                // Aim where the carrier will be as the check arrives.
                val t = d / Tuning.BODY_SPEED
                val ax = c.x + c.vx * t - s.x; val ay = c.y + c.vy * t - s.y
                val a = atan2(ay, ax)
                s.input.moveX = cos(a); s.input.moveY = sin(a)
                s.input.body = true
                return BODY_BIT
            }
        }
        return 0
    }

    private companion object {
        const val MOVE = 0; const val CHASE = 1; const val CARRY = 2; const val SHOOT = 3; const val PASS = 4; const val DEKE = 5
        const val BREAKOUT_PASS = 6
        const val SHOOT_BIT = 1; const val PASS_BIT = 2; const val DEKE_BIT = 4; const val POKE_BIT = 8; const val BODY_BIT = 16

        const val LEAD_S = 0.3f            // a chaser skates at where the puck will be this much later
        const val DZONE_TIME = 3f          // s the defensive-zone plan lasts after a draw in our own end
        const val MARK_OFF = 1.5f          // a marker stands this far off his man, toward our net
        const val SLOT_OUT = 5f            // the centre covers the slot this far out from our goal line
        const val MARK_REACH = 9f          // a skater takes no man further off than this at the drop
        const val NET_FRONT_OUT = 2.5f     // one without a man holds the net front this far out
        const val HUMAN_CLAIM = 4f         // teammates leave a puck this close to the human's skater
        const val KEEP_OFF = 3f            // non-chasers' spots are at least this far from the puck
        const val PRESSURE_RANGE = 2.5f    // an opponent this close may make the carrier pass
        const val DEKE_RANGE = 1.8f
        const val AVOID_RANGE = 3f
        const val AVOID_WEIGHT = 1.5f
        const val CARRY_AIM_OUT = 3f       // the carrier skates for a point this far out from the goal line
        const val SHOOT_MIN_AHEAD = 1.5f   // no shots from closer to the goal line than this
        const val POINT_BLANK = 5f         // this close, shoot even through traffic
        const val LANE_CLEAR = 1f
        const val CHECK_RANGE = 3f         // a chaser this close to the carrier considers checking
        const val BODY_LANDS = 2.2f        // a body check from this close reaches the carrier
        const val ONSIDE_MARGIN = 0.9f     // a held skater's centre stays this far short of the blue line
        const val PACE_AHEAD = 2f          // forwards keep this far ahead of the rushing puck, up to the line
        const val PACE_LEAD = 1f           // ...of where it will be this many reaction times on
        const val ARRIVED = 0.3f
        const val ARRIVE_SLOW = 2.5f

        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        fun wrap(a: Float): Float {
            var r = a
            while (r > PI_F) r -= 2f * PI_F
            while (r <= -PI_F) r += 2f * PI_F
            return r
        }

        const val PI_F = Math.PI.toFloat()
    }
}
