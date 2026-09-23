package app.krafted.candytriangle.ui.board

/**
 * Turns a touch point into a launcher aim angle.
 *
 * Angle convention is `PhysicsParams`': **0 is straight down (+y), positive toward +x**, and the
 * result is clamped to +-`params.aimClampRadians` (70 deg). Uses `kotlin.math.atan2`, not
 * `StrictMath` — the touch is non-deterministic anyway, and determinism enters downstream at
 * `PhysicsParams.launchVx/launchVy`, which are already `StrictMath`.
 *
 * The pivot is `config.board.launcher.pivot` = (500, 0), the apex — **not** the spawn point
 * (500, 75). Every function here is total: no input, including the pivot itself, a point above the
 * pivot, NaN or an infinity, may produce a non-finite result.
 *
 * Owner: Agent 1 (`ui/board`).
 */
object AimMath {

    /** Aim from a pivot to a touch, both in board units. */
    fun aimFor(
        pivotX: Float,
        pivotY: Float,
        touchX: Float,
        touchY: Float,
        clampRadians: Float,
    ): Float = TODO("owner: Agent 1")

    /** [aimFor] with the touch given in screen px, mapped back through [transform]. */
    fun aimForScreen(
        transform: BoardTransform,
        pivotBoardX: Float,
        pivotBoardY: Float,
        touchScreenX: Float,
        touchScreenY: Float,
        clampRadians: Float,
    ): Float = TODO("owner: Agent 1")
}
