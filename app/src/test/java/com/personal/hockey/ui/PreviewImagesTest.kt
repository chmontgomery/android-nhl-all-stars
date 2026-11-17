package com.personal.hockey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.audio.Buzz
import com.personal.hockey.audio.FeedbackDirector
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.audio.Sfx
import com.personal.hockey.game.GameScreen
import com.personal.hockey.game.GameViewModel
import com.personal.hockey.render.anim.Clip
import com.personal.hockey.settings.Level
import com.personal.hockey.settings.Settings
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.SkaterInput
import com.personal.hockey.sim.Tuning
import com.personal.hockey.sim.startFaceoff
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import com.personal.hockey.ui.result.ResultScreen
import com.personal.hockey.ui.select.TeamSelectScreen
import com.personal.hockey.ui.theme.HockeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each screen at 800 × 360 dp into app/build/preview/ for a person to look at. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xhdpi")
class PreviewImagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private object Silent : GameFeedback {
        override fun play(sfx: Sfx) {}
        override fun vibrate(buzz: Buzz) {}
    }

    private fun save(name: String) {
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // captureToImage() waits for a frame commit that Robolectric never signals; draw the window instead.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        assertEquals(1600, bmp.width)
        assertEquals(720, bmp.height)
        val file = File("build/preview/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("${file.absolutePath} ${file.length()} bytes", file.length() > 5_000)
    }

    @Test fun team_select() {
        compose.mainClock.autoAdvance = false   // see PauseOverlayTest
        compose.setContent { HockeyTheme { TeamSelectScreen(Teams.all, Settings()) { _, _, _ -> } } }
        save("team-select.png")
    }

    @Test fun game() {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        // 20 s into live play, then on until the human's skater has the puck, so the offence buttons show.
        var ticks = 0
        while ((ticks < 1200 || vm.match.sim.phase != Phase.PLAY || vm.match.sim.puck.carrier?.id != vm.match.sim.controlledId) && ticks < 20_000) {
            vm.match.tick(null); ticks++
            vm.renderer.onTick(vm.match.sim)   // as GameViewModel.onFrame does
            vm.renderer.advance(vm.match.sim, com.personal.hockey.sim.Tuning.DT)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HockeyTheme {
                GameScreen(vm, soundOn = true, vibrationOn = true, onSoundChange = {}, onVibrationChange = {},
                    onFinished = { _, _ -> }, onQuit = {})
            }
        }
        save("game.png")
    }

    private fun showGame(vm: GameViewModel, name: String) {
        compose(vm)
        save(name)
    }

    private fun compose(vm: GameViewModel) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HockeyTheme {
                GameScreen(vm, soundOn = true, vibrationOn = true, onSoundChange = {}, onVibrationChange = {},
                    onFinished = { _, _ -> }, onQuit = {})
            }
        }
    }

    /** Runs [vm] to live play [seconds] in, moving the camera as the frame loop does. */
    private fun playTo(vm: GameViewModel, seconds: Float) {
        var ticks = 0
        while ((ticks * Tuning.DT < seconds || vm.match.sim.phase != Phase.PLAY) && ticks < 20_000) {
            vm.match.tick(null); ticks++
            vm.renderer.onTick(vm.match.sim)
            vm.renderer.advance(vm.match.sim, Tuning.DT)
        }
    }

    @Test fun goal_just_scored() {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        playTo(vm, 8f)
        val sim = vm.match.sim
        // The HOME centre shoots from the slot past the goalie, who is caught on the near post.
        val shooter = sim.skaters[0]
        shooter.x = 21.5f; shooter.y = 1.8f; shooter.vx = 3f; shooter.vy = 0f; shooter.facing = 0f
        sim.giveTo(shooter)   // credits the banner to him
        sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
        for (g in sim.goalies) { g.holding = false }
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = 22.5f; sim.puck.y = 1.2f; sim.puck.vx = 34f; sim.puck.vy = -1.6f
        var after = 0
        while (after < 40 && sim.phase != Phase.FINAL) {
            sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
            tickWith(vm, null)
            if (sim.phase == Phase.GOAL) after++
        }
        assertEquals(Phase.GOAL, sim.phase)
        assertEquals(Side.HOME, sim.lastGoalSide)
        showGame(vm, "game-goal.png")
    }

    /**
     * [seconds] into the goal pause after HOME's goal number [goalsBefore] + 1 (which picks the
     * scorer's [clip]): the scorer celebrating in the slot, his teammates skating in to pile round him,
     * the back-checkers dejected, the goalie slumped, and the camera pushed in.
     */
    private fun celebration(goalsBefore: Int, name: String, clip: Clip, seconds: Float) {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        playTo(vm, 8f)
        val sim = vm.match.sim
        sim.score[Side.HOME.index] = goalsBefore
        val shooter = sim.skaters[0]
        shooter.x = 21.5f; shooter.y = 1.8f; shooter.vx = 3f; shooter.vy = 0f; shooter.facing = 0f
        // HOME teammates trailing the play; AWAY skaters back-checking round the slot.
        val spots = arrayOf(15f to -4f, 14f to 5f, 11f to 0f, 12f to -7f, 19f to -3f, 17f to 4f, 14f to -1.5f, 10f to 6f, 23f to -5f)
        for ((k, p) in spots.withIndex()) sim.skaters[k + 1].also { it.x = p.first; it.y = p.second; it.vx = 0f; it.vy = 0f; it.act = Act.NONE }
        sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
        for (g in sim.goalies) { g.holding = false }
        sim.giveTo(shooter)   // credits the shooter
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = 22.5f; sim.puck.y = 1.2f; sim.puck.vx = 34f; sim.puck.vy = -1.6f
        var ticks = 0
        while (sim.phase != Phase.GOAL && ticks < 60) {
            sim.goalies[Side.AWAY.index].y = -Rink.GOAL_HALF_W
            tickWith(vm, null); ticks++
        }
        assertEquals(Phase.GOAL, sim.phase)
        assertEquals(shooter.id, sim.lastScorerId)
        repeat(Math.round(seconds / Tuning.DT)) { tickWith(vm, null) }
        assertTrue("${sim.phase}", sim.phase.afterGoal)
        assertEquals(clip, vm.renderer.anim.skaters[shooter.id].clip)
        showGame(vm, name)
    }

    /** The pile, 2.5 s in. */
    @Test fun celebration() = celebration(0, "celebration.png", Clip.CELEBRATE_FIST, 2.5f)

    /** The other two scorer's clips, before the pile closes round him. */
    @Test fun celebration_knee_slide() = celebration(1, "celebration-slide.png", Clip.CELEBRATE_SLIDE, 1.2f)

    @Test fun celebration_stick_raised() = celebration(2, "celebration-stick.png", Clip.CELEBRATE_STICK, 1.2f)

    @Test fun skater_in_the_near_left_corner() {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("EDM")!!, Teams.byId("TOR")!!, Level.HARD), FeedbackDirector(Silent), seed = 5L)
        playTo(vm, 6f)
        val sim = vm.match.sim
        // The human's skater has the puck deep in his own near corner, a checker closing in.
        val me = sim.skaters[sim.controlledId.coerceAtLeast(0)]
        me.x = -27f; me.y = -9.2f; me.vx = 0f; me.vy = 0f; me.facing = (Math.PI / 2).toFloat()
        for (g in sim.goalies) { g.holding = false }
        sim.giveTo(me)
        val opp = sim.skaters[5]
        opp.x = -24.5f; opp.y = -7.5f; opp.vx = -2f; opp.vy = -1f; opp.facing = Math.PI.toFloat()
        repeat(240) { vm.renderer.advance(sim, Tuning.DT) }   // the camera settles on the corner
        showGame(vm, "game-corner.png")
        assertEquals(me, sim.puck.carrier)
        assertTrue(!sim.puckHeld())
    }

    /**
     * The human's skater carrying the puck up ice toward the far net, nobody near, the camera close in
     * on him after 2 s of carrying.
     */
    private fun carryUpIce(): Pair<GameViewModel, Skater> {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("EDM")!!, Teams.byId("TOR")!!, Level.HARD), FeedbackDirector(Silent), seed = 5L)
        playTo(vm, 6f)
        val sim = vm.match.sim
        for (s in sim.skaters) { s.x = 30f + s.id; s.y = 8f; s.vx = 0f; s.vy = 0f }
        val me = sim.skaters[sim.controlledId.coerceAtLeast(0)]
        me.x = -4f; me.y = 0f; me.vx = 4f; me.vy = 0f; me.facing = 0f
        for (g in sim.goalies) { g.holding = false }
        sim.giveTo(me)
        vm.renderer.camera.zoomGoal = 1.4f
        repeat(120) { tickWith(vm, null) }
        return vm to me
    }

    /** One sim tick with [input] for the human, heard by the renderer, as GameViewModel.onFrame does. */
    private fun tickWith(vm: GameViewModel, input: SkaterInput?) {
        vm.match.tick(input); vm.renderer.onTick(vm.match.sim); vm.renderer.advance(vm.match.sim, Tuning.DT)
    }

    @Test fun stickhandling() {
        val (vm, _) = carryUpIce()
        showGame(vm, "stickhandling.png")
    }

    /**
     * The game on screen and settled (laid out, and its first frames run, which release the touch
     * controls), so input set on the controls now is what the frame loop goes on using for the frames
     * save() runs: a held shot stays held and a released one stays released.
     */
    private fun settledControls(vm: GameViewModel): SkaterInput {
        compose(vm)
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        return vm.controls.input
    }

    @Test fun slapshot_windup() {
        val (vm, me) = carryUpIce()
        val shoot = settledControls(vm).apply { moveX = 1f; shoot = true }
        repeat(Math.round(0.65f / Tuning.DT)) { tickWith(vm, shoot) }
        save("slapshot-windup.png")
        assertEquals(Act.WINDUP, me.act)
        assertTrue("windup ${me.windup}", me.windup > 0.6f)
    }

    @Test fun hockey_stop() {
        val (vm, me) = carryUpIce()
        val stick = settledControls(vm).apply { moveX = -1f; moveY = 0f }   // against the heading: a hockey stop
        me.vx = 8f; me.vy = 0f
        repeat(2) { tickWith(vm, stick) }
        assertTrue("stopping, speed ${me.vx}", me.stopping)
        save("hockey-stop.png")
    }

    @Test fun wrist_release() {
        val (vm, me) = carryUpIce()
        me.x = 12f; me.y = 0f   // inside FLAT_SHOT_M of the net, so the wrist lift and shadow show
        vm.renderer.camera.snapTo(me.x, me.y)
        val shoot = settledControls(vm).apply { moveX = 1f; shoot = true }
        repeat(Math.round(0.3f / Tuning.DT)) { tickWith(vm, shoot) }
        shoot.shoot = false
        tickWith(vm, shoot)   // released: the shot goes
        assertEquals(Clip.WRIST_FOLLOW, vm.renderer.anim.skaters[me.id].clip)
        repeat(Math.round(0.12f / Tuning.DT)) { tickWith(vm, shoot) }
        save("wrist-release.png")
    }

    /**
     * A slapshot 0.25 s after the release from inside the attacking blue line (a shot from farther
     * than FLAT_SHOT_M out stays on the ice), the lane to the net cleared so nobody's stick deflects
     * it: the puck up in the air over its shadow on the ice.
     */
    @Test fun shot_in_flight() {
        val (vm, me) = carryUpIce()
        val sim = vm.match.sim
        fun clearLane() { for (s in sim.skaters) if (s !== me) { s.x = me.x - 6f + 1.5f * s.id; s.y = 9f; s.vx = 0f; s.vy = 0f } }
        me.x = 8f; me.y = 0f
        clearLane()
        vm.renderer.camera.snapTo(me.x, me.y)
        val shoot = settledControls(vm).apply { moveX = 1f; shoot = true }
        repeat(Math.round(0.65f / Tuning.DT)) { tickWith(vm, shoot) }
        assertEquals("wound up with the puck", me, sim.puck.carrier)
        clearLane()
        shoot.shoot = false
        tickWith(vm, shoot)   // released: the shot goes
        assertEquals(Clip.SLAP_FOLLOW, vm.renderer.anim.skaters[me.id].clip)
        repeat(Math.round(0.25f / Tuning.DT) - 1) { tickWith(vm, shoot) }
        val h = vm.renderer.anim.flight.height
        assertTrue("height $h", h > 0.8f)
        assertTrue("puck vx ${vm.match.sim.puck.vx}: deflected", vm.match.sim.puck.vx > 25f)
        save("shot-in-flight.png")
    }

    /**
     * The camera pushed in (zoom goal 1.3) on the goal mouth at x = [goalX], a loose puck in the
     * crease and nobody else near, so the goalie fills the frame.
     */
    private fun goalieCloseUp(goalX: Float, name: String) {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        playTo(vm, 4f)
        val sim = vm.match.sim
        for (s in sim.skaters) { s.x = -goalX * 0.3f + s.id; s.y = -8f; s.vx = 0f; s.vy = 0f }
        for (g in sim.goalies) { g.holding = false }
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = goalX - kotlin.math.sign(goalX) * 2.5f; sim.puck.y = 0.6f; sim.puck.vx = 0f; sim.puck.vy = 0f
        vm.renderer.camera.zoomGoal = 1.3f
        vm.renderer.camera.snapTo(sim.puck.x, sim.puck.y)
        showGame(vm, name)
    }

    /**
     * A wrist shot from the slot, the lane cleared, and the camera pushed in (zoom goal 1.8) on the
     * goalie 0.3 s after the release: down in the butterfly, pads flared flat along the ice (toward
     * and away from the camera, which looks across the rink).
     */
    @Test fun goalie_butterfly() {
        val (vm, me) = carryUpIce()
        val sim = vm.match.sim
        fun clearLane() { for (s in sim.skaters) if (s !== me) { s.x = me.x - 6f + 1.5f * s.id; s.y = 9f; s.vx = 0f; s.vy = 0f } }
        me.x = 14f; me.y = -1.5f; me.vx = 2f; me.vy = 0f; me.facing = 0f
        clearLane()
        val goalie = sim.goalies[Side.AWAY.index]
        val shoot = settledControls(vm).apply { moveX = 1f; shoot = true }
        repeat(Math.round(0.3f / Tuning.DT)) { tickWith(vm, shoot) }
        assertEquals("wound up with the puck", me, sim.puck.carrier)
        clearLane()
        shoot.shoot = false
        shoot.moveX = 0f
        tickWith(vm, shoot)   // released: the shot goes
        assertEquals(Clip.GOALIE_BUTTERFLY, vm.renderer.anim.goalies[goalie.side.index].clip)
        repeat(Math.round(0.3f / Tuning.DT) - 1) { tickWith(vm, shoot) }
        assertEquals(Phase.PLAY, sim.phase)
        assertEquals(Clip.GOALIE_BUTTERFLY, vm.renderer.anim.goalies[goalie.side.index].clip)
        vm.renderer.camera.zoomGoal = 1.8f
        vm.renderer.camera.snapTo(goalie.x - 2.5f, goalie.y)
        save("goalie-butterfly.png")
    }

    /** The AWAY goalie's drawn state, with the camera pushed in (zoom goal 1.6) on him. */
    private fun goalieShot(vm: GameViewModel, name: String) {
        val g = vm.match.sim.goalies[Side.AWAY.index]
        vm.renderer.camera.zoomGoal = 1.6f
        vm.renderer.camera.snapTo(g.x - 2f, g.y)
        showGame(vm, name)
    }

    /** Every skater but [except] stood far down ice, so nobody gets to the puck or into the shot. */
    private fun park(vm: GameViewModel, except: Skater? = null) {
        for (s in vm.match.sim.skaters) if (s !== except) { s.x = -24f; s.y = -12f + 2f * s.id; s.vx = 0f; s.vy = 0f }
    }

    /** A puck carrier at a sharp angle by the AWAY dot: the goalie is out on his arc, facing him. */
    @Test fun goalie_angle() {
        val (vm, me) = carryUpIce()
        val sim = vm.match.sim
        me.x = Rink.FACEOFF_X; me.y = -Rink.FACEOFF_Y; me.vx = 0f; me.vy = 0f
        sim.giveTo(me)
        repeat(90) { park(vm, me); me.vx = 0f; me.vy = 0f; tickWith(vm, null) }
        goalieShot(vm, "goalie-angle.png")
    }

    /** The puck behind the AWAY net: the goalie seals the near post, facing the corner. */
    @Test fun goalie_post() {
        val (vm, _) = carryUpIce()
        val sim = vm.match.sim
        park(vm)
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = Rink.GOAL_X + 2.5f; sim.puck.y = -3f; sim.puck.vx = 0f; sim.puck.vy = 0f
        repeat(120) { park(vm); tickWith(vm, null) }
        goalieShot(vm, "goalie-post.png")
    }

    /** A cross-crease pass: the puck jumps across the slot and the goalie T-pushes, captured mid-move. */
    @Test fun goalie_t_push() {
        val (vm, _) = carryUpIce()
        val sim = vm.match.sim
        val side = Side.AWAY.index
        park(vm)
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = Rink.GOAL_X - 5f; sim.puck.vx = 0f; sim.puck.vy = 0f
        sim.puck.y = -5f
        repeat(90) { park(vm); tickWith(vm, null) }
        sim.puck.y = 5f
        var n = 0
        while (vm.renderer.anim.goalies[side].pushW <= 0.8f && n < 240) { park(vm); sim.puck.y = 5f; tickWith(vm, null); n++ }
        assertTrue("pushW ${vm.renderer.anim.goalies[side].pushW} after $n ticks", vm.renderer.anim.goalies[side].pushW > 0.8f)
        goalieShot(vm, "goalie-t-push.png")
    }

    /** After a catch by the far goalie: both teams lined up on his zone's near-side dot, the camera there. */
    @Test fun zone_faceoff() {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        playTo(vm, 4f)
        val sim = vm.match.sim
        sim.startFaceoff(Rink.FACEOFF_X, -Rink.FACEOFF_Y)
        // Let the camera follow the puck to the dot; the sim holds still, as it does in FACEOFF.
        repeat(180) {
            vm.renderer.onTick(sim)
            vm.renderer.advance(sim, Tuning.DT)
        }
        assertEquals(Phase.FACEOFF, sim.phase)
        showGame(vm, "zone-faceoff.png")
    }

    @Test fun goalie_close_up() = goalieCloseUp(-Rink.GOAL_X, "goalie-close-up.png")

    @Test fun goalie_close_up_far_end() = goalieCloseUp(Rink.GOAL_X, "goalie-close-up-far-end.png")

    /**
     * The camera pushed in (zoom goal 1.6) on a row of standing skaters turning through a full circle,
     * both kits, so the number on the back can be seen turning with the body and fading side-on.
     */
    @Test fun skater_close_up() {
        val vm = GameViewModel(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.HARD), FeedbackDirector(Silent), seed = 3L)
        playTo(vm, 4f)
        val sim = vm.match.sim
        for (s in sim.skaters) {
            s.x = -6.75f + 1.5f * s.id; s.y = if (s.id % 2 == 0) 0f else 1.5f; s.vx = 0f; s.vy = 0f; s.act = Act.NONE
            s.facing = (s.id * 2.0 * Math.PI / sim.skaters.size).toFloat()
        }
        for (g in sim.goalies) { g.holding = false }
        sim.puck.carrier = null; sim.puck.passTarget = null
        sim.puck.x = 0f; sim.puck.y = -6f; sim.puck.vx = 0f; sim.puck.vy = 0f
        vm.renderer.camera.zoomGoal = 1.6f
        vm.renderer.camera.snapTo(0f, 0.75f)
        showGame(vm, "skater-close-up.png")
    }

    @Test fun result() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HockeyTheme { ResultScreen(Teams.byId("MIN")!!, Teams.byId("COL")!!, 4, 2, onRematch = {}, onChangeTeams = {}) }
        }
        save("result.png")
    }
}
