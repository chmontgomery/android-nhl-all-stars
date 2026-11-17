package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

class ActionsTest {
    /** A sim with every skater parked in a row along the near boards, out of the way. */
    private fun sim(seed: Int = 1): HockeySim {
        val sim = HockeySim(Random(seed))
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float, facing: Float = 0f): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f; it.facing = facing }

    /** HOME carrier at (x, y) facing +x with the puck on its stick. */
    private fun HockeySim.carrier(x: Float = 0f, y: Float = 0f): Skater =
        place(0, x, y).also { giveTo(it) }

    /** Steps for [seconds], calling [each] with the tick index before every tick; returns every event. */
    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) {
            each(it)
            step()
            all += events
        }
        return all
    }

    private fun HockeySim.tick(each: () -> Unit = {}): List<SimEvent> = run(Tuning.DT) { each() }

    /** Puck speed at launch: the tick that fired it has already applied one tick of drag. */
    private fun Puck.launchSpeed() = hypot(vx, vy) / (1f - Tuning.PUCK_DRAG * Tuning.DT)
    private fun Skater.speed() = hypot(vx, vy)

    /** Holds shoot for [ticks] ticks, then releases; returns every event including the release tick. */
    private fun HockeySim.shoot(s: Skater, ticks: Int, moveY: Float = 0f): List<SimEvent> {
        val events = run(ticks * Tuning.DT) { s.input.shoot = true; s.input.moveY = moveY }
        return events + tick { s.input.shoot = false }
    }

    private fun List<SimEvent>.shots() = filterIsInstance<SimEvent.Shot>()

    // --- Shoot ---

    @Test fun tap_shot_is_min_speed_full_hold_is_max() {
        val tap = sim(); val a = tap.carrier()
        val tapShots = tap.shoot(a, 2).shots()
        assertEquals(1, tapShots.size)
        assertEquals(0f, tapShots[0].power, 0f)
        assertNull(tap.puck.carrier)
        assertTrue("tap ${tap.puck.launchSpeed()}", tap.puck.launchSpeed() in 22f..24f)

        val full = sim(); val b = full.carrier()
        val fullShots = full.shoot(b, 48).shots()
        assertEquals(1, fullShots.size)
        assertEquals(1f, fullShots[0].power, 1e-3f)
        assertTrue("full ${full.puck.launchSpeed()}", full.puck.launchSpeed() in 37f..38.01f)
    }

    @Test fun shot_scale_multiplies_shot_speed() {
        val sim = sim(); val s = sim.carrier()
        s.shotScale = 1.2f
        sim.shoot(s, 48)
        assertTrue("speed ${sim.puck.launchSpeed()}", sim.puck.launchSpeed() in 44f..46f)
    }

    @Test fun shot_fires_on_release_not_on_press() {
        val sim = sim(); val s = sim.carrier()
        val held = sim.run(20 * Tuning.DT) { s.input.shoot = true }
        assertTrue(held.shots().isEmpty())
        assertSame(s, sim.puck.carrier)
        assertEquals(Act.WINDUP, s.act)
        val released = sim.tick { s.input.shoot = false }
        assertEquals(1, released.shots().size)
        assertEquals(Side.HOME, released.shots()[0].side)
        assertNull(sim.puck.carrier)
        assertEquals(Act.NONE, s.act)
        assertEquals(0f, s.windup, 0f)
    }

    @Test fun held_too_long_fires_itself() {
        val sim = sim(); val s = sim.carrier()
        var firedAt = -1
        var shots = 0
        sim.run(2f) { t ->
            s.input.shoot = true
            if (t > 0 && sim.events.any { it is SimEvent.Shot }) { shots++; if (firedAt < 0) firedAt = t }
        }
        if (sim.events.any { it is SimEvent.Shot }) shots++
        assertEquals(1, shots)
        // firedAt is the index of the tick after the one that fired; the firing tick is firedAt - 1.
        val firedTime = firedAt * Tuning.DT
        assertTrue("fired at $firedTime", firedTime <= Tuning.WINDUP_MAX + 0.2f + Tuning.DT + 1e-4f)
    }

    @Test fun shot_heads_for_the_attacked_net() {
        for (seed in 1..10) {
            val sim = sim(seed); val s = sim.carrier(10f, 5f)
            sim.shoot(s, 2)
            val p = sim.puck
            val want = atan2(Tuning.SHOT_AIM * Rink.GOAL_HALF_W - p.y, Rink.GOAL_X - p.x)
            assertEquals("seed $seed", want, atan2(p.vy, p.vx), 0.15f)
        }
        // AWAY attacks -x.
        val sim = sim(); val s = sim.place(5, -10f, -5f, PI.toFloat())
        sim.giveTo(s)
        sim.shoot(s, 2)
        val p = sim.puck
        val want = atan2(Tuning.SHOT_AIM * Rink.GOAL_HALF_W - p.y, -Rink.GOAL_X - p.x)
        assertEquals(want, atan2(p.vy, p.vx), 0.15f)
    }

    /** Where a loose puck's line crosses the attacked goal line. */
    private fun Puck.yAtGoalLine(): Float = y + vy * (Rink.GOAL_X - x) / vx

    @Test fun stick_up_and_down_pick_opposite_sides() {
        for (seed in 1..10) {
            val up = sim(seed); val a = up.carrier(20f, 0f)
            up.shoot(a, 2, moveY = 1f)
            assertTrue("seed $seed up ${up.puck.yAtGoalLine()}", up.puck.yAtGoalLine() > 0f)

            val down = sim(seed); val b = down.carrier(20f, 0f)
            down.shoot(b, 2, moveY = -1f)
            assertTrue("seed $seed down ${down.puck.yAtGoalLine()}", down.puck.yAtGoalLine() < 0f)
        }
    }

    @Test fun full_power_shots_from_the_slot_rarely_miss_the_net() {
        var misses = 0; var shots = 0
        for (y in listOf(-1.5f, 0f, 1.5f)) for (seed in 1..50) {
            val sim = sim(seed); val s = sim.carrier(Rink.GOAL_X - 6.9f, y)   // puck 6 m out
            sim.shoot(s, 48)
            shots++
            if (abs(sim.puck.yAtGoalLine()) >= Rink.GOAL_HALF_W) misses++
        }
        assertTrue("missed $misses of $shots", misses * 20 <= shots)
    }

    @Test fun losing_puck_cancels_windup() {
        val sim = sim(); val s = sim.carrier()
        val poker = sim.place(5, 2.4f, 0f, PI.toFloat())   // puck at (0.9, 0): 1.5 m dead ahead
        val events = sim.run(2f) { t ->
            s.input.shoot = t < 90
            poker.input.poke = t == 10
            if (t == 12) {
                assertNotSame(s, sim.puck.carrier)
                assertFalse(s.act == Act.WINDUP)
                assertEquals(0f, s.windup, 0f)
            }
        }
        assertTrue(events.contains(SimEvent.Poke(true)))
        assertTrue(events.shots().isEmpty())
        assertEquals(0f, s.windup, 0f)
        assertFalse(s.act == Act.WINDUP)
    }

    // --- Pass ---

    @Test fun pass_goes_to_teammate_in_stick_direction() {
        for (dir in floatArrayOf(1f, -1f)) {
            val sim = sim(); val s = sim.carrier()
            val upMate = sim.place(1, 0f, 6f); val downMate = sim.place(2, 0f, -6f)
            s.input.moveY = dir
            val want = if (dir > 0f) upMate else downMate
            assertSame(want, sim.passTargetFor(s))
            val events = sim.tick { s.input.moveY = dir; s.input.pass = true }
            assertTrue(events.hasPass(Side.HOME))
            assertNull(sim.puck.carrier)
            assertSame(want, sim.puck.passTarget)
            assertTrue("vy ${sim.puck.vy}", sim.puck.vy * dir > 15f)
        }
    }

    @Test fun pass_with_stick_breaks_near_ties_toward_the_nearer_teammate() {
        val sim = sim(); val s = sim.carrier()
        val far = sim.place(1, 0f, 12f)           // dead on the stick, far away
        val near = sim.place(2, 0.6f, 4f)         // 0.15 rad off the stick, much nearer
        s.input.moveY = 1f
        assertSame(near, sim.passTargetFor(s))
        near.x = 3f                               // now 0.64 rad off: the far one wins
        assertSame(far, sim.passTargetFor(s))
    }

    @Test fun centred_pass_picks_open_forward_teammate() {
        val sim = sim(); val s = sim.carrier()
        val blocked = sim.place(1, 15f, 0f)
        sim.place(5, 7f, 0.5f)                    // opponent in the lane to the furthest teammate
        val open = sim.place(2, 10f, 6f)
        sim.place(3, -5f, 5f)                     // open but behind
        assertSame(open, sim.passTargetFor(s))
        // Clear the lane and the furthest teammate wins.
        sim.skaters[5].y = 4f
        sim.skaters[5].x = 0f
        assertSame(blocked, sim.passTargetFor(s))
    }

    @Test fun centred_pass_for_away_looks_toward_minus_x() {
        val sim = sim(); val s = sim.place(5, 0f, 3f, PI.toFloat())
        sim.giveTo(s)
        val forward = sim.place(6, -12f, 6f)
        sim.place(7, 8f, 6f)
        assertSame(forward, sim.passTargetFor(s))
    }

    @Test fun pass_leads_a_moving_receiver_and_is_caught() {
        val sim = sim(); val s = sim.carrier(-5f, 0f)
        val receiver = sim.place(1, 5f, 4f)
        receiver.vx = 6f
        var caughtAt = -1f
        sim.run(1.5f) { t ->
            receiver.input.moveX = 6f / Tuning.MAX_SPEED
            s.input.pass = t == 0
            if (caughtAt < 0f && sim.puck.carrier === receiver) caughtAt = t * Tuning.DT
        }
        if (caughtAt < 0f && sim.puck.carrier === receiver) caughtAt = 1.5f
        assertTrue("never caught; carrier ${sim.puck.carrier?.id}", caughtAt > 0f)
    }

    // --- Deke ---

    @Test fun deke_moves_sideways_and_has_cooldown() {
        val sim = sim(); val s = sim.carrier()
        sim.tick { s.input.deke = true }
        assertEquals(Act.DEKE, s.act)
        assertEquals(Tuning.DEKE_SPEED, s.vy, 1e-3f)     // 90° left of facing +x
        assertEquals(0f, s.vx, 1e-3f)
        sim.run(Tuning.DEKE_TIME) { s.input.deke = false }
        assertEquals(Act.NONE, s.act)
        assertSame(s, sim.puck.carrier)
        sim.run(0.2f)
        // 0.6 s after the first: nothing.
        sim.tick { s.input.deke = true }
        assertEquals(Act.NONE, s.act)
        sim.run(0.5f) { s.input.deke = false }
        sim.tick { s.input.deke = true }
        assertEquals(Act.DEKE, s.act)
    }

    @Test fun deke_follows_the_stick() {
        val sim = sim(); val s = sim.carrier()
        sim.tick { s.input.moveY = -1f; s.input.deke = true }
        assertEquals(-Tuning.DEKE_SPEED, s.vy, 0.3f)
    }

    @Test fun deke_keeps_facing_so_the_puck_stays_in_front() {
        val sim = sim(); val s = sim.carrier()
        sim.tick { s.input.deke = true }
        sim.run(Tuning.DEKE_TIME - 2 * Tuning.DT) {
            s.input.deke = false
            assertEquals(Act.DEKE, s.act)
            assertEquals(0f, s.facing, 1e-6f)
            assertEquals(s.y, sim.puck.y, 1e-4f)          // still straight ahead, not off to the side
        }
        // A second deke after the cooldown doesn't spin the skater either.
        sim.run(Tuning.DEKE_CD) { s.input.deke = false; s.input.moveX = 0f }
        val facing = s.facing
        sim.tick { s.input.deke = true }
        sim.run(Tuning.DEKE_TIME - 2 * Tuning.DT) { s.input.deke = false; assertEquals(facing, s.facing, 1e-6f) }
    }

    // --- Poke ---

    @Test fun successful_poke_locks_the_poker_briefly() {
        val sim = sim(); sim.carrier()
        val poker = sim.place(5, 2.4f, 0f, PI.toFloat())
        val events = sim.tick { poker.input.poke = true }
        assertTrue(events.contains(SimEvent.Poke(true)))
        assertEquals(Tuning.POKE_PICKUP_LOCK, poker.pickupLock, 1e-4f)
        assertNotSame(poker, sim.puck.carrier)
    }

    @Test fun poke_knocks_puck_loose() {
        val sim = sim(); val s = sim.carrier()
        val poker = sim.place(5, 2.4f, 0f, PI.toFloat())
        val events = sim.tick { poker.input.poke = true }
        assertNull(sim.puck.carrier)
        assertTrue(events.contains(SimEvent.Poke(true)))
        assertEquals(Act.POKE, poker.act)
        assertTrue("puck vx ${sim.puck.vx}", sim.puck.vx < -5f)
        assertTrue(s.pickupLock > 0f)
    }

    @Test fun poke_misses_out_of_arc_and_out_of_reach() {
        // Out of arc: facing up, puck straight across.
        val a = sim(); val ca = a.carrier()
        val pa = a.place(5, 2.4f, 0f, PI.toFloat() / 2f)
        val ea = a.tick { pa.input.poke = true }
        assertTrue(ea.contains(SimEvent.Poke(false)))
        assertSame(ca, a.puck.carrier)
        // Out of reach: 2.1 m.
        val b = sim(); val cb = b.carrier()
        val pb = b.place(5, 3f, 0f, PI.toFloat())
        val eb = b.tick { pb.input.poke = true }
        assertTrue(eb.contains(SimEvent.Poke(false)))
        assertSame(cb, b.puck.carrier)
    }

    @Test fun poke_misses_a_deking_carrier() {
        val sim = sim(); val s = sim.carrier()
        s.act = Act.DEKE; s.actT = 1f
        val poker = sim.place(5, 2.4f, 0f, PI.toFloat())
        val events = sim.tick { poker.input.poke = true }
        assertTrue(events.contains(SimEvent.Poke(false)))
        assertSame(s, sim.puck.carrier)
    }

    @Test fun poke_does_not_strip_a_teammate() {
        val sim = sim(); val s = sim.carrier()
        val mate = sim.place(1, 2.4f, 0f, PI.toFloat())
        val events = sim.tick { mate.input.poke = true }
        assertTrue(events.contains(SimEvent.Poke(false)))
        assertSame(s, sim.puck.carrier)
    }

    @Test fun poke_knocks_a_loose_puck() {
        val sim = sim()
        val poker = sim.place(5, 2.4f, 0f, PI.toFloat())
        sim.puck.x = 0.9f; sim.puck.y = 0f
        val events = sim.tick { poker.input.poke = true }
        assertTrue(events.contains(SimEvent.Poke(true)))
        assertTrue("puck vx ${sim.puck.vx}", sim.puck.vx < -5f)
    }

    @Test fun poke_has_cooldown() {
        val sim = sim(); sim.carrier()
        val poker = sim.place(5, 3f, 0f, PI.toFloat())   // out of reach: just counting attempts
        val pokes = ArrayList<SimEvent>()
        pokes += sim.tick { poker.input.poke = true }
        pokes += sim.run(0.3f) { poker.input.poke = false }
        pokes += sim.tick { poker.input.poke = true }       // 0.32 s in: still cooling down
        assertEquals(1, pokes.count { it is SimEvent.Poke })
        assertEquals(Act.NONE, poker.act)
        pokes += sim.run(0.3f) { poker.input.poke = false }
        pokes += sim.tick { poker.input.poke = true }       // 0.63 s in: ready
        assertEquals(2, pokes.count { it is SimEvent.Poke })
    }

    // --- Body ---

    @Test fun body_knocks_carrier_down_and_frees_puck() {
        val sim = sim()
        val victim = sim.place(5, 2f, 0f, PI.toFloat())
        sim.giveTo(victim)
        val checker = sim.place(0, 0f, 0f)
        val events = ArrayList<SimEvent>()
        var hitTick = -1
        sim.run(0.3f) { t ->
            checker.input.body = t == 0
            if (hitTick < 0 && sim.events.contains(SimEvent.Hit(checker.id, victim.id))) hitTick = t
        }.also { events += it }
        assertTrue(events.contains(SimEvent.Hit(checker.id, victim.id)))
        assertEquals(Act.DOWN, victim.act)
        assertEquals(Act.NONE, checker.act)
        assertNotSame(victim, sim.puck.carrier)
        assertTrue("victim pushed ${victim.vx}", victim.vx > 1f)
        // DOWN for DOWN_TIME, measured from the hit.
        val elapsed = (0.3f / Tuning.DT - hitTick) * Tuning.DT
        sim.run(Tuning.DOWN_TIME - elapsed - 0.05f) { assertNotSame(victim, sim.puck.carrier) }
        assertEquals(Act.DOWN, victim.act)
        sim.run(0.1f) { assertNotSame(victim, sim.puck.carrier) }
        assertEquals(Act.NONE, victim.act)
        // Locked out of the puck until 0.8 s after the hit.
        sim.run(0.1f) { assertNotSame(victim, sim.puck.carrier) }
    }

    @Test fun body_hit_sets_pickup_lock_and_sends_puck_along_the_hit() {
        val sim = sim()
        val victim = sim.place(5, 1.05f, 0f, PI.toFloat())
        sim.giveTo(victim)
        val checker = sim.place(0, 0f, 0f)
        val events = sim.tick { checker.input.body = true }
        assertTrue(events.contains(SimEvent.Hit(checker.id, victim.id)))
        assertEquals(Tuning.DOWN_TIME + 0.2f, victim.pickupLock, 1e-4f)
        assertTrue("puck vx ${sim.puck.vx}", sim.puck.vx > 4f)
    }

    @Test fun landed_check_leaves_checker_at_most_its_own_top_speed() {
        val sim = sim()
        val victim = sim.place(5, 1.05f, 0f, PI.toFloat())
        val checker = sim.place(0, 0f, 0f)
        checker.speedScale = 0.75f
        val events = sim.tick { checker.input.body = true }
        assertTrue(events.contains(SimEvent.Hit(checker.id, victim.id)))
        assertTrue("speed ${checker.speed()}", checker.speed() <= Tuning.MAX_SPEED * 0.75f + 1e-4f)
    }

    @Test fun body_misses_a_deking_carrier_then_recovers() {
        val sim = sim()
        val victim = sim.place(5, 2f, 0.7f, PI.toFloat())
        sim.giveTo(victim)
        victim.act = Act.DEKE; victim.actT = 2f
        val checker = sim.place(0, 0f, 0f)
        val events = sim.run(Tuning.BODY_TIME + Tuning.DT) { t -> checker.input.body = t == 0 }
        assertTrue(events.none { it is SimEvent.Hit })
        assertEquals(Act.RECOVER, checker.act)
        var maxSpeed = 0f
        sim.run(Tuning.RECOVER_TIME - 2 * Tuning.DT) {
            checker.input.moveX = 1f
            maxSpeed = maxOf(maxSpeed, checker.speed())
            assertEquals(Act.RECOVER, checker.act)
        }
        assertTrue("max $maxSpeed", maxSpeed <= Tuning.RECOVER_SPEED * Tuning.MAX_SPEED + 1e-3f)
        sim.run(0.1f) { checker.input.moveX = 1f }
        assertEquals(Act.NONE, checker.act)
        assertSame(victim, sim.puck.carrier)
    }

    @Test fun body_can_hit_a_non_carrier() {
        val sim = sim()
        val victim = sim.place(5, 2f, 0f, PI.toFloat())
        val checker = sim.place(0, 0f, 0f)
        val events = sim.run(0.3f) { t -> checker.input.body = t == 0 }
        assertTrue(events.contains(SimEvent.Hit(checker.id, victim.id)))
        assertEquals(Act.DOWN, victim.act)
    }

    @Test fun downed_skater_slides_and_ignores_input() {
        val sim = sim(); val s = sim.place(0, 0f, 0f)
        s.act = Act.DOWN; s.actT = 0.5f; s.vx = 4f
        sim.run(0.25f) { s.input.moveY = 1f }
        assertEquals(0f, s.vy, 0f)
        assertTrue("vx ${s.vx}", s.vx in 1f..3.5f)
    }

    // --- Misc ---

    @Test fun buttons_that_do_not_apply_do_nothing() {
        val sim = sim(); val c = sim.carrier()
        val d = sim.place(6, 5f, 5f)
        val e1 = sim.tick { c.input.poke = true; c.input.body = true; d.input.shoot = true; d.input.pass = true; d.input.deke = true }
        assertTrue("events $e1", e1.none { it !is SimEvent.Pickup })
        assertEquals(Act.NONE, c.act); assertEquals(Act.NONE, d.act)
        assertSame(c, sim.puck.carrier)
        assertEquals(0f, d.windup, 0f)
        assertEquals(0f, d.speed(), 0f)
        // A downed skater can't act at all.
        val sim2 = sim(); val down = sim2.place(5, 2f, 0f); down.act = Act.DOWN; down.actT = 0.5f
        val e2 = sim2.tick { down.input.body = true; down.input.poke = true }
        assertTrue("events $e2", e2.isEmpty())
        assertEquals(Act.DOWN, down.act)
    }

    @Test fun mashing_everything_never_breaks_invariants() {
        val sim = HockeySim(Random(11))
        sim.startPlayForTest()
        val rnd = Random(99)
        sim.giveTo(sim.skaters[0])
        sim.run(60f) {
            for (s in sim.skaters) {
                s.input.moveX = rnd.nextFloat() * 2f - 1f
                s.input.moveY = rnd.nextFloat() * 2f - 1f
                s.input.shoot = rnd.nextFloat() < 0.3f
                s.input.pass = rnd.nextFloat() < 0.1f
                s.input.deke = rnd.nextFloat() < 0.1f
                s.input.poke = rnd.nextFloat() < 0.2f
                s.input.body = rnd.nextFloat() < 0.1f
            }
            for (s in sim.skaters) {
                assertTrue("skater ${s.id} at ${s.x},${s.y}", s.x.isFinite() && s.y.isFinite() && s.vx.isFinite() && s.vy.isFinite())
                assertTrue("skater ${s.id} at ${s.x},${s.y}", Rink.inside(s.x, s.y, Tuning.SKATER_R - 1e-3f))
                for (t in floatArrayOf(s.actT, s.dekeCd, s.pokeCd, s.pickupLock)) assertTrue("skater ${s.id} timer $t", t >= -Tuning.DT)
                if (s.act == Act.WINDUP) assertSame(s, sim.puck.carrier)
            }
            val p = sim.puck
            assertTrue("puck at ${p.x},${p.y}", p.x.isFinite() && p.y.isFinite() && Rink.inside(p.x, p.y, 0f))
            assertFalse(p.carrier?.act == Act.DOWN)
            assertTrue(sim.skaters.count { it === p.carrier } <= 1)
        }
    }
}
