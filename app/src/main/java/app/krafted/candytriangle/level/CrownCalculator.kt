package app.krafted.candytriangle.level

/**
 * Ball threshold crown evaluator (§5.3).
 */
object CrownCalculator {

    /**
     * Calculates the number of crowns (1-3) earned for completing a level.
     *
     * @param levelDef The definition of the level being played.
     * @param remainingBalls The number of balls the player has remaining when objectives are completed.
     * @param objectivesMet Whether all level objectives were successfully completed.
     * @return The number of crowns earned (0 if objectives were not met, otherwise 1, 2, or 3).
     */
    fun calculateCrowns(levelDef: LevelDef, remainingBalls: Int, objectivesMet: Boolean): Int {
        if (!objectivesMet) return 0

        // levelDef.crowns should be a list of two ints: [twoCrownBalls, threeCrownBalls]
        val twoCrownThreshold = levelDef.twoCrownBalls
        val threeCrownThreshold = levelDef.threeCrownBalls

        return when {
            remainingBalls >= threeCrownThreshold -> 3
            remainingBalls >= twoCrownThreshold -> 2
            else -> 1
        }
    }
}
