package com.personal.hockey.ui.select

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.settings.Level
import com.personal.hockey.settings.Settings
import com.personal.hockey.teams.Crest
import com.personal.hockey.teams.Team
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
class TeamSelectScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val plays = mutableListOf<Triple<String, String, Level>>()

    private fun show(teams: List<Team> = Teams.all, initial: Settings = Settings()) = compose.setContent {
        HockeyTheme { TeamSelectScreen(teams, initial) { h, a, l -> plays += Triple(h, a, l) } }
    }

    @Test fun shows_four_teams_on_both_rows_and_restores_last_choice() {
        show(initial = Settings(homeId = "TOR", awayId = "EDM", level = Level.HARD))
        for (t in Teams.all) {
            compose.onNodeWithTag("you-${t.id}").assertIsDisplayed()
            compose.onNodeWithTag("opp-${t.id}").assertIsDisplayed()
        }
        compose.onNodeWithTag("you-TOR").assertIsSelected()
        compose.onNodeWithTag("opp-EDM").assertIsSelected()
        compose.onNodeWithTag("level-HARD").assertIsSelected()
    }

    @Test fun easy_medium_and_hard_are_all_shown_and_selectable() {
        show()
        for ((level, label) in listOf(Level.EASY to "Easy", Level.MEDIUM to "Medium", Level.HARD to "Hard")) {
            compose.onNodeWithTag("level-${level.name}").assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        for (level in listOf(Level.MEDIUM, Level.HARD, Level.EASY, Level.MEDIUM)) {
            compose.onNodeWithTag("level-${level.name}").performClick().assertIsSelected()
            for (other in Level.entries - level) compose.onNodeWithTag("level-${other.name}").assertIsNotSelected()
            compose.onNodeWithTag("play").performClick()
            assertEquals(level, plays.last().third)
        }
    }

    @Test fun a_saved_medium_is_restored() {
        show(initial = Settings(level = Level.MEDIUM))
        compose.onNodeWithTag("level-MEDIUM").assertIsSelected()
    }

    /** The level choice and Play share the title row; the title must still show in full. */
    @Test fun title_level_choice_and_play_fit_on_one_row() {
        show()
        val title = compose.onNodeWithText("NHL All Stars").fetchSemanticsNode()
        val easy = compose.onNodeWithTag("level-EASY").fetchSemanticsNode().boundsInRoot
        val hard = compose.onNodeWithTag("level-HARD").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("play").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("title clear of the levels", title.boundsInRoot.right <= easy.left)
        assertTrue("play inside the screen: $play vs $root", play.right <= root.right)
        assertTrue("levels then play", hard.right <= play.left)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals("title on one line", 1, results.single().lineCount)
        assertTrue("title not clipped", !results.single().hasVisualOverflow)
    }

    @Test fun play_reports_the_selection() {
        show()
        compose.onNodeWithTag("you-EDM").performClick()
        compose.onNodeWithTag("opp-TOR").performClick()
        compose.onNodeWithTag("level-HARD").performClick()
        compose.onNodeWithTag("play").performClick()
        assertEquals(listOf(Triple("EDM", "TOR", Level.HARD)), plays)
    }

    @Test fun a_fifth_team_shows_up_and_can_be_played() {
        val extra = Teams.all[0].copy(id = "ZZZ", name = "Zed Zebras", code = "ZZZ", crest = Crest.LEAF)
        show(teams = Teams.all + extra)
        compose.onNodeWithTag("you-row").performScrollToNode(hasTestTag("you-ZZZ"))
        compose.onNodeWithTag("you-ZZZ").performClick()
        compose.onNodeWithTag("play").performClick()
        assertEquals("ZZZ", plays.single().first)
    }

    @Test fun a_remembered_team_far_down_a_long_list_is_scrolled_into_view() {
        val extras = (1..6).map { Teams.all[0].copy(id = "X$it", name = "Extra $it", code = "X$it") }
        show(teams = Teams.all + extras, initial = Settings(homeId = "X6", awayId = "X5"))
        compose.onNodeWithTag("you-X6").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("opp-X5").assertIsDisplayed().assertIsSelected()
    }

    @Test fun picking_the_same_team_moves_the_other_row() {
        show(initial = Settings(homeId = "MIN", awayId = "COL"))
        compose.onNodeWithTag("you-COL").performClick()
        compose.onNodeWithTag("opp-EDM").assertIsSelected()
        compose.onNodeWithTag("play").performClick()
        assertEquals("COL", plays.single().first)
        assertEquals("EDM", plays.single().second)
    }

    @Test fun everything_fits_without_scrolling() {
        show()
        compose.onNodeWithTag("play").assertIsDisplayed()
        for (t in Teams.all) {
            compose.onNodeWithTag("you-${t.id}").assertIsDisplayed()
            compose.onNodeWithTag("opp-${t.id}").assertIsDisplayed()
        }
    }

    @Test fun unknown_saved_ids_fall_back_to_the_first_two_teams() {
        show(initial = Settings(homeId = "NOPE", awayId = "ALSO-NOPE"))
        compose.onNodeWithTag("you-${Teams.all[0].id}").assertIsSelected()
        compose.onNodeWithTag("opp-${Teams.all[1].id}").assertIsSelected()
    }

    @Test fun same_saved_id_on_both_rows_is_separated() {
        show(initial = Settings(homeId = "EDM", awayId = "EDM"))
        compose.onNodeWithTag("you-EDM").assertIsSelected()
        compose.onNodeWithTag("opp-TOR").assertIsSelected()
    }

    @Test fun no_teams_does_not_crash_and_play_is_disabled() {
        show(teams = emptyList())
        compose.onNodeWithTag("play").assertIsNotEnabled()
        assertEquals(0, plays.size)
    }

    /** The check mark on a selected card must not squeeze the name into mid-word breaks. */
    @Test fun a_selected_card_gives_the_name_as_much_room_as_an_unselected_one() {
        show(initial = Settings(homeId = "MIN", awayId = "COL"))
        for (name in listOf("Minnesota Wild", "Colorado Avalanche")) {
            val widths = compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot.width }
            assertEquals(name, 2, widths.size)
            assertEquals(name, widths[0], widths[1], 0.5f)
        }
    }
}
