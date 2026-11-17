package com.personal.hockey.sim

import kotlin.math.sqrt

/**
 * Which HOME skater the human drives. The carrier always; the receiver as soon as a pass to him is
 * made; otherwise the skater nearest the puck, but only once he has been nearer by more than
 * [Tuning.SWITCH_MARGIN] for [Tuning.SWITCH_TIME] and the current skater isn't mid-action, so
 * control doesn't flicker or get yanked away during a check.
 */
internal fun HockeySim.stepControl() {
    if (!humanControlled) { controlledId = -1; return }
    if (phase == Phase.FACEOFF) return   // the centre, set when the faceoff began
    val c = puck.carrier
    val target = puck.passTarget
    val immediate = when {
        c != null && c.side == Side.HOME -> c
        c == null && target != null && target.side == Side.HOME -> target
        else -> null
    }
    if (immediate != null || controlledId < 0) {
        controlledId = (immediate ?: nearestHomeToPuck()).id
        switchCandidate = -1; switchT = 0f
        return
    }
    val cand = nearestHomeToPuck()
    val cur = skaters[controlledId]
    if (cand === cur || cur.act != Act.NONE || dist(cur) - dist(cand) <= Tuning.SWITCH_MARGIN) {
        switchCandidate = -1; switchT = 0f
        return
    }
    if (switchCandidate != cand.id) { switchCandidate = cand.id; switchT = 0f }
    switchT += Tuning.DT
    if (switchT >= Tuning.SWITCH_TIME - Tuning.DT * 0.5f) {
        controlledId = cand.id
        switchCandidate = -1; switchT = 0f
    }
}

private fun HockeySim.dist(s: Skater): Float {
    val dx = s.x - puck.x; val dy = s.y - puck.y
    return sqrt(dx * dx + dy * dy)
}

private fun HockeySim.nearestHomeToPuck(): Skater {
    val home = skatersOf(Side.HOME)
    var best = home[0]
    var bestD = Float.MAX_VALUE
    for (i in home.indices) {
        val d = dist(home[i])
        if (d < bestD) { best = home[i]; bestD = d }
    }
    return best
}
