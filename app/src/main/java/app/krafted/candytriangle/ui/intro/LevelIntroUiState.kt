package app.krafted.candytriangle.ui.intro

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.ObjectiveType

/**
 * What the pre-level `LevelIntroDialog` shows (D3): the level's objectives with icons, its ball
 * count and its §5.3 crown thresholds.
 *
 * FROZEN D3 CONTRACT — written by the lead before the three D3 agents started. `LevelIntroMapper`
 * produces it, `LevelIntroDialog` draws it, `LevelMapViewModel` holds it.
 *
 * No user-facing strings and no Android types, for the same JVM-testability reason as `HudState`.
 */
data class LevelIntroUiState(
    val levelId: Int,
    /** "B1".."B4" for a Sweet Room, `null` for a main level (§6.3 `code`). */
    val levelCode: String?,
    /** Panorama world 1..4, or 0 for a Sweet Room (§6.1). */
    val world: Int,
    /** Balls the level starts with (`LevelDef.balls`). */
    val balls: Int,
    /** §5.3: balls that must remain for the 2nd crown (`LevelDef.twoCrownBalls`). */
    val twoCrownBalls: Int,
    /** §5.3: balls that must remain for the 3rd crown (`LevelDef.threeCrownBalls`). */
    val threeCrownBalls: Int,
    /** Best crowns so far, 0..3 — 0 if never cleared. */
    val crownsEarned: Int,
    /** Best score so far, 0 if never played. */
    val bestScore: Int,
    /** In authored order. World 4 levels carry two (§6.1). */
    val objectives: List<IntroObjective>,
    /** The distinct gem types on this board, in [GemType] declaration order — the gem icon row. */
    val gems: List<GemType>,
    /** Gems whose §4.2 intro level is this level, flagged "New". The full gem-intro popup is D4's. */
    val newGems: List<GemType>,
    /** True when the layout has oscillating peg rows (§3.3, World 3+). */
    val hasMovingRows: Boolean,
) {
    val isSweetRoom: Boolean get() = LevelIds.isBonus(levelId)
}

/**
 * One §6.3 objective, flattened for display.
 *
 * [target] is always the one number the objective is about, so the dialog never re-reads the
 * `ObjectiveDef` tagged union:
 *
 * | [type]          | [target]                  | also                                  |
 * |-----------------|---------------------------|---------------------------------------|
 * | `COLLECT_CANDY` | candies to collect        | [color], or `null` for any colour      |
 * | `SCORE`         | points to reach           |                                        |
 * | `COLLECT_GEM`   | gems to smash             | [gem], or `null` for any gem           |
 * | `CLEAR_COLOR`   | 0 — no count, clear them all | [color] (always set)               |
 * | `CHAIN`         | chain length to reach     | [times], how many chains (>= 1)        |
 * | `CUP`           | Candy Cup catches         |                                        |
 */
data class IntroObjective(
    val type: ObjectiveType,
    val color: CandyColor?,
    val gem: GemType?,
    val target: Int,
    /** `CHAIN` only: `ObjectiveDef.count`, at least 1. Always 1 for every other type. */
    val times: Int,
)
