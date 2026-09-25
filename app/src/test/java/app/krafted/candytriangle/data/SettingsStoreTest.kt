package app.krafted.candytriangle.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §10's persisted vibration preference. */
class SettingsStoreTest {

    private val preferences = FakePreferencesDataStore()
    private val store = SettingsStore(preferences)

    @Test
    fun vibrationDefaultsToOnWhenUnset() = runTest {
        assertTrue(store.vibrateEnabled.first())
        assertEquals(GameSettings(), store.settings.first())
    }

    @Test
    fun setterRoundTrips() = runTest {
        store.setVibrateEnabled(false)

        assertFalse(store.vibrateEnabled.first())
        assertEquals(GameSettings(vibrateEnabled = false), store.settings.first())
    }

    /** Pins §10's key spelling, so an existing save's vibration choice keeps working. */
    @Test
    fun readsTheSection10KeyNames() = runTest {
        val seeded = FakePreferencesDataStore(
            mutablePreferencesOf(
                booleanPreferencesKey("vibrate_on") to false,
            ),
        )

        assertEquals(
            GameSettings(vibrateEnabled = false),
            SettingsStore(seeded).settings.first(),
        )

        store.setVibrateEnabled(false)
        assertFalse(preferences.data.first()[booleanPreferencesKey("vibrate_on")]!!)
    }

    /**
     * [ProgressStore] and [SettingsStore] can share one preferences file: §10's key names do not
     * collide, and `AppContainer` is free to hand both stores the same `DataStore`.
     */
    @Test
    fun coexistsWithProgressInOneDataStore() = runTest {
        val progress = ProgressStore(preferences)

        store.setVibrateEnabled(false)
        progress.recordLevelResult(levelId = 3, crowns = 2, score = 1_200)

        assertFalse(store.vibrateEnabled.first())
        assertEquals(2, progress.crownsFor(3).first())
    }
}
