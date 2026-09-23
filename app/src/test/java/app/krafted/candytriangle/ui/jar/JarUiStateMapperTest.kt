package app.krafted.candytriangle.ui.jar

import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.DEFAULT_JARS
import app.krafted.candytriangle.level.JarConfig
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §7 Candy Jar arithmetic the jar screen draws from.
 *
 * Pure JVM by construction: §11 forbids an emulator and Robolectric, so no composable in this
 * module can run in a test. [JarUiStateMapper] exists as a separate object precisely so every
 * number `JarCanvas` and `CandyJarScreen` paint is reachable from here.
 */
class JarUiStateMapperTest {

    private val defaults = JarUnlocks.DEFAULT_TIER_THRESHOLDS

    private fun jar(count: Int, color: CandyColor = CandyColor.GREEN, config: JarConfig? = null) =
        JarUiStateMapper.mapJar(color, count, JarUiStateMapper.thresholds(config), config)

    // ---------------------------------------------------------------- tiers

    /**
     * The §7 table's boundaries are `>=`, not `>`: banking the 40th candy unlocks tier 1, it does
     * not leave the player one short. Every count either side of all three thresholds is pinned
     * here because an off-by-one would silently withhold a reward the player earned.
     */
    @Test
    fun tierBoundariesAreInclusive() {
        val expected = listOf(
            0 to 0, 39 to 0, 40 to 1, 41 to 1,
            119 to 1, 120 to 2, 199 to 2, 200 to 3, 9_999 to 3,
        )
        for ((count, tier) in expected) {
            assertEquals("count $count", tier, jar(count).tier)
        }
    }

    /** A jar can never reach a fourth tier, however many candies are banked. */
    @Test
    fun tierIsClampedToMaxTier() {
        assertEquals(JarUnlocks.MAX_TIER, jar(Int.MAX_VALUE).tier)
        assertTrue(jar(200).isComplete)
        assertFalse(jar(199).isComplete)
    }

    /** A negative count cannot exist in DataStore (`bankCandies` only increments), but a corrupt
     *  save must still render rather than crash the jar screen. */
    @Test
    fun negativeCountIsTreatedAsEmpty() {
        val state = jar(-50)
        assertEquals(0, state.count)
        assertEquals(0, state.tier)
        assertEquals(0f, state.fillFraction, 0f)
    }

    /** The mapper uses `JarUnlocks.tierFor` (sorted, clamped), not `JarConfig.tierFor`. */
    @Test
    fun tierMatchesJarUnlocks() {
        for (count in listOf(0, 39, 40, 119, 120, 199, 200, 5_000)) {
            assertEquals(JarUnlocks.tierFor(count, defaults), jar(count).tier)
        }
    }

    // ---------------------------------------------------------------- progress to next tier

    @Test
    fun nextThresholdAndRemainderTrackTheNextTier() {
        assertEquals(40, jar(0).nextThreshold)
        assertEquals(40, jar(0).candiesToNextTier)

        assertEquals(120, jar(40).nextThreshold)
        assertEquals(80, jar(40).candiesToNextTier)

        assertEquals(200, jar(120).nextThreshold)
        assertEquals(80, jar(120).candiesToNextTier)
    }

    /** The gap [JarUnlocks] does not fill: at MAX_TIER there is no next threshold to point at. */
    @Test
    fun noNextThresholdAtMaxTier() {
        val complete = jar(200)
        assertNull(complete.nextThreshold)
        assertEquals(0, complete.candiesToNextTier)

        val overflowing = jar(9_999)
        assertNull(overflowing.nextThreshold)
        assertEquals(0, overflowing.candiesToNextTier)
    }

    // ---------------------------------------------------------------- fill and ticks

    @Test
    fun fillFractionIsCountOverAFullJar() {
        assertEquals(0f, jar(0).fillFraction, 1e-6f)
        assertEquals(0.2f, jar(40).fillFraction, 1e-6f)
        assertEquals(0.6f, jar(120).fillFraction, 1e-6f)
    }

    /** Candies keep banking forever, but the glass does not overflow. */
    @Test
    fun fillFractionClampsToOneAtAndAboveTheTopThreshold() {
        assertEquals(1f, jar(200).fillFraction, 0f)
        assertEquals(1f, jar(201).fillFraction, 0f)
        assertEquals(1f, jar(50_000).fillFraction, 0f)
    }

    /**
     * `JarCanvas` draws its threshold marks from these, never from a hardcoded list. A tick at 0
     * would sit flush with the jar's base and read as a rendering bug, hence the open lower bound.
     */
    @Test
    fun tickFractionsAreSortedAndWithinZeroExclusiveToOne() {
        val ticks = jar(0).tickFractions
        assertEquals(3, ticks.size)
        assertEquals(ticks.sorted(), ticks)
        for (tick in ticks) {
            assertTrue("tick $tick > 0", tick > 0f)
            assertTrue("tick $tick <= 1", tick <= 1f)
        }
        assertEquals(1f, ticks.last(), 0f)
    }

    @Test
    fun tickFractionsFollowConfiguredThresholds() {
        val config = JarConfig(tierThresholds = listOf(10, 50, 100))
        val ticks = jar(0, config = config).tickFractions
        assertEquals(3, ticks.size)
        assertEquals(0.1f, ticks[0], 1e-6f)
        assertEquals(0.5f, ticks[1], 1e-6f)
        assertEquals(1f, ticks[2], 1e-6f)
    }

    // ---------------------------------------------------------------- reward slots

    /**
     * **Regression guard — do not "fix" this.**
     *
     * PRD §7's table awards the Pink jar the *Gold* ball skin, not a pink one. It is encoded that
     * way in [JarUnlocks.tier1Skin] and in `config.json` (`jar.jars[2].tier1BallSkin = "GOLD"`),
     * and there is no pink ball sprite for it to be "corrected" to.
     */
    @Test
    fun pinkJarTierOneRewardIsTheGoldSkin() {
        val pink = jar(40, color = CandyColor.PINK)
        val reward = pink.reward(1)?.reward
        assertEquals(JarReward.Skin(BallSkin.GOLD), reward)
        assertEquals(BallSkin.GOLD, JarUnlocks.tier1Skin(CandyColor.PINK))

        // And the other three do match their jar, so the quirk is Pink's alone.
        assertEquals(JarReward.Skin(BallSkin.GREEN), jar(40, CandyColor.GREEN).reward(1)?.reward)
        assertEquals(JarReward.Skin(BallSkin.PURPLE), jar(40, CandyColor.PURPLE).reward(1)?.reward)
        assertEquals(JarReward.Skin(BallSkin.BLUE), jar(40, CandyColor.BLUE).reward(1)?.reward)
    }

    @Test
    fun tierTwoRewardsAreTheMatchingTrails() {
        for (color in CandyColor.entries) {
            assertEquals(
                "tier 2 for $color",
                JarReward.Trail(JarUnlocks.tier2Trail(color)),
                jar(120, color).reward(2)?.reward,
            )
        }
    }

    /** Sweet Rooms live in the single Int keyspace as 101..104, spelled B1..B4 for display. */
    @Test
    fun tierThreeRewardsAreSweetRooms101To104() {
        val ids = CandyColor.entries.map { color ->
            val reward = jar(200, color).reward(3)?.reward
            assertTrue("tier 3 for $color", reward is JarReward.SweetRoom)
            reward as JarReward.SweetRoom
            assertEquals(JarUnlocks.tier3SweetRoomId(color), reward.levelId)
            assertEquals(LevelIds.bonusCode(reward.levelId), reward.code)
            assertTrue(LevelIds.isBonus(reward.levelId))
            reward.levelId
        }
        assertEquals(listOf(101, 102, 103, 104), ids)
    }

    @Test
    fun slotThresholdsAndLockStateFollowTheTier() {
        val partway = jar(130)
        assertEquals(listOf(1, 2, 3), partway.rewards.map { it.tier })
        assertEquals(listOf(40, 120, 200), partway.rewards.map { it.threshold })
        assertEquals(listOf(true, true, false), partway.rewards.map { it.unlocked })
    }

    // ---------------------------------------------------------------- config wins

    /** `config.jar.tierThresholds` is the source of truth once the asset parse lands. */
    @Test
    fun configuredThresholdsBeatTheDefaultTable() {
        val config = JarConfig(tierThresholds = listOf(5, 10, 15))
        val state = jar(10, config = config)

        assertEquals(2, state.tier)
        assertEquals(15, state.nextThreshold)
        assertEquals(5, state.candiesToNextTier)
        assertEquals(listOf(5, 10, 15), state.rewards.map { it.threshold })
        // The same count is still tier 0 under §7's table, so the config genuinely won.
        assertEquals(0, jar(10).tier)
    }

    /** Hand-authored JSON can be out of order; the ticks and slots must not be. */
    @Test
    fun outOfOrderConfiguredThresholdsAreSorted() {
        val config = JarConfig(tierThresholds = listOf(200, 40, 120))
        val state = jar(40, config = config)

        assertEquals(listOf(40, 120, 200), state.rewards.map { it.threshold })
        assertEquals(1, state.tier)
        assertEquals(state.tickFractions.sorted(), state.tickFractions)
    }

    /** A wrong-length list is an editing mistake; falling back beats inventing the third number. */
    @Test
    fun wrongLengthConfiguredThresholdsFallBackToTheDefaults() {
        val config = JarConfig(tierThresholds = listOf(5, 10))
        assertEquals(defaults, JarUiStateMapper.thresholds(config))
        assertEquals(0, jar(10, config = config).tier)
    }

    @Test
    fun configuredTierOneSkinBeatsTheDefaultTable() {
        val overridden = DEFAULT_JARS.map { def ->
            if (def.color == CandyColor.GREEN) def.copy(tier1BallSkin = BallSkin.GOLD) else def
        }
        val config = JarConfig(jars = overridden)

        assertEquals(
            JarReward.Skin(BallSkin.GOLD),
            jar(40, CandyColor.GREEN, config).reward(1)?.reward,
        )
        // ...and the selector is offered the config's skin, not JarUnlocks'.
        val unlocked = JarUiStateMapper.unlockedSkins(mapOf(CandyColor.GREEN to 40), config)
        assertEquals(setOf(BallSkin.DEFAULT, BallSkin.GOLD), unlocked)
    }

    @Test
    fun configuredTierTwoTrailAndSweetRoomBeatTheDefaultTable() {
        val overridden = DEFAULT_JARS.map { def ->
            if (def.color == CandyColor.BLUE) {
                def.copy(
                    tier2Trail = TrailType.PINK,
                    tier3SweetRoomCode = "B1",
                    tier3SweetRoomLevelId = LevelIds.BONUS_FIRST,
                )
            } else {
                def
            }
        }
        val config = JarConfig(jars = overridden)
        val blue = jar(200, CandyColor.BLUE, config)

        assertEquals(JarReward.Trail(TrailType.PINK), blue.reward(2)?.reward)
        assertEquals(JarReward.SweetRoom(LevelIds.BONUS_FIRST, "B1"), blue.reward(3)?.reward)
    }

    /** `null` means "the asset parse has not landed yet" and must give §7's shipped table. */
    @Test
    fun aMissingConfigUsesTheDefaultThresholds() {
        assertEquals(defaults, JarUiStateMapper.thresholds(null))
    }

    // ---------------------------------------------------------------- unlock sets

    /**
     * The unlock sets are the gate `JarViewModel` applies before writing to `ProgressStore`, whose
     * KDoc hands unlock validation to D2. With §7's own table they must agree with [JarUnlocks]
     * exactly, or the two halves of that contract have drifted apart.
     */
    @Test
    fun unlockSetsAgreeWithJarUnlocksWhenTheConfigLeavesTheTableAlone() {
        val counts = mapOf(
            CandyColor.GREEN to 212,
            CandyColor.PURPLE to 131,
            CandyColor.PINK to 64,
            CandyColor.BLUE to 12,
        )
        for (config in listOf(null, JarConfig())) {
            val thresholds = JarUiStateMapper.thresholds(config)
            assertEquals(
                JarUnlocks.unlockedSkins(counts, thresholds),
                JarUiStateMapper.unlockedSkins(counts, config),
            )
            assertEquals(
                JarUnlocks.unlockedTrails(counts, thresholds),
                JarUiStateMapper.unlockedTrails(counts, config),
            )
            assertEquals(
                JarUnlocks.unlockedSweetRooms(counts, thresholds),
                JarUiStateMapper.unlockedSweetRooms(counts, config),
            )
        }
    }

    @Test
    fun freshInstallOffersOnlyTheFreebies() {
        val state = JarUiStateMapper.map(emptyMap(), BallSkin.DEFAULT, TrailType.NONE, null)

        assertEquals(setOf(BallSkin.DEFAULT), state.unlockedSkins)
        assertEquals(setOf(TrailType.NONE), state.unlockedTrails)
        assertEquals(emptySet<Int>(), state.unlockedSweetRooms)
        assertEquals(0, state.totalCandies)
    }

    // ---------------------------------------------------------------- whole-screen state

    @Test
    fun mapCoversAllFourJarsInEnumOrderAndTotalsThem() {
        val counts = mapOf(
            CandyColor.GREEN to 212,
            CandyColor.PURPLE to 131,
            CandyColor.PINK to 64,
            CandyColor.BLUE to 12,
        )
        val state = JarUiStateMapper.map(counts, BallSkin.GOLD, TrailType.PURPLE, null)

        assertEquals(CandyColor.entries.toList(), state.jars.map { it.color })
        assertEquals(listOf(3, 2, 1, 0), state.jars.map { it.tier })
        assertEquals(212 + 131 + 64 + 12, state.totalCandies)
        assertEquals(BallSkin.GOLD, state.equippedBallSkin)
        assertEquals(TrailType.PURPLE, state.equippedTrail)
        assertEquals(defaults, state.tierThresholds)
        assertNotNull(state.jar(CandyColor.PINK))
    }

    /** A partial map is the fresh-install shape; a missing colour is 0, not an exception. */
    @Test
    fun missingColoursReadAsZero() {
        val state = JarUiStateMapper.map(
            mapOf(CandyColor.BLUE to 40),
            BallSkin.DEFAULT,
            TrailType.NONE,
            null,
        )
        assertEquals(4, state.jars.size)
        assertEquals(0, state.jar(CandyColor.GREEN)?.count)
        assertEquals(40, state.jar(CandyColor.BLUE)?.count)
        assertEquals(setOf(BallSkin.DEFAULT, BallSkin.BLUE), state.unlockedSkins)
    }
}
