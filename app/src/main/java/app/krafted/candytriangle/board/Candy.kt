package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Contact
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.RowCol
import kotlin.math.sqrt

/**
 * A non-solid sensor candy item entity (§4.1, §8).
 * 
 * Candies do not participate in the PhysicsWorld step directly since they are non-solid sensors.
 * Instead, the board checks for collision after each step.
 */
class Candy(
    val id: Int,
    val color: CandyColor,
    var x: Float,
    var y: Float,
    val rc: RowCol,
) {
    /** True if this candy is still on the board. */
    var active: Boolean = true

    /**
     * Ball-vs-sensor overlap test.
     * Returns true if the ball (center cx, cy, radius ballRadius) overlaps this candy.
     * 
     * @param cx ball center x
     * @param cy ball center y
     * @param ballRadius the active ball collider radius
     * @param candyRadius the sensor radius for candy collection (`config.physics.candyRadius`)
     */
    fun contact(cx: Float, cy: Float, ballRadius: Float, candyRadius: Float): Boolean {
        if (!active) return false
        
        val dx = x - cx
        val dy = y - cy
        val reach = ballRadius + candyRadius
        
        // Fast AABB reject
        if (dx >= reach || dx <= -reach || dy >= reach || dy <= -reach) return false
        
        // Squared distance check
        return dx * dx + dy * dy < reach * reach
    }
}
