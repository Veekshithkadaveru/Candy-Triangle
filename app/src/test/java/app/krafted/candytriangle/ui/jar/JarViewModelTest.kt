package app.krafted.candytriangle.ui.jar

import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.FakePreferencesDataStore
import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.TrailType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The D2 rules that live in the ViewModel rather than in [JarUiStateMapper]: unlock validation on
 * the way into `ProgressStore`, and the config-versus-defaults handover.
 *
 * Runs on the JVM with no Android context and no Robolectric. Two things make that possible:
 * [JarViewModel] takes its collaborators by constructor (the `Context.appContainer` lookup is
 * confined to `JarViewModel.factory`), and `Dispatchers.setMain` gives `viewModelScope` a
 * dispatcher it would otherwise have no main looper for.
 */
// `Dispatchers.setMain`, `UnconfinedTestDispatcher` and `advanceUntilIdle` are all still marked
// experimental in kotlinx-coroutines-test 1.9.0; they are the documented way to test a ViewModel
// off-device, and the dependency is frozen for this phase.
@OptIn(ExperimentalCoroutinesApi::class)
class JarViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val preferences = FakePreferencesDataStore()
    private val progressStore = ProgressStore(preferences)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** `ConfigLoader` falls back to `GameConfig.DEFAULTS` on a missing asset, never throwing. */
    private fun loader(json: String? = null) = ConfigLoader(AssetSource { json })

    private fun viewModel(json: String? = null) = JarViewModel(progressStore, loader(json))

    /**
     * `uiState` is a `WhileSubscribed` flow, so it is inert until something collects it. The real
     * collector is `collectAsStateWithLifecycle`; here it is a background coroutine that outlives
     * the assertions and is cancelled with the test.
     */
    private fun TestScope.subscribe(viewModel: JarViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    // ---------------------------------------------------------------- fresh install

    @Test
    fun freshInstallOffersOnlyTheDefaultSkinAndNoTrail() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(BallSkin.DEFAULT), state.unlockedSkins)
        assertEquals(setOf(TrailType.NONE), state.unlockedTrails)
        assertEquals(emptySet<Int>(), state.unlockedSweetRooms)
        assertEquals(0, state.totalCandies)
        assertEquals(BallSkin.DEFAULT, state.equippedBallSkin)
        assertEquals(TrailType.NONE, state.equippedTrail)
        assertEquals(listOf(0, 0, 0, 0), state.jars.map { it.tier })
    }

    // ---------------------------------------------------------------- banking unlocks rewards

    @Test
    fun bankingFortyGreenCandiesOffersTheGreenSkinAndEquippingItPersists() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            subscribe(viewModel)
            progressStore.bankCandies(mapOf(CandyColor.GREEN to 40))
            advanceUntilIdle()

            assertTrue(BallSkin.GREEN in viewModel.uiState.value.unlockedSkins)

            viewModel.equipSkin(BallSkin.GREEN)
            advanceUntilIdle()

            assertEquals(BallSkin.GREEN, progressStore.equippedBallSkin.first())
            assertEquals(BallSkin.GREEN, viewModel.uiState.value.equippedBallSkin)
        }

    @Test
    fun bankingOneHundredAndTwentyCandiesOffersTheMatchingTrail() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.PURPLE to 120))
        advanceUntilIdle()

        assertTrue(TrailType.PURPLE in viewModel.uiState.value.unlockedTrails)

        viewModel.equipTrail(TrailType.PURPLE)
        advanceUntilIdle()

        assertEquals(TrailType.PURPLE, progressStore.equippedTrail.first())
    }

    /** §7's Pink quirk survives the whole pipeline, not just the mapper. */
    @Test
    fun bankingFortyPinkCandiesOffersTheGoldSkin() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.PINK to 40))
        advanceUntilIdle()

        val skins = viewModel.uiState.value.unlockedSkins
        assertEquals(setOf(BallSkin.DEFAULT, BallSkin.GOLD), skins)
    }

    /** Tier 3 is display-only in D2 — the ids appear, and no route into them does. */
    @Test
    fun bankingTwoHundredCandiesUnlocksASweetRoomForDisplay() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.BLUE to 200))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(JarUnlocks.tier3SweetRoomId(CandyColor.BLUE)), state.unlockedSweetRooms)
        assertTrue(LevelIds.isBonus(state.unlockedSweetRooms.first()))
        assertEquals(3, state.jar(CandyColor.BLUE)?.tier)
    }

    // ---------------------------------------------------------------- unlock validation (D2's job)

    /**
     * `ProgressStore.equipBallSkin` deliberately does **not** validate — its KDoc hands that to
     * D2 — so this is the only thing standing between a locked skin and the save file.
     */
    @Test
    fun equippingALockedSkinIsANoOp() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.equipSkin(BallSkin.GOLD)
        advanceUntilIdle()

        assertEquals(BallSkin.DEFAULT, progressStore.equippedBallSkin.first())
        assertNotEquals(BallSkin.GOLD, viewModel.uiState.value.equippedBallSkin)
    }

    /** 39 candies is not 40: the boundary is `>=`, and one short must still be refused. */
    @Test
    fun equippingASkinOneCandyShortIsANoOp() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.GREEN to 39))
        advanceUntilIdle()

        viewModel.equipSkin(BallSkin.GREEN)
        advanceUntilIdle()

        assertEquals(BallSkin.DEFAULT, progressStore.equippedBallSkin.first())
    }

    @Test
    fun equippingALockedTrailIsANoOp() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.equipTrail(TrailType.BLUE)
        advanceUntilIdle()

        assertEquals(TrailType.NONE, progressStore.equippedTrail.first())
    }

    /** A tier-1 jar has not earned its trail; unlocking a skin must not unlock a trail with it. */
    @Test
    fun aTierOneJarDoesNotUnlockItsTrail() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.GREEN to 40))
        advanceUntilIdle()

        viewModel.equipTrail(TrailType.GREEN)
        advanceUntilIdle()

        assertEquals(TrailType.NONE, progressStore.equippedTrail.first())
        assertEquals(setOf(TrailType.NONE), viewModel.uiState.value.unlockedTrails)
    }

    /** The always-available freebies are equippable on a fresh install. */
    @Test
    fun theFreebiesAreAlwaysEquippable() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        subscribe(viewModel)
        progressStore.equipBallSkin(BallSkin.DEFAULT)
        advanceUntilIdle()

        viewModel.equipSkin(BallSkin.DEFAULT)
        viewModel.equipTrail(TrailType.NONE)
        advanceUntilIdle()

        assertEquals(BallSkin.DEFAULT, progressStore.equippedBallSkin.first())
        assertEquals(TrailType.NONE, progressStore.equippedTrail.first())
    }

    // ---------------------------------------------------------------- config handover

    /**
     * Before `config.json` has been parsed the screen still has to draw something, and §7's
     * shipped table is the right guess. `uiState`'s initial value is mapped against it.
     */
    @Test
    fun defaultThresholdsAreUsedBeforeTheConfigResolves() = runTest(mainDispatcher) {
        val viewModel = viewModel(CONFIG_WITH_SMALL_THRESHOLDS)

        // No subscriber yet, so the WhileSubscribed upstream — and with it the asset parse —
        // has not run. This is exactly the first-frame state.
        assertEquals(JarUnlocks.DEFAULT_TIER_THRESHOLDS, viewModel.uiState.value.tierThresholds)
        assertEquals(JarViewModel.INITIAL, viewModel.uiState.value)
    }

    @Test
    fun configuredThresholdsAreUsedOnceTheConfigResolves() = runTest(mainDispatcher) {
        val viewModel = viewModel(CONFIG_WITH_SMALL_THRESHOLDS)
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.GREEN to 10))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(5, 10, 15), state.tierThresholds)
        // 10 candies is tier 2 under this config and tier 0 under §7's table.
        assertEquals(2, state.jar(CandyColor.GREEN)?.tier)
        assertTrue(BallSkin.GREEN in state.unlockedSkins)
        assertTrue(TrailType.GREEN in state.unlockedTrails)
    }

    /** The equip gate reads the config too, not just the mapper that feeds the screen. */
    @Test
    fun theEquipGateHonoursConfiguredThresholds() = runTest(mainDispatcher) {
        val viewModel = viewModel(CONFIG_WITH_SMALL_THRESHOLDS)
        subscribe(viewModel)
        progressStore.bankCandies(mapOf(CandyColor.GREEN to 5))
        advanceUntilIdle()

        viewModel.equipSkin(BallSkin.GREEN)
        advanceUntilIdle()

        assertEquals(BallSkin.GREEN, progressStore.equippedBallSkin.first())
    }

    /** A missing or unparseable asset degrades to §7's table rather than breaking the screen. */
    @Test
    fun anUnparseableConfigFallsBackToTheDefaultThresholds() = runTest(mainDispatcher) {
        val viewModel = viewModel("{ this is not json")
        subscribe(viewModel)
        advanceUntilIdle()

        assertEquals(
            JarUnlocks.DEFAULT_TIER_THRESHOLDS,
            viewModel.uiState.value.tierThresholds,
        )
    }

    private companion object {

        /**
         * Only the `jar` block; every other section of `ConfigJson.Root` is absent and falls back
         * to `GameConfig.DEFAULTS`, which is exactly the tuning-override path this exercises.
         */
        const val CONFIG_WITH_SMALL_THRESHOLDS = """
            { "jar": { "tierThresholds": [5, 10, 15] } }
        """
    }
}
