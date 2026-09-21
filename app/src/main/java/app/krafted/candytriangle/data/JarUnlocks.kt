package app.krafted.candytriangle.data

import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType

/**
 * The Candy Jar reward table (PRD §7), as pure arithmetic.
 *
 * Deliberately free of DataStore, config models and Android: the same three thresholds decide what
 * the jar screen (D2) paints, what the skin picker offers and which Sweet Rooms the map (D3) draws,
 * and none of those callers should have to hold a `ProgressStore` to ask "is the gold ball unlocked
 * yet?". Callers pass the thresholds in — they live in `config.json` under `jar.tierThresholds` so
 * they stay tunable — and [DEFAULT_TIER_THRESHOLDS] mirrors the PRD table for callers that have no
 * config loaded yet.
 */
object JarUnlocks {

    /** §7's table: 40 candies for tier 1, 120 for tier 2, 200 for tier 3. */
    val DEFAULT_TIER_THRESHOLDS: List<Int> = listOf(40, 120, 200)

    /** A jar has three reward tiers; a fourth cannot be earned however many candies are banked. */
    const val MAX_TIER: Int = 3

    /**
     * How many of [thresholds] a lifetime jar [count] has reached, clamped to 0..[MAX_TIER].
     *
     * Thresholds are sorted defensively: `config.json` is hand-authored and an out-of-order list
     * would otherwise silently hand out tier 3 before tier 1.
     */
    fun tierFor(count: Int, thresholds: List<Int> = DEFAULT_TIER_THRESHOLDS): Int {
        val banked = count.coerceAtLeast(0)
        return thresholds.sorted().count { banked >= it }.coerceIn(0, MAX_TIER)
    }

    /**
     * Tier 1 reward for [color].
     *
     * Note the Pink jar awards the **Gold** ball, not a pink one. That is §7's table verbatim and
     * matches `config.json` (`jar.jars[2].tier1BallSkin = "GOLD"`) — it is a deliberate PRD quirk,
     * not a transcription slip, so please do not "correct" it to a pink skin that does not exist.
     */
    fun tier1Skin(color: CandyColor): BallSkin = when (color) {
        CandyColor.GREEN -> BallSkin.GREEN
        CandyColor.PURPLE -> BallSkin.PURPLE
        CandyColor.PINK -> BallSkin.GOLD
        CandyColor.BLUE -> BallSkin.BLUE
    }

    /** Tier 2 reward for [color] — here the trail colour does match the jar. */
    fun tier2Trail(color: CandyColor): TrailType = when (color) {
        CandyColor.GREEN -> TrailType.GREEN
        CandyColor.PURPLE -> TrailType.PURPLE
        CandyColor.PINK -> TrailType.PINK
        CandyColor.BLUE -> TrailType.BLUE
    }

    /** Tier 3 reward for [color]: the level id of Sweet Room B1..B4 in the §10 Int keyspace. */
    fun tier3SweetRoomId(color: CandyColor): Int = LevelIds.BONUS_FIRST + when (color) {
        CandyColor.GREEN -> 0 // B1
        CandyColor.PURPLE -> 1 // B2
        CandyColor.PINK -> 2 // B3
        CandyColor.BLUE -> 3 // B4
    }

    /**
     * Every ball skin the player may equip.
     *
     * [BallSkin.DEFAULT] is always present: the player has to have something to fire before any
     * jar fills, and the skin picker needs a non-empty list on a fresh install.
     */
    fun unlockedSkins(
        jarCounts: Map<CandyColor, Int>,
        thresholds: List<Int> = DEFAULT_TIER_THRESHOLDS,
    ): Set<BallSkin> = buildSet {
        add(BallSkin.DEFAULT)
        for (color in CandyColor.entries) {
            if (tierFor(jarCounts[color] ?: 0, thresholds) >= 1) add(tier1Skin(color))
        }
    }

    /** Every trail the player may equip; [TrailType.NONE] is always available (it means "off"). */
    fun unlockedTrails(
        jarCounts: Map<CandyColor, Int>,
        thresholds: List<Int> = DEFAULT_TIER_THRESHOLDS,
    ): Set<TrailType> = buildSet {
        add(TrailType.NONE)
        for (color in CandyColor.entries) {
            if (tierFor(jarCounts[color] ?: 0, thresholds) >= 2) add(tier2Trail(color))
        }
    }

    /**
     * Sweet Room level ids (101..104) the player has earned.
     *
     * Unlike skins and trails there is no freebie here: an empty set on a fresh install is correct,
     * and the map simply draws four locked nodes.
     */
    fun unlockedSweetRooms(
        jarCounts: Map<CandyColor, Int>,
        thresholds: List<Int> = DEFAULT_TIER_THRESHOLDS,
    ): Set<Int> = buildSet {
        for (color in CandyColor.entries) {
            if (tierFor(jarCounts[color] ?: 0, thresholds) >= 3) add(tier3SweetRoomId(color))
        }
    }
}
