package app.krafted.candytriangle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.krafted.candytriangle.data.AndroidAssetSource
import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.data.SettingsStore
import app.krafted.candytriangle.level.LevelRepository
import com.google.gson.Gson

/**
 * The single DataStore Preferences file backing PRD §10.
 *
 * [preferencesDataStore] writes to `filesDir/datastore/candy_progress.preferences_pb`, which is
 * precisely the path both `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
 * opt into, so progress survives a reinstall. Changing this name is fine; moving it out of
 * `filesDir/datastore` would silently drop the app out of the backup set.
 *
 * It is a Context extension rather than a field because the delegate enforces process-wide
 * single-instantiation per file - constructing two DataStores over one file throws at runtime.
 */
private val Context.candyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "candy_progress",
)

/**
 * Manual service locator for the app-scoped singletons built in phase A2.
 *
 * Deliberately not Hilt: this module has no Kotlin Android plugin and therefore no KSP or kapt,
 * and a single-module fully-offline game does not earn an annotation processor. Everything here
 * takes its collaborators by constructor, so the JVM verification suite (§11) builds these same
 * types directly with fakes and never needs this class or an emulator.
 *
 * Every property is `lazy`: `Application.onCreate` runs on the main thread before the first frame,
 * so nothing that touches disk should be built eagerly. The first `config()` or `catalog()` call
 * does the parse, off the main thread, and caches it.
 */
class AppContainer(private val context: Context) {

    /** Reads JSON out of the APK. Phase C1 adds `levels.json` beside `config.json`. */
    val assetSource: AssetSource by lazy { AndroidAssetSource(context.assets) }

    /** One Gson for both loaders - it is thread-safe and reflection caching is worth sharing. */
    private val gson: Gson by lazy { Gson() }

    /** Physics, board, scoring and world tables from §3.2-§7. */
    val configLoader: ConfigLoader by lazy { ConfigLoader(assetSource, gson) }

    /**
     * The §6.3 level catalogue. Returns an empty catalogue until C1 authors `levels.json`;
     * that is expected, not an error.
     */
    val levelRepository: LevelRepository by lazy { LevelRepository(assetSource, gson) }

    /**
     * [ProgressStore] and [SettingsStore] intentionally share one [DataStore]: their §10 key
     * names are disjoint, and DataStore rejects a second instance over the same file anyway.
     * `ProgressStore.resetProgress()` clears only the keys it owns, so a Settings screen
     * "Reset Progress" leaves the audio preferences alone.
     */
    val progressStore: ProgressStore by lazy { ProgressStore(context.candyDataStore) }

    val settingsStore: SettingsStore by lazy { SettingsStore(context.candyDataStore) }
}
