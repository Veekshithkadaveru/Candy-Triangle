package app.krafted.candytriangle.verification

import app.krafted.candytriangle.board.LatticeGeometry
import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsListener
import app.krafted.candytriangle.engine.WallSegment
import app.krafted.candytriangle.level.LevelDef
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §11 #4, tunnelling on the real levels: balls at the 1,600 u/s terminal speed fired at every gem,
 * a sample of pegs and both slanted walls of each level's actual layout (moving rows included),
 * for [STEPS_PER_LEVEL] steps per level.
 *
 * The level's own `PhysicsWorld` is stepped directly with the game listener swapped for a
 * contact recorder, so smashed gems stay solid and no game rule interferes. After **every** step,
 * every active ball must have its centre outside every active solid (distance ≥ its radius),
 * overlap none by more than one step of travel, and — above the open base — be inside both walls.
 */
class LevelTunnellingTest {

    @Test
    fun terminalSpeedShotsAtGemsPegsAndWallsNeverTunnelOnAnyLevel() {
        val failures = LevelFailures("§11.4 tunnelling at 1,600 u/s")
        val summary = StringBuilder()
        var totalSteps = 0L
        for (level in RealLevels.levels) {
            val r = runLevel(level)
            totalSteps += r.steps
            r.violation?.let { failures.add(level, it) }
            failures.check(level, r.steps >= STEPS_PER_LEVEL) { "only ${r.steps} steps ran" }
            failures.check(level, r.gemsShotAt == r.gems) {
                "only ${r.gemsShotAt} of ${r.gems} gems were shot at (no clear start found)"
            }
            failures.check(level, r.struck >= MIN_STRIKE_RATIO * r.shots) {
                "only ${r.struck} of ${r.shots} shots struck their target at near terminal speed — vacuous"
            }
            summary.append(
                String.format(
                    "  %-10s %5d steps, %3d shots (%3d gem, %3d peg, %3d wall), %3d struck, " +
                        "deepest overlap %.3f u solid / %.3f u wall%n",
                    RealLevels.label(level), r.steps, r.shots, r.gemShots, r.pegShots, r.wallShots,
                    r.struck, r.deepestSolid, r.deepestWall,
                ),
            )
        }
        println("LevelTunnellingTest ($totalSteps steps):\n$summary")
        failures.assertNone()
    }

    private class Result(
        val steps: Long,
        val shots: Int,
        val gemShots: Int,
        val pegShots: Int,
        val wallShots: Int,
        val gems: Int,
        val gemsShotAt: Int,
        val struck: Int,
        val deepestSolid: Double,
        val deepestWall: Double,
        val violation: String?,
    )

    /** A shot: a start position and a unit direction, plus what it is aimed at. */
    private class Shot(
        val x: Double,
        val y: Double,
        val ux: Double,
        val uy: Double,
        val pegId: Int,
        val wall: Int,
        /** Normal approach speed that counts as a direct strike on the target, u/s. */
        val strikeSpeed: Float,
    )

    private fun runLevel(level: LevelDef): Result {
        val board = RealLevels.board(level)
        val world = board.world
        val recorder = FirstContact()
        world.listener = recorder
        val p = world.params
        val maxStep = p.maxStepDisplacement.toDouble()
        val ballR = p.ballRadius.toDouble()
        val baseY = board.config.board.baseY.toDouble()

        var deepestSolid = Double.NEGATIVE_INFINITY
        var deepestWall = Double.NEGATIVE_INFINITY

        fun violation(ball: Ball): String? {
            val bx = ball.x.toDouble()
            val by = ball.y.toDouble()
            for (peg in world.pegs) {
                if (!peg.active) continue
                val dx = bx - peg.x
                val dy = by - peg.y
                val d = sqrt(dx * dx + dy * dy)
                val overlap = ballR + peg.radius - d
                deepestSolid = max(deepestSolid, overlap)
                if (d < peg.radius) return "centre inside $peg (distance $d)"
                if (overlap > maxStep + EPS) return "overlaps $peg by $overlap u (> one step $maxStep)"
            }
            if (by < baseY) {
                val inside = LatticeGeometry.wallClearance(ball.x, ball.y, board.config.board).toDouble()
                deepestWall = max(deepestWall, ballR - inside)
                if (inside < 0.0) return "centre $inside u outside a wall"
                if (ballR - inside > maxStep + EPS) return "overlaps a wall by ${ballR - inside} u (> one step)"
            }
            return null
        }

        fun clearStart(x: Double, y: Double): Boolean {
            if (y + ballR >= baseY - 1.0 || y - ballR < 0.0) return false
            if (LatticeGeometry.wallClearance(x.toFloat(), y.toFloat(), board.config.board) < ballR + 1.0) return false
            for (peg in world.pegs) {
                if (!peg.active) continue
                val dx = x - peg.x
                val dy = y - peg.y
                if (sqrt(dx * dx + dy * dy) < ballR + peg.radius + 1.0) return false
            }
            return true
        }

        /** A clear start [LEAD_STEPS] steps out from [peg], trying [DIRECTIONS] headings in turn. */
        fun shotAt(peg: Peg, rotation: Int): Shot? {
            val lead = ballR + peg.radius + LEAD_STEPS * maxStep
            for (k in 0 until DIRECTIONS) {
                // Headings every 15°: every lattice gap direction (30° + 60°k) is among them.
                val theta = 2.0 * PI * ((k + 7 * rotation) % DIRECTIONS) / DIRECTIONS
                val ox = cos(theta)
                val oy = sin(theta)
                val x = peg.x + ox * lead
                val y = peg.y + oy * lead
                if (clearStart(x, y)) return Shot(x, y, -ox, -oy, peg.id, -1, STRIKE_SPEED)
            }
            return null
        }

        val walls = world.walls
        fun shotsAtWall(index: Int, wall: WallSegment): List<Shot> {
            val out = ArrayList<Shot>()
            val nx = wall.nx.toDouble()
            val ny = wall.ny.toDouble()
            for (h in 0 until WALL_HEIGHTS) {
                val footY = 120.0 + (baseY - 200.0) * h / (WALL_HEIGHTS - 1)
                val along = (footY - wall.y1) / (wall.y2.toDouble() - wall.y1)
                val footX = wall.x1 + along * (wall.x2.toDouble() - wall.x1)
                for (incidence in WALL_INCIDENCES) {
                    val phi = Math.toRadians(incidence)
                    val dx = -nx * cos(phi) + ny * sin(phi)
                    val dy = -nx * sin(phi) - ny * cos(phi)
                    val lead = ballR + LEAD_STEPS * maxStep * cos(phi)
                    val x = footX + nx * lead
                    val y = footY + ny * lead
                    if (clearStart(x, y)) out += Shot(x, y, dx, dy, -1, index, (STRIKE_FRACTION * p.maxBallSpeed * cos(phi)).toFloat())
                }
            }
            return out
        }

        val gems = world.pegs.filter { it.kind == ColliderKind.GEM }
        val pegs = world.pegs.filter { it.kind == ColliderKind.PEG }
        val pegSample = if (pegs.size <= PEG_SAMPLE) pegs else {
            (0 until PEG_SAMPLE).map { pegs[it * pegs.size / PEG_SAMPLE] }
        }
        val wallShots = walls.withIndex().flatMap { (i, w) -> shotsAtWall(i, w) }

        var steps = 0L
        var shots = 0
        var gemShots = 0
        var pegShots = 0
        var wallShotCount = 0
        var struck = 0
        var round = 0
        var violationText: String? = null
        val gemsShotAt = HashSet<Int>()

        fun fire(shot: Shot, what: String): Boolean {
            // Moving pegs have moved since the shot was planned; re-check the start.
            if (!clearStart(shot.x, shot.y)) return true
            recorder.reset()
            val ball = world.spawnBall(
                shot.x.toFloat(), shot.y.toFloat(),
                (shot.ux * p.maxBallSpeed).toFloat(), (shot.uy * p.maxBallSpeed).toFloat(),
            )
            shots++
            for (s in 0 until SHOT_STEPS) {
                world.step()
                steps++
                for (b in world.balls) {
                    if (!b.active) continue
                    val v = violation(b)
                    if (v != null) {
                        violationText = "$what (round $round): after step ${world.stepCount}, ball at " +
                            "(${b.x}, ${b.y}) v=(${b.vx}, ${b.vy}): $v"
                        return false
                    }
                }
                if (!ball.active) break
            }
            val hitTarget = if (shot.pegId >= 0) recorder.pegId == shot.pegId else recorder.wall == shot.wall
            if (hitTarget && recorder.impactSpeed > shot.strikeSpeed) struck++
            world.removeBall(ball)
            return true
        }

        outer@ while (steps < STEPS_PER_LEVEL) {
            for (gem in gems) {
                val shot = shotAt(gem, round) ?: continue
                gemShots++
                gemsShotAt += gem.id
                if (!fire(shot, "shot at gem ${gem.id} (${gem.x}, ${gem.y})")) break@outer
            }
            for (peg in pegSample) {
                val shot = shotAt(peg, round) ?: continue
                pegShots++
                if (!fire(shot, "shot at peg ${peg.id} (${peg.x}, ${peg.y})")) break@outer
            }
            for (shot in wallShots) {
                wallShotCount++
                if (!fire(shot, "shot at wall ${shot.wall} from (${shot.x}, ${shot.y})")) break@outer
            }
            round++
            if (round > MAX_ROUNDS) break
        }
        return Result(
            steps, shots, gemShots, pegShots, wallShotCount, gems.size, gemsShotAt.size, struck,
            deepestSolid, deepestWall, violationText,
        )
    }

    /** The first contact of the current shot, to prove the shots really hit what they aim at. */
    private class FirstContact : PhysicsListener {
        var pegId = -1
        var wall = -1
        var impactSpeed = 0f
        private var seen = false

        fun reset() {
            pegId = -1
            wall = -1
            impactSpeed = 0f
            seen = false
        }

        override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
            if (seen) return
            seen = true
            pegId = peg.id
            this.impactSpeed = impactSpeed
        }

        override fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {
            if (seen) return
            seen = true
            this.wall = wallIndex(wall)
            this.impactSpeed = impactSpeed
        }

        private fun wallIndex(wall: WallSegment): Int = if (wall.x2 < wall.x1) 0 else 1
    }

    private companion object {
        const val STEPS_PER_LEVEL = 10_000L
        const val SHOT_STEPS = 24
        const val LEAD_STEPS = 2.5
        const val DIRECTIONS = 24
        const val PEG_SAMPLE = 24
        const val WALL_HEIGHTS = 12
        val WALL_INCIDENCES = listOf(0.0, 35.0, 60.0)
        const val MAX_ROUNDS = 200
        const val EPS = 1e-3
        const val STRIKE_SPEED = 1000f
        const val STRIKE_FRACTION = 0.6
        const val MIN_STRIKE_RATIO = 0.8
    }
}
