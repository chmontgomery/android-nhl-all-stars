package com.personal.hockey.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawOrderTest {
    private val renderer = CanvasRenderer(MatchSetup.of(Teams.all[0], Teams.all[1], Level.EASY))
    private val sim = HockeySim(Random(1))
    private val out = IntArray(CanvasRenderer.ORDER_SIZE)

    @Test fun far_skaters_are_drawn_before_near_ones() {
        sim.skaters[0].y = 10f
        sim.skaters[1].y = -10f
        val n = renderer.drawOrder(sim, out)
        val far = (0 until n).first { out[it] == 0 }
        val near = (0 until n).first { out[it] == 1 }
        assertTrue("far at $far, near at $near", far < near)
    }

    @Test fun order_contains_all_ten_skaters_and_two_goalies_once() {
        val n = renderer.drawOrder(sim, out)
        val codes = out.copyOf(n).toList()
        for (id in 0 until 10) assertEquals("skater $id", 1, codes.count { it == id })
        for (side in 0 until 2) assertEquals("goalie $side", 1, codes.count { it == CanvasRenderer.GOALIE_MARK + side })
    }

    @Test fun a_held_puck_is_left_out() {
        sim.skaters[3].y = 4f
        sim.puck.y = -2f
        var n = renderer.drawOrder(sim, out)
        assertTrue(out.copyOf(n).contains(CanvasRenderer.PUCK_MARK))
        sim.goalies[0].holding = true
        n = renderer.drawOrder(sim, out)
        assertTrue(!out.copyOf(n).contains(CanvasRenderer.PUCK_MARK))
    }

    @Test fun a_carried_puck_is_drawn_after_its_carrier() {
        val s = sim.skaters[2]
        s.y = 0f; s.facing = (Math.PI / 2).toFloat()   // puck sits 0.9 m farther than the carrier
        sim.giveTo(s)
        val n = renderer.drawOrder(sim, out)
        val carrier = (0 until n).first { out[it] == 2 }
        val puck = (0 until n).first { out[it] == CanvasRenderer.PUCK_MARK }
        assertTrue(puck > carrier)
    }

    @Test fun a_goalie_is_drawn_after_its_net_wherever_it_stands() {
        for (y in floatArrayOf(-0.8f, 0f, 0.8f)) {
            for (g in sim.goalies) g.y = y
            val n = renderer.drawOrder(sim, out)
            for (side in 0 until 2) {
                val net = (0 until n).first { out[it] == CanvasRenderer.NET_MARK + side }
                val goalie = (0 until n).first { out[it] == CanvasRenderer.GOALIE_MARK + side }
                assertTrue("y $y side $side: net at $net, goalie at $goalie", goalie > net)
            }
        }
    }

    @Test fun a_skater_well_behind_a_goalie_is_still_drawn_first() {
        sim.goalies[0].y = 0.8f
        sim.skaters[0].y = 3f
        val n = renderer.drawOrder(sim, out)
        val skater = (0 until n).first { out[it] == 0 }
        val goalie = (0 until n).first { out[it] == CanvasRenderer.GOALIE_MARK }
        assertTrue(skater < goalie)
    }

    @Test fun a_puck_inside_a_net_is_drawn_between_its_back_and_its_near_side() {
        for (y in floatArrayOf(-0.9f, 0f, 0.9f)) {
            sim.puck.carrier = null
            sim.puck.x = 26.7f; sim.puck.y = y
            val n = renderer.drawOrder(sim, out)
            val side = 1   // the +x net is AWAY's
            val back = (0 until n).first { out[it] == CanvasRenderer.NET_MARK + side }
            val front = (0 until n).first { out[it] == CanvasRenderer.NET_FRONT_MARK + side }
            val puck = (0 until n).first { out[it] == CanvasRenderer.PUCK_MARK }
            assertTrue("y $y: back $back, puck $puck, front $front", puck in (back + 1) until front)
        }
    }

    @Test fun a_carried_puck_is_drawn_after_every_skater_goalie_and_net() {
        // The carrier faces away, so his puck is farther than he is; a teammate and an opponent
        // skate across nearer the camera, and he is beside a net.
        val s = sim.skaters[2]
        s.x = 24f; s.y = 1.5f; s.facing = (Math.PI / 2).toFloat()
        sim.giveTo(s)
        sim.skaters[3].x = 24.5f; sim.skaters[3].y = 0.5f
        sim.skaters[7].x = 23.5f; sim.skaters[7].y = -2f
        val n = renderer.drawOrder(sim, out)
        val puck = (0 until n).first { out[it] == CanvasRenderer.PUCK_MARK }
        assertEquals("puck should be last of $n", n - 1, puck)
    }
}
