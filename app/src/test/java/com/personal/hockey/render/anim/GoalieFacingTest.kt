package com.personal.hockey.render.anim

import com.personal.hockey.render.Effects
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Tuning
import com.personal.hockey.sim.startFaceoff
import com.personal.hockey.sim.wrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GoalieFacingTest {
    private val sim = HockeySim(Random(1)).also { it.startPlayForTest(); it.puck.carrier = null }
    private val director = AnimDirector()
    private val away = sim.goalies[Side.AWAY.index]
    private val awayState = director.goalies[Side.AWAY.index]
    private val straight = PI.toFloat()
    private val eps = 1e-4f

    private fun tick() { sim.events.clear(); director.onTick(sim) }
    private fun puckAt(x: Float, y: Float) { sim.puck.x = x; sim.puck.y = y }

    @Test fun goalie_turns_toward_the_puck_at_the_turn_rate() {
        tick()
        val a = (straight + 30f * PI.toFloat() / 180f)
        puckAt(away.x + 5f * cos(a), away.y + 5f * sin(a))
        val before = awayState.facing
        tick()
        assertTrue(abs(wrap(awayState.facing - before)) <= GOALIE_TURN_RATE * Tuning.DT + eps)
        repeat(59) { tick() }
        assertEquals(0f, wrap(awayState.facing - atan2(sin(a), cos(a))), 0.02f)
    }

    @Test fun facing_is_clamped_with_the_puck_behind_the_net() {
        tick()
        puckAt(28f, 0.5f)
        repeat(120) { tick() }
        assertEquals(GOALIE_FACE_MAX, abs(wrap(awayState.facing - straight)), 0.01f)
    }

    @Test fun facing_turns_the_short_way_across_the_back() {
        tick()
        for (k in 0..60) {
            puckAt(28f, 3f - 6f * k / 60f)
            tick()
            assertTrue(abs(wrap(awayState.facing - straight)) <= GOALIE_FACE_MAX + 0.01f)
        }
    }

    @Test fun facing_holds_when_the_puck_is_on_the_goalie() {
        tick()
        puckAt(away.x + 3f, away.y + 3f)
        repeat(30) { tick() }
        away.holding = true
        puckAt(away.x, away.y)
        var last = awayState.facing
        repeat(60) {
            tick()
            assertTrue(abs(wrap(awayState.facing - last)) <= GOALIE_TURN_RATE * Tuning.DT + eps)
            last = awayState.facing
        }
        assertEquals(0f, wrap(awayState.facing - straight), 0.01f)
    }

    @Test fun a_faceoff_reset_does_not_spin_the_goalie() {
        tick()
        puckAt(away.x - 3f, away.y + 2f)
        repeat(30) { tick() }
        sim.startFaceoff()
        sim.phase = Phase.PLAY
        for (g in director.goalies) {
            var last = g.facing
            repeat(60) {
                tick()
                assertTrue(abs(wrap(g.facing - last)) <= GOALIE_TURN_RATE * Tuning.DT + eps)
                last = g.facing
            }
        }
    }

    @Test fun goalie_facing_allocates_nothing() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val d = AnimDirector(Effects())
        fun round(k: Int) {
            sim.events.clear()
            puckAt(20f * sin(k * 0.05f), 10f * cos(k * 0.07f))
            d.onTick(sim)
            d.advance(Tuning.DT)
        }
        repeat(200) { round(it) }
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 600) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 600 ticks", bytes < 16 * 1024L)
    }
}
