package app.krafted.candytriangle.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max

/**
 * Whole drops on realistic boards: every legal aim, every fixture board, through to the exit.
 *
 * These are B1's end-to-end guarantees, the ones later phases build on: a launched ball always
 * leaves the board (§2 "drop end" — no ball is ever trapped, the §3.3 stuck rescue included), never
 * escapes through a wall, and never moves faster than the terminal-speed clamp plus what one contact
 * can add (a §3.3 moving peg hands the ball up to `(1 + transfer)` times its own speed).
 */
class EngineScenarioTest {

    // -- the aim sweep -------------------------------------------------------------------------

    @Test
    fun everyAimFromMinus70To70DegreesLeavesTheBoardWithinSixtySeconds() {
        val report = StringBuilder("EngineScenarioTest, aim sweep -70..70 deg in 1 deg steps:\n")
        for (spec in TestBoards.ALL) {
            val exitSeconds = ArrayList<Double>()
            var rescuedAims = 0
            var rescues = 0
            var fastest = 0f
            var bound = 0f
            for (degrees in -70..70) {
                val probe = Probe()
                val world = TestBoards.world(spec, listener = probe)
                bound = speedBound(world)
                val ball = world.launch(radians(degrees.toFloat()))
                while (world.hasActiveBalls && world.stepCount < STEP_CAP) {
                    world.step()
                    if (!ball.active) break
                    if (ball.y < BASE_Y && !(insideWalls(ball) > 0.0)) {
                        fail(
                            "$spec, aim $degrees deg, step ${world.stepCount}: ball centre " +
                                "(${ball.x}, ${ball.y}) is outside a wall",
                        )
                    }
                    if (ball.speed > bound) {
                        fail(
                            "$spec, aim $degrees deg, step ${world.stepCount}: speed " +
                                "${ball.speed} u/s exceeds the bound $bound u/s",
                        )
                    }
                    fastest = max(fastest, ball.speed)
                }
                assertFalse(
                    "$spec, aim $degrees deg: still on the board after 60 s, at " +
                        "(${ball.x}, ${ball.y}) moving (${ball.vx}, ${ball.vy})",
                    world.hasActiveBalls,
                )
                assertEquals("$spec, aim $degrees deg: exits", 1, probe.exits)
                exitSeconds += world.timeSeconds
                if (probe.impulses > 0) {
                    rescuedAims++
                    rescues += probe.impulses
                }
            }
            exitSeconds.sort()
            report.append(
                "  $spec: exit after min ${fmt(exitSeconds.first())} s, median " +
                    "${fmt(exitSeconds[exitSeconds.size / 2])} s, mean " +
                    "${fmt(exitSeconds.average())} s, max ${fmt(exitSeconds.last())} s; " +
                    "stuck rescue fired on $rescuedAims of ${exitSeconds.size} aims ($rescues " +
                    "impulses); top speed ${fmt(fastest.toDouble())} u/s (bound $bound)\n",
            )
        }
        println(report)
    }

    // -- the axis peg --------------------------------------------------------------------------

    /**
     * Straight down onto the d = 90 board, whose axis peg sits exactly under the spawn point.
     *
     * The launch is exactly (0, 900) and the peg exactly below, so the first bounce is exactly
     * vertical — but at ~977 u/s it rebounds ~123 u, back up through the launcher zone into the
     * apex, where the two walls converge on the ball. Resolved in their fixed order (left, then
     * right), their pushes are not mirror images, so the ball comes back down a hair off the axis,
     * and a balance on a peg's crown is unstable: it rolls off by itself. B1 guarantees only that
     * the drop ends, so that is what is asserted; the rest is recorded.
     */
    @Test
    fun aStraightDownLaunchOntoTheAxisPegLeavesTheBoard() {
        val probe = Probe()
        val world = TestBoards.world(TestBoards.D90, listener = probe)
        val axisPeg = world.pegs.first()
        assertEquals("row 0's only peg is on the axis", 500f, axisPeg.x, 0f)
        assertEquals("row 0 is the top of the lattice band", 150f, axisPeg.y, 0f)

        val ball = world.launch(0f)
        var highestRebound = Float.POSITIVE_INFINITY
        var offAxisStep = -1L
        while (world.hasActiveBalls && world.stepCount < STEP_CAP) {
            world.step()
            if (!ball.active) break
            if (probe.firstPegId >= 0) highestRebound = minOf(highestRebound, ball.y)
            if (offAxisStep < 0 && ball.x != axisPeg.x) offAxisStep = world.stepCount - 1
        }

        assertEquals("the first contact is the axis peg", axisPeg.id, probe.firstPegId)
        assertFalse("the ball must leave the board", world.hasActiveBalls)
        assertEquals(1, probe.exits)

        println(
            "EngineScenarioTest, straight-down launch on ${TestBoards.D90}: rebounds to " +
                "y = ${fmt(highestRebound.toDouble())} (${probe.wallContacts} wall contacts in " +
                "all, the first at ${seconds(world, probe.firstWallContactStep)}), first off the " +
                "axis at ${seconds(world, offAxisStep)}, stuck rescue fired ${probe.impulses} " +
                "time(s), exit at ${fmt(world.timeSeconds)} s",
        )
    }

    /**
     * The case §3.3's rescue exists for, on a full board: a ball that really does come to rest
     * balanced on the axis peg. Dropped from rest 50 u above the peg ([PhysicsWorld.spawnBall], not
     * a launch), its rebounds stay far below the apex walls, so every bounce is exactly vertical
     * and it settles into micro-bounces under 30 u/s. The rescue must fire after the 1 s dwell and
     * push toward +x (the on-axis tie-break); the ball must then roll off and find its way out.
     */
    @Test
    fun aBallAtRestOnTheAxisPegIsRescuedAndLeaves() {
        val probe = Probe()
        val world = TestBoards.world(TestBoards.D90, listener = probe)
        val axisPeg = world.pegs.first()
        val ball = world.spawnBall(axisPeg.x, axisPeg.y - 50f, 0f, 0f)

        while (world.hasActiveBalls && world.stepCount < STEP_CAP) {
            world.step()
            if (probe.impulses == 0 && ball.active) {
                assertEquals("until rescued, the ball stays exactly on the axis", 500f, ball.x, 0f)
            }
        }

        assertEquals("it settles on the axis peg", axisPeg.id, probe.firstPegId)
        assertEquals("no wall is ever involved", 0, probe.wallContacts)
        assertTrue("the §3.3 stuck rescue must fire", probe.impulses >= 1)
        assertEquals("the rescued ball was exactly on the axis", 500f, probe.firstImpulseX, 0f)
        assertTrue("on the axis the tie-break pushes toward +x", probe.firstImpulseVx > 0f)
        assertFalse("the rescued ball must leave the board", world.hasActiveBalls)
        assertEquals(1, probe.exits)

        println(
            "EngineScenarioTest, ball at rest on the axis peg of ${TestBoards.D90}: " +
                "${probe.hardBouncesBeforeRescue} bounces over 30 u/s, stuck rescue at " +
                "${seconds(world, probe.firstImpulseStep)} (${probe.impulses} impulse(s) in all), " +
                "exit at ${fmt(world.timeSeconds)} s",
        )
    }

    // -- helpers -------------------------------------------------------------------------------

    /** Counts and first occurrences; every step number is a 0-based step index. */
    private class Probe : PhysicsListener {
        var exits = 0
        var impulses = 0
        var wallContacts = 0
        var firstPegId = -1
        var firstWallContactStep = -1L
        var firstImpulseStep = -1L
        var firstImpulseX = Float.NaN
        var firstImpulseVx = Float.NaN
        var hardBouncesBeforeRescue = 0

        /** Steps completed so far — the index of the step now running. */
        private var steps = 0L

        override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
            if (firstPegId < 0) firstPegId = peg.id
            if (impulses == 0 && impactSpeed >= 30f) hardBouncesBeforeRescue++
        }

        override fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {
            if (wallContacts++ == 0) firstWallContactStep = steps
        }

        override fun onStuckImpulse(ball: Ball) {
            impulses++
            if (impulses == 1) {
                firstImpulseStep = steps
                firstImpulseX = ball.x
                firstImpulseVx = ball.vx
            }
        }

        override fun onBallExited(ball: Ball) {
            exits++
        }

        override fun afterStep(world: PhysicsWorld) {
            steps++
        }
    }

    private companion object {

        /** Sixty simulated seconds at 240 Hz. */
        const val STEP_CAP = 60L * 240

        val BASE_Y: Float = TestBoards.CONFIG.board.baseY

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        /** The ball centre's distance inside the nearer wall, u; positive means inside both. */
        fun insideWalls(ball: Ball): Double = TestBoards.WALL_LINES.minOf { line ->
            TestBoards.interiorDistance(line, ball.x.toDouble(), ball.y.toDouble())
        }

        /**
         * The fastest a ball may be after a step: the terminal clamp, plus what one contact with
         * the fastest peg can add — `|v'| <= |v - u| + transfer |u| <= |v| + (1 + transfer) |u|`,
         * zero on a static board — plus float rounding.
         */
        fun speedBound(world: PhysicsWorld): Float =
            world.params.maxBallSpeed +
                (1f + world.params.movingPegTransfer) * TestBoards.maxPegSpeed(world) +
                0.01f

        fun fmt(value: Double): String = "%.3f".format(value)

        /** The end of 0-based step [stepIndex], in simulated seconds; "never" for -1. */
        fun seconds(world: PhysicsWorld, stepIndex: Long): String =
            if (stepIndex < 0) {
                "never"
            } else {
                "${fmt((stepIndex + 1).toDouble() / world.params.stepsPerSecond)} s"
            }
    }
}
