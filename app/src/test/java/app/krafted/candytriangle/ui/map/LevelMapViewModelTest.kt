package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.FakePreferencesDataStore
import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.ui.intro.LevelIntroUiState
import app.krafted.candytriangle.verification.RealLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The D3 rules that live in `LevelMapViewModel` rather than in `MapStateMapper`: the map flow
 * over the live store, and the tap gate in front of `LevelIntroDialog`.
 *
 * JVM-only, like `JarViewModelTest`: the collaborators come in by constructor and
 * `Dispatchers.setMain` gives `viewModelScope` a dispatcher. Two choices are specific to this
 * class:
 *
 * - Main is a **`StandardTestDispatcher`**, not an unconfined one, so a launched open waits in the
 *   queue until the test advances. That is what lets "the last tap wins" be tested as a real
 *   cancellation — with an eager dispatcher every open would finish before the next tap arrived.
 * - The intro is built by a **fake mapper** passed through the ViewModel's `introMapper` seam,
 *   which also records every level it was asked to build. Whether an open *ran at all* is visible
 *   in [introsBuilt], and these tests never depend on `LevelIntroMapper` (another agent's file).
 *
 * Levels come from the real `levels.json` through a warmed [LevelRepository], so `level()` never
 * hops to `Dispatchers.Default` in the middle of a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LevelMapViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val progressStore = ProgressStore(FakePreferencesDataStore())

    /** Every level id the fake intro mapper built an intro for, in order. */
    private val introsBuilt = mutableListOf<Int>()

    /** The config each of those intros was built against. */
    private val configsSeen = mutableListOf<GameConfig?>()

    private val fakeIntro: (LevelDef, PlayerProgress, GameConfig?) -> LevelIntroUiState =
        { level, progress, config ->
            introsBuilt += level.id
            configsSeen += config
            LevelIntroUiState(
                levelId = level.id,
                levelCode = level.code,
                world = level.world,
                balls = level.balls,
                twoCrownBalls = level.twoCrownBalls,
                threeCrownBalls = level.threeCrownBalls,
                crownsEarned = progress.crownsFor(level.id),
                bestScore = progress.bestScoreFor(level.id),
                objectives = emptyList(),
                gems = emptyList(),
                newGems = emptyList(),
                hasMovingRows = false,
            )
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        configLoader: ConfigLoader = ConfigLoader(RealLevels.assets),
        levelRepository: LevelRepository = realLevels,
    ) = LevelMapViewModel(progressStore, configLoader, levelRepository, fakeIntro)

    /** `uiState` is `WhileSubscribed`: inert until collected, as `collectAsStateWithLifecycle`
     *  would. */
    private fun TestScope.subscribe(viewModel: LevelMapViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    private fun LevelMapUiState.node(id: Int): LevelNodeUiState =
        allLevels.single { it.levelId == id }

    // ---------------------------------------------------------------- the map

    @Test
    fun uiStateIsTheUnloadedPlaceholderUntilSomeoneSubscribes() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(LevelMapViewModel.INITIAL, viewModel.uiState.value)
        assertFalse(LevelMapViewModel.INITIAL.loaded)
        val freshInstall = MapStateMapper.map(PlayerProgress(), null)
        assertEquals(freshInstall.copy(loaded = false), LevelMapViewModel.INITIAL)
    }

    @Test
    fun aFreshInstallPutsTheMarkerOnLevelOne() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals(1, state.currentLevelId)
        assertEquals(0, state.currentSliceIndex)
        assertTrue(state.node(1).isCurrent)
        assertEquals(NodeStatus.AVAILABLE, state.node(1).status)
        assertEquals(NodeStatus.LOCKED, state.node(2).status)
        assertNull(viewModel.intro.value)
    }

    @Test
    fun aRecordedResultClearsLevelOneAndOpensLevelTwo() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        advanceUntilIdle()

        progressStore.recordLevelResult(levelId = 1, crowns = 3, score = 500)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(NodeStatus.CLEARED, state.node(1).status)
        assertEquals(3, state.node(1).crowns)
        assertEquals(500, state.node(1).bestScore)
        assertEquals(NodeStatus.AVAILABLE, state.node(2).status)
        assertTrue(state.node(2).isCurrent)
        assertEquals(2, state.currentLevelId)
        assertEquals(3, state.totalCrowns)

        viewModel.openLevel(2)
        advanceUntilIdle()
        assertEquals(2, viewModel.intro.value?.levelId)
    }

    // ---------------------------------------------------------------- opening a level

    @Test
    fun openingLevelOneShowsItsIntroBuiltAgainstTheParsedConfig() = runTest(mainDispatcher) {
        val configLoader = ConfigLoader(RealLevels.assets)
        val viewModel = viewModel(configLoader)

        viewModel.openLevel(1)
        advanceUntilIdle()

        val intro = viewModel.intro.value
        assertNotNull(intro)
        assertEquals(1, intro!!.levelId)
        assertEquals(listOf(1), introsBuilt)
        // Not the `null` "not parsed yet" placeholder the map starts from.
        assertSame(configLoader.config(), configsSeen.single())
    }

    @Test
    fun openingALockedLevelOpensNothing() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.openLevel(2)
        advanceUntilIdle()

        assertNull(viewModel.intro.value)
        assertTrue("a locked tap must not even build an intro", introsBuilt.isEmpty())
    }

    @Test
    fun aClearedLevelReopensForAReplay() = runTest(mainDispatcher) {
        progressStore.recordLevelResult(levelId = 1, crowns = 1, score = 100)
        val viewModel = viewModel()

        viewModel.openLevel(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.intro.value?.levelId)
        assertEquals(1, viewModel.intro.value?.crownsEarned)
    }

    @Test
    fun aSweetRoomOpensOnlyOnceItsJarReachesTierThree() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.openLevel(101)
        advanceUntilIdle()
        assertNull(viewModel.intro.value)

        progressStore.bankCandies(mapOf(CandyColor.GREEN to 200))
        viewModel.openLevel(101)
        advanceUntilIdle()

        assertEquals(101, viewModel.intro.value?.levelId)
        assertEquals("B1", viewModel.intro.value?.levelCode)
        assertEquals(listOf(101), introsBuilt)
    }

    @Test
    fun anIdOffTheMapOpensNothing() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        for (id in listOf(999, 0, -1, 41, 105)) {
            viewModel.openLevel(id)
            advanceUntilIdle()
            assertNull("id $id", viewModel.intro.value)
        }
        assertTrue(introsBuilt.isEmpty())
    }

    /** Playable on the map, absent from the catalogue: a no-op, not a crash or an empty dialog. */
    @Test
    fun aLevelTheCatalogueLacksOpensNothing() = runTest(mainDispatcher) {
        // The fixture holds levels 3, 14, 27 and 101 — not level 1, which a fresh install may open.
        val viewModel = viewModel(levelRepository = fixtureLevels)

        viewModel.openLevel(1)
        advanceUntilIdle()

        assertNull(viewModel.intro.value)
        assertTrue(introsBuilt.isEmpty())
    }

    @Test
    fun dismissIntroClosesTheDialog() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.openLevel(1)
        advanceUntilIdle()
        assertNotNull(viewModel.intro.value)

        viewModel.dismissIntro()

        assertNull(viewModel.intro.value)
    }

    // ---------------------------------------------------------------- racing taps

    @Test
    fun theLastOfTwoQuickTapsWins() = runTest(mainDispatcher) {
        progressStore.recordLevelResult(levelId = 1, crowns = 1, score = 100)
        val viewModel = viewModel()

        viewModel.openLevel(1)
        viewModel.openLevel(2) // before the first open has had a chance to run
        advanceUntilIdle()

        assertEquals(2, viewModel.intro.value?.levelId)
        // Cancelled, not merely overwritten: level 1's intro was never even built.
        assertEquals(listOf(2), introsBuilt)
    }

    @Test
    fun aLaterTapOnALockedNodeStillCancelsAnEarlierOpen() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.openLevel(1)
        viewModel.openLevel(5)
        advanceUntilIdle()

        assertNull(viewModel.intro.value)
        assertTrue(introsBuilt.isEmpty())
    }

    /** A slow open must not pop the dialog back up after the player has already closed it. */
    @Test
    fun dismissingCancelsAnOpenStillInFlight() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.openLevel(1)
        viewModel.dismissIntro()
        advanceUntilIdle()

        assertNull(viewModel.intro.value)
        assertTrue(introsBuilt.isEmpty())
    }

    // ---------------------------------------------------------------- the gate reads live data

    /**
     * With nobody collecting, `uiState` is still the `INITIAL` placeholder in which level 2 is
     * locked — yet the store says level 1 is cleared, and the store is what the gate reads.
     */
    @Test
    fun theTapGateReadsTheStoreEvenWithNoSubscriber() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        progressStore.recordLevelResult(levelId = 1, crowns = 2, score = 300)

        viewModel.openLevel(2)
        advanceUntilIdle()

        assertEquals(LevelMapViewModel.INITIAL, viewModel.uiState.value)
        assertEquals(2, viewModel.intro.value?.levelId)
    }

    @Test
    fun theMapAndTheTapGateHonourTheParsedConfig() = runTest(mainDispatcher) {
        // Ten crowns: short of §6.2's 15, enough for this config's 5.
        for (id in 1..10) progressStore.recordLevelResult(levelId = id, crowns = 1, score = 100)

        val lenient = viewModel(ConfigLoader(withConfig(LENIENT_WORLD_TWO_GATE)))
        subscribe(lenient)
        advanceUntilIdle()
        assertTrue(lenient.uiState.value.slice(2)!!.unlocked)
        assertEquals(NodeStatus.AVAILABLE, lenient.uiState.value.node(11).status)
        lenient.openLevel(11)
        advanceUntilIdle()
        assertEquals(11, lenient.intro.value?.levelId)

        // Against the shipped config the same save leaves World 2 shut: the same tap is a no-op.
        val shipped = viewModel()
        shipped.openLevel(11)
        advanceUntilIdle()
        assertNull(shipped.intro.value)
    }

    private companion object {

        /** Overrides World 2's gate only; Worlds 3 and 4 fall back to §6.2's defaults. */
        const val LENIENT_WORLD_TWO_GATE = """
            { "gates": [ {
                "world": 2, "requiresLevelCleared": 10, "crownsRequired": 5, "maxAvailableCrowns": 30
            } ] }
        """

        /** The shipped `levels.json`, parsed once and warm. */
        val realLevels: LevelRepository by lazy { warmed(LevelRepository(RealLevels.assets)) }

        /** `test/resources/levels_fixture.json`: levels 3, 14, 27 and 101 only. */
        val fixtureLevels: LevelRepository by lazy {
            val stream = LevelMapViewModelTest::class.java.classLoader!!
                .getResourceAsStream("levels_fixture.json")
            val json = checkNotNull(stream) { "levels_fixture.json is missing from the test resources" }
                .bufferedReader()
                .use { it.readText() }
            warmed(
                LevelRepository(
                    AssetSource { path -> json.takeIf { path == LevelRepository.DEFAULT_ASSET_PATH } },
                ),
            )
        }

        /** The real assets, except `config.json`, which is [configJson]. */
        fun withConfig(configJson: String) = AssetSource { path ->
            if (path == ConfigLoader.DEFAULT_PATH) configJson else RealLevels.assets.readText(path)
        }

        /** Parses the catalogue up front, off the test dispatcher, so `level()` never suspends. */
        fun warmed(repository: LevelRepository): LevelRepository =
            repository.also { runBlocking { it.catalog() } }
    }
}
