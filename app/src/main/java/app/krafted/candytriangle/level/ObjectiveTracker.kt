package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import kotlin.math.max

/**
 * Tracks objective completion state (§6.3, B4).
 */
class ObjectiveTracker(private val objectives: List<ObjectiveDef>) {

    // Internal progress store for objectives that accumulate a count or score.
    // Keyed by the objective instance.
    private val progress = mutableMapOf<ObjectiveDef, Int>()

    init {
        objectives.forEach { progress[it] = 0 }
    }

    /**
     * Checks if all objectives are currently met.
     * @param boardState Required for evaluating CLEAR_COLOR objectives.
     */
    fun areAllComplete(boardState: BoardState): Boolean {
        if (objectives.isEmpty()) return true
        return objectives.all { isComplete(it, boardState) }
    }

    /**
     * Checks if a specific objective is met.
     */
    fun isComplete(def: ObjectiveDef, boardState: BoardState): Boolean {
        return when (def.type) {
            ObjectiveType.COLLECT_CANDY -> getProgress(def) >= (def.count ?: 1)
            ObjectiveType.SCORE -> getProgress(def) >= (def.score ?: 1)
            ObjectiveType.COLLECT_GEM -> getProgress(def) >= (def.count ?: 1)
            ObjectiveType.CHAIN -> getProgress(def) >= (def.count ?: 1)
            ObjectiveType.CUP -> getProgress(def) >= (def.count ?: 1)
            ObjectiveType.CLEAR_COLOR -> {
                val targetColor = def.color
                if (targetColor != null) {
                    boardState.getCandiesByColor(targetColor).isEmpty()
                } else {
                    true
                }
            }
        }
    }

    /**
     * Gets the current accumulated progress for the given objective.
     */
    fun getProgress(def: ObjectiveDef): Int = progress[def] ?: 0

    /**
     * Processes a game event to update progress for accumulating objectives.
     */
    fun processEvent(event: GameEvent) {
        when (event) {
            is GameEvent.CandyPopped -> {
                objectives
                    .filter { it.type == ObjectiveType.COLLECT_CANDY && (it.color == null || it.color == event.color) }
                    .forEach { progress[it] = getProgress(it) + 1 }
            }
            is GameEvent.GemSmashed -> {
                objectives
                    .filter { it.type == ObjectiveType.COLLECT_GEM && (it.gem == null || it.gem == event.gemType) }
                    .forEach { progress[it] = getProgress(it) + 1 }
            }
            is GameEvent.CupCaught -> {
                objectives
                    .filter { it.type == ObjectiveType.CUP }
                    .forEach { progress[it] = getProgress(it) + 1 }
            }
            is GameEvent.ChainAdvanced -> {
                objectives
                    .filter { it.type == ObjectiveType.CHAIN }
                    .forEach {
                        // Increment count exactly once when the chain reaches the target length.
                        val targetLength = it.chain ?: 1
                        if (event.length == targetLength) {
                            progress[it] = getProgress(it) + 1
                        }
                    }
            }
            else -> {
                // Other events don't directly drive internal counter progress.
            }
        }
    }

    /**
     * Adds score points.
     */
    fun addScore(points: Int) {
        if (points <= 0) return
        objectives
            .filter { it.type == ObjectiveType.SCORE }
            .forEach { progress[it] = getProgress(it) + points }
    }
}
