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
     * Whether a pop counts as **direct contact** for chains and §5.1 scoring.
     *
     * - A ball touching a candy the Magnet never moved is direct.
     * - A ball touching a candy the Magnet pulled in is direct only when
     *   `magnetPullCountsAsDirectContact` (default true, per §4.1's Magnet exception).
     * - A pop by an effect (Sugar Pop, gem effects) is never direct.
     *
     * @param touched true when the ball's sensor touched the candy; false for an effect pop
     * @param magnetPulled true when the Magnet moved the candy before it was touched
     */
    fun countsAsDirect(touched: Boolean, magnetPulled: Boolean = false): Boolean =
        touched && (!magnetPulled || config.magnetPullCountsAsDirectContact)

    /**
     * Evaluates a candy collection against the chain rules (§4.1).
     *
     * A direct pop always extends the chain. A non-direct (effect) pop extends it only when
     * `onlyDirectContactExtendsChain` is false **and** `effectPoppedCandiesExtendChain` is true, so
     * both flags must agree before an effect pop can build a chain. The defaults (true / false) give
     * §4.1's "only direct contact builds chains".
     *
     * @param candy the candy that was popped
     * @param direct true if collected by direct ball contact — see [countsAsDirect] for magnet
     *   pulls; false if popped by an effect
     * @return the result indicating chain progression and triggers
     */
    fun onCandyPopped(candy: Candy, direct: Boolean): ChainResult {
        var sugarPop = false
        var extraBall = false

        val extendsChain = direct ||
            (!config.onlyDirectContactExtendsChain && config.effectPoppedCandiesExtendChain)

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

        return ChainResult(currentChain, sugarPop, extraBall, extendsChain)
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
    /** True if this pop extended (or restarted) the chain; false if it left the chain untouched. */
    val extended: Boolean = true,
)
