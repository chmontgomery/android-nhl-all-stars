package com.personal.hockey.render

import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Plain JVM: the snow-spray pool and the puck streak, stepped without drawing. */
class EffectsTest {
    private val effects = Effects()

    /** A HOME skater at (0, -3) facing +x shoots toward +x; the director hears it. */
    private fun shoot() {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        val director = AnimDirector(effects)
        val s = sim.skaters[2]
        s.x = 0f; s.y = -3f; s.facing = 0f; s.act = Act.NONE
        sim.puck.carrier = null
        sim.puck.x = 0.9f; sim.puck.y = -3f; sim.puck.vx = 30f; sim.puck.vy = 0f
        sim.events.clear()
        sim.events.add(SimEvent.Shot(Side.HOME, 0.6f, s.id))
        director.onTick(sim)
    }

    @Test fun a_shot_sprays_snow() {
        shoot()
        assertTrue("alive ${effects.alive}", effects.alive > 0)
        // Along the shot direction (+x): the mean particle velocity points that way.
        var sx = 0f; var n = 0
        for (i in effects.life.indices) if (effects.life[i] > 0f) { sx += effects.vx[i]; n++ }
        assertEquals(14, n)
        assertTrue("mean vx ${sx / n}", sx / n > 1f)
    }

    @Test fun particles_die() {
        shoot()
        repeat(Math.round(2f / Tuning.DT)) { effects.step(Tuning.DT) }
        assertEquals(0, effects.alive)
    }

    @Test fun a_full_pool_reuses_particles() {
        val small = Effects(capacity = 16)
        repeat(5) { small.spray(0f, 0f, 1f, 0f, 14, 4f) }
        assertEquals(16, small.alive)
    }

    @Test fun a_fast_puck_leaves_a_streak_and_a_slow_one_does_not() {
        var fast = 0f
        repeat(12) { fast += 30f * Tuning.DT; effects.notePuck(fast, 0f, 0f, 30f) }
        assertEquals(8, effects.streakLength)

        val slow = Effects()
        var x = 0f
        repeat(12) { x += 10f * Tuning.DT; slow.notePuck(x, 0f, 0f, 10f) }
        assertEquals(0, slow.streakLength)

        // Slowing down, the streak shrinks away rather than vanishing at once.
        fast += 0.25f
        effects.notePuck(fast, 0f, 0f, 15f)
        assertTrue("length ${effects.streakLength}", effects.streakLength in 1..7)
        repeat(8) { fast += 0.25f; effects.notePuck(fast, 0f, 0f, 15f) }
        assertEquals(0, effects.streakLength)
    }

    @Test fun a_teleported_puck_leaves_no_streak() {
        var x = 0f
        repeat(10) { x += 30f * Tuning.DT; effects.notePuck(x, 0f, 0f, 30f) }
        effects.notePuck(0f, 0f, 0f, 0f)   // the faceoff after a goal
        assertEquals(0, effects.streakLength)
    }

    @Suppress("DEPRECATION")   // Thread.id: threadId() is not in the Android SDK the tests compile against
    @Test fun effects_do_not_allocate() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        var x = 0f
        fun round(k: Int) {
            effects.step(Tuning.DT)
            if (k % 20 == 0) effects.spray(x, 1f, 0.6f, 0.8f, 14, 4f)
            x += 0.4f
            if (x > 50f) x = -50f
            effects.notePuck(x, 0f, 0f, if (k % 50 < 30) 25f else 5f)
        }
        repeat(200) { round(it) }
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 1000) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 1000 rounds", bytes < 16 * 1024L)
    }
}
