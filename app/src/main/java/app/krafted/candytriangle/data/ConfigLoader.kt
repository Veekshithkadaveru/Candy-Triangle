package app.krafted.candytriangle.data

import app.krafted.candytriangle.level.ConfigJson
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.WorldDef
import app.krafted.candytriangle.level.toDomain
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Loads and caches `assets/config.json` (PRD §3.2-§7).
 *
 * ## Contract
 *
 * [config] **always** returns a usable [GameConfig]. If the asset is absent, unreadable or
 * unparseable it returns [GameConfig.DEFAULTS] — the §3.2-§7 tables encoded in Kotlin — instead of
 * throwing. The game is fully offline with no remote config and no recovery UI, so a bad config
 * edit must cost the tuning, never the launch.
 *
 * ## Caching
 *
 * The parse happens at most once per instance. The fast path is a plain volatile read; the slow
 * path takes a [Mutex] so two concurrent first callers (say, the map screen and the engine warming
 * up together) parse once rather than racing. A coroutine [Mutex] rather than `synchronized` so the
 * lock never blocks a thread — the caller may be on the main dispatcher.
 *
 * ## Derived lookups
 *
 * [worldFor], [spacingFor], [ballsFor], [gem] and [cupSpeedFor] are thin suspending forwarders.
 * The real implementations are pure, synchronous functions on [GameConfig] itself, so a caller that
 * already holds the config (the render loop, a ViewModel that loaded it in `init`) uses those
 * directly and never suspends. These exist for callers that just want one number.
 *
 * Create one per process and share it; `AppContainer` owns the instance.
 */
class ConfigLoader(
    private val assets: AssetSource,
    private val gson: Gson = Gson(),
    private val path: String = DEFAULT_PATH,
) {

    private val mutex = Mutex()

    @Volatile
    private var cached: GameConfig? = null

    /** The parsed config, parsing on first call. Falls back to [GameConfig.DEFAULTS]; never throws. */
    suspend fun config(): GameConfig =
        cached ?: mutex.withLock {
            // Re-check: another coroutine may have finished parsing while we waited for the lock.
            cached ?: parse().also { cached = it }
        }

    /** See [GameConfig.worldFor]. */
    suspend fun worldFor(levelId: Int): WorldDef? = config().worldFor(levelId)

    /** See [GameConfig.spacingFor]. */
    suspend fun spacingFor(levelId: Int): Float = config().spacingFor(levelId)

    /** See [GameConfig.ballsFor]. */
    suspend fun ballsFor(levelId: Int): Int = config().ballsFor(levelId)

    /** See [GameConfig.gem]. */
    suspend fun gem(type: GemType): GemDef? = config().gem(type)

    /** See [GameConfig.cupSpeedFor]. */
    suspend fun cupSpeedFor(world: Int): Float = config().cupSpeedFor(world)

    private fun parse(): GameConfig =
        try {
            val json = assets.readText(path) ?: return GameConfig.DEFAULTS
            gson.fromJson(json, ConfigJson.Root::class.java).toDomain()
        } catch (e: RuntimeException) {
            // Deliberately broad. Gson signals every failure mode with an unchecked exception
            // (JsonSyntaxException, MalformedJsonException wrapped, NumberFormatException on a
            // string where a number was expected), and an AssetSource is free to throw its own.
            // There is no partial-recovery story worth writing: fall back to the whole table.
            GameConfig.DEFAULTS
        }

    companion object {

        /** Path relative to the APK's assets root. */
        const val DEFAULT_PATH: String = "config.json"
    }
}
