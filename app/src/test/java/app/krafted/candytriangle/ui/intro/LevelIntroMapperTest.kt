package app.krafted.candytriangle.ui.intro

import app.krafted.candytriangle.R
import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.DEFAULT_GEMS
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelDefaults
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.MovingRowDef
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.verification.LevelFailures
import app.krafted.candytriangle.verification.RealLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers behind the pre-level intro (D3): each §6.3 objective flattened for display, the §5.3
 * crown thresholds, the player's crowns and best, and which gems the dialog flags as new.
 *
 * Pure JVM, like `JarUiStateMapperTest`: §11 rules out an emulator and Robolectric, so no
 * composable can run here, which is exactly why every decision `LevelIntroDialog` paints is made in
 * [LevelIntroMapper]. The last section runs the mapper over the shipped `levels.json` and
 * `config.json` through [RealLevels], the production loaders the C2 suite uses.
 */
class LevelIntroMapperTest {

    private val fresh = PlayerProgress()

    private fun intro(
        level: LevelDef,
        progress: PlayerProgress = fresh,
        config: GameConfig? = null,
    ): LevelIntroUiState = LevelIntroMapper.map(level, progress, config)

    private fun onlyObjective(def: ObjectiveDef): IntroObjective =
        intro(level(objectives = listOf(def))).objectives.single()

    // ---------------------------------------------------------------- objectives, per type

    @Test
    fun collectCandyTargetsItsCountAndCarriesItsColour() {
        val pink = onlyObjective(objective(ObjectiveType.COLLECT_CANDY, color = CandyColor.PINK, count = 14))
        assertEquals(IntroObjective(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, 14, 1), pink)

        // No colour means "any colour" and must stay null — the dialog draws the any-candy icon.
        val any = onlyObjective(objective(ObjectiveType.COLLECT_CANDY, count = 11))
        assertEquals(IntroObjective(ObjectiveType.COLLECT_CANDY, null, null, 11, 1), any)
    }

    @Test
    fun scoreTargetsItsPointsNotACount() {
        val score = onlyObjective(objective(ObjectiveType.SCORE, score = 25_000))
        assertEquals(IntroObjective(ObjectiveType.SCORE, null, null, 25_000, 1), score)
    }

    @Test
    fun collectGemTargetsItsCountAndCarriesItsGem() {
        val blast = onlyObjective(objective(ObjectiveType.COLLECT_GEM, gem = GemType.BLAST, count = 2))
        assertEquals(IntroObjective(ObjectiveType.COLLECT_GEM, null, GemType.BLAST, 2, 1), blast)

        val any = onlyObjective(objective(ObjectiveType.COLLECT_GEM, count = 3))
        assertEquals(IntroObjective(ObjectiveType.COLLECT_GEM, null, null, 3, 1), any)
    }

    /** No count to show: `ObjectiveTracker` tests the board, not a counter (D1 note 4). */
    @Test
    fun clearColorHasNoTargetAndCarriesItsColour() {
        val clear = onlyObjective(objective(ObjectiveType.CLEAR_COLOR, color = CandyColor.BLUE))
        assertEquals(IntroObjective(ObjectiveType.CLEAR_COLOR, CandyColor.BLUE, null, 0, 1), clear)
    }

    /** `chain` is the length, `count` how many such chains — L24's "chain of 3, twice". */
    @Test
    fun chainTargetsItsLengthAndCountsItsRepeatsInTimes() {
        val twice = onlyObjective(objective(ObjectiveType.CHAIN, chain = 3, count = 2))
        assertEquals(IntroObjective(ObjectiveType.CHAIN, null, null, 3, 2), twice)

        val once = onlyObjective(objective(ObjectiveType.CHAIN, chain = 4, count = 1))
        assertEquals(4, once.target)
        assertEquals(1, once.times)
    }

    /** `count` is optional for CHAIN (§6.3); absent, zero or negative, it is one chain. */
    @Test
    fun chainTimesDefaultsToOneAndNeverDropsBelowOne() {
        for (count in listOf(null, 0, -2)) {
            val chain = onlyObjective(objective(ObjectiveType.CHAIN, chain = 3, count = count))
            assertEquals("count $count", 1, chain.times)
            assertEquals("count $count", 3, chain.target)
        }
    }

    @Test
    fun cupTargetsItsCatches() {
        val cup = onlyObjective(objective(ObjectiveType.CUP, count = 2))
        assertEquals(IntroObjective(ObjectiveType.CUP, null, null, 2, 1), cup)
    }

    /** `times` belongs to CHAIN alone; a COLLECT_CANDY's `count` is its target, not a repeat. */
    @Test
    fun everyTypeButChainCountsOnce() {
        val defs = listOf(
            objective(ObjectiveType.COLLECT_CANDY, count = 9),
            objective(ObjectiveType.SCORE, score = 3_000),
            objective(ObjectiveType.COLLECT_GEM, count = 5),
            objective(ObjectiveType.CLEAR_COLOR, color = CandyColor.GREEN, count = 7),
            objective(ObjectiveType.CUP, count = 4),
        )
        for (objective in intro(level(objectives = defs)).objectives) {
            assertEquals("${objective.type}", 1, objective.times)
        }
    }

    /** §6.1: World 4 levels carry two objectives, and the dialog lists them as authored. */
    @Test
    fun objectivesKeepTheirAuthoredOrder() {
        val defs = listOf(
            objective(ObjectiveType.CHAIN, chain = 4, count = 1),
            objective(ObjectiveType.COLLECT_CANDY, color = CandyColor.GREEN, count = 20),
            objective(ObjectiveType.SCORE, score = 50_000),
        )
        val types = intro(level(objectives = defs)).objectives.map { it.type }
        assertEquals(
            listOf(ObjectiveType.CHAIN, ObjectiveType.COLLECT_CANDY, ObjectiveType.SCORE),
            types,
        )
    }

    /**
     * `LevelDef`'s mapper drops an objective missing its required field, so only a hand-built def
     * can get here. It falls back to exactly what `ObjectiveTracker` enforces (`?: 1`), never to a
     * target the session would not hold the player to.
     */
    @Test
    fun aMissingRequiredFieldFallsBackToTheTrackersOwnDefault() {
        val defs = listOf(
            objective(ObjectiveType.COLLECT_CANDY),
            objective(ObjectiveType.SCORE),
            objective(ObjectiveType.COLLECT_GEM),
            objective(ObjectiveType.CHAIN),
            objective(ObjectiveType.CUP),
        )
        for (objective in intro(level(objectives = defs)).objectives) {
            assertEquals("${objective.type}", 1, objective.target)
        }
    }

    @Test
    fun aLevelWithNoObjectivesMapsToAnEmptyList() {
        assertEquals(emptyList<IntroObjective>(), intro(level(objectives = emptyList())).objectives)
    }

    // ---------------------------------------------------------------- balls and §5.3 crowns

    @Test
    fun ballsAndCrownThresholdsComeStraightFromTheLevel() {
        val state = intro(level(balls = 9, crowns = listOf(2, 5)))
        assertEquals(9, state.balls)
        assertEquals(2, state.twoCrownBalls)
        assertEquals(5, state.threeCrownBalls)
    }

    @Test
    fun crownsAndBestScoreComeFromThePlayersProgressForThisLevelOnly() {
        val progress = PlayerProgress(
            crownsByLevel = mapOf(12 to 2, 13 to 3),
            bestScoreByLevel = mapOf(12 to 12_450, 13 to 99_999),
        )
        val state = intro(level(id = 12), progress)
        assertEquals(2, state.crownsEarned)
        assertEquals(12_450, state.bestScore)
    }

    @Test
    fun aNeverPlayedLevelHasNoCrownsAndNoBest() {
        val state = intro(level(id = 12))
        assertEquals(0, state.crownsEarned)
        assertEquals(0, state.bestScore)
    }

    /**
     * `ProgressStore` clamps on write, so neither value can come from a healthy save — but a
     * corrupt or hand-edited one must still render: never a fourth lit crown, never a negative best.
     */
    @Test
    fun crownsClampIntoZeroToThreeAndANegativeBestReadsAsZero() {
        val over = PlayerProgress(crownsByLevel = mapOf(12 to 7), bestScoreByLevel = mapOf(12 to -40))
        assertEquals(3, intro(level(id = 12), over).crownsEarned)
        assertEquals(0, intro(level(id = 12), over).bestScore)

        val under = PlayerProgress(crownsByLevel = mapOf(12 to -1))
        assertEquals(0, intro(level(id = 12), under).crownsEarned)
    }

    /** A Sweet Room keeps its own crowns and best in the shared Int keyspace (A2 note 1). */
    @Test
    fun aSweetRoomReadsItsProgressByItsOwnId() {
        val progress = PlayerProgress(
            crownsByLevel = mapOf(102 to 3, 2 to 1),
            bestScoreByLevel = mapOf(102 to 41_000, 2 to 900),
        )
        val state = intro(sweetRoom(id = 102), progress)
        assertEquals(3, state.crownsEarned)
        assertEquals(41_000, state.bestScore)
    }

    // ---------------------------------------------------------------- gems

    @Test
    fun gemsAreDistinctAndInDeclarationOrderNotAuthoredOrder() {
        val authored = listOf(GemType.MAGNET, GemType.SWEET, GemType.MAGNET, GemType.BLAST, GemType.SWEET)
        val state = intro(level(id = 30, gems = authored))
        assertEquals(listOf(GemType.SWEET, GemType.BLAST, GemType.MAGNET), state.gems)
    }

    @Test
    fun aBoardWithNoGemsHasNoGemsAndNothingNew() {
        val state = intro(level(id = 3, gems = emptyList()))
        assertEquals(emptyList<GemType>(), state.gems)
        assertEquals(emptyList<GemType>(), state.newGems)
    }

    @Test
    fun newGemsAreTheGemsIntroducedOnThisVeryLevel() {
        val introLevel = intro(level(id = 6, gems = listOf(GemType.BLAST, GemType.SWEET)))
        assertEquals(listOf(GemType.BLAST), introLevel.newGems)

        // The same board one level later: nothing is new any more.
        val nextLevel = intro(level(id = 7, gems = listOf(GemType.BLAST, GemType.SWEET)))
        assertEquals(emptyList<GemType>(), nextLevel.newGems)
    }

    /** The "New" badge sits on the gem row, so a gem that is not on the board is never flagged. */
    @Test
    fun aGemMissingFromTheBoardIsNeverFlaggedNew() {
        val state = intro(level(id = 6, gems = listOf(GemType.SWEET)))
        assertEquals(emptyList<GemType>(), state.newGems)
    }

    /** Several gems sharing an intro level are all flagged, in declaration order. */
    @Test
    fun everyGemIntroducedHereIsFlaggedInDeclarationOrder() {
        val gems = DEFAULT_GEMS.map { if (it.type == GemType.MAGNET) it.copy(introLevel = 21) else it }
        val state = intro(
            level(id = 21, gems = listOf(GemType.MAGNET, GemType.EXTRA_BALL, GemType.SWEET)),
            config = GameConfig(gems = gems),
        )
        assertEquals(listOf(GemType.EXTRA_BALL, GemType.MAGNET), state.newGems)
    }

    /** `config.json` is the live tuning surface: moving a gem's intro level moves its badge. */
    @Test
    fun aConfiguredIntroLevelBeatsTheEnums() {
        val gems = DEFAULT_GEMS.map { if (it.type == GemType.SWEET) it.copy(introLevel = 4) else it }
        val config = GameConfig(gems = gems)
        val board = listOf(GemType.SWEET)

        assertEquals(emptyList<GemType>(), intro(level(id = 3, gems = board), config = config).newGems)
        assertEquals(listOf(GemType.SWEET), intro(level(id = 4, gems = board), config = config).newGems)
        // Under the enum's §4.2 table the same board is new at level 3, so the config really won.
        assertEquals(listOf(GemType.SWEET), intro(level(id = 3, gems = board)).newGems)
    }

    /** A config that drops a gem entirely falls back to [GemType.introLevel] for it. */
    @Test
    fun aGemTheConfigDroppedFallsBackToTheEnumsIntroLevel() {
        val config = GameConfig(gems = DEFAULT_GEMS.filter { it.type != GemType.BLAST })
        val state = intro(level(id = 6, gems = listOf(GemType.BLAST)), config = config)
        assertEquals(listOf(GemType.BLAST), state.newGems)
    }

    /** `null` means the asset parse has not landed yet and must give §4.2's shipped table. */
    @Test
    fun aNullConfigMapsExactlyLikeTheDefaults() {
        val board = level(id = 11, gems = listOf(GemType.LINE, GemType.SWEET))
        assertEquals(intro(board, config = GameConfig.DEFAULTS), intro(board, config = null))
        assertEquals(listOf(GemType.LINE), intro(board, config = null).newGems)
    }

    // ---------------------------------------------------------------- identity and board

    @Test
    fun movingRowsAreFlagged() {
        val swinging = level(movingRows = listOf(MovingRowDef(row = 7, amplitude = 40f, periodSeconds = 3f)))
        assertTrue(intro(swinging).hasMovingRows)
        assertFalse(intro(level(movingRows = emptyList())).hasMovingRows)
    }

    @Test
    fun aMainLevelHasNoCodeAndKeepsItsWorld() {
        val state = intro(level(id = 12, world = 2))
        assertEquals(12, state.levelId)
        assertNull(state.levelCode)
        assertEquals(2, state.world)
        assertFalse(state.isSweetRoom)
    }

    /** `LevelDef.code` is whatever the JSON authored; a main level must still come out `null`. */
    @Test
    fun aStrayCodeOnAMainLevelIsDropped() {
        assertNull(intro(level(id = 7, code = "L7")).levelCode)
    }

    @Test
    fun aSweetRoomCarriesItsBonusCodeAndWorldZero() {
        val state = intro(sweetRoom(id = 101))
        assertEquals("B1", state.levelCode)
        assertEquals(0, state.world)
        assertTrue(state.isSweetRoom)
        assertEquals(15, state.balls)
    }

    /** The code is derived from the id range, so a hand-built Sweet Room with no code still has one. */
    @Test
    fun aSweetRoomWithNoAuthoredCodeStillGetsOne() {
        assertEquals("B4", intro(sweetRoom(id = 104, code = null)).levelCode)
    }

    // ---------------------------------------------------------------- sprite tables

    /**
     * `ObjectiveIcons` maps colours and gems to drawables through an explicit `when`, never
     * `Resources.getIdentifier` (resource shrinking cannot trace a name built at runtime, and each
     * call is a reflective lookup). The enums' `spriteName` is the §9.1 source of truth, so the two
     * tables are pinned together here, through the generated `R` class.
     */
    @Test
    fun spriteTablesAgreeWithTheEnumsSpriteNames() {
        for (color in CandyColor.entries) {
            assertEquals("$color", drawableId(color.spriteName), color.spriteRes())
        }
        for (gem in GemType.entries) {
            assertEquals("$gem", drawableId(gem.spriteName), gem.spriteRes())
        }
    }

    // ---------------------------------------------------------------- the shipped catalogue

    /** §4.2's introductions, on the real boards: each intro level flags its own gem and no other. */
    @Test
    fun eachRealIntroLevelFlagsExactlyItsOwnGem() {
        val expected = linkedMapOf(
            3 to GemType.SWEET,
            6 to GemType.BLAST,
            11 to GemType.LINE,
            15 to GemType.SPLIT,
            21 to GemType.EXTRA_BALL,
            25 to GemType.MAGNET,
            31 to GemType.SUGAR_STORM,
        )
        for ((id, gem) in expected) {
            val level = RealLevels.catalog.level(id)
            assertNotNull("L$id is missing from levels.json", level)
            val state = realIntro(level!!)
            assertTrue("L$id places no $gem gem: ${state.gems}", gem in state.gems)
            assertEquals("L$id newGems", listOf(gem), state.newGems)
        }
    }

    /** ...and no other level on the map carries a "New" badge at all. */
    @Test
    fun noOtherRealLevelFlagsANewGem() {
        val flagged = RealLevels.levels.filter { realIntro(it).newGems.isNotEmpty() }.map { it.id }
        assertEquals(listOf(3, 6, 11, 15, 21, 25, 31), flagged)
    }

    @Test
    fun theRealSweetRoomsAreB1ToB4InWorldZero() {
        val expected = mapOf(101 to "B1", 102 to "B2", 103 to "B3", 104 to "B4")
        for ((id, code) in expected) {
            val level = RealLevels.catalog.level(id)
            assertNotNull("Sweet Room $id is missing from levels.json", level)
            val state = realIntro(level!!)
            assertEquals("$id code", code, state.levelCode)
            assertEquals("$id world", 0, state.world)
            assertTrue("$id isSweetRoom", state.isSweetRoom)
        }
    }

    /** Every shipped level maps without throwing, into something the dialog can draw. */
    @Test
    fun everyRealLevelMapsToAWellFormedIntro() {
        val levels = RealLevels.levels
        // A vacuous sweep proves nothing: RealLevels fails on a missing file, this on a short one.
        assertEquals(40 + 4, levels.size)

        val failures = LevelFailures("level intro over the shipped catalogue")
        for (level in levels) {
            val state = realIntro(level)
            failures.check(level, state.levelId == level.id) { "levelId ${state.levelId}" }
            failures.check(level, state.objectives.isNotEmpty()) { "no objectives" }
            failures.check(level, state.objectives.size == level.objectives.size) {
                "${state.objectives.size} intro objectives for ${level.objectives.size} authored"
            }
            for (o in state.objectives) {
                if (o.type == ObjectiveType.CLEAR_COLOR) {
                    failures.check(level, o.target == 0 && o.color != null) { "CLEAR_COLOR $o" }
                } else {
                    failures.check(level, o.target > 0) { "${o.type} target ${o.target} <= 0" }
                }
                failures.check(level, o.times >= 1) { "${o.type} times ${o.times} < 1" }
            }
            failures.check(
                level,
                state.twoCrownBalls in 0..state.threeCrownBalls && state.threeCrownBalls <= state.balls,
            ) { "crowns ${state.twoCrownBalls}/${state.threeCrownBalls} with ${state.balls} balls" }
            failures.check(level, state.crownsEarned == 0 && state.bestScore == 0) {
                "fresh progress shows ${state.crownsEarned} crowns, best ${state.bestScore}"
            }
            failures.check(level, state.gems == state.gems.distinct().sortedBy { it.ordinal }) {
                "gem row ${state.gems} is not distinct and in declaration order"
            }
            failures.check(level, state.gems.toSet() == level.gems.map { it.type }.toSet()) {
                "gem row ${state.gems} vs placed ${level.gems.map { it.type }}"
            }
            failures.check(level, state.gems.containsAll(state.newGems)) {
                "new ${state.newGems} not all on the board ${state.gems}"
            }
            failures.check(level, state.hasMovingRows == level.layout.movingRows.isNotEmpty()) {
                "hasMovingRows ${state.hasMovingRows}"
            }
            if (LevelIds.isBonus(level.id)) {
                failures.check(level, state.isSweetRoom) { "not a Sweet Room" }
                failures.check(level, state.levelCode in SWEET_ROOM_CODES) { "code ${state.levelCode}" }
                failures.check(level, state.world == 0) { "world ${state.world}" }
            } else {
                failures.check(level, !state.isSweetRoom) { "flagged as a Sweet Room" }
                failures.check(level, state.levelCode == null) { "code ${state.levelCode}" }
                failures.check(level, state.world in 1..4) { "world ${state.world}" }
            }
        }
        failures.assertNone()
    }

    private fun realIntro(level: LevelDef): LevelIntroUiState =
        LevelIntroMapper.map(level, fresh, RealLevels.config)

    // ---------------------------------------------------------------- fixtures

    private fun objective(
        type: ObjectiveType,
        color: CandyColor? = null,
        gem: GemType? = null,
        count: Int? = null,
        score: Int? = null,
        chain: Int? = null,
    ) = ObjectiveDef(type, color, gem, count, score, chain)

    /** A main level, shaped the way `LevelDef`'s mapper would produce it. */
    private fun level(
        id: Int = 12,
        code: String? = null,
        world: Int = 2,
        balls: Int = 11,
        crowns: List<Int> = listOf(3, 6),
        gems: List<GemType> = emptyList(),
        movingRows: List<MovingRowDef> = emptyList(),
        objectives: List<ObjectiveDef> = listOf(objective(ObjectiveType.SCORE, score = 1_000)),
    ) = LevelDef(
        id = id,
        code = code,
        world = world,
        isBonus = LevelIds.isBonus(id),
        balls = balls,
        crowns = crowns,
        layout = LayoutDef(
            spacing = LevelDefaults.SPACING,
            pattern = LayoutPattern.FULL,
            holes = emptyList(),
            clusters = emptyList(),
            movingRows = movingRows,
        ),
        candies = CandyPlacementDef(
            seed = id * 100L + 7,
            count = 20,
            weights = LevelDefaults.UNIFORM_CANDY_WEIGHTS,
            fixed = emptyList(),
        ),
        // Rows and columns are irrelevant to the intro; distinct nodes keep the fixture honest.
        gems = gems.mapIndexed { i, type -> GemPlacementDef(type, row = 4 + 2 * i, col = 1 + i) },
        cup = CupDef(speed = LevelDefaults.CUP_SPEED),
        objectives = objectives,
    )

    private fun sweetRoom(id: Int, code: String? = LevelIds.bonusCode(id)) = level(
        id = id,
        code = code,
        world = LevelDefaults.BONUS_WORLD,
        balls = LevelDefaults.SWEET_ROOM_BALLS,
        crowns = listOf(4, 8),
        gems = listOf(GemType.SWEET, GemType.BLAST),
        objectives = listOf(objective(ObjectiveType.SCORE, score = 7_000)),
    )

    /** A drawable's id by name, through the generated `R` class — no Android runtime needed. */
    private fun drawableId(name: String): Int = R.drawable::class.java.getField(name).getInt(null)

    private companion object {
        val SWEET_ROOM_CODES = setOf("B1", "B2", "B3", "B4")
    }
}
