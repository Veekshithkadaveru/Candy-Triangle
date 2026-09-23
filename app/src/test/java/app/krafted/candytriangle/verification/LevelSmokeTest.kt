package app.krafted.candytriangle.verification

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.LevelDef
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Whole-level smoke run: every level, every aim from −70° to +70° in 5° steps, one drop each on a
 * fresh board. Each drop must end before the step cap without throwing, and the session's books
 * must balance:
 *
 * - `totalScore` ≥ 0 and equals ∑ `DropCompleted.dropScore` + ∑ `SugarRush.bonus`;
 * - exactly one `DropCompleted` per drop;
 * - no candy is counted twice: `collectedByColor` per colour == candies made inactive of that
 *   colour == `CandyPopped` events of that colour.
 */
class LevelSmokeTest {

    @Test
    fun everyAimOnEveryLevelFinishesCleanlyWithBalancedBooks() {
        val levels = RealLevels.levels
        RealLevels.config
        val pool = Executors.newFixedThreadPool(max(1, Runtime.getRuntime().availableProcessors()))
        val results = try {
            val futures = levels.map { level -> pool.submit(Callable { runLevel(level) }) }
            levels.zip(futures.map { it.get() })
        } finally {
            pool.shutdownNow()
        }
        val failures = LevelFailures("smoke (−70°..70° step 5°)")
        val summary = StringBuilder()
        for ((level, r) in results) {
            r.problems.forEach { failures.add(level, it) }
            summary.append(
                String.format(
                    "  %-10s max %5d steps/drop, best drop score %7d, candies popped %3d max%n",
                    RealLevels.label(level), r.maxSteps, r.bestScore, r.maxPopped,
                ),
            )
        }
        println("LevelSmokeTest:\n$summary")
        failures.assertNone()
    }

    private class Result(val problems: List<String>, val maxSteps: Int, val bestScore: Int, val maxPopped: Int)

    private fun runLevel(level: LevelDef): Result {
        val problems = ArrayList<String>()
        var maxSteps = 0
        var bestScore = 0
        var maxPopped = 0
        for (aimDeg in -70..70 step 5) {
            val where = "aim $aimDeg°"
            try {
                val board = RealLevels.board(level)
                val recorder = EventRecorder.start(board.session)
                var steps = 0
                try {
                    if (board.launch(degrees(aimDeg.toDouble())) == null) {
                        problems += "$where: launch refused on a fresh board"
                        continue
                    }
                    steps = board.stepUntilDropEnds()
                } finally {
                    recorder.stop()
                }
                maxSteps = max(maxSteps, steps)
                val s = board.session
                val events = recorder.events
                if (board.isDropActive) {
                    problems += "$where: drop still active after ${LevelBoard.DEFAULT_MAX_DROP_STEPS} steps " +
                        "(balls ${board.world.balls})"
                    continue
                }
                if (s.totalScore < 0) problems += "$where: totalScore ${s.totalScore} < 0"
                val drops = events.filterIsInstance<GameEvent.DropCompleted>()
                if (drops.size != 1) problems += "$where: ${drops.size} DropCompleted events for one drop"
                val dropSum = drops.sumOf { it.dropScore }
                val rush = events.filterIsInstance<GameEvent.SugarRush>().sumOf { it.bonus }
                if (s.isLevelComplete && events.none { it is GameEvent.SugarRush }) {
                    problems += "$where: level completed without a SugarRush event"
                }
                if (dropSum + rush != s.totalScore) {
                    problems += "$where: totalScore ${s.totalScore} != ∑dropScore $dropSum + Sugar Rush $rush"
                }
                for (drop in drops) {
                    if (drop.dropScore != drop.subtotal * drop.dropMultiplier) {
                        problems += "$where: $drop — dropScore != subtotal × multiplier"
                    }
                }
                bestScore = max(bestScore, dropSum)

                val inactive = board.candies.filter { !it.active }
                maxPopped = max(maxPopped, inactive.size)
                val popEvents = events.filterIsInstance<GameEvent.CandyPopped>()
                for (color in CandyColor.entries) {
                    val made = inactive.count { it.color == color }
                    val collected = s.collectedByColor[color] ?: 0
                    val popped = popEvents.count { it.color == color }
                    if (made != collected || made != popped) {
                        problems += "$where: $color candies made inactive $made, collectedByColor $collected, " +
                            "CandyPopped events $popped"
                    }
                }
            } catch (t: Throwable) {
                problems += "$where: threw $t @ ${t.stackTrace.take(4).joinToString(" < ")}"
            }
        }
        return Result(problems, maxSteps, bestScore, maxPopped)
    }
}
