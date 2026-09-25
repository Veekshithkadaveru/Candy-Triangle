package app.krafted.candytriangle.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * §10 persistence keys owned by [ProgressStore].
 *
 * §10 sketches a single `ProgressKeys` object holding progress *and* settings. That is split here:
 * the vibration flag lives in [SettingsKeys] next to [SettingsStore], because the two have
 * different lifetimes — [ProgressStore.resetProgress] wipes everything in this object and must not
 * touch the player's feedback preference. The key string is unchanged from §10, so the two objects
 * can safely address the same preferences file; whether `AppContainer` hands both stores one
 * `DataStore` or two is its call.
 */
internal object ProgressKeys {

    fun levelCrowns(id: Int): Preferences.Key<Int> = intPreferencesKey("lvl_${id}_crowns")

    fun levelBestScore(id: Int): Preferences.Key<Int> = intPreferencesKey("lvl_${id}_best")

    val JAR_GREEN: Preferences.Key<Int> = intPreferencesKey("jar_green")
    val JAR_PURPLE: Preferences.Key<Int> = intPreferencesKey("jar_purple")
    val JAR_PINK: Preferences.Key<Int> = intPreferencesKey("jar_pink")
    val JAR_BLUE: Preferences.Key<Int> = intPreferencesKey("jar_blue")

    val EQUIPPED_BALL_SKIN: Preferences.Key<String> = stringPreferencesKey("ball_skin")
    val EQUIPPED_TRAIL: Preferences.Key<String> = stringPreferencesKey("trail")
    val SEEN_GEM_INTROS: Preferences.Key<Set<String>> = stringSetPreferencesKey("seen_intros")

    fun jar(color: CandyColor): Preferences.Key<Int> = when (color) {
        CandyColor.GREEN -> JAR_GREEN
        CandyColor.PURPLE -> JAR_PURPLE
        CandyColor.PINK -> JAR_PINK
        CandyColor.BLUE -> JAR_BLUE
    }

    private val LEVEL_CROWNS_NAME = Regex("""^lvl_(\d+)_crowns$""")
    private val LEVEL_BEST_NAME = Regex("""^lvl_(\d+)_best$""")

    private val SINGLETONS: Set<String> = setOf(
        JAR_GREEN.name,
        JAR_PURPLE.name,
        JAR_PINK.name,
        JAR_BLUE.name,
        EQUIPPED_BALL_SKIN.name,
        EQUIPPED_TRAIL.name,
        SEEN_GEM_INTROS.name,
    )

    /** Level id behind a `lvl_N_crowns` key, else null. */
    fun crownsLevelId(key: Preferences.Key<*>): Int? =
        LEVEL_CROWNS_NAME.find(key.name)?.groupValues?.get(1)?.toIntOrNull()

    /** Level id behind a `lvl_N_best` key, else null. */
    fun bestScoreLevelId(key: Preferences.Key<*>): Int? =
        LEVEL_BEST_NAME.find(key.name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether [key] is progress rather than settings.
     *
     * Matched by name so [ProgressStore.resetProgress] can drop per-level keys for levels that
     * were never enumerated, while leaving any key it does not recognise (settings today, whatever
     * a later phase adds) alone.
     */
    fun owns(key: Preferences.Key<*>): Boolean =
        key.name in SINGLETONS ||
            crownsLevelId(key) != null ||
            bestScoreLevelId(key) != null
}

/**
 * An immutable snapshot of everything §10 persists about the player, minus settings.
 *
 * Handed to the D2/D3 ViewModels as one value so the jar screen and the map can render from a
 * single `collectAsStateWithLifecycle` rather than juggling eight flows that tear against each
 * other mid-frame.
 */
data class PlayerProgress(
    val crownsByLevel: Map<Int, Int> = emptyMap(),
    val bestScoreByLevel: Map<Int, Int> = emptyMap(),
    val jarCounts: Map<CandyColor, Int> = EMPTY_JARS,
    val equippedBallSkin: BallSkin = BallSkin.DEFAULT,
    val equippedTrail: TrailType = TrailType.NONE,
    val seenGemIntros: Set<GemType> = emptySet(),
) {

    /**
     * Crowns earned across the 40 main levels — the number §6.2's world gates compare against.
     *
     * Sweet Rooms are excluded on purpose. §6.2 budgets "Max Available 30 / 60 / 90", which is
     * exactly 10 / 20 / 30 main levels times 3 crowns, so counting a Sweet Room's crowns here
     * would let a player unlock World 2 without clearing the World 1 levels the gate is about.
     */
    val totalCrowns: Int
        get() = crownsByLevel.entries.sumOf { (id, crowns) ->
            if (LevelIds.isMain(id)) crowns else 0
        }

    fun crownsFor(levelId: Int): Int = crownsByLevel[levelId] ?: 0

    fun bestScoreFor(levelId: Int): Int = bestScoreByLevel[levelId] ?: 0

    fun jarCount(color: CandyColor): Int = jarCounts[color] ?: 0

    /** A level counts as cleared once it has been finished at all, i.e. at least one crown (§5.3). */
    fun isCleared(levelId: Int): Boolean = crownsFor(levelId) >= 1

    /** Highest cleared main level, or 0 on a fresh install — §6.2's "Level 10 Cleared" half. */
    val highestClearedMainLevel: Int
        get() = crownsByLevel.keys
            .filter { LevelIds.isMain(it) && isCleared(it) }
            .maxOrNull() ?: 0

    fun unlockedBallSkins(
        thresholds: List<Int> = JarUnlocks.DEFAULT_TIER_THRESHOLDS,
    ): Set<BallSkin> = JarUnlocks.unlockedSkins(jarCounts, thresholds)

    fun unlockedTrails(
        thresholds: List<Int> = JarUnlocks.DEFAULT_TIER_THRESHOLDS,
    ): Set<TrailType> = JarUnlocks.unlockedTrails(jarCounts, thresholds)

    fun unlockedSweetRooms(
        thresholds: List<Int> = JarUnlocks.DEFAULT_TIER_THRESHOLDS,
    ): Set<Int> = JarUnlocks.unlockedSweetRooms(jarCounts, thresholds)

    companion object {
        /** All four jars at zero — the fresh-install state, and never a partial map. */
        val EMPTY_JARS: Map<CandyColor, Int> = CandyColor.entries.associateWith { 0 }
    }
}

/**
 * Reads and writes the player's §10 progress: crowns, high scores, Candy Jar counts, the equipped
 * ball skin and trail, and which gem intros have been shown.
 *
 * The [dataStore] is injected rather than created from a `Context` via `preferencesDataStore(name)`
 * on purpose: §11's verification suite is JVM-only (no emulator, no Robolectric), and a `Context`
 * delegate would make every rule below untestable. `AppContainer` supplies the real
 * Context-backed instance; tests supply a fake or a temp file.
 *
 * All mutators are single [edit] transactions, so a read-modify-write (the max-merge in
 * [recordLevelResult], the increments in [bankCandies]) cannot interleave with another writer.
 */
class ProgressStore(private val dataStore: DataStore<Preferences>) {

    /**
     * A corrupt or unreadable preferences file surfaces as an [IOException] on the read flow.
     * A player whose save cannot be read should still get a playable app rather than a crash on
     * the map screen, so fall back to a clean slate; anything else is a genuine bug and rethrows.
     */
    private val preferences: Flow<Preferences> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

    /** Everything §10 knows about the player, recomputed on every write. */
    val progress: Flow<PlayerProgress> = preferences
        .map { it.toPlayerProgress() }
        .distinctUntilChanged()

    /** Main-level crowns only — see [PlayerProgress.totalCrowns] for why Sweet Rooms are out. */
    val totalCrowns: Flow<Int> = progress.map { it.totalCrowns }.distinctUntilChanged()

    val jarCounts: Flow<Map<CandyColor, Int>> =
        progress.map { it.jarCounts }.distinctUntilChanged()

    val equippedBallSkin: Flow<BallSkin> =
        progress.map { it.equippedBallSkin }.distinctUntilChanged()

    val equippedTrail: Flow<TrailType> = progress.map { it.equippedTrail }.distinctUntilChanged()

    val seenGemIntros: Flow<Set<GemType>> =
        progress.map { it.seenGemIntros }.distinctUntilChanged()

    /** Crowns stored for [levelId], 0 if never played. */
    fun crownsFor(levelId: Int): Flow<Int> = preferences
        .map { it[ProgressKeys.levelCrowns(levelId)] ?: 0 }
        .distinctUntilChanged()

    /** High score stored for [levelId], 0 if never played. */
    fun bestScoreFor(levelId: Int): Flow<Int> = preferences
        .map { it[ProgressKeys.levelBestScore(levelId)] ?: 0 }
        .distinctUntilChanged()

    /**
     * Files a finished level, **max-merging** both halves.
     *
     * Levels are replayable, and a replay is usually a worse run: the player is grinding jar
     * candies, or showing the board to someone. Neither the crown count nor the high score may
     * regress, or a World gate could close behind a player who already passed it.
     *
     * [crowns] is coerced into 0..[MAX_CROWNS_PER_LEVEL] and [score] to non-negative so a caller
     * bug upstream in `CrownCalculator` cannot poison the save file.
     */
    suspend fun recordLevelResult(levelId: Int, crowns: Int, score: Int) {
        val earnedCrowns = crowns.coerceIn(0, MAX_CROWNS_PER_LEVEL)
        val earnedScore = score.coerceAtLeast(0)
        dataStore.edit { prefs ->
            val crownsKey = ProgressKeys.levelCrowns(levelId)
            val bestKey = ProgressKeys.levelBestScore(levelId)
            prefs[crownsKey] = maxOf(prefs[crownsKey] ?: 0, earnedCrowns)
            prefs[bestKey] = maxOf(prefs[bestKey] ?: 0, earnedScore)
        }
    }

    /**
     * Adds a level's collected candies to the lifetime jars (§7).
     *
     * Called on **failed** levels too — §7 says "win or fail" and `config.json` says
     * `jar.bankCandiesOnFailedLevels = true`. A jar is a lifetime total, so this only ever
     * increments; non-positive entries are ignored rather than allowed to drain a jar.
     */
    suspend fun bankCandies(counts: Map<CandyColor, Int>) {
        val banked = counts.filterValues { it > 0 }
        if (banked.isEmpty()) return
        dataStore.edit { prefs ->
            for ((color, delta) in banked) {
                val key = ProgressKeys.jar(color)
                prefs[key] = (prefs[key] ?: 0) + delta
            }
        }
    }

    /**
     * Equips [skin]. Unlock checking is the caller's job ([JarUnlocks.unlockedSkins]): the picker
     * in D2 only offers unlocked skins, and re-validating here would need the jar thresholds from
     * a config this class deliberately does not depend on.
     */
    suspend fun equipBallSkin(skin: BallSkin) {
        dataStore.edit { it[ProgressKeys.EQUIPPED_BALL_SKIN] = skin.configKey }
    }

    /** Equips [trail]; see [equipBallSkin] on why the unlock check lives in the caller. */
    suspend fun equipTrail(trail: TrailType) {
        dataStore.edit { it[ProgressKeys.EQUIPPED_TRAIL] = trail.configKey }
    }

    /**
     * Remembers that the one-time intro popup for [gem] has been shown (§4.2 intro levels, D4).
     * Idempotent — the set absorbs a repeat.
     */
    suspend fun markGemIntroSeen(gem: GemType) {
        dataStore.edit { prefs ->
            val seen = prefs[ProgressKeys.SEEN_GEM_INTROS] ?: emptySet()
            prefs[ProgressKeys.SEEN_GEM_INTROS] = seen + gem.configKey
        }
    }

    /**
     * Wipes every key this store owns — D4's Progress Reset in Settings.
     *
     * Crowns, high scores, jars, equipped cosmetics and seen intros all go; the vibration flag
     * owned by [SettingsStore] deliberately survives, since resetting progress should not change
     * an accessibility/feedback preference. Keys are matched by
     * §10's name shape, so per-level entries vanish without having to enumerate all 44 levels.
     */
    suspend fun resetProgress() {
        dataStore.edit { prefs ->
            val owned = prefs.asMap().keys.filter(ProgressKeys::owns)
            for (key in owned) prefs -= key
        }
    }

    private fun Preferences.toPlayerProgress(): PlayerProgress {
        val crowns = mutableMapOf<Int, Int>()
        val bestScores = mutableMapOf<Int, Int>()
        for ((key, value) in asMap()) {
            // Jar counts are Ints too, so match on the key shape rather than the value type.
            val stored = value as? Int ?: continue
            val crownsLevel = ProgressKeys.crownsLevelId(key)
            if (crownsLevel != null) {
                crowns[crownsLevel] = stored
                continue
            }
            val bestLevel = ProgressKeys.bestScoreLevelId(key)
            if (bestLevel != null) bestScores[bestLevel] = stored
        }
        return PlayerProgress(
            crownsByLevel = crowns,
            bestScoreByLevel = bestScores,
            jarCounts = CandyColor.entries.associateWith { this[ProgressKeys.jar(it)] ?: 0 },
            // An unrecognised stored string means a downgrade, a hand-edited file or a renamed
            // enum constant. Fall back to the default cosmetic instead of throwing: a cosmetic is
            // never worth a crash loop on launch.
            equippedBallSkin = BallSkin.fromKey(this[ProgressKeys.EQUIPPED_BALL_SKIN])
                ?: BallSkin.DEFAULT,
            equippedTrail = TrailType.fromKey(this[ProgressKeys.EQUIPPED_TRAIL])
                ?: TrailType.NONE,
            seenGemIntros = (this[ProgressKeys.SEEN_GEM_INTROS] ?: emptySet())
                .mapNotNull { GemType.fromKey(it) }
                .toSet(),
        )
    }

    companion object {
        /** §5.3 tops out at three crowns per level. */
        const val MAX_CROWNS_PER_LEVEL: Int = 3
    }
}
