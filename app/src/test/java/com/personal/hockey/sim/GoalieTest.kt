package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GoalieTest {
    private val standard = GoalieSkill(0.1f, 6f, 0.7f)

    /** Live play with every skater parked in a row along the near boards, out of the way. */
    private fun sim(away: GoalieSkill = standard, seed: Int = 1): HockeySim {
        val sim = HockeySim(Random(seed), standard, away)
        for ((i, s) in sim.skaters.withIndex()) {
            s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f
        }
        sim.startPlayForTest()
        return sim
    }

    private fun HockeySim.place(id: Int, x: Float, y: Float): Skater =
        skaters[id].also { it.x = x; it.y = y; it.vx = 0f; it.vy = 0f; it.facing = 0f }

    private fun HockeySim.loose(x: Float, y: Float, vx: Float, vy: Float) {
        puck.x = x; puck.y = y; puck.vx = vx; puck.vy = vy
    }

    private fun HockeySim.run(seconds: Float, each: (Int) -> Unit = {}): List<SimEvent> {
        val all = ArrayList<SimEvent>()
        repeat(Math.round(seconds / Tuning.DT)) {
            each(it)
            step()
            all += events
        }
        return all
    }

    /**
     * A HOME shot fired straight down the middle from [fromX] at [speed]: the puck as Actions leaves
     * it, without the aim and spread, so only the goalie decides the outcome.
     */
    private fun HockeySim.straightShot(fromX: Float, speed: Float) {
        loose(fromX, 0f, speed, 0f)
        puck.lastSide = Side.HOME
        puck.fromShot = true; puck.shotX = fromX; puck.shotY = 0f
    }

    private val away get() = Side.AWAY.index

    private val target = FloatArray(2)

    @Test fun goalie_stays_between_puck_and_net() {
        val sim = sim()
        sim.loose(15f, 8f, 0f, 0f)
        sim.run(2f)
        val g = sim.goalies[away]
        goalieTarget(26f, -1f, 15f, 8f, target)
        assertEquals(target[0], g.x, 0.05f)
        assertEquals(target[1], g.y, 0.05f)
    }

    @Test fun goalie_challenges_a_far_puck_at_the_top_of_the_crease() {
        goalieTarget(26f, -1f, 15f, 8f, target)
        val r = sqrt(11f * 11f + 8f * 8f)
        assertEquals(26f - 1.5f * 11f / r, target[0], 0.01f)
        assertEquals(1.5f * 8f / r, target[1], 0.01f)
    }

    @Test fun goalie_backs_in_as_the_puck_closes() {
        goalieTarget(26f, -1f, 24f, 0f, target)   // 2 m out: deep
        assertEquals(25.4f, target[0], 0.01f); assertEquals(0f, target[1], 0.01f)
        goalieTarget(26f, -1f, 21.5f, 0f, target) // 4.5 m: halfway between 3 and 6
        assertEquals(26f - 1.05f, target[0], 0.01f)
    }

    @Test fun goalie_angle_is_clamped_near_the_goal_line() {
        goalieTarget(26f, -1f, 25.5f, 8f, target)  // nearly along the goal line
        val a = atan2(target[1], 26f - target[0])
        assertEquals(Tuning.GOALIE_MAX_ANGLE, a, 0.01f)
    }

    @Test fun goalie_seals_the_near_post_with_the_puck_behind_the_net() {
        goalieTarget(26f, -1f, 28f, -3f, target)
        assertEquals(26f - 0.35f, target[0], 0.01f)
        assertEquals(-(Rink.GOAL_HALF_W - 0.25f), target[1], 0.01f)
    }

    @Test fun goalie_never_enters_the_net_as_the_puck_goes_round_behind_it() {
        val sim = sim()
        val g = sim.goalies[away]
        var ticks = 0
        sim.run(3f) { t ->
            if (t > 0) { assertTrue("tick $t goalie x ${g.x}", 26f - g.x >= 0.3f); ticks++ }
            val a = t * 0.05f
            sim.loose(26f + 2.5f * cos(a), 2.5f * sin(a) * 2f, 0f, 0f)
        }
        assertTrue(26f - g.x >= 0.3f)
        assertEquals(179, ticks)
    }

    @Test fun goalie_move_is_capped_at_his_speed_in_x_and_y() {
        val skill = GoalieSkill(0f, 6f, 0.7f)   // no reaction delay: he goes for the new spot at once
        val sim = sim(skill)
        val g = sim.goalies[away]
        assertEquals(26f - Tuning.GOALIE_DEPTH_OUT, g.x, 1e-4f); assertEquals(0f, g.y, 1e-4f)
        sim.loose(Rink.GOAL_X + 3f, -11f, 0f, 0f)   // the far corner, behind his goal line
        val x0 = g.x; val y0 = g.y
        sim.step()
        val moved = hypot(g.x - x0, g.y - y0)
        assertTrue("moved $moved", moved <= skill.speed * Tuning.DT + 1e-4f)
        assertTrue("moved $moved", moved >= skill.speed * Tuning.DT * 0.99f)
        assertTrue("dx ${g.x - x0} dy ${g.y - y0}", g.x > x0 && g.y < y0)   // toward the near post, in x and y
    }

    @Test fun tracking_draws_no_random_numbers() {
        var draws = 0
        val counting = object : Random() {
            val inner = Random(1)
            override fun nextBits(bitCount: Int): Int { draws++; return inner.nextBits(bitCount) }
        }
        val sim = HockeySim(counting, standard, standard)
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        val g = sim.goalies[away]
        val x0 = g.x; val y0 = g.y
        // The puck circles in the AWAY zone, well away from the goalie, for 2 s: he tracks it, never saves.
        repeat(Math.round(2f / Tuning.DT)) { t ->
            val a = t * 0.05f
            sim.loose(15f + 4f * cos(a), 4f * sin(a), 0f, 0f)
            draws = 0
            sim.step()
            assertEquals("tick $t", 0, draws)
        }
        assertTrue("he never moved", hypot(g.x - x0, g.y - y0) > 0.1f)
    }

    @Test fun perfect_goalie_stops_centre_shots() {
        var goals = 0; var saves = 0
        for (seed in 1..50) {
            val sim = sim(GoalieSkill(0.1f, 6f, 1f), seed)
            sim.straightShot(15f, Tuning.SHOT_SPEED_MIN + (seed % 9) * 2f)
            val events = sim.run(1f)
            goals += events.count { it is SimEvent.Goal }
            saves += events.count { it is SimEvent.Save }
        }
        // The brief asks for 0 goals, but its save formula caps p at 0.97, so even a perfect goalie
        // lets about 1 in 33 through (here 1 of 50). Allow 2 rather than pick seeds that happen to pass.
        assertTrue("goals $goals", goals <= 2)
        assertTrue("saves $saves", saves >= 45)
    }

    @Test fun hopeless_goalie_lets_most_in() {
        var goals = 0
        for (seed in 1..50) {
            val sim = sim(GoalieSkill(0.1f, 0f, 0f), seed)
            sim.straightShot(Rink.GOAL_X - 6f, Tuning.SHOT_SPEED_MAX)
            val events = sim.run(1f)
            goals += events.count { it is SimEvent.Goal }
        }
        assertTrue("goals $goals", goals >= 35)
    }

    @Test fun shot_outside_reach_scores() {
        val sim = sim(GoalieSkill(0f, 0f, 1f))
        sim.goalies[away].y = -1f
        sim.loose(20f, 0.9f, 30f, 0f)
        val events = sim.run(0.5f)
        assertTrue(events.hasGoal(Side.HOME))
        assertTrue(events.none { it is SimEvent.Save })
    }

    @Test fun wide_shot_is_not_a_goal() {
        val sim = sim()
        sim.loose(20f, 2f, 30f, 0f)
        var bounced = false
        val events = sim.run(1f) { if (sim.puck.vx < 0f) bounced = true }
        assertTrue(events.none { it is SimEvent.Goal })
        assertTrue(bounced)
        assertTrue(events.contains(SimEvent.Boards))
    }

    /** Skills that always take one outcome of a save that rolls one. */
    private val catcher = GoalieSkill(0f, 6f, 1f, catch = 1f, kick = 0f)
    private val kicker = GoalieSkill(0f, 6f, 1f, catch = 0f, kick = 1f)
    private val spiller = GoalieSkill(0f, 6f, 1f, catch = 0f, kick = 0f)

    @Test fun slow_save_is_caught_and_whistled() {
        val sim = sim(spiller)   // never catches by the roll: a slow puck is caught without one
        sim.loose(20f, 0f, 10f, 0f)
        val g = sim.goalies[away]
        val events = sim.run(1f)
        assertTrue(events.contains(SimEvent.Save(Side.AWAY)))
        assertTrue(events.contains(SimEvent.Cover))
        assertTrue(events.contains(SimEvent.Whistle(Side.AWAY)))
        assertTrue(events.indexOf(SimEvent.Cover) < events.indexOf(SimEvent.Whistle(Side.AWAY)))
        assertTrue(g.holding)
        assertEquals(Phase.WHISTLE, sim.phase)
        assertEquals(g.x, sim.puck.x, 1e-4f); assertEquals(g.y, sim.puck.y, 1e-4f)
    }

    @Test fun slow_save_rolls_no_outcome() {
        // The tick of a slow save draws only the save roll itself.
        var draws = 0
        val counting = object : Random() {
            val inner = Random(1)
            override fun nextBits(bitCount: Int): Int { draws++; return inner.nextBits(bitCount) }
        }
        val sim = HockeySim(counting, standard, spiller)
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = -11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        sim.loose(20f, 0f, 10f, 0f)
        repeat(60) {
            draws = 0
            sim.step()
            if (sim.events.contains(SimEvent.Save(Side.AWAY))) {
                assertEquals(1, draws)
                return
            }
        }
        throw AssertionError("no save")
    }

    @Test fun caught_save_whistles_and_faces_off_in_that_zone() {
        for (y in floatArrayOf(0.3f, -0.3f)) {
            val sim = sim(catcher)
            val events = sim.hardShotSaved(y)
            val clock = sim.clockS
            val g = sim.goalies[away]
            assertTrue("events $events", events.contains(SimEvent.Cover))
            assertTrue("events $events", events.contains(SimEvent.Whistle(Side.AWAY)))
            assertTrue(events.none { it is SimEvent.Pass })
            assertEquals(Phase.WHISTLE, sim.phase)
            assertFalse(sim.phase.afterGoal)
            repeat(Math.round(Tuning.WHISTLE_TIME / Tuning.DT) - 1) {
                sim.step()
                assertEquals(Phase.WHISTLE, sim.phase)
                assertTrue(g.holding)
                assertEquals(g.x, sim.puck.x, 1e-4f); assertEquals(g.y, sim.puck.y, 1e-4f)
            }
            assertEquals("clock frozen", clock, sim.clockS, 0f)
            sim.step()
            assertEquals(Phase.FACEOFF, sim.phase)
            assertFalse(g.holding)
            // AWAY defends +x, so the dot is in the +x zone, on the side of the catch.
            assertEquals(Rink.FACEOFF_X, sim.puck.x, 1e-4f)
            assertEquals(if (y >= 0f) Rink.FACEOFF_Y else -Rink.FACEOFF_Y, sim.puck.y, 1e-4f)
        }
    }

    @Test fun home_goalie_catch_faces_off_in_the_home_zone() {
        val sim = HockeySim(Random(1), catcher, standard)
        for ((i, s) in sim.skaters.withIndex()) { s.x = -10f + 2f * i; s.y = 11f; s.vx = 0f; s.vy = 0f }
        sim.startPlayForTest()
        sim.loose(-20f, -0.3f, -36f, 0f)
        sim.puck.lastSide = Side.AWAY
        sim.puck.fromShot = true; sim.puck.shotX = -20f; sim.puck.shotY = -0.3f
        val events = sim.run(0.5f)
        assertTrue("events $events", events.contains(SimEvent.Whistle(Side.HOME)))
        sim.run(Tuning.WHISTLE_TIME)
        assertEquals(Phase.FACEOFF, sim.phase)
        assertEquals(-Rink.FACEOFF_X, sim.puck.x, 1e-4f)
        assertEquals(-Rink.FACEOFF_Y, sim.puck.y, 1e-4f)
    }

    @Test fun puck_from_behind_cannot_score() {
        val sim = sim()
        sim.loose(29f, 0f, -30f, 0f)
        val events = sim.run(1f)
        assertTrue(events.none { it is SimEvent.Goal })
        assertTrue(events.none { it is SimEvent.Save })
    }

    @Test fun loose_rebound_still_happens() {
        // A fast puck the goalie neither catches nor kicks comes back out at 30% speed.
        var rebounds = 0
        for (seed in 1..10) {
            val sim = sim(spiller, seed)
            sim.loose(20f, 0f, 36f, 0f)
            val events = sim.run(Tuning.DT * 12)
            if (events.contains(SimEvent.Save(Side.AWAY))) {
                rebounds++
                assertFalse(sim.goalies[away].holding)
                assertEquals(Phase.PLAY, sim.phase)
                assertTrue(events.none { it is SimEvent.Pass || it is SimEvent.Whistle })
                assertTrue("vx ${sim.puck.vx}", sim.puck.vx < 0f && abs(sim.puck.vx) < 36f * 0.31f)
            }
        }
        assertTrue(rebounds >= 8)
    }

    /**
     * AWAY skaters 5..8 each with a HOME skater on his lane from the AWAY goalie, HOME 4 far off in
     * the other zone, and AWAY 9 at ([x9], [y9]).
     */
    private fun HockeySim.markAllBut9(x9: Float, y9: Float): Skater {
        val spots = listOf(10f to 5f, 10f to -5f, 15f to -8f, 5f to 0f)
        for ((k, p) in spots.withIndex()) {
            place(5 + k, p.first, p.second)
            place(k, p.first - 1f, p.second)
        }
        place(4, -20f, 10f)
        return place(9, x9, y9)
    }

    /** Fires a 36 m/s HOME shot at the AWAY goalie, [y] off centre, and steps until he saves it; the events of that tick. */
    private fun HockeySim.hardShotSaved(y: Float = 0f): List<SimEvent> {
        straightShot(20f, 36f)
        puck.y = y; puck.shotY = y
        repeat(30) {
            step()
            if (events.contains(SimEvent.Save(Side.AWAY))) return events.toList()
        }
        throw AssertionError("no save")
    }

    @Test fun kick_goes_to_open_teammate() {
        val sim = sim(kicker)
        val t = sim.markAllBut9(15f, 6f)
        t.vx = 0f; t.vy = 6f   // across the lane, so the lead shows in the angle
        val events = sim.hardShotSaved()
        val p = sim.puck
        assertFalse(sim.goalies[away].holding)
        assertEquals(Phase.PLAY, sim.phase)
        assertTrue("passTarget ${p.passTarget?.id}", p.passTarget === t)
        assertEquals(Side.AWAY, p.lastSide)
        val save = events.indexOf(SimEvent.Save(Side.AWAY))
        val pass = events.indexOf(SimEvent.Pass(Side.AWAY, -1, t.id))
        assertTrue("events $events", pass > save)
        // The puck left the contact point this tick; aim from there at the teammate led by his velocity
        // for as long as the rebound, at 30% of the shot's speed, takes to reach him.
        val ox = p.x - p.vx * Tuning.DT; val oy = p.y - p.vy * Tuning.DT
        var dx = t.x - ox; var dy = t.y - oy
        val lead = kotlin.math.sqrt(dx * dx + dy * dy) / minOf(36f * Tuning.REBOUND_KEEP, Tuning.PASS_SPEED)
        dx += t.vx * lead; dy += t.vy * lead
        val want = kotlin.math.atan2(dy, dx)
        val got = kotlin.math.atan2(p.vy, p.vx)
        assertEquals(want, got, 0.05f)
        val speed = kotlin.math.hypot(p.vx, p.vy)
        assertTrue("speed $speed", speed <= 36f * Tuning.REBOUND_KEEP + 1e-3f && speed > 9f)
    }

    @Test fun kick_with_nobody_open_is_caught() {
        val sim = sim(kicker)
        sim.markAllBut9(15f, 6f)
        sim.place(4, 17f, 4.5f)   // on AWAY 9's lane too
        val events = sim.hardShotSaved()
        assertNull(sim.puck.passTarget)
        assertTrue("events $events", events.none { it is SimEvent.Pass })
        assertTrue("events $events", events.contains(SimEvent.Whistle(Side.AWAY)))
        assertEquals(Phase.WHISTLE, sim.phase)
        assertTrue(sim.goalies[away].holding)
    }

    @Test fun loose_rebound_ignores_an_open_teammate() {
        val sim = sim(spiller)
        sim.markAllBut9(15f, 6f)   // open
        val events = sim.hardShotSaved()
        val p = sim.puck
        assertNull(p.passTarget)
        assertTrue("events $events", events.none { it is SimEvent.Pass || it is SimEvent.Whistle })
        // AWAY defends +x, so straight out is pi.
        val off = kotlin.math.PI.toFloat() - abs(kotlin.math.atan2(p.vy, p.vx))
        assertTrue("off $off", off <= Tuning.REBOUND_ANGLE + 1e-4f)
    }

    @Test fun kick_never_goes_toward_own_net() {
        val sim = sim(kicker)
        sim.markAllBut9(28f, 3f)   // open, but behind the goal line: nobody to kick it to
        val events = sim.hardShotSaved()
        assertNull(sim.puck.passTarget)
        assertTrue("events $events", events.none { it is SimEvent.Pass })
        assertEquals(Phase.WHISTLE, sim.phase)
    }

    @Test fun centred_shot_aims_away_from_the_goalie() {
        for (goalieY in floatArrayOf(-0.5f, 0.5f)) {
            val sim = sim(GoalieSkill(0f, 0f, 0.7f))
            sim.goalies[away].y = goalieY
            val s = sim.place(0, 15f, 0f)
            sim.giveTo(s)
            sim.run(3 * Tuning.DT) { t -> s.input.shoot = t < 2 }
            val p = sim.puck
            assertTrue(p.fromShot)
            assertEquals(15f + Tuning.CARRY_OFFSET, p.shotX, 0.3f)
            val yAtLine = p.y + p.vy * (Rink.GOAL_X - p.x) / p.vx
            assertTrue("goalie $goalieY shot to $yAtLine", yAtLine * goalieY < 0f)
        }
    }

    @Test fun held_puck_cannot_be_picked_up_or_poked() {
        val sim = sim(GoalieSkill(0.1f, 6f, 1f))
        sim.loose(20f, 0f, 10f, 0f)
        sim.run(0.6f)
        val g = sim.goalies[away]
        assertTrue(g.holding)
        sim.startPlayForTest()   // live play around the held puck, which the whistle otherwise stops
        val poker = sim.place(1, g.x - 1f, g.y)
        val events = sim.run(0.2f) { t -> poker.input.poke = t == 0 }
        assertTrue(events.contains(SimEvent.Poke(false)))
        assertTrue(g.holding)
        assertNull(sim.puck.carrier)
    }

    @Test fun skaters_are_kept_off_the_goalie() {
        val sim = sim()
        val s = sim.place(0, 20f, 0f)
        sim.run(2f) {
            s.input.moveX = 1f
            val g = sim.goalies[away]
            assertTrue("skater ${s.x},${s.y}", kotlin.math.hypot(s.x - g.x, s.y - g.y) >= Tuning.GOALIE_BODY_R - 1e-3f)
        }
    }

    /** Steers [s] toward (tx, ty) at full stick every tick. */
    private fun steer(s: Skater, tx: Float, ty: Float) {
        val dx = tx - s.x; val dy = ty - s.y
        val d = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        s.input.moveX = dx / d; s.input.moveY = dy / d
    }

    @Test fun perfect_goalie_strips_a_carrier_who_skates_straight_at_him() {
        var strips = 0; var goals = 0
        for (seed in 1..20) {
            val sim = sim(GoalieSkill(0.1f, 6f, 1f, catch = 1f), seed)
            val s = sim.place(0, 18f, 0f)
            sim.giveTo(s)
            val events = sim.run(2f) { steer(s, Rink.GOAL_X + 1f, 0f) }
            goals += events.count { it is SimEvent.Goal }
            if (events.contains(SimEvent.Save(Side.AWAY)) && events.contains(SimEvent.Cover)) strips++
        }
        assertTrue("strips $strips of 20", strips >= 18)
        assertTrue("goals $goals", goals <= 2)
    }

    @Test fun a_stripped_carrier_loses_the_puck_to_the_covering_goalie() {
        val sim = sim(GoalieSkill(0.1f, 6f, 1f, catch = 1f), 2)
        val s = sim.place(0, 18f, 0f)
        sim.giveTo(s)
        var guard = 0
        while (!sim.events.contains(SimEvent.Cover) && guard++ < 120) { steer(s, Rink.GOAL_X + 1f, 0f); sim.step() }
        assertTrue("never stripped", sim.events.contains(SimEvent.Save(Side.AWAY)))
        assertNull(sim.puck.carrier)
        assertTrue(sim.goalies[away].holding)
        assertTrue("carrier lock ${s.pickupLock}", s.pickupLock > 0f)
        val g = sim.goalies[away]
        assertEquals(g.x, sim.puck.x, 1e-4f); assertEquals(g.y, sim.puck.y, 1e-4f)
    }

    /** HOME 0 skates straight at the AWAY goalie with the puck until he strips it; the events of that tick. */
    private fun HockeySim.stripped(): List<SimEvent> {
        val s = place(0, 18f, 0f)
        giveTo(s)
        repeat(120) {
            steer(s, Rink.GOAL_X + 1f, 0f)
            step()
            if (events.contains(SimEvent.Save(Side.AWAY))) return events.toList()
        }
        throw AssertionError("never stripped")
    }

    @Test fun a_strip_kick_is_caught_with_the_carrier_in_every_lane() {
        // The stripped carrier is within reach of the goalie, so on every lane out of the net.
        val sim = sim(GoalieSkill(0.1f, 6f, 1f, catch = 0f, kick = 1f), 2)
        sim.place(9, 15f, -6f)
        val events = sim.stripped()
        assertTrue("events $events", events.none { it is SimEvent.Pass })
        assertTrue("events $events", events.contains(SimEvent.Whistle(Side.AWAY)))
        assertNull(sim.puck.carrier)
        assertTrue(sim.goalies[away].holding)
        assertEquals(Phase.WHISTLE, sim.phase)
    }

    @Test fun a_strip_can_squirt_loose() {
        val sim = sim(GoalieSkill(0.1f, 6f, 1f, catch = 0f, kick = 0f), 2)
        val events = sim.stripped()
        assertTrue("events $events", events.none { it is SimEvent.Pass || it is SimEvent.Whistle || it == SimEvent.Cover })
        assertNull(sim.puck.carrier)
        assertNull(sim.puck.passTarget)
        assertFalse(sim.goalies[away].holding)
        assertEquals(Phase.PLAY, sim.phase)
        val off = kotlin.math.PI.toFloat() - abs(kotlin.math.atan2(sim.puck.vy, sim.puck.vx))
        assertTrue("off $off", off <= Tuning.REBOUND_ANGLE + 1e-4f)
    }

    @Test fun a_carrier_going_wide_around_a_slow_goalie_can_score() {
        // The goalie is caught on the far post and slow to come across; even one who saves everything
        // he reaches is beaten by a carrier who stays out of his reach on the open side.
        val sim = sim(GoalieSkill(0.5f, 0.5f, 1f))
        sim.goalies[away].y = -1f
        val s = sim.place(0, 18f, 0.6f)
        sim.giveTo(s)
        val events = sim.run(2f) { steer(s, Rink.GOAL_X + 1f, 0.6f) }
        assertTrue("no save expected: $events", events.none { it is SimEvent.Save })
        assertTrue("no goal: $events", events.hasGoal(Side.HOME))
    }

    @Test fun a_carrier_in_reach_gets_one_save_roll_per_approach() {
        // p = 0.25 + 0.25 (standing still) = 0.5. Rolling every tick would save nearly every time.
        var saves = 0
        for (seed in 1..100) {
            val sim = sim(GoalieSkill(0f, 0f, 0.25f), seed)
            val s = sim.place(0, 23.9f, 0.5f)
            s.facing = kotlin.math.atan2(-0.5f, 1.1f)
            sim.giveTo(s)
            val events = sim.run(1f)
            if (events.any { it is SimEvent.Save }) saves++ else assertTrue("seed $seed lost the puck", sim.puck.carrier === s)
        }
        assertTrue("saves $saves of 100", saves in 35..65)
    }
}
