package app.krafted.candytriangle.verification

import app.krafted.candytriangle.board.LatticeGeometry
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.level.RowCol
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §11 #2 / §3.4: the layout `LevelBoard.create` actually builds for every shipped level obeys the
 * clearance rules — on the real board, including moving rows over their whole swing.
 *
 * Positions are recomputed from each peg's rest position and [app.krafted.candytriangle.engine.PegMotion]
 * (`x(t) = baseX + A·sin(2πt/T)`), sampled at [PHASES] phases per period, never read from a stepped
 * world, so the check is independent of the engine clock.
 */
class LevelClearanceTest {

    private val config get() = RealLevels.config

    @Test
    fun everyAuthoredGemIsPlacedAtItsNodeAndNoneIsRejected() {
        val failures = LevelFailures("§3.4 gem placement")
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            val layout = board.layout
            failures.check(level, layout.rejectedGems.isEmpty()) {
                "LayoutBuilder rejected gems ${layout.rejectedGems}"
            }
            failures.check(level, layout.gems.size == level.gems.size) {
                "${layout.gems.size} gems placed of ${level.gems.size} authored"
            }
            val d = level.layout.spacing
            for (gem in layout.gems) {
                val nx = LatticeGeometry.nodeX(gem.rc, d, config.board)
                val ny = LatticeGeometry.nodeY(gem.rc, d, config.board)
                failures.check(level, abs(gem.peg.x - nx) < 1e-3f && abs(gem.peg.y - ny) < 1e-3f) {
                    "${gem.type} at ${gem.rc} sits at (${gem.peg.x}, ${gem.peg.y}), node is ($nx, $ny)"
                }
                failures.check(level, gem.peg.motion == null) { "${gem.type} at ${gem.rc} moves" }
                failures.check(level, gem.peg.kind == ColliderKind.GEM && gem.peg.radius == board.params.gemRadius) {
                    "${gem.type} at ${gem.rc} is ${gem.peg}"
                }
                failures.check(level, gem.peg in board.world.pegs) { "${gem.type} at ${gem.rc} not in the world" }
            }
        }
        failures.assertNone()
    }

    @Test
    fun pegsKeepLatticeSpacingAtRestAndBallRoomWhileSwinging() {
        val failures = LevelFailures("§3.4 ball between pegs")
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            val d = level.layout.spacing
            val pegs = board.world.pegs.filter { it.kind == ColliderKind.PEG }
            val movingRows = pegs.filter { it.motion != null }.map { it.y }.toSet().size
            failures.check(level, movingRows == level.layout.movingRows.size) {
                "authors ${level.layout.movingRows.size} moving rows, the board has $movingRows"
            }
            val ballRoom = 2 * board.params.pegRadius + 2 * board.params.ballRadius +
                config.clearance.ballClearancePadding
            for (i in pegs.indices) {
                for (j in i + 1 until pegs.size) {
                    val a = pegs[i]
                    val b = pegs[j]
                    val rest = dist(restX(a), a.y, restX(b), b.y)
                    failures.check(level, rest >= d - 1e-3) {
                        "pegs ${describe(a)} and ${describe(b)} are ${fmt(rest)} apart at rest (< d = $d)"
                    }
                    if (a.motion == null && b.motion == null) continue
                    val closest = closestOverSwing(a, b)
                    failures.check(level, closest.first >= ballRoom - 1e-3) {
                        "pegs ${describe(a)} and ${describe(b)} come within ${fmt(closest.first)} " +
                            "(< ${fmt(ballRoom.toDouble())} = 2·pegR + 2·ballR + pad) at t=${fmt(closest.second)} s"
                    }
                }
            }
        }
        failures.assertNone()
    }

    @Test
    fun everySolidKeepsThirtySixUnitsFromBothWallsOverItsWholeSwing() {
        val failures = LevelFailures("§3.4 ball between solid and wall")
        val margin = config.clearance.minPegEdgeToWall
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            for (peg in board.world.pegs) {
                val amp = abs(peg.motion?.amplitude ?: 0f)
                val base = restX(peg)
                val worst = listOf(base, base - amp, base + amp).minOf {
                    LatticeGeometry.wallClearance(it, peg.y, config.board)
                } - peg.radius
                failures.check(level, worst >= margin - 1e-3f) {
                    "${describe(peg)} edge is ${fmt(worst.toDouble())} u from a wall (< $margin)"
                }
            }
        }
        failures.assertNone()
    }

    @Test
    fun gemsLeaveBallRoomToEveryPegAndEveryOtherGem() {
        val failures = LevelFailures("§3.4 ball between gem and solid")
        val need = 2 * config.physics.ballRadius + config.clearance.ballClearancePadding
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            val gems = board.world.pegs.filter { it.kind == ColliderKind.GEM }
            for (gem in gems) {
                for (other in board.world.pegs) {
                    if (other === gem) continue
                    if (other.kind == ColliderKind.GEM && other.id < gem.id) continue
                    val restGap = dist(restX(gem), gem.y, restX(other), other.y) - gem.radius - other.radius
                    val swingGap = if (other.motion != null || gem.motion != null) {
                        closestOverSwing(gem, other).first - gem.radius - other.radius
                    } else restGap
                    val gap = minOf(restGap, swingGap)
                    failures.check(level, gap >= need - 1e-3) {
                        "${describe(gem)} and ${describe(other)} leave a ${fmt(gap)} u surface gap (< $need)"
                    }
                }
            }
        }
        failures.assertNone()
    }

    @Test
    fun candiesSitInsideTheWallsClearOfSolidsWithEveryFixedCandyPlaced() {
        val failures = LevelFailures("§4.1 candy placement")
        val candyR = config.physics.candyRadius
        for (level in RealLevels.levels) {
            val board = RealLevels.board(level)
            val candies = board.candies
            failures.check(level, candies.isNotEmpty()) { "places no candies" }
            val expected = level.candies.fixed.size + level.candies.count
            failures.check(level, candies.size == expected) {
                "placed ${candies.size} candies, authored ${level.candies.fixed.size} fixed + " +
                    "${level.candies.count} seeded = $expected (fixed skipped or not enough free gaps)"
            }
            for (fixed in level.candies.fixed) {
                val at = RowCol(fixed.row, fixed.col)
                failures.check(level, candies.any { it.rc == at && it.color == fixed.color }) {
                    "fixed ${fixed.color} candy at gap $at was not placed"
                }
            }
            val slots = candies.map { it.rc }
            failures.check(level, slots.toSet().size == slots.size) { "two candies share a gap" }
            for (candy in candies) {
                val wall = LatticeGeometry.wallClearance(candy.x, candy.y, config.board)
                failures.check(level, wall >= candyR - 1e-3f) {
                    "${candy.color} candy at ${candy.rc} is ${fmt(wall.toDouble())} u from a wall (< $candyR)"
                }
                for (solid in board.world.pegs) {
                    val gap = dist(candy.x, candy.y, restX(solid), solid.y)
                    failures.check(level, gap >= candyR + solid.radius - 1e-3) {
                        "${candy.color} candy at ${candy.rc} overlaps ${describe(solid)} (centres ${fmt(gap)} apart)"
                    }
                }
            }
        }
        failures.assertNone()
    }

    // -- helpers -------------------------------------------------------------------------------

    private fun restX(peg: Peg): Float = peg.motion?.baseX ?: peg.x

    private fun xAt(peg: Peg, t: Double): Double {
        val m = peg.motion ?: return peg.x.toDouble()
        return m.baseX + m.amplitude * sin(2.0 * PI * t / m.periodSeconds)
    }

    /**
     * The closest centre distance of [a] and [b] over their swings, and when. Each moving peg's own
     * period is sampled at [PHASES] phases; when both move with different periods, the two sample
     * sets are unioned over a window of [WINDOW_PERIODS] of the longer period.
     */
    private fun closestOverSwing(a: Peg, b: Peg): Pair<Double, Double> {
        val periods = listOfNotNull(a.motion?.periodSeconds, b.motion?.periodSeconds).map { it.toDouble() }
        val times = ArrayList<Double>()
        val same = periods.size == 2 && periods[0] == periods[1]
        if (periods.size == 1 || same) {
            for (k in 0 until PHASES) times += periods[0] * k / PHASES
        } else {
            val window = periods.max() * WINDOW_PERIODS
            val n = PHASES * WINDOW_PERIODS * 4
            for (k in 0 until n) times += window * k / n
        }
        var best = Double.MAX_VALUE
        var at = 0.0
        for (t in times) {
            val dd = dist(xAt(a, t), a.y.toDouble(), xAt(b, t), b.y.toDouble())
            if (dd < best) {
                best = dd
                at = t
            }
        }
        return best to at
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Double =
        dist(ax.toDouble(), ay.toDouble(), bx.toDouble(), by.toDouble())

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun describe(p: Peg): String =
        "${p.kind}#${p.id}(${fmt(restX(p).toDouble())}, ${fmt(p.y.toDouble())}" +
            (p.motion?.let { ", A=${it.amplitude}, T=${it.periodSeconds}" } ?: "") + ")"

    private fun fmt(v: Double) = "%.3f".format(v)

    private companion object {
        const val PHASES = 64
        const val WINDOW_PERIODS = 8
    }
}
