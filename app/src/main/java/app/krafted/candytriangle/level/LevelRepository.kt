package app.krafted.candytriangle.level

import android.util.Log
import app.krafted.candytriangle.data.AssetSource
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads and caches `levels.json` (§6.3).
 *
 * Built once at app start and handed to the ViewModels: D3's map needs every level's world and
 * crown thresholds, B4's `LevelSession` needs one level's layout, and §11's verification suite
 * walks all 44. Parsing happens at most once per instance — the first [catalog] call wins and
 * every later call reads the cached result.
 *
 * Nothing here throws. `levels.json` is authored in C1, so an absent asset is the *expected*
 * state today and is reported at info level; a parse failure is reported at warn level. Both
 * yield [LevelCatalog.EMPTY], because a zero-crash offline game (§13) has to survive one bad
 * hand-authored file by showing an empty map rather than dying on the splash screen.
 *
 * @param assets where to read the JSON from — the APK's assets in production, a fixture string
 *   in the JVM tests (§11 runs without Robolectric).
 * @param gson injected so a caller can share one configured instance; the default is fine.
 * @param assetPath overridable for tests and for any future per-variant level pack.
 */
class LevelRepository(
    private val assets: AssetSource,
    private val gson: Gson = Gson(),
    private val assetPath: String = DEFAULT_ASSET_PATH,
) {

    /**
     * Guards the one-shot parse. A [Mutex] rather than `synchronized` because [catalog] is a
     * suspend function and several ViewModels may race for the first read on different
     * dispatchers; blocking a dispatcher thread there would be the wrong trade.
     */
    private val mutex = Mutex()

    @Volatile
    private var cached: LevelCatalog? = null

    /**
     * The parsed catalogue, parsing on first call and caching thereafter.
     *
     * Double-checked: the fast path never touches the mutex once the cache is warm, and the slow
     * path re-reads [cached] inside the lock so a queued caller does not parse a second time.
     */
    suspend fun catalog(): LevelCatalog =
        cached ?: mutex.withLock { cached ?: load().also { cached = it } }

    /** One level by its §10 id, or `null` when the catalogue has no such level. */
    suspend fun level(id: Int): LevelDef? = catalog().level(id)

    /** Main levels, ids 1..40, ordered (§6.1). */
    suspend fun mainLevels(): List<LevelDef> = catalog().mainLevels()

    /** Bonus Sweet Rooms, ids 101..104, ordered (§7). */
    suspend fun sweetRooms(): List<LevelDef> = catalog().sweetRooms()

    /** Every level of one panorama world, ordered (§6.1). */
    suspend fun levelsInWorld(world: Int): List<LevelDef> = catalog().levelsInWorld(world)

    private suspend fun load(): LevelCatalog = withContext(Dispatchers.Default) {
        // Everything below is plain blocking code — AssetSource.readText is not suspending — so
        // no cancellation can originate inside the catch, which is what makes the broad
        // RuntimeException catch safe (CancellationException is an IllegalStateException).
        val json = assets.readText(assetPath)
        if (json == null) {
            Log.i(LEVEL_LOG_TAG, "No '$assetPath' asset; starting with an empty level catalogue")
            return@withContext LevelCatalog.EMPTY
        }
        try {
            parse(json)
        } catch (e: RuntimeException) {
            // Gson raises JsonParseException / IllegalStateException / NumberFormatException
            // depending on how the JSON is wrong. None of them should reach the UI.
            Log.w(LEVEL_LOG_TAG, "Could not parse '$assetPath'; using an empty catalogue", e)
            LevelCatalog.EMPTY
        }
    }

    /**
     * Binds JSON to DTOs, then maps to the domain.
     *
     * Accepts both documented top-level shapes: the canonical
     * `{ "schemaVersion": 1, "levels": [...] }` object and a bare `[...]` array. Branching on the
     * already-parsed root element costs nothing — `fromJson` binds straight off the element — and
     * spares C1 a whole class of "wrong wrapper" mistakes.
     */
    private fun parse(json: String): LevelCatalog {
        val root = JsonParser.parseString(json)
        val file = when {
            root.isJsonArray ->
                LevelFileDto(levels = gson.fromJson(root, Array<LevelDto?>::class.java)?.toList())
            root.isJsonObject ->
                gson.fromJson(root, LevelFileDto::class.java)
            else -> null
        }
        if (file == null) {
            Log.w(LEVEL_LOG_TAG, "'$assetPath' is neither a levels object nor a levels array")
            return LevelCatalog.EMPTY
        }

        val catalog = file.toDomain()
        if (catalog.schemaVersion != LevelDefaults.SCHEMA_VERSION) {
            // Not fatal: unknown fields are ignored and missing ones default, so an older or
            // newer file still loads. Worth a breadcrumb when a level looks wrong in the field.
            Log.w(
                LEVEL_LOG_TAG,
                "'$assetPath' declares schemaVersion ${catalog.schemaVersion}, " +
                    "expected ${LevelDefaults.SCHEMA_VERSION}",
            )
        }
        Log.i(LEVEL_LOG_TAG, "Loaded ${catalog.levels.size} levels from '$assetPath'")
        return catalog
    }

    companion object {

        /** Path of the level catalogue inside the APK's assets (§6.3). */
        const val DEFAULT_ASSET_PATH = "levels.json"
    }
}
