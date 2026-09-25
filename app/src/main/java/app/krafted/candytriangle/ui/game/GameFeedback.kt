package app.krafted.candytriangle.ui.game

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType

/** Presentation-only feedback emitted from authoritative game events. */
data class GameFeedbackCue(
    val type: GameFeedbackType,
    val x: Float = Float.NaN,
    val y: Float = Float.NaN,
    val candyColor: CandyColor? = null,
    val gemType: GemType? = null,
    val amount: Int = 1,
    val sequence: Long,
)

enum class GameFeedbackType {
    CANDY,
    GEM,
    CUP,
    CHAIN,
    OBJECTIVE,
}
