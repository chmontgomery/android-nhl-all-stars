package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class SkatingTest {
    /** A sim with every skater parked in a row along the near boards, out of the way. */
    private fun sim(seed: Int = 1): HockeySim {
        val sim = HockeySim(Random(seed))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f }

    /** Steps for [seconds], calling [inputs] before every tick; returns the max speed of [watch]. */
    private fun HockeySim.run(seconds: Float, watch: Skater? = null, inputs: () -> Unit = {}): Float {
        var maxSpeed = 0f
        repeat((seconds / Tuning.DT).toInt()) {
            inputs()
            step()
            if (watch != null) maxSpeed = maxOf(maxSpeed, watch.speed())
        }
        return maxSpeed
    }

    private fun Skater.speed() = hypot(vx, vy)

    @Test fun reaches_but_never_exceeds_top_speed() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        val max = sim.run(3f, s) { s.input.moveX = 1f }
        assertTrue("speed ${s.speed()}", s.speed() in 8.5f..9.0001f)
        assertTrue("max $max", max <= Tuning.MAX_SPEED + 1e-4f)
    }

    @Test fun half_stick_is_half_speed() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        sim.run(3f) { s.input.moveX = 0.5f }
        assertEquals(4.5f, s.speed(), 0.3f)
    }

    @Test fun speed_scale_caps_speed() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        s.speedScale = 0.75f
        val max = sim.run(3f, s) { s.input.moveX = 1f }
        assertTrue("max $max", max <= 6.75f + 1e-4f)
        assertTrue("speed ${s.speed()}", s.speed() > 6.5f)
    }

    @Test fun glides_then_stops() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        sim.run(1.5f) { s.input.moveX = 1f }
        s.input.clear()
        sim.run(0.5f)
        assertTrue("after 0.5s ${s.speed()}", s.speed() > 3f)
        sim.run(5.5f)
        assertTrue("after 6s ${s.speed()}", s.speed() < 0.5f)
    }

    @Test fun light_stick_eases_off_instead_of_braking() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        sim.run(1.5f) { s.input.moveX = 1f }
        sim.run(0.25f) { s.input.moveX = 0.15f }
        // Gentle deceleration loses about 2 m/s here; full ACCEL would lose 5.5.
        assertTrue("speed ${s.speed()}", s.speed() > 6.5f)
        sim.run(2f) { s.input.moveX = 0.15f }
        assertEquals(Tuning.MAX_SPEED * 0.15f, s.speed(), 0.05f)
    }

    @Test fun light_stick_still_turns() {
        val sim = sim(); val s = sim.place(0, -10f, 0f)
        sim.run(1.5f) { s.input.moveX = 1f }
        // Exactly 90° off the heading with a light stick: a carve, whichever way rounding falls.
        sim.run(0.5f) { s.input.moveX = 0f; s.input.moveY = 0.15f }
        assertTrue("vx ${s.vx} vy ${s.vy}", s.vy > 0f && abs(s.vx) < 0.01f)
    }

    @Test fun gliding_skater_comes_to_a_dead_stop() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        sim.run(1.5f) { s.input.moveX = 1f }
        s.input.clear()
        sim.run(6f)
        assertEquals(0f, s.vx, 0f)
        assertEquals(0f, s.vy, 0f)
    }

    @Test fun reversed_stick_brakes_hard() {
        val sim = sim(); val s = sim.place(0, -10f, 0f)
        sim.run(1.5f) { s.input.moveX = 1f }
        var minSpeed = Float.MAX_VALUE
        sim.run(0.5f) { s.input.moveX = -1f; minSpeed = minOf(minSpeed, s.speed()) }
        assertTrue("min speed $minSpeed", minSpeed < 2f)
    }

    /** At top speed along +x, holds the stick at [deg] degrees with length [len] for 0.2 s. */
    private fun cutBack(deg: Double, len: Float): Skater {
        val sim = sim(); val s = sim.place(0, -10f, 0f)
        sim.run(1.5f) { s.input.moveX = 1f }
        val a = Math.toRadians(deg)
        sim.run(0.2f) { s.input.moveX = len * Math.cos(a).toFloat(); s.input.moveY = len * Math.sin(a).toFloat() }
        return s
    }

    @Test fun sharp_cut_back_under_135_degrees_carves() {
        val s = cutBack(120.0, 1f)
        // A hockey stop would hold vy at 0 and shed 4.4 m/s; a carve turns and keeps most of its speed.
        assertTrue("vy ${s.vy}", s.vy > 2f)
        assertTrue("speed ${s.speed()}", s.speed() > 6f)
    }

    @Test fun stopping_is_set_only_while_hockey_stopping() {
        fun run(mx: Float, my: Float): Boolean {
            val sim = sim(); val s = sim.place(0, 0f, 0f)
            s.vx = 8f; s.facing = 0f
            s.input.moveX = mx; s.input.moveY = my
            sim.step()
            return s.stopping
        }
        assertTrue(run(-1f, 0f))
        assertEquals(false, run(0f, 1f))
        assertEquals(false, run(0f, 0f))
    }

    @Test fun full_stick_past_135_degrees_is_a_hockey_stop() {
        val s = cutBack(150.0, 1f)
        assertEquals(0f, s.vy, 1e-3f)
        assertTrue("speed ${s.speed()}", s.speed() < Tuning.MAX_SPEED - 0.19f * Tuning.ACCEL)
    }

    @Test fun light_stick_past_135_degrees_carves() {
        val s = cutBack(150.0, 0.4f)
        assertTrue("vy ${s.vy}", s.vy > 0.5f)
    }

    /** Distance from (x, y) to the footprint of the net on side gs (+1 or -1). */
    private fun netGap(x: Float, y: Float, gs: Float): Float {
        val x0 = if (gs > 0f) Rink.GOAL_X else -Rink.GOAL_X - Rink.NET_DEPTH
        val x1 = x0 + Rink.NET_DEPTH
        val dx = maxOf(x0 - x, 0f, x - x1)
        val dy = maxOf(-Rink.GOAL_HALF_W - y, 0f, y - Rink.GOAL_HALF_W)
        return hypot(dx, dy)
    }

    @Test fun skaters_cannot_enter_nets() {
        // (start x, start y, stick x, stick y): front, side, behind, at both ends, plus a diagonal.
        val cases = listOf(
            floatArrayOf(20f, 0.3f, 1f, 0f), floatArrayOf(26.5f, 6f, 0f, -1f), floatArrayOf(29f, -0.2f, -1f, 0f),
            floatArrayOf(-20f, -0.3f, -1f, 0f), floatArrayOf(-26.5f, -6f, 0f, 1f), floatArrayOf(-29f, 0.2f, 1f, 0f),
            floatArrayOf(22f, 4f, 0.7071f, -0.7071f),
        )
        for (c in cases) {
            val sim = sim(); val s = sim.place(0, c[0], c[1])
            sim.run(3f) {
                s.input.moveX = c[2]; s.input.moveY = c[3]
                val gap = netGap(s.x, s.y, if (s.x > 0f) 1f else -1f)
                assertTrue("case ${c.toList()} at ${s.x},${s.y} gap $gap", gap >= Tuning.SKATER_R - 1e-3f)
            }
        }
    }

    @Test fun cannot_reverse_instantly() {
        val sim = sim(); val s = sim.place(0, -25f, 3f)
        sim.run(1.5f) { s.input.moveX = 1f }
        assertEquals(Tuning.MAX_SPEED, s.vx, 0.01f)
        sim.run(0.1f) { s.input.moveX = -1f }
        assertTrue("vx ${s.vx}", s.vx > 0f)
        // ...but does turn around given time.
        sim.run(2f) { s.input.moveX = -1f }
        assertTrue("vx ${s.vx}", s.vx < -8f)
    }

    @Test fun facing_follows_velocity() {
        val sim = sim(); val s = sim.place(0, 0f, 3f)
        sim.run(1f) { s.input.moveY = 1f }
        assertEquals((Math.PI / 2).toFloat(), s.facing, 1e-3f)
    }

    @Test fun stops_at_boards_and_stays_inside() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        sim.run(5f) {
            s.input.moveY = 1f
            assertTrue(Rink.inside(s.x, s.y, Tuning.SKATER_R - 1e-3f))
        }
        assertTrue(Rink.inside(s.x, s.y, Tuning.SKATER_R - 1e-3f))
        assertEquals(0f, s.vy, 1e-3f)
    }

    @Test fun stays_inside_when_driven_into_a_corner() {
        val sim = sim(); val s = sim.place(0, 15f, 0f)
        sim.run(5f) { s.input.moveX = 0.7071f; s.input.moveY = 0.7071f }
        assertTrue(Rink.inside(s.x, s.y, Tuning.SKATER_R - 1e-3f))
        assertTrue("x ${s.x} y ${s.y}", s.x > 25f && s.y > 8f)
    }

    @Test fun skaters_do_not_overlap() {
        val sim = sim(); val a = sim.place(0, -5f, 3f); val b = sim.place(5, 5f, 3f)
        var minDist = Float.MAX_VALUE
        sim.run(3f) {
            a.input.moveX = 1f; b.input.moveX = -1f
            minDist = minOf(minDist, hypot(a.x - b.x, a.y - b.y))
        }
        minDist = minOf(minDist, hypot(a.x - b.x, a.y - b.y))
        assertTrue("min distance $minDist", minDist >= 2 * Tuning.SKATER_R - 0.02f)
    }

    @Test fun step_is_deterministic() {
        fun play(): List<Float> {
            val sim = sim(seed = 7)
            var t = 0
            sim.run(4f) {
                t++
                for (s in sim.skaters) {
                    s.input.moveX = if ((t / 30 + s.id) % 2 == 0) 1f else -0.6f
                    s.input.moveY = if ((t / 45 + s.id) % 3 == 0) 0.8f else -0.4f
                }
            }
            return sim.skaters.flatMap { listOf(it.x, it.y, it.vx, it.vy) }
        }
        assertEquals(play(), play())
    }

    @Test fun ids_sides_and_positions_follow_conventions() {
        val sim = HockeySim(Random(1))
        assertEquals(10, sim.skaters.size)
        sim.skaters.forEachIndexed { i, s ->
            assertEquals(i, s.id)
            assertEquals(if (i < 5) Side.HOME else Side.AWAY, s.side)
            assertEquals(Position.entries[i % 5], s.pos)
            assertTrue(Rink.inside(s.x, s.y, Tuning.SKATER_R))
        }
        assertEquals(sim.skaters.subList(0, 5), sim.skatersOf(Side.HOME))
        assertEquals(sim.skaters.subList(5, 10), sim.skatersOf(Side.AWAY))
    }

    @Test fun skaters_pinned_against_the_boards_never_overlap() {
        val sim = sim()
        // Three skaters driven into the same stretch of side boards and into each other.
        val a = sim.place(0, 0f, 12f)
        val b = sim.place(1, 0.7f, 11.4f)
        val c = sim.place(5, -0.7f, 11.4f)
        var worst = 0f
        sim.run(2f) {
            a.input.moveX = 0f; a.input.moveY = 1f
            b.input.moveX = -0.7f; b.input.moveY = 0.7f
            c.input.moveX = 0.7f; c.input.moveY = 0.7f
            for (p in listOf(a to b, a to c, b to c)) {
                val overlap = 2f * Tuning.SKATER_R - hypot(p.first.x - p.second.x, p.first.y - p.second.y)
                worst = maxOf(worst, overlap)
            }
        }
        assertTrue("worst overlap $worst m", worst <= 0.02f)
    }
}
