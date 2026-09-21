package app.krafted.candytriangle.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The three §10 flags owned by [SettingsStore].
 *
 * Split out of §10's single `ProgressKeys` sketch so that [ProgressStore.resetProgress] has a
 * clean line to stop at — see the note on [ProgressKeys]. The key strings are §10's verbatim, so
 * both objects can address the same preferences file.
 */
internal object SettingsKeys {
    val SOUND_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("sound_on")
    val MUSIC_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("music_on")
    val VIBRATE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("vibrate_on")
}

/** Snapshot of the D4 Settings screen's three toggles, for collecting as one value. */
data class GameSettings(
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
)

/**
 * Sound, music and vibration preferences (§10, surfaced by D4's Settings screen).
 *
 * All three default to **on** when unset: the game ships with synthesised SFX and music loops
 * (D5) that are part of the intended first-run experience, and a fresh install must not boot
 * silent. "Unset" and "explicitly true" are therefore indistinguishable by design — nothing needs
 * to tell them apart, so no nullable tri-state is stored.
 *
 * Takes its [dataStore] by constructor for the same JVM-testability reason as [ProgressStore].
 * `AppContainer` may hand it the same instance it hands [ProgressStore]; the key names do not
 * collide either way.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    /** See [ProgressStore]: an unreadable file degrades to defaults rather than crashing. */
    private val preferences: Flow<Preferences> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    val soundEnabled: Flow<Boolean> = flagFlow(SettingsKeys.SOUND_ENABLED)

    val musicEnabled: Flow<Boolean> = flagFlow(SettingsKeys.MUSIC_ENABLED)

    val vibrateEnabled: Flow<Boolean> = flagFlow(SettingsKeys.VIBRATE_ENABLED)

    /** All three at once, so the Settings screen collects one state instead of three. */
    val settings: Flow<GameSettings> = preferences
        .map {
            GameSettings(
                soundEnabled = it[SettingsKeys.SOUND_ENABLED] ?: true,
                musicEnabled = it[SettingsKeys.MUSIC_ENABLED] ?: true,
                vibrateEnabled = it[SettingsKeys.VIBRATE_ENABLED] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun setSoundEnabled(enabled: Boolean) = setFlag(SettingsKeys.SOUND_ENABLED, enabled)

    suspend fun setMusicEnabled(enabled: Boolean) = setFlag(SettingsKeys.MUSIC_ENABLED, enabled)

    suspend fun setVibrateEnabled(enabled: Boolean) =
        setFlag(SettingsKeys.VIBRATE_ENABLED, enabled)

    private fun flagFlow(key: Preferences.Key<Boolean>): Flow<Boolean> = preferences
        .map { it[key] ?: true }
        .distinctUntilChanged()

    private suspend fun setFlag(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { it[key] = enabled }
    }
}
