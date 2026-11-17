package com.personal.hockey.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.render.anim.PuckFlight
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/** Shots lift off the ice on an arc, drawn over a shadow; passes and rebounds stay on the ice. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PuckFlightTest {
    private val sim = HockeySim(Random(1))
    private val director = AnimDirector()
    private val flight get() = director.flight
    private val shooter = sim.skaters[2]
    /** The goal line HOME attacks. */
    private val goalLine = Rink.GOAL_X

    /**
     * Live play: HOME's skater 2 with the puck at the attack goal line − 15 m, facing the net,
     * everyone else parked along the far boards.
     */
    private fun setUp() {
        sim.startPlayForTest()
        sim.controlledId = -1
        for (s in sim.skaters) { s.x = -18f + 2f * s.id; s.y = 11f; s.vx = 0f; s.vy = 0f; s.facing = 0f }
        shooter.x = goalLine - 15f; shooter.y = 0f; shooter.facing = 0f
        for (g in sim.goalies) { g.holding = false }
        sim.giveTo(shooter)
        tick()
    }

    /** One tick as the game runs it; the AWAY goalie is kept on the far post, out of the shot's way. */
    private fun tick() {
        sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
        sim.step()
        director.onTick(sim)
    }

    private fun frame() { tick(); director.advance(Tuning.DT) }

    /** Holds the shot for [windup] s and lets go; returns once the Shot event has been heard. */
    private fun shoot(windup: Float) {
        shooter.input.shoot = true
        repeat(Math.round(windup / Tuning.DT)) { frame() }
        shooter.input.shoot = false
        var shot = false
        var guard = 0
        while (!shot && guard++ < 10) {
            tick()
            shot = sim.events.any { it is SimEvent.Shot }
            director.advance(Tuning.DT)
        }
        assertTrue("the shot never went", shot)
        assertTrue("puck vx ${sim.puck.vx}", sim.puck.vx > 15f)
    }

    /** Ticks until the puck crosses the goal line; the height as it does. */
    private fun heightAtGoalLine(): Float {
        var guard = 0
        while (guard++ < 120) {
            tick()
            if (sim.puck.x >= goalLine) return flight.height
            director.advance(Tuning.DT)
        }
        throw AssertionError("the puck never reached the goal line: at ${sim.puck.x}")
    }

    @Test fun slapshot_reaches_crossbar_height_at_the_net() {
        setUp()
        shoot(0.7f)
        val h = heightAtGoalLine()
        assertTrue("height $h at the goal line", h in 0.95f..1.15f)
    }

    @Test fun wrist_shot_flies_lower() {
        setUp()
        shoot(0.3f)
        val h = heightAtGoalLine()
        assertTrue("height $h at the goal line", h in 0.4f..0.6f)
    }

    @Test fun puck_leaves_the_stick_fast() {
        for ((windup, peak) in listOf(0.7f to PuckFlight.SLAP_PEAK, 0.3f to PuckFlight.WRIST_PEAK)) {
            val t = PuckFlightTest()
            t.setUp()
            t.shoot(windup)
            repeat(Math.round(0.1f / Tuning.DT) - 1) { t.frame() }   // the release tick was the first
            val h = t.flight.height
            assertTrue("windup $windup: height $h at 0.1 s, peak $peak", h > 0.3f * peak)
        }
    }

    /** A slapshot 0.1 s out, still climbing or at its peak. */
    private fun midFlight() {
        setUp()
        shoot(0.7f)
        repeat(5) { frame() }
        assertTrue("height ${flight.height} mid-flight", flight.height > 0.5f)
    }

    private fun inject(e: SimEvent) {
        sim.events.clear()
        sim.events.add(e)
        director.onTick(sim)
        sim.events.clear()
    }

    @Test fun save_lands_the_puck() {
        midFlight()
        inject(SimEvent.Save(Side.AWAY))
        director.advance(0.2f)
        assertEquals(0f, flight.height, 0f)
    }

    @Test fun rebound_away_from_net_lands() {
        midFlight()
        sim.puck.vx = -abs(sim.puck.vx)
        sim.events.clear()
        director.onTick(sim)
        director.advance(0.2f)
        assertEquals(0f, flight.height, 0f)
    }

    /** Mid-flight, the puck's velocity becomes [keep] of its speed turned [turn] rad: a deflection. */
    private fun deflect(keep: Float, turn: Float) {
        midFlight()
        val vx = sim.puck.vx; val vy = sim.puck.vy
        val c = kotlin.math.cos(turn) * keep; val sn = kotlin.math.sin(turn) * keep
        sim.puck.vx = vx * c - vy * sn; sim.puck.vy = vx * sn + vy * c
        assertTrue("still heading for the net: vx ${sim.puck.vx}", sim.puck.vx > 8f)
        sim.events.clear()
        director.onTick(sim)
        director.advance(0.2f)
    }

    @Test fun deflection_lands_the_puck() {
        deflect(0.6f, 0.5f)
        assertEquals(0f, flight.height, 0f)
        // Either sign of a deflection alone is enough: the speed lost, or the turn.
        for ((keep, turn) in listOf(0.6f to 0f, 1f to 0.5f, 1f to -0.5f)) {
            val t = PuckFlightTest()
            t.deflect(keep, turn)
            assertEquals("keep $keep turn $turn", 0f, t.flight.height, 0f)
        }
        // Drag alone over a tick is no deflection: the puck stays up.
        val t = PuckFlightTest()
        t.midFlight()
        t.frame()
        assertTrue("height ${t.flight.height} after a plain tick", t.flight.height > 0.5f)
    }

    @Test fun long_shot_stays_flat() {
        // A slapshot from 25 m out (past FLAT_SHOT_M): a dump-in, it never leaves the ice.
        setUp()
        shooter.x = goalLine - 25f
        tick()
        shooter.input.shoot = true
        repeat(Math.round(0.7f / Tuning.DT)) { frame() }
        shooter.input.shoot = false
        var shot = false
        repeat(Math.round(0.6f / Tuning.DT)) {
            tick()
            if (sim.events.any { it is SimEvent.Shot }) shot = true
            director.advance(Tuning.DT)
            assertEquals(0f, flight.height, 0f)
        }
        assertTrue("the shot never went", shot)
        assertTrue("puck x ${sim.puck.x}: it travelled", sim.puck.x > goalLine - 20f)
    }

    @Test fun passes_stay_flat() {
        // A real pass up ice to a teammate: the puck never leaves the ice.
        setUp()
        val mate = sim.skaters[3]
        mate.x = shooter.x + 10f; mate.y = 2f
        shooter.input.pass = true
        var passed = false
        repeat(Math.round(0.4f / Tuning.DT)) {
            tick()
            if (sim.events.any { it is SimEvent.Pass }) passed = true
            director.advance(Tuning.DT)
            assertEquals(0f, flight.height, 0f)
        }
        assertTrue("no pass went", passed)

        // A Pass heard mid-flight puts it down.
        val t = PuckFlightTest()
        t.midFlight()
        t.inject(SimEvent.Pass(Side.HOME, 1, 3))
        t.director.advance(0.2f)
        assertEquals(0f, t.flight.height, 0f)
    }

    @Test fun shadow_drawn_under_lifted_puck() {
        val w = 1600; val h = 720
        val r = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY))
        r.camera.setViewport(w.toFloat(), h.toFloat()); r.camera.snapTo(12f)
        sim.startPlayForTest()
        sim.controlledId = -1
        for (s in sim.skaters) { s.x = -18f + 4f * s.id; s.y = 11f; s.vx = 0f; s.vy = 0f; s.facing = 0f }
        // A slapshot from x = 7 at the net at +GOAL_X, flying past x = 12.3 (clear of the lines and
        // circles): about 1 m up.
        val px = 12.3f
        shooter.x = 7f; shooter.y = -2f
        sim.puck.carrier = null; sim.puck.x = px; sim.puck.y = -2f; sim.puck.vx = 34f; sim.puck.vy = 0f
        val flight = r.anim.flight
        flight.onShot(sim, shooter.id, Side.HOME, slap = true)
        flight.onTick(sim)
        val z = flight.height
        assertTrue("height $z", z in 0.85f..1.15f)

        fun render(): PixelMap {
            val bmp = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                r.draw(this, sim, 0f)
            }
            return bmp.toPixelMap()
        }
        val with = render()
        flight.clear()
        sim.puck.y = -30f   // off the ice: the same frame without the puck
        val without = render()

        fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        val p = FloatArray(3)
        r.camera.project(px, -2f, 0f, p)
        val gx = p[0].toInt(); val gy = p[1].toInt()
        assertTrue("shadow ${lum(with[gx, gy])} vs ice ${lum(without[gx, gy])}",
            lum(with[gx, gy]) < lum(without[gx, gy]) - 0.15f)

        // The puck's own black disc is up at z, above the shadow on screen.
        r.camera.project(px, -2f, z, p)
        val rad = (0.4f * p[2]).toInt()
        var sumY = 0L; var n = 0
        for (ix in p[0].toInt() - rad..p[0].toInt() + rad) for (iy in p[1].toInt() - rad..p[1].toInt() + rad) {
            if (ix !in 0 until w || iy !in 0 until h) continue
            if (lum(with[ix, iy]) < 0.15f && lum(without[ix, iy]) > 0.5f) { sumY += iy; n++ }
        }
        assertTrue("only $n puck pixels near z = $z", n >= 20)
        val meanY = sumY.toFloat() / n
        assertTrue("puck at screen y $meanY, shadow at $gy", meanY < gy - 0.5f * p[2])
    }
}
