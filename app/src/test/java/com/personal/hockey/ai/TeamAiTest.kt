package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

class TeamAiTest {
    /** Live play; AWAY on its faceoff spots, HOME parked idle along the far boards, puck loose at (px, py). */
    private fun world(px: Float, py: Float, seed: Int = 1): HockeySim {
        val sim = HockeySim(Random(seed))
        sim.humanControlled = false
        for (s in sim.skatersOf(Side.HOME)) park(s, 2f + 4f * s.pos.ordinal, 12f)
        sim.puck.x = px; sim.puck.y = py; sim.puck.vx = 0f; sim.puck.vy = 0f
        sim.startPlayForTest()
        return sim
    }

    private fun park(s: Skater, x: Float, y: Float) { s.x = x; s.y = y; s.vx = 0f; s.vy = 0f }

    private fun HockeySim.distToPuck(s: Skater) = hypot(s.x - puck.x, s.y - puck.y)

    /** Steps with [ai] thinking before every tick; [each] runs after each step. Returns all events. */
    private fun HockeySim.run(ai: TeamAi, seconds: Float, skipId: Int = -1, each: () -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) {
            ai.think(this, skipId)
            step()
            all += events
            each()
        }
        return all
    }

    @Test fun nearest_skater_goes_for_a_loose_puck() {
        for (level in Level.entries) {
            val sim = world(-8f, -5f)
            // Easy and Medium shoot from long range (it can reach the net they attack from here); the puck
            // flying off would hide the chase, so their shooting is turned off. Hard plays as it is.
            val d = Difficulty.of(level)
            val noLongShots = when (level) {
                Level.EASY, Level.MEDIUM -> d.copy(shootRange = 0f)
                Level.HARD -> d
            }
            val ai = TeamAi(Side.AWAY, noLongShots, Random(1))
            sim.run(ai, 2f)
            val near = sim.skatersOf(Side.AWAY).count { it === sim.puck.carrier || sim.distToPuck(it) < 2f }
            assertEquals("$level", 1, near)
        }
    }

    @Test fun carrier_heads_for_the_net_and_shoots() {
        for (level in Level.entries) {
            val sim = world(0f, 0f)
            val c = sim.skaters[5]
            park(c, 0f, 0f); c.facing = Math.PI.toFloat()
            sim.giveTo(c)
            val ai = TeamAi(Side.AWAY, Difficulty.of(level), Random(1))
            val shots = sim.run(ai, 6f).filterIsInstance<SimEvent.Shot>()
            assertTrue("$level", shots.any { it.side == Side.AWAY })
        }
    }

    @Test fun carrier_under_pressure_with_every_teammate_covered_keeps_moving() {
        val sim = world(8f, 0f)
        val away = sim.skatersOf(Side.AWAY)
        val home = sim.skatersOf(Side.HOME)
        val c = away[0]
        park(c, 8f, 0f); c.facing = Math.PI.toFloat()
        // Teammates behind the play, each with a HOME skater standing in his lane; a checker 2 m off.
        for (k in 1..4) {
            park(away[k], 10f + 3f * k, if (k % 2 == 0) 9f else -9f)
            park(home[k], away[k].x - 1.2f, away[k].y * 0.85f)
        }
        park(home[0], 8f, 2f)
        sim.giveTo(c)
        val ai = TeamAi(Side.AWAY, Difficulty.HARD, Random(1))
        var slowest = Float.MAX_VALUE
        var t = 0
        sim.run(ai, 2f) {
            t++
            if (t > 30) slowest = minOf(slowest, hypot(c.vx, c.vy))
        }
        assertTrue("still carrying", sim.puck.carrier === c)
        assertTrue("slowest $slowest", slowest > 3f)
    }

    /**
     * Lets AWAY chase a puck far up the boards, then moves it behind them. Returns the ticks until
     * the AWAY skater nearest the puck has its stick pushed and pointing at it.
     */
    private fun reactionTicks(difficulty: Difficulty): Int {
        val sim = world(-20f, 9f)
        val ai = TeamAi(Side.AWAY, difficulty, Random(1))
        sim.run(ai, 1f)
        sim.puck.x = 8f; sim.puck.y = -9f; sim.puck.vx = 0f; sim.puck.vy = 0f
        var ticks = 0
        while (ticks < 300) {
            ai.think(sim, -1)
            val s = sim.skatersOf(Side.AWAY).minBy { sim.distToPuck(it) }
            val mx = s.input.moveX; val my = s.input.moveY
            val toPuck = atan2(sim.puck.y - s.y, sim.puck.x - s.x)
            if (hypot(mx, my) > 0.5f && abs(wrapAngle(atan2(my, mx) - toPuck)) < 0.35f) break
            sim.step()
            ticks++
        }
        return ticks
    }

    private fun wrapAngle(a: Float): Float {
        var r = a
        while (r > Math.PI) r -= (2 * Math.PI).toFloat()
        while (r <= -Math.PI) r += (2 * Math.PI).toFloat()
        return r
    }

    @Test fun easy_reacts_later_than_hard() {
        val easy = reactionTicks(Difficulty.EASY)
        val hard = reactionTicks(Difficulty.HARD)
        assertTrue("easy $easy hard $hard", easy > hard + 15)
        assertTrue("hard reacts at all: $hard", hard < 60)
    }

    @Test fun skaters_never_beat_their_level_speed_cap() {
        for (level in Level.entries) {
            val match = Match(level, seed = 3, humanControlled = false)
            val cap = Difficulty.of(level).speedScale * Tuning.MAX_SPEED
            var max = 0f
            repeat(Math.round(20f / Tuning.DT)) {
                match.tick(null)
                for (s in match.sim.skatersOf(Side.AWAY)) {
                    // Skating under stick control; the scripted bursts of a deke, check or fall don't count.
                    val skating = s.act == Act.NONE || s.act == Act.WINDUP || s.act == Act.POKE
                    if (skating) max = maxOf(max, hypot(s.vx, s.vy))
                }
            }
            assertTrue("$level max $max", max <= cap + 0.1f)
            assertTrue("$level they skate: $max", max > 0.6f * Tuning.MAX_SPEED)
        }
    }

    @Test fun teammates_leave_the_puck_to_the_human() {
        val sim = world(2.5f, 0f)
        sim.humanControlled = true
        for (s in sim.skatersOf(Side.AWAY)) park(s, 10f + 2f * s.pos.ordinal, -12f)
        val home = sim.skatersOf(Side.HOME)
        park(home[0], 0f, 0f)
        park(home[1], -6f, 7f); park(home[2], -6f, -7f); park(home[3], -12f, 5f); park(home[4], -12f, -5f)
        val ai = TeamAi(Side.HOME, Difficulty.TEAMMATE, Random(1))
        var closest = Float.MAX_VALUE
        sim.run(ai, 2f, skipId = 0) {
            for (i in 1..4) closest = minOf(closest, sim.distToPuck(home[i]))
        }
        assertTrue("closest teammate $closest", closest > 2f)
    }

    @Test fun teammates_do_go_for_a_puck_the_human_is_far_from() {
        val sim = world(-8f, -5f)
        sim.humanControlled = true
        for (s in sim.skatersOf(Side.AWAY)) park(s, 10f + 2f * s.pos.ordinal, -12f)
        val home = sim.skatersOf(Side.HOME)
        park(home[0], 10f, 10f)
        park(home[1], -6f, 7f); park(home[2], -6f, -7f); park(home[3], -12f, 5f); park(home[4], -12f, -5f)
        val ai = TeamAi(Side.HOME, Difficulty.TEAMMATE, Random(1))
        sim.run(ai, 2f, skipId = 0)
        assertTrue((1..4).any { home[it] === sim.puck.carrier || sim.distToPuck(home[it]) < 2f })
    }

    @Test fun ai_never_writes_the_human_skater() {
        val sim = world(-2f, 3f)
        val ai = TeamAi(Side.HOME, Difficulty.TEAMMATE, Random(1))
        fun mark(s: Skater) {
            s.input.moveX = 0.37f; s.input.moveY = -0.21f
            s.input.shoot = false; s.input.pass = true; s.input.deke = false; s.input.poke = true; s.input.body = false
        }
        fun assertMarked(s: Skater) {
            val i = s.input
            assertTrue("id ${s.id}", i.moveX == 0.37f && i.moveY == -0.21f && !i.shoot && i.pass && !i.deke && i.poke && !i.body)
        }
        val human = sim.skaters[1]
        park(human, -3f, 3f)
        mark(human)
        sim.run(ai, 3f, skipId = 1) { assertMarked(human) }
        // Control can move every tick; whoever is skipped this tick is left alone.
        val away = TeamAi(Side.AWAY, Difficulty.HARD, Random(2))
        repeat(600) { t ->
            val skip = sim.skaters[t % 5]
            mark(skip)
            ai.think(sim, skip.id)
            away.think(sim, -1)
            assertMarked(skip)
            sim.step()
        }
    }

    @Test fun skater_released_by_the_human_decides_afresh_at_once() {
        val sim = world(0f, 0f)
        for (s in sim.skatersOf(Side.AWAY)) park(s, 10f + 2f * s.pos.ordinal, -12f)
        val home = sim.skatersOf(Side.HOME)
        for (s in home) park(s, -20f + 2f * s.pos.ordinal, -12f)
        val ai = TeamAi(Side.HOME, Difficulty.TEAMMATE.copy(reactionS = 1f), Random(1))
        val lw = home[1]
        ai.think(sim, -1)  // a decision for everyone, the LW included: his formation spot
        ai.think(sim, 1)   // then the human takes the LW
        // The human skates the LW right up to the puck, then control moves to the C, far away.
        park(lw, 2f, 0f)
        park(home[0], -20f, -8f)
        ai.think(sim, 0)
        // Nearest to a loose puck, the LW goes for it now, not a second later.
        assertTrue("stick (${lw.input.moveX}, ${lw.input.moveY})", lw.input.moveX < -0.9f && abs(lw.input.moveY) < 0.1f)
    }

    /**
     * [side]'s centre, driven straight up ice like a human would, carries the puck from its own half
     * into the attacking zone; with [pause], he first stops for 2 s just short of the blue line. The
     * other four are [side]'s AI with [difficulty]. The other team stands still along the far boards
     * behind the play. Calls [each] after every tick with the puck's x and its x when the tick began,
     * both in [side]'s attacking frame.
     */
    private fun rush(side: Side, difficulty: Difficulty, pause: Boolean, each: (HockeySim, Float, Float) -> Unit) {
        val sim = HockeySim(Random(1))
        sim.humanControlled = false
        val d = side.dir
        for (o in sim.skatersOf(side.other)) park(o, d * (-24f + 2f * o.pos.ordinal), d * 12f)
        val mates = sim.skatersOf(side)
        val c = mates[0]
        park(c, d * -10f, 0f); c.facing = if (d > 0f) 0f else Math.PI.toFloat()
        // Teammates start spread across the ice a few metres ahead of the carrier, as on a breakout.
        val ys = floatArrayOf(0f, 7f, -7f, 4f, -4f)
        val xs = floatArrayOf(-10f, -5f, -6f, -12f, -12f)
        for (i in 0 until 5) mates[i].speedScale = difficulty.speedScale   // as Match sets it
        for (i in 1 until 5) { park(mates[i], d * xs[i], d * ys[i]); mates[i].facing = c.facing }
        sim.giveTo(c)
        sim.startPlayForTest()
        val ai = TeamAi(side, difficulty, Random(1))
        var waited = 0f
        repeat(Math.round(7f / Tuning.DT)) {
            val before = sim.puck.x * d
            // Up ice (perhaps waiting at the line) until well into the zone, then stop short of the slot.
            c.input.clear()
            val waiting = pause && c.x * d > 4f && waited < 2f
            if (waiting) waited += Tuning.DT
            if (!waiting && c.x * d < 15f) c.input.moveX = d
            ai.think(sim, c.id)
            sim.step()
            for (o in sim.skatersOf(side.other)) park(o, d * (-24f + 2f * o.pos.ordinal), d * 12f)
            assertTrue("the carrier lost the puck", sim.puck.carrier === c)
            each(sim, sim.puck.x * d, before)
        }
    }

    @Test fun teammates_hold_onside_until_the_puck_enters_the_zone_then_go_in() {
        for (pause in listOf(false, true)) for (side in Side.entries)
            for ((name, difficulty) in listOf("teammate" to Difficulty.TEAMMATE) + Level.entries.map { it.name.lowercase() to Difficulty.of(it) }) {
            val label = "$side $name${if (pause) " after a pause" else ""}"
            var entryChecked = false
            var inZoneT = 0f
            var wentIn = false
            rush(side, difficulty, pause) { sim, px, before ->
                val mates = sim.skatersOf(side).drop(1)
                if (px < Rink.BLUE_X) {
                    for (m in mates) {
                        assertTrue("$label: ${m.pos} offside at ${m.x * side.dir} with the puck at $px", m.x * side.dir < Rink.BLUE_X)
                    }
                } else {
                    if (before < Rink.BLUE_X) {
                        // As the puck crosses, the wings are up at the line, not waiting far back;
                        // after a steady rush they are still skating.
                        for (m in mates.filter { it.pos == Position.LW || it.pos == Position.RW }) {
                            val x = m.x * side.dir; val vx = m.vx * side.dir
                            assertTrue("$label: ${m.pos} at $x, ${"%.1f".format(vx)} m/s as the puck crosses",
                                x > Rink.BLUE_X - 3f && (pause || vx > 1.5f))
                        }
                        entryChecked = true
                    }
                    inZoneT += Tuning.DT
                    if (inZoneT > 1.5f && mates.any { it.x * side.dir > Rink.BLUE_X + 2f }) wentIn = true
                }
            }
            assertTrue("$label: the puck never entered the zone", entryChecked)
            assertTrue("$label: nobody followed the puck into the zone", wentIn)
        }
    }
}
