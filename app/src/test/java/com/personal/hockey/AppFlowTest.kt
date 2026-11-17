package com.personal.hockey

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.audio.Buzz
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.audio.Sfx
import com.personal.hockey.game.GameViewModel
import com.personal.hockey.settings.Level
import com.personal.hockey.settings.MemorySettings
import com.personal.hockey.settings.Settings
import com.personal.hockey.sim.Phase
import com.personal.hockey.ui.AppDeps
import com.personal.hockey.ui.AppRoot
import com.personal.hockey.ui.NavViewModel
import com.personal.hockey.ui.Screen
import com.personal.hockey.ui.theme.HockeyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private object Silent : GameFeedback {
        override fun play(sfx: Sfx) {}
        override fun vibrate(buzz: Buzz) {}
    }

    private val store = MemorySettings(Settings(homeId = "MIN", awayId = "COL", level = Level.EASY))

    private fun start() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HockeyTheme { AppRoot(AppDeps(store, { Silent })) } }
        advance()
    }

    private fun pressBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            Snapshot.sendApplyNotifications()
        }
        advance()
    }

    private fun advance(ms: Long = 100) = compose.mainClock.advanceTimeBy(ms)

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        advance()
    }

    private val nav get() = ViewModelProvider(compose.activity)[NavViewModel::class.java].navigator
    private fun gameVm() = ViewModelProvider(nav.current)[GameViewModel::class.java]

    @Test fun select_play_quit_returns_to_select() {
        start()
        compose.onNodeWithTag("play").assertIsDisplayed()
        click("play")
        compose.onNodeWithTag("game-canvas").assertIsDisplayed()
        click("pause-button")
        compose.onNodeWithText("Quit").performClick()
        advance()
        compose.onNodeWithText("Yes").performClick()
        advance()
        compose.onNodeWithTag("play").assertIsDisplayed()
        assertEquals(Screen.TeamSelect, nav.current.screen)
        assertEquals(0, compose.onAllNodesWithTag("game-canvas").fetchSemanticsNodes().size)
    }

    @Test fun matchup_is_saved_when_play_is_tapped() {
        start()
        click("you-EDM")
        click("opp-TOR")
        click("level-HARD")
        click("play")
        val saved = runBlocking { store.settings.first() }
        assertEquals(Triple("EDM", "TOR", Level.HARD), Triple(saved.homeId, saved.awayId, saved.level))
        assertEquals(Screen.Game("EDM", "TOR", Level.HARD), nav.current.screen)
    }

    @Test fun finished_game_shows_result_then_rematch_starts_a_new_game() {
        start()
        click("play")
        val first = gameVm()
        var guard = 0
        while (first.match.sim.phase != Phase.FINAL && guard++ < 200_000) first.match.tick(null)
        assertEquals(Phase.FINAL, first.match.sim.phase)
        advance(3_000)
        assertTrue(nav.current.screen is Screen.Result)
        compose.onNodeWithText("Final").assertIsDisplayed()
        compose.onNodeWithText("Rematch").performClick()
        advance()
        assertTrue(nav.current.screen is Screen.Game)
        val second = gameVm()
        assertNotSame(first, second)
        compose.onNodeWithText("20:00").assertIsDisplayed()
        assertEquals(0, second.hud.value.homeScore + second.hud.value.awayScore)
        compose.onNodeWithTag("hud-home-score").assertIsDisplayed()
    }

    @Test fun back_on_team_select_exits_and_back_in_game_pauses() {
        start()
        pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test fun back_in_game_pauses() {
        start()
        click("play")
        pressBack()
        assertTrue(gameVm().hud.value.paused)
        assertTrue(nav.current.screen is Screen.Game)
    }

    @Test fun back_on_result_returns_to_select() {
        start()
        click("play")
        val vm = gameVm()
        var guard = 0
        while (vm.match.sim.phase != Phase.FINAL && guard++ < 200_000) vm.match.tick(null)
        assertEquals(Phase.FINAL, vm.match.sim.phase)
        advance(3_000)
        assertTrue(nav.current.screen is Screen.Result)
        pressBack()
        assertEquals(Screen.TeamSelect, nav.current.screen)
        compose.onNodeWithTag("play").assertIsDisplayed()
    }

    @Test fun double_tap_on_play_starts_only_one_game() {
        start()
        compose.onNodeWithTag("play").performClick()
        compose.onNodeWithTag("play").performClick()
        advance()
        assertTrue(nav.current.screen is Screen.Game)
        click("pause-button")
        compose.onNodeWithText("Quit").performClick()
        advance()
        compose.onNodeWithText("Yes").performClick()
        advance()
        assertEquals(Screen.TeamSelect, nav.current.screen)
        assertTrue(!nav.canGoBack)
    }
}
