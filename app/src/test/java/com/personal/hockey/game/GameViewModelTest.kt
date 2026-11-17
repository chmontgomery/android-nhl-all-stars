package com.personal.hockey.game

import com.personal.hockey.audio.Buzz
import com.personal.hockey.audio.FeedbackDirector
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.audio.Sfx
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Robolectric: the renderer builds android.graphics.Path objects. */
@RunWith(AndroidJUnit4::class)
class GameViewModelTest {
    private class Recorder : GameFeedback {
        val sounds = mutableListOf<Sfx>()
        override fun play(sfx: Sfx) { sounds += sfx }
        override fun vibrate(buzz: Buzz) {}
    }

    private val feedback = Recorder()
    private val setup = MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY)
    private val vm = GameViewModel(setup, FeedbackDirector(feedback), seed = 1L)
    private val sim get() = vm.match.sim
    private val frameNanos = 16_666_667L

    /** Elapsed sim ticks: faceoff countdown, then the period clock. */
    private fun ticks(): Int {
        val elapsed = (Tuning.FACEOFF_TIME - sim.phaseT) + (Tuning.PERIOD_SECONDS - sim.clockS)
        return Math.round(elapsed / Tuning.DT)
    }

    private fun runFrames(from: Long, count: Int): Long {
        var t = from
        repeat(count) { vm.onFrame(t); t += frameNanos }
        return t
    }

    @Test fun frames_advance_the_sim_at_sixty_per_second() {
        runFrames(0, 61)   // 60 intervals = 1 s
        assertTrue("ticks ${ticks()}", ticks() in 59..61)
    }

    /** Ticks run by each of [count] frames at [periodNanos] with deterministic ±5 µs jitter. */
    private fun ticksPerFrame(periodNanos: Long, count: Int): IntArray {
        val rng = kotlin.random.Random(7)
        val out = IntArray(count)
        val base = 1_000_000_000L
        for (k in 0 until count) {
            val jitter = rng.nextLong(-5_000L, 5_001L)
            vm.onFrame(base + k * periodNanos + jitter)
            out[k] = vm.lastFrameTicks
        }
        return out
    }

    @Test fun at_60_hz_every_frame_runs_exactly_one_tick_despite_jitter() {
        val t = ticksPerFrame(16_666_667L, 3600)
        for (k in 1 until t.size) assertEquals("frame $k ran ${t[k]} ticks", 1, t[k])
    }

    @Test fun at_120_hz_frames_alternate_zero_and_one_tick_despite_jitter() {
        val t = ticksPerFrame(8_333_333L, 3600)
        for (k in 1 until t.size) {
            assertTrue("frame $k ran ${t[k]} ticks", t[k] <= 1)
            if (k > 1) assertFalse("frames ${k - 1} and $k both ran 0 ticks", t[k] == 0 && t[k - 1] == 0)
        }
        assertTrue("total ${t.sum()}", t.sum() in 1798..1800)
    }

    @Test fun after_resume_60_hz_frames_still_run_one_tick_each() {
        ticksPerFrame(16_666_667L, 10)
        vm.pause(); vm.resume()
        val rng = kotlin.random.Random(3)
        val base = 50_000_000_000L
        for (k in 0 until 600) {
            vm.onFrame(base + k * 16_666_667L + rng.nextLong(-5_000L, 5_001L))
            if (k > 0) assertEquals("frame $k after resume", 1, vm.lastFrameTicks)
        }
    }

    @Test fun the_camera_moves_with_sim_ticks_not_drawn_frames() {
        // Same sim time at 60 Hz and 120 Hz gives the same camera position.
        val other = GameViewModel(setup, FeedbackDirector(feedback), seed = 1L)
        var ticks60 = 0; var ticks120 = 0
        for (k in 0 until 600) { vm.onFrame(k * 16_666_667L); ticks60 += vm.lastFrameTicks }
        for (k in 0 until 1199) { other.onFrame(k * 8_333_333L); ticks120 += other.lastFrameTicks }
        assertEquals(ticks60, ticks120)
        assertTrue("camera never moved", vm.renderer.camera.focusX != 0f)
        assertEquals(vm.renderer.camera.focusX, other.renderer.camera.focusX, 1e-4f)
    }

    @Test fun the_renderer_hears_every_sim_tick() {
        var ticks = 0
        for (k in 0 until 300) { vm.onFrame(k * 8_333_333L + (k % 7) * 3_000_000L); ticks += vm.lastFrameTicks }
        vm.onFrame(300 * 8_333_333L + 90_000_000L)   // a stall: several ticks in one frame
        ticks += vm.lastFrameTicks
        assertTrue("ticks $ticks", ticks > 100)
        assertEquals(ticks, vm.renderer.anim.ticks)
    }

    @Test fun a_long_stall_is_clamped() {
        vm.onFrame(0)
        vm.onFrame(5_000_000_000L)
        assertTrue("ticks ${ticks()}", ticks() in 1..6)
    }

    @Test fun paused_game_does_not_advance() {
        runFrames(0, 5)
        vm.pause()
        val clock = sim.clockS; val phaseT = sim.phaseT
        runFrames(1_000_000_000L, 120)
        assertEquals(clock, sim.clockS, 0f)
        assertEquals(phaseT, sim.phaseT, 0f)
        assertTrue(vm.hud.value.paused)
    }

    @Test fun a_paused_game_does_not_ask_for_redraws() {
        runFrames(0, 5)
        vm.pause()
        val frame = vm.frame.value
        runFrames(1_000_000_000L, 60)
        assertEquals(frame, vm.frame.value)
        vm.resume()
        vm.onFrame(3_000_000_000L)
        assertTrue(vm.frame.value > frame)
    }

    @Test fun resume_does_not_jump_time() {
        vm.onFrame(0)
        vm.onFrame(1_000_000_000L)
        vm.pause()
        vm.resume()
        assertFalse(vm.hud.value.paused)
        val before = ticks()
        vm.onFrame(60_000_000_000L)
        vm.onFrame(60_016_000_000L)
        assertTrue("ticks ${ticks() - before}", ticks() - before <= 2)
    }

    @Test fun hud_shows_codes_score_period_and_clock() {
        val h = vm.hud.value
        assertEquals("MIN", h.homeCode); assertEquals("COL", h.awayCode)
        assertEquals(0, h.homeScore); assertEquals(0, h.awayScore)
        assertEquals("1st", h.periodLabel)
        assertEquals("20:00", h.clock)
        assertNull(h.banner)
    }

    @Test fun clock_text_follows_the_sim() {
        runFrames(0, 200)   // past the faceoff, into play
        assertEquals(sim.clockText(), vm.hud.value.clock)
        assertTrue(vm.hud.value.clock != "20:00")
    }

    @Test fun hud_has_puck_follows_possession() {
        val me = sim.skaters[sim.controlledId]
        sim.puck.carrier = me
        vm.onFrame(0)
        vm.onFrame(frameNanos)
        assertTrue(vm.hud.value.hasPuck)
        assertTrue(vm.controls.hasPuck)
        sim.puck.carrier = null
        vm.onFrame(2 * frameNanos)
        assertFalse(vm.hud.value.hasPuck)
        assertFalse(vm.controls.hasPuck)
    }

    @Test fun goal_banner_names_the_scorer_then_clears() {
        sim.phase = Phase.GOAL; sim.phaseT = Tuning.GOAL_TIME; sim.lastGoalSide = com.personal.hockey.sim.Side.AWAY
        vm.onFrame(0)
        vm.onFrame(frameNanos)
        assertEquals("Goal for the Avalanche!", vm.hud.value.banner)
        sim.phase = Phase.FACEOFF
        vm.onFrame(2 * frameNanos)
        assertNull(vm.hud.value.banner)
        sim.phase = Phase.GOAL; sim.lastGoalSide = com.personal.hockey.sim.Side.HOME
        vm.onFrame(3 * frameNanos)
        assertEquals("Goal for the Wild!", vm.hud.value.banner)
    }

    private fun skater(side: com.personal.hockey.sim.Side, pos: com.personal.hockey.sim.Position) =
        sim.skaters.first { it.side == side && it.pos == pos }

    @Test fun banner_names_the_scorer() {
        val boldy = skater(com.personal.hockey.sim.Side.HOME, com.personal.hockey.sim.Position.RW)
        sim.phase = Phase.GOAL; sim.phaseT = Tuning.GOAL_TIME
        sim.lastGoalSide = com.personal.hockey.sim.Side.HOME; sim.lastScorerId = boldy.id
        vm.onFrame(0)
        vm.onFrame(frameNanos)
        assertEquals("Boldy scores for the Wild!", vm.hud.value.banner)
    }

    @Test fun own_goal_banner_names_the_player() {
        val faber = skater(com.personal.hockey.sim.Side.HOME, com.personal.hockey.sim.Position.RD)
        sim.phase = Phase.GOAL; sim.phaseT = Tuning.GOAL_TIME
        sim.lastGoalSide = com.personal.hockey.sim.Side.AWAY; sim.lastScorerId = faber.id   // COL credited
        vm.onFrame(0)
        vm.onFrame(frameNanos)
        assertEquals("Faber scores for the Wild!", vm.hud.value.banner)
    }

    @Test fun banner_without_a_side_is_plain_goal() {
        sim.phase = Phase.GOAL; sim.phaseT = Tuning.GOAL_TIME; sim.lastGoalSide = null; sim.lastScorerId = -1
        vm.onFrame(0)
        vm.onFrame(frameNanos)
        assertEquals("GOAL!", vm.hud.value.banner)
    }

    @Test fun break_banner_names_the_period() {
        sim.phase = Phase.BREAK; sim.phaseT = Tuning.BREAK_TIME
        vm.onFrame(0)
        assertEquals("End of 1st", vm.hud.value.banner)
    }

    @Test fun finished_is_set_at_final() {
        var guard = 0
        while (sim.phase != Phase.FINAL && guard++ < 100_000) vm.match.tick(null)
        assertEquals(Phase.FINAL, sim.phase)
        assertFalse(vm.hud.value.finished)
        vm.onFrame(0)
        assertTrue(vm.hud.value.finished)
        assertEquals("Final", vm.hud.value.banner)
    }

    @Test fun events_from_every_tick_reach_the_director() {
        // The first pickup after the drop happens on one tick of a multi-tick frame. (The drop
        // itself is silent; the whistle is for stoppages.)
        var t = 0L
        repeat(120) { vm.onFrame(t); t += 100_000_000L / 3 }   // 33 ms frames: 2 ticks each
        assertTrue("${feedback.sounds}", Sfx.STICK in feedback.sounds)
    }

    @Test fun stick_input_moves_the_controlled_skater() {
        runFrames(0, 100)   // through the faceoff
        vm.setLayout(com.personal.hockey.input.ControlsLayout(2400f, 1080f, 2.625f))
        vm.controls.down(1, 300f, 700f)
        vm.controls.move(1, 300f + 147f, 700f)
        runFrames(2_000_000_000L, 61)
        assertEquals(Phase.PLAY, sim.phase)
        assertTrue("vx ${sim.skaters[sim.controlledId].vx}", sim.skaters[sim.controlledId].vx > 2f)
    }

    @Test fun the_layout_reaches_the_renderer_so_the_pass_arrow_clears_the_buttons() {
        val layout = com.personal.hockey.input.ControlsLayout(2424f, 1080f, 420f / 160f)
        vm.setLayout(layout)
        sim.startPlayForTest()
        val me = sim.skaters[sim.controlledId.coerceAtLeast(0)]
        sim.controlledId = me.id
        sim.giveTo(me)
        me.x = 0f; me.y = 0f; me.input.moveX = 1f; me.input.moveY = 0f
        for (m in sim.skaters) if (m.side == me.side && m !== me) { m.x = 25f; m.y = 0f }   // ahead, off screen
        vm.renderer.camera.setViewport(layout.width, layout.height); vm.renderer.camera.snapTo(0f)
        val pts = FloatArray(6)
        assertTrue(vm.renderer.edgeArrowCorners(sim, pts))
        val shoot = layout.buttons(hasPuck = true).first { it.btn == com.personal.hockey.input.Btn.SHOOT }
        // Points along each side of the arrow, none inside the SHOOT button.
        for (i in 0 until 3) for (k in 0..20) {
            val t = k / 20f
            val x = pts[2 * i] + (pts[(2 * i + 2) % 6] - pts[2 * i]) * t
            val y = pts[2 * i + 1] + (pts[(2 * i + 3) % 6] - pts[2 * i + 1]) * t
            assertTrue("arrow point $x, $y over SHOOT", kotlin.math.hypot(x - shoot.cx, y - shoot.cy) > shoot.radius)
        }
    }
}
