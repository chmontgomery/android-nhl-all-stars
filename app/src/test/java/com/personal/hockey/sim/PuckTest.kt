package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class PuckTest {
    /** A sim with every skater parked in a row along the near boards, out of the way. */
    private fun sim(seed: Int = 1): HockeySim {
        val sim = HockeySim(Random(seed))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f }

    private fun HockeySim.shoot(x: Float, y: Float, vx: Float, vy: Float) {
        puck.x = x; puck.y = y; puck.vx = vx; puck.vy = vy
    }

    /** Steps for [seconds], calling [each] before every tick; returns every event emitted. */
    private fun HockeySim.run(seconds: Float, each: () -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) {
            each()
            step()
            all += events
        }
        return all
    }

    private fun Puck.speed() = hypot(vx, vy)
    private fun Skater.speed() = hypot(vx, vy)

    @Test fun slides_and_slows() {
        val sim = sim(); sim.shoot(0f, 0f, 10f, 0f)
        sim.run(1f)
        assertTrue("x ${sim.puck.x}", sim.puck.x in 8f..10f)
        assertTrue("speed ${sim.puck.speed()}", sim.puck.speed() < 10f)
        assertNull(sim.puck.carrier)
    }

    @Test fun bounces_off_side_boards_with_loss() {
        val sim = sim(); sim.shoot(0f, 0f, 0f, 20f)
        val events = sim.run(2f)
        assertTrue("vy ${sim.puck.vy}", sim.puck.vy < 0f)
        assertTrue("vy ${sim.puck.vy}", abs(sim.puck.vy) < 20f * Tuning.BOARD_BOUNCE + 0.5f)
        assertEquals(1, events.count { it == SimEvent.Boards })
    }

    @Test fun never_leaves_rink() {
        val rnd = Random(42)
        repeat(500) {
            val sim = sim(seed = it)
            val a = rnd.nextFloat() * 2f * Math.PI.toFloat()
            sim.shoot(rnd.nextFloat() * 48f - 24f, rnd.nextFloat() * 20f - 10f, 40f * cos(a), 40f * sin(a))
            sim.run(4f) { assertTrue("shot $it at ${sim.puck.x},${sim.puck.y}", Rink.inside(sim.puck.x, sim.puck.y, 0f)) }
            assertTrue(Rink.inside(sim.puck.x, sim.puck.y, 0f))
        }
    }

    @Test fun fast_shot_does_not_tunnel_through_net_back() {
        val sim = sim(); sim.shoot(28.5f, 0f, -40f, 0f)
        var bounced = false
        sim.run(2f) {
            val p = sim.puck
            if (p.vx > 0f) bounced = true
            // Neither in front of the goal line having come through, nor inside the net.
            assertFalse("x ${p.x} y ${p.y}", p.x in 24.9f..(Rink.GOAL_X + Rink.NET_DEPTH) && abs(p.y) < Rink.GOAL_HALF_W)
        }
        assertTrue(bounced)
        assertTrue("x ${sim.puck.x}", sim.puck.x > Rink.GOAL_X + Rink.NET_DEPTH)
    }

    @Test fun puck_inside_net_cannot_leave_through_back_or_sides() {
        val sim = sim()
        sim.shoot(26.5f, 0f, 30f, 25f)
        sim.run(1f) {
            val p = sim.puck
            // The mouth is open, so it may bounce back out; past the goal line it must stay inside.
            if (p.x > Rink.GOAL_X) {
                assertTrue("x ${p.x} y ${p.y}", p.x < Rink.GOAL_X + Rink.NET_DEPTH && abs(p.y) < Rink.GOAL_HALF_W)
            }
        }
        assertTrue("x ${sim.puck.x}", sim.puck.x < Rink.GOAL_X)
    }

    @Test fun net_mouth_is_open() {
        val sim = sim(); sim.shoot(20f, 0.3f, 25f, 0f)
        var entered = false
        sim.run(0.5f) {
            sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W   // keep the goalie out of the way
            if (sim.puck.x > Rink.GOAL_X + 0.5f) entered = true
        }
        assertTrue(entered)
    }

    @Test fun slow_loose_puck_is_picked_up() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        sim.shoot(-1f, 0f, 5f, 0f)
        val events = sim.run(0.2f)
        assertSame(s, sim.puck.carrier)
        assertTrue(events.hasPickup(Side.HOME))
        assertEquals(Side.HOME, sim.puck.lastSide)
    }

    @Test fun fast_loose_puck_deflects() {
        val sim = sim(); sim.place(0, 0f, 0f)
        sim.shoot(-3f, 0.5f, 20f, 0f)
        sim.run(0.5f)
        assertNull(sim.puck.carrier)
        assertTrue("speed ${sim.puck.speed()}", sim.puck.speed() < 15f)
    }

    @Test fun pass_target_catches_fast_puck() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        sim.puck.passTarget = s
        sim.shoot(-3f, 0.5f, 25f, 0f)
        sim.run(0.3f)
        assertSame(s, sim.puck.carrier)
        assertNull(sim.puck.passTarget)
    }

    @Test fun carried_puck_stays_ahead_of_carrier() {
        val sim = sim(); val s = sim.place(0, -10f, 0f)
        sim.giveTo(s)
        sim.run(1f) { s.input.moveX = 1f; s.input.moveY = 0.5f }
        val ex = s.x + Tuning.CARRY_OFFSET * cos(s.facing)
        val ey = s.y + Tuning.CARRY_OFFSET * sin(s.facing)
        assertTrue("off by ${hypot(sim.puck.x - ex, sim.puck.y - ey)}", hypot(sim.puck.x - ex, sim.puck.y - ey) < 0.05f)
    }

    @Test fun carrier_is_slower() {
        fun topSpeed(withPuck: Boolean): Float {
            val sim = sim(); val s = sim.place(0, -25f, 3f)
            if (withPuck) sim.giveTo(s)
            sim.run(3f) { s.input.moveX = 1f }
            assertEquals(withPuck, sim.puck.carrier === s)
            return s.speed()
        }
        assertEquals(Tuning.CARRY_SPEED * topSpeed(false), topSpeed(true), 0.2f)
    }

    @Test fun locked_skater_cannot_repick() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        sim.giveTo(s)
        sim.step()
        sim.knockLoose(0f, 0f, 0.5f)
        assertNull(sim.puck.carrier)
        sim.run(0.48f) { assertNull(sim.puck.carrier) }
        sim.run(0.1f)
        assertSame(s, sim.puck.carrier)
    }

    @Test fun downed_skater_cannot_pick_up() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        s.act = Act.DOWN; s.actT = 1f
        sim.shoot(-1f, 0f, 2f, 0f)
        sim.run(0.3f)
        assertNull(sim.puck.carrier)
    }

    @Test fun give_to_attaches_and_clears_pass_target() {
        val sim = sim(); val s = sim.place(7, 5f, 0f)
        sim.puck.passTarget = sim.skaters[0]
        sim.giveTo(s)
        assertSame(s, sim.puck.carrier)
        assertNull(sim.puck.passTarget)
        assertEquals(Side.AWAY, sim.puck.lastSide)
        assertTrue(sim.events.hasPickup(Side.AWAY))
    }

    private fun stillPuckBehindNetIsFreed(x: Float, y: Float) {
        val sim = sim(); sim.shoot(x, y, 0f, 0f)
        var freed = false
        sim.run(4.5f) { if (abs(sim.puck.x) < Rink.GOAL_X || abs(sim.puck.y) > 2f) freed = true }
        assertTrue("puck stayed behind the net at ${sim.puck.x},${sim.puck.y}", freed)
    }

    @Test fun still_puck_behind_far_net_is_freed() = stillPuckBehindNetIsFreed(28.5f, 0f)

    @Test fun still_puck_behind_near_net_is_freed() = stillPuckBehindNetIsFreed(-28.5f, 0.5f)

    /** True if (x, y) is strictly inside either net's footprint. */
    private fun inNet(x: Float, y: Float): Boolean =
        abs(x) > Rink.GOAL_X && abs(x) < Rink.GOAL_X + Rink.NET_DEPTH && abs(y) < Rink.GOAL_HALF_W

    @Test fun pickup_behind_net_does_not_put_puck_in_net() {
        val sim = sim(); val s = sim.place(0, 27.8f, 0f)
        s.facing = Math.PI.toFloat()
        sim.giveTo(s)
        assertFalse("puck at ${sim.puck.x},${sim.puck.y}", inNet(sim.puck.x, sim.puck.y))
        sim.step()
        assertFalse("puck at ${sim.puck.x},${sim.puck.y}", inNet(sim.puck.x, sim.puck.y))
        assertSame(s, sim.puck.carrier)
    }

    @Test fun carrier_turning_behind_net_keeps_puck_out_of_net() {
        val sim = sim(); val s = sim.place(0, 28.2f, 2.5f)
        s.facing = -Math.PI.toFloat() / 2f
        sim.giveTo(s)
        // Skate down behind the net, then turn toward it.
        sim.run(1.5f) {
            s.input.moveX = if (s.y < 0f) -1f else 0f; s.input.moveY = if (s.y < 0f) 0f else -1f
            assertFalse("puck at ${sim.puck.x},${sim.puck.y}", inNet(sim.puck.x, sim.puck.y))
        }
    }

    @Test fun carrier_skating_past_post_keeps_puck() {
        // Skating along +x at y = 1.3, the skater's circle catches the post and slides round it.
        // The goalie stays frozen on the far post, out of reach: this is about the net, not saves.
        val sim = HockeySim(Random(1), awayGoalie = GoalieSkill(0f, 0f, 0.7f))
        for ((i, o) in sim.skaters.withIndex()) { o.x = -10f + 2f * i; o.y = -11f; o.vx = 0f; o.vy = 0f }
        sim.startPlayForTest()
        sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
        val s = sim.place(0, 20f, 1.3f)
        sim.giveTo(s)
        var touched = false
        sim.run(1.5f) {
            s.input.moveX = 1f
            if (abs(s.y - 1.3f) > 0.01f) touched = true
            val d = hypot(sim.puck.x - s.x, sim.puck.y - s.y)
            assertTrue("puck $d from carrier at ${s.x},${s.y}", d <= Tuning.CARRY_OFFSET + 0.1f)
            assertFalse("puck at ${sim.puck.x},${sim.puck.y}", inNet(sim.puck.x, sim.puck.y))
        }
        assertTrue("never touched the post", touched)
        assertSame(s, sim.puck.carrier)
    }

    @Test fun pass_target_beats_nearer_opponent_to_fast_pass() {
        val sim = sim(); val target = sim.place(0, 0f, 0f); val opp = sim.place(5, -0.3f, 0.9f)
        sim.puck.passTarget = target
        sim.shoot(-0.6f, 0.5f, 25f, 0f)
        // After one tick the puck is at about (-0.18, 0.5): 0.42 m from the opponent, 0.53 from the target.
        sim.step()
        assertSame(target, sim.puck.carrier)
    }

    @Test fun nearer_opponent_intercepts_slow_pass() {
        val sim = sim(); val target = sim.place(0, 0f, 0f); val opp = sim.place(5, -0.3f, 0.9f)
        sim.puck.passTarget = target
        sim.shoot(-0.6f, 0.5f, 8f, 0f)
        sim.step()
        assertSame(opp, sim.puck.carrier)
    }

    @Test fun still_puck_inside_net_leaves_through_the_mouth() {
        val sim = sim(); sim.shoot(26.6f, 0.3f, 0f, 0f)
        sim.run(Tuning.STUCK_TIME + 0.5f) {
            val p = sim.puck
            assertTrue("puck at ${p.x},${p.y}", abs(p.y) < Rink.GOAL_HALF_W)
        }
        assertTrue("x ${sim.puck.x}", sim.puck.x < Rink.GOAL_X)
    }

    @Test fun wedged_puck_is_freed() {
        val sim = sim(); sim.shoot(0f, 12.9f, 0f, 0f)
        var freed = false
        sim.run(3.5f) {
            if (sim.puck.speed() > 3f && sim.puck.vy < 0f) freed = true
        }
        assertTrue(freed)
    }
}
