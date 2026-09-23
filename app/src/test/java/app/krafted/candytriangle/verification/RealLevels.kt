package app.krafted.candytriangle.verification

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.LevelCatalog
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.level.LevelSession
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import java.io.File

/**
 * The shipped data under test: `src/main/assets/config.json` and `src/main/assets/levels.json`,
 * read from disk through a file-backed [AssetSource] and loaded by the production [ConfigLoader]
 * and [LevelRepository] — the same code path the app runs, minus the `AssetManager`.
 *
 * Both files are **required**: a missing `levels.json` would make the production repository return
 * an empty catalogue, and every per-level suite would then pass vacuously. [catalog] throws an
 * [AssertionError] instead, so every C2 test fails loudly until the file exists.
 *
 * [raw] parses `levels.json` a second time with Gson's tree model, independent of the tolerant
 * DTO mapper, so [LevelCatalogTest] can prove the mapper dropped nothing (A2 note 7: malformed
 * entries are skipped with a `Log.w`, which is silent under the JVM stub).
 */
object RealLevels {

    /** `src/main/assets`, resolved from the Gradle test working directory (`app/`) or the root. */
    val assetsDir: File by lazy {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("../app/src/main/assets"),
        )
        candidates.firstOrNull { File(it, "config.json").isFile }?.absoluteFile
            ?: throw AssertionError(
                "Could not find src/main/assets/config.json from working dir " +
                    "${File(".").absolutePath}; tried ${candidates.map { it.absolutePath }}",
            )
    }

    val levelsFile: File get() = File(assetsDir, LevelRepository.DEFAULT_ASSET_PATH)

    val configFile: File get() = File(assetsDir, ConfigLoader.DEFAULT_PATH)

    /** Reads `assets/<path>` from disk; `null` for an absent file, like `AndroidAssetSource`. */
    val assets: AssetSource = AssetSource { path ->
        File(assetsDir, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    val config: GameConfig by lazy {
        if (!configFile.isFile) throw AssertionError("config.json is missing at $configFile")
        runBlocking { ConfigLoader(assets).config() }
    }

    /** The raw text of the shipped `levels.json`; fails (never skips) when it does not exist. */
    val levelsJson: String by lazy {
        if (!levelsFile.isFile) {
            throw AssertionError(
                "levels.json is missing at $levelsFile — the §11 suite runs against the shipped " +
                    "catalogue and must not pass vacuously",
            )
        }
        levelsFile.readText(Charsets.UTF_8)
    }

    val catalog: LevelCatalog by lazy {
        val json = levelsJson
        val loaded = runBlocking { LevelRepository(AssetSource { json }).catalog() }
        if (loaded.levels.isEmpty()) {
            throw AssertionError("levels.json at $levelsFile loaded as an empty catalogue")
        }
        loaded
    }

    /** Every shipped level, ascending by id (main 1..40, then Sweet Rooms 101..104). */
    val levels: List<LevelDef> get() = catalog.levels

    /** The raw `levels` array entries of `levels.json`, as Gson tree objects. */
    val raw: List<JsonElement> by lazy {
        val root = JsonParser.parseString(levelsJson)
        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("levels")?.isJsonArray == true ->
                root.asJsonObject.getAsJsonArray("levels")
            else -> throw AssertionError("levels.json is neither an object with 'levels' nor an array")
        }
        array.toList()
    }

    fun board(level: LevelDef): LevelBoard = LevelBoard.create(level, config)

    /** "L7" for a main level, "B2 (102)" for a Sweet Room. */
    fun label(level: LevelDef): String =
        if (level.isBonus) "${level.code ?: "B?"} (${level.id})" else "L${level.id}"

    // -- raw JSON helpers ---------------------------------------------------------------------

    fun JsonElement?.obj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null

    fun JsonElement?.arr(): JsonArray? = if (this != null && isJsonArray) asJsonArray else null

    fun JsonObject?.arrSize(key: String): Int = this?.get(key).arr()?.size() ?: 0

    fun JsonObject?.intOrNull(key: String): Int? {
        val e = this?.get(key) ?: return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null
    }

    fun JsonObject?.floatOrNull(key: String): Float? {
        val e = this?.get(key) ?: return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asFloat else null
    }
}

/**
 * Collects per-level failures so a suite reports **every** offending level in one message instead
 * of stopping at the first.
 */
class LevelFailures(private val what: String) {

    private val byLevel = LinkedHashMap<String, MutableList<String>>()

    val isEmpty: Boolean get() = byLevel.isEmpty()

    fun add(level: LevelDef, message: String) = add(RealLevels.label(level), message)

    fun add(label: String, message: String) {
        byLevel.getOrPut(label) { ArrayList() } += message
    }

    inline fun check(level: LevelDef, condition: Boolean, message: () -> String) {
        if (!condition) add(level, message())
    }

    fun assertNone() {
        if (byLevel.isEmpty()) return
        val sb = StringBuilder()
        val total = byLevel.values.sumOf { it.size }
        sb.append("$what: $total violation(s) in ${byLevel.size} level(s) ${byLevel.keys}\n")
        for ((label, messages) in byLevel) {
            sb.append("  $label (${messages.size}):\n")
            for (m in messages.take(MAX_PER_LEVEL)) sb.append("    - ").append(m).append('\n')
            if (messages.size > MAX_PER_LEVEL) {
                sb.append("    - ... and ${messages.size - MAX_PER_LEVEL} more\n")
            }
        }
        fail(sb.toString())
    }

    private companion object {
        const val MAX_PER_LEVEL = 10
    }
}

/**
 * Records every [GameEvent] a [LevelSession] emits, synchronously and in order.
 *
 * `LevelSession.events` is a replay-less `SharedFlow` fed by `tryEmit`, so an event is only seen
 * by a subscriber that is already collecting. The collector is started `UNDISPATCHED` on
 * [Dispatchers.Unconfined]: it subscribes before [start] returns, and each `tryEmit` resumes it
 * inline on the stepping thread, so the list is complete the moment a step returns and the
 * 64-slot buffer never fills.
 */
class EventRecorder private constructor(private val job: Job, val events: List<GameEvent>) {

    fun stop() = job.cancel()

    companion object {
        fun start(session: LevelSession): EventRecorder {
            val list = ArrayList<GameEvent>()
            val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { list += it }
            }
            return EventRecorder(job, list)
        }
    }
}

fun degrees(deg: Double): Float = Math.toRadians(deg).toFloat()
