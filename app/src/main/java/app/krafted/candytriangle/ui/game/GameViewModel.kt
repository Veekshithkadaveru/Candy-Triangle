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
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.level.LevelSession
import app.krafted.candytriangle.ui.board.GameCommandChannel
import app.krafted.candytriangle.ui.board.GameLoopListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

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

    private val _feedback = MutableSharedFlow<GameFeedbackCue>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val feedback: SharedFlow<GameFeedbackCue> = _feedback.asSharedFlow()

    /** Set once per attempt, after the result has been persisted. See [consumeFinished]. */
    private val _finished = MutableStateFlow<LevelOutcome?>(null)
    val finished: StateFlow<LevelOutcome?> = _finished.asStateFlow()

    /** The head of the one-time mechanic education queue, if this level introduces a gem. */
    private val _gemIntro = MutableStateFlow<GemType?>(null)
    val gemIntro: StateFlow<GemType?> = _gemIntro.asStateFlow()

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
    private val feedbackSequence = AtomicLong()
    private var finishedDelivered = false
    private var objectiveFeedbackReady = false
    private var objectiveCompletions = BooleanArray(0)
    private val pendingGemIntros = ArrayDeque<GemType>()
    private var gemIntroDismissInFlight = false

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
                val progress = progressStore.progress.first()
                val newGems = GemType.entries.filter { type ->
                    resolvedLevel.gems.any { it.type == type } &&
                        (resolvedConfig.gem(type)?.introLevel ?: type.introLevel) == resolvedLevel.id &&
                        type !in progress.seenGemIntros
                }
                Prepared(
                    resolvedConfig,
                    resolvedLevel,
                    LevelBoard.create(resolvedLevel, resolvedConfig),
                    progress.equippedBallSkin,
                    progress.equippedTrail,
                    newGems,
                )
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
        publish(prepared.board, prepared.skin, prepared.trail, prepared.newGems)
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
    private suspend fun publish(
        board: LevelBoard,
        skin: BallSkin,
        trail: app.krafted.candytriangle.level.TrailType,
        newGems: List<GemType> = emptyList(),
    ) {
        mapper = HudStateMapper(board)
        terminalConsumed = false
        objectiveFeedbackReady = false
        objectiveCompletions = BooleanArray(board.level.objectives.size)
        startCollectingEvents(board.session)
        if (newGems.isNotEmpty()) {
            pendingGemIntros.clear()
            pendingGemIntros.addAll(newGems)
            _gemIntro.value = pendingGemIntros.firstOrNull()
        }
        channel.ballSkin = skin
        channel.trail = trail
        channel.paused = false
        _paused.value = false
        generation++
        val resolvedConfig = board.config
        val launcher = resolvedConfig.board.launcher
        _uiState.value = GameUiState.Ready(
            board = board,
            worldIndex = backdropWorldFor(board.level.id, board.level.world, resolvedConfig),
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
     * [HudStateMapper]. Presentation feedback may observe the same hook without owning game state.
     */
    private fun onGameEvent(event: GameEvent) {
        when (event) {
            is GameEvent.CandyPopped -> if (event.isDirect) {
                emitFeedback(
                    type = GameFeedbackType.CANDY,
                    x = event.x,
                    y = event.y,
                    candyColor = event.color,
                )
            }

            is GameEvent.GemSmashed -> emitFeedback(
                type = GameFeedbackType.GEM,
                x = event.x,
                y = event.y,
                gemType = event.gemType,
            )

            is GameEvent.CupCaught -> emitFeedback(
                type = GameFeedbackType.CUP,
                x = event.x,
                y = event.y,
            )

            is GameEvent.ChainAdvanced -> {
                _chainBanner.value = ChainCue(event.color, event.length, sugarPop = false, sequence = ++bannerSequence)
                emitFeedback(
                    type = GameFeedbackType.CHAIN,
                    x = event.x,
                    y = event.y,
                    candyColor = event.color,
                    amount = event.length,
                )
            }

            is GameEvent.SugarPop -> {
                _chainBanner.value = ChainCue(event.color, event.popped, sugarPop = true, sequence = ++bannerSequence)
                emitFeedback(
                    type = GameFeedbackType.CHAIN,
                    x = event.x,
                    y = event.y,
                    candyColor = event.color,
                    amount = event.popped.coerceAtLeast(3),
                )
            }

            is GameEvent.DropCompleted ->
                _dropFlash.value = DropFlash(event.dropScore, event.dropMultiplier, ++bannerSequence)

            is GameEvent.BallLaunched -> {
                _chainBanner.value = null
                _dropFlash.value = null
            }

            else -> Unit
        }
    }

    private fun emitFeedback(
        type: GameFeedbackType,
        x: Float = Float.NaN,
        y: Float = Float.NaN,
        candyColor: CandyColor? = null,
        gemType: GemType? = null,
        amount: Int = 1,
    ) {
        _feedback.tryEmit(
            GameFeedbackCue(
                type = type,
                x = x,
                y = y,
                candyColor = candyColor,
                gemType = gemType,
                amount = amount,
                sequence = feedbackSequence.incrementAndGet(),
            ),
        )
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
        snapshotMapper.update(launchRefusalsOf(channel))?.let { snapshot ->
            publishObjectiveFeedback(snapshot)
            _hud.value = snapshot
        }

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

    private fun publishObjectiveFeedback(snapshot: HudState) {
        if (!objectiveFeedbackReady) {
            objectiveCompletions = BooleanArray(snapshot.objectives.size) { snapshot.objectives[it].complete }
            objectiveFeedbackReady = true
            return
        }
        if (objectiveCompletions.size != snapshot.objectives.size) {
            objectiveCompletions = BooleanArray(snapshot.objectives.size)
        }
        for (i in snapshot.objectives.indices) {
            val complete = snapshot.objectives[i].complete
            if (complete && !objectiveCompletions[i]) {
                emitFeedback(type = GameFeedbackType.OBJECTIVE, amount = i + 1)
            }
            objectiveCompletions[i] = complete
        }
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

    /** Persists the current gem lesson, then advances to the next newly introduced gem. */
    fun dismissGemIntro() {
        val gem = _gemIntro.value ?: return
        if (gemIntroDismissInFlight) return
        gemIntroDismissInFlight = true
        viewModelScope.launch {
            progressStore.markGemIntroSeen(gem)
            if (pendingGemIntros.firstOrNull() == gem) pendingGemIntros.removeFirst()
            _gemIntro.value = pendingGemIntros.firstOrNull()
            gemIntroDismissInFlight = false
        }
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
            objectiveFeedbackReady = false
            objectiveCompletions = BooleanArray(0)
            channel.paused = true
            channel.reset()
            _uiState.value = GameUiState.Loading
            val skin = channel.ballSkin
            val board = withContext(workDispatcher) { LevelBoard.create(currentLevel, currentConfig) }
            publish(board, skin, channel.trail)
        }
    }

    override fun onCleared() {
        // Stop the loop advancing anything it might still be holding, then drop the board: the
        // surface's own detach()/join() is driven by the screen's DisposableEffect.
        channel.paused = true
        mapper = null
        eventJob?.cancel()
        eventJob = null
        pendingGemIntros.clear()
        objectiveFeedbackReady = false
        objectiveCompletions = BooleanArray(0)
        _gemIntro.value = null
        _uiState.value = GameUiState.Loading
        super.onCleared()
    }

    private class Prepared(
        val config: GameConfig,
        val level: LevelDef,
        val board: LevelBoard,
        val skin: BallSkin,
        val trail: app.krafted.candytriangle.level.TrailType,
        val newGems: List<GemType>,
    )

    companion object {

        private const val WORLD_MIN = 1
        private const val WORLD_MAX = 4

        /**
         * The world (1..4) whose backdrop and §6.1 peg tint a level is drawn with — the
         * `worldIndex` handed to `GameSurfaceView.attach`, which resolves both from it.
         *
         * A main level plays in the world that owns it: `config.worldFor`, else the level's own
         * `world` — unchanged by D3. A Sweet Room has no world of its own (`world = 0`, no
         * `WorldDef`) and used to fall through to World 1's panorama and pink pegs; D3's map draws
         * Sweet Room Bn on World n's slice, so Bn now plays on World n's backdrop and pegs too
         * (`id - LevelIds.BONUS_FIRST + 1`), the slice the player tapped it on. Anything else
         * clamps into 1..4, because `world_1`..`world_4` are the only backdrops that exist.
         */
        internal fun backdropWorldFor(levelId: Int, levelWorld: Int, config: GameConfig): Int {
            val world = if (LevelIds.isBonus(levelId)) {
                levelId - LevelIds.BONUS_FIRST + 1
            } else {
                config.worldFor(levelId)?.index ?: levelWorld
            }
            return world.coerceIn(WORLD_MIN, WORLD_MAX)
        }

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
