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
        var last = 0f
        var flips = 0
        // Crawls behind the net at about 1.5 m/s: 6 m in 4 s (the sim tick is 1/60 s).
        val ticks = (4f / Tuning.DT).toInt()
        for (k in 0..ticks) {
            puckAt(28f, 3f - 6f * k / ticks)
            tick()
            val off = wrap(awayState.facing - straight)
            assertTrue(abs(off) <= GOALIE_FACE_MAX + 0.01f)
            if (off != 0f && last != 0f && off * last < 0f) flips++
            if (off != 0f) last = off
        }
        assertTrue("facing offset changed sign $flips times", flips <= 1)
    }

    @Test fun puck_just_behind_the_goal_line_draws_the_goalie_to_the_puck_side() {
        tick()
        away.y = 0.9f   // on the +y post
        puckAt(away.x + 2.5f, 0.5f)
        repeat(120) { tick() }
        assertTrue("faces ${awayState.facing}", sin(awayState.facing) > 0f)
        assertEquals(GOALIE_FACE_MAX, abs(wrap(awayState.facing - straight)), 0.01f)
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
        assertEquals(Phase.FACEOFF, sim.phase)
        puckAt(away.x - 5f, away.y + 4f)   // the puck on an off-axis dot
        for (g in director.goalies) {
            var last = g.facing
            repeat(60) {
                tick()
                assertTrue(abs(wrap(g.facing - last)) <= GOALIE_TURN_RATE * Tuning.DT + eps)
                last = g.facing
            }
        }
        assertEquals(Phase.FACEOFF, sim.phase)
        val dot = sim.puck
        val want = wrap(atan2(dot.y - away.y, dot.x - away.x) - straight).coerceIn(-GOALIE_FACE_MAX, GOALIE_FACE_MAX)
        assertEquals(want, wrap(awayState.facing - straight), 0.02f)
    }

    @Test fun facing_is_straight_out_in_the_goal_pause_and_breaks() {
        for (ph in listOf(Phase.GOAL, Phase.CELEBRATE, Phase.BREAK, Phase.FINAL)) {
            tick()
            puckAt(away.x - 5f, away.y + 5f)
            repeat(30) { tick() }
            sim.phase = ph
            repeat(120) { tick() }
            assertEquals("$ph", 0f, wrap(awayState.facing - straight), 0.01f)
            sim.phase = Phase.PLAY
        }
    }

    @Test fun goalies_start_facing_straight_out() {
        val d = AnimDirector()
        assertEquals(0f, d.goalies[Side.HOME.index].facing, 0f)
        assertEquals(straight, d.goalies[Side.AWAY.index].facing, 0f)
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
