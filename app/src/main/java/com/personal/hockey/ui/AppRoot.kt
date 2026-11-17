package com.personal.hockey.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.hockey.audio.FeedbackDirector
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.game.GameScreen
import com.personal.hockey.game.GameViewModel
import com.personal.hockey.settings.Settings
import com.personal.hockey.settings.SettingsStore
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Team
import com.personal.hockey.teams.Teams
import com.personal.hockey.ui.result.ResultScreen
import com.personal.hockey.ui.select.TeamSelectScreen
import java.io.IOException
import kotlinx.coroutines.launch

/** Everything the screens need; built once by AppGraph (or by tests). */
class AppDeps(
    val settings: SettingsStore,
    val feedback: () -> GameFeedback,
)

@Composable
fun AppRoot(deps: AppDeps) {
    val nav = viewModel<NavViewModel>().navigator
    val prefs: Settings? by deps.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    // Storage problems never crash the app: a failed settings write just doesn't stick.
    val persist: (suspend () -> Unit) -> Unit = { write ->
        scope.launch { try { write() } catch (e: IOException) { } }
    }

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Until settings load show only the background, so there is no flash of defaults.
        val current = prefs ?: return@Box
        val entry = nav.current
        key(entry.id) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                when (val screen = entry.screen) {
                    Screen.TeamSelect -> TeamSelectScreen(Teams.all, current) { homeId, awayId, level ->
                        // Navigate at once (a second tap must not add a second game); save alongside.
                        if (nav.current.id == entry.id) {
                            nav.push(Screen.Game(homeId, awayId, level))
                            persist { deps.settings.setMatchup(homeId, awayId, level) }
                        }
                    }

                    is Screen.Game -> {
                        WithTeams(screen.homeId, screen.awayId, nav, entry) { home, away ->
                            val vm: GameViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer {
                                        val director = FeedbackDirector(deps.feedback()).also {
                                            it.soundOn = current.soundOn
                                            it.vibrationOn = current.vibrationOn
                                        }
                                        GameViewModel(MatchSetup.of(home, away, screen.level), director)
                                    }
                                },
                            )
                            GameScreen(
                                vm,
                                soundOn = current.soundOn,
                                vibrationOn = current.vibrationOn,
                                onSoundChange = { on -> persist { deps.settings.setSoundOn(on) } },
                                onVibrationChange = { on -> persist { deps.settings.setVibrationOn(on) } },
                                onFinished = { h, a ->
                                    nav.replace(Screen.Result(screen.homeId, screen.awayId, screen.level, h, a))
                                },
                                onQuit = { nav.reset() },
                            )
                        }
                    }

                    is Screen.Result -> {
                        WithTeams(screen.homeId, screen.awayId, nav, entry) { home, away ->
                            ResultScreen(
                                home, away, screen.homeScore, screen.awayScore,
                                onRematch = { nav.replace(Screen.Game(screen.homeId, screen.awayId, screen.level)) },
                                onChangeTeams = { nav.reset() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shows [content] for two known teams; unknown ids send the app back to team select. */
@Composable
private fun WithTeams(homeId: String, awayId: String, nav: Navigator, entry: Entry, content: @Composable (Team, Team) -> Unit) {
    val home = Teams.byId(homeId)
    val away = Teams.byId(awayId)
    if (home == null || away == null) LaunchedEffect(entry.id) { nav.reset() } else content(home, away)
}
