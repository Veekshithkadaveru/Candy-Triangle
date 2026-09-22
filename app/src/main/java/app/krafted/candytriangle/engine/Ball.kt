package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.BallSkin
import kotlin.math.sqrt

/**
 * A ball in flight (§8 `Ball.kt`): position, velocity, radius, skin and active status.
 *
 * ## Units and axes
 *
 * Logical board units (§3.1): x grows rightward, y grows **downward**, the apex is (500, 0) and the
 * open base is y = 1250. Gravity is therefore +y. Velocities are u/s.
 *
 * ## Why mutable
 *
 * The 240 Hz step touches every ball ~240 times a second, more while a Split Gem clone is alive,
 * and it must not allocate: a GC pause on the game thread is a dropped frame (§13's 60 fps budget).
 * So [PhysicsWorld] updates these fields in place rather than producing copies. Nothing outside
 * the engine should write them; the setters are public only because Kotlin has no package-private.
 *
 * Instances come only from [PhysicsWorld.spawnBall] / [PhysicsWorld.launch], which assign [id] in
 * spawn order. That order is the order the step iterates, so it is part of the determinism
 * contract. Like the rest of the engine, a ball is confined to the game thread.
 */
class Ball internal constructor(
    /** Unique within one [PhysicsWorld]; 0, 1, 2... in spawn order. */
    val id: Int,
    x: Float,
    y: Float,
    vx: Float,
    vy: Float,
    /** Collider radius, u ([PhysicsParams.ballRadius]). */
    val radius: Float,
    /** Cosmetic only — physics never reads it. Carried so the renderer draws the §7 skin. */
    val skin: BallSkin,
) {

    /** Centre, u. */
    var x: Float = x
    var y: Float = y

    /** Velocity, u/s. */
    var vx: Float = vx
    var vy: Float = vy

    /**
     * Centre at the start of the most recent step. The renderer draws at
     * `lerp(prev, current, PhysicsWorld.interpolationAlpha)` so motion stays smooth on displays
     * whose refresh rate does not divide 240.
     */
    var prevX: Float = x
    var prevY: Float = y

    /** False once the ball has left through the open base or been removed; see [PhysicsWorld]. */
    var active: Boolean = true

    /** Consecutive steps spent below [PhysicsParams.stuckSpeedThreshold] (§3.3 stuck rescue). */
    var stuckSteps: Int = 0

    /** Current speed, u/s. */
    val speed: Float get() = sqrt(vx * vx + vy * vy)

    override fun toString(): String = "Ball#$id(x=$x, y=$y, vx=$vx, vy=$vy, active=$active)"
}
