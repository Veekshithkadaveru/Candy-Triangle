package app.krafted.candytriangle.ui.jar

import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.JarConfig
import app.krafted.candytriangle.level.JarDef
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType

/**
 * Turns raw §10 progress into the §7 Candy Jar screen state.
 *
 * ## Why this is a separate object
 *
 * §11's verification suite is JVM-only — no emulator, no Robolectric, no `compose-ui-test-junit4`
 * on `testImplementation` — so no composable can run in a test here. Every non-trivial jar
 * computation therefore lives in this pure object instead of inside a `Canvas` block, which leaves
 * `CandyJarScreen`, `JarCanvas` and `CosmeticSelector` thin enough that compilation is adequate
 * proof of them.
 *
 * ## What it adds over [JarUnlocks]
 *
 * [JarUnlocks] already owns the thresholds, the tier arithmetic and the three reward tables, and
 * this object reuses all of it rather than restating §7 a second time. What [JarUnlocks] has no
 * concept of is *progress toward* the next tier — [JarUiState.nextThreshold],
 * [JarUiState.candiesToNextTier], [JarUiState.fillFraction] and [JarUiState.tickFractions] are the
 * gap this fills.
 *
 * Note there are two tier functions in the tree with different semantics: `JarConfig.tierFor` is
 * unsorted and unclamped, [JarUnlocks.tierFor] sorts defensively and clamps to
 * `0..JarUnlocks.MAX_TIER`. This object uses [JarUnlocks.tierFor] throughout.
 *
 * ## Config wins over the hardcoded table
 *
 * Every entry point takes a nullable [JarConfig]: `null` means "the asset parse has not landed
 * yet", and the §7 defaults in [JarUnlocks] apply until it does. Once a config is present its
 * `tierThresholds` and its per-colour [JarDef] rewards win, so a tuning pass in `config.json` moves
 * the jar screen without a code change.
 */
object JarUiStateMapper {

    /**
     * The full screen state.
     *
     * [jarCounts] may be partial or empty; a missing colour reads as 0 rather than throwing, since
     * a fresh install has written no jar keys at all.
     */
    fun map(
        jarCounts: Map<CandyColor, Int>,
        equippedBallSkin: BallSkin,
        equippedTrail: TrailType,
        jarConfig: JarConfig?,
    ): CandyJarUiState {
        val thresholds = thresholds(jarConfig)
        val jars = CandyColor.entries.map { color ->
            mapJar(color, jarCounts[color] ?: 0, thresholds, jarConfig)
        }
        return CandyJarUiState(
            jars = jars,
            equippedBallSkin = equippedBallSkin,
            equippedTrail = equippedTrail,
            unlockedSkins = skinsOf(jars),
            unlockedTrails = trailsOf(jars),
            unlockedSweetRooms = sweetRoomsOf(jars),
            tierThresholds = thresholds,
        )
    }

    /** One jar, for a count that has already been defaulted to 0. */
    fun mapJar(
        color: CandyColor,
        count: Int,
        thresholds: List<Int>,
        jarConfig: JarConfig?,
    ): JarUiState {
        val banked = count.coerceAtLeast(0)
        val tier = JarUnlocks.tierFor(banked, thresholds)
        // `thresholds` is ascending and `tier` is how many of them have been reached, so index
        // `tier` is the next one — and is out of bounds exactly at MAX_TIER, which is the null.
        val next = thresholds.getOrNull(tier)
        val full = thresholds.lastOrNull()?.coerceAtLeast(1) ?: 1
        return JarUiState(
            color = color,
            count = banked,
            tier = tier,
            nextThreshold = next,
            candiesToNextTier = if (next == null) 0 else (next - banked).coerceAtLeast(0),
            fillFraction = (banked.toFloat() / full).coerceIn(0f, 1f),
            tickFractions = tickFractions(thresholds),
            rewards = listOf(
                RewardSlot(
                    tier = 1,
                    threshold = thresholds.getOrElse(0) { JarUnlocks.DEFAULT_TIER_THRESHOLDS[0] },
                    unlocked = tier >= 1,
                    reward = JarReward.Skin(tier1Skin(color, jarConfig)),
                ),
                RewardSlot(
                    tier = 2,
                    threshold = thresholds.getOrElse(1) { JarUnlocks.DEFAULT_TIER_THRESHOLDS[1] },
                    unlocked = tier >= 2,
                    reward = JarReward.Trail(tier2Trail(color, jarConfig)),
                ),
                RewardSlot(
                    tier = 3,
                    threshold = thresholds.getOrElse(2) { JarUnlocks.DEFAULT_TIER_THRESHOLDS[2] },
                    unlocked = tier >= JarUnlocks.MAX_TIER,
                    reward = tier3SweetRoom(color, jarConfig),
                ),
            ),
        )
    }

    /**
     * The thresholds in force: `config.jar.tierThresholds` when it supplies exactly
     * [JarUnlocks.MAX_TIER] of them, else §7's table.
     *
     * Sorted on the way out for the same reason [JarUnlocks.tierFor] sorts — `config.json` is
     * hand-authored, and an out-of-order list would otherwise put the tier-3 tick below the tier-1
     * one. A list of the wrong length falls back wholesale rather than being padded: a two-entry
     * list is an editing mistake, and inventing the third number would hide it.
     */
    fun thresholds(jarConfig: JarConfig?): List<Int> {
        val configured = jarConfig?.tierThresholds
        return if (configured != null && configured.size == JarUnlocks.MAX_TIER) {
            configured.sorted()
        } else {
            JarUnlocks.DEFAULT_TIER_THRESHOLDS
        }
    }

    /**
     * Every ball skin the player may equip — the gate `JarViewModel.equipSkin` applies before
     * calling `ProgressStore.equipBallSkin`, which deliberately does not validate.
     *
     * This equals `JarUnlocks.unlockedSkins(jarCounts, thresholds)` whenever the config leaves §7's
     * reward table alone (the overwhelmingly common case, asserted in `JarUiStateMapperTest`). It
     * is derived from the mapped slots instead of calling [JarUnlocks] directly so that a config
     * that *does* override a `tier1BallSkin` stays self-consistent: the selector must not offer a
     * skin the gate would then refuse, nor vice versa.
     */
    fun unlockedSkins(jarCounts: Map<CandyColor, Int>, jarConfig: JarConfig?): Set<BallSkin> {
        val thresholds = thresholds(jarConfig)
        val jars = CandyColor.entries.map { mapJar(it, jarCounts[it] ?: 0, thresholds, jarConfig) }
        return skinsOf(jars)
    }

    /** Every trail the player may equip; see [unlockedSkins] for why it is slot-derived. */
    fun unlockedTrails(jarCounts: Map<CandyColor, Int>, jarConfig: JarConfig?): Set<TrailType> {
        val thresholds = thresholds(jarConfig)
        val jars = CandyColor.entries.map { mapJar(it, jarCounts[it] ?: 0, thresholds, jarConfig) }
        return trailsOf(jars)
    }

    /** Earned Sweet Room ids (101..104). D2 only *displays* these — D3/D4 route into them. */
    fun unlockedSweetRooms(jarCounts: Map<CandyColor, Int>, jarConfig: JarConfig?): Set<Int> {
        val thresholds = thresholds(jarConfig)
        val jars = CandyColor.entries.map { mapJar(it, jarCounts[it] ?: 0, thresholds, jarConfig) }
        return sweetRoomsOf(jars)
    }

    // ------------------------------------------------------------------ internals

    /**
     * Tick positions as fractions of a full jar.
     *
     * Non-positive thresholds are dropped rather than drawn at the very bottom of the glass, which
     * keeps every value in `(0f, 1f]` — a tick flush with the jar's base would read as a rendering
     * bug, not as a reward the player already has.
     */
    private fun tickFractions(thresholds: List<Int>): List<Float> {
        val full = thresholds.lastOrNull()?.coerceAtLeast(1) ?: return emptyList()
        return thresholds
            .filter { it > 0 }
            .map { (it.toFloat() / full).coerceIn(0f, 1f) }
            .sorted()
    }

    /** Mirrors `GameConfig.jarFor(color)`; this object is handed the [JarConfig] rather than the
     *  whole `GameConfig` so it stays a pure §7 mapper. */
    private fun jarDef(color: CandyColor, jarConfig: JarConfig?): JarDef? =
        jarConfig?.jars?.firstOrNull { it.color == color }

    /**
     * §7's tier-1 skin, config first.
     *
     * The Pink jar's tier-1 reward is [BallSkin.GOLD], not a pink skin. That is §7's table
     * verbatim, it is already encoded in both [JarUnlocks.tier1Skin] and `config.json`, and
     * `JarUiStateMapperTest` guards it explicitly so nobody later "fixes" it.
     */
    private fun tier1Skin(color: CandyColor, jarConfig: JarConfig?): BallSkin =
        jarDef(color, jarConfig)?.tier1BallSkin ?: JarUnlocks.tier1Skin(color)

    private fun tier2Trail(color: CandyColor, jarConfig: JarConfig?): TrailType =
        jarDef(color, jarConfig)?.tier2Trail ?: JarUnlocks.tier2Trail(color)

    private fun tier3SweetRoom(color: CandyColor, jarConfig: JarConfig?): JarReward.SweetRoom {
        val def = jarDef(color, jarConfig)
        val levelId = def?.tier3SweetRoomLevelId ?: JarUnlocks.tier3SweetRoomId(color)
        val code = def?.tier3SweetRoomCode?.takeIf { it.isNotBlank() }
            ?: LevelIds.bonusCode(levelId)
            ?: ""
        return JarReward.SweetRoom(levelId, code)
    }

    private fun skinsOf(jars: List<JarUiState>): Set<BallSkin> = buildSet {
        // The player must have something to fire before any jar fills, so the default skin is
        // always present — the same freebie JarUnlocks.unlockedSkins grants. Note this is
        // BallSkin.DEFAULT, not `config.jar.defaultBallSkin`: the latter says what a fresh install
        // *equips*, which is a different question from what it is allowed to equip.
        add(BallSkin.DEFAULT)
        for (jar in jars) {
            val slot = jar.reward(1) ?: continue
            val reward = slot.reward
            if (slot.unlocked && reward is JarReward.Skin) add(reward.skin)
        }
    }

    private fun trailsOf(jars: List<JarUiState>): Set<TrailType> = buildSet {
        // NONE means "off" and is always selectable.
        add(TrailType.NONE)
        for (jar in jars) {
            val slot = jar.reward(2) ?: continue
            val reward = slot.reward
            if (slot.unlocked && reward is JarReward.Trail) add(reward.trail)
        }
    }

    private fun sweetRoomsOf(jars: List<JarUiState>): Set<Int> = buildSet {
        // No freebie here, unlike skins and trails: an empty set on a fresh install is correct.
        for (jar in jars) {
            val slot = jar.reward(3) ?: continue
            val reward = slot.reward
            if (slot.unlocked && reward is JarReward.SweetRoom) add(reward.levelId)
        }
    }
}
