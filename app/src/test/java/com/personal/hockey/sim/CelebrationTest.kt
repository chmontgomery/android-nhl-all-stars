package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class CelebrationTest {
    private fun sim(): HockeySim {
        val sim = HockeySim(Random(1))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.spots() = FloatArray(skaters.size * 2) { if (it % 2 == 0) skaters[it / 2].x else skaters[it / 2].y }

    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}) =
        repeat(Math.round(seconds / Tuning.DT)) { each(it); step() }

    /** HOME skater 0 carries the puck into the AWAY net; returns the sim in GOAL. */
    private fun scored(): HockeySim {
        val sim = sim()
        // Skater 0 in the slot; its four teammates spread out within 15 m.
        val spots = arrayOf(12f to 0f, 3f to -8f, 2f to 6f, 0f to -3f, 5f to 9f)
        for ((i, p) in spots.withIndex()) sim.skaters[i].also { it.x = p.first; it.y = p.second }
        sim.giveTo(sim.skaters[0])
        sim.puck.carrier = null
        sim.puck.x = Rink.GOAL_X - 0.05f; sim.puck.y = 0.5f; sim.puck.vx = 12f; sim.puck.vy = 0f
        sim.run(Tuning.DT)
        assertEquals(Phase.GOAL, sim.phase)
        assertEquals(0, sim.lastScorerId)
        return sim
    }

    @Test fun teammates_gather_round_the_scorer() {
        val sim = scored()
        sim.run(3.0f)
        val scorer = sim.skaters[0]
        for (s in sim.skaters) {
            if (s.side != Side.HOME || s === scorer) continue
            val d = hypot(s.x - scorer.x, s.y - scorer.y)
            assertTrue("skater ${s.id} is $d away", d <= Tuning.CELEBRATE_GAP + 2 * Tuning.SKATER_R + 0.5f)
        }
    }

    @Test fun opponents_just_coast() {
        val sim = scored()
        for (s in sim.skaters) if (s.side == Side.AWAY) { s.vx = 3f; s.vy = 1f }
        val last = FloatArray(sim.skaters.size) { hypot(sim.skaters[it].vx, sim.skaters[it].vy) }
        sim.run(3.0f) {
            for (s in sim.skaters) {
                if (s.side != Side.AWAY) continue
                val v = hypot(s.vx, s.vy)
                assertTrue("skater ${s.id} sped up $v > ${last[s.id]}", v <= last[s.id] + 1e-4f)
                last[s.id] = v
            }
        }
    }

    @Test fun the_pause_lasts_four_seconds() {
        val sim = scored()
        sim.run(2.4f - Tuning.DT)
        assertEquals(Phase.GOAL, sim.phase)
        sim.run(0.2f)
        assertEquals(Phase.CELEBRATE, sim.phase)
        sim.run(1.3f - Tuning.DT)
        assertEquals(Phase.CELEBRATE, sim.phase)
        sim.run(0.15f + Tuning.DT)
        assertTrue("${sim.phase}", sim.phase == Phase.FACEOFF || sim.phase == Phase.BREAK)
    }

    @Test fun a_last_second_goal_celebrates_then_breaks() {
        val sim = sim()
        sim.clockS = Tuning.DT + 0.005f
        sim.giveTo(sim.skaters[0])
        sim.puck.carrier = null
        sim.puck.x = Rink.GOAL_X - 0.05f; sim.puck.y = 0.5f; sim.puck.vx = 12f; sim.puck.vy = 0f
        sim.run(Tuning.DT)
        assertEquals(Phase.GOAL, sim.phase)
        sim.run(Tuning.GOAL_TIME + 0.05f)
        assertEquals(Phase.CELEBRATE, sim.phase)
        sim.run(Tuning.CELEBRATE_TIME + 0.05f)
        assertEquals(Phase.BREAK, sim.phase)
    }

    @Test fun ai_draws_no_random_during_the_celebration() = aiRestsThrough(Phase.CELEBRATE)

    @Test fun ai_draws_no_random_during_the_whistle() = aiRestsThrough(Phase.WHISTLE)

    private fun aiRestsThrough(phase: Phase) {
        fun match() = com.personal.hockey.ai.Match(com.personal.hockey.settings.Level.HARD, 7L, humanControlled = false)
        val rested = match(); val fresh = match()
        for (m in listOf(rested, fresh)) m.sim.startPlayForTest()
        rested.sim.phase = phase; rested.sim.phaseT = 100f
        repeat(300) { rested.tick(null) }
        // Same spots at rest, live play for both: any random draw or timer the AI spent must show.
        for (m in listOf(rested, fresh)) {
            m.sim.phase = Phase.PLAY
            for ((i, s) in m.sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f; s.act = Act.NONE; s.actT = 0f }
            m.sim.puck.carrier = null; m.sim.puck.x = 0f; m.sim.puck.y = 0f; m.sim.puck.vx = 0f; m.sim.puck.vy = 0f
        }
        repeat(600) { rested.tick(null); fresh.tick(null) }
        org.junit.Assert.assertArrayEquals(fresh.sim.spots(), rested.sim.spots(), 1e-4f)
    }

    @Test fun nobody_steers_with_no_scorer() {
        val sim = sim()
        sim.phase = Phase.GOAL; sim.phaseT = Tuning.GOAL_TIME
        val s = sim.skaters[3]
        s.vx = 0f; s.vy = 0f
        sim.run(1f)
        assertEquals(0f, hypot(s.vx, s.vy), 1e-6f)
    }
}
