package com.personal.hockey.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }

    @Test fun defaultsEqualSettings() = runTest {
        assertEquals(Settings(), DataStoreSettings(dataStore()).settings.first())
    }

    @Test fun defaultMatchupIsTheFirstTwoTeams() {
        assertEquals(com.personal.hockey.teams.Teams.all[0].id, Settings().homeId)
        assertEquals(com.personal.hockey.teams.Teams.all[1].id, Settings().awayId)
    }

    @Test fun aReadErrorGivesTheDefaults() = runTest {
        val broken = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> {
                throw java.io.IOException("corrupt")
            }
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ) = throw java.io.IOException("corrupt")
        }
        assertEquals(Settings(), DataStoreSettings(broken).settings.first())
    }

    @Test fun matchupAndTogglesAreReadBack() = runTest {
        val store = DataStoreSettings(dataStore())
        store.setMatchup("TOR", "EDM", Level.HARD)
        store.setSoundOn(false)
        store.setVibrationOn(false)
        assertEquals(Settings("TOR", "EDM", Level.HARD, soundOn = false, vibrationOn = false), store.settings.first())
    }

    @Test fun everyLevelIsReadBack() = runTest {
        val store = DataStoreSettings(dataStore())
        for (level in Level.entries) {
            store.setMatchup("TOR", "EDM", level)
            assertEquals(level, store.settings.first().level)
        }
    }

    @Test fun storedMediumIsReadBack() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("level")] = "MEDIUM" }
        assertEquals(Level.MEDIUM, DataStoreSettings(ds).settings.first().level)
    }

    @Test fun unknownStoredLevelFallsBackToDefaultLevel() = runTest {
        for (bad in listOf("medium", "", "EXPERT")) {
            val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "bad-${bad.length}-$bad.preferences_pb") }
            ds.edit { it[stringPreferencesKey("level")] = bad }
            assertEquals(bad, Settings().level, DataStoreSettings(ds).settings.first().level)
        }
    }

    @Test fun unknownStoredValuesFallBackToDefaults() = runTest {
        val ds = dataStore()
        ds.edit {
            it[stringPreferencesKey("home_id")] = "ZZZ"
            it[stringPreferencesKey("away_id")] = "QQQ"
            it[stringPreferencesKey("level")] = "NIGHTMARE"
        }
        assertEquals(Settings(), DataStoreSettings(ds).settings.first())
    }

    @Test fun sameTeamBothSidesMovesAwayToNext() = runTest {
        val store = DataStoreSettings(dataStore())
        store.setMatchup("MIN", "MIN", Level.EASY)
        assertEquals("COL", store.settings.first().awayId)
    }

    @Test fun invalidStoredHomeNeverEqualsStoredAway() = runTest {
        val ds = dataStore()
        ds.edit {
            it[stringPreferencesKey("home_id")] = "ZZZ"   // falls back to the default home, MIN
            it[stringPreferencesKey("away_id")] = "MIN"
        }
        val s = DataStoreSettings(ds).settings.first()
        assertEquals("MIN", s.homeId)
        assertEquals("COL", s.awayId)
    }

    @Test fun missingStoredAwayNeverEqualsStoredHome() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("home_id")] = "COL" }   // away falls back to the default, COL
        val s = DataStoreSettings(ds).settings.first()
        assertEquals("COL", s.homeId)
        assertEquals("EDM", s.awayId)
    }
}
