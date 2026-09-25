package app.krafted.candytriangle.data

import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.ClearanceEnforcement
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies that the shipped `assets/config.json` parses into the PRD's numbers, and that a broken
 * or missing config degrades instead of throwing.
 *
 * Pure JVM: no emulator, no Robolectric, no android.jar. [AssetSource] is a `fun interface`
 * precisely so the real asset can be read straight off disk here — this exercises the file the app
 * actually ships, not a fixture that can drift away from it.
 */
class ConfigLoaderTest {

    /**
     * Reads the real asset from the module's source tree.
     *
     * Gradle runs a module's unit tests with the working directory set to the module directory, so
     * paths are relative to `app/`. [configAssetIsReachable] fails loudly with the actual working
     * directory if that ever stops being true.
     */
    private val realAssets = AssetSource { path ->
        File("src/main/assets/$path").takeIf(File::exists)?.readText()
    }

    private fun loader() = ConfigLoader(realAssets)

    // -- working directory / smoke -------------------------------------------------------------

    @Test
    fun configAssetIsReachable() = runTest {
        val onDisk = File("src/main/assets/config.json")
        assertTrue(
            "config.json not found. Unit-test working directory is " +
                File("").absolutePath + ", expected the app module directory.",
            onDisk.exists(),
        )

        val config = loader().config()
        assertEquals("configVersion", 1, config.configVersion)
        assertEquals("worlds parsed", 4, config.worlds.size)
        assertEquals("gems parsed", 7, config.gems.size)
        assertEquals("gates parsed", 3, config.gates.size)
    }

    /**
     * Proves the assertions below are reading the asset rather than the fallback.
     *
     * [GameConfig.DEFAULTS] deliberately encodes the same §3.2-§7 numbers as `config.json`, so a
     * config that failed to parse would silently satisfy every other test in this class. Feeding
     * the loader the real file with one number changed is the only way to tell the two paths apart.
     */
    @Test
    fun theRealAssetIsParsedRatherThanQuietlyFallenBackOn() = runTest {
        val json = checkNotNull(realAssets.readText(ConfigLoader.DEFAULT_PATH)) {
            "config.json is unreadable"
        }
        val mutated = json.replace("\"gravity\": 1400", "\"gravity\": 1234")
        assertNotEquals("config.json no longer contains the expected gravity key", json, mutated)

        val config = ConfigLoader(AssetSource { mutated }).config()
        assertF(
            "gravity must come from the asset, not from GameConfig.DEFAULTS",
            1234f,
            config.physics.gravity,
        )
    }

    // -- §3.2 physics --------------------------------------------------------------------------

    @Test
    fun physicsConstantsMatchTheSection32Table() = runTest {
        val p = loader().config().physics

        assertF("ballRadius", 16f, p.ballRadius)
        assertF("pegRadius", 7f, p.pegRadius)
        assertF("candyRadius", 22f, p.candyRadius)
        assertF("gemRadius", 30f, p.gemRadius)
        assertF("gravity", 1400f, p.gravity)
        assertF("launchSpeed", 900f, p.launchSpeed)
        assertF("maxBallSpeed", 1600f, p.maxBallSpeed)
        assertF("restitutionPeg", 0.60f, p.restitutionPeg)
        assertF("restitutionGem", 0.65f, p.restitutionGem)
        assertF("restitutionWall", 0.50f, p.restitutionWall)
        assertF("tangentialDamping", 0.98f, p.tangentialDamping)
        assertF("physicsStepMs", 4.1667f, p.physicsStepMs)
        assertF("cupWidth", 210f, loader().config().cup.cupWidth)
    }

    @Test
    fun physicsSpecialRulesMatchSection33() = runTest {
        val p = loader().config().physics

        assertTrue("§11 requires a reproducible engine", p.deterministic)
        assertF("randomJitter must stay at zero for determinism", 0f, p.randomJitter)

        assertF("stuck-ball speed threshold", 30f, p.stuckBall.speedThreshold)
        assertF("stuck-ball dwell", 1.0f, p.stuckBall.dwellSeconds)
        assertF("stuck-ball impulse", 120f, p.stuckBall.impulseSpeed)

        assertEquals("moving pegs arrive in World 3", 3, p.movingPegs.introducedInWorld)
        assertF("tangential transfer", 0.50f, p.movingPegs.tangentialTransfer)

        // The §3.3 claim that swept collision is unnecessary only holds while the per-step
        // displacement stays under the smallest collider radius; assert the inequality, not a
        // comment about it.
        assertTrue(
            "max displacement per step must stay under the smallest collider radius",
            p.antiTunnelling.maxDisplacementPerStep < p.antiTunnelling.smallestColliderRadius,
        )
        assertF("1600 / 240", 6.67f, p.antiTunnelling.maxDisplacementPerStep)
        assertFalse(p.antiTunnelling.sweptCollisionRequired)
    }

    // -- §3.1 board ----------------------------------------------------------------------------

    @Test
    fun boardGeometryMatchesSection31() = runTest {
        val board = loader().config().board

        assertF("width", 1000f, board.width)
        assertF("height", 1250f, board.height)
        assertF("apex x", 500f, board.apex.x)
        assertF("apex y", 0f, board.apex.y)
        assertF("left wall ends at the base corner", 0f, board.walls.left.x2)
        assertF("right wall ends at the base corner", 1000f, board.walls.right.x2)
        assertF("wall slant", 21.8f, board.walls.slantDegreesFromVertical)
        assertF("d minimum", 66f, board.lattice.spacingMin)
        assertF("d maximum", 90f, board.lattice.spacingMax)
        assertF("aim clamp", 70f, board.launcher.aimClampDegrees)
    }

    // -- §4.2 gems -----------------------------------------------------------------------------

    @Test
    fun allSevenGemsParseWithTheirSection42Effects() = runTest {
        val config = loader().config()

        assertEquals(GemType.entries.size, config.gems.size)
        assertEquals(
            "every GemType must have a definition",
            GemType.entries.toSet(),
            config.gems.map { it.type }.toSet(),
        )

        assertEquals("Sweet Gem is a pure multiplier", GemEffect.None, effectOf(config, GemType.SWEET))
        assertEquals(GemEffect.PopRadius(2.5f), effectOf(config, GemType.BLAST))
        assertEquals(GemEffect.PopBand(0.5f), effectOf(config, GemType.LINE))
        assertEquals(
            GemEffect.SplitBall(clones = 1, mirrorHorizontalVelocity = true),
            effectOf(config, GemType.SPLIT),
        )
        assertEquals(GemEffect.RefundBalls(balls = 1), effectOf(config, GemType.EXTRA_BALL))
        assertEquals(
            GemEffect.Magnet(durationSeconds = 2.0f, radiusFactor = 3.0f),
            effectOf(config, GemType.MAGNET),
        )
        assertEquals(
            GemEffect.PopAllOfLargestColour(maxPerLevel = 1),
            effectOf(config, GemType.SUGAR_STORM),
        )
    }

    /**
     * Schema-drift alarm.
     *
     * [GemType] duplicates `multiplier` and `introLevel` from the JSON so gameplay code can reason
     * about a gem without loading the config. That duplication is only safe while the two agree —
     * this test is what makes editing one and not the other a red build instead of a silent
     * gameplay bug.
     */
    @Test
    fun jsonGemTableAgreesWithTheGemTypeEnum() = runTest {
        for (gem in loader().config().gems) {
            assertEquals(
                "${gem.type} multiplier: config.json and GemType disagree",
                gem.type.multiplier,
                gem.multiplier,
            )
            assertEquals(
                "${gem.type} introLevel: config.json and GemType disagree",
                gem.type.introLevel,
                gem.introLevel,
            )
            assertEquals(
                "${gem.type} sprite: config.json and GemType disagree",
                gem.type.spriteName,
                gem.sprite,
            )
        }
    }

    // -- §3.4 clearance ------------------------------------------------------------------------

    @Test
    fun clearanceRulesMatchSection34() = runTest {
        val clearance = loader().config().clearance
        assertEquals(4, clearance.rules.size)

        val byId = clearance.rules.associateBy { it.id }
        assertF("ball between pegs", 50.0f, byId.getValue("BALL_BETWEEN_PEGS").minSpacing ?: -1f)
        assertF(
            "candy in lattice gap",
            50.2f,
            byId.getValue("CANDY_IN_LATTICE_GAP").minSpacing ?: -1f,
        )
        assertF(
            "ball between gem and peg",
            73.0f,
            byId.getValue("BALL_BETWEEN_GEM_AND_PEG").minSpacing ?: -1f,
        )

        // The wall rule constrains the peg-edge margin, not d, so its minSpacing is null by design.
        val wallRule = byId.getValue("BALL_BETWEEN_PEG_AND_WALL")
        assertNull("wall rule does not constrain d", wallRule.minSpacing)
        assertF("peg edge to wall", 36f, wallRule.minPegEdgeToWall ?: -1f)
        assertEquals(
            ClearanceEnforcement.CLIP_OUTER_BORDER_PEGS,
            wallRule.enforcement,
        )
        assertEquals(
            "73 u exceeds the 66 u minimum d, so gem neighbours must be removed",
            ClearanceEnforcement.REMOVE_PEGS_ADJACENT_TO_GEM,
            byId.getValue("BALL_BETWEEN_GEM_AND_PEG").enforcement,
        )
    }

    // -- §6.1 worlds ---------------------------------------------------------------------------

    @Test
    fun fourWorldsMatchTheSection61Table() = runTest {
        val worlds = loader().config().worlds
        assertEquals(4, worlds.size)
        assertEquals(listOf(1, 2, 3, 4), worlds.map { it.index })

        assertEquals(
            "level ranges",
            listOf(1 to 10, 11 to 20, 21 to 30, 31 to 40),
            worlds.map { it.levelFrom to it.levelTo },
        )
        assertEquals(
            "peg tints",
            listOf("#FF4FC8", "#3FE3FF", "#A56BFF", "#FFC23F"),
            worlds.map { it.pegTint },
        )
        assertEquals(
            "peg tint names",
            listOf("Pink", "Cyan", "Violet", "Gold"),
            worlds.map { it.pegTintName },
        )
        assertEquals(
            "backdrops are world_N — a resource name cannot start with a digit",
            listOf("world_1", "world_2", "world_3", "world_4"),
            worlds.map { it.backdrop },
        )
        assertEquals(
            "cup speeds",
            listOf(200f, 260f, 320f, 380f),
            worlds.map { it.cupSpeed },
        )
        assertEquals(
            "moving peg rows arrive in World 3",
            listOf(false, false, true, true),
            worlds.map { it.movingPegRows },
        )

        // The hex string is parsed here rather than by android.graphics.Color, which would silently
        // return 0 under isReturnDefaultValues.
        assertEquals("World 1 pink as packed ARGB", 0xFFFF4FC8.toInt(), worlds[0].pegTintArgb)
    }

    @Test
    fun cupSpeedForResolvesPerWorld() = runTest {
        val config = loader().config()
        assertF("world 1", 200f, config.cupSpeedFor(1))
        assertF("world 4", 380f, config.cupSpeedFor(4))
        assertF("unknown world falls back to the slowest lane", 200f, config.cupSpeedFor(9))
    }

    // -- §6.2 gates ----------------------------------------------------------------------------

    @Test
    fun threeGatesMatchTheSection62Table() = runTest {
        val gates = loader().config().gates
        assertEquals("World 1 is open, so there are three gates", 3, gates.size)

        assertEquals(
            listOf(2 to 15, 3 to 35, 4 to 55),
            gates.map { it.world to it.crownsRequired },
        )
        assertEquals(
            "each gate requires the previous world's last level",
            listOf(10, 20, 30),
            gates.map { it.requiresLevelCleared },
        )
        assertEquals(listOf(30, 60, 90), gates.map { it.maxAvailableCrowns })
    }

    // -- §7 Candy Jar --------------------------------------------------------------------------

    @Test
    fun jarThresholdsAndRewardsMatchSection7() = runTest {
        val jar = loader().config().jar

        assertEquals(listOf(40, 120, 200), jar.tierThresholds)
        assertEquals(40, jar.tier1Threshold)
        assertEquals(120, jar.tier2Threshold)
        assertEquals(200, jar.tier3Threshold)
        assertEquals("candies bank even from a failed level", true, jar.bankCandiesOnFailedLevels)
        assertEquals(15, jar.sweetRoomBalls)

        assertEquals(4, jar.jars.size)
        assertEquals(
            listOf(CandyColor.GREEN, CandyColor.PURPLE, CandyColor.PINK, CandyColor.BLUE),
            jar.jars.map { it.color },
        )

        // The deliberate §7 quirk: Pink's tier 1 is the GOLD ball skin, not a pink one.
        val pink = checkNotNull(jar.jars.firstOrNull { it.color == CandyColor.PINK })
        assertEquals("Pink jar tier 1 is GOLD, per §7", BallSkin.GOLD, pink.tier1BallSkin)
        assertEquals(TrailType.PINK, pink.tier2Trail)
        assertEquals("B3", pink.tier3SweetRoomCode)
        assertEquals("B3 maps into the LevelIds keyspace", 103, pink.tier3SweetRoomLevelId)

        assertEquals(
            "the other three jars award their own colour",
            listOf(BallSkin.GREEN, BallSkin.PURPLE, BallSkin.BLUE),
            jar.jars.filter { it.color != CandyColor.PINK }.map { it.tier1BallSkin },
        )
        assertEquals(
            listOf(101, 102, 103, 104),
            jar.jars.map { it.tier3SweetRoomLevelId },
        )
    }

    // -- derived lookups -----------------------------------------------------------------------

    @Test
    fun spacingInterpolatesAcrossItsWorld() = runTest {
        val config = loader().config()

        assertF("world 1 opens at 90", 90f, config.spacingFor(1))
        assertF("world 1 closes at 84", 84f, config.spacingFor(10))
        assertF("world 2 opens where world 1 closed", 84f, config.spacingFor(11))
        assertF("world 4 closes at the 66 u minimum", 66f, config.spacingFor(40))

        // Level 5 sits 4/9 of the way through world 1: 90 + (84 - 90) * 4 / 9.
        assertF("linear in between", 90f - 6f * 4f / 9f, config.spacingFor(5))

        // Monotonically non-increasing across the whole campaign: boards only get denser.
        val curve = (LevelIds.MAIN_FIRST..LevelIds.MAIN_LAST).map(config::spacingFor)
        assertTrue(
            "spacing must never widen as levels advance",
            curve.zipWithNext().all { (a, b) -> b <= a + 1e-4f },
        )
        assertTrue(
            "spacing must stay within the lattice bounds",
            curve.all { it in config.board.lattice.spacingMin..config.board.lattice.spacingMax },
        )
    }

    @Test
    fun ballsInterpolateAcrossItsWorld() = runTest {
        val config = loader().config()

        assertEquals("world 1 opens at 12", 12, config.ballsFor(1))
        assertEquals("world 1 closes at 10", 10, config.ballsFor(10))
        assertEquals("world 2 opens at 11", 11, config.ballsFor(11))
        assertEquals("world 2 closes at 9", 9, config.ballsFor(20))
        assertEquals("world 4 opens at 10", 10, config.ballsFor(31))
        assertEquals("world 4 closes at 8", 8, config.ballsFor(40))
        // 12 + (10 - 12) * 4 / 9 = 11.11, rounded to a whole ball.
        assertEquals("rounded to whole balls", 11, config.ballsFor(5))
    }

    @Test
    fun worldForResolvesEveryMainLevel() = runTest {
        val config = loader().config()
        for (id in LevelIds.MAIN_FIRST..LevelIds.MAIN_LAST) {
            val world = config.worldFor(id)
            assertNotNull("level $id has no world", world)
            assertEquals("level $id is in the wrong world", (id - 1) / 10 + 1, world?.index)
        }
    }

    /** Sweet Rooms share the level-id keyspace but sit outside the four worlds (§7). */
    @Test
    fun sweetRoomsHaveNoWorldAndFallBackSensibly() = runTest {
        val config = loader().config()

        for (id in LevelIds.BONUS_FIRST..LevelIds.BONUS_LAST) {
            assertNull("Sweet Room $id must not claim a world", config.worldFor(id))
            assertEquals(
                "a Sweet Room uses the §7 bonus ball count",
                config.jar.sweetRoomBalls,
                config.ballsFor(id),
            )
            assertF(
                "a Sweet Room gets the most forgiving lattice",
                config.board.lattice.spacingMax,
                config.spacingFor(id),
            )
        }
    }

    @Test
    fun gemLookupFindsEveryType() = runTest {
        val config = loader().config()
        for (type in GemType.entries) {
            assertNotNull("no definition for $type", config.gem(type))
        }
    }

    // -- degradation ---------------------------------------------------------------------------

    /**
     * The launch-path guarantee: an absent asset must produce the built-in §3.2-§7 tables, not an
     * exception. A config regression costs the tuning, never the app.
     */
    @Test
    fun missingAssetFallsBackToTheBuiltInDefaults() = runTest {
        val config = ConfigLoader(AssetSource { null }).config()

        assertEquals(GameConfig.DEFAULTS, config)
        assertF("defaults still carry §3.2", 16f, config.physics.ballRadius)
        assertF("defaults still carry §3.2", 1400f, config.physics.gravity)
        assertEquals("defaults still carry §4.2", 7, config.gems.size)
        assertEquals("defaults still carry §6.1", 4, config.worlds.size)
        assertEquals("defaults still carry §6.2", 3, config.gates.size)
        assertF("derived lookups work off the defaults", 90f, config.spacingFor(1))
        assertEquals(
            "the §7 Pink/GOLD quirk survives into the defaults",
            BallSkin.GOLD,
            config.jar.jars.first { it.color == CandyColor.PINK }.tier1BallSkin,
        )
    }

    @Test
    fun malformedJsonFallsBackToTheBuiltInDefaults() = runTest {
        val config = ConfigLoader(AssetSource { "{ this is not json" }).config()
        assertEquals(GameConfig.DEFAULTS, config)
    }

    @Test
    fun partialJsonKeepsWhatItSuppliesAndDefaultsTheRest() = runTest {
        val config = ConfigLoader(
            AssetSource { """{ "configVersion": 9, "physics": { "gravity": 2000 } }""" },
        ).config()

        assertEquals(9, config.configVersion)
        assertF("the supplied key wins", 2000f, config.physics.gravity)
        assertF("a sibling key still defaults", 16f, config.physics.ballRadius)
        assertEquals("an absent section defaults wholesale", 4, config.worlds.size)
    }

    @Test
    fun configIsParsedOnceAndCached() = runTest {
        var reads = 0
        val counting = AssetSource { path ->
            reads++
            realAssets.readText(path)
        }
        val loader = ConfigLoader(counting)

        val first = loader.config()
        val second = loader.config()

        assertEquals("the asset must be read exactly once", 1, reads)
        assertTrue("the cache must hand back the same instance", first === second)
    }

    private companion object {

        /** Generous enough for float round-tripping, tight enough to catch a real retune. */
        const val DELTA = 1e-4

        fun assertF(message: String, expected: Float, actual: Float) =
            assertEquals(message, expected.toDouble(), actual.toDouble(), DELTA)

        fun effectOf(config: GameConfig, type: GemType): GemEffect =
            checkNotNull(config.gem(type)) { "no definition for $type" }.effect
    }
}
