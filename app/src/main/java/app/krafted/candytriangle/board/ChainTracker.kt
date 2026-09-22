package app.krafted.candytriangle.board

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.ChainConfig

/**
 * Tracks consecutive color hits and evaluates chain rewards like Sugar Pop (§4.1, §8).
 */
class ChainTracker(
    val config: ChainConfig,
) {
    /** The color of the currently active chain. */
    var currentColor: CandyColor? = null
        private set
    
    /** The length of the currently active chain. */
    var currentChain: Int = 0
        private set
        
    /** How many extra balls have been awarded on this single ball drop. */
    var extraBallsAwarded: Int = 0
        private set

    /**
     * Resets the chain state for a new ball drop.
     */
    fun resetForDrop() {
        currentColor = null
        currentChain = 0
        extraBallsAwarded = 0
    }

    /**
     * Evaluates a candy collection against the chain rules.
     * 
     * @param candy the candy that was popped
     * @param direct true if collected by direct ball contact (including magnet), false if popped by an effect
     * @return the result indicating chain progression and triggers
     */
    fun onCandyPopped(candy: Candy, direct: Boolean): ChainResult {
        var sugarPop = false
        var extraBall = false
        
        val extendsChain = if (direct) {
            true // Direct contact always extends (unless onlyDirectContactExtendsChain is strictly false for some weird reason, but PRD says direct extends)
        } else {
            config.effectPoppedCandiesExtendChain
        }

        if (extendsChain) {
            if (currentColor == candy.color) {
                currentChain++
            } else {
                currentColor = candy.color
                currentChain = config.chainResetValueOnColourChange
            }
            
            if (currentChain == config.sugarPopChain) {
                sugarPop = true
            }
            
            if (currentChain == config.extraBallChain && extraBallsAwarded < config.extraBallMaxPerLaunchedBall) {
                extraBall = true
                extraBallsAwarded++
            }
        }
        
        return ChainResult(currentChain, sugarPop, extraBall)
    }
    
    /**
     * Called when a solid gem is hit.
     */
    fun onGemHit() {
        if (config.gemHitBreaksChain) {
            currentColor = null
            currentChain = 0
        }
    }
}

/**
 * Output of a candy pop evaluation.
 */
data class ChainResult(
    /** The chain length after this pop. If the pop didn't extend the chain, this is the current length. */
    val chainLength: Int,
    /** True if this pop triggered the Sugar Pop threshold. */
    val triggerSugarPop: Boolean,
    /** True if this pop triggered the Extra Ball threshold. */
    val triggerExtraBall: Boolean,
)
