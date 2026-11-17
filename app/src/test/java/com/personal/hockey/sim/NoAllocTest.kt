package com.personal.hockey.sim

import com.personal.hockey.ai.Difficulty
import com.personal.hockey.ai.Match
import com.personal.hockey.settings.Level
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sim and AI must not allocate per tick: after warm-up, thousands of ticks allocate almost
 * nothing (rare events such as a goal or a save may allocate their event object). A whole game is
 * played first so one-off costs (class loading, the first goal's code path) are not counted.
 *
 * Measured with com.sun.management.ThreadMXBean.getThreadAllocatedBytes, reached by reflection
 * because the unit-test compile classpath is android.jar, which has no java.lang.management.
 */
class NoAllocTest {
    private val threads = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getThreadMXBean").invoke(null)
    private val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
        .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private val budget = 64 * 1024L

    private fun allocatedBy(block: () -> Unit): Long {
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        block()
        return allocatedBytes.invoke(threads, id) as Long - before
    }

    /** Plays a full game with both kinds of [play] so every code path has run once. */
    private fun warmUp(play: (Match, Int) -> Unit) {
        val match = Match(Level.HARD, 99)
        var tick = 0
        while (match.sim.phase != Phase.FINAL) play(match, tick++)
    }

    private fun mash(match: Match, tick: Int, human: SkaterInput) {
        // Skate at the far net, pressing every button in turn, so every action path runs.
        human.clear()
        human.moveX = 1f; human.moveY = if (tick / 90 % 2 == 0) 0.4f else -0.4f
        when (tick % 40) {
            in 0..9 -> human.shoot = true
            in 10..12 -> human.pass = true
            in 20..22 -> { human.deke = true; human.poke = true }
            in 30..32 -> human.body = true
        }
        match.tick(human)
    }

    @Test fun ai_only_match_does_not_allocate_per_tick() {
        warmUp { m, _ -> m.tick(null) }
        val match = Match(Level.HARD, 7, humanControlled = false, homeDifficulty = Difficulty.HARD)
        repeat(600) { match.tick(null) }
        val bytes = allocatedBy { repeat(6000) { match.tick(null) } }
        assertTrue("allocated $bytes bytes in 6000 ticks", bytes < budget)
    }

    @Test fun human_controlled_match_does_not_allocate_per_tick() {
        val human = SkaterInput()
        warmUp { m, t -> mash(m, t, human) }
        val match = Match(Level.EASY, 11)
        var tick = 0
        repeat(600) { mash(match, tick++, human) }
        val bytes = allocatedBy { repeat(6000) { mash(match, tick++, human) } }
        assertTrue("allocated $bytes bytes in 6000 ticks", bytes < budget)
    }
}
