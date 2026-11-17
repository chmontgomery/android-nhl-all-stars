package com.personal.hockey.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.personal.hockey.teams.Teams
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Easiest first; the order the team-select screen shows them in. */
enum class Level { EASY, MEDIUM, HARD }

data class Settings(
    val homeId: String = Teams.all[0].id,
    val awayId: String = Teams.all[1].id,
    val level: Level = Level.EASY,
    val soundOn: Boolean = true,
    val vibrationOn: Boolean = true,
)

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun setMatchup(homeId: String, awayId: String, level: Level)
    suspend fun setSoundOn(on: Boolean)
    suspend fun setVibrationOn(on: Boolean)
}

/** A team cannot play itself: a clashing away side moves to the team after home. */
private fun distinctAway(homeId: String, awayId: String): String =
    if (awayId == homeId) Teams.next(homeId).id else awayId

class DataStoreSettings(private val store: DataStore<Preferences>) : SettingsStore {
    private val homeKey = stringPreferencesKey("home_id")
    private val awayKey = stringPreferencesKey("away_id")
    private val levelKey = stringPreferencesKey("level")
    private val soundKey = booleanPreferencesKey("sound_on")
    private val vibrationKey = booleanPreferencesKey("vibration_on")

    /** An unreadable file gives the defaults rather than crashing the app. */
    override val settings: Flow<Settings> = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { prefs ->
        val defaults = Settings()
        val homeId = prefs[homeKey]?.takeIf { Teams.byId(it) != null } ?: defaults.homeId
        val awayId = prefs[awayKey]?.takeIf { Teams.byId(it) != null } ?: defaults.awayId
        Settings(
            homeId = homeId,
            awayId = distinctAway(homeId, awayId),   // a fallback can collide with the other side
            level = prefs[levelKey]?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: defaults.level,
            soundOn = prefs[soundKey] ?: defaults.soundOn,
            vibrationOn = prefs[vibrationKey] ?: defaults.vibrationOn,
        )
    }

    override suspend fun setMatchup(homeId: String, awayId: String, level: Level) {
        store.edit {
            it[homeKey] = homeId
            it[awayKey] = distinctAway(homeId, awayId)
            it[levelKey] = level.name
        }
    }

    override suspend fun setSoundOn(on: Boolean) { store.edit { it[soundKey] = on } }
    override suspend fun setVibrationOn(on: Boolean) { store.edit { it[vibrationKey] = on } }
}

/** Not persisted; handy in tests and previews. */
class MemorySettings(initial: Settings = Settings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun setMatchup(homeId: String, awayId: String, level: Level) =
        state.update { it.copy(homeId = homeId, awayId = distinctAway(homeId, awayId), level = level) }
    override suspend fun setSoundOn(on: Boolean) = state.update { it.copy(soundOn = on) }
    override suspend fun setVibrationOn(on: Boolean) = state.update { it.copy(vibrationOn = on) }
}
