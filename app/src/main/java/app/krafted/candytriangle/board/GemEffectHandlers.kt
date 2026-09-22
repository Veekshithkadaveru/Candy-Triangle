package app.krafted.candytriangle.board

import app.krafted.candytriangle.level.GemEffect

/**
 * Executes the pure-logic side of Gem Effects (§4.2, §8).
 * 
 * Returns an [EffectResult] describing the consequences of the smash (candies popped,
 * balls spawned, etc.), leaving the actual application (points, object creation)
 * to the engine pipeline.
 */
object GemEffectHandlers {

    /**
     * Resolves the effect against the current board state.
     * 
     * @param effect the GemEffect configuration
     * @param gem the physical Gem that was broken
     * @param state the BoardState for spatial queries
     * @param latticeSpacing the `d` spacing parameter for the level
     * @param remainingSugarStorms how many Sugar Storms are left this level
     */
    fun applyEffect(
        effect: GemEffect, 
        gem: Gem, 
        state: BoardState, 
        latticeSpacing: Float,
        remainingSugarStorms: Int = 1,
    ): EffectResult {
        var poppedCandies = emptyList<Candy>()
        var spawnBalls = 0
        var mirrorHorizontal = false
        var refundBalls = 0
        var magnetDuration = 0f
        var magnetRadius = 0f
        var sugarStormConsumed = false

        when (effect) {
            is GemEffect.None -> {
                // Sweet Gem: pure multiplier, no board effect.
            }
            is GemEffect.PopRadius -> {
                // Blast Gem: pops every candy within radiusFactor * d.
                val r = effect.radiusFactor * latticeSpacing
                poppedCandies = state.getCandiesInRadius(gem.x, gem.y, r)
            }
            is GemEffect.PopBand -> {
                // Line Gem: pops candies within plus or minus bandRows * equilateral row height.
                val rowHeight = latticeSpacing * 0.8660254f
                val halfHeight = effect.bandRows * rowHeight
                poppedCandies = state.getCandiesInBand(gem.y, halfHeight)
            }
            is GemEffect.SplitBall -> {
                // Split Gem: spawns clones.
                spawnBalls = effect.clones
                mirrorHorizontal = effect.mirrorHorizontalVelocity
            }
            is GemEffect.RefundBalls -> {
                // Extra Ball Gem: refunds balls.
                refundBalls = effect.balls
            }
            is GemEffect.Magnet -> {
                // Magnet Gem: triggers magnet state.
                magnetDuration = effect.durationSeconds
                magnetRadius = effect.radiusFactor * latticeSpacing
            }
            is GemEffect.PopAllOfLargestColour -> {
                // Sugar Storm: pops every candy of the most-populous remaining colour.
                if (remainingSugarStorms > 0) {
                    sugarStormConsumed = true
                    val color = state.getLargestCandyColor()
                    if (color != null) {
                        poppedCandies = state.getCandiesByColor(color)
                    }
                }
            }
        }

        return EffectResult(
            poppedCandies = poppedCandies,
            spawnBalls = spawnBalls,
            mirrorHorizontal = mirrorHorizontal,
            refundBalls = refundBalls,
            magnetDuration = magnetDuration,
            magnetRadius = magnetRadius,
            sugarStormConsumed = sugarStormConsumed
        )
    }
}

/**
 * The consequences of a gem smash.
 */
data class EffectResult(
    /** Candies popped instantly by the effect (e.g. Blast, Line, Sugar Storm). */
    val poppedCandies: List<Candy>,
    /** Number of balls to spawn (Split Gem). */
    val spawnBalls: Int,
    /** Whether spawned balls should mirror the horizontal velocity of the current ball. */
    val mirrorHorizontal: Boolean,
    /** Number of balls refunded to the launcher (Extra Ball). */
    val refundBalls: Int,
    /** How long the magnet effect lasts (seconds). */
    val magnetDuration: Float,
    /** The magnet radius of effect. */
    val magnetRadius: Float,
    /** True if a Sugar Storm charge was consumed. */
    val sugarStormConsumed: Boolean,
)
