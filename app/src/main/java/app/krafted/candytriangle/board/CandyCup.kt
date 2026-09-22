package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball

/**
 * The Candy Cup (§3.2, §5.1) that slides horizontally along the base of the board.
 *
 * @param width the catch width of the cup
 * @param speed the sliding speed in board units per second
 * @param boardWidth the total width of the board's base
 */
class CandyCup(
    val width: Float,
    var speed: Float,
    val boardWidth: Float = 1000f,
) {
    /** The center x-coordinate of the cup. */
    var x: Float = boardWidth / 2f

    /** Direction of movement: 1 for right (+x), -1 for left (-x). */
    var direction: Int = 1

    /**
     * Updates the cup's position based on the elapsed time.
     *
     * @param dt the elapsed time in seconds.
     */
    fun updateMotion(dt: Float) {
        if (speed <= 0f) return

        val halfWidth = width / 2f
        val minX = halfWidth
        val maxX = boardWidth - halfWidth

        x += speed * direction * dt

        // Bounce at the edges
        if (x >= maxX) {
            x = maxX - (x - maxX)
            direction = -1
        } else if (x <= minX) {
            x = minX + (minX - x)
            direction = 1
        }
    }

    /**
     * Checks if the given ball falls within the cup's width bounds.
     *
     * Note: The caller is responsible for checking if the ball has reached the correct
     * y-coordinate (i.e. crossing the cup lane). This only checks horizontal intersection.
     */
    fun checkCatch(ball: Ball): Boolean {
        return Math.abs(ball.x - x) <= width / 2f
    }
}
