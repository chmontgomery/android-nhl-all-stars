package com.personal.hockey.game

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.ui.theme.HockeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class PauseOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** At twice the font size the overlay is taller than a 360 dp screen; Quit must still be reachable. */
    @Test fun every_control_is_reachable_at_large_font_scale() {
        var quits = 0
        // Like GameScreenTest: a game's frame loop left running by an earlier test in this JVM would
        // otherwise keep the clock from ever going idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                HockeyTheme {
                    PauseOverlay(soundOn = true, vibrationOn = true, onSoundChange = {}, onVibrationChange = {},
                        onResume = {}, onQuit = { quits++ })
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Paused").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quit").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Yes").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, quits)
    }

    private fun show(onResume: () -> Unit = {}, onQuit: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HockeyTheme {
                PauseOverlay(soundOn = true, vibrationOn = true, onSoundChange = {}, onVibrationChange = {},
                    onResume = onResume, onQuit = onQuit)
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun quit_asks_first_and_no_goes_back() {
        var quits = 0
        show { quits++ }
        click("Quit")
        assertEquals(0, quits)
        compose.onNodeWithText("Quit game?").assertIsDisplayed()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        click("No")
        assertEquals(0, quits)
        compose.onNodeWithText("Resume").assertIsDisplayed()
        click("Quit")
        click("Yes")
        assertEquals(1, quits)
    }

    @Test fun quit_is_well_apart_from_resume() {
        show {}
        val resume = compose.onNodeWithText("Resume").fetchSemanticsNode().boundsInRoot
        val quit = compose.onNodeWithText("Quit").fetchSemanticsNode().boundsInRoot
        val gapPx = maxOf(quit.top - resume.bottom, resume.top - quit.bottom, quit.left - resume.right, resume.left - quit.right)
        val gapDp = gapPx / compose.density.density
        assertTrue("Resume and Quit only $gapDp dp apart", gapDp >= 48f)
    }
}
