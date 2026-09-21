package app.krafted.candytriangle.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §10's three audio/haptics flags: default-on, and each independent of the others. */
class SettingsStoreTest {

    private val preferences = FakePreferencesDataStore()
    private val store = SettingsStore(preferences)

    /**
     * A fresh install must not boot silent — the synthesised SFX and music loops (D5) are part of
     * the intended first run, so an unset flag reads as on.
     */
    @Test
    fun allFlagsDefaultToOnWhenUnset() = runTest {
        assertTrue(store.soundEnabled.first())
        assertTrue(store.musicEnabled.first())
        assertTrue(store.vibrateEnabled.first())
        assertEquals(GameSettings(), store.settings.first())
    }

    @Test
    fun settersRoundTrip() = runTest {
        store.setSoundEnabled(false)
        store.setMusicEnabled(false)
        store.setVibrateEnabled(false)

        assertFalse(store.soundEnabled.first())
        assertFalse(store.musicEnabled.first())
        assertFalse(store.vibrateEnabled.first())
        assertEquals(
            GameSettings(soundEnabled = false, musicEnabled = false, vibrateEnabled = false),
            store.settings.first(),
        )

        store.setMusicEnabled(true)
        assertTrue(store.musicEnabled.first())
        assertFalse(store.soundEnabled.first())
    }

    /** Muting the music must not mute the SFX — the Settings screen has three separate toggles. */
    @Test
    fun flagsAreIndependent() = runTest {
        store.setMusicEnabled(false)

        assertFalse(store.musicEnabled.first())
        assertTrue(store.soundEnabled.first())
        assertTrue(store.vibrateEnabled.first())
    }

    /** Pins §10's key spelling, so an existing save's flags keep working across releases. */
    @Test
    fun readsTheSection10KeyNames() = runTest {
        val seeded = FakePreferencesDataStore(
            mutablePreferencesOf(
                booleanPreferencesKey("sound_on") to false,
                booleanPreferencesKey("music_on") to false,
                booleanPreferencesKey("vibrate_on") to false,
            ),
        )

        assertEquals(
            GameSettings(soundEnabled = false, musicEnabled = false, vibrateEnabled = false),
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

        store.setSoundEnabled(false)
        progress.recordLevelResult(levelId = 3, crowns = 2, score = 1_200)

        assertFalse(store.soundEnabled.first())
        assertEquals(2, progress.crownsFor(3).first())
    }
}
