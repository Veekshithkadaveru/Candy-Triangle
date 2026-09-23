package app.krafted.candytriangle.verification

import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.ObjectiveType
import org.junit.Test

/**
 * §11 #3, level feasibility: every item objective has at least 1.5× its target actually placed on
 * the board `LevelBoard.create` builds (100% presence for `CLEAR_COLOR`), and no objective is
 * already complete before the first ball. Prints the per-level table C1 tunes against.
 */
class LevelFeasibilityTest {

    @Test
    fun everyItemObjectiveHasOneAndAHalfTimesItsTargetOnTheBoard() {
        val failures = LevelFailures("§11.3 feasibility")
        val table = StringBuilder()
        table.append(String.format("%-10s %-38s %8s %9s %7s%n", "level", "objective", "target", "available", "ratio"))
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            val candies = board.candies
            val gems = board.layout.gems
            for (o in level.objectives) {
                val tracker = board.session.objectiveTracker
                failures.check(level, !tracker.isComplete(o, board.boardState)) {
                    "objective $o is already complete before the first ball"
                }
                var target: Int? = null
                var available: Int? = null
                val label: String
                when (o.type) {
                    ObjectiveType.COLLECT_CANDY -> {
                        label = "COLLECT_CANDY ${o.color ?: "ANY"}"
                        target = o.count ?: 0
                        available = candies.count { o.color == null || it.color == o.color }
                        failures.check(level, target > 0) { "$label has a non-positive count $target" }
                        failures.check(level, available >= 1.5 * target) {
                            "$label ${o.count}: only $available placed (< 1.5 × $target = ${1.5 * target})"
                        }
                    }
                    ObjectiveType.COLLECT_GEM -> {
                        label = "COLLECT_GEM ${o.gem ?: "ANY"}"
                        target = o.count ?: 0
                        available = gems.count { o.gem == null || it.type == o.gem }
                        failures.check(level, target > 0) { "$label has a non-positive count $target" }
                        // A gem capped per level (Sugar Storm, §4.2 "Max 1/level") can never be
                        // placed at 1.5× a target of 1, so it needs 100% instead — and a target
                        // no larger than its cap.
                        val cap = o.gem?.let { perLevelCap(it) }
                        if (cap != null) {
                            failures.check(level, target <= cap && available >= target) {
                                "$label ${o.count}: capped at $cap per level, $available placed"
                            }
                        } else {
                            failures.check(level, available >= 1.5 * target) {
                                "$label ${o.count}: only $available placed (< 1.5 × $target = ${1.5 * target})"
                            }
                        }
                    }
                    ObjectiveType.CLEAR_COLOR -> {
                        label = "CLEAR_COLOR ${o.color}"
                        available = candies.count { it.color == o.color }
                        target = available
                        failures.check(level, available >= 1) { "$label: no ${o.color} candy placed" }
                    }
                    ObjectiveType.SCORE -> label = "SCORE ${o.score}"
                    ObjectiveType.CHAIN -> label = "CHAIN ${o.chain} ×${o.count ?: 1}"
                    ObjectiveType.CUP -> label = "CUP ${o.count}"
                }
                val ratio = if (target != null && available != null && target > 0) {
                    String.format("%.2f", available.toDouble() / target)
                } else "-"
                table.append(
                    String.format(
                        "%-10s %-38s %8s %9s %7s%n",
                        RealLevels.label(level), label, target?.toString() ?: "-",
                        available?.toString() ?: "-", ratio,
                    ),
                )
            }
            val byColor = candies.groupingBy { it.color }.eachCount().toSortedMap()
            table.append(
                String.format(
                    "%-10s %-38s%n", "", "placed: ${candies.size} candies $byColor, " +
                        "${gems.size} gems ${gems.groupingBy { it.type }.eachCount()}",
                ),
            )
        }
        println("LevelFeasibilityTest — per-level objective availability (§11.3)\n$table")
        failures.assertNone()
    }

    /** The per-level cap a gem's effect imposes, or null when it is uncapped. */
    private fun perLevelCap(type: GemType): Int? =
        (RealLevels.config.gem(type)?.effect as? GemEffect.PopAllOfLargestColour)?.maxPerLevel
}
