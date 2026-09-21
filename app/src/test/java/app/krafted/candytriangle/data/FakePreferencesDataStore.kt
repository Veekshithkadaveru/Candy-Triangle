package app.krafted.candytriangle.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory `DataStore<Preferences>` for the store logic tests.
 *
 * The interface is only a read flow plus a transactional write, so a `MutableStateFlow` behind a
 * `Mutex` reproduces it exactly — including the serialisation that [ProgressStore]'s max-merge and
 * increment rules rely on. No files, no dispatchers, no virtual-time surprises.
 *
 * Persistence itself is *not* what this proves; `ProgressStoreTest.survivesStoreRebuildOnRealFile`
 * covers that against a real temp-file DataStore.
 */
class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)
    private val writeLock = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences,
    ): Preferences = writeLock.withLock {
        // `edit { }` hands back the live MutablePreferences it just mutated; the real store
        // freezes it before publishing, so copy here for the same guarantee.
        val updated = transform(state.value).toPreferences()
        state.value = updated
        updated
    }
}
