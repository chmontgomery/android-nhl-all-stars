package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.SkaterInput
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Whole games against scripted human players: is each level the right kind of fun? */
class BalanceTest {
    private class Game(val home: Int, val away: Int, val steals: Int, val homeShots: Int, val awayShots: Int) {
        override fun toString() = "$home-$away st$steals sh$homeShots/$awayShots"
    }

    private class Games(val games: List<Game>) {
        val wins = games.count { it.home > it.away }
        val worstLoss = games.maxOf { it.away - it.home }
        val meanAbsDiff = games.map { abs(it.home - it.away) }.average()
        val meanSteals = games.map { it.steals }.average()
        val meanHomeShots = games.map { it.homeShots }.average()
        val meanAwayShots = games.map { it.awayShots }.average()
        val meanHome = games.map { it.home }.average()
        val meanAway = games.map { it.away }.average()
        override fun toString() =
            "wins $wins, mean ${"%.1f".format(meanHome)}-${"%.1f".format(meanAway)}, steals ${"%.1f".format(meanSteals)}, " +
                "shots ${"%.1f".format(meanHomeShots)}/${"%.1f".format(meanAwayShots)}: $games"
    }

    @Test fun kid_beats_easy_nearly_always() {
        // 18 of 20 as a share, now that the series runs over CLOSE_SEEDS (2026-11-17).
        assertTrue("$kidEasy", kidEasy.wins * 20 >= 18 * kidEasy.games.size)
    }

    @Test fun little_kid_beats_easy_most_of_the_time() {
        val r = littleEasy
        // 15 of 20 as a share, now that the series runs over CLOSE_SEEDS (2026-11-17).
        assertTrue("$r", r.wins * 20 >= 15 * r.games.size)
        assertTrue("$r", r.worstLoss <= 1)
        assertTrue("$r", r.games.all { it.home >= 2 })
    }

    /** Easy still plays hockey: it gets up ice and takes the odd shot, even against the kid bots. */
    @Test fun easy_takes_a_few_shots() {
        // Upper bound: old 10.0, new 12.5, over CLOSE_SEEDS: the 200-seed mean 11.6 plus 2 standard
        // errors (sd 3.5, 60 games). Re-baselined for zone faceoffs (2026-11-17).
        for (r in listOf(kidEasy, littleEasy)) assertTrue("$r", r.meanAwayShots in 2.5..12.5)
    }

    @Test fun kid_loses_to_hard_most_of_the_time() {
        // Old 6 of 20, new 22 of 60, over CLOSE_SEEDS: the 200-seed rate 0.26 with the old bound's
        // margin (0.30 over 0.21). Re-baselined for zone faceoffs (2026-11-17).
        assertTrue("$kidHard", kidHard.wins <= 22)
    }

    /** Wins measured over [CLOSE_SEEDS] games: 20 games swing too much to pin a win rate. */
    @Test fun skilled_player_and_hard_are_close() {
        val r = skilledHard
        val n = r.games.size
        assertTrue("$r", r.wins in n / 4..n * 3 / 4)
        assertTrue("$r", r.meanAbsDiff <= 3.0)
    }

    @Test fun hard_takes_the_puck_away() {
        assertTrue("$kidHard", kidHard.games.all { it.steals >= 8 })
    }

    @Test fun easy_rarely_takes_the_puck_away() {
        assertTrue("easy $kidEasy\nhard $kidHard", kidEasy.meanSteals * 3 < kidHard.meanSteals)
    }

    @Test fun games_have_shots_both_ways_on_hard() {
        for (r in listOf(kidHard, skilledHard)) {
            assertTrue("$r", r.meanHomeShots >= 5.0 && r.meanAwayShots >= 5.0)
        }
    }

    /** The design's Easy column: slow, late, rarely checks, weak shots at the goalie, a weak goalie. */
    @Test fun easy_is_within_the_design_bounds() {
        val e = Difficulty.EASY
        assertTrue("speed", e.speedScale in 0.70f..0.80f)
        assertTrue("reaction", e.reactionS >= 0.5f)
        assertTrue("pokes", e.pokePerSecond <= 0.3f)
        assertTrue("hits", e.hitPerSecond <= 0.1f)
        assertTrue("timing", e.timingError >= 0.7f)
        assertTrue("passing", e.passUnderPressure <= 0.3f)
        assertTrue("shot hold", e.shotHold <= 0.25f)
        assertTrue("corners", !e.aimsForCorners)
        assertTrue("goalie reaction", e.goalie.reactionS >= 0.3f)
        assertTrue("goalie speed", e.goalie.speed <= 3.5f)
        assertTrue("goalie saves", e.goalie.saveBase <= 0.4f)
    }

    /** shootRange is left out: shooting from further out is not stronger, only less patient. */
    @Test fun easy_is_never_stronger_than_hard() {
        val e = Difficulty.EASY; val h = Difficulty.HARD
        assertTrue(e.speedScale <= h.speedScale && e.reactionS >= h.reactionS)
        assertTrue(e.pokePerSecond <= h.pokePerSecond && e.hitPerSecond <= h.hitPerSecond)
        assertTrue(e.timingError >= h.timingError && e.passUnderPressure <= h.passUnderPressure)
        assertTrue(e.shotHold <= h.shotHold && (h.aimsForCorners || !e.aimsForCorners))
        assertTrue(e.goalie.reactionS >= h.goalie.reactionS && e.goalie.speed <= h.goalie.speed)
        assertTrue(e.goalie.saveBase <= h.goalie.saveBase)
        assertTrue(h.speedScale <= 1f)   // nobody outskates MAX_SPEED
    }

    /**
     * Medium is for a kid who can skate and shoot but rarely passes or dekes: a close-ish win, worked for.
     * Wins are counted over [CLOSE_SEEDS] games.
     */
    @Test fun kid_usually_wins_a_closer_game_on_medium() {
        val r = kidMedium
        // Old 12..17 of 20 (0.60..0.85), new 39..54 of 60 (0.64..0.90). Re-baselined for zone faceoffs (2026-11-17).
        assertTrue("$r", r.wins in 39..54)
        assertTrue("$r", r.meanHome in 5.0..9.0)
        assertTrue("$r", r.meanAway in 2.0..4.0)
    }

    @Test fun medium_takes_the_puck_away_more_than_easy_and_less_than_hard() {
        val msg = "easy $kidEasy\nmedium $kidMedium\nhard $kidHard"
        assertTrue(msg, kidEasy.meanSteals * 1.5 <= kidMedium.meanSteals)
        // Old factor 1.5, new 1.35 (still Medium < Hard). Re-baselined for zone faceoffs (2026-11-17).
        assertTrue(msg, kidMedium.meanSteals * 1.35 <= kidHard.meanSteals)
    }

    /**
     * The 6-year-old is below Medium: it mostly loses, but often gets on the scoreboard (in about
     * 45% of games). Measured over [CLOSE_SEEDS] games.
     */
    @Test fun little_kid_loses_to_medium_but_often_scores() {
        val r = littleMedium
        val n = r.games.size
        assertTrue("$r", r.games.count { it.home < it.away } > n / 2)
        // Test fix (2026-11-17): old "more than n/2", new "more than 0.35 n". The old bound never held
        // beyond seeds 1..20: the rate was 0.44 over 200 seeds before the zone faceoffs and is 0.46 now.
        assertTrue("$r", r.games.count { it.home >= 1 } > n * 0.35)
    }

    @Test fun skilled_player_beats_medium() {
        assertTrue("$skilledMedium", skilledMedium.wins >= 16)
    }

    @Test fun games_have_shots_both_ways_on_medium() {
        assertTrue("$kidMedium", kidMedium.meanHomeShots >= 5.0 && kidMedium.meanAwayShots >= 5.0)
    }

    /** Every field lies between Easy's and Hard's, inclusive; shootRange is left out as above. */
    @Test fun medium_lies_between_easy_and_hard() {
        val e = Difficulty.EASY; val m = Difficulty.MEDIUM; val h = Difficulty.HARD
        fun between(name: String, v: Float, a: Float, b: Float) =
            assertTrue("$name $v not between $a and $b", v in minOf(a, b)..maxOf(a, b))
        between("speed", m.speedScale, e.speedScale, h.speedScale)
        between("reaction", m.reactionS, e.reactionS, h.reactionS)
        between("pokes", m.pokePerSecond, e.pokePerSecond, h.pokePerSecond)
        between("hits", m.hitPerSecond, e.hitPerSecond, h.hitPerSecond)
        between("timing", m.timingError, e.timingError, h.timingError)
        between("passing", m.passUnderPressure, e.passUnderPressure, h.passUnderPressure)
        between("shot hold", m.shotHold, e.shotHold, h.shotHold)
        between("goalie reaction", m.goalie.reactionS, e.goalie.reactionS, h.goalie.reactionS)
        between("goalie speed", m.goalie.speed, e.goalie.speed, h.goalie.speed)
        between("goalie saves", m.goalie.saveBase, e.goalie.saveBase, h.goalie.saveBase)
        assertTrue("corners", !m.aimsForCorners)
        assertTrue("faceoffs ${e.faceoffWin} < ${m.faceoffWin} < ${h.faceoffWin}", e.faceoffWin < m.faceoffWin && m.faceoffWin < h.faceoffWin)
    }

    @Test fun slot_shots_beat_the_medium_goalie_between_the_easy_and_hard_rates() {
        val easy = slotGoals(Difficulty.EASY); val medium = slotGoals(Difficulty.MEDIUM); val hard = slotGoals(Difficulty.HARD)
        assertTrue("easy $easy medium $medium hard $hard", medium in hard + 1 until easy)
    }

    @Test fun slot_shots_beat_the_easy_goalie_most_of_the_time() {
        val goals = slotGoals(Difficulty.EASY)
        assertTrue("$goals of $SLOT_SHOTS", goals >= SLOT_SHOTS / 2)
    }

    @Test fun slot_shots_beat_the_hard_goalie_one_time_in_three_or_four() {
        val goals = slotGoals(Difficulty.HARD)
        assertTrue("$goals of $SLOT_SHOTS", goals in SLOT_SHOTS / 5..SLOT_SHOTS * 2 / 5)
    }

    private companion object {
        /**
         * A safety cap, not a balance bound: play time plus room for many goals and goalie catches,
         * each stopping play for the banner or whistle and a faceoff.
         */
        private const val TICK_LIMIT = 3 * 3 * 120 * 60
        private val SEEDS = 1L..20L
        private val CLOSE_SEEDS = 1L..60L

        // Seeds: old SEEDS, new CLOSE_SEEDS for every kid and little-kid series, so the steals
        // comparisons share one sample size. Re-baselined for zone faceoffs (2026-11-17).
        val kidEasy by lazy { series(Level.EASY, CLOSE_SEEDS) { KidBot(it)::input } }
        val littleEasy by lazy { series(Level.EASY, CLOSE_SEEDS) { LittleKidBot(it)::input } }
        val kidHard by lazy { series(Level.HARD, CLOSE_SEEDS) { KidBot(it)::input } }
        val kidMedium by lazy { series(Level.MEDIUM, CLOSE_SEEDS) { KidBot(it)::input } }
        val littleMedium by lazy { series(Level.MEDIUM, CLOSE_SEEDS) { LittleKidBot(it)::input } }
        val skilledMedium by lazy { series(Level.MEDIUM) { SkilledBot(it)::input } }
        val skilledHard by lazy { series(Level.HARD, CLOSE_SEEDS) { SkilledBot(it)::input } }

        private fun series(level: Level, seeds: LongRange = SEEDS, bot: (Random) -> (HockeySim) -> SkaterInput) =
            Games(seeds.map { play(level, it, bot(Random(it * 7919 + 3))) })

        const val SLOT_SHOTS = 100

        /**
         * Full wind-up shots with the stick centred, from 6-8 m out in the slot at the [difficulty]'s
         * goalie, which tracks normally. A centred stick aims [Tuning.SHOT_AIM] of the way to the
         * post on the side the goalie is not on when the shot is released, plus the usual spread.
         * Nobody else moves. Returns the goals.
         */
        fun slotGoals(difficulty: Difficulty): Int {
            var goals = 0
            for (seed in 1..SLOT_SHOTS) {
                val r = Random(seed)
                val sim = HockeySim(Random(seed), awayGoalie = difficulty.goalie)
                sim.humanControlled = false
                sim.controlledId = -1
                for (s in sim.skaters) { s.x = -20f + 2f * s.id; s.y = if (s.id % 2 == 0) 11f else -11f; s.vx = 0f; s.vy = 0f }
                val shooter = sim.skaters[0]
                shooter.x = Rink.GOAL_X - 6f - 2f * r.nextFloat() - Tuning.CARRY_OFFSET
                shooter.y = (r.nextFloat() * 2f - 1f) * 1.5f
                shooter.facing = 0f
                sim.giveTo(shooter)
                sim.startPlayForTest()
                var scored = false
                for (tick in 0 until 180) {
                    shooter.input.shoot = sim.puck.carrier === shooter && shooter.windup < Tuning.WINDUP_MAX
                    sim.step()
                    if (sim.events.any { it is SimEvent.Goal && it.scorer == Side.HOME }) { scored = true; break }
                    if (sim.events.any { it is SimEvent.Save }) break
                }
                if (scored) goals++
            }
            return goals
        }

        /**
         * One full game. A steal is the HOME carrier losing the puck to a poke or a hit, with AWAY
         * the next to pick it up.
         */
        private fun play(level: Level, seed: Long, bot: (HockeySim) -> SkaterInput): Game {
            val m = Match(level, seed)
            val sim = m.sim
            var ticks = 0
            var steals = 0; var homeShots = 0; var awayShots = 0
            var knocked = false
            while (sim.phase != Phase.FINAL && ticks < TICK_LIMIT) {
                val homeHad = sim.puck.carrier?.side == Side.HOME
                m.tick(bot(sim))
                ticks++
                var check = false; var release = false
                for (e in sim.events) {
                    when (e) {
                        is SimEvent.Shot -> { release = true; if (e.side == Side.HOME) homeShots++ else awayShots++ }
                        is SimEvent.Pass -> release = true
                        is SimEvent.Poke -> if (e.hit) check = true
                        is SimEvent.Hit -> check = true
                        is SimEvent.Pickup -> {
                            if (knocked && e.side == Side.AWAY) steals++
                            knocked = false
                        }
                        is SimEvent.Goal, SimEvent.FaceoffDrop -> knocked = false
                        else -> {}
                    }
                }
                if (homeHad && sim.puck.carrier == null && check && !release) knocked = true
            }
            assertEquals("seed $seed stopped after $ticks ticks", Phase.FINAL, sim.phase)
            return Game(sim.score[0], sim.score[1], steals, homeShots, awayShots)
        }
    }
}
