package app.krafted.candytriangle.levels

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.level.RowCol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * C1 authoring report for `assets/levels.json` — the level designer's feedback tool, not a gate.
 *
 * Builds every shipped level on the real `LevelBoard` and prints one row per level: pegs, gems
 * placed/rejected, candies per colour, fixed candies honoured, each objective's target against
 * what the board actually offers, and the result of a greedy look-ahead bot. The table goes to
 * stdout (`-i`) and to `<root>/build/agent-b/level-report.txt`.
 *
 * The bot is omniscient and therefore far stronger than a player: for each drop it replays the
 * level from scratch with the aims chosen so far, tries every aim in [AIMS_DEG] and keeps the one
 * with the best objective progress. "Cleared with N left" is an upper bound on how easy a level
 * is. A second run picks the 75th-percentile aim each drop ("p75") — a rough stand-in for a player
 * who aims sensibly but cannot see the outcome — and is what SCORE targets were calibrated against.
 *
 * Environment knobs (Gradle forwards the environment to the test JVM):
 * - `CT_LEVELS=1,2,101` restricts the run to those ids;
 * - `CT_BOT=0` skips the bot (static table only, well under a second);
 * - `CT_TRACE=1` prints each bot line drop by drop.
 *
 * Gradle does not treat `assets/levels.json` as a unit-test input, so rerun with `--rerun` after
 * regenerating. Full run with the bot: ~17 s on 8 threads.
 *
 * It does assert the authoring invariants C2 also relies on — zero rejected gems, every fixed
 * candy placed, the seeded count honoured — so a regenerated `levels.json` cannot silently break.
 */
class LevelAuthoringReportTest {

    private val assets = AssetSource { path ->
        File("src/main/assets/$path").takeIf(File::exists)?.readText()
    }

    @Test
    fun levelAuthoringReport() {
        val config = runBlocking { ConfigLoader(assets).config() }
        val all = runBlocking { LevelRepository(assets).catalog().levels }
        assertTrue("levels.json missing or empty (cwd ${File("").absolutePath})", all.isNotEmpty())
        val only = System.getenv("CT_LEVELS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val runBot = System.getenv("CT_BOT") != "0"
        val levels = all.filter { only == null || it.id in only }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(threads)
        val started = System.nanoTime()
        val rows = try {
            levels.map { level -> pool.submit(Callable { analyse(level, config, runBot) }) }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        val seconds = (System.nanoTime() - started) / 1e9

        val out = StringBuilder()
        out.appendLine("C1 level authoring report — ${levels.size} levels, bot=${if (runBot) "on" else "off"}, ${"%.1f".format(seconds)} s on $threads threads")
        out.appendLine("id    w  b  d  crowns pegs gems  G/Pu/Pk/B      fixed  objectives (target/avail)                                   | bot")
        rows.forEach { out.appendLine(it.line) }
        val text = out.toString()
        println(text)
        File("../build/agent-b").mkdirs()
        File("../build/agent-b/level-report.txt").writeText(text)

        val problems = rows.flatMap { it.problems }
        assertTrue("authoring problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private class Row(val line: String, val problems: List<String>)

    private fun analyse(level: LevelDef, config: GameConfig, runBot: Boolean): Row {
        val board = LevelBoard.create(level, config)
        val problems = mutableListOf<String>()
        val pegs = board.layout.pegs.count { it.kind == ColliderKind.PEG }
        val gems = board.layout.gems
        if (board.layout.rejectedGems.isNotEmpty()) {
            problems += "L${level.id}: rejected gems ${board.layout.rejectedGems}"
        }
        val byColor = CandyColor.entries.associateWith { c -> board.candies.count { it.color == c } }
        val fixedOk = level.candies.fixed.count { f ->
            board.candies.any { it.rc == RowCol(f.row, f.col) && it.color == f.color }
        }
        if (fixedOk != level.candies.fixed.size) {
            problems += "L${level.id}: only $fixedOk/${level.candies.fixed.size} fixed candies placed"
        }
        val expected = level.candies.fixed.size + level.candies.count
        if (board.candies.size != expected) {
            problems += "L${level.id}: ${board.candies.size} candies placed, $expected authored"
        }

        val objectives = level.objectives.joinToString("  ") { o ->
            val (target, avail) = when (o.type) {
                ObjectiveType.COLLECT_CANDY ->
                    (o.count ?: 0) to (o.color?.let { byColor.getValue(it) } ?: board.candies.size)
                ObjectiveType.COLLECT_GEM ->
                    (o.count ?: 0) to (o.gem?.let { t -> gems.count { it.type == t } } ?: gems.size)
                ObjectiveType.CLEAR_COLOR -> byColor.getValue(o.color!!) to byColor.getValue(o.color!!)
                ObjectiveType.SCORE -> (o.score ?: 0) to -1
                ObjectiveType.CHAIN -> (o.chain ?: 0) to (o.count ?: 1)
                ObjectiveType.CUP -> (o.count ?: 0) to -1
            }
            // A gem with a per-level cap (Sugar Storm, maxPerLevel 1) cannot be placed 1.5x over:
            // for it the rule is target <= placed, as in C2's LevelFeasibilityTest.
            val capped = o.type == ObjectiveType.COLLECT_GEM && o.gem == GemType.SUGAR_STORM
            if (capped && target > avail) {
                problems += "L${level.id}: COLLECT_GEM SUGAR_STORM target $target > placed $avail"
            }
            if (!capped && (o.type == ObjectiveType.COLLECT_CANDY || o.type == ObjectiveType.COLLECT_GEM) &&
                target * 1.5 > avail
            ) {
                problems += "L${level.id}: ${o.type} target $target > available $avail / 1.5"
            }
            if (o.type == ObjectiveType.CLEAR_COLOR && avail == 0) {
                problems += "L${level.id}: CLEAR_COLOR ${o.color} has no candies"
            }
            val tag = o.color?.name?.take(2) ?: o.gem?.name?.take(2) ?: "*"
            when (o.type) {
                ObjectiveType.SCORE -> "SCORE $target"
                ObjectiveType.CUP -> "CUP $target"
                ObjectiveType.CHAIN -> "CHAIN ${target}x$avail"
                ObjectiveType.CLEAR_COLOR -> "CLEAR $tag($avail)"
                else -> "${o.type.name.removePrefix("COLLECT_")} $tag $target/$avail(${"%.1f".format(avail.toDouble() / target)})"
            }
        }

        val bot = if (runBot) {
            val b = Bot(level, config)
            "best: ${b.run(1.0)} | p75: ${b.run(0.75)}"
        } else "-"
        val line = "%-5s %d %2d %2d %-6s %4d %2d/%-2d %-14s %2d/%-2d  %-60s| %s".format(
            level.code ?: level.id.toString(), level.world, level.balls, level.layout.spacing.toInt(),
            level.crowns.toString().replace(" ", ""), pegs, gems.size, board.layout.rejectedGems.size,
            CandyColor.entries.joinToString("/") { byColor.getValue(it).toString() },
            fixedOk, level.candies.fixed.size, objectives, bot,
        )
        return Row(line, problems)
    }

    /**
     * Greedy replay bot. Each candidate is evaluated on a fresh board that replays the chosen aims
     * first — the boards expose no snapshot/copy, and the physics is deterministic, so a replay is
     * an exact copy.
     */
    private class Bot(private val level: LevelDef, private val config: GameConfig) {

        private val initialByColor: Map<CandyColor, Int> =
            LevelBoard.create(level, config).candies.groupingBy { it.color }.eachCount()

        /**
         * Plays the level choosing, for each drop, the candidate at [quantile] of the ranked aims:
         * 1.0 is the omniscient best aim, 0.5 the median aim — a stand-in for a typical player who
         * cannot see the outcome before firing.
         */
        fun run(quantile: Double): String {
            val chosen = ArrayList<Float>()
            var board = replay(chosen)
            while (!board.session.isLevelComplete && !board.session.isLevelFailed && board.session.remainingBalls > 0) {
                val ranked = AIMS_DEG.map { deg ->
                    val aim = Math.toRadians(deg.toDouble()).toFloat()
                    val candidate = replay(chosen + aim)
                    Triple(aim, candidate, value(candidate))
                }.sortedByDescending { it.third } // stable: ties keep aim order
                val pick = ranked[((ranked.size - 1) * (1.0 - quantile)).toInt()]
                chosen += pick.first
                board = pick.second
                if (chosen.size > 40) break // cup/extra-ball refunds can't loop forever in practice
            }
            val s = board.session
            if (System.getenv("CT_TRACE") == "1") trace(chosen, quantile)
            return if (s.isLevelComplete) {
                val crowns = when {
                    s.remainingBalls >= level.threeCrownBalls -> 3
                    s.remainingBalls >= level.twoCrownBalls -> 2
                    else -> 1
                }
                "CLEAR %2d left %2d drops %dc %6d".format(s.remainingBalls, chosen.size, crowns, s.totalScore)
            } else {
                val progress = level.objectives.joinToString(",") { o -> "%.0f%%".format(100 * fraction(board, o)) }
                val stranded = level.objectives.filter { it.type == ObjectiveType.CLEAR_COLOR }.flatMap { o ->
                    board.candies.filter { it.active && it.color == o.color }.map { "(${it.rc.row},${it.rc.col})" }
                }
                "FAIL  [$progress] ${s.totalScore}" + if (stranded.isEmpty()) "" else " left@" + stranded.joinToString("")
            }
        }

        /** Per-drop breakdown of the chosen line (CT_TRACE=1), for sanity-checking the bot. */
        private fun trace(aims: List<Float>, quantile: Double) {
            val b = LevelBoard.create(level, config)
            val sb = StringBuilder("trace L${level.id} q=$quantile\n")
            for (a in aims) {
                val before = b.candies.count { it.active }
                val scoreBefore = b.session.totalScore
                b.launch(a) ?: break
                val steps = b.stepUntilDropEnds()
                sb.append("  aim=%.0f steps=%d popped=%d dropScore=%d balls=%d gemsLeft=%d\n".format(
                    Math.toDegrees(a.toDouble()), steps, before - b.candies.count { it.active },
                    b.session.totalScore - scoreBefore, b.session.remainingBalls, b.layout.gems.count { it.active }))
            }
            println(sb)
        }

        private fun replay(aims: List<Float>): LevelBoard {
            val b = LevelBoard.create(level, config)
            for (a in aims) {
                b.launch(a) ?: break
                b.stepUntilDropEnds()
            }
            return b
        }

        private fun fraction(b: LevelBoard, o: ObjectiveDef): Double {
            val t = b.session.objectiveTracker
            val raw = when (o.type) {
                ObjectiveType.SCORE -> t.getProgress(o).toDouble() / (o.score ?: 1)
                ObjectiveType.CLEAR_COLOR -> {
                    val initial = initialByColor[o.color] ?: 0
                    if (initial == 0) 1.0 else 1.0 - b.candies.count { it.active && it.color == o.color }.toDouble() / initial
                }
                else -> t.getProgress(o).toDouble() / (o.count ?: 1)
            }
            return raw.coerceIn(0.0, 1.0)
        }

        private fun value(b: LevelBoard): Double {
            val s = b.session
            var v = level.objectives.sumOf { fraction(b, it) } * 1000.0
            v += s.totalScore * 1e-3
            v += s.remainingBalls * 150.0
            if (s.isLevelComplete) v += 1e6 + s.remainingBalls * 1e4
            return v
        }
    }

    private companion object {
        val AIMS_DEG: List<Int> = (-70..70 step 5).toList()
    }
}
