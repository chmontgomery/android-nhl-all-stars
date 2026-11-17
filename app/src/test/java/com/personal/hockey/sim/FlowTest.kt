package com.personal.hockey.sim

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FlowTest {
    /** A fresh game: in FACEOFF with everyone on their spots. */
    private fun game(seed: Int = 1, away: GoalieSkill = GoalieSkill(0.1f, 6f, 0.7f),
                     home: GoalieSkill = GoalieSkill(0.1f, 6f, 0.7f)) = HockeySim(Random(seed), home, away)

    /** Live play with every skater parked along the near boards. */
    private fun play(seed: Int = 1, home: GoalieSkill = GoalieSkill(0.1f, 6f, 0.7f)): HockeySim {
        val sim = game(seed, home = home)
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) {
            each(it)
            step()
            all += events
        }
        return all
    }

    /** Loose puck just past the AWAY goalie, crossing the +x goal line next tick. */
    private fun HockeySim.puckIntoAwayNet() {
        puck.carrier = null
        puck.x = Rink.GOAL_X - 0.05f; puck.y = 0.5f; puck.vx = 12f; puck.vy = 0f
    }

    /** Expected faceoff spot of every skater, as [x0, y0, x1, y1, ...]. */
    private fun spots(): FloatArray {
        val back = floatArrayOf(1.2f, 6f, 6f, 12f, 12f)
        val wide = floatArrayOf(0f, 7f, -7f, 5f, -5f)
        val out = FloatArray(20)
        for (id in 0 until 10) {
            val dir = if (id < 5) 1f else -1f
            out[id * 2] = -dir * back[id % 5]; out[id * 2 + 1] = dir * wide[id % 5]
        }
        return out
    }

    private fun HockeySim.positions(): FloatArray =
        FloatArray(20) { if (it % 2 == 0) skaters[it / 2].x else skaters[it / 2].y }

    @Test fun starts_in_faceoff_then_drops() {
        val sim = game()
        assertEquals(Phase.FACEOFF, sim.phase)
        assertEquals(120f, sim.clockS, 0f)
        assertEquals(1, sim.period)
        assertArrayEquals(spots(), sim.positions(), 1e-4f)
        val before = sim.run(Tuning.FACEOFF_TIME - Tuning.DT) { for (s in sim.skaters) s.input.moveX = 1f }
        assertTrue(before.none { it == SimEvent.FaceoffDrop })
        assertEquals(Phase.FACEOFF, sim.phase)
        assertArrayEquals("nobody moves", spots(), sim.positions(), 1e-4f)
        val drop = sim.run(Tuning.DT)
        assertTrue(drop.contains(SimEvent.FaceoffDrop))
        assertEquals(Phase.PLAY, sim.phase)
        val v = kotlin.math.hypot(sim.puck.vx, sim.puck.vy)
        assertEquals("drop speed $v", Tuning.FACEOFF_WIN_SPEED, v, 0.2f)
    }

    @Test fun nobody_picks_up_the_dropped_puck_for_the_faceoff_lock() {
        for (seed in 1..20) {
            val sim = game(seed)
            sim.run(Tuning.FACEOFF_TIME)
            var dropped = false
            var liveTicks = 0
            while (liveTicks < 60) {
                sim.step()
                if (sim.events.contains(SimEvent.FaceoffDrop)) dropped = true
                if (!dropped) continue
                val t = liveTicks * Tuning.DT
                if (t < Tuning.FACEOFF_PICKUP_LOCK - Tuning.DT) {
                    assertEquals("seed $seed: picked up ${t}s after the drop", null, sim.puck.carrier)
                }
                liveTicks++
            }
        }
    }

    @Test fun won_draw_goes_back_to_winners_defence() {
        for (dot in arrayOf(0f to 0f, Rink.FACEOFF_X to -Rink.FACEOFF_Y, -Rink.FACEOFF_X to Rink.FACEOFF_Y)) {
            for (winner in Side.entries) for (seed in 1..10) {
                val sim = game(seed)
                sim.faceoffWinAway = if (winner == Side.AWAY) 1f else 0f
                sim.startFaceoff(dot.first, dot.second)
                val ld = sim.skatersOf(winner)[Position.LD.ordinal]; val rd = sim.skatersOf(winner)[Position.RD.ordinal]
                val at = "dot $dot winner $winner seed $seed"
                val drop = sim.run(Tuning.FACEOFF_TIME)
                assertTrue(at, drop.contains(SimEvent.FaceoffDrop))
                // Off the dot toward the midpoint of the winner's defencemen, give or take the spread.
                val want = kotlin.math.atan2((ld.y + rd.y) / 2f - dot.second, (ld.x + rd.x) / 2f - dot.first)
                val got = kotlin.math.atan2(sim.puck.vy, sim.puck.vx)
                var off = abs(got - want); if (off > kotlin.math.PI) off = (2 * kotlin.math.PI - off).toFloat()
                assertTrue("$at off $off", off <= Tuning.FACEOFF_SPREAD + 1e-3f)
                assertEquals(at, Tuning.FACEOFF_WIN_SPEED, kotlin.math.hypot(sim.puck.vx, sim.puck.vy), 0.2f)
                // With nobody steering, the winner's side picks it up first.
                var first: Side? = null
                sim.run(2f) { if (first == null) first = sim.puck.carrier?.side }
                assertEquals(at, winner, first ?: sim.puck.carrier?.side)
            }
        }
    }

    @Test fun losing_centre_is_tied_up() {
        val sim = game()
        sim.faceoffWinAway = 1f   // HOME's centre loses the draw
        sim.run(Tuning.FACEOFF_TIME)
        assertEquals(Phase.PLAY, sim.phase)
        val c = sim.skatersOf(Side.HOME)[Position.C.ordinal]
        val winner = sim.skatersOf(Side.AWAY)[Position.C.ordinal]
        assertTrue(c.tiedUpT > 0f)
        assertEquals(0f, winner.tiedUpT, 0f)
        // Everyone else out of the way; a loose puck on the loser's stick.
        for (s in sim.skaters) if (s !== c) { s.x = -20f + 2f * s.id; s.y = -11f; s.vx = 0f; s.vy = 0f }
        c.x = 0f; c.y = 0f; c.vx = 0f; c.vy = 0f; c.facing = 0f
        sim.puck.carrier = null; sim.puck.x = 0.5f; sim.puck.y = 0f; sim.puck.vx = 0f; sim.puck.vy = 0f
        val tied = Math.round(Tuning.TIE_UP_S / Tuning.DT) - 2
        val during = sim.run(tied * Tuning.DT) { t ->
            c.input.poke = t % 2 == 0; c.input.body = t % 2 == 1
            sim.puck.x = 0.5f; sim.puck.y = 0f; sim.puck.vx = 0f; sim.puck.vy = 0f
        }
        assertTrue("events $during", during.none { it is SimEvent.Poke || it is SimEvent.Pickup })
        assertEquals(null, sim.puck.carrier)
        assertTrue(c.act == Act.NONE)
        // Once he is free: he picks it up, and a poke at an opposing carrier goes off.
        val after = sim.run(0.2f) { c.input.poke = false; c.input.body = false }
        assertTrue("events $after", after.contains(SimEvent.Pickup(Side.HOME, c.id)))
        assertEquals(0f, c.tiedUpT, 0f)
        val opp = sim.skatersOf(Side.AWAY)[Position.LW.ordinal]
        opp.x = 1.4f; opp.y = 0f; opp.facing = kotlin.math.PI.toFloat()
        sim.giveTo(opp)
        val poke = sim.run(Tuning.DT) { c.input.poke = true }
        assertTrue("events $poke", poke.any { it is SimEvent.Poke })
    }

    @Test fun clock_only_runs_in_play() {
        val sim = game()
        sim.run(Tuning.FACEOFF_TIME - Tuning.DT)
        assertEquals(120f, sim.clockS, 0f)
        sim.run(Tuning.DT)              // the drop tick
        sim.run(1f)
        assertEquals(119f, sim.clockS, 1e-3f)
        sim.puckIntoAwayNet()
        sim.run(Tuning.DT)
        assertEquals(Phase.GOAL, sim.phase)
        val atGoal = sim.clockS
        sim.run(2f)
        assertEquals(atGoal, sim.clockS, 0f)
    }

    @Test fun clock_text() {
        val sim = game()
        sim.clockS = 120f; assertEquals("20:00", sim.clockText()); assertEquals(1200, sim.displaySeconds)
        sim.clockS = 60f; assertEquals("10:00", sim.clockText())
        sim.clockS = 0.05f; assertEquals("0:01", sim.clockText())
        sim.clockS = 0f; assertEquals("0:00", sim.clockText())
        sim.clockS = 6.5f; assertEquals("1:05", sim.clockText())
    }

    @Test fun goal_scores_once_and_resets_to_faceoff() {
        val sim = play()
        sim.puckIntoAwayNet()
        val first = sim.run(Tuning.DT)
        assertTrue(first.hasGoal(Side.HOME))
        assertArrayEquals(intArrayOf(1, 0), sim.score)
        assertEquals(Side.HOME, sim.lastGoalSide)
        assertEquals(Phase.GOAL, sim.phase)
        // During the banner a second crossing adds nothing.
        sim.run(0.5f)
        sim.puckIntoAwayNet()
        val banner = sim.run(Tuning.GOAL_TIME - 0.5f - Tuning.DT)
        assertTrue(banner.none { it is SimEvent.Goal })
        assertArrayEquals(intArrayOf(1, 0), sim.score)
        assertEquals(Phase.GOAL, sim.phase)
        sim.run(Tuning.DT)
        assertEquals(Phase.CELEBRATE, sim.phase)
        sim.run(Tuning.CELEBRATE_TIME)
        assertEquals(Phase.FACEOFF, sim.phase)
        assertArrayEquals(spots(), sim.positions(), 1e-4f)
        assertEquals(0f, sim.puck.x, 0f); assertEquals(0f, sim.puck.y, 0f)
    }

    @Test fun own_goal_counts_for_the_other_side() {
        // HOME goalie frozen at y = 0; the carrier skates past it at y = 0.9 into its own net.
        val sim = play(home = GoalieSkill(0.1f, 0f, 0.7f))
        val s = sim.skaters[0]
        s.x = -20f; s.y = 0.9f; s.facing = Math.PI.toFloat()
        sim.giveTo(s)
        val events = sim.run(2f) { s.input.moveX = -1f }
        assertTrue(events.hasGoal(Side.AWAY))
        assertArrayEquals(intArrayOf(0, 1), sim.score)
    }

    @Test fun carried_puck_over_the_line_scores() {
        val sim = play()
        val s = sim.skaters[0]
        sim.goalies[Side.AWAY.index].y = -1.1f
        s.x = 20f; s.y = 0.6f; s.facing = 0f
        sim.giveTo(s)
        val events = sim.run(2f) { s.input.moveX = 1f; sim.goalies[Side.AWAY.index].y = -1.1f }
        assertTrue(events.hasGoal(Side.HOME))
        assertEquals(1, events.count { it is SimEvent.Goal })
    }

    @Test fun goal_on_last_tick_counts() {
        val sim = play()
        sim.clockS = Tuning.DT
        sim.puckIntoAwayNet()
        val events = sim.run(Tuning.DT)
        assertTrue(events.hasGoal(Side.HOME))
        assertTrue(events.none { it is SimEvent.PeriodEnd })
        assertEquals(0f, sim.clockS, 1e-6f)
        val after = sim.run(Tuning.GOAL_TIME + Tuning.CELEBRATE_TIME)
        assertTrue(after.contains(SimEvent.PeriodEnd(1)))
        assertEquals(Phase.BREAK, sim.phase)
        assertArrayEquals(intArrayOf(1, 0), sim.score)
        sim.run(Tuning.BREAK_TIME)
        assertEquals(2, sim.period)
        assertEquals(Phase.FACEOFF, sim.phase)
        assertEquals(120f, sim.clockS, 0f)
    }

    @Test fun goal_on_the_real_last_tick_ends_the_period_without_a_faceoff() {
        // Counting down from 120 in float steps of 1/60, the last tick starts a little above DT.
        val sim = play()
        sim.clockS = Tuning.DT + 0.005f
        sim.puckIntoAwayNet()
        val goal = sim.run(Tuning.DT)
        assertTrue(goal.hasGoal(Side.HOME))
        assertEquals(0f, sim.clockS, 0f)
        val banner = ArrayList<SimEvent>()
        sim.run(Tuning.GOAL_TIME + Tuning.CELEBRATE_TIME + 0.1f) {
            banner += sim.events
            if (sim.phase.afterGoal) { assertEquals(0f, sim.clockS, 0f); assertEquals(0, sim.displaySeconds) }
        }
        banner += sim.events
        assertTrue(banner.none { it == SimEvent.FaceoffDrop })
        assertTrue(banner.contains(SimEvent.PeriodEnd(1)))
        assertEquals(Phase.BREAK, sim.phase)
    }

    @Test fun natural_countdown_reaches_exactly_zero() {
        val sim = play()
        var ended = -1
        sim.run(Tuning.PERIOD_SECONDS + 0.5f) { t -> if (ended < 0 && sim.events.any { it is SimEvent.PeriodEnd }) ended = t }
        assertEquals(0f, sim.clockS, 0f)
        assertTrue("ended after tick $ended", ended in 7199..7201)
    }

    @Test fun button_held_through_faceoff_does_not_fire_on_the_drop() {
        val sim = game()
        sim.faceoffWinAway = 0f   // HOME wins the draw, so its centre isn't tied up
        val c = sim.skaters[0]
        val held = sim.run(Tuning.FACEOFF_TIME) { c.input.body = true }
        assertTrue(held.contains(SimEvent.FaceoffDrop))
        assertEquals(Phase.PLAY, sim.phase)
        assertEquals(Act.NONE, c.act)
        if (sim.puck.carrier === c) sim.knockLoose(0f, 0f, 1f)   // body only works without the puck
        sim.run(Tuning.DT) { c.input.body = false }
        sim.run(Tuning.DT) { c.input.body = true }
        assertEquals(Act.BODY, c.act)
    }

    @Test fun acts_are_cleared_when_a_period_ends() {
        for (last in listOf(false, true)) {
            val sim = play()
            if (last) sim.period = Tuning.PERIODS
            sim.clockS = Tuning.DT
            val down = sim.skaters[1]; down.act = Act.DOWN; down.actT = 5f
            val checker = sim.skaters[6]; checker.act = Act.BODY; checker.actT = 5f
            val shooter = sim.skaters[2]; shooter.x = 0f; shooter.y = 0f; sim.giveTo(shooter)
            shooter.act = Act.WINDUP; shooter.windup = 0.5f; shooter.input.shoot = true
            sim.step()
            assertEquals(if (last) Phase.FINAL else Phase.BREAK, sim.phase)
            for (s in sim.skaters) {
                assertEquals("skater ${s.id}", Act.NONE, s.act)
                assertEquals(0f, s.windup, 0f)
            }
            sim.run(1f)
            for (s in sim.skaters) assertEquals("skater ${s.id}", Act.NONE, s.act)
        }
    }

    @Test fun puck_crossing_after_buzzer_does_not_count() {
        val sim = play()
        sim.clockS = Tuning.DT
        sim.puck.x = Rink.GOAL_X - 0.15f; sim.puck.y = 0.9f; sim.puck.vx = 6f; sim.puck.vy = 0f
        val end = sim.run(Tuning.DT)
        assertTrue(end.contains(SimEvent.PeriodEnd(1)))
        assertTrue(sim.puck.x < Rink.GOAL_X)
        val later = sim.run(0.5f)
        assertTrue(sim.puck.x > Rink.GOAL_X)
        assertTrue(later.none { it is SimEvent.Goal })
        assertArrayEquals(intArrayOf(0, 0), sim.score)
    }

    /** True if (x, y) is inside the +x (AWAY) net's footprint. */
    private fun inAwayNet(x: Float, y: Float): Boolean =
        x > Rink.GOAL_X && x < Rink.GOAL_X + Rink.NET_DEPTH && abs(y) < Rink.GOAL_HALF_W

    @Test fun pickup_beside_the_net_does_not_score_through_its_side() {
        // A skater beside the net, facing the end boards, picks up a still puck lying just in front
        // of the goal line inside the posts. The puck jumps to his stick, outside the net's side
        // wall: it never went in, so it is not a goal.
        val sim = play()
        val s = sim.skaters[0]
        s.x = 26.8f; s.y = 1.6f; s.vx = 0f; s.vy = 0f; s.facing = 0f
        sim.puck.carrier = null
        sim.puck.x = 25.9f; sim.puck.y = 0.9f; sim.puck.vx = 0f; sim.puck.vy = 0f
        val events = sim.run(Tuning.DT)
        assertSame(s, sim.puck.carrier)
        assertTrue(events.none { it is SimEvent.Goal })
        assertArrayEquals(intArrayOf(0, 0), sim.score)
    }

    @Test fun carried_puck_pushed_round_the_post_does_not_score() {
        // The carrier's stick is in front of the goal line inside the posts; he is shoved along the
        // side of the net, so the puck is dragged round the post onto the side wall, not into the net.
        val sim = play()
        val s = sim.skaters[0]
        s.x = 25.6f; s.y = 1.6f; s.vx = 0f; s.vy = 0f; s.facing = -(Math.PI / 2).toFloat()
        sim.giveTo(s)
        assertTrue(sim.puck.x < Rink.GOAL_X && abs(sim.puck.y) < Rink.GOAL_HALF_W)
        val events = sim.run(0.5f) { s.x = minOf(s.x + 0.11f, 26.6f); s.y = 1.6f; s.vx = 0f; s.vy = 0f; s.facing = -(Math.PI / 2).toFloat() }
        assertFalse("puck at ${sim.puck.x},${sim.puck.y}", inAwayNet(sim.puck.x, sim.puck.y))
        assertTrue(events.none { it is SimEvent.Goal })
    }

    @Test fun a_scored_puck_stays_in_the_net_through_the_banner() {
        // A hard shot hits the back of the net; the mesh holds it there for the whole celebration.
        val sim = play()
        sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
        sim.puck.carrier = null
        sim.puck.x = Rink.GOAL_X - 0.2f; sim.puck.y = 0.3f; sim.puck.vx = 36f; sim.puck.vy = 2f
        val events = sim.run(Tuning.DT * 2)
        assertTrue(events.hasGoal(Side.HOME))
        sim.run(Tuning.GOAL_TIME - Tuning.DT * 4) {
            assertEquals(Phase.GOAL, sim.phase)
            assertTrue("puck at ${sim.puck.x},${sim.puck.y}", inAwayNet(sim.puck.x, sim.puck.y))
        }
    }

    @Test fun three_periods_then_final() {
        val sim = game()
        val events = sim.run(3 * (Tuning.PERIOD_SECONDS + Tuning.FACEOFF_TIME) + 2 * Tuning.BREAK_TIME + 1f)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<SimEvent.PeriodEnd>().map { it.period })
        assertEquals(1, events.count { it == SimEvent.GameEnd })
        assertEquals(3, events.count { it == SimEvent.FaceoffDrop })
        assertEquals(Phase.FINAL, sim.phase)
        assertEquals(3, sim.period)
        assertEquals(0f, sim.clockS, 0f)
        val score = sim.score.copyOf()
        sim.puckIntoAwayNet()
        val after = sim.run(5f)
        assertTrue(after.isEmpty())
        assertArrayEquals(score, sim.score)
        assertEquals(0f, sim.clockS, 0f)
        assertEquals(Phase.FINAL, sim.phase)
    }

    @Test fun tie_stays_a_tie() {
        val sim = game()
        sim.run(3 * (Tuning.PERIOD_SECONDS + Tuning.FACEOFF_TIME) + 2 * Tuning.BREAK_TIME + 1f)
        assertArrayEquals(intArrayOf(0, 0), sim.score)
        assertEquals(Phase.FINAL, sim.phase)
    }

    /** A slow loose puck into the AWAY goalie, who always catches it: play stops for the whistle. */
    private fun HockeySim.caught(y: Float = 0.2f) {
        puck.carrier = null
        puck.x = 20f; puck.y = y; puck.vx = 10f; puck.vy = 0f
        repeat(120) { step(); if (phase == Phase.WHISTLE) return }
        throw AssertionError("never caught")
    }

    private val catcher = GoalieSkill(0f, 6f, 1f, catch = 1f)

    @Test fun whistle_freezes_the_clock() {
        val sim = game(away = catcher)
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        sim.run(1f)
        sim.caught()
        val atWhistle = sim.clockS
        assertTrue(atWhistle < 120f)
        assertFalse(sim.phase.afterGoal)
        val events = sim.run(Tuning.WHISTLE_TIME - Tuning.DT) { for (s in sim.skaters) s.input.shoot = true }
        assertEquals(Phase.WHISTLE, sim.phase)
        assertEquals(atWhistle, sim.clockS, 0f)
        assertTrue("events $events", events.none { it is SimEvent.Shot || it is SimEvent.Save || it is SimEvent.Goal })
        sim.run(Tuning.DT)
        assertEquals(Phase.FACEOFF, sim.phase)
        assertEquals(atWhistle, sim.clockS, 0f)
    }

    @Test fun whistle_as_the_clock_runs_out_ends_the_period() {
        val sim = game(away = catcher)
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        sim.clockS = 1f   // time for the catch: the goalie backs in as the puck comes, so meets it late
        sim.caught()
        sim.clockS = 0f   // ran out with the puck in the glove
        val events = sim.run(Tuning.WHISTLE_TIME)
        assertTrue("events $events", events.contains(SimEvent.PeriodEnd(1)))
        assertEquals(Phase.BREAK, sim.phase)
    }

    @Test fun centre_faceoff_unchanged() {
        val sim = play()
        sim.startFaceoff()
        assertEquals(Phase.FACEOFF, sim.phase)
        assertArrayEquals(spots(), sim.positions(), 1e-4f)
        assertEquals(0f, sim.puck.x, 0f); assertEquals(0f, sim.puck.y, 0f)
    }

    @Test fun zone_faceoff_formation_is_on_the_dot() {
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
            val dx = sx * Rink.FACEOFF_X; val dy = sy * Rink.FACEOFF_Y
            val sim = play()
            sim.startFaceoff(dx, dy)
            val at = "dot ($dx, $dy)"
            assertEquals(at, Phase.FACEOFF, sim.phase)
            assertEquals(at, dx, sim.puck.x, 0f); assertEquals(at, dy, sim.puck.y, 0f)
            for (s in sim.skaters) {
                val who = "$at ${s.side} ${s.pos} (${s.x}, ${s.y})"
                if (s.pos == Position.C) {
                    assertTrue(who, kotlin.math.hypot(s.x - dx, s.y - dy) <= 1.5f)
                    assertTrue("$who on his own goal side", (s.x - dx) * s.side.dir < 0f)
                }
                assertTrue(who, Rink.inside(s.x, s.y, Tuning.SKATER_R))
                for (gx in floatArrayOf(-Rink.GOAL_X, Rink.GOAL_X)) {
                    assertTrue("$who in the crease", kotlin.math.hypot(s.x - gx, s.y) > Rink.CREASE_R + Tuning.SKATER_R)
                }
                assertEquals(who, 0f, kotlin.math.hypot(s.vx, s.vy), 0f)
            }
            // Nobody stacked on anyone else.
            for (a in sim.skaters) for (b in sim.skaters) if (a.id < b.id) {
                assertTrue("$at ${a.id} on ${b.id}", kotlin.math.hypot(a.x - b.x, a.y - b.y) >= 2 * Tuning.SKATER_R)
            }
        }
    }

    @Test fun caught_puck_faces_off_on_the_dot_on_the_side_of_the_catch() {
        for (y in floatArrayOf(0.2f, -0.2f)) {
            val sim = game(away = catcher)
            for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
            sim.startPlayForTest()
            sim.caught(y)
            sim.run(Tuning.WHISTLE_TIME)
            assertEquals(Phase.FACEOFF, sim.phase)
            assertEquals(Rink.FACEOFF_X, sim.puck.x, 0f)
            assertEquals(if (y >= 0f) Rink.FACEOFF_Y else -Rink.FACEOFF_Y, sim.puck.y, 0f)
            // The drop then goes as at centre ice.
            val drop = sim.run(Tuning.FACEOFF_TIME)
            assertTrue(drop.contains(SimEvent.FaceoffDrop))
            assertEquals(Phase.PLAY, sim.phase)
        }
    }
}
