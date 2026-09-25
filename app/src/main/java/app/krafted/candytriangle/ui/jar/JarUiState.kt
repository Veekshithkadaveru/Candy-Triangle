package app.krafted.candytriangle.ui.jar

import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.TrailType

/**
 * What one Candy Jar tier hands the player (PRD §7).
 *
 * A sealed hierarchy rather than three nullable fields on [RewardSlot]: the three tiers are
 * genuinely different kinds of thing (an equippable skin, an equippable trail, a level id), and the
 * jar screen renders each with a different affordance — a skin and a trail are tappable in the
 * selector, a Sweet Room is display-only in D2 (entering B1..B4 is D3/D4's map work).
 */
sealed interface JarReward {

    /** Tier 1. Note the Pink jar awards [BallSkin.GOLD] — §7, deliberate. */
    data class Skin(val skin: BallSkin) : JarReward

    /** Tier 2. Persisted here and drawn behind active balls by the board renderer. */
    data class Trail(val trail: TrailType) : JarReward

    /** Tier 3. [levelId] is 101..104 in the single [app.krafted.candytriangle.level.LevelIds]
     *  keyspace and [code] is its "B1".."B4" display spelling. */
    data class SweetRoom(val levelId: Int, val code: String) : JarReward
}

/**
 * One of a jar's three reward rows.
 *
 * [threshold] is the candy count that unlocks it, taken from the resolved thresholds (config first,
 * [JarUnlocks.DEFAULT_TIER_THRESHOLDS] as the fallback) rather than re-derived by the UI.
 */
data class RewardSlot(
    /** 1, 2 or 3 — never 0, and never above [JarUnlocks.MAX_TIER]. */
    val tier: Int,
    val threshold: Int,
    val unlocked: Boolean,
    val reward: JarReward,
)

/**
 * Everything the jar screen paints for a single colour jar.
 *
 * Deliberately pre-computed and free of Android and Compose types: the §11 verification suite is
 * JVM-only, so every number a `Canvas` block or a `Text` reads has to be decided somewhere a JUnit
 * test can reach — that place is [JarUiStateMapper].
 */
data class JarUiState(
    val color: CandyColor,
    /** Lifetime banked candies of this colour. */
    val count: Int,
    /** 0..[JarUnlocks.MAX_TIER], from [JarUnlocks.tierFor]. */
    val tier: Int,
    /** Candies needed for the next tier, or `null` once all three are earned. */
    val nextThreshold: Int?,
    /** `nextThreshold - count`, never negative; 0 at [JarUnlocks.MAX_TIER]. */
    val candiesToNextTier: Int,
    /** How full to draw the glass, clamped to 0f..1f. */
    val fillFraction: Float,
    /** Where to draw the threshold tick marks, ascending, each in `(0f, 1f]`. */
    val tickFractions: List<Float>,
    /** Exactly three slots, tiers 1..3 in order. */
    val rewards: List<RewardSlot>,
) {

    /** True once all three tiers are earned — the jar is drawn brim-full and capped. */
    val isComplete: Boolean get() = tier >= JarUnlocks.MAX_TIER

    /** The slot for [tier] (1..3), or `null` for an out-of-range tier. */
    fun reward(tier: Int): RewardSlot? = rewards.firstOrNull { it.tier == tier }
}

/**
 * The whole Candy Jar screen as one immutable value.
 *
 * One value rather than eight flows so `CandyJarScreen` renders from a single
 * `collectAsStateWithLifecycle` and the four jars, the equipped cosmetics and the unlock sets can
 * never tear against each other mid-frame.
 *
 * [unlockedSkins] and [unlockedTrails] are also the gate `JarViewModel.equipSkin`/`equipTrail`
 * check before writing to `ProgressStore`, whose own KDoc explicitly hands unlock validation to D2.
 */
data class CandyJarUiState(
    /** One entry per [CandyColor], in enum order: Green, Purple, Pink, Blue. */
    val jars: List<JarUiState>,
    val equippedBallSkin: BallSkin,
    val equippedTrail: TrailType,
    /** Always contains at least [BallSkin.DEFAULT]. */
    val unlockedSkins: Set<BallSkin>,
    /** Always contains at least [TrailType.NONE] ("off"). */
    val unlockedTrails: Set<TrailType>,
    /** Sweet Room level ids 101..104 that have been earned; empty on a fresh install. */
    val unlockedSweetRooms: Set<Int>,
    /** The resolved, ascending thresholds these jars were mapped against. */
    val tierThresholds: List<Int>,
) {

    /** Lifetime candies across all four jars — the screen's header figure. */
    val totalCandies: Int get() = jars.sumOf { it.count }

    fun jar(color: CandyColor): JarUiState? = jars.firstOrNull { it.color == color }
}
