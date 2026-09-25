package app.krafted.candytriangle.ui.game

import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.FakePreferencesDataStore
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CrownCalculator
import app.krafted.candytriangle.level.DEFAULT_WORLDS
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.level.TrailType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `GameViewModel` end to end on the JVM: load order, the event-subscription race, and the D1-b
 * result write.
 *
 * No Robolectric and no emulator, which is exactly why the ViewModel takes [ConfigLoader],
 * [LevelRepository] and [ProgressStore] by constructor. `Dispatchers.setMain` is what makes
 * `viewModelScope` — which is `Dispatchers.Main.immediate` — work here at all.
 *
 * The board is driven through `LevelSession`'s own public mutators rather than through the
 * physics: these cases are about what the ViewModel does with a terminal session, and a real drop
 * would make the cup's timing part of the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val preferences = FakePreferencesDataStore()
    private val progressStore = ProgressStore(preferences)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ loading

    @Test
    fun reachesReadyWithABuiltBoardForTheRequestedLevel() = runTest {
        val viewModel = newViewModel(levelId = 1)

        val ready = viewModel.awaitReady()

        assertEquals(1, ready.board.level.id)
        assertEquals(3, ready.board.level.balls)
        assertEquals(1, ready.worldIndex)
        // §3.1: the launcher pivots at the apex (500, 0), not at the spawn point (500, 75).
        assertEquals(500f, ready.pivotX, 0f)
        assertEquals(0f, ready.pivotY, 0f)
        assertTrue(ready.aimClampRadians > 1.2f)
    }

    @Test
    fun equippedCosmeticsAreOnTheChannelBeforeTheBoardIsReachable() = runTest {
        progressStore.equipBallSkin(BallSkin.GOLD)
        progressStore.equipTrail(TrailType.PINK)

        val viewModel = newViewModel(levelId = 1)
        viewModel.awaitReady()

        assertEquals(BallSkin.GOLD, viewModel.channel.ballSkin)
        assertEquals(TrailType.PINK, viewModel.channel.trail)
    }

    /**
     * `LevelSession.events` has no replay, and `viewModelScope.launch { collect() }` returns
     * *before* the collector is registered. If the board were published first, every event emitted
     * in that window would be lost — so the load path awaits `onSubscription` before `Ready`.
     *
     * The proof is to emit the instant `Ready` is observed and assert it still arrives.
     */
    @Test
    fun theEventCollectorIsSubscribedBeforeTheBoardIsPublished() = runTest {
        val viewModel = newViewModel(levelId = 1)

        val ready = viewModel.awaitReady()
        ready.board.session.onChainAdvanced(CandyColor.PINK, length = 4, boardState = ready.board.boardState)
        dispatcher.scheduler.advanceUntilIdle()

        val cue = viewModel.chainBanner.value
        assertNotNull("a chain emitted right after Ready must be observed", cue)
        assertEquals(4, cue!!.length)
        assertEquals(CandyColor.PINK, cue.color)
        assertFalse(cue.sugarPop)
    }

    @Test
    fun anUnknownLevelIdLandsInErrorRatherThanThrowing() = runTest {
        val viewModel = newViewModel(levelId = 404)

        val state = viewModel.uiState.first { it !is GameUiState.Loading }

        assertEquals(GameUiState.Error(404, GameLoadError.LEVEL_NOT_FOUND), state)
        assertNull(viewModel.hud.value)
    }

    @Test
    fun anUnparseableLevelCatalogueLandsInErrorRatherThanThrowing() = runTest {
        val viewModel = newViewModel(levelId = 1, levelsJson = "{ this is not json")

        val state = viewModel.uiState.first { it !is GameUiState.Loading }

        assertEquals(GameUiState.Error(1, GameLoadError.LEVEL_NOT_FOUND), state)
    }

    // ------------------------------------------------------------------ the HUD push

    @Test
    fun onFramePublishesTheHudAndSkipsIdleFrames() = runTest {
        val viewModel = newViewModel(levelId = 1)
        val ready = viewModel.awaitReady()

        viewModel.onFrame(ready.board, FRAME_NANOS, stepsRun = 0)
        val first = viewModel.hud.value
        assertNotNull(first)
        assertEquals(3, first!!.ballsRemaining)

        viewModel.onFrame(ready.board, FRAME_NANOS, stepsRun = 0)
        // Same instance: the dirty check returned null, so nothing was published or allocated.
        assertTrue(first === viewModel.hud.value)
    }

    @Test
    fun candyFeedbackCarriesTheCollisionPositionAndColor() = runTest {
        val viewModel = newViewModel(levelId = 1)
        val ready = viewModel.awaitReady()
        val cue = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.feedback.first { it.type == GameFeedbackType.CANDY }
        }

        ready.board.session.onCandyPopped(
            color = CandyColor.PINK,
            isDirect = true,
            chainPosition = 2,
            boardState = ready.board.boardState,
            x = 412f,
            y = 638f,
        )

        with(cue.await()) {
            assertEquals(GameFeedbackType.CANDY, type)
            assertEquals(CandyColor.PINK, candyColor)
            assertEquals(412f, x, 0f)
            assertEquals(638f, y, 0f)
            assertTrue(sequence > 0L)
        }
    }

    @Test
    fun completingAnObjectiveEmitsOneObjectiveFeedbackCue() = runTest {
        val viewModel = newViewModel(levelId = 1)
        val ready = viewModel.awaitReady()
        val board = ready.board

        // Prime the completion edge detector with the untouched objective state.
        viewModel.onFrame(board, FRAME_NANOS, stepsRun = 0)
        val cue = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.feedback.first { it.type == GameFeedbackType.OBJECTIVE }
        }

        assertNotNull(board.launch(0f))
        board.session.onCandyPopped(
            color = CandyColor.PINK,
            isDirect = true,
            chainPosition = 1,
            boardState = board.boardState,
            x = 500f,
            y = 500f,
        )
        board.session.onBallExited(board.boardState, board.chainTracker)
        viewModel.onFrame(board, FRAME_NANOS, stepsRun = 1)

        with(cue.await()) {
            assertEquals(GameFeedbackType.OBJECTIVE, type)
            assertEquals(1, amount)
            assertTrue(sequence > 0L)
        }
    }

    // ------------------------------------------------------------------ D1-b: the result write

    @Test
    fun aWonLevelWritesCrownsBestScoreAndTheJarCandies() = runTest {
        val viewModel = newViewModel(levelId = 1)
        val ready = viewModel.awaitReady()
        val board = ready.board

        winLevelOne(viewModel)

        val progress = progressStore.progress.first()
        val expectedCrowns = CrownCalculator.calculateCrowns(board.level, board.session.remainingBalls, true)
        assertEquals(expectedCrowns, progress.crownsFor(1))
        assertEquals(board.session.totalScore, progress.bestScoreFor(1))
        assertEquals(2, progress.jarCount(CandyColor.PINK))
        assertEquals(0, progress.jarCount(CandyColor.BLUE))

        val outcome = viewModel.finished.value
        assertNotNull(outcome)
        assertTrue(outcome!!.won)
        assertEquals(expectedCrowns, outcome.crowns)
        assertEquals(
            outcome.ballsRemaining * GameConfig.DEFAULTS.scoring.sugarRushPointsPerRemainingBall,
            outcome.sugarRushBonus,
        )
    }

    @Test
    fun theResultIsWrittenExactlyOncePerAttempt() = runTest {
        val viewModel = newViewModel(levelId = 1)
        val ready = viewModel.awaitReady()

        winLevelOne(viewModel)
        val bankedOnce = progressStore.progress.first().jarCount(CandyColor.PINK)

        // Ten more frames on an already-terminal session must not bank a second time: bankCandies
        // increments a lifetime total, so a repeat would silently double the jar.
        repeat(10) { viewModel.onFrame(ready.board, FRAME_NANOS, stepsRun = 0) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(bankedOnce, progressStore.progress.first().jarCount(CandyColor.PINK))
    }

    @Test
    fun consumeFinishedHandsTheOutcomeOverOnceSoTheScreenCannotNavigateTwice() = runTest {
        val viewModel = newViewModel(levelId = 1)
        viewModel.awaitReady()

        winLevelOne(viewModel)

        assertNotNull(viewModel.consumeFinished())
        assertNull(viewModel.consumeFinished())
    }

    /** §7 banks candies "win or fail" — `config.jar.bankCandiesOnFailedLevels` decides. */
    @Test
    fun aLostLevelStillBanksWhenTheConfigSaysSo() = runTest {
        val viewModel = newViewModel(levelId = 2)
        val ready = viewModel.awaitReady()

        loseLevelTwo(viewModel, ready)

        val progress = progressStore.progress.first()
        assertEquals(0, progress.crownsFor(2))
        assertEquals(3, progress.jarCount(CandyColor.BLUE))
    }

    @Test
    fun aLostLevelSkipsBankingWhenTheConfigTurnsItOff() = runTest {
        val viewModel = newViewModel(levelId = 2, configJson = BANKING_OFF_CONFIG_JSON)
        val ready = viewModel.awaitReady()

        loseLevelTwo(viewModel, ready)

        val progress = progressStore.progress.first()
        // The attempt is still filed — a failed level has a best score — but the jar stays empty.
        assertEquals(0, progress.crownsFor(2))
        assertEquals(0, progress.jarCount(CandyColor.BLUE))
        assertNotNull(viewModel.finished.value)
    }

    /**
     * A replay is usually a worse run (grinding jar candies, showing someone the board). Crowns
     * gate worlds (§6.2), so neither half may regress — `ProgressStore` max-merges and the
     * ViewModel must not work around it.
     */
    @Test
    fun aWorseReplayCannotLowerCrownsOrTheBestScore() = runTest {
        progressStore.recordLevelResult(levelId = 2, crowns = 3, score = 99_999)

        val viewModel = newViewModel(levelId = 2)
        val ready = viewModel.awaitReady()
        loseLevelTwo(viewModel, ready)

        val progress = progressStore.progress.first()
        assertEquals(3, progress.crownsFor(2))
        assertEquals(99_999, progress.bestScoreFor(2))
    }

    // ------------------------------------------------------------------ pause

    @Test
    fun pauseAndResumeDriveTheChannelAndTheDialogTogether() = runTest {
        val viewModel = newViewModel(levelId = 1)
        viewModel.awaitReady()

        assertFalse(viewModel.paused.value)
        assertFalse(viewModel.channel.paused)

        viewModel.pause()
        assertTrue(viewModel.paused.value)
        assertTrue(viewModel.channel.paused)

        viewModel.resume()
        assertFalse(viewModel.paused.value)
        assertFalse(viewModel.channel.paused)
    }

    @Test
    fun aFinishedLevelCannotBeUnpausedBackIntoPlay() = runTest {
        val viewModel = newViewModel(levelId = 1)
        viewModel.awaitReady()

        winLevelOne(viewModel)
        assertTrue(viewModel.channel.paused)

        viewModel.resume()

        assertTrue("the board must stay frozen once the attempt is over", viewModel.channel.paused)
    }

    // ------------------------------------------------------------------ D4: one-time gem education

    @Test
    fun anIntroducedGemIsShownOnceAndPersistedWhenDismissed() = runTest {
        val first = newViewModel(levelId = 3, levelsJson = GEM_INTRO_LEVELS_JSON)
        first.awaitReady()

        assertEquals(GemType.SWEET, first.gemIntro.value)
        first.dismissGemIntro()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(first.gemIntro.value)
        assertTrue(GemType.SWEET in progressStore.progress.first().seenGemIntros)

        val replay = newViewModel(levelId = 3, levelsJson = GEM_INTRO_LEVELS_JSON)
        replay.awaitReady()
        assertNull("a persisted gem lesson must not interrupt a replay", replay.gemIntro.value)
    }

    // ------------------------------------------------------------------ D3: the world a board is drawn in

    /** Main levels are unchanged by D3: each plays in the §6.1 world that owns it. */
    @Test
    fun mainLevelsPlayInTheWorldThatOwnsThem() {
        val config = GameConfig.DEFAULTS
        assertEquals(1, GameViewModel.backdropWorldFor(levelId = 1, levelWorld = 1, config = config))
        assertEquals(1, GameViewModel.backdropWorldFor(levelId = 10, levelWorld = 1, config = config))
        assertEquals(2, GameViewModel.backdropWorldFor(levelId = 11, levelWorld = 2, config = config))
        assertEquals(4, GameViewModel.backdropWorldFor(levelId = 40, levelWorld = 4, config = config))
    }

    /** As before D3, the config's world table outranks a main level's own `world`. */
    @Test
    fun aMainLevelFollowsTheConfigsWorldTableBeforeItsOwnWorld() {
        assertEquals(2, GameViewModel.backdropWorldFor(levelId = 11, levelWorld = 1, config = GameConfig.DEFAULTS))

        // Outside every configured range, the level's own world is the fallback.
        val worldOneOnly = GameConfig(worlds = DEFAULT_WORLDS.take(1))
        assertEquals(3, GameViewModel.backdropWorldFor(levelId = 25, levelWorld = 3, config = worldOneOnly))
    }

    /**
     * D3's map draws Sweet Room Bn on World n's slice, so Bn plays on World n's backdrop and peg
     * tint — not on World 1's for all four, which is where `world = 0` used to fall through to.
     */
    @Test
    fun sweetRoomsPlayInTheWorldOfTheSliceTheyAreDrawnOn() {
        val expected = mapOf(101 to 1, 102 to 2, 103 to 3, 104 to 4)
        for ((id, world) in expected) {
            assertEquals(
                "Sweet Room $id",
                world,
                GameViewModel.backdropWorldFor(levelId = id, levelWorld = 0, config = GameConfig.DEFAULTS),
            )
        }

        // The id decides, not the config: a world table that happens to span 101..104 is ignored.
        val spansSweetRooms = GameConfig(
            worlds = DEFAULT_WORLDS + DEFAULT_WORLDS[3].copy(index = 2, levelFrom = 101, levelTo = 104),
        )
        assertEquals(3, GameViewModel.backdropWorldFor(levelId = 103, levelWorld = 0, config = spansSweetRooms))
    }

    /** `world_1`..`world_4` are the only backdrops, so whatever the ids and config say, 1..4 it is. */
    @Test
    fun anythingOutOfRangeClampsIntoWorldsOneToFour() {
        val config = GameConfig.DEFAULTS
        assertEquals(1, GameViewModel.backdropWorldFor(levelId = 0, levelWorld = 0, config = config))
        assertEquals(1, GameViewModel.backdropWorldFor(levelId = -7, levelWorld = -1, config = config))
        // 105 is past B4: not a Sweet Room, so it has no slice of its own.
        assertEquals(1, GameViewModel.backdropWorldFor(levelId = 105, levelWorld = 0, config = config))
        assertEquals(4, GameViewModel.backdropWorldFor(levelId = 41, levelWorld = 9, config = config))

        val fifthWorld = GameConfig(worlds = listOf(DEFAULT_WORLDS[0].copy(index = 5)))
        assertEquals(4, GameViewModel.backdropWorldFor(levelId = 3, levelWorld = 1, config = fifthWorld))
    }

    /** End to end: a Sweet Room is published on its slice's world, which `attach` then draws. */
    @Test
    fun aSweetRoomIsPublishedOnItsSlicesWorld() = runTest {
        val viewModel = newViewModel(levelId = 103, levelsJson = SWEET_ROOM_LEVELS_JSON)

        val ready = viewModel.awaitReady()

        assertEquals(103, ready.board.level.id)
        assertEquals(0, ready.board.level.world)
        assertEquals(3, ready.worldIndex)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Wins level 1: one launch, two pink candies on the chain, then the drop ends.
     *
     * 4 + 5 chain positions at 10 points each is 90 > the level's `SCORE 10` objective, so the
     * session completes the level and pays Sugar Rush on the two balls left.
     */
    private suspend fun winLevelOne(viewModel: GameViewModel) {
        val ready = viewModel.awaitReady()
        val board = ready.board
        assertNotNull(board.launch(0f))
        board.session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 4, boardState = board.boardState)
        board.session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 5, boardState = board.boardState)
        board.session.onBallExited(board.boardState, board.chainTracker)
        assertTrue(board.session.isLevelComplete)

        viewModel.onFrame(board, FRAME_NANOS, stepsRun = 1)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** Loses level 2: its single ball is spent and its objective is unreachable. */
    private suspend fun loseLevelTwo(viewModel: GameViewModel, ready: GameUiState.Ready) {
        val board = ready.board
        assertNotNull(board.launch(0f))
        repeat(3) {
            board.session.onCandyPopped(CandyColor.BLUE, isDirect = false, chainPosition = 0, boardState = board.boardState)
        }
        board.session.onBallExited(board.boardState, board.chainTracker)
        assertTrue(board.session.isLevelFailed)

        viewModel.onFrame(board, FRAME_NANOS, stepsRun = 1)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun GameViewModel.awaitReady(): GameUiState.Ready =
        uiState.filterIsInstance<GameUiState.Ready>().first()

    /**
     * Builds a ViewModel over fixture JSON.
     *
     * Both loaders are warmed before construction so the ViewModel's own `init` resolves without
     * hopping to `Dispatchers.Default` inside `LevelRepository`, which keeps every case here
     * deterministic on the test dispatcher rather than racing a real thread pool.
     *
     * `launchRefusalsOf` is stubbed to zero: `GameCommandChannel` belongs to `ui/board`, and these
     * tests must not depend on that agent's implementation being finished.
     */
    private suspend fun newViewModel(
        levelId: Int,
        levelsJson: String = LEVELS_JSON,
        configJson: String? = null,
    ): GameViewModel {
        val assets = AssetSource { path ->
            when (path) {
                LevelRepository.DEFAULT_ASSET_PATH -> levelsJson
                ConfigLoader.DEFAULT_PATH -> configJson
                else -> null
            }
        }
        val configLoader = ConfigLoader(assets)
        val levelRepository = LevelRepository(assets)
        configLoader.config()
        levelRepository.catalog()
        return GameViewModel(
            levelId = levelId,
            configLoader = configLoader,
            levelRepository = levelRepository,
            progressStore = progressStore,
            workDispatcher = dispatcher,
        ) { 0 }
    }

    private companion object {

        const val FRAME_NANOS = 16_666_667L

        /**
         * Two levels with no pegs (`CLUSTERS` with no clusters) and no candies, so nothing in
         * these tests depends on the physics: level 1 is winnable in one drop, level 2 is not
         * winnable at all and has a single ball.
         */
        val LEVELS_JSON = """
            {
              "schemaVersion": 1,
              "levels": [
                {
                  "id": 1, "world": 1, "balls": 3, "crowns": [1, 2],
                  "layout": { "spacing": 80, "pattern": "CLUSTERS", "clusters": [] },
                  "candies": { "seed": 7, "count": 0, "weights": { "PINK": 1 } },
                  "gems": [], "cup": { "speed": 200 },
                  "objectives": [ { "type": "SCORE", "score": 10 } ]
                },
                {
                  "id": 2, "world": 1, "balls": 1, "crowns": [1, 2],
                  "layout": { "spacing": 80, "pattern": "CLUSTERS", "clusters": [] },
                  "candies": { "seed": 7, "count": 0, "weights": { "BLUE": 1 } },
                  "gems": [], "cup": { "speed": 200 },
                  "objectives": [ { "type": "SCORE", "score": 1000000 } ]
                }
              ]
            }
        """.trimIndent()

        /** B3 alone, shaped like the shipped Sweet Rooms: world 0, 15 balls, crowns [4, 8]. */
        val SWEET_ROOM_LEVELS_JSON = """
            {
              "schemaVersion": 1,
              "levels": [
                {
                  "id": 103, "code": "B3", "world": 0, "balls": 15, "crowns": [4, 8],
                  "layout": { "spacing": 80, "pattern": "CLUSTERS", "clusters": [] },
                  "candies": { "seed": 7, "count": 0, "weights": { "PINK": 1 } },
                  "gems": [], "cup": { "speed": 200 },
                  "objectives": [ { "type": "SCORE", "score": 7500 } ]
                }
              ]
            }
        """.trimIndent()

        /** Sweet Gem's default intro level is 3, and the gem is physically present on this board. */
        val GEM_INTRO_LEVELS_JSON = """
            {
              "schemaVersion": 1,
              "levels": [
                {
                  "id": 3, "world": 1, "balls": 3, "crowns": [1, 2],
                  "layout": { "spacing": 80, "pattern": "CLUSTERS", "clusters": [] },
                  "candies": { "seed": 7, "count": 0, "weights": { "PINK": 1 } },
                  "gems": [ { "type": "SWEET", "row": 4, "col": 2 } ],
                  "cup": { "speed": 200 },
                  "objectives": [ { "type": "SCORE", "score": 10 } ]
                }
              ]
            }
        """.trimIndent()

        /** Everything else falls back to `GameConfig.DEFAULTS`; only the jar rule is overridden. */
        val BANKING_OFF_CONFIG_JSON = """
            { "jar": { "bankCandiesOnFailedLevels": false } }
        """.trimIndent()
    }
}
