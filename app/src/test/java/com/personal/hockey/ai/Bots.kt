package com.personal.hockey.ai

import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.SkaterInput
import com.personal.hockey.sim.Tuning
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** The HOME skater the human drives, or null when nobody is. */
private fun HockeySim.me(): Skater? = if (controlledId >= 0) skaters[controlledId] else null

private fun nearestOpponent(sim: HockeySim, s: Skater): Skater? {
    var best: Skater? = null; var bestD = Float.MAX_VALUE
    for (o in sim.skatersOf(Side.AWAY)) {
        if (o.act == Act.DOWN) continue
        val d = hypot(o.x - s.x, o.y - s.y)
        if (d < bestD) { best = o; bestD = d }
    }
    return best
}

private fun wrap(a: Float): Float {
    var r = a
    val pi = Math.PI.toFloat()
    while (r > pi) r -= 2f * pi
    while (r <= -pi) r += 2f * pi
    return r
}

/**
 * A 6-year-old: skates at the opponent net with the puck and at the puck without it, never quite
 * straight; shoots whenever near the net without much wind-up; mashes Poke and Body on defence;
 * never passes or dekes.
 */
class KidBot(private val random: Random) {
    private val out = SkaterInput()
    private var wobble = 0f
    private var wobbleT = 0f
    private var shootTicks = 0       // ticks left holding Shoot; 0 = not shooting
    private var holdingShoot = false

    fun input(sim: HockeySim): SkaterInput {
        out.clear()
        if (sim.phase == Phase.CELEBRATE || sim.phase == Phase.WHISTLE) return out   // a stoppage: draw nothing
        val me = sim.me() ?: return out
        wobbleT -= Tuning.DT
        if (wobbleT <= 0f) { wobbleT += WOBBLE_EVERY; wobble = (random.nextFloat() * 2f - 1f) * WOBBLE }
        val carrying = sim.puck.carrier === me
        val tx = if (carrying) Rink.GOAL_X else sim.puck.x
        val ty = if (carrying) 0f else sim.puck.y
        val a = atan2(ty - me.y, tx - me.x) + wobble
        out.moveX = cos(a); out.moveY = sin(a)

        if (carrying) {
            if (holdingShoot) {
                shootTicks--
                if (shootTicks > 0) out.shoot = true else holdingShoot = false   // release fires the shot
            } else if (me.act == Act.NONE && hypot(Rink.GOAL_X - me.x, me.y) < SHOOT_RANGE) {
                shootTicks = 1 + random.nextInt(MAX_HOLD_TICKS)
                holdingShoot = true
                out.shoot = true
            }
        } else {
            holdingShoot = false
            val homeHasIt = sim.puck.carrier?.side == Side.HOME
            if (!homeHasIt && random.nextFloat() < MASH_PER_SECOND * Tuning.DT) {
                if (random.nextBoolean()) out.poke = true else out.body = true
            }
        }
        return out
    }

    private companion object {
        const val WOBBLE = 0.5f
        const val WOBBLE_EVERY = 0.5f
        const val SHOOT_RANGE = 15f
        const val MAX_HOLD_TICKS = 30           // 0.5 s
        const val MASH_PER_SECOND = 2f
    }
}

/**
 * A real 6-year-old, weaker than [KidBot]: the stick wanders far off line and is often let go
 * altogether; shoots now and then once inside the offensive zone; presses Poke or Body about once a
 * second on defence; never passes or dekes.
 */
class LittleKidBot(private val random: Random) {
    private val out = SkaterInput()
    private var wobble = 0f
    private var letGo = false
    private var wobbleT = 0f
    private var shootTicks = 0       // ticks left holding Shoot
    private var holdingShoot = false

    fun input(sim: HockeySim): SkaterInput {
        out.clear()
        if (sim.phase == Phase.CELEBRATE || sim.phase == Phase.WHISTLE) return out   // a stoppage: draw nothing
        val me = sim.me() ?: return out
        wobbleT -= Tuning.DT
        if (wobbleT <= 0f) {
            wobbleT += WOBBLE_EVERY
            wobble = (random.nextFloat() * 2f - 1f) * WOBBLE
            letGo = random.nextFloat() < LET_GO
        }
        val carrying = sim.puck.carrier === me
        if (!letGo) {
            val tx = if (carrying) Rink.GOAL_X else sim.puck.x
            val ty = if (carrying) 0f else sim.puck.y
            val a = atan2(ty - me.y, tx - me.x) + wobble
            out.moveX = cos(a); out.moveY = sin(a)
        }

        if (carrying) {
            if (holdingShoot) {
                shootTicks--
                if (shootTicks > 0) out.shoot = true else holdingShoot = false   // release fires the shot
            } else if (me.act == Act.NONE && me.x > Rink.BLUE_X && random.nextFloat() < SHOTS_PER_SECOND * Tuning.DT) {
                shootTicks = 1 + random.nextInt(MAX_HOLD_TICKS)
                holdingShoot = true
                out.shoot = true
            }
        } else {
            holdingShoot = false
            val homeHasIt = sim.puck.carrier?.side == Side.HOME
            if (!homeHasIt && random.nextFloat() < MASH_PER_SECOND * Tuning.DT) {
                if (random.nextBoolean()) out.poke = true else out.body = true
            }
        }
        return out
    }

    private companion object {
        const val WOBBLE = 1.2f
        const val WOBBLE_EVERY = 0.7f
        const val LET_GO = 0.3f                 // share of the time the stick is let go entirely
        const val SHOTS_PER_SECOND = 0.5f
        const val MAX_HOLD_TICKS = 30           // 0.5 s
        const val MASH_PER_SECOND = 1f
    }
}

/**
 * A competent player: carries toward the slot around the nearest checker, dekes a close checker,
 * passes out of pressure, shoots with a full wind-up at the open side from close in; on defence
 * chases the carrier and pokes when the poke would reach. Like a person it reacts late: it chases
 * the puck as it was [REACTION_TICKS] ago and presses Poke that long after seeing the chance.
 * Without that 0.2 s delay its pokes land ~85% of the time (a frame-perfect, superhuman checker
 * that beat Hard 11-0); with it about 30% land, which is what a good thumb on a touch stick manages.
 */
class SkilledBot(private val random: Random) {
    private val out = SkaterInput()
    private var winding = false
    private var lastPress = 0          // ticks since the last pass/deke/poke press, so presses are edges
    private var pokeIn = -1            // ticks until a poke the player has decided on is pressed
    // The puck as the player saw it, oldest at [head]: a human reacts to what was there a moment ago.
    private val seenX = FloatArray(REACTION_TICKS); private val seenY = FloatArray(REACTION_TICKS)
    private val seenVx = FloatArray(REACTION_TICKS); private val seenVy = FloatArray(REACTION_TICKS)
    private var head = 0

    fun input(sim: HockeySim): SkaterInput {
        out.clear()
        if (sim.phase == Phase.CELEBRATE || sim.phase == Phase.WHISTLE) return out
        lastPress++
        val puck = sim.puck
        seenX[head] = puck.x; seenY[head] = puck.y; seenVx[head] = puck.vx; seenVy[head] = puck.vy
        head = (head + 1) % REACTION_TICKS
        val me = sim.me() ?: run { winding = false; pokeIn = -1; return out }
        if (puck.carrier === me) { pokeIn = -1; carry(sim, me) } else { winding = false; defend(sim, me) }
        return out
    }

    private fun carry(sim: HockeySim, me: Skater) {
        val netX = Rink.GOAL_X
        val gy = sim.goalies[Side.AWAY.index].y
        val openSide = if (gy > 0f) -1f else 1f
        if (winding) {
            steerAt(me, netX, 0f)
            if (me.act == Act.WINDUP && me.windup < Tuning.WINDUP_MAX) {
                out.shoot = true
            } else {
                out.moveX = 0f; out.moveY = openSide   // release, aiming at the open side
                winding = false
            }
            return
        }
        val toNet = hypot(netX - me.x, me.y)
        if (toNet < SHOOT_RANGE && netX - me.x > 1.5f && me.act == Act.NONE) {
            winding = true
            steerAt(me, netX, 0f)
            out.shoot = true
            return
        }
        val opp = nearestOpponent(sim, me)
        val oppD = if (opp == null) Float.MAX_VALUE else hypot(opp.x - me.x, opp.y - me.y)
        if (opp != null && oppD < DEKE_RANGE && me.dekeCd <= 0f && me.act == Act.NONE && lastPress > 1) {
            var nx = -(me.y - opp.y) / oppD; var ny = (me.x - opp.x) / oppD
            if (nx < 0f) { nx = -nx; ny = -ny }
            out.moveX = nx; out.moveY = ny; out.deke = true
            lastPress = 0
            return
        }
        if (opp != null && oppD < PRESSURE_RANGE && me.act == Act.NONE && lastPress > 1 &&
            random.nextFloat() < PASS_CHANCE_PER_TICK) {
            // Who a centred-stick pass would reach (the sim reads the stick from the skater's input).
            me.input.moveX = 0f; me.input.moveY = 0f
            val t = sim.passTargetFor(me)
            if (t != null && t.x > me.x - 3f) {
                out.pass = true   // stick centred: the open man furthest up ice
                lastPress = 0
                return
            }
        }
        var dx = netX - SLOT_OUT - me.x; var dy = -me.y
        var d = hypot(dx, dy).coerceAtLeast(1e-3f)
        dx /= d; dy /= d
        if (opp != null && oppD < AVOID_RANGE) {
            val w = 1.5f * (AVOID_RANGE - oppD) / AVOID_RANGE / oppD.coerceAtLeast(0.1f)
            dx += (me.x - opp.x) * w; dy += (me.y - opp.y) * w
            d = hypot(dx, dy).coerceAtLeast(1e-3f)
            dx /= d; dy /= d
        }
        out.moveX = dx; out.moveY = dy
    }

    private fun defend(sim: HockeySim, me: Skater) {
        val puck = sim.puck
        val c = puck.carrier
        if (c != null && c.side == Side.HOME) { pokeIn = -1; return }   // a teammate has it; control will follow
        val i = head   // the oldest entry: the puck as it was REACTION_TICKS ago
        steerAt(me, seenX[i] + seenVx[i] * LEAD_S, seenY[i] + seenVy[i] * LEAD_S)
        if (pokeIn > 0) {
            if (--pokeIn == 0) { out.poke = true; lastPress = 0; pokeIn = -1 }
            return
        }
        if (c == null || me.act != Act.NONE || me.pokeCd > 0f || lastPress <= 1 || c.act == Act.DEKE) return
        val px = puck.x - me.x; val py = puck.y - me.y
        if (px * px + py * py <= Tuning.POKE_REACH * Tuning.POKE_REACH &&
            abs(wrap(atan2(py, px) - me.facing)) <= Tuning.POKE_ARC) {
            pokeIn = REACTION_TICKS   // sees the chance now, presses a reaction time later
        }
    }

    private fun steerAt(me: Skater, x: Float, y: Float) {
        val dx = x - me.x; val dy = y - me.y
        val d = hypot(dx, dy)
        if (d < 1e-3f) return
        out.moveX = dx / d; out.moveY = dy / d
    }

    private companion object {
        const val SHOOT_RANGE = 10f
        const val DEKE_RANGE = 1.8f
        const val PRESSURE_RANGE = 2.5f
        const val PASS_CHANCE_PER_TICK = 0.05f
        const val AVOID_RANGE = 3f
        const val SLOT_OUT = 5f
        const val LEAD_S = 0.2f
        const val REACTION_TICKS = 12          // 0.2 s
    }
}
