package app.krafted.candytriangle.level

import android.util.Log
import com.google.gson.annotations.SerializedName

/**
 * The `levels.json` data model (§6.3) and the Gson DTOs that parse it.
 *
 * ### File shape
 *
 * The canonical top-level shape is an object carrying a version and the level array:
 *
 * ```json
 * { "schemaVersion": 1, "levels": [ { "id": 1, ... }, { "id": 2, ... } ] }
 * ```
 *
 * A bare top-level array (`[ { "id": 1, ... } ]`) is also accepted — it costs one branch in
 * [LevelRepository] and keeps hand-edited scratch files loadable — but C1 should author the
 * object form so the version field has somewhere to live.
 *
 * ### Level entry
 *
 * ```json
 * {
 *   "id": 14,
 *   "code": "B1",
 *   "world": 2,
 *   "balls": 10,
 *   "crowns": [2, 4],
 *   "layout": {
 *     "spacing": 80,
 *     "pattern": "FULL",
 *     "holes": [[3, 2], [3, 3], [6, 1]],
 *     "clusters": [ { "row": 4, "col": 1, "rows": 3, "cols": 5 } ],
 *     "movingRows": [ { "row": 7, "amplitude": 40, "periodSeconds": 3.0 } ]
 *   },
 *   "candies": {
 *     "seed": 1407,
 *     "count": 30,
 *     "weights": { "GREEN": 1, "PURPLE": 1, "PINK": 2, "BLUE": 1 },
 *     "fixed": [ { "color": "PINK", "row": 9, "col": 4 } ]
 *   },
 *   "gems": [ { "type": "BLAST", "row": 6, "col": 4 } ],
 *   "cup": { "speed": 260 },
 *   "objectives": [ { "type": "COLLECT_CANDY", "color": "PINK", "count": 14 } ]
 * }
 * ```
 *
 * ### Architecture: DTO to domain
 *
 * Gson binds into the `*Dto` classes at the bottom of this file, whose fields are all nullable
 * with `null` defaults (a missing JSON key must land as `null`, never as a lying non-null Kotlin
 * field — Gson constructs through `Unsafe` and does not honour Kotlin's null checks). The
 * hand-written `toDomain` mappers then apply defaults and produce the non-null public models
 * above. There is deliberately **no** custom `TypeAdapter` or `JsonDeserializer`: the malformed
 * cases need to be logged with the owning level id, which an adapter cannot see.
 *
 * ### Tolerance rules
 *
 * The game ships fully offline against 44 hand-authored levels, so one typo must never brick the
 * app (§13, zero crash tolerance). Two consistent rules apply:
 *
 * 1. **List entries** with an unrecognised enum key or a missing required field are *skipped*
 *    with a warning — one bad gem drops that gem, not the level.
 * 2. **Scalar enums and numbers** fall back to a documented default with a warning, because there
 *    is no entry to drop.
 *
 * A level with no usable `id` is the one exception: it is dropped whole, since §10 keys all
 * progress by id and an id-less level can neither be saved nor navigated to.
 */

/** Shared logcat tag for every level-loading diagnostic, so one filter shows the whole load. */
internal const val LEVEL_LOG_TAG = "Levels"

/** Levels per panorama world (§6.1: 1-10, 11-20, 21-30, 31-40). */
private const val LEVELS_PER_WORLD = 10

/**
 * Fallbacks applied by the `toDomain` mappers when JSON omits a value.
 *
 * Values mirror `assets/config.json`; that file is the live tuning surface, these are only the
 * "author forgot the key" backstop so a level still loads and plays.
 */
object LevelDefaults {

    /** Current `levels.json` schema version. Bump when a field changes meaning, not when adding. */
    const val SCHEMA_VERSION = 1

    /** Lattice density band from §3.1 (`config.board.lattice.spacingMin` / `spacingMax`). */
    const val MIN_SPACING = 66f
    const val MAX_SPACING = 90f

    /** §6.3's own example spacing; mid-band, valid in any world. */
    const val SPACING = 80f

    /** `config.physics.movingPegs.defaultAmplitude` — the `A` in `x(t) = x0 + A sin(2 PI t / T)`. */
    const val MOVING_ROW_AMPLITUDE = 40f

    /** `config.physics.movingPegs.defaultPeriodSeconds` — the `T` in the same formula. */
    const val MOVING_ROW_PERIOD_SECONDS = 3f

    /** World 2's cup speed (§6.1); the middle of `config.cup.speedMin`..`speedMax`. */
    const val CUP_SPEED = 260f

    /** Ball count for a main level when omitted — the midpoint of §6.1's 12-to-8 ramp. */
    const val MAIN_BALLS = 10

    /** `config.jar.sweetRoomBalls` — Sweet Rooms B1-B4 ship 15 balls (§7). */
    const val SWEET_ROOM_BALLS = 15

    /** `world` for a level that belongs to no panorama world — the Sweet Rooms (§6.1 lists 1-4). */
    const val BONUS_WORLD = 0

    /** Equal weighting across all four colours (§4.1) when `candies.weights` is absent or unusable. */
    val UNIFORM_CANDY_WEIGHTS: Map<CandyColor, Int> = CandyColor.entries.associateWith { 1 }
}

/**
 * A node of the triangular peg lattice (§3.1), addressed by lattice row and column.
 *
 * Rows count down from the apex. `LayoutBuilder` (B2) turns a [RowCol] into board units using the
 * level's `spacing`; nothing here knows about pixels.
 */
data class RowCol(val row: Int, val col: Int)

/**
 * One playable board: a main level (id 1-40) or a Sweet Room (id 101-104).
 *
 * @property id the single Int keyspace shared with §10's `levelCrowns(id)` DataStore keys.
 * @property code display-only spelling for the Sweet Rooms, `"B1"`..`"B4"`; `null` for main levels.
 * @property world panorama world 1-4 (§6.1), or [LevelDefaults.BONUS_WORLD] for a Sweet Room.
 * @property isBonus derived from [id] via [LevelIds] — the id range is authoritative, so that this
 *   flag can never disagree with what [LevelCatalog.sweetRooms] returns.
 * @property balls balls the player starts with.
 * @property crowns `[twoCrownBallsRemaining, threeCrownBallsRemaining]` per §5.3. Always exactly
 *   two entries after mapping; see [twoCrownBalls] / [threeCrownBalls].
 */
data class LevelDef(
    val id: Int,
    val code: String?,
    val world: Int,
    val isBonus: Boolean,
    val balls: Int,
    val crowns: List<Int>,
    val layout: LayoutDef,
    val candies: CandyPlacementDef,
    val gems: List<GemPlacementDef>,
    val cup: CupDef,
    val objectives: List<ObjectiveDef>,
) {

    /** Balls that must remain on completion for the 2nd crown (§5.3, `crowns[0]`). */
    val twoCrownBalls: Int get() = crowns[0]

    /** Balls that must remain on completion for the 3rd crown (§5.3, `crowns[1]`). */
    val threeCrownBalls: Int get() = crowns[1]
}

/**
 * Peg-lattice shape for one level (§6.3 `layout`).
 *
 * @property spacing the lattice density `d` in board units, clamped to §3.1's 66..90 band — every
 *   §3.4 clearance guarantee is stated "Always satisfied (Min d = 66)", so a typo'd `8` here would
 *   quietly invalidate the physics' anti-overlap argument rather than just look wrong.
 * @property pattern which lattice nodes carry pegs before [holes] and [clusters] apply.
 * @property holes nodes to leave empty under [LayoutPattern.FULL] / [LayoutPattern.SPARSE]. JSON
 *   spells these as `[[row, col], ...]`; malformed inner arrays are skipped, not fatal.
 * @property clusters the node sets that carry pegs under [LayoutPattern.CLUSTERS].
 * @property movingRows rows that oscillate horizontally (§3.3, World 3+).
 */
data class LayoutDef(
    val spacing: Float,
    val pattern: LayoutPattern,
    val holes: List<RowCol>,
    val clusters: List<ClusterDef>,
    val movingRows: List<MovingRowDef>,
)

/**
 * A named set of lattice nodes, used by [LayoutPattern.CLUSTERS] layouts (§6.1, World 2+).
 *
 * Two authoring forms, because 40 hand-written levels need the short one and irregular blobs need
 * the long one:
 *
 * ```json
 * { "row": 4, "col": 1, "rows": 3, "cols": 5 }
 * { "nodes": [[9, 2], [9, 3], [10, 2]] }
 * ```
 *
 * [nodes] wins when non-empty; otherwise the cluster is the `rows` x `cols` block anchored at
 * (`row`, `col`). Use [resolveNodes] rather than reading the fields — it collapses both forms.
 */
data class ClusterDef(
    val row: Int,
    val col: Int,
    val rows: Int,
    val cols: Int,
    val nodes: List<RowCol>,
) {

    /** Every lattice node this cluster covers, in row-major order. */
    fun resolveNodes(): List<RowCol> =
        nodes.ifEmpty {
            (0 until rows).flatMap { r ->
                (0 until cols).map { c -> RowCol(row + r, col + c) }
            }
        }
}

/**
 * A horizontally oscillating peg row (§3.3, introduced in World 3).
 *
 * Pegs in [row] follow `x(t) = x0 + amplitude * sin(2 PI t / periodSeconds)`, and transfer 50% of
 * their instantaneous horizontal velocity to a ball on contact. [periodSeconds] is guaranteed
 * strictly positive so that `2 PI t / T` can never divide by zero in the physics step.
 */
data class MovingRowDef(
    val row: Int,
    val amplitude: Float,
    val periodSeconds: Float,
)

/**
 * Seeded candy placement for one level (§4.1, §6.3 `candies`).
 *
 * @property seed the RNG seed. Placement must stay deterministic — §11's determinism test replays
 *   a level 100 times and compares trajectories — so this is part of the level, not of the run.
 * @property count how many candies the generator places in lattice gap centres, over and above
 *   [fixed].
 * @property weights relative colour frequency. Guaranteed non-empty with a positive total, so the
 *   weighted picker can never divide by zero; a level that omits it gets
 *   [LevelDefaults.UNIFORM_CANDY_WEIGHTS]. A zero weight is legal and means "never pick this
 *   colour" — that is how a Sweet Room floods the board with a single colour (§7).
 * @property fixed candies pinned to exact lattice gaps, placed before the seeded ones. Used where
 *   an objective needs a guaranteed reachable candy.
 */
data class CandyPlacementDef(
    val seed: Long,
    val count: Int,
    val weights: Map<CandyColor, Int>,
    val fixed: List<FixedCandy>,
)

/** A candy pinned to a lattice gap: `{ "color": "PINK", "row": 9, "col": 4 }`. */
data class FixedCandy(
    val color: CandyColor,
    val row: Int,
    val col: Int,
)

/**
 * A gem replacing a peg at a lattice node (§4.2): `{ "type": "BLAST", "row": 6, "col": 4 }`.
 *
 * `LayoutBuilder` removes pegs adjacent to a gem when `d < 73` per §3.4, so authors do not need to
 * carve holes around one by hand.
 */
data class GemPlacementDef(
    val type: GemType,
    val row: Int,
    val col: Int,
)

/** Candy Cup settings for one level (§6.3 `cup`). [speed] is the slide speed in units/second. */
data class CupDef(val speed: Float)

/**
 * One win condition, tracked by `ObjectiveTracker` (B4). A level may carry several — World 4 uses
 * two (§6.1) — and all must complete for the 1st crown (§5.3).
 *
 * The nullable fields are a tagged union keyed by [type]; exactly which apply:
 *
 * | [type]           | fields used                                                        |
 * |------------------|--------------------------------------------------------------------|
 * | `COLLECT_CANDY`  | [count] (required), [color] (optional — `null` means any colour)    |
 * | `SCORE`          | [score] (required)                                                  |
 * | `COLLECT_GEM`    | [count] (required), [gem] (optional — `null` means any gem type)    |
 * | `CLEAR_COLOR`    | [color] (required); every candy of that colour must be cleared      |
 * | `CHAIN`          | [chain] (required — chain length to reach), [count] (optional times)|
 * | `CUP`            | [count] (required — cup catches)                                    |
 *
 * An entry missing its required field is dropped during mapping, so anything that survives here
 * has the fields its [type] needs.
 */
data class ObjectiveDef(
    val type: ObjectiveType,
    val color: CandyColor?,
    val gem: GemType?,
    val count: Int?,
    val score: Int?,
    val chain: Int?,
)

/**
 * The whole parsed `levels.json`: 40 main levels plus 4 Sweet Rooms once C1 lands.
 *
 * [levels] is sorted by [LevelDef.id] and carries no duplicate ids, so every accessor below is
 * already in map order. Partitioning uses [LevelIds] rather than [LevelDef.isBonus] so a level's
 * home is decided purely by its id.
 */
data class LevelCatalog(
    val schemaVersion: Int,
    val levels: List<LevelDef>,
) {

    /** Levels keyed by id — the lookup D3's map and B4's session both want. */
    val byId: Map<Int, LevelDef> = levels.associateBy { it.id }

    fun level(id: Int): LevelDef? = byId[id]

    /** Main levels, ids 1..40, ordered. */
    fun mainLevels(): List<LevelDef> = levels.filter { LevelIds.isMain(it.id) }

    /** Bonus Sweet Rooms, ids 101..104, ordered. */
    fun sweetRooms(): List<LevelDef> = levels.filter { LevelIds.isBonus(it.id) }

    /** Levels of one panorama world, ordered (§6.1). */
    fun levelsInWorld(world: Int): List<LevelDef> = levels.filter { it.world == world }

    companion object {

        /** The catalogue returned when `levels.json` is absent or unparseable. */
        val EMPTY = LevelCatalog(LevelDefaults.SCHEMA_VERSION, emptyList())
    }
}

// --------------------------------------------------------------------------------------------
// Gson DTOs.
//
// Every field is nullable with a `null` default. That is load-bearing twice over: it lets the
// mappers tell "absent" from "authored as 0", and — because all parameters have defaults — Kotlin
// emits a no-arg constructor for Gson to use, so a missing key keeps its default instead of
// leaving an uninitialised field. @SerializedName is explicit on every field so the DTOs survive
// a future R8 pass that renames them.
// --------------------------------------------------------------------------------------------

internal data class LevelFileDto(
    @SerializedName("schemaVersion") val schemaVersion: Int? = null,
    @SerializedName("levels") val levels: List<LevelDto?>? = null,
)

internal data class LevelDto(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("world") val world: Int? = null,
    @SerializedName("isBonus") val isBonus: Boolean? = null,
    @SerializedName("balls") val balls: Int? = null,
    @SerializedName("crowns") val crowns: List<Int?>? = null,
    @SerializedName("layout") val layout: LayoutDto? = null,
    @SerializedName("candies") val candies: CandyPlacementDto? = null,
    @SerializedName("gems") val gems: List<GemPlacementDto?>? = null,
    @SerializedName("cup") val cup: LevelCupDto? = null,
    @SerializedName("objectives") val objectives: List<ObjectiveDto?>? = null,
)

internal data class LayoutDto(
    @SerializedName("spacing") val spacing: Float? = null,
    @SerializedName("pattern") val pattern: String? = null,
    @SerializedName("holes") val holes: List<List<Int?>?>? = null,
    @SerializedName("clusters") val clusters: List<ClusterDto?>? = null,
    @SerializedName("movingRows") val movingRows: List<MovingRowDto?>? = null,
)

internal data class ClusterDto(
    @SerializedName("row") val row: Int? = null,
    @SerializedName("col") val col: Int? = null,
    @SerializedName("rows") val rows: Int? = null,
    @SerializedName("cols") val cols: Int? = null,
    @SerializedName("nodes") val nodes: List<List<Int?>?>? = null,
)

internal data class MovingRowDto(
    @SerializedName("row") val row: Int? = null,
    @SerializedName("amplitude") val amplitude: Float? = null,
    @SerializedName("periodSeconds") val periodSeconds: Float? = null,
)

internal data class CandyPlacementDto(
    @SerializedName("seed") val seed: Long? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("weights") val weights: Map<String, Int?>? = null,
    @SerializedName("fixed") val fixed: List<FixedCandyDto?>? = null,
)

internal data class FixedCandyDto(
    @SerializedName("color") val color: String? = null,
    @SerializedName("row") val row: Int? = null,
    @SerializedName("col") val col: Int? = null,
)

internal data class GemPlacementDto(
    @SerializedName("type") val type: String? = null,
    @SerializedName("row") val row: Int? = null,
    @SerializedName("col") val col: Int? = null,
)

internal data class LevelCupDto(
    @SerializedName("speed") val speed: Float? = null,
)

internal data class ObjectiveDto(
    @SerializedName("type") val type: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("gem") val gem: String? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("score") val score: Int? = null,
    @SerializedName("chain") val chain: Int? = null,
)

// --------------------------------------------------------------------------------------------
// DTO to domain mappers.
// --------------------------------------------------------------------------------------------

/** Maps a parsed file, dropping unusable levels and duplicate ids (first authored id wins). */
internal fun LevelFileDto.toDomain(): LevelCatalog {
    val byId = LinkedHashMap<Int, LevelDef>()
    levels.orEmpty().forEach { dto ->
        val level = dto?.toDomain() ?: return@forEach
        if (byId.containsKey(level.id)) {
            // Silently keeping the last would make a copy-paste slip in a 44-level file behave
            // as a missing level somewhere else entirely.
            Log.w(LEVEL_LOG_TAG, "Duplicate level id ${level.id}; keeping the first definition")
            return@forEach
        }
        byId[level.id] = level
    }
    return LevelCatalog(
        schemaVersion = schemaVersion ?: LevelDefaults.SCHEMA_VERSION,
        levels = byId.values.sortedBy { it.id },
    )
}

/** Maps one level, or `null` when it has no usable id and therefore no place in §10's keyspace. */
internal fun LevelDto.toDomain(): LevelDef? {
    val levelId = id
    if (levelId == null) {
        Log.w(LEVEL_LOG_TAG, "Skipping level with no id: $this")
        return null
    }
    if (!LevelIds.isMain(levelId) && !LevelIds.isBonus(levelId)) {
        // Not fatal — level(id) still resolves it — but it will appear in neither mainLevels()
        // nor sweetRooms(), which is almost never what an author meant.
        Log.w(
            LEVEL_LOG_TAG,
            "Level $levelId is outside both id ranges " +
                "(${LevelIds.MAIN_FIRST}..${LevelIds.MAIN_LAST}, " +
                "${LevelIds.BONUS_FIRST}..${LevelIds.BONUS_LAST})",
        )
    }

    val bonus = LevelIds.isBonus(levelId)
    if (isBonus != null && isBonus != bonus) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId declares isBonus=$isBonus; the id range wins ($bonus)")
    }

    val ballCount = (balls ?: if (bonus) LevelDefaults.SWEET_ROOM_BALLS else LevelDefaults.MAIN_BALLS)
        .coerceAtLeast(1)

    return LevelDef(
        id = levelId,
        code = code?.takeIf { it.isNotBlank() } ?: LevelIds.bonusCode(levelId),
        world = world ?: defaultWorld(levelId),
        isBonus = bonus,
        balls = ballCount,
        crowns = crowns.toCrownThresholds(levelId, ballCount),
        layout = layout.toDomain(levelId),
        candies = candies.toDomain(levelId),
        gems = gems.orEmpty().mapNotNull { it?.toDomain(levelId) },
        cup = cup.toDomain(levelId),
        objectives = objectives.orEmpty().mapNotNull { it?.toDomain(levelId) },
    )
}

/** §6.1 packs ten levels per world; a Sweet Room or a stray id belongs to no panorama world. */
private fun defaultWorld(id: Int): Int =
    if (LevelIds.isMain(id)) {
        ((id - LevelIds.MAIN_FIRST) / LEVELS_PER_WORLD) + 1
    } else {
        LevelDefaults.BONUS_WORLD
    }

/**
 * Normalises §5.3's `crowns` to exactly two non-negative thresholds.
 *
 * A missing entry is padded with [balls] — the strictest threshold that is still a real, printable
 * number. Padding with 0 would hand out three crowns for any clear, which is exactly the failure a
 * malformed level should not cause.
 */
private fun List<Int?>?.toCrownThresholds(levelId: Int, balls: Int): List<Int> {
    if (this == null || size < 2 || any { it == null }) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: crowns $this is not [two, three]; padding with $balls")
    }
    val two = this?.getOrNull(0) ?: balls
    val three = this?.getOrNull(1) ?: balls
    return listOf(two.coerceAtLeast(0), three.coerceAtLeast(0))
}

private fun LayoutDto?.toDomain(levelId: Int): LayoutDef {
    val dto = this
    val patternKey = dto?.pattern
    val pattern = LayoutPattern.fromKey(patternKey)
    if (pattern == null && patternKey != null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: unknown layout pattern '$patternKey'; using FULL")
    }
    return LayoutDef(
        spacing = (dto?.spacing ?: LevelDefaults.SPACING)
            .coerceIn(LevelDefaults.MIN_SPACING, LevelDefaults.MAX_SPACING),
        pattern = pattern ?: LayoutPattern.FULL,
        holes = dto?.holes.toRowCols(levelId, "hole"),
        clusters = dto?.clusters.orEmpty().mapNotNull { it?.toDomain(levelId) },
        movingRows = dto?.movingRows.orEmpty().mapNotNull { it?.toDomain(levelId) },
    )
}

private fun ClusterDto?.toDomain(levelId: Int): ClusterDef? {
    val nodes = this?.nodes.toRowCols(levelId, "cluster node")
    val row = this?.row
    val col = this?.col
    if (nodes.isEmpty() && (row == null || col == null)) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping cluster with neither nodes nor anchor: $this")
        return null
    }
    return ClusterDef(
        row = row ?: 0,
        col = col ?: 0,
        rows = (this?.rows ?: 1).coerceAtLeast(1),
        cols = (this?.cols ?: 1).coerceAtLeast(1),
        nodes = nodes,
    )
}

private fun MovingRowDto.toDomain(levelId: Int): MovingRowDef? {
    val rowIndex = row
    if (rowIndex == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping moving row with no row: $this")
        return null
    }
    val period = periodSeconds ?: LevelDefaults.MOVING_ROW_PERIOD_SECONDS
    if (period <= 0f) {
        // T is a divisor in x(t) = x0 + A sin(2 PI t / T); a non-positive period is not a slow
        // row, it is a NaN generator inside the 240Hz step.
        Log.w(LEVEL_LOG_TAG, "Level $levelId: moving row $rowIndex period $period <= 0; using default")
    }
    return MovingRowDef(
        row = rowIndex,
        amplitude = amplitude ?: LevelDefaults.MOVING_ROW_AMPLITUDE,
        periodSeconds = period.takeIf { it > 0f } ?: LevelDefaults.MOVING_ROW_PERIOD_SECONDS,
    )
}

private fun CandyPlacementDto?.toDomain(levelId: Int): CandyPlacementDef =
    CandyPlacementDef(
        // Seeding from the level id keeps placement deterministic even when an author forgets the
        // key, which §11's determinism test requires.
        seed = this?.seed ?: levelId.toLong(),
        count = (this?.count ?: 0).coerceAtLeast(0),
        weights = this?.weights.toCandyWeights(levelId),
        fixed = this?.fixed.orEmpty().mapNotNull { it?.toDomain(levelId) },
    )

private fun Map<String, Int?>?.toCandyWeights(levelId: Int): Map<CandyColor, Int> {
    val weights = LinkedHashMap<CandyColor, Int>()
    this.orEmpty().forEach { (key, value) ->
        val color = CandyColor.fromKey(key)
        when {
            color == null ->
                Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping unknown candy weight '$key'")
            value == null || value < 0 ->
                Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping negative weight $key=$value")
            else -> weights[color] = value
        }
    }
    // The placement generator picks a colour by weighted draw over the total; an empty or
    // all-zero map would divide by zero rather than just look odd.
    if (weights.isEmpty() || weights.values.sum() == 0) {
        if (this != null) {
            Log.w(LEVEL_LOG_TAG, "Level $levelId: candy weights total zero; using uniform weights")
        }
        return LevelDefaults.UNIFORM_CANDY_WEIGHTS
    }
    return weights
}

private fun FixedCandyDto.toDomain(levelId: Int): FixedCandy? {
    val candyColor = CandyColor.fromKey(color)
    val r = row
    val c = col
    if (candyColor == null || r == null || c == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping malformed fixed candy: $this")
        return null
    }
    return FixedCandy(candyColor, r, c)
}

private fun GemPlacementDto.toDomain(levelId: Int): GemPlacementDef? {
    val gemType = GemType.fromKey(type)
    val r = row
    val c = col
    if (gemType == null || r == null || c == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping malformed gem: $this")
        return null
    }
    return GemPlacementDef(gemType, r, c)
}

private fun LevelCupDto?.toDomain(levelId: Int): CupDef {
    val speed = this?.speed ?: LevelDefaults.CUP_SPEED
    if (speed <= 0f) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: cup speed $speed <= 0; using ${LevelDefaults.CUP_SPEED}")
    }
    return CupDef(speed.takeIf { it > 0f } ?: LevelDefaults.CUP_SPEED)
}

private fun ObjectiveDto.toDomain(levelId: Int): ObjectiveDef? {
    val objectiveType = ObjectiveType.fromKey(type)
    if (objectiveType == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping objective with unknown type '$type'")
        return null
    }
    val candyColor = CandyColor.fromKey(color)
    if (color != null && candyColor == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping objective with unknown colour '$color'")
        return null
    }
    val gemType = GemType.fromKey(gem)
    if (gem != null && gemType == null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping objective with unknown gem '$gem'")
        return null
    }

    // See ObjectiveDef's table: each type needs one specific field, and an objective that cannot
    // state its target can never complete — which would soft-lock the level.
    val missingField = when (objectiveType) {
        ObjectiveType.COLLECT_CANDY -> if (count == null) "count" else null
        ObjectiveType.SCORE -> if (score == null) "score" else null
        ObjectiveType.COLLECT_GEM -> if (count == null) "count" else null
        ObjectiveType.CLEAR_COLOR -> if (candyColor == null) "color" else null
        ObjectiveType.CHAIN -> if (chain == null) "chain" else null
        ObjectiveType.CUP -> if (count == null) "count" else null
    }
    if (missingField != null) {
        Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping $objectiveType objective missing '$missingField'")
        return null
    }

    return ObjectiveDef(
        type = objectiveType,
        color = candyColor,
        gem = gemType,
        count = count,
        score = score,
        chain = chain,
    )
}

/** Maps JSON's `[[row, col], ...]` pair arrays; an inner array that is not a pair is skipped. */
private fun List<List<Int?>?>?.toRowCols(levelId: Int, what: String): List<RowCol> =
    this.orEmpty().mapNotNull { pair ->
        val row = pair?.getOrNull(0)
        val col = pair?.getOrNull(1)
        if (row == null || col == null) {
            Log.w(LEVEL_LOG_TAG, "Level $levelId: skipping malformed $what $pair (expected [row, col])")
            null
        } else {
            RowCol(row, col)
        }
    }
