package app.krafted.candytriangle.level

import app.krafted.candytriangle.data.AssetSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only coverage for the `levels.json` schema (§6.3) and its loader.
 *
 * Runs without Robolectric or an emulator per §11; `android.util.Log` is satisfied by the
 * module's `unitTests.isReturnDefaultValues` setting, so the tolerance paths below really do
 * execute their log lines.
 *
 * The happy-path fixture lives in `test/resources/levels_fixture.json` and doubles as C1's
 * reference for what a well-formed catalogue looks like. Malformed input is kept in inline
 * strings here so the fixture stays clean.
 */
class LevelRepositoryTest {

    private fun fixtureJson(): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("levels_fixture.json")) {
            "levels_fixture.json is missing from the test resources"
        }.readBytes().toString(Charsets.UTF_8)

    private fun fixtureRepository() = LevelRepository(AssetSource { fixtureJson() })

    private fun repositoryOf(json: String) = LevelRepository(AssetSource { json })

    /** A level the test knows must be there — a missing one is a broken fixture, not a failure. */
    private suspend fun LevelRepository.requireLevel(id: Int): LevelDef =
        checkNotNull(level(id)) { "expected level $id in the catalogue" }

    // ------------------------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------------------------

    @Test
    fun `parses every field of a full layout level`() = runTest {
        val level = fixtureRepository().requireLevel(3)

        assertEquals(3, level.id)
        assertEquals(1, level.world)
        assertEquals(12, level.balls)
        assertFalse(level.isBonus)
        assertNull(level.code)

        // §5.3: crowns is [twoCrownBallsRemaining, threeCrownBallsRemaining].
        assertEquals(listOf(3, 6), level.crowns)
        assertEquals(3, level.twoCrownBalls)
        assertEquals(6, level.threeCrownBalls)

        assertEquals(LayoutPattern.FULL, level.layout.pattern)
        assertEquals(90f, level.layout.spacing, 0f)
        // JSON [[3,2],[3,3],[6,1]] becomes RowCol triples, in authored order.
        assertEquals(
            listOf(RowCol(3, 2), RowCol(3, 3), RowCol(6, 1)),
            level.layout.holes,
        )
        assertTrue(level.layout.clusters.isEmpty())
        assertTrue(level.layout.movingRows.isEmpty())

        assertEquals(1407L, level.candies.seed)
        assertEquals(24, level.candies.count)
        assertEquals(
            mapOf(
                CandyColor.GREEN to 1,
                CandyColor.PURPLE to 1,
                CandyColor.PINK to 2,
                CandyColor.BLUE to 1,
            ),
            level.candies.weights,
        )
        assertEquals(listOf(FixedCandy(CandyColor.PINK, 9, 4)), level.candies.fixed)

        assertEquals(
            listOf(
                GemPlacementDef(GemType.SWEET, 6, 4),
                GemPlacementDef(GemType.SWEET, 10, 2),
            ),
            level.gems,
        )

        assertEquals(200f, level.cup.speed, 0f)

        assertEquals(
            listOf(ObjectiveDef(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, 14, null, null)),
            level.objectives,
        )
    }

    @Test
    fun `parses a clusters layout in both authoring forms`() = runTest {
        val layout = fixtureRepository().requireLevel(14).layout

        assertEquals(LayoutPattern.CLUSTERS, layout.pattern)
        assertEquals(2, layout.clusters.size)

        // Block form: 3 rows x 4 cols anchored at (4, 1).
        val block = layout.clusters[0]
        assertEquals(ClusterDef(row = 4, col = 1, rows = 3, cols = 4, nodes = emptyList()), block)
        assertEquals(12, block.resolveNodes().size)
        assertEquals(RowCol(4, 1), block.resolveNodes().first())
        assertEquals(RowCol(6, 4), block.resolveNodes().last())

        // Explicit form: nodes win over the (defaulted) block extent.
        val explicit = layout.clusters[1]
        assertEquals(
            listOf(RowCol(9, 2), RowCol(9, 3), RowCol(10, 2)),
            explicit.resolveNodes(),
        )
    }

    @Test
    fun `parses moving rows and applies the config defaults`() = runTest {
        val level = fixtureRepository().requireLevel(27)

        assertEquals(
            listOf(
                MovingRowDef(row = 7, amplitude = 48f, periodSeconds = 2.5f),
                // Only "row" authored: A and T fall back to config.physics.movingPegs defaults.
                MovingRowDef(
                    row = 11,
                    amplitude = LevelDefaults.MOVING_ROW_AMPLITUDE,
                    periodSeconds = LevelDefaults.MOVING_ROW_PERIOD_SECONDS,
                ),
            ),
            level.layout.movingRows,
        )
    }

    @Test
    fun `fills in omitted world, cup and candy weights`() = runTest {
        val level = fixtureRepository().requireLevel(27)

        // §6.1 packs ten levels per world, so id 27 lands in World 3 without being told.
        assertEquals(3, level.world)
        assertEquals(LevelDefaults.CUP_SPEED, level.cup.speed, 0f)
        assertEquals(LevelDefaults.UNIFORM_CANDY_WEIGHTS, level.candies.weights)
        assertTrue(level.candies.fixed.isEmpty())
        assertTrue(level.layout.clusters.isEmpty())
    }

    @Test
    fun `supports two-objective levels`() = runTest {
        val objectives = fixtureRepository().requireLevel(27).objectives

        assertEquals(
            listOf(
                ObjectiveDef(ObjectiveType.SCORE, null, null, null, 18000, null),
                ObjectiveDef(ObjectiveType.COLLECT_GEM, null, GemType.MAGNET, 1, null, null),
            ),
            objectives,
        )
    }

    // ------------------------------------------------------------------------------------
    // The Int keyspace
    // ------------------------------------------------------------------------------------

    @Test
    fun `derives bonus flag and code from the id`() = runTest {
        val repository = fixtureRepository()

        val sweetRoom = repository.requireLevel(101)
        assertTrue(sweetRoom.isBonus)
        assertEquals("B1", sweetRoom.code)
        // Sweet Rooms sit outside the four panorama worlds (§6.1 lists 1-4 only).
        assertEquals(LevelDefaults.BONUS_WORLD, sweetRoom.world)
        assertEquals(15, sweetRoom.balls)

        val main = repository.requireLevel(3)
        assertFalse(main.isBonus)
        assertNull(main.code)
    }

    @Test
    fun `keeps a zero weight so a sweet room can flood one colour`() = runTest {
        val weights = fixtureRepository().requireLevel(101).candies.weights

        assertEquals(1, weights[CandyColor.GREEN])
        assertEquals(0, weights[CandyColor.PINK])
    }

    @Test
    fun `partitions the catalogue by id range and world`() = runTest {
        val repository = fixtureRepository()

        assertEquals(listOf(3, 14, 27), repository.mainLevels().map { it.id })
        assertEquals(listOf(101), repository.sweetRooms().map { it.id })

        assertEquals(listOf(3), repository.levelsInWorld(1).map { it.id })
        assertEquals(listOf(14), repository.levelsInWorld(2).map { it.id })
        assertEquals(listOf(27), repository.levelsInWorld(3).map { it.id })
        assertTrue(repository.levelsInWorld(4).isEmpty())

        assertNull(repository.level(999))
    }

    // ------------------------------------------------------------------------------------
    // Degraded inputs
    // ------------------------------------------------------------------------------------

    @Test
    fun `returns an empty catalogue when the asset is absent`() = runTest {
        // The expected state until C1 authors assets levels.json — must not throw.
        val repository = LevelRepository(AssetSource { null })

        assertEquals(LevelCatalog.EMPTY, repository.catalog())
        assertTrue(repository.mainLevels().isEmpty())
        assertTrue(repository.sweetRooms().isEmpty())
        assertNull(repository.level(1))
    }

    @Test
    fun `returns an empty catalogue when the json is unparseable`() = runTest {
        assertEquals(LevelCatalog.EMPTY, repositoryOf(TRUNCATED_JSON).catalog())
        assertEquals(LevelCatalog.EMPTY, repositoryOf("\"a bare string\"").catalog())
    }

    @Test
    fun `skips malformed entries instead of dropping the level`() = runTest {
        val level = repositoryOf(MALFORMED_JSON).requireLevel(5)

        // An unknown scalar enum falls back to a documented default...
        assertEquals(LayoutPattern.FULL, level.layout.pattern)
        // ...while malformed list entries are dropped one by one.
        assertEquals(listOf(RowCol(1, 1)), level.layout.holes)
        assertEquals(mapOf(CandyColor.GREEN to 1), level.candies.weights)
        assertEquals(listOf(FixedCandy(CandyColor.BLUE, 3, 1)), level.candies.fixed)
        assertEquals(listOf(GemPlacementDef(GemType.BLAST, 4, 2)), level.gems)
        assertEquals(
            listOf(ObjectiveDef(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, 8, null, null)),
            level.objectives,
        )
    }

    @Test
    fun `drops a level with no id and keeps the first of duplicate ids`() = runTest {
        val catalog = repositoryOf(MALFORMED_JSON).catalog()

        assertEquals(listOf(5), catalog.levels.map { it.id })
        assertEquals(11, catalog.level(5)!!.balls)
    }

    @Test
    fun `clamps spacing into the lattice density band`() = runTest {
        // §3.4's clearance guarantees are all stated for d >= 66; a typo must not void them.
        val catalog = repositoryOf(
            """[{ "id": 1, "layout": { "spacing": 8 } }, { "id": 2, "layout": { "spacing": 400 } }]""",
        ).catalog()

        assertEquals(LevelDefaults.MIN_SPACING, catalog.level(1)!!.layout.spacing, 0f)
        assertEquals(LevelDefaults.MAX_SPACING, catalog.level(2)!!.layout.spacing, 0f)
    }

    @Test
    fun `accepts a bare top-level array`() = runTest {
        val catalog = repositoryOf(
            """[{ "id": 7, "balls": 11, "crowns": [3, 5] }]""",
        ).catalog()

        assertEquals(1, catalog.levels.size)
        assertEquals(11, catalog.level(7)!!.balls)
        assertEquals(LevelDefaults.SCHEMA_VERSION, catalog.schemaVersion)
    }

    // ------------------------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------------------------

    @Test
    fun `parses the asset exactly once`() = runTest {
        var reads = 0
        var requestedPath: String? = null
        val repository = LevelRepository(
            AssetSource { path ->
                reads++
                requestedPath = path
                fixtureJson()
            },
        )

        repository.catalog()
        repository.catalog()
        repository.level(3)
        repository.mainLevels()
        repository.sweetRooms()
        repository.levelsInWorld(2)

        assertEquals(1, reads)
        assertEquals(LevelRepository.DEFAULT_ASSET_PATH, requestedPath)
    }

    @Test
    fun `parses once even when several callers race for the first read`() = runTest {
        var reads = 0
        val repository = LevelRepository(
            AssetSource {
                reads++
                fixtureJson()
            },
        )

        coroutineScope { repeat(8) { launch { repository.catalog() } } }

        assertEquals(1, reads)
    }

    private companion object {

        /** Cut off mid-object: Gson raises, and the loader must swallow it. */
        const val TRUNCATED_JSON = """{ "schemaVersion": 1, "levels": [ { "id": 1,"""

        /**
         * Every tolerance rule in one level, plus an id-less level and a duplicate id.
         *
         * Deliberately ugly, and deliberately not in the fixture file: C1 reads that file as the
         * schema reference, so the "what not to do" cases stay here.
         */
        const val MALFORMED_JSON = """
        {
          "schemaVersion": 1,
          "levels": [
            {
              "id": 5,
              "world": 1,
              "balls": 11,
              "crowns": [3, 5],
              "layout": {
                "spacing": 88,
                "pattern": "SPIRAL",
                "holes": [[1, 1], [2], [4, null]],
                "clusters": [{ "rows": 2 }],
                "movingRows": [{ "amplitude": 30 }]
              },
              "candies": {
                "seed": 77,
                "count": 12,
                "weights": { "GREEN": 1, "RAINBOW": 5, "BLUE": -3 },
                "fixed": [
                  { "color": "TEAL", "row": 2, "col": 2 },
                  { "color": "BLUE", "row": 3, "col": 1 }
                ]
              },
              "gems": [
                { "type": "BLAST", "row": 4, "col": 2 },
                { "type": "NOT_A_GEM", "row": 5, "col": 1 },
                { "type": "LINE", "col": 3 }
              ],
              "cup": { "speed": 200 },
              "objectives": [
                { "type": "COLLECT_CANDY", "color": "PINK", "count": 8 },
                { "type": "TELEPORT", "count": 3 },
                { "type": "CLEAR_COLOR" },
                { "type": "COLLECT_GEM", "gem": "UNOBTAINIUM", "count": 2 },
                { "type": "COLLECT_CANDY", "color": "MAUVE", "count": 4 }
              ]
            },
            { "id": 5, "balls": 4 },
            { "world": 2, "balls": 9 }
          ]
        }
        """
    }
}
