package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import kotlin.math.max

/**
 * Tracks objective completion state (§6.3, B4).
 */
class ObjectiveTracker(private val objectives: List<ObjectiveDef>) {

    // Accumulated count or score per objective, by its position in [objectives] — never keyed by
    // the def itself. ObjectiveDef is a data class, so two identical objectives (one goal authored
    // twice) would share a single map entry that every event bumps once per objective: "collect 5
    // pink" listed twice would complete after 3 pops.
    private val progress = IntArray(objectives.size)

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
     * Gets the current accumulated progress for the given objective. Identical objectives always
     * hold identical progress, so the first match answers for all of them; an objective this
     * tracker was not built with reads 0.
     */
    fun getProgress(def: ObjectiveDef): Int {
        val index = objectives.indexOf(def)
        return if (index >= 0) progress[index] else 0
    }

    /**
     * Processes a game event to update progress for accumulating objectives.
     */
    fun processEvent(event: GameEvent) {
        when (event) {
            is GameEvent.CandyPopped ->
                bump { it.type == ObjectiveType.COLLECT_CANDY && (it.color == null || it.color == event.color) }

            is GameEvent.GemSmashed ->
                bump { it.type == ObjectiveType.COLLECT_GEM && (it.gem == null || it.gem == event.gemType) }

            is GameEvent.CupCaught -> bump { it.type == ObjectiveType.CUP }

            // Increment count exactly once when the chain reaches the target length.
            is GameEvent.ChainAdvanced ->
                bump { it.type == ObjectiveType.CHAIN && event.length == (it.chain ?: 1) }

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
        bump(points) { it.type == ObjectiveType.SCORE }
    }

    /** Adds [amount] to every objective [matches] accepts — once per objective, duplicates included. */
    private inline fun bump(amount: Int = 1, matches: (ObjectiveDef) -> Boolean) {
        for (i in objectives.indices) {
            if (matches(objectives[i])) progress[i] += amount
        }
    }
}
