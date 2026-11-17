package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScorerTest {
    private fun sim(): HockeySim {
        val sim = HockeySim(Random(1))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float, facing: Float = 0f): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f; it.facing = facing }

    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) { each(it); step(); all += events }
        return all
    }

    /** Frees the puck just outside the AWAY net's mouth, heading in. */
    private fun HockeySim.intoAwayNet() {
        puck.carrier = null
        puck.x = Rink.GOAL_X - 0.05f; puck.y = 0.5f; puck.vx = 12f; puck.vy = 0f
    }

    @Test fun the_last_skater_to_carry_it_is_the_scorer() {
        val sim = sim()
        sim.giveTo(sim.skaters[2])
        sim.intoAwayNet()
        val goal = sim.run(Tuning.DT).filterIsInstance<SimEvent.Goal>().single()
        assertEquals(Side.HOME, goal.scorer)
        assertEquals(2, goal.scorerId)
        assertEquals(2, sim.lastScorerId)
    }

    @Test fun lastScorerId_is_minus_one_before_any_goal() {
        assertEquals(-1, sim().lastScorerId)
    }

    @Test fun a_pass_then_shot_credits_the_shooter() {
        val sim = sim()
        val passer = sim.place(1, 0f, 0f); val mate = sim.place(3, 0f, 6f)
        sim.giveTo(passer)
        passer.input.moveY = 1f; passer.input.pass = true
        val events = sim.run(1f) { if (it > 0) { passer.input.pass = false; passer.input.moveY = 0f } }
        assertTrue("no pickup by 3: $events", events.contains(SimEvent.Pickup(Side.HOME, 3)))
        assertEquals(3, sim.puck.carrier?.id)
        sim.intoAwayNet()
        val goal = sim.run(Tuning.DT).filterIsInstance<SimEvent.Goal>().single()
        assertEquals(3, goal.scorerId)
        assertEquals(mate.id, sim.lastScorerId)
    }

    @Test fun an_own_goal_credits_the_scoring_sides_skater_nearest_that_net() {
        val sim = sim()
        // HOME skater 4 is the closest to x = +GOAL_X; AWAY skater 7 carries it into that net.
        sim.place(4, 20f, 3f)
        sim.place(0, -20f, 0f)
        sim.giveTo(sim.skaters[7])
        sim.intoAwayNet()
        val goal = sim.run(Tuning.DT).filterIsInstance<SimEvent.Goal>().single()
        assertEquals(Side.HOME, goal.scorer)
        assertEquals(4, goal.scorerId)
        assertEquals(4, sim.lastScorerId)
    }

    @Test fun shot_pass_and_pickup_events_carry_ids() {
        val sim = sim()
        val s = sim.place(2, 0f, 0f)
        sim.place(4, 0f, -6f)
        sim.giveTo(s)
        val pass = sim.run(Tuning.DT) { s.input.moveY = -1f; s.input.pass = true }
        assertTrue("$pass", pass.contains(SimEvent.Pass(Side.HOME, 2, 4)))
        s.input.clear()
        val pick = sim.run(1f)
        assertTrue("$pick", pick.contains(SimEvent.Pickup(Side.HOME, 4)))

        val shooter = sim.skaters[4]
        sim.run(2f)   // let it settle in carrying
        val shot = sim.run(5 * Tuning.DT) { shooter.input.shoot = true } +
            sim.run(Tuning.DT) { shooter.input.shoot = false }
        val e = shot.filterIsInstance<SimEvent.Shot>().single()
        assertEquals(4, e.shooterId)
        assertEquals(Side.HOME, e.side)
    }
}
