package app.krafted.candytriangle.level

/**
 * Events streamed from the engine/board state to the ViewModel.
 */
sealed interface GameEvent {
    /** A candy was popped either by direct hit or effect. */
    data class CandyPopped(
        val color: CandyColor,
        val isDirect: Boolean,
        val points: Int
    ) : GameEvent

    /** A gem was smashed. */
    data class GemSmashed(
        val gemType: GemType,
        val points: Int
    ) : GameEvent

    /** The ball was caught in the cup. */
    data class CupCaught(
        val points: Int
    ) : GameEvent

    /** The chain of direct candy hits has advanced. */
    data class ChainAdvanced(
        val color: CandyColor,
        val length: Int
    ) : GameEvent

    /** The active drop has ended (all balls exited or caught). */
    data class DropCompleted(
        val dropScore: Int,
        val dropMultiplier: Int,
        val subtotal: Int
    ) : GameEvent

    /** Objective progress was updated. */
    data object ObjectiveProgressUpdated : GameEvent

    /** The level was successfully completed. */
    data class LevelCompleted(
        val crowns: Int,
        val finalScore: Int
    ) : GameEvent

    /** The level was failed (out of balls with objectives incomplete). */
    data class LevelFailed(
        val reason: String = "Out of balls"
    ) : GameEvent

    /** A new ball was spawned. */
    data object BallSpawned : GameEvent

    /** A ball exited the board. */
    data object BallExited : GameEvent

    /** A ball left the launcher (one per [LevelSession.launchBall]). */
    data class BallLaunched(val aimRadians: Float, val ballsRemaining: Int) : GameEvent

    /** A chain reached the Sugar Pop threshold and popped [popped] same-colour candies (§4.1). */
    data class SugarPop(val color: CandyColor, val x: Float, val y: Float, val popped: Int) : GameEvent

    /** A gem's board effect fired at (x, y) — the D5 VFX hook (Blast ring, Line beam, Magnet aura...). */
    data class GemEffectTriggered(val gemType: GemType, val x: Float, val y: Float, val popped: Int) : GameEvent

    /** Sugar Rush bonus applied on completion (§5.1): [balls] remaining × 500. */
    data class SugarRush(val balls: Int, val bonus: Int) : GameEvent
}
