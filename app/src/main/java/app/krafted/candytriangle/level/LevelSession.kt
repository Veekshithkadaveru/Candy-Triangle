package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import app.krafted.candytriangle.board.ChainTracker
import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.PhysicsWorld
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tracks the session state for a level including objectives, score, and ball inventory (§5, B4).
 * Bridges engine events to the ViewModel via SharedFlow.
 */
class LevelSession(val levelDef: LevelDef) {

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

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /**
     * Consumes one ball from inventory for a launch. Returns the ball if successful.
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
            return world.launch(aimRadians, skin)
        }
        return null
    }

    /**
     * Adds a ball to inventory (e.g. Extra Ball Gem).
     */
    fun addBall() {
        remainingBalls++
    }

    fun onCandyPopped(color: CandyColor, isDirect: Boolean, chainPosition: Int, boardState: BoardState) {
        val points = if (isDirect) {
            (chainPosition * 10).coerceAtMost(100)
        } else {
            10 // flat points for effect pops
        }

        dropSubtotal += points

        val event = GameEvent.CandyPopped(color, isDirect, points)
        _events.tryEmit(event)
        
        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    fun onGemSmashed(gemType: GemType, boardState: BoardState) {
        val points = 50
        dropSubtotal += points

        val event = GameEvent.GemSmashed(gemType, points)
        _events.tryEmit(event)

        objectiveTracker.processEvent(event)
        _events.tryEmit(GameEvent.ObjectiveProgressUpdated)
    }

    fun onCupCaught(boardState: BoardState) {
        val points = 100
        dropSubtotal += points
        addBall() // Cup refunds 1 ball

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
            // Level is complete. Apply Sugar Rush.
            val sugarRushBonus = remainingBalls * 500
            totalScore += sugarRushBonus
            isLevelComplete = true

            val crowns = CrownCalculator.calculateCrowns(levelDef, remainingBalls, objectivesMet = true)
            _events.tryEmit(GameEvent.LevelCompleted(crowns, totalScore))
        } else if (!objectivesMet && remainingBalls <= 0 && !isLevelComplete) {
            // Objectives not met and no balls left -> level failed.
            isLevelFailed = true
            _events.tryEmit(GameEvent.LevelFailed("Out of balls"))
        }
    }
}

