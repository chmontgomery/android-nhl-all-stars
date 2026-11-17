package com.personal.hockey.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val HALF_PI = (PI / 2).toFloat()

/**
 * Buttons for one tick: timers first for everyone, then each skater's presses and ongoing acts.
 * Runs before [stepSkaters], which moves skaters according to the acts set here.
 */
internal fun HockeySim.stepActions() {
    for (i in skaters.indices) tickTimers(skaters[i])
    for (i in skaters.indices) {
        val s = skaters[i]
        if (s.act == Act.WINDUP && puck.carrier !== s) { s.act = Act.NONE; s.windup = 0f }
        if (s.act == Act.NONE) {
            if (puck.carrier === s) startCarrierAction(s) else if (s.tiedUpT <= 0f) startDefenderAction(s)
        }
        if (s.act == Act.WINDUP) holdWindup(s)
        if (s.act == Act.BODY) bodyCheck(s)
        latchButtons(s)
    }
}

/**
 * Remembers this tick's buttons for edge detection. Runs every tick, live or not, so a button held
 * through a stoppage doesn't count as a fresh press when play resumes.
 */
internal fun latchButtons(s: Skater) {
    val p = s.prev; val inp = s.input
    p.shoot = inp.shoot; p.pass = inp.pass; p.deke = inp.deke; p.poke = inp.poke; p.body = inp.body
}

private fun pressed(now: Boolean, before: Boolean) = now && !before

private fun Skater.stickPushed(): Boolean =
    input.moveX * input.moveX + input.moveY * input.moveY > Tuning.STICK_PUSHED * Tuning.STICK_PUSHED

private fun HockeySim.tickTimers(s: Skater) {
    if (s.dekeCd > 0f) s.dekeCd = max(0f, s.dekeCd - Tuning.DT)
    if (s.pokeCd > 0f) s.pokeCd = max(0f, s.pokeCd - Tuning.DT)
    if (s.pickupLock > 0f) s.pickupLock = max(0f, s.pickupLock - Tuning.DT)
    if (s.tiedUpT > 0f) s.tiedUpT = max(0f, s.tiedUpT - Tuning.DT)
    if (s.act == Act.NONE || s.act == Act.WINDUP) return
    s.actT -= Tuning.DT
    if (s.actT >= Tuning.DT * 0.5f) return   // half a tick of slack absorbs float drift
    if (s.act == Act.BODY) {
        // A missed check leaves the checker off balance: slow for RECOVER_TIME.
        s.act = Act.RECOVER; s.actT = Tuning.RECOVER_TIME
        val cap = Tuning.MAX_SPEED * s.speedScale * Tuning.RECOVER_SPEED
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
        if (speed > cap) { s.vx *= cap / speed; s.vy *= cap / speed }
    } else {
        s.act = Act.NONE; s.actT = 0f
    }
}

/** Shoot beats pass beats deke when several are pressed in one tick. */
private fun HockeySim.startCarrierAction(s: Skater) {
    val inp = s.input; val prev = s.prev
    when {
        pressed(inp.shoot, prev.shoot) -> { s.act = Act.WINDUP; s.windup = 0f }
        pressed(inp.pass, prev.pass) -> pass(s)
        pressed(inp.deke, prev.deke) && s.dekeCd <= 0f -> {
            val a = if (s.stickPushed()) atan2(inp.moveY, inp.moveX) else s.facing + HALF_PI
            s.act = Act.DEKE; s.actT = Tuning.DEKE_TIME; s.dekeCd = Tuning.DEKE_CD
            s.vx = cos(a) * Tuning.DEKE_SPEED; s.vy = sin(a) * Tuning.DEKE_SPEED
        }
    }
}

/** Poke beats body when both are pressed in one tick. */
private fun HockeySim.startDefenderAction(s: Skater) {
    val inp = s.input; val prev = s.prev
    when {
        pressed(inp.poke, prev.poke) && s.pokeCd <= 0f -> poke(s)
        pressed(inp.body, prev.body) -> {
            val a = if (s.stickPushed()) atan2(inp.moveY, inp.moveX) else s.facing
            s.act = Act.BODY; s.actT = Tuning.BODY_TIME
            s.vx = cos(a) * Tuning.BODY_SPEED; s.vy = sin(a) * Tuning.BODY_SPEED
        }
    }
}

private fun HockeySim.holdWindup(s: Skater) {
    if (s.input.shoot) {
        s.windup += Tuning.DT
        if (s.windup < Tuning.WINDUP_AUTO_FIRE) return
    }
    shoot(s)
}

private fun HockeySim.shoot(s: Skater) {
    val power = ((s.windup - Tuning.WINDUP_MIN) / (Tuning.WINDUP_MAX - Tuning.WINDUP_MIN)).coerceIn(0f, 1f)
    val speed = (Tuning.SHOT_SPEED_MIN + (Tuning.SHOT_SPEED_MAX - Tuning.SHOT_SPEED_MIN) * power) * s.shotScale
    val my = s.input.moveY
    val aimY = if (abs(my) > Tuning.STICK_PUSHED) (if (my > 0f) 1f else -1f) * Tuning.SHOT_AIM * Rink.GOAL_HALF_W
               else openSideAimY(s.side)
    val spread = (random.nextFloat() * 2f - 1f) * Tuning.SHOT_SPREAD * (1f + power)
    val a = atan2(aimY - puck.y, s.side.dir * Rink.GOAL_X - puck.x) + spread
    knockLoose(cos(a) * speed, sin(a) * speed, Tuning.PICKUP_LOCK)   // also ends the wind-up
    puck.passTarget = null
    puck.lastSide = s.side
    puck.fromShot = true; puck.shotX = puck.x; puck.shotY = puck.y
    events.add(SimEvent.Shot(s.side, power, s.id))
}

/** Aim y for a shot with the stick centred: the side of the attacked net farther from its goalie. */
private fun HockeySim.openSideAimY(shooter: Side): Float {
    val aim = Tuning.SHOT_AIM * Rink.GOAL_HALF_W
    return if (goalies[shooter.other.index].y > 0f) -aim else aim
}

/** Passes at [Tuning.PASS_SPEED] to where the target will be when the puck gets there. */
private fun HockeySim.pass(s: Skater) {
    val t = passTargetFor(s) ?: return
    var dx = t.x - puck.x; var dy = t.y - puck.y
    val lead = sqrt(dx * dx + dy * dy) / Tuning.PASS_SPEED
    dx += t.vx * lead; dy += t.vy * lead
    val d = sqrt(dx * dx + dy * dy)
    val a = if (d > 1e-3f) atan2(dy, dx) else s.facing
    knockLoose(cos(a) * Tuning.PASS_SPEED, sin(a) * Tuning.PASS_SPEED, Tuning.PICKUP_LOCK)
    puck.passTarget = t
    puck.lastSide = s.side
    events.add(SimEvent.Pass(s.side, s.id, t.id))
}

/**
 * Stick pushed: the teammate closest in angle to the stick, near ties going to the nearer one.
 * Stick centred: the teammate furthest up ice with a clear passing line, else simply the furthest.
 */
internal fun HockeySim.choosePassTarget(s: Skater): Skater? {
    val mates = skatersOf(s.side)
    if (s.stickPushed()) {
        val want = atan2(s.input.moveY, s.input.moveX)
        var minAngle = Float.MAX_VALUE
        for (i in mates.indices) {
            val m = mates[i]
            if (m !== s) minAngle = minOf(minAngle, angleTo(s, m, want))
        }
        var best: Skater? = null
        var bestD2 = Float.MAX_VALUE
        for (i in mates.indices) {
            val m = mates[i]
            if (m === s || angleTo(s, m, want) > minAngle + Tuning.PASS_TIE) continue
            val dx = m.x - s.x; val dy = m.y - s.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { best = m; bestD2 = d2 }
        }
        return best
    }
    var open: Skater? = null; var openX = -Float.MAX_VALUE
    var any: Skater? = null; var anyX = -Float.MAX_VALUE
    for (i in mates.indices) {
        val m = mates[i]
        if (m === s) continue
        val up = m.x * s.side.dir
        if (up > anyX) { any = m; anyX = up }
        if (up > openX && laneClear(s, m)) { open = m; openX = up }
    }
    return open ?: any
}

private fun angleTo(from: Skater, to: Skater, want: Float): Float =
    abs(wrap(atan2(to.y - from.y, to.x - from.x) - want))

/** True if no opponent of [a] is within [Tuning.PASS_LANE_CLEAR] of the segment from [a] to [b]. */
private fun HockeySim.laneClear(a: Skater, b: Skater): Boolean {
    val ex = b.x - a.x; val ey = b.y - a.y
    val len2 = ex * ex + ey * ey
    val opps = skatersOf(a.side.other)
    for (i in opps.indices) {
        val o = opps[i]
        val t = if (len2 > 1e-6f) (((o.x - a.x) * ex + (o.y - a.y) * ey) / len2).coerceIn(0f, 1f) else 0f
        val dx = a.x + ex * t - o.x; val dy = a.y + ey * t - o.y
        if (dx * dx + dy * dy < Tuning.PASS_LANE_CLEAR * Tuning.PASS_LANE_CLEAR) return false
    }
    return true
}

/** Resolved at once on the first tick; the rest of [Tuning.POKE_TIME] is the stick staying out. */
private fun HockeySim.poke(s: Skater) {
    s.act = Act.POKE; s.actT = Tuning.POKE_TIME; s.pokeCd = Tuning.POKE_CD
    val dx = puck.x - s.x; val dy = puck.y - s.y
    val c = puck.carrier
    val hit = dx * dx + dy * dy <= Tuning.POKE_REACH * Tuning.POKE_REACH &&
        abs(wrap(atan2(dy, dx) - s.facing)) <= Tuning.POKE_ARC &&
        (c == null || (c.side != s.side && c.act != Act.DEKE)) && !puckHeld()
    if (hit) {
        val a = s.facing + (random.nextFloat() * 2f - 1f) * Tuning.POKE_SCATTER
        knockLoose(cos(a) * Tuning.POKE_PUCK_SPEED, sin(a) * Tuning.POKE_PUCK_SPEED, Tuning.PICKUP_LOCK)
        puck.passTarget = null
        s.pickupLock = Tuning.POKE_PICKUP_LOCK   // a loose puck to battle for, not an instant steal
    }
    events.add(SimEvent.Poke(hit))
}

/** Knocks down the first opponent in reach who isn't deking or already down; the check then ends. */
private fun HockeySim.bodyCheck(s: Skater) {
    val opps = skatersOf(s.side.other)
    for (i in opps.indices) {
        val o = opps[i]
        if (o.act == Act.DEKE || o.act == Act.DOWN) continue
        var nx = o.x - s.x; var ny = o.y - s.y
        val d2 = nx * nx + ny * ny
        if (d2 > Tuning.BODY_REACH * Tuning.BODY_REACH) continue
        val d = sqrt(d2)
        if (d > 1e-4f) { nx /= d; ny /= d } else { nx = cos(s.facing); ny = sin(s.facing) }
        if (puck.carrier === o) {
            knockLoose(nx * Tuning.HIT_PUCK_SPEED, ny * Tuning.HIT_PUCK_SPEED, Tuning.DOWN_TIME + 0.2f)
            puck.passTarget = null
        }
        o.act = Act.DOWN; o.actT = Tuning.DOWN_TIME; o.windup = 0f
        o.vx += nx * Tuning.HIT_PUSH; o.vy += ny * Tuning.HIT_PUSH
        s.act = Act.NONE; s.actT = 0f
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy)   // the check's burst is spent
        val cap = Tuning.MAX_SPEED * s.speedScale
        if (speed > cap) { s.vx *= cap / speed; s.vy *= cap / speed }
        events.add(SimEvent.Hit(s.id, o.id))
        return
    }
}
