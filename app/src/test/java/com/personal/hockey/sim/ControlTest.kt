package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ControlTest {
    /** Live play with every skater parked along the near boards, out of the way. */
    private fun sim(): HockeySim {
        val sim = HockeySim(Random(1))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f; it.facing = 0f }

    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}) {
        repeat(Math.round(seconds / Tuning.DT)) { each(it); step() }
    }

    @Test fun carrier_is_controlled() {
        val sim = sim()
        sim.giveTo(sim.place(2, 0f, 5f))
        sim.step()
        assertEquals(2, sim.controlledId)
    }

    @Test fun passing_switches_to_the_receiver_at_once() {
        val sim = sim()
        val c = sim.place(0, 0f, 0f); sim.giveTo(c)
        sim.place(1, 0f, 8f)
        sim.step()
        assertEquals(0, sim.controlledId)
        c.input.moveY = 1f; c.input.pass = true
        sim.step()
        assertEquals(1, sim.puck.passTarget?.id)
        assertEquals(1, sim.controlledId)
    }

    @Test fun on_defence_nearest_home_skater_is_controlled() {
        val sim = sim()
        sim.giveTo(sim.place(5, -15f, 4f))
        sim.place(3, -17f, 3f)
        sim.run(0.5f)
        assertEquals(3, sim.controlledId)
    }

    @Test fun no_flicker_between_two_equidistant_skaters() {
        val sim = sim()
        sim.place(1, -2f, 4f); sim.place(2, 2f, 4f)
        sim.puck.x = 0f; sim.puck.y = 4f; sim.puck.vx = 0f; sim.puck.vy = 0f
        sim.step()
        var last = sim.controlledId
        var switches = 0
        sim.run(5f) { t ->
            sim.puck.x = if ((t / 10) % 2 == 0) 0.5f else -0.5f
            sim.puck.y = 4f; sim.puck.vx = 0f; sim.puck.vy = 0f
            if (sim.controlledId != last) { switches++; last = sim.controlledId }
        }
        assertTrue("switches $switches", switches <= 1)
    }

    @Test fun no_switch_during_an_action() {
        val sim = sim()
        val cur = sim.place(0, 0f, 0f)
        sim.puck.x = 0f; sim.puck.y = 2f       // out of stick reach: a check needs no puck on the stick
        sim.step()
        assertEquals(0, cur.id); assertEquals(0, sim.controlledId)
        sim.place(1, 15f, 5f)
        sim.puck.x = 15f; sim.puck.y = 3.6f; sim.puck.vx = 0f; sim.puck.vy = 0f   // outside skater 1's reach
        cur.input.body = true                 // a missed check: BODY then RECOVER
        sim.step()
        cur.input.body = false
        assertEquals(Act.BODY, cur.act)
        var ticks = 0
        while (cur.act != Act.NONE && ticks < 120) {
            sim.puck.vx = 0f; sim.puck.vy = 0f
            sim.step(); ticks++
            if (cur.act != Act.NONE) assertEquals(0, sim.controlledId)
        }
        assertEquals(Act.NONE, cur.act)
        sim.run(Tuning.SWITCH_TIME + 2 * Tuning.DT) { sim.puck.vx = 0f; sim.puck.vy = 0f }
        assertEquals(1, sim.controlledId)
    }

    @Test fun ai_only_game_has_no_controlled_skater() {
        val sim = HockeySim(Random(1))
        sim.humanControlled = false
        repeat(600) {
            sim.step()
            assertEquals(-1, sim.controlledId)
        }
    }

    @Test fun faceoff_gives_control_to_the_centre() {
        val sim = sim()
        sim.giveTo(sim.place(3, 0f, 0f))
        sim.step()
        assertEquals(3, sim.controlledId)
        sim.puck.carrier = null
        sim.puck.x = Rink.GOAL_X - 0.05f; sim.puck.y = 0.5f; sim.puck.vx = 12f; sim.puck.vy = 0f
        sim.run(Tuning.GOAL_TIME + Tuning.CELEBRATE_TIME + Tuning.DT)
        assertEquals(Phase.FACEOFF, sim.phase)
        assertEquals(0, sim.controlledId)
    }
}
