package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.board.LevelBoard

/**
 * The one-way door from the game thread back to the ViewModel.
 *
 * [onFrame] is called on the game thread, **between `world.advance()` and the draw**, when the
 * world is quiescent — no step is in progress, so `LevelSession`'s plain non-volatile fields
 * (`totalScore`, `remainingBalls`, `activeBallsInFlight`, `isLevelComplete`, `isLevelFailed`) and
 * `ObjectiveTracker` are mutually consistent. That instant is the only safe place to read them.
 *
 * An implementation must not block, must not allocate when nothing has changed, and must never call
 * back into `PhysicsWorld.step`/`advance` (re-entry throws `IllegalStateException`).
 *
 * Owner: Agent 1 (`ui/board`); implemented by Agent 2 (`ui/game`).
 */
interface GameLoopListener {

    /** @param stepsRun how many fixed 1/240 s steps this frame ran (0..12). */
    fun onFrame(board: LevelBoard, frameNanos: Long, stepsRun: Int)

    /** The loop thread has exited; the surface is gone or the view was detached. */
    fun onLoopStopped()

    companion object {
        val NONE: GameLoopListener = object : GameLoopListener {
            override fun onFrame(board: LevelBoard, frameNanos: Long, stepsRun: Int) = Unit
            override fun onLoopStopped() = Unit
        }
    }
}
