package com.personal.hockey.ui.result

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.teams.Teams
import com.personal.hockey.ui.theme.HockeyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class ResultScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val min = Teams.byId("MIN")!!
    private val col = Teams.byId("COL")!!
    private var rematches = 0
    private var changes = 0

    private fun show(h: Int, a: Int) = compose.setContent {
        HockeyTheme { ResultScreen(min, col, h, a, onRematch = { rematches++ }, onChangeTeams = { changes++ }) }
    }

    @Test fun shows_winner() {
        show(3, 1)
        compose.onNodeWithText("Final").assertIsDisplayed()
        compose.onNodeWithText("Minnesota Wild win!").assertIsDisplayed()
    }

    @Test fun shows_away_winner() {
        show(1, 3)
        compose.onNodeWithText("Colorado Avalanche win!").assertIsDisplayed()
    }

    @Test fun shows_tie() {
        show(2, 2)
        compose.onNodeWithText("Tie game").assertIsDisplayed()
    }

    @Test fun buttons_call_back() {
        show(2, 2)
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Rematch").performClick()
        compose.onNodeWithText("Change teams").performClick()
        assertEquals(1, rematches)
        assertEquals(1, changes)
    }

    @Test fun taps_in_the_first_moment_are_ignored() {
        compose.mainClock.autoAdvance = false
        show(2, 2)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Rematch").performClick()
        compose.onNodeWithText("Change teams").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, rematches)
        assertEquals(0, changes)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Rematch").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, rematches)
    }
}
