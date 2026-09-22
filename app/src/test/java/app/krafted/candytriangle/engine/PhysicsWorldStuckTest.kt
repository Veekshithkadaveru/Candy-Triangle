package app.krafted.candytriangle.engine

import app.krafted.candytriangle.engine.PhysicsWorldTest.Companion.emptyWorld
import app.krafted.candytriangle.engine.PhysicsWorldTest.Companion.runUntilNoActiveBalls
import app.krafted.candytriangle.engine.PhysicsWorldTest.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The §3.3 stuck-ball rescue (pipeline step 2.6): a ball slower than `stuckSpeedThreshold` for more
 * than `stuckDwellSteps` consecutive steps gets `stuckImpulseSpeed` toward the axis, and down.
 *
 * The rule is tested in isolation first — zero gravity, no colliders, so the ball's speed is exactly
 * what the test sets — then in context, on the board shape that motivates the on-axis tie-break.
 */
class PhysicsWorldStuckTest {

    // -- the rule in isolation ---------------------------------------------------------------------

    @Test
    fun nothingHappensDuringTheDwellThenExactlyOneImpulseOnTheNextStep() {
        val params = PhysicsParams(gravity = 0f)
        val log = EventLog()
        val world = emptyWorld(params, log)
        val ball = world.spawnBall(x = 300f, y = 600f, vx = 0f, vy = 0f)

        for (n in 1..params.stuckDwellSteps) {
            world.step()
            assertEquals("stuckSteps after $n slow steps", n, ball.stuckSteps)
        }
        assertTrue("no impulse during the dwell", log.kicks.isEmpty())
        assertEquals("still at rest: vx", 0f, ball.vx, 0f)
        assertEquals("still at rest: vy", 0f, ball.vy, 0f)

        world.step()

        assertEquals("exactly one impulse", 1, log.kicks.size)
        assertEquals("on step dwell + 1", params.stuckDwellSteps, log.kicks.first().step)
        assertEquals("of magnitude stuckImpulseSpeed", params.stuckImpulseSpeed, ball.speed, 1e-3f)
        assertEquals("at 45 degrees: equal parts across and down", ball.vx, ball.vy, 0f)
        assertEquals("each part is the impulse over sqrt(2)", 120f / SQRT_2, ball.vx, 1e-4f)
        assertEquals("the counter restarts", 0, ball.stuckSteps)

        repeat(2 * params.stuckDwellSteps) { world.step() }
        assertEquals("a ball that is moving again is left alone", 1, log.kicks.size)
    }

    @Test
    fun theImpulsePointsTowardTheAxisAndExactlyOnItPicksPlusX() {
        val params = PhysicsParams(gravity = 0f)
        for ((x, sign) in listOf(300f to 1f, 700f to -1f, params.stuckAxisX to 1f)) {
            val ball = rescuedBallAt(x, params)
            assertEquals("x = $x: horizontal part", sign * 120f / SQRT_2, ball.vx, 1e-4f)
            assertEquals("x = $x: downward part", 120f / SQRT_2, ball.vy, 1e-4f)
        }
    }

    @Test
    fun withStuckDownwardOffTheImpulseIsPurelyHorizontal() {
        val params = PhysicsParams(gravity = 0f, stuckDownward = false)
        for ((x, sign) in listOf(300f to 1f, 700f to -1f, params.stuckAxisX to 1f)) {
            val ball = rescuedBallAt(x, params)
            assertEquals("x = $x: the whole impulse is horizontal", sign * params.stuckImpulseSpeed, ball.vx, 0f)
            assertEquals("x = $x: none of it is vertical", 0f, ball.vy, 0f)
        }
    }

    @Test
    fun theImpulseAddsToTheVelocityTheBallAlreadyHas() {
        val params = PhysicsParams(gravity = 0f)
        val world = emptyWorld(params)
        // Slow, and drifting away from the axis: the rescue must add to that, not replace it.
        val ball = world.spawnBall(x = 300f, y = 600f, vx = -10f, vy = 0f)

        repeat(params.stuckDwellSteps + 1) { world.step() }

        assertEquals("vx = -10 + 120 / sqrt(2)", -10f + 120f / SQRT_2, ball.vx, 1e-3f)
        assertEquals("vy = 0 + 120 / sqrt(2)", 120f / SQRT_2, ball.vy, 1e-3f)
    }

    @Test
    fun theDwellCounterResetsWhenTheBallSpeedsUp() {
        val params = PhysicsParams(gravity = 0f)
        val log = EventLog()
        val world = emptyWorld(params, log)
        val ball = world.spawnBall(x = 300f, y = 600f, vx = 0f, vy = 0f)

        repeat(200) { world.step() }
        assertEquals(200, ball.stuckSteps)

        // One step at exactly the threshold counts as moving: the rule is strictly "slower than".
        ball.vx = params.stuckSpeedThreshold
        world.step()
        assertEquals("reset by a step at the threshold", 0, ball.stuckSteps)

        // At rest again, the full dwell has to elapse from scratch.
        ball.vx = 0f
        repeat(params.stuckDwellSteps) { world.step() }
        assertTrue("no impulse before a fresh full dwell", log.kicks.isEmpty())
        world.step()
        assertEquals("then exactly one", 1, log.kicks.size)
        assertEquals("on step 200 + 1 + dwell + 1", 200 + 1 + params.stuckDwellSteps, log.kicks.first().step)
    }

    // -- in context ----------------------------------------------------------------------------------

    /**
     * A ball dropped dead-centre onto a peg on the axis only ever bounces straight up and down on
     * it, so it can never roll off on its own: it settles into micro-bounces far under 30 u/s. The
     * rescue must fire, the +x tie-break must push it off the crown of the peg, and it must fall out
     * of the board. Without the tie-break the impulse would be straight down, into the peg, forever.
     */
    @Test
    fun aBallBalancedOnAnAxisPegIsRescuedRollsOffAndExits() {
        val params = PhysicsParams()
        val peg = Peg(
            id = 1,
            x = params.stuckAxisX,
            y = 600f,
            radius = params.pegRadius,
            restitution = params.restitutionPeg,
        )
        val log = EventLog()
        val world = PhysicsWorld(params, walls = emptyList(), pegs = listOf(peg), listener = log)
        val ball = world.spawnBall(x = params.stuckAxisX, y = 450f, vx = 0f, vy = 0f)

        runUntilNoActiveBalls(world, maxSteps = 10_000)

        assertTrue("it bounced on the peg", log.pegHits.isNotEmpty())
        assertEquals("one rescue was enough", 1, log.kicks.size)
        val kick = log.kicks.first()
        assertEquals("it was balanced exactly on the axis", params.stuckAxisX, kick.x, 0f)
        assertTrue("so the tie-break pushed it toward +x (vx ${kick.vx})", kick.vx > 0f)
        assertTrue(
            "until then it only ever bounced straight up and down",
            log.pegHits.filter { it.step < kick.step }.all { it.vxAfter == 0f },
        )
        assertTrue(
            "and it had settled: every bounce in the dwell left it slower than the threshold",
            log.pegHits.filter { it.step in (kick.step - params.stuckDwellSteps) until kick.step }
                .all { abs(it.vyAfter) < params.stuckSpeedThreshold },
        )
        assertEquals("it left the board exactly once", listOf(ball.id), log.exits.map { it.ballId })
        assertTrue("off the +x side of the peg", ball.x > params.stuckAxisX)
        assertFalse(world.hasActiveBalls)
    }

    // -- helpers -------------------------------------------------------------------------------------

    private companion object {

        const val SQRT_2 = 1.4142135f

        /** A ball left at rest at [x] in empty zero-gravity space, stepped through its first rescue. */
        fun rescuedBallAt(x: Float, params: PhysicsParams): Ball {
            val log = EventLog()
            val world = emptyWorld(params, log)
            val ball = world.spawnBall(x = x, y = 600f, vx = 0f, vy = 0f)
            repeat(params.stuckDwellSteps + 1) { world.step() }
            check(log.kicks.size == 1) { "expected exactly one rescue at x = $x, got ${log.kicks.size}" }
            return ball
        }
    }
}
