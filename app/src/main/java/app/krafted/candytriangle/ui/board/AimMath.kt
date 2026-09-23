package app.krafted.candytriangle.ui.board

import kotlin.math.abs
import kotlin.math.atan2

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
    ): Float {
        // A NaN or infinite clamp cannot be honoured, so the launcher stays where it is: dead
        // centre. coerceIn with a NaN bound would silently pass the raw angle straight through.
        val clamp = if (clampRadians.isFinite()) abs(clampRadians) else 0f

        val dx = touchX - pivotX
        val dy = touchY - pivotY
        // Covers a NaN or infinite pivot/touch, and infinity - infinity.
        if (!dx.isFinite() || !dy.isFinite()) return 0f
        // The pivot itself has no direction. Exactly 0, not atan2(0, 0)'s 0.0-vs--0.0 lottery.
        if (dx == 0f && dy == 0f) return 0f

        // atan2(dx, dy): the *first* argument is x, so the zero angle is +y — straight down.
        val raw = atan2(dx, dy)
        if (!raw.isFinite()) return 0f

        // A touch above the pivot gives |raw| > PI/2 and saturates to the clamp on its own side.
        // Straight above (dx == 0, dy < 0) is atan2(+0, -1) = +PI, so it saturates to +clamp: an
        // arbitrary but deterministic tie-break, matching the engine's "+1 exactly on the axis".
        return raw.coerceIn(-clamp, clamp)
    }

    /** [aimFor] with the touch given in screen px, mapped back through [transform]. */
    fun aimForScreen(
        transform: BoardTransform,
        pivotBoardX: Float,
        pivotBoardY: Float,
        touchScreenX: Float,
        touchScreenY: Float,
        clampRadians: Float,
    ): Float = aimFor(
        pivotX = pivotBoardX,
        pivotY = pivotBoardY,
        touchX = transform.bx(touchScreenX),
        touchY = transform.by(touchScreenY),
        clampRadians = clampRadians,
    )
}
