package app.krafted.candytriangle.engine

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §11 #4, tunnelling prevention: at the 1,600 u/s terminal speed, against pegs, gems and both
 * slanted walls, no ball centre ever ends a step inside a solid collider.
 *
 * §3.3 does without swept collision because one step moves a ball at most
 * `maxStepDisplacement` = 1600 / 240 = 6.67 u, less than the smallest solid radius (the 7 u peg).
 * So after **every** step, for every ball, a [Watch] asserts:
 *
 * 1. no ball centre inside any active peg or gem: distance >= the collider's radius;
 * 2. no overlap deeper than one step of travel: distance >= ballRadius + radius - maxStep - eps;
 * 3. while the ball is above the open base, its centre is strictly on the interior side of both
 *    walls, and it overlaps neither by more than one step.
 *
 * The shots are built so the step on which a ball first overlaps its target lands at every depth
 * from 0 to a full step ([PHASES] evenly spaced phases), from every direction, at terminal speed.
 */
class TunnellingTest {

    // -- pegs and gems -------------------------------------------------------------------------

    /**
     * A ball fired at terminal speed straight at every peg and gem of every board, from
     * [DIRECTIONS] directions and [PHASES] phases, starting [LEAD_STEPS] whole steps out.
     */
    @Test
    fun terminalSpeedShotsAtEveryPegAndGemNeverTunnel() {
        var steps = 0L
        val summary = StringBuilder()
        for (spec in TestBoards.ALL) {
            val first = FirstContact()
            val world = TestBoards.world(spec, listener = first)
            val watch = Watch(world)
            val p = world.params
            var shots = 0
            var struck = 0
            for (target in world.pegs) {
                for (direction in 0 until DIRECTIONS) {
                    val theta = 2.0 * PI * (direction + 0.25) / DIRECTIONS
                    val ux = cos(theta)
                    val uy = sin(theta)
                    for (phase in 0 until PHASES) {
                        val lead = p.ballRadius + target.radius +
                            ((phase + 0.5) / PHASES + LEAD_STEPS) * p.maxStepDisplacement
                        val x = target.x + ux * lead
                        val y = target.y + uy * lead
                        if (!watch.isClearStart(x, y)) continue
                        shots++
                        first.reset()
                        val ball = world.spawnBall(
                            x.toFloat(),
                            y.toFloat(),
                            (-ux * p.maxBallSpeed).toFloat(),
                            (-uy * p.maxBallSpeed).toFloat(),
                        )
                        repeat(SHOT_STEPS) {
                            world.step()
                            steps++
                            watch.check {
                                "$spec: shot at ${target.kind} ${target.id}, direction " +
                                    "$direction, phase $phase"
                            }
                        }
                        if (first.pegId == target.id && first.impactSpeed > STRIKE_SPEED) struck++
                        world.removeBall(ball)
                    }
                }
            }
            assertTrue(
                "$spec: only $struck of $shots shots struck their target at over $STRIKE_SPEED u/s " +
                    "— the test would be vacuous",
                struck >= 0.9 * shots,
            )
            summary.append(
                "  $spec: $shots shots, $struck direct strikes, deepest overlap after a step " +
                    "${fmt(watch.deepestPegOverlap)} u (peg) / " +
                    "${fmt(watch.deepestWallOverlap)} u (wall)\n",
            )
        }
        assertTrue("§11 asks for at least 10,000 steps; ran $steps", steps >= 10_000)
        println("TunnellingTest, peg and gem shots ($steps steps):\n$summary")
    }

    // -- walls ---------------------------------------------------------------------------------

    /**
     * A ball fired at terminal speed into each slanted wall, all the way down its length, at
     * incidences from head-on to grazing, with no pegs in the way. It must bounce, never cross.
     */
    @Test
    fun terminalSpeedShotsAtBothWallsNeverCrossThem() {
        val first = FirstContact()
        val world = TestBoards.emptyWorld(listener = first)
        val watch = Watch(world)
        val p = world.params
        var steps = 0L
        var shots = 0
        var struck = 0
        for ((wallIndex, line) in TestBoards.WALL_LINES.withIndex()) {
            val (nx, ny) = inwardNormal(line)
            for (height in 0..22) {
                val footY = 100.0 + 50.0 * height
                val along = (footY - line.y1) / (line.y2.toDouble() - line.y1)
                val footX = line.x1 + along * (line.x2.toDouble() - line.x1)
                for (incidence in INCIDENCES_DEGREES) {
                    val phi = Math.toRadians(incidence)
                    // The outward normal, rotated by the incidence angle.
                    val dx = -nx * cos(phi) + ny * sin(phi)
                    val dy = -nx * sin(phi) - ny * cos(phi)
                    val normalStep = p.maxStepDisplacement * cos(phi)
                    for (phase in 0 until PHASES) {
                        val lead = p.ballRadius + ((phase + 0.5) / PHASES + LEAD_STEPS) * normalStep
                        val x = footX + nx * lead
                        val y = footY + ny * lead
                        if (!watch.isClearStart(x, y)) continue
                        shots++
                        first.reset()
                        val ball = world.spawnBall(
                            x.toFloat(),
                            y.toFloat(),
                            (dx * p.maxBallSpeed).toFloat(),
                            (dy * p.maxBallSpeed).toFloat(),
                        )
                        repeat(SHOT_STEPS) {
                            world.step()
                            steps++
                            watch.check {
                                "wall $wallIndex at y=$footY, incidence $incidence, phase $phase"
                            }
                        }
                        val expectedImpact = 0.9 * p.maxBallSpeed * cos(phi)
                        val onTarget = first.wallIndex == wallIndex
                        if (onTarget && first.impactSpeed > expectedImpact) struck++
                        world.removeBall(ball)
                    }
                }
            }
        }
        assertTrue("only $struck of $shots wall shots struck their wall", struck >= 0.9 * shots)
        println(
            "TunnellingTest, wall shots: $shots shots, $struck direct strikes, $steps steps, " +
                "deepest wall overlap after a step ${fmt(watch.deepestWallOverlap)} u",
        )
    }

    /**
     * Where a wall ends — the open base's two corners, which the walls' endpoints make solid — a
     * ball fired at the very corner point from anywhere nearby inside must still never cross.
     */
    @Test
    fun terminalSpeedShotsIntoTheBaseCornersNeverCrossAWall() {
        val world = TestBoards.emptyWorld()
        val watch = Watch(world)
        val p = world.params
        var shots = 0
        for (line in TestBoards.WALL_LINES) {
            val cornerX = line.x2.toDouble()
            val cornerY = line.y2.toDouble()
            val inward = if (cornerX < TestBoards.CONFIG.board.centerAxisX) 1.0 else -1.0
            for (ox in 4..120 step 4) {
                for (oy in 4..120 step 4) {
                    val x = cornerX + inward * ox
                    val y = cornerY - oy
                    if (!watch.isClearStart(x, y)) continue
                    shots++
                    val length = sqrt((cornerX - x) * (cornerX - x) + (cornerY - y) * (cornerY - y))
                    val ball = world.spawnBall(
                        x.toFloat(),
                        y.toFloat(),
                        ((cornerX - x) / length * p.maxBallSpeed).toFloat(),
                        ((cornerY - y) / length * p.maxBallSpeed).toFloat(),
                    )
                    repeat(CORNER_STEPS) {
                        world.step()
                        watch.check { "corner shot from ($x, $y)" }
                    }
                    world.removeBall(ball)
                }
            }
        }
        assertTrue("the corner sweep found no valid starts", shots > 500)
        println(
            "TunnellingTest, base corner shots: $shots shots, deepest wall overlap after a step " +
                "${fmt(watch.deepestWallOverlap)} u",
        )
    }

    // -- a whole board at terminal speed -------------------------------------------------------

    /**
     * Gravity turned up to [HIGH_GRAVITY] (1,000 u/s gained per step) holds every ball at the
     * terminal-speed clamp on nearly every step — [BALLS_IN_FLIGHT] balls at a time, relaunched as
     * they exit, through the densest (d = 66, gems) board and the others. Every one of those steps
     * moves a ball the full 6.67 u the anti-tunnelling budget allows.
     */
    @Test
    fun ballsHeldAtTerminalSpeedByHighGravityNeverTunnel() {
        val params = TestBoards.PARAMS.copy(gravity = HIGH_GRAVITY)
        val summary = StringBuilder()
        for ((spec, stepCount) in HIGH_GRAVITY_RUNS) {
            val exits = ExitCounter()
            val world = TestBoards.world(spec, params, exits)
            val watch = Watch(world)
            var launched = 0
            var retired = 0
            var ballSteps = 0L
            var terminalSteps = 0L
            val launchedAt = LongArray(BALLS_IN_FLIGHT * 1000)
            repeat(stepCount) {
                // Relaunch between steps; retire (rather than wait on) anything unusually slow.
                for (ball in world.balls.toList()) {
                    if (world.stepCount - launchedAt[ball.id] > RETIRE_AFTER_STEPS) {
                        world.removeBall(ball)
                        retired++
                    }
                }
                while (world.balls.size < BALLS_IN_FLIGHT) {
                    val aim = HIGH_GRAVITY_AIMS[launched % HIGH_GRAVITY_AIMS.size]
                    val ball = world.launch(radians(aim))
                    launchedAt[ball.id] = world.stepCount
                    launched++
                }
                world.step()
                watch.check { "$spec under $HIGH_GRAVITY u/s^2 gravity" }
                for (ball in world.balls) {
                    ballSteps++
                    val moved = hypot(ball.x - ball.prevX, ball.y - ball.prevY)
                    if (moved >= 0.99 * params.maxStepDisplacement) terminalSteps++
                }
            }
            assertTrue("$spec: only ${exits.count} balls cycled through", exits.count >= 20)
            assertTrue(
                "$spec: only $terminalSteps of $ballSteps ball-steps moved at terminal speed",
                terminalSteps >= 0.5 * ballSteps,
            )
            summary.append(
                "  $spec: $stepCount steps, $launched balls launched, ${exits.count} exited, " +
                    "$retired retired, ${pct(terminalSteps, ballSteps)} of ball-steps at the full " +
                    "${fmt(params.maxStepDisplacement.toDouble())} u, deepest overlap after a step " +
                    "${fmt(watch.deepestPegOverlap)} u (peg) / " +
                    "${fmt(watch.deepestWallOverlap)} u (wall)\n",
            )
        }
        println("TunnellingTest, high gravity:\n$summary")
    }

    // -- the invariants ------------------------------------------------------------------------

    /** Checks the class-doc invariants for every active ball of [world]; see [check]. */
    private class Watch(private val world: PhysicsWorld) {

        private val maxStep = world.params.maxStepDisplacement.toDouble()
        private val radius = world.params.ballRadius.toDouble()
        private val baseY = TestBoards.CONFIG.board.baseY.toDouble()

        /** The deepest ball-vs-collider overlap seen after any step, u (<= 0: none ever). */
        var deepestPegOverlap = Double.NEGATIVE_INFINITY
            private set

        /** The same against the walls, for balls above the base. */
        var deepestWallOverlap = Double.NEGATIVE_INFINITY
            private set

        inline fun check(context: () -> String) {
            for (ball in world.balls) {
                if (!ball.active) continue
                val violation = violation(ball) ?: continue
                fail(
                    "${context()}: after step ${world.stepCount}, ball ${ball.id} at " +
                        "(${ball.x}, ${ball.y}) $violation",
                )
            }
        }

        /** A description of the first invariant [ball] breaks, or null. */
        fun violation(ball: Ball): String? {
            val bx = ball.x.toDouble()
            val by = ball.y.toDouble()
            for (peg in world.pegs) {
                if (!peg.active) continue
                val reach = radius + peg.radius
                val dx = bx - peg.x
                val dy = by - peg.y
                if (abs(dx) >= reach || abs(dy) >= reach) continue
                val distance = sqrt(dx * dx + dy * dy)
                val overlap = reach - distance
                deepestPegOverlap = max(deepestPegOverlap, overlap)
                if (distance < peg.radius) {
                    return "has its centre inside $peg (distance $distance)"
                }
                if (overlap > maxStep + EPSILON) {
                    return "overlaps $peg by $overlap u, deeper than one step ($maxStep u)"
                }
            }
            if (by < baseY) {
                for (line in TestBoards.WALL_LINES) {
                    val inside = TestBoards.interiorDistance(line, bx, by)
                    val overlap = radius - inside
                    deepestWallOverlap = max(deepestWallOverlap, overlap)
                    if (!(inside > 0.0)) return "has its centre across the wall $line ($inside u)"
                    if (overlap > maxStep + EPSILON) {
                        return "overlaps the wall $line by $overlap u, deeper than one step"
                    }
                }
            }
            return null
        }

        /**
         * Whether a ball can start at ([x], [y]): wholly above the base, at least 1 u clear of both
         * walls and of every collider.
         */
        fun isClearStart(x: Double, y: Double): Boolean {
            if (y + radius >= baseY - 1.0) return false
            for (line in TestBoards.WALL_LINES) {
                if (TestBoards.interiorDistance(line, x, y) < radius + 1.0) return false
            }
            for (peg in world.pegs) {
                if (!peg.active) continue
                val dx = x - peg.x
                val dy = y - peg.y
                if (sqrt(dx * dx + dy * dy) < radius + peg.radius + 1.0) return false
            }
            return true
        }
    }

    /** The first contact of the current shot, for the did-it-actually-hit check. */
    private class FirstContact : PhysicsListener {
        var pegId = -1
        var wallIndex = -1
        var impactSpeed = 0f
        private var seen = false

        fun reset() {
            pegId = -1
            wallIndex = -1
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
            // The left wall runs down-left from the apex, the right one down-right.
            wallIndex = if (wall.x2 < wall.x1) 0 else 1
            this.impactSpeed = impactSpeed
        }
    }

    private class ExitCounter : PhysicsListener {
        var count = 0

        override fun onBallExited(ball: Ball) {
            count++
        }
    }

    private companion object {

        /** Float rounding allowance on the one-step bound, u. */
        const val EPSILON = 1e-3

        const val DIRECTIONS = 12
        const val PHASES = 5

        /** Whole steps between the start and the first overlapping step. */
        const val LEAD_STEPS = 2

        const val SHOT_STEPS = 8
        const val CORNER_STEPS = 24

        /** A shot counts as a direct strike above this normal approach speed, u/s. */
        const val STRIKE_SPEED = 1500f

        /** 1,000 u/s gained per 240 Hz step: back at the terminal clamp within two steps. */
        const val HIGH_GRAVITY = 240_000f
        const val BALLS_IN_FLIGHT = 3

        /** Ten seconds at 240 Hz — far longer than any ball needs to fall at 1,600 u/s. */
        const val RETIRE_AFTER_STEPS = 2_400L

        val INCIDENCES_DEGREES = doubleArrayOf(0.0, 30.0, -30.0, 60.0, -60.0, 80.0, -80.0)

        val HIGH_GRAVITY_RUNS = listOf(
            TestBoards.D66_GEMS to 12_000,
            TestBoards.D90 to 6_000,
            TestBoards.D78_MOVING to 6_000,
        )

        /** Never exactly 0: dropped dead-centre on the axis peg, a ball would bounce there forever. */
        val HIGH_GRAVITY_AIMS = floatArrayOf(
            -67f, -53.5f, -41f, -29.5f, -17f, -6.5f, 4.5f, 16f, 28.5f, 40f, 52.5f, 65f,
        )

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        fun hypot(x: Float, y: Float): Double = sqrt(x.toDouble() * x + y.toDouble() * y)

        /** [TestBoards.interiorDistance]'s inward unit normal for [line]. */
        fun inwardNormal(line: app.krafted.candytriangle.level.WallLine): Pair<Double, Double> {
            val wx = line.x2.toDouble() - line.x1
            val wy = line.y2.toDouble() - line.y1
            val length = sqrt(wx * wx + wy * wy)
            // Perpendicular (-wy, wx), flipped to face the interior.
            val nx = -wy / length
            val ny = wx / length
            val probe = TestBoards.interiorDistance(line, line.x1 + nx, line.y1 + ny)
            return if (probe > 0.0) nx to ny else -nx to -ny
        }

        fun fmt(value: Double): String = "%.4f".format(value)

        fun pct(part: Long, whole: Long): String = "%.1f%%".format(100.0 * part / whole)
    }
}
