package com.personal.hockey.ai

import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FormationTest {
    private val possessions = listOf(Side.HOME, Side.AWAY, null)

    @Test fun all_spots_are_inside_the_rink() {
        val out = FloatArray(2)
        for (side in Side.entries) for (pos in Position.entries) for (poss in possessions) {
            var px = -30f
            while (px <= 30f) {
                var py = -13f
                while (py <= 13f) {
                    Formation.spot(side, pos, px, py, poss, out)
                    val where = "$side $pos $poss puck ($px, $py) -> (${out[0]}, ${out[1]})"
                    assertTrue(where, Rink.inside(out[0], out[1], 0.45f))
                    for (g in listOf(-1f, 1f)) {
                        assertTrue("in crease: $where", hypot(out[0] - g * Rink.GOAL_X, out[1]) > Rink.CREASE_R)
                    }
                    py += 1f
                }
                px += 1f
            }
        }
    }

    @Test fun defenders_stay_goal_side_when_defending() {
        val out = FloatArray(2)
        for (pos in listOf(Position.LD, Position.RD)) {
            Formation.spot(Side.HOME, pos, -15f, 3f, Side.AWAY, out)
            assertTrue("$pos x ${out[0]}", out[0] < -15f)
        }
    }

    @Test fun formation_is_mirrored_for_away() {
        val home = FloatArray(2); val away = FloatArray(2)
        for (pos in Position.entries) for (poss in possessions) {
            var px = -29f
            while (px <= 29f) {
                var py = -12f
                while (py <= 12f) {
                    Formation.spot(Side.HOME, pos, px, py, poss, home)
                    Formation.spot(Side.AWAY, pos, -px, -py, poss?.other, away)
                    assertEquals("$pos $poss ($px, $py) x", -home[0], away[0], 1e-5f)
                    assertEquals("$pos $poss ($px, $py) y", -home[1], away[1], 1e-5f)
                    py += 1.5f
                }
                px += 1.5f
            }
        }
    }

    @Test fun attacking_wings_are_wide_and_ahead_of_the_puck() {
        val out = FloatArray(2)
        Formation.spot(Side.HOME, Position.LW, 0f, 0f, Side.HOME, out)
        assertTrue("LW ${out[0]}, ${out[1]}", out[0] > 0f && out[1] >= 7f)
        Formation.spot(Side.HOME, Position.RW, 0f, 0f, Side.HOME, out)
        assertTrue("RW ${out[0]}, ${out[1]}", out[0] > 0f && out[1] <= -7f)
        Formation.spot(Side.HOME, Position.C, 0f, 0f, Side.HOME, out)
        assertEquals("C 6 m ahead", 6f, out[0], 0.01f)
    }
}
