package com.personal.hockey.render.anim

import com.personal.hockey.render.Effects
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.Skeleton
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import com.personal.hockey.sim.Tuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** Plain JVM: events are injected into sim.events and fed to [AnimDirector.onTick] tick by tick. */
class AnimDirectorTest {
    private val sim = HockeySim(Random(1))
    private val director = AnimDirector()
    private val testClips = arrayOf(Clip.WRIST_FOLLOW, Clip.SLAP_FOLLOW)
    private val saved = arrayOfNulls<ClipData>(testClips.size)

    /** A 0.35 s clip that keys only the blade toe, lifting it to 1.5 m. */
    private fun bladeClip(): ClipData {
        val k0 = Pose(); val k1 = Pose()
        k0.a[Skeleton.BLADE_TOE] = 1f; k0.z[Skeleton.BLADE_TOE] = 1.5f
        k1.a[Skeleton.BLADE_TOE] = 1f; k1.z[Skeleton.BLADE_TOE] = 1.5f
        val keyed = BooleanArray(Skeleton.SLOTS).also { it[Skeleton.BLADE_TOE] = true }
        return ClipData(floatArrayOf(0f, 0.35f), arrayOf(k0, k1), keyed, loop = false)
    }

    @Before fun registerTestClips() {
        for (i in testClips.indices) { saved[i] = ClipLibrary[testClips[i]]; ClipLibrary.register(testClips[i], bladeClip()) }
    }

    @After fun restoreClips() {
        for (i in testClips.indices) ClipLibrary.register(testClips[i], saved[i])
    }

    private fun tick(vararg events: SimEvent) {
        sim.events.clear()
        for (e in events) sim.events.add(e)
        director.onTick(sim)
        sim.events.clear()
    }

    private fun ticksFor(seconds: Float) = repeat(Math.round(seconds / Tuning.DT)) { tick() }

    @Test fun a_weak_shot_plays_the_wrist_follow_through() {
        tick(SimEvent.Shot(Side.HOME, power = 0.3f, shooterId = 2))
        val st = director.skaters[2]
        assertEquals(Clip.WRIST_FOLLOW, st.clip)
        assertFalse(st.slap)
        assertFalse(st.oneTimer)
    }

    @Test fun a_hard_shot_plays_the_slap_follow_through() {
        tick(SimEvent.Shot(Side.AWAY, power = 0.7f, shooterId = 7))
        val st = director.skaters[7]
        assertEquals(Clip.SLAP_FOLLOW, st.clip)
        assertTrue(st.slap)
    }

    @Test fun the_slap_threshold_is_the_windup_that_gives_half_power() {
        val power = (SLAP_WINDUP - Tuning.WINDUP_MIN) / (Tuning.WINDUP_MAX - Tuning.WINDUP_MIN)
        assertEquals(SLAP_POWER, power, 1e-4f)
    }

    @Test fun a_quick_shot_off_a_pass_is_a_one_timer() {
        tick(SimEvent.Pass(Side.HOME, passerId = 1, targetId = 3))
        tick(SimEvent.Pickup(Side.HOME, skaterId = 3))
        assertTrue(director.skaters[3].pickupFromPass)
        ticksFor(0.4f)
        tick(SimEvent.Shot(Side.HOME, power = 0.1f, shooterId = 3))
        val st = director.skaters[3]
        assertEquals(Clip.SLAP_FOLLOW, st.clip)
        assertTrue(st.oneTimer)
        assertFalse("a one-timer is not a wound-up slapshot", st.slap)
    }

    @Test fun a_quick_shot_not_off_a_pass_is_a_wrist_shot() {
        tick(SimEvent.Pickup(Side.HOME, skaterId = 3))
        ticksFor(0.4f)
        tick(SimEvent.Shot(Side.HOME, power = 0.1f, shooterId = 3))
        assertEquals(Clip.WRIST_FOLLOW, director.skaters[3].clip)
        assertFalse(director.skaters[3].oneTimer)
    }

    @Test fun picking_up_a_pass_meant_for_someone_else_is_not_off_a_pass() {
        tick(SimEvent.Pass(Side.HOME, passerId = 1, targetId = 4))
        tick(SimEvent.Pickup(Side.HOME, skaterId = 3))
        assertFalse(director.skaters[3].pickupFromPass)
        tick(SimEvent.Shot(Side.HOME, power = 0.1f, shooterId = 3))
        assertEquals(Clip.WRIST_FOLLOW, director.skaters[3].clip)
    }

    @Test fun a_pass_ended_by_a_save_is_not_received_off_the_rebound() {
        tick(SimEvent.Pass(Side.HOME, passerId = 1, targetId = 3))
        tick(SimEvent.Save(Side.AWAY))
        tick(SimEvent.Pickup(Side.HOME, skaterId = 3))
        assertFalse(director.skaters[3].pickupFromPass)
    }

    @Test fun a_slow_shot_off_a_pass_is_not_a_one_timer() {
        tick(SimEvent.Pass(Side.HOME, passerId = 1, targetId = 3))
        tick(SimEvent.Pickup(Side.HOME, skaterId = 3))
        ticksFor(0.7f)
        tick(SimEvent.Shot(Side.HOME, power = 0.1f, shooterId = 3))
        assertEquals(Clip.WRIST_FOLLOW, director.skaters[3].clip)
        assertFalse(director.skaters[3].oneTimer)
    }

    @Test fun a_clip_without_a_library_entry_does_not_play() {
        val saved = ClipLibrary[Clip.HUG]
        ClipLibrary.register(Clip.HUG, null)
        try {
            val st = AnimState()
            director.play(st, Clip.HUG)
            assertEquals(Clip.NONE, st.clip)
        } finally {
            ClipLibrary.register(Clip.HUG, saved)
        }
    }

    @Test fun blend_weight_eases_in_over_a_tenth_of_a_second() {
        val st = director.skaters[0]
        director.play(st, Clip.WRIST_FOLLOW)
        assertEquals(0f, st.weight, 0f)
        repeat(3) { director.advance(Tuning.DT) }   // 0.05 s
        assertTrue("weight ${st.weight} at 0.05 s", st.weight in 0.4f..0.6f)
        repeat(3) { director.advance(Tuning.DT) }   // 0.1 s
        assertEquals(1f, st.weight, 1e-4f)
    }

    @Test fun a_finished_clip_eases_out_and_clears() {
        val st = director.skaters[0]
        director.play(st, Clip.WRIST_FOLLOW)
        repeat(Math.round(0.3f / Tuning.DT)) { director.advance(Tuning.DT) }
        assertEquals("fully in before the clip's end", 1f, st.weight, 1e-4f)
        repeat(Math.round(0.17f / Tuning.DT)) { director.advance(Tuning.DT) }   // past the end, plus BLEND_S
        assertEquals(Clip.NONE, st.clip)
        assertEquals(0f, st.weight, 0f)
    }

    @Test fun playing_none_eases_the_clip_out() {
        val st = director.skaters[0]
        director.play(st, Clip.WRIST_FOLLOW)
        repeat(6) { director.advance(Tuning.DT) }
        director.play(st, Clip.NONE)
        repeat(3) { director.advance(Tuning.DT) }
        assertTrue("weight ${st.weight} halfway out", st.weight in 0.4f..0.6f)
        repeat(4) { director.advance(Tuning.DT) }
        assertEquals(Clip.NONE, st.clip)
        assertEquals(0f, st.weight, 0f)
    }

    @Test fun blend_moves_only_keyed_slots() {
        val st = director.skaters[0]
        director.play(st, Clip.WRIST_FOLLOW)
        repeat(3) { director.advance(Tuning.DT) }
        val w = st.weight
        val base = Pose()
        for (i in 0 until Skeleton.SLOTS) { base.a[i] = 0.1f * i; base.b[i] = -0.05f * i; base.z[i] = 0.02f * i }
        val out = Pose()
        blendPose(st, base, out)
        val t = Skeleton.BLADE_TOE
        for (i in 0 until Skeleton.SLOTS) {
            if (i == t) continue
            assertEquals("a[$i]", base.a[i], out.a[i], 0f)
            assertEquals("b[$i]", base.b[i], out.b[i], 0f)
            assertEquals("z[$i]", base.z[i], out.z[i], 0f)
        }
        assertEquals(base.a[t] + (1f - base.a[t]) * w, out.a[t], 1e-5f)
        assertEquals(base.b[t] + (0f - base.b[t]) * w, out.b[t], 1e-5f)
        assertEquals(base.z[t] + (1.5f - base.z[t]) * w, out.z[t], 1e-5f)

        blendPose(st, base, base)   // in place
        assertEquals(out.z[t], base.z[t], 0f)
    }

    @Test fun blend_interpolates_between_keys() {
        val k0 = Pose(); val k1 = Pose()
        k1.z[Skeleton.HEAD] = 2f
        val keyed = BooleanArray(Skeleton.SLOTS).also { it[Skeleton.HEAD] = true }
        ClipLibrary.register(Clip.WRIST_FOLLOW, ClipData(floatArrayOf(0f, 0.4f), arrayOf(k0, k1), keyed, loop = false))
        val st = director.skaters[0]
        director.play(st, Clip.WRIST_FOLLOW)
        repeat(12) { director.advance(Tuning.DT) }   // 0.2 s: halfway between the keys, fully in
        val out = Pose()
        blendPose(st, Pose(), out)
        assertEquals(1f, out.z[Skeleton.HEAD], 1e-3f)
    }

    @Test fun turn_rate_follows_facing() {
        val s = sim.skaters[4]
        s.facing = 0f
        tick()
        repeat(5) { s.facing += 0.05f; tick() }
        assertEquals(3f, director.skaters[4].turnRate, 1e-3f)
        s.facing = 3.12f; tick()
        s.facing = -3.13f; tick()   // across ±PI: a small left turn, not a spin
        assertEquals((2 * Math.PI - 6.25).toFloat() / Tuning.DT, director.skaters[4].turnRate, 1e-2f)
    }

    @Test fun a_skater_down_at_the_goal_gets_his_role_but_no_clip_until_he_is_up() {
        sim.phase = Phase.GOAL
        sim.lastScorerId = 0
        val down = sim.skaters[7]
        down.act = Act.DOWN
        tick(SimEvent.Goal(Side.HOME, 0))
        ticksFor(0.5f)
        val st = director.skaters[7]
        assertEquals(Role.DEJECTED, st.role)
        assertEquals(Clip.NONE, st.clip)
        down.act = Act.NONE
        tick()
        assertEquals(Clip.DEJECTED, st.clip)
    }

    @Test fun celebrations_do_not_allocate() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val director = AnimDirector(Effects())   // the knee slide sprays snow
        val goal = SimEvent.Goal(Side.HOME, 0)
        sim.score[Side.HOME.index] = 2   // the second goal: the knee slide
        sim.lastScorerId = 0
        sim.skaters[1].x = sim.skaters[0].x + 1f; sim.skaters[1].y = sim.skaters[0].y   // close enough to hug
        // A 4 s goal pause, then 1 s of faceoff that clears the roles, over and over.
        fun round(k: Int) {
            sim.events.clear()
            val t = k % 300
            sim.phase = if (t < 240) Phase.GOAL else Phase.FACEOFF
            if (t == 0) sim.events.add(goal)
            director.onTick(sim)
            director.advance(Tuning.DT)
        }
        repeat(300) { round(it) }
        assertEquals(Clip.NONE, director.skaters[1].clip)   // the first round ran through and cleared
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 3000) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 3000 rounds", bytes < 16 * 1024L)
    }

    @Test fun on_tick_and_advance_do_not_allocate() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val director = AnimDirector(Effects())   // shots spray snow too
        val shot = SimEvent.Shot(Side.HOME, 0.7f, 2)
        val pass = SimEvent.Pass(Side.HOME, 1, 3)
        val pickup = SimEvent.Pickup(Side.HOME, 3)
        fun round(k: Int) {
            sim.events.clear()
            when (k % 3) { 0 -> sim.events.add(pass); 1 -> sim.events.add(pickup); else -> sim.events.add(shot) }
            sim.skaters[k % 10].facing += 0.03f
            director.onTick(sim)
            director.advance(Tuning.DT)
        }
        repeat(200) { round(it) }
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 3000) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 3000 rounds", bytes < 16 * 1024L)
    }

    @Test fun a_shot_flight_and_landing_do_not_allocate() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val director = AnimDirector(Effects())
        sim.startPlayForTest()
        val shooter = sim.skaters[2]
        shooter.x = 8f; shooter.y = 0f   // within FLAT_SHOT_M of the goal line at +GOAL_X
        val shot = SimEvent.Shot(Side.HOME, 0.7f, shooter.id)
        val save = SimEvent.Save(Side.AWAY)
        val puck = sim.puck
        puck.carrier = null
        var peak = 0f
        // Every 30 ticks: a slapshot flies at the net for 18 ticks, a save lands it, the puck slides.
        fun round(k: Int) {
            sim.events.clear()
            val t = k % 30
            when (t) { 0 -> sim.events.add(shot); 18 -> sim.events.add(save) }
            puck.x = 9f + 0.5f * t; puck.y = 0f; puck.vx = if (t < 18) 30f else -10f; puck.vy = 0f
            director.onTick(sim)
            director.advance(Tuning.DT)
            peak = maxOf(peak, director.flight.height)
        }
        repeat(210) { round(it) }
        assertTrue("the shots never lifted: peak $peak", peak > 1f)
        assertEquals("landed between shots", 0f, director.flight.height, 0f)
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 3000) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 3000 rounds", bytes < 16 * 1024L)
    }

    @Test fun the_butterfly_does_not_allocate() {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val director = AnimDirector(Effects())
        sim.startPlayForTest()
        sim.skaters[2].x = sim.goalies[Side.AWAY.index].x - 12f   // a shot, not a dump
        val shot = SimEvent.Shot(Side.HOME, 0.7f, 2)
        val save = SimEvent.Save(Side.AWAY)
        val pickup = SimEvent.Pickup(Side.HOME, 3)
        val g = sim.goalies[Side.AWAY.index]
        var downs = 0
        // Every 300 ticks: a shot and a covered save, held 1 s, then up; a shot and a pickup; a shot
        // left live to the cap; a shot cut short by a faceoff; a shot cut short by a break.
        fun round(k: Int) {
            sim.events.clear()
            val t = k % 300
            when (t) {
                0, 60, 100, 200, 240 -> sim.events.add(shot)
                10 -> { sim.events.add(save); sim.events.add(SimEvent.Cover) }
                70 -> sim.events.add(pickup)
                220 -> sim.events.add(SimEvent.FaceoffDrop)
            }
            g.holding = t in 10..69
            sim.phase = if (t in 250..259) Phase.BREAK else Phase.PLAY
            director.onTick(sim)
            director.advance(Tuning.DT)
            if (t == 5 && director.goalies[Side.AWAY.index].clip == Clip.GOALIE_BUTTERFLY) downs++
        }
        repeat(600) { round(it) }
        assertEquals(2, downs)
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        for (k in 0 until 3000) round(k)
        val bytes = allocatedBytes.invoke(threads, id) as Long - before
        assertTrue("allocated $bytes bytes in 3000 rounds", bytes < 16 * 1024L)
    }
}
