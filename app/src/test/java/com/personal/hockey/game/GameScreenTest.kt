package com.personal.hockey.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveTo
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.audio.Buzz
import com.personal.hockey.audio.FeedbackDirector
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.audio.Sfx
import com.personal.hockey.settings.Level
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import com.personal.hockey.ui.theme.HockeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class GameScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private object Silent : GameFeedback {
        override fun play(sfx: Sfx) {}
        override fun vibrate(buzz: Buzz) {}
    }

    private var vm = GameViewModel(
        MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY), FeedbackDirector(Silent), seed = 1L,
    )
    private var quits = 0
    private val sound = mutableListOf<Boolean>()
    private val vibration = mutableListOf<Boolean>()

    private var view: android.view.View? = null

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            HockeyTheme {
                GameScreen(vm, soundOn = true, vibrationOn = true, onSoundChange = { sound += it },
                    onVibrationChange = { vibration += it }, onFinished = { _, _ -> }, onQuit = { quits++ })
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun click(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun shows_scoreboard_and_offence_or_defence_buttons() {
        show()
        compose.onNodeWithText("MIN").assertIsDisplayed()
        compose.onNodeWithText("COL").assertIsDisplayed()
        compose.onNodeWithText("20:00").assertIsDisplayed()
        compose.onNodeWithText("POKE").assertIsDisplayed()
        compose.onNodeWithText("BODY").assertIsDisplayed()
        compose.onNodeWithTag("game-canvas").assertIsDisplayed()
    }

    @Test fun pause_button_opens_overlay_and_resume_closes_it() {
        show()
        click(compose.onNodeWithTag("pause-button"))
        compose.onNodeWithText("Resume").assertIsDisplayed()
        assertTrue(vm.hud.value.paused)
        click(compose.onNodeWithText("Resume"))
        compose.onNodeWithText("Resume").assertDoesNotExist()
        assertTrue(!vm.hud.value.paused)
    }

    @Test fun screen_stays_on_only_while_not_paused() {
        show()
        assertTrue(view!!.keepScreenOn)
        click(compose.onNodeWithTag("pause-button"))
        assertFalse(view!!.keepScreenOn)
        click(compose.onNodeWithText("Resume"))
        assertTrue(view!!.keepScreenOn)
    }

    @Test fun quit_calls_onQuit() {
        show()
        click(compose.onNodeWithTag("pause-button"))
        click(compose.onNodeWithText("Quit"))
        assertEquals(0, quits)
        click(compose.onNodeWithText("Yes"))
        assertEquals(1, quits)
    }

    @Test fun switches_report_changes() {
        show()
        click(compose.onNodeWithTag("pause-button"))
        click(compose.onNodeWithText("Sound"))
        click(compose.onNodeWithText("Vibration"))
        assertEquals(listOf(false), sound)
        assertEquals(listOf(false), vibration)
    }

    @Test fun going_to_background_pauses() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Resume").assertIsDisplayed()
    }

    @Test fun hud_and_buttons_are_inside_the_screen() {
        show()
        val root = DpRect(0.dp, 0.dp, 800.dp, 360.dp)
        for (b in listOf(
            compose.onNodeWithTag("pause-button").getUnclippedBoundsInRoot(),
            compose.onNodeWithTag("hud-clock").getUnclippedBoundsInRoot(),
            compose.onNodeWithTag("hud-home-score").getUnclippedBoundsInRoot(),
            compose.onNodeWithText("POKE").getUnclippedBoundsInRoot(),
            compose.onNodeWithText("BODY").getUnclippedBoundsInRoot(),
        )) {
            assertTrue("$b", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
        }
    }

    private fun assertInputClear() {
        val i = vm.controls.input
        assertEquals(0f, i.moveX, 0f); assertEquals(0f, i.moveY, 0f)
        assertFalse(i.shoot); assertFalse(i.pass); assertFalse(i.deke); assertFalse(i.poke); assertFalse(i.body)
    }

    /** Centre of a defence button in canvas pixels (w800dp-h360dp is 1 px per dp). */
    private fun pokeSpot(): Offset {
        val s = vm.controls.layout.buttons(false).first { it.btn == com.personal.hockey.input.Btn.POKE }
        return Offset(s.cx, s.cy)
    }

    @Test fun a_cancelled_button_press_leaves_input_clear() {
        show()
        val spot = pokeSpot()
        compose.onNodeWithTag("game-canvas").performTouchInput { down(spot) }
        assertTrue(vm.controls.input.poke)
        compose.onNodeWithTag("game-canvas").performTouchInput { cancel() }
        assertInputClear()
    }

    @Test fun two_pointers_then_both_up_leaves_input_clear() {
        show()
        val spot = pokeSpot()
        compose.onNodeWithTag("game-canvas").performTouchInput {
            down(0, Offset(100f, 250f))
            moveTo(0, Offset(150f, 250f))
            down(1, spot)
        }
        assertTrue(vm.controls.input.poke)
        assertTrue(vm.controls.input.moveX > 0f)
        compose.onNodeWithTag("game-canvas").performTouchInput { up(0); up(1) }
        assertInputClear()
    }

    @Test fun pausing_with_a_pointer_down_clears_input_until_a_fresh_down() {
        show()
        val spot = pokeSpot()
        compose.onNodeWithTag("game-canvas").performTouchInput { down(spot) }
        assertTrue(vm.controls.input.poke)
        vm.pause()
        compose.mainClock.advanceTimeByFrame()
        assertInputClear()
        // The test injector allows one gesture at a time, so resume as the Resume button does.
        compose.runOnUiThread { vm.resume() }
        compose.mainClock.advanceTimeByFrame()
        assertInputClear()
        compose.onNodeWithTag("game-canvas").performTouchInput { up() }
        assertInputClear()
        compose.onNodeWithTag("game-canvas").performTouchInput { down(spot) }
        assertTrue(vm.controls.input.poke)
    }

    private fun intersectsCircle(b: DpRect, cx: Float, cy: Float, r: Float): Boolean {
        val nx = cx.coerceIn(b.left.value, b.right.value)
        val ny = cy.coerceIn(b.top.value, b.bottom.value)
        return (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy) < r * r
    }

    @Test fun goal_banner_sits_low_and_clear_of_the_stick_and_buttons() {
        // The longest text the game can show: a Maple Leafs centre with the longest surname.
        val tor = Teams.byId("TOR")!!
        val longTor = tor.copy(skaters = tor.skaters.map { if (it.position == com.personal.hockey.sim.Position.C) it.copy(name = "Eriksson Ek") else it })
        vm = GameViewModel(MatchSetup.of(longTor, Teams.byId("COL")!!, Level.EASY), FeedbackDirector(Silent), seed = 1L)
        show()
        val sim = vm.match.sim
        sim.phase = com.personal.hockey.sim.Phase.GOAL
        sim.lastGoalSide = com.personal.hockey.sim.Side.HOME
        sim.lastScorerId = sim.skaters.first { it.side == com.personal.hockey.sim.Side.HOME && it.pos == com.personal.hockey.sim.Position.C }.id
        compose.runOnUiThread { vm.pause(); vm.resume() }   // publishes the HUD
        compose.mainClock.advanceTimeByFrame()
        assertTrue(vm.hud.value.goalBanner)
        assertEquals("Eriksson Ek scores for the Maple Leafs!", vm.hud.value.banner)
        val b = compose.onNodeWithTag("banner").getUnclippedBoundsInRoot()
        val layout = vm.controls.layout   // w800dp-h360dp is 1 px per dp
        assertTrue("below the middle: $b", (b.top.value + b.bottom.value) / 2 > 360f * 0.7f)
        assertFalse("stick: $b", intersectsCircle(b, layout.stickRestX, layout.stickRestY, layout.stickRadius))
        for (hasPuck in listOf(true, false)) for (s in layout.buttons(hasPuck)) {
            assertFalse("${s.btn}: $b", intersectsCircle(b, s.cx, s.cy, s.radius))
        }
    }

    @Test fun other_banners_stay_centred() {
        show()
        vm.match.sim.phase = com.personal.hockey.sim.Phase.BREAK
        compose.runOnUiThread { vm.pause(); vm.resume() }
        compose.mainClock.advanceTimeByFrame()
        assertFalse(vm.hud.value.goalBanner)
        val b = compose.onNodeWithTag("banner").getUnclippedBoundsInRoot()
        assertEquals(180f, (b.top.value + b.bottom.value) / 2, 2f)
    }
}
