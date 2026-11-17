package com.personal.hockey.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FACEOFF: players on their spots, clock stopped. PLAY: the clock runs and everything is live.
 * WHISTLE: a goalie caught the puck and holds it, the clock stopped and the AI resting; then a
 * faceoff on his zone's dot, or the period's end. GOAL: the banner and goal light after a goal.
 * CELEBRATE: the rest of that pause, the scorer's celebration, with the AI resting; then a faceoff,
 * or the period's end. BREAK: between periods. FINAL: the game is over. Outside PLAY, skaters and
 * puck slide with inputs ignored and nothing can score.
 */
enum class Phase {
    FACEOFF, PLAY, WHISTLE, GOAL, CELEBRATE, BREAK, FINAL;

    /** The banner/goal-light pause after a goal: the goal itself, then the celebration. */
    val afterGoal: Boolean get() = this == GOAL || this == CELEBRATE
}

/** Half a tick of slack absorbs float drift in the countdowns. */
private const val SLACK = Tuning.DT * 0.5f

/** Phase timers and the clock; runs first in a tick. The period ends in [checkPeriodEnd]. */
internal fun HockeySim.stepFlow() {
    when (phase) {
        Phase.FACEOFF -> {
            phaseT -= Tuning.DT
            if (phaseT < SLACK) {
                phaseT = 0f
                // One draw picks the winner; the puck is pulled back toward his defencemen, the
                // second draw spreading the direction a little.
                val winner = if (random.nextFloat() < faceoffWinAway) Side.AWAY else Side.HOME
                val mates = skatersOf(winner)
                val ld = mates[Position.LD.ordinal]; val rd = mates[Position.RD.ordinal]
                val a = atan2((ld.y + rd.y) * 0.5f - puck.y, (ld.x + rd.x) * 0.5f - puck.x) +
                    (random.nextFloat() * 2f - 1f) * Tuning.FACEOFF_SPREAD
                puck.vx = cos(a) * Tuning.FACEOFF_WIN_SPEED; puck.vy = sin(a) * Tuning.FACEOFF_WIN_SPEED
                // The centres start within reach of the dot: a short lock makes it a battle, not
                // an instant pickup by whoever the drift favours.
                for (i in skaters.indices) skaters[i].pickupLock = Tuning.FACEOFF_PICKUP_LOCK
                // The centre who lost the draw is tied up with the winner for a moment.
                skatersOf(winner.other)[Position.C.ordinal].tiedUpT = Tuning.TIE_UP_S
                phase = Phase.PLAY
                events.add(SimEvent.FaceoffDrop)
            }
        }
        Phase.PLAY -> {
            clockS -= Tuning.DT
            // Float drift means the count never lands on 0: within half a tick, it has.
            if (clockS < SLACK) clockS = 0f
        }
        Phase.WHISTLE -> {
            phaseT -= Tuning.DT
            if (phaseT < SLACK) { if (clockS < SLACK) endPeriod() else startFaceoff(whistleDotX, whistleDotY) }
        }
        Phase.GOAL -> {
            phaseT -= Tuning.DT
            if (phaseT < SLACK) { phase = Phase.CELEBRATE; phaseT = Tuning.CELEBRATE_TIME }
        }
        Phase.CELEBRATE -> {
            phaseT -= Tuning.DT
            if (phaseT < SLACK) { if (clockS < SLACK) endPeriod() else startFaceoff() }
        }
        Phase.BREAK -> {
            phaseT -= Tuning.DT
            if (phaseT < SLACK) {
                period++
                clockS = Tuning.PERIOD_SECONDS
                startFaceoff()
            }
        }
        Phase.FINAL -> {}
    }
}

/** Ends the period once the clock has run out in live play; runs after the goal check. */
internal fun HockeySim.checkPeriodEnd() {
    if (phase != Phase.PLAY || clockS >= SLACK) return
    clockS = 0f
    endPeriod()
}

private fun HockeySim.endPeriod() {
    events.add(SimEvent.PeriodEnd(period))
    for (i in skaters.indices) clearAct(skaters[i])   // nobody stays frozen mid-check or down
    if (period < Tuning.PERIODS) {
        phase = Phase.BREAK; phaseT = Tuning.BREAK_TIME
    } else {
        phase = Phase.FINAL; phaseT = 0f
        events.add(SimEvent.GameEnd)
    }
}

/**
 * Scores if the puck's move this tick, from (px, py), crossed a goal line between the posts going
 * in and ended inside the net. Only in live play; the side attacking that net scores, whoever
 * touched it last.
 *
 * The end point matters because the puck does not always slide: a pickup or a carrier's stick can
 * move it a metre in one tick, and the net walls then stop it outside the net. The straight line
 * from where it was can still cut the goal line between the posts, but the puck went round the
 * post, not in. The walls are solid and a tick's move is far shorter than the net is deep, so a
 * puck that really came through the mouth is always inside at the end of that tick.
 */
internal fun HockeySim.checkGoal(px: Float, py: Float) {
    if (phase != Phase.PLAY) return
    for (i in Side.entries.indices) {
        val side = Side.entries[i]
        // side attacks the net at x = side.dir * GOAL_X; measure along that direction.
        val a = px * side.dir; val b = puck.x * side.dir
        if (a >= Rink.GOAL_X || b < Rink.GOAL_X) continue
        if (b > Rink.GOAL_X + Rink.NET_DEPTH || abs(puck.y) >= Rink.GOAL_HALF_W) continue
        val t = (Rink.GOAL_X - a) / (b - a)
        if (abs(py + (puck.y - py) * t) >= Rink.GOAL_HALF_W) continue
        score[side.index]++
        lastGoalSide = side
        val scorerId = scorerFor(side)
        lastScorerId = scorerId
        events.add(SimEvent.Goal(side, scorerId))
        phase = Phase.GOAL; phaseT = Tuning.GOAL_TIME
        if (puck.carrier != null) knockLoose(puck.vx, puck.vy, 0f)   // let it slide into the net
        puck.passTarget = null
        return
    }
}

/** The skater credited when [side] scores: its last toucher, else its skater nearest the net it attacks. */
private fun HockeySim.scorerFor(side: Side): Int {
    val touched = lastTouch[side.index]
    if (puck.lastSide == side && touched >= 0) return touched
    val nx = side.dir * Rink.GOAL_X
    var best = -1
    var bestD2 = Float.MAX_VALUE
    for (i in skaters.indices) {
        val s = skaters[i]
        if (s.side != side) continue
        val dx = s.x - nx
        val d2 = dx * dx + s.y * s.y
        if (d2 < bestD2) { best = s.id; bestD2 = d2 }
    }
    return best
}

private fun clearAct(s: Skater) {
    s.act = Act.NONE; s.actT = 0f; s.windup = 0f
}

/**
 * Everyone to their faceoff spot at rest around the dot ([dotX], [dotY]), the puck on it, control to
 * the HOME centre. At centre ice (0, 0) the teams line up across the red line; on an end-zone dot
 * they line up around it, each centre on his own goal side, and nobody is put outside the boards or
 * in a crease.
 */
internal fun HockeySim.startFaceoff(dotX: Float = 0f, dotY: Float = 0f) {
    phase = Phase.FACEOFF; phaseT = Tuning.FACEOFF_TIME
    val centre = dotX == 0f && dotY == 0f
    for (i in skaters.indices) {
        val s = skaters[i]
        // Metres back toward his own goal and out to his left (+y when attacking +x), from the dot.
        val back: Float; val wide: Float
        if (centre) {
            back = CENTRE_BACK[s.pos.ordinal]; wide = CENTRE_WIDE[s.pos.ordinal]
        } else {
            back = ZONE_BACK[s.pos.ordinal]; wide = ZONE_WIDE[s.pos.ordinal]
        }
        s.x = dotX - s.side.dir * back
        s.y = dotY + s.side.dir * wide
        if (!centre) placeInBounds(s)
        s.vx = 0f; s.vy = 0f
        s.facing = if (s.side == Side.HOME) 0f else PI.toFloat()
        clearAct(s)
        s.dekeCd = 0f; s.pokeCd = 0f; s.pickupLock = 0f; s.tiedUpT = 0f
    }
    lastTouch[0] = -1; lastTouch[1] = -1
    puck.x = dotX; puck.y = dotY; puck.vx = 0f; puck.vy = 0f
    puck.carrier = null; puck.passTarget = null; puck.stillT = 0f; puck.fromShot = false
    for (g in goalies) resetGoalie(g)
    controlledId = if (humanControlled) 0 else -1
    switchCandidate = -1; switchT = 0f
}

/** Faceoff spots by [Position]: metres back toward the skater's own goal from the dot, and out to his left. */
private val CENTRE_BACK = floatArrayOf(1.2f, 6f, 6f, 12f, 12f)
private val CENTRE_WIDE = floatArrayOf(0f, 7f, -7f, 5f, -5f)
private val ZONE_BACK = floatArrayOf(1.2f, 1.5f, 1.5f, 6f, 6f)
private val ZONE_WIDE = floatArrayOf(0f, 3.5f, -3.5f, 3f, -3f)

/** Puts [s] inside the boards and outside both creases (and nets), wherever his spot fell. */
private fun HockeySim.placeInBounds(s: Skater) {
    if (Rink.clamp(s.x, s.y, Tuning.SKATER_R, clampOut)) { s.x = clampOut[0]; s.y = clampOut[1] }
    val clear = Rink.CREASE_R + Tuning.SKATER_R + 0.05f
    for (k in 0..1) {
        val gx = if (k == 0) -Rink.GOAL_X else Rink.GOAL_X
        val dx = s.x - gx; val dy = s.y
        val d = sqrt(dx * dx + dy * dy)
        if (d >= clear) continue
        // Straight out from the goal centre; one on the goal line itself goes out in front.
        if (d > 1e-4f) { s.x = gx + dx / d * clear; s.y = dy / d * clear } else s.x = gx - (if (gx > 0f) clear else -clear)
    }
    keepOutOfNets(s)
}
