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

/** The vibration flag owned by [SettingsStore]. */
internal object SettingsKeys {
    val VIBRATE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("vibrate_on")
}

/** Snapshot of the working feedback options exposed by Settings. */
data class GameSettings(
    val vibrateEnabled: Boolean = true,
)

/**
 * Vibration feedback preference (§10, surfaced by D4's Settings screen).
 *
 * It defaults to **on** when unset, matching the persistence schema's first-run behavior.
 * "Unset" and "explicitly true" are therefore indistinguishable by design — nothing needs to
 * tell them apart, so no nullable tri-state is stored.
 *
 * Takes its [dataStore] by constructor for the same JVM-testability reason as [ProgressStore].
 * `AppContainer` may hand it the same instance it hands [ProgressStore]; the key names do not
 * collide either way.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    /** See [ProgressStore]: an unreadable file degrades to defaults rather than crashing. */
    private val preferences: Flow<Preferences> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    val vibrateEnabled: Flow<Boolean> = flagFlow(SettingsKeys.VIBRATE_ENABLED)

    /** Kept as a snapshot so every consumer shares the same state shape. */
    val settings: Flow<GameSettings> = preferences
        .map {
            GameSettings(
                vibrateEnabled = it[SettingsKeys.VIBRATE_ENABLED] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun setVibrateEnabled(enabled: Boolean) =
        setFlag(SettingsKeys.VIBRATE_ENABLED, enabled)

    private fun flagFlow(key: Preferences.Key<Boolean>): Flow<Boolean> = preferences
        .map { it[key] ?: true }
        .distinctUntilChanged()

    private suspend fun setFlag(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { it[key] = enabled }
    }
}
