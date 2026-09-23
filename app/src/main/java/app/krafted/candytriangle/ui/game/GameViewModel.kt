package app.krafted.candytriangle.ui.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.krafted.candytriangle.appContainer
import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.level.LevelSession
import app.krafted.candytriangle.ui.board.GameCommandChannel
import app.krafted.candytriangle.ui.board.GameLoopListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What `GameScreen` is showing: still loading, playable, or unloadable. */
sealed interface GameUiState {

    data object Loading : GameUiState

    /**
     * A built board, ready for `GameSurfaceView.attach`.
     *
     * The main thread may hold this [board] *reference* and pass it to `attach`, but must never
     * read a mutable field off it — every session read happens inside [GameViewModel.onFrame], on
     * the game thread. The scalars beside it are immutable config values, safe to read anywhere.
     *
     * [generation] increments on [GameViewModel.restart] so an otherwise identical state still
     * re-keys the `DisposableEffect` that owns `attach`/`detach`.
     */
    data class Ready(
        val board: LevelBoard,
        val worldIndex: Int,
        val pivotX: Float,
        val pivotY: Float,
        val aimClampRadians: Float,
        val generation: Int,
    ) : GameUiState

    data class Error(val levelId: Int, val reason: GameLoadError) : GameUiState
}

/** Why a level could not be opened. No strings — `GameScreen` resolves the copy. */
enum class GameLoadError {
    /** `levels.json` has no such id — an empty catalogue, or a bad deep link. */
    LEVEL_NOT_FOUND,

    /** Anything else; the level is unplayable rather than mis-tuned. */
    UNEXPECTED,
}

/**
 * Owns one attempt at one level: the [LevelBoard], the HUD snapshot, and the result write.
 *
 * Scoped to the *game* navigation destination, not the Activity, so leaving a level calls
 * [onCleared] — which is what finally releases the board and lets the loop thread die. (An
 * Activity-scoped ViewModel would keep one board alive across level changes: a thread-and-board
 * leak that a JVM-only suite cannot catch.)
 *
 * Collaborators come in by **constructor**, never from a `Context`, which is what makes the whole
 * class JVM-testable with no Robolectric and no emulator; [factory] does the `appContainer` lookup
 * for the Compose call site.
 *
 * ## Threading
 *
 * [onFrame] is called on the game thread. It is the only place that reads `LevelSession`, and it
 * publishes through `MutableStateFlow.value =`, which is safe from any thread and gives the main
 * thread its happens-before edge. Everything else here is main-thread.
 *
 * ## D1 deviation D1-b — this class writes progress at level end
 *
 * The first frame on which the session reports a terminal state, [onFrame] computes the
 * [LevelOutcome] from the session's **authoritative fields** and this ViewModel persists it
 * through `ProgressStore.recordLevelResult` and — on a win, or on a loss when
 * `config.jar.bankCandiesOnFailedLevels` is set — `bankCandies`. Exactly once per attempt, guarded
 * by [terminalConsumed].
 *
 * **D4's results screen must display what is written here, not bank again.** `bankCandies`
 * increments a lifetime total, so a second call would silently double every jar.
 *
 * Owner: Agent 2 (`ui/game`).
 */
class GameViewModel(
    private val levelId: Int,
    private val configLoader: ConfigLoader,
    private val levelRepository: LevelRepository,
    private val progressStore: ProgressStore,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * How [onFrame] reads `GameCommandChannel.launchRefusals`.
     *
     * A seam, not indirection for its own sake: `ui/board` is owned by another agent and its
     * bodies land on a different schedule, so the JVM tests substitute a constant here rather than
     * depending on that implementation being finished. Production uses the real property.
     */
    private val launchRefusalsOf: (GameCommandChannel) -> Int = GameCommandChannel::launchRefusals,
) : ViewModel(), GameLoopListener {

    /** Main thread -> game thread. Handed to `GameSurfaceView.attach` alongside the board. */
    val channel = GameCommandChannel()

    private val _uiState = MutableStateFlow<GameUiState>(GameUiState.Loading)
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /** `null` until the loop has run one frame. Collect with `collectAsStateWithLifecycle()`. */
    private val _hud = MutableStateFlow<HudState?>(null)
    val hud: StateFlow<HudState?> = _hud.asStateFlow()

    /** The *dialog* flag. The board freeze itself is `channel.paused`, which the loop reads. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _chainBanner = MutableStateFlow<ChainCue?>(null)
    val chainBanner: StateFlow<ChainCue?> = _chainBanner.asStateFlow()

    private val _dropFlash = MutableStateFlow<DropFlash?>(null)
    val dropFlash: StateFlow<DropFlash?> = _dropFlash.asStateFlow()

    /** Set once per attempt, after the result has been persisted. See [consumeFinished]. */
    private val _finished = MutableStateFlow<LevelOutcome?>(null)
    val finished: StateFlow<LevelOutcome?> = _finished.asStateFlow()

    /** Written on the main thread, read on the game thread. */
    @Volatile
    private var mapper: HudStateMapper? = null

    /** Set on the game thread in [onFrame]; cleared on the main thread by [restart]. */
    @Volatile
    private var terminalConsumed = false

    private var eventJob: Job? = null
    private var config: GameConfig? = null
    private var level: LevelDef? = null
    private var bankCandiesOnFailedLevels = true
    private var generation = 0
    private var bannerSequence = 0L
    private var finishedDelivered = false

    init {
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------------ loading

    private suspend fun load() {
        val prepared = try {
            withContext(workDispatcher) {
                val resolvedConfig = configLoader.config()
                val resolvedLevel = levelRepository.level(levelId)
                    ?: return@withContext null
                val skin = progressStore.equippedBallSkin.first()
                Prepared(resolvedConfig, resolvedLevel, LevelBoard.create(resolvedLevel, resolvedConfig), skin)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A level that will not build is a data bug, not a crash: §13 tolerates zero crashes,
            // so the screen shows an error and the player can back out.
            _uiState.value = GameUiState.Error(levelId, GameLoadError.UNEXPECTED)
            return
        }

        if (prepared == null) {
            _uiState.value = GameUiState.Error(levelId, GameLoadError.LEVEL_NOT_FOUND)
            return
        }

        config = prepared.config
        level = prepared.level
        bankCandiesOnFailedLevels = prepared.config.jar.bankCandiesOnFailedLevels
        publish(prepared.board, prepared.skin)
    }

    /**
     * Makes [board] the live board.
     *
     * Order matters and is the whole reason this is a suspend function: the event collector is
     * registered and **awaited** before the board becomes reachable by Compose, because
     * `LevelSession.events` has no replay and `viewModelScope.launch { collect() }` returns before
     * the collector is actually subscribed. Publishing first would lose every event emitted
     * between the two.
     */
    private suspend fun publish(board: LevelBoard, skin: BallSkin) {
        mapper = HudStateMapper(board)
        terminalConsumed = false
        startCollectingEvents(board.session)
        channel.ballSkin = skin
        channel.paused = false
        _paused.value = false
        generation++
        val resolvedConfig = board.config
        val launcher = resolvedConfig.board.launcher
        _uiState.value = GameUiState.Ready(
            board = board,
            // `worldFor` has no world for a Sweet Room (id 101..104); the backdrop still has to be
            // one of world_1..world_4, so fall back to the level's own world and clamp into range.
            worldIndex = (resolvedConfig.worldFor(board.level.id)?.index ?: board.level.world)
                .coerceIn(WORLD_MIN, WORLD_MAX),
            pivotX = launcher.pivot.x,
            pivotY = launcher.pivot.y,
            aimClampRadians = board.params.aimClampRadians,
            generation = generation,
        )
    }

    /**
     * Subscribes to [session]'s events and returns only once the collector is registered.
     *
     * `onSubscription` runs after registration and before any emission can be missed; the
     * `CompletableDeferred` carries that moment back to the caller.
     */
    private suspend fun startCollectingEvents(session: LevelSession) {
        val subscribed = CompletableDeferred<Unit>()
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            session.events
                .onSubscription { subscribed.complete(Unit) }
                .collect(::onGameEvent)
        }
        subscribed.await()
    }

    /**
     * Transient HUD garnish only.
     *
     * Score, balls, objective progress and the win/fail decision are **never** reconstructed here:
     * `events` is a no-replay `MutableSharedFlow` with `DROP_OLDEST`, so a Sugar Storm can drop
     * hundreds of `CandyPopped`s. Those numbers come from the session's fields via
     * [HudStateMapper]. D5 takes this hook for VFX and audio.
     */
    private fun onGameEvent(event: GameEvent) {
        when (event) {
            is GameEvent.ChainAdvanced ->
                _chainBanner.value = ChainCue(event.color, event.length, sugarPop = false, sequence = ++bannerSequence)

            is GameEvent.SugarPop ->
                _chainBanner.value = ChainCue(event.color, event.popped, sugarPop = true, sequence = ++bannerSequence)

            is GameEvent.DropCompleted ->
                _dropFlash.value = DropFlash(event.dropScore, event.dropMultiplier, ++bannerSequence)

            is GameEvent.BallLaunched -> {
                _chainBanner.value = null
                _dropFlash.value = null
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ game thread

    /**
     * The HUD publish point. **Game thread**, between `world.advance()` and the draw.
     *
     * Does no work on an idle frame: [HudStateMapper.update] returns `null` and nothing is
     * allocated or published.
     */
    override fun onFrame(board: LevelBoard, frameNanos: Long, stepsRun: Int) {
        val snapshotMapper = mapper ?: return
        snapshotMapper.update(launchRefusalsOf(channel))?.let { _hud.value = it }

        if (terminalConsumed || !snapshotMapper.isTerminal) return
        terminalConsumed = true
        // Computed here, on the game thread, while the world is quiescent — including the one and
        // only `collectedByColor` read of the attempt.
        val outcome = snapshotMapper.outcome()
        // A volatile write; the loop parks itself at the top of the next frame and the surface
        // keeps its last posted buffer, so the board freezes for free.
        channel.paused = true
        viewModelScope.launch { commit(outcome) }
    }

    override fun onLoopStopped() {
        // The loop owns nothing this ViewModel has to release: the board outlives the surface so a
        // backgrounded level resumes on a fresh loop thread. onCleared() is the real teardown.
    }

    /** Persists the attempt, then publishes it. Main thread. See D1-b on this class. */
    private suspend fun commit(outcome: LevelOutcome) {
        progressStore.recordLevelResult(outcome.levelId, outcome.crowns, outcome.score)
        if (outcome.won || bankCandiesOnFailedLevels) {
            progressStore.bankCandies(outcome.collected)
        }
        _finished.value = outcome
    }

    // ------------------------------------------------------------------ main thread commands

    /**
     * The outcome, the first time it is asked for; `null` afterwards.
     *
     * The flag lives on the ViewModel rather than in a `remember`, so a recomposition or a
     * configuration change cannot fire `onLevelFinished` a second time.
     */
    fun consumeFinished(): LevelOutcome? {
        if (finishedDelivered) return null
        val outcome = _finished.value ?: return null
        finishedDelivered = true
        return outcome
    }

    /** Pause button, or `Lifecycle.Event.ON_PAUSE`. Idempotent; a finished level stays finished. */
    fun pause() {
        if (_finished.value != null) return
        channel.paused = true
        _paused.value = true
    }

    fun resume() {
        if (_finished.value != null) return
        _paused.value = false
        // The loop resets its frame clock and the world's timestep remainder when it wakes, so the
        // first frame back is neither a multi-second catch-up burst nor a partial step.
        channel.paused = false
    }

    /**
     * Throws the attempt away and builds a fresh board for the same level.
     *
     * Drops back through [GameUiState.Loading] on purpose: that is what makes the screen's
     * `DisposableEffect` detach the old board before the new one is attached, so the loop is never
     * holding two.
     */
    fun restart() {
        val currentConfig = config ?: return
        val currentLevel = level ?: return
        viewModelScope.launch {
            eventJob?.cancel()
            mapper = null
            terminalConsumed = false
            finishedDelivered = false
            _finished.value = null
            _hud.value = null
            _chainBanner.value = null
            _dropFlash.value = null
            channel.paused = true
            channel.reset()
            _uiState.value = GameUiState.Loading
            val skin = channel.ballSkin
            val board = withContext(workDispatcher) { LevelBoard.create(currentLevel, currentConfig) }
            publish(board, skin)
        }
    }

    override fun onCleared() {
        // Stop the loop advancing anything it might still be holding, then drop the board: the
        // surface's own detach()/join() is driven by the screen's DisposableEffect.
        channel.paused = true
        mapper = null
        eventJob?.cancel()
        eventJob = null
        _uiState.value = GameUiState.Loading
        super.onCleared()
    }

    private class Prepared(
        val config: GameConfig,
        val level: LevelDef,
        val board: LevelBoard,
        val skin: BallSkin,
    )

    companion object {

        private const val WORLD_MIN = 1
        private const val WORLD_MAX = 4

        /**
         * Pulls the three collaborators out of `Context.appContainer`.
         *
         * Holds `applicationContext`, never the Activity, so a retained factory cannot leak a
         * window.
         */
        fun factory(context: Context, levelId: Int): ViewModelProvider.Factory {
            val application = context.applicationContext
            return viewModelFactory {
                initializer {
                    val container = application.appContainer
                    GameViewModel(
                        levelId = levelId,
                        configLoader = container.configLoader,
                        levelRepository = container.levelRepository,
                        progressStore = container.progressStore,
                    )
                }
            }
        }
    }
}
