package com.personal.hockey

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.personal.hockey.audio.AndroidFeedback
import com.personal.hockey.audio.GameFeedback
import com.personal.hockey.settings.DataStoreSettings
import com.personal.hockey.settings.SettingsStore
import com.personal.hockey.ui.AppDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependency container: the app's long-lived objects, owned by [HockeyApp]. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsStore = DataStoreSettings(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) {
            appContext.preferencesDataStoreFile("settings")
        },
    )

    private val androidFeedback: GameFeedback by lazy { AndroidFeedback(appContext) }

    /** Lazy: sounds are only synthesised once a game asks for them. */
    val feedback: () -> GameFeedback = { androidFeedback }

    val deps = AppDeps(settings, feedback)
}
