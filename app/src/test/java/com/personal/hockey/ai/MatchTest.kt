package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.SkaterInput
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.Attributes
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Headless AI-vs-AI games. */
class MatchTest {
    /** Play time plus room for many goals, each stopping play for the banner and a faceoff. */
    private val tickLimit = 2 * 3 * 120 * 60

    private class Result(val home: Int, val away: Int, val ticks: Int, val gameEnds: Int)

    private fun play(match: Match, each: (Match) -> Unit = {}): Result {
        var ticks = 0
        var ends = 0
        while (match.sim.phase != Phase.FINAL && ticks < tickLimit) {
            match.tick(null)
            ticks++
            ends += match.sim.events.count { it == SimEvent.GameEnd }
            each(match)
        }
        assertEquals("stopped after $ticks ticks", Phase.FINAL, match.sim.phase)   // games always finish
        return Result(match.sim.score[0], match.sim.score[1], ticks, ends)
    }

    private fun aiGame(seed: Long, home: Difficulty, away: Difficulty) =
        Match(Level.HARD, seed, humanControlled = false, homeDifficulty = home, awayDifficulty = away)

    @Test fun a_full_game_finishes() {
        val match = aiGame(1, Difficulty.HARD, Difficulty.HARD)
        val r = play(match)
        assertEquals("after ${r.ticks} ticks", Phase.FINAL, match.sim.phase)
        assertEquals(1, r.gameEnds)
    }

    @Test fun puck_stays_in_the_rink_and_moves() {
        var stillTicks = 0
        var worst = 0
        play(aiGame(2, Difficulty.HARD, Difficulty.EASY)) { m ->
            val sim = m.sim
            val p = sim.puck
            assertTrue("puck (${p.x}, ${p.y})", Rink.inside(p.x, p.y, 0f))
            val loose = sim.phase == Phase.PLAY && p.carrier == null && !sim.puckHeld()
            stillTicks = if (loose && hypot(p.vx, p.vy) < Tuning.STUCK_SPEED) stillTicks + 1 else 0
            worst = maxOf(worst, stillTicks)
        }
        assertTrue("still for $worst ticks", worst <= 4 * 60)
    }

    @Test fun scoring_is_in_a_sane_range() {
        val totals = (1L..10L).map { val r = play(aiGame(it, Difficulty.HARD, Difficulty.HARD)); r.home + r.away }
        val mean = totals.average()
        assertTrue("goals per game $totals", mean in 2.0..14.0)
    }

    @Test fun hard_beats_easy() {
        var wins = 0; var homeGoals = 0; var awayGoals = 0
        val scores = ArrayList<String>()
        for (seed in 1L..20L) {
            val r = play(aiGame(seed, Difficulty.HARD, Difficulty.EASY))
            if (r.home > r.away) wins++
            homeGoals += r.home; awayGoals += r.away
            scores += "${r.home}-${r.away}"
        }
        assertTrue("wins $wins $scores", wins >= 15)
        assertTrue("goals $homeGoals-$awayGoals", homeGoals > 2 * awayGoals)
    }

    @Test fun faceoffs_favour_neither_side() {
        val wins = IntArray(2)
        for (seed in 1L..200L) {
            val m = aiGame(seed, Difficulty.HARD, Difficulty.HARD)
            var winner: com.personal.hockey.sim.Side? = null
            var guard = 0
            while (winner == null && guard++ < 600) {
                m.tick(null)
                winner = m.sim.events.firstNotNullOfOrNull { (it as? SimEvent.Pickup)?.side }
            }
            if (winner != null) wins[winner.index]++
        }
        val total = wins.sum()
        assertTrue("faceoffs decided $total of 200", total >= 190)
        for (side in 0..1) assertTrue("faceoff wins home ${wins[0]} away ${wins[1]}", wins[side] * 100 in 35 * total..65 * total)
    }

    @Test fun same_seed_same_game() {
        val a = play(aiGame(7, Difficulty.HARD, Difficulty.EASY))
        val b = play(aiGame(7, Difficulty.HARD, Difficulty.EASY))
        assertEquals(a.home, b.home); assertEquals(a.away, b.away); assertEquals(a.ticks, b.ticks)
    }

    @Test fun player_attributes_reach_the_sim() {
        val away = Teams.standardSkaters().map {
            if (it.position == Position.C) it.copy(attributes = Attributes(speed = 1.1f, shot = 1.2f)) else it
        }
        val m = Match(Level.EASY, 1, awaySkaters = away)
        assertEquals(1.1f * Difficulty.EASY.speedScale, m.sim.skaters[5].speedScale, 1e-4f)
        assertEquals(1.2f, m.sim.skaters[5].shotScale)
        assertEquals(Difficulty.EASY.speedScale, m.sim.skaters[6].speedScale, 1e-4f)
        assertEquals(1f, m.sim.skaters[0].speedScale, 1e-4f)
    }

    @Test fun a_button_held_across_a_control_switch_does_not_fire_on_the_new_skater() {
        val m = Match(Level.EASY, 1)
        val sim = m.sim
        val body = SkaterInput().apply { this.body = true }
        val none = SkaterInput()
        repeat(200) { m.tick(none) }   // through the faceoff into play
        for (s in sim.skaters) {       // spread out, so a check meets nobody and lasts its full time
            s.x = -20f + 4.5f * s.id; s.y = if (s.id % 2 == 0) 8f else -8f; s.vx = 0f; s.vy = 0f
        }
        sim.giveTo(sim.skaters[5])     // defending, so body is a check
        val first = sim.controlledId
        m.tick(body)                   // a press: the controlled skater lunges
        assertEquals(com.personal.hockey.sim.Act.BODY, sim.skaters[first].act)
        // Switch to a teammate that is free to act and keep holding.
        val next = sim.skatersOf(com.personal.hockey.sim.Side.HOME).first {
            it.id != first && it !== sim.puck.carrier && it.act == com.personal.hockey.sim.Act.NONE
        }
        sim.controlledId = next.id
        m.tick(body)
        assertTrue("held body fired on the new skater", next.act != com.personal.hockey.sim.Act.BODY)
        m.tick(none)
        m.tick(body)                   // a fresh press does
        assertEquals(com.personal.hockey.sim.Act.BODY, next.act)
    }

    @Test fun a_roster_missing_a_position_plays_with_standard_attributes() {
        val away = Teams.standardSkaters().filter { it.position != Position.C }
        val m = Match(Level.EASY, 1, awaySkaters = away)
        assertEquals(Difficulty.EASY.speedScale, m.sim.skaters[5].speedScale, 1e-4f)
        assertEquals(1f, m.sim.skaters[5].shotScale)
        repeat(300) { m.tick(null) }
    }

    @Test fun human_input_drives_the_controlled_skater() {
        val m = Match(Level.EASY, 1)
        val input = SkaterInput().apply { moveX = 1f }
        repeat(200) { m.tick(input) }   // through the faceoff into play
        val c = m.sim.skaters[m.sim.controlledId]
        assertEquals(1f, c.input.moveX, 0f)
        assertEquals(0f, c.input.moveY, 0f)
    }
}
