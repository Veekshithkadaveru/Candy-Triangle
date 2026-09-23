package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import app.krafted.candytriangle.board.ChainTracker
import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.PhysicsWorld
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tracks the session state for one attempt at a level: objectives, score and ball inventory (§2,
 * §5, B4), and bridges engine events to the ViewModel via [events].
 *
 * ## Threading and authority
 *
 * Every mutator runs on the game thread, from inside `PhysicsWorld.step` (via
 * `GamePhysicsListener`) or between steps ([launchBall]). The **session's fields are
 * authoritative**: [totalScore], [remainingBalls], [isLevelComplete], [isLevelFailed],
 * [collectedByColor] and [objectiveTracker] are what results screens and persistence must read.
 * [events] are **best-effort notifications** for HUD animation, audio and VFX; see [events].
 *
 * @param scoring §5.1 point values; `LevelBoard` passes `config.scoring`. Defaults to the §5.1 table.
 * @param cup Candy Cup rules; only `catchRefundBalls` is read here (§2 "+1 ball"). `LevelBoard`
 *   passes `config.cup`. Catch *points* come from [scoring]'s `cupCatchPoints`.
 */
class LevelSession(
    val levelDef: LevelDef,
    private val scoring: ScoringConfig = ScoringConfig(),
    private val cup: CupConfig = CupConfig(),
) {

    val objectiveTracker = ObjectiveTracker(levelDef.objectives)

    var remainingBalls = levelDef.balls
        private set

    var totalScore = 0
        private set

    var dropSubtotal = 0
        private set

    var isLevelComplete = false
        private set

    var isLevelFailed = false
        private set

    var activeBallsInFlight = 0
        private set

    /** Per-colour pop counts, indexed by [CandyColor.ordinal]. Written on the game thread only. */
    private val collectedCounts = IntArray(CandyColor.entries.size)

    /**
     * Candies collected this attempt, per colour — direct hits and effect pops (Sugar Pop, gem
     * effects) alike — which D4 hands to `ProgressStore.bankCandies` on win or fail (§7).
     *
     * An immutable snapshot taken at the time of the call, holding only colours with a count > 0
     * (empty before the first pop). Counted on the game thread; reading from another thread may see
     * a value one pop stale, never a torn map.
     */
    val collectedByColor: Map<CandyColor, Int>
        get() {
            val out = LinkedHashMap<CandyColor, Int>()
            for (color in CandyColor.entries) {
                val n = collectedCounts[color.ordinal]
                if (n > 0) out[color] = n
            }
            return java.util.Collections.unmodifiableMap(out)
        }

    private val _events = MutableSharedFlow<GameEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Hot stream of [GameEvent]s, no replay. **Best-effort:** emission never suspends the game
     * thread; when a slow collector falls more than [EVENT_BUFFER_CAPACITY] events behind, the
     * *oldest* buffered events are dropped, so the terminal ones ([GameEvent.SugarRush],
     * [GameEvent.LevelCompleted], [GameEvent.LevelFailed]) — always the last of a burst — survive
     * even a board-clearing Sugar Storm. Events emitted with no collector are lost. Anything that
     * must be exact is read from the session's fields, not reconstructed from events.
     */
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /**
     * Consumes one ball from inventory and fires it, enforcing §2's "one ball in flight". Returns
     * the ball, or null when out of balls, a drop is still active, or the level is over.
     */
    fun launchBall(
        world: PhysicsWorld,
        aimRadians: Float,
        skin: BallSkin = BallSkin.DEFAULT,
        boardState: BoardState? = null,
        chainTracker: ChainTracker? = null
    ): Ball? {
        if (remainingBalls > 0 && activeBallsInFlight == 0 && !isLevelComplete && !isLevelFailed) {
            remainingBalls--
            activeBallsInFlight++
            boardState?.resetForDrop()
            chainTracker?.resetForDrop()
            val ball = world.launch(aimRadians, skin)
            _events.tryEmit(GameEvent.BallLaunched(aimRadians, remainingBalls))
            return ball
        }
        return null
    }

    /**
     * Adds a ball to inventory (e.g. Extra Ball Gem).
     */
    fun addBall() {
        remainingBalls++
    }

    /**
     * A candy left the board. Direct hits score `directCandyPointsPerChainPosition × chainPosition`
     * capped at `directCandyPointsCap`; effect pops score `poppedCandyPoints` flat (§5.1).
     */
    fun onCandyPopped(color: CandyColor, isDirect: Boolean, chainPosition: Int, boardState: BoardState) {
        val points = if (isDirect) {
            (chainPosition * scoring.directCandyPointsPerChainPosition).coerceAtMost(scoring.directCandyPointsCap)
        } else {
            scoring.poppedCandyPoints
        }

        dropSubtotal += points
        collectedCounts[color.ordinal]++

        val event = GameEvent.CandyPopped(color, isDirect, points)
        _events.tryEmit(event)

        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    fun onGemSmashed(gemType: GemType, boardState: BoardState) {
        val points = scoring.gemBrokenPoints
        dropSubtotal += points

        val event = GameEvent.GemSmashed(gemType, points)
        _events.tryEmit(event)

        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    fun onCupCaught(boardState: BoardState) {
        val points = scoring.cupCatchPoints
        dropSubtotal += points
        // §2: a catch refunds `cup.catchRefundBalls` (1 by default); a negative value refunds none.
        remainingBalls += cup.catchRefundBalls.coerceAtLeast(0)

        val event = GameEvent.CupCaught(points)
        _events.tryEmit(event)

        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    fun onChainAdvanced(color: CandyColor, length: Int, boardState: BoardState) {
        val event = GameEvent.ChainAdvanced(color, length)
        _events.tryEmit(event)

        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    /**
     * A chain reached Sugar Pop (§4.1) at ([x], [y]) and popped [popped] further candies. Scoring
     * and counting already happened per candy through [onCandyPopped]; this is the VFX/audio cue.
     */
    fun onSugarPop(color: CandyColor, x: Float, y: Float, popped: Int) {
        _events.tryEmit(GameEvent.SugarPop(color, x, y, popped))
    }

    /**
     * A gem's §4.2 board effect fired at ([x], [y]), popping [popped] candies (already scored
     * through [onCandyPopped]). The D5 VFX hook.
     */
    fun onGemEffectTriggered(gemType: GemType, x: Float, y: Float, popped: Int) {
        _events.tryEmit(GameEvent.GemEffectTriggered(gemType, x, y, popped))
    }

    fun onBallSpawned(ball: Ball) {
        activeBallsInFlight++
        _events.tryEmit(GameEvent.BallSpawned)
    }

    fun onBallExited(boardState: BoardState, chainTracker: ChainTracker? = null) {
        _events.tryEmit(GameEvent.BallExited)
        activeBallsInFlight--
        if (activeBallsInFlight <= 0) {
            activeBallsInFlight = 0
            onDropCompleted(boardState, chainTracker)
        }
    }

    /**
     * Called when all active balls have exited the board or been caught in the cup.
     */
    private fun onDropCompleted(boardState: BoardState, chainTracker: ChainTracker? = null) {
        val dropMultiplier = boardState.dropMultiplier.coerceAtLeast(1)
        val dropScore = dropSubtotal * dropMultiplier

        totalScore += dropScore
        objectiveTracker.addScore(dropScore)

        _events.tryEmit(GameEvent.DropCompleted(dropScore, dropMultiplier, dropSubtotal))

        // Reset board and chain state for the next drop
        boardState.resetForDrop()
        chainTracker?.resetForDrop()

        // Reset subtotal for the next drop
        dropSubtotal = 0

        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)

        val objectivesMet = objectiveTracker.areAllComplete(boardState)

        if (objectivesMet && !isLevelComplete) {
            // Level is complete. Apply Sugar Rush (§5.1), announced even when no balls remain.
            val balls = remainingBalls
            val sugarRushBonus = balls * scoring.sugarRushPointsPerRemainingBall
            totalScore += sugarRushBonus
            isLevelComplete = true
            _events.tryEmit(GameEvent.SugarRush(balls, sugarRushBonus))

            val crowns = CrownCalculator.calculateCrowns(levelDef, remainingBalls, objectivesMet = true)
            _events.tryEmit(GameEvent.LevelCompleted(crowns, totalScore))
        } else if (!objectivesMet && remainingBalls <= 0 && !isLevelComplete) {
            // Objectives not met and no balls left -> level failed.
            isLevelFailed = true
            _events.tryEmit(GameEvent.LevelFailed("Out of balls"))
        }
    }

    companion object {

        /** Events a collector may fall behind by before the oldest are dropped; see [events]. */
        const val EVENT_BUFFER_CAPACITY: Int = 256
    }
}
