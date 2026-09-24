package app.krafted.candytriangle.ui.intro

import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType

/**
 * Builds the pre-level intro from a level definition and the player's progress.
 *
 * Pure and Android-free, so `LevelIntroMapperTest` can run it over all 44 shipped levels on the
 * JVM. `config == null` means "not parsed yet" and maps against `GameConfig.DEFAULTS`.
 *
 * Every decision the dialog shows is made here — the one number each objective is about, which
 * crowns are lit, which gems are new — so `LevelIntroDialog` is left with layout and copy, the same
 * split as `HudStateMapper` / `GameHud` and `JarUiStateMapper` / `CandyJarScreen`.
 *
 * Owner: D3 Agent C (intro).
 */
object LevelIntroMapper {

    /**
     * What an objective asks for when its required field is missing — `ObjectiveTracker`'s own
     * `?: 1`, so the intro never states a target the session would not hold the player to.
     * `LevelDef`'s mapper drops such an objective from `levels.json`, so only a hand-built
     * [ObjectiveDef] ever reaches this.
     */
    private const val TRACKER_FALLBACK_TARGET = 1

    fun map(level: LevelDef, progress: PlayerProgress, config: GameConfig?): LevelIntroUiState {
        val resolved = config ?: GameConfig.DEFAULTS
        val gems = gemsOnBoard(level)
        return LevelIntroUiState(
            levelId = level.id,
            // From the id, never `LevelDef.code`: the id range is authoritative (A2 note 1), so a
            // main level stays `null` even if its JSON carries a stray "code", and a Sweet Room is
            // always "B1".."B4" — the same spelling the jar screen derives for its tier-3 slot.
            levelCode = LevelIds.bonusCode(level.id),
            world = level.world,
            balls = level.balls,
            twoCrownBalls = level.twoCrownBalls,
            threeCrownBalls = level.threeCrownBalls,
            // ProgressStore already clamps on write; a corrupt or hand-edited save must still
            // render, not light a fourth crown or print a negative best.
            crownsEarned = progress.crownsFor(level.id).coerceIn(0, ProgressStore.MAX_CROWNS_PER_LEVEL),
            bestScore = progress.bestScoreFor(level.id).coerceAtLeast(0),
            objectives = level.objectives.map(::introObjective),
            gems = gems,
            newGems = gems.filter { introLevel(it, resolved) == level.id },
            hasMovingRows = level.layout.movingRows.isNotEmpty(),
        )
    }

    /**
     * One objective, flattened per the table on [IntroObjective]: [IntroObjective.target] is the
     * single number the objective is about, so the dialog never re-reads the tagged union.
     */
    private fun introObjective(def: ObjectiveDef): IntroObjective {
        val target = when (def.type) {
            ObjectiveType.COLLECT_CANDY,
            ObjectiveType.COLLECT_GEM,
            ObjectiveType.CUP,
            -> def.count ?: TRACKER_FALLBACK_TARGET

            ObjectiveType.SCORE -> def.score ?: TRACKER_FALLBACK_TARGET

            // The chain *length*. How many such chains is `count`, carried in `times` below.
            ObjectiveType.CHAIN -> def.chain ?: TRACKER_FALLBACK_TARGET

            // A live board predicate with no counter (D1 note 4). The HUD's synthetic target is the
            // candies placed at build time, which needs a built board the intro does not have.
            ObjectiveType.CLEAR_COLOR -> 0
        }
        val times = if (def.type == ObjectiveType.CHAIN) {
            (def.count ?: 1).coerceAtLeast(1)
        } else {
            1
        }
        return IntroObjective(
            type = def.type,
            color = def.color,
            gem = def.gem,
            target = target,
            times = times,
        )
    }

    /**
     * The distinct gem types placed on the board, in [GemType] declaration order rather than
     * authored order, so a gem row never reshuffles between two levels that carry the same gems.
     */
    private fun gemsOnBoard(level: LevelDef): List<GemType> =
        GemType.entries.filter { type -> level.gems.any { it.type == type } }

    /**
     * §4.2's intro level for [type]: the config's, with the enum's as the fallback when the config
     * dropped that gem — the same rule `LevelCatalogTest` applies to the shipped catalogue.
     */
    private fun introLevel(type: GemType, config: GameConfig): Int =
        config.gem(type)?.introLevel ?: type.introLevel
}
