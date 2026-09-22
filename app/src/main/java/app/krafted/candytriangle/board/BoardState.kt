package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.level.CandyColor
import kotlin.math.abs

/**
 * Active entity container and spatial index (§8).
 * 
 * Holds the current board state including candies, gems, and the active drop multiplier.
 */
class BoardState(
    val candies: List<Candy>,
    val gems: List<Gem>,
    val params: PhysicsParams,
) {
    /** 
     * The multiplier for the current ball drop (§5.1). 
     * Calculated as: Max(Highest Gem Multiplier Broken in Drop, 1).
     */
    var dropMultiplier: Int = 1
        private set

    /**
     * Prepares the board state for a new ball drop.
     */
    fun resetForDrop() {
        dropMultiplier = 1
    }

    /**
     * Marks a gem as broken and updates the drop multiplier if applicable.
     */
    fun registerGemBreak(gem: Gem) {
        gem.active = false
        if (gem.multiplier > dropMultiplier) {
            dropMultiplier = gem.multiplier
        }
    }

    /**
     * Retrieves all active candies within the specified radius.
     */
    fun getCandiesInRadius(x: Float, y: Float, radius: Float): List<Candy> {
        val radiusSq = radius * radius
        return candies.filter { candy ->
            if (!candy.active) false
            else {
                val dx = candy.x - x
                val dy = candy.y - y
                dx * dx + dy * dy <= radiusSq
            }
        }
    }

    /**
     * Retrieves all active candies within a horizontal band (used for Line Gem).
     */
    fun getCandiesInBand(yCenter: Float, halfHeight: Float): List<Candy> {
        return candies.filter { candy ->
            candy.active && abs(candy.y - yCenter) <= halfHeight
        }
    }

    /**
     * Finds the candy color with the most active candies on the board.
     * Used by the Sugar Storm gem (§4.2).
     */
    fun getLargestCandyColor(): CandyColor? {
        val counts = mutableMapOf<CandyColor, Int>()
        for (c in candies) {
            if (c.active) {
                counts[c.color] = counts.getOrDefault(c.color, 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }
    
    /**
     * Retrieves all active candies matching the given color.
     */
    fun getCandiesByColor(color: CandyColor): List<Candy> {
        return candies.filter { it.active && it.color == color }
    }
}
