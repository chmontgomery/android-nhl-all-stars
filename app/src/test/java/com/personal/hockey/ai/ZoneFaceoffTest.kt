package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import com.personal.hockey.sim.startFaceoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The computer's faceoff skill and its defensive-zone plan after a draw in its own end. */
class ZoneFaceoffTest {
    /** Ticks [m] (no human input) until the drop; returns the side the puck was pulled toward. */
    private fun dropWinner(m: Match): Side {
        repeat(600) {
            m.tick(null)
            if (m.sim.events.contains(SimEvent.FaceoffDrop)) {
                // The puck goes back toward the winner's own end.
                return if (m.sim.puck.vx > 0f) Side.AWAY else Side.HOME
            }
        }
        throw AssertionError("no drop")
    }

    @Test fun hard_ai_wins_most_draws() {
        var ai = 0
        for (seed in 1L..200L) if (dropWinner(Match(Level.HARD, seed)) == Side.AWAY) ai++
        assertEquals("AI won $ai of 200", Difficulty.HARD.faceoffWin.toDouble(), ai / 200.0, 0.08)
    }

    @Test fun easy_ai_loses_most_draws() {
        var ai = 0
        for (seed in 1L..200L) if (dropWinner(Match(Level.EASY, seed)) == Side.AWAY) ai++
        assertTrue("AI won $ai of 200", ai / 200.0 < 0.5)
    }

    @Test fun ai_only_games_split_draws_evenly() {
        val m = Match(Level.HARD, 1L, humanControlled = false, homeDifficulty = Difficulty.HARD)
        assertEquals(0.5f, m.sim.faceoffWinAway, 0f)
    }

    /** Distance from (px, py) to the segment (ax, ay)–(bx, by). */
    private fun segDist(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        val ex = bx - ax; val ey = by - ay
        val t = (((px - ax) * ex + (py - ay) * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
        return hypot(ax + ex * t - px, ay + ey * t - py)
    }

    @Test fun ai_defends_goal_side_after_dzone_draw() {
        // AWAY defends +x. A draw on its left-hand dot, which the human's side always wins.
        val netX = Rink.GOAL_X
        var checked = 0
        for (seed in 1L..40L) {
            val m = Match(Level.HARD, seed)
            val sim = m.sim
            repeat(10) { m.tick(null) }
            sim.faceoffWinAway = 0f
            sim.startFaceoff(Rink.FACEOFF_X, Rink.FACEOFF_Y)
            var dropped = false
            var after = 0
            var awayTouched = false
            while (after < Math.round(1f / Tuning.DT)) {
                m.tick(null)
                if (sim.events.contains(SimEvent.FaceoffDrop)) dropped = true
                if (!dropped) continue
                after++
                if (sim.puck.carrier?.side == Side.AWAY) awayTouched = true
            }
            assertEquals(Phase.PLAY, sim.phase)
            if (awayTouched) continue   // the plan ends once the AI has the puck
            checked++
            val home = sim.skatersOf(Side.HOME)
            val markers = ArrayList<Skater>()
            // One skater chases the puck, as in open play, and is on it; the rest follow the plan.
            for (a in sim.skatersOf(Side.AWAY)) {
                if (hypot(a.x - sim.puck.x, a.y - sim.puck.y) < 3f) continue
                if (a.pos == Position.C) {
                    // On his way to the slot (he may chase the loose drop first), goal-side of the puck.
                    val at = "seed $seed centre (${a.x}, ${a.y}), puck (${sim.puck.x}, ${sim.puck.y})"
                    assertTrue(at, hypot(a.x - (netX - 5f), a.y) < 4.5f)
                    assertTrue(at, hypot(a.x - netX, a.y) < hypot(sim.puck.x - netX, sim.puck.y))
                } else markers += a
            }
            // Goal-side of [h]: nearer the net, on his line to it.
            fun goalSide(a: Skater, h: Skater) =
                hypot(a.x - netX, a.y) < hypot(h.x - netX, h.y) && segDist(h.x, h.y, netX, 0f, a.x, a.y) < 1.2f
            // Some one-to-one assignment of markers to opponents has every marker goal-side of his man.
            fun assign(k: Int, used: Int): Boolean {
                if (k == markers.size) return true
                for (j in home.indices) if (used and (1 shl j) == 0 && goalSide(markers[k], home[j]) && assign(k + 1, used or (1 shl j))) return true
                return false
            }
            assertTrue(
                "seed $seed: AWAY ${markers.map { "${it.pos}(${it.x}, ${it.y})" }} HOME ${home.map { "${it.pos}(${it.x}, ${it.y})" }}",
                assign(0, 0),
            )
        }
        assertTrue("checked $checked", checked >= 10)
    }

    /** A HARD match with a draw on AWAY's own dot that AWAY wins; returns it on the drop tick. */
    private fun wonDzoneDraw(seed: Long, dotX: Float = Rink.FACEOFF_X, dotY: Float = -Rink.FACEOFF_Y): Match {
        val m = Match(Level.HARD, seed)
        repeat(10) { m.tick(null) }
        m.sim.faceoffWinAway = 1f
        m.sim.startFaceoff(dotX, dotY)
        repeat(600) {
            m.tick(null)
            if (m.sim.events.contains(SimEvent.FaceoffDrop)) return m
        }
        throw AssertionError("no drop")
    }

    @Test fun ai_d_moves_puck_after_won_dzone_draw() {
        for (pos in arrayOf(Position.LD, Position.RD)) for (seed in 1L..5L) {
            val m = wonDzoneDraw(seed)
            val sim = m.sim
            // The human's side caught out up ice, so the lanes out of the zone are open.
            for (h in sim.skatersOf(Side.HOME)) { h.x = -15f + h.id; h.y = 10f - 4f * h.id; h.vx = 0f; h.vy = 0f }
            val d = sim.skatersOf(Side.AWAY)[pos.ordinal]
            sim.giveTo(d)
            var pass: SimEvent.Pass? = null
            repeat(3) { if (pass == null) { m.tick(null); pass = sim.events.filterIsInstance<SimEvent.Pass>().firstOrNull() } }
            val at = "$pos seed $seed"
            assertTrue("$at: no pass", pass != null)
            assertEquals(at, d.id, pass!!.passerId)
            val t = sim.skaters[pass!!.targetId]
            assertTrue("$at: to ${t.pos}", t.side == Side.AWAY && t.pos != Position.C)
        }
    }

    /**
     * Places [carrier] at ([cx], [cy]) with its listed [targets] at their given points, a HOME skater
     * in the middle of each lane to them, and one more HOME skater [PRESSER_GAP] straight up ice of
     * the carrier: outside TeamAi's AVOID_RANGE (3 m), so ordinary carrying would skate on up ice
     * past him, toward the far net. Everyone else is parked out of play. Returns the presser.
     */
    private fun noLanes(sim: HockeySim, carrier: Skater, cx: Float, cy: Float, vararg targets: Pair<Skater, FloatArray>): Skater {
        val dir = Side.AWAY.dir
        for (s in sim.skaters) if (s !== carrier) { s.x = -dir * 24f; s.y = if (s.id % 2 == 0) 11f else -11f; s.vx = 0f; s.vy = 0f }
        carrier.x = cx; carrier.y = cy; carrier.vx = 0f; carrier.vy = 0f
        val home = sim.skatersOf(Side.HOME)
        for ((k, tp) in targets.withIndex()) {
            val (t, at) = tp
            t.x = at[0]; t.y = at[1]
            home[k].x = (cx + at[0]) / 2f; home[k].y = (cy + at[1]) / 2f
        }
        val p = home[4]
        p.x = cx + dir * PRESSER_GAP; p.y = cy
        sim.giveTo(carrier)
        return p
    }

    /** With every lane covered and a man up ice of him, the defenceman turns back, straight away from him. */
    @Test fun ai_d_with_no_lane_carries_away_from_pressure() {
        for (seed in 1L..5L) {
            val m = wonDzoneDraw(seed)
            val sim = m.sim
            val a = sim.skatersOf(Side.AWAY)
            val d = a[Position.LD.ordinal]
            val dir = Side.AWAY.dir
            noLanes(sim, d, -dir * 22f, -6f,
                a[Position.RD.ordinal] to floatArrayOf(-dir * 22f, 6f),
                a[Position.LW.ordinal] to floatArrayOf(-dir * 5f, -8f),
                a[Position.RW.ordinal] to floatArrayOf(-dir * 5f, 8f))
            repeat(2) { m.tick(null) }
            assertTrue("seed $seed", sim.events.none { it is SimEvent.Pass })
            assertTrue("seed $seed: input ${d.input.moveX}, ${d.input.moveY}", d.input.moveX * dir < -0.9f)
        }
    }

    /** The same for a centre who won a centre-ice draw to himself: he turns back toward his own end. */
    @Test fun ai_centre_with_no_lane_carries_away_from_pressure() {
        for (seed in 1L..5L) {
            val m = wonDzoneDraw(seed, 0f, 0f)
            val sim = m.sim
            val a = sim.skatersOf(Side.AWAY)
            val c = a[Position.C.ordinal]
            val dir = Side.AWAY.dir
            noLanes(sim, c, 0f, 0f,
                a[Position.LD.ordinal] to floatArrayOf(-dir * 12f, -6f),
                a[Position.RD.ordinal] to floatArrayOf(-dir * 12f, 6f))
            repeat(2) { m.tick(null) }
            assertTrue("seed $seed", sim.events.none { it is SimEvent.Pass })
            assertTrue("seed $seed: input ${c.input.moveX}, ${c.input.moveY}", c.input.moveX * dir < -0.9f)
        }
    }

    /** AWAY's centre wins a draw in his own zone or at centre ice to himself: he passes straight back to a defenceman. */
    @Test fun ai_centre_moves_puck_back_after_won_draw() {
        for (dot in arrayOf(floatArrayOf(Rink.FACEOFF_X, -Rink.FACEOFF_Y), floatArrayOf(0f, 0f))) for (seed in 1L..5L) {
            val m = wonDzoneDraw(seed, dot[0], dot[1])
            val sim = m.sim
            // The human's side caught out on the far boards, so the lanes back are open.
            for (h in sim.skatersOf(Side.HOME)) { h.x = -20f + 2f * h.id; h.y = 11f; h.vx = 0f; h.vy = 0f }
            val c = sim.skatersOf(Side.AWAY)[Position.C.ordinal]
            sim.giveTo(c)
            var pass: SimEvent.Pass? = null
            repeat(3) { if (pass == null) { m.tick(null); pass = sim.events.filterIsInstance<SimEvent.Pass>().firstOrNull() } }
            val at = "dot ${dot[0]},${dot[1]} seed $seed"
            assertTrue("$at: no pass", pass != null)
            assertEquals(at, c.id, pass!!.passerId)
            val t = sim.skaters[pass!!.targetId]
            assertTrue("$at: to ${t.pos}", t.side == Side.AWAY && (t.pos == Position.LD || t.pos == Position.RD))
        }
    }

    private companion object {
        const val PRESSER_GAP = 3.3f   // just outside TeamAi's AVOID_RANGE of 3 m
    }
}
