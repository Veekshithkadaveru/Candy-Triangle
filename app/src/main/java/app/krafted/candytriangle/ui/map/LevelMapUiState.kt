package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.level.CandyColor

/**
 * The level map (PRD §6.1-§6.2, D3) as immutable, Android-free values.
 *
 * FROZEN D3 CONTRACT — written by the lead before the three D3 agents started. `MapStateMapper`
 * produces these, `LevelMapScreen` only draws them. Change a shape here and both sides break, so a
 * change goes through the lead.
 *
 * Same rule as `HudState` and `CandyJarUiState`: **no user-facing strings and no Android types.**
 * The §11 suite is JVM-only and no composable can run in it, so every number and every lock
 * decision the map paints is made in `MapStateMapper`, where a JUnit test can reach it.
 */

/** Whether a node can be tapped, and how it is drawn. */
enum class NodeStatus {

    /** Not reachable yet: its world gate is shut, the previous level is not cleared, or (for a
     *  Sweet Room) its jar has not reached tier 3. Tapping it opens no intro. */
    LOCKED,

    /** Reachable and not yet cleared. */
    AVAILABLE,

    /** Cleared at least once — i.e. at least one crown (§5.3). Always replayable, even if a
     *  later config change would have kept its world shut: a gate never closes behind a player. */
    CLEARED,
}

/**
 * A point inside one world slice, as fractions of **that slice's** width ([x]) and height ([y]),
 * both in `0f..1f`. Resolution-independent: the screen multiplies by the slice's laid-out size.
 */
data class MapPoint(val x: Float, val y: Float)

/** One of the 40 main levels (§6.1). */
data class LevelNodeUiState(
    val levelId: Int,
    val status: NodeStatus,
    /** 0..3 (§5.3); always 0 unless [status] is [NodeStatus.CLEARED]. */
    val crowns: Int,
    val bestScore: Int,
    /** True for exactly one main-level node on the whole map: where the pulsing `triangle.png`
     *  marker sits (§9.1). */
    val isCurrent: Boolean,
    /** Node centre within its slice. */
    val position: MapPoint,
) {
    val isPlayable: Boolean get() = status != NodeStatus.LOCKED
}

/**
 * One bonus Sweet Room, B1..B4 (§7), drawn on the slice of the same number (B1 on World 1's slice
 * ... B4 on World 4's).
 *
 * Unlocked by its jar reaching tier 3 **and nothing else** — world gates do not apply, since a jar
 * can fill before World 2 opens (C1 note 5). A Sweet Room on a still-locked world's slice can
 * therefore be playable.
 */
data class SweetRoomNodeUiState(
    /** 101..104 in the single `LevelIds` keyspace. */
    val levelId: Int,
    /** "B1".."B4". A display code, not a string resource — it is the level's name. */
    val code: String,
    val status: NodeStatus,
    val crowns: Int,
    val bestScore: Int,
    /** The jar whose tier 3 unlocks this room (§7: Green→B1, Purple→B2, Pink→B3, Blue→B4). */
    val jarColor: CandyColor,
    /** Lifetime candies banked in that jar. */
    val jarCount: Int,
    /** The resolved tier-3 threshold (config first, §7's 200 as the fallback). */
    val jarThreshold: Int,
    val position: MapPoint,
) {
    val isPlayable: Boolean get() = status != NodeStatus.LOCKED
}

/**
 * A §6.2 world gate. Both halves must hold: [requiresLevelCleared] cleared **and**
 * [crownsHave] >= [crownsRequired].
 */
data class GateUiState(
    /** The world this gate opens, 2..4. */
    val world: Int,
    val requiresLevelCleared: Int,
    val levelCleared: Boolean,
    val crownsRequired: Int,
    /** Main-level crowns only — `PlayerProgress.totalCrowns`, the number §6.2 compares against. */
    val crownsHave: Int,
    val open: Boolean,
    /** Where the gate badge sits within its world's slice. */
    val position: MapPoint,
)

/** One 1080 x 1920 backdrop slice of the 4320 x 1920 panorama — one world (§6.1). */
data class WorldSliceUiState(
    /** 1..4. The slice's index in [LevelMapUiState.slices] is `world - 1`. */
    val world: Int,
    val levelFrom: Int,
    val levelTo: Int,
    /** `WorldDef.pegTintArgb`: map nodes and the path glow in their world's §6.1 peg tint. */
    val pegTintArgb: Int,
    /** World 1 always; otherwise whether its [gate] is open. */
    val unlocked: Boolean,
    /** Crowns earned across this world's levels. */
    val crownsEarned: Int,
    /** 3 per level in this world. */
    val crownsAvailable: Int,
    /** `null` for World 1, which has no gate. */
    val gate: GateUiState?,
    /** This world's levels in play order, `levelFrom..levelTo`. */
    val levels: List<LevelNodeUiState>,
    /** The Sweet Room drawn on this slice, or `null` if the config has none for it. */
    val sweetRoom: SweetRoomNodeUiState?,
    /**
     * The dotted path's polyline: a point on the slice's left edge (x = 0), every level node in
     * play order, then a point on the right edge (x = 1). The exit of slice `k` and the entry of
     * slice `k + 1` share a y, so the path reads as one continuous line across the panorama.
     */
    val path: List<MapPoint>,
)

/** The whole map as one value, so the screen renders from a single `collectAsStateWithLifecycle`. */
data class LevelMapUiState(
    /** Exactly four, World 1..4 in order — one per backdrop slice. */
    val slices: List<WorldSliceUiState>,
    /** Main-level crowns (`PlayerProgress.totalCrowns`) — Sweet Rooms excluded, as in §6.2. */
    val totalCrowns: Int,
    /** 3 x the main levels on the map (120). */
    val maxCrowns: Int,
    /** The level under the pulsing marker: the lowest reachable uncleared main level; failing that
     *  (everything cleared, or the frontier is behind a shut gate) the highest cleared one; on a
     *  fresh install, level 1. */
    val currentLevelId: Int,
    /** 0-based index into [slices] of the slice holding [currentLevelId] — where the map opens. */
    val currentSliceIndex: Int,
    /** False only for the provisional state before the first DataStore read lands, so the screen
     *  knows not to auto-scroll to a "current level" that is really just the fresh-install default. */
    val loaded: Boolean = true,
) {

    /** Every main-level node on the map, in id order. */
    val allLevels: List<LevelNodeUiState> get() = slices.flatMap { it.levels }

    fun slice(world: Int): WorldSliceUiState? = slices.firstOrNull { it.world == world }
}
